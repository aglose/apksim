package dev.apksim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A `resources.arsc` as a mapped file, not a `byte[]`: the table's pages stay in the system's file cache, shared by
 * every instance and standby, where Robolectric inflates (or reads) it into the Java heap of each. The framework's is
 * 16.7 MB, the largest object of an idle instance's heap; an app's own is 0.7 MB (the test app) to several MB.
 *
 * <p>Called by apksim's copy of Robolectric's {@code CppApkAssets.LoadImpl}, the one place a table becomes a buffer.
 * A stored table (an APK's, since targetSdk 30 requires it) is mapped where it lies in the APK, as AppClasses maps DEX
 * files. A deflated one (the framework jar's; APKs built for older Android versions) is inflated once into a file
 * named by the entry's CRC and length and mapped from there: the framework's in `STATE/cache/framework/`, which a
 * standby with no APK can reach, any other in the APK's cache entry (`arsc/`, beside `dex/`), swept with it.
 *
 * <p>Whatever fails (no cache folder known, zip64, a mapping refused) answers null and the caller reads the table into
 * an array as before; a table inflated for a cache folder that cannot be written is handed over as that array. Old
 * framework tables (one per framework jar ever used, 17 MB) are not removed. A beamed instance maps the framework's table once any other launch (training included) has left it
 * in the cache, which its sandbox lets it read and not write. `-Dapksim.mappedResources=false`, or
 * `APKSIM_MAPPED_RESOURCES=0` in the environment (which reaches standbys), turns it off for A/B runs.
 */
public final class MappedResources {
  private static final boolean ON =
      !"false".equals(System.getProperty("apksim.mappedResources")) && !"0".equals(System.getenv("APKSIM_MAPPED_RESOURCES"));
  private static final boolean LOG = "verbose".equals(System.getProperty("apksim.timing"));
  private static final java.util.concurrent.atomic.AtomicInteger MAPPED = new java.util.concurrent.atomic.AtomicInteger();

  private MappedResources() {}

  /** How many tables this process has mapped (MappedResourcesCheck). */
  public static int mapped() {
    return MAPPED.get();
  }

  /**
   * The table {@code entry} of {@code zip} as a little-endian buffer of exactly its length (mapped and read-only; a
   * heap buffer when it was inflated and could not be written to the cache), or null to read it the old way. {@code dataOffset} is where a stored entry's bytes start in the file (null when unknown);
   * {@code system} says it is the framework's.
   */
  public static ByteBuffer table(String path, ZipFile zip, ZipEntry entry, Long dataOffset, boolean system) {
    if (!ON || entry == null) return null;
    long size = entry.getSize();
    if (size <= 0 || size > Integer.MAX_VALUE) return null;
    try {
      long t0 = LOG ? System.nanoTime() : 0;
      ByteBuffer out;
      String how;
      if (entry.getMethod() == ZipEntry.STORED) {
        if (dataOffset == null || dataOffset < 0 || entry.getCompressedSize() != size) return null;
        try (FileChannel ch = FileChannel.open(Path.of(path), StandardOpenOption.READ)) {
          if (dataOffset + size > ch.size()) return null;
          out = ch.map(FileChannel.MapMode.READ_ONLY, dataOffset, size);
        }
        how = "in place";
      } else {
        Path dir = cacheDir(system);
        if (dir == null) return null;
        // Named by content, so a rebuilt APK or another framework jar never reads a stale table.
        Path file = dir.resolve("resources-" + Long.toHexString(entry.getCrc() & 0xffffffffL) + "-" + size + ".arsc");
        boolean had = file.toFile().length() == size;
        // A beamed instance may read the framework's (the launcher's profile) but write nothing there: without
        // this, it would inflate the table here, fail to write it, and inflate it again the old way.
        if (!had && system && BeamSandbox.ON) return null;
        byte[] bytes = had ? null : inflated(zip, entry, size);
        try {
          if (!had) write(file, bytes);
          try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (ch.size() != size) throw new IOException(file + ": " + ch.size() + " bytes, expected " + size);
            out = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
          }
        } catch (IOException | RuntimeException e) {
          // A cache folder that cannot be written (read-only, full): the table just inflated serves this launch from
          // the heap, as it did before, rather than being inflated a second time by the caller.
          if (bytes == null) return null;
          return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        how = had ? "from " + file : "inflated into " + file;
      }
      if (LOG) {
        System.err.printf("[apksim] resources.arsc of %s mapped %s: %d KB, %.1f ms%n",
            Path.of(path).getFileName(), how, size >> 10, (System.nanoTime() - t0) / 1e6);
      }
      MAPPED.incrementAndGet();
      return out.order(ByteOrder.LITTLE_ENDIAN);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** Where an inflated table is kept, or null when this process has no such folder. */
  private static Path cacheDir(boolean system) {
    if (system) {
      String root = System.getProperty("apksim.cacheRoot");
      return root == null ? null : Path.of(root, "framework");
    }
    // The APK's cache entry (AppInfo sets it once the APK is known; a beamed instance's is in its own out folder).
    String apk = System.getProperty("apksim.cache");
    return apk == null ? null : Path.of(apk, "arsc");
  }

  private static byte[] inflated(ZipFile zip, ZipEntry entry, long size) throws IOException {
    byte[] bytes;
    try (InputStream in = zip.getInputStream(entry)) {
      bytes = in.readAllBytes();
    }
    if (bytes.length != size) throw new IOException("resources.arsc: " + bytes.length + " bytes, expected " + size);
    return bytes;
  }

  /** Written beside its name and renamed into place, so a reader never maps a table still being written. */
  private static void write(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(file.getFileName() + Storage.tmpTag());
    try {
      Files.write(tmp, bytes);
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
