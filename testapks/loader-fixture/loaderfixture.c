// A native library whose checks exercise what apksim's ELF loader and bionic shims must get right for Android code,
// and which the smoke tests and real apps reach only by chance: the stack protector's guard read through the thread
// pointer (rewritten on arm64), thread-local variables (TLSDESC on arm64, __tls_get_addr on x86_64) on several
// threads with registers live across the access, barriers, mutexes and condition variables placed where bionic
// allows and glibc does not (int-aligned, 4 mod 8: boxed on arm64 Linux), thread attributes (boxed on arm64 Linux;
// pthread_getattr_np, as engines read their stack bounds) and setjmp/longjmp into bionic's jmp_buf. Each check
// returns "ok" or what went wrong. A device's bionic passes them all, so they test the device's behaviour, not
// apksim's. The test app's NativeLoaderTest calls them through LoaderFixture. Built by build.sh into the test app's
// jniLibs.
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <setjmp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static char err[256];
#define FAIL(...) do { snprintf(err, sizeof err, __VA_ARGS__); return err; } while (0)

// Workers that meet at a barrier wait at this gate first: start_threads opens it once every one of them exists, or
// closes it if one could not be created, so the others return instead of waiting for a party that never comes.
static pthread_mutex_t gate_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t gate_cond = PTHREAD_COND_INITIALIZER;
static int gate;  // 0 shut, 1 open, -1 closed for good

static void gate_set(int v) {
  pthread_mutex_lock(&gate_lock);
  gate = v;
  pthread_cond_broadcast(&gate_cond);
  pthread_mutex_unlock(&gate_lock);
}

static int gate_wait(void) {
  pthread_mutex_lock(&gate_lock);
  while (!gate) pthread_cond_wait(&gate_cond, &gate_lock);
  int g = gate;
  pthread_mutex_unlock(&gate_lock);
  return g;
}

// Starts n threads, the i-th given arg + i * stride. If one cannot be created, the gate closes, the ones started are
// joined (nothing is left running on the caller's stack), and it returns -1.
static int start_threads(pthread_t *t, int n, void *(*fn)(void *), void *arg, size_t stride) {
  gate_set(0);
  for (int i = 0; i < n; i++) {
    if (pthread_create(&t[i], NULL, fn, (char *)arg + i * stride)) {
      gate_set(-1);
      for (int j = 0; j < i; j++) pthread_join(t[j], NULL);
      return -1;
    }
  }
  gate_set(1);
  return 0;
}

// ---------------------------------------------------------------- stack guard
// The guard the stack protector compares: [thread pointer + 0x28] on both architectures.
static uintptr_t guard_word(void) {
#if defined(__aarch64__)
  return ((volatile uintptr_t *)__builtin_thread_pointer())[5];
#else
  uintptr_t g;
  __asm__ volatile("mov %%fs:0x28, %0" : "=r"(g));
  return g;
#endif
}

// Built with -fstack-protector-all: every call checks the guard on the way out, and aborts if it changed.
__attribute__((noinline)) static unsigned protected_sum(unsigned n) {
  volatile unsigned char buf[64];
  for (unsigned i = 0; i < sizeof buf; i++) buf[i] = (unsigned char)(i * n);
  unsigned s = 0;
  for (unsigned i = 0; i < sizeof buf; i++) s += buf[i];
  return s;
}

static unsigned guard_rounds(void) {
  unsigned total = 0;
  for (unsigned i = 0; i < 20000; i++) total += protected_sum(i);
  return total;
}

typedef struct { unsigned total; int moved; } GuardRun;

static void *guard_thread(void *p) {
  GuardRun *r = p;
  uintptr_t g = guard_word();
  char scratch[128];
  for (int i = 0; i < 200; i++) {
    r->total = guard_rounds();
    snprintf(scratch, sizeof scratch, "%d %f", i, i * 0.5);  // the host libc's own thread state at work meanwhile
    if (guard_word() != g) r->moved = 1;
  }
  return NULL;
}

