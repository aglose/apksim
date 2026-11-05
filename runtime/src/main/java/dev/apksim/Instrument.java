package dev.apksim;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Runs a test APK (an app's androidTest build) against the app, as {@code am instrument -w -r} does on a device:
 * the test APK's classes join the app's in this one process and its JUnit tests run, reported in am instrument's raw
 * format on stdout.
 *
 * <p>Tests run the way Robolectric runs androidx.test tests: the test body on the Android main thread, with Espresso
 * and ActivityScenario served by Robolectric's in-process implementations, and a simulated clock that jumps ahead
 * whenever the main thread is idle. So nothing is drawn in a window and no test waits for real time. The test APK's
 * instrumentation class (AndroidJUnitRunner or a subclass) is read from its manifest but not run; each test class
 * runs under {@link SimRunner}. Arguments are am instrument's {@code -e} ones: class, notClass, package, notPackage
 * and log (list the tests without running them), and numShards/shardIndex (AndroidJUnitRunner's sharding: a test runs in
 * shard {@code abs(description.hashCode() % numShards)}, so N processes given 0..N-1 run every test exactly once).
 */
final class Instrument {
  /** Library code a test APK carries, never searched for tests (AndroidJUnitRunner skips most of these too). */
  private static final String[] LIBRARIES = {
    "android.", "androidx.", "kotlin.", "kotlinx.", "junit.", "org.junit.", "org.hamcrest.", "com.google.", "org.jetbrains.",
    "org.intellij.", "javax.", "dagger.", "hilt_aggregated_deps.", "org.mockito.", "io.mockk.", "net.bytebuddy.", "org.objenesis.",
    "okhttp3.", "okio.", "com.squareup.", "org.jacoco.", "org.checkerframework.", "_COROUTINE.",
  };

  private Instrument() {}

  static int run(Path testApk, AppClasses app) throws Exception {
    String[] instr = instrumentation(testApk);
    String pkg = System.getProperty("apksim.package");
    if (!pkg.equals(instr[1])) {
      System.err.printf("[apksim] %s targets %s, not %s%n", testApk.getFileName(), instr[1], pkg);
      return 2;
    }
    Map<String, String> args = arguments();
    AppClasses tests = AppClasses.installTest(testApk, app);
    // For each test's InstrumentationRegistry.getInstrumentation().getContext(): the test APK's own (SimRunner).
    System.setProperty("apksim.testApk", testApk.toAbsolutePath().toString());
    System.setProperty("apksim.testPackage", testPackage(testApk));
    // @RunWith(AndroidJUnit4.class) hands each class to the runner this names (it would pick Robolectric's own).
    System.setProperty("android.junit.runner", SimRunner.class.getName());
    // What Host's annotations ask for, for test classes that carry none.
    System.setProperty("robolectric.graphicsMode", "NATIVE");
    System.setProperty("robolectric.sqliteMode", "NATIVE");
    System.setProperty("robolectric.conscryptMode", "OFF");
    System.setProperty("robolectric.looperMode", System.getProperty("apksim.looperMode", "PAUSED"));
    // A window gets the size it asks for, as from a device's window manager, not the whole display: a Compose Dialog
    // (WRAP_CONTENT) was measured at its own width and then again at the display's on every traversal, so Compose
    // never settled its layout and its tests never got idle.
    System.setProperty("robolectric.windowManager.useRequestedSize", "true");

    long t0 = System.nanoTime();
    List<Class<?>> classes = testClasses(tests, args);
    System.err.printf("[apksim] instrumentation %s: %d test classes found in %d ms%n", instr[0], classes.size(),
        (System.nanoTime() - t0) / 1_000_000);
    // The Application the instrumentation's newApplication asks for, which every test then gets. Not when only listing
    // the tests (-e log true, as instrument.py --isolate does to find them): nothing runs, so it would be wasted.
    if (!Boolean.parseBoolean(args.get("log"))) RunnerApplication.probe(instr[0]);
    List<Runner> runners = new ArrayList<>();
    for (Class<?> c : classes) {
      // A class without @RunWith gets what AndroidJUnitRunner would give it, an Android runner: here, Robolectric's.
      // JUnit's Parameterized would run outside the test environment: each parameter set runs as a SimRunner child.
      RunWith with = c.getAnnotation(RunWith.class);
      // (An @Ignore'd class stays on JUnit's path, which reports it ignored without building it or its parameters.)
      if (with != null && with.value() == org.junit.runners.Parameterized.class && !c.isAnnotationPresent(org.junit.Ignore.class)) {
        try {
          runners.add(new SimParameterized(c));
        } catch (org.junit.internal.AssumptionViolatedException a) {
          // An assumption in @Parameters skips the class, as JUnit's Parameterized reports it, rather than failing it.
          runners.add(SimParameterized.assumptionFailed(c, a));
        } catch (Throwable t) {
          runners.add(new org.junit.internal.runners.ErrorReportingRunner(c, t));
        }
        continue;
      }
      runners.add(with != null ? Request.aClass(c).getRunner() : new SimRunner(c));
    }
    Request request = Request.runner(new org.junit.runners.Suite((Class<?>) null, runners) {});
    Filter filter = methodFilter(args.get("class"), args.get("notClass"));
    if (filter != null) request = request.filterWith(filter);
    Filter shard = shardFilter(args.get("numShards"), args.get("shardIndex"));
    boolean emptyShard = false;
    if (shard != null) {
      emptyShard = leaves(request.getRunner().getDescription(), new ArrayList<>()).stream().noneMatch(shard::shouldRun);
      if (!emptyShard) request = request.filterWith(shard);
    }

    PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
    RawListener raw = new RawListener(out, instr[0]);
    if (emptyShard) {
      // A shard can be empty (more shards than tests): a run, or a listing, of no tests, not JUnit's "No tests found"
      // (and not an empty suite either, whose childless description JUnit counts as one test).
      raw.finish(new Result(), 0);
      return 0;
    }
    if (Boolean.parseBoolean(args.get("log"))) {
      // "-e log true": report every test as passed without running it (how tools list a suite).
      Description all = request.getRunner().getDescription();
      raw.testRunStarted(all);
      for (Description d : leaves(all, new ArrayList<>())) {
        raw.testStarted(d);
        raw.testFinished(d);
      }
      Result r = new Result();
      raw.testRunFinished(r);
      raw.finish(r, 0);
      return 0;
    }
    Bundles.runStarted();
    JUnitCore core = new JUnitCore();
    core.addListener(raw);
    if (dev.apksim.dex.Probes.ON) core.addListener(new DepsListener(out));  // after raw: its lines go between blocks
    Result r = core.run(request);
    raw.finish(r, r.getRunTime());
    return r.wasSuccessful() && raw.networkFailures.isEmpty() ? 0 : 1;
  }

  /** The test APK's own package. */
  private static String testPackage(Path testApk) throws Exception {
    String xml;
    try (ZipFile zip = new ZipFile(testApk.toFile()); InputStream in = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
      xml = Axml.decode(in.readAllBytes());
    }
    return DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement().getAttribute("package");
  }

  /** The test APK's {@code <instrumentation>}: its class and the package it targets. */
  private static String[] instrumentation(Path testApk) throws Exception {
    String xml;
    try (ZipFile zip = new ZipFile(testApk.toFile()); InputStream in = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
      xml = Axml.decode(in.readAllBytes());
    }
    Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    NodeList list = root.getElementsByTagName("instrumentation");
    if (list.getLength() == 0) throw new IllegalArgumentException(testApk + " has no <instrumentation>: not a test APK");
    Element e = (Element) list.item(0);
    String name = e.getAttribute("android:name");
    if (name.startsWith(".")) name = root.getAttribute("package") + name;
    return new String[] {name, e.getAttribute("android:targetPackage")};
  }

  /** {@code -e KEY VALUE} arguments, passed by the launcher as apksim.instrument.KEY properties. */
  private static Map<String, String> arguments() {
    Map<String, String> out = new LinkedHashMap<>();
    String prefix = "apksim.instrument.";
    for (String k : System.getProperties().stringPropertyNames()) {
      if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), System.getProperty(k));
    }
    return out;
  }

  /**
   * The test APK's test classes: public, concrete, and annotated @RunWith or with a public @Test method. Library
   * packages are not searched, so only the suite's own classes are translated to look at them.
   */
  private static List<Class<?>> testClasses(AppClasses tests, Map<String, String> args) {
    List<String> onlyClasses = new ArrayList<>();
    String cls = args.get("class");
    if (cls != null) {
      for (String c : selectorParts(cls)) onlyClasses.add(c.split("#")[0].trim());
    }
    List<String> packages = split(args.get("package"));
    List<String> notPackages = split(args.get("notPackage"));
    List<Class<?>> out = new ArrayList<>();
    List<String> names = tests.classNames();
    names.sort(null);
    ClassLoader loader = ClassLoader.getSystemClassLoader();
    for (String name : names) {
      if (!onlyClasses.isEmpty() ? !onlyClasses.contains(name) : (isLibrary(name) || name.contains("$"))) continue;
      if (!packages.isEmpty() && packages.stream().noneMatch(p -> name.startsWith(p + "."))) continue;
      if (notPackages.stream().anyMatch(p -> name.startsWith(p + "."))) continue;
      if (!isTestClass(tests, name.replace('.', '/'), true)) continue;
      try {
        out.add(Class.forName(name, false, loader));
      } catch (ClassNotFoundException | LinkageError e) {
        // not loadable: as AndroidJUnitRunner, skip it
      }
    }
    return out;
  }

  private static List<String> split(String s) {
    List<String> out = new ArrayList<>();
    if (s != null) {
      for (String p : s.split(",")) {
        if (!p.isBlank()) out.add(p.trim());
      }
    }
    return out;
  }

  private static boolean isLibrary(String name) {
    for (String p : LIBRARIES) {
      if (name.startsWith(p)) return true;
    }
    return name.endsWith(".R") || name.endsWith(".BuildConfig");
  }

  /**
   * Public, concrete, and annotated @RunWith or with a public @Test method (its own or inherited). Read from the class
   * bytes, not by reflection: this runs before Robolectric's sandbox is set up, and reflection parses every
   * annotation on a method, so an annotation whose default is a framework enum (android.annotation.SystemApi's
   * Client, in a KMP app's shared-module suite) initialized that framework class without the sandbox, which broke it for the
   * whole run (every test then failed with NoClassDefFoundError).
   */
  private static boolean isTestClass(AppClasses tests, String internalName, boolean top) {
    byte[] bytes = tests.translate(internalName);
    if (bytes == null) return false;  // a library or framework superclass: no tests of the suite's own there
    org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(bytes);
    int access = r.getAccess();
    if (top && ((access & org.objectweb.asm.Opcodes.ACC_PUBLIC) == 0
        || (access & (org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_INTERFACE)) != 0)) {
      return false;
    }
    boolean[] found = {false};
    r.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
      @Override
      public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.equals("Lorg/junit/runner/RunWith;")) found[0] = true;
        return null;
      }

      @Override
      public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
        if ((acc & org.objectweb.asm.Opcodes.ACC_PUBLIC) == 0 || found[0]) return null;
        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
          @Override
          public org.objectweb.asm.AnnotationVisitor visitAnnotation(String d, boolean visible) {
            if (d.equals("Lorg/junit/Test;")) found[0] = true;
            return null;
          }
        };
      }
    }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
    if (found[0]) return true;
    String sup = r.getSuperName();
    return sup != null && !sup.equals("java/lang/Object") && isTestClass(tests, sup, false);
  }

  /**
   * -e class or notClass split on its commas as {@code String.split(",")} would, except that {@code \,} is a comma and
   * {@code \\} a backslash inside a name: an apksim extension (AndroidJUnitRunner has no escape), for names such as
   * Parameterized's {@code test[0: x, y]}, which instrument.py passes when it reruns tests. Any other backslash stays
   * as it is, so input without one parses as before.
   */
  static List<String> selectorParts(String s) {
    List<String> out = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == ',' || s.charAt(i + 1) == '\\')) {
        part.append(s.charAt(++i));
      } else if (ch == ',') {
        out.add(part.toString());
        part.setLength(0);
      } else {
        part.append(ch);
      }
    }
    out.add(part.toString());
    if (out.size() > 1) {  // as String.split: trailing empty parts go (a lone empty string stays)
      while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
    }
    return out;
  }

  /** {@link #selectorParts}, trimmed, without blank parts. */
  private static List<String> selectors(String s) {
    List<String> out = new ArrayList<>();
    if (s != null) {
      for (String p : selectorParts(s)) {
        if (!p.isBlank()) out.add(p.trim());
      }
    }
    return out;
  }

  /** -e class a.B,a.C#m and -e notClass, as tests to keep. */
  private static Filter methodFilter(String only, String not) {
    List<String> keep = selectors(only);
    List<String> drop = selectors(not);
    if (keep.isEmpty() && drop.isEmpty()) return null;
    return new Filter() {
      @Override
      public boolean shouldRun(Description d) {
        if (!d.isTest()) return true;  // a suite: run it if any of its tests run
        String c = d.getClassName();
        String full = c + "#" + d.getMethodName();
        if (drop.contains(c) || drop.contains(full)) return false;
        return keep.isEmpty() || keep.contains(c) || keep.contains(full);
      }

      @Override
      public String describe() {
        return "class " + keep + " notClass " + drop;
      }
    };
  }

  /**
   * -e numShards N -e shardIndex I, as AndroidJUnitRunner's ShardingFilter: a test belongs to the shard its
   * description's hash picks, so shards split classes but every test lands in exactly one, whatever else is filtered.
   */
  private static Filter shardFilter(String numShards, String shardIndex) {
    if (numShards == null && shardIndex == null) return null;
    int n = Integer.parseInt(numShards == null ? "1" : numShards.trim());
    int i = Integer.parseInt(shardIndex == null ? "0" : shardIndex.trim());
    if (n < 1 || i < 0 || i >= n) {
      throw new IllegalArgumentException("-e numShards " + numShards + " -e shardIndex " + shardIndex
          + ": the index must be at least 0 and less than the number of shards");
    }
    if (n == 1) return null;
    return new Filter() {
      @Override
      public boolean shouldRun(Description d) {
        // abs(hash % n), not AndroidJUnitRunner's abs(hash) % n: the same shard for every hash but Integer.MIN_VALUE,
        // whose abs is negative, so that test would have matched no shard and run nowhere.
        if (d.isTest()) return Math.abs(d.hashCode() % n) == i;
        for (Description child : d.getChildren()) {
          if (shouldRun(child)) return true;
        }
        return false;
      }

      @Override
      public String describe() {
        return "shard " + i + " of " + n;
      }
    };
  }

  private static List<Description> leaves(Description d, List<Description> out) {
    if (d.isTest()) {
      out.add(d);
    } else {
      for (Description c : d.getChildren()) leaves(c, out);
    }
    return out;
  }

  /**
   * Test-impact recording (-Dapksim.deps=true, `instrument.py --record-deps`): which classes' code ran in each test, as
   * lines on stdout between am instrument's status blocks, which instrument.py takes out of the output:
   * {@code INSTRUMENTATION_APKSIM_DEPS: P <packages>} lists the probed packages, {@code ... C <id> <class>} names a class the first time it appears,
   * {@code ... T <class> <method> <ids>} lists a finished test's classes, and {@code ... G <class> <ids>} classes that ran
   * between tests (@BeforeClass/@AfterClass), which count for every test of the class before and after them; the class
   * {@code *} stands for every test (what ran before the first: the runners' construction, the runner's setup). Fields are tab-separated, with tab, newline and backslash escaped. A test that never finishes (the
   * process died) has no line, and instrument.py selects such tests always.
   */
  private static final class DepsListener extends RunListener {
    private static final String PREFIX = "INSTRUMENTATION_APKSIM_DEPS: ";
    private final PrintStream out;
    private final Map<String, Integer> ids = new java.util.HashMap<>();
    private String lastClass;

    DepsListener(PrintStream out) {
      this.out = out;
    }

    @Override
    public void testRunStarted(Description d) {
      out.println(PREFIX + "P\t" + dev.apksim.dex.Probes.packages());
      // Before the first test: building the runners (a Parameterized class's @Parameters), the runner's
      // newApplication probe. Any class's tests may depend on that, so "*" puts it on every test.
      line("G", "*", null, dev.apksim.dex.Probes.mark());
    }

    @Override
    public void testStarted(Description d) {
      List<String> gap = dev.apksim.dex.Probes.mark();
      String c = d.getClassName();
      if (lastClass != null && !lastClass.equals(c)) line("G", lastClass, null, gap);
      line("G", c, null, gap);
      lastClass = c;
    }

    @Override
    public void testFinished(Description d) {
      line("T", d.getClassName(), d.getMethodName(), dev.apksim.dex.Probes.mark());
    }

    @Override
    public void testRunFinished(Result r) {
      if (lastClass != null) line("G", lastClass, null, dev.apksim.dex.Probes.mark());
    }

    private void line(String kind, String cls, String method, List<String> classes) {
      if (classes.isEmpty() && kind.equals("G")) return;
      StringBuilder ref = new StringBuilder();
      StringBuilder names = new StringBuilder();
      for (String name : classes) {
        Integer id = ids.get(name);
        if (id == null) {
          id = ids.size();
          ids.put(name, id);
          names.append(PREFIX).append("C\t").append(id).append('\t').append(escape(name)).append('\n');
        }
        if (ref.length() > 0) ref.append(' ');
        ref.append(id);
      }
      StringBuilder s = new StringBuilder(names).append(PREFIX).append(kind).append('\t').append(escape(cls));
      if (method != null) s.append('\t').append(escape(method));
      s.append('\t').append(ref);
      out.println(s);
    }

    private static String escape(String s) {
      return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }
  }

  /** am instrument -r output, as AndroidJUnitRunner's InstrumentationResultPrinter writes it. */
  private static final class RawListener extends RunListener {
    private static final int START = 1, OK = 0, FAILURE = -2, IGNORED = -3, ASSUMPTION = -4;
    private final PrintStream out;
    private final String id;
    private int total;
    private int current;
    private int code;
    private Failure failure;
    private String lastClass;
    /** Tests that passed but made replay requests the recording had no answer for (docs/network.md). */
    final List<Failure> networkFailures = new ArrayList<>();

    RawListener(PrintStream out, String instrumentation) {
      this.out = out;
      this.id = instrumentation.substring(instrumentation.lastIndexOf('.') + 1);
    }

    @Override
    public void testRunStarted(Description d) {
      total = d.testCount();
    }

    @Override
    public void testStarted(Description d) {
      current++;
      code = OK;
      failure = null;
      dev.apksim.net.Net.testStarted();
      Bundles.testStarted(d);
      String stream = "";
      if (!d.getClassName().equals(lastClass)) {
        stream = "\n" + d.getClassName() + ":";
        lastClass = d.getClassName();
      }
      status(d, START, stream, null);
    }

    @Override
    public void testFailure(Failure f) {
      if (f.getDescription().isTest() && failure == null) {
        code = FAILURE;
        failure = f;
        Bundles.listenerFailure(f.getDescription(), f.getException());
      } else if (!f.getDescription().isTest()) {
        // A class that could not run at all (initializationError): report it as one failed test, as the runner does.
        testStarted(f.getDescription());
        code = FAILURE;
        failure = f;
        Bundles.listenerFailure(f.getDescription(), f.getException());
        testFinished(f.getDescription());
      }
    }

    @Override
    public void testAssumptionFailure(Failure f) {
      code = ASSUMPTION;
      failure = f;
    }

    @Override
    public void testIgnored(Description d) {
      testStarted(d);
      code = IGNORED;
      testFinished(d);
    }

    @Override
    public void testFinished(Description d) {
      List<String> unmatched = dev.apksim.net.Net.takeUnmatchedFailures();
      if (!unmatched.isEmpty() && code == OK) {
        code = FAILURE;
        failure = new Failure(d, new AssertionError(unmatched.size() + " request" + (unmatched.size() == 1 ? "" : "s")
            + " the network recording has no answer for (apksim --network replay):\n  " + String.join("\n  ", unmatched)));
        networkFailures.add(failure);
        Bundles.listenerFailure(d, failure.getException());
      }
      Bundles.testFinished(d, code == OK, code == FAILURE);
      String stream = code == FAILURE ? "\nError in " + d.getMethodName() + "(" + d.getClassName() + "):\n" + trace(failure) : ".";
      status(d, code, stream, code == FAILURE || code == ASSUMPTION ? trace(failure) : null);
    }

    private void status(Description d, int statusCode, String stream, String stack) {
      // Where this test's failure bundle is (--bundles): an extra key, which am instrument parsers keep or ignore.
      String bundle = statusCode == START ? null : Bundles.bundleOf(d);
      if (bundle != null) out.println("INSTRUMENTATION_STATUS: apksim_bundle=" + bundle);
      out.println("INSTRUMENTATION_STATUS: class=" + d.getClassName());
      out.println("INSTRUMENTATION_STATUS: current=" + current);
      out.println("INSTRUMENTATION_STATUS: id=" + id);
      out.println("INSTRUMENTATION_STATUS: numtests=" + total);
      if (stack != null) out.println("INSTRUMENTATION_STATUS: stack=" + stack);
      out.println("INSTRUMENTATION_STATUS: stream=" + stream);
      out.println("INSTRUMENTATION_STATUS: test=" + d.getMethodName());
      out.println("INSTRUMENTATION_STATUS_CODE: " + statusCode);
    }

    private static String trace(Failure f) {
      if (f == null) return "";
      StringWriter w = new StringWriter();
      f.getException().printStackTrace(new PrintWriter(w));
      return w.toString();
    }

    void finish(Result r, long ms) {
      StringBuilder s = new StringBuilder("\n\nTime: ").append(ms / 1000.0).append("\n");
      List<Failure> failures = new ArrayList<>(r.getFailures());
      failures.addAll(networkFailures);
      if (failures.isEmpty()) {
        s.append("\nOK (").append(r.getRunCount()).append(" test").append(r.getRunCount() == 1 ? "" : "s").append(")\n");
      } else {
        s.append("There ").append(failures.size() == 1 ? "was 1 failure:" : "were " + failures.size() + " failures:");
        int i = 1;
        for (Failure f : failures) s.append("\n").append(i++).append(") ").append(f.getTestHeader()).append("\n").append(trace(f));
        s.append("\nFAILURES!!!\nTests run: ").append(r.getRunCount()).append(",  Failures: ").append(failures.size()).append("\n");
      }
      out.println("INSTRUMENTATION_RESULT: stream=" + s);
      out.println("INSTRUMENTATION_CODE: -1");
    }
  }
}
