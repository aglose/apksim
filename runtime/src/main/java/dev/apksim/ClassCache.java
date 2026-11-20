package dev.apksim;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translated classes kept between launches. There is one pack per DEX file, named by the DEX's CRC, so a rebuilt
 * APK reuses the classes of every DEX file that did not change (debug builds put each library in its own).
 *
 * <p>A DEX file whose CRC is new is not all new: a rebuilt debug app changes the checksum of nearly every file while
 * ~99.7% of its classes are what they were (a string added anywhere moves every index after it). So each saved
 * class is also recorded in an index, by a hash of its own definition with the indices resolved to names
 * ({@link dev.apksim.dex.ClassHash}), pointing at the pack that holds it. A class missing from its DEX file's pack is
 * looked up there, and a hit is used and copied into the new pack, so the next launch of this build takes the
 * ordinary path. {@code -Dapksim.classContent=false} turns it off.
 *
 * <p>A translation reads two things outside its own class: whether each class it calls is an interface, and, for a
 * super call that names an interface, the superclasses and interfaces on the way to it (AppClasses keeps each of
 * those as a hash under a key that starts with NUL). Each entry records those answers and is used only while they
 * still hold, so a pack stays valid whatever the other DEX files
 * now contain. Packs live under a directory named for this runtime build and its class path, since a different
 * translator writes different code.
 *
 * <p>It also remembers which classes each app loaded, in order, so the next launch can translate the ones whose DEX
 * file changed on other threads while the framework starts ({@link #hints}).
 */
final class ClassCache {
  private static final int MAGIC = 0x41584331; // "AXC1"
  /** An owner's answer: an app class, an app interface, or not an app class (the framework, which is fixed). */
  static final byte CLASS = 0, INTERFACE = 1, NOT_APP = 2;

  private record Entry(ByteBuffer pack, int offset, int length, String[] owners, byte[] answers) {}

  /** {@code hash} is known for a class found by content; a translated one carries {@code lazy}, worked out at the save. */
  private record Fresh(byte[] bytes, String[] owners, byte[] answers, byte[] hash, Supplier<byte[]> lazy) {}

  /** A class's content hash as a map key. */
  private record Hash(long a, long b) {
    static Hash of(byte[] h) {
      ByteBuffer bb = ByteBuffer.wrap(h);
      return new Hash(bb.getLong(), bb.getLong());
    }
  }

  /** The caches this process opened (the app's, and a test APK's), for {@link #flush} on the way out. */
  private static final List<ClassCache> OPEN = new java.util.concurrent.CopyOnWriteArrayList<>();

  private final Path dir;
  /** Read only, where dir has nothing yet: a beamed instance's build cache (BeamSandbox), else null. */
  private final Path seed;
  /** Read only: earlier builds' class folders (a beamed instance's, issue 578), the content lookup's other source. */
  private final List<Path> siblings;
  private final Object saving = new Object();
  private final String[] keys;
  private final Map<String, Entry>[] loaded;
  private final Map<String, Fresh>[] fresh;
  private final Path hintFile;
  private final LinkedHashSet<String> hints = new LinkedHashSet<>();
  private final List<String> previous;
  private final boolean content;
  private final Path indexFile;
  /** Content hash to the key of the pack holding that class; read when the first class misses its own pack. */
  private Map<Hash, String> index;
  /** Earlier builds' index entries (read only, beam): consulted after {@link #index}, never written out. */
  private Map<Hash, String> siblingIndex;
  private final Map<String, Map<String, Entry>> otherPacks = new HashMap<>();
  private final Map<Hash, String> indexAdds = new HashMap<>();
  private volatile boolean exiting;
  private int hintsSaved;
  private int unsaved;
  int hits;
  /** How many of {@link #hits} came from another build's pack, by content. */
  int contentHits;

  @SuppressWarnings("unchecked")
  private ClassCache(Path dir, Path seed, List<Path> siblings, String[] keys, String pkg) {
    this.dir = dir;
    this.seed = seed;
    this.siblings = siblings;
    this.keys = keys;
    content = Boolean.parseBoolean(System.getProperty("apksim.classContent", "true"))
        && !Boolean.getBoolean("apksim.debugInfo") && dev.apksim.dex.Coverage.cacheKey().isEmpty()
        && pkg != null && !pkg.isEmpty();
    indexFile = pkg == null || pkg.isEmpty() ? null : dir.resolve(pkg + ".index");
    hintFile = pkg == null || pkg.isEmpty() ? null : dir.resolve(pkg + ".classes");
    Path hintsIn = hintFile == null ? null : read(hintFile.getFileName().toString());
    if (hintsIn != null && Files.isRegularFile(hintsIn)) {
      try {
        hints.addAll(Files.readAllLines(hintsIn));
      } catch (IOException ignored) {
        // Hints only speed things up.
      }
    }
    hintsSaved = hints.size();
    previous = List.copyOf(hints);
    loaded = new Map[keys.length];
    fresh = new Map[keys.length];
    for (int i = 0; i < keys.length; i++) fresh[i] = new HashMap<>();
  }

  /**
   * The cache for these DEX files (keys from their CRCs), or null when it is off or this runtime is not a jar. Load
   * hints are kept under {@code hintName} (the package, or the package plus ".test" for its test APK).
   */
  static ClassCache open(String[] keys, String hintName) {
    if (!Boolean.parseBoolean(System.getProperty("apksim.classCache", "true"))) return null;
    String runtime = runtimeHash();
    if (runtime == null) return null;
    // Classes translated with debug info (--debug) are kept apart, so neither kind is ever used for the other.
    if (Boolean.getBoolean("apksim.debugInfo")) runtime += "-debug";
    else if (!dev.apksim.dex.ClassTranslator.PEEPHOLE) runtime += "-raw";
    runtime += dev.apksim.dex.Probes.cacheKey();  // test-impact recording: probed classes
    runtime += dev.apksim.dex.Coverage.cacheKey();  // coverage: classes with counters
    Path root = BeamSandbox.ON ? BeamSandbox.classCacheRoot() : Path.of(System.getProperty("apksim.cacheRoot"), "classes");
    Path seed = BeamSandbox.ON ? BeamSandbox.classCacheSeed() : null;
    List<Path> siblings = new java.util.ArrayList<>();
    if (BeamSandbox.ON) for (Path p : BeamSandbox.classCacheSiblings()) siblings.add(p.resolve(runtime));
    ClassCache cache = new ClassCache(root.resolve(runtime), seed == null ? null : seed.resolve(runtime), siblings, keys, hintName);
    Thread saver = new Thread(cache::saveLoop, "apksim-class-cache");
    saver.setDaemon(true);
    saver.setPriority(Thread.MIN_PRIORITY);
    saver.start();
    OPEN.add(cache);
    return cache;
  }

  /** A file of this cache to read: this process's own, else the seed's (a beamed instance's build cache). */
  private Path read(String name) {
    Path own = dir.resolve(name);
    return seed == null || Files.exists(own) ? own : seed.resolve(name);
  }

  /** A pack to read for the content lookup: this process's, the seed's, else an earlier build's (read only). */
  private Path packFile(String name) {
    Path p = read(name);
    if (Files.exists(p)) return p;
    for (Path s : siblings) {
      Path q = s.resolve(name);
      if (Files.isRegularFile(q)) return q;
    }
    return p;
  }

  /** Classes the last launches of this app loaded, in the order they first loaded them. */
  List<String> hints() {
    return previous;
  }

  /** Notes that the app loaded this class, for the next launch's {@link #hints}. */
  synchronized void loaded(String name) {
    if (hints.add(name)) unsaved++;
  }

  /** Whether a usable translation of the class is cached. */
  boolean has(int dex, String name, ToIntFunction<String> answer, Supplier<byte[]> hash) {
    synchronized (this) {
      if (valid(dex, name, answer) != null || fresh[dex].containsKey(name)) return true;
      if (hash == null || !contentPossible()) return false;
    }
    byte[] h = hash.get(); // outside the lock: the walk is the slow part
    synchronized (this) {
      return fresh[dex].containsKey(name) || (h != null && byContent(dex, name, answer, h) != null);
    }
  }

  /** Whether a content lookup can find anything: it is on, and the index has an entry (read at the first ask). */
  private boolean contentPossible() {
    if (!content) return false;
    if (index == null) index = loadIndex();
    return !index.isEmpty() || !siblingIndex().isEmpty();
  }

  private Entry valid(int dex, String name, ToIntFunction<String> answer) {
    if (loaded[dex] == null) loaded[dex] = load(packFile(keys[dex] + ".pack"));
    return check(loaded[dex].get(name), answer);
  }

  private static Entry check(Entry e, ToIntFunction<String> answer) {
    if (e == null) return null;
    for (int i = 0; i < e.owners.length; i++) {
      if (answer.applyAsInt(e.owners[i]) != e.answers[i]) return null;
    }
    return e;
  }

  /**
   * The entry of an identical class (by content hash) in a pack of another build, if the index knows one. A hit is
   * kept to be saved into this DEX file's own pack, so the next launch of this build need not look.
   */
  private Entry byContent(int dex, String name, ToIntFunction<String> answer, byte[] h) {
    if (!content || h == null) return null;
    if (fresh[dex].containsKey(name)) return null;  // taken meanwhile by another thread: callers look in `fresh`
    if (index == null) index = loadIndex();
    String key = index.get(Hash.of(h));
    if (key == null) key = siblingIndex().get(Hash.of(h));
    if (key == null || key.equals(keys[dex])) return null;
    Map<String, Entry> pack = otherPacks.get(key);
    if (pack == null) otherPacks.put(key, pack = load(packFile(key + ".pack")));
    Entry e = check(pack.get(name), answer);
    if (e == null) return null;
    byte[] bytes = new byte[e.length];
    e.pack.get(e.offset, bytes);
    fresh[dex].put(name, new Fresh(bytes, e.owners, e.answers, h, null));
    unsaved++;
    contentHits++;
    return e;
  }

  /** The cached translation of a class in DEX file {@code dex}, if its recorded answers still hold. */
  byte[] get(int dex, String name, ToIntFunction<String> answer, Supplier<byte[]> hash) {
    synchronized (this) {
      Entry e = valid(dex, name, answer);
      if (e != null) return copy(e);
      Fresh f = fresh[dex].get(name);
      if (f != null) {
        // Translated or taken from another build's pack since the last save, and not yet in this one's pack.
        for (int i = 0; i < f.owners.length; i++) {
          if (answer.applyAsInt(f.owners[i]) != f.answers[i]) return null;
        }
        hits++;
        return f.bytes.clone();
      }
      if (!contentPossible()) return null;
    }
    byte[] h = hash.get(); // outside the lock: the walk is the slow part
    synchronized (this) {
      Entry e = byContent(dex, name, answer, h);
      if (e != null) return copy(e);
      Fresh f = fresh[dex].get(name);  // put or found by another thread while this one hashed
      if (f == null) return null;
      for (int i = 0; i < f.owners.length; i++) {
        if (answer.applyAsInt(f.owners[i]) != f.answers[i]) return null;
      }
      hits++;
      return f.bytes.clone();
    }
  }

  private byte[] copy(Entry e) {
    byte[] out = new byte[e.length];
    e.pack.get(e.offset, out);
    hits++;
    return out;
  }

  void put(int dex, String name, byte[] bytes, Map<String, Byte> answers, Supplier<byte[]> hash) {
    // The hash (only for the index, which a later build reads) is worked out at the save, on the idle saver thread:
    // here it was on the thread that translates, and a launch that finds nothing to reuse paid for it (issue 571).
    putFresh(dex, name, bytes, answers, content ? hash : null);
  }

  private synchronized void putFresh(int dex, String name, byte[] bytes, Map<String, Byte> answers, Supplier<byte[]> lazy) {
    String[] owners = answers.keySet().toArray(new String[0]);
    byte[] a = new byte[owners.length];
    for (int i = 0; i < owners.length; i++) a[i] = answers.get(owners[i]);
    fresh[dex].put(name, new Fresh(bytes, owners, a, null, lazy));
    unsaved++;
  }

  /** Saves once translating has paused for a few seconds, so writing never competes with startup. */
  private void saveLoop() {
    int seen = 0;
    try {
      while (true) {
        Thread.sleep(3000);
        int now;
        synchronized (this) {
          now = unsaved;
        }
        if (now > 0 && now == seen) {
          synchronized (saving) {
            save(Long.MAX_VALUE);
          }
          seen = 0;
        } else {
          seen = now;
        }
      }
    } catch (InterruptedException ignored) {
      // Daemon thread: the process is going away.
    }
  }

  /** Saves what this process translated since the last save, before it exits (it halts, so no daemon gets to). */
  static void flush() {
    for (ClassCache cache : OPEN) cache.exiting = true;  // a saver in the middle of hashing lets go of `saving`
    for (ClassCache cache : OPEN) {
      synchronized (cache.saving) {
        synchronized (cache) {
          if (cache.unsaved == 0) continue;
        }
        cache.save(1_500_000_000L);
      }
    }
  }

  private void save(long hashBudgetNanos) {
    // Hash first, before anything leaves `fresh`, so a class stays findable meanwhile. The saver stops as an exit begins
    // (`exiting`: flush takes over, with a budget); past the budget the rest go unindexed.
    List<Fresh> work = new ArrayList<>();
    synchronized (this) {
      for (int i = 0; i < keys.length; i++) {
        for (Fresh f : fresh[i].values()) if (f.lazy != null) work.add(f);
      }
    }
    long deadline = hashBudgetNanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + hashBudgetNanos;
    Map<Fresh, byte[]> hashes = new IdentityHashMap<>();
    for (Fresh f : work) {
      if (hashBudgetNanos == Long.MAX_VALUE ? exiting : System.nanoTime() > deadline) break;
      try {
        byte[] h = f.lazy.get();
        if (h != null) hashes.put(f, h);
      } catch (RuntimeException ignored) {
        // Unindexed only: a later build translates it again.
      }
    }
    if (hashBudgetNanos == Long.MAX_VALUE && exiting) return;  // flush saves it all, under the same lock, next
    Map<Integer, Map<String, Fresh>> todo = new LinkedHashMap<>();
    List<String> names = null;
    synchronized (this) {
      if (hintFile != null && hints.size() != hintsSaved) {
        names = new ArrayList<>(hints);
        hintsSaved = names.size();
      }
      for (int i = 0; i < keys.length; i++) {
        if (fresh[i].isEmpty()) continue;
        if (loaded[i] == null) loaded[i] = load(read(keys[i] + ".pack"));
        todo.put(i, fresh[i]);
        for (Fresh f : fresh[i].values()) {
          byte[] h = f.hash != null ? f.hash : hashes.get(f);
          if (h != null) indexAdds.put(Hash.of(h), keys[i]);
        }
        fresh[i] = new HashMap<>();
      }
      unsaved = 0;
    }
    int written = 0;
    try {
      Files.createDirectories(dir);
      for (Map.Entry<Integer, Map<String, Fresh>> t : todo.entrySet()) {
        int dex = t.getKey();
        Map<String, Fresh> add = t.getValue();
        Path pack = dir.resolve(keys[dex] + ".pack");
        Path tmp = dir.resolve(keys[dex] + ".pack" + Storage.tmpTag());
        Map<String, Entry> old;
        synchronized (this) {
          old = loaded[dex];
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(add.size() + (int) old.keySet().stream().filter(k -> !add.containsKey(k)).count());
        for (Map.Entry<String, Fresh> f : add.entrySet()) {
          Fresh v = f.getValue();
          writeEntry(out, f.getKey(), v.owners, v.answers, v.bytes);
        }
        for (Map.Entry<String, Entry> o : old.entrySet()) {
          if (add.containsKey(o.getKey())) continue;
          Entry e = o.getValue();
          byte[] b = new byte[e.length];
          e.pack.get(e.offset, b);
          writeEntry(out, o.getKey(), e.owners, e.answers, b);
        }
        out.flush();
        Files.write(tmp, bytes.toByteArray());
        // Rename over the old pack: a process that mapped it keeps reading the old file.
        Files.move(tmp, pack, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Map<String, Entry> now = load(pack);
        synchronized (this) {
          loaded[dex] = now;
        }
        written += add.size();
      }
      if (!todo.isEmpty()) saveIndex();
      if (names != null) {
        Path tmp = dir.resolve(hintFile.getFileName() + Storage.tmpTag());
        Files.write(tmp, names);
        Files.move(tmp, hintFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
      if (!BeamSandbox.ON) sweep();  // a beamed instance's folder is new each launch, and the agent's to tidy
    } catch (IOException e) {
      System.err.println("[apksim] could not save translated classes: " + e);
      return;
    }
    if (!Boolean.getBoolean("apksim.quiet")) {
      if (contentHits > 0) System.err.printf("[apksim] saved %d translated classes (%d found by content in another build's pack)%n", written, contentHits);
      else System.err.printf("[apksim] saved %d translated classes%n", written);
    }
  }

  private static final int INDEX_MAGIC = 0x41584931; // "AXI1"

  /** The index on disk (empty when there is none or it is damaged: a lookup then finds nothing). */
  private Map<Hash, String> loadIndex() {
    return readIndex(indexFile == null ? null : read(indexFile.getFileName().toString()));
  }

  /** Earlier builds' indexes (a beamed instance's siblings), newest first; read once. */
  private synchronized Map<Hash, String> siblingIndex() {
    if (siblingIndex == null) {
      Map<Hash, String> m = new HashMap<>();
      if (content && indexFile != null) {
        for (Path s : siblings) {
          for (Map.Entry<Hash, String> e : readIndex(s.resolve(indexFile.getFileName().toString())).entrySet()) m.putIfAbsent(e.getKey(), e.getValue());
        }
      }
      siblingIndex = m;
    }
    return siblingIndex;
  }

  private static Map<Hash, String> readIndex(Path f) {
    Map<Hash, String> m = new HashMap<>();
    if (f == null || !Files.isRegularFile(f)) return m;
    try {
      ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f));
      if (b.getInt() != INDEX_MAGIC) return m;
      int nk = b.getInt();
      String[] names = new String[nk];
      for (int i = 0; i < nk; i++) names[i] = readString(b);
      int n = b.getInt();
      for (int i = 0; i < n; i++) {
        long hi = b.getLong();
        long lo = b.getLong();
        String k = names[b.getInt()];
        // A pack key names a file beside the index: never a path (an app's own instance wrote this file).
        if (!k.matches("[0-9a-zA-Z_-]{1,80}")) continue;
        m.put(new Hash(hi, lo), k);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[apksim] ignoring damaged class index " + f.getFileName() + ": " + e);
      return new HashMap<>();
    }
    return m;
  }

  /** Adds what this save wrote to the index, dropping entries whose pack is gone, and writes it. Under {@code saving}. */
  private void saveIndex() throws IOException {
    if (!content || indexFile == null) return;
    Map<Hash, String> all;
    synchronized (this) {
      if (index == null) index = loadIndex();
      index.putAll(indexAdds);
      indexAdds.clear();
      all = new HashMap<>(index);
    }
    Map<String, Boolean> alive = new HashMap<>();
    all.values().removeIf(k -> !alive.computeIfAbsent(k, key -> Files.exists(dir.resolve(key + ".pack"))));
    List<String> names = new ArrayList<>(new LinkedHashSet<>(all.values()));
    Map<String, Integer> at = new HashMap<>();
    for (int i = 0; i < names.size(); i++) at.put(names.get(i), i);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(all.size() * 20 + 1024);
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(INDEX_MAGIC);
    out.writeInt(names.size());
    for (String k : names) writeString(out, k);
    out.writeInt(all.size());
    for (Map.Entry<Hash, String> e : all.entrySet()) {
      out.writeLong(e.getKey().a());
      out.writeLong(e.getKey().b());
      out.writeInt(at.get(e.getValue()));
    }
    out.flush();
    Path tmp = dir.resolve(indexFile.getFileName() + Storage.tmpTag());
    Files.write(tmp, bytes.toByteArray());
    Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    synchronized (this) {
      index = all;
    }
  }

  private static void writeEntry(DataOutputStream out, String name, String[] owners, byte[] answers, byte[] bytes)
      throws IOException {
    writeString(out, name);
    out.writeShort(owners.length);
    for (int i = 0; i < owners.length; i++) {
      writeString(out, owners[i]);
      out.writeByte(answers[i]);
    }
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static void writeString(DataOutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    out.writeShort(b.length);
    out.write(b);
  }

  private static Map<String, Entry> load(Path pack) {
    Map<String, Entry> m = new HashMap<>();
    if (!Files.isRegularFile(pack)) return m;
    try (FileChannel ch = FileChannel.open(pack, StandardOpenOption.READ)) {
      ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
      if (b.getInt() != MAGIC) return m;
      int n = b.getInt();
      for (int i = 0; i < n; i++) {
        String name = readString(b);
        int deps = b.getShort() & 0xffff;
        String[] owners = new String[deps];
        byte[] answers = new byte[deps];
        for (int j = 0; j < deps; j++) {
          owners[j] = readString(b);
          answers[j] = b.get();
        }
        int len = b.getInt();
        m.put(name, new Entry(b, b.position(), len, owners, answers));
        b.position(b.position() + len);
      }
      // Touching the file marks it as in use for sweep(). Not a beamed instance's (its seed is read only, and the
      // agent keeps it as long as the build), and never at the cost of the classes read.
      if (!BeamSandbox.ON) {
        try {
          Files.setLastModifiedTime(pack, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
          // a read-only cache: used as it is
        }
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[apksim] ignoring damaged class cache " + pack.getFileName() + ": " + e);
      return new HashMap<>();
    }
    return m;
  }

  private static String readString(ByteBuffer b) {
    byte[] s = new byte[b.getShort() & 0xffff];
    b.get(s);
    return new String(s, StandardCharsets.UTF_8);
  }

  /** Drops packs unused for two weeks and the caches of runtime builds unused for a day. */
  private void sweep() throws IOException {
    long now = System.currentTimeMillis();
    try (DirectoryStream<Path> packs = Files.newDirectoryStream(dir, "*.{pack,classes}")) {
      for (Path p : packs) {
        if (now - Files.getLastModifiedTime(p).toMillis() > 14L * 24 * 3600_000) Files.deleteIfExists(p);
      }
    }
    try (DirectoryStream<Path> builds = Files.newDirectoryStream(dir.getParent())) {
      for (Path d : builds) {
        if (d.equals(dir) || !Files.isDirectory(d)) continue;
        if (now - Files.getLastModifiedTime(d).toMillis() < 24L * 3600_000) continue;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(d)) {
          for (Path f : files) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(d);
      }
    }
  }

  /** Identifies this runtime: the CRC of every class in its jar plus the class path (framework versions). */
  private static String runtimeHash;

  /**
   * Identifies this runtime build (memoized: it reads every entry of apksim.jar, and the jar a process runs does not
   * change under it). A standby computes it before it waits.
   */
  static synchronized String runtimeHash() {
    if (runtimeHash == null) runtimeHash = computeRuntimeHash();
    return runtimeHash;
  }

  private static String computeRuntimeHash() {
    try {
      Path jar = Path.of(ClassCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (!Files.isRegularFile(jar)) return null;
      CRC32 crc = new CRC32();
      try (ZipFile z = new ZipFile(jar.toFile())) {
        Enumeration<? extends ZipEntry> e = z.entries();
        while (e.hasMoreElements()) {
          ZipEntry entry = e.nextElement();
          crc.update(entry.getName().getBytes(StandardCharsets.UTF_8));
          long c = entry.getCrc();
          for (int i = 0; i < 8; i++) crc.update((int) (c >>> (i * 8)));
        }
      }
      // The class path names the framework jars and their versions; link paths differ by folder, so names only.
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
        crc.update(Path.of(part).getFileName().toString().getBytes(StandardCharsets.UTF_8));
      }
      return Long.toHexString(crc.getValue());
    } catch (Exception e) {
      return null;
    }
  }
}
