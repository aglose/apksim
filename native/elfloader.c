// Loads Android ELF shared objects into this process: arm64 ones on macOS and arm64 Linux, x86_64 ones on x86_64
// Linux. The CPU is the same, so code runs natively; what needs translating is the loader, the C library (bionic.c
// on macOS, linux/bionic_linux.c on Linux), the thread pointer on arm64 and, on macOS, the variadic ABI.
#include "elfloader.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#ifdef __APPLE__
#include <libkern/OSCacheControl.h>
#elif defined(__aarch64__)
#include <sys/random.h>
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
#define PT_GNU_RELRO 0x6474e552
// Error messages name the APK entry a library came from, so it is clear which dependency brought it.
#define APK_ENTRY "lib/" ELF_ABI "/%s"
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
// The same RELR table under Android's own tags: the NDK writes these for libraries built for API 28-29 (lld's
// --use-android-relr-tags), as the standard DT_RELR came only with API 30.
#define DT_ANDROID_RELR 0x6fffe000
#define DT_ANDROID_RELRSZ 0x6fffe001
#if defined(__x86_64__)
#define ELF_MACHINE 62
#define ELF_ARCH "x86_64"
#define ELF_ABI "x86_64"
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
#define ELF_ABI "arm64-v8a"
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

// Where an address the file gives (base + vaddr) is in memory: the same, except in data moved off a page shared with
// code (plan_data_move, macOS only). Every data address the loader computes or hands out goes through it.
static inline uintptr_t at(const Module *m, uintptr_t a) {
  return a >= m->moved_lo && a < m->moved_hi ? a + m->moved_by : a;
}

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

// A defined symbol's address. Once move_symbol_values has given moved symbols their new values (symbols_moved), the
// value is the address; before that, the file's, which at() moves.
static uintptr_t symbol_address(const Module *m, const Sym *s) {
  return m->symbols_moved ? m->base + s->value : at(m, m->base + s->value);
}

