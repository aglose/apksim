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
 * Exceptions on apksim's own threads (named {@value #OWN_THREADS}..., every thread apksim starts) are printed as the JVM
 * prints them and end only that thread, as before: they are apksim's bugs, not the app's crash. Every other thread is
 * the app's: not decided by the exception's stack, which is where it was made (a JDK exception passed on, or one made
 * without a stack trace) and may have no frame of the app's.
 */
final class Crashes {
  private Crashes() {}

  /** The name every thread apksim starts has; any other thread is the app's (its own, or the framework's for it). */
  static final String OWN_THREADS = "apksim-";
  /** The exception last logged as FATAL, so the pre-handler and the default handler log it once. */
  private static volatile Throwable logged;
  private static final AtomicBoolean crashing = new AtomicBoolean();
  /** Read once at install (after a standby's hand-off): nothing to allocate when it is needed. */
  private static boolean keepRunning;
  private static boolean failCleanupForTest;
  /** "\nProcess: PKG, PID: N", made at install, so a crash after an OutOfMemoryError has less to allocate. */
  private static String processLine = "";
  /** What stderr gets when logging the crash itself failed (an OutOfMemoryError): written as is, nothing allocated. */
  private static final byte[] LOG_FAILED =
      "E/AndroidRuntime: FATAL EXCEPTION (logging it failed: out of memory?)\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private static volatile boolean installed;

  /** Whether this is a running instance with the device's handler (not a test run, not a standby's warm-up). */
  static boolean installed() {
    return installed;
  }

  static void install() {
    installed = true;
    keepRunning = "log".equals(System.getProperty("apksim.crashes"));
    failCleanupForTest = Boolean.getBoolean("apksim.crashTest.failCleanup");
    processLine = "\nProcess: " + System.getProperty("apksim.package") + ", PID: " + ProcessHandle.current().pid();
    Thread.setDefaultUncaughtExceptionHandler(new KillApplication());
  }

  /** The device's default handler: logs (unless already logged), then ends the process. */
  static final class KillApplication implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread t, Throwable e) {
      boolean own = false;
      try {
        own = t.getName().startsWith(OWN_THREADS);
      } catch (Throwable unknown) {
        // Cannot tell: the app's, as on a device.
      }
      if (own) {
        try {
          System.err.print("[apksim] exception in apksim's thread \"" + t.getName() + "\" ");
          e.printStackTrace();
        } catch (Throwable ignored) {
          // Only that thread ends, as before.
        }
        return;
      }
      log(t, e);
      if (keepRunning) {
        say("[apksim] -Dapksim.crashes=log: the app keeps running (on a device the process ends here)");
        return;
      }
      if (!crashing.compareAndSet(false, true)) return;  // another thread's crash is already ending the process
      say("[apksim] the app crashed (FATAL EXCEPTION): exit 10");
      terminate();
    }
  }

  /**
   * An exception left the main looper (Host's loop, the activity's start, Application.onCreate): on a device the main
   * thread dies with it. Logged as the pre-handler logs it, then handed to the thread's handler (the app's, or
   * {@link KillApplication}). Returns only with -Dapksim.crashes=log; when an app's handler returns, the main thread is
   * gone on a device (nothing would run again), so the process ends.
   */
  static void mainThreadDied(Throwable e) {
    if (e instanceof AppCrash && e.getCause() != null) e = e.getCause();
    Thread t = Thread.currentThread();
    log(t, e);
    try {
      t.getUncaughtExceptionHandler().uncaughtException(t, e);
    } catch (Throwable ignored) {
      // As the JVM ignores what a handler throws.
    }
    if (keepRunning) return;
    if (!crashing.compareAndSet(false, true)) {
      // Another thread's crash is ending the process: the main thread does nothing more meanwhile.
      while (true) java.util.concurrent.locks.LockSupport.park();
    }
    say("[apksim] the main thread died (FATAL EXCEPTION on main) and the app's handler returned: exit 10");
    terminate();
  }

  /**
   * Ends the process with exit status 10 whatever fails on the way (an OutOfMemoryError, a class cache that cannot be
   * saved): each step is best-effort, and the exit status reaches a warm launch's client (Standby.haltNow).
   * {@code -Dapksim.crashTest.failCleanup=true} makes the cache save throw, to check that.
   */
  private static void terminate() {
    try {
      if (failCleanupForTest) throw new IllegalStateException("apksim.crashTest.failCleanup");
      ClassCache.flush();
    } catch (Throwable ignored) {
      // Translated again next launch.
    }
    try {
      Storage.deleteScratch();
    } catch (Throwable ignored) {
      // Swept at the next launch.
    }
    Standby.haltNow(10);
  }

  private static void say(String line) {
    try {
      System.err.println(line);
    } catch (Throwable ignored) {
      // Ending anyway.
    }
  }

  /** Whether this exception was already logged as FATAL here. */
  static boolean wasLogged(Throwable e) {
    int depth = 0;
    for (Throwable x = e; x != null && depth++ < 20; x = x.getCause()) {
      if (x == logged) return true;
    }
    return false;
  }

  /**
   * What app code threw during a lifecycle transition a control command ran (restart, recreate, process-death, a
   * configuration change, home/resume, memory): Host's command loop lets it through to its crash path (mainThreadDied)
   * rather than answering `error: ...`, which left a half-made app running. Errors in a command's own arguments are not
   * wrapped and stay answers.
   */
  static final class AppCrash extends RuntimeException {
    AppCrash(Throwable cause) {
      super(String.valueOf(cause), cause, false, false);
    }
  }

  interface AppCode<T> {
    T run() throws Exception;
  }

  /** Runs app code (activity lifecycle, callbacks) for a command: in a running instance what it throws is a crash. */
  static <T> T app(AppCode<T> code) throws Exception {
    try {
      return code.run();
    } catch (AppCrash c) {
      throw c;
    } catch (Throwable t) {
      if (installed) throw new AppCrash(t);
      throw t;
    }
  }

  /** {@link #app} for code that throws nothing checked. */
  static void app(Runnable code) {
    try {
      code.run();
    } catch (AppCrash c) {
      throw c;
    } catch (Throwable t) {
      if (installed) throw new AppCrash(t);
      throw t;
    }
  }

  /** Logs the crash once; never throws (a minimal line on stderr if logging it failed). */
  private static void log(Thread t, Throwable e) {
    try {
      ensureLogged(t, e);
    } catch (Throwable failed) {
      try {
        System.err.write(LOG_FAILED, 0, LOG_FAILED.length);
        System.err.flush();
      } catch (Throwable ignored) {
        // Nothing left to tell it with.
      }
    }
  }

  private static void ensureLogged(Thread t, Throwable e) {
    if (logged == e) return;
    logged = e;
    String message = "FATAL EXCEPTION: " + threadName(t) + processLine;
    boolean inLog = false;
    try {
      // The adb log's tee in front again at once: the app or `logcat on|off` may have just replaced ShadowLog.stream, and
      // its watcher would put it back only within 250 ms, after the process is gone.
      AdbLog.reattach();
      Log.e("AndroidRuntime", message, e);
      inLog = true;
    } catch (Throwable ignored) {
      // The framework is not up (or went down): stderr below has it.
    }
    // Without `logcat on` the app's log goes only to `adb logcat`: the host log gets it too, as a device's logcat would;
    // and it gets it whenever Log.e failed.
    if (!inLog || !AdbLog.reachesStderr()) {
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
}
