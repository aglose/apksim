// The launcher's fast path: hands a launch to a standby JVM that has already booted the framework (Standby.java,
// native/standby.c), instead of starting a JVM.
//
//   apksim-client launch LAUNCHER ROOT FLAGS -- ARG...   hand ARG... (-D options, then the APK) to the standby for this
//                                                        folder; if there is none, run LAUNCHER again for a cold launch
//   apksim-client sock ROOT FLAGS                        print the standby socket path for ROOT and FLAGS
//   apksim-client detach LOG CMD ARG...                  run CMD in a session of its own, output to LOG
//
// A standby is per apksim folder and sandbox flags (FLAGS: "", "n" no network, "s" no sandbox): those are fixed
// when its JVM starts. It changes to the launch's working directory when it takes the launch. While a launch runs, this process stands in for it: the instance writes to
// this process's terminal, and its exit code becomes this process's. If this process is killed, the instance quits.
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

static void sock_path(char *out, size_t n, const char *root, const char *flags) {
  // FNV-1a over everything a standby's JVM fixes at its start: the apksim folder and the sandbox flags. (Not the
  // working directory: the standby moves to the launch's.)
  uint64_t h = 1469598103934665603ULL;
  const char *parts[2] = {root, flags};
  for (int i = 0; i < 2; i++) {
    for (const char *p = parts[i]; *p; p++) h = (h ^ (unsigned char) *p) * 1099511628211ULL;
    h = (h ^ '|') * 1099511628211ULL;
  }
  const char *tmp = getenv("TMPDIR");
  if (!tmp || !*tmp) tmp = "/tmp/";
  snprintf(out, n, "%s%sapksim-standby-%016llx.sock", tmp, tmp[strlen(tmp) - 1] == '/' ? "" : "/", (unsigned long long) h);
}

static long long now_ms(void) {
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return (long long) tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

// How long ago a standby started booting (its pid file's age), in ms, or -1.
static long long age_ms(const char *pidfile) {
  struct stat st;
  if (stat(pidfile, &st) != 0) return -1;
#ifdef __APPLE__
  long long t = (long long) st.st_mtimespec.tv_sec * 1000 + st.st_mtimespec.tv_nsec / 1000000;
#else
  long long t = (long long) st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1000000;
#endif
  return now_ms() - t;
}

static int pid_alive(const char *pidfile) {
  FILE *f = fopen(pidfile, "r");
  if (!f) return 0;
  long pid = 0;
  int ok = fscanf(f, "%ld", &pid) == 1;
  fclose(f);
  return ok && pid > 0 && (kill((pid_t) pid, 0) == 0 || errno == EPERM);
}

static int try_connect(const char *path) {
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof addr);
  addr.sun_family = AF_UNIX;
  strncpy(addr.sun_path, path, sizeof addr.sun_path - 1);
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0) return -1;
  if (connect(fd, (struct sockaddr *) &addr, sizeof addr) != 0) {
    close(fd);
    return -1;
  }
  return fd;
}

static int read_full(int fd, void *buf, size_t n) {
  size_t got = 0;
  while (got < n) {
    ssize_t r = read(fd, (char *) buf + got, n - got);
    if (r < 0 && errno == EINTR) continue;
    if (r <= 0) return -1;
    got += (size_t) r;
  }
  return 0;
}

