// Sockets as Android code sees them, on top of macOS. The calls are the same, but Linux numbers address
// families, socket options and message flags differently, its sockaddr starts with a 16-bit family where
// BSD has a length byte and a family byte, and its msghdr and cmsghdr use wider length fields.
//
// Android also has no getifaddrs worth the name: libwebrtc lists interfaces by asking the kernel over a
// netlink socket (RTM_GETADDR), which macOS does not have. Such sockets are emulated here, answered from
// the host's own getifaddrs, along with the SIOCGIFFLAGS ioctl it asks next.
#define __APPLE_USE_RFC_3542 1  // IPV6_RECVPKTINFO and friends.
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/sockio.h>
#include <sys/un.h>
#include <unistd.h>

int bionic_errno_to_android(int host);

// ---------------------------------------------------------------- numbers

enum { A_AF_UNSPEC = 0, A_AF_UNIX = 1, A_AF_INET = 2, A_AF_INET6 = 10, A_AF_NETLINK = 16 };
enum { A_SOCK_NONBLOCK = 04000, A_SOCK_CLOEXEC = 02000000 };

static int af_a2m(int a) {
  switch (a) {
    case A_AF_INET6: return AF_INET6;
    default: return a;  // UNSPEC, UNIX and INET agree.
  }
}
static int af_m2a(int m) { return m == AF_INET6 ? A_AF_INET6 : m; }

static int msg_flags_a2m(int a) {
  int m = a & (MSG_OOB | MSG_PEEK | MSG_DONTROUTE);
  if (a & 0x20) m |= MSG_TRUNC;
  if (a & 0x40) m |= MSG_DONTWAIT;
  if (a & 0x100) m |= MSG_WAITALL;
  return m;  // MSG_NOSIGNAL: every socket made here has SO_NOSIGPIPE instead.
}
static int msg_flags_m2a(int m) {
  int a = m & (MSG_OOB | MSG_PEEK | MSG_DONTROUTE);
  if (m & MSG_TRUNC) a |= 0x20;
  if (m & MSG_CTRUNC) a |= 0x8;
  if (m & MSG_EOR) a |= 0x80;
  return a;
}

// Socket option (level, name) pairs, Android's on the left. Unlisted ones are accepted and ignored on set,
// so features macOS lacks (path MTU discovery, error queues) simply stay off.
typedef struct { int al, an, ml, mn; } Opt;
static const Opt opts[] = {
    {1, 2, SOL_SOCKET, SO_REUSEADDR}, {1, 3, SOL_SOCKET, SO_TYPE}, {1, 4, SOL_SOCKET, SO_ERROR},
    {1, 5, SOL_SOCKET, SO_DONTROUTE}, {1, 6, SOL_SOCKET, SO_BROADCAST}, {1, 7, SOL_SOCKET, SO_SNDBUF},
    {1, 8, SOL_SOCKET, SO_RCVBUF}, {1, 9, SOL_SOCKET, SO_KEEPALIVE}, {1, 10, SOL_SOCKET, SO_OOBINLINE},
    {1, 13, SOL_SOCKET, SO_LINGER}, {1, 15, SOL_SOCKET, SO_REUSEPORT}, {1, 18, SOL_SOCKET, SO_RCVLOWAT},
    {1, 19, SOL_SOCKET, SO_SNDLOWAT}, {1, 20, SOL_SOCKET, SO_RCVTIMEO}, {1, 21, SOL_SOCKET, SO_SNDTIMEO},
    {1, 29, SOL_SOCKET, SO_TIMESTAMP},
    {IPPROTO_IP, 1, IPPROTO_IP, IP_TOS}, {IPPROTO_IP, 2, IPPROTO_IP, IP_TTL}, {IPPROTO_IP, 8, IPPROTO_IP, IP_PKTINFO},
    {IPPROTO_IP, 32, IPPROTO_IP, IP_MULTICAST_IF}, {IPPROTO_IP, 33, IPPROTO_IP, IP_MULTICAST_TTL},
    {IPPROTO_IP, 34, IPPROTO_IP, IP_MULTICAST_LOOP},
    {IPPROTO_IPV6, 16, IPPROTO_IPV6, IPV6_UNICAST_HOPS}, {IPPROTO_IPV6, 26, IPPROTO_IPV6, IPV6_V6ONLY},
    {IPPROTO_IPV6, 49, IPPROTO_IPV6, IPV6_RECVPKTINFO}, {IPPROTO_IPV6, 67, IPPROTO_IPV6, IPV6_TCLASS},
    {IPPROTO_IPV6, 62, IPPROTO_IPV6, IPV6_DONTFRAG},
    {IPPROTO_TCP, 1, IPPROTO_TCP, TCP_NODELAY}, {IPPROTO_TCP, 2, IPPROTO_TCP, TCP_MAXSEG},
    {IPPROTO_TCP, 4, IPPROTO_TCP, TCP_KEEPALIVE}, {IPPROTO_TCP, 5, IPPROTO_TCP, TCP_KEEPINTVL},
    {IPPROTO_TCP, 6, IPPROTO_TCP, TCP_KEEPCNT},
};
static const Opt *find_opt(int level, int name) {
  for (size_t i = 0; i < sizeof opts / sizeof opts[0]; i++) {
    if (opts[i].al == level && opts[i].an == name) return &opts[i];
  }
  return NULL;
}

