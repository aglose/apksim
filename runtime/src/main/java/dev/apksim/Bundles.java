package dev.apksim;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.os.Looper;
import android.util.StringBuilderPrinter;
import android.view.View;
import android.view.WindowManager;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

/**
 * Failure bundles (`--bundles DIR` in a test run): when a test fails, what CI needs to see why without running it
 * again, in DIR/&lt;class&gt;/&lt;method&gt;-&lt;hash&gt;/: the failure and the run's settings ({@code failure.txt}), the screen
 * ({@code screen.png}, {@code windows.txt}), the accessibility tree ({@code nodes.txt}), the test's log lines
 * ({@code logcat.txt}), every thread ({@code threads.txt}), the main queue and activity stages ({@code looper.txt}),
 * and a command that runs the test alone ({@code repro.sh}). docs/plan.md phase 5 is the spec.
 *
 * <p>Where the capture happens matters. JUnit reports a failure to listeners only after the test's @After methods and
 * rules have run, and ActivityScenarioRule and Compose's rule close their activity in that teardown, so a listener
 * finds no windows. So the test method itself is wrapped ({@link #wrap}, from SimRunner's helper runner): a Throwable
 * out of it is captured on the main thread before anything unwinds, then rethrown as it was. The listener
 * ({@link #listenerFailure}) covers what that cannot see (@Before/@After and rule failures, classes with their own
 * runner), and the Timeout watchdog writes what it can before it ends the process ({@link #watchdog}).
 *
 * <p>A capture must not change the result or the state it shows: every part has its own try/catch, the test's own
 * Throwable is the one reported, and nothing here runs the main queue, moves the clock or asks for a frame. Nor does it
 * read the accessibility tree while the test may still go on with that screen ({@link #nodesPending}).
 */
public final class Bundles {
  private Bundles() {}

  private static final String ROOT = System.getProperty("apksim.bundles");
  /**
   * Bundles are being written (a test run with --bundles). Not in a run that only lists the suite (-e log true, as
   * instrument.py does to rerun what a crashed process left): its tests "pass" without running, and with
   * --bundles-on all they would overwrite the crashed process's bundles.
   */
  static final boolean ON = ROOT != null && TestActivities.testRun()
      && !Boolean.parseBoolean(System.getProperty("apksim.instrument.log"));
  /** --bundles-on all: passing tests get a bundle too (for debugging a test that passes). */
  private static final boolean ALL = "all".equals(System.getProperty("apksim.bundles.on"));
  /** Full bundles per process; later failures get failure.txt only (a broken build failing thousands of tests). */
  private static final int MAX = Integer.getInteger("apksim.bundles.max", 50);
  private static final List<Pattern> REDACT = redactions();
  /** Log lines kept per test: the first and last half of this many. */
  private static final int LOG_LINES = 5000;

