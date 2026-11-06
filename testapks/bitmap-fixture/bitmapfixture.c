// Native code reading and writing Bitmaps through libjnigraphics (<android/bitmap.h>), as image libraries do: Fresco's
// copy of one bitmap into another (lock both, copy rows), OpenCV's and ML helpers' reads. NativeBitmapTest runs it; a
// device passes every check. Built by build.sh into the test app's jniLibs.
#include <android/bitmap.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *vm;
static int last;

JNIEXPORT jint JNI_OnLoad(JavaVM *v, void *reserved) {
  vm = v;
  return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_lastResult(JNIEnv *env, jclass c) { return last; }

// {result, width, height, stride, format, flags}
JNIEXPORT jintArray JNICALL Java_dev_apksim_composeapp_BitmapFixture_info(JNIEnv *env, jclass c, jobject bitmap) {
  AndroidBitmapInfo info;
  memset(&info, 0, sizeof info);
  jint v[6] = {AndroidBitmap_getInfo(env, bitmap, &info), (jint)info.width, (jint)info.height, (jint)info.stride,
               info.format, (jint)info.flags};
  jintArray out = (*env)->NewIntArray(env, 6);
  (*env)->SetIntArrayRegion(env, out, 0, 6, v);
  return out;
}

// length bytes at offset of the locked pixels, or null (lastResult says why).
JNIEXPORT jbyteArray JNICALL Java_dev_apksim_composeapp_BitmapFixture_read(JNIEnv *env, jclass c, jobject bitmap, jint offset,
                                                                         jint length) {
  void *px = NULL;
  if ((last = AndroidBitmap_lockPixels(env, bitmap, &px)) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
  jbyteArray out = (*env)->NewByteArray(env, length);
  (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte *)px + offset);
  last = AndroidBitmap_unlockPixels(env, bitmap);
  return out;
}

JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_write(JNIEnv *env, jclass c, jobject bitmap, jint offset,
                                                                    jbyteArray bytes) {
  void *px = NULL;
  int r = AndroidBitmap_lockPixels(env, bitmap, &px);
  if (r != ANDROID_BITMAP_RESULT_SUCCESS) return r;
  (*env)->GetByteArrayRegion(env, bytes, 0, (*env)->GetArrayLength(env, bytes), (jbyte *)px + offset);
  return AndroidBitmap_unlockPixels(env, bitmap);
}

// Fresco's Bitmaps.copyBitmap (nativeCopyBitmap): both locked at once, rows copied, both unlocked.
JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_copy(JNIEnv *env, jclass c, jobject dst, jobject src) {
  AndroidBitmapInfo di, si;
  int r;
  if ((r = AndroidBitmap_getInfo(env, dst, &di)) || (r = AndroidBitmap_getInfo(env, src, &si))) return r;
  if (di.height != si.height || di.format != si.format) return -100;
  void *dp = NULL, *sp = NULL;
  if ((r = AndroidBitmap_lockPixels(env, dst, &dp))) return r;
  if ((r = AndroidBitmap_lockPixels(env, src, &sp))) {
    AndroidBitmap_unlockPixels(env, dst);
    return r;
  }
  uint32_t row = di.stride < si.stride ? di.stride : si.stride;
  for (uint32_t y = 0; y < si.height; y++) memcpy((char *)dp + y * di.stride, (char *)sp + y * si.stride, row);
  AndroidBitmap_unlockPixels(env, src);
  return AndroidBitmap_unlockPixels(env, dst);
}

// Two locks of one bitmap share memory; a write through the inner one lasts past both unlocks.
JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_nested(JNIEnv *env, jclass c, jobject bitmap, jint value) {
  void *a = NULL, *b = NULL;
  int r;
  if ((r = AndroidBitmap_lockPixels(env, bitmap, &a))) return r;
  if ((r = AndroidBitmap_lockPixels(env, bitmap, &b))) return r;
  if (a != b) return -101;
  memcpy(b, &value, 4);
  if ((r = AndroidBitmap_unlockPixels(env, bitmap))) return r;
  if (memcmp(a, &value, 4)) return -102;
  return AndroidBitmap_unlockPixels(env, bitmap);
}

typedef struct {
  jobject bitmap;
  int row, rounds, result;
} Job;

// Each thread locks the shared bitmap, writes its row (one 32-bit value per pixel), and unlocks, many times over.
static void *fill_row(void *arg) {
  Job *j = arg;
  JNIEnv *env = NULL;
  if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
    j->result = -103;
    return NULL;
  }
  AndroidBitmapInfo info;
  if ((j->result = AndroidBitmap_getInfo(env, j->bitmap, &info)) == 0) {
    for (int k = 0; k < j->rounds && j->result == 0; k++) {
      void *px = NULL;
      if ((j->result = AndroidBitmap_lockPixels(env, j->bitmap, &px))) break;
      uint32_t *row = (uint32_t *)((char *)px + j->row * info.stride);
      for (uint32_t x = 0; x < info.width; x++) row[x] = 0xff000000u | (uint32_t)(j->row * 16 + k % 16);
      j->result = AndroidBitmap_unlockPixels(env, j->bitmap);
    }
  }
  (*vm)->DetachCurrentThread(vm);
  return NULL;
}