// ---------------------------------------------------------------- addresses

// Android's sockaddr begins with a 16-bit family; BSD's with a length byte and a family byte. The rest of
// sockaddr_in, sockaddr_in6 and sockaddr_un lines up.
static int addr_a2m(const void *a, socklen_t alen, struct sockaddr_storage *m, socklen_t *mlen) {
  if (!a) return 0;
  if (alen < 2 || alen > sizeof *m) {
    errno = EINVAL;
    return -1;
  }
  int family = *(const uint16_t *)a;
  memset(m, 0, sizeof *m);
  memcpy(m, a, alen);
  struct sockaddr *s = (struct sockaddr *)m;
  s->sa_family = (sa_family_t)af_a2m(family);
  switch (family) {
    case A_AF_INET: *mlen = sizeof(struct sockaddr_in); break;
    case A_AF_INET6: *mlen = sizeof(struct sockaddr_in6); break;
    case A_AF_UNIX: *mlen = alen > sizeof(struct sockaddr_un) ? sizeof(struct sockaddr_un) : alen; break;
    default: *mlen = alen; break;
  }
  s->sa_len = (uint8_t)*mlen;
  return 0;
}

// Writes a host address in Android's layout into a caller buffer of *alen bytes, and sets *alen to its size.
static void addr_m2a(const struct sockaddr_storage *m, socklen_t mlen, void *a, socklen_t *alen) {
  if (!a || !alen) return;
  unsigned char tmp[sizeof *m];
  memcpy(tmp, m, sizeof tmp);
  const struct sockaddr *s = (const struct sockaddr *)m;
  *(uint16_t *)tmp = (uint16_t)af_m2a(s->sa_family);
  socklen_t full = s->sa_family == AF_INET ? sizeof(struct sockaddr_in)
                   : s->sa_family == AF_INET6 ? sizeof(struct sockaddr_in6) : mlen;
  memcpy(a, tmp, full < *alen ? full : *alen);
  *alen = full;
}

// ---------------------------------------------------------------- netlink

typedef struct { uint32_t len; uint16_t type, flags; uint32_t seq, pid; } NlHdr;
typedef struct { uint8_t family, prefixlen, flags, scope; uint32_t index; } IfaMsg;
typedef struct { uint16_t len, type; } RtAttr;

#define MAX_NETLINK 16
static pthread_mutex_t nl_lock = PTHREAD_MUTEX_INITIALIZER;
static struct { int fd; char *reply; size_t len; } nl[MAX_NETLINK];