static const char *check_stack_guard(void) {
  unsigned want = guard_rounds();
  enum { N = 4 };
  pthread_t t[N];
  GuardRun runs[N];
  memset(runs, 0, sizeof runs);
  if (start_threads(t, N, guard_thread, runs, sizeof runs[0])) FAIL("pthread_create failed");
  for (int i = 0; i < N; i++) pthread_join(t[i], NULL);
  for (int i = 0; i < N; i++) {
    if (runs[i].moved) FAIL("thread %d: the stack guard changed under it", i);
    if (runs[i].total != want) FAIL("thread %d: sum %u, want %u", i, runs[i].total, want);
  }
  return "ok";
}

// ---------------------------------------------------------------- thread-local variables
// Exported, so preemptible: general-dynamic access (a TLSDESC relocation against the symbol on arm64).
__attribute__((visibility("default"))) __thread long tls_shared = 42;
static __thread long tls_zero[16];                  // .tbss, local-dynamic
static __thread char tls_text[8] = "fixture";       // .tdata

__attribute__((always_inline)) static inline double step(double acc, long v) { return acc * 1.0000001 + (double)v * 1e-9; }

// acc, k and m stay live across the thread-local access: the access must preserve every register but x0 (TLSDESC on
// arm64 promises all of them, vector registers included, so the compiler keeps acc in one).
__attribute__((noinline)) static double tls_step(double acc, long k, long m) {
  tls_shared += 1;
  return step(acc, tls_zero[k & 15] + tls_shared) + (double)(k ^ m) * 1e-12;
}

__attribute__((noinline)) static double plain_steps(double acc, long id, long n) {
  long shared = id;
  for (long k = 0; k < n; k++) {
    shared += 1;
    acc = step(acc, id * 100 + (k & 15) + shared) + (double)(k ^ (k * 3)) * 1e-12;
  }
  return acc;
}

typedef struct { long id; pthread_barrier_t *barrier; double acc; uintptr_t addr; int bad, serial; } TlsRun;
static _Atomic int arrived;

static void *tls_thread(void *p) {
  TlsRun *r = p;
  if (gate_wait() < 0) return NULL;
  if (tls_shared != 42) r->bad = 1;
  if (strcmp(tls_text, "fixture")) r->bad = 2;
  for (int i = 0; i < 16; i++) if (tls_zero[i]) r->bad = 3;
  tls_shared = r->id;
  for (int i = 0; i < 16; i++) tls_zero[i] = r->id * 100 + i;
  r->addr = (uintptr_t)&tls_shared;
  arrived++;
  // Every thread has written its own values before any reads them back.
  r->serial = pthread_barrier_wait(r->barrier) == PTHREAD_BARRIER_SERIAL_THREAD;
  if (arrived != 4) r->bad = 6;
  double acc = 1.0 + (double)r->id;
  for (long k = 0; k < 100000; k++) acc = tls_step(acc, k, k * 3);
  r->acc = acc;
  if (tls_shared != r->id + 100000) r->bad = 4;
  for (int i = 0; i < 16; i++) if (tls_zero[i] != r->id * 100 + i) r->bad = 5;
  return NULL;
}

static const char *check_tls(void) {
  enum { N = 4 };
  pthread_barrier_t barrier;
  if (pthread_barrier_init(&barrier, NULL, N)) FAIL("pthread_barrier_init failed");
  arrived = 0;
  pthread_t t[N];
  TlsRun runs[N];
  memset(runs, 0, sizeof runs);
  for (int i = 0; i < N; i++) {
    runs[i].id = 1000 * (i + 1);
    runs[i].barrier = &barrier;
  }
  if (start_threads(t, N, tls_thread, runs, sizeof runs[0])) {
    pthread_barrier_destroy(&barrier);
    FAIL("pthread_create failed");
  }
  for (int i = 0; i < N; i++) pthread_join(t[i], NULL);
  pthread_barrier_destroy(&barrier);
  static const char *what[] = {"", "tls_shared did not start at 42", "tls_text did not start as \"fixture\"",
                               "tls_zero did not start zeroed", "tls_shared lost updates or saw another thread's",
                               "tls_zero saw another thread's values", "pthread_barrier_wait let a thread through early"};
  int serial = 0;
  for (int i = 0; i < N; i++) {
    if (runs[i].bad) FAIL("thread %d: %s", i, what[runs[i].bad]);
    serial += runs[i].serial;
    double want = plain_steps(1.0 + (double)runs[i].id, runs[i].id, 100000);
    if (runs[i].acc != want) FAIL("thread %d: a register live across the access changed (%.17g, want %.17g)", i, runs[i].acc, want);
    for (int j = 0; j < i; j++)
      if (runs[i].addr == runs[j].addr) FAIL("threads %d and %d share tls_shared's address", j, i);
  }
  if (serial != 1) FAIL("%d threads got PTHREAD_BARRIER_SERIAL_THREAD, want 1", serial);
  if (tls_shared != 42) FAIL("this thread's tls_shared is %ld, not 42", tls_shared);
  return "ok";
}

