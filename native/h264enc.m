// H.264 encoding of the screen for `apksim serve`'s video stream (dev.apksim.video.H264Encoder), on VideoToolbox. Input
// is a presented frame's BGRA pixels; output one access unit in Annex B (start codes), with the SPS and PPS in front of
// every keyframe, as WebCodecs takes H.264 without a description. Low latency: real time, no frame reordering, and
// each frame waited for before the call returns.
#import <CoreMedia/CoreMedia.h>
#import <VideoToolbox/VideoToolbox.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
  VTCompressionSessionRef session;
  int w, h;
  uint8_t *out;  // [0] keyframe flag, then Annex B
  size_t outLen, outCap;
  int ok;
} Encoder;

static void append(Encoder *e, const void *p, size_t n) {
  if (e->outLen + n > e->outCap) {
    size_t cap = e->outCap ? e->outCap : 1 << 16;
    while (cap < e->outLen + n) cap *= 2;
    e->out = realloc(e->out, cap);
    e->outCap = cap;
  }
  memcpy(e->out + e->outLen, p, n);
  e->outLen += n;
}

static const uint8_t START[4] = {0, 0, 0, 1};

static void onEncoded(void *ref, void *frameRef, OSStatus status, VTEncodeInfoFlags flags, CMSampleBufferRef sample) {
  Encoder *e = ref;
  if (status != noErr || !sample || !CMSampleBufferDataIsReady(sample)) return;
  int key = 1;
  CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
  if (attachments && CFArrayGetCount(attachments) > 0) {
    CFDictionaryRef a = CFArrayGetValueAtIndex(attachments, 0);
    key = !CFDictionaryContainsKey(a, kCMSampleAttachmentKey_NotSync);
  }
  e->outLen = 0;
  uint8_t flag = key;
  append(e, &flag, 1);
  if (key) {
    CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sample);
    size_t count = 0;
    CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0, NULL, NULL, &count, NULL);
    for (size_t i = 0; i < count; i++) {
      const uint8_t *set;
      size_t len;
      if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, i, &set, &len, NULL, NULL) != noErr) continue;
      append(e, START, 4);
      append(e, set, len);
    }
  }
  CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);
  size_t total = 0;
  char *data = NULL;
  if (CMBlockBufferGetDataPointer(block, 0, NULL, &total, &data) != noErr) return;
  // AVCC: each NAL unit after a 4-byte big-endian length.
  for (size_t at = 0; at + 4 <= total;) {
    uint32_t len = ((uint8_t)data[at] << 24) | ((uint8_t)data[at + 1] << 16) | ((uint8_t)data[at + 2] << 8) | (uint8_t)data[at + 3];
    at += 4;
    if (len > total - at) break;
    append(e, START, 4);
    append(e, data + at, len);
    at += len;
  }
  e->ok = 1;
}

static void setInt(VTCompressionSessionRef s, CFStringRef key, int v) {
  CFNumberRef n = CFNumberCreate(NULL, kCFNumberIntType, &v);
  VTSessionSetProperty(s, key, n);
  CFRelease(n);
}

