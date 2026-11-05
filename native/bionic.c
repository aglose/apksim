// The Android C library as seen by loaded ELF code, on top of macOS libSystem. Most functions have the
// same ABI and are found by name. Everything here differs: struct layouts, constants, errno numbers,
// pthread object sizes, and variadic calling convention (Android passes varargs in registers; Apple
// on the stack).
#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <locale.h>
#include <math.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <xlocale.h>
#include <os/os_sync_wait_on_address.h>

#include "elfloader.h"

void *net_lookup(const char *name);
int net_ioctl(int fd, unsigned long req, void *arg);

// ---------------------------------------------------------------- variadics

// AAPCS64 va_list, as Android code builds it and hands to v*printf and JNI Call*MethodV.
typedef struct {
  char *stack;
  char *gr_top;
  char *vr_top;
  int gr_offs;
  int vr_offs;
} AVaList;

static inline uint64_t va_gr(AVaList *v) {
  if (v->gr_offs < 0) {
    uint64_t r = *(uint64_t *)(v->gr_top + v->gr_offs);
    v->gr_offs += 8;
    return r;
  }
  uint64_t r = *(uint64_t *)v->stack;
  v->stack += 8;
  return r;
}

static inline double va_fp(AVaList *v) {
  if (v->vr_offs < 0) {
    double r = *(double *)(v->vr_top + v->vr_offs);
    v->vr_offs += 16;
    return r;
  }
  double r = *(double *)v->stack;
  v->stack += 8;
  return r;
}

uint64_t aapcs_gr(void *v) { return va_gr(v); }
double aapcs_fp(void *v) { return va_fp(v); }

/** Host vsnprintf over an AAPCS argument list: formats one conversion at a time. */
static int a_vsnprintf(char *out, size_t cap, const char *fmt, AVaList *v) {
  size_t n = 0;
  char tmp[512];
#define EMIT(s, l)                                  \
  do {                                              \
    size_t len_ = (l);                              \
    if (out && n < cap) {                           \
      size_t k_ = len_ < cap - n ? len_ : cap - n;  \
      memcpy(out + n, (s), k_);                     \
    }                                               \
    n += len_;                                      \
  } while (0)
  while (*fmt) {
    if (*fmt != '%') {
      const char *e = strchr(fmt, '%');
      size_t l = e ? (size_t)(e - fmt) : strlen(fmt);
      EMIT(fmt, l);
      fmt += l;
      continue;
    }
    char spec[64];
    int si = 0;
    spec[si++] = *fmt++;
    while (*fmt && strchr("-+ #0'", *fmt) && si < 40) spec[si++] = *fmt++;
    int width = 0, prec = 0, has_w = 0, has_p = 0;
    if (*fmt == '*') {
      width = (int)va_gr(v);
      has_w = 1;
      spec[si++] = '*';
      fmt++;
    } else {
      while (*fmt >= '0' && *fmt <= '9' && si < 50) spec[si++] = *fmt++;
    }
    if (*fmt == '.') {
      spec[si++] = *fmt++;
      if (*fmt == '*') {
        prec = (int)va_gr(v);
        has_p = 1;
        spec[si++] = '*';
        fmt++;
      } else {
        while (*fmt >= '0' && *fmt <= '9' && si < 55) spec[si++] = *fmt++;
      }
    }
    int lng = 0, isL = 0;
    while (*fmt && strchr("hlqjztL", *fmt)) {
      if (*fmt == 'l' || *fmt == 'q' || *fmt == 'j' || *fmt == 'z' || *fmt == 't') lng = 1;
      if (*fmt == 'L') isL = 1;
      spec[si++] = *fmt++;
    }
    char conv = *fmt ? *fmt++ : 0;
    if (!conv) break;
    spec[si++] = conv;
    spec[si] = 0;
    int w;
#define CALL(val)                                                                            \
  (has_w && has_p   ? snprintf(tmp, sizeof tmp, spec, width, prec, val)                      \
   : has_w          ? snprintf(tmp, sizeof tmp, spec, width, val)                            \
   : has_p          ? snprintf(tmp, sizeof tmp, spec, prec, val)                             \
                    : snprintf(tmp, sizeof tmp, spec, val))
    switch (conv) {
      case '%':
        EMIT("%", 1);
        continue;
      case 'd': case 'i': case 'u': case 'x': case 'X': case 'o': case 'c': case 'p': {
        uint64_t x = va_gr(v);
        if (conv == 'p' || lng) w = CALL((long)x);
        else w = CALL((int)x);
        EMIT(tmp, w < (int)sizeof tmp ? w : (int)sizeof tmp - 1);
        break;
      }
      case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': case 'a': case 'A': {
        if (isL) {  // 128-bit long double: no host equivalent, take the slot and print its top half.
          double x = 0;
          if (v->vr_offs < 0) {
            x = *(double *)(v->vr_top + v->vr_offs);
            v->vr_offs += 16;
          } else {
            v->stack = (char *)(((uintptr_t)v->stack + 15) & ~15ULL) + 16;
          }
          spec[si - 2] = conv;
          spec[si - 1] = 0;
          w = CALL(x);
        } else {
          w = CALL(va_fp(v));
        }
        EMIT(tmp, w < (int)sizeof tmp ? w : (int)sizeof tmp - 1);
        break;
      }
      case 's': {
        const char *s = (const char *)va_gr(v);
        if (!s) s = "(null)";
        if (!has_w && !has_p && si == 2) {
          EMIT(s, strlen(s));
        } else {
          size_t need = strlen(s) + 256 + (has_w ? (width < 0 ? -width : width) : 0);
          char *big = malloc(need);
          w = has_w && has_p ? snprintf(big, need, spec, width, prec, s)
              : has_w        ? snprintf(big, need, spec, width, s)
              : has_p        ? snprintf(big, need, spec, prec, s)
                             : snprintf(big, need, spec, s);
          EMIT(big, w);
          free(big);
        }
        break;
      }
      case 'n': {
        int *p = (int *)va_gr(v);
        if (p) *p = (int)n;
        break;
      }
      default:
        EMIT(spec, si);
        break;
    }
  }
  if (out && cap) out[n < cap ? n : cap - 1] = 0;
  return (int)n;
}