// ---------------------------------------------------------------- mutexes and condition variables, 4 mod 8
// Bionic's pthread_mutex_t and pthread_cond_t need only int alignment; glibc's use 64-bit atomics. Zero bytes are
// bionic's static initialisers, so the statically initialised ones need no init call.
static _Alignas(8) unsigned char static_sync[4 + sizeof(pthread_mutex_t) + sizeof(pthread_cond_t) + 8];
#define STATIC_MUTEX ((pthread_mutex_t *)(static_sync + 4))
#define STATIC_COND ((pthread_cond_t *)(static_sync + 4 + sizeof(pthread_mutex_t)))

static long counter;

static void *count_thread(void *p) {
  pthread_mutex_t *m = p;
  for (int i = 0; i < 50000; i++) {
    pthread_mutex_lock(m);
    counter++;
    pthread_mutex_unlock(m);
  }
  return NULL;
}

static void *trylock_thread(void *p) {
  int r = pthread_mutex_trylock(p);
  if (r == 0) pthread_mutex_unlock(p);
  return (void *)(intptr_t)r;
}

static const char *check_mutex(void) {
  if ((uintptr_t)STATIC_MUTEX % 8 != 4) FAIL("the fixture's mutex is not at 4 mod 8");
  enum { N = 4 };
  pthread_t t[N];
  counter = 0;
  if (start_threads(t, N, count_thread, STATIC_MUTEX, 0)) FAIL("pthread_create failed");
  for (int i = 0; i < N; i++) pthread_join(t[i], NULL);
  if (counter != N * 50000L) FAIL("statically initialised mutex: counter %ld, want %ld", counter, N * 50000L);

  // A recursive mutex from an attribute, also at 4 mod 8.
  _Alignas(8) unsigned char buf[4 + sizeof(pthread_mutex_t)];
  pthread_mutex_t *m = (pthread_mutex_t *)(buf + 4);
  pthread_mutexattr_t a;
  pthread_mutexattr_init(&a);
  pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
  if (pthread_mutex_init(m, &a)) FAIL("pthread_mutex_init failed");
  pthread_mutexattr_destroy(&a);
  if (pthread_mutex_lock(m) || pthread_mutex_lock(m)) FAIL("a recursive mutex could not be locked twice");
  void *r;
  pthread_t other;
  if (pthread_create(&other, NULL, trylock_thread, m)) FAIL("pthread_create failed");
  pthread_join(other, &r);
  if ((intptr_t)r != EBUSY) FAIL("another thread's trylock of a held mutex returned %d, not EBUSY", (int)(intptr_t)r);
  pthread_mutex_unlock(m);
  pthread_mutex_unlock(m);
  if (pthread_create(&other, NULL, trylock_thread, m)) FAIL("pthread_create failed");
  pthread_join(other, &r);
  if (r) FAIL("trylock of a released mutex returned %d", (int)(intptr_t)r);
  counter = 0;
  if (start_threads(t, N, count_thread, m, 0)) FAIL("pthread_create failed");
  for (int i = 0; i < N; i++) pthread_join(t[i], NULL);
  if (counter != N * 50000L) FAIL("recursive mutex: counter %ld, want %ld", counter, N * 50000L);
  pthread_mutex_destroy(m);
  return "ok";
}

