// AAC and MP3 decoding for MediaCodec on Linux (dev.apksim.video.SimAudio's decoder natives, the Mac's being
// native/audio.m on AudioToolbox), on libavcodec with only the decoders built in (docker/Dockerfile's AUDIO_DECODERS),
// linked statically (FFmpeg's symbols stay private: an APK may bring its own FFmpeg). Output is the 16-bit interleaved
// PCM a MediaCodec audio decoder gives (no swresample: the few sample formats libavcodec's decoders return are
// converted here). The AudioTrack side is not here: on Linux it is the Java VirtualOut clock.
//
// Synchronous like AudioToolbox's: one access unit in, the PCM it decodes to out (a decoder holding a frame back returns
// none for it and two later). The decoding logic is plain C (aud_*), which native/linux/avaudio_check.c includes with
// AUDEC_NO_JNI to test without a JVM.
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>

#ifndef AUDEC_NO_JNI
#include <jni.h>
#endif

typedef struct {
  AVCodecContext *ctx;
  AVPacket *pkt;
  AVFrame *frame;
  int rate, channels;  // what the stream gives: an HE-AAC config's doubled rate and PS's two channels are in them
} AuDec;

// codec: 0 AAC (config: the AudioSpecificConfig, or none for ADTS), 1 MP3.
static AuDec *aud_create(int codecKind, int rate, int channels, const uint8_t *config, int configLen) {
  const AVCodec *codec = avcodec_find_decoder(codecKind == 1 ? AV_CODEC_ID_MP3 : AV_CODEC_ID_AAC);
  if (!codec) return NULL;
  AuDec *d = calloc(1, sizeof *d);
  if (!d) return NULL;
  d->ctx = avcodec_alloc_context3(codec);
  d->pkt = av_packet_alloc();
  d->frame = av_frame_alloc();
  if (!d->ctx || !d->pkt || !d->frame) goto fail;
  d->ctx->thread_count = 1;
  if (rate > 0) d->ctx->sample_rate = rate;
  if (channels > 0) av_channel_layout_default(&d->ctx->ch_layout, channels);
  if (config && configLen > 0) {
    d->ctx->extradata = av_mallocz(configLen + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!d->ctx->extradata) goto fail;
    memcpy(d->ctx->extradata, config, configLen);
    d->ctx->extradata_size = configLen;
  }
  if (avcodec_open2(d->ctx, codec, NULL) < 0) goto fail;
  d->rate = d->ctx->sample_rate > 0 ? d->ctx->sample_rate : rate;
  d->channels = d->ctx->ch_layout.nb_channels > 0 ? d->ctx->ch_layout.nb_channels : channels;
  return d;
fail:
  avcodec_free_context(&d->ctx);
  av_packet_free(&d->pkt);
  av_frame_free(&d->frame);
  free(d);
  return NULL;
}

static void aud_release(AuDec *d) {
  if (!d) return;
  avcodec_free_context(&d->ctx);
  av_packet_free(&d->pkt);
  av_frame_free(&d->frame);
  free(d);
}

static int16_t to_s16(float v) {
  if (!(v < 1.0f)) v = 1.0f;  // also NaN
  if (v < -1.0f) v = -1.0f;
  return (int16_t)lrintf(v * 32767.0f);
}

// One sample of channel c at index i of a frame, as a float in -1..1.
static float sample_at(const AVFrame *f, int c, int i) {
  int fmt = f->format, ch = f->ch_layout.nb_channels;
  int planar = av_sample_fmt_is_planar(fmt);
  const uint8_t *base = planar ? f->extended_data[c] : f->extended_data[0];
  size_t idx = planar ? (size_t)i : (size_t)i * ch + c;
  switch (av_get_packed_sample_fmt(fmt)) {
    case AV_SAMPLE_FMT_FLT: return ((const float *)base)[idx];
    case AV_SAMPLE_FMT_DBL: return (float)((const double *)base)[idx];
    case AV_SAMPLE_FMT_S16: return ((const int16_t *)base)[idx] / 32768.0f;
    case AV_SAMPLE_FMT_S32: return ((const int32_t *)base)[idx] / 2147483648.0f;
    case AV_SAMPLE_FMT_U8: return (((const uint8_t *)base)[idx] - 128) / 128.0f;
    default: return 0;
  }
}

