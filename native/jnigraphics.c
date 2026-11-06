// libjnigraphics (the NDK's <android/bitmap.h>) for loaded ELF code, on macOS and Linux alike: AndroidBitmap_getInfo,
// lockPixels, unlockPixels, getDataSpace, getHardwareBuffer and compress. Robolectric's Bitmap gives no address for its
// pixels, so a lock copies them out through dev.apksim.NativeBitmaps into memory of ours, and the last unlock copies
// them back (copyPixelsFromBuffer, which bumps Skia's generation ID as a device's unlock does), only when they changed.
// ARGB_8888 pixels are BGRA in a Bitmap on macOS (Skia's order there) and RGBA in what native code is given, as on a
// device. Locks nest and are shared: every lock of one Bitmap, from any thread, gets the same memory until the last
// unlock. Java writes to a Bitmap while native code holds it locked are not seen until it is locked again.
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "elfloader.h"

enum { RESULT_SUCCESS = 0, RESULT_BAD_PARAMETER = -1, RESULT_JNI_EXCEPTION = -2, RESULT_ALLOCATION_FAILED = -3 };

typedef struct {
  uint32_t width, height, stride;
  int32_t format;
  uint32_t flags;
} BitmapInfo;  // AndroidBitmapInfo

static JavaVM *vm;
static jclass helper;
static jmethodID m_info, m_size, m_copy_out, m_copy_in, m_data_space, m_compress;

typedef struct Locked {
  struct Locked *next;
  jobject bitmap;  // global reference
  uint8_t *pixels;  // what native code reads and writes
  uint8_t *original;  // as copied out, in the Bitmap's own byte order
  size_t size;
  int swap;  // red and blue swapped between the two
  int count;
} Locked;
static Locked *locked;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

void jnigraphics_init(void *jenv) {
  JNIEnv *env = jenv;
  if (helper) return;
  (*env)->GetJavaVM(env, &vm);
  jclass c = (*env)->FindClass(env, "dev/apksim/NativeBitmaps");
  if (!c) {
    (*env)->ExceptionClear(env);
    fprintf(stderr, "[apksim] no dev.apksim.NativeBitmaps: AndroidBitmap_* will fail\n");
    return;
  }
  m_info = (*env)->GetStaticMethodID(env, c, "info", "(Landroid/graphics/Bitmap;[I)I");
  m_size = (*env)->GetStaticMethodID(env, c, "size", "(Landroid/graphics/Bitmap;)I");
  m_copy_out = (*env)->GetStaticMethodID(env, c, "copyOut", "(Landroid/graphics/Bitmap;Ljava/nio/ByteBuffer;)I");
  m_copy_in = (*env)->GetStaticMethodID(env, c, "copyIn", "(Landroid/graphics/Bitmap;Ljava/nio/ByteBuffer;)I");
  m_data_space = (*env)->GetStaticMethodID(env, c, "dataSpace", "(Landroid/graphics/Bitmap;)I");
  m_compress = (*env)->GetStaticMethodID(env, c, "compress", "(Ljava/nio/ByteBuffer;IIIIIIII)[B");
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionDescribe(env);
    return;
  }
  helper = (*env)->NewGlobalRef(env, c);
}

// A Java exception from a call of ours is reported and cleared: native code calling the NDK does not expect one.
static int threw(JNIEnv *env, const char *fn) {
  if (!(*env)->ExceptionCheck(env)) return 0;
  fprintf(stderr, "[apksim] %s: ", fn);
  (*env)->ExceptionDescribe(env);
  return 1;
}

static int call_int(JNIEnv *env, const char *fn, jmethodID m, const jvalue *args) {
  jint r = (*env)->CallStaticIntMethodA(env, helper, m, args);
  return threw(env, fn) ? RESULT_JNI_EXCEPTION : r;
}

static void swap_red_blue(uint8_t *p, size_t n) {
  for (size_t i = 0; i + 3 < n; i += 4) {
    uint8_t r = p[i];
    p[i] = p[i + 2];
    p[i + 2] = r;
  }
}

