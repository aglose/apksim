package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.UiAutomation;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The UiAutomator engine (`--uia engine`, -Dapksim.uia=engine; test runs only, off by default): what UiAutomator
 * (androidx.test.uiautomator, the test APK's own copy, unchanged) waits on, decided by the process that runs the app
 * rather than guessed from outside it. A device's UiAutomator is another process: it takes the UI for idle after
 * 500 ms without an accessibility event, paces gestures with real sleeps and polls for what it waits for. Here the
 * engine owns the main queue, the clock and the frames, so it knows.
 *
 * <ul>
 *   <li><b>Idle</b> ({@link #idle}, behind UiAutomation.waitForIdle, which every UiAutomator query calls first): the
 *       main queue is run one task at a time until nothing is left that is due soon (QUIET); or, while frames keep
 *       coming, until the accessibility tree ({@link UiaTree#fingerprint}) has not changed for
 *       {@code apksim.uia.steadyMs} of the app's time (STEADY: an endless animation that moves nothing, which the
 *       old wait ran for its whole quiet time on every query); a tree that is still moving (a drawer settling, a
 *       fling) is waited for, up to {@code apksim.uia.movingMs}, where the old wait gave up at the quiet time.
 *   <li><b>Time</b>: with `--realtime-clock` the engine's own waits fast-forward
 *       ({@link RealTimeClock#fastForward}): the clock moves to the next frame or message without the wall-clock
 *       wait, and what comes due runs in order. That covers idle waits and the sleeps UiAutomator paces a gesture with
 *       ({@link #sleep} with {@link #GESTURE}; the translator sends UiAutomator's SystemClock.sleep calls here). The
 *       app sees the same event times and the same frames between them. Sleeps of a wait on the app
 *       ({@link #POLL}: WaitMixin's and UiObject's polling) keep real time there.
 *   <li><b>Accessibility</b> ({@link #connect}, {@link #onEvent}): once a test uses UiAutomation, AccessibilityManager
 *       is enabled with no service listed, as a device's is while only UiAutomation is connected, so views send their
 *       accessibility events. They reach UiAutomation's listener and executeAndWaitForEvent's filter
 *       ({@link #runAndAwait}), which ends when the filter accepts one or nothing it can see is left that could send
 *       one, not at its timeout. The listener's events carry no source node (reading one inside the view's own call
 *       is not safe); a filter's do.
 *   <li><b>Workers</b> ({@link UiaWorkers}): a poll's sleep gives the app's other threads real time while they are
 *       at work, at most the sleep's length, as a device's sleep does; the simulated clock gave them none, and a
 *       wait's whole timeout could pass before a worker's result reached the main thread. Not an idle wait: a
 *       device's does not wait for them either (apksim.uia.holdIdle=true makes it).
 * </ul>
 *
 * Main thread only, except {@link #onEvent} and {@link #on}. {@code apksim.uia.events=false} and
 * {@code apksim.uia.workers=false} turn those parts off, for A/B runs; {@link #report} prints what the engine did.
 */
public final class Uia {
  private Uia() {}

  /** {@link #sleep}'s kinds: the pacing of an injected gesture, or a wait for the app between two looks at it. */
  public static final int POLL = 0;
  public static final int GESTURE = 1;

  private static final long STEADY_MS = Long.getLong("apksim.uia.steadyMs", 250);
  private static final long MOVING_MS = Long.getLong("apksim.uia.movingMs", 1000);
  private static final boolean EVENTS = !"false".equals(System.getProperty("apksim.uia.events"));
  private static final boolean WORKERS = !"false".equals(System.getProperty("apksim.uia.workers"));
  /**
   * -Dapksim.uia.holdIdle=true: an idle wait too gives busy workers real time. Off: a device's does not (checked on an
   * API 36 emulator: a look right after a click does not find what a thread takes 80 ms to work out; only a wait
   * does), so a test that passes for it here would fail there.
   */
  private static final boolean HOLD_IDLE = Boolean.getBoolean("apksim.uia.holdIdle");
  /** -Dapksim.uia.trace=true: a line on stderr for each idle wait, sleep and event (debugging). */
  private static final boolean TRACE = Boolean.getBoolean("apksim.uia.trace");
  /** With the clock on real time, work due later than this does not hold an idle wait (as before the engine). */
  private static final long SOON_MS = 50;
  /** The tree is first read this long into a wait that has not ended, then at most this often (the app's time). */
  private static final long FINGERPRINT_AFTER_MS = 32;
  private static final long FINGERPRINT_EVERY_MS = 48;

  private static volatile Boolean on;

  /** Whether the engine is asked for (-Dapksim.uia=engine). */
  static boolean on() {
    Boolean b = on;
    if (b == null) on = b = "engine".equals(System.getProperty("apksim.uia"));
    return b;
  }

  /** Whether the engine acts here: asked for, a test run, the main thread. */
  static boolean active() {
    return on() && TestActivities.testRun() && Looper.myLooper() == Looper.getMainLooper();
  }

  // --- A test's start ----------------------------------------------------------------------------------------------

  private static volatile boolean connected;
  private static volatile UiAutomation automation;
  private static volatile List<AccessibilityEvent> collector;

  /** At each test's setup: Robolectric's reset has turned accessibility off again. */
  static void startTest() {
    connected = false;
    automation = null;
    collector = null;
    UiaWorkers.reset();
  }

  /**
   * A test uses UiAutomation: accessibility is on from here, as on a device from the moment UiAutomation connects
   * (isEnabled() true, no enabled service listed: UiAutomation's is not among them, which Compose checks). Main thread.
   */
  static void connect(UiAutomation real) {
    if (!active()) return;
    automation = real;
    if (connected || !EVENTS) return;
    connected = true;  // first: enabling sends events
    AccessibilityManager am = RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager.class);
    Shadow.<ShadowAccessibilityManager>extract(am).setEnabled(true);  // tells each window, which registers and reports
    Stats.connects++;
  }

  /** Whether accessibility events are being taken (a test connected UiAutomation with the engine on). */
  static boolean connected() {
    return connected;
  }

  // --- Events --------------------------------------------------------------------------------------------------------

  private static java.lang.reflect.Field listenerField;

  /** An accessibility event a view sent (AccessibilityManager.sendAccessibilityEvent), on the thread that sent it. */
  static void onEvent(AccessibilityEvent event) {
    if (!connected) return;
    AccessibilityEvent e = new AccessibilityEvent(event);  // the sender may reuse its own
    if (e.getEventTime() == 0) e.setEventTime(SystemClock.uptimeMillis());
    Stats.events++;
    if (TRACE) System.err.println("[apksim] uia event: " + AccessibilityEvent.eventTypeToString(e.getEventType()) + " " + e.getClassName());
    List<AccessibilityEvent> c = collector;
    if (c != null) {
      synchronized (c) {
        c.add(e);
      }
    }
    UiAutomation a = automation;
    if (a == null) return;
    try {
      if (listenerField == null) {
        java.lang.reflect.Field f = UiAutomation.class.getDeclaredField("mOnAccessibilityEventListener");
        f.setAccessible(true);
        listenerField = f;
      }
      UiAutomation.OnAccessibilityEventListener l = (UiAutomation.OnAccessibilityEventListener) listenerField.get(a);
      if (l != null) l.onAccessibilityEvent(e);
    } catch (ReflectiveOperationException | RuntimeException ex) {
      System.err.println("[apksim] uia: an accessibility event listener failed: " + ex);
    }
  }

  /**
   * executeAndWaitForEvent: runs the command and offers the filter each accessibility event from then on, as it
   * arrives. Returns the event the filter accepted, or null once the timeout has passed (in the app's time) or
   * nothing is left that could send one: no work scheduled on the main thread within the timeout (the clock moves to
   * what is, a delayed post included), frames that no longer change the tree, and no other thread at work (those get
   * real time, at most the timeout). A device waits its timeout out whatever happens; what this cannot see coming (an
   * answer from the network, a timer on another thread) is not waited for.
   */
  static AccessibilityEvent runAndAwait(Runnable command, UiAutomation.AccessibilityEventFilter filter, long timeoutMs) {
    List<AccessibilityEvent> got = new ArrayList<>();
    collector = got;
    try {
      long from = SystemClock.uptimeMillis();
      command.run();
      int[] next = {0};
      AccessibilityEvent[] hit = {null};
      BooleanSupplier check = () -> {
        while (hit[0] == null) {
          AccessibilityEvent e;
          synchronized (got) {
            if (next[0] >= got.size()) break;
            e = got.get(next[0]++);
          }
          if (e.getEventTime() < from) continue;
          e = ShadowSimUiAutomation.asDelivered(e);
          if (filter.accept(e)) hit[0] = e;
        }
        return hit[0] != null;
      };
      long real0 = System.nanoTime();
      long deadline = from + Math.max(timeoutMs, 1);
      while (!check.getAsBoolean()) {
        long left = deadline - SystemClock.uptimeMillis();
        if (left <= 0) break;
        idle(left, left, check, left);  // work scheduled any time before the timeout gets its turn
        if (check.getAsBoolean() || !WORKERS || !UiaWorkers.busy()) break;
        if (System.nanoTime() - real0 >= timeoutMs * 1_000_000L) break;
        if (!TestActivities.canRunMainQueue()) break;
        long t = System.nanoTime();
        try {
          TestActivities.sleep(1);  // a worker is at work: real time for it, the main queue running meanwhile
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        Stats.workerWaits++;
        Stats.workerNanos += System.nanoTime() - t;
        UiaWorkers.held(System.nanoTime() - t);
      }
      Stats.waits++;
      if (hit[0] != null) Stats.waitsByEvent++;
      return hit[0];
    } finally {
      collector = null;
    }
  }

  // --- Idle ----------------------------------------------------------------------------------------------------------

  /** How an idle wait ended. */
  enum Idle {
    /** Nothing is queued that is due soon. */
    QUIET,
    /** Frames (or recurring work) keep coming and the accessibility tree has stopped changing. */
    STEADY,
    /** The tree was still changing when the time allowed ran out. */
    CAP,
    /** The caller's condition held. */
    STOPPED,
    /** Nothing can run (a traversal waits for a frame that is not coming). */
    STUCK
  }

  private static final int FRAME = 1;
  private static final int WORK = 2;
  /** Work that is due now: it runs before any wait ends. */
  private static final int DUE = 4;

  /**
   * The main queue's fields, looked up when an idle wait first reads the queue: not in this class's own initializer,
   * which every test run reaches (on(), report()), with the engine off and before the framework is set up.
   */
  private static final class Queue {
    static final java.lang.reflect.Field MESSAGES;
    static final java.lang.reflect.Field NEXT;

    static {
      try {
        MESSAGES = MessageQueue.class.getDeclaredField("mMessages");
        MESSAGES.setAccessible(true);
        NEXT = Message.class.getDeclaredField("next");
        NEXT.setAccessible(true);
      } catch (NoSuchFieldException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }

  private static Class<?> frameHandler;

  /** A frame on its way: the Choreographer's own messages, or the real-time clock's vsync. */
  private static boolean frameMessage(Message m) {
    if (RealTimeClock.isVsync(m)) return true;
    Class<?> c = m.getTarget().getClass();
    if (frameHandler == null && c.getName().equals("android.view.Choreographer$FrameHandler")) frameHandler = c;
    return c == frameHandler;
  }

  /** What the main queue holds that is due by {@code untilMs}: FRAME, WORK (DUE too if by {@code nowMs}) or neither. */
  private static int pending(long untilMs, long nowMs) {
    MessageQueue q = Looper.getMainLooper().getQueue();
    int mask = 0;
    try {
      synchronized (q) {
        for (Message m = (Message) Queue.MESSAGES.get(q); m != null; m = (Message) Queue.NEXT.get(m)) {
          if (m.getTarget() == null) {  // a sync barrier: a traversal waiting for its frame
            mask |= FRAME;
            continue;
          }
          if (m.getWhen() > untilMs) break;
          if (TestRecorder.isTask(m)) continue;
          if (frameMessage(m)) mask |= FRAME;
          else mask |= m.getWhen() <= nowMs ? WORK | DUE : WORK;
        }
      }
    } catch (IllegalAccessException e) {
      return WORK;
    }
    return mask;
  }

  /** UiAutomation.waitForIdle. */
  static void waitForIdle(long quietMs, long globalMs) {
    idle(quietMs, globalMs, null);
  }

  /**
   * Runs the main queue until the UI is idle (see the class comment), {@code stop} holds (checked between tasks), or
   * the time allowed has passed: {@code quietMs} for work that is scheduled, longer (apksim.uia.movingMs, within
   * {@code globalMs}) while the accessibility tree is still changing, and the quiet time in real time whatever
   * happens. With the clock on real time, it is fast-forwarded meanwhile.
   */
  static Idle idle(long quietMs, long globalMs, BooleanSupplier stop) {
    return idle(quietMs, globalMs, stop, -1);
  }

  /**
   * As {@link #idle(long, long, BooleanSupplier)}; {@code workMs} (when not negative) is how far ahead scheduled work
   * holds the wait, on either clock: an event wait gives it its whole timeout.
   */
  static Idle idle(long quietMs, long globalMs, BooleanSupplier stop, long workMs) {
    long real0 = System.nanoTime();
    int held0 = Stats.workerWaits;
    boolean realTime = RealTimeClock.requested();
    if (realTime) RealTimeClock.fastForward++;
    Idle how;
    try {
      how = idleLoop(quietMs, globalMs, stop, realTime, real0, workMs);
    } finally {
      if (realTime) RealTimeClock.fastForward--;
    }
    Stats.idle(how, System.nanoTime() - real0);
    if (TRACE) {
      System.err.println("[apksim] uia idle: " + how + " after " + (System.nanoTime() - real0) / 1000 + " us, quiet " + quietMs
          + " ms, held " + (Stats.workerWaits - held0) + ", clock " + SystemClock.uptimeMillis());
    }
    return how;
  }

  private static Idle idleLoop(long quietMs, long globalMs, BooleanSupplier stop, boolean realTime, long real0,
      long workMs) {
    ShadowLooper main = shadowOf(Looper.getMainLooper());
    long t0 = SystemClock.uptimeMillis();
    // Work scheduled up to here is waited for (the clock jumps to it): the quiet time, as before the engine; with the
    // clock on real time only what is due soon, as before there.
    long workBy = t0 + (workMs >= 0 ? workMs : realTime ? SOON_MS : quietMs);
    long cap = t0 + Math.min(globalMs, Math.max(quietMs, MOVING_MS));
    long realCap = real0 + Math.max(quietMs, 1) * 1_000_000L;
    boolean have = false;
    long fingerprint = 0;
    long readAt = 0;
    long sameSince = 0;
    while (true) {
      if (stop != null && stop.getAsBoolean()) return Idle.STOPPED;
      long now = SystemClock.uptimeMillis();
      long until = Math.max(workBy, now + SOON_MS);
      int mask = pending(until, now);
      Idle done = null;
      if (mask == 0) {
        done = Idle.QUIET;
      } else {
        if (now - t0 >= FINGERPRINT_AFTER_MS && (!have || now - readAt >= FINGERPRINT_EVERY_MS)) {
          long f = UiaTree.fingerprint();
          if (!have || f != fingerprint) {
            fingerprint = f;
            sameSince = now;
            have = true;
          }
          readAt = now;
        }
        boolean steady = have && now - sameSince >= STEADY_MS;
        // Work scheduled within the time allowed gets its turn first; past that, work that keeps coming (a ticker)
        // holds the wait no more than frames do.
        if (steady && (mask & DUE) == 0 && ((mask & WORK) == 0 || now >= workBy)) done = Idle.STEADY;
        else if (now >= cap || System.nanoTime() - realCap >= 0) done = Idle.CAP;
      }
      if (done != null) {
        if (done != Idle.CAP && HOLD_IDLE) {
          if (UiaWorkers.busy()) {
            if (workersHold(real0, quietMs)) continue;
          } else if (pending(until, now) != mask) {
            continue;  // a worker's last act was a post to this thread, made since the queue was looked at
          }
        }
        return done;
      }
      if (main.isIdle() && main.getNextScheduledTaskTime().isZero()) return Idle.STUCK;
      main.runOneTask();  // what is due, else the clock moves to the next one
      Stats.tasks++;
    }
  }

  /**
   * The app's other threads are at work: gives them a moment more (a real millisecond, waited here) and says so, at
   * most the quiet time per wait.
   */
  private static boolean workersHold(long real0, long quietMs) {
    if (System.nanoTime() - real0 >= quietMs * 1_000_000L) return false;
    long t = System.nanoTime();
    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
    Stats.workerWaits++;
    Stats.workerNanos += System.nanoTime() - t;
    UiaWorkers.held(System.nanoTime() - t);
    return true;
  }

  // --- UiAutomator's sleeps ------------------------------------------------------------------------------------------

  /**
   * SystemClock.sleep in UiAutomator's own code (MethodTranslator sends those calls here, with what the sleep is for).
   * Without the engine, or off the main thread, it is SystemClock.sleep.
   */
  public static void sleep(long ms, int kind) {
    if (ms <= 0 || !active()) {
      SystemClock.sleep(ms);
      return;
    }
    if (TRACE) System.err.println("[apksim] uia sleep: " + ms + " ms, " + (kind == GESTURE ? "gesture" : "poll"));
    if (kind == GESTURE) gestureSleep(ms); else pollSleep(ms);
  }

  /**
   * The time between two events of an injected gesture, a press's length, the wait for a list to snap back: time the
   * app needs to see pass, not time anyone needs to wait. With the clock on real time it is fast-forwarded: the clock
   * moves through it and each frame or message that comes due runs at its time. (Without, the clock jumps anyway.)
   */
  private static void gestureSleep(long ms) {
    if (!RealTimeClock.requested() || !TestActivities.canRunMainQueue()) {
      SystemClock.sleep(ms);
      return;
    }
    Stats.gestureSleeps++;
    Stats.gestureMs += ms;
    long t = System.nanoTime();
    RealTimeClock.fastForward++;
    try {
      ShadowLooper main = shadowOf(Looper.getMainLooper());
      long target = SystemClock.uptimeMillis() + ms;
      long giveUp = t + 2_000_000_000L;  // a task that posts itself again without end
      while (System.nanoTime() - giveUp < 0) {
        if (!main.isIdle()) {
          main.runOneTask();
          continue;
        }
        long now = SystemClock.uptimeMillis();
        if (now >= target) break;
        long next = main.getNextScheduledTaskTime().toMillis();
        long to = next > now && next < target ? next : target;
        ShadowSystemClock.advanceBy(Duration.ofMillis(to - now));
      }
    } finally {
      RealTimeClock.fastForward--;
    }
    Stats.gestureNanos += System.nanoTime() - t;
  }

  /**
   * UiAutomator's wait for the next frame after a gesture's step (TestActivities.awaitFrame), with the clock on real
   * time: the frame it asked for is fast-forwarded to, not waited for. A frame that is not coming within a few
   * (nothing was scheduled) is waited for in real time, up to the timeout, as before.
   */
  static boolean awaitFrame(java.util.concurrent.CountDownLatch latch, long timeoutNanos) throws InterruptedException {
    ShadowLooper main = shadowOf(Looper.getMainLooper());
    long end = System.nanoTime() + timeoutNanos;
    RealTimeClock.fastForward++;
    try {
      while (latch.getCount() > 0) {
        if (Thread.interrupted()) throw new InterruptedException();
        if (System.nanoTime() - end >= 0) return false;
        if (!main.isIdle()) {
          main.runOneTask();
          continue;
        }
        long now = SystemClock.uptimeMillis();
        long next = main.getNextScheduledTaskTime().toMillis();
        if (next > now && next <= now + 2 * SOON_MS) ShadowSystemClock.advanceBy(Duration.ofMillis(next - now));
        else Thread.sleep(1);
      }
      return true;
    } finally {
      RealTimeClock.fastForward--;
    }
  }

  /**
   * The sleep between two looks at the app (WaitMixin, UiObject.waitForExists). With the clock on real time it is a
   * real sleep, as before. Without, the clock jumps, as before; but first the app's other threads get real time
   * while they are at work, at most the sleep's length, with the main queue running: a poll's whole timeout could
   * pass in simulated time before a worker had finished what the test was waiting for.
   */
  private static void pollSleep(long ms) {
    Stats.pollSleeps++;
    if (WORKERS && !RealTimeClock.requested() && TestActivities.canRunMainQueue()) {
      long t = System.nanoTime();
      long end = t + ms * 1_000_000L;
      boolean waited = false;
      while (System.nanoTime() - end < 0 && UiaWorkers.busy()) {
        waited = true;
        long step = System.nanoTime();
        try {
          TestActivities.sleep(1);  // runs what reaches the main queue meanwhile
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        UiaWorkers.held(System.nanoTime() - step);  // a thread that never stops is not waited for for ever
      }
      if (waited) {
        Stats.workerWaits++;
        Stats.workerNanos += System.nanoTime() - t;
      }
    }
    SystemClock.sleep(ms);
  }

  // --- What it did ---------------------------------------------------------------------------------------------------

  /** Counters for {@link #report}; main thread, but for events (a count that may be off by a few). */
  static final class Stats {
    private Stats() {}

    static int connects;
    static int events;
    static int waits;
    static int waitsByEvent;
    static int waitsBySynthesized;
    static long tasks;
    static int gestureSleeps;
    static long gestureMs;
    static long gestureNanos;
    static int pollSleeps;
    static int workerWaits;
    static long workerNanos;
    static int fingerprints;
    static long fingerprintNanos;
    private static final int[] IDLE = new int[Idle.values().length];
    private static long idleNanos;

    static void idle(Idle how, long nanos) {
      IDLE[how.ordinal()]++;
      idleNanos += nanos;
    }
  }

  /** One line on stderr at the end of a run with the engine on: what it did and what it cost. */
  static void report() {
    if (!on()) return;
    StringBuilder idle = new StringBuilder();
    int total = 0;
    for (Idle i : Idle.values()) {
      int n = Stats.IDLE[i.ordinal()];
      total += n;
      if (n > 0) idle.append(idle.length() == 0 ? "" : ", ").append(n).append(' ').append(i.name().toLowerCase(java.util.Locale.ROOT));
    }
    System.err.printf(
        "[apksim] uia engine: %d idle waits (%s) in %d ms, %d tasks, %d tree reads in %d ms; "
            + "%d gesture sleeps, %d ms of gesture time in %d ms; %d poll sleeps; %d accessibility events, "
            + "%d event waits (%d by an event, %d by a synthesized one); workers held %d waits for %d ms%n",
        total, idle, Stats.idleNanos / 1_000_000L, Stats.tasks, Stats.fingerprints, Stats.fingerprintNanos / 1_000_000L,
        Stats.gestureSleeps, Stats.gestureMs, Stats.gestureNanos / 1_000_000L, Stats.pollSleeps, Stats.events,
        Stats.waits, Stats.waitsByEvent, Stats.waitsBySynthesized, Stats.workerWaits, Stats.workerNanos / 1_000_000L);
  }
}
