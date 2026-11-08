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
 * shared volume too) and touches `<entry>/last-used`. A sweep runs at most once an hour across processes
 * (`cache/apk-cache.swept`), 3 s after startup on a background thread, or on the way out if the launch ends sooner
 * (`beforeExit`, from Standby.halt, before the launcher's client is told the exit code: once told, the next launch may
 * start while this process still holds its data slot). It lists and sizes the
 * entries without a lock: an entry is a candidate when it is not this process's and was not used in the last 10
 * minutes; those unused for 14 days go, then the least recently used until the rest fit in `-Dapksim.apkCacheMB`
 * (default 2048). Under the global lock held exclusively each candidate is checked again (used since, or locked by a
 * process: it stays) and moved into `cache/.apk-cache-trash/`, which is emptied after the lock is released, so a
 * launch never waits for a deletion and never finds a half-deleted entry. An entry from an apksim before content keys
 * (no `in-use.lock`, judged by its manifest's mtime) may be in use with nothing to show it, so it goes only once
 * unused for 14 days, never for the cap.
 */
public final class ApkCache {
  /**
   * The package an APK's manifest names (beam's moved data must be the build's: docs/beam.md phase 5). The APK is a
   * site's, read by the agent outside any sandbox: the manifest at most 1 MB (real ones are tens of KB), and only its
   * first element decoded (Axml, bounded by its input), its package attribute read from that text.
   */
  public static String manifestPackage(Path apk) throws IOException {
    byte[] raw;
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk.toFile())) {
      java.util.zip.ZipEntry e = zip.getEntry("AndroidManifest.xml");
      if (e == null) throw new IOException("the APK has no manifest");
      try (java.io.InputStream in = zip.getInputStream(e)) {
        raw = in.readNBytes((1 << 20) + 1);
      }
    }
    if (raw.length > 1 << 20) throw new IOException("the APK's manifest is over 1 MB");
    String first;
    try {
      first = Axml.decode(raw, 1);  // the manifest element alone: its package is all this needs
    } catch (RuntimeException e) {
      throw new IOException("the APK's manifest could not be read: " + e.getMessage());
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<manifest\\b[^>]*?\\spackage=\"([^\"]*)\"").matcher(first);
    if (!m.find() || !m.group(1).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
      throw new IOException("the manifest names no package");
    }
    return m.group(1);
  }


  static final String LOCK = "apk-cache.lock";
  private static final String IN_USE = "in-use.lock";
  private static final String LAST_USED = "last-used";
  private static final long RECENT_MS = 10 * 60_000L;
  private static final long STALE_MS = 14 * 24 * 3600_000L;
  private static final long SWEEP_EVERY_MS = 3600_000L;
  static final String SWEPT = "apk-cache.swept";
  /** Evicted entries are moved in here, then deleted: no entry can have this name (an entry's ends in -16 hex). */
  static final String TRASH = ".apk-cache-trash";
  private static final Object WAKE = new Object();
  private static volatile boolean exiting;
  private static volatile Thread sweeper;
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
    if (BeamSandbox.ON) BeamSandbox.seedApkCache(root);
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
    // A beamed instance's entry is in its own scratch, and every other is out of its reach (BeamSandbox).
    if (BeamSandbox.ON) return entry;
    // After startup: listing and sizing the entries must not compete with the launch. A launch that ends sooner runs
    // it on the way out (beforeExit), as Standby.halt's Runtime.halt would end this thread.
    Thread t = new Thread(() -> {
      synchronized (WAKE) {
        long until = System.currentTimeMillis() + 3000;
        for (long left; !exiting && (left = until - System.currentTimeMillis()) > 0; ) {
          try {
            WAKE.wait(left);
          } catch (InterruptedException e) {
            return;
          }
        }
      }
      try {
        sweep(root, entry);
      } catch (Throwable e) {
        System.err.println("[apksim] apk cache eviction: " + e);
      }
    }, "apksim-apk-cache-evict");
    t.setDaemon(true);
    t.setPriority(Thread.MIN_PRIORITY);
    t.start();
    sweeper = t;
    return entry;
  }

  /** Lets a sweep not yet run (at most once an hour) finish before the process halts: up to 10 s. */
  static void beforeExit() {
    Thread t = sweeper;
    if (t == null || !t.isAlive()) return;
    synchronized (WAKE) {
      exiting = true;
      WAKE.notifyAll();
    }
    try {
      t.join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** At most once an hour across processes (`cache/apk-cache.swept`'s mtime, read and set under the global lock), then {@link #evict}. */
  private static void sweep(Path root, Path current) throws IOException {
    Path swept = root.resolve(SWEPT);
    try (FileChannel global = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
         FileLock g = global.lock()) {
      if (Files.exists(swept) && System.currentTimeMillis() - Files.getLastModifiedTime(swept).toMillis() < SWEEP_EVERY_MS) return;
      touch(swept);
    }
    evict(root, current);
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
    Path trash = root.resolve(TRASH);
    // Listed and sized without the lock (a launch's take needs it shared); each one is checked again under it.
    List<Entry> entries = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path d : (Iterable<Path>) dirs::iterator) {
        // An entry has a manifest; classes/, data/, test-outputs/, the trash and the lock file do not.
        if (!Files.isRegularFile(d.resolve("AndroidManifest.xml"))) continue;
        try {
          entries.add(new Entry(d, lastUsed(d), size(d)));
        } catch (IOException | java.io.UncheckedIOException e) {
          // Moved or changing meanwhile (another launch's temp + rename): left for the next sweep.
        }
      }
    }
    entries.sort((x, y) -> Long.compare(x.lastUsed, y.lastUsed));
    long total = 0;
    for (Entry e : entries) total += e.bytes;
    if (total > capBytes || entries.stream().anyMatch(e -> now - e.lastUsed > STALE_MS)) {
      Files.createDirectories(trash);
      try (FileChannel global = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
           FileLock g = global.lock()) {
        for (Entry e : entries) {
          boolean stale = now - e.lastUsed > STALE_MS;
          if (!stale && total <= capBytes) break;
          // This process's, used since it was listed (a launch took it), or held by a process: it stays, and the
          // next one in line is considered instead.
          try {
            if (e.dir.equals(current) || !Files.isDirectory(e.dir) || System.currentTimeMillis() - lastUsed(e.dir) < RECENT_MS
                || inUse(e.dir)) continue;
            // From an apksim before content keys (no lock file): one may be running it now, and nothing shows that,
            // so it goes only once 14 days unused, never for the cap.
            if (!stale && !Files.exists(e.dir.resolve(IN_USE))) continue;
            Files.move(e.dir, trash.resolve(e.dir.getFileName() + Storage.tmpTag()), StandardCopyOption.ATOMIC_MOVE);
            total -= e.bytes;
          } catch (IOException | java.io.UncheckedIOException ex) {
            // Next sweep.
          }
        }
      }
    }
    // What this or an earlier sweep (one that ended mid-deletion) moved there.
    if (Files.isDirectory(trash)) {
      try (Stream<Path> dirs = Files.list(trash)) {
        for (Path d : (Iterable<Path>) dirs::iterator) {
          try {
            Storage.deleteTree(d);
          } catch (java.io.UncheckedIOException e) {
            // Next sweep.
          }
        }
      }
    }
  }

  /**
   * Removes the entries of one APK file name (`<file name>-<16 hex>`), as evict does: under the global lock held
   * exclusively, an entry a process holds stays; the rest move into the trash, emptied after the lock. For `apksim
   * beam` (docs/beam.md), whose builds are stored by content hash, so only that build ever used such an entry. Whether
   * every one went (none left, or none was there).
   */
  public static boolean remove(Path root, String apkFileName) throws IOException {
    if (!Files.isDirectory(root)) return true;
    List<Path> mine = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path d : (Iterable<Path>) dirs::iterator) {
        String n = d.getFileName().toString();
        if (n.length() == apkFileName.length() + 17 && n.startsWith(apkFileName + "-")
            && n.substring(apkFileName.length() + 1).matches("[0-9a-f]{16}") && Files.isDirectory(d)) mine.add(d);
      }
    }
    if (mine.isEmpty()) return true;
    boolean all = true;
    Path trash = root.resolve(TRASH);
    Files.createDirectories(trash);
    try (FileChannel global = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
         FileLock g = global.lock()) {
      for (Path d : mine) {
        if (inUse(d)) {
          all = false;
          continue;
        }
        Files.move(d, trash.resolve(d.getFileName() + Storage.tmpTag()), StandardCopyOption.ATOMIC_MOVE);
      }
    }
    try (Stream<Path> dirs = Files.list(trash)) {
      for (Path d : (Iterable<Path>) dirs::iterator) {
        try {
          Storage.deleteTree(d);
        } catch (java.io.UncheckedIOException e) {
          // the next sweep
        }
      }
    }
    return all;
  }

  private static long lastUsed(Path entry) throws IOException {
    Path marker = Files.exists(entry.resolve(LAST_USED)) ? entry.resolve(LAST_USED) : entry.resolve("AndroidManifest.xml");
    return Files.getLastModifiedTime(marker).toMillis();
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
    }  // an UncheckedIOException (a file renamed during the walk) goes to evict's listing, which skips the entry
  }

  private static void touch(Path file) throws IOException {
    // CREATE, not exists-then-createFile: processes starting together take one new entry at once.
    Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).close();
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
  }
}
