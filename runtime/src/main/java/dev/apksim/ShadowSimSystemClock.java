package dev.apksim;

import android.os.SystemClock;
import java.util.concurrent.TimeUnit;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPausedSystemClock;

/**
 * SystemClock.sleep in a running instance sleeps in real time, as on a device. Robolectric's moves the simulated clock
 * on by the time asked, at once and for every thread: a worker polling with SystemClock.sleep(100) spun without waiting
 * and pushed elapsedRealtime and uptimeMillis ahead of real time, as fast as it could loop, so the app's own timers came
 * due early (a 10-minute inactivity CountDownTimer fired after ~30 s). The Host loop moves the clock with real time; a
 * sleep only has to take that time. In test runs Robolectric's stays: there the clock is meant to jump over waits (and
 * --realtime-clock has its own, RealTimeClock.ShadowRealTimeSystemClock, registered instead of this one).
 */
@Implements(SystemClock.class)
public class ShadowSimSystemClock extends ShadowPausedSystemClock {
  @Implementation
  protected static void sleep(long ms) {
    if (TestActivities.testRun()) {
      ShadowPausedSystemClock.sleep(ms);
      return;
    }
    // Android's: to its own deadline, ignoring interrupts and keeping them.
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
    boolean interrupted = false;
    for (long left; (left = deadline - System.nanoTime()) > 0; ) {
      try {
        TimeUnit.NANOSECONDS.sleep(left);
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
