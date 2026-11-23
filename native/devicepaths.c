// External storage at a device's path (dev.apksim.DevicePaths). An app reads where its external storage is from the
// framework (Environment.getExternalStorageDirectory, Context.getExternalFilesDir) and shows it, writes it in files and
// hands it to libraries: it used to be the host folder of the instance's data slot
// (/Users/NAME/.apksim/cache/data/PKG/crawl-0.device/sdcard), which differs between instances and names the host's
// user. Now the framework answers /storage/emulated/0, as on a device, and this library, in every apksim JVM
// (DYLD_INSERT_LIBRARIES on macOS, LD_PRELOAD on Linux, for an instance's JVM: the launcher), rewrites a path under /storage/emulated/0,
// /storage/self/primary, /sdcard or /mnt/sdcard to that host folder in each file call the process makes, whoever makes
// it (the JDK's java.io and java.nio, Robolectric's SQLite, the ELF loader's bionic shim for the app's own native code).
// realpath given a device path answers a device path, so getCanonicalPath shows nothing of the host either, and getcwd
// in external storage answers its device path. A device path is folded lexically (".." included) first, as the device's
// kernel would resolve it, and /storage, /storage/emulated and /storage/self exist: a skeleton folder Java makes.
//
// Nothing is rewritten until Java names the folders (DevicePaths.map, once the data slot is taken): until then, and for
// every path outside /storage, /sdcard and /mnt/sdcard, each call is passed on unchanged. Not mapped: a relative path
// that climbs out of external storage after a chdir into it (".." from its top reaches the slot's host folder), symlink targets (readlink of a link made with a device
// target answers the host path it was written as), a descriptor's path (fcntl F_GETPATH, /proc/self/fd), the entries
// fts_read walks (its root is mapped, its entries keep host paths), and child processes (exec'd programs start without
// the mapping).
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
#include <copyfile.h>
#include <sys/xattr.h>
#else
#include <dlfcn.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/xattr.h>
#include <utime.h>
#include <stdint.h>
#include <sys/inotify.h>
#endif

// Device paths that name external storage itself (aliases of DEVICE_ROOT on a device), and the root they all live under.
static const char *const ALIASES[] = {"/storage/self/primary", "/sdcard", "/mnt/sdcard"};
#define DEVICE_ROOT "/storage/emulated/0"

static char host[PATH_MAX];      // the slot's sdcard folder, as Java named it
static size_t host_len;
static char host_real[PATH_MAX]; // the same, as realpath gives it (/tmp is /private/tmp on macOS)
static size_t host_real_len;
static char skel[PATH_MAX];      // a folder holding storage/emulated/0, storage/self/primary: /storage's ancestors on the host
static size_t skel_len;
static char skel_real[PATH_MAX];
static size_t skel_real_len;
static volatile int active;

/** Whether [p] is [root] or below it. */
static int under(const char *p, const char *root) {
  size_t n = strlen(root);
  return strncmp(p, root, n) == 0 && (p[n] == 0 || p[n] == '/');
}

/** Whether this call's path may be a device one: /storage, /sdcard or /mnt/sdcard, or below them. */
static int device_path(const char *p) {
  return p && p[0] == '/' && (under(p, "/storage") || under(p, "/sdcard") || under(p, "/mnt/sdcard"));
}

/** [in] (absolute) folded lexically into [out]: "//", "." and ".." taken out, a trailing '/' kept. 0 when too long. */
static int fold(const char *in, char *out) {
  size_t o = 0;
  const char *s = in;
  while (*s) {
    while (*s == '/') s++;
    if (!*s) break;
    const char *e = s;
    while (*e && *e != '/') e++;
    size_t n = (size_t)(e - s);
    if (n == 2 && s[0] == '.' && s[1] == '.') {
      while (o > 0 && out[o - 1] != '/') o--;
      if (o > 0) o--;
    } else if (!(n == 1 && s[0] == '.')) {
      if (o + 1 + n + 2 > PATH_MAX) return 0;
      out[o++] = '/';
      memcpy(out + o, s, n);
      o += n;
    }
    s = e;
  }
  size_t len = strlen(in);
  if (o == 0) out[o++] = '/';
  else if (in[len - 1] == '/') out[o++] = '/';
  out[o] = 0;
  return 1;
}