static void *lookup_in(Module *m, const char *name) {
  const Sym *s = find_sym(m, name);
  return s ? (void *)symbol_address(m, s) : NULL;
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
  if (sym->shndx != 0) return (void *)symbol_address(self, sym);
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
  uint64_t *where = (uint64_t *)at(m, m->base + r->offset);
  const Sym *sym = symi ? &((const Sym *)m->symtab)[symi] : NULL;
  switch (type) {
    case 0:
      break;
    case R_RELATIVE:
      *where = at(m, m->base + r->addend);
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

static void relr_word(Module *m, uintptr_t a) {
  uint64_t *w = (uint64_t *)at(m, a);
  *w = at(m, *w + m->base);
}

static void apply_relr(Module *m, const uint64_t *r, size_t bytes) {
  uintptr_t where = 0;  // the file's address, not moved: a bitmap's run may cross into moved data
  for (size_t i = 0; i < bytes / 8; i++) {
    uint64_t e = r[i];
    if (!(e & 1)) {
      where = m->base + e;
      relr_word(m, where);
      where += 8;
    } else {
      for (int j = 0; (e >>= 1); j++) {
        if (e & 1) relr_word(m, where + 8 * (uintptr_t)j);
      }
      where += 63 * 8;
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
#elif defined(__aarch64__)
// Android code reads its thread pointer from TPIDR_EL0: the stack protector's guard is at [tp + 0x28] (bionic's
// TLS_SLOT_STACK_GUARD), and TLSDESC accesses add tlsdesc_resolver's result to it. On Linux that register is glibc's,
// and [tp + 0x28] lies in its static TLS, which can change under a running function ("stack smashing detected").
// TPIDRRO_EL0, which macOS leaves free, reads as zero for 64-bit Linux processes. So each `mrs xN, tpidr_el0` becomes
// `adrp xN, <the module's tp page>`: the page after the module (always within ADRP's +-4 GB), read-only, holding a
// random guard at +0x28. TLSDESC results are offsets from that page (tlsdesc_resolve_c). The page is the same for
// every thread, which the guard allows; code that writes bionic TLS slots through it faults instead of racing.
static uint64_t stack_guard;

static void map_tp_page(Module *m, size_t page) {
  if (!stack_guard && getrandom(&stack_guard, sizeof stack_guard, 0) != sizeof stack_guard) stack_guard = 0x5a17c0de;
  mprotect((void *)m->tp, page, PROT_READ | PROT_WRITE);
  ((uint64_t *)m->tp)[5] = stack_guard;
  mprotect((void *)m->tp, page, PROT_READ);
}

static void patch_thread_pointer(Module *m, uint8_t *start, size_t size) {
  uint32_t *code = (uint32_t *)start;
  size_t n = size / 4;
  for (size_t i = 0; i < n; i++) {
    if ((code[i] & 0xffffffe0) != 0xd53bd040) continue;
    int64_t pages = (int64_t)(m->tp >> 12) - (int64_t)((uintptr_t)&code[i] >> 12);
    code[i] = 0x90000000 | (uint32_t)(pages & 3) << 29 | (uint32_t)((pages >> 2) & 0x7ffff) << 5 | (code[i] & 0x1f);
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

#ifdef __APPLE__
// macOS on Apple Silicon maps memory in 16 KB pages and never makes a page writable and executable at once: mprotect
// and mmap refuse RWX, and MAP_JIT memory is writable or executable per thread, never both (checked on macOS 26). A
// library linked for 4 KB pages (an NDK before r27, -z max-page-size=4096) has 16 KB pages that hold the end of its
// code and the start of the segment after it. That segment starts with RELRO (GOT, vtables, .init_array, .dynamic),
// which a device's linker makes read-only once relocated, so such a page becomes read+execute after relocation and
// nothing is lost. Writable data outside RELRO (.data, .bss) on a page with code cannot stay there (a write to it
// would fault): plan_data_move moves it. Finds the lowest such byte of the file's addresses, or returns 0.
static int code_page_conflict(const Phdr *ph, int phnum, size_t page, uint64_t *lo) {
  uint64_t rl = 0, rh = 0;
  int found = 0;
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type == PT_GNU_RELRO) rl = ph[i].vaddr, rh = ph[i].vaddr + ph[i].memsz;
  }
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type != PT_LOAD || !(ph[i].flags & 1)) continue;
    uint64_t from = ph[i].vaddr & ~(uint64_t)(page - 1);
    uint64_t to = (ph[i].vaddr + ph[i].memsz + page - 1) & ~(uint64_t)(page - 1);
    for (int j = 0; j < phnum; j++) {
      if (ph[j].type != PT_LOAD || !(ph[j].flags & 2)) continue;
      uint64_t ws = ph[j].vaddr > from ? ph[j].vaddr : from;
      uint64_t we = ph[j].vaddr + ph[j].memsz < to ? ph[j].vaddr + ph[j].memsz : to;
      uint64_t bad = we;
      if (ws >= we) continue;
      if (rh <= rl || ws < rl) bad = ws;              // writable bytes before RELRO (or no RELRO at all)
      else if (we > rh) bad = ws > rh ? ws : rh;      // writable bytes after RELRO
      if (bad < we && (!found || bad < *lo)) *lo = bad, found = 1;
    }
  }
  return found;
}

// Such data (libevent's in a 4 KB-aligned debug build, a few hundred bytes of .data and .bss after a small RELRO) is
// moved, with everything writable after it, to fresh pages after the image: [lo, hi) of the file's addresses lives
// `by` bytes on (Module.moved_*, `at`). lo is the conflict's 4 KB page (lld starts each segment on a 4 KB page of its
// own, so no code is on it) and `by` a multiple of 16 KB, so an address's low 12 bits stay: code reaches its data with
// adrp (the 4 KB page) plus an add or load of those bits, and only the adrp needs another page (patch_moved_refs).
// Relocations, symbol lookups and the dynamic section go through `at`. Returns 0 with nothing to move, 1 with a plan,
// -1 when code lies after lo (code cannot move: its PC-relative references would all change).
static int plan_data_move(const Phdr *ph, int phnum, size_t page, uint64_t span, uint64_t *lo, uint64_t *hi, uint64_t *by) {
  uint64_t c;
  if (!code_page_conflict(ph, phnum, page, &c)) return 0;
  *lo = c & ~(uint64_t)0xfff;
  *hi = 0;
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type != PT_LOAD) continue;
    uint64_t end = ph[i].vaddr + ph[i].memsz;
    if (end <= *lo) continue;
    if (ph[i].flags & 1) return -1;
    if (end > *hi) *hi = end;
  }
  *by = span - (*lo & ~(uint64_t)(page - 1));
  return 1;
}

static int64_t sign_extend(uint64_t v, int bits) { return (int64_t)(v << (64 - bits)) >> (64 - bits); }

// Points one range of the module's code at its moved data: an adrp of a moved 4 KB page gets that page's new place,
// and an adr or a load (literal) into the moved range its new address when still within their reach (1 MB); one that
// is not fails the load. Returns how many instructions changed, or -1 with *bad_at set.
static long patch_moved_range(const Module *m, uint64_t from, uint64_t to, uint64_t *bad_at) {
  long patched = 0;
  int64_t pages = (int64_t)(m->moved_by >> 12);
  for (uint64_t va = (from + 3) & ~(uint64_t)3; va + 4 <= to; va += 4) {
    uint32_t *insn = (uint32_t *)(m->base + va), i = *insn;
    uintptr_t pc = m->base + va;
    if ((i & 0x9f000000) == 0x90000000) {  // adrp
      int64_t imm = sign_extend(((i >> 29) & 3) | ((uint64_t)((i >> 5) & 0x7ffff) << 2), 21);
      uintptr_t target = (pc & ~(uintptr_t)0xfff) + (uintptr_t)(imm << 12);
      if (target < m->moved_lo || target >= m->moved_hi) continue;
      int64_t v = imm + pages;
      if (v != sign_extend((uint64_t)v, 21)) return *bad_at = va, -1;
      *insn = (i & 0x9f00001f) | (uint32_t)(v & 3) << 29 | (uint32_t)((v >> 2) & 0x7ffff) << 5;
      patched++;
    } else if ((i & 0x9f000000) == 0x10000000 || (i & 0x3b000000) == 0x18000000) {  // adr, load (literal)
      int adr = (i & 0x9f000000) == 0x10000000;
      int64_t imm = adr ? sign_extend(((i >> 29) & 3) | ((uint64_t)((i >> 5) & 0x7ffff) << 2), 21)
                        : sign_extend((i >> 5) & 0x7ffff, 19) * 4;
      uintptr_t target = pc + (uintptr_t)imm;
      if (target < m->moved_lo || target >= m->moved_hi) continue;
      int64_t v = imm + (int64_t)m->moved_by;
      if (v < -(1 << 20) || v >= (1 << 20)) return *bad_at = va, -1;
      if (adr) *insn = (i & 0x9f00001f) | (uint32_t)(v & 3) << 29 | (uint32_t)((v >> 2) & 0x7ffff) << 5;
      else *insn = (i & 0xff00001f) | (uint32_t)((v >> 2) & 0x7ffff) << 5;
      patched++;
    }
  }
  return patched;
}

// Every executable section when the file has section headers (however many: a library built with one section per
// function has thousands), else every executable segment. Returns how many instructions changed, or -1.
static long patch_moved_refs(const Module *m, const uint8_t *file, size_t file_size, const Phdr *ph, int phnum,
                             uint64_t *bad_at) {
  long patched = 0, r;
  int sections = 0;
  uint64_t shoff = *(const uint64_t *)(file + 40);
  uint16_t shentsize = *(const uint16_t *)(file + 58), shnum = *(const uint16_t *)(file + 60);
  if (shoff && shentsize == 64 && shoff + (uint64_t)shnum * 64 <= file_size) {
    for (int i = 0; i < shnum; i++) {
      const uint8_t *s = file + shoff + (uint64_t)i * 64;
      uint32_t type = *(const uint32_t *)(s + 4);
      uint64_t flags = *(const uint64_t *)(s + 8), addr = *(const uint64_t *)(s + 16), size = *(const uint64_t *)(s + 32);
      if (type != 1 /* PROGBITS */ || !(flags & 4 /* EXECINSTR */) || !addr || !size) continue;
      sections++;
      if ((r = patch_moved_range(m, addr, addr + size, bad_at)) < 0) return -1;
      patched += r;
    }
  }
  if (sections) return patched;
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type != PT_LOAD || !(ph[i].flags & 1)) continue;
    if ((r = patch_moved_range(m, ph[i].vaddr, ph[i].vaddr + ph[i].filesz, bad_at)) < 0) return -1;
    patched += r;
  }
  return patched;
}
#endif

