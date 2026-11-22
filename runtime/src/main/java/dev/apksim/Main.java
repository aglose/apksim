package dev.apksim;

import java.nio.file.Path;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/** Process entry point. Everything Android runs on this one process's threads. */
public final class Main {
  private static volatile java.util.concurrent.CompletableFuture<org.junit.runner.Runner> prebuiltHostRunner;

  private Main() {}

  public static void main(String[] args) throws Exception {
    if (Standby.requested()) {
      args = Standby.await();
    } else {
      Timing.mark("jvm-main");
      initTestEnvironmentAhead();
    }
    if (Boolean.getBoolean("apksim.profile")) Sampler.start();
    java.util.Map<String, String> downloadHeaders = takeDownloadHeaders();
    boolean downloadNetrc = Boolean.parseBoolean(System.clearProperty("apksim.downloadNetrc"));
    String targetArg = args[0];
    boolean isUrl = ApkDownloader.isUrl(targetArg);
    java.util.concurrent.CompletableFuture<ApkDownloader.Result> downloadFuture = null;
    if (isUrl) {
      if (BeamSandbox.ON) {
        System.err.println("[apksim] URL launches are not allowed inside the beam sandbox");
        Standby.halt(2);
        return;
      }
      boolean freshDl = Boolean.getBoolean("apksim.freshDownload") || Boolean.getBoolean("apksim.fresh");
      downloadFuture = ApkDownloader.downloadToCacheAsync(targetArg, Path.of(System.getProperty("apksim.cacheRoot")),
          freshDl, downloadHeaders, downloadNetrc);
    }
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
    dev.apksim.net.TabRelay.load();
    dev.apksim.net.ForwardLink.load();
    dev.apksim.net.Net.configure();  // --network options: before any app code makes a connection
    dev.apksim.rec.Rec.launch();  // --rec: its net and db channels are on before the app's first connection or query
    String zone = System.getProperty("apksim.timezone");
    if (zone != null) TimeZones.parse(zone);  // an unknown zone stops the launch here (TimeZones applies it)
    // --skin: its device unless --device names one; an unknown skin stops the launch as a bad --device does.
    if (!Skinning.launch()) {
      Standby.halt(2);
      return;
    }
    // A bad --device stops the launch here, one line and exit 2, before any port is opened or announced.
    Device device;
    try {
      device = Device.current();
    } catch (IllegalArgumentException e) {
      System.err.println("[apksim] --device: " + e.getMessage());
      Standby.halt(2);
      return;
    }
    Path apkCacheRoot = BeamSandbox.ON ? BeamSandbox.apkCacheRoot() : Path.of(System.getProperty("apksim.cacheRoot"));
    // The APK's cache entry (a content key, locks, ~2 ms) while the ports and the window open.
    ApkCache.Taking taking = isUrl ? null : ApkCache.start(apkCacheRoot, Path.of(targetArg).toAbsolutePath());
    String control = System.getProperty("apksim.control");
    if (control != null) {
      String tokenFile = System.getProperty("apksim.controlTokenFile");
      try {
        Control.start(control.equals("auto") ? 0 : Integer.parseInt(control), tokenFile);
      } catch (java.io.IOException e) {
        if (tokenFile == null) throw e;
        // No channel without its token. In a warm launch the standby's sandbox may be what keeps the file unread
        // (~/.config, ~/Documents, ~/Desktop): keep it elsewhere, or launch cold (APKSIM_STANDBY=0).
        System.err.println("[apksim] --control-token-file: cannot use " + tokenFile + ": " + e
            + " (the sandbox keeps ~/.config, ~/Documents and ~/Desktop from a warm launch)");
        Standby.halt(2);
        return;
      }
    }
    String adb = System.getProperty("apksim.adb");
    if (adb != null) AdbServer.start(adb.equals("auto") ? 0 : Integer.parseInt(adb));
    String testApk = System.getProperty("apksim.instrument");
    // A test run draws nothing (tests run on a simulated clock, see Instrument), so it needs no window.
    if (testApk != null) System.setProperty("apksim.headless", "true");
    System.setProperty("apksim.qualifiers", device.qualifiers());
    String displayName = isUrl ? ApkDownloader.fileNameFromUrl(targetArg) : Path.of(targetArg).getFileName().toString();
    System.setProperty("apksim.windowTitle", displayName);
    SimWindow.open(device.widthPx(), device.heightPx(), device.density(),
        displayName + " - " + device.name() + " " + device.widthDp() + "\u00d7" + device.heightDp() + " dp");
    if (Skinning.current() != null) SimWindow.setSkin(Skinning.layout(device.widthPx(), device.heightPx(), 0));
    if (!SimWindow.isHeadless()) {
      // Device > Skin: the store is read off the launch's path (a few small files, but nothing a first frame needs).
      Thread menu = new Thread(DeviceControls::fillSkinMenu, "apksim-skin-menu");
      menu.setDaemon(true);
      menu.start();
    }
    Storage.initScratch();
    AppClasses[] classesHolder = new AppClasses[1];
    boolean directOverlap = downloadFuture != null && !downloadFuture.isDone() && !Standby.isAdopted() && testApk == null
        && Boolean.parseBoolean(System.getProperty("apksim.overlapDownload", "true"));
    if (directOverlap) {
      // Zero-reset overlap: boot sandbox + native runtime directly inside Host's runner while the APK downloads,
      // then finish download + APK indexing right before Robolectric parses the manifest.
      var dlFut = downloadFuture;
      SimRunner.deferredApkSetup = () -> {
        try {
          Path apk = awaitDownload(dlFut, displayName);
          ApkCache.Taking t = ApkCache.start(apkCacheRoot, apk);
          classesHolder[0] = prepareApk(apk, t);
          Path cache = Path.of(System.getProperty("apksim.cache"));
          String pkg = System.getProperty("apksim.package");
          return new org.robolectric.manifest.AndroidManifest(cache.resolve("AndroidManifest.xml"), null, null,
              java.util.List.of(), pkg, apk);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      };
    } else {
      Path apk;
      if (downloadFuture != null) {
        apk = awaitDownload(downloadFuture, displayName);
        if (apk == null) return;
        taking = ApkCache.start(apkCacheRoot, apk);
      } else {
        apk = Path.of(targetArg).toAbsolutePath();
      }
      classesHolder[0] = prepareApk(apk, taking);
      if (classesHolder[0] == null) return;
    }
    if (testApk != null) {
      int code = Instrument.run(Path.of(testApk).toAbsolutePath(), classesHolder[0]);
      // -Dapksim.exitMarks=true (instrument.py --exit-marks / --reap-early): where the time between the result and
      // the process ending goes, and the exit status ahead of it, so a caller can count the slot free once the
      // result and the outputs are complete and let the rest (class cache save, scratch removal) finish unwatched.
      boolean marks = Boolean.getBoolean("apksim.exitMarks");
      long tn = System.nanoTime();
      if (marks) {
        dev.apksim.net.Net.flush();  // a recording's last exchanges, before a caller may stop waiting
        System.out.println("APKSIM_EXIT: " + code);
        System.out.flush();
      }
      long t0 = System.nanoTime();
      if (Boolean.getBoolean("apksim.profile")) Sampler.report();  // -Dapksim.profile=true: the whole run's samples
      Uia.report();  // --uia engine: what the UiAutomator engine did
      long t1 = System.nanoTime();
      ClassCache.flush();
      AppClasses.flushDexCache();
      long t2 = System.nanoTime();
      Storage.deleteScratch();
      long t3 = System.nanoTime();
      if (marks) {
        StringBuilder live = new StringBuilder();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
          if (t.isAlive() && !t.isDaemon() && t != Thread.currentThread()) live.append(' ').append(t.getName());
        }
        System.err.printf("[apksim] exit marks: net flush %d ms, profile %d ms, class cache %d ms, scratch %d ms; non-daemon threads:%s%n",
            (t0 - tn) / 1_000_000, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, live.length() == 0 ? " none" : live);
      }
      Standby.halt(code);
    }
    // A device's default uncaught-exception handler (RuntimeInit's): FATAL EXCEPTION in the log, then the process ends.
    Crashes.install();
    org.junit.runner.Runner hostRunner = null;
    java.util.concurrent.CompletableFuture<org.junit.runner.Runner> pre = prebuiltHostRunner;
    prebuiltHostRunner = null;
    if (pre != null) {
      try {
        hostRunner = pre.join();
      } catch (Throwable ignored) {
        // Fall through to build on the main thread so any error reports normally.
      }
    }
    Result r = hostRunner != null ? new JUnitCore().run(hostRunner) : new JUnitCore().run(Request.aClass(Host.class));
    for (Failure f : r.getFailures()) {
      if (!Crashes.wasLogged(f.getException())) StackTraces.clean(f.getException()).printStackTrace();
    }
    AppClasses classes = classesHolder[0];
    if (classes != null) {
      System.err.printf("[apksim] translated %d classes in %d ms, %d from cache, %d ahead%n", classes.translated,
          classes.translateNanos / 1_000_000, classes.cached(), classes.translatedAhead());
    }
    if (r.getRunCount() == 0) System.err.println("[apksim] the app did not start: runner found nothing to run");
    Exits.log(r.wasSuccessful() ? 0 : 1, "the main loop ended" + (r.wasSuccessful() ? "" : " with an error (above)"));
    ClassCache.flush();
    AppClasses.flushDexCache();
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
  /** Reads and immediately clears CLI {@code --header} properties so app code cannot inspect download credentials. */
  private static java.util.Map<String, String> takeDownloadHeaders() {
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 16; i++) {
      String raw = System.clearProperty("apksim.downloadHeader." + i);
      if (raw == null) continue;
      int colon = raw.indexOf(':');
      if (colon <= 0) continue;
      String name = raw.substring(0, colon).trim();
      String val = raw.substring(colon + 1).trim();
      if (!name.isEmpty()) out.put(name, val);
    }
    return out;
  }