// Thunks for variadic functions: spill the argument registers into an AAPCS va_list, then call the C
// implementation with the fixed arguments and that list. Saved layout from sp: x0-x7, q0-q7, list.
#define FRAME 256
#define THUNK(sym, nfixed, impl)                                                                 \
  __asm__(".text\n.p2align 2\n_" #sym ":\n"                                                       \
          "  sub sp, sp, #" "288" "\n"                                                            \
          "  stp x29, x30, [sp, #256]\n  add x29, sp, #256\n"                                     \
          "  stp x0, x1, [sp, #0]\n  stp x2, x3, [sp, #16]\n  stp x4, x5, [sp, #32]\n  stp x6, x7, [sp, #48]\n" \
          "  stp q0, q1, [sp, #64]\n  stp q2, q3, [sp, #96]\n  stp q4, q5, [sp, #128]\n  stp q6, q7, [sp, #160]\n" \
          "  add x9, sp, #288\n  str x9, [sp, #192]\n"                     /* stack */            \
          "  add x9, sp, #64\n  str x9, [sp, #200]\n"                      /* gr_top */           \
          "  add x9, sp, #192\n  str x9, [sp, #208]\n"                     /* vr_top */           \
          "  mov w9, #-(8-" #nfixed ")*8\n  str w9, [sp, #216]\n"          /* gr_offs */          \
          "  mov w9, #-128\n  str w9, [sp, #220]\n"                        /* vr_offs */          \
          "  add x" #nfixed ", sp, #192\n"                                                        \
          "  bl _" #impl "\n"                                                                     \
          "  ldp x29, x30, [sp, #256]\n  add sp, sp, #288\n  ret\n");                             \
  extern void sym(void);

static FILE *map_file(FILE *f);

int impl_snprintf(char *b, size_t n, const char *f, AVaList *v) { return a_vsnprintf(b, n, f, v); }
int impl_sprintf(char *b, const char *f, AVaList *v) { return a_vsnprintf(b, (size_t)-1 >> 1, f, v); }
int impl_fprintf(FILE *o, const char *f, AVaList *v) {
  char buf[4096];
  int n = a_vsnprintf(buf, sizeof buf, f, v);
  fwrite(buf, 1, n < (int)sizeof buf ? n : (int)sizeof buf - 1, map_file(o));
  return n;
}
int impl_printf(const char *f, AVaList *v) { return impl_fprintf(stdout, f, v); }
static const char *log_level(int p) {
  static const char *names = "??VDIWEF";
  static char s[2];
  s[0] = p >= 0 && p < 8 ? names[p] : '?';
  return s;
}
int impl_log_print(int prio, const char *tag, const char *f, AVaList *v) {
  char buf[4096];
  a_vsnprintf(buf, sizeof buf, f, v);
  fprintf(stderr, "%s/%s: %s\n", log_level(prio), tag ? tag : "", buf);
  return 1;
}
void impl_log_assert(const char *cond, const char *tag, const char *f, AVaList *v) {
  char buf[4096];
  if (f) a_vsnprintf(buf, sizeof buf, f, v);
  else strcpy(buf, "assertion failed");
  fprintf(stderr, "F/%s: %s\n", tag ? tag : "", buf);
  abort();
}
int impl_snprintf_chk(char *b, size_t n, int flag, size_t dst, const char *f, AVaList *v) { return a_vsnprintf(b, n, f, v); }
int impl_sprintf_chk(char *b, int flag, size_t dst, const char *f, AVaList *v) { return a_vsnprintf(b, dst, f, v); }

__asm__(".globl _snprintf_thunk\n");
THUNK(snprintf_thunk, 3, impl_snprintf)
THUNK(sprintf_thunk, 2, impl_sprintf)
THUNK(fprintf_thunk, 2, impl_fprintf)
THUNK(printf_thunk, 1, impl_printf)
THUNK(log_print_thunk, 3, impl_log_print)
THUNK(log_assert_thunk, 3, impl_log_assert)
THUNK(snprintf_chk_thunk, 5, impl_snprintf_chk)
THUNK(sprintf_chk_thunk, 4, impl_sprintf_chk)

static int s_vsnprintf(char *b, size_t n, const char *f, AVaList *v) { AVaList c = *v; return a_vsnprintf(b, n, f, &c); }
static int s_vsprintf(char *b, const char *f, AVaList *v) { AVaList c = *v; return a_vsnprintf(b, (size_t)-1 >> 1, f, &c); }
static int s_vfprintf(FILE *o, const char *f, AVaList *v) { AVaList c = *v; return impl_fprintf(o, f, &c); }
static int s_vasprintf(char **out, const char *f, AVaList *v) {
  AVaList c = *v;
  int n = a_vsnprintf(NULL, 0, f, &c);
  *out = malloc(n + 1);
  c = *v;
  return a_vsnprintf(*out, n + 1, f, &c);
}
static int s_vsnprintf_chk(char *b, size_t n, int flag, size_t dst, const char *f, AVaList *v) { return s_vsnprintf(b, n, f, v); }
static int s_log_vprint(int prio, const char *tag, const char *f, AVaList *v) {
  AVaList c = *v;
  return impl_log_print(prio, tag, f, &c);
}
static int s_log_write(int prio, const char *tag, const char *msg) {
  fprintf(stderr, "%s/%s: %s\n", log_level(prio), tag ? tag : "", msg ? msg : "");
  return 1;
}
#define MAX_SCAN 12
int impl_sscanf(const char *s, const char *f, AVaList *v) {
  void *p[MAX_SCAN];
  for (int i = 0; i < MAX_SCAN; i++) p[i] = (void *)va_gr(v);
  return sscanf(s, f, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11]);
}
THUNK(sscanf_thunk, 2, impl_sscanf)
int s_vsscanf(const char *s, const char *f, AVaList *v) { AVaList c = *v; return impl_sscanf(s, f, &c); }
int impl_fscanf(FILE *in, const char *f, AVaList *v) {
  void *p[MAX_SCAN];
  for (int i = 0; i < MAX_SCAN; i++) p[i] = (void *)va_gr(v);
  return fscanf(map_file(in), f, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11]);
}
THUNK(fscanf_thunk, 2, impl_fscanf)
// Android's syslog goes to the log; here to stderr, like __android_log_print. libSystem's would read the arguments
// the Apple way and crash (libwebrtc logs through it).
void impl_syslog(int prio, const char *f, AVaList *v) {
  char buf[4096];
  a_vsnprintf(buf, sizeof buf, f, v);
  static const char android_prio[8] = {7, 7, 7, 6, 5, 4, 4, 3};  // LOG_EMERG..LOG_DEBUG -> F F F E W I I D
  fprintf(stderr, "%s/syslog: %s\n", log_level(android_prio[prio & 7]), buf);
}
THUNK(syslog_thunk, 2, impl_syslog)
static void s_vsyslog(int prio, const char *f, AVaList *v) {
  AVaList c = *v;
  impl_syslog(prio, f, &c);
}