// Gives back a failed load's address range: its pages (file mappings, both copies of moved data) dropped for an
// inaccessible reservation again, and the arena's next address put back when nothing was placed after it, so a
// library whose System.loadLibrary keeps failing does not use up the arena.
static void release_range(uintptr_t base, size_t total, size_t page) {
  // The page after the range too: arm64 Linux keeps the module's thread-pointer page there (map_tp_page).
  size_t len = base + total + page <= elf_arena_hi ? total + page : total;
  mmap((void *)base, len, PROT_NONE, MAP_PRIVATE | MAP_ANON | MAP_FIXED, -1, 0);
  if (arena_next == base + total + page) arena_next = base;
}

#ifdef __APPLE__
// The dynamic section as dl_iterate_phdr's callers read it (DT_PLTGOT, DT_SYMTAB, ... plus dlpi_addr): entries that
// hold addresses in the moved range get their new ones. Sizes, offsets and flags stay. Done once the loader has
// finished reading the entries through at(), which expects the file's addresses.
static void move_dynamic_addresses(const Module *m, uint64_t *d) {
  uint64_t lo = m->moved_lo - m->base, hi = m->moved_hi - m->base;
  for (uint64_t *e = d; e[0] != DT_NULL; e += 2) {
    switch (e[0]) {
      case 3:  /* DT_PLTGOT */ case DT_HASH: case DT_STRTAB: case DT_SYMTAB: case DT_RELA: case DT_INIT:
      case 13: /* DT_FINI */ case DT_JMPREL: case DT_INIT_ARRAY: case 26: /* DT_FINI_ARRAY */
      case 32: /* DT_PREINIT_ARRAY */ case DT_RELR: case DT_GNU_HASH: case DT_ANDROID_RELA: case DT_ANDROID_RELR:
      case 0x6ffffef6: /* DT_TLSDESC_PLT */ case 0x6ffffef7: /* DT_TLSDESC_GOT */
      case 0x6ffffff0: /* DT_VERSYM */ case 0x6ffffffc: /* DT_VERDEF */ case 0x6ffffffe: /* DT_VERNEED */
        if (e[1] >= lo && e[1] < hi) e[1] += m->moved_by;
        break;
    }
  }
}

// How many entries the dynamic symbol table has: DT_HASH's nchain, else past the highest GNU hash chain.
static uint32_t symbol_count(const Module *m) {
  if (m->sysv_hash) return m->sysv_hash[1];
  if (!m->gnu_hash) return 0;
  const uint32_t *h = m->gnu_hash;
  uint32_t nbuckets = h[0], symoff = h[1];
  const uint32_t *buckets = (const uint32_t *)((const uint64_t *)(h + 4) + h[2]);
  const uint32_t *chain = buckets + nbuckets;
  uint32_t last = 0;
  for (uint32_t i = 0; i < nbuckets; i++) {
    if (buckets[i] > last) last = buckets[i];
  }
  if (last < symoff) return symoff;
  while (!(chain[last - symoff] & 1)) last++;
  return last + 1;
}

// The dynamic symbols as dl_iterate_phdr's callers read them (dlpi_addr + st_value): defined, non-TLS symbols in the
// moved range get their new values. From then on the loader's own lookups take the values as they are
// (symbols_moved), so dlsym and other libraries' relocations still find the live data.
static void move_symbol_values(Module *m) {
  uint64_t lo = m->moved_lo - m->base, hi = m->moved_hi - m->base;
  uint32_t n = symbol_count(m);
  Sym *syms = (Sym *)m->symtab;
  for (uint32_t i = 1; i < n; i++) {
    Sym *s = &syms[i];
    if (s->shndx == 0 || s->shndx == 0xfff1 /* SHN_ABS */ || (s->info & 0xf) == 6 /* STT_TLS */) continue;
    if (s->value >= lo && s->value < hi) s->value += m->moved_by;
  }
  m->symbols_moved = 1;
}

