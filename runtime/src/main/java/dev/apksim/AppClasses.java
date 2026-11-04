package dev.apksim;

import dev.apksim.dex.ClassTranslator;
import dev.apksim.dex.DexFile;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Serves the APK's classes to the JVM's application class loader. Classes are translated from DEX
 * only when something first asks for them, so APK size does not affect launch time, and kept in {@link ClassCache}
 * for the next launch.
 */
final class AppClasses {
  private static final Pattern DEX = Pattern.compile("classes\\d*\\.dex");
  private static final int ACC_INTERFACE = 0x0200;

  /** Lookups by name so far: "a/b/C" -> {dex, classDef}, or {@link #NONE}. See {@link #find}. */
  private final Map<String, int[]> found = new ConcurrentHashMap<>();
  private static final int[] NONE = new int[0];
  /** Per DEX file, each type's class definition there or -1. */
  private final List<int[]> defsByType = new ArrayList<>();
  private final List<DexFile> dexes = new ArrayList<>();
  private final Map<String, Boolean> otherInterfaces = new ConcurrentHashMap<>();
  /** Classes translated ahead by {@link #translateAhead} and not yet asked for. */
  private final Map<String, byte[]> ahead = new ConcurrentHashMap<>();
  private final Set<String> asked = ConcurrentHashMap.newKeySet();
  private final ClassLoader loader = ClassLoader.getSystemClassLoader();
  private final ZipFile zip;
  private final ClassCache cache;
  /** Where this APK's files appear on the class path: "/app/", or "/test/" for a test APK. */
  private final String root;
  /** Classes of the APK this one is tested against (a test APK calls the app's classes), or null. */
  private final AppClasses target;
  public int translated;
  public long translateNanos;
  private volatile int translatedAhead;
  private boolean aheadStarted;

  private AppClasses(Path apk, String root, AppClasses target, String hintName) throws IOException {
    this.root = root;
    this.target = target;
    zip = new ZipFile(apk.toFile());
    List<ZipEntry> entries = new ArrayList<>();
    Enumeration<? extends ZipEntry> e = zip.entries();
    while (e.hasMoreElements()) {
      ZipEntry z = e.nextElement();
      if (DEX.matcher(z.getName()).matches()) entries.add(z);
    }
    // classes.dex must win over classes2.dex and so on.
    entries.sort((x, y) -> {
      int c = Integer.compare(x.getName().length(), y.getName().length());
      return c != 0 ? c : x.getName().compareTo(y.getName());
    });
    Map<String, Long> stored = storedOffsets(apk);
    String[] keys = new String[entries.size()];
    try (FileChannel ch = FileChannel.open(apk, StandardOpenOption.READ)) {
      for (ZipEntry z : entries) {
        // APKs keep DEX files uncompressed, so map them: the bytes stay in the page cache, not on the heap.
        Long at = stored.get(z.getName());
        ByteBuffer data;
        if (at != null) {
          data = ch.map(FileChannel.MapMode.READ_ONLY, at, z.getSize());
        } else {
          data = inflated(apk, z);
        }
        DexFile dex = new DexFile(data);
        int dexIdx = dexes.size();
        keys[dexIdx] = String.format("%08x-%d", z.getCrc(), z.getSize());
        dexes.add(dex);
        defsByType.add(dex.classDefsByType());
      }
    }
    cache = ClassCache.open(keys, hintName);
  }

