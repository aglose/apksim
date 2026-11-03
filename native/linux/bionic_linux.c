// The Android C library as seen by loaded x86_64 ELF code, on top of glibc. Both sit on the same Linux
// kernel ABI and the same SysV calling convention, so most functions are found by name in glibc. What is
// here differs: Android-only functions, sysconf and getaddrinfo constants, addrinfo's field order, the
// 8-byte sigset_t, and fortify checks glibc lacks. The macOS equivalent is ../bionic.c.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <locale.h>
#include <netdb.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "../elfloader.h"

void *elf_tls_get_addr(void *index);

// ---------------------------------------------------------------- Android-only

static int *s_errno(void) { return __errno_location(); }
static int s_system_property_get(const char *n, char *v) {
  if (v) v[0] = 0;
  return 0;
}
static void *s_system_property_find(const char *n) { return NULL; }
static int s_api_level(void) { return 36; }
static void s_set_abort_message(const char *m) { fprintf(stderr, "F/libc: %s\n", m); }
static void s_noop(void) {}
static int s_zero(void) { return 0; }

static const char *log_level(int p) {
  static const char *l = "??VDIWEF";
  static char out[2];
  out[0] = p >= 0 && p < 8 ? l[p] : '?';
  return out;
}
static int s_log_vprint(int prio, const char *tag, const char *f, va_list v) {
  fprintf(stderr, "%s/%s: ", log_level(prio), tag ? tag : "");
  vfprintf(stderr, f, v);
  fputc('\n', stderr);
  return 1;
}
static int s_log_print(int prio, const char *tag, const char *f, ...) {
  va_list v;
  va_start(v, f);
  int r = s_log_vprint(prio, tag, f, v);
  va_end(v);
  return r;
}
static int s_log_write(int prio, const char *tag, const char *msg) {
  fprintf(stderr, "%s/%s: %s\n", log_level(prio), tag ? tag : "", msg ? msg : "");
  return 1;
}
static void s_log_assert(const char *cond, const char *tag, const char *f, ...) {
  fprintf(stderr, "F/%s: assertion failed: %s ", tag ? tag : "", cond ? cond : "");
  if (f) {
    va_list v;
    va_start(v, f);
    vfprintf(stderr, f, v);
    va_end(v);
  }
  fputc('\n', stderr);
  abort();
}
static int s_log_is_loggable(int prio, const char *tag, int def) { return 1; }

// ---------------------------------------------------------------- differing constants and layouts

static long s_sysconf(int name) {
  switch (name) {
    case 0x27: case 0x28: return sysconf(_SC_PAGESIZE);
    case 0x60: return sysconf(_SC_NPROCESSORS_CONF);
    case 0x61: return sysconf(_SC_NPROCESSORS_ONLN);
    case 0x62: return sysconf(_SC_PHYS_PAGES);
    case 0x63: return sysconf(_SC_AVPHYS_PAGES);
    case 0x06: return sysconf(_SC_CLK_TCK);
    case 0x05: return sysconf(_SC_OPEN_MAX);
    case 0x00: return sysconf(_SC_ARG_MAX);
    case 0x01: return sysconf(_SC_CHILD_MAX);
    default:
      errno = EINVAL;
      return -1;
  }
}

// Bionic's addrinfo puts ai_canonname before ai_addr; glibc the other way round. Flags and errors use BSD values.
typedef struct AAddrinfo {
  int flags, family, socktype, protocol;
  socklen_t addrlen;
  char *canonname;
  struct sockaddr *addr;
  struct AAddrinfo *next;
} AAddrinfo;