static uint64_t uleb(const uint8_t **pp) {
  const uint8_t *p = *pp;
  uint64_t r = 0;
  int s = 0;
  uint8_t b;
  do {
    b = *p++;
    if (s < 64) r |= (uint64_t)(b & 0x7f) << s;
    s += 7;
  } while (b & 0x80);
  *pp = p;
  return r;
}

// One encoded pointer of .eh_frame (DW_EH_PE_*) at file address `fa`, live at `p`: a PC-relative one whose target
// (the address, or with DW_EH_PE_indirect the slot holding it, as a C++ personality's DW.ref.__gxx_personality_v0)
// is in the moved range is rewritten to reach the moved copy. Absolute ones are relocations, which at() already
// moves; other kinds (data-, text- or function-relative) are not used by arm64 Android code and are left alone.
// Returns the field's size, or -1 for a pointer that would need moving but cannot be.
static int eh_pointer(const Module *m, uint8_t enc, uint8_t *p, uint64_t fa, int write) {
  if (enc == 0xff) return 0;  // DW_EH_PE_omit
  int size;
  int64_t v;
  switch (enc & 0x0f) {
    case 0x00: case 0x04: case 0x0c: size = 8; memcpy(&v, p, 8); break;
    case 0x03: { uint32_t u; memcpy(&u, p, 4); v = u; size = 4; break; }
    case 0x0b: { int32_t s; memcpy(&s, p, 4); v = s; size = 4; break; }
    case 0x02: { uint16_t u; memcpy(&u, p, 2); v = u; size = 2; break; }
    case 0x0a: { int16_t s; memcpy(&s, p, 2); v = s; size = 2; break; }
    case 0x01: case 0x09: {  // LEB128: no room to rewrite in place
      const uint8_t *q = p;
      v = (enc & 0x0f) == 0x01 ? (int64_t)uleb(&q) : (int64_t)sleb(&q);
      size = (int)(q - p);
      break;
    }
    default: return -1;
  }
  if (!write || (enc & 0x70) != 0x10) return size;  // only its size wanted, or not PC-relative
  uint64_t target = fa + (uint64_t)v, lo = m->moved_lo - m->base, hi = m->moved_hi - m->base;
  if (target < lo || target >= hi) return size;
  int64_t nv = v + (int64_t)m->moved_by;
  switch (enc & 0x0f) {
    case 0x00: case 0x04: case 0x0c: memcpy(p, &nv, 8); return size;
    case 0x0b: if (nv != (int32_t)nv) return -1; { int32_t s = (int32_t)nv; memcpy(p, &s, 4); } return size;
    case 0x03: if (nv < 0 || nv > 0xffffffffLL) return -1; { uint32_t u = (uint32_t)nv; memcpy(p, &u, 4); } return size;
    default: return -1;
  }
}

// Finds .eh_frame: by section name when the file has section headers, else from PT_GNU_EH_FRAME's header to the end
// of the segment holding it. Returns 0 when there is none.
static int find_eh_frame(const Module *m, const uint8_t *file, size_t file_size, const Phdr *ph, int phnum,
                         uint64_t *start, uint64_t *end) {
  uint64_t shoff = *(const uint64_t *)(file + 40);
  uint16_t shentsize = *(const uint16_t *)(file + 58), shnum = *(const uint16_t *)(file + 60);
  uint16_t shstrndx = *(const uint16_t *)(file + 62);
  if (shoff && shentsize == 64 && shoff + (uint64_t)shnum * 64 <= file_size && shstrndx < shnum) {
    const uint8_t *strs = file + shoff + (uint64_t)shstrndx * 64;
    uint64_t stroff = *(const uint64_t *)(strs + 24), strsize = *(const uint64_t *)(strs + 32);
    for (int i = 0; i < shnum && stroff + strsize <= file_size; i++) {
      const uint8_t *s = file + shoff + (uint64_t)i * 64;
      uint32_t name = *(const uint32_t *)s;
      if (name + 10 > strsize || memcmp(file + stroff + name, ".eh_frame", 10)) continue;
      *start = *(const uint64_t *)(s + 16);
      *end = *start + *(const uint64_t *)(s + 32);
      return *start != 0;
    }
    return 0;
  }
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type != 0x6474e550 /* PT_GNU_EH_FRAME */) continue;
    const uint8_t *hdr = (const uint8_t *)(m->base + ph[i].vaddr);
    if (hdr[0] != 1 || hdr[1] != 0x1b) return 0;  // version 1, eh_frame_ptr pcrel|sdata4
    int32_t rel;
    memcpy(&rel, hdr + 4, 4);
    *start = ph[i].vaddr + 4 + (int64_t)rel;
    for (int j = 0; j < phnum; j++) {
      if (ph[j].type == PT_LOAD && *start >= ph[j].vaddr && *start < ph[j].vaddr + ph[j].filesz) {
        *end = ph[j].vaddr + ph[j].filesz;
        return 1;
      }
    }
  }
  return 0;
}

// The file address a PC-relative pointer of fixed size points at, for reading where an LSDA is. Returns its size, or
// -1 for any other encoding.
static int eh_target(uint8_t enc, const uint8_t *p, uint64_t fa, uint64_t *target, int64_t *raw) {
  int64_t v;
  int size;
  if ((enc & 0x70) != 0x10) return -1;
  switch (enc & 0x0f) {
    case 0x0b: { int32_t s; memcpy(&s, p, 4); v = s; size = 4; break; }
    case 0x03: { uint32_t u; memcpy(&u, p, 4); v = u; size = 4; break; }
    case 0x00: case 0x04: case 0x0c: memcpy(&v, p, 8); size = 8; break;
    default: return -1;
  }
  *raw = v;
  *target = fa + (uint64_t)v;
  return size;
}

