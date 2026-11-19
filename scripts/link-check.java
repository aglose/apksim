import dev.apksim.dex.ClassTranslator;
import dev.apksim.dex.DexFile;
import dev.apksim.dex.Hierarchy;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Translates every class of an APK as apksim does (the same ClassTranslator calls as translate-all.java, with the
 * app-wide hierarchy and, as the live translator, the framework's and the JDK's interfaces), then checks every invoke
 * instruction of the translated output the way HotSpot would resolve it when the instruction first runs, and writes a
 * JSON report. Findings are what HotSpot refuses (IncompatibleClassChangeError, NoSuchMethodError, IllegalAccessError,
 * AbstractMethodError): the shapes issues 275, 299 and 522 were, each found by an app at run time.
 *
 * <pre>
 * java -Xmx6g -cp "work/apksim.jar:$(ls work/lib/asm*.jar | tr '\n' :)" scripts/link-check.java APK OUT.json [JAR[:JAR...]]
 * </pre>
 *
 * JARs are the platform the app runs against, after the APK's own classes: work/apksim.jar (what translated code
 * calls into) and the android-all jar; then the JDK. An owner found nowhere is counted as unresolved, not an error
 * (a class the app brings from outside, such as Play services); so is a lookup that ends in one. Class and field
 * accessibility and fields are not checked; invokespecial's rules are (the direct-superinterface rule, a class owner
 * that is not a superclass).
 */
public class LinkCheck {
  /** What a lookup needs of a class. */
  static final class Info {
    String name, superName;
    String[] ifaces = new String[0];
    int access;
    final Map<String, Integer> methods = new HashMap<>();
    boolean isInterface() { return (access & Opcodes.ACC_INTERFACE) != 0; }
  }

  static final Map<String, Info> app = new HashMap<>();
  static final Map<String, Info> cache = new HashMap<>();
  static final Map<String, ZipFile> jarOf = new HashMap<>();
  static final Info NONE = new Info();

  static Info parse(byte[] b) {
    Info in = new Info();
    new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public void visit(int v, int acc, String n, String sig, String sup, String[] itfs) {
        in.name = n; in.access = acc; in.superName = sup; if (itfs != null) in.ifaces = itfs;
      }
      @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
        in.methods.put(n + d, acc);
        return null;
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return in;
  }

  /** The class's info, or null when it is not the app's, the platform's or the JDK's. */
  static Info get(String name) {
    Info a = app.get(name);
    if (a != null) return a;
    Info c = cache.get(name);
    if (c != null) return c == NONE ? null : c;
    Info found = NONE;
    try {
      ZipFile z = jarOf.get(name);
      if (z != null) {
        try (InputStream is = z.getInputStream(z.getEntry(name + ".class"))) { found = parse(is.readAllBytes()); }
      } else {
        try (InputStream is = ClassLoader.getSystemResourceAsStream(name + ".class")) {
          if (is != null) found = parse(is.readAllBytes());
        }
      }
    } catch (Exception e) {
      found = NONE;
    }
    cache.put(name, found);
    return found == NONE ? null : found;
  }

  /** A lookup's answer: where the method was found, or that it is certainly missing, or that a class on the way is unknown. */
  record Res(Info owner, int access, boolean unknown) {
    static final Res MISSING = new Res(null, 0, false), UNKNOWN = new Res(null, 0, true);
    boolean found() { return owner != null; }
  }

