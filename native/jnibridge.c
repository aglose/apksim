// JNI for loaded ELF code. The JNI function table is the same on both platforms, with one exception:
// the Call*Method families take C varargs (or a va_list), and those are laid out differently. The
// process-wide table is swapped for one whose variadic entries check who is calling: Android code gets
// its arguments translated, everything else goes straight to the JVM's original function.
#include <jni.h>
#include <jvmti.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "elfloader.h"

uint64_t aapcs_gr(void *v);
double aapcs_fp(void *v);

const struct JNINativeInterface_ *jni_orig;
static jvmtiEnv *jvmti;
static JavaVM *vm;

// Indices into the JNI table (Java SE spec). Each family is {variadic, V, A} for ten return types.
enum { NEW_OBJECT = 28, CALL_METHOD = 34, CALL_NONVIRTUAL = 64, CALL_STATIC = 114, FAMILY_SIZE = 30 };

typedef struct {
  uint64_t x[8];
  uint8_t q[8][16];
} Saved;

typedef struct {
  char *stack;
  char *gr_top;
  char *vr_top;
  int gr_offs;
  int vr_offs;
} AVaList;

static void fill_args(jmethodID mid, AVaList *v, jvalue *out, int max) {
  char *sig = NULL;
  if ((*jvmti)->GetMethodName(jvmti, mid, NULL, &sig, NULL) != JVMTI_ERROR_NONE || sig == NULL) {
    // JVMTI's live phase is over: the VM is exiting. A native thread calling into Java now (WebRTC's, still starting
    // when a short test run ends) read sig + 1 and crashed the exit with SIGSEGV, failing a run whose tests passed.
    // The JVM blocks a thread that comes back from native during exit; do the same until the process is gone.
    for (;;) pause();
  }
  const char *p = sig + 1;
  int n = 0;
  while (*p != ')' && n < max) {
    switch (*p) {
      case 'F':
        out[n++].f = (float)aapcs_fp(v);
        p++;
        break;
      case 'D':
        out[n++].d = aapcs_fp(v);
        p++;
        break;
      case 'J':
        out[n++].j = (jlong)aapcs_gr(v);
        p++;
        break;
      case 'L':
        out[n++].l = (jobject)aapcs_gr(v);
        while (*p != ';') p++;
        p++;
        break;
      case '[':
        out[n++].l = (jobject)aapcs_gr(v);
        while (*p == '[') p++;
        if (*p == 'L') {
          while (*p != ';') p++;
        }
        p++;
        break;
      case 'Z':
        out[n++].z = (jboolean)aapcs_gr(v);
        p++;
        break;
      case 'B':
        out[n++].b = (jbyte)aapcs_gr(v);
        p++;
        break;
      case 'C':
        out[n++].c = (jchar)aapcs_gr(v);
        p++;
        break;
      case 'S':
        out[n++].s = (jshort)aapcs_gr(v);
        p++;
        break;
      default:
        out[n++].i = (jint)aapcs_gr(v);
        p++;
        break;
    }
  }
  (*jvmti)->Deallocate(jvmti, (unsigned char *)sig);
}

#define MAX_ARGS 64

