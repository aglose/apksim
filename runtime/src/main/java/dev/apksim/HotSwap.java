package dev.apksim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Spike S2 (docs/spike-s2-hot-swap.md): a rebuilt APK's changed classes swapped into the running app. Opt-in, a
 * prototype: the {@code hotswap NEW.apk [none|redraw|compose|compose-reset|recreate]} control command, and {@code java -cp ...
 * dev.apksim.HotSwap OLD.apk NEW.apk} offline for the list of changes and their shapes.
 *
 * <p>Classes are compared by {@link dev.apksim.dex.ClassHash} (index-independent, so two builds compare). A changed
 * class that is loaded is translated afresh from the new APK and redefined in place with JVMTI RedefineClasses
 * ({@code native/hotswap.c}) when its shape is what HotSpot allows (same supers, fields, methods and modifiers: only
 * method bodies differ); a changed class not yet loaded, and every added class, is served from the new APK when the
 * class loader asks ({@link AppClasses#swapped}). One loaded class whose shape changed and nothing is redefined: the
 * answer says why, and that the app must be relaunched.
 */
final class HotSwap {
  private HotSwap() {}

  /** What the comparison of two builds found. */
  record Diff(List<String> changed, List<String> added, List<String> removed, int classes, long hashMs) {}

  /**
   * The classes that differ. Only the new build's DEX files that the old build has no identical copy of are looked at
   * (a debug build of the test app changes one: the module's own), and in them each class's content hash against the
   * old build's, wherever the old build defines it. {@code -Dapksim.hotswap.allDex=true} hashes every class.
   */
  static Diff diff(AppClasses old, AppClasses neu) {
    long t0 = System.nanoTime();
    boolean all = Boolean.getBoolean("apksim.hotswap.allDex");
    Set<String> oldKeys = new java.util.HashSet<>();
    for (int i = 0; i < old.dexCount(); i++) oldKeys.add(old.dexKey(i));
    Set<String> newKeys = new java.util.HashSet<>();
    for (int i = 0; i < neu.dexCount(); i++) newKeys.add(neu.dexKey(i));
    List<String> changed = new ArrayList<>();
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    int classes = 0;
    Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < neu.dexCount(); i++) {
      List<String> names = neu.namesIn(i);
      classes += names.size();
      if (!all && oldKeys.contains(neu.dexKey(i))) continue;
      for (String n : names) {
        if (!seen.add(n)) continue;
        byte[] h = old.contentHash(n);
        byte[] g = neu.contentHash(n);
        if (h == null) added.add(n);
        else if (h.length == 0 || g.length == 0 || !Arrays.equals(h, g)) changed.add(n);
      }
    }
    for (int i = 0; i < old.dexCount(); i++) {
      if (!all && newKeys.contains(old.dexKey(i))) continue;
      for (String n : old.namesIn(i)) if (neu.contentHash(n) == null) removed.add(n);
    }
    changed.sort(null);
    added.sort(null);
    removed.sort(null);
    return new Diff(changed, added, removed, classes, (System.nanoTime() - t0) / 1_000_000);
  }

  /**
   * Why HotSpot would refuse to redefine a class with these bytes, or null if only method bodies differ (what
   * {@code JvmtiRedefineClasses::compare_and_normalize_class_versions} checks).
   */
  static String incompatibility(byte[] oldBytes, byte[] newBytes) {
    ClassNode o = node(oldBytes);
    ClassNode n = node(newBytes);
    List<String> why = new ArrayList<>();
    if (!java.util.Objects.equals(o.superName, n.superName)) why.add("superclass " + o.superName + " -> " + n.superName);
    if (!new TreeSet<>(o.interfaces).equals(new TreeSet<>(n.interfaces))) why.add("interfaces " + o.interfaces + " -> " + n.interfaces);
    if ((o.access & 0xffff & ~0x20) != (n.access & 0xffff & ~0x20)) why.add("class modifiers " + Integer.toHexString(o.access) + " -> " + Integer.toHexString(n.access));
    Set<String> of = new TreeSet<>();
    for (FieldNode f : o.fields) of.add(f.name + ":" + f.desc + "/" + Integer.toHexString(f.access));
    Set<String> nf = new TreeSet<>();
    for (FieldNode f : n.fields) nf.add(f.name + ":" + f.desc + "/" + Integer.toHexString(f.access));
    setDiff("field", of, nf, why);
    Set<String> om = new TreeSet<>();
    for (MethodNode m : o.methods) om.add(m.name + m.desc + "/" + Integer.toHexString(m.access & 0xffff));
    Set<String> nm = new TreeSet<>();
    for (MethodNode m : n.methods) nm.add(m.name + m.desc + "/" + Integer.toHexString(m.access & 0xffff));
    setDiff("method", om, nm, why);
    if (!java.util.Objects.equals(o.nestHostClass, n.nestHostClass)) why.add("nest host");
    if (!java.util.Objects.equals(o.nestMembers, n.nestMembers)) why.add("nest members");
    if (!java.util.Objects.equals(o.permittedSubclasses, n.permittedSubclasses)) why.add("permitted subclasses");
    return why.isEmpty() ? null : String.join("; ", why);
  }

  private static void setDiff(String what, Set<String> o, Set<String> n, List<String> why) {
    Set<String> gone = new TreeSet<>(o);
    gone.removeAll(n);
    Set<String> come = new TreeSet<>(n);
    come.removeAll(o);
    if (!gone.isEmpty()) why.add(what + "s removed " + gone);
    if (!come.isEmpty()) why.add(what + "s added " + come);
  }

  /** The class with stack map frames computed by ASM (the JVM's split verifier needs them), supers resolved by the app's loader. */
  static byte[] withFrames(byte[] b) {
    ClassReader r = new ClassReader(b);
    org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
      @Override
      protected ClassLoader getClassLoader() {
        return ClassLoader.getSystemClassLoader();
      }
    };
    r.accept(w, ClassReader.SKIP_FRAMES);
    return w.toByteArray();
  }

  private static ClassNode node(byte[] b) {
    ClassNode n = new ClassNode();
    new ClassReader(b).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return n;
  }

  private static java.lang.reflect.Method findLoaded;

  /** The class if the app's class loader has already loaded it, else null (never loads it). */
  static Class<?> loaded(String internalName) {
    try {
      if (findLoaded == null) {
        findLoaded = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        findLoaded.setAccessible(true);
      }
      return (Class<?>) findLoaded.invoke(ClassLoader.getSystemClassLoader(), internalName.replace('/', '.'));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Null when the class came from the APK (the {@code apksim:} class path entry), else where it came from. */
  static String origin(Class<?> c) {
    var cs = c.getProtectionDomain().getCodeSource();
    String at = cs == null || cs.getLocation() == null ? "?" : cs.getLocation().toString();
    return at.startsWith("apksim:") ? null : at;
  }

  private static boolean libLoaded;

  private static native int nativeRedefine(Class<?>[] classes, byte[][] bytes);

  static int redefine(Class<?>[] classes, byte[][] bytes) {
    if (!libLoaded) {
      try {
        System.load(HostOs.runtimeLib("libhotswap").toString());
      } catch (UnsatisfiedLinkError e) {
        throw new IllegalStateException("libhotswap is not built (scripts/build-native.sh)", e);
      }
      libLoaded = true;
    }
    return nativeRedefine(classes, bytes);
  }

  /** JVMTI's name for an error RedefineClasses returns. */
  static String jvmtiError(int e) {
    return switch (e) {
      case 0 -> "none";
      case 60 -> "INVALID_CLASS_FORMAT";
      case 61 -> "CIRCULAR_CLASS_DEFINITION";
      case 62 -> "FAILS_VERIFICATION";
      case 63 -> "UNSUPPORTED_REDEFINITION_METHOD_ADDED";
      case 64 -> "UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED";
      case 65 -> "INVALID_TYPESTATE";
      case 66 -> "UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED";
      case 67 -> "UNSUPPORTED_REDEFINITION_METHOD_DELETED";
      case 68 -> "UNSUPPORTED_VERSION";
      case 69 -> "NAMES_DONT_MATCH";
      case 70 -> "UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED";
      case 71 -> "UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED";
      case 72 -> "UNSUPPORTED_REDEFINITION_CLASS_ATTRIBUTE_CHANGED";
      case 99 -> "MUST_POSSESS_CAPABILITY";
      default -> e < -1000 ? "AddCapabilities failed: " + (-1000 - e) : "error " + e;
    };
  }

  /** Called on the main thread with the refresh mode once classes are swapped: draws, resets Compose, or recreates. */
  interface Refresh {
    String run(String mode) throws Exception;
  }

  /** The newest build swapped in, kept so its mapped DEX files stay valid for classes served later. */
  private static AppClasses current;

  /** {@code hotswap NEW.apk [none|redraw|compose|compose-reset|recreate] [force]}: see the class comment. One line. */
  static String command(String[] a, Refresh refresh) throws Exception {
    if (a.length < 2) return "usage: hotswap NEW.apk [none|redraw|compose|compose-reset|recreate] [force]";
    // It runs another APK's code in this instance: only when the launch asked for it (`--jvm -Dapksim.hotswap=true`).
    if (!Boolean.getBoolean("apksim.hotswap")) return "error: hotswap is off (launch with --jvm -Dapksim.hotswap=true)";
    if (!HostOs.MAC) return "error: hotswap is Mac only for now";
    AppClasses app = AppClasses.app;
    if (app == null) return "error: no app classes";
    if (a[1].equals("selftest")) return selftest(app, a.length > 2 ? a[2] : "");
    String mode = a.length > 2 ? a[2] : "none";
    boolean force = a.length > 3 && a[3].equals("force");
    long t0 = System.nanoTime();
    AppClasses neu = AppClasses.isolated(Storage.absolute(a[1]));
    AppClasses base = current != null ? current : app;
    Diff d = diff(base, neu);
    long t1 = System.nanoTime();
    List<Class<?>> classes = new ArrayList<>();
    List<byte[]> bytes = new ArrayList<>();
    Map<String, String> refused = new TreeMap<>();
    List<String> notLoaded = new ArrayList<>();
    int translated = 0;
    int identical = 0;
    int notApk = 0;
    for (String name : d.changed()) {
      Class<?> c = loaded(name);
      if (c == null) {
        notLoaded.add(name);
        continue;
      }
      String from = origin(c);
      if (from != null) {
        // A jar on apksim's own class path defines it first (Robolectric's Guava, say): the APK's copy never ran.
        notApk++;
        continue;
      }
      byte[] nb = neu.translateFresh(name);
      byte[] ob = base == app ? app.peek(name) : base.translateFresh(name);
      translated++;
      if (nb == null || ob == null) {
        refused.put(name, "cannot translate");
        continue;
      }
      if (Arrays.equals(ob, nb)) {
        // ClassHash saw a difference the translation does not carry (e.g. a Kotlin inline function's line mapping).
        identical++;
        continue;
      }
      String why = incompatibility(ob, nb);
      if (why != null) {
        refused.put(name, why);
        continue;
      }
      // RedefineClasses verifies the new bytes whatever -XX:-BytecodeVerificationRemote says (seen on JDK 24: the
      // split verifier, "with new format"), and translated classes have no StackMapTable: compute frames for them.
      if (!"false".equals(System.getProperty("apksim.hotswap.frames"))) {
        try {
          nb = withFrames(nb);
        } catch (RuntimeException | LinkageError e) {
          refused.put(name, "frames: " + e);
          continue;
        }
      }
      classes.add(c);
      bytes.add(nb);
    }
    long t2 = System.nanoTime();
    StringBuilder out = new StringBuilder();
    out.append(String.format("hotswap: %d classes, %d changed (%d loaded, %d not; %d loaded identical once translated, %d loaded from a jar, not the APK), %d added, %d removed; ",
        d.classes(), d.changed().size(), d.changed().size() - notLoaded.size(), notLoaded.size(), identical, notApk, d.added().size(),
        d.removed().size()));
    int err = 0;
    if (!refused.isEmpty() && !force) {
      out.append("RELAUNCH NEEDED, nothing swapped: ");
      refused.forEach((k, v) -> out.append(k).append(" (").append(v).append("); "));
    } else {
      // Classes not loaded yet (changed or added) are served from the new build before any redefined body can ask
      // for them; taken back if the redefinition fails.
      List<String> served = new ArrayList<>(notLoaded);
      served.addAll(d.added());
      for (String n : served) app.swapped.put(n, () -> neu.translateFresh(n));
      if (!classes.isEmpty()) err = redefine(classes.toArray(new Class<?>[0]), bytes.toArray(new byte[0][]));
      if (err != 0) for (String n : served) app.swapped.remove(n);
      if (err != 0) {
        out.append("RedefineClasses failed: ").append(jvmtiError(err)).append("; ");
        // Which one: each alone (a spike's diagnosis, not what a product would do).
        for (int i = 0; i < classes.size(); i++) {
          int e1 = redefine(new Class<?>[] {classes.get(i)}, new byte[][] {bytes.get(i)});
          if (e1 != 0) out.append(classes.get(i).getName()).append(": ").append(jvmtiError(e1)).append("; ");
        }
      } else {
        out.append("redefined ").append(classes.size()).append(": ");
        for (Class<?> c : classes) out.append(c.getName()).append(' ');
        out.append("; ");
        if (!refused.isEmpty()) out.append("forced past ").append(refused.size()).append(" refused: ").append(refused.keySet()).append("; ");
        current = neu;
      }
    }
    long t3 = System.nanoTime();
    String r = err == 0 && (refused.isEmpty() || force) ? refresh.run(mode) : "skipped";
    long t4 = System.nanoTime();
    if (!d.added().isEmpty()) out.append("added ").append(abbrev(d.added())).append("; ");
    if (!d.removed().isEmpty()) out.append("removed ").append(abbrev(d.removed())).append("; ");
    if (!notLoaded.isEmpty()) out.append("changed, not loaded ").append(abbrev(notLoaded)).append("; ");
    out.append(String.format("refresh %s: %s; ms: open+hash %d, translate+check %d (%d classes), redefine %d, refresh %d, total %d",
        mode, r, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, translated, (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000,
        (t4 - t0) / 1_000_000));
    return out.toString();
  }

  /**
   * {@code hotswap selftest [PREFIX]}: every loaded class of the running APK whose name starts with PREFIX (internal
   * form, e.g. {@code dev/apksim/}) redefined, one at a time, with its own translation plus computed frames: how many
   * the verifier would accept if they were edited. The code does not change, so the app runs on as before.
   */
  static String selftest(AppClasses app, String prefix) {
    // It redefines from the launched build: after a swap that would put the old code back.
    if (current != null) return "error: selftest runs only before the first swap (it would undo it)";
    long t0 = System.nanoTime();
    int tried = 0;
    int fromJar = 0;
    int ok = 0;
    Map<String, Integer> failures = new TreeMap<>();
    List<String> examples = new ArrayList<>();
    Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < app.dexCount(); i++) {
      for (String n : app.namesIn(i)) {
        if (!n.startsWith(prefix) || !seen.add(n)) continue;
        Class<?> c = loaded(n);
        if (c == null) continue;
        if (origin(c) != null) {
          fromJar++;
          continue;
        }
        tried++;
        String why;
        try {
          byte[] b = withFrames(app.peek(n));
          int e = redefine(new Class<?>[] {c}, new byte[][] {b});
          why = e == 0 ? null : jvmtiError(e);
        } catch (RuntimeException | LinkageError e) {
          why = "frames: " + e.getClass().getSimpleName();
        }
        if (why == null) ok++;
        else {
          failures.merge(why, 1, Integer::sum);
          if (examples.size() < 15) examples.add(n + " (" + why + ")");
        }
      }
    }
    String r = String.format("selftest %s: %d loaded from jars skipped, %d loaded classes redefined with their own code, %d accepted, failures %s, e.g. %s; %d ms",
        prefix.isEmpty() ? "(all)" : prefix, fromJar, tried, ok, failures, examples, (System.nanoTime() - t0) / 1_000_000);
    System.err.println("[apksim] hotswap " + r);
    return r;
  }

  private static String abbrev(List<String> names) {
    return names.size() <= 12 ? names.toString() : names.subList(0, 12) + " and " + (names.size() - 12) + " more";
  }

  /** Compose's own hot reload (what Android Studio's Live Edit calls after swapping code): dispose and recompose all. */
  static String composeReset(Object context) throws Exception {
    Class<?> companion = Class.forName("androidx.compose.runtime.HotReloader$Companion", true, ClassLoader.getSystemClassLoader());
    var field = Class.forName("androidx.compose.runtime.HotReloader", true, ClassLoader.getSystemClassLoader()).getField("Companion");
    field.setAccessible(true);
    Object inst = field.get(null);
    var save = companion.getDeclaredMethod("saveStateAndDispose", Object.class);
    var load = companion.getDeclaredMethod("loadStateAndCompose", Object.class);
    save.setAccessible(true);
    load.setAccessible(true);
    Object token = save.invoke(inst, context);
    load.invoke(inst, token);
    return "compose reset";
  }

  /**
   * Every composition the running Recomposers know (subcompositions such as a LazyColumn's items included) invalidated
   * whole: each restartable scope runs again with its remembered state, so the swapped bodies run and nothing resets.
   */
  static String composeInvalidate() throws Exception {
    ClassLoader l = ClassLoader.getSystemClassLoader();
    Class<?> rc = Class.forName("androidx.compose.runtime.Recomposer", true, l);
    var running = rc.getDeclaredField("_runningRecomposers");
    running.setAccessible(true);
    Object flow = running.get(null);
    var value = flow.getClass().getMethod("getValue");
    value.setAccessible(true);
    Iterable<?> infos = (Iterable<?>) value.invoke(flow);
    var known = rc.getDeclaredMethod("knownCompositions");
    known.setAccessible(true);
    int recomposers = 0;
    int compositions = 0;
    for (Object info : infos) {
      var outer = info.getClass().getDeclaredField("this$0");
      outer.setAccessible(true);
      Object recomposer = outer.get(info);
      recomposers++;
      for (Object c : (List<?>) known.invoke(recomposer)) {
        var inv = c.getClass().getMethod("invalidateAll");
        inv.setAccessible(true);
        inv.invoke(c);
        compositions++;
      }
    }
    return "invalidated " + compositions + " compositions of " + recomposers + " recomposers";
  }

  /** Offline:{@code HotSwap OLD.apk NEW.apk}: changed classes and, for each, whether its shape allows redefinition. */
  public static void main(String[] args) throws Exception {
    AppClasses old = AppClasses.isolated(Path.of(args[0]));
    AppClasses neu = AppClasses.isolated(Path.of(args[1]));
    Diff d = diff(old, neu);
    System.out.printf("%d classes, hashed in %d ms: %d changed, %d added, %d removed%n", d.classes(), d.hashMs(),
        d.changed().size(), d.added().size(), d.removed().size());
    for (String n : d.added()) System.out.println("  added   " + n);
    for (String n : d.removed()) System.out.println("  removed " + n);
    for (String n : d.changed()) {
      byte[] ob = old.translateFresh(n);
      byte[] nb = neu.translateFresh(n);
      String why = ob == null || nb == null ? "cannot translate" : Arrays.equals(ob, nb) ? "IDENTICAL once translated" : incompatibility(ob, nb);
      System.out.println("  changed " + n + (why == null ? "  (body only: redefinable)" : "  SHAPE: " + why));
    }
  }
}