  static boolean inheritable(int acc) { return (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0; }

  /** JVMS 5.4.3.3: the class and its superclasses, then the superinterfaces' maximally specific method. */
  static Res resolveClass(Info c, String key) {
    Set<String> seen = new LinkedHashSet<>();
    Info k = c;
    List<Info> chain = new ArrayList<>();
    while (k != null) {
      Integer acc = k.methods.get(key);
      if (acc != null) return new Res(k, acc, false);
      chain.add(k);
      if (k.superName == null) break;
      Info s = get(k.superName);
      if (s == null) return Res.UNKNOWN;
      k = s;
    }
    return fromInterfaces(chain, key);
  }

  /** JVMS 5.4.3.4: the interface, then Object's public methods, then its superinterfaces'. */
  static Res resolveInterface(Info c, String key) {
    Integer acc = c.methods.get(key);
    if (acc != null) return new Res(c, acc, false);
    Info obj = get("java/lang/Object");
    Integer o = obj == null ? null : obj.methods.get(key);
    if (o != null && (o & Opcodes.ACC_PUBLIC) != 0 && (o & Opcodes.ACC_STATIC) == 0) return new Res(obj, o, false);
    return fromInterfaces(List.of(c), key);
  }

  static Res fromInterfaces(List<Info> classes, String key) {
    Res best = null;
    boolean unknown = false;
    Set<String> seen = new LinkedHashSet<>();
    List<String> todo = new ArrayList<>();
    for (Info c : classes) todo.addAll(List.of(c.ifaces));
    while (!todo.isEmpty()) {
      String n = todo.remove(todo.size() - 1);
      if (!seen.add(n)) continue;
      Info i = get(n);
      if (i == null) { unknown = true; continue; }
      Integer acc = i.methods.get(key);
      if (acc != null && inheritable(acc)) {
        // Prefer a non-abstract (default) method, as selection would; any one otherwise.
        if (best == null || ((best.access & Opcodes.ACC_ABSTRACT) != 0 && (acc & Opcodes.ACC_ABSTRACT) == 0)) best = new Res(i, acc, false);
      }
      todo.addAll(List.of(i.ifaces));
    }
    if (best != null) return best;
    return unknown ? Res.UNKNOWN : Res.MISSING;
  }

  /** Is {@code c} {@code d} or a subclass of it; true when the chain cannot be followed. */
  static boolean subclassOf(Info c, String d) {
    for (int guard = 0; c != null && guard < 200; guard++) {
      if (c.name.equals(d)) return true;
      if (c.superName == null) return false;
      c = get(c.superName);
    }
    return true;
  }

  static String pkg(String n) { int i = n.lastIndexOf('/'); return i < 0 ? "" : n.substring(0, i); }

  record Finding(String kind, String cls, String method, String insn, String detail) {}
  static final List<Finding> findings = new ArrayList<>();
  static final Map<String, Integer> kinds = new TreeMap<>();
  static final Map<String, Integer> unresolvedOwners = new TreeMap<>();
  static long invokes, unresolvedCalls, shadowedClasses;

  /**
   * An APK class the platform has too is never loaded from the APK: the platform's loader answers first (D8 and R8
   * put stubs of newer framework classes in an app, such as android.text.SegmentFinder with only a static initializer).
   */
  static boolean shadowed(String name) {
    return jarOf.containsKey(name) || (name.startsWith("java/") && ClassLoader.getSystemResource(name + ".class") != null);
  }

  static void add(String kind, String cls, String method, String insn, String detail) {
    kinds.merge(kind, 1, Integer::sum);
    findings.add(new Finding(kind, cls, method, insn, detail));
  }

  static void check(Info cur, String method, int op, String owner, String name, String desc, boolean itf) {
    invokes++;
    if (owner.startsWith("[") || owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle")) return;
    String insn = switch (op) {
      case Opcodes.INVOKEVIRTUAL -> "invokevirtual";
      case Opcodes.INVOKESPECIAL -> "invokespecial";
      case Opcodes.INVOKESTATIC -> "invokestatic";
      default -> "invokeinterface";
    } + " " + owner + "." + name + desc + (itf ? " [itf]" : "");
    Info o = get(owner);
    if (o == null) {
      unresolvedCalls++;
      unresolvedOwners.merge(owner, 1, Integer::sum);
      return;
    }
    if (o.isInterface() != itf) {
      add("icce-owner-kind", cur.name, method, insn,
          itf ? "InterfaceMethodref, but " + owner + " is a class" : "Methodref, but " + owner + " is an interface");
      return;
    }
    String key = name + desc;
    boolean init = name.equals("<init>");
    Res r = init ? (o.methods.containsKey(key) ? new Res(o, o.methods.get(key), false) : Res.MISSING)
                 : itf ? resolveInterface(o, key) : resolveClass(o, key);
    if (op == Opcodes.INVOKESPECIAL && itf && !cur.name.equals(owner) && !List.of(cur.ifaces).contains(owner)) {
      add("icce-indirect-super", cur.name, method, insn, owner + " is not a direct superinterface of " + cur.name
          + " (extends " + cur.superName + ", implements " + String.join(",", cur.ifaces) + ")");
      return;
    }
    if (op == Opcodes.INVOKESPECIAL && !itf && !init && !cur.name.equals(owner) && !subclassOf(cur, owner)) {
      add("special-not-super", cur.name, method, insn, owner + " is not a superclass of " + cur.name);
    }
    if (r.unknown()) {
      unresolvedCalls++;
      unresolvedOwners.merge(owner + " (hierarchy)", 1, Integer::sum);
      return;
    }
    if (!r.found()) {
      add("no-such-method", cur.name, method, insn, "not found in " + owner + " or its supertypes");
      return;
    }
    boolean isStatic = (r.access & Opcodes.ACC_STATIC) != 0;
    if (op == Opcodes.INVOKESTATIC && !isStatic) {
      add("icce-static", cur.name, method, insn, "resolves to the instance method of " + r.owner.name);
      return;
    }
    if (op != Opcodes.INVOKESTATIC && isStatic) {
      add("icce-static", cur.name, method, insn, "resolves to the static method of " + r.owner.name);
      return;
    }
    if (op == Opcodes.INVOKESPECIAL && !itf && !init && !cur.name.equals(owner) && cur.superName != null) {
      // ACC_SUPER (JVMS invokespecial): a superclass owner only names the method; it is selected from the direct
      // superclass up, so an abstract Context.setTheme called by an activity runs ContextThemeWrapper's.
      Info sup = get(cur.superName);
      Res sel = sup == null ? Res.UNKNOWN : resolveClass(sup, key);
      if (sel.unknown()) { unresolvedCalls++; return; }
      if (!sel.found()) { add("no-such-method", cur.name, method, insn, "not found from the direct superclass " + cur.superName + " up"); return; }
      if ((sel.access & Opcodes.ACC_ABSTRACT) != 0) { add("abstract-special", cur.name, method, insn, "selected from " + cur.superName + ": the abstract method of " + sel.owner.name); return; }
      r = sel;
    }
    if (op == Opcodes.INVOKESPECIAL && (r.access & Opcodes.ACC_ABSTRACT) != 0) {
      add("abstract-special", cur.name, method, insn, "resolves to the abstract method of " + r.owner.name);
      return;
    }
    int acc = r.access;
    boolean ok;
    if ((acc & Opcodes.ACC_PUBLIC) != 0) ok = true;
    else if ((acc & Opcodes.ACC_PRIVATE) != 0) ok = cur.name.equals(r.owner.name);
    else if ((acc & Opcodes.ACC_PROTECTED) != 0) ok = pkg(cur.name).equals(pkg(r.owner.name)) || subclassOf(cur, r.owner.name);
    else ok = pkg(cur.name).equals(pkg(r.owner.name));
    if (!ok) add("illegal-access", cur.name, method, insn, "method of " + r.owner.name + " is not accessible from " + cur.name);
  }

  static String esc(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }

  public static void main(String[] a) throws Exception {
    if (a.length > 2) {
      for (String j : a[2].split(java.io.File.pathSeparator)) {
        if (j.isEmpty() || !Files.exists(Path.of(j))) continue;
        ZipFile z = new ZipFile(j);
        for (var e : Collections.list(z.entries())) {
          String n = e.getName();
          if (n.endsWith(".class")) jarOf.putIfAbsent(n.substring(0, n.length() - 6), z);
        }
      }
    }
    List<DexFile> dexes = new ArrayList<>();
    Path in0 = Path.of(a[0]);
    if (Files.isDirectory(in0) || a[0].endsWith(".dex")) {
      // A fixture: one .dex, or a folder of classes.dex, classes2.dex... as one app.
      List<Path> files;
      if (Files.isDirectory(in0)) {
        try (var st = Files.list(in0)) { files = st.filter(f -> f.toString().endsWith(".dex")).sorted().toList(); }
      } else files = List.of(in0);
      for (Path f : files) dexes.add(new DexFile(ByteBuffer.wrap(Files.readAllBytes(f))));
    } else {
      Map<Integer, DexFile> byNumber = new TreeMap<>();
      try (ZipFile z = new ZipFile(a[0])) {
        for (var e : Collections.list(z.entries())) {
          var m = java.util.regex.Pattern.compile("classes(\\d*)\\.dex").matcher(e.getName());
          if (m.matches()) byNumber.put(m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1)), new DexFile(ByteBuffer.wrap(z.getInputStream(e).readAllBytes())));
        }
      }
      dexes.addAll(byNumber.values());
    }
    Set<String> interfaces = new LinkedHashSet<>();
    Map<String, int[]> where = new HashMap<>();
    for (int k = 0; k < dexes.size(); k++) {
      DexFile d = dexes.get(k);
      for (int i = 0; i < d.classDefsSize; i++) {
        String t = d.className(i);
        if ((d.classAccess(i) & 0x200) != 0) interfaces.add(t);
        where.putIfAbsent(t.substring(1, t.length() - 1), new int[] {k, i});
      }
    }
    Hierarchy full = (cls, name, desc) -> {
      int[] at = where.get(cls);
      if (at == null) return null;
      DexFile d = dexes.get(at[0]);
      return new Hierarchy.Shape(d.superclassOf(at[1]), d.interfacesOf(at[1]), d.declaresMethod(at[1], name, desc));
    };
    // Self-test switch: -Dlinkcheck.hierarchy=single (each class sees only its own DEX file: before issue 522) or none
    // (no super-call retargeting: before issues 275 and 299). The check must then fail on the committed fixtures.
    String mode = System.getProperty("linkcheck.hierarchy", "all");
    Hierarchy all = mode.equals("none") ? (c, n, d) -> null : full;
    // The live translator asks the running app's loader, which knows the framework's interfaces too.
    java.util.function.Predicate<String> isInterface = n -> {
      if (where.containsKey(n) && !shadowed(n)) return interfaces.contains("L" + n + ";");
      Info i = get(n);
      return i != null && i.isInterface();
    };
    List<byte[]> out = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    int classes = 0;
    long t0 = System.nanoTime();
    for (int di = 0; di < dexes.size(); di++) {
      DexFile d = dexes.get(di);
      for (int i = 0; i < d.classDefsSize; i++) {
        classes++;
        try {
          byte[] b = new ClassTranslator(d, i, n -> isInterface.test(n), mode.equals("single") ? Hierarchy.of(d) : all).translate();
          Info in = parse(b);
          if (shadowed(in.name)) { shadowedClasses++; continue; }
          out.add(b);
          app.putIfAbsent(in.name, in);
        } catch (Throwable t) {
          String m = String.valueOf(t);
          failures.add("{\"class\":" + esc(d.className(i)) + ",\"error\":" + esc(m.length() > 300 ? m.substring(0, 300) : m) + "}");
        }
      }
    }
    long translateMs = (System.nanoTime() - t0) / 1_000_000;
    for (byte[] b : out) {
      Info cur = app.get(parse(b).name);
      String cn = cur.name;
      new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override public MethodVisitor visitMethod(int acc, String mn, String md, String s, String[] e) {
          String method = mn + md;
          return new MethodVisitor(Opcodes.ASM9) {
            @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
              check(cur, method, op, o, n, d, itf);
            }
          };
        }
      }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    StringBuilder j = new StringBuilder("{\n");
    j.append("\"apk\":").append(esc(Path.of(a[0]).getFileName().toString())).append(",\n");
    j.append("\"dexFiles\":").append(dexes.size()).append(",\"classes\":").append(classes).append(",\"translateMs\":").append(translateMs).append(",\"shadowedByPlatform\":").append(shadowedClasses).append(",\n");
    j.append("\"translateFailures\":[").append(String.join(",\n", failures)).append("],\n");
    j.append("\"invokes\":").append(invokes).append(",\"unresolvedCalls\":").append(unresolvedCalls).append(",\n");
    j.append("\"linkKinds\":{");
    boolean first = true;
    for (var e : kinds.entrySet()) { j.append(first ? "" : ",").append(esc(e.getKey())).append(':').append(e.getValue()); first = false; }
    j.append("},\n\"linkErrors\":[");
    first = true;
    int shown = 0;
    for (Finding f : findings) {
      if (shown++ >= 200000) break;
      j.append(first ? "\n" : ",\n").append("{\"kind\":").append(esc(f.kind)).append(",\"class\":").append(esc(f.cls.replace('/', '.')))
          .append(",\"method\":").append(esc(f.method)).append(",\"insn\":").append(esc(f.insn)).append(",\"detail\":").append(esc(f.detail)).append('}');
      first = false;
    }
    j.append("],\n\"unresolvedOwners\":{");
    first = true;
    int n = 0;
    var top = new ArrayList<>(unresolvedOwners.entrySet());
    top.sort((x, y) -> y.getValue() - x.getValue());
    for (var e : top) {
      if (n++ >= 50) break;
      j.append(first ? "" : ",").append(esc(e.getKey())).append(':').append(e.getValue());
      first = false;
    }
    j.append("}\n}\n");
    Files.writeString(Path.of(a[1]), j.toString(), StandardCharsets.UTF_8);
    System.out.printf("%s: %d classes, %d translate failures, %d link errors, %d invokes (%d unresolved), translate %d ms%n",
        Path.of(a[0]).getFileName(), classes, failures.size(), findings.size(), invokes, unresolvedCalls, translateMs);
  }
}
