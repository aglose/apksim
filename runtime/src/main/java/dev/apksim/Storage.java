package dev.apksim;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where an instance keeps its files. App data survives restarts, like on a device; the first running
 * instance of an app gets its normal data, and further concurrent instances get their own. Everything
 * else is scratch, removed on exit and swept on the next launch if a process was killed.
 */
final class Storage {
  private static FileLock held; // Kept for the life of the process.
  private static volatile String heldSlot;

  /** The name of the data slot this process holds (default, instance-1, serve-0...), or null before one is taken. */
  static String heldSlot() {
    return heldSlot;
  }
  private static Path scratch;

  private Storage() {}

  /** The launcher points java.io.tmpdir at work/tmp/PID, since the JDK reads it only at startup. */
  static void initScratch() throws IOException {
    if (scratch != null) return;  // a standby did it before it waited
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

  /**
   * A path from a command, against the launch's working directory. Not Path.toAbsolutePath: the JDK fixes that at
   * JVM start, and an instance from a standby (Standby) started elsewhere and moved to the launch's directory.
   */
  static Path absolute(String path) {
    return Path.of(System.getProperty("user.dir")).resolve(path);
  }

  /**
   * A suffix for a file written beside its final name and renamed into place. Not the PID alone: processes in separate
   * containers sharing one cache volume (docker run per test) often have the same PID.
   */
  static String tmpTag() {
    return "." + ProcessHandle.current().pid() + "-" + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()) + ".tmp";
  }

  /** Empties a data directory this instance holds (Storage.dataDir), keeping its lock. */
  static void clearDataDir(Path dir) throws IOException {
    // Not deleteTree's best effort: a launch that asked for fresh data must not go on with some of the old.
    List<Path> paths;
    try (Stream<Path> all = Files.walk(dir)) {
      paths = all.filter(p -> !p.equals(dir) && !p.equals(dir.resolve(".lock"))).sorted(Comparator.reverseOrder()).toList();
    }
    for (Path p : paths) Files.delete(p);  // walking does not follow links, so a link is removed, not its target
  }

  /**
   * The persistent data directory for this instance: the first free of default, instance-1, ... or, with
   * {@code --data-slots NAME} (apksim serve), of NAME-0, NAME-1, ..., which an ordinary launch never takes, so a
   * served session's --fresh never empties the owner's own data.
   */
  static Path dataDir(Path root, String pkg) throws IOException {
    String name = System.getProperty("apksim.dataSlots");
    if (name != null && !name.matches("[a-z][a-z0-9]{0,15}")) throw new IOException("bad --data-slots name: " + name);
    for (int slot = 0; ; slot++) {
      String slotName = name != null ? name + "-" + slot : slot == 0 ? "default" : "instance-" + slot;
      Path dir = root.resolve("data").resolve(pkg).resolve(slotName);
      Files.createDirectories(dir);
      FileChannel lock = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock l = lock.tryLock();
      if (l != null) {
        held = l;
        heldSlot = slotName;
        return dir;
      }
      lock.close();
    }
  }
}
