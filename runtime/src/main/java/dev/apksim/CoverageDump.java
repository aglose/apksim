package dev.apksim;

import dev.apksim.dex.Coverage;
import dev.apksim.dex.Probes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Writes a test process's coverage (`--coverage DIR`, docs/coverage.md) as text for `scripts/coverage.py` to merge:
 * {@code cov-<pid>-<random>.cov} holds what ran in this process (only counters above zero), and
 * {@code universe-<key>.cov} every probed method and line of the APK and its tests with no counts, which is what makes
 * a method nobody ran read 0. The universe is the same for every process of one build, so the first writes it and the
 * rest see the file and skip the work (an isolated run has thousands of processes).
 *
 * <p>Lines: {@code C class kind source}, {@code S smap} (newlines as \n), {@code M name desc hits}, {@code L line hits}
 * (belonging to the last M), tab-separated; line numbers are the DEX's (Kotlin inline code is mapped by the SMAP later).
 */
final class CoverageDump {
  private CoverageDump() {}

  static void write(Path dir, AppClasses app, AppClasses tests) {
    try {
      Files.createDirectories(dir);
      StringBuilder hits = new StringBuilder("V\t1\n");
      Map<String, int[]> counters = new TreeMap<>(Coverage.counters());
      for (Map.Entry<String, int[]> e : counters.entrySet()) {
        Meta m = meta(e.getKey().replace('.', '/'), app, tests);
        if (m != null) m.emit(hits, e.getValue(), false);
      }
      put(dir.resolve("cov-" + ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime()) + ".cov"), hits);
      Path universe = dir.resolve("universe-" + key(app, tests) + ".cov");
      if (!Files.exists(universe)) {
        StringBuilder u = new StringBuilder("V\t1\n");
        List<String> names = new ArrayList<>();
        for (String n : app.internalNames()) names.add(n);
        for (String n : tests.internalNames()) names.add(n);
        java.util.Collections.sort(names);
        for (String n : names) {
          if (!Coverage.selects(n)) continue;
          Meta m = meta(n, app, tests);
          if (m != null) m.emit(u, null, true);
        }
        put(universe, u);
      }
    } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException e) {
      System.err.println("[apksim] coverage not written: " + e);
    }
  }

  private static String key(AppClasses app, AppClasses tests) throws java.security.NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update((app.fingerprint() + "|" + tests.fingerprint() + "|" + Probes.packages() + "|" + ClassCache.runtimeHash()).getBytes(StandardCharsets.UTF_8));
    byte[] sum = md.digest();
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 8; i++) b.append(String.format("%02x", sum[i]));
    return b.toString();
  }

  private static void put(Path file, StringBuilder text) throws IOException {
    Path tmp = file.resolveSibling(file.getFileName() + Storage.tmpTag());
    Files.writeString(tmp, text);
    Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /** A class's slot table, read from its bytes (nothing is loaded or initialized). */
  private static final class Meta {
    String name, kind, source, smap;
    final StringBuilder table = new StringBuilder();

    void emit(StringBuilder out, int[] counts, boolean universe) {
      // slot -> method / line
      Map<Integer, String[]> methods = new TreeMap<>();
      Map<Integer, List<int[]>> lines = new TreeMap<>(); // method slot -> {line slot, line}
      for (String row : table.toString().split("\n")) {
        if (row.isEmpty()) continue;
        String[] f = row.split("\t");
        if (f[0].equals("M")) methods.put(Integer.parseInt(f[1]), new String[] {f[2], f[3]});
        else lines.computeIfAbsent(Integer.parseInt(f[2]), k -> new ArrayList<>()).add(new int[] {Integer.parseInt(f[1]), Integer.parseInt(f[3])});
      }
      StringBuilder body = new StringBuilder();
      for (Map.Entry<Integer, String[]> m : methods.entrySet()) {
        int h = universe || counts == null || m.getKey() >= counts.length ? 0 : counts[m.getKey()];
        StringBuilder ls = new StringBuilder();
        for (int[] l : lines.getOrDefault(m.getKey(), List.of())) {
          int lh = universe || l[0] >= counts.length ? 0 : counts[l[0]];
          if (universe || lh > 0) ls.append("L\t").append(l[1]).append('\t').append(lh).append('\n');
        }
        if (universe || h > 0 || ls.length() > 0) {
          body.append("M\t").append(m.getValue()[0]).append('\t').append(m.getValue()[1]).append('\t').append(h).append('\n').append(ls);
        }
      }
      if (body.length() == 0 && !universe) return;
      out.append("C\t").append(name).append('\t').append(kind).append('\t').append(source == null ? "-" : source).append('\n');
      if (universe && smap != null) out.append("S\t").append(smap.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")).append('\n');
      out.append(body);
    }
  }

  private static Meta meta(String internal, AppClasses app, AppClasses tests) {
    byte[] bytes = app.peek(internal);
    String kind = "app";
    if (bytes == null) {
      bytes = tests.peek(internal);
      kind = "test";
    }
    if (bytes == null) return null;
    Meta m = new Meta();
    m.name = internal;
    m.kind = kind;
    Map<Integer, String> chunks = new TreeMap<>();
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public void visitSource(String source, String debug) {
        m.source = source;
        m.smap = debug;
      }

      @Override
      public FieldVisitor visitField(int access, String n, String desc, String sig, Object value) {
        if (n.startsWith(Coverage.META_FIELD) && value instanceof String s) {
          chunks.put(Integer.parseInt(n.substring(Coverage.META_FIELD.length())), s);
        }
        return null;
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
    if (chunks.isEmpty()) return null;
    for (String c : chunks.values()) m.table.append(c);
    return m;
  }
}