// Entered from the assembly stub for calls made by ELF code. Returns the integer result; a floating
// result is written back into the saved q0, which the stub restores.
uint64_t jni_from_elf(int idx, Saved *r, char *overflow) {
  JNIEnv *env = (JNIEnv *)r->x[0];
  void **table = (void **)jni_orig;
  int family, offset;
  int fixed;  // How many integer registers hold fixed arguments, env included.
  if (idx >= CALL_STATIC) {
    family = CALL_STATIC;
    fixed = 3;
  } else if (idx >= CALL_NONVIRTUAL) {
    family = CALL_NONVIRTUAL;
    fixed = 4;
  } else if (idx >= CALL_METHOD) {
    family = CALL_METHOD;
    fixed = 3;
  } else {
    family = NEW_OBJECT;
    fixed = 3;
  }
  offset = idx - family;
  int is_v = offset % 3 == 1;
  jmethodID mid = (jmethodID)r->x[fixed - 1];
  AVaList local;
  AVaList *list;
  if (is_v) {
    list = (AVaList *)r->x[fixed];
    local = *list;  // Do not consume the caller's list.
  } else {
    local.stack = overflow;
    local.gr_top = (char *)&r->x[8];
    local.gr_offs = -(8 - fixed) * 8;
    local.vr_top = (char *)&r->q[8];
    local.vr_offs = -8 * 16;
  }
  jvalue args[MAX_ARGS];
  fill_args(mid, &local, args, MAX_ARGS);
  void *a_fn = table[idx - offset % 3 + 2];
  if (family == NEW_OBJECT) {
    return (uint64_t)((jobject(*)(JNIEnv *, jclass, jmethodID, const jvalue *))a_fn)(env, (jclass)r->x[1], mid, args);
  }
  int ret = offset / 3;  // Object Boolean Byte Char Short Int Long Float Double Void.
#define INVOKE(T)                                                                                                          \
  (family == CALL_NONVIRTUAL ? ((T(*)(JNIEnv *, jobject, jclass, jmethodID, const jvalue *))a_fn)(env, (jobject)r->x[1], (jclass)r->x[2], mid, args) \
                             : ((T(*)(JNIEnv *, jobject, jmethodID, const jvalue *))a_fn)(env, (jobject)r->x[1], mid, args))
  switch (ret) {
    case 0: return (uint64_t)INVOKE(jobject);
    case 1: return (uint64_t)INVOKE(jboolean);
    case 2: return (uint64_t)(int64_t)INVOKE(jbyte);
    case 3: return (uint64_t)INVOKE(jchar);
    case 4: return (uint64_t)(int64_t)INVOKE(jshort);
    case 5: return (uint64_t)(int64_t)INVOKE(jint);
    case 6: return (uint64_t)INVOKE(jlong);
    case 7: {
      jfloat f = INVOKE(jfloat);
      memcpy(r->q[0], &f, sizeof f);
      return 0;
    }
    case 8: {
      jdouble d = INVOKE(jdouble);
      memcpy(r->q[0], &d, sizeof d);
      return 0;
    }
    default:
      if (family == CALL_NONVIRTUAL) {
        ((void (*)(JNIEnv *, jobject, jclass, jmethodID, const jvalue *))a_fn)(env, (jobject)r->x[1], (jclass)r->x[2], mid, args);
      } else {
        ((void (*)(JNIEnv *, jobject, jmethodID, const jvalue *))a_fn)(env, (jobject)r->x[1], mid, args);
      }
      return 0;
  }
}

// One stub per table slot: x16 carries the slot. Calls from outside the ELF arena fall through to the
// original function with every register intact, so the host's own native code is unaffected.
__asm__(
    ".text\n.p2align 2\n_jni_common:\n"
    "  adrp x9, _elf_arena_lo@PAGE\n  ldr x9, [x9, _elf_arena_lo@PAGEOFF]\n"
    "  adrp x10, _elf_arena_hi@PAGE\n  ldr x10, [x10, _elf_arena_hi@PAGEOFF]\n"
    "  cmp lr, x9\n  b.lo 1f\n  cmp lr, x10\n  b.hs 1f\n"
    "  sub sp, sp, #208\n  stp x29, x30, [sp, #192]\n  add x29, sp, #192\n"
    "  stp x0, x1, [sp, #0]\n  stp x2, x3, [sp, #16]\n  stp x4, x5, [sp, #32]\n  stp x6, x7, [sp, #48]\n"
    "  stp q0, q1, [sp, #64]\n  stp q2, q3, [sp, #96]\n  stp q4, q5, [sp, #128]\n  stp q6, q7, [sp, #160]\n"
    "  mov x0, x16\n  mov x1, sp\n  add x2, sp, #208\n  bl _jni_from_elf\n"
    "  ldr q0, [sp, #64]\n  ldp x29, x30, [sp, #192]\n  add sp, sp, #208\n  ret\n"
    "1:\n  adrp x9, _jni_orig@PAGE\n  ldr x9, [x9, _jni_orig@PAGEOFF]\n  ldr x9, [x9, x16, lsl #3]\n  br x9\n"
    ".p2align 2\n.globl _jni_stubs\n_jni_stubs:\n"
    ".set i, 0\n.rept 256\n  mov x16, #i\n  b _jni_common\n.set i, i+1\n.endr\n");
extern char jni_stubs[];


// ---------------------------------------------------------------- Java calling ELF natives

// The JVM calls a native method with Apple's arm64 convention and ELF code expects AAPCS64's. They agree
// on registers but not on arguments that spill to the stack: Apple packs them at their natural size and
// alignment, AAPCS64 gives each an 8-byte slot. A method whose stack arguments differ gets a trampoline
// that rebuilds them; for example libwebrtc's JavaI420Buffer.nativeCropAndScaleI420 (19 arguments)
// otherwise read garbage after the eighth and crashed.
#define MAX_TRAMPOLINES 512
typedef struct { void *target; int count; uint8_t size[48], sign[48]; } Layout;
static Layout layouts[MAX_TRAMPOLINES];
static int layout_count;

uint64_t jt_stack_bytes(long slot) { return ((uint64_t)layouts[slot].count * 8 + 15) & ~15UL; }

