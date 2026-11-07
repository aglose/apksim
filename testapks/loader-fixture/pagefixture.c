// A library linked for 4 KB pages (build.sh: libpagefixture4k.so and libpagefixturenorelro.so), for NativeLoaderTest.
// macOS maps 16 KB pages, none writable and executable at once, so the end of this library's code shares a page with
// what the linker put after it. In libpagefixture4k.so that is RELRO (a table of function pointers, 16 KB of it, so
// .data and .bss start past that page): apksim makes the page read+execute once relocated. In
// libpagefixturenorelro.so (-z norelro) it is writable data, which apksim moves to pages of its own and repoints the
// code at. Either way the code, relocated pointers, .data, .bss and an exported variable must all agree. On Linux and
// on a device both load as they are.
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#ifndef FIXTURE_METHOD
#error "build.sh sets FIXTURE_METHOD and FIXTURE_LIB"
#endif
#define JNI_NAME2(m) Java_dev_apksim_composeapp_PageFixtures_##m
#define JNI_NAME(m) JNI_NAME2(m)
#define STR2(s) #s
#define STR(s) STR2(s)

static int add_one(int x) { return x + 1; }
static int twice(int x) { return x * 2; }

// 2048 relocated pointers: 16 KB of .data.rel.ro.
typedef int (*op)(int);
static op const table[2048] = {[0 ... 2046] = add_one, [2047] = twice};

static int counter = 5;                 // .data
static int *counter_ptr = &counter;     // .data, relocated to point into .data
static int *const counter_ro = &counter;  // .data.rel.ro (RELRO unless -z norelro)
static char scratch[4096];              // .bss
static char message[160];
__attribute__((visibility("default"))) int pagefixture_exported = 7;  // through the GOT, and dlsym

static jstring fail(JNIEnv *env, const char *what) {
  snprintf(message, sizeof message, "%s", what);
  return (*env)->NewStringUTF(env, message);
}

JNIEXPORT jstring JNICALL JNI_NAME(FIXTURE_METHOD)(JNIEnv *env, jobject self) {
  (void)self;
  op const *volatile t = table;  // read at run time, not folded by the compiler
  int sum = 0;
  for (int i = 0; i < 2048; i++) sum += t[i](1);
  // add_one 2047 times and twice once: 2047 * 2 + 2 = 4096.
  if (sum != 4096) return fail(env, "the function table gave the wrong sum");
  int *volatile p = counter_ptr, *volatile q = counter_ro;
  if (p != &counter || q != &counter) return fail(env, "a relocated pointer does not point at counter");
  *p += 1;
  *q += 1;
  counter += 1;
  if (counter != 8) return fail(env, "writes through pointers and directly disagree");
  counter = 5;
  for (size_t i = 0; i < sizeof scratch; i++) {
    if (scratch[i]) return fail(env, ".bss is not zero");
  }
  memset(scratch, 'x', sizeof scratch);
  if (scratch[4095] != 'x') return fail(env, ".bss did not take a write");
  memset(scratch, 0, sizeof scratch);
  void *h = dlopen(STR(FIXTURE_LIB), RTLD_NOW);
  int *volatile e = h ? dlsym(h, "pagefixture_exported") : NULL;
  if (e != &pagefixture_exported) return fail(env, "dlsym and the GOT disagree on an exported variable");
  pagefixture_exported++;
  if (*e != 8) return fail(env, "an exported variable did not take a write");
  pagefixture_exported = 7;
  return fail(env, "ok");
}