static int turn;

static void *pong_thread(void *p) {
  (void)p;
  for (int i = 0; i < 2000; i++) {
    pthread_mutex_lock(STATIC_MUTEX);
    while (turn != 1) pthread_cond_wait(STATIC_COND, STATIC_MUTEX);
    turn = 0;
    pthread_cond_broadcast(STATIC_COND);
    pthread_mutex_unlock(STATIC_MUTEX);
  }
  return NULL;
}

static double since(const struct timespec *t0) {
  struct timespec t1;
  clock_gettime(CLOCK_MONOTONIC, &t1);
  return (double)(t1.tv_sec - t0->tv_sec) + (double)(t1.tv_nsec - t0->tv_nsec) / 1e9;
}

static void add_ms(struct timespec *t, long ms) {
  t->tv_nsec += ms * 1000000L;
  t->tv_sec += t->tv_nsec / 1000000000L;
  t->tv_nsec %= 1000000000L;
}

// The monotonic static initialiser, 8-aligned and at 4 mod 8: timed waits measure their deadline on the monotonic
// clock without any init call.
static _Alignas(8) pthread_cond_t mono_aligned = PTHREAD_COND_INITIALIZER_MONOTONIC_NP;
static struct { int pad; pthread_cond_t c; } __attribute__((aligned(8))) mono_odd = {0, PTHREAD_COND_INITIALIZER_MONOTONIC_NP};

static const char *monotonic_wait(pthread_cond_t *c, const char *which) {
  struct timespec t0, deadline;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  deadline = t0;
  add_ms(&deadline, 100);
  pthread_mutex_lock(STATIC_MUTEX);
  int r = pthread_cond_timedwait(c, STATIC_MUTEX, &deadline);
  pthread_mutex_unlock(STATIC_MUTEX);
  double waited = since(&t0);
  if (r != ETIMEDOUT) FAIL("%s monotonic static initialiser: timed wait returned %d, not ETIMEDOUT", which, r);
  if (waited < 0.08 || waited > 5) FAIL("%s monotonic static initialiser: timed wait took %.3f s for 0.1 s", which, waited);
  pthread_cond_signal(c);  // and the other calls take it too
  pthread_cond_broadcast(c);
  return NULL;
}

static const char *check_cond(void) {
  if ((uintptr_t)STATIC_COND % 8 != 4) FAIL("the fixture's condition variable is not at 4 mod 8");
  // Ping-pong: 2,000 hand-overs each way on a statically initialised condition variable.
  turn = 0;
  pthread_t t;
  if (pthread_create(&t, NULL, pong_thread, NULL)) FAIL("pthread_create failed");
  for (int i = 0; i < 2000; i++) {
    pthread_mutex_lock(STATIC_MUTEX);
    while (turn != 0) pthread_cond_wait(STATIC_COND, STATIC_MUTEX);
    turn = 1;
    pthread_cond_broadcast(STATIC_COND);
    pthread_mutex_unlock(STATIC_MUTEX);
  }
  pthread_join(t, NULL);

  // Timed waits: a wall-clock deadline, then one on the monotonic clock set with pthread_condattr_setclock.
  struct timespec t0, deadline;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  clock_gettime(CLOCK_REALTIME, &deadline);
  add_ms(&deadline, 100);
  pthread_mutex_lock(STATIC_MUTEX);
  int r = pthread_cond_timedwait(STATIC_COND, STATIC_MUTEX, &deadline);
  pthread_mutex_unlock(STATIC_MUTEX);
  double waited = since(&t0);
  if (r != ETIMEDOUT) FAIL("wall-clock timed wait returned %d, not ETIMEDOUT", r);
  if (waited < 0.08 || waited > 5) FAIL("wall-clock timed wait took %.3f s for 0.1 s", waited);

  _Alignas(8) unsigned char buf[4 + sizeof(pthread_cond_t)];
  pthread_cond_t *c = (pthread_cond_t *)(buf + 4);
  pthread_condattr_t ca;
  pthread_condattr_init(&ca);
  pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
  if (pthread_cond_init(c, &ca)) FAIL("pthread_cond_init failed");
  pthread_condattr_destroy(&ca);
  clock_gettime(CLOCK_MONOTONIC, &t0);
  deadline = t0;
  add_ms(&deadline, 100);
  pthread_mutex_lock(STATIC_MUTEX);
  r = pthread_cond_timedwait(c, STATIC_MUTEX, &deadline);
  pthread_mutex_unlock(STATIC_MUTEX);
  waited = since(&t0);
  pthread_cond_destroy(c);
  if (r != ETIMEDOUT) FAIL("monotonic timed wait returned %d, not ETIMEDOUT", r);
  if (waited < 0.08 || waited > 5) FAIL("monotonic timed wait took %.3f s for 0.1 s", waited);

  if ((uintptr_t)&mono_odd.c % 8 != 4) FAIL("the fixture's odd monotonic condition variable is not at 4 mod 8");
  const char *bad = monotonic_wait(&mono_aligned, "an 8-aligned");
  if (!bad) bad = monotonic_wait(&mono_odd.c, "a 4 mod 8");
  return bad ? bad : "ok";
}

