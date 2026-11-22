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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Serves the APK's classes to the JVM's application class loader. Classes are translated from DEX
 * only when something first asks for them (or speculatively in parallel on background cores), and
 * kept in {@link ClassCache} for the next launch.
 */
final class AppClasses {
  private static final Pattern DEX = Pattern.compile("classes\\d*\\.dex");
  private static final int ACC_INTERFACE = 0x0200;
  private static final int SPEC_CAP = Integer.getInteger("apksim.speculateCap", 400);
  private static final boolean SPECULATE = Boolean.parseBoolean(System.getProperty("apksim.speculate", "true"));

  /** Stops speculative BFS once the first frame is drawn so no idle CPU is spent translating unused classes. */
  private static volatile boolean firstFrame;

  /** Background disk writer for freshly inflated DEX files so startup never blocks on APFS writes. */
  private static final ConcurrentLinkedQueue<DexWrite> DEX_WRITES = new ConcurrentLinkedQueue<>();
  private static final AtomicInteger DEX_PENDING = new AtomicInteger();
  private static volatile Thread dexWriterThread;
  private static final Object DEX_WRITER_LOCK = new Object();

  private record DexWrite(Path file, ByteBuffer buf) {}

  private record DexEntry(String name, int method, long crc, int compSize, int uncompSize, long localHeader) {}

  private record Ahead(byte[] bytes, String[] refs, Map<String, Byte> answers, int dex, int classDef) {}

  /** Lookups by name so far: "a/b/C" -> {dex, classDef}, or {@link #NONE}. See {@link #find}. */
  private final Map<String, int[]> found = new ConcurrentHashMap<>();
  private static final int[] NONE = new int[0];
  /** Per DEX file, each type's class definition there or -1. Populated before {@link #ready}. */
  private final List<int[]> defsByType = new ArrayList<>();
  private final List<DexFile> dexes = new ArrayList<>();
  private final Map<String, Boolean> otherInterfaces = new ConcurrentHashMap<>();
  /** Classes translated ahead by background workers and not yet asked for by the app. */
  private final Map<String, Ahead> ahead = new ConcurrentHashMap<>();
  /** Per-class in-flight translations so workers and main thread never serialize on one coarse lock. */
  private final Map<String, CompletableFuture<Ahead>> inFlight = new ConcurrentHashMap<>();
  private final Set<String> asked = ConcurrentHashMap.newKeySet();
  private final ClassLoader loader = ClassLoader.getSystemClassLoader();
  private final Path apkPath;
  private volatile ZipFile lazyZip;
  private volatile ClassCache cache;
  /** Where this APK's files appear on the class path: "/app/", or "/test/" for a test APK. */
  private final String root;
  /** Classes of the APK this one is tested against (a test APK calls the app's classes), or null. */
  private final AppClasses target;
  private final String hintName;
  private final CountDownLatch ready = new CountDownLatch(1);
  private volatile IOException initError;
  private final AtomicInteger translatedCount = new AtomicInteger();
  private final AtomicLong translateNanosCount = new AtomicLong();
  public int translated;
  public long translateNanos;
  private final AtomicInteger translatedAheadCount = new AtomicInteger();
  private volatile boolean aheadStarted;
  private volatile boolean specActive;
  private final ConcurrentLinkedDeque<String> urgentQueue = new ConcurrentLinkedDeque<>();
  private final ConcurrentLinkedQueue<String> specQueue = new ConcurrentLinkedQueue<>();
  private final Set<String> seenSpec = ConcurrentHashMap.newKeySet();
  private final AtomicInteger specQueued = new AtomicInteger();
  private final Object specSignal = new Object();
  private final ThreadLocal<DexFile[]> threadViews = new ThreadLocal<>();

  private AppClasses(Path apk, String root, AppClasses target, String hintName, boolean async) throws IOException {
    this.apkPath = apk;
    this.root = root;
    this.target = target;
    this.hintName = hintName;
    if (async) {
      Thread init = new Thread(this::initSafe, "apksim-dex-init");
      init.setDaemon(true);
      init.start();
    } else {
      initDexAndCache();
    }
  }

  private static volatile AppClasses activeApp;

