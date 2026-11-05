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
 *   <li>elapsedRealtime reads catch up as uptime reads do, and a read off the main thread reports at least real time
 *       without moving the clock (only the main thread moves it, so frames stay on that thread).
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
   * What a clock read reports: on the main thread the clock caught up to real time; elsewhere, without moving it, at
   * least the real time since the test started (both clocks start a test together and advance together).
   */
  private static long readNanos(long simulated, long base) {
    if (!on || Thread.currentThread() == main) return simulated;
    return Math.max(simulated, base + realElapsedNanos());
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

  /** After any advance of the clock, on the thread that made it: waits until real time has caught up with it. */
  private static void advanced(Duration by) {
    if (!on || (catchingUp && Thread.currentThread() == main)) return;
    long ahead = aheadNanos();
    if (ahead <= 0) return;
    try {
      TimeUnit.NANOSECONDS.sleep(ahead);
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

  /** The longest a looper waits for the frame that will lift a barrier: more than a frame means none is coming. */
  private static final long BARRIER_WAIT_MS = 100;

  /**
   * The looper is looking for its next message: if a sync barrier holds the queue and the next asynchronous message
   * (the frame that runs the traversal and lifts it) is due within BARRIER_WAIT_MS, waits for it in real time.
   */
  private static void waitOutBarrier() {
    android.os.MessageQueue q = Looper.getMainLooper().getQueue();
    long frameAt = -1;
    try {
      synchronized (q) {
        Message m = (Message) MESSAGES.get(q);
        if (m == null || m.getTarget() != null) return;  // no barrier at the head
        for (; m != null; m = (Message) NEXT.get(m)) {
          if (m.isAsynchronous()) {
            frameAt = m.getWhen();
            break;
          }
        }
      }
    } catch (IllegalAccessException e) {
      return;
    }
    if (frameAt < 0) return;
    long waitMs = frameAt - ApksimClock.uptimeNanos() / 1_000_000L;
    if (waitMs <= 0 || waitMs > BARRIER_WAIT_MS) return;
    try {
      Thread.sleep(waitMs);
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