void *jt_fill(long slot, const char *apple, uint64_t *dst) {
  Layout *l = &layouts[slot];
  size_t off = 0;
  for (int i = 0; i < l->count; i++) {
    size_t sz = l->size[i];
    off = (off + sz - 1) & ~(sz - 1);
    uint64_t v = 0;
    memcpy(&v, apple + off, sz);
    if (l->sign[i] && sz < 8) {
      int shift = 64 - 8 * (int)sz;
      v = (uint64_t)((int64_t)(v << shift) >> shift);
    }
    dst[i] = v;
    off += sz;
  }
  return l->target;
}

__asm__(
    ".text\n.p2align 2\n_jt_common:\n"
    "  stp x29, x30, [sp, #-16]!\n  mov x29, sp\n  sub sp, sp, #192\n"
    "  stp x0, x1, [sp, #0]\n  stp x2, x3, [sp, #16]\n  stp x4, x5, [sp, #32]\n  stp x6, x7, [sp, #48]\n"
    "  stp q0, q1, [sp, #64]\n  stp q2, q3, [sp, #96]\n  stp q4, q5, [sp, #128]\n  stp q6, q7, [sp, #160]\n"
    "  stp x16, x17, [sp, #-16]!\n  mov x0, x16\n  bl _jt_stack_bytes\n  ldp x16, x17, [sp], #16\n"
    "  sub sp, sp, x0\n  mov x0, x16\n  add x1, x29, #16\n  mov x2, sp\n  bl _jt_fill\n  mov x16, x0\n"
    "  sub x17, x29, #192\n"
    "  ldp x0, x1, [x17, #0]\n  ldp x2, x3, [x17, #16]\n  ldp x4, x5, [x17, #32]\n  ldp x6, x7, [x17, #48]\n"
    "  ldp q0, q1, [x17, #64]\n  ldp q2, q3, [x17, #96]\n  ldp q4, q5, [x17, #128]\n  ldp q6, q7, [x17, #160]\n"
    "  blr x16\n  mov sp, x29\n  ldp x29, x30, [sp], #16\n  ret\n"
    ".p2align 2\n.globl _jt_stubs\n_jt_stubs:\n"
    ".set i, 0\n.rept 512\n  mov x16, #i\n  b _jt_common\n.set i, i+1\n.endr\n");
extern char jt_stubs[];

// [target] itself when the two conventions put this signature's arguments in the same places.
static void *native_entry(const char *sig, void *target) {
  if (!sig || !target) return target;
  int gp = 2, fp = 0, n = 0, differs = 0;  // JNIEnv* and the class or receiver come first.
  uint8_t size[48], sign[48];
  for (const char *p = sig + 1; *p && *p != ')'; p++) {
    int isfp = 0, sz = 8, sg = 0;
    switch (*p) {
      case 'Z': sz = 1; break;
      case 'B': sz = 1; sg = 1; break;
      case 'C': sz = 2; break;
      case 'S': sz = 2; sg = 1; break;
      case 'I': sz = 4; sg = 1; break;
      case 'J': break;
      case 'F': sz = 4; isfp = 1; break;
      case 'D': isfp = 1; break;
      case '[':
        while (*p == '[') p++;
        if (*p == 'L') while (*p && *p != ';') p++;
        break;
      case 'L': while (*p && *p != ';') p++; break;
      default: return target;
    }
    if (isfp ? fp++ < 8 : gp++ < 8) continue;
    if (n == 48) return target;
    size[n] = (uint8_t)sz;
    sign[n] = (uint8_t)sg;
    if (sz != 8) differs = 1;
    n++;
  }
  if (!differs) return target;
  if (layout_count == MAX_TRAMPOLINES) {
    fprintf(stderr, "[apksim] out of native trampolines; %s keeps the host convention\n", sig);
    return target;
  }
  Layout *l = &layouts[layout_count];
  l->target = target;
  l->count = n;
  memcpy(l->size, size, (size_t)n);
  memcpy(l->sign, sign, (size_t)n);
  return jt_stubs + 8 * layout_count++;
}

// RegisterNatives from ELF code (JNI_OnLoad): methods implemented in the ELF arena get the same trampolines.
static jint s_register_natives(JNIEnv *env, jclass c, const JNINativeMethod *methods, jint n) {
  JNINativeMethod *copy = malloc(sizeof *copy * (size_t)(n > 0 ? n : 1));
  for (jint i = 0; i < n; i++) {
    copy[i] = methods[i];
    uintptr_t f = (uintptr_t)methods[i].fnPtr;
    if (f >= elf_arena_lo && f < elf_arena_hi) copy[i].fnPtr = native_entry(methods[i].signature, methods[i].fnPtr);
  }
  jint r = jni_orig->RegisterNatives(env, c, copy, n);
  free(copy);
  return r;
}

