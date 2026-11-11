package dev.apksim;

import android.app.Application;
import android.content.ComponentCallbacks;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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

  private static final boolean ON = Boolean.parseBoolean(System.getProperty("apksim.keepAppCallbacks", "true"));

  /** The process's one callback list and controller (the first Application's). */
  private static List<Application.ActivityLifecycleCallbacks> processActivity;
  private static Object processController;

  /** The Application whose startup is running or ran last, its startup's number, and whether it is still running. */
  private static Application current;
  private static int startup;
  private static volatile boolean starting;

  /** Who registered a callback: through which Application, during which startup (-1: outside one), from where. */
  private record Registration(Application owner, int startup, String site) {}

  /** Per callback API: one object may be registered for both, from different places, and unregistered from one. */
  private static final Map<Object, Registration> activityRegistrations = Collections.synchronizedMap(new IdentityHashMap<>());
  private static final Map<Object, Registration> componentRegistrations = Collections.synchronizedMap(new IdentityHashMap<>());

  /** At the start of an Application's startup (callApplicationOnCreate, before its providers): it uses the process's lists. */
  static void adopt(Application app) {
    if (!ON || app == null) return;
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
  }

  /**
   * At the end of an Application's startup (its providers and onCreate ran): what an earlier startup registered from a
   * call site this one registered from again is replaced, so it goes; so do framework callbacks of earlier
   * Applications. A startup that failed never gets here: the next one that ends drops its replaced registrations too.
   */
  static void carryOver(Application app) {
    starting = false;
    if (!ON || app == null || app != current || processActivity == null) return;
    int now = startup;
    Predicate<Object> activityReplaced = replaced(activityRegistrations, app, now);
    Predicate<Object> componentReplaced = replaced(componentRegistrations, app, now);
    List<Object> gone = new ArrayList<>();
    synchronized (processActivity) {
      for (Object c : processActivity) if (activityReplaced.test(c)) gone.add(c);
      processActivity.removeIf(c -> containsSame(gone, c));
    }
    for (Object c : gone) activityRegistrations.remove(c);
    List<Object> goneHere = new ArrayList<>();
    synchronized (lock(processController)) {
      List<ComponentCallbacks> shared = ReflectionHelpers.getField(processController, "mComponentCallbacks");
      if (shared != null) {
        for (Object c : shared) if (componentReplaced.test(c)) goneHere.add(c);
        shared.removeIf(c -> containsSame(goneHere, c));
      }
    }
    for (Object c : goneHere) componentRegistrations.remove(c);
  }

  /** Whether a callback of one API is replaced: by a registration from the same call site in this startup. */
  private static Predicate<Object> replaced(Map<Object, Registration> registrations, Application app, int now) {
    Set<String> sitesNow = new HashSet<>();
    synchronized (registrations) {
      for (Registration r : registrations.values()) {
        if (r.startup() == now && r.site() != null) sitesNow.add(r.site());
      }
    }
    return c -> {
      Registration r = registrations.get(c);
      if (r == null) return false;
      if (!fromApk(c.getClass())) return r.owner() != app;
      return r.startup() >= 0 && r.startup() < now && r.site() != null && sitesNow.contains(r.site());
    };
  }

  static void registered(Map<Object, Registration> registrations, Application app, Object callback) {
    if (ON && callback != null) registrations.put(callback, new Registration(app, starting ? startup : -1, site()));
  }

  static void unregistered(Map<Object, Registration> registrations, Object callback) {
    if (ON && callback != null) registrations.remove(callback);
  }

  private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  /** The registering call site: the first frame of APK code, with its bytecode index; null when there is none. */
  private static String site() {
    return WALKER.walk(frames -> frames
        .filter(f -> fromApk(f.getDeclaringClass()))
        .findFirst()
        .map(f -> f.getClassName() + "." + f.getMethodName() + "@" + f.getByteCodeIndex())
        .orElse(null));
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
      Reflector.reflector(ApplicationReflector.class, self).registerActivityLifecycleCallbacks(callback);
      if (TestActivities.testRun()) registered(activityRegistrations, self, callback);
    }

    @Implementation
    protected void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
      Reflector.reflector(ApplicationReflector.class, self).unregisterActivityLifecycleCallbacks(callback);
      if (TestActivities.testRun()) unregistered(activityRegistrations, callback);
    }

    @Implementation
    protected void registerComponentCallbacks(ComponentCallbacks callback) {
      Reflector.reflector(ApplicationReflector.class, self).registerComponentCallbacks(callback);
      if (TestActivities.testRun()) registered(componentRegistrations, self, callback);
    }

    @Implementation
    protected void unregisterComponentCallbacks(ComponentCallbacks callback) {
      Reflector.reflector(ApplicationReflector.class, self).unregisterComponentCallbacks(callback);
      if (TestActivities.testRun()) unregistered(componentRegistrations, callback);
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
