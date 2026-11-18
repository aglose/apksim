// The NDK's ALooper (<android/looper.h>) for loaded ELF code, on macOS and Linux alike (issue 477: native SDKs run
// their event loops on it, and an unresolved ALooper_prepare ended the app).
//
// A looper is a set of watched file descriptors and a wake pipe, polled with poll(2), as Android's Looper does with
// epoll: level-triggered, ALOOPER_POLL_* results, callbacks unregistered when they return 0, idents returned for fds
// without a callback. A thread without a Java Looper (a native thread the library made) runs it itself with
// ALooper_pollOnce/pollAll. A thread with a Java Looper (the main thread, a HandlerThread) gets one backed by it, as on a
// device, where the Java Looper and the native one are the same object: nobody on that thread calls pollOnce, so a
// watcher thread (apksim-alooper) polls its fds and, when one is ready, posts an asynchronous message to that Looper
// (dev.apksim.NativeLoopers) that runs the callbacks there, then waits until it has run before polling again.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "elfloader.h"

enum { POLL_WAKE = -1, POLL_CALLBACK = -2, POLL_TIMEOUT = -3, POLL_ERROR = -4 };
enum { EV_INPUT = 1, EV_OUTPUT = 2, EV_ERROR = 4, EV_HANGUP = 8, EV_INVALID = 16 };
enum { PREPARE_ALLOW_NON_CALLBACKS = 1 };

typedef int (*Callback)(int fd, int events, void *data);
typedef struct {
  int fd, ident, events, seq;
  Callback cb;
  void *data;
} Req;
typedef struct {
  Req req;
  int events;  // what happened
} Resp;

typedef struct Looper {
  pthread_mutex_t mu;
  pthread_cond_t cv;
  int refs, allow_non_callbacks;
  int wake_rd, wake_wr;
  Req *reqs;
  int nreq, cap, next_seq;
  Resp *resp;  // the owner thread's, from its last poll
  int nresp, iresp, resp_cap;
  // Backed by a Java Looper: watcher thread and its control pipe, whether a pump message is posted.
  jobject jlooper;
  pthread_t watcher;
  int ctl_rd, ctl_wr, pump_pending, closing;
  int token;  // of the pump message posted last: one a test reset dropped is posted again
  int woken;  // ALooper_wake was called (the wake pipe also carries changes to the fd set)
} Looper;

static JavaVM *vm;
static jclass helper;
static jmethodID m_my_looper, m_post;
static pthread_key_t tls;
static pthread_once_t tls_once = PTHREAD_ONCE_INIT;

static void looper_release(Looper *l);
static void tls_destructor(void *p) { looper_release(p); }
static void make_tls(void) { pthread_key_create(&tls, tls_destructor); }

static int make_pipe(int *rd, int *wr) {
  int fds[2];
  if (pipe(fds)) return -1;
  for (int i = 0; i < 2; i++) {
    fcntl(fds[i], F_SETFL, fcntl(fds[i], F_GETFL) | O_NONBLOCK);
    fcntl(fds[i], F_SETFD, FD_CLOEXEC);
  }
  *rd = fds[0];
  *wr = fds[1];
  return 0;
}
static void drain(int fd) {
  char buf[64];
  while (read(fd, buf, sizeof buf) > 0) {
  }
}
static void poke(int fd) {
  char c = 1;
  ssize_t r = write(fd, &c, 1);  // a full pipe already wakes the reader
  (void)r;
}

static short to_poll(int events) { return (short)((events & EV_INPUT ? POLLIN : 0) | (events & EV_OUTPUT ? POLLOUT : 0)); }
static int from_poll(short re) {
  return (re & POLLIN ? EV_INPUT : 0) | (re & POLLOUT ? EV_OUTPUT : 0) | (re & POLLERR ? EV_ERROR : 0) |
         (re & POLLHUP ? EV_HANGUP : 0) | (re & POLLNVAL ? EV_INVALID : 0);
}

static JNIEnv *env_for_thread(int *attached) {
  JNIEnv *env = NULL;
  *attached = 0;
  if (!vm) return NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
  return NULL;
}