static int ai_flags_to_host(int a) {
  int h = a & (AI_PASSIVE | AI_CANONNAME | AI_NUMERICHOST);
  if (a & 0x8) h |= AI_NUMERICSERV;
  if (a & 0x100) h |= AI_ALL;
  if (a & 0x400) h |= AI_ADDRCONFIG;
  if (a & 0x800) h |= AI_V4MAPPED;
  return h;
}
static int eai_to_android(int e) {
  switch (e) {
    case 0: return 0;
    case EAI_AGAIN: return 2;
    case EAI_BADFLAGS: return 3;
    case EAI_FAIL: return 4;
    case EAI_FAMILY: return 5;
    case EAI_MEMORY: return 6;
    case EAI_NODATA: return 7;
    case EAI_NONAME: return 8;
    case EAI_SERVICE: return 9;
    case EAI_SOCKTYPE: return 10;
    case EAI_OVERFLOW: return 14;
    default: return 11;  // EAI_SYSTEM
  }
}
static int eai_to_host(int a) {
  static const int map[] = {0, EAI_ADDRFAMILY, EAI_AGAIN, EAI_BADFLAGS, EAI_FAIL, EAI_FAMILY, EAI_MEMORY, EAI_NODATA,
                            EAI_NONAME, EAI_SERVICE, EAI_SOCKTYPE, EAI_SYSTEM, EAI_BADFLAGS, EAI_SYSTEM, EAI_OVERFLOW};
  return a >= 0 && a < 15 ? map[a] : EAI_SYSTEM;
}
static int s_getaddrinfo(const char *node, const char *service, const AAddrinfo *ahints, AAddrinfo **out) {
  struct addrinfo hints, *res = NULL;
  memset(&hints, 0, sizeof hints);
  if (ahints) {
    hints.ai_flags = ai_flags_to_host(ahints->flags);
    hints.ai_family = ahints->family;
    hints.ai_socktype = ahints->socktype;
    hints.ai_protocol = ahints->protocol;
  }
  int r = getaddrinfo(node, service, ahints ? &hints : NULL, &res);
  *out = NULL;
  if (r) return eai_to_android(r);
  AAddrinfo **tail = out;
  for (struct addrinfo *h = res; h; h = h->ai_next) {
    AAddrinfo *a = calloc(1, sizeof *a + h->ai_addrlen);
    a->flags = ahints ? ahints->flags : 0;
    a->family = h->ai_family;
    a->socktype = h->ai_socktype;
    a->protocol = h->ai_protocol;
    a->addrlen = h->ai_addrlen;
    a->addr = (struct sockaddr *)(a + 1);
    memcpy(a->addr, h->ai_addr, h->ai_addrlen);
    a->canonname = h->ai_canonname ? strdup(h->ai_canonname) : NULL;
    *tail = a;
    tail = &a->next;
  }
  freeaddrinfo(res);
  return 0;
}
static void s_freeaddrinfo(AAddrinfo *a) {
  while (a) {
    AAddrinfo *n = a->next;
    free(a->canonname);
    free(a);
    a = n;
  }
}
static const char *s_gai_strerror(int e) { return gai_strerror(eai_to_host(e)); }
static int s_getnameinfo(const struct sockaddr *sa, socklen_t len, char *host, socklen_t hl, char *serv, socklen_t sl, int af) {
  int f = 0;
  if (af & 1) f |= NI_NOFQDN;
  if (af & 2) f |= NI_NUMERICHOST;
  if (af & 4) f |= NI_NAMEREQD;
  if (af & 8) f |= NI_NUMERICSERV;
  if (af & 16) f |= NI_DGRAM;
  return eai_to_android(getnameinfo(sa, len, host, hl, serv, sl, f));
}

// Signals belong to the JVM: registering handlers from a library would break it. Bionic's sigset_t is 8 bytes.
static int s_sigaction(int s, const void *a, void *o) {
  if (o) memset(o, 0, 32);
  return 0;
}
static void *s_signal(int s, void *h) { return NULL; }
static int s_sigemptyset(uint64_t *m) { *m = 0; return 0; }
static int s_sigfillset(uint64_t *m) { *m = ~0ULL; return 0; }
static int s_sigaddset(uint64_t *m, int s) { *m |= 1ULL << (s - 1); return 0; }
static int s_sigdelset(uint64_t *m, int s) { *m &= ~(1ULL << (s - 1)); return 0; }
static int s_sigismember(const uint64_t *m, int s) { return (int)((*m >> (s - 1)) & 1); }
static int s_sigprocmask(int h, const uint64_t *m, uint64_t *o) {
  if (o) *o = 0;
  return 0;
}

// Bionic's ctype table: index with c + 1 (bits as in upstream ctype.h).
static char a_ctype[257];
static void init_ctype(void) {
  for (int c = 0; c < 128; c++) {
    char v = 0;
    if (isupper(c)) v |= 1;
    if (islower(c)) v |= 2;
    if (isdigit(c)) v |= 4;
    if (isspace(c)) v |= 8;
    if (ispunct(c)) v |= 0x10;
    if (iscntrl(c)) v |= 0x20;
    if (isxdigit(c)) v |= 0x40;
    if (c == ' ') v |= (char)0x80;
    a_ctype[c + 1] = v;
  }
}
static void *s_newlocale(int mask, const char *name, void *base) { return newlocale(LC_ALL_MASK, name, base); }