  static void onFirstFrame() {
    firstFrame = true;
    AppClasses a = activeApp;
    if (a != null) {
      if ("verbose".equals(System.getProperty("apksim.timing"))) {
        System.err.printf("[apksim] translate stats: main=%d ahead=%d specQueued=%d seeds=%d%n",
            a.translatedCount.get(), a.translatedAheadCount.get(), a.specQueued.get(), AppInfo.startupClasses().size());
      }
      synchronized (a.specSignal) {
        a.specSignal.notifyAll();
      }
      // Drop unused speculative translations and BFS state so they don't stay in heap after first frame.
      a.urgentQueue.clear();
      a.specQueue.clear();
      a.seenSpec.clear();
      a.ahead.clear();
    }
  }

  /** Waits briefly for any in-flight background DEX cache writes before the process halts. */
  static void flushDexCache() {
    long deadline = System.nanoTime() + 1_500_000_000L;
    while (DEX_PENDING.get() > 0 && System.nanoTime() < deadline) {
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void enqueueDexWrite(Path file, ByteBuffer buf) {
    DEX_PENDING.incrementAndGet();
    DEX_WRITES.add(new DexWrite(file, buf));
    synchronized (DEX_WRITER_LOCK) {
      if (dexWriterThread == null || !dexWriterThread.isAlive()) {
        Thread t = new Thread(() -> {
          while (true) {
            DexWrite w = DEX_WRITES.poll();
            if (w == null) {
              synchronized (DEX_WRITER_LOCK) {
                if (DEX_WRITES.isEmpty()) {
                  dexWriterThread = null;
                  return;
                }
              }
              continue;
            }
            try {
              Files.createDirectories(w.file.getParent());
              Path tmp = w.file.resolveSibling(w.file.getFileName() + Storage.tmpTag());
              try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer src = w.buf.duplicate();
                src.position(0);
                while (src.hasRemaining()) out.write(src);
              }
              Files.move(tmp, w.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
              // Next launch inflates again.
            } finally {
              DEX_PENDING.decrementAndGet();
            }
          }
        }, "apksim-dex-cache-write");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        dexWriterThread = t;
        t.start();
      }
    }
  }

  private void initSafe() {
    try {
      initDexAndCache();
    } catch (IOException e) {
      initError = e;
      ready.countDown();
    } catch (Throwable t) {
      initError = new IOException("DEX init failed", t);
      ready.countDown();
    }
  }

  private void ensureReady() {
    if (ready.getCount() == 0) {
      if (initError != null) throw new java.io.UncheckedIOException(initError);
      return;
    }
    try {
      ready.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    if (initError != null) throw new java.io.UncheckedIOException(initError);
  }

  private void initDexAndCache() throws IOException {
    List<DexEntry> entries = scanDexEntries(apkPath);
    if (entries != null) {
      // classes.dex must win over classes2.dex and so on.
      entries.sort((x, y) -> {
        int c = Integer.compare(x.name.length(), y.name.length());
        return c != 0 ? c : x.name.compareTo(y.name);
      });
      int n = entries.size();
      DexFile[] loadedDex = new DexFile[n];
      int[][] loadedDefs = new int[n][];
      String[] keys = new String[n];
      String cacheDir = System.getProperty("apksim.cache");
      int threads = Math.min(n, Math.max(1, Runtime.getRuntime().availableProcessors()));
      try (FileChannel ch = FileChannel.open(apkPath, StandardOpenOption.READ)) {
        if (threads <= 1) {
          for (int i = 0; i < n; i++) loadOneDex(ch, entries.get(i), cacheDir, i, loadedDex, loadedDefs, keys);
        } else {
          ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "apksim-dex-inflate");
            t.setDaemon(true);
            return t;
          });
          try {
            List<CompletableFuture<Void>> fs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
              final int idx = i;
              final DexEntry de = entries.get(i);
              fs.add(CompletableFuture.runAsync(() -> {
                try {
                  loadOneDex(ch, de, cacheDir, idx, loadedDex, loadedDefs, keys);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              }, pool));
            }
            for (CompletableFuture<Void> f : fs) f.join();
          } catch (java.util.concurrent.CompletionException ce) {
            if (ce.getCause() instanceof java.io.UncheckedIOException u) throw u.getCause();
            throw new IOException("parallel DEX load failed", ce.getCause());
          } finally {
            pool.shutdown();
          }
        }
      }
      for (int i = 0; i < n; i++) {
        dexes.add(loadedDex[i]);
        defsByType.add(loadedDefs[i]);
      }
      cache = hintName == null ? null : ClassCache.open(keys, hintName);
    } else {
      initDexViaZipFile();
    }
    ready.countDown();
    // Kick off background acceleration immediately (while the main thread is still booting the framework).
    if (cache != null) {
      if (!cache.hints().isEmpty()) {
        startTranslateAhead();
        startLoadAhead();
      } else if (SPECULATE && "/app/".equals(root) && System.getProperty("apksim.instrument") == null) {
        startSpeculateAhead();
      }
    }
  }