// One LSDA (a function's .gcc_except_table entry: call sites, actions, and the type table a typed catch or an
// exception specification names its std::type_info by): its type-table entries are encoded pointers, PC-relative
// and usually indirect through a DW.ref.<typeinfo> slot, which is writable data and may have moved. Each entry an
// action or exception specification uses is repointed (eh_pointer), once per LSDA. Returns -1 (with *bad_at) for
// what cannot be followed or rewritten.
static int move_lsda(const Module *m, uint64_t lsda, uint64_t *bad_at) {
  const uint8_t *p = (const uint8_t *)(m->base + lsda);
  uint8_t lpstart_enc = *p++;
  if (lpstart_enc != 0xff) {
    int n = eh_pointer(m, lpstart_enc, (uint8_t *)p, 0, 0);
    if (n < 0) return *bad_at = lsda, -1;
    p += n;
  }
  uint8_t ttype_enc = *p++;
  const uint8_t *ttype_base = NULL;
  if (ttype_enc != 0xff) {
    uint64_t off = uleb(&p);
    ttype_base = p + off;
  }
  uint8_t cs_enc = *p++;
  uint64_t cs_len = uleb(&p);
  const uint8_t *cs = p, *cs_end = p + cs_len, *actions = cs_end;
  uint64_t max_index = 0;
  while (cs < cs_end) {
    for (int k = 0; k < 3; k++) {  // start, length, landing pad
      int n = eh_pointer(m, cs_enc, (uint8_t *)cs, 0, 0);
      if (n <= 0) return *bad_at = lsda, -1;
      cs += n;
    }
    uint64_t action = uleb(&cs);
    if (!action) continue;
    const uint8_t *a = actions + action - 1;
    for (int guard = 0; guard < 4096; guard++) {
      int64_t filter = (int64_t)sleb(&a);
      const uint8_t *disp_at = a;
      int64_t disp = (int64_t)sleb(&a);
      if (filter > 0 && (uint64_t)filter > max_index) max_index = (uint64_t)filter;
      if (filter < 0 && ttype_base) {  // an exception specification: type indices after the type table's base
        const uint8_t *spec = ttype_base + (-filter - 1);
        for (uint64_t i; (i = uleb(&spec)) != 0;) {
          if (i > max_index) max_index = i;
        }
      }
      if (!disp) break;
      a = disp_at + disp;
    }
  }
  if (!max_index) return 0;
  if (!ttype_base) return *bad_at = lsda, -1;
  int size;
  switch (ttype_enc & 0x0f) {
    case 0x02: case 0x0a: size = 2; break;
    case 0x03: case 0x0b: size = 4; break;
    case 0x00: case 0x04: case 0x0c: size = 8; break;
    default: return *bad_at = lsda, -1;
  }
  for (uint64_t i = 1; i <= max_index; i++) {
    uint8_t *e = (uint8_t *)ttype_base - i * (uint64_t)size;
    if (eh_pointer(m, ttype_enc, e, (uint64_t)((uintptr_t)e - m->base), 1) < 0) {
      return *bad_at = (uint64_t)((uintptr_t)e - m->base), -1;
    }
  }
  return 0;
}