// ---------------------------------------------------------------- errno

static const short errno_a2d[134] = {
    [1] = EPERM, [2] = ENOENT, [3] = ESRCH, [4] = EINTR, [5] = EIO, [6] = ENXIO, [7] = E2BIG, [8] = ENOEXEC, [9] = EBADF,
    [10] = ECHILD, [11] = EAGAIN, [12] = ENOMEM, [13] = EACCES, [14] = EFAULT, [15] = ENOTBLK, [16] = EBUSY, [17] = EEXIST,
    [18] = EXDEV, [19] = ENODEV, [20] = ENOTDIR, [21] = EISDIR, [22] = EINVAL, [23] = ENFILE, [24] = EMFILE, [25] = ENOTTY,
    [26] = ETXTBSY, [27] = EFBIG, [28] = ENOSPC, [29] = ESPIPE, [30] = EROFS, [31] = EMLINK, [32] = EPIPE, [33] = EDOM,
    [34] = ERANGE, [35] = EDEADLK, [36] = ENAMETOOLONG, [37] = ENOLCK, [38] = ENOSYS, [39] = ENOTEMPTY, [40] = ELOOP,
    [61] = ENODATA, [71] = EPROTO, [75] = EOVERFLOW, [84] = EILSEQ, [88] = ENOTSOCK, [89] = EDESTADDRREQ, [90] = EMSGSIZE,
    [91] = EPROTOTYPE, [92] = ENOPROTOOPT, [93] = EPROTONOSUPPORT, [95] = EOPNOTSUPP, [97] = EAFNOSUPPORT, [98] = EADDRINUSE,
    [99] = EADDRNOTAVAIL, [100] = ENETDOWN, [101] = ENETUNREACH, [103] = ECONNABORTED, [104] = ECONNRESET, [105] = ENOBUFS,
    [106] = EISCONN, [107] = ENOTCONN, [110] = ETIMEDOUT, [111] = ECONNREFUSED, [113] = EHOSTUNREACH, [114] = EALREADY,
    [115] = EINPROGRESS, [125] = ECANCELED,
};

static int a2d_errno(int a) { return a > 0 && a < 134 && errno_a2d[a] ? errno_a2d[a] : a; }

// The reverse of errno_a2d, by host errno (lowest Android value wins, as a scan from 1 would): every errno read by
// native code goes through it.
static unsigned char errno_d2a[ELAST + 1];

__attribute__((constructor)) static void init_errno_d2a(void) {
  for (int a = 133; a > 0; a--) {
    if (errno_a2d[a] > 0 && errno_a2d[a] <= ELAST) errno_d2a[errno_a2d[a]] = (unsigned char)a;
  }
}

static int d2a_errno(int d) { return d > 0 && d <= ELAST && errno_d2a[d] ? errno_d2a[d] : d; }

static __thread int t_errno, t_last;

int bionic_errno_to_android(int host) { return d2a_errno(host); }

static int *s_errno(void) {
  // Android code both reads and assigns errno through this pointer, so sync in both directions.
  if (t_errno != t_last) errno = a2d_errno(t_errno);
  t_errno = t_last = d2a_errno(errno);
  return &t_errno;
}

// ---------------------------------------------------------------- pthread

// Android's pthread objects are smaller than Apple's. Each holds a pointer to a real host object made
// on first use, which also handles Android's all-zero static initialisers.
typedef struct { _Atomic(void *) host; } Shell;

// Android's objects are only int-aligned, while an atomic pointer must be 8-byte aligned. They are all
// at least 40 bytes, so there is always an aligned slot inside.
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

