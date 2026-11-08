package dev.apksim;

import java.nio.file.Path;
import java.util.List;

/**
 * A beamed instance (`apksim beam`, docs/beam.md "The beam sandbox"): an APK a web site sent, run in a sandbox of its
 * own (the launcher's profile for standbys named with `b`). It may write only its own scratch (`STATE/tmp/<pid>`), its
 * `beam-N` data slot and its own out folder (`STATE/beam/out/<pid>`), so the caches every other launch shares are not
 * its to write: a translated class or an extracted `.so` it could plant there would run in another app. Its APK cache
 * entry and translated classes start from the agent's cache for its build (`-Dapksim.beamBuildCache`, which it may
 * read but not write) and are written to its out folder, which the agent moves into that build's cache once the
 * instance has exited: an app can reach only its own.
 */
final class BeamSandbox {
  static final boolean ON = Boolean.getBoolean("apksim.beamSandbox");

  private BeamSandbox() {}

  private static Path state() {
    return Path.of(System.getProperty("apksim.cacheRoot")).getParent();
  }

  private static String pid() {
    return Long.toString(ProcessHandle.current().pid());
  }

  /**
   * The APK cache's root: this process's out folder (`STATE/beam/out/<pid>/apk-cache`), started from a clone of the
   * build's entry the agent kept (APFS clones cost nothing), so the manifest and native libraries are not extracted
   * again every launch (113 ms of the test app's 390). The agent moves it back once the instance has exited.
   */
  static Path apkCacheRoot() {
    return out().resolve("apk-cache");
  }

  /** Where this process saves translated classes: its out folder, which the agent reads after it exits. */
  static Path classCacheRoot() {
    return out().resolve("classes");
  }

  private static Path out() {
    return state().resolve("beam").resolve("out").resolve(pid());
  }

  /** The agent's cache for this build (`-Dapksim.beamBuildCache`, per launch), read only, or null. */
  private static Path buildCache() {
    String s = System.getProperty("apksim.beamBuildCache");
    return s == null || s.isEmpty() ? null : Path.of(s);
  }

  /** The build's translated classes, read only, or null. */
  static Path classCacheSeed() {
    Path b = buildCache();
    return b == null ? null : b.resolve("classes");
  }

  /** Before the APK cache entry is taken: a clone of the one the agent kept for this build, if any. */
  static void seedApkCache(Path root) {
    Path b = buildCache();
    if (b == null || !java.nio.file.Files.isDirectory(b.resolve("apk"))) return;
    try {
      java.nio.file.Files.createDirectories(root);
      // cp -c clones (copy on write) on APFS, --reflink=auto where GNU cp's file system can; -R copies links as links,
      // never what they point to.
      String flags = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac") ? "-cR" : "-R";
      List<String> cmd = new java.util.ArrayList<>(List.of("cp", flags));
      if (!flags.equals("-cR")) cmd.add("--reflink=auto");
      cmd.addAll(List.of(b.resolve("apk") + "/.", root + "/"));
      Process p = new ProcessBuilder(cmd)
          .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
    } catch (java.io.IOException e) {
      System.err.println("[apksim] beam: no APK cache entry kept for this build: " + e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
