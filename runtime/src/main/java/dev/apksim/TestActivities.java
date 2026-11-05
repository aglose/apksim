package dev.apksim;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.IRequestFinishCallback;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowInstrumentation;
import org.robolectric.util.ReflectionHelpers;

/**
 * In an instrumented test run (Instrument) there is no Host activity stack, so what the system server does for an
 * activity is otherwise left undone. Two things that end-to-end suites check (those of a large app), done as
 * Android 12+ does them:
 *
 * <ul>
 *   <li>Back on a task's root activity (predictive back's default callback, Activity.navigateBack): an activity
 *       launched from the launcher (MAIN/LAUNCHER, as ActivityScenario launches) has its task moved to the
 *       background, paused and stopped; any other root is finished.
 *   <li>startActivity for an activity that is already running as singleTask/singleInstance (or singleTop, or
 *       FLAG_ACTIVITY_SINGLE_TOP, while it is on top): the running instance gets onNewIntent and comes to the front.
 *       Robolectric only records such a start; it still does for anything else, which a test reads with
 *       ShadowApplication.getNextStartedActivity as before.
 * </ul>
 *
 * Host runs its own stack and does neither: these act only when apksim.instrument is set.
 */
public final class TestActivities {
  private TestActivities() {}

  static boolean testRun() {
    return System.getProperty("apksim.instrument") != null;
  }

  /** The activities that have a window, bottom to top. */
  static List<Activity> withWindows() {
    List<View> views = ReflectionHelpers.getField(android.view.WindowManagerGlobal.getInstance(), "mViews");
    List<Activity> out = new ArrayList<>();
    for (View v : new ArrayList<>(views)) {
      Activity a = activityOf(v);
      if (a != null && !a.isFinishing() && !a.isDestroyed() && !out.contains(a)) out.add(a);
    }
    return out;
  }

  /** The activity whose window a root view is: a DecorView's own context is a DecorContext, not the activity. */
  static Activity activityOf(View root) {
    Context c = root instanceof com.android.internal.policy.DecorView d ? ReflectionHelpers.<android.view.Window>getField(d, "mWindow").getContext() : root.getContext();
    while (c instanceof ContextWrapper && !(c instanceof Activity)) c = ((ContextWrapper) c).getBaseContext();
    return c instanceof Activity a ? a : null;
  }

  /**
   * Compose's test clock and runOnIdle hand their work to runOnUiThread. From a test thread on a device that is a
   * post to the main thread, which first runs what was queued before it: GlobalSnapshotManager's apply of a state
   * just written, so the recomposer hears of it before the clock steps. Here the test is on the main thread (Robolectric's
   * PAUSED looper) and the work would run at once, ahead of the queue: with autoAdvance off a state set in runOnIdle
   * never reached the composition, and an animation it started never moved. So the queue runs first (due messages
   * only; the clock does not move). The translator calls this before each runOnUiThread call in Compose's test library.
   */
  public static void beforeUiThreadAction() {
    if (!testRun() || Looper.myLooper() != Looper.getMainLooper()) return;
    org.robolectric.shadows.ShadowLooper main = Shadow.extract(Looper.getMainLooper());
    main.idle();
  }

  /**
   * Thread.sleep in app and test code (the translator calls this instead). On a device a test sleeping in a polling
   * loop (waiting for a request to reach a fake server, say) runs on its own thread, and the app's main thread goes on
   * meanwhile: a coroutine on Dispatchers.Main resumes, a Handler post runs. Here the test is on the main thread and a
   * plain sleep would hold all of that back until the test next idles the looper, so work that needs one more turn
   * of the main thread never got there and the poll timed out. So, in a test run, on the main thread and outside any
   * message being dispatched (where a device's main thread would be the one sleeping), the sleep runs the queue's due
   * messages as it goes (the clock does not move, as in beforeUiThreadAction).
   */
  public static void sleep(long millis) throws InterruptedException {
    if (millis <= 0 || !pumpsWhileSleeping()) {
      Thread.sleep(millis);  // also its checks: a negative value throws, an interrupt throws
      return;
    }
    pumpFor(TimeUnit.MILLISECONDS.toNanos(millis));
  }

  public static void sleep(long millis, int nanos) throws InterruptedException {
    if (millis < 0) throw new IllegalArgumentException("timeout value is negative");
    if (nanos < 0 || nanos > 999_999) throw new IllegalArgumentException("nanosecond timeout value out of range");
    if ((millis == 0 && nanos == 0) || !pumpsWhileSleeping()) {
      Thread.sleep(millis, nanos);
      return;
    }
    long total = TimeUnit.MILLISECONDS.toNanos(millis);
    pumpFor(total > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : total + nanos);
  }

