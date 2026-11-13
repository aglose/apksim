package dev.apksim;

import android.app.Application;
import android.content.ComponentCallbacks;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import org.robolectric.util.reflector.Reflector;

/**
 * Application callbacks across a test run's tests, as on a device's one Application (docs/conformance.md).
 *
 * On a device a suite's tests share one process and one Application. Here each test gets a fresh Application while
 * app statics live on, so a library that registers its ActivityLifecycleCallbacks once a process (AndroidX
 * ProcessLifecycleOwner, through AndroidX Startup's initializer, whose singleton runs it only once) left them on the
 * first test's Application, and from the second test on heard of no activity at all (traced: ProcessLifecycleOwner's
 * activityStarted/activityStopped never ran in a later test).
 *
 * So the process has one ActivityLifecycleCallbacks list and one ComponentCallbacksController, the first Application's,
 * and every later Application uses them from the start of its startup ({@link #adopt}, before its providers and
 * onCreate): whatever a singleton registers or unregisters, through any Application it kept or the new one, from any
 * thread, lands in the live registrations. What does not carry over is what a device would never have: the callbacks
 * an earlier Application's startup registered from a call site the new Application's startup registered from again
 * (an app's onCreate registering its own listener each time), and framework ones registered through an earlier
 * Application. Those go at the end of the new startup ({@link #carryOver}). Registrations are told apart by owner,
 * startup and call site (the first APK frame and its bytecode index), not by class, so a singleton and a
 * per-Application listener of the same class are each kept or replaced as they should be. Test runs only: a running
 * instance has one Application. {@code -Dapksim.keepAppCallbacks=false} turns it off.
 */
final class AppCallbacks {
  private AppCallbacks() {}

  private static final boolean ON = Api.LEVEL >= 31 /* SPIKE: Application.mCallbacksController is API 31 */ && Boolean.parseBoolean(System.getProperty("apksim.keepAppCallbacks", "true"));

  /** The process's one callback list and controller (the first Application's). */
  private static List<Application.ActivityLifecycleCallbacks> processActivity;
  private static Object processController;

  /** The Application whose startup is running or ran last, its startup's number, and whether it is still running. */
  private static Application current;
  private static int startup;
  private static volatile boolean starting;

  /** Who registered a callback: through which Application, during which startup (-1: outside one), from where. */
  private record Registration(Application owner, int startup, String site) {}

  /**
   * Per callback API, every occurrence of each registered object (one may be registered for both APIs, from different
   * places, more than once, and unregistered from one). Guarded by LOCK, which a registration holds together with the
   * framework's own change, and adopt and carryOver hold throughout: a registration is either wholly before or wholly
   * after a handover.
   */
  static final Object LOCK = new Object();
  private static final Map<Object, List<Registration>> activityRegistrations = new IdentityHashMap<>();
  private static final Map<Object, List<Registration>> componentRegistrations = new IdentityHashMap<>();

  /** At the start of an Application's startup (callApplicationOnCreate, before its providers): it uses the process's lists. */
  static void adopt(Application app) {
    if (!ON || app == null) return;
    synchronized (LOCK) {
      adoptLocked(app);
    }
  }