// Points .eh_frame's personality and LSDA pointers into moved data at the moved copy, so a C++ exception unwinds
// through this library: the personality slot DW.ref.__gxx_personality_v0 is writable data (relocated), reached
// PC-relative and indirect from each CIE. Returns -1 (with *bad_at) when a pointer cannot be moved, or .eh_frame
// itself lies in the moved range: such a library is refused before it is registered.
static int move_eh_frame(const Module *m, const uint8_t *file, size_t file_size, const Phdr *ph, int phnum,
                         uint64_t *bad_at) {
  uint64_t start, end, lo = m->moved_lo - m->base, hi = m->moved_hi - m->base;
  if (!find_eh_frame(m, file, file_size, ph, phnum, &start, &end)) return 0;
  if (start < hi && end > lo) return *bad_at = start, -1;
  uint64_t at_ = start, *seen = NULL;
  size_t nseen = 0, capseen = 0;
  while (at_ + 4 <= end) {
    uint8_t *rec = (uint8_t *)(m->base + at_);
    uint64_t len = *(uint32_t *)rec, hdr = 4;
    if (len == 0) break;  // terminator
    if (len == 0xffffffff) len = *(uint64_t *)(rec + 4), hdr = 12;
    uint64_t next = at_ + hdr + len;
    if (next > end) break;
    uint8_t *body = rec + hdr;
    uint32_t id = *(uint32_t *)body;
    uint64_t cie_at = id == 0 ? at_ : at_ + hdr - id;
    // The CIE's augmentation: which encodings follow, and where.
    const uint8_t *c = (const uint8_t *)(m->base + cie_at);
    uint64_t chdr = *(const uint32_t *)c == 0xffffffff ? 12 : 4;
    const uint8_t *q = c + chdr + 4;
    uint8_t version = *q++;
    const char *aug = (const char *)q;
    q += strlen(aug) + 1;
    if (aug[0] == 'e' && aug[1] == 'h') q += 8;
    uleb(&q);
    sleb(&q);
    if (version == 1) q++;
    else uleb(&q);
    uint8_t fde_enc = 0, lsda_enc = 0xff;
    if (aug[0] == 'z') {
      uleb(&q);
      for (const char *a = aug + 1; *a; a++) {
        if (*a == 'R') fde_enc = *q++;
        else if (*a == 'L') lsda_enc = *q++;
        else if (*a == 'P') {
          uint8_t enc = *q++;
          // Rewritten when the CIE itself is walked, not again for each FDE that refers to it.
          int n = eh_pointer(m, enc, (uint8_t *)q, (uint64_t)((uintptr_t)q - m->base), id == 0);
          if (n < 0) return free(seen), *bad_at = (uint64_t)((uintptr_t)q - m->base), -1;
          q += n;
        } else if (*a != 'S' && *a != 'B') break;
      }
    }
    if (id != 0 && aug[0] == 'z' && lsda_enc != 0xff) {
      // FDE: pc_begin, pc_range (code: never moved), augmentation length, then the LSDA pointer.
      const uint8_t *f = body + 4;
      for (int k = 0; k < 2; k++) {
        int n = eh_pointer(m, (uint8_t)(fde_enc & 0x0f), (uint8_t *)f, 0, 0);
        if (n < 0) return free(seen), *bad_at = (uint64_t)((uintptr_t)f - m->base), -1;
        f += n;
      }
      uleb(&f);
      uint64_t fa = (uint64_t)((uintptr_t)f - m->base), lsda;
      int64_t raw;
      // The LSDA (.gcc_except_table) is read-only data, never moved: its type table is what may point into moved
      // data. Each is walked once, as functions can share one.
      if (eh_target(lsda_enc, f, fa, &lsda, &raw) < 0 || (raw && lsda >= lo && lsda < hi)) {
        free(seen);
        return *bad_at = fa, -1;
      }
      int again = 0;
      for (size_t i = 0; i < nseen && !again; i++) again = seen[i] == lsda;
      if (raw && !again) {
        if (nseen == capseen) {
          capseen = capseen ? capseen * 2 : 64;
          uint64_t *grown = realloc(seen, capseen * sizeof *seen);
          if (!grown) {
            free(seen);
            return *bad_at = fa, -1;
          }
          seen = grown;
        }
        seen[nseen++] = lsda;
        if (move_lsda(m, lsda, bad_at) < 0) {
          free(seen);
          return -1;
        }
      }
    }
    at_ = next;
  }
  free(seen);
  return 0;
}
#endif

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
    if (errno == ENOENT) snprintf(err, errlen, APK_ENTRY " is not in the APK", base_name);
    else snprintf(err, errlen, APK_ENTRY " cannot be opened: %s", base_name, strerror(errno));
    goto done;
  }
  struct stat st;
  fstat(fd, &st);
  uint8_t *file = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  if (file == MAP_FAILED || memcmp(file, "\177ELF", 4) || file[4] != 2 || *(uint16_t *)(file + 18) != ELF_MACHINE) {
    close(fd);
    snprintf(err, errlen, APK_ENTRY " is not an " ELF_ARCH " ELF", base_name);
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
  size_t span = (max_va + page - 1) & ~(page - 1), total = span;
#ifdef __APPLE__
  uint64_t move_lo = 0, move_hi = 0, move_by = 0;
  int plan = plan_data_move(ph, phnum, page, span, &move_lo, &move_hi, &move_by);
  if (plan < 0) {
    close(fd);
    munmap(file, st.st_size);
    snprintf(err, errlen,
             APK_ENTRY " is 4 KB aligned with writable data at 0x%llx on a 16 KB page with code and more code after it,"
             " which macOS cannot map (no page is writable and executable at once); rebuild it for 16 KB pages (NDK r27"
             " or later, or -Wl,-z,max-page-size=16384)",
             base_name, (unsigned long long)move_lo);
    goto done;
  }
  if (plan) total = (move_hi + move_by + page - 1) & ~(page - 1);
#endif
  uintptr_t base = arena_next;
  if (base + total > elf_arena_hi) {
    close(fd);
    snprintf(err, errlen, APK_ENTRY ": no address space left for native libraries", base_name);
    goto done;
  }
  arena_next = base + total + page;
  if (mprotect((void *)base, total, PROT_READ | PROT_WRITE)) {
    close(fd);
    release_range(base, total, page);
    snprintf(err, errlen, APK_ENTRY ": mprotect failed", base_name);
    goto done;
  }
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type == PT_LOAD) load_segment(base, &ph[i], file, fd, page);
  }
  close(fd);
  Module *m = calloc(1, sizeof(Module));
  strlcpy(m->name, base_name, sizeof m->name);
  m->base = base;
  m->size = total;
