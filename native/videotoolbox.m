// H.264 and HEVC (H.265) decoding for Android apps' video (dev.apksim.video.VideoToolbox), on VideoToolbox. Input is
// what libwebrtc or a media player hands an Android decoder: one access unit of Annex B NAL units. Output is copied out
// as I420, or kept as a picture for MediaCodec.
#import <Accelerate/Accelerate.h>
#import <CoreMedia/CoreMedia.h>
#import <VideoToolbox/VideoToolbox.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
  CMVideoFormatDescriptionRef format;
  VTDecompressionSessionRef session;
  int hevc;
  uint8_t vps[512], sps[512], pps[512];  // vps: HEVC only
  size_t vpsLen, spsLen, ppsLen;
  CVPixelBufferRef picture;  // The latest decoded picture, NV12.
  OSStatus lastError;
} Decoder;

static void onDecoded(void *ref, void *frameRef, OSStatus status, VTDecodeInfoFlags flags, CVImageBufferRef image,
                      CMTime pts, CMTime duration) {
  Decoder *d = ref;
  d->lastError = status;
  if (status != noErr || !image) return;
  if (d->picture) CVPixelBufferRelease(d->picture);
  d->picture = CVPixelBufferRetain(image);
}

static void dropSession(Decoder *d) {
  if (d->session) {
    VTDecompressionSessionWaitForAsynchronousFrames(d->session);
    VTDecompressionSessionInvalidate(d->session);
    CFRelease(d->session);
    d->session = NULL;
  }
  if (d->format) {
    CFRelease(d->format);
    d->format = NULL;
  }
}

