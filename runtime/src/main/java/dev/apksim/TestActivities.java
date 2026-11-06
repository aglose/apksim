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
    RealTimeClock.noBarrierWait++;
    try {
      main.idle();
    } finally {
      RealTimeClock.noBarrierWait--;
    }
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

  /**
   * LockSupport.parkNanos from app or test code (MethodTranslator). kotlinx.coroutines' runBlocking parks its thread
   * this way between events, so a suite's polling helper, runBlocking { while (!done) delay(poll) } on the main
   * thread, held it while the app's main-thread work (a Dispatchers.Main continuation, a postDelayed callback) waited
   * behind it, and the poll timed out. As with sleep: in a test run on the main thread, outside a dispatch, the main
   * queue runs, and the clock moves on by the real time the park took, as a device's would while its test thread
   * waits. A park may return early, so this parks one short step and lets the caller check again.
   */
  public static void parkNanos(Object blocker, long nanos) {
    if (nanos > 0 && pumpsWhileSleeping()) {
      parkStep(nanos);
      return;
    }
    java.util.concurrent.locks.LockSupport.parkNanos(blocker, nanos);
  }

  public static void parkNanos(long nanos) {
    if (nanos > 0 && pumpsWhileSleeping()) {
      parkStep(nanos);
      return;
    }
    java.util.concurrent.locks.LockSupport.parkNanos(nanos);
  }

  /**
   * Parked time not yet given to the clock: the looper moves it in whole milliseconds and drops the rest, so parks of
   * under a millisecond each would never move it. Carried over between parks; reset between tests (beforeReset).
   */
  private static long parkCarryNanos;

  private static void parkStep(long nanos) {
    org.robolectric.shadows.ShadowLooper main = Shadow.extract(Looper.getMainLooper());
    main.idle();
    long start = System.nanoTime();
    java.util.concurrent.locks.LockSupport.parkNanos(Math.min(nanos, 2_000_000L));
    long elapsed = System.nanoTime() - start;
    // With --realtime-clock the clock already follows real time (the next idle's clock read catches it up).
    if (elapsed <= 0 || RealTimeClock.requested()) return;
    long total = parkCarryNanos + elapsed;
    long ms = total / 1_000_000L;
    parkCarryNanos = total - ms * 1_000_000L;
    if (ms > 0) main.idleFor(java.time.Duration.ofMillis(ms));
  }

  /** The test APK's own context in a test run (SimRunner.useTestApkContext); null elsewhere. */
  static volatile Context testApkContext;

  /** Instrumentation.getContext() from translated code: the test APK's context, as on a device. */
  public static Context instrumentationContext(Instrumentation instrumentation) {
    Context c = testApkContext;
    return c != null && testRun() ? c : instrumentation.getContext();
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
    // A stopped one shows its window again (Host.bringToFront); one only paused (under a dialog-like activity) kept it.
    if ((boolean) ReflectionHelpers.getField(top, "mStopped")) {
      Host.bringToFront(below);
      ShadowSimWindowManagerImpl.focusTop();  // the window that had it went while this one was hidden
    } else {
      below.resume();
    }
    Host.revealBelow(top, controllers(left.subList(0, left.size() - 1)));
  }

  /** The controllers of {@code activities} (bottom to top), top first. */
  private static List<ActivityController<?>> controllers(List<Activity> activities) {
    List<ActivityController<?>> out = new ArrayList<>();
    for (int i = activities.size() - 1; i >= 0; i--) {
      ActivityController<?> c = controller(activities.get(i));
      if (c != null) out.add(c);
    }
    return out;
  }

  /** Receivers still registered when a test ended, which Robolectric's reset dropped (see ShadowSimContextImpl). */
  static final java.util.Set<android.content.BroadcastReceiver> REGISTERED_BEFORE_RESET =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  /** Called by SimTestEnvironment before Robolectric resets the framework between two tests. */
  private static volatile java.lang.ref.WeakReference<Object> composeIdling;
  private static volatile java.lang.reflect.Method composeIdleNow;
  private static volatile java.lang.reflect.Method loopUntilIdle;
  /** The longest one Espresso check waits for Compose here, in real time. */
  private static final long COMPOSE_WAIT_MS = 50;

  /**
   * A Compose test rule was set up in its Robolectric mode (MethodTranslator, at RobolectricIdlingStrategy's
   * constructor): its idling resource, whose isIdleNow moves the rule's frame clock on a frame when something waits for
   * one. On a device the rule registers that resource with Espresso, so every Espresso check waits for Compose to be
   * idle and a Compose animation (an animated scroll to a section) runs to its end while a check polls for its result.
   * In Robolectric mode only the rule's own calls move the clock, and the animation stood still.
   */
  public static void composeIdling(Object resource) {
    composeIdling = new java.lang.ref.WeakReference<>(resource);
    composeRealBase = System.nanoTime();
    composeClockBase = -1;
  }

  private static volatile java.lang.reflect.Method composeShouldPump;
  private static volatile long composeRealBase;
  private static volatile long composeClockBase = -1;

  /**
   * Whether the Compose rule's clock is already ahead of real time since the rule was made: Espresso on a device polls
   * in real time, so the frames it pumps keep that clock near real time. Pumped back to back on every check, an app
   * that always has a frame waiting got the clock far ahead (an in-class screen's timer then stalled).
   */
  private static boolean composeAhead(Object resource) throws ReflectiveOperationException {
    java.lang.reflect.Field f = resource.getClass().getDeclaredField("clock");
    f.setAccessible(true);
    Object clock = f.get(resource);
    long now = (Long) clock.getClass().getMethod("getCurrentTime").invoke(clock);
    if (composeClockBase < 0) {  // both clocks from the first check: the rule's has not moved before it
      composeClockBase = now;
      composeRealBase = System.nanoTime();
    }
    return now - composeClockBase > (System.nanoTime() - composeRealBase) / 1_000_000L;
  }

  /**
   * Whether this wait is the Compose rule's own (its Robolectric idling strategy runs the main queue through Espresso's
   * UiController): the rule drives its clock there itself, and a second pump from here ran it twice over, which threw an
   * in-class screen's Compose effects off for good (a list that never filled, a timer that stopped). Only an Espresso
   * check of the app's views needs Compose waited for here.
   */
  private static boolean inComposeSync() {
    return StackWalker.getInstance().walk(f -> f.anyMatch(x ->
        x.getClassName().startsWith("androidx.compose.ui.test.RobolectricIdlingStrategy")));
  }

  /** Compose's own test for "the rule's clock has frames to give", without isIdleNow's side effects. */
  private static boolean shouldPump(Object resource) throws ReflectiveOperationException {
    java.lang.reflect.Method m = composeShouldPump;
    if (m == null || m.getDeclaringClass() != resource.getClass()) {
      m = resource.getClass().getDeclaredMethod("_get_isIdleNow_$shouldPumpTime", resource.getClass());
      m.setAccessible(true);
      composeShouldPump = m;
    }
    return (Boolean) m.invoke(null, resource);
  }

  /**
   * UiController.loopMainThreadUntilIdle from Espresso (MethodTranslator): Robolectric's, then, as Espresso on a device
   * waits for the idling resources too, until the Compose rule's resource (composeIdling) is idle. Only while Compose
   * has frames to pump (an animation waiting on its clock), outside the rule's own sync, and with its clock not ahead of
   * real time; paced about a frame of real time apart, as Espresso polls on a device, for at most COMPOSE_WAIT_MS.
   * isIdleNow also applies snapshot changes and runs the rule's test dispatcher: called on every check, back to back,
   * a screen that is never quite idle (a running in-class timer) got its Compose clock run tens of seconds ahead.
   */
  public static void espressoLoopMainThreadUntilIdle(Object controller) throws Throwable {
    java.lang.reflect.Method loop = loopUntilIdle;
    if (loop == null || loop.getDeclaringClass() != controller.getClass()) {
      loop = controller.getClass().getMethod("loopMainThreadUntilIdle");
      loop.setAccessible(true);
      loopUntilIdle = loop;
    }
    try {
      loop.invoke(controller);
      java.lang.ref.WeakReference<Object> ref = composeIdling;
      Object resource = ref == null ? null : ref.get();
      if (resource == null || !testRun() || Looper.myLooper() != Looper.getMainLooper()) return;
      if (!shouldPump(resource) || inComposeSync() || composeAhead(resource)) return;
      java.lang.reflect.Method idleNow = composeIdleNow;
      if (idleNow == null || idleNow.getDeclaringClass() != resource.getClass()) {
        idleNow = resource.getClass().getMethod("isIdleNow");
        idleNow.setAccessible(true);
        composeIdleNow = idleNow;
      }
      long until = System.nanoTime() + COMPOSE_WAIT_MS * 1_000_000L;
      while (!composeAhead(resource) && !(Boolean) idleNow.invoke(resource) && System.nanoTime() < until) {
        long next = System.nanoTime() + 16_000_000L;
        loop.invoke(controller);
        long left = next - System.nanoTime();
        if (left > 0) java.util.concurrent.locks.LockSupport.parkNanos(left);
      }
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause();
    }
  }

  static void beforeReset() {
    composeIdling = null;
    parkCarryNanos = 0;
    // Until the next test publishes its own (SimRunner.useTestApkContext, after its Application starts), calls fall
    // back to the current instrumentation rather than a context over the previous test's Application.
    testApkContext = null;
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
          Host.setWindowVisible(a, false);  // a stopped activity's window is hidden, as on a device
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

    /**
     * An activity resumed shows its window, as ActivityThread.handleResumeActivity does (updateVisibility), whoever
     * resumes it: here also ActivityScenario.moveToState, which restarts an activity whose window apksim hid when it
     * stopped (back on a launcher root, a rotation while stopped). The visible top then has the focus.
     */
    @Implementation
    protected void callActivityOnResume(Activity activity) {
      org.robolectric.util.reflector.Reflector.reflector(InstrumentationReflector.class, self).callActivityOnResume(activity);
      if (!testRun() || activity.getWindow() == null) return;
      View decor = activity.getWindow().peekDecorView();
      if (decor == null || decor.getVisibility() == View.VISIBLE) return;
      Host.setWindowVisible(activity, true);  // unless the app hid it (setVisible(false))
      if (decor.getVisibility() == View.VISIBLE) ShadowSimWindowManagerImpl.focusTop();
    }

    @org.robolectric.util.reflector.ForType(Instrumentation.class)
    interface InstrumentationReflector {
      @org.robolectric.util.reflector.Direct
      void callActivityOnResume(Activity activity);
    }

    /** In a test run, what a device has before the app starts (InstallState), then the app's onCreate. */
    @Implementation
    protected void callApplicationOnCreate(android.app.Application app) {
      // Not for the runner probe's plain Application (RunnerApplication): providers' startup work would keep it.
      if (testRun() && !RunnerApplication.probing) InstallState.beforeApplicationOnCreate(app);
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

    /**
   * --start-activities: a start of one of the app's activities that no running one takes (a new instance) launches it,
   * as ActivityThread does: the activity on top is paused, the new one created, started and resumed, then the one
   * below stopped (unless it is finishing, which finish() already ends). Robolectric only records such a start
   * (getNextStartedActivity), so a suite whose flow goes through a second activity (it starts one, and that one does the
   * next step) waited for a step that never came. Off by default: suites written for Robolectric check the recorded
   * start instead.
   */
  private static void launch(Intent intent, ActivityInfo info, List<Activity> running) {
    Context app = RuntimeEnvironment.getApplication();
    try {
      // An activity-alias's name is the alias; the class to make is its targetActivity (the intent keeps the alias).
      String className = info.targetActivity != null ? info.targetActivity : info.name;
      Class<? extends Activity> cls = Class.forName(className, true, app.getClassLoader()).asSubclass(Activity.class);
      Activity top = running.isEmpty() ? null : running.get(running.size() - 1);
      ActivityController<?> below = top == null ? null : controller(top);
      if (below != null && top.isResumed()) below.pause();
      Intent start = new Intent(intent);
      if (start.getComponent() == null) start.setComponent(new android.content.ComponentName(info.packageName, info.name));
      Activity started = org.robolectric.Robolectric.buildActivity(cls, start).setup().get();
      // Those below are stopped and hidden, as ActivityThread hides a stopped activity's window (UiAutomator found the
      // views of every activity below, and screenshots and failure bundles drew them under the top one), unless the
      // new one is see-through (a dialog-themed activity): then they stay visible, paused.
      Host.coverBelow(started, controllers(running));
    } catch (ReflectiveOperationException | LinkageError e) {
      // No such class: on a device the start fails in the app's own call.
      throw new IllegalStateException("starting " + info.name + ": " + e, e);
    }
    // An exception from the new activity's onCreate/onStart/onResume propagates to the app's startActivity call, as a
    // device's crash would end the test; a clear-task start then stops before destroying the old task (below).
  }

  /**
   * NEW_TASK | CLEAR_TASK, as an app restarts itself (a profile switch): on a device the task's activities are
   * finished and a new instance of the target comes up, whatever its launch mode, so its onCreate runs again. Here: the
   * new one is launched over the top one (paused), then every earlier one is finished and stopped and destroyed.
   */
  private static void clearTaskAndLaunch(Intent intent, ActivityInfo info, List<Activity> running) {
    launch(intent, info, running);
    for (int i = running.size() - 1; i >= 0; i--) {
      Activity old = running.get(i);
      ActivityController<?> c = controller(old);
      if (c == null || old.isDestroyed()) continue;
      ReflectionHelpers.setField(Activity.class, old, "mFinished", true);
      try {
        if (old.isResumed()) c.pause();
        if (!(boolean) ReflectionHelpers.getField(old, "mStopped")) c.stop();
        c.destroy();
      } catch (RuntimeException e) {
        System.err.println("[apksim] clearing " + old.getClass().getName() + ": " + e);
      }
    }
  }

  /** An activity that is already running and whose launch mode takes the intent itself. */
    private static void deliverToRunning(Intent intent) {
      Context app = RuntimeEnvironment.getApplication();
      android.content.pm.ResolveInfo ri = app.getPackageManager().resolveActivity(intent, 0);
      ActivityInfo info = ri == null ? null : ri.activityInfo;
      if (info == null || !info.packageName.equals(app.getPackageName())) return;
      List<Activity> running = withWindows();
      int clearTask = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
      if (Boolean.getBoolean("apksim.startActivities") && (intent.getFlags() & clearTask) == clearTask) {
        clearTaskAndLaunch(intent, info, running);
        return;
      }
      Activity existing = null;
      // Started through an activity-alias, the running instance's class is the alias's targetActivity.
      String className = info.targetActivity != null ? info.targetActivity : info.name;
      for (Activity a : running) if (a.getClass().getName().equals(className)) existing = a;
      boolean onTop = existing != null && running.get(running.size() - 1) == existing;
      boolean reuse = existing != null && (info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
          || info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
          || ((info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP || (intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) && onTop));
      if (!reuse) {
        if (Boolean.getBoolean("apksim.startActivities")) launch(intent, info, running);
        return;
      }
      ActivityController<?> c = controller(existing);
      if (c == null) return;
      // As ActivityThread: a resumed activity is paused around onNewIntent; a stopped one restarts first. The start
      // stays in Robolectric's list of started activities, as every start does.
      if (existing.isResumed()) {
        c.pause();
        c.newIntent(intent);
        c.resume();
      } else {
        if ((boolean) ReflectionHelpers.getField(existing, "mStopped")) {
          c.restart();
          Host.setWindowVisible(existing, true);
          ShadowSimWindowManagerImpl.focusTop();
        }
        c.newIntent(intent);
        c.resume();
      }
      // Activities that were above it in a singleTask's task are finished on a device (CLEAR_TOP): not done here.
    }
  }
}
