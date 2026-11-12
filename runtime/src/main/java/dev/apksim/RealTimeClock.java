package dev.apksim;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.DisplayEventReceiver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ApksimClock;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowDisplayEventReceiver;
import org.robolectric.shadows.ShadowPausedSystemClock;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * `--realtime-clock` (-Dapksim.realtimeClock=true), for test runs: the simulated clock follows real time instead of
 * jumping ahead, as a device's does. Off by default: suites written for apksim (and Robolectric) rely on idle time
 * being skipped, and run much faster for it.
 *
 * <p>By default Robolectric's Choreographer is not paused in a test run: each frame a view or animation asks for
 * moves the clock 15 ms on the spot and runs at once, so a single idle of the main looper (every Espresso check) runs
 * frame after frame for as long as an animation lasts, and a message posted for 5 s later (a banner that closes
 * itself) comes due within that one check. With this switch:
 * <ul>
 *   <li>the Choreographer is paused, as Host runs it, so a frame comes when the clock passes its vsync, not at once;
 *       each frame asked for also posts an asynchronous message for its vsync time, as a device's display does, so the
 *       looper sees it even behind a traversal's sync barrier (without it, a check Espresso posted behind the barrier
 *       waited for a frame that only a clock read would bring, and the looper reads the clock only for a message it
 *       can see);
 *   <li>on the main thread, reading the clock (SystemClock.uptimeMillis, which the looper does each time it looks for
 *       its next message) first moves it up to real time, so frames and delayed messages come due at a device's pace
 *       and an idle ends when nothing is due yet, as Espresso sees between a device's frames;
 *   <li>when the looper looks for its next message and the queue is held by a sync barrier (a traversal waiting for
 *       its frame) with the frame due within a few milliseconds, it waits that long: Espresso posts its check, runs the
 *       queue until idle and then waits on the check from the main thread itself, which on a device is another thread,
 *       so a check behind the barrier would otherwise never run;
 *   <li>SystemClock.sleep sleeps in real time until its own deadline (two threads sleeping a second each take a
 *       second, not two), and anything else that moves the clock ahead of real time (Robolectric's idleFor) waits for
 *       real time to catch up;
 *   <li>elapsedRealtime reads catch up as uptime reads do, and a read off the main thread reports real time, in the
 *       main thread's whole milliseconds, without moving the clock (only the main thread moves it, so frames stay on
 *       that thread).
 * </ul>
 * Real time is counted from each test's setup, where Robolectric's clock starts again.
 */
final class RealTimeClock {
  private RealTimeClock() {}

  private static volatile boolean on;
  private static volatile Thread main;
  private static volatile long realBaseNanos;
  private static volatile long simBaseNanos;
  private static volatile long elapsedBaseNanos;
  private static boolean listening;
  /** Set on the main thread while it moves the clock itself, so that move neither recurses nor waits. */
  private static boolean catchingUp;
  /** Main thread only: whether a vsync message is queued, and the handler that queues it (new each test). */
  private static boolean vsyncPosted;
  private static Handler handler;

  static boolean requested() {
    return TestActivities.testRun() && Boolean.getBoolean("apksim.realtimeClock");
  }

  /** At each test's setup, after Robolectric has reset its clock. */
  static void startTest() {
    on = false;
    if (!listening) {
      ApksimClock.addStaticListener(RealTimeClock::advanced);
      listening = true;
    }
    main = Looper.getMainLooper().getThread();
    handler = new Handler(Looper.getMainLooper());
    vsyncPosted = false;
    barrierSince = -1;
    barrierSeen = -1;
    ShadowChoreographer.setPaused(true);
    simBaseNanos = ApksimClock.uptimeNanos();
    elapsedBaseNanos = ApksimClock.elapsedRealtimeNanos();
    realBaseNanos = System.nanoTime();
    on = true;
  }

  /** Before Robolectric resets between tests: its clock goes back, which is not time passing. */
  static void endTest() {
    on = false;
  }

  /** How far the simulated clock is ahead of real time since the test started (negative: behind). */
  private static long aheadNanos() {
    return (ApksimClock.uptimeNanos() - simBaseNanos) - (System.nanoTime() - realBaseNanos);
  }

