package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * An app's data slot moved between machines (docs/beam.md, phase 5): the slot (`cache/data/<pkg>/<slot>`: the app's
 * files, databases, preferences) and its `.device` folder (its /sdcard and Settings values, SlotDevice) as one zip,
 * `slot/...` and `device/...`, with the package in a `package` entry. Packed by the server once its instance has quit
 * (so what it wrote is flushed), unpacked by the agent into a slot of its own before the instance there starts.
 * Unpacking takes what a site sent: only regular files under those two roots, no absolute or `..` paths, no links, at
 * most {@link #MAX_FILES} files and {@link #MAX_BYTES} in all, into folders made fresh for it.
 */
final class SlotPack {
  static final long MAX_BYTES = 1L << 30;
  static final int MAX_FILES = 50_000;

  private SlotPack() {}

  /** Zips a slot and its .device folder (links are not followed, nor packed). */
  static void pack(Path slot, String pkg, OutputStream out) throws IOException {
    Path device = slot.resolveSibling(slot.getFileName() + ".device");
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      z.putNextEntry(new ZipEntry("package"));
      z.write(pkg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      z.closeEntry();
      add(z, slot, "slot/");
      add(z, device, "device/");
    }
  }

  private static void add(ZipOutputStream z, Path root, String prefix) throws IOException {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
    List<Path> files;
    try (Stream<Path> all = Files.walk(root)) {
      files = all.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
    }
    for (Path f : files) {
      String rel = root.relativize(f).toString().replace('\\', '/');
      if (rel.equals(".lock")) continue;  // the slot's lock is the slot's own
      z.putNextEntry(new ZipEntry(prefix + rel));
      Files.copy(f, z);
      z.closeEntry();
    }
  }

  /** At most this many bytes of package name (Android's limit is 255). */
  static final int MAX_PACKAGE = 256;

  /**
   * The package a pack names, or null (a name as Android allows it). It must be the first entry, as {@link #pack}
   * writes it, and short: nothing else is decompressed before {@link #unpack}'s limits apply.
   */
  static String packageOf(Path zip) throws IOException {
    try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry e = z.getNextEntry();
      if (e == null || !e.getName().equals("package")) return null;
      byte[] b = z.readNBytes(MAX_PACKAGE + 1);
      if (b.length > MAX_PACKAGE) return null;
      String p = new String(b, java.nio.charset.StandardCharsets.UTF_8).strip();
      return p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") ? p : null;
    }
  }

  /**
   * Unpacks a pack into `slot` and `slot.device`, which must not exist yet (they are made here). Throws why it would
   * not; what it made is removed then.
   */
  static void unpack(Path zip, Path slot) throws IOException {
    Path device = slot.resolveSibling(slot.getFileName() + ".device");
    if (Files.exists(slot, LinkOption.NOFOLLOW_LINKS) || Files.exists(device, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("the slot exists already");
    }
    Files.createDirectories(slot);
    Files.createDirectories(device);
    long total = 0;
    int count = 0;
    try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
      boolean first = true;
      for (ZipEntry e; (e = z.getNextEntry()) != null; first = false) {
        String name = e.getName();
        // Every entry counts, and none is passed over unread: skipping one would decompress it outside the limits.
        if (++count > MAX_FILES) throw new IOException("more than " + MAX_FILES + " files");
        if (name.equals("package")) {
          if (!first || z.readNBytes(MAX_PACKAGE + 1).length > MAX_PACKAGE) throw new IOException("bad package entry");
          if (z.read() != -1) throw new IOException("bad package entry");
          continue;
        }
        if (e.isDirectory()) {
          if (z.read() != -1) throw new IOException("a folder entry with contents: " + name);
          continue;
        }
        Path root;
        String rel;
        if (name.startsWith("slot/")) {
          root = slot;
          rel = name.substring(5);
        } else if (name.startsWith("device/")) {
          root = device;
          rel = name.substring(7);
        } else {
          throw new IOException("unexpected entry " + name);
        }
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains("\0")) throw new IOException("bad entry " + name);
        Path to = root.resolve(rel).normalize();
        if (!to.startsWith(root) || to.equals(root)) throw new IOException("entry outside its folder: " + name);
        for (String part : rel.split("/")) {
          if (part.equals("..") || part.equals(".")) throw new IOException("bad entry " + name);
        }
        Files.createDirectories(to.getParent());
        if (!to.getParent().toRealPath().startsWith(root.toRealPath())) throw new IOException("entry outside its folder: " + name);
        try (OutputStream out = Files.newOutputStream(to, java.nio.file.StandardOpenOption.CREATE_NEW)) {
          byte[] buf = new byte[1 << 16];
          for (int n; (n = z.read(buf)) > 0; ) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("more than " + (MAX_BYTES >> 20) + " MB");
            out.write(buf, 0, n);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      deleteTree(slot);
      deleteTree(device);
      throw e instanceof IOException io ? io : new IOException(e);
    }
  }

  /**
   * A moved session's slot (`cache/data/<any package>/NAME-0` and its .device) that no instance took, erased: the
   * agent unpacks it before the session exists, so a grant that expires or is refused, or a session that never
   * starts, would otherwise leave the app's data unlisted for good. `except` (the slot an instance runs in) is kept.
   */
  static void dropSeed(Path state, String name, Path except, java.util.function.Consumer<String> log) {
    if (name == null || !name.matches("bm[0-9a-f]{12}")) return;
    try (Stream<Path> pkgs = Files.list(state.resolve("cache/data"))) {
      for (Path pkg : pkgs.toList()) {
        Path slot = pkg.resolve(name + "-0");
        if (slot.equals(except) || !Files.isDirectory(slot, LinkOption.NOFOLLOW_LINKS)) continue;
        deleteTree(slot);
        deleteTree(slot.resolveSibling(name + "-0.device"));
        log.accept("[beam] erased moved data no session used (" + pkg.getFileName() + ", " + name + ")");
      }
    } catch (IOException | RuntimeException e) {
      log.accept("[beam] could not look for unused moved data: " + e);
    }
  }

  static void deleteTree(Path dir) {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not remove " + dir + ": " + e);
    }
  }
}
