import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The stub census: every native method of the framework jar apksim runs, and whether anything shadows it.
 *
 * <pre>
 * java -cp "work/lib/asm-9.10.1.jar" scripts/stub-census.java ROOT [--out FILE | --check FILE] [--quiet]
 * </pre>
 *
 * Wrote FILE (or, with --check, compares it with what the jars give and exits 1 when it differs) and prints the
 * summary per package. scripts/stub-census.sh runs it with the right class path. docs/stub-census.tsv is the table.
 *
 * Natives: Robolectric's instrumentation turns each native method X of a framework class into a plain method X that
 * calls `$$robo$$X$nativeBinding`, which keeps the native flag (X's access and descriptor stay). A class it does not
 * instrument keeps its native methods as they are; those count too.
 *
 * Status, from the @Implements shadows on the class path (shadows-framework, Robolectric's other jars, work/apksim.jar):
 *   shadowed-by CLASS  a shadow of the declaring class (value or className, SDK range holding 36, not a ShadowLegacy*)
 *                      has an @Implementation of that name and parameter types (a shadow's Object stands for any
 *                      reference type: looseSignatures), declared in it or in a shadow superclass. apksim's own
 *                      shadows are preferred to Robolectric's, those to the native runtime's.
 *   native-runtime     that shadow is a ShadowNative*: Robolectric's native runtime (librobolectric-nativeruntime,
 *                      registered on its own org.robolectric.nativeruntime.*Natives classes) does the work
 *   unshadowed         nothing: a call returns the default (0, false, null) or whatever the instrumented class does
 * Last column: public when the member is public or protected in a public class, else internal.
 */
public class StubCensus {
  static final int SDK = 36;
  static final String HEADER = "class\tmethod\tdescriptor\tstatus\taccess";

  record Native(String cls, String name, String desc, boolean visible) {}

  static class Shadow {
    String name, superName;
    String shadowed;   // internal name of the class (value), or null
    String className;  // internal name from className=
    int minSdk = -1, maxSdk = -1;
    boolean implementsSeen;
    List<String[]> impls = new ArrayList<>();  // name, params descriptor
    List<int[]> implSdk = new ArrayList<>();
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path out = null, check = null, registered = root.resolve("docs/stub-census-registered.txt"), fromLog = null;
    boolean quiet = false;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--out" -> out = Path.of(args[++i]);
        case "--check" -> check = Path.of(args[++i]);
        case "--quiet" -> quiet = true;
        case "--registered" -> registered = Path.of(args[++i]);
        case "--registered-from" -> fromLog = Path.of(args[++i]);
        default -> throw new IllegalArgumentException(args[i]);
      }
    }
    if (fromLog != null) {  // -Xlog:jni+resolve=debug of a launch -> the registered list
      writeRegistered(fromLog, registered);
      return;
    }
    Set<String> inRuntime = new HashSet<>();  // class/method the native runtime registers on framework classes
    for (String l : Files.readAllLines(registered, StandardCharsets.UTF_8)) {
      if (!l.isEmpty() && !l.startsWith("#")) inRuntime.add(l.replace('.', '/').replace('\t', '.'));
    }
    Path lib = root.resolve("work/lib");
    Path framework = find(lib, "android-all-instrumented-");
    List<Path> shadowJars = new ArrayList<>();
    for (String pre : new String[] {"shadows-framework-", "robolectric-4", "shadowapi-", "resources-4", "sandbox-", "utils-"}) {
      Path p = findOpt(lib, pre);
      if (p != null) shadowJars.add(p);
    }
    Path sim = root.resolve("work/apksim.jar");
    if (!Files.exists(sim)) throw new IllegalStateException(sim + " missing: run scripts/build.sh");
    shadowJars.add(sim);

    List<Native> natives = readNatives(framework);
    Map<String, Shadow> shadowClasses = new HashMap<>();
    for (Path jar : shadowJars) readShadows(jar, shadowClasses, jar.equals(sim));
    Map<String, List<String>> byShadowed = new HashMap<>();
    for (Shadow s : shadowClasses.values()) {
      if (!s.implementsSeen) continue;
      String target = s.shadowed != null ? s.shadowed : s.className;
      if (target == null || target.equals("java/lang/Object") && s.className == null) continue;
      if (s.name.substring(s.name.lastIndexOf('/') + 1).startsWith("ShadowLegacy")) continue;
      if (s.minSdk > SDK || (s.maxSdk != -1 && s.maxSdk < SDK)) continue;
      byShadowed.computeIfAbsent(target, k -> new ArrayList<>()).add(s.name);
    }

    List<String> rows = new ArrayList<>();
    Map<String, int[]> perPackage = new TreeMap<>();  // natives, unshadowed, native-runtime
    int nearMiss = 0;
    for (Native n : natives) {
      String status = "unshadowed";
      String best = null;
      int bestRank = 9;
      boolean sameName = false;
      for (String sh : byShadowed.getOrDefault(n.cls, List.of())) {
        int rank = sh.startsWith("dev/apksim/") ? 0 : sh.substring(sh.lastIndexOf('/') + 1).startsWith("ShadowNative") ? 2 : 1;
        int m = implemented(shadowClasses, sh, n.name, n.desc);
        if (m == 2) sameName = true;
        if (m == 1 && (rank < bestRank || rank == bestRank && sh.compareTo(best) < 0)) { best = sh; bestRank = rank; }
      }
      boolean registeredNative = inRuntime.contains(n.cls + "." + n.name);
      if (best != null && bestRank < 2) status = "shadowed-by " + best.replace('/', '.');
      else if (best != null || registeredNative) status = "native-runtime";
      else if (sameName) nearMiss++;
      rows.add(String.join("\t", n.cls.replace('/', '.'), n.name, n.desc, status, n.visible ? "public" : "internal"));
      int[] c = perPackage.computeIfAbsent(topPackage(n.cls), k -> new int[3]);
      c[0]++;
      if (status.equals("unshadowed")) c[1]++;
      if (status.equals("native-runtime")) c[2]++;
    }
    Collections.sort(rows);
    String table = HEADER + "\n" + String.join("\n", rows) + "\n";

    if (!quiet) summary(perPackage, rows.size(), nearMiss);
    if (out != null) Files.writeString(out, table, StandardCharsets.UTF_8);
    if (check != null) {
      String have = Files.exists(check) ? Files.readString(check, StandardCharsets.UTF_8) : "";
      if (!have.equals(table)) {
        System.err.println("stub-census: " + check + " is stale against the jars (" + framework.getFileName()
            + ", shadows, work/apksim.jar). Regenerate: scripts/stub-census.sh --update");
        List<String> old = List.of(have.split("\n"));
        Set<String> oldSet = new HashSet<>(old), newSet = new HashSet<>(rows);
        int shown = 0;
        for (String r : rows) if (!oldSet.contains(r) && shown++ < 10) System.err.println("  + " + r);
        for (String r : old) if (!newSet.contains(r) && !r.equals(HEADER) && shown++ < 20) System.err.println("  - " + r);
        System.exit(1);
      }
      System.out.println("stub-census: table is current (" + rows.size() + " natives)");
    }
  }

  /** The framework natives the native runtime registered in one launch: lines "Registering JNI native method CLASS.$$robo$$NAME$nativeBinding". */
  static void writeRegistered(Path log, Path out) throws IOException {
    java.util.TreeSet<String> names = new java.util.TreeSet<>();
    for (String l : Files.readAllLines(log, StandardCharsets.UTF_8)) {
      int at = l.indexOf("Registering JNI native method ");
      if (at < 0) continue;
      String m = l.substring(at + 30).replaceAll("\\]$", "").trim();
      int dot = m.lastIndexOf('.', m.indexOf("$$robo$$") < 0 ? m.length() : m.indexOf("$$robo$$") - 1);
      if (!m.contains("$$robo$$") || !m.endsWith("$nativeBinding")) continue;
      String cls = m.substring(0, dot), meth = m.substring(dot + 1 + 8, m.length() - 14);
      names.add(cls + "\t" + meth);
    }
    Files.writeString(out, "# Framework natives the native runtime registers (class TAB method), from one launch's -Xlog:jni+resolve=debug.\n"
        + "# Regenerate on a Mac: scripts/stub-census.sh --update-registered APK. By name: every overload counts.\n"
        + String.join("\n", names) + "\n", StandardCharsets.UTF_8);
    System.out.println("stub-census: " + names.size() + " registered natives written to " + out);
  }

  static void summary(Map<String, int[]> per, int total, int nearMiss) {
    int un = 0, nr = 0;
    for (int[] c : per.values()) { un += c[1]; nr += c[2]; }
    System.out.printf("%-34s %8s %10s %15s%n", "package", "natives", "unshadowed", "native-runtime");
    for (Map.Entry<String, int[]> e : per.entrySet()) {
      int[] c = e.getValue();
      System.out.printf("%-34s %8d %10d %15d%n", e.getKey(), c[0], c[1], c[2]);
    }
    System.out.printf("%-34s %8d %10d %15d%n", "TOTAL", total, un, nr);
    System.out.println("unshadowed with a same-named shadow method of other parameter types: " + nearMiss);
  }

  /** android.media, android.os, com.android.internal, java.lang ...: the first two segments of the package (three under com.android). */
  static String topPackage(String cls) {
    int slash = cls.lastIndexOf('/');
    if (slash < 0) return "(default)";
    String[] seg = cls.substring(0, slash).split("/");
    int n = seg.length >= 2 && seg[0].equals("com") && seg[1].equals("android") ? 3 : 2;
    return String.join(".", java.util.Arrays.copyOf(seg, Math.min(n, seg.length)));
  }

  /** 1: a shadow in the chain implements it; 2: only a same-named implementation of other parameter types; 0: none. */
  static int implemented(Map<String, Shadow> all, String shadow, String name, String desc) {
    String params = desc.substring(0, desc.indexOf(')') + 1);
    int result = 0;
    for (String c = shadow; c != null; ) {
      Shadow s = all.get(c);
      if (s == null) break;
      for (int i = 0; i < s.impls.size(); i++) {
        String[] im = s.impls.get(i);
        int[] sdk = s.implSdk.get(i);
        if (!im[0].equals(name)) continue;
        if (sdk[0] > SDK || (sdk[1] != -1 && sdk[1] < SDK)) continue;
        if (paramsMatch(im[1], params)) return 1;
        result = 2;
      }
      c = s.superName;
    }
    return result;
  }

  static boolean paramsMatch(String shadow, String target) {
    Type[] a = Type.getArgumentTypes(shadow + "V"), b = Type.getArgumentTypes(target + "V");
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i].equals(b[i])) continue;
      boolean ref = b[i].getSort() == Type.OBJECT || b[i].getSort() == Type.ARRAY;
      if (ref && a[i].getDescriptor().equals("Ljava/lang/Object;")) continue;  // looseSignatures
      return false;
    }
    return true;
  }

  static Path find(Path dir, String prefix) throws IOException {
    Path p = findOpt(dir, prefix);
    if (p == null) throw new IllegalStateException("no " + prefix + "*.jar in " + dir);
    return p;
  }

  static Path findOpt(Path dir, String prefix) throws IOException {
    try (var s = Files.list(dir)) {
      return s.filter(f -> f.getFileName().toString().startsWith(prefix) && f.toString().endsWith(".jar")).sorted().findFirst().orElse(null);
    }
  }

  static List<Native> readNatives(Path jar) throws IOException {
    List<Native> out = new ArrayList<>();
    try (ZipFile z = new ZipFile(jar.toFile())) {
      for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        if (!e.getName().endsWith(".class")) continue;
        try (InputStream in = z.getInputStream(e)) {
          ClassReader cr = new ClassReader(in);
          int[] classAccess = new int[1];
          Map<String, Integer> access = new HashMap<>();
          List<String[]> found = new ArrayList<>();  // original name, desc, wrapper key or null
          cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) { classAccess[0] = acc; }
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
              if ((acc & Opcodes.ACC_NATIVE) != 0) {
                if (name.startsWith("$$robo$$") && name.endsWith("$nativeBinding")) {
                  found.add(new String[] {name.substring(8, name.length() - 14), desc});
                } else {
                  found.add(new String[] {name, desc});
                  access.put(name + desc, acc);
                }
              } else {
                access.put(name + desc, acc);
              }
              return null;
            }
          }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
          boolean publicClass = (classAccess[0] & Opcodes.ACC_PUBLIC) != 0;
          for (String[] f : found) {
            Integer acc = access.get(f[0] + f[1]);
            boolean vis = acc != null && publicClass && (acc & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
            out.add(new Native(cr.getClassName(), f[0], f[1], vis));
          }
        }
      }
    }
    return out;
  }

  static void readShadows(Path jar, Map<String, Shadow> into, boolean sim) throws IOException {
    try (ZipFile z = new ZipFile(jar.toFile())) {
      for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        String n = e.getName();
        if (!n.endsWith(".class") || n.startsWith("android/") || n.startsWith("META-INF/")) continue;
        try (InputStream in = z.getInputStream(e)) {
          ClassReader cr = new ClassReader(in);
          Shadow s = new Shadow();
          cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) { s.name = name; s.superName = sup; }
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
              if (!desc.equals("Lorg/robolectric/annotation/Implements;")) return null;
              s.implementsSeen = true;
              return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String name, Object value) {
                  switch (name) {
                    case "value" -> { if (value instanceof Type t) s.shadowed = t.getInternalName(); }
                    case "className" -> { if (!((String) value).isEmpty()) s.className = ((String) value).replace('.', '/'); }
                    case "minSdk" -> s.minSdk = (Integer) value;
                    case "maxSdk" -> s.maxSdk = (Integer) value;
                    default -> { }
                  }
                }
              };
            }
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
              return new MethodVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String ad, boolean visible) {
                  if (!ad.equals("Lorg/robolectric/annotation/Implementation;")) return null;
                  int[] sdk = {-1, -1};
                  s.impls.add(new String[] {name, desc.substring(0, desc.indexOf(')') + 1)});
                  s.implSdk.add(sdk);
                  return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String an, Object value) {
                      if (an.equals("minSdk")) sdk[0] = (Integer) value;
                      if (an.equals("maxSdk")) sdk[1] = (Integer) value;
                    }
                  };
                }
              };
            }
          }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
          if (s.name != null && (s.implementsSeen || !s.impls.isEmpty())) {
            Shadow old = into.putIfAbsent(s.name, s);
            if (old != null && sim) into.put(s.name, s);
          }
        }
      }
    }
  }
}
