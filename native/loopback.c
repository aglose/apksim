// --no-network's sandbox allows only `(remote ip "localhost:*")`, which macOS matches for 127.0.0.1 over an IPv4
// socket and ::1 over an IPv6 one. The JDK makes dual-stack IPv6 sockets and reaches 127.0.0.1 through them as
// ::ffff:127.0.0.1, which the sandbox refuses ("Operation not permitted"), and its profile language has no way to
// name that address. So this library (DYLD_INSERT_LIBRARIES, only under --no-network) interposes connect(): an IPv6
// socket connecting to a v4-mapped loopback address is swapped for an IPv4 socket on the same descriptor, which
// then connects to the plain IPv4 address. Everything else goes to connect() unchanged.
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int copy_opt(int from, int to, int level, int name, size_t size) {
  char v[32];
  socklen_t len = (socklen_t)size;
  if (getsockopt(from, level, name, v, &len) != 0) return -1;
  return setsockopt(to, level, name, v, len);
}

static int loopback_connect(int fd, const struct sockaddr *a, socklen_t alen) {
  if (!a || a->sa_family != AF_INET6 || alen < (socklen_t)sizeof(struct sockaddr_in6)) return connect(fd, a, alen);
  const struct sockaddr_in6 *a6 = (const struct sockaddr_in6 *)a;
  const uint8_t *b = a6->sin6_addr.s6_addr;
  if (!IN6_IS_ADDR_V4MAPPED(&a6->sin6_addr) || b[12] != 127) return connect(fd, a, alen);
  int type, proto = 0;
  socklen_t tl = sizeof type;
  if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &tl) != 0) return connect(fd, a, alen);
  if (type == SOCK_STREAM) proto = IPPROTO_TCP; else if (type == SOCK_DGRAM) proto = IPPROTO_UDP;
  int s4 = socket(AF_INET, type, proto);
  if (s4 < 0) return connect(fd, a, alen);
  // What the JDK and native code set before connecting: blocking mode, close-on-exec and the usual options.
  int fl = fcntl(fd, F_GETFL), fdfl = fcntl(fd, F_GETFD);
  if (fl >= 0) fcntl(s4, F_SETFL, fl);
  static const int sol[] = {SO_REUSEADDR, SO_REUSEPORT, SO_KEEPALIVE, SO_RCVBUF, SO_SNDBUF, SO_NOSIGPIPE, SO_OOBINLINE,
                            SO_BROADCAST};
  for (size_t i = 0; i < sizeof sol / sizeof *sol; i++) copy_opt(fd, s4, SOL_SOCKET, sol[i], sizeof(int));
  copy_opt(fd, s4, SOL_SOCKET, SO_LINGER, sizeof(struct linger));
  copy_opt(fd, s4, SOL_SOCKET, SO_RCVTIMEO, sizeof(struct timeval));
  copy_opt(fd, s4, SOL_SOCKET, SO_SNDTIMEO, sizeof(struct timeval));
  if (type == SOCK_STREAM) {
    copy_opt(fd, s4, IPPROTO_TCP, TCP_NODELAY, sizeof(int));
    copy_opt(fd, s4, IPPROTO_TCP, TCP_KEEPALIVE, sizeof(int));
  }
  // dup2 replaces the descriptor atomically, so no other thread can see it closed or reused in between.
  if (dup2(s4, fd) < 0) {
    int e = errno;
    close(s4);
    errno = e;
    return -1;
  }
  close(s4);
  if (fdfl >= 0) fcntl(fd, F_SETFD, fdfl);
  struct sockaddr_in a4;
  memset(&a4, 0, sizeof a4);
  a4.sin_len = sizeof a4;
  a4.sin_family = AF_INET;
  a4.sin_port = a6->sin6_port;
  memcpy(&a4.sin_addr, b + 12, 4);
  return connect(fd, (const struct sockaddr *)&a4, sizeof a4);
}

__attribute__((used)) static struct { const void *replacement, *replacee; } interposers[]
    __attribute__((section("__DATA,__interpose"))) = {{(const void *)loopback_connect, (const void *)connect}};
