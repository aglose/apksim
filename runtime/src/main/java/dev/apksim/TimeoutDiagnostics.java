package dev.apksim;

import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * What a test that hit its JUnit timeout was waiting on, for the failure's report (issue 604). The stack of the
 * timed-out thread, often the test thread blocked in Espresso's result queue, does not say whether the window never
 * became ready for Espresso (RootViewPicker gives up after 10 s per attempt), an idling resource stayed busy or the
 * main looper was busy. This reads, without running anything on the main thread: the main looper thread's stack when
 * it is not the timed-out thread, each window root's focus and layout-requested flags, and the names of the registered idling
 * resources (not asked: their code must not run here). Every part is best effort (reflection, plain field reads from the watchdog's thread) and
 * a part that fails is left out; it never throws.
 */
public final class TimeoutDiagnostics {
  private TimeoutDiagnostics() {}

  /** {@link #capture} on a daemon thread, waited for at most {@code maxMs}: a lock or a call that blocks must not stall the caller. */
  public static Throwable captureBounded(Thread timedOut, long maxMs) {
    Throwable[] out = new Throwable[1];
    Thread t = new Thread(() -> out[0] = capture(timedOut), "apksim-timeout-diagnostics");
    t.setDaemon(true);
    t.start();
    try {
      t.join(maxMs);
    } catch (InterruptedException e) {
      // report what there is
    }
    return out[0];
  }

  /** A throwable to add as suppressed to the timeout failure: its message and stack carry the report; null if nothing was read. */
  public static Throwable capture(Thread timedOut) {
    StringBuilder b = new StringBuilder("state when the test timed out:");
    StackTraceElement[] mainStack = null;
    try {
      Looper main = Looper.getMainLooper();
      Thread mt = main == null ? null : main.getThread();
      if (mt != null) {
        if (mt == timedOut) b.append("\n  main looper thread: the timed-out thread itself");
        else {
          b.append("\n  main looper thread ").append(mt.getName()).append(" (").append(mt.getState()).append("), its stack follows");
          mainStack = mt.getStackTrace();
        }
      }
    } catch (Throwable t) {
      // not available: leave it out
    }
    for (String s : roots()) b.append("\n  ").append(s);
    List<String> names = registeredIdlingResources();
    if (names != null) b.append("\n  idling resources registered (not asked if idle): ").append(names.isEmpty() ? "none" : String.join(", ", names));
    Throwable t = new Throwable(b.toString());
    t.setStackTrace(mainStack != null ? mainStack : new StackTraceElement[0]);
    return t;
  }

  private static List<String> roots() {
    List<String> out = new ArrayList<>();
    try {
      Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
      Object g = wmg.getMethod("getInstance").invoke(null);
      Object lock = field(wmg, g, "mLock");
      Object list;
      synchronized (lock == null ? out : lock) {
        Object roots = field(wmg, g, "mRoots");
        list = roots == null ? null : new ArrayList<Object>((java.util.Collection<?>) roots);
      }
      if (list == null) return out;
      int i = 0;
      for (Object r : (List<?>) list) {
        try {
          Class<?> c = r.getClass();
          Object lr = field(c, r, "mLayoutRequested");
          Object view = field(c, r, "mView");
          String focus = view == null ? "?" : String.valueOf(call(view.getClass(), view, "hasWindowFocus"));  // as Espresso's root matcher asks
          out.add("window root " + i++ + " " + (view == null ? "?" : view.getClass().getName())
              + ": has-window-focus=" + focus + " is-layout-requested=" + lr);
        } catch (Throwable t) {
          // one root unreadable
        }
      }
    } catch (Throwable t) {
      // no window manager: nothing to report
    }
    return out;
  }

  /**
   * Names of the idling resources registered with Espresso, or null when Espresso is not there. They are not asked
   * whether they are idle: isIdleNow runs the resource's own code (Compose's moves the rule's clock), which must not
   * happen off the test thread while it is stuck.
   */
  private static List<String> registeredIdlingResources() {
    try {
      Class<?> reg = Class.forName("androidx.test.espresso.IdlingRegistry");
      Class<?> iface = Class.forName("androidx.test.espresso.IdlingResource");
      Object inst = reg.getMethod("getInstance").invoke(null);
      Iterable<?> resources = (Iterable<?>) reg.getMethod("getResources").invoke(inst);
      List<String> names = new ArrayList<>();
      for (Object r : resources) {
        try {
          names.add((String) iface.getMethod("getName").invoke(r));
        } catch (Throwable t) {
          names.add("unreadable: " + r.getClass().getName());
        }
      }
      return names;
    } catch (Throwable t) {
      return null;
    }
  }

  private static Object field(Class<?> c, Object o, String name) throws Exception {
    for (Class<?> k = c; k != null; k = k.getSuperclass()) {
      try {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
      } catch (NoSuchFieldException e) {
        // try the superclass
      }
    }
    return null;
  }

  private static Object call(Class<?> c, Object o, String name) throws Exception {
    Method m = c.getMethod(name);
    return m.invoke(o);
  }
}