static int s_mutexattr_init(long *a) { *a = 0; return 0; }
static int s_mutexattr_destroy(long *a) { return 0; }
static int s_mutexattr_settype(long *a, int t) { *a = t; return 0; }
static int s_mutexattr_gettype(long *a, int *t) { *t = (int)*a; return 0; }
static int s_mutex_init(void *m, long *attr) {
  pthread_mutex_t *n = malloc(sizeof *n);
  pthread_mutexattr_t a;
  pthread_mutexattr_init(&a);
  int t = attr ? (int)*attr : 0;
  pthread_mutexattr_settype(&a, t == 1 ? PTHREAD_MUTEX_RECURSIVE : t == 2 ? PTHREAD_MUTEX_ERRORCHECK : PTHREAD_MUTEX_NORMAL);
  int r = pthread_mutex_init(n, &a);
  pthread_mutexattr_destroy(&a);
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
static int s_mutex_lock(void *m) { return d2a_errno(pthread_mutex_lock(host_mutex(m))); }
static int s_mutex_trylock(void *m) { return d2a_errno(pthread_mutex_trylock(host_mutex(m))); }
static int s_mutex_unlock(void *m) { return d2a_errno(pthread_mutex_unlock(host_mutex(m))); }

static pthread_cond_t *host_cond(void *c) {
  Shell *s = shell_of(c);
  void *h = atomic_load(&s->host);
  if (h) return h;
  pthread_cond_t *n = malloc(sizeof *n);
  pthread_cond_init(n, NULL);
  if (atomic_compare_exchange_strong(&s->host, &h, n)) return n;
  pthread_cond_destroy(n);
  free(n);
  return atomic_load(&s->host);
}
// The clock a condition variable's timed waits measure their deadline on, kept beside its host pointer (Android's
// pthread_cond_t is 48 bytes): 0 is CLOCK_REALTIME, which the all-zero static initialiser means, 1 CLOCK_MONOTONIC.
typedef struct { _Atomic(void *) host; int clock; } CondShell;
static inline CondShell *cond_shell(void *c) { return (CondShell *)shell_of(c); }

static int s_cond_init(void *c, void *a) {
  atomic_store(&shell_of(c)->host, NULL);
  // Android's pthread_condattr_t is a long; ours holds the clock pthread_condattr_setclock set (see s_condattr_init).
  cond_shell(c)->clock = a ? (int)*(long *)a : 0;
  return 0;
}
static int s_cond_destroy(void *c) {
  void *h = atomic_exchange(&shell_of(c)->host, NULL);
  if (h) {
    pthread_cond_destroy(h);
    free(h);
  }
  return 0;
}
static int s_cond_signal(void *c) { return pthread_cond_signal(host_cond(c)); }
static int s_cond_broadcast(void *c) { return pthread_cond_broadcast(host_cond(c)); }
static int s_cond_wait(void *c, void *m) { return d2a_errno(pthread_cond_wait(host_cond(c), host_mutex(m))); }
static int s_clock_gettime(int id, struct timespec *t);
// A deadline on Android's clock [clk] (0 realtime, 1 monotonic). Apple's pthread_cond_timedwait takes realtime
// deadlines only: a monotonic one (seconds since boot) read as realtime is long past, so every wait returned at
// once and callers that wait in a loop spun a core each (WebRTC's threads: two cores for as long as an app ran).
// So a monotonic deadline becomes a relative wait, measured from the same clock clock_gettime gives the app.
static int cond_wait_until(void *c, void *m, int clk, const struct timespec *t) {
  if (clk == 0) return d2a_errno(pthread_cond_timedwait(host_cond(c), host_mutex(m), t));
  struct timespec now, rel;
  s_clock_gettime(clk, &now);
  rel.tv_sec = t->tv_sec - now.tv_sec;
  rel.tv_nsec = t->tv_nsec - now.tv_nsec;
  if (rel.tv_nsec < 0) { rel.tv_nsec += 1000000000L; rel.tv_sec--; }
  if (rel.tv_sec < 0) return d2a_errno(ETIMEDOUT);
  return d2a_errno(pthread_cond_timedwait_relative_np(host_cond(c), host_mutex(m), &rel));
}
static int s_cond_timedwait(void *c, void *m, const struct timespec *t) { return cond_wait_until(c, m, cond_shell(c)->clock, t); }
static int s_cond_timedwait_monotonic(void *c, void *m, const struct timespec *t) { return cond_wait_until(c, m, 1, t); }
static int s_cond_clockwait(void *c, void *m, int clk, const struct timespec *t) {
  if (clk != 0 && clk != 1) return 22;  // EINVAL: bionic takes CLOCK_REALTIME and CLOCK_MONOTONIC only
  return cond_wait_until(c, m, clk, t);
}
static int s_condattr_init(void *a) { *(long *)a = 0; return 0; }
static int s_condattr_noop(void *a) { return 0; }
static int s_condattr_setclock(void *a, int clk) {
  if (clk != 0 && clk != 1) return 22;
  *(long *)a = clk;
  return 0;
}
static int s_condattr_getclock(const void *a, int *clk) { *clk = (int)*(const long *)a; return 0; }

static pthread_rwlock_t *host_rwlock(void *l) {
  Shell *s = shell_of(l);
  void *h = atomic_load(&s->host);
  if (h) return h;
  pthread_rwlock_t *n = malloc(sizeof *n);
  pthread_rwlock_init(n, NULL);
  if (atomic_compare_exchange_strong(&s->host, &h, n)) return n;
  pthread_rwlock_destroy(n);
  free(n);
  return atomic_load(&s->host);
}
static int s_rwlock_init(void *l, void *a) { atomic_store(&shell_of(l)->host, NULL); return 0; }
static int s_rwlock_destroy(void *l) {
  void *h = atomic_exchange(&shell_of(l)->host, NULL);
  if (h) {
    pthread_rwlock_destroy(h);
    free(h);
  }
  return 0;
}
static int s_rwlock_rdlock(void *l) { return pthread_rwlock_rdlock(host_rwlock(l)); }
static int s_rwlock_wrlock(void *l) { return pthread_rwlock_wrlock(host_rwlock(l)); }
static int s_rwlock_tryrdlock(void *l) { return d2a_errno(pthread_rwlock_tryrdlock(host_rwlock(l))); }
static int s_rwlock_trywrlock(void *l) { return d2a_errno(pthread_rwlock_trywrlock(host_rwlock(l))); }
static int s_rwlock_unlock(void *l) { return pthread_rwlock_unlock(host_rwlock(l)); }

static int s_once(_Atomic int *once, void (*fn)(void)) {
  int expect = 0;
  if (atomic_compare_exchange_strong(once, &expect, 1)) {
    fn();
    atomic_store(once, 2);
    return 0;
  }
  while (atomic_load(once) != 2) sched_yield();
  return 0;
}

// Android pthread_key_t is int; Apple's is unsigned long.
static int s_key_create(int *k, void (*d)(void *)) {
  pthread_key_t key;
  int r = pthread_key_create(&key, d);
  *k = (int)key;
  return r;
}
static int s_key_delete(int k) { return pthread_key_delete((pthread_key_t)(unsigned)k); }
static void *s_getspecific(int k) { return pthread_getspecific((pthread_key_t)(unsigned)k); }
static int s_setspecific(int k, const void *v) { return pthread_setspecific((pthread_key_t)(unsigned)k, v); }

// pthread_attr_t: holds the settings until pthread_create.
typedef struct { int detach; size_t stack; } AAttr;
static int s_attr_init(AAttr *a) { memset(a, 0, sizeof *a); return 0; }
static int s_attr_destroy(AAttr *a) { return 0; }
static int s_attr_setdetachstate(AAttr *a, int d) { a->detach = d; return 0; }
static int s_attr_getdetachstate(AAttr *a, int *d) { *d = a->detach; return 0; }
static int s_attr_setstacksize(AAttr *a, size_t s) { a->stack = s; return 0; }
static int s_attr_getstacksize(AAttr *a, size_t *s) { *s = a->stack ? a->stack : 1 << 20; return 0; }
static int s_attr_setguardsize(AAttr *a, size_t s) { return 0; }
static int s_attr_setschedparam(AAttr *a, void *p) { return 0; }
static int s_attr_setschedpolicy(AAttr *a, int p) { return 0; }
static int s_attr_setinheritsched(AAttr *a, int p) { return 0; }

static int s_pthread_create(pthread_t *t, const AAttr *a, void *(*fn)(void *), void *arg) {
  pthread_attr_t h;
  pthread_attr_init(&h);
  size_t stack = a && a->stack ? a->stack : 1 << 20;
  size_t page = 16384;
  pthread_attr_setstacksize(&h, (stack + page - 1) & ~(page - 1));
  if (a && a->detach) pthread_attr_setdetachstate(&h, PTHREAD_CREATE_DETACHED);
  int r = pthread_create(t, &h, fn, arg);
  pthread_attr_destroy(&h);
  return d2a_errno(r);
}
static int s_pthread_setname(pthread_t t, const char *name) {
  if (pthread_equal(t, pthread_self())) pthread_setname_np(name);
  return 0;
}
static int s_setschedparam(pthread_t t, int p, void *x) { return 0; }
static int s_atfork(void (*a)(void), void (*b)(void), void (*c)(void), void *d) { return 0; }
static long s_gettid(void) {
  uint64_t id;
  pthread_threadid_np(NULL, &id);
  return (long)id;
}

// ---------------------------------------------------------------- files

typedef struct {
  uint64_t st_dev, st_ino;
  uint32_t st_mode, st_nlink, st_uid, st_gid;
  uint64_t st_rdev, pad1;
  int64_t st_size;
  int32_t st_blksize, pad2;
  int64_t st_blocks;
  struct timespec st_atim, st_mtim, st_ctim;
  uint32_t unused4, unused5;
} AStat;

static void to_astat(const struct stat *s, AStat *a) {
  memset(a, 0, sizeof *a);
  a->st_dev = s->st_dev;
  a->st_ino = s->st_ino;
  a->st_mode = s->st_mode;
  a->st_nlink = s->st_nlink;
  a->st_uid = s->st_uid;
  a->st_gid = s->st_gid;
  a->st_rdev = s->st_rdev;
  a->st_size = s->st_size;
  a->st_blksize = s->st_blksize;
  a->st_blocks = s->st_blocks;
  a->st_atim = s->st_atimespec;
  a->st_mtim = s->st_mtimespec;
  a->st_ctim = s->st_ctimespec;
}

static int s_stat(const char *p, AStat *a) {
  struct stat s;
  int r = stat(p, &s);
  if (!r) to_astat(&s, a);
  return r;
}
static int s_lstat(const char *p, AStat *a) {
  struct stat s;
  int r = lstat(p, &s);
  if (!r) to_astat(&s, a);
  return r;
}
static int s_fstat(int fd, AStat *a) {
  struct stat s;
  int r = fstat(fd, &s);
  if (!r) to_astat(&s, a);
  return r;
}
static int s_fstatat(int d, const char *p, AStat *a, int flags) {
  struct stat s;
  if (d == -100) d = AT_FDCWD;
  int r = fstatat(d, p, &s, flags & 0x100 ? AT_SYMLINK_NOFOLLOW : 0);
  if (!r) to_astat(&s, a);
  return r;
}

static int map_open_flags(int a) {
  int d = a & 3;
  if (a & 0100) d |= O_CREAT;
  if (a & 0200) d |= O_EXCL;
  if (a & 0400) d |= O_NOCTTY;
  if (a & 01000) d |= O_TRUNC;
  if (a & 02000) d |= O_APPEND;
  if (a & 04000) d |= O_NONBLOCK;
  if (a & 010000) d |= O_DSYNC;
  if (a & 040000) d |= O_DIRECTORY;
  if (a & 0100000) d |= O_NOFOLLOW;
  if (a & 02000000) d |= O_CLOEXEC;
  return d;
}
static int unmap_open_flags(int d) {
  int a = d & 3;
  if (d & O_APPEND) a |= 02000;
  if (d & O_NONBLOCK) a |= 04000;
  return a;
}
// Variadic in C, but the mode is simply in the next register under Android's ABI.
static int s_open(const char *p, int flags, int mode) { return open(p, map_open_flags(flags), mode); }
static int s_open_2(const char *p, int flags) { return open(p, map_open_flags(flags), 0); }
static int s_openat(int d, const char *p, int flags, int mode) { return openat(d == -100 ? AT_FDCWD : d, p, map_open_flags(flags), mode); }

typedef struct { short l_type, l_whence; int64_t l_start, l_len; int l_pid; } AFlock;

static int s_fcntl(int fd, int cmd, long arg) {
  switch (cmd) {
    case 0: return fcntl(fd, F_DUPFD, (int)arg);
    case 1: return fcntl(fd, F_GETFD);
    case 2: return fcntl(fd, F_SETFD, (int)arg);
    case 3: return unmap_open_flags(fcntl(fd, F_GETFL));
    case 4: return fcntl(fd, F_SETFL, map_open_flags((int)arg));
    case 5: case 6: case 7: {
      AFlock *a = (AFlock *)arg;
      struct flock f = {0};
      f.l_start = a->l_start;
      f.l_len = a->l_len;
      f.l_pid = a->l_pid;
      // F_RDLCK/F_WRLCK/F_UNLCK are 0/1/2 on Android, 1/3/2 on macOS.
      f.l_type = a->l_type == 0 ? F_RDLCK : a->l_type == 1 ? F_WRLCK : F_UNLCK;
      f.l_whence = a->l_whence;
      int r = fcntl(fd, cmd == 5 ? F_GETLK : cmd == 6 ? F_SETLK : F_SETLKW, &f);
      if (cmd == 5 && !r) {
        a->l_start = f.l_start;
        a->l_len = f.l_len;
        a->l_pid = f.l_pid;
        a->l_whence = f.l_whence;
        a->l_type = f.l_type == F_RDLCK ? 0 : f.l_type == F_WRLCK ? 1 : 2;
      }
      return r;
    }
    case 1030: return fcntl(fd, F_DUPFD_CLOEXEC, (int)arg);
    default:
      errno = EINVAL;
      return -1;
  }
}
static int s_ioctl(int fd, unsigned long req, void *arg) {
  if (req == 0x5421) return ioctl(fd, FIONBIO, arg);
  if (req == 0x541B) return ioctl(fd, FIONREAD, arg);
  return net_ioctl(fd, req, arg);
}

typedef struct { uint64_t d_ino; int64_t d_off; unsigned short d_reclen; unsigned char d_type; char d_name[256]; } ADirent;
static __thread ADirent t_dirent;
static ADirent *s_readdir(DIR *d) {
  struct dirent *e = readdir(d);
  if (!e) return NULL;
  t_dirent.d_ino = e->d_ino;
  t_dirent.d_type = e->d_type;
  t_dirent.d_reclen = sizeof t_dirent;
  strlcpy(t_dirent.d_name, e->d_name, sizeof t_dirent.d_name);
  return &t_dirent;
}

// Android programs may refer to the standard streams through __sF.
static char sF[3 * 256];
static FILE *map_file(FILE *f) {
  if ((char *)f >= sF && (char *)f < sF + sizeof sF) {
    int i = (int)(((char *)f - sF) / 152);
    return i == 0 ? stdin : i == 1 ? stdout : stderr;
  }
  return f;
}
static FILE *a_stdin, *a_stdout, *a_stderr;
static size_t s_fwrite(const void *p, size_t a, size_t b, FILE *f) { return fwrite(p, a, b, map_file(f)); }
static int s_fputs(const char *s, FILE *f) { return fputs(s, map_file(f)); }
static int s_fputc(int c, FILE *f) { return fputc(c, map_file(f)); }
static int s_fflush(FILE *f) { return fflush(f ? map_file(f) : NULL); }
static int s_ferror(FILE *f) { return ferror(map_file(f)); }

static void *s_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
  int d = flags & 3;
  if (flags & 0x10) d |= MAP_FIXED;
  if (flags & 0x20) d |= MAP_ANON;
  if (flags & 0x4000) d |= MAP_NORESERVE;
  return mmap(addr, len, prot, d, flags & 0x20 ? -1 : fd, off);
}
static void *s_mremap(void *a, size_t o, size_t n, int f, ...) {
  errno = ENOMEM;
  return MAP_FAILED;
}
static int s_madvise(void *a, size_t l, int advice) {
  if (advice == 4) return madvise(a, l, MADV_DONTNEED);
  return 0;
}
static long s_sysconf(int name) {
  switch (name) {
    case 0x27: return 16384;                        // _SC_PAGESIZE
    case 0x60: return sysconf(_SC_NPROCESSORS_CONF);
    case 0x61: return sysconf(_SC_NPROCESSORS_ONLN);
    case 0x62: return sysconf(_SC_PHYS_PAGES);
    case 0x63: return sysconf(_SC_PHYS_PAGES) / 2;
    case 0x06: return 100;                          // _SC_CLK_TCK
    case 0x05: return sysconf(_SC_OPEN_MAX);
    default:
      errno = EINVAL;
      return -1;
  }
}
static unsigned long s_getauxval(unsigned long t) { return t == 6 ? 16384 : 0; }
static int s_clock_gettime(int id, struct timespec *t) {
  clockid_t c;
  switch (id) {
    case 0: case 5: c = CLOCK_REALTIME; break;
    case 2: c = CLOCK_PROCESS_CPUTIME_ID; break;
    case 3: c = CLOCK_THREAD_CPUTIME_ID; break;
    case 4: c = CLOCK_MONOTONIC_RAW; break;
    default: c = CLOCK_MONOTONIC; break;
  }
  return clock_gettime(c, t);
}
// Linux arm64 system call numbers the APK's native code makes through syscall() rather than libc.
enum { SYS_FUTEX = 98, SYS_GETPID = 172, SYS_GETTID = 178, SYS_GETRANDOM = 278 };
enum { FUTEX_WAIT = 0, FUTEX_WAKE = 1, FUTEX_CMD_MASK = 0x7f };

