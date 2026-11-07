package dev.apksim;

import android.app.Activity;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowProcess;

/**
 * Why the process ends, said on stderr (issue 116: an app's process ended with status 0 and nothing in the log, a few
 * seconds after a sign-in). Every way apksim ends a running instance goes through {@link #log}, one line
 * {@code [apksim] exit N: REASON}, and the ways the app itself can end it say who asked:
 * <ul>
 *   <li>The last activity finished (finish, finishAffinity, finishAndRemoveTask, back on the task's root): the line
 *       names the activity and the call, with the stack of the code that made it ({@link #finishing}). On a device the
 *       process would linger and the screen go home; apksim ends, but only once nothing has started another activity
 *       for {@link #LINGER_MS} (Host's loop runs the app meanwhile), so a hand-off that finishes first and starts the
 *       next activity from a posted message or a coroutine still lands.</li>
 *   <li>The app's own {@code System.exit}, {@code Runtime.exit} and {@code Runtime.halt}: the translator sends those
 *       calls here, which logs the code and the caller and ends the process with that code. A warm launch's client
 *       gets it too (the standby's shutdown hook reported 0 for any exit, and nothing at all for a halt).</li>
 *   <li>{@code Process.killProcess(Process.myPid())} (and {@code Process.sendSignal(myPid, SIGNAL_KILL)}): on a device
 *       the process dies at once, as restart-after-login libraries rely on; Robolectric only recorded the pid and the
 *       app ran on. Running instances end with status 137 (a SIGKILL); test runs keep Robolectric's record, which
 *       suites check.</li>
 * </ul>
 * Crashes have their own lines (Crashes); this class adds the exit line after them.
 */
public final class Exits {
  private Exits() {}

  /** How long Host keeps the app running after its last activity finished, waiting for a start. */
  static final long LINGER_MS = 1000;

  /** The status a halt by SIGKILL gives the launcher's shell. */
  static final int KILLED = 137;

  /** The last finish call per activity: its name and the stack that made it. Main thread only, as finish is. */
  private static final java.util.Map<Activity, Throwable> FINISHED_BY = new java.util.WeakHashMap<>();

  /** Set by the first exit line: the client hanging up once it has the code is then not another reason. */
  static volatile boolean ending;

  /** One line on stderr: the status and why. Always printed: it is the only record of why an instance went away. */
  static void log(int code, String reason) {
    ending = true;
    try {
      System.err.println("[apksim] exit " + code + ": " + reason);
      System.err.flush();
    } catch (Throwable ignored) {
      // Ending anyway.
    }
  }

  /** Called by Activity's finish shadows: who asked, for the exit line if this turns out to be the last activity. */
  static void finishing(Activity a, String call) {
    if (!FINISHED_BY.containsKey(a)) FINISHED_BY.put(a, new Throwable("Activity." + call + "()"));
  }

  /** The exit line's reason once the last activity, {@code a}, finished. */
  static String lastActivityFinished(Activity a) {
    if (a == null) return "no activity left";
    Throwable by = FINISHED_BY.get(a);
    StringBuilder s = new StringBuilder("the last activity, ").append(a.getClass().getName()).append(", finished");
    if (by == null) return s.toString();
    s.append(" (").append(by.getMessage()).append(caller(by.getStackTrace(), 2)).append(')');
    s.append(stack(by.getStackTrace(), 2));
    return s.toString();
  }

  // ---------------------------------------------------------------- the app's own exits (the translator's targets)

  /** The app's {@code System.exit(code)}. */
  public static void systemExit(int code) {
    appExit("System.exit", code);
    Runtime.getRuntime().exit(code);
  }

  /** The app's {@code runtime.exit(code)}. */
  public static void runtimeExit(Runtime runtime, int code) {
    java.util.Objects.requireNonNull(runtime);  // as the call would throw, before anything is logged
    appExit("Runtime.exit", code);
    runtime.exit(code);
  }

  /** The app's {@code runtime.halt(code)}: no shutdown hooks, so the warm client is told here. */
  public static void runtimeHalt(Runtime runtime, int code) {
    java.util.Objects.requireNonNull(runtime);
    appExit("Runtime.halt", code);
    Standby.haltNow(code);
  }

  /** Logs and saves best-effort: an app exiting from an OutOfMemoryError handler still exits. */
  private static void appExit(String call, int code) {
    Standby.exitCode = code;
    try {
      StackTraceElement[] st = new Throwable().getStackTrace();
      log(code, "the app called " + call + "(" + code + ") on thread " + Thread.currentThread().getName() + caller(st, 2)
          + stack(st, 2));
    } catch (Throwable ignored) {
      // Exiting anyway.
    }
    try {
      SlotDevice.flush();
      ClassCache.flush();
    } catch (Throwable ignored) {
      // Best-effort, as on the way out of Host.
    }
  }

  // ---------------------------------------------------------------- Process.killProcess(myPid)

  /** Running instances only (SimRunner adds it outside test runs): a kill of the app's own process ends it. */
  @Implements(android.os.Process.class)
  public static class ShadowKillableProcess extends ShadowProcess {
    @Implementation
    protected static void killProcess(int pid) {
      if (pid == android.os.Process.myPid()) killed("Process.killProcess(myPid())");
      ShadowProcess.killProcess(pid);
    }

    @Implementation
    protected static void sendSignal(int pid, int signal) {
      if (pid == android.os.Process.myPid() && signal == android.os.Process.SIGNAL_KILL) {
        killed("Process.sendSignal(myPid(), SIGNAL_KILL)");
      }
    }
  }

  private static void killed(String call) {
    try {
      StackTraceElement[] st = new Throwable().getStackTrace();
      log(KILLED, "the app called " + call + " on thread " + Thread.currentThread().getName() + caller(st, 2) + stack(st, 2));
    } catch (Throwable ignored) {
      // Killed anyway.
    }
    try {
      SlotDevice.flush();  // what a device has already written to disk (its settings provider) survives
    } catch (Throwable ignored) {
      // Killed anyway.
    }
    Standby.haltNow(KILLED);
  }

  // ---------------------------------------------------------------- stacks

  /** " from CLASS.METHOD(FILE:LINE)": the first frame past {@code skip} that is not the framework's or apksim's. */
  private static String caller(StackTraceElement[] st, int skip) {
    for (int i = skip; i < st.length; i++) {
      if (!system(st[i].getClassName())) return " from " + st[i];
    }
    return "";
  }

  /** The stack below the call, a frame a line (up to 25), as the exit line's continuation. */
  private static String stack(StackTraceElement[] st, int skip) {
    StringBuilder s = new StringBuilder();
    int shown = 0;
    for (int i = skip; i < st.length && shown < 25; i++, shown++) s.append("\n    at ").append(st[i]);
    if (st.length - skip > shown) s.append("\n    ...");
    return s.toString();
  }

  private static boolean system(String cls) {
    String pkg = System.getProperty("apksim.package");
    if (pkg != null && cls.startsWith(pkg + ".")) return false;  // the app's own (the test app's is under dev.apksim)
    return cls.startsWith("android.") || cls.startsWith("com.android.") || cls.startsWith("java.")
        || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("org.robolectric.") || cls.startsWith("dev.apksim.")
        || cls.startsWith("androidx.activity.") || cls.startsWith("androidx.core.app.") || cls.startsWith("kotlin.")
        || cls.startsWith("kotlinx.coroutines.");
  }
}
