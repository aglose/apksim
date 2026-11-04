// Loads Android ELF shared objects into this process: arm64 ones on macOS, x86_64 ones on Linux. The CPU is
// the same, so code runs natively; what needs translating is the loader, the C library (bionic.c on macOS,
// linux/bionic_linux.c on Linux) and, on macOS, the variadic ABI.
#include "elfloader.h"

#include <dlfcn.h>
#include <fcntl.h>
#ifdef __APPLE__
#include <libkern/OSCacheControl.h>
#endif
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define PT_LOAD 1
#define PT_DYNAMIC 2
#define PT_TLS 7
#define DT_NULL 0
#define DT_NEEDED 1
#define DT_PLTRELSZ 2
#define DT_HASH 4
#define DT_STRTAB 5
#define DT_SYMTAB 6
#define DT_RELA 7
#define DT_RELASZ 8
#define DT_INIT 12
#define DT_JMPREL 23
#define DT_INIT_ARRAY 25
#define DT_INIT_ARRAYSZ 27
#define DT_RELRSZ 35
#define DT_RELR 36
#define DT_GNU_HASH 0x6ffffef5
#define DT_ANDROID_RELA 0x60000011
#define DT_ANDROID_RELASZ 0x60000012
#if defined(__x86_64__)
#define ELF_MACHINE 62
#define ELF_ARCH "x86_64"
#define R_ABS64 1
#define R_GLOB_DAT 6
#define R_JUMP_SLOT 7
#define R_RELATIVE 8
#define R_DTPMOD64 16
#define R_DTPOFF64 17
#define R_TLS_TPREL 18
#define R_TLSDESC 36
#define R_IRELATIVE 37
#else
#define ELF_MACHINE 183
#define ELF_ARCH "arm64"
#define R_ABS64 257
#define R_GLOB_DAT 1025
#define R_JUMP_SLOT 1026
#define R_RELATIVE 1027
#define R_DTPMOD64 1028
#define R_DTPOFF64 1029
#define R_TLS_TPREL 1030
#define R_TLSDESC 1031
#define R_IRELATIVE 1032
#endif

typedef struct { uint32_t name; uint8_t info, other; uint16_t shndx; uint64_t value, size; } Sym;
typedef struct { uint64_t offset, info; int64_t addend; } Rela;
typedef struct { uint32_t type, flags; uint64_t offset, vaddr, paddr, filesz, memsz, align; } Phdr;

#define ARENA_SIZE (1ULL << 33)
uintptr_t elf_arena_lo, elf_arena_hi;
static uintptr_t arena_next;

static Module *modules;
#if !defined(PTHREAD_RECURSIVE_MUTEX_INITIALIZER) && defined(PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP)
#define PTHREAD_RECURSIVE_MUTEX_INITIALIZER PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP
#endif
static pthread_mutex_t load_lock = PTHREAD_RECURSIVE_MUTEX_INITIALIZER;

static void *lookup_in(Module *m, const char *name);

Module *elf_modules(void) { return modules; }

static int init_arena(void) {
  if (elf_arena_lo) return 0;
  void *p = mmap(NULL, ARENA_SIZE, PROT_NONE, MAP_PRIVATE | MAP_ANON, -1, 0);
  if (p == MAP_FAILED) return -1;
  elf_arena_lo = (uintptr_t)p;
  elf_arena_hi = elf_arena_lo + ARENA_SIZE;
  arena_next = elf_arena_lo;
  return 0;
}

static uint32_t gnu_hash(const char *s) {
  uint32_t h = 5381;
  for (; *s; s++) h = h * 33 + (uint8_t)*s;
  return h;
}

static uint32_t elf_hash(const char *s) {
  uint32_t h = 0, g;
  for (; *s; s++) {
    h = (h << 4) + (uint8_t)*s;
    g = h & 0xf0000000;
    if (g) h ^= g >> 24;
    h &= ~g;
  }
  return h;
}

