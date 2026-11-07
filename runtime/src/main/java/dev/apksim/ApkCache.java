package dev.apksim;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The per-APK folder in `STATE/cache` (the manifest, extracted `.so` files, inflated DEX files), and keeping that
 * folder bounded (issue 125).
 *
 * <p>An entry is `<file name>-<content key>`: 16 hex digits of CRC32 and Adler32 over the APK's ZIP central directory (every
 * entry's name, CRC and sizes) and its length, so an APK downloaded, copied or touched again reuses its entry. It used to
 * be keyed by the file's mtime, and every CI run or rebuild left another entry (a large app's: ~370 MB) for good.
 *
 * <p>Each launch takes its entry while holding a shared lock on `cache/apk-cache.lock`, then holds a shared lock on
 * `<entry>/in-use.lock` for as long as the process lives (released by the OS when it dies, in a container on a
 * shared volume too) and touches `<entry>/last-used`. Eviction runs on a background thread, under the global lock
 * held exclusively: an entry is a candidate when it is not locked by any process and was not used in the last 10
 * minutes; candidates unused for 14 days go, then the least recently used until the entries fit in
 * `-Dapksim.apkCacheMB` (default 2048). An evicted entry is renamed to `.evict-...` under the lock and deleted after
 * it, so a launch never waits for a deletion and never finds a half-deleted entry.
 */
final class ApkCache {
  static final String LOCK = "apk-cache.lock";
  private static final String IN_USE = "in-use.lock";
  private static final String LAST_USED = "last-used";
  private static final long RECENT_MS = 10 * 60_000L;
  private static final long STALE_MS = 14 * 24 * 3600_000L;
  /** The whole process's hold on its entry: never closed, the OS releases it at exit. */
  private static FileChannel held;
  private static FileLock heldLock;

  private ApkCache() {}

  /** {@link #take} on a thread of its own, for Main to join when it needs the entry. */
  static final class Taking extends Thread {
    private final Path root;
    private final Path apk;
    private Path entry;
    private Exception failure;

    Taking(Path root, Path apk) {
      super("apksim-apk-cache");
      this.root = root;
      this.apk = apk;
      setDaemon(true);
    }

    @Override
    public void run() {
      try {
        entry = take(root, apk);
      } catch (Exception e) {
        failure = e;
      }
    }

    Path await() throws Exception {
      join();
      if (failure != null) throw failure;
      return entry;
    }
  }

  static Taking start(Path root, Path apk) {
    Taking t = new Taking(root, apk);
    t.start();
    return t;
  }