/** [base] followed by [rest] into [buf]; 0 when too long. */
static int join(char *buf, const char *base, size_t base_len, const char *rest) {
  size_t r = strlen(rest);
  if (base_len + r + 1 > PATH_MAX) return 0;
  memcpy(buf, base, base_len);
  memcpy(buf + base_len, rest, r + 1);
  return 1;
}

/**
 * [p] on the host, in [buf], or [p] unchanged. A device path is taken to DEVICE_ROOT's form (an alias's prefix replaced)
 * and folded lexically, as the device's kernel would resolve it through its folders: what is then under DEVICE_ROOT is
 * in the slot's sdcard folder, the rest of /storage (its ancestors, /storage/self) in the skeleton folder, and a path
 * that climbed out of /storage is that host path, as on a device.
 */
static const char *map(const char *p, char *buf) {
  if (!active || !device_path(p)) return p;
  char d[PATH_MAX], f[PATH_MAX];
  const char *src = p;
  for (size_t i = 0; i < sizeof ALIASES / sizeof ALIASES[0]; i++) {
    if (under(p, ALIASES[i])) {
      if (!join(d, DEVICE_ROOT, strlen(DEVICE_ROOT), p + strlen(ALIASES[i]))) return p;
      src = d;
      break;
    }
  }
  if (!fold(src, f)) return p;  // too long to map: the call fails on the device path, as it would
  if (under(f, DEVICE_ROOT)) return join(buf, host, host_len, f + strlen(DEVICE_ROOT)) ? buf : p;
  if (under(f, "/storage")) return skel_len && join(buf, skel, skel_len, f) ? buf : p;
  memcpy(buf, f, strlen(f) + 1);
  return buf;
}

/** [out] starting with [base] (len) gets [put] in its place; whether it did. */
static int swap_prefix(char *out, const char *base, size_t len, const char *put) {
  if (!len || strncmp(out, base, len) != 0 || (out[len] != 0 && out[len] != '/')) return 0;
  size_t pl = strlen(put), rest = strlen(out + len);
  if (pl + rest + 1 > PATH_MAX) return 1;
  memmove(out + pl, out + len, rest + 1);
  memcpy(out, put, pl);
  if (!out[0]) out[0] = '/', out[1] = 0;
  return 1;
}

/** A host path a call answered ([out], PATH_MAX long) back as the device path it stands for, when it is one. */
static void unmap(char *out) {
  if (!out || !active) return;
  if (swap_prefix(out, host_real, host_real_len, DEVICE_ROOT) || swap_prefix(out, host, host_len, DEVICE_ROOT)) return;
  if (swap_prefix(out, skel_real, skel_real_len, "") || swap_prefix(out, skel, skel_len, "")) return;
}

/** After mkstemp and the like on a mapped template: the X's (in the last [tail] bytes) copied back into [t]. */
static void filled(char *t, const char *b, size_t tail) {
  size_t tl = strlen(t), bl = strlen(b);
  if (tail > tl) tail = tl;
  if (tail > bl) tail = bl;
  memcpy(t + tl - tail, b + bl - tail, tail);
}

#include <glob.h>
#include <ftw.h>
#include <fts.h>

/** glob's matches back at their device paths (each a fresh string: globfree frees them one by one). */
static void unmap_glob(glob_t *g) {
  for (size_t i = 0; i < g->gl_pathc; i++) {
    char *e = g->gl_pathv[g->gl_offs + i];
    if (!e) continue;
    char t[PATH_MAX];
    size_t n = strlen(e);
    if (n + 1 > PATH_MAX) continue;
    memcpy(t, e, n + 1);
    unmap(t);
    if (strcmp(t, e) == 0) continue;
    char *c = strdup(t);
    if (!c) continue;
    free(e);
    g->gl_pathv[g->gl_offs + i] = c;
  }
}