static int nl_slot(int fd) {
  for (int i = 0; i < MAX_NETLINK; i++) {
    if (nl[i].fd == fd && fd > 0) return i;
  }
  return -1;
}

static int prefix_of(const struct sockaddr *mask) {
  if (!mask) return 0;
  const unsigned char *b;
  int n;
  if (mask->sa_family == AF_INET) {
    b = (const unsigned char *)&((const struct sockaddr_in *)mask)->sin_addr;
    n = 4;
  } else {
    b = (const unsigned char *)&((const struct sockaddr_in6 *)mask)->sin6_addr;
    n = 16;
  }
  int bits = 0;
  for (int i = 0; i < n; i++) bits += __builtin_popcount(b[i]);
  return bits;
}

// One RTM_NEWADDR per address, then NLMSG_DONE: what Linux answers an RTM_GETADDR dump with.
static void nl_build_addresses(int slot, uint32_t seq) {
  size_t cap = 4096, len = 0;
  char *out = calloc(1, cap);
  struct ifaddrs *list = NULL;
  getifaddrs(&list);
  for (struct ifaddrs *i = list; i; i = i->ifa_next) {
    if (!i->ifa_addr || (i->ifa_addr->sa_family != AF_INET && i->ifa_addr->sa_family != AF_INET6)) continue;
    int v6 = i->ifa_addr->sa_family == AF_INET6;
    size_t alen = v6 ? 16 : 4;
    size_t need = sizeof(NlHdr) + sizeof(IfaMsg) + ((sizeof(RtAttr) + alen + 3) & ~3UL);
    if (len + need + 64 > cap) out = realloc(out, cap *= 2);
    NlHdr *h = (NlHdr *)(out + len);
    memset(h, 0, need);
    h->len = (uint32_t)need;
    h->type = 20;  // RTM_NEWADDR
    h->flags = 2;  // NLM_F_MULTI
    h->seq = seq;
    IfaMsg *m = (IfaMsg *)(h + 1);
    m->family = v6 ? A_AF_INET6 : A_AF_INET;
    m->prefixlen = (uint8_t)prefix_of(i->ifa_netmask);
    m->index = if_nametoindex(i->ifa_name);
    const void *addr = v6 ? (const void *)&((struct sockaddr_in6 *)i->ifa_addr)->sin6_addr
                          : (const void *)&((struct sockaddr_in *)i->ifa_addr)->sin_addr;
    if (v6 && IN6_IS_ADDR_LINKLOCAL((const struct in6_addr *)addr)) m->scope = 253;  // RT_SCOPE_LINK
    if (i->ifa_flags & IFF_LOOPBACK) m->scope = 254;  // RT_SCOPE_HOST
    RtAttr *r = (RtAttr *)(m + 1);
    r->len = (uint16_t)(sizeof(RtAttr) + alen);
    r->type = v6 ? 1 : 2;  // IFA_ADDRESS for IPv6, IFA_LOCAL for IPv4, as libwebrtc reads them.
    memcpy(r + 1, addr, alen);
    if (v6) {
      // macOS's scoped link-local addresses carry the interface in bytes 2-3; Linux's do not.
      unsigned char *b = (unsigned char *)(r + 1);
      if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) b[2] = b[3] = 0;
    }
    len += need;
  }
  freeifaddrs(list);
  NlHdr *done = (NlHdr *)(out + len);
  memset(done, 0, sizeof *done + 4);
  done->len = sizeof *done + 4;
  done->type = 3;  // NLMSG_DONE
  done->flags = 2;
  done->seq = seq;
  len += done->len;
  free(nl[slot].reply);
  nl[slot].reply = out;
  nl[slot].len = len;
}

static ssize_t nl_send(int slot, const void *buf, size_t n) {
  if (n >= sizeof(NlHdr)) {
    const NlHdr *h = buf;
    pthread_mutex_lock(&nl_lock);
    if (h->type == 22) nl_build_addresses(slot, h->seq);  // RTM_GETADDR
    pthread_mutex_unlock(&nl_lock);
  }
  return (ssize_t)n;
}

