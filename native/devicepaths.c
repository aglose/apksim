// External storage at a device's path (dev.apksim.DevicePaths). An app reads where its external storage is from the
// framework (Environment.getExternalStorageDirectory, Context.getExternalFilesDir) and shows it, writes it in files and
// hands it to libraries: it used to be the host folder of the instance's data slot
// (/Users/NAME/.apksim/cache/data/PKG/crawl-0.device/sdcard), which differs between instances and names the host's
// user. Now the framework answers /storage/emulated/0, as on a device, and this library, in every apksim JVM
// (DYLD_INSERT_LIBRARIES on macOS, LD_PRELOAD on Linux: the launcher), rewrites a path under /storage/emulated/0,
// /storage/self/primary, /sdcard or /mnt/sdcard to that host folder in each file call the process makes, whoever makes
// it (the JDK's java.io and java.nio, Robolectric's SQLite, the ELF loader's bionic shim for the app's own native code).
// realpath given a device path answers a device path, so getCanonicalPath shows nothing of the host either.
//
// Nothing is rewritten until Java names the folder (DevicePaths.map, once the data slot is taken): until then, and for
// every path outside those prefixes, each call is passed on unchanged. Not mapped: relative paths (an app that chdirs
// into external storage), symlink targets read back with readlink, paths a call returns other than realpath (getcwd),
// /storage and /storage/emulated themselves (they do not exist on the host).
#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#ifdef __APPLE__
#include <sys/attr.h>
#include <sys/mount.h>
#include <sys/clonefile.h>
#include <sys/xattr.h>
#else
#include <dlfcn.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/xattr.h>
#include <utime.h>
#endif

static const char *const PREFIXES[] = {"/storage/emulated/0", "/storage/self/primary", "/sdcard", "/mnt/sdcard"};
#define DEVICE_ROOT "/storage/emulated/0"

static char host[PATH_MAX];      // the slot's sdcard folder, as Java named it
static size_t host_len;
static char host_real[PATH_MAX]; // the same, as realpath gives it (/tmp is /private/tmp on macOS)
static size_t host_real_len;
static volatile int active;

/** Length of the device prefix [p] starts with (the whole path, or followed by '/'), else 0. */
static size_t device_prefix(const char *p) {
  if (!p || p[0] != '/') return 0;
  for (size_t i = 0; i < sizeof PREFIXES / sizeof PREFIXES[0]; i++) {
    size_t n = strlen(PREFIXES[i]);
    if (strncmp(p, PREFIXES[i], n) == 0 && (p[n] == 0 || p[n] == '/')) return n;
  }
  return 0;
}

/** [p] on the host: [buf] holding the host folder plus what follows the device prefix, or [p] unchanged. */
static const char *map(const char *p, char *buf) {
  if (!active) return p;
  size_t n = device_prefix(p);
  if (!n) return p;
  size_t rest = strlen(p + n);
  if (host_len + rest + 1 > PATH_MAX) return p;  // too long to map: the call fails on the device path, as it would
  memcpy(buf, host, host_len);
  memcpy(buf + host_len, p + n, rest + 1);
  return buf;
}

/** After realpath of a device path: [out] (a host path) back under DEVICE_ROOT when it is in the host folder. */
static void unmap(char *out) {
  if (!out) return;
  const char *base = NULL;
  size_t len = 0;
  if (host_real_len && strncmp(out, host_real, host_real_len) == 0 && (out[host_real_len] == 0 || out[host_real_len] == '/')) {
    base = host_real, len = host_real_len;
  } else if (strncmp(out, host, host_len) == 0 && (out[host_len] == 0 || out[host_len] == '/')) {
    base = host, len = host_len;
  }
  if (!base) return;
  size_t root = strlen(DEVICE_ROOT), rest = strlen(out + len);
  if (root + rest + 1 > PATH_MAX) return;
  memmove(out + root, out + len, rest + 1);
  memcpy(out, DEVICE_ROOT, root);
}

#ifdef __APPLE__
// dyld's interposing: calls from every other image go to the replacement; calls made here reach the original.
#define INTERPOSE(replacement, replacee)                                                                          \
  __attribute__((used)) static struct { const void *r; const void *e; } interpose_##replacee                       \
      __attribute__((section("__DATA,__interpose"))) = {(const void *)(unsigned long)&replacement,               \
                                                        (const void *)(unsigned long)&replacee};