// syscall() with AAPCS arguments: variadic ones travel in registers like named ones, so fixed parameters read them.
// libc++abi's guard for function-local statics records the initializing thread with SYS_gettid and sleeps with
// SYS_futex: when every thread got -1 from here, a second thread reaching a static another was initializing took it
// for recursion and aborted ("__cxa_guard_acquire detected recursive initialization"), now and then at WebRTC start.
static long s_syscall(long n, long a, long b, long c, long d, long e, long f) {
  switch (n) {
    case SYS_GETTID:
      return s_gettid();
    case SYS_GETPID:
      return getpid();
    case SYS_GETRANDOM:
      arc4random_buf((void *)a, (size_t)b);
      return b;
    case SYS_FUTEX: {
      uint32_t *addr = (uint32_t *)a;
      int op = (int)b & FUTEX_CMD_MASK;
      if (op == FUTEX_WAKE) {
        int woken = 0;
        if ((int)c == 1) {
          woken = os_sync_wake_by_address_any(addr, 4, OS_SYNC_WAKE_BY_ADDRESS_NONE) == 0;
        } else if ((int)c > 1) {
          woken = os_sync_wake_by_address_all(addr, 4, OS_SYNC_WAKE_BY_ADDRESS_NONE) == 0;
        }
        return woken;
      }
      if (op == FUTEX_WAIT) {
        if (atomic_load((_Atomic uint32_t *)addr) != (uint32_t)c) {
          errno = EAGAIN;
          return -1;
        }
        const struct timespec *timeout = (const struct timespec *)d;  // relative; same layout as Linux arm64
        int r = timeout ? os_sync_wait_on_address_with_timeout(addr, (uint32_t)c, 4, OS_SYNC_WAIT_ON_ADDRESS_NONE,
                                                               OS_CLOCK_MACH_ABSOLUTE_TIME,
                                                               (uint64_t)timeout->tv_sec * 1000000000ull + timeout->tv_nsec)
                        : os_sync_wait_on_address(addr, (uint32_t)c, 4, OS_SYNC_WAIT_ON_ADDRESS_NONE);
        // A spurious return is allowed: callers check the word again.
        return r < 0 ? -1 : 0;
      }
      break;
    }
  }
  errno = ENOSYS;
  return -1;
}
static int s_prctl(int o, long a, long b, long c, long d) { return o == 15 ? 0 : (errno = EINVAL, -1); }
static int s_system_property_get(const char *n, char *v) {
  if (v) v[0] = 0;
  return 0;
}
static int s_api_level(void) { return 36; }
// As liblog with no log.tag property set: the caller's default priority decides. Answering yes to everything made
// native code format (and print) every verbose and debug line.
static int s_log_is_loggable(int prio, const char *tag, int default_prio) { return prio >= default_prio; }
static void s_stack_chk_fail(void) {
  fprintf(stderr, "[apksim] stack corruption detected in native code\n");
  abort();
}
static void s_set_abort_message(const char *m) { fprintf(stderr, "F/libc: %s\n", m); }