// ---------------------------------------------------------------- the watcher of a Java-backed looper

static void *watch(void *arg) {
  Looper *l = arg;
  JNIEnv *env = NULL;
  JavaVMAttachArgs a = {JNI_VERSION_1_6, "apksim-alooper", NULL};
  if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, &a) != JNI_OK) {
    fprintf(stderr, "[apksim] ALooper: could not attach its watcher thread; fd callbacks on a Java looper will not run\n");
    return NULL;
  }
  struct pollfd *fds = NULL;
  int cap = 0;
  for (;;) {
    pthread_mutex_lock(&l->mu);
    int repost = 0;
    while (l->pump_pending && !l->closing) {
      // A posted message can be dropped (Robolectric clears loopers between tests, a looper quits): after a
      // second without its pump, post another. A late one only releases its reference.
      struct timespec d;
      clock_gettime(CLOCK_REALTIME, &d);
      d.tv_sec += 1;
      if (pthread_cond_timedwait(&l->cv, &l->mu, &d) == ETIMEDOUT && l->pump_pending) {
        repost = 1;
        break;
      }
    }
    if (l->closing) {
      pthread_mutex_unlock(&l->mu);
      break;
    }
    if (repost) {
      pthread_mutex_unlock(&l->mu);
      goto post;
    }
    if (cap < l->nreq + 1) {
      cap = l->nreq + 8;
      fds = realloc(fds, cap * sizeof *fds);
    }
    int n = 0;
    fds[n++] = (struct pollfd){l->ctl_rd, POLLIN, 0};
    for (int i = 0; i < l->nreq; i++) fds[n++] = (struct pollfd){l->reqs[i].fd, to_poll(l->reqs[i].events), 0};
    pthread_mutex_unlock(&l->mu);
    int r = poll(fds, (nfds_t)n, -1);
    if (r < 0) {
      if (errno == EINTR) continue;
      usleep(10000);
      continue;
    }
    if (fds[0].revents) drain(l->ctl_rd);
    int ready = 0;
    for (int i = 1; i < n; i++) ready |= fds[i].revents != 0;
    if (!ready) continue;
  post:
    pthread_mutex_lock(&l->mu);
    if (l->closing) {
      pthread_mutex_unlock(&l->mu);
      break;
    }
    l->pump_pending = 1;
    int token = ++l->token;
    l->refs++;  // the message's, released when it runs
    pthread_mutex_unlock(&l->mu);
    jvalue args[3] = {{.l = l->jlooper}, {.j = (jlong)(intptr_t)l}, {.i = token}};
    jboolean posted = (*env)->CallStaticBooleanMethodA(env, helper, m_post, args);
    if ((*env)->ExceptionCheck(env)) {
      (*env)->ExceptionDescribe(env);
      (*env)->ExceptionClear(env);
      posted = JNI_FALSE;
    }
    if (!posted) {  // the looper has quit, or the post threw: try again later
      pthread_mutex_lock(&l->mu);
      if (l->token == token) l->pump_pending = 0;
      l->refs--;
      pthread_mutex_unlock(&l->mu);
      usleep(100000);
    }
  }
  free(fds);
  (*vm)->DetachCurrentThread(vm);
  return NULL;
}

// ---------------------------------------------------------------- looper objects

static Looper *looper_new(int allow, jobject jlooper, JNIEnv *env) {
  Looper *l = calloc(1, sizeof *l);
  if (!l) return NULL;
  pthread_mutex_init(&l->mu, NULL);
  pthread_cond_init(&l->cv, NULL);
  l->refs = 1;  // the thread's own
  l->allow_non_callbacks = allow;
  l->ctl_rd = l->ctl_wr = -1;
  if (make_pipe(&l->wake_rd, &l->wake_wr)) {
    free(l);
    return NULL;
  }
  if (jlooper) {
    l->jlooper = (*env)->NewGlobalRef(env, jlooper);
    if (make_pipe(&l->ctl_rd, &l->ctl_wr) || pthread_create(&l->watcher, NULL, watch, l)) {
      fprintf(stderr, "[apksim] ALooper: no watcher thread; fd callbacks on this Java looper will not run\n");
      (*env)->DeleteGlobalRef(env, l->jlooper);
      l->jlooper = NULL;
    }
  }
  return l;
}