  private static void adoptLocked(Application app) {
    List<Application.ActivityLifecycleCallbacks> mine = ReflectionHelpers.getField(app, "mActivityLifecycleCallbacks");
    Object myController = ReflectionHelpers.getField(app, "mCallbacksController");
    if (processActivity == null) {
      processActivity = mine;
      processController = myController;
    } else {
      if (mine != processActivity) {
        synchronized (processActivity) {
          synchronized (mine) {  // what was registered on it before its startup (attachBaseContext) joins the rest
            for (Application.ActivityLifecycleCallbacks c : mine) {
              if (!containsSame(processActivity, c)) processActivity.add(c);
            }
            ReflectionHelpers.setField(Application.class, app, "mActivityLifecycleCallbacks", processActivity);
          }
        }
      }
      if (myController != processController) {
        synchronized (lock(processController)) {
          synchronized (lock(myController)) {
            List<ComponentCallbacks> theirs = ReflectionHelpers.getField(myController, "mComponentCallbacks");
            if (theirs != null) {
              List<ComponentCallbacks> shared = callbacksOf(processController);
              for (ComponentCallbacks c : theirs) {
                if (!containsSame(shared, c)) shared.add(c);
              }
            }
            ReflectionHelpers.setField(Application.class, app, "mCallbacksController", processController);
          }
        }
      }
    }
    current = app;
    startup++;
    starting = true;
    // What its constructor or attachBaseContext registered (before this) belongs to its startup too.
    for (Map<Object, List<Registration>> registrations : List.of(activityRegistrations, componentRegistrations)) {
      for (List<Registration> occurrences : registrations.values()) {
        occurrences.replaceAll(r -> r.owner() == app && r.startup() < 0 ? new Registration(app, startup, r.site()) : r);
      }
    }
  }

  /**
   * At the end of an Application's startup (its providers and onCreate ran): what an earlier startup registered from a
   * call site this one registered from again is replaced, so it goes; so do framework callbacks of earlier
   * Applications. A startup that failed never gets here: the next one that ends drops its replaced registrations too.
   */
  static void carryOver(Application app) {
    synchronized (LOCK) {
      starting = false;
      if (!ON || app == null || app != current || processActivity == null) return;
      synchronized (processActivity) {
        dropReplaced(activityRegistrations, processActivity, app, startup);
      }
      synchronized (lock(processController)) {
        List<ComponentCallbacks> shared = ReflectionHelpers.getField(processController, "mComponentCallbacks");
        if (shared != null) dropReplaced(componentRegistrations, shared, app, startup);
      }
    }
  }

  /**
   * Removes, one occurrence at a time, what this startup replaced: an occurrence an earlier startup registered from a
   * call site this startup registered from again (the same object registered again by each onCreate included), and
   * framework callbacks registered through an earlier Application. Hold LOCK and the list's lock.
   */
  private static void dropReplaced(Map<Object, List<Registration>> registrations, List<?> shared, Application app, int now) {
    Set<String> sitesNow = new HashSet<>();
    for (List<Registration> occurrences : registrations.values()) {
      for (Registration r : occurrences) if (r.startup() == now && r.site() != null) sitesNow.add(r.site());
    }
    for (Iterator<Map.Entry<Object, List<Registration>>> it = registrations.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<Object, List<Registration>> e = it.next();
      Object c = e.getKey();
      boolean apk = fromApk(c.getClass());
      for (Iterator<Registration> o = e.getValue().iterator(); o.hasNext(); ) {
        Registration r = o.next();
        boolean replaced = apk
            ? r.startup() >= 0 && r.startup() < now && r.site() != null && sitesNow.contains(r.site())
            : r.owner() != app;
        if (replaced) {
          removeOne(shared, c);
          o.remove();
        }
      }
      if (e.getValue().isEmpty()) it.remove();
    }
  }

