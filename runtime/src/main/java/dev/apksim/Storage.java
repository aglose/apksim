package dev.apksim;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where an instance keeps its files. App data survives restarts, like on a device; the first running
 * instance of an app gets its normal data, and further concurrent instances get their own. Everything
 * else is scratch, removed on exit and swept on the next launch if a process was killed.
 */
final class Storage {
  private static FileLock held; // Kept for the life of the process.
  private static Path scratch;

  private Storage() {}

  /** The launcher points java.io.tmpdir at work/tmp/PID, since the JDK reads it only at startup. */
  static void initScratch() throws IOException {
    scratch = Path.of(System.getProperty("java.io.tmpdir"));
    sweepDeadInstances(scratch.getParent());
  }

  private static void sweepDeadInstances(Path base) throws IOException {
    try (Stream<Path> dirs = Files.list(base)) {
      dirs.forEach(d -> {
        try {
          long pid = Long.parseLong(d.getFileName().toString());
          if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return;
        } catch (NumberFormatException e) {
          return; // Not one of ours.
        }
        deleteTree(d);
      });
    }
  }

  static void deleteScratch() {
    if (scratch != null) deleteTree(scratch);
  }

  static void deleteTree(Path dir) {
    // Walking does not follow symlinks, so the link to the persistent data dir is removed, not its target.
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          // Best effort: the next launch sweeps it again.
        }
      });
    } catch (IOException e) {
      // Same.
    }
  }

  /** The persistent data directory for this instance. */
  static Path dataDir(Path root, String pkg) throws IOException {
    for (int slot = 0; ; slot++) {
      Path dir = root.resolve("data").resolve(pkg).resolve(slot == 0 ? "default" : "instance-" + slot);
      Files.createDirectories(dir);
      FileChannel lock = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock l = lock.tryLock();
      if (l != null) {
        held = l;
        return dir;
      }
      lock.close();
    }
  }
}
