package org.robolectric.shadows;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * apksim's access to Robolectric's package-private clock API, for dev.apksim.RealTimeClock: a listener on every
 * advance of the simulated clock that outlives Robolectric's reset between tests, and the clock read without going
 * through SystemClock's shadow.
 */
public final class ApksimClock {
  private ApksimClock() {}

  /** Calls {@code listener} after each advance of the simulated clock, on the thread that advanced it. */
  public static void addStaticListener(Consumer<Duration> listener) {
    ShadowPausedSystemClock.addStaticListener(listener::accept);
  }

  /** The simulated elapsed realtime in nanoseconds, as Robolectric keeps it. */
  public static long elapsedRealtimeNanos() {
    return ShadowPausedSystemClock.elapsedRealtimeNanos();
  }

  /** The simulated uptime in nanoseconds, as Robolectric keeps it. */
  public static long uptimeNanos() {
    return ShadowPausedSystemClock.uptimeNanos();
  }
}