static const Sym *find_sym(Module *m, const char *name) {
  if (m->gnu_hash) {
    const uint32_t *h = m->gnu_hash;
    uint32_t nbuckets = h[0], symoff = h[1], bloom_size = h[2];
    const uint64_t *bloom = (const uint64_t *)(h + 4);
    const uint32_t *buckets = (const uint32_t *)(bloom + bloom_size);
    const uint32_t *chain = buckets + nbuckets;
    uint32_t hash = gnu_hash(name);
    uint64_t word = bloom[(hash / 64) % bloom_size];
    uint64_t mask = (1ULL << (hash % 64)) | (1ULL << ((hash >> h[3]) % 64));
    if ((word & mask) != mask) return NULL;
    uint32_t i = buckets[hash % nbuckets];
    if (i < symoff) return NULL;
    for (;; i++) {
      uint32_t c = chain[i - symoff];
      if ((c | 1) == (hash | 1)) {
        const Sym *s = &((const Sym *)m->symtab)[i];
        if (s->shndx != 0 && !strcmp(m->strtab + s->name, name)) return s;
      }
      if (c & 1) return NULL;
    }
  }
  if (m->sysv_hash) {
    const uint32_t *h = m->sysv_hash;
    uint32_t nbucket = h[0];
    const uint32_t *bucket = h + 2;
    const uint32_t *chain = bucket + nbucket;
    for (uint32_t i = bucket[elf_hash(name) % nbucket]; i; i = chain[i]) {
      const Sym *s = &((const Sym *)m->symtab)[i];
      if (s->shndx != 0 && !strcmp(m->strtab + s->name, name)) return s;
    }
  }
  return NULL;
}

static void *lookup_in(Module *m, const char *name) {
  const Sym *s = find_sym(m, name);
  return s ? (void *)(m->base + s->value) : NULL;
}

void *elf_find(Module *m, const char *name) { return lookup_in(m, name); }

void *elf_find_global(const char *name) {
  for (Module *m = modules; m; m = m->next) {
    void *p = lookup_in(m, name);
    if (p) return p;
  }
  return NULL;
}

Module *elf_module_for(const void *addr) {
  uintptr_t a = (uintptr_t)addr;
  for (Module *m = modules; m; m = m->next) {
    if (a >= m->base && a < m->base + m->size) return m;
  }
  return NULL;
}

/** Resolves an import: other loaded libraries, then the C library shim, then the host. */
static void *resolve(Module *self, const Sym *sym, int is_call) {
  const char *name = self->strtab + sym->name;
  if (sym->shndx != 0) return (void *)(self->base + sym->value);
  void *p = elf_find_global(name);
  if (p) return p;
  p = bionic_lookup(name);
  if (p) return p;
  if ((sym->info >> 4) == 2 /* weak */) return NULL;
  return missing_stub(name);
}

static uint64_t sleb(const uint8_t **pp) {
  const uint8_t *p = *pp;
  uint64_t r = 0;
  int s = 0;
  uint8_t b;
  do {
    b = *p++;
    r |= (uint64_t)(b & 0x7f) << s;
    s += 7;
  } while (b & 0x80);
  if (s < 64 && (b & 0x40)) r |= -(1ULL << s);
  *pp = p;
  return r;
}

static void apply_rela(Module *m, const Rela *r) {
  uint32_t type = (uint32_t)r->info;
  uint32_t symi = (uint32_t)(r->info >> 32);
  uint64_t *where = (uint64_t *)(m->base + r->offset);
  const Sym *sym = symi ? &((const Sym *)m->symtab)[symi] : NULL;
  switch (type) {
    case 0:
      break;
    case R_RELATIVE:
      *where = m->base + r->addend;
      break;
    case R_ABS64:
    case R_GLOB_DAT:
    case R_JUMP_SLOT: {
      void *t = resolve(m, sym, type == R_JUMP_SLOT);
      *where = t ? (uint64_t)t + r->addend : 0;
      break;
    }
#ifdef __x86_64__
    // General-dynamic TLS: __tls_get_addr ({module, offset}) finds this thread's block (elf_tls_get_addr).
    case R_DTPMOD64:
      *where = (uint64_t)m;
      break;
    case R_DTPOFF64:
      *where = (sym && sym->shndx ? sym->value : 0) + r->addend;
      break;
#endif
    case R_TLSDESC: {
      // Each thread's copy of the module's TLS block lives under a pthread key.
      uint64_t offset = (sym && sym->shndx ? sym->value : 0) + r->addend;
      where[0] = (uint64_t)tlsdesc_resolver;
      where[1] = (uint64_t)tls_descriptor(m, offset);
      break;
    }
    case R_IRELATIVE:
      m->pending_irelative = 1;
      *where = ((uint64_t (*)(void))(m->base + r->addend))();
      break;
    case R_TLS_TPREL:
      fprintf(stderr, "[apksim] %s: static TLS relocation not supported\n", m->name);
      break;
    default:
      fprintf(stderr, "[apksim] %s: unsupported relocation %u\n", m->name, type);
      break;
  }
}

