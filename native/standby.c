// The standby side of a warm launch (see Standby.java and native/apksim-client.c).
// A standby JVM has booted the framework and listens on a Unix socket. The launcher's client connects and sends one
// message: the launch's arguments plus its own stdin, stdout and stderr (SCM_RIGHTS). The standby puts those on fds
// 0-2, acknowledges, and from then on is that launch: its output reaches the user's terminal, and when it exits it
// sends the exit code back, which the client exits with. If the client goes away (Ctrl-C, terminal closed), the
// connection's end of file tells the instance to quit.
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int conn = -1;
static int pending[3] = {-1, -1, -1};

JNIEXPORT jint JNICALL Java_dev_apksim_Standby_nativeListen(JNIEnv *env, jclass c, jstring jpath) {
  const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof addr);
  addr.sun_family = AF_UNIX;
  if (strlen(path) >= sizeof addr.sun_path) {
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return -ENAMETOOLONG;
  }
  strcpy(addr.sun_path, path);
  // Bind under a temporary name and rename into place, so a client never finds the name before it accepts.
  char tmp[sizeof addr.sun_path + 8];
  snprintf(tmp, sizeof tmp, "%s.%d", path, (int) getpid());
  struct sockaddr_un taddr = addr;
  if (strlen(tmp) >= sizeof taddr.sun_path) {
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return -ENAMETOOLONG;
  }
  strcpy(taddr.sun_path, tmp);
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  unlink(tmp);
  int rc = fd < 0 ? -errno : 0;
  if (rc == 0 && bind(fd, (struct sockaddr *) &taddr, sizeof taddr) != 0) rc = -errno;
  if (rc == 0 && listen(fd, 4) != 0) rc = -errno;
  if (rc == 0 && rename(tmp, path) != 0) rc = -errno;
  (*env)->ReleaseStringUTFChars(env, jpath, path);
  if (rc != 0) {
    if (fd >= 0) close(fd);
    unlink(tmp);
    return rc;
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

// Waits up to timeoutMs for a launch. Returns its message (NUL-separated strings), or null on timeout. The caller then
// takes the launch (nativeAdopt) or turns it down (nativeDecline).
JNIEXPORT jbyteArray JNICALL Java_dev_apksim_Standby_nativeAccept(JNIEnv *env, jclass c, jint lfd, jint timeoutMs) {
  for (;;) {
    struct pollfd p = {lfd, POLLIN, 0};
    int n = poll(&p, 1, timeoutMs);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return NULL;
    int fd = accept(lfd, NULL, NULL);
    if (fd < 0) continue;
    uint32_t len = 0;
    char control[CMSG_SPACE(3 * sizeof(int))];
    struct iovec iov = {&len, sizeof len};
    struct msghdr msg;
    memset(&msg, 0, sizeof msg);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof control;
    ssize_t r;
    do r = recvmsg(fd, &msg, MSG_WAITALL); while (r < 0 && errno == EINTR);
    int fds[3] = {-1, -1, -1};
    struct cmsghdr *cm = CMSG_FIRSTHDR(&msg);
    if (cm && cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS && cm->cmsg_len == CMSG_LEN(3 * sizeof(int))) {
      memcpy(fds, CMSG_DATA(cm), sizeof fds);
    }
    char *body = NULL;
    int ok = r == (ssize_t) sizeof len && fds[2] >= 0 && len < (1u << 20);
    if (ok) {
      body = malloc(len ? len : 1);
      ok = body && read_full(fd, body, len) == 0;
    }
    if (!ok) {
      // Not a launch (or a broken one): forget it and keep waiting.
      for (int i = 0; i < 3; i++) if (fds[i] >= 0) close(fds[i]);
      free(body);
      close(fd);
      continue;
    }
    memcpy(pending, fds, sizeof fds);
    conn = fd;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (jbyte *) body);
    free(body);
    return out;
  }
}

JNIEXPORT void JNICALL Java_dev_apksim_Standby_nativeClose(JNIEnv *env, jclass c, jint fd) {
  close(fd);
}

// Takes the launch: its stdio onto fds 0-2, and tells the client (which then waits for the exit code).
JNIEXPORT jboolean JNICALL Java_dev_apksim_Standby_nativeAdopt(JNIEnv *env, jclass c) {
  for (int i = 0; i < 3; i++) {
    dup2(pending[i], i);
    close(pending[i]);
    pending[i] = -1;
  }
  char b = 'A';
  return conn >= 0 && write(conn, &b, 1) == 1;
}

// Turns the launch down: the client then makes a cold launch itself.
JNIEXPORT void JNICALL Java_dev_apksim_Standby_nativeDecline(JNIEnv *env, jclass c) {
  for (int i = 0; i < 3; i++) {
    if (pending[i] >= 0) close(pending[i]);
    pending[i] = -1;
  }
  if (conn >= 0) close(conn);
  conn = -1;
}

// Blocks until the client goes away.
JNIEXPORT void JNICALL Java_dev_apksim_Standby_nativeAwaitHangup(JNIEnv *env, jclass c) {
  char b;
  for (;;) {
    ssize_t r = read(conn, &b, 1);
    if (r == 0 || (r < 0 && errno != EINTR)) return;
  }
}

JNIEXPORT jboolean JNICALL Java_dev_apksim_Standby_nativeChdir(JNIEnv *env, jclass c, jstring jdir) {
  const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
  int ok = chdir(dir) == 0;
  (*env)->ReleaseStringUTFChars(env, jdir, dir);
  return ok;
}

JNIEXPORT void JNICALL Java_dev_apksim_Standby_nativeSendExit(JNIEnv *env, jclass c, jint code) {
  if (conn < 0) return;
  unsigned char b[5] = {'X', (unsigned char) (code >> 24), (unsigned char) (code >> 16), (unsigned char) (code >> 8),
                        (unsigned char) code};
  ssize_t r;
  do r = write(conn, b, sizeof b); while (r < 0 && errno == EINTR);
}
