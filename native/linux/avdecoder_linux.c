// H.264, HEVC and VP9 decoding for MediaCodec on Linux (dev.apksim.video.VideoToolbox's natives, the Mac's being
// native/videotoolbox.m), on libavcodec with only the decoders built in (docker/Dockerfile's VIDEO_DECODERS), linked statically (docker/Dockerfile builds
// FFmpeg and this file into libvtdecoder.so; its symbols stay private, an APK may bring its own FFmpeg).
//
// Unlike VideoToolbox, libavcodec returns pictures in presentation order and later than the input that carried them,
// so the interface is send / receive: nativeSend gives it an access unit (Annex B) with its pts, nativeReceive returns
// the next finished picture with the pts of the input it came from (0 when none), nativeSend with length 0 starts
// draining at end of stream, nativeReset is a seek. A picture is an AVFrame* (av_frame_clone), released with
// nativeReleasePicture. nativePictureToRgba scales and converts to RGBA or BGRA in one sws_scale; it may run on another
// thread than the decoding one. One decoding thread per codec (thread_count 1): frame threading would add pictures of
// delay that MediaCodec's few spare input buffers cannot carry.
//
// The decoding logic is plain C (avd_*), which native/linux/avdecoder_check.c includes with AVDEC_NO_JNI to test
// without a JVM.
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>

#ifndef AVDEC_NO_JNI
#include <jni.h>
#endif

// Pictures a send had to take out of the decoder to make room (avcodec_send_packet answers EAGAIN while output waits).
#define QUEUE_MAX 64

typedef struct {
  AVCodecContext *ctx;
  AVPacket *pkt;
  AVFrame *frame;
  AVFrame *queue[QUEUE_MAX];
  int queued;
  int draining;  // avcodec_send_packet(NULL) was called: a new send needs a flush first
} AvDec;