// Android's compact "APS2" relocation encoding.
static void apply_android_rela(Module *m, const uint8_t *p, size_t size) {
  if (size < 4 || memcmp(p, "APS2", 4)) {
    fprintf(stderr, "[apksim] %s: bad packed relocations\n", m->name);
    return;
  }
  p += 4;
  uint64_t count = sleb(&p);
  uint64_t offset = sleb(&p);
  uint64_t info = 0;
  int64_t addend = 0;
  uint64_t done = 0;
  while (done < count) {
    uint64_t group = sleb(&p);
    uint64_t flags = sleb(&p);
    uint64_t group_delta = 0;
    if (flags & 2) group_delta = sleb(&p);
    if (flags & 1) info = sleb(&p);
    if ((flags & 8) && (flags & 4)) addend += sleb(&p);
    if (!(flags & 8)) addend = 0;
    for (uint64_t i = 0; i < group; i++) {
      offset += (flags & 2) ? group_delta : sleb(&p);
      if (!(flags & 1)) info = sleb(&p);
      if ((flags & 8) && !(flags & 4)) addend += sleb(&p);
      Rela r = {offset, info, addend};
      apply_rela(m, &r);
    }
    done += group;
  }
}

static void apply_relr(Module *m, const uint64_t *r, size_t bytes) {
  uint64_t *where = NULL;
  for (size_t i = 0; i < bytes / 8; i++) {
    uint64_t e = r[i];
    if (!(e & 1)) {
      where = (uint64_t *)(m->base + e);
      *where++ += m->base;
    } else {
      for (int j = 0; (e >>= 1); j++) {
        if (e & 1) where[j] += m->base;
      }
      where += 63;
    }
  }
}

#ifdef __APPLE__
// Android code reads its thread pointer from TPIDR_EL0 (stack protector, TLS). macOS keeps that in
// TPIDRRO_EL0, so patch the instruction. Stack-guard reads then see a stable per-thread value.
static void patch_thread_pointer(uint8_t *start, size_t size) {
  uint32_t *code = (uint32_t *)start;
  size_t n = size / 4;
  for (size_t i = 0; i < n; i++) {
    if ((code[i] & 0xffffffe0) == 0xd53bd040) code[i] = 0xd53bd060 | (code[i] & 0x1f);
  }
}
#endif

// Copies a range's current contents into fresh anonymous memory at the same address (a file-backed range the
// system will not make executable).
static int make_anonymous(uintptr_t from, size_t len) {
  void *copy = malloc(len);
  if (!copy) return -1;
  memcpy(copy, (void *)from, len);
  int failed = mmap((void *)from, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON | MAP_FIXED, -1, 0) == MAP_FAILED;
  if (!failed) memcpy((void *)from, copy, len);
  free(copy);
  return failed ? -1 : 0;
}

// Puts a PT_LOAD segment's file bytes at base + vaddr (the arena there is already read-write anonymous memory). On
// macOS the whole pages of a code segment are mapped from the file instead of copied: pages nothing writes to stay
// clean file pages the system can drop and read again, rather than dirty memory for the life of the process (most of
// a large library's code; the rest are the pages whose thread-pointer reads get patched). The file must not be
// rewritten in place while loaded: ElfLoader replaces libraries by renaming a new file over them.
static void load_segment(uintptr_t base, const Phdr *p, const uint8_t *file, int fd, size_t page) {
  uintptr_t seg = base + p->vaddr, end = seg + p->filesz;
#ifdef __APPLE__
  uintptr_t from = (seg + page - 1) & ~(page - 1), to = end & ~(page - 1);
  if ((p->flags & 1) && !(p->flags & 2) && p->offset % page == p->vaddr % page && to > from) {
    off_t at = (off_t)(p->offset + (from - seg));
    if (mmap((void *)from, to - from, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_FIXED, fd, at) != MAP_FAILED) {
      memcpy((void *)seg, file + p->offset, from - seg);
      memcpy((void *)to, file + p->offset + (to - seg), end - to);
      return;
    }
    // A failed MAP_FIXED may have taken the range's old mapping with it.
    mmap((void *)from, to - from, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON | MAP_FIXED, -1, 0);
  }
#endif
  memcpy((void *)seg, file + p->offset, p->filesz);
}

