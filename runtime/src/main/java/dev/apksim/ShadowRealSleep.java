package dev.apksim;

import android.os.Looper;
import android.os.SystemClock;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPausedSystemClock;

/**
 * Running instances (not test runs): SystemClock.sleep sleeps in real time, and off the main thread leaves the clock
 * alone, as on a device. Robolectric's moves the simulated clock forward by the time asked and returns at once, from any thread. In
 * Host, whose loop moves the clock with real time, that time was never given back: each call left every app timer
 * (CountDownTimer, Handler.postDelayed) firing that much earlier, and a worker polling with SystemClock.sleep spun
 * without sleeping, racing the clock minutes ahead (most likely why an app's 10-minute inactivity timer fired after ~30 s). The test
 * app's apksim://timer page shows it (scripts/timer-check.py). Test runs keep Robolectric's, which suites rely on, or
 * RealTimeClock's with --realtime-clock.
 *
 * <p>On the main thread the clock does move by the time slept: Host's loop cannot move it while the thread sleeps, and
 * code waiting for a deadline there (`while (uptimeMillis() < end) SystemClock.sleep(10)`) would wait for ever. Host
 * counts that time as already given ({@link #takeMainAdvancedMs}), so it is not added twice.
 */
@Implements(SystemClock.class)
public class ShadowRealSleep extends ShadowPausedSystemClock {
  /** Milliseconds the main thread's sleeps moved the clock since Host last asked. Main thread only. */
  private static long mainAdvancedMs;

  /** For Host's loop: the clock time main-thread sleeps added since the last call, which real time already covers. */
  static long takeMainAdvancedMs() {
    long ms = mainAdvancedMs;
    mainAdvancedMs = 0;
    return ms;
  }

  @Implementation
  protected static void sleep(long ms) {
    // As Android's: to its own deadline, ignoring interrupts and keeping them.
    long deadline = System.nanoTime() + ms * 1_000_000L;
    boolean interrupted = false;
    for (long left; (left = deadline - System.nanoTime()) > 0; ) {
      try {
        Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L));
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    if (ms > 0 && Looper.getMainLooper().isCurrentThread()) {
      ShadowPausedSystemClock.sleep(ms);  // moves the clock (and wakes loopers waiting on it), without sleeping
      mainAdvancedMs += ms;
    }
  }
}
