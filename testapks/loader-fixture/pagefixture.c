// A library linked for 4 KB pages (build.sh: libpagefixture4k.so and libpagefixturenorelro.so), for NativeLoaderTest.
// macOS maps 16 KB pages, none writable and executable at once, so the end of this library's code shares a page with
// what the linker put after it. In libpagefixture4k.so that is RELRO (a table of function pointers, 16 KB of it, so
// .data and .bss start past that page): apksim makes the page read+execute once relocated. In
// libpagefixturenorelro.so (-z norelro) it is writable data, which apksim moves to pages of its own and repoints the
// code at. Either way the code, relocated pointers, .data, .bss and an exported variable must all agree. On Linux and
// on a device both load as they are.
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
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

// The pointers are volatile objects, so they are read at run time rather than folded into &counter: each is a
// relocation (R_AARCH64_RELATIVE or a RELR entry) whose value points into .data.
static int counter = 5;                              // .data
static int *volatile counter_ptr = &counter;         // .data
static int *const volatile counter_ro = &counter;    // .data.rel.ro (RELRO unless -z norelro)
extern const char _DYNAMIC[];                        // the linker's: where this library's dynamic section is
static char scratch[4096];              // .bss
static char message[160];
__attribute__((visibility("default"))) int pagefixture_exported = 7;  // through the GOT, and dlsym

// dl_iterate_phdr (unwinders and crash reporters walk it) must describe the library as it is in memory: counter
// inside one of its PT_LOADs, and its PT_DYNAMIC where the dynamic section is.
struct phdr_seen { int found, counter_in_load, dynamic_right, pltgot_live; };

// DT_PLTGOT read from the PT_DYNAMIC dl_iterate_phdr gives, plus dlpi_addr, must be the live GOT: one of its jump
// slots holds memset as resolved (a stale copy holds the file's unrelocated words).
static void check_pltgot(struct dl_phdr_info *info, const ElfW(Dyn) *dyn, struct phdr_seen *seen) {
  uintptr_t pltgot = 0, pltrelsz = 0;
  for (const ElfW(Dyn) *e = dyn; e->d_tag != DT_NULL; e++) {
    if (e->d_tag == DT_PLTGOT) pltgot = e->d_un.d_ptr;
    if (e->d_tag == DT_PLTRELSZ) pltrelsz = e->d_un.d_val;
  }
  if (!pltgot) return;
  void *volatile want = (void *)memset;
  void *const *got = (void *const *)(info->dlpi_addr + pltgot);
  for (size_t i = 3; i < 3 + pltrelsz / sizeof(ElfW(Rela)); i++) {
    if (got[i] == want) seen->pltgot_live = 1;
  }
}

static int phdr_callback(struct dl_phdr_info *info, size_t size, void *data) {
  (void)size;
  struct phdr_seen *seen = data;
  uintptr_t c = (uintptr_t)&counter, dyn = (uintptr_t)_DYNAMIC;
  int mine = 0;
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *p = &info->dlpi_phdr[i];
    uintptr_t at = info->dlpi_addr + p->p_vaddr;
    if (p->p_type == PT_LOAD && c >= at && c < at + p->p_memsz) mine = seen->counter_in_load = 1;
    if (p->p_type == PT_DYNAMIC && at == dyn) seen->dynamic_right = 1;
  }
  for (int i = 0; mine && i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *p = &info->dlpi_phdr[i];
    if (p->p_type == PT_DYNAMIC) check_pltgot(info, (const ElfW(Dyn) *)(info->dlpi_addr + p->p_vaddr), seen);
  }
  if (mine) seen->found = 1;
  return mine;
}

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
  struct phdr_seen seen = {0, 0, 0, 0};
  dl_iterate_phdr(phdr_callback, &seen);
  if (!seen.found) return fail(env, "dl_iterate_phdr has no PT_LOAD holding .data");
  if (!seen.dynamic_right) return fail(env, "dl_iterate_phdr's PT_DYNAMIC is not where the dynamic section is");
  if (!seen.pltgot_live) return fail(env, "DT_PLTGOT through dl_iterate_phdr is not the live GOT");
  return fail(env, "ok");
}
