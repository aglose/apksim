package dev.apksim;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * and log (list the tests without running them).
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
    // @RunWith(AndroidJUnit4.class) hands each class to the runner this names (it would pick Robolectric's own).
    System.setProperty("android.junit.runner", SimRunner.class.getName());
    // What Host's annotations ask for, for test classes that carry none.
    System.setProperty("robolectric.graphicsMode", "NATIVE");
    System.setProperty("robolectric.sqliteMode", "NATIVE");
    System.setProperty("robolectric.conscryptMode", "OFF");
    System.setProperty("robolectric.looperMode", "PAUSED");

    long t0 = System.nanoTime();
    List<Class<?>> classes = testClasses(tests, args);
    System.err.printf("[apksim] instrumentation %s: %d test classes found in %d ms%n", instr[0], classes.size(),
        (System.nanoTime() - t0) / 1_000_000);
    List<Runner> runners = new ArrayList<>();
    for (Class<?> c : classes) {
      // A class without @RunWith gets what AndroidJUnitRunner would give it, an Android runner: here, Robolectric's.
      runners.add(c.isAnnotationPresent(RunWith.class) ? Request.aClass(c).getRunner() : new SimRunner(c));
    }
    Request request = Request.runner(new org.junit.runners.Suite((Class<?>) null, runners) {});
    Filter filter = methodFilter(args.get("class"), args.get("notClass"));
    if (filter != null) request = request.filterWith(filter);

    PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
    RawListener raw = new RawListener(out, instr[0]);
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
    JUnitCore core = new JUnitCore();
    core.addListener(raw);
    Result r = core.run(request);
    raw.finish(r, r.getRunTime());
    return r.wasSuccessful() ? 0 : 1;
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
      for (String c : cls.split(",")) onlyClasses.add(c.split("#")[0].trim());
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
      Class<?> c;
      try {
        c = Class.forName(name, false, loader);
      } catch (ClassNotFoundException | LinkageError e) {
        continue;
      }
      if (isTestClass(c)) out.add(c);
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

  private static boolean isTestClass(Class<?> c) {
    int m = c.getModifiers();
    if (!Modifier.isPublic(m) || Modifier.isAbstract(m) || c.isInterface()) return false;
    if (c.isAnnotationPresent(RunWith.class)) return true;
    try {
      for (Method method : c.getMethods()) {
        if (method.isAnnotationPresent(Test.class)) return true;
      }
    } catch (LinkageError e) {
      return false;
    }
    return false;
  }

  /** -e class a.B,a.C#m and -e notClass, as tests to keep. */
  private static Filter methodFilter(String only, String not) {
    List<String> keep = split(only);
    List<String> drop = split(not);
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

  private static List<Description> leaves(Description d, List<Description> out) {
    if (d.isTest()) {
      out.add(d);
    } else {
      for (Description c : d.getChildren()) leaves(c, out);
    }
    return out;
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
      } else if (!f.getDescription().isTest()) {
        // A class that could not run at all (initializationError): report it as one failed test, as the runner does.
        testStarted(f.getDescription());
        code = FAILURE;
        failure = f;
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
      String stream = code == FAILURE ? "\nError in " + d.getMethodName() + "(" + d.getClassName() + "):\n" + trace(failure) : ".";
      status(d, code, stream, code == FAILURE || code == ASSUMPTION ? trace(failure) : null);
    }

    private void status(Description d, int statusCode, String stream, String stack) {
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
      if (r.wasSuccessful()) {
        s.append("\nOK (").append(r.getRunCount()).append(" test").append(r.getRunCount() == 1 ? "" : "s").append(")\n");
      } else {
        s.append("There ").append(r.getFailureCount() == 1 ? "was 1 failure:" : "were " + r.getFailureCount() + " failures:");
        int i = 1;
        for (Failure f : r.getFailures()) s.append("\n").append(i++).append(") ").append(f.getTestHeader()).append("\n").append(trace(f));
        s.append("\nFAILURES!!!\nTests run: ").append(r.getRunCount()).append(",  Failures: ").append(r.getFailureCount()).append("\n");
      }
      out.println("INSTRUMENTATION_RESULT: stream=" + s);
      out.println("INSTRUMENTATION_CODE: -1");
    }
  }
}