// Signals belong to the JVM: registering handlers from a library would break it.
static int s_sigaction(int s, const void *a, void *o) {
  if (o) memset(o, 0, 32);
  return 0;
}
static void *s_signal(int s, void *h) { return NULL; }
static int s_sigemptyset(uint64_t *m) { *m = 0; return 0; }
static int s_sigfillset(uint64_t *m) { *m = ~0ULL; return 0; }
static int s_sigaddset(uint64_t *m, int s) { *m |= 1ULL << (s - 1); return 0; }
static int s_sigdelset(uint64_t *m, int s) { *m &= ~(1ULL << (s - 1)); return 0; }
static int s_sigprocmask(int h, const uint64_t *m, uint64_t *o) {
  if (o) *o = 0;
  return 0;
}

// fortify variants
static void *s_memcpy_chk(void *d, const void *s, size_t n, size_t c) { return memcpy(d, s, n); }
static void *s_memmove_chk(void *d, const void *s, size_t n, size_t c) { return memmove(d, s, n); }
static void *s_memset_chk(void *d, int v, size_t n, size_t c) { return memset(d, v, n); }
static size_t s_strlen_chk(const char *s, size_t c) { return strlen(s); }
static char *s_strchr_chk(const char *s, int ch, size_t c) { return strchr(s, ch); }
static char *s_strrchr_chk(const char *s, int ch, size_t c) { return strrchr(s, ch); }
static char *s_strcpy_chk(char *d, const char *s, size_t c) { return strcpy(d, s); }
static char *s_strcat_chk(char *d, const char *s, size_t c) { return strcat(d, s); }
static char *s_strncat_chk(char *d, const char *s, size_t n, size_t c) { return strncat(d, s, n); }
static char *s_strncpy_chk(char *d, const char *s, size_t n, size_t c) { return strncpy(d, s, n); }
static size_t s_strlcpy_chk(char *d, const char *s, size_t n, size_t c) { return strlcpy(d, s, n); }
static size_t s_strlcat_chk(char *d, const char *s, size_t n, size_t c) { return strlcat(d, s, n); }
static ssize_t s_read_chk(int f, void *b, size_t n, size_t c) { return read(f, b, n); }
static void s_fd_set_chk(int fd, fd_set *s, size_t c) { FD_SET(fd, s); }
static void s_fd_clr_chk(int fd, fd_set *s, size_t c) { FD_CLR(fd, s); }
static int s_fd_isset_chk(int fd, const fd_set *s, size_t c) { return FD_ISSET(fd, s); }