  /**
   * How long before a deadline a real-time wait stops sleeping and spins (-Dapksim.preciseWaits: true = 200 ms, false =
   * never, auto = probed once). Some VMs (macOS CI runners) wake a short sleep 8x late (a 16 ms sleep took ~130 ms), which
   * made every frame wait of a barrier several times slower than on a desktop Mac (issue 201).
   */
  private static volatile long spinMarginNanos = -1;

  static long spinMargin() {
    long m = spinMarginNanos;
    if (m >= 0) return m;
    String mode = System.getProperty("apksim.preciseWaits", "auto");
    if (mode.equals("true")) m = 200_000_000L;
    else if (mode.equals("false")) m = 0;
    else {
      long[] over = new long[5];
      for (int i = 0; i < over.length; i++) {
        long t = System.nanoTime();
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return 0;  // an incomplete probe is not kept: a later wait probes again
        }
        over[i] = System.nanoTime() - t - 5_000_000L;
      }
      java.util.Arrays.sort(over);
      long median = over[over.length / 2];
      m = median < 3_000_000L ? 0 : Math.min(4 * median, 200_000_000L);
      System.err.println("[apksim] realtime-clock: sleeps overshoot " + median / 1_000_000L + " ms"
          + (m > 0 ? ", spinning the last " + m / 1_000_000L + " ms of short waits" : ""));
    }
    return spinMarginNanos = m;
  }

  /**
   * Waits until {@code deadline} (System.nanoTime): sleeps while more than the spin margin is left, then spins. Throws
   * InterruptedException on interrupt.
   */
  static void waitUntil(long deadline) throws InterruptedException {
    long margin = spinMargin();
    for (long left; (left = deadline - System.nanoTime()) > 0; ) {
      if (Thread.interrupted()) throw new InterruptedException("sleep interrupted");
      if (left > margin) {
        long sleep = left - margin;
        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
      } else {
        Thread.onSpinWait();
      }
    }
  }

  /** On the main thread: moves the clock up to real time, in whole milliseconds (Robolectric's resolution). */
  static void catchUp() {
    if (!on || catchingUp || Thread.currentThread() != main) return;
    long behindMs = -aheadNanos() / 1_000_000L;
    if (behindMs <= 0) return;
    catchingUp = true;
    try {
      ShadowSystemClock.advanceBy(Duration.ofMillis(behindMs));
    } finally {
      catchingUp = false;
    }
  }

  /** Real time since the test started, in nanoseconds. */
  private static long realElapsedNanos() {
    return System.nanoTime() - realBaseNanos;
  }

  /**
   * What a clock read reports: on the main thread the clock, caught up to real time; elsewhere, without moving it, the
   * real time since the test started (both clocks start a test together), in whole milliseconds as the main thread's
   * catch-up moves it, so a time a worker hands to the main thread is never ahead of the main thread's own. Not the
   * simulated clock when that is ahead: while idleFor waits for real time to catch up with it, a worker would see that
   * future time and a real sleep of its own would seem to take none.
   */
  private static long readNanos(long simulated, long base) {
    if (!on || Thread.currentThread() == main) return simulated;
    return base + realElapsedNanos() / 1_000_000L * 1_000_000L;
  }

  /** SystemClock.sleep: a real-time sleep to its own deadline, ignoring interrupts and keeping them, as Android's. */
  private static void sleepRealTime(long ms) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
    boolean interrupted = false;
    for (long left; (left = deadline - System.nanoTime()) > 0; ) {
      try {
        // On the main thread in a test body, the queue keeps running as for Thread.sleep (TestActivities.sleep).
        TestActivities.sleep(TimeUnit.NANOSECONDS.toMillis(left), (int) (left % 1_000_000L));
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    catchUp();
  }

  /**
   * Main thread only: above 0 while the UiAutomator engine ({@link Uia}) fast-forwards. Wherever this clock would wait
   * for real time (after an advance past it, for a barrier's frame), it then takes the simulated time as passed
   * instead: real time's base moves back by what was skipped, so the clock still follows real time from there on,
   * and a worker's clock reads (real time since the base) see the jump too.
   */
  static int fastForward;

  /** Whether the clock moves ahead without waiting right now (the main thread, inside the engine's fast-forward). */
  private static boolean skipping() {
    return fastForward > 0 && Thread.currentThread() == main;
  }

  /** After any advance of the clock, on the thread that made it: waits until real time has caught up with it. */
  private static void advanced(Duration by) {
    if (!on || (catchingUp && Thread.currentThread() == main)) return;
    long ahead = aheadNanos();
    if (ahead <= 0) return;
    if (skipping()) {
      realBaseNanos -= ahead;  // the time skipped counts as passed
      return;
    }
    try {
      if (spinMargin() > 0) waitUntil(System.nanoTime() + ahead);
      else TimeUnit.NANOSECONDS.sleep(ahead);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();  // a test's timeout: let it reach the test
    }
  }

  /**
   * A frame was asked for: queues an asynchronous message for its vsync time, which brings the clock there (catchUp)
   * when real time does, and Robolectric's paused Choreographer then delivers the frame.
   */
  static void vsyncWanted() {
    if (!on || vsyncPosted || Thread.currentThread() != main) return;
    long atMs = (ShadowChoreographer.getNextVsyncTimeNanos() + 999_999L) / 1_000_000L;
    Message m = Message.obtain(handler, () -> {
      vsyncPosted = false;
      catchUp();
    });
    m.setAsynchronous(true);
    vsyncPosted = handler.sendMessageAtTime(m, atMs);
  }

  /** Whether a queued message is this clock's vsync message (a frame that was asked for). Main thread. */
  static boolean isVsync(Message m) {
    return handler != null && m.getTarget() == handler;
  }

  /** DisplayEventReceiver with a vsync message for each frame asked for; registered only with the switch on. */
  @Implements(DisplayEventReceiver.class)
  public static class ShadowRealTimeDisplayEventReceiver extends ShadowDisplayEventReceiver {
    @Implementation
    protected static void nativeScheduleVsync(long receiverPtr) {
      ShadowDisplayEventReceiver.nativeScheduleVsync(receiverPtr);
      vsyncWanted();
    }
  }

  private static final java.lang.reflect.Field MESSAGES;
  private static final java.lang.reflect.Field NEXT;

  static {
    try {
      MESSAGES = android.os.MessageQueue.class.getDeclaredField("mMessages");
      MESSAGES.setAccessible(true);
      NEXT = Message.class.getDeclaredField("next");
      NEXT.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Main thread only: since when (System.nanoTime) the looper's looks at the queue have kept finding a barrier at its
   * head, none more than BARRIER_WAIT_MS after the one before, or -1.
   */
  private static long barrierSince = -1;
  /** Main thread only: when a look last found a barrier at the head, or -1. */
  private static long barrierSeen = -1;
  /**
   * `--jvm -Dapksim.barrierStreakMs=N` (issue 215): a Scroller-driven settle (DrawerLayout, ViewPager) is a finite run of
   * barriers like an endless animation's, and counts as idle once the streak passes this, with Espresso acting on a
   * view still moving. A drawer settles within ~600 ms, so 1000 covers it; 250 stays the default until a Mac run says
   * what the longer idle wait costs a suite under an endless animation.
   */
  private static final long BARRIER_STREAK_MS = Long.getLong("apksim.barrierStreakMs", 250);

  /**
   * Main thread only: above 0 while a caller runs just the messages already due (Compose's test clock stepping a
   * frame, say), where a barrier's frame is not wanted: waiting it out cost a frame of real time per test-clock frame.
   */
  static int noBarrierWait;

  /** The longest a looper waits for the frame that will lift a barrier: more than a frame means none is coming. */
  private static final long BARRIER_WAIT_MS = 100;

  /**
   * The looper is looking for its next message: if a sync barrier holds the queue and the next asynchronous message
   * (the frame that runs the traversal and lifts it) is due within BARRIER_WAIT_MS, waits for it in real time.
   */
  private static void waitOutBarrier() {
    if (noBarrierWait > 0) return;
    android.os.MessageQueue q = Looper.getMainLooper().getQueue();
    long frameAt = -1;
    try {
      synchronized (q) {
        Message m = (Message) MESSAGES.get(q);
        long nowNanos = System.nanoTime();
        // A streak ends only once no barrier has been seen for longer than a frame could take (BARRIER_WAIT_MS), not
        // at the first look that finds something else at the head: a screen that changes a view from a message
        // between frames (a ticker, a post from each frame) has that message at the head between every two
        // barriers, and resetting there kept the streak from ever reaching BARRIER_STREAK_MS (issue 131).
        boolean gap = barrierSeen < 0 || nowNanos - barrierSeen > BARRIER_WAIT_MS * 1_000_000L;
        if (m == null || m.getTarget() != null) {  // no barrier at the head
          if (gap) barrierSince = -1;
          return;
        }
        if (gap || barrierSince < 0) barrierSince = nowNanos;
        barrierSeen = nowNanos;
        boolean waiting = false;  // work held behind the barrier that is due now (an Espresso check, a posted task)
        long nowMs = ApksimClock.uptimeNanos() / 1_000_000L;
        for (Message n = (Message) NEXT.get(m); n != null; n = (Message) NEXT.get(n)) {
          if (n.isAsynchronous()) {
            if (frameAt < 0) frameAt = n.getWhen();
          } else if (n.getWhen() <= nowMs && !TestRecorder.isTask(n)) {
            waiting = true;
          }
        }
        // An endless animation keeps a barrier up frame after frame, and waiting each one out kept the looper from ever
        // being idle. Past BARRIER_STREAK_MS of barriers with nothing due behind them, a barrier counts as idle, as
        // Espresso counts it on a device; frames still come as the clock catches up. Work due behind the barrier keeps
        // the wait for this frame (Espresso blocks the main thread on its check's result once the queue is idle) but
        // does not start the streak again: a 16 ms ticker has its next tick due behind nearly every barrier, and
        // restarting there made one idle wait out frame after frame for as long as the screen ran (issue 131).
        if (!waiting && nowNanos - barrierSince > BARRIER_STREAK_MS * 1_000_000L) return;
      }
    } catch (IllegalAccessException e) {
      return;
    }
    if (frameAt < 0) return;
    long waitMs = frameAt - ApksimClock.uptimeNanos() / 1_000_000L;
    if (waitMs <= 0 || waitMs > BARRIER_WAIT_MS) return;
    if (skipping()) {  // the engine's fast-forward: the frame comes now (advanced() takes the time as passed)
      ShadowSystemClock.advanceBy(Duration.ofMillis(waitMs));
      return;
    }
    try {
      if (spinMargin() > 0) waitUntil(System.nanoTime() + waitMs * 1_000_000L);
      else Thread.sleep(waitMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    catchUp();
  }

  /** Whether this clock read is the paused looper's own, as it decides whether its next message is due. */
  private static boolean looperPeek() {
    return StackWalker.getInstance().walk(frames -> frames.limit(8).anyMatch(f ->
        f.getMethodName().equals("getNextExecutableMessage") && f.getClassName().endsWith("ShadowPausedLooper")));
  }

  /** SystemClock with the catch-up on reads from the main thread; registered only with the switch on. */
  @Implements(SystemClock.class)
  public static class ShadowRealTimeSystemClock extends ShadowPausedSystemClock {
    @Implementation
    protected static long uptimeMillis() {
      catchUp();
      if (on && !catchingUp && Thread.currentThread() == main && looperPeek()) waitOutBarrier();
      return readNanos(ApksimClock.uptimeNanos(), simBaseNanos) / 1_000_000L;
    }

    @Implementation
    protected static long elapsedRealtime() {
      return elapsedRealtimeNanos() / 1_000_000L;
    }

    @Implementation
    protected static long elapsedRealtimeNanos() {
      catchUp();
      return readNanos(ApksimClock.elapsedRealtimeNanos(), elapsedBaseNanos);
    }

    @Implementation
    protected static void sleep(long ms) {
      if (on) sleepRealTime(ms); else ShadowPausedSystemClock.sleep(ms);
    }
  }
}