static Locked *find(JNIEnv *env, jobject bitmap, Locked ***link) {
  for (Locked **l = &locked; *l; l = &(*l)->next) {
    if ((*env)->IsSameObject(env, (*l)->bitmap, bitmap)) {
      if (link) *link = l;
      return *l;
    }
  }
  return NULL;
}

static int bm_get_info(JNIEnv *env, jobject bitmap, BitmapInfo *info) {
  if (!env || !bitmap) return RESULT_BAD_PARAMETER;
  if (!helper) return RESULT_JNI_EXCEPTION;
  if (!info) return RESULT_SUCCESS;
  jintArray out = (*env)->NewIntArray(env, 5);
  if (!out) {
    threw(env, "AndroidBitmap_getInfo");
    return RESULT_ALLOCATION_FAILED;
  }
  jvalue a[2] = {{.l = bitmap}, {.l = out}};
  int r = call_int(env, "AndroidBitmap_getInfo", m_info, a);
  if (r == RESULT_SUCCESS) {
    jint v[5];
    (*env)->GetIntArrayRegion(env, out, 0, 5, v);
    info->width = (uint32_t)v[0];
    info->height = (uint32_t)v[1];
    info->stride = (uint32_t)v[2];
    info->format = v[3];
    info->flags = (uint32_t)v[4];
  }
  (*env)->DeleteLocalRef(env, out);
  return r;
}

static int bm_lock_pixels(JNIEnv *env, jobject bitmap, void **addr) {
  if (!env || !bitmap) return RESULT_BAD_PARAMETER;
  if (!helper) return RESULT_JNI_EXCEPTION;
  pthread_mutex_lock(&lock);
  int r = RESULT_SUCCESS;
  Locked *l = find(env, bitmap, NULL);
  if (!l) {
    jvalue a[2] = {{.l = bitmap}};
    int size = call_int(env, "AndroidBitmap_lockPixels", m_size, a);
    if (size < 0) {
      r = size;
      goto out;
    }
    l = calloc(1, sizeof *l);
    if (l) {
      l->size = (size_t)size;
      l->pixels = malloc(l->size ? l->size : 1);
      l->original = malloc(l->size ? l->size : 1);
    }
    if (!l || !l->pixels || !l->original) {
      r = RESULT_ALLOCATION_FAILED;
      goto fail;
    }
    jobject buf = (*env)->NewDirectByteBuffer(env, l->original, (jlong)l->size);
    if (!buf) {
      threw(env, "AndroidBitmap_lockPixels");
      r = RESULT_ALLOCATION_FAILED;
      goto fail;
    }
    a[1].l = buf;
    int swap = call_int(env, "AndroidBitmap_lockPixels", m_copy_out, a);
    (*env)->DeleteLocalRef(env, buf);
    if (swap < 0) {
      r = swap;
      goto fail;
    }
    l->bitmap = (*env)->NewGlobalRef(env, bitmap);
    if (!l->bitmap) {
      r = RESULT_ALLOCATION_FAILED;
      goto fail;
    }
    memcpy(l->pixels, l->original, l->size);
    l->swap = swap;
    if (swap) swap_red_blue(l->pixels, l->size);
    l->next = locked;
    locked = l;
  }
  l->count++;
  if (addr) *addr = l->pixels;
  goto out;
fail:
  if (l) {
    free(l->pixels);
    free(l->original);
    free(l);
  }
out:
  pthread_mutex_unlock(&lock);
  return r;
}

static int bm_unlock_pixels(JNIEnv *env, jobject bitmap) {
  if (!env || !bitmap) return RESULT_BAD_PARAMETER;
  pthread_mutex_lock(&lock);
  int r = RESULT_SUCCESS;
  Locked **link = NULL;
  Locked *l = find(env, bitmap, &link);
  if (!l || --l->count > 0) goto out;  // a device's unlock without a lock only notifies the change
  *link = l->next;
  if (l->swap) swap_red_blue(l->pixels, l->size);
  if (memcmp(l->pixels, l->original, l->size)) {
    jobject buf = (*env)->NewDirectByteBuffer(env, l->pixels, (jlong)l->size);
    if (!buf) {
      threw(env, "AndroidBitmap_unlockPixels");
      r = RESULT_ALLOCATION_FAILED;
    } else {
      jvalue a[2] = {{.l = l->bitmap}, {.l = buf}};
      r = call_int(env, "AndroidBitmap_unlockPixels", m_copy_in, a);
      (*env)->DeleteLocalRef(env, buf);
    }
  }
  (*env)->DeleteGlobalRef(env, l->bitmap);
  free(l->pixels);
  free(l->original);
  free(l);
out:
  pthread_mutex_unlock(&lock);
  return r;
}