#define REAL(name) name
#else
// LD_PRELOAD: these definitions come first in the global scope; the originals are the next ones (RTLD_NEXT).
#define REAL(name) real_##name()
#define NEXT(ret, name, ...)                                                                                       \
  static ret (*real_##name(void))(__VA_ARGS__) {                                                                \
    static ret (*f)(__VA_ARGS__);                                                                               \
    if (!f) f = (ret(*)(__VA_ARGS__))dlsym(RTLD_NEXT, #name);                                                   \
    return f;                                                                                                   \
  }
#endif

// One path argument, the call's own return type and arguments.
#ifdef __APPLE__
#define WRAP1(ret, name, params, args)                                                                             \
  static ret dp_##name params {                                                                                 \
    char b[PATH_MAX];                                                                                           \
    p = map(p, b);                                                                                              \
    return name args;                                                                                           \
  }                                                                                                             \
  INTERPOSE(dp_##name, name)
#define WRAP2(ret, name, params, args)                                                                             \
  static ret dp_##name params {                                                                                 \
    char b1[PATH_MAX], b2[PATH_MAX];                                                                            \
    p = map(p, b1);                                                                                             \
    q = map(q, b2);                                                                                             \
    return name args;                                                                                           \
  }                                                                                                             \
  INTERPOSE(dp_##name, name)
#else
#define WRAP1(ret, name, params, args, ...)                                                                        \
  NEXT(ret, name, __VA_ARGS__)                                                                                  \
  ret name params {                                                                                             \
    char b[PATH_MAX];                                                                                           \
    p = map(p, b);                                                                                              \
    return real_##name() args;                                                                                  \
  }
#define WRAP2(ret, name, params, args, ...)                                                                        \
  NEXT(ret, name, __VA_ARGS__)                                                                                  \
  ret name params {                                                                                             \
    char b1[PATH_MAX], b2[PATH_MAX];                                                                            \
    p = map(p, b1);                                                                                             \
    q = map(q, b2);                                                                                             \
    return real_##name() args;                                                                                  \
  }
#endif

#ifdef __APPLE__
static int dp_open(const char *p, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  char b[PATH_MAX];
  return open(map(p, b), flags, mode);
}
INTERPOSE(dp_open, open)
static int dp_openat(int fd, const char *p, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  char b[PATH_MAX];
  return openat(fd, map(p, b), flags, mode);
}
INTERPOSE(dp_openat, openat)
static char *dp_realpath(const char *p, char *out) {
  char b[PATH_MAX];
  int device = active && device_prefix(p) > 0;
  char *r = realpath(map(p, b), out);
  if (r && device) unmap(r);
  return r;
}
INTERPOSE(dp_realpath, realpath)
WRAP1(int, stat, (const char *p, struct stat *s), (p, s))
WRAP1(int, lstat, (const char *p, struct stat *s), (p, s))
WRAP1(int, access, (const char *p, int m), (p, m))
WRAP1(int, mkdir, (const char *p, mode_t m), (p, m))
WRAP1(int, rmdir, (const char *p), (p))
WRAP1(int, unlink, (const char *p), (p))
WRAP1(DIR *, opendir, (const char *p), (p))
WRAP1(int, chmod, (const char *p, mode_t m), (p, m))
WRAP1(int, chown, (const char *p, uid_t u, gid_t g), (p, u, g))
WRAP1(int, lchown, (const char *p, uid_t u, gid_t g), (p, u, g))
WRAP1(int, utimes, (const char *p, const struct timeval t[2]), (p, t))
WRAP1(int, lutimes, (const char *p, const struct timeval t[2]), (p, t))
WRAP1(int, statfs, (const char *p, struct statfs *s), (p, s))
WRAP1(int, truncate, (const char *p, off_t n), (p, n))
WRAP1(ssize_t, readlink, (const char *p, char *o, size_t n), (p, o, n))
WRAP1(long, pathconf, (const char *p, int n), (p, n))
WRAP1(int, chdir, (const char *p), (p))
WRAP1(int, mknod, (const char *p, mode_t m, dev_t d), (p, m, d))
WRAP1(int, mkfifo, (const char *p, mode_t m), (p, m))
WRAP1(FILE *, fopen, (const char *p, const char *m), (p, m))
WRAP1(FILE *, freopen, (const char *p, const char *m, FILE *f), (p, m, f))
WRAP1(int, getattrlist, (const char *p, void *a, void *b2, size_t n, unsigned int o), (p, a, b2, n, o))
WRAP1(int, setattrlist, (const char *p, void *a, void *b2, size_t n, unsigned int o), (p, a, b2, n, o))
WRAP1(ssize_t, getxattr, (const char *p, const char *k, void *v, size_t n, u_int32_t pos, int o), (p, k, v, n, pos, o))
WRAP1(int, setxattr, (const char *p, const char *k, const void *v, size_t n, u_int32_t pos, int o), (p, k, v, n, pos, o))
WRAP1(int, removexattr, (const char *p, const char *k, int o), (p, k, o))
WRAP1(ssize_t, listxattr, (const char *p, char *k, size_t n, int o), (p, k, n, o))
static int dp_fstatat(int fd, const char *p, struct stat *s, int f) { char b[PATH_MAX]; return fstatat(fd, map(p, b), s, f); }
INTERPOSE(dp_fstatat, fstatat)
static int dp_faccessat(int fd, const char *p, int m, int f) { char b[PATH_MAX]; return faccessat(fd, map(p, b), m, f); }
INTERPOSE(dp_faccessat, faccessat)
static int dp_mkdirat(int fd, const char *p, mode_t m) { char b[PATH_MAX]; return mkdirat(fd, map(p, b), m); }
INTERPOSE(dp_mkdirat, mkdirat)
static int dp_unlinkat(int fd, const char *p, int f) { char b[PATH_MAX]; return unlinkat(fd, map(p, b), f); }
INTERPOSE(dp_unlinkat, unlinkat)
static int dp_fchmodat(int fd, const char *p, mode_t m, int f) { char b[PATH_MAX]; return fchmodat(fd, map(p, b), m, f); }
INTERPOSE(dp_fchmodat, fchmodat)
static int dp_fchownat(int fd, const char *p, uid_t u, gid_t g, int f) { char b[PATH_MAX]; return fchownat(fd, map(p, b), u, g, f); }
INTERPOSE(dp_fchownat, fchownat)
static int dp_utimensat(int fd, const char *p, const struct timespec t[2], int f) { char b[PATH_MAX]; return utimensat(fd, map(p, b), t, f); }
INTERPOSE(dp_utimensat, utimensat)
static ssize_t dp_readlinkat(int fd, const char *p, char *o, size_t n) { char b[PATH_MAX]; return readlinkat(fd, map(p, b), o, n); }
INTERPOSE(dp_readlinkat, readlinkat)
WRAP2(int, rename, (const char *p, const char *q), (p, q))
WRAP2(int, link, (const char *p, const char *q), (p, q))
WRAP2(int, symlink, (const char *p, const char *q), (p, q))
static int dp_renameat(int f1, const char *p, int f2, const char *q) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return renameat(f1, map(p, b1), f2, map(q, b2));
}
INTERPOSE(dp_renameat, renameat)
static int dp_clonefile(const char *p, const char *q, uint32_t f) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return clonefile(map(p, b1), map(q, b2), f);
}
INTERPOSE(dp_clonefile, clonefile)
#else
// Linux (glibc). The 64-bit and fortified variants are separate symbols, and glibc before 2.33 has stat as __xstat.
NEXT(int, open, const char *, int, ...)
int open(const char *p, int flags, ...) {
  mode_t mode = 0;
  if (flags & (O_CREAT | O_TMPFILE)) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  char b[PATH_MAX];
  return real_open()(map(p, b), flags, mode);
}
NEXT(int, open64, const char *, int, ...)
int open64(const char *p, int flags, ...) {
  mode_t mode = 0;
  if (flags & (O_CREAT | O_TMPFILE)) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  char b[PATH_MAX];
  return real_open64()(map(p, b), flags, mode);
}
NEXT(int, openat, int, const char *, int, ...)
int openat(int fd, const char *p, int flags, ...) {
  mode_t mode = 0;
  if (flags & (O_CREAT | O_TMPFILE)) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  char b[PATH_MAX];
  return real_openat()(fd, map(p, b), flags, mode);
}
NEXT(int, openat64, int, const char *, int, ...)
int openat64(int fd, const char *p, int flags, ...) {
  mode_t mode = 0;
  if (flags & (O_CREAT | O_TMPFILE)) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  char b[PATH_MAX];
  return real_openat64()(fd, map(p, b), flags, mode);
}
NEXT(int, __open_2, const char *, int)
int __open_2(const char *p, int f) { char b[PATH_MAX]; return real___open_2()(map(p, b), f); }
NEXT(int, __open64_2, const char *, int)
int __open64_2(const char *p, int f) { char b[PATH_MAX]; return real___open64_2()(map(p, b), f); }
NEXT(int, __openat_2, int, const char *, int)
int __openat_2(int fd, const char *p, int f) { char b[PATH_MAX]; return real___openat_2()(fd, map(p, b), f); }
NEXT(int, __openat64_2, int, const char *, int)
int __openat64_2(int fd, const char *p, int f) { char b[PATH_MAX]; return real___openat64_2()(fd, map(p, b), f); }
NEXT(char *, realpath, const char *, char *)
char *realpath(const char *p, char *out) {
  char b[PATH_MAX];
  int device = active && device_prefix(p) > 0;
  char *r = real_realpath()(map(p, b), out);
  if (r && device) unmap(r);
  return r;
}
NEXT(char *, canonicalize_file_name, const char *)
char *canonicalize_file_name(const char *p) {
  char b[PATH_MAX];
  int device = active && device_prefix(p) > 0;
  char *r = real_canonicalize_file_name()(map(p, b));
  if (r && device) {
    char *big = realloc(r, PATH_MAX);  // room to put the device prefix back
    if (big) unmap(r = big);
  }
  return r;
}
WRAP1(int, stat, (const char *p, struct stat *s), (p, s), const char *, struct stat *)
WRAP1(int, lstat, (const char *p, struct stat *s), (p, s), const char *, struct stat *)
WRAP1(int, stat64, (const char *p, struct stat64 *s), (p, s), const char *, struct stat64 *)
WRAP1(int, lstat64, (const char *p, struct stat64 *s), (p, s), const char *, struct stat64 *)
NEXT(int, __xstat, int, const char *, struct stat *)
int __xstat(int v, const char *p, struct stat *s) { char b[PATH_MAX]; return real___xstat()(v, map(p, b), s); }
NEXT(int, __lxstat, int, const char *, struct stat *)
int __lxstat(int v, const char *p, struct stat *s) { char b[PATH_MAX]; return real___lxstat()(v, map(p, b), s); }
NEXT(int, __xstat64, int, const char *, struct stat64 *)
int __xstat64(int v, const char *p, struct stat64 *s) { char b[PATH_MAX]; return real___xstat64()(v, map(p, b), s); }
NEXT(int, __lxstat64, int, const char *, struct stat64 *)
int __lxstat64(int v, const char *p, struct stat64 *s) { char b[PATH_MAX]; return real___lxstat64()(v, map(p, b), s); }
NEXT(int, fstatat, int, const char *, struct stat *, int)
int fstatat(int fd, const char *p, struct stat *s, int f) { char b[PATH_MAX]; return real_fstatat()(fd, map(p, b), s, f); }
NEXT(int, fstatat64, int, const char *, struct stat64 *, int)
int fstatat64(int fd, const char *p, struct stat64 *s, int f) { char b[PATH_MAX]; return real_fstatat64()(fd, map(p, b), s, f); }
NEXT(int, __fxstatat, int, int, const char *, struct stat *, int)
int __fxstatat(int v, int fd, const char *p, struct stat *s, int f) { char b[PATH_MAX]; return real___fxstatat()(v, fd, map(p, b), s, f); }
NEXT(int, __fxstatat64, int, int, const char *, struct stat64 *, int)
int __fxstatat64(int v, int fd, const char *p, struct stat64 *s, int f) { char b[PATH_MAX]; return real___fxstatat64()(v, fd, map(p, b), s, f); }
NEXT(int, statx, int, const char *, int, unsigned int, struct statx *)
int statx(int fd, const char *p, int f, unsigned int m, struct statx *s) { char b[PATH_MAX]; return real_statx()(fd, map(p, b), f, m, s); }
WRAP1(int, access, (const char *p, int m), (p, m), const char *, int)
NEXT(int, faccessat, int, const char *, int, int)
int faccessat(int fd, const char *p, int m, int f) { char b[PATH_MAX]; return real_faccessat()(fd, map(p, b), m, f); }
WRAP1(int, euidaccess, (const char *p, int m), (p, m), const char *, int)
WRAP1(int, mkdir, (const char *p, mode_t m), (p, m), const char *, mode_t)
NEXT(int, mkdirat, int, const char *, mode_t)
int mkdirat(int fd, const char *p, mode_t m) { char b[PATH_MAX]; return real_mkdirat()(fd, map(p, b), m); }
WRAP1(int, rmdir, (const char *p), (p), const char *)
WRAP1(int, unlink, (const char *p), (p), const char *)
NEXT(int, unlinkat, int, const char *, int)
int unlinkat(int fd, const char *p, int f) { char b[PATH_MAX]; return real_unlinkat()(fd, map(p, b), f); }
WRAP1(DIR *, opendir, (const char *p), (p), const char *)
WRAP1(int, chmod, (const char *p, mode_t m), (p, m), const char *, mode_t)
NEXT(int, fchmodat, int, const char *, mode_t, int)
int fchmodat(int fd, const char *p, mode_t m, int f) { char b[PATH_MAX]; return real_fchmodat()(fd, map(p, b), m, f); }
WRAP1(int, chown, (const char *p, uid_t u, gid_t g), (p, u, g), const char *, uid_t, gid_t)
WRAP1(int, lchown, (const char *p, uid_t u, gid_t g), (p, u, g), const char *, uid_t, gid_t)
NEXT(int, fchownat, int, const char *, uid_t, gid_t, int)
int fchownat(int fd, const char *p, uid_t u, gid_t g, int f) { char b[PATH_MAX]; return real_fchownat()(fd, map(p, b), u, g, f); }
WRAP1(int, utime, (const char *p, const struct utimbuf *t), (p, t), const char *, const struct utimbuf *)
WRAP1(int, utimes, (const char *p, const struct timeval t[2]), (p, t), const char *, const struct timeval *)
WRAP1(int, lutimes, (const char *p, const struct timeval t[2]), (p, t), const char *, const struct timeval *)
NEXT(int, utimensat, int, const char *, const struct timespec *, int)
int utimensat(int fd, const char *p, const struct timespec t[2], int f) { char b[PATH_MAX]; return real_utimensat()(fd, map(p, b), t, f); }
WRAP1(int, statfs, (const char *p, struct statfs *s), (p, s), const char *, struct statfs *)
WRAP1(int, statfs64, (const char *p, struct statfs64 *s), (p, s), const char *, struct statfs64 *)
WRAP1(int, statvfs, (const char *p, struct statvfs *s), (p, s), const char *, struct statvfs *)
WRAP1(int, statvfs64, (const char *p, struct statvfs64 *s), (p, s), const char *, struct statvfs64 *)
WRAP1(int, truncate, (const char *p, off_t n), (p, n), const char *, off_t)
WRAP1(int, truncate64, (const char *p, off64_t n), (p, n), const char *, off64_t)
WRAP1(ssize_t, readlink, (const char *p, char *o, size_t n), (p, o, n), const char *, char *, size_t)
NEXT(ssize_t, readlinkat, int, const char *, char *, size_t)
ssize_t readlinkat(int fd, const char *p, char *o, size_t n) { char b[PATH_MAX]; return real_readlinkat()(fd, map(p, b), o, n); }
WRAP1(long, pathconf, (const char *p, int n), (p, n), const char *, int)
WRAP1(int, chdir, (const char *p), (p), const char *)
WRAP1(int, mknod, (const char *p, mode_t m, dev_t d), (p, m, d), const char *, mode_t, dev_t)
WRAP1(int, mkfifo, (const char *p, mode_t m), (p, m), const char *, mode_t)
WRAP1(int, creat, (const char *p, mode_t m), (p, m), const char *, mode_t)
WRAP1(int, creat64, (const char *p, mode_t m), (p, m), const char *, mode_t)
WRAP1(FILE *, fopen, (const char *p, const char *m), (p, m), const char *, const char *)
WRAP1(FILE *, fopen64, (const char *p, const char *m), (p, m), const char *, const char *)
WRAP1(FILE *, freopen, (const char *p, const char *m, FILE *f), (p, m, f), const char *, const char *, FILE *)
WRAP1(FILE *, freopen64, (const char *p, const char *m, FILE *f), (p, m, f), const char *, const char *, FILE *)
WRAP1(ssize_t, getxattr, (const char *p, const char *k, void *v, size_t n), (p, k, v, n), const char *, const char *, void *, size_t)
WRAP1(ssize_t, lgetxattr, (const char *p, const char *k, void *v, size_t n), (p, k, v, n), const char *, const char *, void *, size_t)
WRAP1(int, setxattr, (const char *p, const char *k, const void *v, size_t n, int f), (p, k, v, n, f), const char *, const char *, const void *, size_t, int)
WRAP1(int, lsetxattr, (const char *p, const char *k, const void *v, size_t n, int f), (p, k, v, n, f), const char *, const char *, const void *, size_t, int)
WRAP1(int, removexattr, (const char *p, const char *k), (p, k), const char *, const char *)
WRAP1(int, lremovexattr, (const char *p, const char *k), (p, k), const char *, const char *)
WRAP1(ssize_t, listxattr, (const char *p, char *k, size_t n), (p, k, n), const char *, char *, size_t)
WRAP1(ssize_t, llistxattr, (const char *p, char *k, size_t n), (p, k, n), const char *, char *, size_t)
WRAP2(int, rename, (const char *p, const char *q), (p, q), const char *, const char *)
WRAP2(int, link, (const char *p, const char *q), (p, q), const char *, const char *)
WRAP2(int, symlink, (const char *p, const char *q), (p, q), const char *, const char *)
NEXT(int, renameat, int, const char *, int, const char *)
int renameat(int f1, const char *p, int f2, const char *q) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return real_renameat()(f1, map(p, b1), f2, map(q, b2));
}
NEXT(int, renameat2, int, const char *, int, const char *, unsigned int)
int renameat2(int f1, const char *p, int f2, const char *q, unsigned int fl) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return real_renameat2()(f1, map(p, b1), f2, map(q, b2), fl);
}
NEXT(int, linkat, int, const char *, int, const char *, int)
int linkat(int f1, const char *p, int f2, const char *q, int fl) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return real_linkat()(f1, map(p, b1), f2, map(q, b2), fl);
}
NEXT(int, symlinkat, const char *, int, const char *)
int symlinkat(const char *p, int fd, const char *q) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return real_symlinkat()(map(p, b1), fd, map(q, b2));
}
#endif