// nftw's and ftw's callbacks see device paths: the caller's callback is called through these (one walk per thread).
static __thread int (*nftw_fn)(const char *, const struct stat *, int, struct FTW *);
static __thread int (*ftw_fn)(const char *, const struct stat *, int);
static int nftw_tramp(const char *p, const struct stat *s, int flag, struct FTW *f) {
  char t[PATH_MAX];
  size_t n = strlen(p);
  if (n + 1 > PATH_MAX) return nftw_fn(p, s, flag, f);
  memcpy(t, p, n + 1);
  unmap(t);
  struct FTW g = *f;
  g.base += (int)strlen(t) - (int)n;
  return nftw_fn(t, s, flag, &g);
}
static int ftw_tramp(const char *p, const struct stat *s, int flag) {
  char t[PATH_MAX];
  size_t n = strlen(p);
  if (n + 1 > PATH_MAX) return ftw_fn(p, s, flag);
  memcpy(t, p, n + 1);
  unmap(t);
  return ftw_fn(t, s, flag);
}

/** fts_open's paths mapped into [store] (the caller's array when nothing changes). Its entries' paths stay host ones. */
static char *const *fts_paths(char *const *argv, char **store, char (*bufs)[PATH_MAX], size_t max) {
  size_t i = 0;
  int changed = 0;
  for (; argv && argv[i]; i++) {
    if (i + 1 >= max) return argv;
    const char *m = map(argv[i], bufs[i]);
    changed |= m != argv[i];
    store[i] = (char *)m;
  }
  store[i] = NULL;
  return changed ? store : argv;
}
#define FTS_MAX 16

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
// realpath of a device path answers a device path; on failure the buffer (the component that failed) too.
static char *realpath_device(char *(*real)(const char *, char *), const char *p, char *out) {
  char b[PATH_MAX];
  int device = active && device_path(p);
  char *r = real(map(p, b), out);
  if (r && device && !out) {
    char *big = realloc(r, PATH_MAX);  // allocated to fit the host path: room for the device one
    if (!big) return r;
    r = big;
  }
  if (device) unmap(r ? r : out);
  return r;
}
static char *dp_realpath(const char *p, char *out) { return realpath_device(realpath, p, out); }
INTERPOSE(dp_realpath, realpath)
// The headers name realpath and fopen's UNIX03 variants; code built otherwise (bionic_lookup's dlsym) gets the plain ones.
extern char *realpath_plain(const char *, char *) __asm("_realpath");
static char *dp_realpath_plain(const char *p, char *out) { return realpath_device(realpath_plain, p, out); }
INTERPOSE(dp_realpath_plain, realpath_plain)
extern FILE *fopen_unix03(const char *, const char *) __asm("_fopen$DARWIN_EXTSN");
static FILE *dp_fopen_unix03(const char *p, const char *m) { char b[PATH_MAX]; return fopen_unix03(map(p, b), m); }
INTERPOSE(dp_fopen_unix03, fopen_unix03)
static char *dp_getcwd(char *buf, size_t size) {
  char t[PATH_MAX];
  if (!active || !getcwd(t, sizeof t)) return getcwd(buf, size);
  unmap(t);
  size_t n = strlen(t) + 1;
  if (buf && !size) return errno = EINVAL, (char *)NULL;
  if (size && size < n) return errno = ERANGE, (char *)NULL;
  if (!buf && !(buf = malloc(size ? size : n))) return errno = ENOMEM, (char *)NULL;
  memcpy(buf, t, n);
  return buf;
}
INTERPOSE(dp_getcwd, getcwd)
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
WRAP1(int, remove, (const char *p), (p))
static int dp_copyfile(const char *p, const char *q, copyfile_state_t st, copyfile_flags_t f) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return copyfile(map(p, b1), map(q, b2), st, f);
}
INTERPOSE(dp_copyfile, copyfile)
static int dp_mkstemp(char *t) {
  char b[PATH_MAX];
  if (map(t, b) == t) return mkstemp(t);
  int fd = mkstemp(b);
  if (fd >= 0) filled(t, b, 6);
  return fd;
}
INTERPOSE(dp_mkstemp, mkstemp)
static int dp_mkstemps(char *t, int sfx) {
  char b[PATH_MAX];
  if (map(t, b) == t) return mkstemps(t, sfx);
  int fd = mkstemps(b, sfx);
  if (fd >= 0) filled(t, b, 6 + (size_t)sfx);
  return fd;
}
INTERPOSE(dp_mkstemps, mkstemps)
static int dp_mkostemp(char *t, int fl) {
  char b[PATH_MAX];
  if (map(t, b) == t) return mkostemp(t, fl);
  int fd = mkostemp(b, fl);
  if (fd >= 0) filled(t, b, 6);
  return fd;
}
INTERPOSE(dp_mkostemp, mkostemp)
static int dp_mkostemps(char *t, int sfx, int fl) {
  char b[PATH_MAX];
  if (map(t, b) == t) return mkostemps(t, sfx, fl);
  int fd = mkostemps(b, sfx, fl);
  if (fd >= 0) filled(t, b, 6 + (size_t)sfx);
  return fd;
}
INTERPOSE(dp_mkostemps, mkostemps)
static char *dp_mkdtemp(char *t) {
  char b[PATH_MAX];
  if (map(t, b) == t) return mkdtemp(t);
  if (!mkdtemp(b)) return NULL;
  filled(t, b, 6);
  return t;
}
INTERPOSE(dp_mkdtemp, mkdtemp)
// Calls libc makes inside itself (creat's open, scandir's opendir, glob's and fts's walks) never reach the
// replacements above, so these take the path on the way in.
WRAP1(int, creat, (const char *p, mode_t m), (p, m))
static int dp_scandir(const char *p, struct dirent ***l, int (*sel)(const struct dirent *),
                      int (*cmp)(const struct dirent **, const struct dirent **)) {
  char b[PATH_MAX];
  return scandir(map(p, b), l, sel, cmp);
}
INTERPOSE(dp_scandir, scandir)
static int dp_glob(const char *p, int f, int (*err)(const char *, int), glob_t *g) {
  char b[PATH_MAX];
  const char *m = map(p, b);
  int r = glob(m, f, err, g);
  if (m != p) unmap_glob(g);
  return r;
}
INTERPOSE(dp_glob, glob)
static int dp_nftw(const char *p, int (*fn)(const char *, const struct stat *, int, struct FTW *), int fds, int f) {
  char b[PATH_MAX];
  const char *m = map(p, b);
  if (m == p) return nftw(p, fn, fds, f);
  int (*was)(const char *, const struct stat *, int, struct FTW *) = nftw_fn;
  nftw_fn = fn;
  int r = nftw(m, nftw_tramp, fds, f);
  nftw_fn = was;
  return r;
}
INTERPOSE(dp_nftw, nftw)
static int dp_ftw(const char *p, int (*fn)(const char *, const struct stat *, int), int fds) {
  char b[PATH_MAX];
  const char *m = map(p, b);
  if (m == p) return ftw(p, fn, fds);
  int (*was)(const char *, const struct stat *, int) = ftw_fn;
  ftw_fn = fn;
  int r = ftw(m, ftw_tramp, fds);
  ftw_fn = was;
  return r;
}
INTERPOSE(dp_ftw, ftw)
static FTS *dp_fts_open(char *const *argv, int o, int (*cmp)(const FTSENT **, const FTSENT **)) {
  char *store[FTS_MAX], bufs[FTS_MAX][PATH_MAX];
  return fts_open(fts_paths(argv, store, bufs, FTS_MAX), o, cmp);
}
INTERPOSE(dp_fts_open, fts_open)
static int dp_linkat(int f1, const char *p, int f2, const char *q, int fl) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return linkat(f1, map(p, b1), f2, map(q, b2), fl);
}
INTERPOSE(dp_linkat, linkat)
static int dp_symlinkat(const char *p, int fd, const char *q) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return symlinkat(map(p, b1), fd, map(q, b2));
}
INTERPOSE(dp_symlinkat, symlinkat)
static int dp_renamex_np(const char *p, const char *q, unsigned int fl) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return renamex_np(map(p, b1), map(q, b2), fl);
}
INTERPOSE(dp_renamex_np, renamex_np)
static int dp_renameatx_np(int f1, const char *p, int f2, const char *q, unsigned int fl) {
  char b1[PATH_MAX], b2[PATH_MAX];
  return renameatx_np(f1, map(p, b1), f2, map(q, b2), fl);
}
INTERPOSE(dp_renameatx_np, renameatx_np)
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
  if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
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
  if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
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
  if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
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
  if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
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
  int device = active && device_path(p);
  char *r = real_realpath()(map(p, b), out);
  if (r && device && !out) {
    char *big = realloc(r, PATH_MAX);  // allocated to fit the host path: room for the device one
    if (!big) return r;
    r = big;
  }
  if (device) unmap(r ? r : out);
  return r;
}
NEXT(char *, canonicalize_file_name, const char *)
char *canonicalize_file_name(const char *p) {
  char b[PATH_MAX];
  int device = active && device_path(p);
  char *r = real_canonicalize_file_name()(map(p, b));
  if (r && device) {
    char *big = realloc(r, PATH_MAX);  // room to put the device prefix back
    if (big) unmap(r = big);
  }
  return r;
}
NEXT(char *, getcwd, char *, size_t)
char *getcwd(char *buf, size_t size) {
  char t[PATH_MAX];
  if (!active || !real_getcwd()(t, sizeof t)) return real_getcwd()(buf, size);
  unmap(t);
  size_t n = strlen(t) + 1;
  if (buf && !size) return errno = EINVAL, (char *)NULL;
  if (size && size < n) return errno = ERANGE, (char *)NULL;
  if (!buf && !(buf = malloc(size ? size : n))) return errno = ENOMEM, (char *)NULL;
  memcpy(buf, t, n);
  return buf;
}
char *__getcwd_chk(char *buf, size_t size, size_t buflen) { (void)buflen; return getcwd(buf, size); }
// stat and lstat are exported from glibc 2.33 on; before, only through __xstat (whose version argument is 1 on x86_64,
// 0 on arm64). Without either, ENOSYS rather than a call through NULL.
#if defined(__x86_64__)
#define DP_STAT_VER 1
#else
#define DP_STAT_VER 0
#endif
#define STAT_VIA_XSTAT(name, xname, type)                                                                         \
  static int name##_fallback(const char *p, type *s) {                                                          \
    static int (*x)(int, const char *, type *);                                                                 \
    if (!x) x = (int (*)(int, const char *, type *))dlsym(RTLD_NEXT, #xname);                                   \
    if (!x) return errno = ENOSYS, -1;                                                                          \
    return x(DP_STAT_VER, p, s);                                                                                \
  }                                                                                                             \
  NEXT(int, name, const char *, type *)                                                                         \
  int name(const char *p, type *s) {                                                                            \
    char b[PATH_MAX];                                                                                           \
    p = map(p, b);                                                                                              \
    return real_##name() ? real_##name()(p, s) : name##_fallback(p, s);                                         \
  }