// ---------------------------------------------------------------- thread attributes
typedef struct { size_t stack_size; int inside; int detached; uintptr_t local; } AttrSeen;

static void *attr_thread(void *p) {
  AttrSeen *s = p;
  pthread_attr_t a;
  if (pthread_getattr_np(pthread_self(), &a)) return (void *)1;
  void *base;
  size_t size;
  pthread_attr_getstack(&a, &base, &size);
  pthread_attr_getstacksize(&a, &s->stack_size);
  pthread_attr_getdetachstate(&a, &s->detached);
  pthread_attr_destroy(&a);
  char here;
  s->local = (uintptr_t)&here;
  s->inside = (uintptr_t)&here >= (uintptr_t)base && (uintptr_t)&here < (uintptr_t)base + size;
  return NULL;
}

// A detached thread reports itself detached; it hands its answer over under the gate's lock, since it cannot be joined.
static int detached_seen = -1;

static void *detached_thread(void *p) {
  (void)p;
  if (gate_wait() < 0) return NULL;
  pthread_attr_t a;
  int state = -2;
  if (!pthread_getattr_np(pthread_self(), &a)) {
    pthread_attr_getdetachstate(&a, &state);
    pthread_attr_destroy(&a);
  }
  pthread_mutex_lock(&gate_lock);
  detached_seen = state;
  pthread_cond_broadcast(&gate_cond);
  pthread_mutex_unlock(&gate_lock);
  return NULL;
}

static int detached_state_seen(void) {
  pthread_mutex_lock(&gate_lock);
  while (detached_seen == -1) pthread_cond_wait(&gate_cond, &gate_lock);
  int state = detached_seen;
  pthread_mutex_unlock(&gate_lock);
  return state;
}

static const char *check_detached_attr(void) {
  pthread_attr_t a;
  pthread_attr_init(&a);
  pthread_attr_setdetachstate(&a, PTHREAD_CREATE_DETACHED);
  pthread_mutex_lock(&gate_lock);
  detached_seen = -1;
  gate = 1;
  pthread_mutex_unlock(&gate_lock);
  pthread_t t;
  int r = pthread_create(&t, &a, detached_thread, NULL);
  pthread_attr_destroy(&a);
  if (r) FAIL("pthread_create of a detached thread failed");
  int state = detached_state_seen();
  if (state == -2) FAIL("pthread_getattr_np failed in a detached thread");
  if (state != PTHREAD_CREATE_DETACHED) FAIL("a detached thread's pthread_getattr_np reports detach state %d", state);

  // Created joinable and detached at once, before it has run any code of its own.
  for (int i = 0; i < 50; i++) {
    pthread_mutex_lock(&gate_lock);
    detached_seen = -1;
    gate = 0;
    pthread_mutex_unlock(&gate_lock);
    if (pthread_create(&t, NULL, detached_thread, NULL)) FAIL("pthread_create failed");
    if (pthread_detach(t)) FAIL("pthread_detach failed");
    gate_set(1);
    state = detached_state_seen();
    if (state != PTHREAD_CREATE_DETACHED)
      FAIL("a thread detached right after pthread_create reports detach state %d (try %d)", state, i);
  }
  return "ok";
}