static int launch(int argc, char **argv) {
  // argv: launch LAUNCHER ROOT FLAGS -- ARG...
  const char *launcher = argv[2], *root = argv[3], *flags = argv[4];
  // The message arguments run up to a second "--"; after it come the launcher's own arguments, for a cold launch.
  int sep = argc;
  for (int i = 6; i < argc; i++) {
    if (strcmp(argv[i], "--") == 0) {
      sep = i;
      break;
    }
  }
  const char *t0env = getenv("APKSIM_T0");
  long long t0 = t0env && *t0env ? atoll(t0env) : now_ms();
  char path[1024], pidfile[1100];
  sock_path(path, sizeof path, root, flags);
  snprintf(pidfile, sizeof pidfile, "%s.pid", path);
  int fd = try_connect(path);
  if (fd < 0 && pid_alive(pidfile)) {
    // A standby is booting (~2.2 s to ready, a cold launch ~1.3 s): wait for one that is past the first second of it,
    // and give up on one that is slow (a busy machine) after 6 s in all.
    long long age = age_ms(pidfile);
    if (age >= 1000) {
      long long deadline = now_ms() + (6000 - age);
      while (fd < 0 && pid_alive(pidfile) && now_ms() < deadline) {
        usleep(2000);
        fd = try_connect(path);
      }
    }
  }
  if (fd >= 0) {
    // Message: T0, the working directory, then the launch's arguments, NUL-separated.
    char cwd[4096];
    if (!getcwd(cwd, sizeof cwd)) cwd[0] = 0;
    size_t len = 0;
    char t0s[32];
    snprintf(t0s, sizeof t0s, "%lld", t0);
    len += strlen(t0s) + 1 + strlen(cwd) + 1;
    for (int i = 6; i < sep; i++) len += strlen(argv[i]) + 1;
    char *body = malloc(len);
    char *p = body;
    p = stpcpy(p, t0s) + 1;
    p = stpcpy(p, cwd) + 1;
    for (int i = 6; i < sep; i++) p = stpcpy(p, argv[i]) + 1;
    uint32_t n = (uint32_t) len;
    int fds[3] = {0, 1, 2};
    char control[CMSG_SPACE(sizeof fds)];
    memset(control, 0, sizeof control);
    struct iovec iov = {&n, sizeof n};
    struct msghdr msg;
    memset(&msg, 0, sizeof msg);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof control;
    struct cmsghdr *cm = CMSG_FIRSTHDR(&msg);
    cm->cmsg_level = SOL_SOCKET;
    cm->cmsg_type = SCM_RIGHTS;
    cm->cmsg_len = CMSG_LEN(sizeof fds);
    memcpy(CMSG_DATA(cm), fds, sizeof fds);
    signal(SIGPIPE, SIG_IGN);
    char ack = 0;
    if (sendmsg(fd, &msg, 0) == (ssize_t) sizeof n && write(fd, body, len) == (ssize_t) len && read_full(fd, &ack, 1) == 0 &&
        ack == 'A') {
      // The launch is the standby's now. Wait for its exit code; Ctrl-C or a kill ends this process, and the
      // instance, seeing the connection close, quits too.
      unsigned char x[5];
      if (read_full(fd, x, 5) == 0 && x[0] == 'X') return (x[1] << 24) | (x[2] << 16) | (x[3] << 8) | x[4];
      return 134;  // the instance died without an exit code (crashed)
    }
    free(body);
    close(fd);
  }
  // No standby (or it declined): a cold launch, the same arguments through the launcher.
  setenv("APKSIM_STANDBY", "cold", 1);
  char t0buf[32];
  snprintf(t0buf, sizeof t0buf, "%lld", t0);
  setenv("APKSIM_T0", t0buf, 1);
  char **args = calloc((size_t) argc + 2, sizeof(char *));
  int k = 0;
  args[k++] = (char *) launcher;
  for (int i = sep + 1; i < argc; i++) args[k++] = argv[i];
  args[k] = NULL;
  execv(launcher, args);
  perror("apksim-client: exec launcher");
  return 127;
}

int main(int argc, char **argv) {
  if (argc >= 4 && strcmp(argv[1], "sock") == 0) {
    char path[1024];
    sock_path(path, sizeof path, argv[2], argv[3]);
    puts(path);
    return 0;
  }
  if (argc >= 4 && strcmp(argv[1], "detach") == 0) {
    pid_t pid = fork();
    if (pid < 0) return 1;
    if (pid > 0) return 0;
    setsid();
    int in = open("/dev/null", O_RDONLY);
    int out = open(argv[2], O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (in >= 0) dup2(in, 0);
    if (out >= 0) {
      dup2(out, 1);
      dup2(out, 2);
    }
    for (int i = 3; i < 256; i++) close(i);
    signal(SIGHUP, SIG_IGN);
    execv(argv[3], argv + 3);
    _exit(127);
  }
  if (argc >= 6 && strcmp(argv[1], "launch") == 0 && strcmp(argv[5], "--") == 0) return launch(argc, argv);
  fprintf(stderr, "usage: apksim-client launch LAUNCHER ROOT FLAGS -- ARG... [-- ORIG...] | sock ROOT FLAGS | detach LOG CMD...\n");
  return 2;
}