static int is_system_lib(const char *n) {
  static const char *sys[] = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so", "libGLESv1_CM.so",
                              "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libjnigraphics.so", "libz.so", "libstdc++.so",
                              "libmediandk.so", "libaaudio.so", "libnativewindow.so", NULL};
  for (int i = 0; sys[i]; i++) {
    if (!strcmp(n, sys[i])) return 1;
  }
  return 0;
}

static char search_dir[1024];

void elf_set_search_dir(const char *dir) { strlcpy(search_dir, dir, sizeof search_dir); }

Module *elf_loaded(const char *basename) {
  for (Module *m = modules; m; m = m->next) {
    if (!strcmp(m->name, basename)) return m;
  }
  return NULL;
}

Module *elf_load(const char *path, char *err, size_t errlen) {
  pthread_mutex_lock(&load_lock);
  Module *result = NULL;
  const char *base_name = strrchr(path, '/');
  base_name = base_name ? base_name + 1 : path;
  if ((result = elf_loaded(base_name))) goto done;
  if (init_arena()) {
    snprintf(err, errlen, "cannot reserve address space");
    goto done;
  }
  int fd = open(path, O_RDONLY);
  if (fd < 0) {
    snprintf(err, errlen, "cannot open %s", path);
    goto done;
  }
  struct stat st;
  fstat(fd, &st);
  uint8_t *file = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  if (file == MAP_FAILED || memcmp(file, "\177ELF", 4) || file[4] != 2 || *(uint16_t *)(file + 18) != ELF_MACHINE) {
    close(fd);
    snprintf(err, errlen, "%s is not an " ELF_ARCH " ELF", path);
    goto done;
  }
  const Phdr *ph = (const Phdr *)(file + *(uint64_t *)(file + 32));
  int phnum = *(uint16_t *)(file + 56);
  uint64_t max_va = 0;
  const Phdr *dyn = NULL;
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type == PT_LOAD && ph[i].vaddr + ph[i].memsz > max_va) max_va = ph[i].vaddr + ph[i].memsz;
    if (ph[i].type == PT_DYNAMIC) dyn = &ph[i];
  }
#ifdef __APPLE__
  size_t page = 16384;
#else
  size_t page = (size_t)sysconf(_SC_PAGESIZE);