static const char *check_thread_attr(void) {
  pthread_attr_t a;
  pthread_attr_init(&a);
  if (pthread_attr_setstacksize(&a, 512 * 1024)) FAIL("pthread_attr_setstacksize failed");
  pthread_attr_setdetachstate(&a, PTHREAD_CREATE_JOINABLE);
  size_t got;
  int state;
  pthread_attr_getstacksize(&a, &got);
  pthread_attr_getdetachstate(&a, &state);
  if (got != 512 * 1024) FAIL("the attribute's stack size reads back as %zu", got);
  if (state != PTHREAD_CREATE_JOINABLE) FAIL("the attribute's detach state reads back as %d", state);
  AttrSeen seen = {0};
  pthread_t t;
  if (pthread_create(&t, &a, attr_thread, &seen)) FAIL("pthread_create with an attribute failed");
  pthread_attr_destroy(&a);
  void *r;
  if (pthread_join(t, &r)) FAIL("pthread_join failed");
  if (r) FAIL("pthread_getattr_np failed in the thread");
  if (seen.stack_size < 512 * 1024) FAIL("the thread's stack is %zu bytes, asked for 512 KB", seen.stack_size);
  if (!seen.inside) FAIL("a local's address is outside the stack pthread_getattr_np reports");
  if (seen.detached != PTHREAD_CREATE_JOINABLE) FAIL("the thread reports detach state %d", seen.detached);

  // A stack the app allocates itself: the thread must run on it, not on one the host picks.
  enum { STACK = 256 * 1024 };
  void *stack;
  if (posix_memalign(&stack, 16384, STACK)) FAIL("posix_memalign failed");
  pthread_attr_init(&a);
  if (pthread_attr_setstack(&a, stack, STACK)) FAIL("pthread_attr_setstack refused a 16 KB-aligned 256 KB stack");
  void *base;
  pthread_attr_getstack(&a, &base, &got);
  if (base != stack || got != STACK) FAIL("the attribute's stack reads back as %p+%zu, set %p+%d", base, got, stack, STACK);
  memset(&seen, 0, sizeof seen);
  if (pthread_create(&t, &a, attr_thread, &seen)) FAIL("pthread_create with the app's own stack failed");
  pthread_attr_destroy(&a);
  pthread_join(t, &r);
  if (r) FAIL("pthread_getattr_np failed in a thread on the app's stack");
  if (seen.local < (uintptr_t)stack || seen.local >= (uintptr_t)stack + STACK)
    FAIL("a thread given the app's own stack did not run on it");
  if (!seen.inside) FAIL("pthread_getattr_np reports another stack than the app's");
  free(stack);
  return check_detached_attr();
}

// ---------------------------------------------------------------- barriers: reuse and teardown
enum { ROUNDS = 1000, PARTIES = 4 };
static pthread_barrier_t reused;
static _Atomic int round_arrivals[ROUNDS], serials;
static _Atomic int early;

static void *round_thread(void *p) {
  (void)p;
  if (gate_wait() < 0) return NULL;
  for (int i = 0; i < ROUNDS; i++) {
    round_arrivals[i]++;
    if (pthread_barrier_wait(&reused) == PTHREAD_BARRIER_SERIAL_THREAD) serials++;
    if (round_arrivals[i] != PARTIES) early = 1;  // released before every party of this round arrived
  }
  return NULL;
}

static void *wait_once(void *b) {
  pthread_barrier_wait(b);
  return NULL;
}