  private void loadOneDex(FileChannel ch, DexEntry z, String cacheDir, int idx,
      DexFile[] loadedDex, int[][] loadedDefs, String[] keys) throws IOException {
    ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    readFully(ch, local, z.localHeader);
    if (local.getInt(0) != 0x04034b50) throw new IOException("bad local header for " + z.name);
    long dataOffset = z.localHeader + 30 + (local.getShort(26) & 0xffff) + (local.getShort(28) & 0xffff);
    ByteBuffer data;
    if (z.method == 0) {
      data = ch.map(FileChannel.MapMode.READ_ONLY, dataOffset, z.uncompSize);
    } else {
      data = inflatedDirect(ch, dataOffset, z, cacheDir);
    }
    DexFile dex = new DexFile(data);
    loadedDex[idx] = dex;
    loadedDefs[idx] = dex.classDefsByType();
    keys[idx] = String.format("%08x-%d", z.crc, z.uncompSize);
  }

  private static void readFully(FileChannel ch, ByteBuffer dst, long pos) throws IOException {
    while (dst.hasRemaining()) {
      int r = ch.read(dst, pos);
      if (r < 0) throw new IOException("unexpected EOF reading APK");
      pos += r;
    }
    dst.flip();
  }

  private static ByteBuffer inflatedDirect(FileChannel ch, long dataOffset, DexEntry z, String cacheDir) throws IOException {
    Path file = cacheDir == null ? null : Path.of(cacheDir, "dex", String.format("%08x-%d.dex", z.crc, z.uncompSize));
    if (file != null && file.toFile().length() == z.uncompSize) {
      try (FileChannel dch = FileChannel.open(file, StandardOpenOption.READ)) {
        return dch.map(FileChannel.MapMode.READ_ONLY, 0, z.uncompSize);
      }
    }
    // Direct-to-direct native zlib inflate: zero Java heap allocation for 68+ MB of DEX (avoids GC pauses & Old Gen promotion).
    ByteBuffer comp = ch.map(FileChannel.MapMode.READ_ONLY, dataOffset, z.compSize);
    ByteBuffer out = ByteBuffer.allocateDirect(z.uncompSize);
    Inflater inf = new Inflater(true);
    try {
      inf.setInput(comp);
      while (out.hasRemaining()) {
        int n = inf.inflate(out);
        if (n == 0) {
          if (inf.finished() || inf.needsDictionary() || inf.needsInput()) break;
        }
      }
      if (out.position() != z.uncompSize) {
        throw new IOException("truncated DEX inflate for " + z.name + ": " + out.position() + "/" + z.uncompSize);
      }
    } catch (DataFormatException e) {
      throw new IOException("corrupt DEX " + z.name, e);
    } finally {
      inf.end();
    }
    out.flip();
    if (file != null) enqueueDexWrite(file, out);
    return out;
  }

  /** Fallback for zip64 APKs where the fast central-directory scan returns null. */
  private void initDexViaZipFile() throws IOException {
    ZipFile zip = zipFile();
    List<ZipEntry> entries = new ArrayList<>();
    Enumeration<? extends ZipEntry> e = zip.entries();
    while (e.hasMoreElements()) {
      ZipEntry z = e.nextElement();
      if (DEX.matcher(z.getName()).matches()) entries.add(z);
    }
    entries.sort((x, y) -> {
      int c = Integer.compare(x.getName().length(), y.getName().length());
      return c != 0 ? c : x.getName().compareTo(y.getName());
    });
    String[] keys = new String[entries.size()];
    String cacheDir = System.getProperty("apksim.cache");
    for (int i = 0; i < entries.size(); i++) {
      ZipEntry z = entries.get(i);
      Path file = cacheDir == null ? null : Path.of(cacheDir, "dex", String.format("%08x-%d.dex", z.getCrc(), z.getSize()));
      ByteBuffer data;
      if (file != null && file.toFile().length() == z.getSize()) {
        try (FileChannel dch = FileChannel.open(file, StandardOpenOption.READ)) {
          data = dch.map(FileChannel.MapMode.READ_ONLY, 0, z.getSize());
        }
      } else {
        byte[] bytes;
        try (InputStream in = zip.getInputStream(z)) {
          bytes = in.readAllBytes();
        }
        if (file != null) enqueueDexWrite(file, ByteBuffer.wrap(bytes));
        data = ByteBuffer.wrap(bytes);
      }
      DexFile dex = new DexFile(data);
      keys[i] = String.format("%08x-%d", z.getCrc(), z.getSize());
      dexes.add(dex);
      defsByType.add(dex.classDefsByType());
    }
    cache = hintName == null ? null : ClassCache.open(keys, hintName);
  }