// Host equivalents with different names, and things macOS has no notion of.
static void s_sincos(double x, double *sn, double *cs) { *sn = sin(x); *cs = cos(x); }
static void s_sincosf(float x, float *sn, float *cs) { *sn = sinf(x); *cs = cosf(x); }
static int s_tgkill(int tgid, int tid, int sig) { return 0; }
static void *s_egl_current(void) { return NULL; }
static int s_enosys(void) { errno = ENOSYS; return -1; }
// CMSG_NXTHDR for Android's cmsghdr layout (64-bit length, 8-byte alignment).
typedef struct { void *n; unsigned nl; void *iov; size_t iovl; void *c; size_t cl; int f; } AMsg;
typedef struct { size_t len; int level, type; } ACmsg;
static ACmsg *s_cmsg_nxthdr(AMsg *m, ACmsg *c) {
  char *next = (char *)c + ((c->len + 7) & ~7UL);
  if (next + sizeof(ACmsg) > (char *)m->c + m->cl) return NULL;
  return (ACmsg *)next;
}
// Bionic's ctype table: index with c + 1 (bits as in upstream ctype.h).
#define C_U 1
#define C_L 2
#define C_D 4
#define C_S 8
#define C_P 0x10
#define C_C 0x20
#define C_X 0x40
#define C_B 0x80
static char a_ctype[257];
static void init_ctype(void) {
  for (int c = 0; c < 128; c++) {
    char v = 0;
    if (isupper(c)) v |= C_U;
    if (islower(c)) v |= C_L;
    if (isdigit(c)) v |= C_D;
    if (isspace(c)) v |= C_S;
    if (ispunct(c)) v |= C_P;
    if (iscntrl(c)) v |= C_C;
    if (isxdigit(c)) v |= C_X;
    if (c == ' ') v |= C_B;
    a_ctype[c + 1] = v;
  }
}

// locale
static void *s_newlocale(int mask, const char *name, void *base) { return newlocale(LC_ALL_MASK, name, base); }
static size_t s_ctype_mb_cur_max(void) { return MB_CUR_MAX; }
static int s_cxa_finalize(void *d) { return 0; }
static int s_cxa_atexit(void (*f)(void *), void *a, void *d) { return 0; }
static int s_cxa_thread_atexit(void (*f)(void *), void *a, void *d) { return 0; }

