package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kept sessions on `apksim serve` (issue 221, docs/serve.md "Kept sessions"): a session started with `keep=KEY` leaves
 * its data slot and `.device` folder (app data, /sdcard, Settings) here when it ends, packed as a hand-off is
 * ({@link SlotPack}), and a later session with the same key and an APK of the same package starts on it.
 *
 * <p>On disk: `STATE/serve/kept/<id>/<package>/data-<16 hex>.zip` and `meta.properties` (which pack is current, the
 * build's SHA-256, its signers, when it was saved and last used). A save writes a new pack beside the current one and
 * then replaces the metadata naming it, in one rename: until then the previous pack and its metadata stand, whatever
 * stops the save. `id` is an HMAC of the key under the server's token, so a key reaches only what
 * the same token kept, and the key itself is stored nowhere. One session at a time per id ({@link #take}); `--keep-max`
 * packages kept in all (least recently used go first) and `--keep-days` unused bound the disk.
 */
final class KeptStore {
  /** Starting with a letter or digit: `.` and `..` would be normalized out of `/api/kept/KEY` by browsers and clients. */
  static final String KEY = "[A-Za-z0-9][A-Za-z0-9._~-]{0,127}";
  private static final String PACKAGE = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";

  final Path dir;
  final int max;
  final double days;
  private final byte[] secret;
  private final Set<String> inUse = ConcurrentHashMap.newKeySet();

  /** What was kept for a package: its pack, the build that wrote it and that build's signers. */
  record Found(Path zip, String sha256, List<String> signers, long saved, long used, long size) {}

  KeptStore(Path dir, String token, int max, double days) {
    this.dir = dir;
    this.max = max;
    this.days = days;
    // No token (loopback only): every client is one scope, as every client may do anything there anyway.
    this.secret = ("apksim-kept\0" + (token == null ? "" : token)).getBytes(StandardCharsets.UTF_8);
  }

  /** Held while this server keeps sessions: one server per state folder owns the store (claim). */
  private java.nio.channels.FileChannel lockChannel;
  private volatile boolean owned;

  boolean on() {
    return max > 0 && owned;
  }

  /**
   * Takes the store for this server (an exclusive lock on `kept/.lock`, held for the process's life), once it has bound
   * its port. Another server on the same state folder keeps nothing meanwhile: two would reserve the same key in two
   * processes, and each would sweep what the other uses. With `--keep-max 0` it is never taken, so such a server
   * neither keeps nor deletes anything.
   */
  boolean claim() {
    if (max <= 0) return false;
    try {
      Files.createDirectories(dir);
      java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.resolve(".lock"),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
      java.nio.channels.FileLock l = ch.tryLock();
      if (l == null) {
        ch.close();
        return false;
      }
      lockChannel = ch;
      owned = true;
      return true;
    } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
      return false;
    }
  }

  boolean owned() {
    return owned;
  }

  static boolean validKey(String key) {
    return key != null && key.matches(KEY);
  }

  /** The store's name for a key under this server's token (32 hex digits). */
  String id(String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(key.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Takes the key for one session; false when another session has it. On the store's monitor, as forget and sweep
   * decide on it: a DELETE or an expiry that saw the key free finishes before a session can take it.
   */
  synchronized boolean take(String id) {
    return inUse.add(id);
  }

  boolean busy(String id) {
    return inUse.contains(id);
  }

  /** Gives the key back, then sweeps: a save swept while the key was held, so its other packages counted but could not go. */
  void release(String id) {
    if (id == null) return;
    synchronized (this) {
      inUse.remove(id);
    }
    if (owned && max > 0) Thread.ofVirtual().name("serve-kept-sweep").start(this::sweep);
  }

  private Path entry(String id, String pkg) throws IOException {
    if (!id.matches("[0-9a-f]{32}") || pkg == null || !pkg.matches(PACKAGE) || pkg.length() > 255) throw new IOException("bad kept entry");
    return dir.resolve(id).resolve(pkg);
  }

  private static final String PACK = "data-[0-9a-f]{16}\\.zip";

  /** The pack the metadata names, or null. */
  private static Path current(Path e, Properties p) {
    String name = p.getProperty("data", "");
    if (!name.matches(PACK)) return null;
    Path zip = e.resolve(name);
    return Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) ? zip : null;
  }

  /** What `id` kept for `pkg`, or null. */
  synchronized Found find(String id, String pkg) throws IOException {
    Path e = entry(id, pkg);
    Properties p = meta(e);
    Path zip = current(e, p);
    if (zip == null) return null;
    List<String> signers = new ArrayList<>();
    for (String s : p.getProperty("signers", "").split(",")) if (!s.isEmpty()) signers.add(s);
    return new Found(zip, p.getProperty("sha256", ""), signers, num(p, "saved"), num(p, "used"), Files.size(zip));
  }

  /** Marks it used now (a session started on it), so `--keep-days` and `--keep-max` count from here. */
  synchronized void touch(String id, String pkg) {
    try {
      Path e = entry(id, pkg);
      Properties p = meta(e);
      p.setProperty("used", Long.toString(System.currentTimeMillis()));
      writeMeta(e, p);
    } catch (IOException e) {
      // counted from when it was saved
    }
  }

  /** A new file in `id`'s package folder to pack into, then {@link #save}. */
  Path newPack(String id, String pkg) throws IOException {
    Path e = entry(id, pkg);
    Files.createDirectories(e);
    if (!Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS) || !e.toRealPath().equals(dir.toRealPath().resolve(id).resolve(pkg))) {
      throw new IOException("the kept folder is not a plain folder: " + e);
    }
    return e.resolve("data-" + HexFormat.of().formatHex(randomBytes()) + ".zip");
  }

  private static byte[] randomBytes() {
    byte[] b = new byte[8];
    new java.security.SecureRandom().nextBytes(b);
    return b;
  }

  /** Puts a finished pack in place of what `id` kept for `pkg`, then drops the least recently used over `--keep-max`. */
  void save(String id, String pkg, Path pack, String sha256, List<String> signers) throws IOException {
    synchronized (this) {
      Path e = entry(id, pkg);
      if (!pack.getParent().equals(e) || !pack.getFileName().toString().matches(PACK)) throw new IOException("not a pack of this entry: " + pack);
      Path before = current(e, meta(e));
      long now = System.currentTimeMillis();
      Properties p = new Properties();
      p.setProperty("data", pack.getFileName().toString());
      p.setProperty("sha256", sha256 == null ? "" : sha256);
      p.setProperty("signers", signers == null ? "" : String.join(",", signers));
      p.setProperty("saved", Long.toString(now));
      p.setProperty("used", Long.toString(now));
      writeMeta(e, p);  // the commit: from here this pack is the kept one
      if (before != null && !before.equals(pack)) {
        try {
          Files.deleteIfExists(before);
        } catch (IOException x) {
          // best effort: the save stands, and the sweep removes a pack no metadata names
        }
      }
    }
    sweep();
  }

  /** Deletes what `id` kept, for every package: how many, or -1 while a session has it. */
  synchronized int forget(String id) {
    if (!id.matches("[0-9a-f]{32}")) return 0;
    if (busy(id)) return -1;
    Path d = dir.resolve(id);
    int n = packages(d).size();
    if (Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) SlotPack.deleteTree(d);
    return n;
  }

  /** `id`'s kept packages and what each holds, for `GET /api/kept/KEY`. */
  synchronized String describe(String id) throws IOException {
    StringBuilder b = new StringBuilder("{\"inUse\":" + busy(id) + ",\"packages\":[");
    boolean first = true;
    for (String pkg : packages(dir.resolve(id))) {
      Found f = find(id, pkg);
      if (f == null) continue;
      if (!first) b.append(',');
      first = false;
      b.append("{\"package\":").append(Http.q(pkg)).append(",\"sha256\":").append(Http.q(f.sha256())).append(",\"size\":").append(f.size())
          .append(",\"saved\":").append(f.saved()).append(",\"used\":").append(f.used()).append('}');
    }
    return b.append("]}").toString();
  }

  private static List<String> packages(Path d) {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) return out;
    try (Stream<Path> all = Files.list(d)) {
      all.forEach(p -> {
        String n = p.getFileName().toString();
        if (n.matches(PACKAGE) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) out.add(n);
      });
    } catch (IOException e) {
      // none
    }
    return out;
  }

  /**
   * Drops what has gone unused for `--keep-days`, then the least recently used past `--keep-max`, never one a session
   * has open; and packs left half written (a server that stopped mid-pack). Hourly and after each save.
   */
  synchronized void sweep() {
    if (!owned || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
    long now = System.currentTimeMillis();
    List<Object[]> live = new ArrayList<>();  // {entry, used}
    try (Stream<Path> ids = Files.list(dir)) {
      for (Path d : ids.toList()) {
        String id = d.getFileName().toString();
        if (id.equals(".lock")) continue;  // the store's own lock (claim): deleting it would let a second server take a new one
        if (!id.matches("[0-9a-f]{32}") || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
          SlotPack.deleteTree(d);
          continue;
        }
        for (String pkg : packages(d)) {
          Path e = d.resolve(pkg);
          try (Stream<Path> files = Files.list(e)) {
            for (Path f : files.toList()) {
              String fn = f.getFileName().toString();
              // A pack no metadata names (a save that stopped before its commit), or metadata half written.
              boolean stray = fn.matches(PACK) ? !f.equals(current(e, meta(e))) : fn.startsWith("meta.properties.");
              if (stray && now - Files.getLastModifiedTime(f, LinkOption.NOFOLLOW_LINKS).toMillis() > 3_600_000L
                  && !busy(id)) {
                Files.deleteIfExists(f);
              }
            }
          }
          Properties p = meta(e);
          long used = Math.max(num(p, "used"), num(p, "saved"));
          if (current(e, p) == null) {
            if (!busy(id) && now - Files.getLastModifiedTime(e, LinkOption.NOFOLLOW_LINKS).toMillis() > 3_600_000L) SlotPack.deleteTree(e);
            continue;
          }
          if (busy(id)) {
            live.add(new Object[] {e, Long.MAX_VALUE});  // counts toward --keep-max, but is never the one dropped
            continue;
          }
          if (now - used > days * 86_400_000L) {
            SlotPack.deleteTree(e);
            System.err.println("[serve] a kept session's data went unused for " + BeamStore.num(days) + " days: deleted");
          } else {
            live.add(new Object[] {e, used});
          }
        }
        if (packages(d).isEmpty() && !busy(id)) SlotPack.deleteTree(d);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not sweep kept sessions: " + e);
      return;
    }
    if (live.size() > max) {
      live.sort(Comparator.comparingLong(o -> (long) o[1]));
      int dropped = 0;
      for (int i = 0; i < live.size() - max && (long) live.get(i)[1] != Long.MAX_VALUE; i++) {
        Path e = (Path) live.get(i)[0];
        SlotPack.deleteTree(e);
        Path d = e.getParent();
        if (packages(d).isEmpty()) SlotPack.deleteTree(d);
        dropped++;
      }
      if (dropped > 0) System.err.println("[serve] kept sessions over --keep-max " + max + ": the " + dropped + " least recently used deleted");
    }
  }

  private static Properties meta(Path e) {
    Properties p = new Properties();
    Path f = e.resolve("meta.properties");
    if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
      try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
        p.load(in);
      } catch (IOException | IllegalArgumentException x) {
        // empty
      }
    }
    return p;
  }

  private static void writeMeta(Path e, Properties p) throws IOException {
    Path tmp = e.resolve("meta.properties." + tmpTag());
    try (OutputStream o = Files.newOutputStream(tmp)) {
      p.store(o, null);
    }
    Files.move(tmp, e.resolve("meta.properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Beside its final name, renamed into place: PID and a random part (containers sharing a volume repeat PIDs). */
  private static String tmpTag() {
    return ProcessHandle.current().pid() + "-" + Long.toHexString(new java.security.SecureRandom().nextLong()) + ".tmp";
  }

  private static long num(Properties p, String k) {
    try {
      return Long.parseLong(p.getProperty(k, "0"));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