static ssize_t nl_recv(int slot, void *buf, size_t n) {
  pthread_mutex_lock(&nl_lock);
  size_t len = nl[slot].len < n ? nl[slot].len : n;
  if (nl[slot].reply) memcpy(buf, nl[slot].reply, len);
  free(nl[slot].reply);
  nl[slot].reply = NULL;
  nl[slot].len = 0;
  pthread_mutex_unlock(&nl_lock);
  return (ssize_t)len;
}

// ---------------------------------------------------------------- calls

static int net_socket(int domain, int type, int proto) {
  int fd;
  if (domain == A_AF_NETLINK) {
    // A real descriptor, so close() and the descriptor table behave; its traffic is answered here.
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) < 0) return -1;
    close(pair[1]);
    fd = pair[0];
    pthread_mutex_lock(&nl_lock);
    for (int i = 0; i < MAX_NETLINK; i++) {
      if (nl[i].fd <= 0) {
        nl[i].fd = fd;
        break;
      }
    }
    pthread_mutex_unlock(&nl_lock);
    return fd;
  }
  fd = socket(af_a2m(domain), type & 0xf, proto);
  if (fd < 0) return -1;
  int one = 1;
  setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
  if (type & A_SOCK_NONBLOCK) fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
  if (type & A_SOCK_CLOEXEC) fcntl(fd, F_SETFD, FD_CLOEXEC);
  return fd;
}

