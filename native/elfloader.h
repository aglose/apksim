#pragma once
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

#ifndef __APPLE__
// glibc before 2.38 has no strlcpy/strlcat.
#include <string.h>
static inline size_t apksim_strlcpy(char *d, const char *s, size_t n) {
  size_t l = strlen(s);
  if (n) {
    size_t c = l < n - 1 ? l : n - 1;
    memcpy(d, s, c);
    d[c] = 0;
  }
  return l;
}
static inline size_t apksim_strlcat(char *d, const char *s, size_t n) {
  size_t dl = strnlen(d, n);
  return dl == n ? n + strlen(s) : dl + apksim_strlcpy(d + dl, s, n - dl);
}
#define strlcpy apksim_strlcpy
#define strlcat apksim_strlcat
#endif

typedef struct Module {
  struct Module *next;
  char name[128];
  uintptr_t base;
  size_t size;
  const void *symtab;
  const char *strtab;
  const uint32_t *gnu_hash;
  const uint32_t *sysv_hash;
  const void *phdr;
  void *phdr_copy;
  int phnum;
  int pending_irelative;
  const void *tls_image;
  size_t tls_filesz;
  size_t tls_memsz;
  pthread_key_t tls_key;
  int tls_key_ready;
  // arm64 Linux only: the page this module's code sees as its thread pointer (see patch_thread_pointer).
  uintptr_t tp;
  // macOS only: writable data moved off a 16 KB page it shared with code (elfloader.c, plan_data_move): what the
  // file puts at [moved_lo, moved_hi) lives moved_by bytes further on.
  uintptr_t moved_lo, moved_hi;
  uintptr_t moved_by;
  int symbols_moved;  // the dynamic symbols' values were moved too: they are addresses as they are
} Module;

// Bounds of the address range all loaded ELF code lives in; read by the JNI bridge's assembly.
extern uintptr_t elf_arena_lo, elf_arena_hi;

Module *elf_load(const char *path, char *err, size_t errlen);
void elf_set_search_dir(const char *dir);
Module *elf_modules(void);
Module *elf_loaded(const char *basename);
Module *elf_module_for(const void *addr);
void *elf_find(Module *m, const char *name);
void *elf_find_global(const char *name);

void tlsdesc_resolver(void);
void *tls_descriptor(Module *m, uint64_t offset);

// bionic.c: the Android C library as seen by loaded code.
void *bionic_lookup(const char *name);
void *missing_stub(const char *name);

// jnigraphics.c: libjnigraphics (AndroidBitmap_*), through dev.apksim.NativeBitmaps. Init takes a JNIEnv *.
void jnigraphics_init(void *env);
void *jnigraphics_lookup(const char *name);