static void looper_acquire(Looper *l) {
  pthread_mutex_lock(&l->mu);
  l->refs++;
  pthread_mutex_unlock(&l->mu);
}

static void looper_release(Looper *l) {
  if (!l) return;
  pthread_mutex_lock(&l->mu);
  int left = --l->refs;
  if (left == 0 && l->jlooper) {
    l->closing = 1;
    pthread_cond_broadcast(&l->cv);
    poke(l->ctl_wr);
  }
  pthread_mutex_unlock(&l->mu);
  if (left) return;
  if (l->jlooper) {
    pthread_join(l->watcher, NULL);
    int attached = 0;
    JNIEnv *env = env_for_thread(&attached);
    if (env) (*env)->DeleteGlobalRef(env, l->jlooper);  // else kept: a few bytes, never a crash
    close(l->ctl_rd);
    close(l->ctl_wr);
  }
  close(l->wake_rd);
  close(l->wake_wr);
  free(l->reqs);
  free(l->resp);
  pthread_mutex_destroy(&l->mu);
  pthread_cond_destroy(&l->cv);
  free(l);
}

static void changed(Looper *l) {  // under l->mu: the watcher polls the new set
  if (l->jlooper) poke(l->ctl_wr);
}
// A pollOnce under way on another thread polls the new set, as epoll would watch it at once; the owner's own changes
// (a callback adding or removing fds) need no wake.
static void set_changed(Looper *l) {
  if (pthread_getspecific(tls) != l) poke(l->wake_wr);
}

static int looper_add(Looper *l, int fd, int ident, int events, Callback cb, void *data) {
  if (!cb) {
    if (!l->allow_non_callbacks || ident < 0) return -1;
  } else {
    ident = POLL_CALLBACK;
  }
  if (fd < 0) return -1;
  pthread_mutex_lock(&l->mu);
  int i = 0;
  while (i < l->nreq && l->reqs[i].fd != fd) i++;
  if (i == l->nreq) {
    if (l->nreq == l->cap) {
      int cap = l->cap ? l->cap * 2 : 8;
      Req *r = realloc(l->reqs, cap * sizeof *r);
      if (!r) {
        pthread_mutex_unlock(&l->mu);
        return -1;
      }
      l->reqs = r;
      l->cap = cap;
    }
    l->nreq++;
  }
  l->reqs[i] = (Req){fd, ident, events, ++l->next_seq, cb, data};
  changed(l);
  pthread_mutex_unlock(&l->mu);
  set_changed(l);
  return 1;
}

// seq < 0: whatever is registered for fd. Else only that registration (a callback returning 0 removes itself, not
// a newer registration of the same fd made meanwhile).
static int looper_remove(Looper *l, int fd, int seq) {
  pthread_mutex_lock(&l->mu);
  int i = 0;
  while (i < l->nreq && l->reqs[i].fd != fd) i++;
  int found = i < l->nreq && (seq < 0 || l->reqs[i].seq == seq);
  if (found) {
    l->reqs[i] = l->reqs[--l->nreq];
    changed(l);
  }
  pthread_mutex_unlock(&l->mu);
  if (found) set_changed(l);
  return found;
}

static int64_t now_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