static int net_socketpair(int domain, int type, int proto, int sv[2]) {
  int r = socketpair(af_a2m(domain), type & 0xf, proto, sv);
  if (r == 0) {
    for (int i = 0; i < 2; i++) {
      int one = 1;
      setsockopt(sv[i], SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
      if (type & A_SOCK_NONBLOCK) fcntl(sv[i], F_SETFL, fcntl(sv[i], F_GETFL) | O_NONBLOCK);
      if (type & A_SOCK_CLOEXEC) fcntl(sv[i], F_SETFD, FD_CLOEXEC);
    }
  }
  return r;
}

static int net_close(int fd) {
  pthread_mutex_lock(&nl_lock);
  int s = nl_slot(fd);
  if (s >= 0) {
    free(nl[s].reply);
    nl[s].reply = NULL;
    nl[s].len = 0;
    nl[s].fd = 0;
  }
  pthread_mutex_unlock(&nl_lock);
  return close(fd);
}

static int net_bind(int fd, const void *a, socklen_t alen) {
  if (nl_slot(fd) >= 0) return 0;
  struct sockaddr_storage m;
  socklen_t mlen;
  if (addr_a2m(a, alen, &m, &mlen) < 0) return -1;
  return bind(fd, (struct sockaddr *)&m, mlen);
}

// `network offline` (dev.apksim.net.Net, set through ElfLoader.nativeSetOffline): native code's connections to
// anything but loopback fail as with no network, and names do not resolve, as Java's do.
volatile int net_offline;

static int loopback_m(const struct sockaddr_storage *m) {
  if (m->ss_family == AF_INET) return (ntohl(((const struct sockaddr_in *)m)->sin_addr.s_addr) >> 24) == 127;
  if (m->ss_family == AF_INET6) {
    const struct in6_addr *a = &((const struct sockaddr_in6 *)m)->sin6_addr;
    return IN6_IS_ADDR_LOOPBACK(a) || (IN6_IS_ADDR_V4MAPPED(a) && a->s6_addr[12] == 127);
  }
  return 1;  // not an internet address (Unix sockets)
}

static int net_connect(int fd, const void *a, socklen_t alen) {
  struct sockaddr_storage m;
  socklen_t mlen;
  if (addr_a2m(a, alen, &m, &mlen) < 0) return -1;
  if (net_offline && !loopback_m(&m)) {
    errno = ENETUNREACH;
    return -1;
  }
  return connect(fd, (struct sockaddr *)&m, mlen);
}

static int net_accept(int fd, void *a, socklen_t *alen) {
  struct sockaddr_storage m;
  socklen_t mlen = sizeof m;
  int r = accept(fd, (struct sockaddr *)&m, &mlen);
  if (r >= 0) {
    int one = 1;
    setsockopt(r, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
    addr_m2a(&m, mlen, a, alen);
  }
  return r;
}

static int name_of(int fd, void *a, socklen_t *alen, int peer) {
  if (nl_slot(fd) >= 0) {
    // sockaddr_nl: family, padding, port id, groups.
    uint32_t nla[3] = {A_AF_NETLINK, (uint32_t)getpid(), 0};
    memcpy(a, nla, *alen < sizeof nla ? *alen : sizeof nla);
    *alen = sizeof nla;
    return 0;
  }
  struct sockaddr_storage m;
  socklen_t mlen = sizeof m;
  int r = peer ? getpeername(fd, (struct sockaddr *)&m, &mlen) : getsockname(fd, (struct sockaddr *)&m, &mlen);
  if (r == 0) addr_m2a(&m, mlen, a, alen);
  return r;
}
static int net_getsockname(int fd, void *a, socklen_t *alen) { return name_of(fd, a, alen, 0); }
static int net_getpeername(int fd, void *a, socklen_t *alen) { return name_of(fd, a, alen, 1); }

static ssize_t net_send(int fd, const void *b, size_t n, int flags) {
  int s = nl_slot(fd);
  if (s >= 0) return nl_send(s, b, n);
  return send(fd, b, n, msg_flags_a2m(flags));
}

static ssize_t net_sendto(int fd, const void *b, size_t n, int flags, const void *a, socklen_t alen) {
  int s = nl_slot(fd);
  if (s >= 0) return nl_send(s, b, n);
  if (!a) return send(fd, b, n, msg_flags_a2m(flags));
  struct sockaddr_storage m;
  socklen_t mlen;
  if (addr_a2m(a, alen, &m, &mlen) < 0) return -1;
  return sendto(fd, b, n, msg_flags_a2m(flags), (struct sockaddr *)&m, mlen);
}

static ssize_t net_recv(int fd, void *b, size_t n, int flags) {
  int s = nl_slot(fd);
  if (s >= 0) return nl_recv(s, b, n);
  return recv(fd, b, n, msg_flags_a2m(flags));
}

static ssize_t net_recvfrom(int fd, void *b, size_t n, int flags, void *a, socklen_t *alen) {
  int s = nl_slot(fd);
  if (s >= 0) return nl_recv(s, b, n);
  struct sockaddr_storage m;
  socklen_t mlen = sizeof m;
  ssize_t r = recvfrom(fd, b, n, msg_flags_a2m(flags), a ? (struct sockaddr *)&m : NULL, a ? &mlen : NULL);
  if (r >= 0 && a) addr_m2a(&m, mlen, a, alen);
  return r;
}

// Android's msghdr and cmsghdr: lengths are size_t where BSD's are int and socklen_t.
typedef struct { void *name; socklen_t namelen; struct iovec *iov; size_t iovlen; void *control; size_t controllen; int flags; } AMsgHdr;
typedef struct { size_t len; int level, type; } ACmsgHdr;

static ssize_t net_sendmsg(int fd, const AMsgHdr *a, int flags) {
  int s = nl_slot(fd);
  if (s >= 0) return a->iovlen ? nl_send(s, a->iov[0].iov_base, a->iov[0].iov_len) : 0;
  struct sockaddr_storage m;
  socklen_t mlen = 0;
  if (a->name && addr_a2m(a->name, a->namelen, &m, &mlen) < 0) return -1;
  struct msghdr h = {a->name ? &m : NULL, mlen, a->iov, (int)a->iovlen, NULL, 0, 0};
  return sendmsg(fd, &h, msg_flags_a2m(flags));  // Ancillary data on send is not translated.
}

static ssize_t net_recvmsg(int fd, AMsgHdr *a, int flags) {
  int s = nl_slot(fd);
  if (s >= 0) {
    a->controllen = 0;
    a->flags = 0;
    return a->iovlen ? nl_recv(s, a->iov[0].iov_base, a->iov[0].iov_len) : 0;
  }
  struct sockaddr_storage m;
  char control[256];
  struct msghdr h = {a->name ? &m : NULL, a->name ? sizeof m : 0, a->iov, (int)a->iovlen,
                     a->control ? control : NULL, a->control ? sizeof control : 0, 0};
  ssize_t r = recvmsg(fd, &h, msg_flags_a2m(flags));
  if (r < 0) return r;
  if (a->name) addr_m2a(&m, h.msg_namelen, a->name, &a->namelen);
  // Only receive timestamps are passed on; libwebrtc goes without any other ancillary data.
  size_t used = 0;
  if (a->control) {
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&h); c; c = CMSG_NXTHDR(&h, c)) {
      if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_TIMESTAMP) continue;
      size_t total = sizeof(ACmsgHdr) + sizeof(struct timeval);
      if (used + total > a->controllen) {
        a->flags |= 0x8;
        break;
      }
      ACmsgHdr *o = (ACmsgHdr *)((char *)a->control + used);
      o->len = total;
      o->level = 1;
      o->type = 29;  // SO_TIMESTAMP
      struct timeval tv;
      memcpy(&tv, CMSG_DATA(c), sizeof tv);
      int64_t out[2] = {tv.tv_sec, tv.tv_usec};  // Android's timeval is two longs.
      memcpy(o + 1, out, sizeof out);
      used += (total + 7) & ~7UL;
    }
  }
  a->controllen = used;
  a->flags = msg_flags_m2a(h.msg_flags);
  return r;
}