  private static Path awaitDownload(java.util.concurrent.CompletableFuture<ApkDownloader.Result> future, String displayName) {
    ApkDownloader.Result dl;
    try {
      dl = future.join();
    } catch (java.util.concurrent.CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof java.io.UncheckedIOException uio && uio.getCause() != null) cause = uio.getCause();
      System.err.println("[apksim] download failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
      Storage.deleteScratch();
      Standby.halt(2);
      return null;
    }
    if (dl.connectionsUsed() > 0 && !Boolean.getBoolean("apksim.quiet")) {
      System.err.printf("[apksim] downloaded %s (%.1f MB) in %d ms (%d streams)%n",
          displayName, dl.bytes() / 1048576.0, dl.elapsedMs(), dl.connectionsUsed());
    }
    Timing.mark("download-ready");
    return dl.path();
  }

  private static AppClasses prepareApk(Path apk, ApkCache.Taking taking) throws Exception {
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
      return null;
    }
    Timing.detail("appinfo");
    if (Boolean.parseBoolean(System.getProperty("apksim.initAhead", "true"))) {
      org.robolectric.res.android.CppApkAssets.preload(apk, false);
    }
    AppClasses classes = AppClasses.install(apk);
    Timing.detail("appclasses");
    if (!Boolean.parseBoolean(System.getProperty("apksim.initAhead", "true"))) {
      SimKeyStore.install();  // before the app's code runs: Robolectric has no Android Keystore
    }
    classes.loadAhead();
    Timing.mark("apk-indexed");
    return classes;
  }

  private static void initTestEnvironmentAhead() {
    if (!Boolean.parseBoolean(System.getProperty("apksim.initAhead", "true"))) return;
    // Preload the framework's 17 MB resources.arsc on its own thread while the JVM initializes Robolectric + natives.
    String deps = System.getProperty("robolectric.dependency.dir");
    if (deps != null) {
      try (var stream = java.nio.file.Files.newDirectoryStream(Path.of(deps), "android-all-instrumented-*.jar")) {
        for (Path jar : stream) {
          org.robolectric.res.android.CppApkAssets.preload(jar, true);
          break;
        }
      } catch (Throwable ignored) {
        // Loaded on demand otherwise.
      }
    }
    // Split cold-boot prerequisites across parallel threads so BouncyCastle, Robolectric's Guice/SimRunner injector,
    // framework resources.arsc, and metadata tables initialize concurrently while Main handles window + APK setup.
    Thread bc = new Thread(() -> {
      try {
        Class.forName("org.robolectric.android.internal.AndroidTestEnvironment", true, ClassLoader.getSystemClassLoader());
        SimKeyStore.install();
      } catch (Throwable e) {
        // The main thread meets the same error when it gets there, and reports it.
      }
    }, "apksim-init-bc");
    bc.setDaemon(true);
    bc.start();

    if (System.getProperty("apksim.instrument") == null) {
      java.util.concurrent.CompletableFuture<org.junit.runner.Runner> rf = new java.util.concurrent.CompletableFuture<>();
      prebuiltHostRunner = rf;
      Thread rt = new Thread(() -> {
        try {
          rf.complete(Request.aClass(Host.class).getRunner());
        } catch (Throwable e) {
          rf.completeExceptionally(e);
        }
      }, "apksim-init-runner");
      rt.setDaemon(true);
      rt.start();
    } else {
      Thread rt = new Thread(() -> {
        try {
          Class.forName("dev.apksim.SimRunner", true, ClassLoader.getSystemClassLoader());
        } catch (Throwable ignored) {}
      }, "apksim-init-runner");
      rt.setDaemon(true);
      rt.start();
    }

    Thread meta = new Thread(() -> {
      try {
        CompatTable.preload();
        Gaps.preload();
        InstallState.frameworkProtection();
      } catch (Throwable ignored) {}
    }, "apksim-init-meta");
    meta.setDaemon(true);
    meta.start();
  }
}
