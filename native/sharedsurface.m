// Spike S1 (docs/spike-s1-shared-surfaces.md): the presented frame copied into a ring of three BGRA IOSurfaces that
// another process reads without a copy. `global`: the surfaces are created global (kIOSurfaceIsGlobal, deprecated) and
// the host finds them by ID with IOSurfaceLookup. `mach`: each surface's Mach send right (IOSurfaceCreateMachPort) goes
// in one message to the bootstrap service NAME the host checked in. SharedSurface.java is the Java side.
#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#include <jni.h>
#include <mach/mach.h>
#include <pthread.h>
#include <servers/bootstrap.h>
#include <string.h>

#define RING 3
static IOSurfaceRef ring[RING];
static int ringW, ringH, next, mode;
static mach_port_t hostPort = MACH_PORT_NULL;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

// The message the host gets for each new ring: a complex message with three port descriptors, then the size.
typedef struct {
  mach_msg_header_t header;
  mach_msg_body_t body;
  mach_msg_port_descriptor_t ports[RING];
  int32_t w, h;
} RingMsg;

static void freeRing(void) {
  for (int i = 0; i < RING; i++) {
    if (ring[i]) CFRelease(ring[i]);
    ring[i] = NULL;
  }
  ringW = ringH = 0;
}

static int sendRing(void) {
  RingMsg m = {0};
  m.header.msgh_bits = MACH_MSGH_BITS(MACH_MSG_TYPE_COPY_SEND, 0) | MACH_MSGH_BITS_COMPLEX;
  m.header.msgh_size = sizeof m;
  m.header.msgh_remote_port = hostPort;
  m.header.msgh_id = 0x51;
  m.body.msgh_descriptor_count = RING;
  for (int i = 0; i < RING; i++) {
    m.ports[i].name = IOSurfaceCreateMachPort(ring[i]);
    m.ports[i].disposition = MACH_MSG_TYPE_MOVE_SEND;  // the message takes the right IOSurfaceCreateMachPort made
    m.ports[i].type = MACH_MSG_PORT_DESCRIPTOR;
  }
  m.w = ringW;
  m.h = ringH;
  kern_return_t kr = mach_msg(&m.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof m, 0, MACH_PORT_NULL, 100,
                              MACH_PORT_NULL);
  if (kr != KERN_SUCCESS) {
    fprintf(stderr, "[apksim] surfacewatch: mach_msg to the host: %s (%d)\n", mach_error_string(kr), kr);
    for (int i = 0; i < RING; i++) mach_port_deallocate(mach_task_self(), m.ports[i].name);
    return -1;
  }
  return 0;
}

JNIEXPORT jint JNICALL Java_dev_apksim_SharedSurface_nativeStart(JNIEnv *env, jclass c, jint m, jstring name) {
  pthread_mutex_lock(&lock);
  freeRing();
  mode = m;
  int rc = 0;
  if (m == 2) {
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    kern_return_t kr = bootstrap_look_up(bootstrap_port, n, &hostPort);
    if (kr != KERN_SUCCESS) {
      fprintf(stderr, "[apksim] surfacewatch: bootstrap_look_up(%s): %s (%d)\n", n, bootstrap_strerror(kr), kr);
      hostPort = MACH_PORT_NULL;
      rc = kr;
    }
    (*env)->ReleaseStringUTFChars(env, name, n);
  }
  pthread_mutex_unlock(&lock);
  return rc;
}

JNIEXPORT void JNICALL Java_dev_apksim_SharedSurface_nativeStop(JNIEnv *env, jclass c) {
  pthread_mutex_lock(&lock);
  freeRing();
  if (hostPort != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), hostPort);
  hostPort = MACH_PORT_NULL;
  mode = 0;
  pthread_mutex_unlock(&lock);
}

JNIEXPORT jint JNICALL Java_dev_apksim_SharedSurface_nativeFrame(JNIEnv *env, jclass c, jobject buf, jint w, jint h) {
  const uint8_t *src = (*env)->GetDirectBufferAddress(env, buf);
  if (!src || (*env)->GetDirectBufferCapacity(env, buf) < (jlong)w * h * 4) return -1;
  pthread_mutex_lock(&lock);
  if (!mode) {
    pthread_mutex_unlock(&lock);
    return -1;
  }
  int fresh = 0;
  if (w != ringW || h != ringH) {
    freeRing();
    NSMutableDictionary *props = [@{
      (id)kIOSurfaceWidth : @(w),
      (id)kIOSurfaceHeight : @(h),
      (id)kIOSurfaceBytesPerElement : @4,
      (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
    } mutableCopy];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    if (mode == 1) props[(id)kIOSurfaceIsGlobal] = @YES;
#pragma clang diagnostic pop
    for (int i = 0; i < RING; i++) ring[i] = IOSurfaceCreate((__bridge CFDictionaryRef)props);
    ringW = w;
    ringH = h;
    next = 0;
    fresh = 1;
    if (!ring[0] || !ring[1] || !ring[2] || (mode == 2 && sendRing() != 0)) {
      freeRing();
      pthread_mutex_unlock(&lock);
      return -1;
    }
  }
  int idx = next;
  next = (next + 1) % RING;
  IOSurfaceRef s = ring[idx];
  IOSurfaceLock(s, 0, NULL);
  uint8_t *dst = IOSurfaceGetBaseAddress(s);
  size_t stride = IOSurfaceGetBytesPerRow(s), row = (size_t)w * 4;
  if (stride == row) memcpy(dst, src, row * h);
  else for (int y = 0; y < h; y++) memcpy(dst + y * stride, src + y * row, row);
  IOSurfaceUnlock(s, 0, NULL);
  pthread_mutex_unlock(&lock);
  return idx | (fresh ? 0x100 : 0);
}

JNIEXPORT jintArray JNICALL Java_dev_apksim_SharedSurface_nativeIds(JNIEnv *env, jclass c) {
  jint ids[RING] = {0};
  pthread_mutex_lock(&lock);
  for (int i = 0; i < RING; i++) ids[i] = ring[i] ? (jint)IOSurfaceGetID(ring[i]) : 0;
  pthread_mutex_unlock(&lock);
  jintArray a = (*env)->NewIntArray(env, RING);
  (*env)->SetIntArrayRegion(env, a, 0, RING, ids);
  return a;
}
