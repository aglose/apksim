// H.264 encoding of the screen for `apksim serve`'s video stream on Linux (dev.apksim.video.H264Encoder), on
// OpenH264 (BSD), opened at run time: the image installs libopenh264-7, and without it the stream stays JPEG. Built
// against its headers (libopenh264-dev in the image's build stage). Input is a presented frame's pixels (BGRA or RGBA,
// as Bitmap memory holds them here), converted to I420 with BT.709's limited-range matrix, which the stream's VUI names;
// output one access unit in Annex B, the SPS and PPS in front of every keyframe (IDR).
#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wels/codec_api.h>

typedef int (*CreateFn)(ISVCEncoder **);
typedef void (*DestroyFn)(ISVCEncoder *);

static CreateFn create;
static DestroyFn destroy;

static int load(void) {
  if (create) return 1;
  const char *names[] = {"libopenh264.so.7", "libopenh264.so.8", "libopenh264.so.6", "libopenh264.so"};
  for (size_t i = 0; i < sizeof names / sizeof *names; i++) {
    void *lib = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
    if (!lib) continue;
    CreateFn c = (CreateFn)dlsym(lib, "WelsCreateSVCEncoder");
    DestroyFn d = (DestroyFn)dlsym(lib, "WelsDestroySVCEncoder");
    if (c && d) {
      destroy = d;
      create = c;
      return 1;
    }
    dlclose(lib);
  }
  return 0;
}

typedef struct {
  ISVCEncoder *enc;
  int w, h;
  uint8_t *yuv;  // I420: w*h luma, then two (w/2)*(h/2) chroma planes
  uint8_t *out;
  size_t outCap;
} Encoder;