// Fortify checks glibc does not have.
static size_t s_strlen_chk(const char *s, size_t c) { return strlen(s); }
static char *s_strchr_chk(const char *s, int ch, size_t c) { return (char *)strchr(s, ch); }
static char *s_strrchr_chk(const char *s, int ch, size_t c) { return (char *)strrchr(s, ch); }
static size_t s_strlcpy_chk(char *d, const char *s, size_t n, size_t c) { return strlcpy(d, s, n); }
static size_t s_strlcat_chk(char *d, const char *s, size_t n, size_t c) { return strlcat(d, s, n); }
static size_t s_strlcpy(char *d, const char *s, size_t n) { return strlcpy(d, s, n); }
static size_t s_strlcat(char *d, const char *s, size_t n) { return strlcat(d, s, n); }
static void s_fd_set_chk(int fd, fd_set *s, size_t c) { FD_SET(fd, s); }
static void s_fd_clr_chk(int fd, fd_set *s, size_t c) { FD_CLR(fd, s); }
static int s_fd_isset_chk(int fd, const fd_set *s, size_t c) { return FD_ISSET(fd, s); }
static void *s_memcpy_chk(void *d, const void *s, size_t n, size_t c) { return memcpy(d, s, n); }
static long s_gettid(void) { return syscall(SYS_gettid); }

// Older code names the standard streams as &__sF[0..2] (bionic FILE is 152 bytes): map those to glibc's.
static char sF[3 * 152];
static FILE *map_file(FILE *f) {
  if ((char *)f == sF) return stdin;
  if ((char *)f == sF + 152) return stdout;
  if ((char *)f == sF + 304) return stderr;
  return f;
}
static size_t s_fwrite(const void *p, size_t a, size_t b, FILE *f) { return fwrite(p, a, b, map_file(f)); }
static int s_fputs(const char *s, FILE *f) { return fputs(s, map_file(f)); }
static int s_fputc(int c, FILE *f) { return fputc(c, map_file(f)); }
static int s_fflush(FILE *f) { return fflush(f ? map_file(f) : NULL); }
static int s_vfprintf(FILE *f, const char *fmt, va_list v) { return vfprintf(map_file(f), fmt, v); }
static int s_fprintf(FILE *f, const char *fmt, ...) {
  va_list v;
  va_start(v, fmt);
  int r = vfprintf(map_file(f), fmt, v);
  va_end(v);
  return r;
}
static void *s_null(void) { return NULL; }

// Destructors registered by loaded code would run after the JVM has torn things down; skip them.
static int s_cxa_atexit(void (*f)(void *), void *a, void *d) { return 0; }
static int s_cxa_finalize(void *d) { return 0; }

// ---------------------------------------------------------------- dynamic loader (as in ../bionic.c)

static __thread const char *t_dlerror;
const char *elf_search_dir(void);
static void *s_dlopen(const char *name, int flags) {
  if (!name) return RTLD_DEFAULT;
  const char *b = strrchr(name, '/');
  b = b ? b + 1 : name;
  Module *m = elf_loaded(b);
  if (m) return m;
  char path[1200], err[256];
  snprintf(path, sizeof path, "%s/%s", name[0] == '/' ? "" : elf_search_dir(), name[0] == '/' ? name + 1 : name);
  m = elf_load(path, err, sizeof err);
  if (m) return m;
  // A system library: its symbols resolve through the shim, so hand back a harmless handle.
  return RTLD_DEFAULT;
}
static void *s_dlsym(void *h, const char *name) {
  if (h && h != RTLD_DEFAULT && h != RTLD_NEXT) {
    void *p = elf_find(h, name);
    if (p) return p;
  }
  void *p = elf_find_global(name);
  if (!p) p = bionic_lookup(name);
  if (!p) t_dlerror = "symbol not found";
  return p;
}
static int s_dlclose(void *h) { return 0; }
static const char *s_dlerror(void) {
  const char *e = t_dlerror;
  t_dlerror = NULL;
  return e;
}
static int s_dladdr(const void *a, Dl_info *i) {
  Module *m = elf_module_for(a);
  if (!m) return dladdr(a, i);
  i->dli_fname = m->name;
  i->dli_fbase = (void *)m->base;
  i->dli_sname = NULL;
  i->dli_saddr = NULL;
  return 1;
}
typedef struct { uint64_t addr; const char *name; const void *phdr; uint16_t phnum; } APhdrInfo;
static int s_dl_iterate_phdr(int (*cb)(APhdrInfo *, size_t, void *), void *data) {
  for (Module *m = elf_modules(); m; m = m->next) {
    APhdrInfo i = {m->base, m->name, m->phdr, (uint16_t)m->phnum};
    int r = cb(&i, sizeof i, data);
    if (r) return r;
  }
  return 0;
}