// Decodes one access unit; the PCM (16-bit, interleaved) is written to out, at most cap bytes. Returns the bytes written,
// or -1 for a unit the decoder refused (it goes on with the next).
static int aud_decode(AuDec *d, const uint8_t *data, int length, uint8_t *out, int cap) {
  if (!d || length <= 0) return -1;
  int r = av_new_packet(d->pkt, length);  // padded, as the decoders read past the end
  if (r < 0) return -1;
  memcpy(d->pkt->data, data, length);
  r = avcodec_send_packet(d->ctx, d->pkt);
  av_packet_unref(d->pkt);
  if (r < 0 && r != AVERROR(EAGAIN)) return -1;
  int written = 0;
  while (avcodec_receive_frame(d->ctx, d->frame) >= 0) {
    AVFrame *f = d->frame;
    int ch = f->ch_layout.nb_channels;
    if (f->sample_rate > 0) d->rate = f->sample_rate;
    if (ch > 0) d->channels = ch;
    for (int i = 0; i < f->nb_samples && ch > 0; i++) {
      if (written + 2 * ch > cap) break;
      for (int c = 0; c < ch; c++) {
        int16_t s = to_s16(sample_at(f, c, i));
        memcpy(out + written, &s, 2);  // little endian, as AudioTrack's PCM_16BIT on every host here
        written += 2;
      }
    }
    av_frame_unref(f);
  }
  return written;
}

static void aud_reset(AuDec *d) {
  if (d) avcodec_flush_buffers(d->ctx);
}

#ifndef AUDEC_NO_JNI

#define SA(name) Java_dev_apksim_video_SimAudio_##name

JNIEXPORT jlong JNICALL SA(nativeDecoderCreate)(JNIEnv *env, jclass c, jint codec, jint rate, jint channels, jbyteArray config) {
  jbyte *cfg = NULL;
  jsize n = 0;
  if (config) {
    n = (*env)->GetArrayLength(env, config);
    cfg = (*env)->GetByteArrayElements(env, config, NULL);
  }
  AuDec *d = aud_create(codec, rate, channels, (const uint8_t *)cfg, n);
  if (cfg) (*env)->ReleaseByteArrayElements(env, config, cfg, JNI_ABORT);
  return (jlong)(intptr_t)d;
}

JNIEXPORT jint JNICALL SA(nativeDecode)(JNIEnv *env, jclass c, jlong h, jobject in, jint offset, jint length, jobject out,
                                        jint capacity) {
  if (!in || !out || offset < 0 || length <= 0 || capacity < 0) return -1;
  uint8_t *src = (*env)->GetDirectBufferAddress(env, in);
  uint8_t *dst = (*env)->GetDirectBufferAddress(env, out);
  jlong srcCap = (*env)->GetDirectBufferCapacity(env, in);
  jlong dstCap = (*env)->GetDirectBufferCapacity(env, out);
  if (!src || !dst || (jlong)offset + length > srcCap || capacity > dstCap) return -1;
  return aud_decode((AuDec *)(intptr_t)h, src + offset, length, dst, capacity);
}

JNIEXPORT jint JNICALL SA(nativeDecoderRate)(JNIEnv *env, jclass c, jlong h) { return h ? ((AuDec *)(intptr_t)h)->rate : 0; }

JNIEXPORT jint JNICALL SA(nativeDecoderChannels)(JNIEnv *env, jclass c, jlong h) {
  return h ? ((AuDec *)(intptr_t)h)->channels : 0;
}

// libavcodec's AAC decoder finds SBR and parametric stereo signalled in the frames itself: nothing to sniff for.
JNIEXPORT jint JNICALL SA(nativeSniffSbr)(JNIEnv *env, jclass c, jbyteArray config, jobject frame, jint offset, jint length) {
  return 0;
}

JNIEXPORT void JNICALL SA(nativeDecoderReset)(JNIEnv *env, jclass c, jlong h) { aud_reset((AuDec *)(intptr_t)h); }

JNIEXPORT void JNICALL SA(nativeDecoderRelease)(JNIEnv *env, jclass c, jlong h) { aud_release((AuDec *)(intptr_t)h); }

#endif
