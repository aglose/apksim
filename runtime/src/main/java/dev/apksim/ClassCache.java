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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translated classes kept between launches. There is one pack per DEX file, named by the DEX's CRC, so a rebuilt
 * APK reuses the classes of every DEX file that did not change (debug builds put each library in its own).
 *
 * <p>A translation reads one thing outside its own class: whether each class it calls is an interface. Each entry
 * records those answers and is used only while they still hold, so a pack stays valid whatever the other DEX files
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

  private record Fresh(byte[] bytes, String[] owners, byte[] answers) {}

  private final Path dir;
  private final String[] keys;
  private final Map<String, Entry>[] loaded;
  private final Map<String, Fresh>[] fresh;
  private final Path hintFile;
  private final LinkedHashSet<String> hints = new LinkedHashSet<>();
  private final List<String> previous;
  private int hintsSaved;
  private int unsaved;
  int hits;

  @SuppressWarnings("unchecked")
  private ClassCache(Path dir, String[] keys, String pkg) {
    this.dir = dir;
    this.keys = keys;
    hintFile = pkg == null || pkg.isEmpty() ? null : dir.resolve(pkg + ".classes");
    if (hintFile != null && Files.isRegularFile(hintFile)) {
      try {
        hints.addAll(Files.readAllLines(hintFile));
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

  /** The cache for these DEX files (keys from their CRCs), or null when it is off or this runtime is not a jar. */
  static ClassCache open(String[] keys) {
    if (!Boolean.parseBoolean(System.getProperty("apksim.classCache", "true"))) return null;
    String runtime = runtimeHash();
    if (runtime == null) return null;
    Path root = Path.of(System.getProperty("apksim.cacheRoot"), "classes");
    ClassCache cache = new ClassCache(root.resolve(runtime), keys, System.getProperty("apksim.package"));
    Thread saver = new Thread(cache::saveLoop, "apksim-class-cache");
    saver.setDaemon(true);
    saver.setPriority(Thread.MIN_PRIORITY);
    saver.start();
    return cache;
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
  synchronized boolean has(int dex, String name, ToIntFunction<String> answer) {
    return valid(dex, name, answer) != null || fresh[dex].containsKey(name);
  }

  private Entry valid(int dex, String name, ToIntFunction<String> answer) {
    if (loaded[dex] == null) loaded[dex] = load(dir.resolve(keys[dex] + ".pack"));
    Entry e = loaded[dex].get(name);
    if (e == null) return null;
    for (int i = 0; i < e.owners.length; i++) {
      if (answer.applyAsInt(e.owners[i]) != e.answers[i]) return null;
    }
    return e;
  }

  /** The cached translation of a class in DEX file {@code dex}, if its recorded answers still hold. */
  synchronized byte[] get(int dex, String name, ToIntFunction<String> answer) {
    Entry e = valid(dex, name, answer);
    if (e == null) return null;
    byte[] out = new byte[e.length];
    e.pack.get(e.offset, out);
    hits++;
    return out;
  }

  synchronized void put(int dex, String name, byte[] bytes, Map<String, Byte> answers) {
    String[] owners = answers.keySet().toArray(new String[0]);
    byte[] a = new byte[owners.length];
    for (int i = 0; i < owners.length; i++) a[i] = answers.get(owners[i]);
    fresh[dex].put(name, new Fresh(bytes, owners, a));
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
          save();
          seen = 0;
        } else {
          seen = now;
        }
      }
    } catch (InterruptedException ignored) {
      // Daemon thread: the process is going away.
    }
  }

  private void save() {
    Map<Integer, Map<String, Fresh>> todo = new LinkedHashMap<>();
    List<String> names = null;
    synchronized (this) {
      if (hintFile != null && hints.size() != hintsSaved) {
        names = new ArrayList<>(hints);
        hintsSaved = names.size();
      }
      for (int i = 0; i < keys.length; i++) {
        if (fresh[i].isEmpty()) continue;
        if (loaded[i] == null) loaded[i] = load(dir.resolve(keys[i] + ".pack"));
        todo.put(i, fresh[i]);
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
        Path tmp = dir.resolve(keys[dex] + ".pack." + ProcessHandle.current().pid());
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
      if (names != null) {
        Path tmp = dir.resolve(hintFile.getFileName() + "." + ProcessHandle.current().pid());
        Files.write(tmp, names);
        Files.move(tmp, hintFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
      sweep();
    } catch (IOException e) {
      System.err.println("[apksim] could not save translated classes: " + e);
      return;
    }
    if (!Boolean.getBoolean("apksim.quiet")) System.err.printf("[apksim] saved %d translated classes%n", written);
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
      // Touching the file marks it as in use for sweep().
      Files.setLastModifiedTime(pack, FileTime.fromMillis(System.currentTimeMillis()));
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
  private static String runtimeHash() {
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
