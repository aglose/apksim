// --no-network's sandbox allows only `(remote ip "localhost:*")`, which macOS matches for 127.0.0.1 over an IPv4
// socket and ::1 over an IPv6 one. The JDK makes dual-stack IPv6 sockets and reaches 127.0.0.1 through them as
// ::ffff:127.0.0.1, which the sandbox refuses ("Operation not permitted"), and its profile language has no way to
// name that address. So this library (DYLD_INSERT_LIBRARIES, only under --no-network) interposes connect(): an IPv6
// TCP socket connecting to a v4-mapped loopback address is swapped for an IPv4 socket on the same descriptor, which
// then connects to the plain IPv4 address, from the same local port and address if the socket was bound. A socket
// that is IPv4 already (swapped before) connects in place, and one already connected is left to connect() as it is.
//
// Swapping closes the old socket, and with it its kqueue filters: a channel registered with a Selector before it
// connects would never hear of its readiness. macOS has no public call that lists a kqueue's filters, so this
// library also interposes kevent() and close() to keep its own record of the filters on each descriptor, and adds
// them again, to the same kqueues, on the new socket before it connects. Everything else goes through unchanged.
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/socket.h>
#include <unistd.h>

// ---------------------------------------------------------------- kqueue filters, per descriptor

typedef struct { int kq; struct kevent kev; } Filter;  // kev.ident is the descriptor; kev.flags without EV_ADD/EV_DELETE

static pthread_mutex_t filters_lock = PTHREAD_MUTEX_INITIALIZER;
static Filter *filters;
static size_t nfilters, capfilters;
static atomic_size_t filters_in_use;  // lets close() skip the lock while nothing is recorded

static void note_change(int kq, const struct kevent *c) {
  if (c->filter != EVFILT_READ && c->filter != EVFILT_WRITE) return;  // what selectors put on sockets
  for (size_t i = 0; i < nfilters; i++) {
    Filter *f = &filters[i];
    if (f->kq != kq || f->kev.ident != c->ident || f->kev.filter != c->filter) continue;
    if (c->flags & EV_DELETE) {
      filters[i] = filters[--nfilters];
    } else {
      uint16_t keep = f->kev.flags;
      f->kev = *c;
      f->kev.flags = (c->flags & ~(EV_ADD | EV_ENABLE | EV_DISABLE | EV_RECEIPT)) | (keep & EV_DISABLE);
      if (c->flags & EV_DISABLE) f->kev.flags |= EV_DISABLE;
      if (c->flags & EV_ENABLE) f->kev.flags &= ~EV_DISABLE;
    }
    atomic_store(&filters_in_use, nfilters);
    return;
  }
  if (!(c->flags & EV_ADD)) return;
  if (nfilters == capfilters) {
    size_t cap = capfilters ? capfilters * 2 : 64;
    Filter *grown = realloc(filters, cap * sizeof *filters);
    if (!grown) return;
    filters = grown;
    capfilters = cap;
  }
  Filter *f = &filters[nfilters++];
  f->kq = kq;
  f->kev = *c;
  f->kev.flags = c->flags & ~(EV_ADD | EV_ENABLE | EV_RECEIPT);
  atomic_store(&filters_in_use, nfilters);
}

static int loopback_kevent(int kq, const struct kevent *changes, int nchanges, struct kevent *events, int nevents,
                           const struct timespec *timeout) {
  if (nchanges > 0 && changes) {
    pthread_mutex_lock(&filters_lock);
    for (int i = 0; i < nchanges; i++) note_change(kq, &changes[i]);
    pthread_mutex_unlock(&filters_lock);
  }
  return kevent(kq, changes, nchanges, events, nevents, timeout);
}

// A closed descriptor takes its filters with it, and a closed kqueue all of its own.
static int loopback_close(int fd) {
  if (atomic_load(&filters_in_use) > 0) {
    pthread_mutex_lock(&filters_lock);
    for (size_t i = 0; i < nfilters;) {
      if (filters[i].kq == fd || (int)filters[i].kev.ident == fd) filters[i] = filters[--nfilters];
      else i++;
    }
    atomic_store(&filters_in_use, nfilters);
    pthread_mutex_unlock(&filters_lock);
  }
  return close(fd);
}

// After the socket under fd was replaced: the filters the old one had, on the new one.
static void restore_filters(int fd) {
  if (atomic_load(&filters_in_use) == 0) return;
  pthread_mutex_lock(&filters_lock);
  for (size_t i = 0; i < nfilters; i++) {
    if ((int)filters[i].kev.ident != fd) continue;
    struct kevent k = filters[i].kev;
    k.flags |= EV_ADD;
    kevent(filters[i].kq, &k, 1, NULL, 0, NULL);  // the real one: this is not a change to record
  }
  pthread_mutex_unlock(&filters_lock);
}

