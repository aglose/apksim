package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Set;
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
public final class SlotPack {
  static final long MAX_BYTES = 1L << 30;
  static final int MAX_FILES = 50_000;
  /** Entries listed in all, moved or not (links, pipes): what a walk may look at before it gives up. */
  static final int MAX_LISTED = 2 * MAX_FILES;
  /** An entry's name at most this long and this many folders deep (a 64 KB name would nest thousands deep). */
  static final int MAX_NAME = 4096, MAX_DEPTH = 64;

  private SlotPack() {}

  /**
   * Zips a slot (under `state`) and its .device folder. Read through open folder handles from `state` down, each
   * opened without following a link (openat with O_NOFOLLOW, SecureDirectoryStream), never by path: a process the app
   * left running may still write there, and swapping a file or a folder above it for a link between a check and an
   * open would otherwise pack what the link names (a file of the server's, which the app may not read itself). Links
   * are left out, as are other special files. At most {@link #MAX_FILES} files and {@link #MAX_BYTES}, as unpacking
   * takes.
   */
  /**
   * How far a pack has got, for a caller that waits on it with a deadline: a file swapped for a named pipe between
   * the check and the open blocks that open until something writes to the pipe, and open() cannot be interrupted.
   */
  static final class Progress {
    volatile long files, bytes;
    /** Entries left out: links, other special files, ones swapped meanwhile (the handoff says how many). */
    volatile long skipped;
    private volatile SecureDirectoryStream<Path> openingIn;
    private volatile Path opening;

    /**
     * Unblocks an open stuck on a pipe: opens the same entry, through the same folder handle, for writing (no create,
     * no truncate, no link followed), which lets a reader's open return. Run it on a thread of its own: with no reader
     * left it blocks itself.
     */
    void unstick() {
      SecureDirectoryStream<Path> d = openingIn;
      Path n = opening;
      if (d == null || n == null) return;
      try (SeekableByteChannel ch = d.newByteChannel(n, Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
        // opened is enough
      } catch (IOException | RuntimeException e) {
        // swapped again, or gone
      }
    }
  }

  public static void pack(Path state, Path slot, String pkg, OutputStream out) throws IOException {
    pack(state, slot, pkg, out, new Progress());
  }