  private static void removeOne(List<?> list, Object c) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) == c) {
        list.remove(i);
        return;
      }
    }
  }

  /** Hold LOCK, with the framework's change. */
  private static void registered(Map<Object, List<Registration>> registrations, Application app, Object callback) {
    if (!ON || callback == null) return;
    int at = starting && app == current ? startup : -1;
    registrations.computeIfAbsent(callback, k -> new ArrayList<>()).add(new Registration(app, at, site()));
  }

  /** Hold LOCK, with the framework's change: the framework removes the first occurrence, and so does this. */
  private static void unregistered(Map<Object, List<Registration>> registrations, Object callback) {
    if (!ON || callback == null) return;
    List<Registration> occurrences = registrations.get(callback);
    if (occurrences == null) return;
    occurrences.remove(0);
    if (occurrences.isEmpty()) registrations.remove(callback);
  }

  private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  /**
   * The registering path: every APK frame with its bytecode index, innermost first, so registrations through one
   * helper or an overriding register method from different callers differ; null when no APK code is on the stack.
   */
  private static String site() {
    String path = WALKER.walk(frames -> frames
        .filter(f -> fromApk(f.getDeclaringClass()))
        .map(f -> f.getClassName() + "." + f.getMethodName() + "@" + f.getByteCodeIndex())
        .collect(java.util.stream.Collectors.joining(" < ")));
    return path.isEmpty() ? null : path;
  }

  private static boolean containsSame(List<?> list, Object c) {
    for (Object o : list) if (o == c) return true;
    return false;
  }

  private static Object lock(Object controller) {
    return ReflectionHelpers.getField(controller, "mLock");
  }

  /** The controller's list, made if it has none yet (as its registerCallbacks does); hold its lock. */
  private static List<ComponentCallbacks> callbacksOf(Object controller) {
    List<ComponentCallbacks> list = ReflectionHelpers.getField(controller, "mComponentCallbacks");
    if (list == null) {
      list = new ArrayList<>();
      ReflectionHelpers.setField(controller, "mComponentCallbacks", list);
    }
    return list;
  }

  /** A class translated from the app's or the test APK's DEX: served from AppClasses' {@code apksim:} URL. */
  static boolean fromApk(Class<?> c) {
    CodeSource source = c.getProtectionDomain().getCodeSource();
    return source != null && source.getLocation() != null && "apksim".equals(source.getLocation().getProtocol());
  }

  /** Records who registers Application callbacks (test runs only; otherwise the framework's calls, unchanged). */
  @Implements(Application.class)
  public static class ShadowSimApplication extends ShadowApplication {
    @RealObject private Application self;

    @Implementation
    protected void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
      if (!TestActivities.testRun()) {
        Reflector.reflector(ApplicationReflector.class, self).registerActivityLifecycleCallbacks(callback);
        return;
      }
      synchronized (LOCK) {
        Reflector.reflector(ApplicationReflector.class, self).registerActivityLifecycleCallbacks(callback);
        registered(activityRegistrations, self, callback);
      }
    }

    @Implementation
    protected void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
      if (!TestActivities.testRun()) {
        Reflector.reflector(ApplicationReflector.class, self).unregisterActivityLifecycleCallbacks(callback);
        return;
      }
      synchronized (LOCK) {
        Reflector.reflector(ApplicationReflector.class, self).unregisterActivityLifecycleCallbacks(callback);
        unregistered(activityRegistrations, callback);
      }
    }

    @Implementation
    protected void registerComponentCallbacks(ComponentCallbacks callback) {
      if (!TestActivities.testRun()) {
        Reflector.reflector(ApplicationReflector.class, self).registerComponentCallbacks(callback);
        return;
      }
      synchronized (LOCK) {
        Reflector.reflector(ApplicationReflector.class, self).registerComponentCallbacks(callback);
        registered(componentRegistrations, self, callback);
      }
    }

    @Implementation
    protected void unregisterComponentCallbacks(ComponentCallbacks callback) {
      if (!TestActivities.testRun()) {
        Reflector.reflector(ApplicationReflector.class, self).unregisterComponentCallbacks(callback);
        return;
      }
      synchronized (LOCK) {
        Reflector.reflector(ApplicationReflector.class, self).unregisterComponentCallbacks(callback);
        unregistered(componentRegistrations, callback);
      }
    }
  }

  @ForType(Application.class)
  interface ApplicationReflector {
    @Direct
    void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback);

    @Direct
    void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback);

    @Direct
    void registerComponentCallbacks(ComponentCallbacks callback);

    @Direct
    void unregisterComponentCallbacks(ComponentCallbacks callback);
  }
}
