package dev.apksim;

import android.os.SystemClock;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPausedSystemClock;

/**
 * Running instances (not test runs): SystemClock.sleep sleeps in real time and leaves the clock alone, as on a
 * device. Robolectric's moves the simulated clock forward by the time asked and returns at once, from any thread. In
 * Host, whose loop moves the clock with real time, that time was never given back: each call left every app timer
 * (CountDownTimer, Handler.postDelayed) firing that much earlier, and a worker polling with SystemClock.sleep spun
 * without sleeping, racing the clock minutes ahead (an app's 10-minute inactivity timer fired after ~30 s). The test
 * app's apksim://timer page shows it (scripts/timer-check.py). Test runs keep Robolectric's, which suites rely on, or
 * RealTimeClock's with --realtime-clock.
 */
@Implements(SystemClock.class)
public class ShadowRealSleep extends ShadowPausedSystemClock {
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
  }
}