// One poll and the callbacks it calls for: Android's Looper::pollInner. Only the owner thread calls it.
static int poll_inner(Looper *l, int timeout_ms) {
  l->nresp = l->iresp = 0;
  int64_t end = timeout_ms > 0 ? now_ms() + timeout_ms : 0;
  int result;
  for (;;) {
    pthread_mutex_lock(&l->mu);
    int n = l->nreq + 1;
    struct pollfd *fds = malloc(n * sizeof *fds);
    Req *snap = malloc(n * sizeof *snap);
    if (!fds || !snap) {
      pthread_mutex_unlock(&l->mu);
      free(fds);
      free(snap);
      return POLL_ERROR;
    }
    fds[0] = (struct pollfd){l->wake_rd, POLLIN, 0};
    for (int i = 0; i < l->nreq; i++) {
      snap[i + 1] = l->reqs[i];
      fds[i + 1] = (struct pollfd){l->reqs[i].fd, to_poll(l->reqs[i].events), 0};
    }
    pthread_mutex_unlock(&l->mu);
    int r = poll(fds, (nfds_t)n, timeout_ms);
    int only_set_change = 0;
    result = POLL_WAKE;
    if (r < 0) {
      result = errno == EINTR ? POLL_WAKE : POLL_ERROR;
    } else if (r == 0) {
      result = POLL_TIMEOUT;
    } else {
      if (fds[0].revents) {
        drain(l->wake_rd);
        pthread_mutex_lock(&l->mu);
        only_set_change = !l->woken && r == 1;
        l->woken = 0;
        pthread_mutex_unlock(&l->mu);
      }
      for (int i = 1; i < n; i++) {
        if (!fds[i].revents) continue;
        if (l->nresp == l->resp_cap) {
          int cap = l->resp_cap ? l->resp_cap * 2 : 8;
          Resp *p = realloc(l->resp, cap * sizeof *p);
          if (!p) break;
          l->resp = p;
          l->resp_cap = cap;
        }
        l->resp[l->nresp++] = (Resp){snap[i], from_poll(fds[i].revents)};
      }
    }
    free(fds);
    free(snap);
    if (!only_set_change) break;
    // Only the fd set changed: poll the new one for the time left.
    if (timeout_ms > 0) {
      int64_t left = end - now_ms();
      if (left <= 0) {
        result = POLL_TIMEOUT;
        break;
      }
      timeout_ms = (int)left;
    } else if (timeout_ms == 0) {
      result = POLL_TIMEOUT;
      break;
    }
  }
  for (int i = 0; i < l->nresp; i++) {
    Resp *p = &l->resp[i];
    if (p->req.ident != POLL_CALLBACK) continue;
    if (!p->req.cb(p->req.fd, p->events, p->req.data)) looper_remove(l, p->req.fd, p->req.seq);
    result = POLL_CALLBACK;
  }
  return result;
}

// Android's Looper::pollOnce.
static int poll_once(Looper *l, int timeout_ms, int *out_fd, int *out_events, void **out_data) {
  int result = 0;
  for (;;) {
    while (l->iresp < l->nresp) {
      Resp *p = &l->resp[l->iresp++];
      if (p->req.ident >= 0) {
        if (out_fd) *out_fd = p->req.fd;
        if (out_events) *out_events = p->events;
        if (out_data) *out_data = p->req.data;
        return p->req.ident;
      }
    }
    if (result) {
      if (out_fd) *out_fd = 0;
      if (out_events) *out_events = 0;
      if (out_data) *out_data = NULL;
      return result;
    }
    result = poll_inner(l, timeout_ms);
  }
}

// ---------------------------------------------------------------- the NDK functions

static Looper *for_thread(int create_native, int opts) {
  pthread_once(&tls_once, make_tls);
  Looper *l = pthread_getspecific(tls);
  if (l) return l;
  // A Java thread with a Looper has a native one already, on a device.
  int attached = 0;
  JNIEnv *env = env_for_thread(&attached);
  jobject jl = NULL;
  if (env && helper) {
    jl = (*env)->CallStaticObjectMethodA(env, helper, m_my_looper, NULL);
    if ((*env)->ExceptionCheck(env)) {
      (*env)->ExceptionClear(env);
      jl = NULL;
    }
  }
  if (!jl && !create_native) return NULL;
  l = looper_new(jl ? 0 : (opts & PREPARE_ALLOW_NON_CALLBACKS), jl, env);
  if (jl) (*env)->DeleteLocalRef(env, jl);
  if (l) pthread_setspecific(tls, l);
  return l;
}

