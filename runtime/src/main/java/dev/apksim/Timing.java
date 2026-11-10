package dev.apksim;

/** Startup stopwatch measured from the launcher's t0 (epoch millis, passed in APKSIM_T0). */
public final class Timing {
  private static long t0 = t0();
  private static final StringBuilder LOG = new StringBuilder();

  private Timing() {}

  private static long t0() {
    String env = System.getenv("APKSIM_T0");
    if (env != null && !env.isEmpty()) return Long.parseLong(env);
    return ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis());
  }

  public static synchronized void mark(String stage) {
    long t = System.currentTimeMillis() - t0;
    // Verbose marks also give the process's CPU time so far, all threads (JIT, GC and helper threads included).
    String cpu = "verbose".equals(System.getProperty("apksim.timing")) ? String.format("  cpu %6d ms", cpuMs()) : "";
    LOG.append(String.format("%-20s %6d ms%s%n", stage, t, cpu));
    if (!Boolean.getBoolean("apksim.quiet")) System.err.printf("[apksim] %-20s %6d ms%s%n", stage, t, cpu);
  }

  private static long cpuMs() {
    return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime() / 1_000_000;
  }

  /** A standby took a launch: from now on, times count from that launch's start. */
  static synchronized void restart(long launchT0) {
    t0 = launchT0;
    LOG.setLength(0);
  }

  /** Finer marks for startup work, shown with -Dapksim.timing=verbose. */
  public static void detail(String stage) {
    if ("verbose".equals(System.getProperty("apksim.timing"))) mark("  " + stage);
  }

  public static synchronized void done() {
    if (Boolean.getBoolean("apksim.profile")) Sampler.report();
    System.err.flush();
  }
}