// codec: 0 H.264, 1 HEVC, 2 VP9 (superframes are split by the decoder's own bitstream filter).
static AvDec *avd_create(int codecKind) {
  const AVCodec *codec = avcodec_find_decoder(codecKind == 2 ? AV_CODEC_ID_VP9 : codecKind == 1 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
  if (!codec) return NULL;
  AvDec *d = calloc(1, sizeof *d);
  if (!d) return NULL;
  d->ctx = avcodec_alloc_context3(codec);
  d->pkt = av_packet_alloc();
  d->frame = av_frame_alloc();
  if (!d->ctx || !d->pkt || !d->frame) goto fail;
  d->ctx->thread_count = 1;
  if (avcodec_open2(d->ctx, codec, NULL) < 0) goto fail;
  return d;
fail:
  avcodec_free_context(&d->ctx);
  av_packet_free(&d->pkt);
  av_frame_free(&d->frame);
  free(d);
  return NULL;
}

static void avd_clear_queue(AvDec *d) {
  for (int i = 0; i < d->queued; i++) av_frame_free(&d->queue[i]);
  d->queued = 0;
}

static void avd_release(AvDec *d) {
  if (!d) return;
  avd_clear_queue(d);
  avcodec_free_context(&d->ctx);
  av_packet_free(&d->pkt);
  av_frame_free(&d->frame);
  free(d);
}

// Takes one finished picture out of the decoder into the queue; 1 if there was one.
static int avd_pull(AvDec *d) {
  if (d->queued >= QUEUE_MAX) return 0;
  int r = avcodec_receive_frame(d->ctx, d->frame);
  if (r < 0) return 0;
  AVFrame *f = av_frame_clone(d->frame);
  av_frame_unref(d->frame);
  if (!f) return 0;
  d->queue[d->queued++] = f;
  return 1;
}

// One access unit (or parameter sets) with its pts; AV_NOPTS_VALUE (INT64_MIN) for none. Length 0 starts draining.
// 0 on success, a negative AVERROR otherwise (a damaged unit is an error; the decoder goes on with the next).
static int avd_send(AvDec *d, const uint8_t *data, int length, int64_t pts) {
  if (!d) return AVERROR(EINVAL);
  if (length <= 0) {
    if (d->draining) return 0;
    d->draining = 1;
    int r = avcodec_send_packet(d->ctx, NULL);
    while (r == AVERROR(EAGAIN) && avd_pull(d)) r = avcodec_send_packet(d->ctx, NULL);
    return r < 0 && r != AVERROR_EOF ? r : 0;
  }
  if (d->draining) {  // data after the end of stream: a new stream (the pictures drained are queued already)
    while (avd_pull(d)) {}
    avcodec_flush_buffers(d->ctx);
    d->draining = 0;
  }
  int r = av_new_packet(d->pkt, length);  // padded, as the decoders read past the end
  if (r < 0) return r;
  memcpy(d->pkt->data, data, length);
  d->pkt->pts = pts;
  r = avcodec_send_packet(d->ctx, d->pkt);
  while (r == AVERROR(EAGAIN) && avd_pull(d)) r = avcodec_send_packet(d->ctx, d->pkt);
  av_packet_unref(d->pkt);
  return r < 0 ? r : 0;
}

// The next picture in presentation order and its pts, or NULL. Release it with avd_release_picture.
static AVFrame *avd_receive(AvDec *d, int64_t *pts) {
  if (!d) return NULL;
  if (d->queued == 0 && !avd_pull(d)) return NULL;
  AVFrame *f = d->queue[0];
  memmove(d->queue, d->queue + 1, (size_t)(d->queued - 1) * sizeof *d->queue);
  d->queued--;
  *pts = f->pts;
  return f;
}

// A seek: everything inside the decoder goes.
static void avd_reset(AvDec *d) {
  if (!d) return;
  avd_clear_queue(d);
  avcodec_flush_buffers(d->ctx);
  d->draining = 0;
}

static void avd_release_picture(AVFrame *f) { av_frame_free(&f); }

// The scaler is kept between calls (one for the size and format last used), under a lock: a SwsContext is not
// thread safe, and the picture's thread is not the decoder's.
static pthread_mutex_t sws_lock = PTHREAD_MUTEX_INITIALIZER;
static struct SwsContext *sws_ctx;
static struct {
  int sw, sh, sfmt, dw, dh, dfmt, cs, range;
} sws_key;

// Scales a picture to w x h and converts it to RGBA or BGRA bytes (alpha 255) at out, w * 4 bytes a row.
// The matrix and range come from the picture; when unspecified, BT.709 limited from 720 lines up, else BT.601 limited.
static int avd_picture_to_rgba(const AVFrame *f, int w, int h, uint8_t *out, int bgra) {
  if (!f || w <= 0 || h <= 0 || !out) return 0;
  int cs = f->colorspace;
  if (cs == AVCOL_SPC_UNSPECIFIED || cs == AVCOL_SPC_RESERVED || cs == AVCOL_SPC_RGB)
    cs = f->height >= 720 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
  int full = f->color_range == AVCOL_RANGE_JPEG;
  // yuvj formats are full range whatever the tag says
  if (f->format == AV_PIX_FMT_YUVJ420P || f->format == AV_PIX_FMT_YUVJ422P || f->format == AV_PIX_FMT_YUVJ444P) full = 1;
  int dfmt = bgra ? AV_PIX_FMT_BGRA : AV_PIX_FMT_RGBA;
  int ok = 0;
  pthread_mutex_lock(&sws_lock);
  if (!sws_ctx || sws_key.sw != f->width || sws_key.sh != f->height || sws_key.sfmt != f->format || sws_key.dw != w ||
      sws_key.dh != h || sws_key.dfmt != dfmt || sws_key.cs != cs || sws_key.range != full) {
    sws_freeContext(sws_ctx);
    sws_ctx = sws_getContext(f->width, f->height, f->format, w, h, dfmt, SWS_BILINEAR, NULL, NULL, NULL);
    if (sws_ctx) {
      const int *table = sws_getCoefficients(cs);
      sws_setColorspaceDetails(sws_ctx, table, full, sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);
      sws_key.sw = f->width; sws_key.sh = f->height; sws_key.sfmt = f->format;
      sws_key.dw = w; sws_key.dh = h; sws_key.dfmt = dfmt; sws_key.cs = cs; sws_key.range = full;
    }
  }
  if (sws_ctx) {
    uint8_t *dst[4] = {out, NULL, NULL, NULL};
    int dstStride[4] = {w * 4, 0, 0, 0};
    ok = sws_scale(sws_ctx, (const uint8_t *const *)f->data, f->linesize, 0, f->height, dst, dstStride) == h;
  }
  pthread_mutex_unlock(&sws_lock);
  return ok;
}

#ifndef AVDEC_NO_JNI

#define VT(name) Java_dev_apksim_video_VideoToolbox_##name

JNIEXPORT jlong JNICALL VT(nativeCreate)(JNIEnv *env, jclass c, jint codec) {
  return (jlong)(intptr_t)avd_create(codec);
}

JNIEXPORT void JNICALL VT(nativeRelease)(JNIEnv *env, jclass c, jlong handle) { avd_release((AvDec *)(intptr_t)handle); }

JNIEXPORT jint JNICALL VT(nativeSend)(JNIEnv *env, jclass c, jlong handle, jobject data, jint offset, jint length,
                                      jlong ptsUs) {
  const uint8_t *p = NULL;
  if (length > 0) {
    if (!data || offset < 0) return AVERROR(EINVAL);
    jlong cap = (*env)->GetDirectBufferCapacity(env, data);
    uint8_t *base = (*env)->GetDirectBufferAddress(env, data);
    if (!base || cap < 0 || (jlong)offset + length > cap) return AVERROR(EINVAL);
    p = base + offset;
  }
  return avd_send((AvDec *)(intptr_t)handle, p, length, (int64_t)ptsUs);
}

JNIEXPORT jlong JNICALL VT(nativeReceive)(JNIEnv *env, jclass c, jlong handle, jlongArray pts) {
  int64_t t = 0;
  AVFrame *f = avd_receive((AvDec *)(intptr_t)handle, &t);
  if (!f) return 0;
  jlong v = (jlong)t;
  (*env)->SetLongArrayRegion(env, pts, 0, 1, &v);
  return (jlong)(intptr_t)f;
}

JNIEXPORT void JNICALL VT(nativeReset)(JNIEnv *env, jclass c, jlong handle) { avd_reset((AvDec *)(intptr_t)handle); }

JNIEXPORT void JNICALL VT(nativeReleasePicture)(JNIEnv *env, jclass c, jlong picture) {
  avd_release_picture((AVFrame *)(intptr_t)picture);
}

JNIEXPORT jint JNICALL VT(nativePictureWidth)(JNIEnv *env, jclass c, jlong picture) {
  return picture ? ((AVFrame *)(intptr_t)picture)->width : 0;
}

JNIEXPORT jint JNICALL VT(nativePictureHeight)(JNIEnv *env, jclass c, jlong picture) {
  return picture ? ((AVFrame *)(intptr_t)picture)->height : 0;
}

JNIEXPORT jboolean JNICALL VT(nativePictureToRgba)(JNIEnv *env, jclass c, jlong picture, jint w, jint h, jobject rgba,
                                                   jboolean bgra) {
  if (!picture || w <= 0 || h <= 0 || !rgba) return JNI_FALSE;
  uint8_t *out = (*env)->GetDirectBufferAddress(env, rgba);
  jlong cap = (*env)->GetDirectBufferCapacity(env, rgba);
  if (!out || cap < (jlong)w * h * 4) return JNI_FALSE;
  return avd_picture_to_rgba((AVFrame *)(intptr_t)picture, w, h, out, bgra ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}

#endif