  /**
   * Held while a bundle is written and while the current test changes. The watchdog only waits a while for it: the
   * main thread may be stuck inside a capture, and the watchdog must still end the process.
   */
  private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();
  private static volatile Description current;
  private static long logFrom;
  private static int testsBefore = -1;
  /** The bundle the current test has, or null. */
  private static volatile Path written;
  /** It took one of the --bundles-max full slots (a bundle written past the limit has failure.txt only). */
  private static boolean writtenFull;
  /** It was written for a body that returned (--bundles-on all): a later failure (@After, a rule) still rewrites it. */
  private static boolean writtenPassed;
  /** The exception the current bundle describes (null: a test that passed). */
  private static Throwable writtenFor;
  /** What Espresso's handler threw for it (its own, friendlier exception): the same failure when it leaves the body. */
  private static volatile Throwable writtenAlias;
  /**
   * Its nodes.txt is still to be read ({@link #pendingNodes}): it was captured in Espresso's failure handler, and
   * the test may handle that failure and go on. Reading the tree is not side-effect free: Compose's node provider adds
   * a child view to the AndroidComposeView (its AndroidViewsHandler) and builds its accessibility caches, and a test
   * that went on with that screen behaved differently (issue #73). So it is read once the screen cannot matter to the
   * test any more: when the failure leaves the test body, as an activity finishes (its own windows), or at the end.
   */
  private static boolean nodesPending;
  private static int full;
  /** Parts that ran past their deadline once: skipped in later bundles of this process. */
  private static final java.util.Set<String> TIMED_OUT = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bundle-deadline");
        t.setDaemon(true);
        return t;
      });

  private static List<Pattern> redactions() {
    List<Pattern> out = new ArrayList<>();
    for (int i = 0; System.getProperty("apksim.bundles.redact." + i) != null; i++) {
      out.add(Pattern.compile(System.getProperty("apksim.bundles.redact." + i)));
    }
    return out;
  }

  /** Before the first test: keep the app's log from here on (it is only kept once something asks for it). */
  static void runStarted() {
    if (ON) AdbLog.install();
  }

  static void testStarted(Description d) {
    if (!ON) return;
    LOCK.lock();
    try {
      current = d;
      written = null;
      nodesPending = false;
      testsBefore++;
      markLog();
    } finally {
      LOCK.unlock();
    }
  }

  /** From here on the current test's log lines are kept, its first ones even past the ring's capacity. */
  private static void markLog() {
    logFrom = AdbLog.next();
    AdbLog.keepHead(logFrom, LOG_LINES / 2);
  }

  /** The bundle a finished test has, or null: for its status block (apksim_bundle=PATH). */
  static String bundleOf(Description d) {
    Path w = written;
    return ON && w != null && d.equals(current) ? w.toString() : null;
  }

  /**
   * The test method's statement, captured when it throws, before @After methods and rules run (and with --bundles-on
   * all when it returns). An assumption failing is not a failure.
   */
  static Statement wrap(Statement s, Class<? extends Throwable> expected) {
    if (!ON) return s;
    boolean expects = expected != null && expected != org.junit.Test.None.class;
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        installEspressoHandler();
        try {
          s.evaluate();
        } catch (org.junit.internal.AssumptionViolatedException e) {
          throw e;
        } catch (Throwable t) {
          if (expects && !ALL && expected.isInstance(t)) {
            // What @Test(expected) expects: the test passes, so a failures-only run writes nothing (rather than a
            // bundle testFinished would delete).
            throw t;
          }
          if (written == null) {
            locked("test body", t, false);
          } else if (!sameFailure(t, writtenFor) && !sameFailure(t, writtenAlias)) {
            // Espresso's handler captured a failure the test then handled (caught, or its own handler swallowed it),
            // and a different one ended the body: the bundle describes this one, with a new screen if there still is one.
            refresh("test body (an earlier failure was handled)", t, true);
          } else {
            // Espresso's handler captured this failure, and it ended the body: the tree can be read now.
            pendingNodes("as the failure left the test body", null);
          }
          throw t;
        }
        if (ALL && written == null) {
          locked("test body (passed)", null, true);
        } else if (ALL) {
          // A bundle from a failure the test handled (Espresso's handler): rewrite it in its slot rather than take
          // another; the screen is the body's end.
          LOCK.lock();
          try {
            capture("test body (passed; an earlier failure was handled)", null, true, false);
            writtenPassed = true;
          } finally {
            LOCK.unlock();
          }
        }
      }
    };
  }

  /**
   * The test's outcome settles its bundle. Without --bundles-on all, a test that did not fail loses a bundle written
   * along the way (one that expects an Espresso failure and catches it passes, though the failure handler captured
   * it; so does an ignored or assumption-skipped one). With it, a test that passed gets a bundle that says so: one it
   * lacks (a class with its own runner bypasses the body's wrapper), or its body's capture marked passed after all
   * (the body threw what @Test(expected) expected).
   */
  static void testFinished(Description d, boolean passed, boolean failed) {
    if (!ON || failed) return;
    LOCK.lock();
    try {
      Path w = written;
      boolean has = w != null && d.equals(current);
      if (!ALL || !passed) {
        if (!has) return;
        written = null;
        writtenFor = null;
        nodesPending = false;
        if (writtenFull) full--;
        writtenFull = false;
        try {
          delete(w, true);
        } catch (IOException e) {
          System.err.println("[apksim] could not remove the failure bundle of test " + d + ", which did not fail: " + e);
        }
        return;
      }
      if (!d.equals(current)) return;
      if (!has) {
        capture("listener (passed; after the test's teardown)", null, true, true);
        writtenPassed = true;
      } else if (!writtenPassed) {
        capture("listener (passed: what the body threw was expected or handled; screen and tree from then)", null, false, false);
        writtenPassed = true;
      }
      pendingNodes("after the test's teardown", null);
    } finally {
      LOCK.unlock();
    }
  }

  /** One failure seen twice: the same exception, or one the cause of the other (a handler that wraps what it got). */
  private static boolean sameFailure(Throwable a, Throwable b) {
    if (a == null || b == null) return a == b;
    for (Throwable x = a; x != null; x = x.getCause() == x ? null : x.getCause()) if (x == b) return true;
    for (Throwable x = b; x != null; x = x.getCause() == x ? null : x.getCause()) if (x == a) return true;
    return false;
  }

  // --- Espresso ---------------------------------------------------------------------------------------------------

  private static Object espressoHolder;
  private static Method holderGet, holderUpdate;
  private static Class<?> espressoHandlerType;
  private static boolean noEspresso;
  /** Our wrappers, and the handler each wraps: a wrapper is never wrapped again. */
  private static final Map<Object, Object> WRAPS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

  /**
   * Espresso's failures are captured as they happen, in its failure handler: by the time its exception leaves the test
   * method, a scenario closed by `use {}` (or try-with-resources) has destroyed the activity. At the start of each
   * test body (after @Before and rules, which may set a handler of their own) the handler Espresso has is wrapped:
   * the wrapper captures, then hands the failure to that handler, so a test's own handler still decides what happens.
   * Through Espresso's handler holder (Espresso.BASE.failureHolder()), read by reflection; where that is not there (a
   * minified or much older Espresso), nothing is wrapped and failures are captured when they leave the test method.
   */
  private static void installEspressoHandler() {
    if (noEspresso) return;
    try {
      if (espressoHolder == null) {
        ClassLoader l = ClassLoader.getSystemClassLoader();
        espressoHandlerType = Class.forName("androidx.test.espresso.FailureHandler", false, l);
        java.lang.reflect.Field base = Class.forName("androidx.test.espresso.Espresso", true, l).getDeclaredField("BASE");
        base.setAccessible(true);
        Object component = base.get(null);
        Method failureHolder = component.getClass().getMethod("failureHolder");
        failureHolder.setAccessible(true);
        Object holder = failureHolder.invoke(component);
        holderGet = holder.getClass().getMethod("get");
        holderUpdate = holder.getClass().getMethod("update", espressoHandlerType);
        espressoHolder = holder;
      }
      Object installed = holderGet.invoke(espressoHolder);
      Object inner = WRAPS.getOrDefault(installed, installed);
      if (inner == null) return;
      Object proxy = java.lang.reflect.Proxy.newProxyInstance(espressoHandlerType.getClassLoader(), new Class<?>[] {espressoHandlerType},
          (self, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
              return switch (m.getName()) {
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                default -> "apksim failure bundles around " + inner;
              };
            }
            boolean captured = false;
            if (m.getName().equals("handle") && args != null && args.length > 0 && args[0] instanceof Throwable t && written == null) {
              // The test may handle this failure and go on (a poll that retries): the tree is read later.
              LOCK.lock();
              try {
                capture("Espresso failure handler", t, true, true, false);
                writtenPassed = false;
              } finally {
                LOCK.unlock();
              }
              captured = true;
            }
            try {
              m.setAccessible(true);
              return m.invoke(inner, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
              // The handler throws an exception of its own (DefaultFailureHandler's friendlier one): when that leaves
              // the body it is the failure already captured, not another.
              if (captured) writtenAlias = e.getCause();
              throw e.getCause();
            }
          });
      WRAPS.put(proxy, inner);
      holderUpdate.invoke(espressoHolder, proxy);
    } catch (ReflectiveOperationException | LinkageError e) {
      noEspresso = true;  // a test APK without Espresso, or one whose holder is not there to read
    } catch (Throwable e) {
      System.err.println("[apksim] failure bundles: Espresso's failure handler not wrapped: " + e);
    }
  }

  /** A failure the test body did not see first: @Before, @After, a rule, a class with its own runner, a class error. */
  static void listenerFailure(Description d, Throwable t) {
    if (!ON) return;
    LOCK.lock();
    try {
      if (written != null && d.equals(current)) {
        // The bundle describes something else: a body that returned (--bundles-on all) or a failure the test handled,
        // and the test failed after all (in @After, a rule, or with another exception). Keep the earlier screen; the
        // failure, log and threads are this failure's.
        if (writtenPassed || !sameFailure(t, writtenFor) && !sameFailure(t, writtenAlias)) {
          capture("listener (after the test's teardown; screen and tree from the earlier capture)", t, false, false);
          writtenPassed = false;
        }
        pendingNodes("after the test's teardown", null);
        return;
      }
      if (!d.equals(current)) {
        // A failure for something that never started (a class-level error) is that thing's bundle.
        current = d;
        written = null;
        markLog();
      }
      capture("listener (after the test's teardown)", t, true, true);
    } finally {
      LOCK.unlock();
    }
  }

  /**
   * The Timeout watchdog is about to end the process: a test ignores interrupts. The main thread is held, so only what
   * can be read from another thread: the failure, the threads and the log.
   */
  public static void watchdog(String why) {
    if (!ON) return;
    try {
      if (!LOCK.tryLock(2, java.util.concurrent.TimeUnit.SECONDS)) return;
      try {
        capture("timeout watchdog, before ending the process", new Exception(why), false, written == null);
      } finally {
        LOCK.unlock();
      }
    } catch (Throwable ignored) {
      // the process ends next whatever happens here
    }
  }

  // ---------------------------------------------------------------------------------------------------------------

  private static void locked(String point, Throwable t, boolean passed) {
    LOCK.lock();
    try {
      capture(point, t, true, true);
      writtenPassed = passed;
    } finally {
      LOCK.unlock();
    }
  }

  /** Rewrites the current bundle for a later failure, the screen too when [screen] and there are windows to draw. */
  private static void refresh(String point, Throwable t, boolean screen) {
    LOCK.lock();
    try {
      capture(point, t, screen, false);
      writtenPassed = false;
    } finally {
      LOCK.unlock();
    }
  }

  /** Time spent capturing, in all: a test's timeout does not count it (FailOnTimeout's watchdog reads pausedNanos). */
  private static volatile long captureNanos;
  private static volatile long captureSince;

  /** Nanoseconds spent capturing so far, the capture in progress included. */
  public static long pausedNanos() {
    long since = captureSince;
    return captureNanos + (since == 0 ? 0 : System.nanoTime() - since);
  }

  /**
   * Writes the current test's bundle; LOCK is held. [fresh]: a new bundle, in a folder emptied first (an earlier
   * run's files must not pass for this one's); otherwise the current bundle's failure, log and threads are rewritten.
   */
  private static void capture(String point, Throwable t, boolean screen, boolean fresh) {
    capture(point, t, screen, fresh, true);
  }

  /** [tree]: with [screen], read nodes.txt now; otherwise it waits ({@link #nodesPending}). */
  private static void capture(String point, Throwable t, boolean screen, boolean fresh, boolean tree) {
    Description d = current;
    if (d == null) return;
    long t0 = System.nanoTime();
    captureSince = t0;
    StringBuilder parts = new StringBuilder();
    try {
      Path dir = Path.of(ROOT).resolve(d.getClassName()).resolve(folder(d.getClassName(), d.getMethodName()));
      boolean whole;
      if (fresh) {
        if (Files.isDirectory(dir)) delete(dir, false);
        Files.createDirectories(dir);
        shareWithHost(dir);
        written = dir;
        writtenPassed = false;
        whole = full < MAX;
        writtenFull = whole;
        if (whole) full++;
      } else {
        whole = writtenFull;
      }
      writtenFor = t;
      writtenAlias = null;
      // failure.txt first, so that a part that hangs or kills the process still leaves it; again at the end with the parts.
      String head = failure(d, point, t);
      write(dir.resolve("failure.txt"), redact(head + "parts: (capturing)\n"));
      if (!whole) {
        parts.append("  the rest skipped: ").append(MAX).append(" full bundles already written by this process (--bundles-max)\n");
      } else {
        if (screen && !fresh && safeWindows().isEmpty()) {
          parts.append("  screen.png, windows.txt, nodes.txt, looper.txt: kept from the earlier capture (no windows now)\n");
        } else if (screen) {
          part(parts, "screen.png", () -> screen(dir.resolve("screen.png")));
          part(parts, "windows.txt", () -> write(dir.resolve("windows.txt"), windows()));
          if (tree) {
            nodesPending = false;
            part(parts, "nodes.txt", () -> write(dir.resolve("nodes.txt"), redact(nodes(null))));
          } else {
            nodesPending = true;
            String later = "read later: when the failure leaves the test body, as an activity finishes, or after the test's"
                + " teardown (reading the tree changes a Compose screen, and the test may handle this failure and go on)";
            part(parts, "nodes.txt", () -> write(dir.resolve("nodes.txt"), "not read yet: " + later + "\n"));
            parts.append("  (nodes.txt is a placeholder, ").append(later).append(")\n");
          }
          part(parts, "looper.txt", () -> write(dir.resolve("looper.txt"), looper()));
        }
        part(parts, "logcat.txt", () -> write(dir.resolve("logcat.txt"), redact(logcat())));
        part(parts, "threads.txt", () -> write(dir.resolve("threads.txt"), threads()));
        part(parts, "repro.sh", () -> repro(dir.resolve("repro.sh"), d));
      }
      parts.append("  total ").append((System.nanoTime() - t0) / 1_000_000).append(" ms\n");
      write(dir.resolve("failure.txt"), redact(head + "parts:\n" + parts));
    } catch (Throwable e) {
      System.err.println("[apksim] failure bundle for " + d + " not written: " + e);
    } finally {
      captureNanos += System.nanoTime() - t0;
      captureSince = 0;
    }
  }

  private interface Part {
    void run() throws Exception;
  }

  /** Deadline for one part: the screen 2 s (docs/plan.md phase 5), anything else 5 s. */
  private static long deadlineMs(String name) {
    return name.equals("screen.png") ? 2000 : 5000;
  }

  /**
   * Runs one part. Past its deadline the capturing thread is interrupted, which ends a part that waits (a lock, a
   * queue, a sleep) but not one spinning in code that never looks; the part is then skipped in later bundles of this
   * process, so one that hangs costs once. The interrupt is cleared afterwards, for the test's own teardown.
   */
  private static void part(StringBuilder parts, String name, Part p) {
    if (TIMED_OUT.contains(name)) {
      parts.append("  ").append(name).append(" skipped: it ran past its deadline in an earlier bundle\n");
      return;
    }
    long t0 = System.nanoTime();
    Thread self = Thread.currentThread();
    // done/fired change only under the gate, and the interrupt is sent inside it: once the part is marked done, no
    // late interrupt can reach the next part or the test's teardown.
    Object gate = new Object();
    boolean[] doneFired = new boolean[2];
    java.util.concurrent.ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
      synchronized (gate) {
        if (!doneFired[0]) {
          doneFired[1] = true;
          self.interrupt();
        }
      }
    }, deadlineMs(name), java.util.concurrent.TimeUnit.MILLISECONDS);
    try {
      p.run();
      parts.append("  ").append(name).append(' ').append((System.nanoTime() - t0) / 1_000_000).append(" ms\n");
    } catch (Throwable e) {
      parts.append("  ").append(name).append(" failed: ").append(e).append('\n');
    } finally {
      deadline.cancel(false);
      boolean fired;
      synchronized (gate) {
        doneFired[0] = true;
        fired = doneFired[1];
      }
      if (fired) {
        Thread.interrupted();
        TIMED_OUT.add(name);
        parts.append("  ").append(name).append(" ran past its ").append(deadlineMs(name)).append(" ms deadline (interrupted)\n");
      }
    }
  }

  /** Deletes a folder's files, and the folder itself when [self]. */
  private static void delete(Path dir, boolean self) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        if (self || !f.equals(dir)) Files.delete(f);
      }
    }
  }

  /** &lt;method&gt;-&lt;hash&gt;: the name cut down to safe characters, plus 8 hex digits of SHA-256("class#method") (instrument.py works it out the same way). */
  static String folder(String cls, String method) {
    String m = method == null ? "class" : method;
    String safe = m.replaceAll("[^A-Za-z0-9._-]", "_");
    if (safe.length() > 60) safe = safe.substring(0, 60);
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest((cls + "#" + (method == null ? "" : method)).getBytes(StandardCharsets.UTF_8));
      return safe + "-" + HexFormat.of().formatHex(h, 0, 4);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * In a container apksim runs as root, and the host's instrument.py adds process.txt to the folder of a test whose
   * process died: so as root the folders are made writable by everyone (mounted volumes keep owners and modes).
   */
  private static void shareWithHost(Path dir) {
    if (!"root".equals(System.getProperty("user.name"))) return;
    var all = java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");
    for (Path p = dir; p != null && !p.equals(Path.of(ROOT).toAbsolutePath()) && !p.equals(Path.of(ROOT)); p = p.getParent()) {
      try {
        Files.setPosixFilePermissions(p, all);
      } catch (IOException | UnsupportedOperationException e) {
        return;
      }
    }
  }

  private static void write(Path p, String s) throws IOException {
    Files.writeString(p, s, StandardCharsets.UTF_8);
  }

  private static String redact(String s) {
    for (Pattern p : REDACT) s = p.matcher(s).replaceAll("[redacted]");
    return s;
  }

  private static String failure(Description d, String point, Throwable t) {
    StringBuilder b = new StringBuilder();
    b.append("test: ").append(d.getClassName()).append('#').append(d.getMethodName() == null ? "(class)" : d.getMethodName()).append('\n');
    b.append("status: ").append(t == null ? "passed" : "failed").append('\n');
    b.append("captured by: ").append(point).append('\n');
    b.append("tests before this one in this process: ").append(Math.max(testsBefore, 0))
        .append(testsBefore > 0 ? " (if repro.sh passes, state carried over from them: app statics)" : "").append('\n');
    b.append("simulated uptime: ").append(safe(() -> uptimeMs() + " ms")).append('\n');
    b.append("wall time: ").append(Instant.now()).append('\n');
    b.append("time zone: ").append(TimeZone.getDefault().getID()).append('\n');
    b.append("device: ").append(safe(Bundles::device)).append('\n');
    b.append("runtime: ").append(safe(ClassCache::runtimeHash)).append('\n');
    Map<String, String> args = new TreeMap<>();
    String prefix = "apksim.instrument.";
    for (String k : System.getProperties().stringPropertyNames()) {
      if (k.startsWith(prefix)) args.put(k.substring(prefix.length()), System.getProperty(k));
    }
    b.append("arguments:");
    args.forEach((k, v) -> b.append(" -e ").append(k).append(' ').append(v));
    b.append('\n');
    if (t != null) {
      StringWriter w = new StringWriter();
      t.printStackTrace(new PrintWriter(w));
      b.append("\n").append(w).append('\n');
    }
    return b.toString();
  }

  /**
   * The simulated uptime, read without moving it: with --realtime-clock, SystemClock.uptimeMillis on the main thread
   * catches the clock up to real time, so a capture would change the state it reports.
   */
  private static long uptimeMs() {
    return org.robolectric.shadows.ApksimClock.uptimeNanos() / 1_000_000L;
  }

  private interface Text {
    String get() throws Exception;
  }

  private static String safe(Text t) {
    try {
      return t.get();
    } catch (Throwable e) {
      return "(" + e + ")";
    }
  }

  private static String device() {
    Configuration c = RuntimeEnvironment.getApplication().getResources().getConfiguration();
    Point p = displaySize();
    return p.x + "x" + p.y + " px, density " + c.densityDpi + " dpi, "
        + ((c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light")
        + ", font scale " + c.fontScale + ", locale " + c.getLocales().toLanguageTags() + ", qualifiers " + RuntimeEnvironment.getQualifiers();
  }

  private static Point displaySize() {
    Point p = new Point();
    WindowManager wm = (WindowManager) RuntimeEnvironment.getApplication().getSystemService(android.content.Context.WINDOW_SERVICE);
    if (wm != null && wm.getDefaultDisplay() != null) {
      wm.getDefaultDisplay().getRealSize(p);
    } else {
      android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
      p.set(m.widthPixels, m.heightPixels);
    }
    return p;
  }

  // --- windows and screen ----------------------------------------------------------------------------------------

  private record Win(View view, WindowManager.LayoutParams params) {}

  /** This process's visible windows, bottom to top (higher window types above lower; within a type, later above earlier). */
  private static List<Win> visibleWindows() {
    Object global = android.view.WindowManagerGlobal.getInstance();
    List<View> views = new ArrayList<>(ReflectionHelpers.<List<View>>getField(global, "mViews"));
    List<WindowManager.LayoutParams> params = new ArrayList<>(ReflectionHelpers.<List<WindowManager.LayoutParams>>getField(global, "mParams"));
    List<Win> out = new ArrayList<>();
    for (int i = 0; i < views.size() && i < params.size(); i++) {
      View v = views.get(i);
      if (v.getVisibility() == View.VISIBLE && v.isAttachedToWindow() && v.getWidth() > 0 && v.getHeight() > 0) out.add(new Win(v, params.get(i)));
    }
    out.sort(java.util.Comparator.comparingInt(w -> w.params.type));
    return out;
  }

  private static List<Win> safeWindows() {
    try {
      return visibleWindows();
    } catch (Throwable e) {
      return List.of();
    }
  }

  private static String windows() {
    StringBuilder b = new StringBuilder();
    List<Win> wins = visibleWindows();
    if (wins.isEmpty()) b.append("no visible windows\n");
    int[] at = new int[2];
    for (Win w : wins) {
      w.view.getLocationOnScreen(at);
      Activity a = TestActivities.activityOf(w.view);
      b.append("type ").append(w.params.type).append(" \"").append(w.params.getTitle()).append("\" ")
          .append(a == null ? "(no activity)" : a.getClass().getName() + (a.isFinishing() ? " finishing" : ""))
          .append(" [").append(at[0]).append(',').append(at[1]).append(',').append(at[0] + w.view.getWidth()).append(',')
          .append(at[1] + w.view.getHeight()).append("]").append(w.view.hasWindowFocus() ? " focused" : "").append('\n');
    }
    return b.toString();
  }

  /**
   * Every visible window drawn into one display-sized image, as UiAutomation.takeScreenshot does (Robolectric's): each
   * root through HWUI where Robolectric can (its HardwareRenderingScreenshot), else drawn in software.
   */
  private static void screen(Path file) throws Exception {
    List<Win> wins = visibleWindows();
    if (wins.isEmpty()) throw new IllegalStateException("no visible windows");
    Point size = displaySize();
    // Recycled in finally: pixels are native memory, which HotSpot's GC does not count (AGENTS.md), and a capture that
    // throws must not leave a screen's worth behind each time.
    Bitmap out = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(out);
      canvas.drawColor(0xff000000);
      int[] at = new int[2];
      for (Win w : wins) {
        Bitmap one = Bitmap.createBitmap(w.view.getWidth(), w.view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
          drawRoot(w.view, one);
          w.view.getLocationOnScreen(at);
          canvas.drawBitmap(one, at[0], at[1], null);
        } finally {
          one.recycle();
        }
      }
      try (FileOutputStream f = new FileOutputStream(file.toFile())) {
        out.compress(Bitmap.CompressFormat.PNG, 100, f);
      }
    } finally {
      out.recycle();
    }
  }

  private static Method canHw, hw;

  private static void drawRoot(View root, Bitmap into) throws Exception {
    if (canHw == null) {
      Class<?> c = Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot");
      canHw = c.getDeclaredMethod("canTakeScreenshot", View.class);
      hw = c.getDeclaredMethod("takeScreenshot", View.class, Bitmap.class);
      canHw.setAccessible(true);
      hw.setAccessible(true);
    }
    if ((boolean) canHw.invoke(null, root)) {
      hw.invoke(null, root, into);
    } else {
      root.draw(new Canvas(into));
    }
  }

  /** The tree of every visible window, or of [only]'s (an activity's) when given. */
  private static String nodes(Activity only) {
    List<View> roots = new ArrayList<>();
    for (Win w : visibleWindows()) if (only == null || TestActivities.activityOf(w.view) == only) roots.add(w.view);
    String s = Nodes.dump(roots).replace("; ", "\n");
    return s.isEmpty() ? "no nodes with text, a description or an action\n" : s;
  }

  /**
   * Reads the current bundle's pending nodes.txt ({@link #nodesPending}) [when], from every visible window or only
   * [finishing]'s. On the main thread only (the views are read); LOCK is held or taken.
   */
  private static void pendingNodes(String when, Activity finishing) {
    if (!nodesPending || Looper.myLooper() != Looper.getMainLooper()) return;
    LOCK.lock();
    try {
      Path dir = written;
      if (!nodesPending || dir == null) return;
      if (finishing != null) {
        boolean shown = false;
        for (Win w : safeWindows()) shown |= TestActivities.activityOf(w.view) == finishing;
        if (!shown) return;
      } else if (safeWindows().isEmpty()) {
        nodesPending = false;
        write(dir.resolve("nodes.txt"), "not read: no windows were left " + when + "\n");
        return;
      }
      nodesPending = false;
      long t0 = System.nanoTime();
      captureSince = t0;
      try {
        String what = finishing == null ? "every window" : finishing.getClass().getName() + "'s windows";
        write(dir.resolve("nodes.txt"), redact("read " + when + ", after the capture (Espresso's failure handler), from "
            + what + "\n" + nodes(finishing)));
      } finally {
        captureNanos += System.nanoTime() - t0;
        captureSince = 0;
      }
    } catch (Throwable e) {
      System.err.println("[apksim] failure bundle: nodes.txt not written: " + e);
    } finally {
      LOCK.unlock();
    }
  }

  /**
   * An activity is finishing (Activity.finish: ActivityScenario's close, the app's own): a bundle's pending nodes.txt
   * reads its windows now, while they are still there. A screen on its way out cannot change what the test does next.
   */
  static void finishing(Activity a) {
    if (ON && nodesPending) pendingNodes("as " + a.getClass().getSimpleName() + " finished", a);
  }

  // --- the main thread and the log -------------------------------------------------------------------------------

  private static String looper() {
    StringBuilder b = new StringBuilder("simulated uptime ").append(uptimeMs()).append(" ms\n\nmain looper:\n");
    Looper.getMainLooper().dump(new StringBuilderPrinter(b), "  ");
    b.append("\nactivities by stage (androidx.test's lifecycle monitor):\n");
    b.append(safe(Bundles::stages));
    return b.toString();
  }

  /** Activities by lifecycle stage, from the test APK's androidx.test (when it has one). */
  private static String stages() throws Exception {
    Class<?> registry = Class.forName("androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry");
    Class<?> stage = Class.forName("androidx.test.runner.lifecycle.Stage");
    Object monitor = registry.getMethod("getInstance").invoke(null);
    Method in = monitor.getClass().getMethod("getActivitiesInStage", stage);
    in.setAccessible(true);
    StringBuilder b = new StringBuilder();
    for (Object s : stage.getEnumConstants()) {
      for (Object a : (java.util.Collection<?>) in.invoke(monitor, s)) b.append("  ").append(s).append(' ').append(a).append('\n');
    }
    return b.length() == 0 ? "  none\n" : b.toString();
  }

  /**
   * The test's log lines: all of them up to LOG_LINES, else the first and last half of that with a line saying how
   * many went between. The first ones are kept as they arrive (AdbLog.keepHead), since the ring holds only the last
   * 20,000 lines of the process.
   */
  private static String logcat() throws InterruptedException {
    AdbLog.Batch batch = AdbLog.since(logFrom, 0);
    long end = batch.next();
    long total = end - logFrom;
    if (total == 0) return "no log lines during this test\n";
    List<AdbLog.Line> lines = batch.lines();
    long firstSeq = end - lines.size();
    StringBuilder b = new StringBuilder();
    if (total <= LOG_LINES && firstSeq <= logFrom) {
      for (AdbLog.Line l : lines) b.append(l.text()).append('\n');
      return b.toString();
    }
    List<AdbLog.Line> head = AdbLog.head();
    for (AdbLog.Line l : head) b.append(l.text()).append('\n');
    long tailFrom = Math.max(logFrom + head.size(), Math.max(firstSeq, end - LOG_LINES / 2));
    if (tailFrom > logFrom + head.size()) {
      b.append("... ").append(tailFrom - logFrom - head.size()).append(" lines cut ...\n");
    }
    for (int i = (int) (tailFrom - firstSeq); i < lines.size(); i++) b.append(lines.get(i).text()).append('\n');
    return b.toString();
  }

  private static String threads() {
    Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
    List<Thread> order = new ArrayList<>(all.keySet());
    // The Android main thread, when there is one to name: a run whose classes all have their own plain JUnit runner,
    // or one that failed before the sandbox existed, may have no main Looper; the dump is written all the same.
    Thread main = null;
    try {
      Looper l = Looper.getMainLooper();
      if (l != null) main = l.getThread();
    } catch (Throwable ignored) {
      // no main thread to put first
    }
    Thread mainThread = main;
    order.sort(java.util.Comparator.<Thread>comparingInt(t -> t == mainThread ? 0 : 1).thenComparing(Thread::getName));
    StringBuilder b = new StringBuilder();
    for (Thread t : order) {
      b.append('"').append(t.getName()).append("\" ").append(t.getState()).append(t.isDaemon() ? " daemon" : "")
          .append(t == mainThread ? " (main)" : "").append('\n');
      StackTraceElement[] st = all.get(t);
      for (int i = 0; i < st.length && i < 200; i++) b.append("\tat ").append(st[i]).append('\n');
      b.append('\n');
    }
    return b.toString();
  }

  // --- repro.sh --------------------------------------------------------------------------------------------------

  /**
   * The launch that ran this test, narrowed to it: the launcher's own arguments (apksim.argv, \u001f between them)
   * without --bundles options and the -e arguments that choose tests, plus -e class for this one.
   */
  private static void repro(Path file, Description d) throws IOException {
    String argv = System.getProperty("apksim.argv");
    List<String> args = new ArrayList<>();
    if (argv != null) {
      String[] a = argv.split("\u001f", -1);
      for (int i = 0; i < a.length; i++) {
        String s = a[i];
        if (s.startsWith("--bundles")) {
          i++;
        } else if (s.equals("-e") && i + 2 < a.length
            && List.of("class", "notClass", "package", "notPackage", "numShards", "shardIndex", "log").contains(a[i + 1])) {
          i += 2;
        } else {
          args.add(s);
        }
      }
    } else {
      args.add("--instrument");
      args.add(System.getProperty("apksim.instrument"));
      args.add("(app.apk)");
    }
    String sel = d.getClassName() + (d.getMethodName() == null ? "" : "#" + d.getMethodName().replace("\\", "\\\\").replace(",", "\\,"));
    args.addAll(Math.max(args.size() - 1, 0), List.of("-e", "class", sel));
    // (A Parameterized name can hold a line break: in the comment it must not end the line.)
    String shown = sel.replace("\r", "\\r").replace("\n", "\\n");
    StringBuilder b = new StringBuilder("#!/bin/sh\n# Runs ").append(shown).append(" alone, as the run that wrote this bundle ran it.\n");
    if (argv == null) b.append("# The launch's own arguments were not recorded: fill in the app APK and options.\n");
    String cwd = System.getProperty("apksim.cwd", System.getProperty("user.dir"));
    b.append("cd ").append(quote(cwd)).append(" || exit 1\nexec ").append(quote(System.getProperty("apksim.launcherScript", "./apksim")));
    for (String s : args) b.append(' ').append(quote(s));
    b.append('\n');
    write(file, b.toString());
    file.toFile().setExecutable(true);
  }

  /** A shell word: left bare only when it holds nothing the shell reads (no `#`: at a word's start it begins a comment). */
  private static String quote(String s) {
    return s.matches("[A-Za-z0-9_./=:@%+,-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
  }
}