JNIEXPORT jboolean JNICALL Java_dev_apksim_video_H264Encoder_nativeAvailable(JNIEnv *env, jclass c) {
  return load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_dev_apksim_video_H264Encoder_nativeOpen(JNIEnv *env, jclass c, jint w, jint h, jint kbps) {
  if (!load()) return 0;
  ISVCEncoder *enc = NULL;
  if (create(&enc) != 0 || !enc) return 0;
  SEncParamExt p;
  memset(&p, 0, sizeof p);
  (*enc)->GetDefaultParams(enc, &p);
  p.iUsageType = SCREEN_CONTENT_REAL_TIME;
  p.iPicWidth = w;
  p.iPicHeight = h;
  p.iTargetBitrate = kbps * 1000;
  p.iRCMode = RC_BITRATE_MODE;
  p.fMaxFrameRate = 60;
  p.iSpatialLayerNum = 1;
  p.iTemporalLayerNum = 1;
  p.uiIntraPeriod = 0;  // keyframes only when asked for
  p.bEnableFrameSkip = false;  // every frame comes out: the stream skips unchanged ones itself
  p.iMultipleThreadIdc = 1;
  SSpatialLayerConfig *l = &p.sSpatialLayers[0];
  l->iVideoWidth = w;
  l->iVideoHeight = h;
  l->fFrameRate = 60;
  l->iSpatialBitrate = kbps * 1000;
  l->iMaxSpatialBitrate = kbps * 2000;
  l->sSliceArgument.uiSliceMode = SM_SINGLE_SLICE;
  l->bVideoSignalTypePresent = true;
  l->uiVideoFormat = VF_UNDEF;
  l->bFullRange = false;
  l->bColorDescriptionPresent = true;
  l->uiColorPrimaries = CP_BT709;
  l->uiTransferCharacteristics = TRC_BT709;
  l->uiColorMatrix = CM_BT709;
  if ((*enc)->InitializeExt(enc, &p) != 0) {
    destroy(enc);
    return 0;
  }
  int format = videoFormatI420;
  (*enc)->SetOption(enc, ENCODER_OPTION_DATAFORMAT, &format);
  Encoder *e = calloc(1, sizeof(Encoder));
  e->enc = enc;
  e->w = w;
  e->h = h;
  e->yuv = malloc((size_t)w * h * 3 / 2);
  return (jlong)e;
}

JNIEXPORT void JNICALL Java_dev_apksim_video_H264Encoder_nativeClose(JNIEnv *env, jclass c, jlong h) {
  Encoder *e = (Encoder *)h;
  if (!e) return;
  (*e->enc)->Uninitialize(e->enc);
  destroy(e->enc);
  free(e->yuv);
  free(e->out);
  free(e);
}

// BT.709, limited range, in 8.8 fixed point; chroma from each 2x2 block's average.
static void toI420(Encoder *e, const uint8_t *src, int stride, int bgra) {
  int w = e->w, h = e->h, cw = w / 2;
  uint8_t *Y = e->yuv, *U = Y + (size_t)w * h, *V = U + (size_t)cw * (h / 2);
  int ri = bgra ? 2 : 0, bi = bgra ? 0 : 2;
  for (int y = 0; y < h; y += 2) {
    const uint8_t *row0 = src + (size_t)y * stride, *row1 = row0 + stride;
    uint8_t *y0 = Y + (size_t)y * w, *y1 = y0 + w;
    for (int x = 0; x < w; x += 2) {
      int rs = 0, gs = 0, bs = 0;
      const uint8_t *px[4] = {row0 + x * 4, row0 + x * 4 + 4, row1 + x * 4, row1 + x * 4 + 4};
      uint8_t *out[4] = {y0 + x, y0 + x + 1, y1 + x, y1 + x + 1};
      for (int i = 0; i < 4; i++) {
        int r = px[i][ri], g = px[i][1], b = px[i][bi];
        *out[i] = (uint8_t)(16 + ((47 * r + 157 * g + 16 * b + 128) >> 8));
        rs += r;
        gs += g;
        bs += b;
      }
      rs >>= 2;
      gs >>= 2;
      bs >>= 2;
      size_t ci = (size_t)(y / 2) * cw + x / 2;
      U[ci] = (uint8_t)(128 + ((-26 * rs - 87 * gs + 112 * bs + 128) >> 8));
      V[ci] = (uint8_t)(128 + ((112 * rs - 102 * gs - 10 * bs + 128) >> 8));
    }
  }
}

/**
 * Encodes one frame (rows of stride bytes, the encoder's width and height of them; BGRA unless bgra is false) and
 * returns [keyframe flag] + Annex B, or null.
 */
JNIEXPORT jbyteArray JNICALL Java_dev_apksim_video_H264Encoder_nativeEncode(JNIEnv *env, jclass c, jlong h, jbyteArray pixels,
                                                                            jint stride, jboolean bgra, jlong micros,
                                                                            jboolean key) {
  Encoder *e = (Encoder *)h;
  jsize n = (*env)->GetArrayLength(env, pixels);
  if (stride < e->w * 4 || (size_t)n < (size_t)stride * e->h) return NULL;
  uint8_t *src = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
  if (!src) return NULL;
  toI420(e, src, stride, bgra);
  (*env)->ReleasePrimitiveArrayCritical(env, pixels, src, JNI_ABORT);
  if (key) (*e->enc)->ForceIntraFrame(e->enc, true, -1);
  SSourcePicture pic;
  memset(&pic, 0, sizeof pic);
  pic.iColorFormat = videoFormatI420;
  pic.iPicWidth = e->w;
  pic.iPicHeight = e->h;
  pic.iStride[0] = e->w;
  pic.iStride[1] = pic.iStride[2] = e->w / 2;
  pic.pData[0] = e->yuv;
  pic.pData[1] = e->yuv + (size_t)e->w * e->h;
  pic.pData[2] = pic.pData[1] + (size_t)(e->w / 2) * (e->h / 2);
  pic.uiTimeStamp = micros / 1000;
  SFrameBSInfo info;
  memset(&info, 0, sizeof info);
  if ((*e->enc)->EncodeFrame(e->enc, &pic, &info) != cmResultSuccess || info.eFrameType == videoFrameTypeSkip
      || info.eFrameType == videoFrameTypeInvalid) {
    return NULL;
  }
  size_t total = 1;
  for (int i = 0; i < info.iLayerNum; i++) {
    for (int j = 0; j < info.sLayerInfo[i].iNalCount; j++) total += info.sLayerInfo[i].pNalLengthInByte[j];
  }
  if (total > e->outCap) {
    e->out = realloc(e->out, total);
    e->outCap = total;
  }
  // Each layer's NAL units sit one after another in its buffer, with their start codes.
  size_t at = 0;
  e->out[at++] = info.eFrameType == videoFrameTypeIDR;  // a decoder can start only there
  for (int i = 0; i < info.iLayerNum; i++) {
    size_t len = 0;
    for (int j = 0; j < info.sLayerInfo[i].iNalCount; j++) len += info.sLayerInfo[i].pNalLengthInByte[j];
    memcpy(e->out + at, info.sLayerInfo[i].pBsBuf, len);
    at += len;
  }
  jbyteArray out = (*env)->NewByteArray(env, (jsize)at);
  if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)at, (const jbyte *)e->out);
  return out;
}
