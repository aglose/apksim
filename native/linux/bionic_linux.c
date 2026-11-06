// The Android C library as seen by loaded ELF code (x86_64 or arm64), on top of glibc. Both sit on the same Linux
// kernel ABI and the same calling convention (SysV on x86_64, AAPCS64 with the same va_list on arm64), so most
// functions are found by name in glibc. What is here differs: Android-only functions, sysconf and getaddrinfo
// constants, addrinfo's field order, the 8-byte sigset_t, jmp_buf sizes, fortify checks glibc lacks, and on arm64
// glibc's larger pthread_mutex_t and pthread_attr_t. The macOS equivalent is ../bionic.c.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <locale.h>
#include <netdb.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include "../elfloader.h"

#ifdef __x86_64__
void *elf_tls_get_addr(void *index);
#endif

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
static int s_log_is_loggable(int prio, const char *tag, int def) { return prio >= def; }

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
// Bionic's setlocale: Android has only "C" and "C.UTF-8", and setting one changes nothing else. Passing it on would
// change this process's locale under the JVM's threads (macOS's setlocale is not safe against a thread formatting a
// string: see the launcher's LC_NUMERIC), and would give native code the host's decimal point.
static int b_locale_utf8 = 1;
static char *s_setlocale(int category, const char *name) {
  if (category < 0 || category > 12) { errno = EINVAL; return NULL; }  // LC_CTYPE..LC_IDENTIFICATION, LC_ALL is 6
  if (name) {
    if (*name && strcmp(name, "C") && strcmp(name, "C.UTF-8") && strcmp(name, "en_US.UTF-8") && strcmp(name, "POSIX")) {
      errno = ENOENT;
      return NULL;
    }
    b_locale_utf8 = !*name || strstr(name, "UTF-8") != NULL;
  }
  return b_locale_utf8 ? "C.UTF-8" : "C";
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

// setjmp: bionic's jmp_buf is 88 bytes on x86_64 and 256 on arm64, while glibc's setjmp and sigsetjmp also save the
// signal mask after the registers, 200 and 312 bytes in all: past the end of Android's buffer (libpng keeps one in its
// png_struct). glibc's _setjmp saves the registers only (68 and 180 bytes), and the mask is the JVM's anyway: the
// sigprocmask above never changes it. glibc has no `sigsetjmp` symbol (a macro there), so that one was missing too.
// These must be glibc's own entry points, not wrappers: setjmp returns twice into its caller's frame.

#ifdef __aarch64__
// ---------------------------------------------------------------- pthread (arm64)
// Android's pthread_mutex_t is 40 bytes and pthread_attr_t 56; glibc's are 48 and 64 on arm64 (on x86_64 they match,
// so glibc's functions take Android's objects as they are). Each such object holds a pointer to a real glibc one,
// made on first use, which also handles Android's all-zero static initialisers, as ../bionic.c does on macOS.
// Condition variables and rwlocks are the same size on both and stay glibc's, but a wait takes the mutex's glibc twin.
typedef struct { _Atomic(void *) host; } Shell;

// Android's mutexes are only int-aligned, while an atomic pointer must be 8-byte aligned: 40 bytes leave an aligned slot.
static inline Shell *shell_of(void *object) { return (Shell *)(((uintptr_t)object + 7) & ~7UL); }
static inline int is_static_init(void *p) { return (uintptr_t)p < 0x100000; }

static pthread_mutex_t *host_mutex(void *m) {
  Shell *s = shell_of(m);
  void *h = atomic_load(&s->host);
  if (!is_static_init(h)) return h;
  pthread_mutex_t *n = malloc(sizeof *n);
  pthread_mutexattr_t a;
  pthread_mutexattr_init(&a);
  // Android's static initialisers put the mutex type in the first word, in bits 14-15.
  int kind = (*(int *)m >> 14) & 3;
  pthread_mutexattr_settype(&a, kind == 1 ? PTHREAD_MUTEX_RECURSIVE : kind == 2 ? PTHREAD_MUTEX_ERRORCHECK : PTHREAD_MUTEX_NORMAL);
  pthread_mutex_init(n, &a);
  pthread_mutexattr_destroy(&a);
  if (atomic_compare_exchange_strong(&s->host, &h, n)) return n;
  pthread_mutex_destroy(n);
  free(n);
  return atomic_load(&s->host);
}
// pthread_mutexattr_t is 8 bytes on both, so the attribute is glibc's own and passes straight through.
static int s_mutex_init(void *m, const pthread_mutexattr_t *attr) {
  pthread_mutex_t *n = malloc(sizeof *n);
  int r = pthread_mutex_init(n, attr);
  atomic_store(&shell_of(m)->host, n);
  return r;
}
static int s_mutex_destroy(void *m) {
  void *h = atomic_exchange(&shell_of(m)->host, NULL);
  if (!is_static_init(h)) {
    pthread_mutex_destroy(h);
    free(h);
  }
  return 0;
}
static int s_mutex_lock(void *m) { return pthread_mutex_lock(host_mutex(m)); }
static int s_mutex_trylock(void *m) { return pthread_mutex_trylock(host_mutex(m)); }
static int s_mutex_unlock(void *m) { return pthread_mutex_unlock(host_mutex(m)); }
static int s_mutex_timedlock(void *m, const struct timespec *t) { return pthread_mutex_timedlock(host_mutex(m), t); }
static int s_mutex_clocklock(void *m, clockid_t c, const struct timespec *t) { return pthread_mutex_clocklock(host_mutex(m), c, t); }

static int s_cond_wait(pthread_cond_t *c, void *m) { return pthread_cond_wait(c, host_mutex(m)); }
static int s_cond_timedwait(pthread_cond_t *c, void *m, const struct timespec *t) { return pthread_cond_timedwait(c, host_mutex(m), t); }
static int s_cond_clockwait(pthread_cond_t *c, void *m, clockid_t k, const struct timespec *t) {
  return pthread_cond_clockwait(c, host_mutex(m), k, t);
}
static int s_cond_timedwait_monotonic(pthread_cond_t *c, void *m, const struct timespec *t) {
  return pthread_cond_clockwait(c, host_mutex(m), CLOCK_MONOTONIC, t);
}

// pthread_attr_t: Android's 56 bytes hold a pointer to a glibc attribute made by pthread_attr_init (or
// pthread_getattr_np), and freed by pthread_attr_destroy.
static pthread_attr_t *host_attr(void *a) { return a ? atomic_load(&shell_of(a)->host) : NULL; }
static int s_attr_init(void *a) {
  pthread_attr_t *h = malloc(sizeof *h);
  int r = pthread_attr_init(h);
  atomic_store(&shell_of(a)->host, h);
  return r;
}
static int s_attr_destroy(void *a) {
  pthread_attr_t *h = atomic_exchange(&shell_of(a)->host, NULL);
  if (h) {
    pthread_attr_destroy(h);
    free(h);
  }
  return 0;
}
static int s_getattr_np(pthread_t t, void *a) {
  pthread_attr_t *h = malloc(sizeof *h);
  int r = pthread_getattr_np(t, h);
  if (r) {
    free(h);
    return r;
  }
  atomic_store(&shell_of(a)->host, h);
  return 0;
}
static int s_pthread_create(pthread_t *t, void *a, void *(*fn)(void *), void *arg) { return pthread_create(t, host_attr(a), fn, arg); }
#define ATTR1(name, T) static int s_attr_##name(void *a, T v) { return pthread_attr_##name(host_attr(a), v); }
#define ATTR2(name, T, U) static int s_attr_##name(void *a, T v, U w) { return pthread_attr_##name(host_attr(a), v, w); }
ATTR1(setdetachstate, int) ATTR1(getdetachstate, int *) ATTR1(setstacksize, size_t) ATTR1(getstacksize, size_t *)
ATTR2(setstack, void *, size_t) ATTR2(getstack, void **, size_t *) ATTR1(setguardsize, size_t) ATTR1(getguardsize, size_t *)
ATTR1(setschedparam, const struct sched_param *) ATTR1(getschedparam, struct sched_param *) ATTR1(setschedpolicy, int)
ATTR1(getschedpolicy, int *) ATTR1(setinheritsched, int) ATTR1(getinheritsched, int *) ATTR1(setscope, int) ATTR1(getscope, int *)
#endif

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
    E("__errno", s_errno), E("_ctype_", a_ctype), E("__sF", sF),
#ifdef __x86_64__
    E("__tls_get_addr", elf_tls_get_addr),
#else
    E("pthread_mutex_init", s_mutex_init), E("pthread_mutex_destroy", s_mutex_destroy), E("pthread_mutex_lock", s_mutex_lock),
    E("pthread_mutex_trylock", s_mutex_trylock), E("pthread_mutex_unlock", s_mutex_unlock),
    E("pthread_mutex_timedlock", s_mutex_timedlock), E("pthread_mutex_clocklock", s_mutex_clocklock),
    E("pthread_cond_wait", s_cond_wait), E("pthread_cond_timedwait", s_cond_timedwait), E("pthread_cond_clockwait", s_cond_clockwait),
    E("pthread_cond_timedwait_monotonic_np", s_cond_timedwait_monotonic),
    E("pthread_attr_init", s_attr_init), E("pthread_attr_destroy", s_attr_destroy), E("pthread_getattr_np", s_getattr_np),
    E("pthread_create", s_pthread_create),
    E("pthread_attr_setdetachstate", s_attr_setdetachstate), E("pthread_attr_getdetachstate", s_attr_getdetachstate),
    E("pthread_attr_setstacksize", s_attr_setstacksize), E("pthread_attr_getstacksize", s_attr_getstacksize),
    E("pthread_attr_setstack", s_attr_setstack), E("pthread_attr_getstack", s_attr_getstack),
    E("pthread_attr_setguardsize", s_attr_setguardsize), E("pthread_attr_getguardsize", s_attr_getguardsize),
    E("pthread_attr_setschedparam", s_attr_setschedparam), E("pthread_attr_getschedparam", s_attr_getschedparam),
    E("pthread_attr_setschedpolicy", s_attr_setschedpolicy), E("pthread_attr_getschedpolicy", s_attr_getschedpolicy),
    E("pthread_attr_setinheritsched", s_attr_setinheritsched), E("pthread_attr_getinheritsched", s_attr_getinheritsched),
    E("pthread_attr_setscope", s_attr_setscope), E("pthread_attr_getscope", s_attr_getscope),
#endif
    E("setjmp", _setjmp), E("sigsetjmp", _setjmp), E("_setjmp", _setjmp),
    E("longjmp", _longjmp), E("siglongjmp", _longjmp), E("_longjmp", _longjmp),
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
    E("newlocale", s_newlocale), E("setlocale", s_setlocale), E("gettid", s_gettid),
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
#ifdef __x86_64__
#define STUB_SIZE 16
__asm__(".text\nstub_common:\n  push %rbp\n  mov %eax, %edi\n  call stub_hit@PLT\n  mov $-1, %rax\n  pop %rbp\n  ret\n"
        ".balign 16\n.globl stub_table\nstub_table:\n"
        ".set i, 0\n.rept 512\n  .balign 16\n  mov $i, %eax\n  jmp stub_common\n.set i, i+1\n.endr\n");
#else
#define STUB_SIZE 8
__asm__(".text\n.p2align 2\nstub_common:\n  stp x29, x30, [sp, #-16]!\n  mov x29, sp\n  mov w0, w16\n  bl stub_hit\n"
        "  mov x0, #-1\n  ldp x29, x30, [sp], #16\n  ret\n"
        ".p2align 2\n.globl stub_table\nstub_table:\n"
        ".set i, 0\n.rept 512\n  mov x16, #i\n  b stub_common\n.set i, i+1\n.endr\n");
#endif

void *missing_stub(const char *name) {
  fprintf(stderr, "[apksim] unresolved native import: %s\n", name);
  if (stub_count >= STUBS) return NULL;
  stub_names[stub_count] = strdup(name);
  return stub_table + STUB_SIZE * stub_count++;
}
