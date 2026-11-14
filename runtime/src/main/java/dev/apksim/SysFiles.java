package dev.apksim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hardware state files under /sys that an app reads itself (a camera privacy cover's {@code cam_privacy_state}), which the
 * host does not have: {@code --sysfs PATH=VALUE} (repeatable, -Dapksim.sysfs.N) gives a path contents before the app
 * starts, and the {@code sysfs PATH VALUE} command changes them while it runs. Each is a real file in this process's
 * scratch folder; the translator sends the path an app hands to {@code new File(String)}, {@code new FileInputStream(String)}
 * and {@code new FileReader(String)} through {@link #map}, so FileReader, FileInputStream, File.exists, Files.* on
 * {@code File.toPath()} and the rest see it. A value gets a trailing newline, as a sysfs node has. Only paths under
 * /sys/ are taken. The app sees the backing file's path (File.getPath, error texts), not the /sys one. Not covered: {@code Paths.get}/{@code Path.of} and {@code File(parent, child)}.
 */
public final class SysFiles {
  private static final Map<String, Path> FILES = new ConcurrentHashMap<>();
  private static volatile boolean any;
  private static int counter;

  private SysFiles() {}

  /** Called by translated app code in place of a path String: the backing file's path, else the path unchanged. */
  public static String map(String path) {
    if (!any || path == null || !path.startsWith("/sys/")) return path;
    Path p = FILES.get(path);
    if (p == null) {
      try {
        p = FILES.get(Path.of(path).normalize().toString());
      } catch (java.nio.file.InvalidPathException e) {
        return path;  // File(String) never throws on a device
      }
    }
    return p == null ? path : p.toString();
  }

  /** `--sysfs PATH=VALUE` seeds of the launch (-Dapksim.sysfs.N), put in place before Application.onCreate. */
  static void seed() {
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.sysfs." + i);
      if (s == null) break;
      int eq = s.indexOf('=');
      if (eq <= 0) throw new IllegalArgumentException("--sysfs " + s + ": expected PATH=VALUE");
      try {
        set(s.substring(0, eq), s.substring(eq + 1));
      } catch (IOException | IllegalArgumentException e) {
        System.err.println("--sysfs " + s.substring(0, eq) + ": " + e.getMessage());
        System.exit(2);
      }
    }
  }

  static synchronized void set(String path, String value) throws IOException {
    if (!path.startsWith("/sys/") || path.indexOf('\0') >= 0 || path.endsWith("/")) {
      throw new IllegalArgumentException("the path must be a file under /sys/");
    }
    String key = Path.of(path).normalize().toString();
    if (!key.startsWith("/sys/")) throw new IllegalArgumentException("the path must be a file under /sys/");
    Path dir = Storage.scratch().resolve("sysfs");
    Files.createDirectories(dir);
    Path file = FILES.get(key);
    if (file == null) file = dir.resolve("n" + counter++);
    Path tmp = dir.resolve(file.getFileName() + "." + Storage.tmpTag());
    Files.write(tmp, (value + "\n").getBytes(StandardCharsets.UTF_8));
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    FILES.put(key, file);
    any = true;
  }

  /** sysfs | sysfs PATH | sysfs PATH VALUE... | sysfs clear PATH|all (the control channel). */
  public static String command(String[] a) {
    try {
      if (a.length == 1) {
        if (FILES.isEmpty()) return "sysfs: none";
        StringBuilder sb = new StringBuilder();
        for (String k : new java.util.TreeSet<>(FILES.keySet())) sb.append(k).append('=').append(read(k)).append(", ");
        return sb.substring(0, sb.length() - 2);
      }
      if (a[1].equals("clear")) {
        if (a.length != 3) return "error: usage: sysfs clear PATH|all";
        if (a[2].equals("all")) {
          for (Path f : FILES.values()) Files.deleteIfExists(f);  // a File the app built earlier then finds nothing too
          FILES.clear();
        } else {
          Path f = FILES.remove(Path.of(a[2]).normalize().toString());
          if (f == null) return "error: sysfs: " + a[2] + " is not set";
          Files.deleteIfExists(f);
        }
        any = !FILES.isEmpty();
        return "ok";
      }
      if (a.length == 2) {
        Path p = FILES.get(Path.of(a[1]).normalize().toString());
        return p == null ? "error: sysfs: " + a[1] + " is not set" : read(a[1]);
      }
      set(a[1], String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
      return "ok";
    } catch (IllegalArgumentException | IOException e) {
      return "error: sysfs: " + e.getMessage();
    }
  }

  private static String read(String key) throws IOException {
    return Files.readString(FILES.get(Path.of(key).normalize().toString())).stripTrailing();
  }
}
