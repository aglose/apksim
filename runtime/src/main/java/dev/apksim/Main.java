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
    Timing.mark("jvm-main");
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
    String control = System.getProperty("apksim.control");
    if (control != null) Control.start(control.equals("auto") ? 0 : Integer.parseInt(control));
    Device device = Device.named(System.getProperty("apksim.device", "phone"));
    System.setProperty("apksim.qualifiers", device.qualifiers());
    SimWindow.open(device.widthPx(), device.heightPx(), device.density(), Path.of(args[0]).getFileName() + " - " + device.name());
    Storage.initScratch();
    Path apk = Path.of(args[0]).toAbsolutePath();
    AppInfo.load(apk, Path.of(System.getProperty("apksim.cacheRoot"), apk.getFileName() + "-" + apk.toFile().lastModified()));
    AppClasses classes = AppClasses.install(apk);
    SimKeyStore.install();  // before the app's code runs: Robolectric has no Android Keystore
    Timing.mark("apk-indexed");
    Result r = new JUnitCore().run(Request.aClass(Host.class));
    for (Failure f : r.getFailures()) {
      f.getException().printStackTrace();
    }
    System.err.printf("[apksim] translated %d classes in %d ms, %d from cache, %d ahead%n", classes.translated,
        classes.translateNanos / 1_000_000, classes.cached(), classes.translatedAhead());
    if (r.getRunCount() == 0) System.err.println("[apksim] the app did not start: runner found nothing to run");
    ClassCache.flush();
    Storage.deleteScratch();
    Runtime.getRuntime().halt(r.wasSuccessful() ? 0 : 1);
  }
}
