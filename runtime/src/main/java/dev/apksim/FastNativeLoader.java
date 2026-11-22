package dev.apksim;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Priority;
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;

/**
 * Same steps as the stock loader, minus re-extracting the native library, fonts and ICU data from a
 * jar to a fresh temp dir on every launch: they are unpacked once by scripts/prepare-runtime.sh.
 * A stable dylib path also keeps macOS from re-scanning a brand-new binary each time.
 */
@Priority(Integer.MAX_VALUE)
public final class FastNativeLoader extends DefaultNativeRuntimeLoader {
  private static final boolean CACHED_FONT_MAP = !"false".equals(System.getProperty("apksim.cachedFontMap"));
  /** Strong reference to the mapped font-map buffer so its native backing stays alive for the process. */
  @SuppressWarnings("unused")
  private static ByteBuffer mappedFontBuf;
  private boolean done;

  @Override
  public synchronized void ensureLoaded() {
    if (done) return;
    done = true;
    Timing.detail("native-load");
    try {
      Field loaded = DefaultNativeRuntimeLoader.class.getDeclaredField("loaded");
      loaded.setAccessible(true);
      ((AtomicBoolean) loaded.get(null)).set(true);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    Path dir = Path.of(System.getProperty("apksim.runtime"));
    System.setProperty("robolectric.nativeruntime.fontdir", dir.resolve("fonts") + "/");
    String keepIcuPath = System.getProperty("icu.data.path");
    System.setProperty("icu.data.path", dir.resolve("icu/icudt.dat").toString());
    System.setProperty("icu.locale.default", java.util.Locale.getDefault().toLanguageTag());
    System.setProperty("use_base_native_hostruntime", "true");
    System.setProperty("core_native_classes", String.join(",", getCoreClassNatives()));
    System.setProperty("graphics_native_classes", String.join(",", getGraphicsNatives()));
    System.setProperty("method_binding_format", METHOD_BINDING_FORMAT);
    System.load(dir.resolve(libraryName()).toString());
    Timing.detail("native-dlopen");
    invokeDeferredStaticInitializers();
    Timing.detail("native-static-init");
    setNativeSystemProperty("ro.hyphen.data.dir", dir.resolve("hyphen-data").toString());
    loadSystemFontMap(dir, dir.resolve("fonts"), libraryName());
    Timing.detail("native-fonts");
    android.text.Hyphenator.init();
    Timing.detail("native-loaded");
  }

  /**
   * Loads the preinstalled system font map from a serialized cache file in {@code STATE/cache/framework/} when
   * available (~1 ms instead of opening all 236 font files, ~35-48 ms). Builds the file once per fonts directory
   * and native runtime binary.
   */
  private static void loadSystemFontMap(Path runtimeDir, Path fontsDir, String libName) {
    if (!CACHED_FONT_MAP) {
      android.graphics.Typeface.loadPreinstalledSystemFontMap();
      return;
    }
    Path cacheFile = fontMapCacheFile(runtimeDir, fontsDir, libName);
    if (cacheFile != null && Files.isRegularFile(cacheFile)) {
      if (tryDeserializeFontMap(cacheFile)) return;
    }
    android.graphics.Typeface.loadPreinstalledSystemFontMap();
    if (cacheFile != null && !BeamSandbox.ON) {
      saveFontMapAsync(cacheFile);
    }
  }

  private static Path fontMapCacheFile(Path runtimeDir, Path fontsDir, String libName) {
    String root = System.getProperty("apksim.cacheRoot");
    if (root == null) return null;
    try {
      Path xml = fontsDir.resolve("fonts.xml");
      Path lib = runtimeDir.resolve(libName);
      long xmlLen = Files.isRegularFile(xml) ? Files.size(xml) : 0;
      long xmlMtime = Files.isRegularFile(xml) ? Files.getLastModifiedTime(xml).toMillis() : 0;
      long libLen = Files.isRegularFile(lib) ? Files.size(lib) : 0;
      long libMtime = Files.isRegularFile(lib) ? Files.getLastModifiedTime(lib).toMillis() : 0;
      String stamp = fontsDir.toAbsolutePath().normalize() + "|" + xmlLen + "|" + xmlMtime + "|" + libLen + "|" + libMtime;
      java.util.zip.CRC32 crc = new java.util.zip.CRC32();
      crc.update(stamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Path.of(root, "framework", "fontmap-" + Long.toHexString(crc.getValue()) + ".bin");
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean tryDeserializeFontMap(Path file) {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = ch.size();
      if (size <= 0 || size > 16 * 1024 * 1024) return false;
      ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.BIG_ENDIAN);
      Map<String, android.graphics.Typeface> out = new android.util.ArrayMap<>();
      long[] ptrs = android.graphics.Typeface.deserializeFontMap(buf, out);
      if (out.isEmpty()) return false;
      Method addCols = android.graphics.Typeface.class.getDeclaredMethod("nativeAddFontCollections", long.class);
      addCols.setAccessible(true);
      for (long ptr : ptrs) addCols.invoke(null, ptr);
      android.graphics.Typeface.setSystemFontMap(out);
      mappedFontBuf = buf;
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  private static void saveFontMapAsync(Path file) {
    try {
      Map<String, android.graphics.Typeface> map = android.graphics.Typeface.getSystemFontMap();
      if (map == null || map.isEmpty()) return;
      android.os.SharedMemory mem = android.graphics.Typeface.serializeFontMap(map);
      if (mem == null) return;
      ByteBuffer ro = mem.mapReadOnly();
      byte[] bytes = new byte[ro.remaining()];
      ro.get(bytes);
      android.os.SharedMemory.unmap(ro);
      mem.close();
      Thread t = new Thread(() -> {
        try {
          Files.createDirectories(file.getParent());
          Path tmp = file.resolveSibling(file.getFileName() + Storage.tmpTag());
          Files.write(tmp, bytes);
          Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
          // Next cold launch rebuilds it.
        }
      }, "apksim-fontmap-save");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
    } catch (Throwable ignored) {
      // Serialization unavailable on this runtime: fall back silently.
    }
  }
}