// One thread per row of an ARGB_8888 bitmap; row y ends up R = y * 16 + 15, G = B = 0, A = 255.
JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_threads(JNIEnv *env, jclass c, jobject bitmap, jint rows) {
  pthread_t t[16];
  Job jobs[16];
  if (rows > 16) return -104;
  jobject global = (*env)->NewGlobalRef(env, bitmap);
  for (int i = 0; i < rows; i++) {
    jobs[i] = (Job){global, i, 32, 0};
    pthread_create(&t[i], NULL, fill_row, &jobs[i]);
  }
  int r = 0;
  for (int i = 0; i < rows; i++) {
    pthread_join(t[i], NULL);
    if (jobs[i].result && !r) r = jobs[i].result;
  }
  (*env)->DeleteGlobalRef(env, global);
  return r;
}

JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_dataSpace(JNIEnv *env, jclass c, jobject bitmap) {
  return AndroidBitmap_getDataSpace(env, bitmap);
}

typedef struct {
  char *data;
  size_t size;
} Sink;

static bool sink_write(void *context, const void *data, size_t size) {
  Sink *s = context;
  char *grown = realloc(s->data, s->size + size);
  if (!grown) return false;
  memcpy(grown + s->size, data, size);
  s->data = grown;
  s->size += size;
  return true;
}

// The bitmap's pixels encoded by AndroidBitmap_compress (format: ANDROID_BITMAP_COMPRESS_FORMAT_*), or null.
JNIEXPORT jbyteArray JNICALL Java_dev_apksim_composeapp_BitmapFixture_compress(JNIEnv *env, jclass c, jobject bitmap, jint format,
                                                                             jint quality) {
  AndroidBitmapInfo info;
  if ((last = AndroidBitmap_getInfo(env, bitmap, &info))) return NULL;
  int32_t space = AndroidBitmap_getDataSpace(env, bitmap);
  void *px = NULL;
  if ((last = AndroidBitmap_lockPixels(env, bitmap, &px))) return NULL;
  Sink s = {NULL, 0};
  last = AndroidBitmap_compress(&info, space, px, format, quality, &s, sink_write);
  AndroidBitmap_unlockPixels(env, bitmap);
  jbyteArray out = NULL;
  if (last == ANDROID_BITMAP_RESULT_SUCCESS) {
    out = (*env)->NewByteArray(env, (jsize)s.size);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)s.size, (const jbyte *)s.data);
  }
  free(s.data);
  return out;
}

// The NDK's answers to null arguments: 0 when each is BAD_PARAMETER.
JNIEXPORT jint JNICALL Java_dev_apksim_composeapp_BitmapFixture_badParameters(JNIEnv *env, jclass c) {
  AndroidBitmapInfo info;
  void *px;
  if (AndroidBitmap_getInfo(env, NULL, &info) != ANDROID_BITMAP_RESULT_BAD_PARAMETER) return 1;
  if (AndroidBitmap_lockPixels(env, NULL, &px) != ANDROID_BITMAP_RESULT_BAD_PARAMETER) return 2;
  if (AndroidBitmap_unlockPixels(env, NULL) != ANDROID_BITMAP_RESULT_BAD_PARAMETER) return 3;
  return 0;
}
