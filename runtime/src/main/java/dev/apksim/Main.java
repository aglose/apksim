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
    if (Standby.requested()) args = Standby.await(); else Timing.mark("jvm-main");
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
    String zone = System.getProperty("apksim.timezone");
    if (zone != null) java.time.ZoneId.of(zone);  // an unknown zone stops the launch here (SimRunner applies it)
    String control = System.getProperty("apksim.control");
    if (control != null) Control.start(control.equals("auto") ? 0 : Integer.parseInt(control));
    String adb = System.getProperty("apksim.adb");
    if (adb != null) AdbServer.start(adb.equals("auto") ? 0 : Integer.parseInt(adb));
    String testApk = System.getProperty("apksim.instrument");
    // A test run draws nothing (tests run on a simulated clock, see Instrument), so it needs no window.
    if (testApk != null) System.setProperty("apksim.headless", "true");
    Device device = Device.current();
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
      AppInfo.load(apk, Path.of(System.getProperty("apksim.cacheRoot"), apk.getFileName() + "-" + apk.toFile().lastModified()));
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
      ClassCache.flush();
      Storage.deleteScratch();
      Standby.halt(code);
    }
    // A device's default uncaught-exception handler (RuntimeInit's): FATAL EXCEPTION in the log, then the process ends.
    Crashes.install(classes);
    Result r = new JUnitCore().run(Request.aClass(Host.class));
    for (Failure f : r.getFailures()) {
      if (!Crashes.wasLogged(f.getException())) f.getException().printStackTrace();
    }
    System.err.printf("[apksim] translated %d classes in %d ms, %d from cache, %d ahead%n", classes.translated,
        classes.translateNanos / 1_000_000, classes.cached(), classes.translatedAhead());
    if (r.getRunCount() == 0) System.err.println("[apksim] the app did not start: runner found nothing to run");
    ClassCache.flush();
    Storage.deleteScratch();
    Standby.halt(r.wasSuccessful() ? 0 : 1);
  }
}