  private static boolean pumpsWhileSleeping() {
    return testRun() && Looper.myLooper() == Looper.getMainLooper() && !inDispatch();
  }

  /** Sleeps {@code nanos} (> 0), running the main queue's due messages meanwhile; throws on interrupt, as a sleep would. */
  private static void pumpFor(long nanos) throws InterruptedException {
    org.robolectric.shadows.ShadowLooper main = Shadow.extract(Looper.getMainLooper());
    long start = System.nanoTime();
    while (true) {
      if (Thread.interrupted()) throw new InterruptedException("sleep interrupted");
      main.idle();
      long left = nanos - (System.nanoTime() - start);  // no deadline sum to overflow for a long sleep
      if (Thread.interrupted()) throw new InterruptedException("sleep interrupted");
      if (left <= 0) return;
      long step = Math.min(left, 2_000_000L);  // a short step, then the queue again
      Thread.sleep(step / 1_000_000L, (int) (step % 1_000_000L));  // throws if interrupted meanwhile, clearing the flag
    }
  }

  /** Whether this is the main thread outside any message's dispatch, where its queue can be run (runMainQueueUntil). */
  static boolean canRunMainQueue() {
    return Looper.myLooper() == Looper.getMainLooper() && !inDispatch();
  }

  /**
   * On the main thread outside a dispatch ({@link #canRunMainQueue}): runs the main queue's due messages until
   * {@code done} or {@code nanos} have passed, for work that waits on its own thread's queue (an ordered broadcast's
   * result from a shell command). The clock does not move, as in sleep.
   */
  static void runMainQueueUntil(java.util.function.BooleanSupplier done, long nanos) throws InterruptedException {
    org.robolectric.shadows.ShadowLooper main = Shadow.extract(Looper.getMainLooper());
    long start = System.nanoTime();
    while (true) {
      main.idle();
      if (done.getAsBoolean() || System.nanoTime() - start >= nanos) return;
      Thread.sleep(2);
    }
  }

  /** Whether the main thread is inside a message's dispatch (or a looper's own loop), not in the test body itself. */
  private static boolean inDispatch() {
    return StackWalker.getInstance().walk(s -> s.anyMatch(f -> {
      String c = f.getClassName();
      return (c.equals("android.os.Handler") && f.getMethodName().equals("dispatchMessage")) || c.equals("android.os.Looper")
          || c.equals("android.view.Choreographer$FrameDisplayEventReceiver");
    }));
  }

  static ActivityController<?> controller(Activity a) {
    return ReflectionHelpers.getField(Shadow.extract(a), "controller");
  }

  private static void post(Runnable r) {
    new Handler(Looper.getMainLooper()).post(r);
  }

  /**
   * Robolectric injects keys (Espresso's pressBack, UiAutomation) straight into the root view, past ViewRootImpl. With
   * back callbacks enabled (API 33+ opt-in, the default from 36) ViewRootImpl gives BACK to the window's top
   * OnBackInvokedCallback before the views see it, and Activity.onKeyUp then leaves BACK alone, so the key did
   * nothing. As ViewRootImpl: the up goes to the top callback (an app's, else the activity's own, which ends in
   * ActivityClient.onBackPressed below); the down is consumed.
   */
  @Implements(className = "com.android.internal.policy.DecorView", isInAndroidSdk = false)
  public static class ShadowSimDecorView extends org.robolectric.shadows.ShadowViewGroup {
    @org.robolectric.annotation.RealObject private View realDecor;

    @Implementation
    protected boolean dispatchKeyEvent(android.view.KeyEvent e) {
      if (testRun() && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
        Activity a = activityOf(realDecor);
        if (a != null && android.window.WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(a)) {
          if (e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled()) {
            android.view.ViewRootImpl root = realDecor.getViewRootImpl();
            Object d = root == null ? null : root.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback top =
                d instanceof android.window.WindowOnBackInvokedDispatcher w ? w.getTopCallback() : null;
            // ViewRootImpl's own compat callback only re-sends the key; under Robolectric the window's dispatcher
            // can take the app for one without callbacks while the activity has them (Activity.onKeyUp then
            // ignores BACK), so that one stands for "no app callback": the activity's back, which runs androidx's
            // OnBackPressedDispatcher and ends in Activity.onBackInvoked (ActivityClient.onBackPressed below).
            if (top != null && !top.getClass().getName().startsWith("android.view.ViewRootImpl")) top.onBackInvoked();
            else a.onBackPressed();
          }
          return true;
        }
      }
      return org.robolectric.util.reflector.Reflector.reflector(DecorViewReflector.class, realDecor).dispatchKeyEvent(e);
    }