static void *a_for_thread(void) { return for_thread(0, 0); }
static void *a_prepare(int opts) { return for_thread(1, opts); }
static void a_acquire(void *l) {
  if (l) looper_acquire(l);
}
static void a_release(void *l) { looper_release(l); }
static int a_poll_once(int timeout, int *fd, int *events, void **data) {
  Looper *l = for_thread(0, 0);
  if (!l) return POLL_ERROR;
  return poll_once(l, timeout, fd, events, data);
}
static int a_poll_all(int timeout, int *fd, int *events, void **data) {
  Looper *l = for_thread(0, 0);
  if (!l) return POLL_ERROR;
  if (timeout <= 0) {
    int r;
    do r = poll_once(l, timeout, fd, events, data);
    while (r == POLL_CALLBACK);
    return r;
  }
  int64_t end = now_ms() + timeout;
  for (;;) {
    int r = poll_once(l, timeout, fd, events, data);
    if (r != POLL_CALLBACK) return r;
    int64_t left = end - now_ms();
    if (left <= 0) return POLL_TIMEOUT;
    timeout = (int)left;
  }
}
static void a_wake(void *p) {
  Looper *l = p;
  if (!l) return;
  pthread_mutex_lock(&l->mu);
  l->woken = 1;
  pthread_mutex_unlock(&l->mu);
  poke(l->wake_wr);
}
static int a_add_fd(void *l, int fd, int ident, int events, Callback cb, void *data) {
  return l ? looper_add(l, fd, ident, events, cb, data) : -1;
}
static int a_remove_fd(void *l, int fd) { return l ? looper_remove(l, fd, -1) : -1; }

// From NativeLoopers' message on the Java looper's own thread: run the callbacks of the fds that are ready.
static void JNICALL pump(JNIEnv *env, jclass c, jlong ptr, jint token) {
  Looper *l = (Looper *)(intptr_t)ptr;
  pthread_mutex_lock(&l->mu);
  int current = l->pump_pending && l->token == token && !l->closing;
  pthread_mutex_unlock(&l->mu);
  if (current) {
    poll_inner(l, 0);
    l->nresp = l->iresp = 0;  // no idents on a Java looper: nobody would ask for them
    pthread_mutex_lock(&l->mu);
    if (l->token == token) l->pump_pending = 0;
    pthread_cond_broadcast(&l->cv);
    pthread_mutex_unlock(&l->mu);
  }
  looper_release(l);
}

void alooper_init(void *jenv) {
  JNIEnv *env = jenv;
  if (helper) return;
  (*env)->GetJavaVM(env, &vm);
  jclass c = (*env)->FindClass(env, "dev/apksim/NativeLoopers");
  if (!c) {
    (*env)->ExceptionClear(env);
    fprintf(stderr, "[apksim] no dev.apksim.NativeLoopers: ALooper works only on native threads\n");
    return;
  }
  m_my_looper = (*env)->GetStaticMethodID(env, c, "myLooper", "()Ljava/lang/Object;");
  m_post = (*env)->GetStaticMethodID(env, c, "post", "(Ljava/lang/Object;JI)Z");
  JNINativeMethod natives[] = {{"pump", "(JI)V", (void *)pump}};
  if ((*env)->ExceptionCheck(env) || (*env)->RegisterNatives(env, c, natives, 1)) {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return;
  }
  helper = (*env)->NewGlobalRef(env, c);
}

void *alooper_lookup(const char *name) {
  if (strncmp(name, "ALooper_", 8)) return NULL;
  static const struct {
    const char *name;
    void *fn;
  } table[] = {
      {"forThread", (void *)a_for_thread}, {"prepare", (void *)a_prepare},     {"acquire", (void *)a_acquire},
      {"release", (void *)a_release},      {"pollOnce", (void *)a_poll_once},  {"pollAll", (void *)a_poll_all},
      {"wake", (void *)a_wake},            {"addFd", (void *)a_add_fd},        {"removeFd", (void *)a_remove_fd},
  };
  for (size_t i = 0; i < sizeof table / sizeof table[0]; i++) {
    if (!strcmp(name + 8, table[i].name)) return table[i].fn;
  }
  return NULL;
}
