package dev.apksim;

import java.nio.file.Path;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/** Process entry point. Everything Android runs on this one process's threads. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    if (Standby.requested()) {
      args = Standby.await();
    } else {
      Timing.mark("jvm-main");
      initTestEnvironmentAhead();
    }
    if (Boolean.getBoolean("apksim.profile")) Sampler.start();
    if (Boolean.getBoolean("apksim.traceExit")) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> Thread.getAllStackTraces().forEach((t, st) -> {
        for (StackTraceElement f : st) {
          if (f.getClassName().equals("java.lang.Shutdown")) {
            System.err.println("[apksim] exit from thread " + t.getName());
            for (StackTraceElement e : st) System.err.println("    " + e);
            return;
          }
        }
      })));
    }
    dev.apksim.net.Net.configure();  // --network options: before any app code makes a connection
    dev.apksim.rec.Rec.launch();  // --rec: its net and db channels are on before the app's first connection or query
    String zone = System.getProperty("apksim.timezone");
    if (zone != null) TimeZones.parse(zone);  // an unknown zone stops the launch here (TimeZones applies it)
    // A bad --device stops the launch here, one line and exit 2, before any port is opened or announced.
    Device device;
    try {
      device = Device.current();
    } catch (IllegalArgumentException e) {
      System.err.println("[apksim] --device: " + e.getMessage());
      Standby.halt(2);
      return;
    }
    // The APK's cache entry (a content key, locks, ~2 ms) while the ports and the window open.
    ApkCache.Taking taking = ApkCache.start(BeamSandbox.ON ? BeamSandbox.apkCacheRoot() : Path.of(System.getProperty("apksim.cacheRoot")),
        Path.of(args[0]).toAbsolutePath());
    String control = System.getProperty("apksim.control");
    if (control != null) Control.start(control.equals("auto") ? 0 : Integer.parseInt(control));
    String adb = System.getProperty("apksim.adb");
    if (adb != null) AdbServer.start(adb.equals("auto") ? 0 : Integer.parseInt(adb));
    String testApk = System.getProperty("apksim.instrument");
    // A test run draws nothing (tests run on a simulated clock, see Instrument), so it needs no window.
    if (testApk != null) System.setProperty("apksim.headless", "true");
    System.setProperty("apksim.qualifiers", device.qualifiers());
    System.setProperty("apksim.windowTitle", Path.of(args[0]).getFileName().toString());
    SimWindow.open(device.widthPx(), device.heightPx(), device.density(),
        Path.of(args[0]).getFileName() + " - " + device.name() + " " + device.widthDp() + "\u00d7" + device.heightDp() + " dp");
    Storage.initScratch();
    Path apk = Path.of(args[0]).toAbsolutePath();
    // For scripts/ctl.py: an instance from a standby has no APK on its command line.
    java.nio.file.Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "launch.info"),
        "apk=" + apk + "\ndevice=" + System.getProperty("apksim.device", "phone") + "\n");
    try {
      Path entry = taking.await();
      Timing.detail("apkcache");
      AppInfo.load(apk, entry);
    } catch (AppInfo.LaunchError e) {
      System.err.println("[apksim] " + e.getMessage());
      Storage.deleteScratch();
      Standby.halt(2);
    }
    Timing.detail("appinfo");
    AppClasses classes = AppClasses.install(apk);
    Timing.detail("appclasses");
    SimKeyStore.install();  // before the app's code runs: Robolectric has no Android Keystore
    classes.loadAhead();
    Timing.mark("apk-indexed");
    if (testApk != null) {
      int code = Instrument.run(Path.of(testApk).toAbsolutePath(), classes);
      // -Dapksim.exitMarks=true (instrument.py --exit-marks / --reap-early): where the time between the result and
      // the process ending goes, and the exit status ahead of it, so a caller can count the slot free once the
      // result and the outputs are complete and let the rest (class cache save, scratch removal) finish unwatched.
      boolean marks = Boolean.getBoolean("apksim.exitMarks");
      long t0 = System.nanoTime();
      if (marks) {
        dev.apksim.net.Net.flush();  // a recording's last exchanges, before a caller may stop waiting
        System.out.println("APKSIM_EXIT: " + code);
        System.out.flush();
      }
      if (Boolean.getBoolean("apksim.profile")) Sampler.report();  // -Dapksim.profile=true: the whole run's samples
      long t1 = System.nanoTime();
      ClassCache.flush();
      long t2 = System.nanoTime();
      Storage.deleteScratch();
      long t3 = System.nanoTime();
      if (marks) {
        StringBuilder live = new StringBuilder();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
          if (t.isAlive() && !t.isDaemon() && t != Thread.currentThread()) live.append(' ').append(t.getName());
        }
        System.err.printf("[apksim] exit marks: profile %d ms, class cache %d ms, scratch %d ms; non-daemon threads:%s%n",
            (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, live.length() == 0 ? " none" : live);
      }
      Standby.halt(code);
    }
    // A device's default uncaught-exception handler (RuntimeInit's): FATAL EXCEPTION in the log, then the process ends.
    Crashes.install();
    Result r = new JUnitCore().run(Request.aClass(Host.class));
    for (Failure f : r.getFailures()) {
      if (!Crashes.wasLogged(f.getException())) StackTraces.clean(f.getException()).printStackTrace();
    }
    System.err.printf("[apksim] translated %d classes in %d ms, %d from cache, %d ahead%n", classes.translated,
        classes.translateNanos / 1_000_000, classes.cached(), classes.translatedAhead());
    if (r.getRunCount() == 0) System.err.println("[apksim] the app did not start: runner found nothing to run");
    Exits.log(r.wasSuccessful() ? 0 : 1, "the main loop ended" + (r.wasSuccessful() ? "" : " with an error (above)"));
    ClassCache.flush();
    Storage.deleteScratch();
    Standby.halt(r.wasSuccessful() ? 0 : 1);
  }

  /**
   * Robolectric's AndroidTestEnvironment builds a BouncyCastleProvider in its static initializer, which loads and
   * registers every algorithm class: ~180 ms of a cold launch, on the main thread, interpreted, once the sandbox is
   * made. Nothing in it depends on the sandbox or the APK, so the class is initialized here on a thread of its own,
   * while the APK is indexed and the runner built; the main thread finds it done, or waits only for the rest. A standby
   * did it during its warm-up. -Dapksim.initAhead=false turns it off (A/B), and SimRunner's Conscrypt load with it.
   */
  private static void initTestEnvironmentAhead() {
    if (!Boolean.parseBoolean(System.getProperty("apksim.initAhead", "true"))) return;
    Thread t = new Thread(() -> {
      try {
        Class.forName("org.robolectric.android.internal.AndroidTestEnvironment", true, ClassLoader.getSystemClassLoader());
      } catch (Throwable e) {
        // The main thread meets the same error when it gets there, and reports it.
      }
    }, "apksim-init-ahead");
    t.setDaemon(true);
    t.start();
  }
}