// ---------------------------------------------------------------- connect

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
  struct sockaddr_in a4;
  memset(&a4, 0, sizeof a4);
  a4.sin_len = sizeof a4;
  a4.sin_family = AF_INET;
  a4.sin_port = a6->sin6_port;
  memcpy(&a4.sin_addr, b + 12, 4);
  struct sockaddr_storage local;
  socklen_t llen = sizeof local;
  if (getsockname(fd, (struct sockaddr *)&local, &llen) != 0) return connect(fd, a, alen);
  // Swapped by an earlier connect (the caller still thinks it IPv6): connect in place, with connect()'s own answers
  // for a socket already connected or connecting (EISCONN, EALREADY).
  if (local.ss_family == AF_INET) return connect(fd, (const struct sockaddr *)&a4, sizeof a4);
  if (local.ss_family != AF_INET6) return connect(fd, a, alen);
  // Connected already (or a connect in progress): replacing it would drop the connection. connect() says why not.
  struct sockaddr_storage peer;
  socklen_t plen = sizeof peer;
  if (getpeername(fd, (struct sockaddr *)&peer, &plen) == 0) return connect(fd, a, alen);
  // TCP only. The JDK keeps treating a channel it made as IPv6 and later uses IPv6-level options on it: a
  // DatagramChannel's disconnect() "repairs" the socket with them, which fails (EINVAL) on an IPv4 one.
  int type;
  socklen_t tl = sizeof type;
  if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &tl) != 0 || type != SOCK_STREAM) return connect(fd, a, alen);
  // A socket already bound (an explicit bind) keeps its port and address on the new socket.
  // An IPv6 address IPv4 cannot express (::1, a real IPv6 one) leaves the socket alone: connect as it was.
  const struct sockaddr_in6 *l6 = (const struct sockaddr_in6 *)&local;
  struct sockaddr_in bind4;
  memset(&bind4, 0, sizeof bind4);
  bind4.sin_len = sizeof bind4;
  bind4.sin_family = AF_INET;
  int rebind = 0;
  int any = IN6_IS_ADDR_UNSPECIFIED(&l6->sin6_addr);
  if (l6->sin6_port != 0 || !any) {
    if (IN6_IS_ADDR_V4MAPPED(&l6->sin6_addr)) memcpy(&bind4.sin_addr, l6->sin6_addr.s6_addr + 12, 4);
    else if (!any) return connect(fd, a, alen);
    bind4.sin_port = l6->sin6_port;
    rebind = 1;
  }
  int s4 = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (s4 < 0) return connect(fd, a, alen);
  // What the JDK and native code set before connecting: blocking mode, close-on-exec and the usual options.
  int fl = fcntl(fd, F_GETFL), fdfl = fcntl(fd, F_GETFD);
  if (fl >= 0) fcntl(s4, F_SETFL, fl);
  static const int sol[] = {SO_REUSEADDR, SO_REUSEPORT, SO_KEEPALIVE, SO_RCVBUF, SO_SNDBUF, SO_NOSIGPIPE, SO_OOBINLINE};
  for (size_t i = 0; i < sizeof sol / sizeof *sol; i++) copy_opt(fd, s4, SOL_SOCKET, sol[i], sizeof(int));
  copy_opt(fd, s4, SOL_SOCKET, SO_LINGER, sizeof(struct linger));
  copy_opt(fd, s4, SOL_SOCKET, SO_RCVTIMEO, sizeof(struct timeval));
  copy_opt(fd, s4, SOL_SOCKET, SO_SNDTIMEO, sizeof(struct timeval));
  copy_opt(fd, s4, IPPROTO_TCP, TCP_NODELAY, sizeof(int));
  copy_opt(fd, s4, IPPROTO_TCP, TCP_KEEPALIVE, sizeof(int));
  // dup2 replaces the descriptor atomically, so no other thread can see it closed or reused in between.
  if (dup2(s4, fd) < 0) {
    int e = errno;
    close(s4);
    errno = e;
    return -1;
  }
  close(s4);
  if (fdfl >= 0) fcntl(fd, F_SETFD, fdfl);
  // Only now: until dup2 closed the old socket it held the port.
  if (rebind && bind(fd, (const struct sockaddr *)&bind4, sizeof bind4) != 0) return -1;
  // Before connecting, so readiness from the connect reaches the selectors that were waiting for it.
  restore_filters(fd);
  return connect(fd, (const struct sockaddr *)&a4, sizeof a4);
}

__attribute__((used)) static struct { const void *replacement, *replacee; } interposers[]
    __attribute__((section("__DATA,__interpose"))) = {
        {(const void *)loopback_connect, (const void *)connect},
        {(const void *)loopback_kevent, (const void *)kevent},
        {(const void *)loopback_close, (const void *)close},
};