  /** Single-pass scan of the APK's ZIP central directory for all classes*.dex entries, or null for zip64. */
  private static List<DexEntry> scanDexEntries(Path apk) throws IOException {
    try (FileChannel ch = FileChannel.open(apk, StandardOpenOption.READ)) {
      long size = ch.size();
      int tail = (int) Math.min(size, 0xffff + 22);
      ByteBuffer end = ByteBuffer.allocate(tail).order(ByteOrder.LITTLE_ENDIAN);
      readFully(ch, end, size - tail);
      int eocd = -1;
      for (int i = tail - 22; i >= 0; i--) {
        if (end.getInt(i) == 0x06054b50) {
          eocd = i;
          break;
        }
      }
      if (eocd < 0) return null;
      long cdSize = end.getInt(eocd + 12) & 0xffffffffL;
      long cdOffset = end.getInt(eocd + 16) & 0xffffffffL;
      if (cdOffset == 0xffffffffL || cdSize == 0xffffffffL || cdSize > Integer.MAX_VALUE) return null;
      ByteBuffer cd = ByteBuffer.allocate((int) cdSize).order(ByteOrder.LITTLE_ENDIAN);
      readFully(ch, cd, cdOffset);
      List<DexEntry> out = new ArrayList<>();
      for (int p = 0; p + 46 <= cdSize && cd.getInt(p) == 0x02014b50; ) {
        int method = cd.getShort(p + 10) & 0xffff;
        long crc = cd.getInt(p + 16) & 0xffffffffL;
        long compSize = cd.getInt(p + 20) & 0xffffffffL;
        long uncompSize = cd.getInt(p + 24) & 0xffffffffL;
        int nameLen = cd.getShort(p + 28) & 0xffff;
        int extraLen = cd.getShort(p + 30) & 0xffff;
        int commentLen = cd.getShort(p + 32) & 0xffff;
        long header = cd.getInt(p + 42) & 0xffffffffL;
        if (nameLen >= 11 && nameLen <= 16) {
          byte[] name = new byte[nameLen];
          cd.get(p + 46, name);
          String n = new String(name, StandardCharsets.UTF_8);
          if (DEX.matcher(n).matches()) {
            if (header == 0xffffffffL || compSize == 0xffffffffL || uncompSize == 0xffffffffL
                || compSize > Integer.MAX_VALUE || uncompSize > Integer.MAX_VALUE
                || (method != 0 && method != 8)) {
              return null; // zip64 or unsupported compression: fall back to ZipFile
            }
            out.add(new DexEntry(n, method, crc, (int) compSize, (int) uncompSize, header));
          }
        }
        p += 46 + nameLen + extraLen + commentLen;
      }
      return out;
    }
  }

  private ZipFile zipFile() throws IOException {
    ZipFile z = lazyZip;
    if (z != null) return z;
    synchronized (this) {
      if (lazyZip == null) lazyZip = new ZipFile(apkPath.toFile());
      return lazyZip;
    }
  }

  private DexFile dexView(int idx) {
    DexFile[] views = threadViews.get();
    if (views == null) {
      views = new DexFile[dexes.size()];
      threadViews.set(views);
    }
    DexFile v = views[idx];
    if (v == null) views[idx] = v = dexes.get(idx).view();
    return v;
  }