// --- dev.apksim.DevicePaths

/** Whether this library was put in front of libc at the process's start (only then are calls rewritten). */
static int preloaded(void) {
#ifdef __APPLE__
  const char *v = getenv("DYLD_INSERT_LIBRARIES");
#else
  const char *v = getenv("LD_PRELOAD");
#endif
  return v && strstr(v, "devicepaths") != NULL;
}

JNIEXPORT jboolean JNICALL Java_dev_apksim_DevicePaths_nativePreloaded(JNIEnv *env, jclass c) {
  (void)env, (void)c;
  return preloaded() ? JNI_TRUE : JNI_FALSE;
}

/** Map the device prefixes to [folder] (absolute, existing), or stop mapping with null. */
JNIEXPORT jboolean JNICALL Java_dev_apksim_DevicePaths_nativeMap(JNIEnv *env, jclass c, jstring folder) {
  (void)c;
  if (!folder) {
    active = 0;
    return JNI_TRUE;
  }
  const char *f = (*env)->GetStringUTFChars(env, folder, NULL);
  if (!f) return JNI_FALSE;
  size_t n = strlen(f);
  jboolean ok = JNI_FALSE;
  if (f[0] == '/' && n > 1 && n + 1 < PATH_MAX) {
    active = 0;
    memcpy(host, f, n + 1);
    while (host_len = strlen(host), host_len > 1 && host[host_len - 1] == '/') host[host_len - 1] = 0;
    char r[PATH_MAX];
#ifdef __APPLE__
    char *got = realpath(host, r);  // the original: calls made here are not interposed
#else
    char *got = real_realpath()(host, r);
#endif
    if (got) {
      memcpy(host_real, r, strlen(r) + 1);
      host_real_len = strlen(host_real);
    } else {
      host_real[0] = 0;
      host_real_len = 0;
    }
    __sync_synchronize();
    active = 1;
    ok = JNI_TRUE;
  }
  (*env)->ReleaseStringUTFChars(env, folder, f);
  return ok;
}
