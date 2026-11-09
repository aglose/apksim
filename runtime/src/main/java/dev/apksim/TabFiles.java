package dev.apksim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;

/**
 * `devpush DEVICE_PATH BASE64` (`apksim serve` and `beam`, issue 187): a file a browser tab sent, written where
 * `adb push` could write it, and nowhere else. Not in the list of commands a tab may type: only the server sends it,
 * after it has read the upload itself, so the bytes arrive on the control line and no host path is ever named.
 */
final class TabFiles {
  private TabFiles() {}

  /** The folders of the app's data a tab may write into (the same set `adb push` reaches, less the rest of the data folder). */
  private static final List<String> DATA_DIRS = List.of("files", "cache", "shared_prefs", "databases");

  /** Writes the file; answers `ok DEVICE_PATH SIZE` or `error: why`. */
  static String push(String devicePath, String base64) {
    try {
      if (devicePath.indexOf('\0') >= 0) return "error: bad path";
      for (String seg : devicePath.split("/")) {
        if (seg.equals("..")) return "error: no .. in a path";
      }
      String dev = AdbShell.Paths.normalize(devicePath, "/data/data/" + AdbShell.pkg());  // relative: in the app's data
      String pkg = AdbShell.pkg();
      boolean ok = within(dev, "/data/local/tmp") || within(dev, "/sdcard") || within(dev, "/storage/emulated/0");
      for (String d : DATA_DIRS) {
        ok |= within(dev, "/data/data/" + pkg + "/" + d) || within(dev, "/data/user/0/" + pkg + "/" + d);
      }
      if (!ok) {
        return "error: only the app's files, cache, shared_prefs and databases folders, /sdcard and /data/local/tmp";
      }
      Path p = AdbShell.Paths.host(dev, "/");
      if (p == null) return "error: " + dev + " is not mapped";
      byte[] bytes = Base64.getDecoder().decode(base64);
      // Nothing may lead out of the folder it was named in. Checked before anything is created: the deepest folder
      // that exists must be inside the mount (a link the app made would otherwise get its new folders made outside),
      // and again after the folders are made.
      Path mount = mountOf(dev);
      // A mount the app made a link (its /sdcard, the scratch folder) is not a boundary: the link's target would be. The
      // app's data folder is the framework's own and may be reached through a link.
      if (mount != null && !dev.startsWith("/data/data/") && !dev.startsWith("/data/user/") && Files.isSymbolicLink(mount)) {
        return "error: a link leads out of " + dev;
      }
      Path realMount = mount == null ? null : mount.toRealPath();
      if (realMount != null) {
        Path up = p.getParent();
        while (up != null && !Files.exists(up, LinkOption.NOFOLLOW_LINKS)) up = up.getParent();
        if (up == null || !up.toRealPath().startsWith(realMount) || (!up.toRealPath().equals(realMount) && Files.isSymbolicLink(up))) return "error: a link leads out of " + dev;
      }
      Files.createDirectories(p.getParent());
      if (realMount != null && !p.getParent().toRealPath().startsWith(realMount)) return "error: a link leads out of " + dev;
      if (Files.isSymbolicLink(p) || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return "error: " + dev + " is a link or a folder";
      // The app may have it open: a new file moved into place leaves the one it holds as it was (as adb push does).
      // The temporary file is a fresh one of a random name, created exclusively (never a name the app could have linked).
      Path tmp = p.resolveSibling("." + java.util.UUID.randomUUID() + ".tabpush");
      try {
        Files.write(tmp, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(tmp);
      }
      return "ok " + dev + " " + bytes.length;
    } catch (IllegalArgumentException e) {
      return "error: bad data";
    } catch (IOException e) {
      return "error: " + e.getMessage();
    }
  }

  private static boolean within(String dev, String root) {
    return dev.startsWith(root + "/");
  }

  private static Path mountOf(String dev) throws IOException {
    String pkg = AdbShell.pkg();
    for (String root : List.of("/data/local/tmp", "/sdcard", "/storage/emulated/0", "/data/data/" + pkg, "/data/user/0/" + pkg)) {
      if (dev.equals(root) || dev.startsWith(root + "/")) return AdbShell.Paths.host(root, "/");
    }
    return null;
  }
}