static int32_t bm_get_data_space(JNIEnv *env, jobject bitmap) {
  if (!env || !bitmap) return RESULT_BAD_PARAMETER;
  if (!helper) return 0;
  jvalue a[1] = {{.l = bitmap}};
  int r = call_int(env, "AndroidBitmap_getDataSpace", m_data_space, a);
  return r < 0 ? 0 : r;
}

// No HardwareBuffer bitmaps here: a device answers this for HARDWARE ones only.
static int bm_get_hardware_buffer(JNIEnv *env, jobject bitmap, void **out) {
  if (out) *out = NULL;
  return RESULT_BAD_PARAMETER;
}

typedef bool (*CompressWrite)(void *context, const void *data, size_t size);

static int bm_compress(const BitmapInfo *info, int32_t data_space, const void *pixels, int32_t format, int32_t quality,
                       void *context, CompressWrite write) {
  if (!info || !pixels || !write || !info->width || !info->height || !vm) return RESULT_BAD_PARAMETER;
  if (!helper) return RESULT_JNI_EXCEPTION;
  JNIEnv *env = NULL;
  int attached = 0;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    if ((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) != JNI_OK) return RESULT_JNI_EXCEPTION;
    attached = 1;
  }
  int bpp = info->format == 1 ? 4 : info->format == 4 ? 2 : info->format == 8 ? 1 : 8;
  size_t size = (size_t)info->stride * (info->height - 1) + (size_t)info->width * bpp;
  int r;
  jobject buf = (*env)->NewDirectByteBuffer(env, (void *)pixels, (jlong)size);
  if (!buf) {
    threw(env, "AndroidBitmap_compress");
    r = RESULT_ALLOCATION_FAILED;
    goto done;
  }
  jvalue a[9] = {{.l = buf}, {.i = (jint)info->width}, {.i = (jint)info->height}, {.i = (jint)info->stride}, {.i = info->format},
                 {.i = (jint)info->flags}, {.i = data_space}, {.i = format}, {.i = quality}};
  jbyteArray out = (*env)->CallStaticObjectMethodA(env, helper, m_compress, a);
  (*env)->DeleteLocalRef(env, buf);
  if (threw(env, "AndroidBitmap_compress")) {
    r = RESULT_JNI_EXCEPTION;
  } else if (!out) {
    r = RESULT_BAD_PARAMETER;
  } else {
    jsize n = (*env)->GetArrayLength(env, out);
    jbyte *bytes = n ? (*env)->GetByteArrayElements(env, out, NULL) : NULL;
    r = n && bytes && write(context, bytes, (size_t)n) ? RESULT_SUCCESS : RESULT_JNI_EXCEPTION;
    if (bytes) (*env)->ReleaseByteArrayElements(env, out, bytes, JNI_ABORT);
    (*env)->DeleteLocalRef(env, out);
  }
done:
  if (attached) (*vm)->DetachCurrentThread(vm);
  return r;
}

void *jnigraphics_lookup(const char *name) {
  if (strncmp(name, "AndroidBitmap_", 14)) return NULL;
  static const struct { const char *name; void *fn; } table[] = {
      {"getInfo", (void *)bm_get_info}, {"lockPixels", (void *)bm_lock_pixels}, {"unlockPixels", (void *)bm_unlock_pixels},
      {"getDataSpace", (void *)bm_get_data_space}, {"getHardwareBuffer", (void *)bm_get_hardware_buffer},
      {"compress", (void *)bm_compress},
  };
  for (size_t i = 0; i < sizeof table / sizeof table[0]; i++) {
    if (!strcmp(name + 14, table[i].name)) return table[i].fn;
  }
  return NULL;
}
