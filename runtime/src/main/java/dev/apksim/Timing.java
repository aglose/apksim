package dev.apksim;

/** Startup stopwatch measured from the launcher's t0 (epoch millis, passed in APKSIM_T0). */
public final class Timing {
  private static final long T0 = t0();
  private static final StringBuilder LOG = new StringBuilder();

  private Timing() {}

  private static long t0() {
    String env = System.getenv("APKSIM_T0");
    if (env != null && !env.isEmpty()) return Long.parseLong(env);
    return ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis());
  }

  public static synchronized void mark(String stage) {
    long t = System.currentTimeMillis() - T0;
    LOG.append(String.format("%-20s %6d ms%n", stage, t));
    if (!Boolean.getBoolean("apksim.quiet")) System.err.printf("[apksim] %-20s %6d ms%n", stage, t);
  }

  public static synchronized void done() {
    if (Boolean.getBoolean("apksim.profile")) Sampler.report();
    System.err.flush();
  }
}