  /**
   * A compressed DEX file (APKs for older Android versions compress them), inflated once and kept beside the APK's
   * other extracted files, then mapped like a stored one: inflating the test app's 36 MB took ~60 ms every launch.
   */
  private ByteBuffer inflated(Path apk, ZipEntry z) throws IOException {
    String dir = System.getProperty("apksim.cache");
    Path file = dir == null ? null : Path.of(dir, "dex", String.format("%08x-%d.dex", z.getCrc(), z.getSize()));
    if (file != null && file.toFile().length() == z.getSize()) {
      try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
        return ch.map(FileChannel.MapMode.READ_ONLY, 0, z.getSize());
      }
    }
    byte[] bytes;
    try (InputStream in = zip.getInputStream(z)) {
      bytes = in.readAllBytes();
    }
    if (file != null) {
      try {
        java.nio.file.Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + Storage.tmpTag());
        java.nio.file.Files.write(tmp, bytes);
        java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        // Next launch inflates again.
      }
    }
    return ByteBuffer.wrap(bytes);
  }

  /** Where each stored (uncompressed) entry's data starts in the file, from the zip central directory. */
  private static Map<String, Long> storedOffsets(Path apk) throws IOException {
    Map<String, Long> out = new HashMap<>();
    try (FileChannel ch = FileChannel.open(apk, StandardOpenOption.READ)) {
      long size = ch.size();
      int tail = (int) Math.min(size, 0xffff + 22);
      ByteBuffer end = ByteBuffer.allocate(tail).order(ByteOrder.LITTLE_ENDIAN);
      ch.read(end, size - tail);
      int eocd = -1;
      for (int i = tail - 22; i >= 0; i--) {
        if (end.getInt(i) == 0x06054b50) {
          eocd = i;
          break;
        }
      }
      if (eocd < 0) return out;
      long cdSize = end.getInt(eocd + 12) & 0xffffffffL;
      long cdOffset = end.getInt(eocd + 16) & 0xffffffffL;
      if (cdOffset == 0xffffffffL || cdSize > Integer.MAX_VALUE) return out; // zip64: read DEX files the slow way
      ByteBuffer cd = ByteBuffer.allocate((int) cdSize).order(ByteOrder.LITTLE_ENDIAN);
      ch.read(cd, cdOffset);
      ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
      for (int p = 0; p + 46 <= cdSize && cd.getInt(p) == 0x02014b50; ) {
        int method = cd.getShort(p + 10) & 0xffff;
        int nameLen = cd.getShort(p + 28) & 0xffff;
        int extraLen = cd.getShort(p + 30) & 0xffff;
        int commentLen = cd.getShort(p + 32) & 0xffff;
        long header = cd.getInt(p + 42) & 0xffffffffL;
        byte[] name = new byte[nameLen];
        cd.get(p + 46, name);
        String n = new String(name, StandardCharsets.UTF_8);
        if (method == 0 && header != 0xffffffffL && DEX.matcher(n).matches()) {
          local.clear();
          ch.read(local, header);
          if (local.getInt(0) == 0x04034b50) {
            out.put(n, header + 30 + (local.getShort(26) & 0xffff) + (local.getShort(28) & 0xffff));
          }
        }
        p += 46 + nameLen + extraLen + commentLen;
      }
    }
    return out;
  }

  /**
   * Where the APK defines class "a/b/C" ({dex, classDef}), or null. The first DEX file that has it wins, as on
   * Android (classes.dex, then classes2.dex...).
   */
  private int[] find(String name) {
    int[] at = found.get(name);
    if (at == null) {
      at = NONE;
      for (int i = 0; i < dexes.size(); i++) {
        int t = dexes.get(i).findClassType(name);
        if (t >= 0 && defsByType.get(i)[t] >= 0) {
          at = new int[] {i, defsByType.get(i)[t]};
          break;
        }
      }
      found.putIfAbsent(name, at);
    }
    return at == NONE ? null : at;
  }

  private boolean isInterface(String owner) {
    int[] at = find(owner);
    if (at != null) return (dexes.get(at[0]).classAccess(at[1]) & ACC_INTERFACE) != 0;
    if (target != null && target.find(owner) != null) return target.isInterface(owner);
    Boolean known = otherInterfaces.get(owner);
    if (known != null) return known;
    // Not computeIfAbsent: loading a class while holding the map's lock could deadlock with another thread.
    boolean is;
    try {
      is = Class.forName(owner.replace('/', '.'), false, loader).isInterface();
    } catch (ClassNotFoundException | LinkageError ex) {
      is = false;
    }
    otherInterfaces.putIfAbsent(owner, is);
    return is;
  }

  /** What a translation learned about a class it calls; see {@link ClassCache}. */
  private int answer(String owner) {
    int[] at = find(owner);
    if (at == null) return target != null ? target.answer(owner) : ClassCache.NOT_APP;
    return (dexes.get(at[0]).classAccess(at[1]) & ACC_INTERFACE) != 0 ? ClassCache.INTERFACE : ClassCache.CLASS;
  }

  synchronized byte[] translate(String internalName) {
    int[] at = find(internalName);
    if (at == null) return null;
    asked.add(internalName);
    if (cache != null) {
      if (!aheadStarted) {
        // Started at the app's first class rather than at launch, so it does not slow the framework's startup.
        aheadStarted = true;
        Thread planner = new Thread(this::translateAhead, "apksim-translate");
        planner.setDaemon(true);
        planner.start();
      }
      cache.loaded(internalName);
      byte[] done = ahead.remove(internalName);
      if (done != null) return done;
      byte[] hit = cache.get(at[0], internalName, this::answer);
      if (hit != null) return hit;
    }
    long t0 = System.nanoTime();
    byte[] out = translate(dexes.get(at[0]), at, internalName);
    if (out == null) return null;
    translateNanos += System.nanoTime() - t0;
    translated++;
    return out;
  }

  private byte[] translate(DexFile dex, int[] at, String internalName) {
    byte[] out;
    Map<String, Byte> answers = new LinkedHashMap<>();
    try {
      out = new ClassTranslator(dex, at[1], owner -> {
        answers.computeIfAbsent(owner, o -> (byte) answer(o));
        return isInterface(owner);
      }).translate();
    } catch (RuntimeException e) {
      // The class loader would report this only as "class not found".
      System.err.println("[apksim] cannot translate " + internalName);
      e.printStackTrace();
      return null;
    }
    if (cache != null) cache.put(at[0], internalName, out, answers);
    return out;
  }

  /**
   * Translates, on background threads, the classes this app loaded last time that the cache cannot supply (their
   * DEX file changed). Several threads soon get ahead of the main thread, which then finds most classes ready.
   */
  private void translateAhead() {
    List<String> todo = new ArrayList<>();
    for (String name : cache.hints()) {
      int[] at = find(name);
      if (at != null && !cache.has(at[0], name, this::answer)) todo.add(name);
    }
    if (todo.isEmpty()) return;
    AtomicInteger next = new AtomicInteger();
    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
    AtomicInteger running = new AtomicInteger(threads);
    long t0 = System.nanoTime();
    for (int t = 0; t < threads; t++) {
      Thread worker = new Thread(() -> {
        // DexFile caches strings and method refs, so each thread reads through its own view of the same bytes.
        DexFile[] views = new DexFile[dexes.size()];
        for (int i; (i = next.getAndIncrement()) < todo.size(); ) {
          String name = todo.get(i);
          if (asked.contains(name)) continue;
          int[] at = find(name);
          if (views[at[0]] == null) views[at[0]] = dexes.get(at[0]).view();
          byte[] out = translate(views[at[0]], at, name);
          if (out != null && !asked.contains(name)) {
            ahead.put(name, out);
            translatedAhead++;
          }
        }
        if (running.decrementAndGet() == 0 && !Boolean.getBoolean("apksim.quiet")) {
          System.err.printf("[apksim] translated %d of %d changed classes ahead in %d ms%n", translatedAhead, todo.size(),
              (System.nanoTime() - t0) / 1_000_000);
        }
      }, "apksim-translate-" + t);
      worker.setDaemon(true);
      worker.start();
    }
  }

  /**
   * Loads (defines, without initializing: no app code runs) the classes the app loaded last time, in that order, on
   * background threads, while the main thread starts the framework and then the app. Defining a class costs ~40 us,
   * ~120 ms for the test app's 2,800, which the main thread then mostly finds done. The JVM's application loader
   * takes a lock per class name, so a class the main thread wants while a worker defines it is waited for, not
   * defined twice. -Dapksim.loadAhead=false turns it off.
   */
  void loadAhead() {
    if (cache == null || !Boolean.parseBoolean(System.getProperty("apksim.loadAhead", "true"))) return;
    List<String> names = cache.hints();
    if (names.isEmpty()) return;
    AtomicInteger next = new AtomicInteger();
    int threads = Integer.getInteger("apksim.loadAheadThreads", 2);
    for (int t = 0; t < threads; t++) {
      Thread worker = new Thread(() -> {
        for (int i; (i = next.getAndIncrement()) < names.size(); ) {
          String name = names.get(i);
          if (find(name) == null) continue;
          try {
            Class.forName(name.replace('/', '.'), false, loader);
          } catch (Throwable e) {
            // Gone from this build, or broken: the app finds out itself if it asks.
          }
        }
      }, "apksim-load-" + t);
      worker.setDaemon(true);
      worker.start();
    }
  }

  int translatedAhead() {
    return translatedAhead;
  }

  int cached() {
    return cache == null ? 0 : cache.hits;
  }

  private InputStream open(String path) throws IOException {
    if (path.startsWith(root)) path = path.substring(root.length());
    if (path.endsWith(".class")) {
      // Classes the app does not have are refused before translate()'s lock: translateAhead's threads look them up
      // while holding the class loader's lock for that name, and the main thread may be waiting for that lock.
      String name = path.substring(0, path.length() - 6);
      byte[] b = find(name) != null ? translate(name) : null;
      if (b != null) return new ByteArrayInputStream(b);
    } else {
      ZipEntry z = zip.getEntry(path);
      if (z != null) return zip.getInputStream(z);
    }
    throw NOT_FOUND;
  }

  private static final FileNotFoundException NOT_FOUND = new FileNotFoundException() {
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  };

  static AppClasses install(Path apk) throws Exception {
    return install(apk, "/app/", null, System.getProperty("apksim.package"));
  }

  /**
   * Adds a test APK's classes after the app's, as Android does for an instrumentation that targets the app: both run
   * in one process and the test calls the app's classes directly. AGP leaves out of the test APK what the app has.
   */
  static AppClasses installTest(Path apk, AppClasses app) throws Exception {
    return install(apk, "/test/", app, System.getProperty("apksim.package") + ".test");
  }

  /** The test APK's classes, in DEX order, as "a.b.C". */
  List<String> classNames() {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (DexFile dex : dexes) {
      for (int i = 0; i < dex.classDefsSize; i++) {
        String d = dex.className(i);
        out.add(d.substring(1, d.length() - 1).replace('/', '.'));
      }
    }
    return new ArrayList<>(out);
  }

  private static AppClasses install(Path apk, String rootPath, AppClasses target, String hintName) throws Exception {
    AppClasses classes = new AppClasses(apk, rootPath, target, hintName);
    URLStreamHandler handler = new URLStreamHandler() {
      @Override
      protected URLConnection openConnection(URL u) {
        return new URLConnection(u) {
          private InputStream in;

          @Override
          public void connect() throws IOException {
            if (in == null) in = classes.open(u.getPath());
          }

          @Override
          public InputStream getInputStream() throws IOException {
            connect();
            return in;
          }

          // Without a length the JDK reads a class into a buffer it grows (1K, 3K, 7K, ...) and then trims; with it,
          // into one array of the class's size: ~15 MB less allocated before the test app's first frame.
          @Override
          public long getContentLengthLong() {
            try {
              connect();
            } catch (IOException e) {
              return -1;
            }
            return in instanceof ByteArrayInputStream b ? b.available() : -1;
          }
        };
      }
    };
    URL root = new URL(null, "apksim:" + rootPath, handler);
    Field ucp = classes.loader.getClass().getSuperclass().getDeclaredField("ucp");
    ucp.setAccessible(true);
    Object path = ucp.get(classes.loader);
    Method add = path.getClass().getDeclaredMethod("addURL", URL.class);
    add.setAccessible(true);
    add.invoke(path, root);
    return classes;
  }
}