static const char *check_barrier(void) {
  // One barrier for 1,000 rounds: each releases only once all four have arrived, and names one serial thread.
  memset(round_arrivals, 0, sizeof round_arrivals);
  serials = 0;
  early = 0;
  if (pthread_barrier_init(&reused, NULL, PARTIES)) FAIL("pthread_barrier_init failed");
  pthread_t t[PARTIES];
  if (start_threads(t, PARTIES, round_thread, NULL, 0)) {
    pthread_barrier_destroy(&reused);
    FAIL("pthread_create failed");
  }
  for (int i = 0; i < PARTIES; i++) pthread_join(t[i], NULL);
  if (pthread_barrier_destroy(&reused)) FAIL("pthread_barrier_destroy failed after the rounds");
  if (early) FAIL("a reused barrier let a thread through before its round was complete");
  if (serials != ROUNDS) FAIL("%d serial threads in %d rounds", serials, ROUNDS);

  // Destroyed as soon as this thread's wait returns, while the other may still be on its way out (bionic waits for
  // it); then the memory is reused. 500 times.
  for (int i = 0; i < 500; i++) {
    _Alignas(8) unsigned char buf[sizeof(pthread_barrier_t)];
    pthread_barrier_t *b = (pthread_barrier_t *)buf;
    if (pthread_barrier_init(b, NULL, 2)) FAIL("pthread_barrier_init failed (round %d)", i);
    pthread_t other;
    if (pthread_create(&other, NULL, wait_once, b)) {
      pthread_barrier_destroy(b);
      FAIL("pthread_create failed (round %d)", i);
    }
    pthread_barrier_wait(b);
    if (pthread_barrier_destroy(b)) FAIL("pthread_barrier_destroy failed right after a round (round %d)", i);
    memset(buf, 0xa5, sizeof buf);
    pthread_join(other, NULL);
  }
  return "ok";
}

// ---------------------------------------------------------------- setjmp / longjmp
// Words right after the jmp_buf: a setjmp that writes past bionic's jmp_buf (glibc's saves the signal mask there)
// would change them.
#define CANARY 0xa5a5a5a55a5a5a5aUL
static struct { jmp_buf env; unsigned long after[8]; } jump;
static struct { sigjmp_buf env; unsigned long after[8]; } sigjump;

// Garbage in every callee-saved register right before the jump, so only longjmp's restore can bring the values the
// caller kept there back (the function's own epilogue, which would restore them, never runs).
#if defined(__aarch64__)
#define SCRAMBLE_CALLEE_SAVED() __asm__ volatile( \
    "movz x19, #0xdead\n mov x20, x19\n mov x21, x19\n mov x22, x19\n mov x23, x19\n" \
    "mov x24, x19\n mov x25, x19\n mov x26, x19\n mov x27, x19\n mov x28, x19\n" \
    "fmov d8, x19\n fmov d9, x19\n fmov d10, x19\n fmov d11, x19\n" \
    "fmov d12, x19\n fmov d13, x19\n fmov d14, x19\n fmov d15, x19\n" \
    ::: "x19", "x20", "x21", "x22", "x23", "x24", "x25", "x26", "x27", "x28", \
        "d8", "d9", "d10", "d11", "d12", "d13", "d14", "d15")
#else
#define SCRAMBLE_CALLEE_SAVED() __asm__ volatile( \
    "mov $0xdead, %%rbx\n mov %%rbx, %%r12\n mov %%rbx, %%r13\n mov %%rbx, %%r14\n mov %%rbx, %%r15\n" \
    ::: "rbx", "r12", "r13", "r14", "r15")
#endif

__attribute__((noinline)) static void jump_from(int depth) {
  volatile char pad[32];
  pad[0] = (char)depth;
  if (depth == 0) {
    SCRAMBLE_CALLEE_SAVED();
    longjmp(jump.env, 7);
  }
  jump_from(depth - 1);
  pad[1] = 0;
}

__attribute__((noinline)) static void sigjump_from(int depth) {
  volatile char pad[32];
  pad[0] = (char)depth;
  if (depth == 0) {
    SCRAMBLE_CALLEE_SAVED();
    siglongjmp(sigjump.env, 9);
  }
  sigjump_from(depth - 1);
  pad[1] = 0;
}