STAT_VIA_XSTAT(stat, __xstat, struct stat)
STAT_VIA_XSTAT(lstat, __lxstat, struct stat)
STAT_VIA_XSTAT(stat64, __xstat64, struct stat64)
STAT_VIA_XSTAT(lstat64, __lxstat64, struct stat64)
// glibc before 2.33 called stat through these; since, they are compat symbols dlsym may not hand out, so they go through
// the exported calls (which map) rather than the next definition.
int __xstat(int v, const char *p, struct stat *s) { (void)v; return stat(p, s); }
int __lxstat(int v, const char *p, struct stat *s) { (void)v; return lstat(p, s); }
int __xstat64(int v, const char *p, struct stat64 *s) { (void)v; return stat64(p, s); }
int __lxstat64(int v, const char *p, struct stat64 *s) { (void)v; return lstat64(p, s); }
NEXT(int, fstatat, int, const char *, struct stat *, int)
int fstatat(int fd, const char *p, struct stat *s, int f) {
  char b[PATH_MAX];
  p = map(p, b);
  if (real_fstatat()) return real_fstatat()(fd, p, s, f);
  static int (*x)(int, int, const char *, struct stat *, int);
  if (!x) x = (int (*)(int, int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "__fxstatat");
  return x ? x(DP_STAT_VER, fd, p, s, f) : (errno = ENOSYS, -1);
}
NEXT(int, fstatat64, int, const char *, struct stat64 *, int)
int fstatat64(int fd, const char *p, struct stat64 *s, int f) {
  char b[PATH_MAX];
  p = map(p, b);
  if (real_fstatat64()) return real_fstatat64()(fd, p, s, f);
  static int (*x)(int, int, const char *, struct stat64 *, int);
  if (!x) x = (int (*)(int, int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "__fxstatat64");
  return x ? x(DP_STAT_VER, fd, p, s, f) : (errno = ENOSYS, -1);
}
int __fxstatat(int v, int fd, const char *p, struct stat *s, int f) { (void)v; return fstatat(fd, p, s, f); }
int __fxstatat64(int v, int fd, const char *p, struct stat64 *s, int f) { (void)v; return fstatat64(fd, p, s, f); }
NEXT(int, statx, int, const char *, int, unsigned int, struct statx *)
int statx(int fd, const char *p, int f, unsigned int m, struct statx *s) {
  char b[PATH_MAX];
  return real_statx() ? real_statx()(fd, map(p, b), f, m, s) : (errno = ENOSYS, -1);  // glibc 2.28 on
}
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
WRAP1(int, remove, (const char *p), (p), const char *)
// mkstemp and the like: [tail] is how many of the template's last bytes the call fills in.
#define TEMPLATE(name, tail, params, args, ...)                                                                    \
  NEXT(int, name, __VA_ARGS__)                                                                                  \
  int name params {                                                                                             \
    char b[PATH_MAX];                                                                                           \
    if (map(t, b) == t) return real_##name() args;                                                              \
    char *t0 = t;                                                                                               \
    t = b;                                                                                                      \
    int fd = real_##name() args;                                                                                \
    if (fd >= 0) filled(t0, b, tail);                                                                           \
    return fd;                                                                                                  \
  }
TEMPLATE(mkstemp, 6, (char *t), (t), char *)
TEMPLATE(mkstemp64, 6, (char *t), (t), char *)
TEMPLATE(mkostemp, 6, (char *t, int fl), (t, fl), char *, int)
TEMPLATE(mkostemp64, 6, (char *t, int fl), (t, fl), char *, int)
TEMPLATE(mkstemps, 6 + (size_t)sfx, (char *t, int sfx), (t, sfx), char *, int)
TEMPLATE(mkstemps64, 6 + (size_t)sfx, (char *t, int sfx), (t, sfx), char *, int)
TEMPLATE(mkostemps, 6 + (size_t)sfx, (char *t, int sfx, int fl), (t, sfx, fl), char *, int, int)
TEMPLATE(mkostemps64, 6 + (size_t)sfx, (char *t, int sfx, int fl), (t, sfx, fl), char *, int, int)
NEXT(char *, mkdtemp, char *)
char *mkdtemp(char *t) {
  char b[PATH_MAX];
  if (map(t, b) == t) return real_mkdtemp()(t);
  if (!real_mkdtemp()(b)) return NULL;
  filled(t, b, 6);
  return t;
}
// glibc's own calls inside these (scandir's opendir, glob's and the walks' lstat) do not go through a preload.
typedef int (*scan_sel)(const struct dirent *);
typedef int (*scan_cmp)(const struct dirent **, const struct dirent **);
typedef int (*scan_sel64)(const struct dirent64 *);
typedef int (*scan_cmp64)(const struct dirent64 **, const struct dirent64 **);
WRAP1(int, scandir, (const char *p, struct dirent ***l, scan_sel s, scan_cmp c), (p, l, s, c), const char *, struct dirent ***, scan_sel, scan_cmp)
WRAP1(int, scandir64, (const char *p, struct dirent64 ***l, scan_sel64 s, scan_cmp64 c), (p, l, s, c), const char *, struct dirent64 ***, scan_sel64, scan_cmp64)
typedef int (*glob_err)(const char *, int);
NEXT(int, glob, const char *, int, glob_err, glob_t *)
int glob(const char *p, int f, glob_err e, glob_t *g) {
  char b[PATH_MAX];
  const char *m = map(p, b);
  int r = real_glob()(m, f, e, g);
  if (m != p) unmap_glob(g);
  return r;
}
typedef int (*nftw_cb)(const char *, const struct stat *, int, struct FTW *);
typedef int (*ftw_cb)(const char *, const struct stat *, int);
NEXT(int, nftw, const char *, nftw_cb, int, int)
int nftw(const char *p, nftw_cb fn, int fds, int f) {
  char b[PATH_MAX];
  const char *m = map(p, b);
  if (m == p) return real_nftw()(p, fn, fds, f);
  nftw_cb was = nftw_fn;
  nftw_fn = fn;
  int r = real_nftw()(m, nftw_tramp, fds, f);
  nftw_fn = was;
  return r;
}
NEXT(int, ftw, const char *, ftw_cb, int)
int ftw(const char *p, ftw_cb fn, int fds) {
  char b[PATH_MAX];
  const char *m = map(p, b);
  if (m == p) return real_ftw()(p, fn, fds);
  ftw_cb was = ftw_fn;
  ftw_fn = fn;
  int r = real_ftw()(m, ftw_tramp, fds);
  ftw_fn = was;
  return r;
}
typedef int (*fts_cmp)(const FTSENT **, const FTSENT **);
NEXT(FTS *, fts_open, char *const *, int, fts_cmp)
FTS *fts_open(char *const *argv, int o, fts_cmp c) {
  char *store[FTS_MAX], bufs[FTS_MAX][PATH_MAX];
  return real_fts_open()(fts_paths(argv, store, bufs, FTS_MAX), o, c);
}
NEXT(int, inotify_add_watch, int, const char *, uint32_t)
int inotify_add_watch(int fd, const char *p, uint32_t m) { char b[PATH_MAX]; return real_inotify_add_watch()(fd, map(p, b), m); }
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
  if (!real_renameat2()) return errno = ENOSYS, -1;  // glibc 2.28 on
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

/** The original realpath (calls made in this file are not rewritten on macOS; on Linux, the next definition). */
static char *real_path(const char *p, char *out) {
#ifdef __APPLE__
  return realpath(p, out);
#else
  return real_realpath()(p, out);
#endif
}

/** [f] (absolute) into [dst] without trailing slashes, and its realpath into [dst_real]; 0 if it is not usable. */
static int set_folder(const char *f, char *dst, size_t *len, char *dst_real, size_t *real_len) {
  size_t n = strlen(f);
  if (f[0] != '/' || n < 2 || n + 1 >= PATH_MAX) return 0;
  memcpy(dst, f, n + 1);
  while (*len = strlen(dst), *len > 1 && dst[*len - 1] == '/') dst[*len - 1] = 0;
  char r[PATH_MAX];
  if (real_path(dst, r)) {
    memcpy(dst_real, r, strlen(r) + 1);
    *real_len = strlen(dst_real);
  } else {
    dst_real[0] = 0;
    *real_len = 0;
  }
  return 1;
}

/**
 * Map DEVICE_ROOT and its aliases to [folder] (absolute, existing), the rest of /storage to [skeleton]/storage (a folder
 * holding storage/emulated/0 and storage/self/primary, empty, so the ancestors exist), or stop mapping with null.
 */
JNIEXPORT jboolean JNICALL Java_dev_apksim_DevicePaths_nativeMap(JNIEnv *env, jclass c, jstring folder, jstring skeleton) {
  (void)c;
  if (!folder) {
    active = 0;
    return JNI_TRUE;
  }
  const char *f = (*env)->GetStringUTFChars(env, folder, NULL);
  if (!f) return JNI_FALSE;
  const char *k = skeleton ? (*env)->GetStringUTFChars(env, skeleton, NULL) : NULL;
  active = 0;
  jboolean ok = JNI_FALSE;
  if (set_folder(f, host, &host_len, host_real, &host_real_len)) {
    if (!k || !set_folder(k, skel, &skel_len, skel_real, &skel_real_len)) skel_len = skel_real_len = 0;
    __sync_synchronize();
    active = 1;
    ok = JNI_TRUE;
  }
  if (k) (*env)->ReleaseStringUTFChars(env, skeleton, k);
  (*env)->ReleaseStringUTFChars(env, folder, f);
  return ok;
}
