package dev.apksim;

import android.app.Application;
import android.content.ComponentCallbacks;
import java.lang.ref.WeakReference;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
  private static final List<Application.ActivityLifecycleCallbacks> activity = new ArrayList<>();
  private static final List<ComponentCallbacks> component = new ArrayList<>();

  /** Before Robolectric's reset: what the ending test's Application has registered. */
  static void beforeReset() {
    activity.clear();
    component.clear();
    if (!ON) return;
    try {
      Application app = RuntimeEnvironment.getApplication();
      if (app == null) return;
      activity.addAll(apkOnes(ReflectionHelpers.getField(app, "mActivityLifecycleCallbacks")));
      component.addAll(apkOnes(componentCallbacks(app)));
    } catch (RuntimeException e) {
      // no Application yet (the runner's probe), or nothing registered
    }
  }

  /**
   * After the next test's Application has been created and its providers and onCreate have run. Each API's classes are
   * compared on their own (a class may implement both and be registered again for one only). Then every earlier test's
   * Application is pointed at this one's callback list and ComponentCallbacksController: a singleton that kept the
   * Application it registered on and unregisters (or registers) through it later reaches the registrations that are
   * live, as on a device's one Application, instead of a list nothing dispatches from.
   */
  static void carryOver(Application app) {
    if (!ON || app == null) return;
    List<Application.ActivityLifecycleCallbacks> nowActivity = ReflectionHelpers.getField(app, "mActivityLifecycleCallbacks");
    Set<Class<?>> activityClasses = classes(nowActivity);
    Set<Class<?>> componentClasses = classes(componentCallbacks(app));
    for (Application.ActivityLifecycleCallbacks c : activity) {
      if (!activityClasses.contains(c.getClass())) app.registerActivityLifecycleCallbacks(c);
    }
    for (ComponentCallbacks c : component) {
      if (!componentClasses.contains(c.getClass())) app.registerComponentCallbacks(c);
    }
    activity.clear();
    component.clear();
    Object controller = ReflectionHelpers.getField(app, "mCallbacksController");
    for (Iterator<WeakReference<Application>> it = earlier.iterator(); it.hasNext(); ) {
      Application old = it.next().get();
      if (old == null) {
        it.remove();
      } else if (old != app) {
        ReflectionHelpers.setField(Application.class, old, "mActivityLifecycleCallbacks", nowActivity);
        ReflectionHelpers.setField(Application.class, old, "mCallbacksController", controller);
      }
    }
    earlier.add(new WeakReference<>(app));
  }

  /** Every earlier test's Application, which a singleton may still hold. */
  private static final List<WeakReference<Application>> earlier = new ArrayList<>();

  private static Set<Class<?>> classes(List<?> callbacks) {
    Set<Class<?>> out = new HashSet<>();
    if (callbacks == null) return out;
    synchronized (callbacks) {
      for (Object c : callbacks) out.add(c.getClass());
    }
    return out;
  }

  /** Application's ComponentCallbacks live in its ComponentCallbacksController (API 33+); null until one registers. */
  private static List<ComponentCallbacks> componentCallbacks(Application app) {
    Object controller = ReflectionHelpers.getField(app, "mCallbacksController");
    return ReflectionHelpers.getField(controller, "mComponentCallbacks");
  }

  private static <T> List<T> apkOnes(List<T> callbacks) {
    List<T> out = new ArrayList<>();
    if (callbacks == null) return out;
    synchronized (callbacks) {
      for (T c : callbacks) {
        if (fromApk(c.getClass())) out.add(c);
      }
    }
    return out;
  }

  /** A class translated from the app's or the test APK's DEX: served from AppClasses' {@code apksim:} URL. */
  static boolean fromApk(Class<?> c) {
    CodeSource source = c.getProtectionDomain().getCodeSource();
    return source != null && source.getLocation() != null && "apksim".equals(source.getLocation().getProtocol());
  }
}