// dynamic loader, for libraries that look things up themselves
static __thread const char *t_dlerror;
static void *s_dlopen(const char *name, int flags) {
  if (!name) return RTLD_DEFAULT;
  const char *b = strrchr(name, '/');
  b = b ? b + 1 : name;
  Module *m = elf_loaded(b);
  if (m) return m;
  char path[1200], err[256];
  extern const char *elf_search_dir(void);
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
typedef struct { const char *dli_fname; void *dli_fbase; const char *dli_sname; void *dli_saddr; } ADlInfo;
static int s_dladdr(const void *a, ADlInfo *i) {
  Module *m = elf_module_for(a);
  if (!m) return dladdr(a, (Dl_info *)i);
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
    E("sincos", s_sincos), E("sincosf", s_sincosf), E("tgkill", s_tgkill), E("eglGetCurrentContext", s_egl_current), E("_ctype_", a_ctype),
    E("__cmsg_nxthdr", s_cmsg_nxthdr), E("epoll_create", s_enosys), E("epoll_create1", s_enosys), E("epoll_ctl", s_enosys), E("epoll_wait", s_enosys),
    E("eventfd", s_enosys), E("timerfd_create", s_enosys), E("timerfd_settime", s_enosys), E("memfd_create", s_enosys),
    E("__errno", s_errno), E("__stack_chk_fail", s_stack_chk_fail), E("__sF", sF),
    E("stdin", &a_stdin), E("stdout", &a_stdout), E("stderr", &a_stderr),
    E("snprintf", snprintf_thunk), E("sprintf", sprintf_thunk), E("fprintf", fprintf_thunk), E("printf", printf_thunk),
    E("__snprintf_chk", snprintf_chk_thunk), E("__sprintf_chk", sprintf_chk_thunk),
    E("vsnprintf", s_vsnprintf), E("vsprintf", s_vsprintf), E("vfprintf", s_vfprintf), E("vasprintf", s_vasprintf),
    E("__vsnprintf_chk", s_vsnprintf_chk), E("sscanf", sscanf_thunk), E("vsscanf", s_vsscanf),
    E("fscanf", fscanf_thunk), E("syslog", syslog_thunk), E("vsyslog", s_vsyslog),
    E("__android_log_print", log_print_thunk), E("__android_log_vprint", s_log_vprint), E("__android_log_write", s_log_write),
    E("__android_log_assert", log_assert_thunk), E("__android_log_is_loggable", s_log_is_loggable),
    E("android_set_abort_message", s_set_abort_message), E("android_get_device_api_level", s_api_level),
    E("__system_property_get", s_system_property_get),
    E("pthread_mutexattr_init", s_mutexattr_init), E("pthread_mutexattr_destroy", s_mutexattr_destroy),
    E("pthread_mutexattr_settype", s_mutexattr_settype), E("pthread_mutexattr_gettype", s_mutexattr_gettype),
    E("pthread_mutex_init", s_mutex_init), E("pthread_mutex_destroy", s_mutex_destroy), E("pthread_mutex_lock", s_mutex_lock),
    E("pthread_mutex_trylock", s_mutex_trylock), E("pthread_mutex_unlock", s_mutex_unlock),
    E("pthread_cond_init", s_cond_init), E("pthread_cond_destroy", s_cond_destroy), E("pthread_cond_signal", s_cond_signal),
    E("pthread_cond_broadcast", s_cond_broadcast), E("pthread_cond_wait", s_cond_wait), E("pthread_cond_timedwait", s_cond_timedwait),
    E("pthread_condattr_init", s_condattr_init), E("pthread_condattr_destroy", s_condattr_noop), E("pthread_condattr_setclock", s_condattr_setclock),
    E("pthread_condattr_getclock", s_condattr_getclock), E("pthread_cond_clockwait", s_cond_clockwait),
    E("pthread_cond_timedwait_monotonic_np", s_cond_timedwait_monotonic), E("pthread_cond_timedwait_monotonic", s_cond_timedwait_monotonic),
    E("pthread_rwlock_init", s_rwlock_init), E("pthread_rwlock_destroy", s_rwlock_destroy), E("pthread_rwlock_rdlock", s_rwlock_rdlock),
    E("pthread_rwlock_wrlock", s_rwlock_wrlock), E("pthread_rwlock_tryrdlock", s_rwlock_tryrdlock), E("pthread_rwlock_trywrlock", s_rwlock_trywrlock),
    E("pthread_rwlock_unlock", s_rwlock_unlock), E("pthread_once", s_once), E("pthread_key_create", s_key_create),
    E("pthread_key_delete", s_key_delete), E("pthread_getspecific", s_getspecific), E("pthread_setspecific", s_setspecific),
    E("pthread_attr_init", s_attr_init), E("pthread_attr_destroy", s_attr_destroy), E("pthread_attr_setdetachstate", s_attr_setdetachstate),
    E("pthread_attr_getdetachstate", s_attr_getdetachstate), E("pthread_attr_setstacksize", s_attr_setstacksize), E("pthread_attr_getstacksize", s_attr_getstacksize),
    E("pthread_attr_setguardsize", s_attr_setguardsize), E("pthread_attr_setschedparam", s_attr_setschedparam),
    E("pthread_attr_setschedpolicy", s_attr_setschedpolicy), E("pthread_attr_setinheritsched", s_attr_setinheritsched),
    E("pthread_create", s_pthread_create), E("pthread_setname_np", s_pthread_setname), E("pthread_setschedparam", s_setschedparam),
    E("pthread_atfork", s_atfork), E("__register_atfork", s_atfork), E("gettid", s_gettid),
    E("stat", s_stat), E("lstat", s_lstat), E("fstat", s_fstat), E("fstatat", s_fstatat), E("stat64", s_stat), E("lstat64", s_lstat), E("fstat64", s_fstat),
    E("open", s_open), E("__open_2", s_open_2), E("open64", s_open), E("openat", s_openat), E("fcntl", s_fcntl), E("ioctl", s_ioctl),
    E("readdir", s_readdir), E("fwrite", s_fwrite), E("fputs", s_fputs), E("fputc", s_fputc), E("fflush", s_fflush), E("ferror", s_ferror),
    E("mmap", s_mmap), E("mmap64", s_mmap), E("mremap", s_mremap), E("madvise", s_madvise), E("sysconf", s_sysconf), E("getauxval", s_getauxval),
    E("clock_gettime", s_clock_gettime), E("syscall", s_syscall), E("prctl", s_prctl),
    E("sigaction", s_sigaction), E("signal", s_signal), E("sigemptyset", s_sigemptyset), E("sigfillset", s_sigfillset), E("sigaddset", s_sigaddset),
    E("sigdelset", s_sigdelset), E("sigprocmask", s_sigprocmask), E("pthread_sigmask", s_sigprocmask),
    E("__memcpy_chk", s_memcpy_chk), E("__memmove_chk", s_memmove_chk), E("__memset_chk", s_memset_chk), E("__strlen_chk", s_strlen_chk),
    E("__strchr_chk", s_strchr_chk), E("__strrchr_chk", s_strrchr_chk), E("__strcpy_chk", s_strcpy_chk), E("__strcat_chk", s_strcat_chk),
    E("__strncat_chk", s_strncat_chk), E("__strncpy_chk", s_strncpy_chk), E("__strlcpy_chk", s_strlcpy_chk), E("__strlcat_chk", s_strlcat_chk),
    E("__read_chk", s_read_chk), E("__FD_SET_chk", s_fd_set_chk), E("__FD_CLR_chk", s_fd_clr_chk), E("__FD_ISSET_chk", s_fd_isset_chk),
    E("newlocale", s_newlocale), E("__ctype_get_mb_cur_max", s_ctype_mb_cur_max),
    E("__cxa_finalize", s_cxa_finalize), E("__cxa_atexit", s_cxa_atexit), E("__cxa_thread_atexit_impl", s_cxa_thread_atexit),
    E("dlopen", s_dlopen), E("dlsym", s_dlsym), E("dlclose", s_dlclose), E("dlerror", s_dlerror), E("dladdr", s_dladdr), E("dl_iterate_phdr", s_dl_iterate_phdr),
};

void *bionic_lookup(const char *name) {
  a_stdin = (FILE *)(sF + 0);
  a_stdout = (FILE *)(sF + 152);
  a_stderr = (FILE *)(sF + 304);
  if (!a_ctype['a' + 1]) init_ctype();
  void *net = net_lookup(name);
  if (net) return net;
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
__asm__(".text\n.p2align 2\n_stub_common:\n  stp x29, x30, [sp, #-16]!\n  mov x0, x16\n  bl _stub_hit\n  mov x0, #-1\n  ldp x29, x30, [sp], #16\n  ret\n"
        ".p2align 2\n.globl _stub_table\n_stub_table:\n"
        ".set i, 0\n.rept 512\n  mov x16, #i\n  b _stub_common\n.set i, i+1\n.endr\n");

void *missing_stub(const char *name) {
  fprintf(stderr, "[apksim] unresolved native import: %s\n", name);
  if (stub_count >= STUBS) return NULL;
  stub_names[stub_count] = strdup(name);
  return stub_table + 8 * stub_count++;
}