  /**
   * Where the APK defines class "a/b/C" ({dex, classDef}), or null. The first DEX file that has it wins, as on
   * Android (classes.dex, then classes2.dex...).
   */
  private int[] find(String name) {
    ensureReady();
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

  /** The class's content hash (see {@link dev.apksim.dex.ClassHash}), worked out when the cache asks, on a view of its DEX file. */
  private java.util.function.Supplier<byte[]> hashOf(int[] at, String name) {
    return () -> dev.apksim.dex.ClassHash.of(dexes.get(at[0]).view(), at[1]);
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

  /**
   * A class's place in the hierarchy wherever the app defines it (its other DEX files, the test APK's), or null. R8
   * puts a class and its superclass in different files.
   */
  private dev.apksim.dex.Hierarchy.Shape shape(String cls, String name, String desc) {
    int[] at = find(cls);
    if (at == null) return target != null ? target.shape(cls, name, desc) : null;
    DexFile d = dexView(at[0]);
    return new dev.apksim.dex.Hierarchy.Shape(d.superclassOf(at[1]), d.interfacesOf(at[1]), d.declaresMethod(at[1], name, desc));
  }

  /** The cache's key for a hierarchy question: {@code NUL class NUL method descriptor}. */
  private static final char HIERARCHY = '\0';

  /** One byte that changes when the answer to a hierarchy question does (a hash: a collision keeps a stale entry). */
  private int shapeAnswer(String key) {
    int sep = key.indexOf(HIERARCHY, 1);
    String cls = key.substring(1, sep);
    String rest = key.substring(sep + 1);
    int paren = rest.indexOf('(');
    var sh = shape(cls, rest.substring(0, paren), rest.substring(paren));
    if (sh == null) return 0;
    int h = java.util.Objects.hash(sh.superclass(), java.util.Arrays.hashCode(sh.interfaces()), sh.declares());
    return 1 + Math.floorMod(h, 127); // 1..127: the cache keeps answers as signed bytes.
  }

  /** What a translation learned about a class it calls, or (a key starting with NUL) about the hierarchy; see {@link ClassCache}. */
  private int answer(String owner) {
    if (!owner.isEmpty() && owner.charAt(0) == HIERARCHY) return shapeAnswer(owner);
    int[] at = find(owner);
    if (at == null) return target != null ? target.answer(owner) : ClassCache.NOT_APP;
    return (dexes.get(at[0]).classAccess(at[1]) & ACC_INTERFACE) != 0 ? ClassCache.INTERFACE : ClassCache.CLASS;
  }

  /** Every class of this APK, by internal name (coverage reports the ones nothing ran too). */
  List<String> internalNames() {
    ensureReady();
    List<String> out = new ArrayList<>();
    for (DexFile d : dexes) {
      for (int i = 0; i < d.classDefsSize; i++) {
        String t = d.className(i);
        out.add(t.substring(1, t.length() - 1));
      }
    }
    return out;
  }

  /** What identifies the classes' content, cheaply: each DEX file's own checksum and size. */
  String fingerprint() {
    ensureReady();
    StringBuilder b = new StringBuilder();
    for (DexFile d : dexes) b.append(Integer.toHexString(d.i32(8))).append('.').append(d.classDefsSize).append(';');
    return b.toString();
  }

  /** A class's bytes without marking it loaded (the load hints would then define it at the next start). */
  byte[] peek(String internalName) {
    int[] at = find(internalName);
    if (at == null) return null;
    if (cache != null) {
      byte[] hit = cache.get(at[0], internalName, this::answer, hashOf(at, internalName));
      if (hit != null) return hit;
    }
    Ahead a = translateOne(dexView(at[0]), at, internalName, false, true);
    return a == null ? null : a.bytes;
  }

  private void commitAhead(String internalName, int[] at, Ahead a) {
    if (a == null) return;
    if (cache != null && a.answers != null) {
      cache.put(a.dex, internalName, a.bytes, a.answers, hashOf(at, internalName));
    }
    if (specActive && !firstFrame && a.refs != null) {
      enqueueUrgentBatch(a.refs);
    }
  }

  byte[] translate(String internalName) {
    int[] at = find(internalName);
    if (at == null) return null;
    asked.add(internalName);
    if (cache != null) {
      if (!aheadStarted) startTranslateAhead();
      cache.loaded(internalName);
      Ahead done = ahead.remove(internalName);
      if (done != null) {
        commitAhead(internalName, at, done);
        return done.bytes;
      }
      byte[] hit = cache.get(at[0], internalName, this::answer, hashOf(at, internalName));
      if (hit != null) return hit;
    }
    // Per-class coordination: if a worker is already translating this class, wait only for it.
    CompletableFuture<Ahead> mine = new CompletableFuture<>();
    CompletableFuture<Ahead> existing = inFlight.putIfAbsent(internalName, mine);
    if (existing != null) {
      Ahead res = existing.join();
      ahead.remove(internalName);
      commitAhead(internalName, at, res);
      return res == null ? null : res.bytes;
    }
    try {
      // Re-check ahead after claiming inFlight in case a worker finished right before putIfAbsent.
      Ahead done = ahead.remove(internalName);
      if (done != null) {
        mine.complete(done);
        commitAhead(internalName, at, done);
        return done.bytes;
      }
      long t0 = System.nanoTime();
      boolean collectRefs = specActive && !firstFrame;
      Ahead a = translateOne(dexView(at[0]), at, internalName, collectRefs, true);
      mine.complete(a);
      if (a == null) return null;
      if (collectRefs && a.refs != null) {
        enqueueUrgentBatch(a.refs);
      }
      long elapsed = System.nanoTime() - t0;
      translateNanos = translateNanosCount.addAndGet(elapsed);
      int n = translatedCount.incrementAndGet();
      translated = n;
      // serve shows this to a tab waiting on a first launch (a large app takes minutes to translate on a few CPUs).
      if (n % 500 == 0 && !Boolean.getBoolean("apksim.quiet")) {
        System.err.printf("[apksim] translated %d classes so far%n", n);
      }
      return a.bytes;
    } catch (Throwable t) {
      mine.complete(null);
      throw t;
    } finally {
      inFlight.remove(internalName, mine);
    }
  }

  private Ahead translateOne(DexFile dex, int[] at, String internalName, boolean collectRefs, boolean putCache) {
    byte[] out;
    Map<String, Byte> answers = new LinkedHashMap<>();
    List<String> refs = collectRefs ? new ArrayList<>(16) : null;
    Consumer<String> sink = refs == null ? null : refs::add;
    try {
      out = new ClassTranslator(dex, at[1], owner -> {
        answers.computeIfAbsent(owner, o -> (byte) answer(o));
        return isInterface(owner);
      }, (cls, name, desc) -> {
        answers.computeIfAbsent(HIERARCHY + cls + HIERARCHY + name + desc, o -> (byte) answer(o));
        return shape(cls, name, desc);
      }, sink).translate();
    } catch (RuntimeException e) {
      // The class loader would report this only as "class not found".
      System.err.println("[apksim] cannot translate " + internalName);
      e.printStackTrace();
      return null;
    }
    if (putCache && cache != null) cache.put(at[0], internalName, out, answers, hashOf(at, internalName));
    String[] refArr = refs == null || refs.isEmpty() ? null : refs.toArray(new String[0]);
    return new Ahead(out, refArr, putCache ? null : answers, at[0], at[1]);
  }

  private void startTranslateAhead() {
    if (aheadStarted || cache == null) return;
    synchronized (this) {
      if (aheadStarted) return;
      aheadStarted = true;
    }
    if (cache.hints().isEmpty()) return;
    Thread planner = new Thread(this::translateAhead, "apksim-translate");
    planner.setDaemon(true);
    planner.start();
  }

  /**
   * Translates, on background threads, the classes this app loaded last time that the cache cannot supply (their
   * DEX file changed). Several threads soon get ahead of the main thread, which then finds most classes ready.
   */
  private void translateAhead() {
    List<String> todo = new ArrayList<>();
    for (String name : cache.hints()) {
      // Own pack only here: looking for the class by content hashes it, which the workers below do in parallel.
      int[] at = find(name);
      if (at != null && !cache.has(at[0], name, this::answer, null)) todo.add(name);
    }
    if (todo.isEmpty()) return;
    AtomicInteger next = new AtomicInteger();
    int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    AtomicInteger running = new AtomicInteger(threads);
    long t0 = System.nanoTime();
    for (int t = 0; t < threads; t++) {
      Thread worker = new Thread(() -> {
        DexFile[] views = new DexFile[dexes.size()];
        for (int i; (i = next.getAndIncrement()) < todo.size(); ) {
          String name = todo.get(i);
          if (asked.contains(name)) continue;
          int[] at = find(name);
          if (views[at[0]] == null) views[at[0]] = dexes.get(at[0]).view();
          if (cache.has(at[0], name, this::answer, () -> dev.apksim.dex.ClassHash.of(views[at[0]], at[1]))) continue;
          CompletableFuture<Ahead> mine = new CompletableFuture<>();
          if (inFlight.putIfAbsent(name, mine) != null) continue;
          try {
            Ahead a = translateOne(views[at[0]], at, name, false, true);
            mine.complete(a);
            if (a != null && !asked.contains(name)) {
              ahead.put(name, a);
              translatedAheadCount.incrementAndGet();
            }
          } catch (Throwable ex) {
            mine.complete(null);
          } finally {
            inFlight.remove(name, mine);
          }
        }
        if (running.decrementAndGet() == 0 && !Boolean.getBoolean("apksim.quiet")) {
          System.err.printf("[apksim] translated %d of %d classes missing from their pack ahead in %d ms%n",
              translatedAheadCount.get(), todo.size(), (System.nanoTime() - t0) / 1_000_000);
        }
      }, "apksim-translate-" + t);
      worker.setDaemon(true);
      worker.start();
    }
  }

  /**
   * First-ever launch of a brand-new APK (no hints yet): seeds a bounded parallel pre-translator from the manifest's
   * entry points (Application, AppComponentFactory, ContentProviders, androidx.startup Initializers, launcher Activity)
   * and then stays laser-locked 1 hop ahead of every class the main thread loads. Speculative translations are only
   * committed to {@link ClassCache} if the app actually loads them. Stops immediately once the first frame is drawn.
   */
  private void startSpeculateAhead() {
    List<String> seeds = AppInfo.startupClasses();
    if (seeds.isEmpty()) return;
    specActive = true;
    for (String s : seeds) enqueueSpec(s, true);
    int cpus = Runtime.getRuntime().availableProcessors();
    // Keep background workers well within P-core headroom so the main thread and JIT are never preempted.
    int threads = Integer.getInteger("apksim.speculateThreads", Math.max(2, Math.min(3, cpus / 4)));
    for (int t = 0; t < threads; t++) {
      Thread w = new Thread(this::speculateWorker, "apksim-spec-" + t);
      w.setDaemon(true);
      w.setPriority(Thread.NORM_PRIORITY - 1);
      w.start();
    }
  }

  /**
   * Pushes a newly loaded class's references onto the front of {@link #urgentQueue} in reverse order so its
   * superclass, interfaces, fields, and {@code <clinit>}/{@code <init>} targets are translated first.
   */
  private void enqueueUrgentBatch(String[] refs) {
    if (firstFrame) return;
    boolean added = false;
    for (int i = refs.length - 1; i >= 0; i--) {
      String ref = refs[i];
      if (ref == null || ref.isEmpty() || ref.charAt(0) == '[') continue;
      if (!seenSpec.add(ref)) continue;
      if (find(ref) == null) continue;
      urgentQueue.addFirst(ref);
      added = true;
    }
    if (added) {
      synchronized (specSignal) {
        specSignal.notifyAll();
      }
    }
  }

  private void enqueueSpec(String ref, boolean urgent) {
    if (firstFrame || ref == null || ref.isEmpty() || ref.charAt(0) == '[') return;
    if (!urgent && specQueued.get() >= SPEC_CAP) return;
    if (!seenSpec.add(ref)) return;
    if (find(ref) == null) return;
    if (urgent) {
      urgentQueue.addLast(ref);
    } else {
      if (specQueued.incrementAndGet() > SPEC_CAP) return;
      specQueue.add(ref);
    }
    synchronized (specSignal) {
      specSignal.notify();
    }
  }

  private void speculateWorker() {
    DexFile[] views = new DexFile[dexes.size()];
    while (!firstFrame) {
      String name = urgentQueue.pollFirst();
      if (name == null) name = specQueue.poll();
      if (name == null) {
        synchronized (specSignal) {
          if (firstFrame) return;
          if (urgentQueue.isEmpty() && specQueue.isEmpty()) {
            try {
              specSignal.wait(25);
            } catch (InterruptedException e) {
              return;
            }
          }
        }
        continue;
      }
      if (asked.contains(name) || ahead.containsKey(name)) continue;
      int[] at = find(name);
      if (at == null) continue;
      if (cache != null && cache.has(at[0], name, this::answer, null)) continue;
      CompletableFuture<Ahead> mine = new CompletableFuture<>();
      if (inFlight.putIfAbsent(name, mine) != null) continue;
      try {
        if (views[at[0]] == null) views[at[0]] = dexes.get(at[0]).view();
        Ahead a = translateOne(views[at[0]], at, name, true, false);
        mine.complete(a);
        if (a != null && !firstFrame && !asked.contains(name)) {
          ahead.put(name, a);
          if (firstFrame) {
            ahead.remove(name, a);
            return;
          }
          translatedAheadCount.incrementAndGet();
          // Bounded cold BFS from manifest seeds only while specQueued < SPEC_CAP; live main-thread loads drive urgentQueue directly.
          if (a.refs != null && specQueued.get() < SPEC_CAP) {
            for (String ref : a.refs) enqueueSpec(ref, false);
          }
        }
      } catch (Throwable ex) {
        mine.complete(null);
      } finally {
        inFlight.remove(name, mine);
      }
    }
  }

  /**
   * Loads (defines, without initializing: no app code runs) the classes the app loaded last time, in that order, on
   * background threads, while the main thread starts the framework and then the app.
   */
  void loadAhead() {
    // When async init is running, initDexAndCache() kicks off startLoadAhead() as soon as DEX + cache are ready.
    if (ready.getCount() == 0) startLoadAhead();
  }

  private volatile boolean loadAheadStarted;

  private void startLoadAhead() {
    if (loadAheadStarted || cache == null || !Boolean.parseBoolean(System.getProperty("apksim.loadAhead", "true"))) return;
    synchronized (this) {
      if (loadAheadStarted) return;
      loadAheadStarted = true;
    }
    List<String> names = cache.hints();
    if (names.isEmpty()) return;
    AtomicInteger next = new AtomicInteger();
    int threads = Integer.getInteger("apksim.loadAheadThreads", Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
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
    return translatedAheadCount.get();
  }

  int cached() {
    return cache == null ? 0 : cache.hits;
  }

  private InputStream open(String path) throws IOException {
    if (path.startsWith(root)) path = path.substring(root.length());
    if (path.endsWith(".class")) {
      String name = path.substring(0, path.length() - 6);
      byte[] b = find(name) != null ? translate(name) : null;
      if (b != null) return new ByteArrayInputStream(b);
    } else {
      ZipFile zip = zipFile();
      ZipEntry z = zip.getEntry(path);
      if (z != null) {
        // Mockito's mock maker: a JVM one for the ART makers test APKs name (MockMakers, --mock-maker).
        if (path.equals(MockMakers.RESOURCE)) return MockMakers.serve(zip.getInputStream(z));
        return zip.getInputStream(z);
      }
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
    AppClasses c = install(apk, "/app/", null, System.getProperty("apksim.package"), true);
    activeApp = c;
    return c;
  }

  /**
   * Adds a test APK's classes after the app's, as Android does for an instrumentation that targets the app: both run
   * in one process and the test calls the app's classes directly. AGP leaves out of the test APK what the app has.
   */
  static AppClasses installTest(Path apk, AppClasses app) throws Exception {
    return install(apk, "/test/", app, System.getProperty("apksim.package") + ".test", false);
  }

  /**
   * An APK whose classes stay apart from the app's: an instrumentation installed over adb, which brings its own copies
   * of libraries the app also has (Kotlin, AndroidX Test, often minified into each other). Its loader asks
   * {@link #translate} itself (LiveInstrumentation.Loader); nothing is added to the class path.
   */
  static AppClasses isolated(Path apk) throws IOException {
    return new AppClasses(apk, "/isolated/", null, null, false);
  }

  /** The test APK's classes, in DEX order, as "a.b.C". */
  List<String> classNames() {
    ensureReady();
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (DexFile dex : dexes) {
      for (int i = 0; i < dex.classDefsSize; i++) {
        String d = dex.className(i);
        out.add(d.substring(1, d.length() - 1).replace('/', '.'));
      }
    }
    return new ArrayList<>(out);
  }

  private static AppClasses install(Path apk, String rootPath, AppClasses target, String hintName, boolean async) throws Exception {
    AppClasses classes = new AppClasses(apk, rootPath, target, hintName, async);
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
