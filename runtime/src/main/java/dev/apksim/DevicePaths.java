package dev.apksim;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * External storage at a device's path: the app is told /storage/emulated/0 (Environment, the app's external folders),
 * and native/devicepaths.c, in front of libc in an instance's JVM (the launcher's DYLD_INSERT_LIBRARIES or LD_PRELOAD),
 * sends every file call on that path, or /sdcard, to the data slot's sdcard folder on the host. Before, the app was
 * told that host folder (/Users/NAME/.apksim/cache/data/PKG/crawl-0.device/sdcard), and showed it: a "data folder"
 * setting differed between instances and named the host's user.
 *
 * <p>Only when the library is in the process and a marker written to the slot's folder is found at the mapped path:
 * otherwise (an older launcher, another way of starting the JVM, {@code APKSIM_DEVICE_PATHS=0}, which launches cold,
 * {@code -Dapksim.devicePaths=false}) the app is told the host folder, as before, with a line on stderr unless it was
 * asked for. apksim's own code (adb sync, a tab's file pushes, seeds) keeps using host paths.
 */
public final class DevicePaths {
  /** What the app sees as its external storage. */
  public static final String ROOT = "/storage/emulated/0";

  private DevicePaths() {}

  private static native boolean nativePreloaded();

  private static native boolean nativeMap(String folder, String skeleton);

  /**
   * Maps the device prefixes to [sdcard] (the slot's sdcard folder, which exists) for this process, and the rest of
   * /storage to a skeleton in the scratch folder (storage/emulated/0 and storage/self/primary, empty), so its ancestors
   * exist as on a device. True when the app may be told {@link #ROOT}: file calls on it now reach [sdcard]. When the
   * mapping was wanted and does not come up, one stderr line says why: the app then sees the host folder.
   */
  static synchronized boolean map(Path sdcard) {
    if ("false".equals(System.getProperty("apksim.devicePaths")) || "0".equals(System.getenv("APKSIM_DEVICE_PATHS"))) {
      return false;
    }
    String skeleton = null;
    try {
      Path root = Storage.scratch().resolve("device-root");
      Files.createDirectories(root.resolve("storage/emulated/0"));
      Files.createDirectories(root.resolve("storage/self/primary"));
      skeleton = root.toAbsolutePath().toString();
    } catch (Exception e) {
      // no ancestors: /storage and /storage/emulated do not exist, the rest works
    }
    try {
      System.load(HostOs.runtimeLib("libdevicepaths").toString());
      if (!nativePreloaded()) return off("the launcher did not put libdevicepaths in front of libc");
      if (!nativeMap(sdcard.toAbsolutePath().toString(), skeleton)) return off("the slot's folder could not be named");
    } catch (Throwable e) {
      return off("no libdevicepaths (" + e.getMessage() + "): run scripts/build-native.sh");
    }
    // The library rewrites calls only if the process started with it: a probe through the JDK's own file calls, with
    // a marker written through the host path, so a /storage/emulated/0 that happens to exist on the host is not taken.
    Path marker = sdcard.resolve(".apksim-device-paths-" + ProcessHandle.current().pid());
    try {
      Files.writeString(marker, "");
      boolean seen = new File(ROOT, marker.getFileName().toString()).exists();
      Files.deleteIfExists(marker);
      if (seen) return true;
    } catch (Exception e) {
      // not seen
    }
    try {
      nativeMap(null, null);
    } catch (Throwable ignored) {
      // nothing mapped either way
    }
    return off("file calls on " + ROOT + " did not reach the slot (dyld or ld.so ignored the library?)");
  }

  private static boolean off(String why) {
    System.err.println("[apksim] device paths off: " + why + "; the app sees its external storage at the host folder");
    return false;
  }
}
