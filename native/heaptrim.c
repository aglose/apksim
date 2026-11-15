// dev.apksim.HeapTrim: the Java heap's unused pages given back to the system while an instance is idle.
//
// HotSpot's serial collector never returns memory it has touched: the young generation (-Xmn64m) is written once and
// stays dirty for good, empty or not, and so does whatever the old generation held before a full collection shrank it.
// An idle instance or standby keeps ~60 MB of pages that hold nothing. While armed (the instance is idle), the end of
// each collection replaces the free part of every space with fresh zero pages, as HotSpot itself does when it uncommits
// and commits a range (mmap MAP_FIXED over it; madvise MADV_DONTNEED on Linux). It runs in JVMTI's
// GarbageCollectionFinish callback, which the VM thread calls inside the collection's pause: every Java thread is
// stopped and holds no allocation buffer, so nothing can allocate in the range meanwhile. The pages come back, zeroed,
// as the app allocates again.
//
// The spaces are found as the JDK's serviceability agent finds them: libjvm exports a table describing its own
// structures (gHotSpotVMStructs), read here by name, so a JDK without those names (or another collector) turns this
// off rather than reading the wrong memory. Java checks the sizes read against Runtime.totalMemory() before arming.
#define _GNU_SOURCE  // RTLD_DEFAULT, MADV_DONTNEED on glibc
#include <jni.h>
#include <jvmti.h>
#include <dlfcn.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

typedef struct { uintptr_t *bottom, *end, *top; } Space;  // addresses of a ContiguousSpace's three fields

static Space eden, from, to, old;
static uintptr_t heap_lo, heap_hi, page;
static uintptr_t young_bytes;
// How far each of eden and the old generation may have been written since its last trim (the rest was never touched,
// or is fresh already). Only the VM thread reads and writes them, in the callbacks below.
static uintptr_t eden_mark, old_mark, old_top_at_start;
static atomic_int armed;
static atomic_long collections;
static int ready;

static const char *vm_string(void *entry, uint64_t off) { return *(const char **)((char *)entry + off); }

// The offset of TYPE::FIELD (an instance field), or the address of a static one; -1 if the table has no such entry.
static int64_t vm_field(const char *type, const char *field, int is_static) {
  void **table = dlsym(RTLD_DEFAULT, "gHotSpotVMStructs");
  uint64_t *stride = dlsym(RTLD_DEFAULT, "gHotSpotVMStructEntryArrayStride");
  uint64_t *type_off = dlsym(RTLD_DEFAULT, "gHotSpotVMStructEntryTypeNameOffset");
  uint64_t *field_off = dlsym(RTLD_DEFAULT, "gHotSpotVMStructEntryFieldNameOffset");
  uint64_t *static_off = dlsym(RTLD_DEFAULT, "gHotSpotVMStructEntryIsStaticOffset");
  uint64_t *offset_off = dlsym(RTLD_DEFAULT, "gHotSpotVMStructEntryOffsetOffset");
  uint64_t *address_off = dlsym(RTLD_DEFAULT, "gHotSpotVMStructEntryAddressOffset");
  if (!table || !*table || !stride || !type_off || !field_off || !static_off || !offset_off || !address_off) return -1;
  for (char *e = *table; vm_string(e, *type_off); e += *stride) {
    if (strcmp(vm_string(e, *type_off), type) || strcmp(vm_string(e, *field_off), field)) continue;
    if (*(int32_t *)(e + *static_off) != is_static) return -1;
    return is_static ? (int64_t)*(uintptr_t *)(e + *address_off) : (int64_t)*(uint64_t *)(e + *offset_off);
  }
  return -1;
}

static int in_heap(Space *s) {
  uintptr_t b = *s->bottom, e = *s->end, t = *s->top;
  return b >= heap_lo && e <= heap_hi && b <= t && t <= e;
}

// Gives [lo, hi) back, rounded inwards to whole pages. Called with every Java thread stopped.
static void discard(uintptr_t lo, uintptr_t hi) {
  lo = (lo + page - 1) & ~(page - 1);
  hi &= ~(page - 1);
  if (hi <= lo) return;
#ifdef __APPLE__
  // What HotSpot's own commit does: fresh anonymous pages over the range, same protection.
  mmap((void *)lo, hi - lo, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_FIXED | MAP_ANON, -1, 0);
#else
  madvise((void *)lo, hi - lo, MADV_DONTNEED);
#endif
}

// The end of the page a mark is in (that page was written), but never past the space's end, where the next begins.
static uintptr_t written_end(uintptr_t mark, uintptr_t end) {
  mark = (mark + page - 1) & ~(page - 1);
  return mark < end ? mark : end;
}

static void JNICALL gc_start(jvmtiEnv *jvmti) {
  if (!ready) return;
  // Eden only grows between collections, so its top now is as far as it was written since the last trim.
  if (in_heap(&eden) && *eden.top > eden_mark) eden_mark = *eden.top;
  if (in_heap(&old)) {
    old_top_at_start = *old.top;
    if (old_top_at_start > old_mark) old_mark = old_top_at_start;
  }
}