#ifdef __APPLE__
  if (move_by) {
    m->moved_lo = base + move_lo;
    m->moved_hi = base + move_hi;
    m->moved_by = move_by;
    // Only the file's bytes: .bss and gaps stay as the fresh mapping has them, zero and untouched.
    for (int i = 0; i < phnum; i++) {
      if (ph[i].type != PT_LOAD) continue;
      uint64_t s = ph[i].vaddr > move_lo ? ph[i].vaddr : move_lo;
      uint64_t e = ph[i].vaddr + ph[i].filesz < move_hi ? ph[i].vaddr + ph[i].filesz : move_hi;
      if (s < e) memcpy((void *)(base + s + move_by), file + ph[i].offset + (s - ph[i].vaddr), e - s);
    }
    uint64_t bad_at = 0;
    long patched = patch_moved_refs(m, file, st.st_size, ph, phnum, &bad_at);
    if (patched < 0) {
      munmap(file, st.st_size);
      free(m);
      release_range(base, total, page);
      snprintf(err, errlen,
               APK_ENTRY " is 4 KB aligned with writable data on a 16 KB page with code, which macOS cannot map; moving"
               " the data left the instruction at 0x%llx out of reach of it. Rebuild it for 16 KB pages (NDK r27 or"
               " later, or -Wl,-z,max-page-size=16384)",
               base_name, (unsigned long long)bad_at);
      goto done;
    }
    if (move_eh_frame(m, file, st.st_size, ph, phnum, &bad_at) < 0) {
      munmap(file, st.st_size);
      free(m);
      release_range(base, total, page);
      snprintf(err, errlen,
               APK_ENTRY " is 4 KB aligned with writable data on a 16 KB page with code, which macOS cannot map; moving"
               " the data would leave its unwind information at 0x%llx pointing at the old place. Rebuild it for 16 KB"
               " pages (NDK r27 or later, or -Wl,-z,max-page-size=16384)",
               base_name, (unsigned long long)bad_at);
      goto done;
    }
    // Said once per library: if such a library misbehaves, this is where to look first.
    fprintf(stderr, "[apksim] " APK_ENTRY " is 4 KB aligned: its data at 0x%llx-0x%llx moved off a 16 KB page with code,"
            " %ld instructions repointed\n", base_name, (unsigned long long)move_lo, (unsigned long long)move_hi, patched);
  }
#endif
#if !defined(__APPLE__) && defined(__aarch64__)
  m->tp = base + span;  // the guard page arena_next leaves after every module; before relocations (TLSDESC)
  map_tp_page(m, page);
#endif
  m->phdr = (const void *)(base + *(uint64_t *)(file + 32) - ph[0].offset + ph[0].vaddr);
  m->phnum = phnum;
  m->phdr_copy = malloc(sizeof(Phdr) * phnum * 2);
  memcpy(m->phdr_copy, ph, sizeof(Phdr) * phnum);
  m->phdr = m->phdr_copy;
#ifdef __APPLE__
  // dl_iterate_phdr's view (bionic.c) of moved data: what starts in the moved range is at its new address, and a
  // PT_LOAD the move cuts through becomes two, the part before it and the moved rest.
  if (move_by) {
    Phdr *out = m->phdr_copy;
    int n = phnum;
    for (int i = 0; i < phnum; i++) {
      Phdr *p = &out[i];
      if (p->vaddr >= move_lo && p->vaddr < move_hi) {
        p->vaddr += move_by, p->paddr += move_by;
      } else if (p->type == PT_LOAD && p->vaddr < move_lo && p->vaddr + p->memsz > move_lo) {
        uint64_t head = move_lo - p->vaddr;
        Phdr tail = *p;
        tail.vaddr = tail.paddr = move_lo + move_by;
        tail.offset += head;
        tail.memsz -= head;
        tail.filesz = p->filesz > head ? p->filesz - head : 0;
        p->memsz = head;
        if (p->filesz > head) p->filesz = head;
        out[n++] = tail;
      }
    }
    m->phnum = n;
  }