static void install_table(JNIEnv *env) {
  static int done;
  if (done) return;
  done = 1;
  (*env)->GetJavaVM(env, &vm);
  (*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2);
  jniNativeInterface *orig = NULL;
  (*jvmti)->GetJNIFunctionTable(jvmti, &orig);
  jni_orig = orig;
  jniNativeInterface *copy = malloc(sizeof *copy);
  memcpy(copy, orig, sizeof *copy);
  void **slots = (void **)copy;
  int families[][2] = {{NEW_OBJECT, 3}, {CALL_METHOD, FAMILY_SIZE}, {CALL_NONVIRTUAL, FAMILY_SIZE}, {CALL_STATIC, FAMILY_SIZE}};
  for (int f = 0; f < 4; f++) {
    for (int k = 0; k < families[f][1]; k++) {
      int slot = families[f][0] + k;
      if (k % 3 != 2) slots[slot] = jni_stubs + 8 * slot;  // variadic and V; the A form needs no help
    }
  }
  copy->RegisterNatives = s_register_natives;
  (*jvmti)->SetJNIFunctionTable(jvmti, copy);
}

static char search[1024];
const char *elf_search_dir(void) { return search; }

extern volatile int net_offline;

JNIEXPORT void JNICALL Java_dev_apksim_ElfLoader_nativeSetOffline(JNIEnv *env, jclass c, jboolean offline) {
  net_offline = offline;
}

JNIEXPORT jlong JNICALL Java_dev_apksim_ElfLoader_nativeLoad(JNIEnv *env, jclass c, jstring jpath, jstring jdir) {
  install_table(env);
  jnigraphics_init(env);
  const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
  strlcpy(search, dir, sizeof search);
  elf_set_search_dir(dir);
  (*env)->ReleaseStringUTFChars(env, jdir, dir);
  const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
  const char *b = strrchr(path, '/');
  int already = elf_loaded(b ? b + 1 : path) != NULL;
  char err[512] = "";
  Module *m = elf_load(path, err, sizeof err);
  (*env)->ReleaseStringUTFChars(env, jpath, path);
  if (!m) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError"), err);
    return 0;
  }
  if (!already) {
    jint (*onload)(JavaVM *, void *) = elf_find(m, "JNI_OnLoad");
    if (onload) onload(vm, NULL);
  }
  return (jlong)m;
}

// Exported Java_* names, newline-separated, for the Java side to bind.
JNIEXPORT jstring JNICALL Java_dev_apksim_ElfLoader_nativeJavaSymbols(JNIEnv *env, jclass c, jlong handle) {
  Module *m = (Module *)handle;
  size_t count = 0;
  if (m->gnu_hash) {
    const uint32_t *h = m->gnu_hash;
    uint32_t nbuckets = h[0], symoff = h[1], bloom = h[2];
    const uint32_t *buckets = (const uint32_t *)((const uint64_t *)(h + 4) + bloom);
    const uint32_t *chain = buckets + nbuckets;
    uint32_t last = 0;
    for (uint32_t i = 0; i < nbuckets; i++) {
      if (buckets[i] > last) last = buckets[i];
    }
    if (last >= symoff) {
      while (!(chain[last - symoff] & 1)) last++;
      count = last + 1;
    } else {
      count = symoff;
    }
  } else if (m->sysv_hash) {
    count = m->sysv_hash[1];
  }
  typedef struct { uint32_t name; uint8_t info, other; uint16_t shndx; uint64_t value, size; } Sym;
  const Sym *syms = m->symtab;
  size_t cap = 4096, len = 0;
  char *out = malloc(cap);
  out[0] = 0;
  for (size_t i = 1; i < count; i++) {
    if (!syms[i].shndx) continue;
    const char *n = m->strtab + syms[i].name;
    if (strncmp(n, "Java_", 5)) continue;
    size_t l = strlen(n);
    if (len + l + 2 > cap) {
      cap = (len + l + 2) * 2;
      out = realloc(out, cap);
    }
    memcpy(out + len, n, l);
    len += l;
    out[len++] = '\n';
    out[len] = 0;
  }
  jstring r = (*env)->NewStringUTF(env, out);
  free(out);
  return r;
}

JNIEXPORT void JNICALL Java_dev_apksim_ElfLoader_nativeBind(JNIEnv *env, jclass c, jlong handle, jclass target, jstring jname, jstring jsig, jstring jsym) {
  const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
  const char *sig = (*env)->GetStringUTFChars(env, jsig, NULL);
  const char *sym = (*env)->GetStringUTFChars(env, jsym, NULL);
  JNINativeMethod method = {(char *)name, (char *)sig, native_entry(sig, elf_find((Module *)handle, sym))};
  (*env)->RegisterNatives(env, target, &method, 1);
  (*env)->ReleaseStringUTFChars(env, jname, name);
  (*env)->ReleaseStringUTFChars(env, jsig, sig);
  (*env)->ReleaseStringUTFChars(env, jsym, sym);
}