static void JNICALL gc_finish(jvmtiEnv *jvmti) {
  atomic_fetch_add(&collections, 1);
  if (!ready) return;
  if (!in_heap(&eden) || !in_heap(&from) || !in_heap(&to) || !in_heap(&old)) return;
  // During the pause the old generation took at most the young generation's objects (promotion) before a full
  // collection compacted it, so that bounds how far it was written.
  uintptr_t reached = old_top_at_start + young_bytes;
  if (reached > *old.end) reached = *old.end;
  if (reached > old_mark) old_mark = reached;
  if (*old.top > old_mark) old_mark = *old.top;
  if (*eden.top > eden_mark) eden_mark = *eden.top;
  if (!atomic_load(&armed)) return;
  discard(*eden.top, written_end(eden_mark, *eden.end));
  eden_mark = *eden.top;
  discard(*from.top, *from.end);  // the survivor spaces are small and change roles: their whole free part
  discard(*to.top, *to.end);
  discard(*old.top, written_end(old_mark, *old.end));
  old_mark = *old.top;
}

// Finds the heap's spaces and registers the callbacks. Returns the bytes of eden, the from-space and the old
// generation together (what Runtime.totalMemory() reports for the serial collector), or a negative reason:
// -1 no structure table or a name missing, -2 the layout read makes no sense, -3 JVMTI refused.
JNIEXPORT jlong JNICALL Java_dev_apksim_HeapTrim_nativeInit(JNIEnv *env, jclass cls) {
  int64_t heap_addr = vm_field("Universe", "_collectedHeap", 1);
  int64_t reserved = vm_field("CollectedHeap", "_reserved", 0);
  int64_t young = vm_field("SerialHeap", "_young_gen", 0), tenured = vm_field("SerialHeap", "_old_gen", 0);
  int64_t eden_f = vm_field("DefNewGeneration", "_eden_space", 0), from_f = vm_field("DefNewGeneration", "_from_space", 0);
  int64_t to_f = vm_field("DefNewGeneration", "_to_space", 0), the_space = vm_field("TenuredGeneration", "_the_space", 0);
  int64_t bottom = vm_field("ContiguousSpace", "_bottom", 0), end = vm_field("ContiguousSpace", "_end", 0);
  int64_t top = vm_field("ContiguousSpace", "_top", 0);
  if (heap_addr <= 0 || reserved < 0 || young < 0 || tenured < 0 || eden_f < 0 || from_f < 0 || to_f < 0 || the_space < 0
      || bottom < 0 || end < 0 || top < 0) return -1;
  char *heap = *(char **)(uintptr_t)heap_addr;
  if (!heap) return -1;
  heap_lo = *(uintptr_t *)(heap + reserved);                              // MemRegion: start, then its size in words
  heap_hi = heap_lo + *(uintptr_t *)(heap + reserved + sizeof(uintptr_t)) * sizeof(uintptr_t);
  char *young_gen = *(char **)(heap + young), *old_gen = *(char **)(heap + tenured);
  if (!young_gen || !old_gen || heap_hi <= heap_lo) return -2;
  char *spaces[4] = {*(char **)(young_gen + eden_f), *(char **)(young_gen + from_f), *(char **)(young_gen + to_f),
                     *(char **)(old_gen + the_space)};
  Space *out[4] = {&eden, &from, &to, &old};
  for (int i = 0; i < 4; i++) {
    if (!spaces[i]) return -2;
    out[i]->bottom = (uintptr_t *)(spaces[i] + bottom);
    out[i]->end = (uintptr_t *)(spaces[i] + end);
    out[i]->top = (uintptr_t *)(spaces[i] + top);
    if (!in_heap(out[i])) return -2;
  }
  // Serial's layout: the young generation first (eden, then the two survivor spaces), the old generation above it.
  uintptr_t young_end = *from.end > *to.end ? *from.end : *to.end;
  if (*eden.bottom != heap_lo || *eden.end > *from.bottom || *eden.end > *to.bottom || *old.bottom < young_end) return -2;
  young_bytes = young_end - *eden.bottom;
  page = (uintptr_t)sysconf(_SC_PAGESIZE);
  // What was written before now is unknown (a full collection may already have shrunk the old generation): the first
  // trim takes the whole free part of each, most of it pages never touched, which costs nothing.
  eden_mark = *eden.end;
  old_mark = *old.end;
  old_top_at_start = *old.top;

  JavaVM *vm;
  jvmtiEnv *jvmti;
  if ((*env)->GetJavaVM(env, &vm) != 0 || (*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) return -3;
  jvmtiCapabilities caps;
  memset(&caps, 0, sizeof caps);
  caps.can_generate_garbage_collection_events = 1;
  jvmtiEventCallbacks callbacks;
  memset(&callbacks, 0, sizeof callbacks);
  callbacks.GarbageCollectionStart = gc_start;
  callbacks.GarbageCollectionFinish = gc_finish;
  if ((*jvmti)->AddCapabilities(jvmti, &caps) != JVMTI_ERROR_NONE
      || (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof callbacks) != JVMTI_ERROR_NONE
      || (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, NULL) != JVMTI_ERROR_NONE
      || (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, NULL) != JVMTI_ERROR_NONE) {
    return -3;
  }
  ready = 1;
  return (jlong)((*eden.end - *eden.bottom) + (*from.end - *from.bottom) + (*old.end - *old.bottom));
}

JNIEXPORT void JNICALL Java_dev_apksim_HeapTrim_nativeArm(JNIEnv *env, jclass cls, jboolean on) {
  atomic_store(&armed, on ? 1 : 0);
}

JNIEXPORT jlong JNICALL Java_dev_apksim_HeapTrim_nativeCollections(JNIEnv *env, jclass cls) {
  return atomic_load(&collections);
}

// Eden's size: how much garbage fills it, for HeapTrim's own young collection.
JNIEXPORT jlong JNICALL Java_dev_apksim_HeapTrim_nativeEdenBytes(JNIEnv *env, jclass cls) {
  return ready ? (jlong)(*eden.end - *eden.bottom) : 0;
}