// ---------------------------------------------------------------- symbol table

typedef struct { const char *name; void *fn; } Entry;
#define E(a, b) {a, (void *)b}
static const Entry table[] = {
    E("__errno", s_errno), E("_ctype_", a_ctype), E("__tls_get_addr", elf_tls_get_addr), E("__sF", sF),
    E("fwrite", s_fwrite), E("fputs", s_fputs), E("fputc", s_fputc), E("fflush", s_fflush), E("fprintf", s_fprintf),
    E("vfprintf", s_vfprintf), E("eglGetCurrentContext", s_null),
    E("__system_property_get", s_system_property_get), E("__system_property_find", s_system_property_find),
    E("android_get_device_api_level", s_api_level), E("android_set_abort_message", s_set_abort_message),
    E("android_fdsan_exchange_owner_tag", s_noop), E("android_fdsan_close_with_tag", close), E("android_fdsan_get_owner_tag", s_zero),
    E("__android_log_print", s_log_print), E("__android_log_vprint", s_log_vprint), E("__android_log_write", s_log_write),
    E("__android_log_buf_write", s_log_write), E("__android_log_assert", s_log_assert), E("__android_log_is_loggable", s_log_is_loggable),
    E("sysconf", s_sysconf), E("getaddrinfo", s_getaddrinfo), E("freeaddrinfo", s_freeaddrinfo), E("gai_strerror", s_gai_strerror),
    E("getnameinfo", s_getnameinfo),
    E("sigaction", s_sigaction), E("signal", s_signal), E("bsd_signal", s_signal), E("sigemptyset", s_sigemptyset),
    E("sigfillset", s_sigfillset), E("sigaddset", s_sigaddset), E("sigdelset", s_sigdelset), E("sigismember", s_sigismember),
    E("sigprocmask", s_sigprocmask), E("pthread_sigmask", s_sigprocmask),
    E("newlocale", s_newlocale), E("gettid", s_gettid),
    E("__strlen_chk", s_strlen_chk), E("__strchr_chk", s_strchr_chk), E("__strrchr_chk", s_strrchr_chk),
    E("__strlcpy_chk", s_strlcpy_chk), E("__strlcat_chk", s_strlcat_chk), E("strlcpy", s_strlcpy), E("strlcat", s_strlcat),
    E("__FD_SET_chk", s_fd_set_chk), E("__FD_CLR_chk", s_fd_clr_chk), E("__FD_ISSET_chk", s_fd_isset_chk),
    E("__memcpy_chk", s_memcpy_chk),
    E("__cxa_atexit", s_cxa_atexit), E("__cxa_finalize", s_cxa_finalize), E("__cxa_thread_atexit_impl", s_cxa_atexit),
    E("dlopen", s_dlopen), E("dlsym", s_dlsym), E("dlclose", s_dlclose), E("dlerror", s_dlerror), E("dladdr", s_dladdr),
    E("dl_iterate_phdr", s_dl_iterate_phdr),
};

void *bionic_lookup(const char *name) {
  if (!a_ctype['a' + 1]) init_ctype();
  for (size_t i = 0; i < sizeof table / sizeof table[0]; i++) {
    if (!strcmp(table[i].name, name)) return table[i].fn;
  }
  return dlsym(RTLD_DEFAULT, name);
}

// An import nobody provides: only fatal if it is actually called, and then it says what was missing.
#define STUBS 512
static const char *stub_names[STUBS];
static int stub_count;
void stub_hit(int i) {
  fprintf(stderr, "[apksim] native code called unsupported function: %s\n", i < stub_count ? stub_names[i] : "?");
  errno = ENOSYS;
}
extern char stub_table[];
__asm__(".text\nstub_common:\n  push %rbp\n  mov %eax, %edi\n  call stub_hit@PLT\n  mov $-1, %rax\n  pop %rbp\n  ret\n"
        ".balign 16\n.globl stub_table\nstub_table:\n"
        ".set i, 0\n.rept 512\n  .balign 16\n  mov $i, %eax\n  jmp stub_common\n.set i, i+1\n.endr\n");

void *missing_stub(const char *name) {
  fprintf(stderr, "[apksim] unresolved native import: %s\n", name);
  if (stub_count >= STUBS) return NULL;
  stub_names[stub_count] = strdup(name);
  return stub_table + 16 * stub_count++;
}