static int net_setsockopt(int fd, int level, int name, const void *v, socklen_t len) {
  if (nl_slot(fd) >= 0) return 0;
  const Opt *o = find_opt(level, name);
  if (!o) return 0;  // Unknown here: accepted and ignored.
  if (o->ml == SOL_SOCKET && (o->mn == SO_RCVTIMEO || o->mn == SO_SNDTIMEO) && len >= 16) {
    const int64_t *t = v;
    struct timeval tv = {(time_t)t[0], (suseconds_t)t[1]};
    return setsockopt(fd, o->ml, o->mn, &tv, sizeof tv);
  }
  int r = setsockopt(fd, o->ml, o->mn, v, len);
  // Options the host refuses for this socket type are not worth failing a connection over.
  if (r < 0 && (errno == ENOPROTOOPT || errno == EINVAL)) return 0;
  return r;
}

static int net_getsockopt(int fd, int level, int name, void *v, socklen_t *len) {
  const Opt *o = find_opt(level, name);
  if (!o) {
    errno = ENOPROTOOPT;
    return -1;
  }
  int r = getsockopt(fd, o->ml, o->mn, v, len);
  if (r == 0 && o->ml == SOL_SOCKET && o->mn == SO_ERROR && *len >= sizeof(int)) {
    *(int *)v = bionic_errno_to_android(*(int *)v);
  }
  return r;
}

static int net_poll(struct pollfd *fds, nfds_t n, int timeout) {
  for (nfds_t i = 0; i < n; i++) fds[i].events &= ~0x2000;  // POLLRDHUP
  return poll(fds, n, timeout);
}

static int net_inet_pton(int af, const char *src, void *dst) { return inet_pton(af_a2m(af), src, dst); }
static const char *net_inet_ntop(int af, const void *src, char *dst, socklen_t size) {
  return inet_ntop(af_a2m(af), src, dst, size);
}