JNIEXPORT jboolean JNICALL Java_dev_apksim_video_H264Encoder_nativeAvailable(JNIEnv *env, jclass c) {
  return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_dev_apksim_video_H264Encoder_nativeOpen(JNIEnv *env, jclass c, jint w, jint h, jint kbps) {
  Encoder *e = calloc(1, sizeof(Encoder));
  e->w = w;
  e->h = h;
  // Low-latency rate control: no frame reordering, and frames come out as they go in.
  const void *specKeys[] = {kVTVideoEncoderSpecification_EnableLowLatencyRateControl};
  const void *specValues[] = {kCFBooleanTrue};
  CFDictionaryRef spec = CFDictionaryCreate(NULL, specKeys, specValues, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
  int fmt = kCVPixelFormatType_32BGRA;
  CFNumberRef fmtNum = CFNumberCreate(NULL, kCFNumberIntType, &fmt);
  const void *pbKeys[] = {kCVPixelBufferPixelFormatTypeKey};
  const void *pbValues[] = {fmtNum};
  CFDictionaryRef pb = CFDictionaryCreate(NULL, pbKeys, pbValues, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
  OSStatus s = VTCompressionSessionCreate(NULL, w, h, kCMVideoCodecType_H264, spec, pb, NULL, onEncoded, e, &e->session);
  if (s != noErr) {
    // Without the low-latency encoder (older macOS, or a size it refuses): the ordinary one, set up the same way.
    s = VTCompressionSessionCreate(NULL, w, h, kCMVideoCodecType_H264, NULL, pb, NULL, onEncoded, e, &e->session);
  }
  CFRelease(spec);
  CFRelease(pb);
  CFRelease(fmtNum);
  if (s != noErr) {
    free(e);
    return 0;
  }
  VTSessionSetProperty(e->session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
  VTSessionSetProperty(e->session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
  VTSessionSetProperty(e->session, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_High_AutoLevel);
  setInt(e->session, kVTCompressionPropertyKey_AverageBitRate, kbps * 1000);
  // Keyframes only when asked for (a new viewer, a decoder that lost its place) and at a size change (a new session).
  setInt(e->session, kVTCompressionPropertyKey_MaxKeyFrameInterval, 0x7fffffff);
  setInt(e->session, kVTCompressionPropertyKey_ExpectedFrameRate, 60);
  // BT.709, limited range, written into the stream's VUI: the browser converts back with it.
  VTSessionSetProperty(e->session, kVTCompressionPropertyKey_ColorPrimaries, kCVImageBufferColorPrimaries_ITU_R_709_2);
  VTSessionSetProperty(e->session, kVTCompressionPropertyKey_TransferFunction, kCVImageBufferTransferFunction_ITU_R_709_2);
  VTSessionSetProperty(e->session, kVTCompressionPropertyKey_YCbCrMatrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2);
  VTCompressionSessionPrepareToEncodeFrames(e->session);
  return (jlong)e;
}

JNIEXPORT void JNICALL Java_dev_apksim_video_H264Encoder_nativeClose(JNIEnv *env, jclass c, jlong h) {
  Encoder *e = (Encoder *)h;
  if (!e) return;
  VTCompressionSessionCompleteFrames(e->session, kCMTimeInvalid);
  VTCompressionSessionInvalidate(e->session);
  CFRelease(e->session);
  free(e->out);
  free(e);
}

/**
 * Encodes one frame (rows of stride bytes, the encoder's width and height of them; BGRA unless bgra is false) and
 * returns [keyframe flag] + Annex B, or null.
 */
JNIEXPORT jbyteArray JNICALL Java_dev_apksim_video_H264Encoder_nativeEncode(JNIEnv *env, jclass c, jlong h, jbyteArray pixels,
                                                                            jint stride, jboolean bgra, jlong micros,
                                                                            jboolean key) {
  Encoder *e = (Encoder *)h;
  CVPixelBufferPoolRef pool = VTCompressionSessionGetPixelBufferPool(e->session);
  CVPixelBufferRef buf = NULL;
  if (!pool || CVPixelBufferPoolCreatePixelBuffer(NULL, pool, &buf) != kCVReturnSuccess) return NULL;
  CVPixelBufferLockBaseAddress(buf, 0);
  uint8_t *dst = CVPixelBufferGetBaseAddress(buf);
  size_t dstStride = CVPixelBufferGetBytesPerRow(buf);
  jsize n = (*env)->GetArrayLength(env, pixels);
  if (stride < e->w * 4 || (size_t)n < (size_t)stride * e->h) {
    CVPixelBufferUnlockBaseAddress(buf, 0);
    CVPixelBufferRelease(buf);
    return NULL;
  }
  uint8_t *src = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
  if (!src) {  // an exception is pending (out of memory): it reaches Java once this returns
    CVPixelBufferUnlockBaseAddress(buf, 0);
    CVPixelBufferRelease(buf);
    return NULL;
  }
  for (int y = 0; y < e->h; y++) {
    uint8_t *d = dst + y * dstStride;
    const uint8_t *s = src + (size_t)y * stride;
    if (bgra) {
      memcpy(d, s, (size_t)e->w * 4);
    } else {
      for (int x = 0; x < e->w; x++, d += 4, s += 4) {
        d[0] = s[2];
        d[1] = s[1];
        d[2] = s[0];
        d[3] = s[3];
      }
    }
  }
  (*env)->ReleasePrimitiveArrayCritical(env, pixels, src, JNI_ABORT);
  CVPixelBufferUnlockBaseAddress(buf, 0);
  CFDictionaryRef props = NULL;
  if (key) {
    const void *k[] = {kVTEncodeFrameOptionKey_ForceKeyFrame};
    const void *v[] = {kCFBooleanTrue};
    props = CFDictionaryCreate(NULL, k, v, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
  }
  e->ok = 0;
  CMTime pts = CMTimeMake(micros, 1000000);
  OSStatus s = VTCompressionSessionEncodeFrame(e->session, buf, pts, kCMTimeInvalid, props, NULL, NULL);
  if (props) CFRelease(props);
  CVPixelBufferRelease(buf);
  if (s != noErr) return NULL;
  VTCompressionSessionCompleteFrames(e->session, pts);
  if (!e->ok) return NULL;
  jbyteArray out = (*env)->NewByteArray(env, (jsize)e->outLen);
  if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)e->outLen, (const jbyte *)e->out);
  return out;
}
