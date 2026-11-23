package dev.apksim;

import java.io.File;
import java.nio.file.Path;

/**
 * External storage at a device's path: the app is told /storage/emulated/0 (Environment, the app's external folders),
 * and native/devicepaths.c, in front of libc in every apksim JVM (the launcher's DYLD_INSERT_LIBRARIES or LD_PRELOAD),
 * sends every file call on that path, or /sdcard, to the data slot's sdcard folder on the host. Before, the app was
 * told that host folder (/Users/NAME/.apksim/cache/data/PKG/crawl-0.device/sdcard), and showed it: a "data folder"
 * setting differed between instances and named the host's user.
 *
 * <p>Only when the library is in the process and a probe of the mapped path finds the folder: otherwise (an older
 * launcher, another way of starting the JVM, {@code APKSIM_DEVICE_PATHS=0}, {@code -Dapksim.devicePaths=false}) the app
 * is told the host folder, as before. apksim's own code (adb sync, a tab's file pushes, seeds) keeps using host paths.
 */
public final class DevicePaths {
  /** What the app sees as its external storage. */
  public static final String ROOT = "/storage/emulated/0";

  private static volatile boolean on;

  private DevicePaths() {}

  private static native boolean nativePreloaded();

  private static native boolean nativeMap(String folder);

  /**
   * Maps the device prefixes to [sdcard] (the slot's sdcard folder, which exists) for this process. True when the app
   * may be told {@link #ROOT}: file calls on it now reach [sdcard].
   */
  static synchronized boolean map(Path sdcard) {
    on = false;
    if ("false".equals(System.getProperty("apksim.devicePaths")) || "0".equals(System.getenv("APKSIM_DEVICE_PATHS"))) {
      return false;
    }
    try {
      System.load(HostOs.runtimeLib("libdevicepaths").toString());
      if (!nativePreloaded() || !nativeMap(sdcard.toAbsolutePath().toString())) return false;
    } catch (Throwable e) {
      return false;  // no library (an older runtime folder): the host path, as before
    }
    // The library is loaded early only if the process started with it: a probe through the JDK's own file calls.
    if (!new File(ROOT).isDirectory()) {
      try {
        nativeMap(null);
      } catch (Throwable ignored) {
        // nothing mapped either way
      }
      return false;
    }
    on = true;
    return true;
  }

  /** Whether the app sees {@link #ROOT} for external storage in this process. */
  public static boolean on() {
    return on;
  }

  /** [p] as the app sees it: a path in [sdcard] under {@link #ROOT} while mapped, else unchanged. */
  public static String forApp(Path sdcard, Path p) {
    if (!on) return p.toString();
    Path rel = sdcard.toAbsolutePath().relativize(p.toAbsolutePath());
    return rel.toString().isEmpty() ? ROOT : ROOT + "/" + rel;
  }
}