#endif

  const uint64_t *d = (const uint64_t *)at(m, base + dyn->vaddr);
  uint64_t rela = 0, relasz = 0, jmprel = 0, pltsz = 0, relr = 0, relrsz = 0, arela = 0, arelasz = 0, init = 0, inita = 0, initasz = 0;
  for (const uint64_t *e = d; e[0] != DT_NULL; e += 2) {
    switch (e[0]) {
      case DT_STRTAB: m->strtab = (const char *)at(m, base + e[1]); break;
      case DT_SYMTAB: m->symtab = (const void *)at(m, base + e[1]); break;
      case DT_HASH: m->sysv_hash = (const uint32_t *)at(m, base + e[1]); break;
      case DT_GNU_HASH: m->gnu_hash = (const uint32_t *)at(m, base + e[1]); break;
      case DT_RELA: rela = e[1]; break;
      case DT_RELASZ: relasz = e[1]; break;
      case DT_JMPREL: jmprel = e[1]; break;
      case DT_PLTRELSZ: pltsz = e[1]; break;
      case DT_RELR: case DT_ANDROID_RELR: relr = e[1]; break;
      case DT_RELRSZ: case DT_ANDROID_RELRSZ: relrsz = e[1]; break;
      case DT_ANDROID_RELA: arela = e[1]; break;
      case DT_ANDROID_RELASZ: arelasz = e[1]; break;
      case DT_INIT: init = e[1]; break;
      case DT_INIT_ARRAY: inita = e[1]; break;
      case DT_INIT_ARRAYSZ: initasz = e[1]; break;
    }
  }
  for (int i = 0; i < phnum; i++) {
    if (ph[i].type == PT_TLS) {
      m->tls_image = (const void *)at(m, base + ph[i].vaddr);
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
    if (!elf_load(dep_path, err, errlen)) {  // err says why; same outcome as on a device.
      munmap(file, st.st_size);
      free(m->phdr_copy);
      free(m);
      release_range(base, total, page);
      goto done;
    }
  }
  m->next = modules;
  modules = m;

  if (relr) apply_relr(m, (const uint64_t *)at(m, base + relr), relrsz);
  if (arela) apply_android_rela(m, (const uint8_t *)at(m, base + arela), arelasz);
  for (size_t i = 0; i < relasz / sizeof(Rela); i++) apply_rela(m, (const Rela *)at(m, base + rela) + i);
  for (size_t i = 0; i < pltsz / sizeof(Rela); i++) apply_rela(m, (const Rela *)at(m, base + jmprel) + i);
#ifdef __APPLE__
  if (m->moved_by) {
    move_dynamic_addresses(m, (uint64_t *)d);
    if (m->symtab) move_symbol_values(m);
  }
#endif

  for (int i = 0; i < phnum; i++) {
    if (ph[i].type != PT_LOAD) continue;
    uintptr_t from = (base + ph[i].vaddr) & ~(page - 1);
    uintptr_t to = (base + ph[i].vaddr + ph[i].memsz + page - 1) & ~(page - 1);
    if (ph[i].flags & 1) {
      int prot = PROT_READ | PROT_EXEC;
#ifdef __APPLE__
      patch_thread_pointer((uint8_t *)(base + ph[i].vaddr), ph[i].filesz);
      sys_icache_invalidate((void *)from, to - from);
      // Read+execute for every 16 KB page with code in it. In a 4 KB-aligned library that takes in the neighbouring
      // segments' bytes on the same pages: read-only data before, RELRO after (relocated by now, and read-only on a
      // device too). Other writable data that shared such a page was moved (plan_data_move).
#else
#ifdef __aarch64__
      patch_thread_pointer(m, (uint8_t *)(base + ph[i].vaddr), ph[i].filesz);
      // The code was copied in as data: the instruction cache must not keep stale lines for these addresses.
      __builtin___clear_cache((char *)from, (char *)to);
#endif
      // Linux allows writable+executable pages, so code may share a page with data (4 KB-aligned libraries).
      for (int j = 0; j < phnum; j++) {
        if (ph[j].type != PT_LOAD || !(ph[j].flags & 2)) continue;
        uintptr_t ws = base + ph[j].vaddr, we = ws + ph[j].memsz;
        if (ws < to && we > from) prot |= PROT_WRITE;
      }
#endif
      if (mprotect((void *)from, to - from, prot) && (make_anonymous(from, to - from) || mprotect((void *)from, to - from, prot))) {
        snprintf(err, errlen, "cannot make " APK_ENTRY " executable", base_name);
        munmap(file, st.st_size);
        // Not loaded after all: out of the list (it is the newest unless a relocation loaded more) and the range back.
        if (modules == m) {
          modules = m->next;
          free(m->phdr_copy);
          free(m);
          release_range(base, total, page);
        }
        goto done;
      }
    }
  }
  munmap(file, st.st_size);

  if (init) ((void (*)(int, char **, char **))(base + init))(0, NULL, NULL);
  if (inita) {
    void (**fn)(int, char **, char **) = (void *)at(m, base + inita);
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
#elif !defined(__APPLE__)
// arm64 Linux: the thread pointer the module's code reads is its tp page (patch_thread_pointer).
__attribute__((visibility("hidden"))) int64_t tlsdesc_resolve_c(TlsDesc *d) {
  return (int64_t)(tls_block(d->module) + d->offset) - (int64_t)d->module->tp;
}

// TLSDESC ABI: x0 points at the descriptor {resolver, arg}; the result is in x0 and every other register must survive.
// glibc's memcpy and malloc (a thread's first access allocates its block) use any SIMD register, so all 32 are saved.
#define TLSDESC_Q(op) \
  "  " op " q0, q1, [sp, #0]\n  " op " q2, q3, [sp, #32]\n  " op " q4, q5, [sp, #64]\n  " op " q6, q7, [sp, #96]\n" \
  "  " op " q8, q9, [sp, #128]\n  " op " q10, q11, [sp, #160]\n  " op " q12, q13, [sp, #192]\n  " op " q14, q15, [sp, #224]\n" \
  "  " op " q16, q17, [sp, #256]\n  " op " q18, q19, [sp, #288]\n  " op " q20, q21, [sp, #320]\n  " op " q22, q23, [sp, #352]\n" \
  "  " op " q24, q25, [sp, #384]\n  " op " q26, q27, [sp, #416]\n  " op " q28, q29, [sp, #448]\n  " op " q30, q31, [sp, #480]\n"
__asm__(
    ".text\n.globl tlsdesc_resolver\n.type tlsdesc_resolver, %function\n.p2align 2\ntlsdesc_resolver:\n"
    "  stp x29, x30, [sp, #-16]!\n  mov x29, sp\n"
    "  stp x1, x2, [sp, #-16]!\n  stp x3, x4, [sp, #-16]!\n  stp x5, x6, [sp, #-16]!\n  stp x7, x8, [sp, #-16]!\n"
    "  stp x9, x10, [sp, #-16]!\n  stp x11, x12, [sp, #-16]!\n  stp x13, x14, [sp, #-16]!\n  stp x15, x16, [sp, #-16]!\n"
    "  stp x17, x18, [sp, #-16]!\n  sub sp, sp, #512\n" TLSDESC_Q("stp")
    "  ldr x0, [x0, #8]\n  bl tlsdesc_resolve_c\n"
    TLSDESC_Q("ldp") "  add sp, sp, #512\n"
    "  ldp x17, x18, [sp], #16\n  ldp x15, x16, [sp], #16\n  ldp x13, x14, [sp], #16\n  ldp x11, x12, [sp], #16\n  ldp x9, x10, [sp], #16\n"
    "  ldp x7, x8, [sp], #16\n  ldp x5, x6, [sp], #16\n  ldp x3, x4, [sp], #16\n  ldp x1, x2, [sp], #16\n  ldp x29, x30, [sp], #16\n  ret\n"
    ".size tlsdesc_resolver, .-tlsdesc_resolver\n");
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