  static void pack(Path state, Path slot, String pkg, OutputStream out, Progress progress) throws IOException {
    Path base = state.toAbsolutePath().normalize();
    Path rel = base.relativize(slot.toAbsolutePath().normalize());
    if (rel.getNameCount() < 2 || rel.startsWith("..")) throw new IOException("not a data slot under the state folder");
    try (DirectoryStream<Path> top = Files.newDirectoryStream(base)) {
      if (!(top instanceof SecureDirectoryStream<Path> s)) throw new IOException("this system cannot read a folder without following links");
      List<SecureDirectoryStream<Path>> open = new ArrayList<>();
      try (ZipOutputStream z = new ZipOutputStream(out)) {
        SecureDirectoryStream<Path> parent = s;
        for (Path part : rel.getParent()) {
          parent = parent.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
          open.add(parent);
        }
        z.putNextEntry(new ZipEntry("package"));
        z.write(pkg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        z.closeEntry();
        long[] used = {1, 0, 0};  // entries (the package one counted, as unpack counts it), bytes, entries listed
        String name = rel.getFileName().toString();
        add(z, parent, name, "slot/", 0, used, progress);
        add(z, parent, name + ".device", "device/", 0, used, progress);
      } finally {
        for (SecureDirectoryStream<Path> d : open) d.close();
      }
    }
  }

  private static void add(ZipOutputStream z, SecureDirectoryStream<Path> parent, String name, String prefix, int depth, long[] used,
      Progress progress)
      throws IOException {
    if (depth > MAX_DEPTH) throw new IOException("folders nested too deep to move");  // as unpack counts them
    SecureDirectoryStream<Path> d;
    try {
      d = parent.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS);
    } catch (java.nio.file.NoSuchFileException | java.nio.file.NotDirectoryException e) {
      return;  // none (a slot without a .device folder)
    } catch (java.nio.file.FileSystemException e) {
      progress.skipped++;
      return;  // a link (ELOOP): left out
    }
    try (d) {
      List<Path> names = new ArrayList<>();
      for (Path p : d) {
        // Counted as listed, not after: one folder of millions of links (which move as nothing) must not be read whole.
        if (++used[2] > MAX_LISTED) throw new IOException("the app's data has more than " + MAX_LISTED + " entries to look at");
        names.add(p.getFileName());
      }
      names.sort(null);
      for (Path n : names) {
        BasicFileAttributes at;
        try {
          at = d.getFileAttributeView(n, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        } catch (java.nio.file.NoSuchFileException e) {
          continue;  // gone meanwhile
        }
        String entry = prefix + n;
        if (entry.length() > MAX_NAME) throw new IOException("a name in the app's data is over " + MAX_NAME + " characters");
        if (at.isDirectory()) {
          // Each folder an entry of its own (an empty one would otherwise be lost), counted as unpack counts it.
          if (++used[0] > MAX_FILES) throw new IOException("the app's data has more than " + (MAX_FILES - 1) + " files and folders to move");
          progress.files = used[0];
          z.putNextEntry(new ZipEntry(entry + "/"));
          z.closeEntry();
          add(z, d, n.toString(), entry + "/", depth + 1, used, progress);
        } else if (at.isRegularFile()) {
          if (depth == 0 && prefix.equals("slot/") && n.toString().equals(".lock")) continue;  // the slot's own lock
          if (++used[0] > MAX_FILES) throw new IOException("the app's data has more than " + (MAX_FILES - 1) + " files and folders to move");
          progress.files = used[0];
          progress.openingIn = d;
          progress.opening = n;
          SeekableByteChannel opened;
          try {
            opened = d.newByteChannel(n, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          } catch (java.nio.file.FileSystemException e) {
            if (e.getMessage() != null && e.getMessage().contains("symbolic link")) {  // swapped for a link meanwhile
              progress.skipped++;
              continue;
            }
            throw e;
          } finally {
            progress.opening = null;
            progress.openingIn = null;
          }
          try (SeekableByteChannel ch = opened) {
            z.putNextEntry(new ZipEntry(entry));
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            for (int r; (r = ch.read(buf)) > 0; buf.clear()) {
              used[1] += r;
              progress.bytes = used[1];
              if (used[1] > MAX_BYTES) throw new IOException("the app's data is over " + (MAX_BYTES >> 20) + " MB to move");
              z.write(buf.array(), 0, r);
            }
            z.closeEntry();
          }
        } else {
          progress.skipped++;  // a link, a pipe, a socket: not moved
        }
      }
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
    // Folders made so far: one made implicitly, above an entry, counts toward the limit too (else 50,000 entries 63
    // deep each would make three million). Pack writes each folder before what is in it, so its packs pay nothing extra.
    java.util.Set<Path> made = new java.util.HashSet<>();
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
        boolean folder = e.isDirectory();
        if (folder) {
          if (z.read() != -1) throw new IOException("a folder entry with contents: " + name);
          name = name.substring(0, name.length() - 1);  // its trailing "/"
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
        // A backslash is an ordinary character in a name on macOS and Linux (pack takes such a file), so it is kept;
        // only "/" separates, and "." and ".." are refused below.
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\0")) throw new IOException("bad entry " + name);
        // Folders deep, as pack counts them: a folder entry is its own components, a file's are its containing
        // folders (pack takes a file inside 64 nested folders).
        int folders = rel.split("/").length - (folder ? 0 : 1);
        if (name.length() > MAX_NAME || folders > MAX_DEPTH) throw new IOException("an entry too long or too deep: " + name.substring(0, Math.min(80, name.length())));
        Path to = root.resolve(rel).normalize();
        if (!to.startsWith(root) || to.equals(root)) throw new IOException("entry outside its folder: " + name);
        for (String part : rel.split("/")) {
          if (part.equals("..") || part.equals(".")) throw new IOException("bad entry " + name);
        }
        count += newFolders(to.getParent(), root, made);
        if (folder) made.add(to);  // counted as its own entry
        if (count > MAX_FILES) throw new IOException("more than " + MAX_FILES + " files and folders");
        if (folder) {
          // An empty folder the app made is kept too (it may count on it being there).
          Files.createDirectories(to);
          if (!to.toRealPath().startsWith(root.toRealPath())) throw new IOException("entry outside its folder: " + name);
          continue;
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

  /** The folders from `dir` up to `root` (not included) not made yet, marked made; how many. */
  private static int newFolders(Path dir, Path root, java.util.Set<Path> made) {
    int n = 0;
    for (Path d = dir; d != null && !d.equals(root) && d.startsWith(root); d = d.getParent()) {
      if (!made.add(d)) break;  // its parents were made with it
      n++;
    }
    return n;
  }

  /**
   * Deletes a folder and what is in it, never through a link at any level: from its parent's open handle down, each
   * entry removed relative to its folder's handle (unlinkat, SecureDirectoryStream). The agent runs outside any
   * sandbox, and a process an APK left running may still write in what is deleted: by path, a folder swapped for a
   * link between the walk and a delete led that delete outside (a file of the visitor's). Now a swap can only make it
   * delete the link. A link where the folder itself was is removed alone. Best effort, logged.
   */
  static void deleteTree(Path dir) {
    try {
      remove(dir, true, java.util.Set.of());
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not remove " + dir + ": " + e);
    }
  }

  /** As {@link #deleteTree}, but keeps the folder itself and the top-level names in `keep` (a slot's `.lock`). */
  static void emptyTree(Path dir, java.util.Set<String> keep) throws IOException {
    remove(dir, false, keep);
  }

  private static void remove(Path dir, boolean self, java.util.Set<String> keep) throws IOException {
    Path abs = dir.toAbsolutePath().normalize();
    Path parent = abs.getParent(), name = abs.getFileName();
    if (parent == null || name == null) throw new IOException("not a folder to remove: " + dir);
    DirectoryStream<Path> top;
    try {
      top = Files.newDirectoryStream(parent);
    } catch (java.nio.file.NoSuchFileException e) {
      return;
    }
    try (top) {
      if (!(top instanceof SecureDirectoryStream<Path> p)) throw new IOException("this system cannot remove a folder without following links");
      BasicFileAttributes at;
      try {
        at = p.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
      } catch (java.nio.file.NoSuchFileException e) {
        return;
      }
      if (!at.isDirectory()) {  // a link (or a file) where the folder was: it alone goes
        if (self) p.deleteFile(name);
        return;
      }
      clear(p, name, keep, 0);
      if (self) p.deleteDirectory(name);
    }
  }

  /** Empties `parent`'s folder `name` through handles; passes until it is empty (a listing may miss what a delete moved). */
  private static void clear(SecureDirectoryStream<Path> parent, Path name, java.util.Set<String> keep, int depth) throws IOException {
    if (depth > 256) throw new IOException("folders nested too deep to remove");
    for (int pass = 0; pass < 1000; pass++) {
      List<Path> names = new ArrayList<>();
      try (SecureDirectoryStream<Path> d = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
        for (Path n : d) {
          if (depth == 0 && keep.contains(n.getFileName().toString())) continue;
          names.add(n.getFileName());
          if (names.size() >= 10_000) break;  // in batches: a folder of millions is not listed whole
        }
        if (names.isEmpty()) return;
        for (Path n : names) {
          BasicFileAttributes at;
          try {
            at = d.getFileAttributeView(n, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
          } catch (java.nio.file.NoSuchFileException e) {
            continue;
          }
          try {
            if (at.isDirectory()) {
              clear(d, n, java.util.Set.of(), depth + 1);
              d.deleteDirectory(n);
            } else {
              d.deleteFile(n);  // a link is removed, never what it names
            }
          } catch (java.nio.file.NoSuchFileException e) {
            // gone meanwhile
          } catch (java.nio.file.FileSystemException e) {
            if (!at.isDirectory()) throw e;
            d.deleteFile(n);  // swapped for a link since it was looked at: the link goes
          }
        }
      } catch (java.nio.file.NoSuchFileException e) {
        return;
      }
    }
    throw new IOException("could not empty " + name);
  }

}