    @org.robolectric.util.reflector.ForType(className = "com.android.internal.policy.DecorView")
    interface DecorViewReflector {
      @org.robolectric.util.reflector.Direct
      boolean dispatchKeyEvent(android.view.KeyEvent e);
    }
  }

  /**
   * The activity's side of back and finish. Robolectric's onBackPressed only calls finish(), where Android 16 runs
   * Activity.onBackInvoked: ActivityClient.onBackPressed, which moves a launcher task's root to the background (see
   * below). And its finish() only marks the activity finishing; the system would then pause, stop and destroy it and
   * resume the one below, which Host does in its own stack and is done here for a test run. (ActivityScenario's
   * close finishes and destroys at once, so it finds it destroyed already.)
   */
  @Implements(Activity.class)
  public static class ShadowSimActivity extends org.robolectric.shadows.ShadowActivity {
    @Implementation
    @Override
    protected void onBackPressed() {
      if (!testRun()) {
        super.onBackPressed();
        return;
      }
      org.robolectric.util.reflector.Reflector.reflector(ActivityReflector.class, realActivity).onBackPressed();
    }

    @Implementation
    @Override
    protected void finish() {
      super.finish();
      if (testRun()) {
        Activity a = realActivity;
        post(() -> finished(a));
      }
    }

    @org.robolectric.util.reflector.ForType(Activity.class)
    interface ActivityReflector {
      @org.robolectric.util.reflector.Direct
      void onBackPressed();
    }
  }

  private static void finished(Activity a) {
    ActivityController<?> c = controller(a);
    if (c == null || a.isDestroyed()) return;
    if (a.isResumed()) c.pause();
    if (!(boolean) ReflectionHelpers.getField(a, "mStopped")) c.stop();
    c.destroy();
    List<Activity> left = withWindows();
    if (left.isEmpty()) return;
    Activity top = left.get(left.size() - 1);
    ActivityController<?> below = controller(top);
    if (below == null || top.isResumed()) return;
    if ((boolean) ReflectionHelpers.getField(top, "mStopped")) below.restart();
    below.resume();
  }

  /** Receivers still registered when a test ended, which Robolectric's reset dropped (see ShadowSimContextImpl). */
  static final java.util.Set<android.content.BroadcastReceiver> REGISTERED_BEFORE_RESET =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  /** Called by SimTestEnvironment before Robolectric resets the framework between two tests. */
  static void beforeReset() {
    try {
      Object si = Shadow.extract(ShadowInstrumentation.getInstrumentation());
      List<?> wrappers = ReflectionHelpers.callInstanceMethod(si, "getRegisteredReceivers");
      for (Object w : wrappers) {
        REGISTERED_BEFORE_RESET.add(((org.robolectric.shadows.ShadowApplication.Wrapper) w).broadcastReceiver);
      }
    } catch (RuntimeException e) {
      // nothing registered, or no instrumentation yet
    }
  }

  /**
   * Each test gets a fresh Application, but app statics live on, so a singleton can unregister a receiver it
   * registered during an earlier test. Robolectric's reset forgot that registration and throws "Receiver not
   * registered"; on a device (one process, one Application) the registration would still be there. WebRTC's
   * NetworkMonitorAutoDetect does this at teardown, from native code, which then aborts the process.
   */
  @Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
  public static class ShadowSimContextImpl extends org.robolectric.shadows.ShadowContextImpl {
    @Implementation
    @Override
    protected void unregisterReceiver(android.content.BroadcastReceiver receiver) {
      try {
        super.unregisterReceiver(receiver);
      } catch (IllegalArgumentException e) {
        if (!REGISTERED_BEFORE_RESET.remove(receiver)) throw e;
      }
    }
  }

