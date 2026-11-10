package dev.apksim;

import java.nio.charset.StandardCharsets;

/**
 * {@code --sdcard-file PATH=VALUE} (repeatable, -Dapksim.sdcard.N): a file written to /sdcard in the data slot before the
 * app's process starts any code ({@link SlotDevice#seedFiles}), for apps that read a config file once at
 * Application.onCreate. VALUE is inline, never a host file: {@code text:TEXT} as UTF-8, else base64. apksim serve and
 * beam check their launch options with {@link #parse} too.
 */
public final class SdcardSeeds {
  /** A file, and all of a launch's. */
  public static final int FILE_MAX = 64 * 1024;
  public static final int TOTAL_MAX = 256 * 1024;

  private SdcardSeeds() {}

  /** `path`: relative to /sdcard, validated. */
  public record File(String path, byte[] bytes) {}

  /**
   * The seed PATH=VALUE. PATH is under /sdcard (a leading /sdcard/ or /storage/emulated/0/ is allowed) with no empty,
   * "." or ".." parts and no NUL. Throws IllegalArgumentException with the reason when it is not valid.
   */
  public static File parse(String s) {
    int eq = s.indexOf('=');
    if (eq <= 0) throw new IllegalArgumentException("--sdcard-file " + abbreviate(s) + ": expected PATH=text:TEXT or PATH=BASE64");
    String path = s.substring(0, eq);
    String v = s.substring(eq + 1);
    for (String prefix : new String[] {"/sdcard/", "/storage/emulated/0/"}) {
      if (path.startsWith(prefix)) path = path.substring(prefix.length());
    }
    if (path.startsWith("/") || path.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("--sdcard-file " + abbreviate(path) + ": the path must be under /sdcard");
    }
    for (String seg : path.split("/", -1)) {
      if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
        throw new IllegalArgumentException("--sdcard-file " + abbreviate(path) + ": no empty, . or .. parts in a path");
      }
    }
    byte[] bytes;
    if (v.startsWith("text:")) {
      bytes = v.substring(5).getBytes(StandardCharsets.UTF_8);
    } else {
      try {
        bytes = java.util.Base64.getDecoder().decode(v);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("--sdcard-file " + abbreviate(path) + ": the value is neither text:TEXT nor base64");
      }
    }
    if (bytes.length > FILE_MAX) {
      throw new IllegalArgumentException("--sdcard-file " + abbreviate(path) + ": over " + FILE_MAX / 1024 + " KB");
    }
    return new File(path, bytes);
  }

  private static String abbreviate(String s) {
    return s.length() > 80 ? s.substring(0, 80) + "..." : s;
  }
}