#endif
  size_t span = (max_va + page - 1) & ~(page - 1);
  uintptr_t base = arena_next;
  if (base + span > elf_arena_hi) {
    close(fd);
    snprintf(err, errlen, "arena exhausted");
    goto done;
  }
  arena_next = base + span + page;
  if (mprotect((void *)base, span, PROT_READ | PROT_WRITE)) {
    close(fd);
    snprintf(err, errlen, "mprotect failed");
    goto done;
  }
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type == PT_LOAD) load_segment(base, &ph[i], file, fd, page);
  }
  close(fd);
  Module *m = calloc(1, sizeof(Module));
  strlcpy(m->name, base_name, sizeof m->name);
  m->base = base;
  m->size = span;
  m->phdr = (const void *)(base + *(uint64_t *)(file + 32) - ph[0].offset + ph[0].vaddr);
  m->phnum = phnum;
  m->phdr_copy = malloc(sizeof(Phdr) * phnum);
  memcpy(m->phdr_copy, ph, sizeof(Phdr) * phnum);
  m->phdr = m->phdr_copy;

  const uint64_t *d = (const uint64_t *)(base + dyn->vaddr);
  uint64_t rela = 0, relasz = 0, jmprel = 0, pltsz = 0, relr = 0, relrsz = 0, arela = 0, arelasz = 0, init = 0, inita = 0, initasz = 0;
  for (const uint64_t *e = d; e[0] != DT_NULL; e += 2) {
    switch (e[0]) {
      case DT_STRTAB: m->strtab = (const char *)(base + e[1]); break;
      case DT_SYMTAB: m->symtab = (const void *)(base + e[1]); break;
      case DT_HASH: m->sysv_hash = (const uint32_t *)(base + e[1]); break;
      case DT_GNU_HASH: m->gnu_hash = (const uint32_t *)(base + e[1]); break;
      case DT_RELA: rela = e[1]; break;
      case DT_RELASZ: relasz = e[1]; break;
      case DT_JMPREL: jmprel = e[1]; break;
      case DT_PLTRELSZ: pltsz = e[1]; break;
      case DT_RELR: relr = e[1]; break;
      case DT_RELRSZ: relrsz = e[1]; break;
      case DT_ANDROID_RELA: arela = e[1]; break;
      case DT_ANDROID_RELASZ: arelasz = e[1]; break;
      case DT_INIT: init = e[1]; break;
      case DT_INIT_ARRAY: inita = e[1]; break;
      case DT_INIT_ARRAYSZ: initasz = e[1]; break;
    }
  }
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type == PT_TLS) {
      m->tls_image = (const void *)(base + ph[i].vaddr);
      m->tls_filesz = ph[i].filesz;
      m->tls_memsz = ph[i].memsz;
    }
  }
  // Dependencies first: their symbols must be visible to our relocations.
  for (const uint64_t *e = d; e[0] != DT_NULL; e += 2) {
    if (e[0] != DT_NEEDED) continue;
    const char *dep = m->strtab + e[1];
    if (is_system_lib(dep) || elf_loaded(dep)) continue;
    char dep_path[1200];
    snprintf(dep_path, sizeof dep_path, "%s/%s", search_dir, dep);
    if (!elf_load(dep_path, err, errlen)) goto done;  // err says why; same outcome as on a device.
  }
  m->next = modules;
  modules = m;

  if (relr) apply_relr(m, (const uint64_t *)(base + relr), relrsz);
  if (arela) apply_android_rela(m, (const uint8_t *)(base + arela), arelasz);
  for (size_t i = 0; i < relasz / sizeof(Rela); i++) apply_rela(m, (const Rela *)(base + rela) + i);
  for (size_t i = 0; i < pltsz / sizeof(Rela); i++) apply_rela(m, (const Rela *)(base + jmprel) + i);

  for (int i = 0; i < phnum; i++) {
    if (ph[i].type != PT_LOAD) continue;
    uintptr_t from = (base + ph[i].vaddr) & ~(page - 1);
    uintptr_t to = (base + ph[i].vaddr + ph[i].memsz + page - 1) & ~(page - 1);
    if (ph[i].flags & 1) {
      int prot = PROT_READ | PROT_EXEC;
#ifdef __APPLE__
      patch_thread_pointer((uint8_t *)(base + ph[i].vaddr), ph[i].filesz);
      sys_icache_invalidate((void *)from, to - from);
      // Apple Silicon forbids writable+executable pages, so code cannot share a 16 KB host page with
      // writable data. Current libraries are 16 KB aligned (a Play requirement since 2025).
      for (int j = 0; j < phnum; j++) {
        if (ph[j].type != PT_LOAD || !(ph[j].flags & 2)) continue;
        uintptr_t ws = base + ph[j].vaddr, we = ws + ph[j].memsz;
        if (ws < to && we > from) {
          snprintf(err, errlen, "%s is not 16 KB aligned: code and data share a page, which macOS cannot map", base_name);
          goto done;
        }
      }
#else
      // Linux allows writable+executable pages, so code may share a page with data (4 KB-aligned libraries).
      for (int j = 0; j < phnum; j++) {
        if (ph[j].type != PT_LOAD || !(ph[j].flags & 2)) continue;
        uintptr_t ws = base + ph[j].vaddr, we = ws + ph[j].memsz;
        if (ws < to && we > from) prot |= PROT_WRITE;
      }
#endif
      if (mprotect((void *)from, to - from, prot) && (make_anonymous(from, to - from) || mprotect((void *)from, to - from, prot))) {
        snprintf(err, errlen, "cannot make %s executable", base_name);
        goto done;
      }
    }
  }
  munmap(file, st.st_size);

  if (init) ((void (*)(int, char **, char **))(base + init))(0, NULL, NULL);
  if (inita) {
    void (**fn)(int, char **, char **) = (void *)(base + inita);
    for (size_t i = 0; i < initasz / 8; i++) {
      if (fn[i] && (uintptr_t)fn[i] != (uintptr_t)-1) fn[i](0, NULL, NULL);
    }
  }
  result = m;