static int makeSession(Decoder *d) {
  dropSession(d);
  if (d->hevc) {
    const uint8_t *sets[3] = {d->vps, d->sps, d->pps};
    const size_t sizes[3] = {d->vpsLen, d->spsLen, d->ppsLen};
    if (CMVideoFormatDescriptionCreateFromHEVCParameterSets(NULL, 3, sets, sizes, 4, NULL, &d->format) != noErr) return -1;
  } else {
    const uint8_t *sets[2] = {d->sps, d->pps};
    const size_t sizes[2] = {d->spsLen, d->ppsLen};
    if (CMVideoFormatDescriptionCreateFromH264ParameterSets(NULL, 2, sets, sizes, 4, &d->format) != noErr) return -1;
  }
  int fmt = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
  CFNumberRef fmtNum = CFNumberCreate(NULL, kCFNumberIntType, &fmt);
  const void *keys[] = {kCVPixelBufferPixelFormatTypeKey};
  const void *values[] = {fmtNum};
  CFDictionaryRef attrs = CFDictionaryCreate(NULL, keys, values, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
  VTDecompressionOutputCallbackRecord cb = {onDecoded, d};
  OSStatus s = VTDecompressionSessionCreate(NULL, d->format, NULL, attrs, &cb, &d->session);
  CFRelease(attrs);
  CFRelease(fmtNum);
  return s == noErr ? 0 : -1;
}

// Next NAL unit in an Annex B stream: sets *nal and *len, returns the offset after it, or -1 at the end.
static long nextNal(const uint8_t *p, long n, long at, const uint8_t **nal, long *len) {
  long i = at;
  while (i + 3 <= n && !(p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1)) i++;
  if (i + 3 > n) return -1;
  long start = i + 3, j = start;
  while (j + 3 <= n && !(p[j] == 0 && p[j + 1] == 0 && (p[j + 2] == 1 || (p[j + 2] == 0 && j + 3 < n && p[j + 3] == 1)))) j++;
  if (j + 3 > n) j = n;
  long end = j;
  while (end > start && p[end - 1] == 0) end--;  // Trailing zeros belong to the next start code.
  *nal = p + start;
  *len = end - start;
  return j;
}

JNIEXPORT jlong JNICALL Java_dev_apksim_video_VideoToolbox_nativeCreate(JNIEnv *env, jclass c, jboolean hevc) {
  Decoder *d = calloc(1, sizeof(Decoder));
  d->hevc = hevc;
  return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL Java_dev_apksim_video_VideoToolbox_nativeRelease(JNIEnv *env, jclass c, jlong h) {
  Decoder *d = (Decoder *)(intptr_t)h;
  dropSession(d);
  if (d->picture) CVPixelBufferRelease(d->picture);
  free(d);
}

JNIEXPORT jint JNICALL Java_dev_apksim_video_VideoToolbox_nativeDecode(JNIEnv *env, jclass c, jlong h, jobject buf,
                                                                        jint offset, jint length, jintArray sizeOut) {
  Decoder *d = (Decoder *)(intptr_t)h;
  const uint8_t *p = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf) + offset;
  // Parameter sets update the session; everything else goes into one AVCC sample (4-byte lengths).
  uint8_t *avcc = malloc((size_t)length + 64);
  size_t avccLen = 0;
  int changed = 0, vcl = 0;
  const uint8_t *nal;
  long len;
  for (long at = 0; (at = nextNal(p, length, at, &nal, &len)) >= 0;) {
    if (len <= 0) continue;
    // H.264: SPS 7, PPS 8, delimiter 9, slices 1-5. HEVC: VPS 32, SPS 33, PPS 34, delimiter 35, slices 0-31.
    int type = d->hevc ? (nal[0] >> 1) & 0x3f : nal[0] & 0x1f;
    int set = d->hevc ? (type == 32 ? 0 : type == 33 ? 1 : type == 34 ? 2 : -1) : (type == 7 ? 1 : type == 8 ? 2 : -1);
    if (set >= 0) {
      uint8_t *dst = set == 0 ? d->vps : set == 1 ? d->sps : d->pps;
      size_t *dstLen = set == 0 ? &d->vpsLen : set == 1 ? &d->spsLen : &d->ppsLen;
      if (len <= 512 && (*dstLen != (size_t)len || memcmp(dst, nal, (size_t)len))) {
        memcpy(dst, nal, (size_t)len);
        *dstLen = (size_t)len;
        changed = 1;
      }
      continue;
    }
    if (type == (d->hevc ? 35 : 9)) continue;  // Access unit delimiter.
    if (d->hevc ? type <= 31 : type >= 1 && type <= 5) vcl = 1;
    uint32_t be = CFSwapInt32HostToBig((uint32_t)len);
    memcpy(avcc + avccLen, &be, 4);
    memcpy(avcc + avccLen + 4, nal, (size_t)len);
    avccLen += 4 + (size_t)len;
  }
  if ((changed || !d->session) && d->spsLen && d->ppsLen && (d->vpsLen || !d->hevc) && makeSession(d) < 0) {
    free(avcc);
    return -1;
  }
  if (!vcl) {
    free(avcc);
    return 0;
  }
  if (!d->session) {
    free(avcc);
    return -1;  // Pictures before any parameter sets: ask for a key frame.
  }
  CMBlockBufferRef block = NULL;
  CMSampleBufferRef sample = NULL;
  if (CMBlockBufferCreateWithMemoryBlock(NULL, avcc, avccLen, kCFAllocatorMalloc, NULL, 0, avccLen, 0, &block) != noErr) {
    free(avcc);
    return -1;
  }
  size_t sampleSize = avccLen;
  if (CMSampleBufferCreateReady(NULL, block, d->format, 1, 0, NULL, 1, &sampleSize, &sample) != noErr) {
    CFRelease(block);
    return -1;
  }
  CVPixelBufferRef before = d->picture;
  d->lastError = noErr;
  VTDecodeInfoFlags infoFlags = 0;
  OSStatus s = VTDecompressionSessionDecodeFrame(d->session, sample, 0, NULL, &infoFlags);
  VTDecompressionSessionWaitForAsynchronousFrames(d->session);
  CFRelease(sample);
  CFRelease(block);
  if (s == kVTInvalidSessionErr) {
    makeSession(d);
    return -1;
  }
  if (s != noErr || d->lastError != noErr) return -1;
  if (d->picture == before || !d->picture) return 0;
  jint dims[2] = {(jint)CVPixelBufferGetWidth(d->picture), (jint)CVPixelBufferGetHeight(d->picture)};
  (*env)->SetIntArrayRegion(env, sizeOut, 0, 2, dims);
  return 1;
}

JNIEXPORT void JNICALL Java_dev_apksim_video_VideoToolbox_nativeCopyI420(JNIEnv *env, jclass c, jlong h, jobject y, jint sy,
                                                                          jobject u, jint su, jobject v, jint sv) {
  Decoder *d = (Decoder *)(intptr_t)h;
  if (!d->picture) return;
  uint8_t *dy = (*env)->GetDirectBufferAddress(env, y), *du = (*env)->GetDirectBufferAddress(env, u),
          *dv = (*env)->GetDirectBufferAddress(env, v);
  CVPixelBufferLockBaseAddress(d->picture, kCVPixelBufferLock_ReadOnly);
  size_t w = CVPixelBufferGetWidth(d->picture), hgt = CVPixelBufferGetHeight(d->picture);
  const uint8_t *py = CVPixelBufferGetBaseAddressOfPlane(d->picture, 0);
  size_t pys = CVPixelBufferGetBytesPerRowOfPlane(d->picture, 0);
  for (size_t r = 0; r < hgt; r++) memcpy(dy + r * (size_t)sy, py + r * pys, w);
  const uint8_t *puv = CVPixelBufferGetBaseAddressOfPlane(d->picture, 1);
  size_t puvs = CVPixelBufferGetBytesPerRowOfPlane(d->picture, 1);
  size_t cw = (w + 1) / 2, ch = (hgt + 1) / 2;
  for (size_t r = 0; r < ch; r++) {
    const uint8_t *row = puv + r * puvs;
    uint8_t *ru = du + r * (size_t)su, *rv = dv + r * (size_t)sv;
    for (size_t i = 0; i < cw; i++) {
      ru[i] = row[2 * i];
      rv[i] = row[2 * i + 1];
    }
  }
  CVPixelBufferUnlockBaseAddress(d->picture, kCVPixelBufferLock_ReadOnly);
}

static vImage_YpCbCrToARGB conversion;
static pthread_once_t conversionOnce = PTHREAD_ONCE_INIT;
static void makeConversion(void) {
  vImage_YpCbCrPixelRange range = {16, 128, 235, 240, 235, 16, 240, 16};  // Video range, as cameras send.
  vImageConvert_YpCbCrToARGB_GenerateConversion(kvImage_YpCbCrToARGBMatrix_ITU_R_709_2, &range, &conversion,
                                                kvImage420Yp8_Cb8_Cr8, kvImageARGB8888, kvImageNoFlags);
}

JNIEXPORT void JNICALL Java_dev_apksim_video_VideoToolbox_i420ToRgba(JNIEnv *env, jclass c, jobject y, jint sy, jobject u,
                                                                      jint su, jobject v, jint sv, jint w, jint h, jobject out,
                                                                      jboolean bgra) {
  pthread_once(&conversionOnce, makeConversion);
  vImage_Buffer by = {(*env)->GetDirectBufferAddress(env, y), (vImagePixelCount)h, (vImagePixelCount)w, (size_t)sy};
  vImage_Buffer bu = {(*env)->GetDirectBufferAddress(env, u), (vImagePixelCount)((h + 1) / 2), (vImagePixelCount)((w + 1) / 2), (size_t)su};
  vImage_Buffer bv = {(*env)->GetDirectBufferAddress(env, v), (vImagePixelCount)((h + 1) / 2), (vImagePixelCount)((w + 1) / 2), (size_t)sv};
  vImage_Buffer dst = {(*env)->GetDirectBufferAddress(env, out), (vImagePixelCount)h, (vImagePixelCount)w, (size_t)w * 4};
  // ARGB out of vImage, permuted to the byte order a Bitmap holds here: R, G, B, A on Android, B, G, R, A where
  // Skia's native order is BGRA (macOS).
  const uint8_t rgba[4] = {1, 2, 3, 0}, bgraOrder[4] = {3, 2, 1, 0};
  vImageConvert_420Yp8_Cb8_Cr8ToARGB8888(&by, &bu, &bv, &dst, &conversion, bgra ? bgraOrder : rgba, 255, kvImageNoFlags);
}

// ---------------------------------------------------------------- pictures for MediaCodec output buffers

// MediaCodec hands out decoded pictures as output buffers that the app renders later, or never; each keeps
// its own reference to the picture until released.
JNIEXPORT jlong JNICALL Java_dev_apksim_video_VideoToolbox_nativeTakePicture(JNIEnv *env, jclass c, jlong h) {
  Decoder *d = (Decoder *)(intptr_t)h;
  return d->picture ? (jlong)(intptr_t)CVPixelBufferRetain(d->picture) : 0;
}

JNIEXPORT void JNICALL Java_dev_apksim_video_VideoToolbox_nativeReleasePicture(JNIEnv *env, jclass c, jlong picture) {
  if (picture) CVPixelBufferRelease((CVPixelBufferRef)(intptr_t)picture);
}

JNIEXPORT jint JNICALL Java_dev_apksim_video_VideoToolbox_nativePictureWidth(JNIEnv *env, jclass c, jlong picture) {
  return picture ? (jint)CVPixelBufferGetWidth((CVPixelBufferRef)(intptr_t)picture) : 0;
}

JNIEXPORT jint JNICALL Java_dev_apksim_video_VideoToolbox_nativePictureHeight(JNIEnv *env, jclass c, jlong picture) {
  return picture ? (jint)CVPixelBufferGetHeight((CVPixelBufferRef)(intptr_t)picture) : 0;
}

static VTPixelTransferSessionRef transfer;
static pthread_mutex_t transferLock = PTHREAD_MUTEX_INITIALIZER;

// Scales and converts a decoded picture to [w]x[h] RGBA (a Bitmap's byte order) in one hardware pass.
JNIEXPORT jboolean JNICALL Java_dev_apksim_video_VideoToolbox_nativePictureToRgba(JNIEnv *env, jclass c, jlong picture,
                                                                                  jint w, jint h, jobject out, jboolean bgra) {
  if (!picture) return JNI_FALSE;
  CVPixelBufferRef dst = NULL;
  if (CVPixelBufferCreate(NULL, (size_t)w, (size_t)h, kCVPixelFormatType_32BGRA, NULL, &dst) != kCVReturnSuccess) return JNI_FALSE;
  pthread_mutex_lock(&transferLock);
  if (!transfer) {
    VTPixelTransferSessionCreate(NULL, &transfer);
    VTSessionSetProperty(transfer, kVTPixelTransferPropertyKey_ScalingMode, kVTScalingMode_Normal);
  }
  OSStatus s = VTPixelTransferSessionTransferImage(transfer, (CVPixelBufferRef)(intptr_t)picture, dst);
  pthread_mutex_unlock(&transferLock);
  if (s != noErr) {
    CVPixelBufferRelease(dst);
    return JNI_FALSE;
  }
  CVPixelBufferLockBaseAddress(dst, kCVPixelBufferLock_ReadOnly);
  vImage_Buffer src = {CVPixelBufferGetBaseAddress(dst), (vImagePixelCount)h, (vImagePixelCount)w, CVPixelBufferGetBytesPerRow(dst)};
  vImage_Buffer to = {(*env)->GetDirectBufferAddress(env, out), (vImagePixelCount)h, (vImagePixelCount)w, (size_t)w * 4};
  const uint8_t bgraToRgba[4] = {2, 1, 0, 3}, same[4] = {0, 1, 2, 3};
  vImagePermuteChannels_ARGB8888(&src, &to, bgra ? same : bgraToRgba, kvImageNoFlags);
  CVPixelBufferUnlockBaseAddress(dst, kCVPixelBufferLock_ReadOnly);
  CVPixelBufferRelease(dst);
  return JNI_TRUE;
}
