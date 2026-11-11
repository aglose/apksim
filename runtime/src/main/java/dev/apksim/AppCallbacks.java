package dev.apksim;

import android.app.Application;
import android.content.ComponentCallbacks;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

/**
 * Callbacks a process-wide singleton registered on the Application, kept across a test run's tests (docs/conformance.md).
 *
 * On a device a suite's tests share one process and one Application. Here each test gets a fresh Application while
 * app statics live on, so a library that registers its ActivityLifecycleCallbacks once a process (AndroidX
 * ProcessLifecycleOwner, through AndroidX Startup's initializer, whose singleton runs it only once) left them on the
 * first test's Application, and from the second test on heard of no activity at all: ProcessLifecycleOwner stayed in
 * whatever state the first test left it (traced: ProcessLifecycleOwner's activityStarted/activityStopped never ran in a
 * later test). So the callbacks the previous test's Application had are registered on the next one, but only those
 * whose class came from the APK (not Robolectric's, AndroidX Test's or apksim's) and that the new Application has no
 * callback of the same class for: an app that registers its callbacks in every Application.onCreate registers new ones
 * (the old ones belong to the old Application and are dropped), a singleton registers none. ComponentCallbacks
 * (registerComponentCallbacks: configuration and memory) the same way. Test runs only: a running instance has one
 * Application. {@code -Dapksim.keepAppCallbacks=false} turns it off.
 */
final class AppCallbacks {
  private AppCallbacks() {}

  private static final boolean ON = Boolean.parseBoolean(System.getProperty("apksim.keepAppCallbacks", "true"));
  /**
   * The Applications whose registrations the next carryOver takes: the ending test's, and any whose setup failed
   * since the last carryOver (a singleton may have registered on one before it failed), oldest first.
   */
  private static final List<Application> pending = new ArrayList<>();

  /**
   * Before Robolectric's reset: which Application the ending test had. Its registrations are read only at carryOver
   * (and, once it shares the process's lists, are those lists), so what a singleton registers or unregisters through it
   * meanwhile (during the next Application's onCreate, say) counts. A test whose setup failed never reached carryOver: its Application joins the pending ones rather than
   * replacing them, so the next test that starts still gets the callbacks of the last one that did.
   */
  static void beforeReset() {
    if (!ON) return;
    try {
      Application app = RuntimeEnvironment.getApplication();
      if (app != null && !pending.contains(app)) pending.add(app);
    } catch (RuntimeException e) {
      // no Application yet (the runner's probe)
    }
  }

  /**
   * The process's one callback list and ComponentCallbacksController, shared by every Application from the first that
   * started: as on a device, there is one place callbacks live, whichever Application a singleton kept.
   */
  private static List<Application.ActivityLifecycleCallbacks> processActivity;
  private static Object processController;

  /**
   * After the next test's Application has been created and its providers and onCreate have run. The process's shared
   * list is rebuilt from what it holds (and what any Application whose setup failed holds): the callbacks whose class
   * came from the APK and that the new Application has registered none of (a singleton's), then the new Application's
   * own; the new Application, and the failed ones, then use the shared list and controller. All of it happens under
   * those lists' locks, the locks Application's register and unregister take, so a singleton's worker registering or
   * unregistering through an Application it kept, at any moment, ends up in the list that is dispatched from. Each
   * callback API is compared on its own (a class may implement both and be registered again for one only).
   */
  static void carryOver(Application app) {
    if (!ON || app == null) return;
    List<Application.ActivityLifecycleCallbacks> mine = ReflectionHelpers.getField(app, "mActivityLifecycleCallbacks");
    Object myController = ReflectionHelpers.getField(app, "mCallbacksController");
    List<Application> failed = new ArrayList<>();
    for (Application old : pending) {
      if (old != app && ReflectionHelpers.getField(old, "mActivityLifecycleCallbacks") != processActivity) failed.add(old);
    }
    pending.clear();
    if (processActivity == null) {  // the first Application that starts: its lists become the process's
      processActivity = mine;
      processController = myController;
    }
    // Callbacks from Applications whose setup failed (a singleton may have registered on one) join the shared ones.
    for (Application old : failed) {
      List<Application.ActivityLifecycleCallbacks> theirs = ReflectionHelpers.getField(old, "mActivityLifecycleCallbacks");
      synchronized (processActivity) {
        synchronized (theirs) {
          for (Application.ActivityLifecycleCallbacks c : theirs) {
            if (fromApk(c.getClass()) && !containsSame(processActivity, c)) processActivity.add(0, c);
          }
          ReflectionHelpers.setField(Application.class, old, "mActivityLifecycleCallbacks", processActivity);
        }
      }
      Object theirController = ReflectionHelpers.getField(old, "mCallbacksController");
      synchronized (lock(processController)) {
        synchronized (lock(theirController)) {
          List<ComponentCallbacks> shared = callbacksOf(processController);
          for (ComponentCallbacks c : orEmpty(callbacksOf(theirController))) {
            if (fromApk(c.getClass()) && !containsSame(shared, c)) shared.add(0, c);
          }
          ReflectionHelpers.setField(Application.class, old, "mCallbacksController", processController);
        }
      }
    }
    if (mine != processActivity) {
      synchronized (processActivity) {
        synchronized (mine) {
          Set<Class<?>> again = classes(mine);
          processActivity.removeIf(c -> !fromApk(c.getClass()) || again.contains(c.getClass()));
          processActivity.addAll(mine);
          ReflectionHelpers.setField(Application.class, app, "mActivityLifecycleCallbacks", processActivity);
        }
      }
    }
    if (myController != processController) {
      synchronized (lock(processController)) {
        synchronized (lock(myController)) {
          List<ComponentCallbacks> theirs = orEmpty(callbacksOf(myController));
          Set<Class<?>> again = classes(theirs);
          List<ComponentCallbacks> shared = callbacksOf(processController);
          shared.removeIf(c -> !fromApk(c.getClass()) || again.contains(c.getClass()));
          shared.addAll(theirs);
          ReflectionHelpers.setField(Application.class, app, "mCallbacksController", processController);
        }
      }
    }
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

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? new ArrayList<>() : list;
  }

  private static Set<Class<?>> classes(List<?> callbacks) {
    Set<Class<?>> out = new HashSet<>();
    if (callbacks == null) return out;
    synchronized (callbacks) {
      for (Object c : callbacks) out.add(c.getClass());
    }
    return out;
  }

  /** A class translated from the app's or the test APK's DEX: served from AppClasses' {@code apksim:} URL. */
  static boolean fromApk(Class<?> c) {
    CodeSource source = c.getProtectionDomain().getCodeSource();
    return source != null && source.getLocation() != null && "apksim".equals(source.getLocation().getProtocol());
  }
}
