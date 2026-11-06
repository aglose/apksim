package dev.apksim;

import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uncaught exceptions in a running instance, as a device's RuntimeInit handles them (issue 104). A device's app process
 * starts with KillApplicationHandler as the default uncaught-exception handler: it logs `E AndroidRuntime: FATAL
 * EXCEPTION: <thread>`, `Process: <package>, PID: <pid>` and the stack, then kills the process. Here the JVM had no
 * default handler, so ThreadGroup printed "Exception in thread ..." to stderr and the app kept running: kotlinx.coroutines
 * hands a coroutine's uncaught exception to the thread's handler itself (propagateExceptionFinalResort, after the
 * ServiceLoader'd CoroutineExceptionHandlers, whose AndroidExceptionPreHandler does nothing past API 27), so the main
 * thread lived on with a half-made screen; and an app's crash reporter that passes the exception on to the handler it
 * found (none) left nothing at all.
 *
 * {@link #install} sets {@link KillApplication} as the default before any app code runs (not in test runs, which keep
 * Robolectric's behaviour). An app's own default handler replaces it as on a device, and one that passes the exception
 * on reaches it. An exception that leaves the main looper (a posted Runnable, a lifecycle callback, a draw) ends the
 * main thread on a device; Host catches it and calls {@link #mainThreadDied}, which logs as the device's pre-handler
 * does, then calls the thread's handler. The process ends with exit status 10 (RuntimeInit's System.exit(10)).
 * {@code -Dapksim.crashes=log} logs and keeps running (the main loop goes on), to look at a broken screen.
 *
 * Exceptions on apksim's own threads that never ran app code (no frame of a class the APK defines) are printed as the
 * JVM prints them and end only that thread, as before: they are apksim's bugs, not the app's crash.
 */
final class Crashes {
  private Crashes() {}

  private static AppClasses classes;
  /** The exception last logged as FATAL, so the pre-handler and the default handler log it once. */
  private static volatile Throwable logged;
  private static final AtomicBoolean crashing = new AtomicBoolean();

  static void install(AppClasses appClasses) {
    classes = appClasses;
    Thread.setDefaultUncaughtExceptionHandler(new KillApplication());
  }

  static boolean keepRunning() {
    return "log".equals(System.getProperty("apksim.crashes"));
  }

  /** The device's default handler: logs (unless already logged), then ends the process. */
  static final class KillApplication implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread t, Throwable e) {
      if (!isMain(t) && !ranAppCode(e)) {
        System.err.print("[apksim] exception in apksim's thread \"" + t.getName() + "\" ");
        e.printStackTrace();
        return;
      }
      ensureLogged(t, e);
      if (keepRunning()) {
        System.err.println("[apksim] -Dapksim.crashes=log: the app keeps running (on a device the process ends here)");
        return;
      }
      if (!crashing.compareAndSet(false, true)) return;  // another thread's crash is already ending the process
      System.err.println("[apksim] the app crashed (FATAL EXCEPTION on " + threadName(t) + "): exit 10");
      Host.exit(10);
    }
  }

  /**
   * An exception left the main looper (Host's loop, the activity's start, Application.onCreate): on a device the main
   * thread dies with it. Logged as the pre-handler logs it, then handed to the thread's handler (the app's, or
   * {@link KillApplication}). Returns only with -Dapksim.crashes=log; when an app's handler returns, the main thread is
   * gone on a device (nothing would run again), so the process ends.
   */
  static void mainThreadDied(Throwable e) {
    Thread t = Thread.currentThread();
    ensureLogged(t, e);
    try {
      t.getUncaughtExceptionHandler().uncaughtException(t, e);
    } catch (Throwable ignored) {
      // As the JVM ignores what a handler throws.
    }
    if (keepRunning()) return;
    System.err.println("[apksim] the main thread died (FATAL EXCEPTION on main) and the app's handler returned: exit 10");
    Host.exit(10);
  }

  /** Whether this exception was already logged as FATAL here. */
  static boolean wasLogged(Throwable e) {
    return logged == e;
  }

  private static void ensureLogged(Thread t, Throwable e) {
    if (logged == e) return;
    logged = e;
    String message = "FATAL EXCEPTION: " + threadName(t) + "\nProcess: " + System.getProperty("apksim.package")
        + ", PID: " + ProcessHandle.current().pid();
    try {
      Log.e("AndroidRuntime", message, e);
    } catch (Throwable ignored) {
      // The framework is not up (or went down): stderr below still has it.
    }
    // Without `logcat on` the app's log goes only to `adb logcat`: the host log gets it too, as a device's logcat would.
    if (!AdbLog.reachesStderr()) {
      System.err.println("E/AndroidRuntime: " + message);
      e.printStackTrace();
    }
  }

  /** As a device names it: the main looper's thread is "main" (Robolectric's is "SDK 36 Main Thread"). */
  private static String threadName(Thread t) {
    return isMain(t) ? "main" : t.getName();
  }

  private static boolean isMain(Thread t) {
    try {
      Looper main = Looper.getMainLooper();
      return main != null && main.getThread() == t;
    } catch (Throwable e) {
      return false;
    }
  }

  /** Whether the exception (or a cause) passed through a class the APK defines (the app, its libraries). */
  private static boolean ranAppCode(Throwable e) {
    AppClasses c = classes;
    if (c == null) return true;
    int depth = 0;
    for (Throwable x = e; x != null && depth++ < 20; x = x.getCause()) {
      for (StackTraceElement f : x.getStackTrace()) {
        if (c.defines(f.getClassName())) return true;
      }
    }
    return false;
  }
}