done:
  pthread_mutex_unlock(&load_lock);
  return result;
}

// ---------------------------------------------------------------- dynamic TLS

typedef struct { Module *module; uint64_t offset; } TlsDesc;

void *tls_descriptor(Module *m, uint64_t offset) {
  TlsDesc *d = malloc(sizeof(TlsDesc));
  d->module = m;
  d->offset = offset;
  return d;
}

static char *tls_block(Module *m) {
  if (!m->tls_key_ready) {
    pthread_mutex_lock(&load_lock);
    if (!m->tls_key_ready) {
      pthread_key_create(&m->tls_key, NULL);
      m->tls_key_ready = 1;
    }
    pthread_mutex_unlock(&load_lock);
  }
  char *b = pthread_getspecific(m->tls_key);
  if (!b) {
    b = calloc(1, m->tls_memsz ? m->tls_memsz : 16);
    if (m->tls_filesz) memcpy(b, m->tls_image, m->tls_filesz);
    pthread_setspecific(m->tls_key, b);
  }
  return b;
}

#ifdef __x86_64__
typedef struct { Module *module; uint64_t offset; } TlsIndex;
void *elf_tls_get_addr(TlsIndex *i) { return tls_block(i->module) + i->offset; }
// TLSDESC (-mtls-dialect=gnu2) is not used by the NDK on x86_64; fail loudly if it ever is.
void tlsdesc_resolver(void) {
  fprintf(stderr, "[apksim] TLSDESC relocations are not supported on x86_64\n");
  abort();
}
#else
// Called by tlsdesc_resolver: returns the variable's address minus the thread pointer.
int64_t tlsdesc_resolve_c(TlsDesc *d) {
  uint64_t tp;
  __asm__("mrs %0, tpidrro_el0" : "=r"(tp));
  return (int64_t)(tls_block(d->module) + d->offset) - (int64_t)tp;
}

// TLSDESC ABI: x0 points at the descriptor {resolver, arg}; the result is in x0 and every other
// register must survive.
__asm__(
    ".text\n.globl _tlsdesc_resolver\n.p2align 2\n_tlsdesc_resolver:\n"
    "  stp x29, x30, [sp, #-16]!\n  mov x29, sp\n"
    "  stp x1, x2, [sp, #-16]!\n  stp x3, x4, [sp, #-16]!\n  stp x5, x6, [sp, #-16]!\n  stp x7, x8, [sp, #-16]!\n"
    "  stp x9, x10, [sp, #-16]!\n  stp x11, x12, [sp, #-16]!\n  stp x13, x14, [sp, #-16]!\n  stp x15, x16, [sp, #-16]!\n"
    "  stp x17, x18, [sp, #-16]!\n  stp q0, q1, [sp, #-32]!\n  stp q2, q3, [sp, #-32]!\n  stp q4, q5, [sp, #-32]!\n  stp q6, q7, [sp, #-32]!\n"
    "  ldr x0, [x0, #8]\n  bl _tlsdesc_resolve_c\n"
    "  ldp q6, q7, [sp], #32\n  ldp q4, q5, [sp], #32\n  ldp q2, q3, [sp], #32\n  ldp q0, q1, [sp], #32\n"
    "  ldp x17, x18, [sp], #16\n  ldp x15, x16, [sp], #16\n  ldp x13, x14, [sp], #16\n  ldp x11, x12, [sp], #16\n  ldp x9, x10, [sp], #16\n"
    "  ldp x7, x8, [sp], #16\n  ldp x5, x6, [sp], #16\n  ldp x3, x4, [sp], #16\n  ldp x1, x2, [sp], #16\n  ldp x29, x30, [sp], #16\n  ret\n");
#endif