// Values the optimizer cannot see through, so a comparison after the jump is not folded away.
static inline long hide(long v) { __asm__ volatile("" : "+r"(v)); return v; }
static inline double hide_d(double v) {
#if defined(__aarch64__)
  __asm__ volatile("" : "+w"(v));
#else
  __asm__ volatile("" : "+x"(v));
#endif
  return v;
}
#define K(i) hide(seed * (31 + (i)) + (i))
#define D(i) hide_d((double)seed * (1.5 + (i)))
// Eight integers and eight doubles live across setjmp and the calls after it: on arm64 they fill x19-x28 and d8-d15,
// on x86_64 rbx and r12-r15 (the rest, and doubles, are on the stack there).
#define KEPT(k, d) (k##0 == K(0) && k##1 == K(1) && k##2 == K(2) && k##3 == K(3) && k##4 == K(4) && k##5 == K(5) && \
                    k##6 == K(6) && k##7 == K(7) && d##0 == D(0) && d##1 == D(1) && d##2 == D(2) && d##3 == D(3) && \
                    d##4 == D(4) && d##5 == D(5) && d##6 == D(6) && d##7 == D(7))

__attribute__((noinline)) static const char *check_setjmp_with(long seed) {
  for (int i = 0; i < 8; i++) jump.after[i] = sigjump.after[i] = CANARY;
  // Not changed after setjmp, so their values are defined after the jump.
  long k0 = K(0), k1 = K(1), k2 = K(2), k3 = K(3), k4 = K(4), k5 = K(5), k6 = K(6), k7 = K(7);
  double d0 = D(0), d1 = D(1), d2 = D(2), d3 = D(3), d4 = D(4), d5 = D(5), d6 = D(6), d7 = D(7);
  volatile int reached = 0;
  int r = setjmp(jump.env);
  if (r == 0) {
    reached = 1;
    jump_from(5);
    FAIL("longjmp returned");
  }
  if (r != 7) FAIL("setjmp returned %d after longjmp(env, 7)", r);
  if (reached != 1) FAIL("a volatile local lost its value across longjmp");
  if (!KEPT(k, d)) FAIL("callee-saved registers were not restored by longjmp");
  for (int i = 0; i < 8; i++) if (jump.after[i] != CANARY) FAIL("setjmp wrote past jmp_buf (word %d after it)", i);

  r = sigsetjmp(sigjump.env, 1);
  if (r == 0) {
    reached = 2;
    sigjump_from(5);
    FAIL("siglongjmp returned");
  }
  if (r != 9) FAIL("sigsetjmp returned %d after siglongjmp(env, 9)", r);
  if (reached != 2) FAIL("a volatile local lost its value across siglongjmp");
  if (!KEPT(k, d)) FAIL("callee-saved registers were not restored by siglongjmp");
  for (int i = 0; i < 8; i++) if (sigjump.after[i] != CANARY) FAIL("sigsetjmp wrote past sigjmp_buf (word %d after it)", i);
  return "ok";
}

static const char *check_setjmp(void) {
  for (long seed = 1; seed <= 1000; seed++) {
    const char *r = check_setjmp_with(seed);
    if (strcmp(r, "ok")) return r;
  }
  return "ok";
}

// ---------------------------------------------------------------- JNI
JNIEXPORT jstring JNICALL Java_dev_apksim_composeapp_LoaderFixture_check(JNIEnv *env, jobject self, jstring jname) {
  (void)self;
  const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
  const char *r = !strcmp(name, "stackGuard") ? check_stack_guard()
                  : !strcmp(name, "tls")      ? check_tls()
                  : !strcmp(name, "mutex")    ? check_mutex()
                  : !strcmp(name, "cond")     ? check_cond()
                  : !strcmp(name, "threadAttr") ? check_thread_attr()
                  : !strcmp(name, "barrier")  ? check_barrier()
                  : !strcmp(name, "setjmp")   ? check_setjmp()
                                              : "no such check";
  (*env)->ReleaseStringUTFChars(env, jname, name);
  return (*env)->NewStringUTF(env, r);
}