  @Implements(ActivityClient.class)
  public static class ShadowSimActivityClient {
    @Implementation
    protected void onBackPressed(IBinder token, IRequestFinishCallback callback) {
      if (!testRun()) return;
      Activity target = null;
      for (Activity a : withWindows()) if (a.getActivityToken() == token) target = a;
      if (target == null) return;
      Intent in = target.getIntent();
      boolean fromLauncher = in != null && Intent.ACTION_MAIN.equals(in.getAction()) && in.hasCategory(Intent.CATEGORY_LAUNCHER);
      Activity a = target;
      if (fromLauncher) {
        post(() -> {
          ActivityController<?> c = controller(a);
          if (c == null || a.isFinishing()) return;
          a.moveTaskToBack(true);
          if (a.isResumed()) c.pause();
          c.stop();
        });
      } else {
        try {
          callback.requestFinish();
        } catch (android.os.RemoteException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  @Implements(Instrumentation.class)
  public static class ShadowSimInstrumentation extends ShadowInstrumentation {
    @org.robolectric.annotation.RealObject private Instrumentation self;

    /** In a test run, what a device has before the app starts (InstallState), then the app's onCreate. */
    @Implementation
    protected void callApplicationOnCreate(android.app.Application app) {
      if (testRun()) InstallState.beforeApplicationOnCreate(app);
      try {
        java.lang.reflect.Method m = Instrumentation.class.getDeclaredMethod(
            "$$robo$$android_app_Instrumentation$callApplicationOnCreate", android.app.Application.class);
        m.setAccessible(true);
        m.invoke(self, app);
      } catch (java.lang.reflect.InvocationTargetException e) {
        if (e.getCause() instanceof RuntimeException r) throw r;
        if (e.getCause() instanceof Error r) throw r;
        throw new IllegalStateException(e.getCause());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    /*
     * On a device bindService returns false, and never calls back, when no installed service matches the intent.
     * Robolectric returns true and calls onServiceConnected(null, null), which a Kotlin connection (non-null
     * parameters) throws on: one app's bind to a service in a companion app that isn't installed failed every test.
     * Other apps do not exist here, so a bind that BoundServices does not take (not one of the app's own services) and
     * that no installed service resolves is a bind to nothing; what a test set up through ShadowApplication stays
     * Robolectric's.
     */
    private static boolean resolves(Intent intent) {
      if (!testRun()) return true;
      Context app = org.robolectric.RuntimeEnvironment.getApplication();
      if (app.getPackageManager().resolveService(intent, 0) != null) return true;
      System.err.println("[apksim] bindService: no installed service matches " + intent + "; false, as on a device");
      return false;
    }

    /** An instrumentation started over adb (LiveInstrumentation) reports to that `am instrument`, not a watcher. */
    @Implementation
    protected void sendStatus(int resultCode, Bundle results) {
      LiveInstrumentation live = LiveInstrumentation.of(self);
      if (live != null) live.status(resultCode, results);
      else original("sendStatus", resultCode, results);
    }

    @Implementation
    protected void finish(int resultCode, Bundle results) {
      LiveInstrumentation live = LiveInstrumentation.of(self);
      if (live != null) live.finished(resultCode, results);
      else original("finish", resultCode, results);
    }

    /**
     * While RunnerApplication probes the test APK's runner: records the class its newApplication asks for and stops
     * there, so no Application is made. Otherwise Instrumentation's own.
     */
    @Implementation
    protected android.app.Application newApplication(ClassLoader cl, String className, Context context)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
      if (RunnerApplication.recording) throw new RunnerApplication.Recorded(className);
      return (android.app.Application) originalNewApplication(self, new Class<?>[] {ClassLoader.class, String.class, Context.class},
          cl, className, context);
    }

    /** The static form a runner may call with a class instead of a name; recorded the same way. */
    @Implementation
    protected static android.app.Application newApplication(Class<?> clazz, Context context)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
      if (RunnerApplication.recording) throw new RunnerApplication.Recorded(clazz.getName());
      return (android.app.Application) originalNewApplication(null, new Class<?>[] {Class.class, Context.class}, clazz, context);
    }

    private static Object originalNewApplication(Instrumentation self, Class<?>[] types, Object... args)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
      try {
        java.lang.reflect.Method m = Instrumentation.class.getDeclaredMethod("$$robo$$android_app_Instrumentation$newApplication", types);
        m.setAccessible(true);
        return m.invoke(self, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        if (e.getCause() instanceof InstantiationException x) throw x;
        if (e.getCause() instanceof IllegalAccessException x) throw x;
        if (e.getCause() instanceof ClassNotFoundException x) throw x;
        if (e.getCause() instanceof RuntimeException r) throw r;
        throw new IllegalStateException(e.getCause());
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException(e);
      }
    }

    private void original(String name, int code, Bundle results) {
      try {
        java.lang.reflect.Method m = Instrumentation.class.getDeclaredMethod("$$robo$$android_app_Instrumentation$" + name, int.class, Bundle.class);
        m.setAccessible(true);
        m.invoke(self, code, results);
      } catch (java.lang.reflect.InvocationTargetException e) {
        if (e.getCause() instanceof RuntimeException r) throw r;
        throw new IllegalStateException(e.getCause());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    // ShadowContextImpl's bindService and unbindService come here: the app's own services are bound as on a device
    // (BoundServices), anything else as Robolectric does.
    @Override
    protected boolean bindService(Intent intent, android.content.ServiceConnection connection, int flags) {
      if (bindHere(intent, flags, null, connection)) return true;
      if (!setUpByTest(intent) && !resolves(intent)) return false;
      return super.bindService(intent, connection, flags);
    }

    @Override
    protected boolean bindService(Intent intent, int flags, java.util.concurrent.Executor executor, android.content.ServiceConnection connection) {
      if (bindHere(intent, flags, executor, connection)) return true;
      if (!setUpByTest(intent) && !resolves(intent)) return false;
      return super.bindService(intent, flags, executor, connection);
    }

    @Override
    protected void unbindService(android.content.ServiceConnection connection) {
      if (!BoundServices.of(RuntimeEnvironment.getApplication()).unbind(connection)) {
        super.unbindService(connection);
        return;
      }
      List<android.content.ServiceConnection> bound = ReflectionHelpers.getField(this, "boundServiceConnections");
      List<android.content.ServiceConnection> unbound = ReflectionHelpers.getField(this, "unboundServiceConnections");
      unbound.add(connection);
      bound.remove(connection);
    }

    /** What a test set up through ShadowApplication (a binder for the intent, a default one, unbindable intents, a bind that throws). */
    private boolean setUpByTest(Intent intent) {
      ServiceConnectionDataWrapper data = getServiceConnectionDataWrapper(intent);
      List<android.content.ComponentName> unbindable = ReflectionHelpers.getField(this, "unbindableComponents");
      return data.componentNameForBindService != null || data.binderForBindService != null
          || ReflectionHelpers.getField(this, "exceptionForBindService") != null
          || getUnbindableActions().contains(intent.getAction()) || unbindable.contains(intent.getComponent());
    }

    private boolean bindHere(Intent intent, int flags, java.util.concurrent.Executor executor, android.content.ServiceConnection connection) {
      // What a test set up through ShadowApplication stays Robolectric's.
      if (setUpByTest(intent)) return false;
      if (!BoundServices.of(RuntimeEnvironment.getApplication()).bind(intent, flags, executor, connection)) return false;
      List<android.content.ServiceConnection> bound = ReflectionHelpers.getField(this, "boundServiceConnections");
      List<android.content.ServiceConnection> unbound = ReflectionHelpers.getField(this, "unboundServiceConnections");
      bound.add(connection);
      unbound.remove(connection);
      return true;
    }

    @Implementation
    @Override
    protected Instrumentation.ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token,
        Activity target, Intent intent, int requestCode, Bundle options) {
      Instrumentation.ActivityResult r = super.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
      if (testRun() && requestCode < 0) {
        Intent copy = new Intent(intent);
        post(() -> deliverToRunning(copy));
      }
      return r;
    }

    /** An activity that is already running and whose launch mode takes the intent itself. */
    private static void deliverToRunning(Intent intent) {
      Context app = RuntimeEnvironment.getApplication();
      android.content.pm.ResolveInfo ri = app.getPackageManager().resolveActivity(intent, 0);
      ActivityInfo info = ri == null ? null : ri.activityInfo;
      if (info == null || !info.packageName.equals(app.getPackageName())) return;
      List<Activity> running = withWindows();
      Activity existing = null;
      for (Activity a : running) if (a.getClass().getName().equals(info.name)) existing = a;
      if (existing == null) return;
      boolean onTop = running.get(running.size() - 1) == existing;
      boolean reuse = info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK || info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
          || ((info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP || (intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) && onTop);
      if (!reuse) return;
      ActivityController<?> c = controller(existing);
      if (c == null) return;
      // As ActivityThread: a resumed activity is paused around onNewIntent; a stopped one restarts first. The start
      // stays in Robolectric's list of started activities, as every start does.
      if (existing.isResumed()) {
        c.pause();
        c.newIntent(intent);
        c.resume();
      } else {
        if ((boolean) ReflectionHelpers.getField(existing, "mStopped")) c.restart();
        c.newIntent(intent);
        c.resume();
      }
      // Activities that were above it in a singleTask's task are finished on a device (CLEAR_TOP): not done here.
    }
  }
}