// Android's addrinfo has the BSD field order, so only the family and the address inside need converting.
static int net_getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res) {
  struct addrinfo h, *list = NULL;
  if (net_offline && node && strcmp(node, "localhost") != 0) {
    unsigned char buf[sizeof(struct in6_addr)];
    if (inet_pton(AF_INET, node, buf) != 1 && inet_pton(AF_INET6, node, buf) != 1) return EAI_NONAME;
  }
  if (hints) {
    h = *hints;
    h.ai_family = af_a2m(hints->ai_family);
  }
  int r = getaddrinfo(node, service, hints ? &h : NULL, &list);
  if (r != 0) return r;
  struct addrinfo *head = NULL, **tail = &head;
  for (struct addrinfo *i = list; i; i = i->ai_next) {
    struct addrinfo *c = calloc(1, sizeof *c + sizeof(struct sockaddr_storage));
    *c = *i;
    c->ai_next = NULL;
    c->ai_family = af_m2a(i->ai_family);
    c->ai_canonname = i->ai_canonname ? strdup(i->ai_canonname) : NULL;
    c->ai_addr = (struct sockaddr *)(c + 1);
    socklen_t alen = sizeof(struct sockaddr_storage);
    struct sockaddr_storage m;
    memset(&m, 0, sizeof m);
    memcpy(&m, i->ai_addr, i->ai_addrlen);
    addr_m2a(&m, i->ai_addrlen, c->ai_addr, &alen);
    c->ai_addrlen = alen;
    *tail = c;
    tail = &c->ai_next;
  }
  freeaddrinfo(list);
  *res = head;
  return 0;
}
static void net_freeaddrinfo(struct addrinfo *a) {
  while (a) {
    struct addrinfo *n = a->ai_next;
    free(a->ai_canonname);
    free(a);
    a = n;
  }
}

// Interface ioctls: Android's ifreq is 40 bytes and BSD's 32, but the name and the first union member
// share offsets. Flags agree except IFF_MULTICAST.
int net_ioctl(int fd, unsigned long req, void *arg) {
  struct ifreq r;
  memset(&r, 0, sizeof r);
  memcpy(r.ifr_name, arg, IFNAMSIZ);
  switch (req) {
    case 0x8913: {  // SIOCGIFFLAGS
      if (ioctl(fd, SIOCGIFFLAGS, &r) < 0) return -1;
      unsigned short f = (unsigned short)r.ifr_flags;
      unsigned short a = f & 0x3ff;  // Above that, macOS has its own flags.
      if (f & IFF_MULTICAST) a |= 0x1000;
      *(unsigned short *)((char *)arg + 16) = a;
      return 0;
    }
    case 0x8933: {  // SIOCGIFINDEX
      unsigned idx = if_nametoindex(r.ifr_name);
      if (!idx) {
        errno = ENODEV;
        return -1;
      }
      *(int *)((char *)arg + 16) = (int)idx;
      return 0;
    }
    case 0x8921: {  // SIOCGIFMTU
      if (ioctl(fd, SIOCGIFMTU, &r) < 0) return -1;
      *(int *)((char *)arg + 16) = r.ifr_mtu;
      return 0;
    }
    default:
      errno = ENOTTY;
      return -1;
  }
}

typedef struct { const char *name; void *fn; } NetEntry;
static const NetEntry net_table[] = {
    {"socket", net_socket}, {"socketpair", net_socketpair}, {"close", net_close}, {"bind", net_bind},
    {"connect", net_connect}, {"accept", net_accept}, {"getsockname", net_getsockname},
    {"getpeername", net_getpeername}, {"send", net_send}, {"sendto", net_sendto}, {"sendmsg", net_sendmsg},
    {"recv", net_recv}, {"recvfrom", net_recvfrom}, {"recvmsg", net_recvmsg}, {"setsockopt", net_setsockopt},
    {"getsockopt", net_getsockopt}, {"poll", net_poll}, {"inet_pton", net_inet_pton},
    {"inet_ntop", net_inet_ntop}, {"getaddrinfo", net_getaddrinfo}, {"freeaddrinfo", net_freeaddrinfo},
};

void *net_lookup(const char *name) {
  for (size_t i = 0; i < sizeof net_table / sizeof net_table[0]; i++) {
    if (!strcmp(net_table[i].name, name)) return net_table[i].fn;
  }
  return NULL;
}
