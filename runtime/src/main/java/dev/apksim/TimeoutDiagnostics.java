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
 * it is not the timed-out thread, each window root's focus and layout-requested flags, and the names of the idling
 * resources that are not idle. Every part is best effort (reflection, plain field reads from the watchdog's thread) and
 * a part that fails is left out; it never throws.
 */
public final class TimeoutDiagnostics {
  private TimeoutDiagnostics() {}

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
    List<String> busy = busyIdlingResources();
    if (busy != null) b.append("\n  idling resources not idle: ").append(busy.isEmpty() ? "none" : String.join(", ", busy));
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

  /** Names of the idling resources that are not idle, or null when Espresso is not there. */
  private static List<String> busyIdlingResources() {
    try {
      Class<?> reg = Class.forName("androidx.test.espresso.IdlingRegistry");
      Object inst = reg.getMethod("getInstance").invoke(null);
      Iterable<?> resources = (Iterable<?>) reg.getMethod("getResources").invoke(inst);
      List<String> busy = new ArrayList<>();
      for (Object r : resources) {
        try {
          boolean idle = (Boolean) r.getClass().getMethod("isIdleNow").invoke(r);
          if (!idle) busy.add((String) r.getClass().getMethod("getName").invoke(r));
        } catch (Throwable t) {
          // a resource that cannot be asked
        }
      }
      return busy;
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
