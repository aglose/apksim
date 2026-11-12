package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowInstrumentation;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowUiAutomation;
import org.robolectric.util.ReflectionHelpers;

/**
 * UiAutomation for UiAutomator (androidx.test.uiautomator) in an instrumented test run (Instrument). On a device it is
 * an accessibility service connection to the system; here there is no system, so the window tree comes from the
 * process's own windows. Each window's root node is linked to its view hierarchy with
 * {@link AccessibilityNodeInfo#setQueryFromAppProcessEnabled} (API 34), so getChild, getParent, refresh and
 * performAction go through the framework's own AccessibilityInteractionController to the views and virtual nodes
 * (Compose semantics, with testTagsAsResourceId as resource ids), exactly what a service would read. Robolectric's
 * ShadowUiAutomation keeps input injection (taps and gestures reach the windows) and takeScreenshot; rotation
 * delivers a configuration change to the activities as a device does, kept or recreated by their configChanges.
 *
 * Shell commands run apksim's adb shell in this process ({@link #executeShellCommand}).
 *
 * <p>Not here: an accessibility event stream (executeAndWaitForEvent runs the action, lets the UI settle and reports
 * the scrolls and window change it made, else times out) and global actions other than Back.
 */
@Implements(UiAutomation.class)
public class ShadowSimUiAutomation extends ShadowUiAutomation {
  private AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
  /** Window ids for the roots, stable for as long as the root lives (UiAutomator compares them). */
  private static final java.util.Map<ViewRootImpl, Integer> WINDOW_IDS = new java.util.WeakHashMap<>();
  private static int nextWindowId = 1;

  // --- Windows and nodes -------------------------------------------------------------------------------------------

  /** One window of this process, bottom to top. */
  private record Win(ViewRootImpl root, View view, WindowManager.LayoutParams params) {
    boolean focusable() {
      return (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
    }
  }

  /** Every shown window, bottom to top in the stack Host draws ({@link AccessibleWindows#stack}), covered ones included. */
  private static List<Win> allWindows() {
    List<Win> out = new ArrayList<>();
    for (View v : AccessibleWindows.stack()) {
      ViewRootImpl r = v.getViewRootImpl();
      if (r != null) out.add(new Win(r, v, (WindowManager.LayoutParams) v.getLayoutParams()));
    }
    return out;
  }

  /**
   * The windows accessibility reports, bottom to top: those a touch-modal or full-screen window above them covers are
   * left out, as on a device ({@link AccessibleWindows}).
   */
  private static List<Win> windows() {
    List<Win> all = allWindows();
    List<View> views = new ArrayList<>();
    List<WindowManager.LayoutParams> params = new ArrayList<>();
    for (Win w : all) {
      views.add(w.view);
      params.add(w.params);
    }
    boolean[] keep = AccessibleWindows.reported(views, params);
    List<Win> out = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) if (keep[i]) out.add(all.get(i));
    return out;
  }

  /** The root views of every shown window, covered ones included, bottom to top; main thread only. */
  static List<View> rootViews() {
    List<View> out = new ArrayList<>();
    for (Win w : allWindows()) out.add(w.view);
    return out;
  }

  private static int windowId(ViewRootImpl root) {
    synchronized (WINDOW_IDS) {
      return WINDOW_IDS.computeIfAbsent(root, r -> nextWindowId++);
    }
  }

  /** The root node of a window, linked to its views so that the whole tree can be walked from it. */
  private static AccessibilityNodeInfo rootNode(Win w) {
    // The window's accessibility id, which the system gives each window it reports (here no one did): every node of
    // the window carries it, and a node's equals and hashCode include it. Without it every window's root node was
    // equal to every other's (the same root item id, window id undefined), and UiDevice, which collects the roots in a
    // set, kept only the active window's.
    Object attach = ReflectionHelpers.getField(w.root, "mAttachInfo");
    if (ReflectionHelpers.<Integer>getField(attach, "mAccessibilityWindowId") == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
      ReflectionHelpers.setField(attach, "mAccessibilityWindowId", windowId(w.root));
    }
    AccessibilityNodeInfo info = w.view.createAccessibilityNodeInfo();
    if (info == null) return null;
    info.setQueryFromAppProcessEnabled(w.view, true);
    return info;
  }