  /** The entry for this APK, created and marked in use by this process; eviction then runs in the background. */
  static synchronized Path take(Path root, Path apk) throws IOException {
    Path entry = root.resolve(apk.getFileName() + "-" + key(apk));
    Files.createDirectories(root);
    try (FileChannel global = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
         FileLock g = global.lock(0, Long.MAX_VALUE, true)) {
      Files.createDirectories(entry);
      FileChannel ch = FileChannel.open(entry.resolve(IN_USE), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
      heldLock = ch.lock(0, Long.MAX_VALUE, true);
      held = ch;
      touch(entry.resolve(LAST_USED));
    }
    Runnable evict = () -> {
      try {
        evict(root, entry);
      } catch (Throwable t) {
        System.err.println("[apksim] apk cache eviction: " + t);
      }
    };
    if (Boolean.getBoolean("apksim.apkCacheSync")) {
      evict.run();  // scripts/apk-cache-check.sh: deterministic, before the run goes on
    } else {
      // After startup: listing and sizing the entries must not compete with the launch.
      Thread t = new Thread(() -> {
        try {
          Thread.sleep(Long.getLong("apksim.apkCacheEvictDelay", 3000));
        } catch (InterruptedException e) {
          return;
        }
        evict.run();
      }, "apksim-apk-cache-evict");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
    }
    return entry;
  }

  /** CRCs of the central directory and the file's length; for a file it cannot read as a ZIP, of its length and mtime. */
  static String key(Path apk) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(apk.toFile(), "r")) {
      long len = f.length();
      // CRC32 and Adler32, both native: SHA-256 (MessageDigest's provider lookup) took ~60 ms of a cold JVM, CRC32C (a Java loop, interpreted in a fresh instance) ~3 ms of a warm launch.
      java.util.zip.CRC32 crc = new java.util.zip.CRC32();
      java.util.zip.Adler32 crcc = new java.util.zip.Adler32();
      byte[] head = ByteBuffer.allocate(8).putLong(len).array();
      crc.update(head);
      crcc.update(head);
      byte[] cd = centralDirectory(f, len);
      if (cd == null) cd = ByteBuffer.allocate(8).putLong(apk.toFile().lastModified()).array();
      crc.update(cd);
      crcc.update(cd);
      // Not String.format: Formatter is slow while interpreted, as it is in a fresh instance.
      return Long.toHexString(1L << 32 | crc.getValue()).substring(1) + Long.toHexString(1L << 32 | crcc.getValue()).substring(1);
    }
  }

  private static byte[] centralDirectory(RandomAccessFile f, long len) throws IOException {
    byte[] cd = centralDirectory(f, len, 22);  // no archive comment: an APK's end record is the file's last 22 bytes
    return cd != null ? cd : centralDirectory(f, len, (int) Math.min(len, 65_535 + 22));
  }

  private static byte[] centralDirectory(RandomAccessFile f, long len, int tail) throws IOException {
    if (tail < 22) return null;
    byte[] end = new byte[tail];
    f.seek(len - tail);
    f.readFully(end);
    ByteBuffer b = ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = tail - 22; i >= 0; i--) {
      if (b.getInt(i) != 0x06054b50) continue;
      long size = Integer.toUnsignedLong(b.getInt(i + 12));
      long offset = Integer.toUnsignedLong(b.getInt(i + 16));
      if (size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL || offset + size > len || size > Integer.MAX_VALUE) return null;
      byte[] cd = new byte[(int) size];
      f.seek(offset);
      f.readFully(cd);
      return cd;
    }
    return null;
  }

  private record Entry(Path dir, long lastUsed, long bytes) {}

  static void evict(Path root, Path current) throws IOException {
    long capBytes = Long.getLong("apksim.apkCacheMB", 2048) * 1024 * 1024;
    long now = System.currentTimeMillis();
    List<Path> doomed = new ArrayList<>();
    try (FileChannel global = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
         FileLock g = global.lock()) {
      List<Entry> entries = new ArrayList<>();
      try (Stream<Path> dirs = Files.list(root)) {
        for (Path d : (Iterable<Path>) dirs::iterator) {
          String name = d.getFileName().toString();
          if (name.startsWith(".evict-")) {
            doomed.add(d);  // left by a process that ended mid-deletion
            continue;
          }
          // An entry has a manifest; classes/, data/, test-outputs/ and the lock file do not.
          if (!Files.isRegularFile(d.resolve("AndroidManifest.xml"))) continue;
          Path marker = Files.exists(d.resolve(LAST_USED)) ? d.resolve(LAST_USED) : d.resolve("AndroidManifest.xml");
          entries.add(new Entry(d, Files.getLastModifiedTime(marker).toMillis(), size(d)));
        }
      }
      entries.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
      long total = 0;
      for (Entry e : entries) total += e.bytes;
      for (Entry e : entries) {
        boolean stale = now - e.lastUsed > STALE_MS;
        if (!stale && total <= capBytes) break;
        if (e.dir.equals(current) || now - e.lastUsed < RECENT_MS || inUse(e.dir)) continue;
        Path to = root.resolve(".evict-" + e.dir.getFileName() + Storage.tmpTag());
        try {
          Files.move(e.dir, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
          continue;
        }
        doomed.add(to);
        total -= e.bytes;
      }
    }
    for (Path d : doomed) Storage.deleteTree(d);
  }

  /** Whether a process holds the entry's lock: a dead one's lock is gone with it. */
  private static boolean inUse(Path entry) {
    Path f = entry.resolve(IN_USE);
    if (!Files.exists(f)) return false;
    try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock l = ch.tryLock();
      if (l == null) return true;
      l.release();
      return false;
    } catch (OverlappingFileLockException e) {
      return true;  // this process holds it
    } catch (IOException e) {
      return true;  // cannot tell: keep it
    }
  }

  private static long size(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
    } catch (IOException e) {
      return 0;
    }
  }

  private static void touch(Path file) throws IOException {
    if (!Files.exists(file)) Files.createFile(file);
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
  }
}
