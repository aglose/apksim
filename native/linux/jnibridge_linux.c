// JNI for loaded x86_64 ELF code on Linux. Android and the host JVM share the SysV calling convention, so
// unlike ../jnibridge.c (macOS) nothing needs translating: the JVM's own JNI table and function pointers work.
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "../elfloader.h"

static JavaVM *vm;
static char search[1024];
const char *elf_search_dir(void) { return search; }

JNIEXPORT jlong JNICALL Java_dev_apksim_ElfLoader_nativeLoad(JNIEnv *env, jclass c, jstring jpath, jstring jdir) {
  if (!vm) (*env)->GetJavaVM(env, &vm);
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
  JNINativeMethod method = {(char *)name, (char *)sig, elf_find((Module *)handle, sym)};
  (*env)->RegisterNatives(env, target, &method, 1);
  (*env)->ReleaseStringUTFChars(env, jname, name);
  (*env)->ReleaseStringUTFChars(env, jsig, sig);
  (*env)->ReleaseStringUTFChars(env, jsym, sym);
}