  @Implementation
  protected AccessibilityNodeInfo getRootInActiveWindow() {
    return onMain(ShadowSimUiAutomation::activeRoot);
  }

  /** The root node of the active window (the topmost focusable one), linked to its views; main thread only. */
  static AccessibilityNodeInfo activeRoot() {
    List<Win> all = windows();
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).focusable()) return rootNode(all.get(i));
    }
    return all.isEmpty() ? null : rootNode(all.get(all.size() - 1));
  }

  @Implementation
  protected AccessibilityNodeInfo getRootInActiveWindow(int prefetchingStrategy) {
    return getRootInActiveWindow();
  }

  @Implementation
  protected List<AccessibilityWindowInfo> getWindows() {
    return onMain(ShadowSimUiAutomation::windowInfos);
  }

  /**
   * Every window accessibility reports (none a touch-modal or full-screen window covers), topmost first as the system
   * lists them, each one's root linked to its views; main thread.
   */
  static List<AccessibilityWindowInfo> windowInfos() {
    List<Win> all = windows();
    List<AccessibilityWindowInfo> out = new ArrayList<>();
    int active = -1;
    for (int i = all.size() - 1; i >= 0 && active < 0; i--) if (all.get(i).focusable()) active = i;
    // Topmost first, as the system lists them.
    for (int i = all.size() - 1; i >= 0; i--) {
      Win w = all.get(i);
      // The framework's own (hidden) setters: once nodes query the app process, Robolectric's shadow defers to
      // the real object. Its connection is the root's direct one, so getRoot() reads the window's views.
      AccessibilityWindowInfo info = AccessibilityWindowInfo.obtain();
      info.setId(windowId(w.root));
      info.setLayer(i);
      info.setDisplayId(Display.DEFAULT_DISPLAY);
      info.setType(w.params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
          ? AccessibilityWindowInfo.TYPE_SYSTEM : AccessibilityWindowInfo.TYPE_APPLICATION);
      info.setActive(i == active);
      info.setFocused(i == active);
      int[] at = Host.screenPosition(w.view);
      info.setRegionInScreen(new android.graphics.Region(at[0], at[1], at[0] + w.view.getWidth(), at[1] + w.view.getHeight()));
      info.setTitle(w.params.getTitle());
      rootNode(w);  // links the root's direct connection
      info.setConnectionId(w.root.getDirectAccessibilityConnectionId());
      out.add(info);
    }
    return out;
  }

  @Implementation
  protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
    SparseArray<List<AccessibilityWindowInfo>> out = new SparseArray<>();
    out.put(Display.DEFAULT_DISPLAY, getWindows());
    return out;
  }

  @Implementation
  protected AccessibilityServiceInfo getServiceInfo() {
    return serviceInfo;
  }

  @Implementation
  protected void setServiceInfo(AccessibilityServiceInfo info) {
    serviceInfo = info;
  }

  @Implementation
  protected boolean clearCache() {
    return true;
  }

  @Implementation
  protected boolean clearCachedSubtree(AccessibilityNodeInfo node) {
    return true;
  }

  // --- Idle and events ---------------------------------------------------------------------------------------------

  /**
   * Lets the UI settle: runs what the main thread has due, then moves the simulated clock on frame by frame while
   * work is due within the quiet time (a frame, an animation step), so a test sees the screen a device would show
   * once it stopped changing. An endless animation stops it at the quiet time, as it would a device's wait.
   */
  @Implementation
  protected void waitForIdle(long idleTimeoutMillis, long globalTimeoutMillis) {
    if (!TestActivities.testRun()) {
      liveSettle(Math.min(idleTimeoutMillis, globalTimeoutMillis));
      return;
    }
    onMain(() -> {
      settle(Math.min(idleTimeoutMillis, globalTimeoutMillis));
      return null;
    });
  }

  /**
   * In a running instance (an instrumentation started over adb) the Host loop owns the clock, which follows real time:
   * waits, in real time, until the main thread has nothing due, or QUIET ms at most (an endless animation).
   */
  static void liveSettle(long quietMillis) {
    long end = System.nanoTime() + quietMillis * 1_000_000;
    do {
      try {
        Thread.sleep(16);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      boolean idle = onMain(() -> {
        ShadowLooper main = shadowOf(Looper.getMainLooper());
        long next = main.getNextScheduledTaskTime().toMillis();
        return next == 0 || next > SystemClock.uptimeMillis() + 32;
      });
      if (idle) return;
    } while (System.nanoTime() < end);
  }

  private static void settle(long quietMillis) {
    ShadowLooper main = shadowOf(Looper.getMainLooper());
    if (RealTimeClock.requested()) {
      // The clock follows real time, so an endless animation always has its next frame due and idle() never ends; on a
      // device the wait returns after its timeout. Run what comes due for at most the quiet time, in real time, and
      // stop early once nothing is scheduled within 50 ms. Not at the first moment nothing is due: a window just
      // shown has its first layout at the next vsync, a few ms on, and a query before it found an empty window
      // (UiAutomatorConformanceTest's first queries after a launch, with --realtime-clock).
      long until = System.nanoTime() + quietMillis * 1_000_000L;
      while (System.nanoTime() < until) {
        if (!main.isIdle()) {
          main.runOneTask();
          continue;
        }
        long next = main.getNextScheduledTaskTime().toMillis();
        if (next == 0 || next - SystemClock.uptimeMillis() > 50) return;  // the clock read moves it up to real time
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      return;
    }
    // Not main.idle() or idleFor(): with Robolectric's Choreographer unpaused, as in test runs, a frame asked for runs
    // at once, so an endless frame loop never leaves either. A Compose Popup has one (it polls where its anchor is on
    // screen with withInfiniteAnimationFrameNanos), and UiAutomator's every query waited for idle forever once one was
    // shown; a device's wait gives up after its timeout. So tasks run one at a time, what is due first, then the next
    // one scheduled within the quiet time (runOneTask moves the clock to it), and both the simulated and the real
    // quiet time are checked between tasks.
    long end = SystemClock.uptimeMillis() + quietMillis;
    long realEnd = System.nanoTime() + quietMillis * 1_000_000L;
    while (SystemClock.uptimeMillis() < end && System.nanoTime() < realEnd) {
      if (main.isIdle()) {
        long next = main.getNextScheduledTaskTime().toMillis();
        if (next == 0 || next > end) break;  // nothing scheduled within the quiet time
      }
      main.runOneTask();
    }
  }

  /**
   * Without an event stream: runs the action, lets the UI settle, and offers the filter the events the action would
   * have brought. First a TYPE_VIEW_SCROLLED for each view whose scroll state changed (filled by the view itself, as
   * its own event would be: scrollY and maxScrollY for a ScrollView, from, to and item count for an AdapterView or
   * RecyclerView), then those of a window change. UiAutomator's clickAndWait(newWindow / windowChanged) return once
   * the action is done, and its scroll, fling and scrollUntil learn whether the end was reached from the scroll
   * event (Until.scrollFinished). Without it each scroll looked like nothing happened, and scrollUntil gave up after
   * five (UiAutomatorConformanceTest). Compose's scrolling reports no such state through its view, so it still does.
   * A filter that wants anything else times out.
   */
  @Implementation
  protected AccessibilityEvent executeAndWaitForEvent(Runnable command, UiAutomation.AccessibilityEventFilter filter,
      long timeoutMillis) throws TimeoutException {
    java.util.Map<View, String> before = onMain(ShadowSimUiAutomation::scrollStates);
    command.run();
    if (TestActivities.testRun()) {
      onMain(() -> {
        settle(500);
        return null;
      });
    } else {
      liveSettle(500);
    }
    List<AccessibilityEvent> scrolled = onMain(() -> {
      List<AccessibilityEvent> out = new ArrayList<>();
      for (java.util.Map.Entry<View, String> e : before.entrySet()) {
        AccessibilityEvent now = scrollEvent(e.getKey());
        if (e.getKey().isAttachedToWindow() && !scrollFields(now).equals(e.getValue())) out.add(now);
      }
      return out;
    });
    for (AccessibilityEvent e : scrolled) {
      if (filter.accept(e)) return e;
    }
    for (int type : new int[] {AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED}) {
      AccessibilityEvent e = new AccessibilityEvent(type);
      e.setEventTime(SystemClock.uptimeMillis());
      if (filter.accept(e)) return e;
    }
    throw new TimeoutException("apksim: no accessibility events; nothing matched after the action");
  }

  /** Each shown view of the reported windows that can scroll, with its scroll event's fields. Main thread. */
  private static java.util.Map<View, String> scrollStates() {
    java.util.Map<View, String> out = new java.util.LinkedHashMap<>();
    for (Win w : windows()) addScrollables(w.view(), out);
    return out;
  }

  private static void addScrollables(View v, java.util.Map<View, String> out) {
    if (v.getVisibility() != View.VISIBLE) return;
    if (v.canScrollVertically(1) || v.canScrollVertically(-1) || v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) {
      out.put(v, scrollFields(scrollEvent(v)));
    }
    if (v instanceof android.view.ViewGroup g) {
      for (int i = 0; i < g.getChildCount(); i++) addScrollables(g.getChildAt(i), out);
    }
  }

  /** The TYPE_VIEW_SCROLLED event the view would send now, as View.sendAccessibilityEvent fills it. */
  private static AccessibilityEvent scrollEvent(View v) {
    AccessibilityEvent e = new AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
    v.onInitializeAccessibilityEvent(e);
    e.setEventTime(SystemClock.uptimeMillis());
    return e;
  }

  private static String scrollFields(AccessibilityEvent e) {
    return e.getScrollX() + "," + e.getScrollY() + "," + e.getFromIndex() + "," + e.getToIndex() + "," + e.getItemCount();
  }

  @Implementation
  protected boolean performGlobalAction(int action) {
    if (action != AccessibilityService.GLOBAL_ACTION_BACK) {
      System.err.println("[apksim] UiAutomation.performGlobalAction(" + action + ") is not supported");
      return false;
    }
    if (!TestActivities.testRun()) return live(true, "back");
    long now = SystemClock.uptimeMillis();
    injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
    injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
    return true;
  }

  /**
   * A shell command, as the shell user would run it on a device: apksim's adb shell ({@link AdbShell}: getprop,
   * settings, logcat -c/-d, touch, ls, am, pm, ...) run in this process. It runs to the end before this returns, and
   * the descriptor reads what it printed: on a device the shell is another process and the test reads its output as
   * it comes, but here the test is usually on the main thread, which most commands need.
   */
  @Implementation
  protected ParcelFileDescriptor executeShellCommand(String command) {
    return output(AdbShell.runInProcess(command));
  }

  /** {@link #executeShellCommand} with a standard input, which nothing here reads: [output, input]. */
  @Implementation(minSdk = 31)
  protected ParcelFileDescriptor[] executeShellCommandRw(String command) {
    return new ParcelFileDescriptor[] {executeShellCommand(command), sink()};
  }

  /** {@link #executeShellCommandRw} with standard error, which the shell writes into its output: [out, in, err]. */
  @Implementation(minSdk = 31)
  protected ParcelFileDescriptor[] executeShellCommandRwe(String command) {
    return new ParcelFileDescriptor[] {executeShellCommand(command), sink(), output(new byte[0])};
  }

  private static ParcelFileDescriptor output(byte[] bytes) {
    try {
      File f = File.createTempFile("uiautomation", ".out");  // java.io.tmpdir: this instance's scratch
      java.nio.file.Files.write(f.toPath(), bytes);
      ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
      f.delete();  // the descriptor keeps it readable
      return pfd;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ParcelFileDescriptor sink() {
    try {
      return ParcelFileDescriptor.open(new File("/dev/null"), ParcelFileDescriptor.MODE_WRITE_ONLY);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- Input in a running instance -------------------------------------------------------------------------------

  /**
   * In a running instance, injected events go through the Host's input queue, as the window's and `adb shell input`'s
   * do, so they reach the window the Host would pick and pass its gesture tracking. One finger; keys on their down.
   */
  @Implementation
  @Override
  protected boolean injectInputEvent(android.view.InputEvent event, boolean sync) {
    // On the main thread (an instrumentation's onCreate, runOnMainSync) the Host loop that would take the event is
    // this thread, so the queue would never be served: dispatch directly, as in test runs.
    if (TestActivities.testRun() || Looper.myLooper() == Looper.getMainLooper()) return super.injectInputEvent(event, sync);
    if (event instanceof android.view.MotionEvent m) {
      int masked = m.getActionMasked();
      String action = masked == android.view.MotionEvent.ACTION_DOWN ? "down"
          : masked == android.view.MotionEvent.ACTION_MOVE ? "move"
          : masked == android.view.MotionEvent.ACTION_UP ? "up"
          : masked == android.view.MotionEvent.ACTION_CANCEL ? "cancel" : null;
      if (action == null) return false;
      return live(sync, "touch", action, String.valueOf(m.getRawX()), String.valueOf(m.getRawY()));
    }
    if (event instanceof KeyEvent k) {
      // The Host's key command makes a whole press and release, so the down carries it and the up is done.
      if (k.getAction() != KeyEvent.ACTION_DOWN) return true;
      if (k.getKeyCode() == KeyEvent.KEYCODE_BACK && k.getMetaState() == 0) return live(sync, "back");
      // HOME leaves the app (as `adb shell input keyevent HOME` does); the app never sees the key on a device.
      if (k.getKeyCode() == KeyEvent.KEYCODE_HOME) return live(sync, "home");
      List<String> c = new ArrayList<>(List.of("key", KeyEvent.keyCodeToString(k.getKeyCode()).substring("KEYCODE_".length())));
      if (k.isCtrlPressed()) c.add("ctrl");
      if (k.isShiftPressed()) c.add("shift");
      if (k.isAltPressed()) c.add("alt");
      if (k.isMetaPressed()) c.add("meta");
      return live(sync, c.toArray(new String[0]));
    }
    return false;
  }

  /** Queues input with the Host and, when SYNC, waits until the Host has dispatched it, as a device's injection does. */
  private static boolean live(boolean sync, String... command) {
    try {
      String r = AdbShell.control(command);
      if (r != null && (r.startsWith("error") || r.startsWith("unknown"))) return false;
      long end = System.nanoTime() + 5_000_000_000L;
      while (sync && System.nanoTime() < end && Boolean.TRUE.equals(onMain(Host::inputQueued))) Thread.sleep(4);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  // --- Rotation ----------------------------------------------------------------------------------------------------

  /**
   * UiDevice.setOrientation*: turns the display (width and height swap, the qualifiers say land or port) and delivers
   * the configuration change to every activity, which handles it or is recreated, as on a device.
   */
  @Implementation
  @Override
  protected boolean setRotation(int rotation) {
    if (rotation == UiAutomation.ROTATION_FREEZE_CURRENT || rotation == UiAutomation.ROTATION_UNFREEZE) return true;
    if (rotation < 0 || rotation > 3) return false;
    if (!TestActivities.testRun()) {
      // A running instance: the Host's device controls turn the display, so the window and renderer follow.
      if (Looper.myLooper() == Looper.getMainLooper()) return false;  // would wait on this thread's own loop
      return live(true, "rotate", rotation % 2 == 0 ? "portrait" : "landscape");
    }
    return onMain(() -> {
      Context app = RuntimeEnvironment.getApplication();
      Display display = ShadowDisplay.getDefaultDisplay();
      int from = display.getRotation();
      if (from == rotation) return true;
      Configuration before = new Configuration(app.getResources().getConfiguration());
      android.util.DisplayMetrics m = app.getResources().getDisplayMetrics();
      int w = m.widthPixels;
      int h = m.heightPixels;
      if ((from + rotation) % 2 == 1) {
        int t = w;
        w = h;
        h = t;
      }
      float d = m.density;
      // The stack before the change, hidden activities (stopped below the top) included: the controller stays, its
      // activity is replaced when it is recreated.
      java.util.Map<ActivityController<?>, Activity> was = new java.util.LinkedHashMap<>();
      java.util.Map<ActivityController<?>, Integer> states = new java.util.IdentityHashMap<>();
      java.util.Set<ActivityController<?>> shown = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (Activity a : activities()) {
        ActivityController<?> c = ReflectionHelpers.getField(Shadow.extract(a), "controller");
        if (c == null) continue;
        was.put(c, a);
        states.put(c, Host.state(a));
        View decor = a.getWindow().peekDecorView();
        if (decor != null && decor.getVisibility() == View.VISIBLE) shown.add(c);
      }
      // Robolectric's instrumentation recreates the activities ActivityScenario launched here, itself.
      RuntimeEnvironment.setQualifiers("+w" + Math.round(w / d) + "dp-h" + Math.round(h / d) + "dp-" + (w > h ? "land" : "port"));
      shadowOf(display).setRotation(rotation);
      Configuration config = new Configuration(app.getResources().getConfiguration());
      int changed = before.diff(config);
      if (changed != 0) {
        for (java.util.Map.Entry<ActivityController<?>, Activity> e : was.entrySet()) {
          ActivityController<?> c = e.getKey();
          if (c.get() == e.getValue()) c.configurationChange(config, app.getResources().getDisplayMetrics(), changed);
        }
        // Recreating left each activity resumed (whatever it was: a stopped or paused one too), with its window
        // detached (Robolectric's recreate) or added after the others. Each gets its state and window back, and the
        // windows their order (TestActivities reads the stack from it) and focus.
        List<Activity> order = new ArrayList<>();
        for (ActivityController<?> c : was.keySet()) {
          Host.moveTo(c, states.get(c));
          View decor = c.get().getWindow().peekDecorView();
          if (decor == null || decor.getViewRootImpl() == null) c.visible();
          Host.setWindowVisible(c.get(), shown.contains(c));
          order.add(c.get());
        }
        Host.windowsInOrder(order);
      }
      settle(100);
      return true;
    });
  }

  /** The activities that have a window, bottom to top, hidden ones (stopped below the top) included. */
  private static List<Activity> activities() {
    return TestActivities.withWindows();
  }

  // ------------------------------------------------------------------------------------------------------------------

  /** Runs on the main thread: the test's own thread here (Instrument), or posted there from another one. */
  private static <T> T onMain(Callable<T> work) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      try {
        return work.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    if (!TestActivities.testRun()) {
      // A running instance: the Host loop runs the main looper's messages.
      try {
        return AdbShell.onMain(work);
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    Object[] result = new Object[1];
    RuntimeException[] failed = new RuntimeException[1];
    ShadowInstrumentation.runOnMainSyncNoIdle(() -> {
      try {
        result[0] = work.call();
      } catch (RuntimeException e) {
        failed[0] = e;
      } catch (Exception e) {
        failed[0] = new IllegalStateException(e);
      }
    });
    if (failed[0] != null) throw failed[0];
    @SuppressWarnings("unchecked") T t = (T) result[0];
    return t;
  }

  /**
   * The transform from a window to the screen, as the accessibility service hands it to a window's direct connection:
   * AccessibilityInteractionController takes the window's position (AttachInfo's mWindowLeft/mWindowTop) off each node's
   * boundsInScreen and applies this matrix, which on a device moves it back by the window's frame. Robolectric's is the
   * identity, so every node of a window not at 0,0 (a centred dialog) reported bounds inside its window, and
   * UiAutomator clicked there.
   */
  @Implements(android.view.accessibility.AccessibilityManager.class)
  public static class ShadowSimAccessibilityManager extends org.robolectric.shadows.ShadowAccessibilityManager {
    @Implementation(minSdk = 33)
    @Override
    protected Object getWindowTransformationSpec(int windowId) {
      Object spec = super.getWindowTransformationSpec(windowId);
      ViewRootImpl root = null;
      synchronized (WINDOW_IDS) {
        for (java.util.Map.Entry<ViewRootImpl, Integer> e : WINDOW_IDS.entrySet()) {
          if (e.getValue() == windowId) root = e.getKey();
        }
      }
      if (root == null) return spec;
      Object attach = ReflectionHelpers.getField(root, "mAttachInfo");
      if (attach == null) return spec;
      int left = ReflectionHelpers.<Integer>getField(attach, "mWindowLeft");
      int top = ReflectionHelpers.<Integer>getField(attach, "mWindowTop");
      if (left == 0 && top == 0) return spec;
      android.graphics.Matrix m = new android.graphics.Matrix();
      m.setTranslate(left, top);
      float[] values = new float[9];
      m.getValues(values);
      ReflectionHelpers.setField(spec, "transformationMatrix", values);
      return spec;
    }
  }
}
