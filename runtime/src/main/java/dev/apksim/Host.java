package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.OnBackInvokedCallback;
import dev.apksim.gpu.TrackedRuntimeShader;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

/** The Android "main thread": launches the APK's launcher activity and pumps looper, input and frames. */
@RunWith(SimRunner.class)
@Config(sdk = 36) // Fixed, whatever the APK targets: it is the framework this runtime ships.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Host {
  private static final long IDLE_POLL_NANOS = 2_000_000L;
  private static final long FRAME_NANOS = 1_000_000_000L / 120;
  private static final long VSYNC_HZ = 120;
  private static final long MAX_CATCH_UP_MS = 50;
  private static final long VSYNC_SLACK_NANOS = 300_000L;
  /**
   * Each animation frame starts just after the display's own vsync (CADisplayLink), as Choreographer does on a device,
   * rather than on this process's 120 Hz grid. Falls back to the grid whenever the window gets no vsyncs (hidden, the
   * display asleep, headless). {@code -Dapksim.vsync=grid} keeps the grid. On a 120 Hz display (2026-11-03, 5
   * interleaved runs each) the grid drifted against the display's vsync: on the Shader tab a new frame reached 99-100%
   * of vsyncs in some runs and 69% in others (frames shown twice, others never shown); flings drew ~140 fps of which
   * 100-180 frames were replaced before being shown, 84-97% new. Following the display gave 98-99% and 97% in every run.
   */
  private static final boolean FOLLOW_DISPLAY = !"grid".equals(System.getProperty("apksim.vsync"));
  /** When display pacing is on: the System.nanoTime() of the vsync the next frame is for, or 0 to use the grid. */
  private long frameAtNanos;

  private final ArrayDeque<ActivityController<? extends Activity>> stack = new ArrayDeque<>();
  private Application app;
  private int w;
  private int h;
  private Bitmap bitmap;
  private HwRenderer hw;
  private DeviceControls controls;
  private long downTime;
  private long frames;
  private long looperNanos;
  private long windowStart = System.nanoTime();
  private long windowPresented;
  private View touchTarget;
  private int touchDx;
  private int touchDy;

  /** The running Host, for UiAutomation's synchronous injection (see {@link #inputQueued}). */
  private static volatile Host current;

  /** Whether scripted input is still waiting to be dispatched. Main thread only. */
  static boolean inputQueued() {
    Host h = current;
    return h != null && !h.pendingScript.isEmpty();
  }

  @Test
  public void run() throws Exception {
    current = this;
    Timing.mark("framework-ready");
    SimWindow.boostThread();
    RuntimeEnvironment.setQualifiers(System.getProperty("apksim.qualifiers"));
    DeviceControls.applyLaunchOptions();
    // By default Robolectric's vsync fires at once and advances the clock itself, which suits tests but
    // spins forever inside the looper on any endless animation. Paused mode fires only when the clock
    // passes the next frame, and this loop moves the clock in real time.
    ShadowChoreographer.setFrameDelay(Duration.ofMillis(1));
    ShadowChoreographer.setPaused(true);
    usePersistentDataDir();
    if (Boolean.getBoolean("apksim.logcat")) org.robolectric.shadows.ShadowLog.stream = System.err;
    // A phone or tablet has a status bar and gesture navigation, so the app gets their insets as on a device.
    if (Device.current().systemBars()) DeviceControls.applySystemUi(Device.current());
    Timing.detail("host-setup");
    app = RuntimeEnvironment.getApplication();
    Timing.detail("got-application");
    useDeviceGestureThresholds(app);
    HostNetworks.install(app);
    NetConnectivity.networksReplaced();  // --network-profile offline: the host's networks stay away until online
    dev.apksim.video.ShadowSimMediaCodec.install();
    w = app.getResources().getDisplayMetrics().widthPixels;
    h = app.getResources().getDisplayMetrics().heightPixels;
    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    hw = new HwRenderer(app, w, h);
    controls = new DeviceControls(app, stack, w, h, this::resizeDisplay);
    controls.showTitle();
    Timing.detail("controls");

    String launcher = System.getProperty("apksim.launcher");
    Intent intent = new Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(new ComponentName(System.getProperty("apksim.package"), launcher));
    start(launcher, intent);
    Timing.mark("activity-resumed");
    // (Not with --no-autofill: `apksim serve` instances must never sign anyone in with the owner's credentials.)
    Autofill autofill = Boolean.getBoolean("apksim.noAutofill") ? null
        : Autofill.load(Path.of(System.getProperty("apksim.cacheRoot")), System.getProperty("apksim.package"));

    ShadowLooper looper = shadowOf(Looper.getMainLooper());
    long lastInput = 0;
    long last = System.nanoTime();
    boolean firstFrame = true;
    boolean closed = false;
    while (!closed) {
      long start = System.nanoTime();
      // One event per 8 ms: a gesture needs time and looper turns between its events, or the app's
      // gesture detectors see no movement. Real mouse input rarely hits this limit.
      if (start - lastInput >= 8_000_000L) {
        SimWindow.Event e = pendingScript.isEmpty() ? SimWindow.poll() : pendingScript.poll();
        if (e != null) {
          lastInput = start;
          if (e.type() == SimWindow.COMMAND) {
            menuCommand((int) e.x());
          } else if (e.type() == SimWindow.RESIZE) {
            controls.windowResized((int) e.x(), (int) e.y(), e.value() != 0);
          } else if (e.type() == SimWindow.BACK && controls.backClosesKeyboard()) {
            // Back put the software keyboard away; the app does not see it.
          } else if (!controls.hidden()) {
            closed = dispatch(e);
          } else if (e.type() == SimWindow.UP) {
            controls.show();  // a click on the home or lock screen brings the app back
          } else {
            closed = e.type() == SimWindow.CLOSE;
          }
        }
      }
      if (autofill != null) autofill.tick(Host::rootViews);
      controls.tick();
      Control.Pending cmd;
      while ((cmd = Control.QUEUE.poll()) != null) {
        // Logged so a scripted session can be followed afterwards. Not each move of a drag (a browser sends one per
        // frame) or the redraws a stream asks for, and not what is typed (passwords, pasted tokens): only its length.
        String shown = cmd.args[0].equals("type") ? "type (" + (String.join(" ", cmd.args).length() - 5) + " characters)" : String.join(" ", cmd.args);
        if (!(cmd.args[0].equals("touch") && cmd.args.length > 1 && cmd.args[1].equals("move")) && !cmd.args[0].equals("redraw")) System.err.printf("[apksim] control %tT %s%n", System.currentTimeMillis(), shown);
        if (cmd.args[0].equals("settle")) {
          // Answered later, from the loop, once the UI is quiet (or it gives up).
          Settle next;
          try {
            next = new Settle(cmd);
          } catch (NumberFormatException ex) {
            cmd.finish("error: usage: settle [MAX_MS] [QUIET_MS] [HORIZON_MS]: " + ex.getMessage());
            continue;
          }
          if (settle != null) settle.pending.finish("error: replaced by another settle");
          settle = next;
          continue;
        }
        String reply;
        try {
          reply = runCommand(cmd.args);
        } catch (Exception ex) {
          // A bad command (or one the framework rejects) is an answer, not a crash of the app.
          ex.printStackTrace();
          reply = "error: " + ex;
        }
        cmd.finish(reply);
      }
      // Robolectric's looper clock moves in whole milliseconds and drops any fraction, so advance only
      // by whole milliseconds and leave the remainder for next time, or simulated time runs slow.
      long availableMs = (System.nanoTime() - last) / 1_000_000;
      long nowMs = SystemClock.uptimeMillis();
      long stepMs = availableMs;
      if (ShadowChoreographer.getNextVsyncTimeNanos() != 0) {
        if (frameAtNanos != 0) {
          // Following the display: the frame waits for its vsync, then the clock catches up to now.
          if (System.nanoTime() < frameAtNanos) stepMs = 0;
        } else {
          // Something is animating. Like a display's vsync, frames happen on a fixed grid, so while one
          // is wanted the clock moves only to grid lines: to the latest that has passed, dropping
          // any others, as a device does under load.
          long passed = lineAtOrBefore(nowMs + availableMs);
          stepMs = passed > nowMs ? passed - nowMs : 0;
          // The first frame does not wait for a grid line: nothing is on screen yet to keep in step with. The clock
          // runs up to one frame ahead of real time, and waits for it afterwards (last is then in the future).
          if (firstFrame && stepMs == 0) stepMs = lineAtOrBefore(nowMs) + 1000 / VSYNC_HZ + 1 - nowMs;
        }
      }
      long looperStart = System.nanoTime();
      if (availableMs > MAX_CATCH_UP_MS) {
        // A long stall: time spent stalled is not replayed.
        last += (availableMs - MAX_CATCH_UP_MS) * 1_000_000;
        stepMs = Math.min(stepMs, MAX_CATCH_UP_MS);
      }
      if (stepMs > 0) {
        last += stepMs * 1_000_000;
        looper.idleFor(Duration.ofMillis(stepMs));
      } else {
        looper.idle();
      }
      looperNanos += System.nanoTime() - looperStart;
      updateActivityStack();
      if (stack.isEmpty()) break;
      if (Snapshot.ON) focusTopWindow();
      List<View> roots = rootViews();
      if (!controls.hidden() && (firstFrame ? laidOut(roots) : anyDirty(roots))) {
        render(roots);
        if (firstFrame) {
          firstFrame = false;
          Timing.mark("first-frame");
          Standby.spawnNextSoon();
          String shot = System.getProperty("apksim.screenshot");
          if (shot != null) saveFrame(shot);
          if (Boolean.getBoolean("apksim.exitAfterFirstFrame")) {
            Timing.done();
            exit(0);
          }
        }
      }
      if (settle != null && settle.check(firstFrame ? "no first frame yet" : busy(looper, roots))) settle = null;
      if (!firstFrame || ShadowChoreographer.getNextVsyncTimeNanos() == 0) pace(last);
    }
    exit(0);
  }

  // ---------------------------------------------------------------- settle: the idle signal for screenshots

  /** The `settle` waiting for the UI to go quiet, if any. Main thread only. */
  private Settle settle;

  /**
   * `settle [MAX_MS] [QUIET_MS] [HORIZON_MS]`: answers once the UI has been quiet for QUIET_MS (default 200) of real
   * time, no frame rendered meanwhile, or with an error after MAX_MS (default 10000) saying what was still busy.
   * Quiet: see {@link #busy}. Messages due later than HORIZON_MS (default 1000) of simulated time do not count, so a
   * 5 s auto-dismiss does not hold a screenshot up. docs/snapshots.md.
   */
  private final class Settle {
    final Control.Pending pending;
    final long start = System.nanoTime();
    final long maxNanos;
    final long quietNanos;
    final long horizonMs;
    long quietSince;
    long framesAtQuiet;
    String reason = "";

    Settle(Control.Pending p) {
      pending = p;
      String[] a = p.args;
      maxNanos = (a.length > 1 ? Long.parseLong(a[1]) : 10_000) * 1_000_000L;
      quietNanos = (a.length > 2 ? Long.parseLong(a[2]) : 200) * 1_000_000L;
      horizonMs = a.length > 3 ? Long.parseLong(a[3]) : 1000;
    }

    /** Called every loop turn with what keeps the UI busy (null when nothing does); true once answered. */
    boolean check(String busy) {
      long now = System.nanoTime();
      if (busy == null && quietSince != 0 && frames != framesAtQuiet) busy = "a frame was drawn";
      if (busy != null) {
        reason = busy;
        quietSince = 0;
      } else if (quietSince == 0) {
        quietSince = now;
        framesAtQuiet = frames;
      } else if (now - quietSince >= quietNanos) {
        hw.withLatestFrame(b -> {});  // the last frame's raster has finished: `shot` reads it
        pending.finish("settled in " + (now - start) / 1_000_000 + " ms");
        return true;
      }
      if (now - start > maxNanos) {
        pending.finish("error: not settled after " + (now - start) / 1_000_000 + " ms: " + reason);
        return true;
      }
      return false;
    }
  }

  /** What keeps the UI from being quiet for a screenshot, or null. */
  private String busy(ShadowLooper looper, List<View> roots) {
    if (!pendingScript.isEmpty()) return "input queued";
    if (ShadowChoreographer.getNextVsyncTimeNanos() != 0) return "a frame is wanted";
    for (View r : roots) {
      if (r.isDirty() || r.isLayoutRequested()) return "a window needs drawing (" + r.getClass().getSimpleName() + ")";
    }
    if (!looper.isIdle()) return "the main looper has messages due";
    String due = dueMessage(SystemClock.uptimeMillis() + settle.horizonMs);
    return due == null ? null : "a message is due: " + due;
  }

  /**
   * The first message on the main queue due by [until] (uptime ms) that can change what is on screen, described, or
   * null. Some that never draw are skipped: ViewRootImpl's MSG_TOUCH_BOOST_TIMEOUT (39) and
   * MSG_CHECK_INVALIDATION_IDLE (40), frame-rate bookkeeping that reposts itself for a few seconds after touches and
   * drawing, and AnimationHandler's pauser, which pauses the animators of windows no longer visible a second after one
   * goes (every `restart`).
   */
  private static String dueMessage(long until) {
    try {
      android.os.Message m = ReflectionHelpers.getField(Looper.getMainLooper().getQueue(), "mMessages");
      long now = SystemClock.uptimeMillis();
      for (; m != null && m.getWhen() <= until; m = ReflectionHelpers.getField(m, "next")) {
        android.os.Handler target = m.getTarget();
        String handler = target == null ? "" : target.getClass().getName();
        if ((m.what == 39 || m.what == 40) && handler.equals("android.view.ViewRootImpl$ViewRootHandler")) continue;
        if (m.what == 2 && handler.equals("android.view.Choreographer$FrameHandler") && m.obj != null
            && m.obj.getClass().getName().startsWith("android.animation.AnimationHandler$$Lambda")) continue;
        Object cb = m.getCallback();
        return "in " + (m.getWhen() - now) + " ms, " + (target == null ? "barrier" : target.getClass().getName())
            + (cb == null ? " what=" + m.what : " " + cb.getClass().getName()) + (m.obj == null ? "" : " " + m.obj.getClass().getName());
      }
      return null;
    } catch (RuntimeException e) {
      return "unreadable queue: " + e;
    }
  }

  /**
   * `restart [URL]`: every activity finished and destroyed (ViewModels cleared), then the launcher activity started
   * again with no saved state, or with URL the app's activity for that ACTION_VIEW intent. App statics, the
   * Application and data persist. Screenshot tests start every screen this way, so none sees what another left.
   */
  private String restart(String url) throws Exception {
    String pkg = System.getProperty("apksim.package");
    String cls = System.getProperty("apksim.launcher");
    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    if (url != null) {
      // Resolved first: with no activity left the process would end.
      intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg);
      android.content.pm.ResolveInfo r = app.getPackageManager().resolveActivity(intent, 0);
      if (r == null || r.activityInfo == null) return "error: no activity in " + pkg + " handles " + url;
      cls = r.activityInfo.name;
    }
    if (controls.hidden()) controls.show();
    while (shadowOf(app).getNextStartedActivity() != null) {
      // Starts not yet acted on belong to the activities being dropped.
    }
    ActivityController<? extends Activity> top = stack.peek();
    if (top != null) top.pause().stop();
    while (!stack.isEmpty()) stack.pop().destroy();
    controls.results().clear();
    pendingScript.clear();
    intent.setComponent(new ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    start(cls, intent);
    return "ok, " + cls;
  }

  /**
   * --snapshot: the top activity's window has focus, as on a device, so a focused text field draws its caret (Compose
   * and Views draw one only in a focused window). Robolectric gives windows no focus, and running instances leave
   * it so for now; a screenshot should show what a device would.
   */
  private void focusTopWindow() {
    ActivityController<? extends Activity> top = stack.peek();
    if (top == null || controls.hidden()) return;
    View decor = top.get().getWindow().peekDecorView();
    if (decor != null && decor.getViewRootImpl() != null && !decor.hasWindowFocus()) top.windowFocusChanged(true);
  }

  /** The last line of the 120 Hz grid at or before this time. Lines fall on 8, 16, 25, 33... ms. */
  private static long lineAtOrBefore(long ms) {
    return (ms * VSYNC_HZ / 1000) * 1000 / VSYNC_HZ;
  }

  /** Sleeps until the next vsync (the display's, or a grid line) if a frame is wanted, or briefly if nothing is animating. */
  private void pace(long clockSynced) {
    long wait = IDLE_POLL_NANOS;
    long untilVsync = FOLLOW_DISPLAY && ShadowChoreographer.getNextVsyncTimeNanos() != 0 ? SimWindow.untilVsync() : -1;
    if (untilVsync >= 0) {
      // Just after the vsync, so the frame is ready well before the next one, where the screen shows it.
      long now = System.nanoTime();
      if (frameAtNanos == 0 || now >= frameAtNanos) frameAtNanos = now + untilVsync + VSYNC_SLACK_NANOS;
      wait = frameAtNanos - now;
    } else if (ShadowChoreographer.getNextVsyncTimeNanos() != 0) {
      frameAtNanos = 0;
      long nowMs = SystemClock.uptimeMillis();
      long nextLine = (nowMs * VSYNC_HZ / 1000 + 1) * 1000 / VSYNC_HZ + 1;
      wait = (nextLine - nowMs) * 1_000_000 - (System.nanoTime() - clockSynced);
    }
    if (wait > 50_000L) LockSupport.parkNanos(wait);
  }

  private final ArrayDeque<SimWindow.Event> pendingScript = new ArrayDeque<>();

  /** The app data slot this instance holds (`stats` reports it, so `apksim serve` can erase it afterwards). */
  private static Path dataDir;

  /** Swaps the temp "pkg-dataDir" Robolectric made for a link to a directory that outlives the process. */
  private static void usePersistentDataDir() throws Exception {
    String pkg = System.getProperty("apksim.package");
    Path data = Storage.dataDir(Path.of(System.getProperty("apksim.cacheRoot")), pkg);
    // --fresh: start as a just-installed app (as `ctl.py erase` would, but for the slot this instance holds).
    if (Boolean.getBoolean("apksim.fresh")) Storage.clearDataDir(data);
    dataDir = data;
    // apksim serve learns the slot from here (not from `stats`, which waits for the activity to start): it empties the
    // slot when the session ends, even if the app never got that far.
    if (System.getProperty("apksim.dataSlots") != null) {
      System.err.println("[apksim] data slot " + data + " pid " + ProcessHandle.current().pid());
    }
    Path link = RuntimeEnvironment.getTempDirectory().getBasePath().resolve(pkg + "-dataDir");
    Storage.deleteTree(link);
    Files.createSymbolicLink(link, data);
  }

  private static void exit(int code) {
    ClassCache.flush();
    Storage.deleteScratch();
    Standby.halt(code);
  }

  // ---------------------------------------------------------------- activities

  private void start(String className, Intent intent) throws Exception {
    Class<? extends Activity> cls = Class.forName(className, true, Host.class.getClassLoader()).asSubclass(Activity.class);
    ActivityController<? extends Activity> top = stack.peek();
    if (top != null) top.pause();
    ActivityController<? extends Activity> c = Robolectric.buildActivity(cls, intent);
    stack.push(c);
    c.setup();
    arrange(new ArrayList<>(stack), false);
  }

  /**
   * What ActivityThread does on stop and restart (updateVisibility): a stopped activity's window is hidden, not
   * removed. Robolectric's controller leaves it visible, so every activity below the top was drawn each frame: a
   * large app 4 activities deep drew at 3 fps.
   */
  static void setWindowVisible(Activity a, boolean visible) {
    android.view.Window w = a.getWindow();
    View decor = w == null ? null : w.peekDecorView();
    // Shown only if the app has not hidden it itself (Activity.setVisible(false)), as updateVisibility checks.
    if (decor != null && (!visible || (boolean) org.robolectric.util.ReflectionHelpers.getField(a, "mVisibleFromClient"))) {
      decor.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
      touchable(decor, visible);
    }
    // The activity's other windows (dialogs, popups) go with it, as the window manager hides an app token's windows.
    // Only those hidden here are shown again: a dialog the app hid (Dialog.hide) stays hidden.
    for (View r : rootViews()) {
      if (r == decor || TestActivities.activityOf(r) != a) continue;
      if (!visible && r.getVisibility() == View.VISIBLE) {
        r.setVisibility(View.INVISIBLE);
        touchable(r, false);
        HIDDEN_WITH_ACTIVITY.add(r);
      } else if (visible && HIDDEN_WITH_ACTIVITY.remove(r) && r.getVisibility() == View.INVISIBLE) {
        r.setVisibility(View.VISIBLE);
        touchable(r, true);
      }
    }
  }

  /** Dialog and popup windows hidden with their stopped activity, to show again with it. */
  private static final java.util.Set<View> HIDDEN_WITH_ACTIVITY = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
  /** Windows this class made untouchable, so that only its own flag is taken off again. */
  private static final java.util.Set<View> MADE_UNTOUCHABLE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  /**
   * A hidden window takes no touches. Host's dispatch skips invisible windows itself; Robolectric's UiAutomation
   * injection (test runs) only skips windows flagged FLAG_NOT_TOUCHABLE, and ranks a dialog above every activity
   * window, so a stopped activity's dialog took the touches meant for the activity on top.
   */
  private static void touchable(View root, boolean on) {
    if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams lp)) return;
    if (!on && (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
      lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
      MADE_UNTOUCHABLE.add(root);
    } else if (on && MADE_UNTOUCHABLE.remove(root)) {
      lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }
  }

  /**
   * An activity whose window lets the ones below show (windowIsTranslucent, or windowIsFloating as a dialog-themed
   * one): on a device the activity below then stays paused and visible, not stopped.
   */
  static boolean seeThrough(Activity a) {
    for (int attr : new int[] {android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating}) {
      android.content.res.TypedArray t = a.getTheme().obtainStyledAttributes(new int[] {attr});
      try {
        if (t.getBoolean(0, false)) return true;
      } finally {
        t.recycle();
      }
    }
    return false;
  }

  /**
   * Puts the stack (top first) where a device's activity manager would have it after a start, a finish, a
   * configuration change, a rebuild, or the app leaving or coming back: the top resumed (unless {@code backgrounded},
   * the home or lock screen); below it, while everything above is see-through (translucent or floating), paused and
   * visible; every other one stopped, its windows hidden. A visible activity that has no window (recreated or rebuilt
   * while it was not) gets one, and the windows are put in the stack's order, which is the order they are drawn,
   * touched and found in; in test runs the top visible window has the focus. Each path used to fix up only its own
   * part: a rotation left the whole stack resumed, a rebuilt stack's revealed windows were added above the top one.
   */
  static void arrange(List<? extends ActivityController<?>> topFirst, boolean backgrounded) {
    arrange(topFirst, backgrounded, false);
  }

  /**
   * {@code onlyUp}: activities are only brought up (stopped to paused or resumed, paused to resumed), never down:
   * what a finish needs in a test run, whose activities a test may have launched side by side with ActivityScenario.
   */
  static void arrange(List<? extends ActivityController<?>> topFirst, boolean backgrounded, boolean onlyUp) {
    boolean shown = !backgrounded;
    boolean first = true;
    List<Activity> order = new ArrayList<>();
    for (ActivityController<?> c : topFirst) {
      Activity a = c.get();
      if (a.isFinishing() || a.isDestroyed()) continue;  // finish() ends those
      order.add(0, a);
      int target = !shown ? 0 : first ? 2 : 1;
      first = false;
      int now = state(a);
      if (onlyUp && target <= now) {
        if (shown) shown = seeThrough(a);
        continue;
      }
      moveTo(c, target);
      if (shown) {
        View decor = a.getWindow().peekDecorView();
        if (decor == null || decor.getViewRootImpl() == null) c.visible();
        setWindowVisible(a, true);
        shown = seeThrough(a);
      } else {
        setWindowVisible(a, false);
      }
    }
    windowsInOrder(order);
  }

  /** The activities' windows (bottom first) in that order, and in test runs the focus on the top visible one. */
  static void windowsInOrder(List<Activity> bottomFirst) {
    ShadowSimWindowManagerImpl.reorder(v -> bottomFirst.indexOf(TestActivities.activityOf(v)));
    if (TestActivities.testRun()) ShadowSimWindowManagerImpl.focusTop();
  }

  /** Stopped (0), paused (1) or resumed (2). */
  static int state(Activity a) {
    return a.isResumed() ? 2 : isStopped(a) ? 0 : 1;
  }

  /**
   * Moves an activity to stopped (0), paused (1) or resumed (2) along the path a device takes (TransactionExecutor:
   * stopped to paused is restart, start, resume, pause).
   */
  static void moveTo(ActivityController<?> c, int target) {
    Activity a = c.get();
    boolean stopped = isStopped(a);
    if (target == 0) {
      if (a.isResumed()) c.pause();
      if (!stopped) c.stop();
      return;
    }
    if (stopped) c.restart();
    if (!a.isResumed() && (stopped || target == 2)) c.resume();
    if (target == 1 && a.isResumed()) c.pause();
  }

  static boolean isStopped(Activity a) {
    return org.robolectric.util.ReflectionHelpers.getField(a, "mStopped");
  }

  /**
   * Robolectric's ViewConfiguration uses older fixed slops (16 dp touch slop where Android 16 has 8 dp), so a drag
   * scrolled 16 px less than the same drag on a device. Use the framework's own values. Instances are cached per
   * density, so fixing the one for this display covers every context. Test runs (SimRunner) do the same.
   */
  static void useDeviceGestureThresholds(android.content.Context app) throws Exception {
    android.content.res.Resources res = app.getResources();
    Class<?> dimen = Class.forName("com.android.internal.R$dimen");
    int touch = res.getDimensionPixelSize(dimen.getField("config_viewConfigurationTouchSlop").getInt(null));
    Object shadow = org.robolectric.shadow.api.Shadow.extract(android.view.ViewConfiguration.get(app));
    ReflectionHelpers.setField(shadow, "touchSlop", touch);
    ReflectionHelpers.setField(shadow, "pagingTouchSlop", touch * 2);
  }

  /** Honours startActivity() and finish(), which the framework leaves to the system server. */
  private void updateActivityStack() throws Exception {
    Intent next;
    ActivityResults results = controls.results();
    while ((next = shadowOf(app).getNextStartedActivity()) != null) {
      Activity requester = stack.isEmpty() ? null : stack.peek().get();
      boolean forResult = results.claim(requester, next);
      ComponentName c = next.getComponent();
      if (c == null) {
        android.content.pm.ResolveInfo r = app.getPackageManager().resolveActivity(next, 0);
        if (r == null || r.activityInfo == null) {
          if (forResult) {
            results.outside(requester, next);  // a picker, or cancelled
          } else if (!"android.content.pm.action.REQUEST_PERMISSIONS".equals(next.getAction()) && !DeviceControls.openOnHost(next)) {
            // (Permission requests go to a system activity that is not here; Permissions answers them instead.)
            System.err.println("[apksim] no activity for " + next);
          }
          continue;
        }
        c = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
      }
      if (!c.getPackageName().equals(System.getProperty("apksim.package"))) {
        if (forResult) {
          results.outside(requester, next);
        } else if (!DeviceControls.openOnHost(next)) {
          System.err.println("[apksim] intent leaves the app, ignored: " + next);
        }
        continue;
      }
      start(c.getClassName(), next);
      if (forResult) results.started(stack.peek(), requester, next);
    }
    while (!stack.isEmpty() && stack.peek().get().isFinishing()) {
      ActivityController<? extends Activity> done = stack.pop();
      done.pause().stop().destroy();
      results.finished(done.get());  // its setResult, delivered before the requester resumes
      ActivityController<? extends Activity> below = stack.peek();
      if (below != null) arrange(new ArrayList<>(stack), false);
    }
    // An activity below the top that finished (startActivity(next) then finish(), the usual hand-off) is destroyed
    // too, as the system server would. It stayed stopped instead, its window up and its ViewModels alive.
    for (java.util.Iterator<ActivityController<? extends Activity>> it = stack.iterator(); it.hasNext(); ) {
      ActivityController<? extends Activity> c = it.next();
      if (c == stack.peek() || !c.get().isFinishing()) continue;
      it.remove();
      c.destroy();
      results.finished(c.get());
    }
  }

  // ---------------------------------------------------------------- input

  @SuppressWarnings("unchecked")
  static List<View> rootViews() {
    try {
      return new ArrayList<>((List<View>) Views.FIELD.get(WindowManagerGlobal.getInstance()));
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /** WindowManagerGlobal.mViews, looked up once: rootViews() runs on every loop turn. */
  private static final class Views {
    static final java.lang.reflect.Field FIELD;

    static {
      try {
        FIELD = WindowManagerGlobal.class.getDeclaredField("mViews");
        FIELD.setAccessible(true);
      } catch (NoSuchFieldException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private boolean dispatch(SimWindow.Event e) {
    long now = SystemClock.uptimeMillis();
    switch (e.type()) {
      case SimWindow.DOWN, SimWindow.MOVE, SimWindow.UP, SimWindow.CANCEL -> dispatchTouch(e, now);
      case SimWindow.TOUCH2_CENTRE, SimWindow.TOUCH2_DOWN, SimWindow.TOUCH2_MOVE, SimWindow.TOUCH2_UP -> dispatchTouch2(e, now);
      case SimWindow.WHEEL -> dispatchWheel(e, now);
      case SimWindow.BACK -> {
        if (!dispatchPredictiveBack()) sendKey(KeyEvent.KEYCODE_BACK, 0, now);
      }
      case SimWindow.KEY -> sendKey((int) e.value(), (int) e.x(), now);
      case SimWindow.CHAR -> sendChar((char) e.value(), now);
      case SimWindow.CLOSE -> {
        return true;
      }
      default -> {
      }
    }
    return false;
  }

  /** Apps with predictive back never see the key: ViewRootImpl hands it to their top callback. */
  private boolean dispatchPredictiveBack() {
    List<View> roots = rootViews();
    if (roots.isEmpty()) return false;
    ViewRootImpl root = roots.get(roots.size() - 1).getViewRootImpl();
    if (root == null) return false;
    Object dispatcher = root.getOnBackInvokedDispatcher();
    OnBackInvokedCallback top = ReflectionHelpers.callInstanceMethod(dispatcher, "getTopCallback");
    if (top == null) return false;
    top.onBackInvoked();
    return true;
  }

  private void dispatchTouch(SimWindow.Event e, long now) {
    if (e.type() == SimWindow.DOWN) {
      downTime = now;
      List<View> roots = rootViews();
      touchTarget = roots.isEmpty() ? null : roots.get(roots.size() - 1);
      for (int i = roots.size() - 1; i >= 0; i--) {
        View r = roots.get(i);
        // A hidden window (a stopped activity's) is not drawn, so it takes no touches either.
        if (r.getVisibility() != View.VISIBLE || r.getWidth() == 0 || r.getHeight() == 0) continue;
        int[] at = position(r);
        boolean inside = e.x() >= at[0] && e.x() < at[0] + r.getWidth() && e.y() >= at[1] && e.y() < at[1] + r.getHeight();
        touchTarget = r;
        if (inside || !isTouchThrough(r)) break;
      }
      int[] at = position(touchTarget);
      touchDx = at[0];
      touchDy = at[1];
    }
    if (touchTarget == null) return;
    int action = e.type() == SimWindow.CANCEL ? MotionEvent.ACTION_CANCEL : e.type();
    MotionEvent m = MotionEvent.obtain(downTime, now, action, e.x() - touchDx, e.y() - touchDy, 0);
    m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    touchTarget.dispatchTouchEvent(m);
    m.recycle();
    if (e.type() == SimWindow.UP || e.type() == SimWindow.CANCEL) touchTarget = null;
  }

  private static boolean isTouchThrough(View root) {
    ViewGroup.LayoutParams lp = root.getLayoutParams();
    return lp instanceof WindowManager.LayoutParams wl
        && (wl.flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)) != 0;
  }

  // Two fingers (Option-drag, trackpad pinch and rotate, the `pinch` command): the first finger follows the event,
  // the second mirrors it around a centre, or with value 1 keeps its offset so both move together.
  private float centreX;
  private float centreY;
  private float offsetX;
  private float offsetY;
  private boolean parallel;

  private void dispatchTouch2(SimWindow.Event e, long now) {
    if (e.type() == SimWindow.TOUCH2_CENTRE) {
      centreX = e.x();
      centreY = e.y();
      return;
    }
    float x1 = e.x();
    float y1 = e.y();
    if (e.type() == SimWindow.TOUCH2_DOWN) {
      parallel = e.value() == 1;
      offsetX = 2 * (centreX - x1);
      offsetY = 2 * (centreY - y1);
    }
    float x2 = parallel ? x1 + offsetX : 2 * centreX - x1;
    float y2 = parallel ? y1 + offsetY : 2 * centreY - y1;
    if (e.type() == SimWindow.TOUCH2_DOWN) {
      dispatchTouch(new SimWindow.Event(SimWindow.DOWN, x1, y1, 0), now);
      if (touchTarget != null) touchTarget.dispatchTouchEvent(twoFingers(MotionEvent.ACTION_POINTER_DOWN, now, x1, y1, x2, y2));
    } else if (touchTarget != null && e.type() == SimWindow.TOUCH2_MOVE) {
      touchTarget.dispatchTouchEvent(twoFingers(MotionEvent.ACTION_MOVE, now, x1, y1, x2, y2));
    } else if (touchTarget != null) {
      touchTarget.dispatchTouchEvent(twoFingers(MotionEvent.ACTION_POINTER_UP, now, x1, y1, x2, y2));
      dispatchTouch(new SimWindow.Event(SimWindow.UP, x1, y1, 0), now);
    }
  }

  private MotionEvent twoFingers(int action, long now, float x1, float y1, float x2, float y2) {
    MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
    float[][] at = {{x1, y1}, {x2, y2}};
    for (int i = 0; i < 2; i++) {
      props[i] = new MotionEvent.PointerProperties();
      props[i].id = i;
      props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = at[i][0] - touchDx;
      coords[i].y = at[i][1] - touchDy;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    int masked = action == MotionEvent.ACTION_MOVE ? action : action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    MotionEvent m = MotionEvent.obtain(downTime, now, masked, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    return m;
  }

  private void dispatchWheel(SimWindow.Event e, long now) {
    MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
    props.id = 0;
    props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
    coords.x = e.x();
    coords.y = e.y();
    coords.setAxisValue(MotionEvent.AXIS_VSCROLL, e.value());
    MotionEvent m = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
        new MotionEvent.PointerProperties[] {props}, new MotionEvent.PointerCoords[] {coords},
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    List<View> roots = rootViews();
    if (!roots.isEmpty()) roots.get(roots.size() - 1).dispatchGenericMotionEvent(m);
    m.recycle();
  }

  /** A raw key press; the window sends meta 0, and the `key` command can add modifiers. */
  private void sendKey(int code, int meta, long now) {
    List<View> roots = rootViews();
    if (roots.isEmpty()) return;
    View root = roots.get(roots.size() - 1);
    // As a hardware keyboard sends a shortcut: each modifier pressed first and released last, and every event
    // marked as coming from a keyboard. Compose ignores Ctrl+A and the like without that.
    int[] modifiers = {KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT};
    int[] masks = {KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_META_ON};
    int held = 0;
    for (int i = 0; i < modifiers.length; i++) {
      if ((meta & masks[i]) == 0) continue;
      held |= masks[i] | metaSide(masks[i]);
      root.dispatchKeyEvent(keyboardEvent(now, KeyEvent.ACTION_DOWN, modifiers[i], held));
    }
    root.dispatchKeyEvent(keyboardEvent(now, KeyEvent.ACTION_DOWN, code, meta));
    root.dispatchKeyEvent(keyboardEvent(now, KeyEvent.ACTION_UP, code, meta));
    for (int i = modifiers.length - 1; i >= 0; i--) {
      if ((meta & masks[i]) == 0) continue;
      held &= ~(masks[i] | metaSide(masks[i]));
      root.dispatchKeyEvent(keyboardEvent(now, KeyEvent.ACTION_UP, modifiers[i], held));
    }
  }

  private static int metaSide(int mask) {
    return mask == KeyEvent.META_CTRL_ON ? KeyEvent.META_CTRL_LEFT_ON : mask == KeyEvent.META_SHIFT_ON ? KeyEvent.META_SHIFT_LEFT_ON
        : mask == KeyEvent.META_ALT_ON ? KeyEvent.META_ALT_LEFT_ON : KeyEvent.META_META_LEFT_ON;
  }

  private static KeyEvent keyboardEvent(long now, int action, int code, int meta) {
    return new KeyEvent(now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
  }

  private void sendChar(char ch, long now) {
    List<View> roots = rootViews();
    if (roots.isEmpty()) return;
    View root = roots.get(roots.size() - 1);
    KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(new char[] {ch});
    if (events != null) {
      for (KeyEvent k : events) root.dispatchKeyEvent(k);
    }
  }

  // ---------------------------------------------------------------- rendering

  private static boolean laidOut(List<View> roots) {
    return !roots.isEmpty() && roots.get(0).getWidth() > 0 && !roots.get(0).isLayoutRequested();
  }

  private static boolean anyDirty(List<View> roots) {
    for (View r : roots) {
      if (r.isDirty()) return true;
    }
    return false;
  }

  /** Where a window sits on screen, from its layout params (the window manager's job on a device). */
  private int[] position(View root) {
    int x = 0;
    int y = 0;
    if (root.getLayoutParams() instanceof WindowManager.LayoutParams lp) {
      int rw = root.getWidth();
      int rh = root.getHeight();
      int hg = Gravity.getAbsoluteGravity(lp.gravity, root.getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
      int vg = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
      x = hg == Gravity.CENTER_HORIZONTAL ? (w - rw) / 2 + lp.x : hg == Gravity.RIGHT ? w - rw - lp.x : lp.x;
      y = vg == Gravity.CENTER_VERTICAL ? (h - rh) / 2 + lp.y : vg == Gravity.BOTTOM ? h - rh - lp.y : lp.y;
    }
    return new int[] {x, y};
  }

  private void render(List<View> roots) {
    hw.draw(roots, this::position);
    frames++;
  }

  /** The display changed size (rotation, resizing, another device): the renderer, the window and screenshots follow. */
  private void resizeDisplay(int newW, int newH, boolean actualSize) {
    w = newW;
    h = newH;
    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    hw.resize(w, h, actualSize);
  }

  private void saveFrame(String path) throws Exception {
    hw.withLatestFrame(pixels -> {
      pixels.rewind();
      bitmap.copyPixelsFromBuffer(pixels);
      pixels.rewind();
    });
    try (FileOutputStream out = new FileOutputStream(path)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
  }

  // ---------------------------------------------------------------- control channel

  /** A Mac menu item (Device, Features, Debug...): the same as its control command. */
  private void menuCommand(int tag) {
    String cmd = DeviceControls.menuCommand(tag);
    if (cmd == null) return;
    try {
      System.err.println("[apksim] menu: " + cmd + " -> " + runCommand(cmd.split(" ")));
    } catch (Exception ex) {
      System.err.println("[apksim] menu: " + cmd + " failed: " + ex);
    }
  }

  /**
   * A control command from code already on the main thread (UiAutomation.executeShellCommand from a test or app
   * callback), which cannot wait for the loop to take it from Control.QUEUE. Null when no Host is running.
   */
  static String runOnMain(String[] a) throws Exception {
    Host h = current;
    return h == null ? null : h.runCommand(a);
  }

  private String runCommand(String[] a) throws Exception {
    switch (a[0]) {
      case "tap" -> {
        float x = Float.parseFloat(a[1]);
        float y = Float.parseFloat(a[2]);
        pendingScript.add(new SimWindow.Event(SimWindow.DOWN, x, y, 0));
        pendingScript.add(new SimWindow.Event(SimWindow.UP, x, y, 0));
      }
      case "swipe" -> {
        float x1 = Float.parseFloat(a[1]);
        float y1 = Float.parseFloat(a[2]);
        float x2 = Float.parseFloat(a[3]);
        float y2 = Float.parseFloat(a[4]);
        // swipe X1 Y1 X2 Y2 [MS]: with a duration, a slow drag that holds still before lifting, so it does not fling.
        int steps = a.length > 5 ? Math.max(12, Integer.parseInt(a[5]) / 8) : 12;
        pendingScript.add(new SimWindow.Event(SimWindow.DOWN, x1, y1, 0));
        for (int i = 1; i <= steps; i++) {
          pendingScript.add(new SimWindow.Event(SimWindow.MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, 0));
        }
        for (int i = 0; a.length > 5 && i < 15; i++) pendingScript.add(new SimWindow.Event(SimWindow.MOVE, x2, y2, 0));
        pendingScript.add(new SimWindow.Event(SimWindow.UP, x2, y2, 0));
      }
      case "touch" -> {
        // touch down|move|up|cancel X Y: one finger's event, for input that arrives as separate events
        // (UiAutomation's injectInputEvent from an instrumentation started over adb).
        int type = switch (a[1]) {
          case "down" -> SimWindow.DOWN;
          case "move" -> SimWindow.MOVE;
          case "up" -> SimWindow.UP;
          case "cancel" -> SimWindow.CANCEL;
          default -> throw new IllegalArgumentException("touch down|move|up|cancel X Y");
        };
        pendingScript.add(new SimWindow.Event(type, Float.parseFloat(a[2]), Float.parseFloat(a[3]), 0));
      }
      case "wheel" -> pendingScript.add(new SimWindow.Event(SimWindow.WHEEL, Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3])));
      case "type" -> {
        for (char ch : String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).toCharArray()) {
          pendingScript.add(new SimWindow.Event(SimWindow.CHAR, 0, 0, ch));
        }
      }
      case "back" -> pendingScript.add(new SimWindow.Event(SimWindow.BACK, 0, 0, 0));
      case "pinch" -> {
        // pinch CX CY FROM TO [ms]: two fingers FROM px apart, moving to TO px apart around CX,CY (zoom in: TO > FROM).
        float cx = Float.parseFloat(a[1]);
        float cy = Float.parseFloat(a[2]);
        float from = Float.parseFloat(a[3]);
        float to = Float.parseFloat(a[4]);
        int steps = Math.max(2, (a.length > 5 ? Integer.parseInt(a[5]) : 300) / 8);
        pendingScript.add(new SimWindow.Event(SimWindow.TOUCH2_CENTRE, cx, cy, 0));
        pendingScript.add(new SimWindow.Event(SimWindow.TOUCH2_DOWN, cx - from / 2, cy, 0));
        for (int i = 1; i <= steps; i++) {
          float d = from + (to - from) * i / steps;
          pendingScript.add(new SimWindow.Event(SimWindow.TOUCH2_MOVE, cx - d / 2, cy, 0));
        }
        pendingScript.add(new SimWindow.Event(SimWindow.TOUCH2_UP, cx - to / 2, cy, 0));
      }
      case "key" -> {
        // key NAME [COUNT] [ctrl|shift|alt|meta ...]: a raw key, such as DEL, MOVE_END or A with ctrl.
        int code = KeyEvent.keyCodeFromString("KEYCODE_" + a[1].toUpperCase());
        if (code == KeyEvent.KEYCODE_UNKNOWN) return "unknown key: " + a[1];
        int count = 1;
        int meta = 0;
        for (int i = 2; i < a.length; i++) {
          switch (a[i]) {
            case "ctrl" -> meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case "shift" -> meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case "alt" -> meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case "meta" -> meta |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            default -> count = Integer.parseInt(a[i]);
          }
        }
        for (int i = 0; i < count; i++) pendingScript.add(new SimWindow.Event(SimWindow.KEY, meta, 0, code));
      }
      case "paste" -> {
        // Queued with the other scripted input rather than through the window, so a tap sent after a paste
        // lands after the last pasted character (a sign-in's Connect after its password).
        String text = SimWindow.clipboardText();
        int n = 0;
        for (char ch : text == null ? new char[0] : text.toCharArray()) {
          if (ch == '\n' || ch == '\r') continue;  // A copied password often carries a trailing newline.
          pendingScript.add(new SimWindow.Event(SimWindow.CHAR, 0, 0, ch));
          n++;
        }
        return "pasted " + n + " characters";
      }
      case "shot" -> saveFrame(a[1]);
      case "restart" -> {
        return restart(a.length > 1 ? a[1] : null);
      }
      case "diff" -> {
        // diff GOLDEN.png ACTUAL.png [MAP.png] [DELTA]: how many pixels differ by more than DELTA in any channel.
        return ImageDiff.compare(a[1], a[2], a.length > 3 ? a[3] : null, a.length > 4 ? Integer.parseInt(a[4]) : 0);
      }
      case "wshot" -> SimWindow.windowSnapshot(a[1]);
      case "quit" -> exit(0);
      case "ping" -> {
        return "pong";
      }
      case "activity" -> {
        return stack.isEmpty() ? "none" : stack.peek().get().getClass().getName();
      }
      case "windows" -> {
        StringBuilder b = new StringBuilder();
        for (View r : rootViews()) b.append(ViewTree.window(r, position(r))).append("; ");
        return b.toString();
      }
      case "viewtree" -> {
        // viewtree [FILE]: every view of every window, one per line (ViewTree). A reply is one line, so it joins them
        // with "; " (indentation kept); FILE gets them as lines.
        String tree = ViewTree.dump(rootViews(), this::position);
        if (a.length < 2) return tree.strip().replace("\n", "; ");
        Files.writeString(Storage.absolute(a[1]), tree);
        return "ok, " + tree.lines().count() + " lines in " + Storage.absolute(a[1]);
      }
      case "selftest" -> {
        // selftest [file]: libwebrtc's native scaler (19 arguments: stack arguments must be translated; needs an APK with
        // libwebrtc), and with a file, MediaCodec as media players use it: H.264 (.h264) or HEVC (.h265) to a Surface,
        // AAC (.aac, ADTS) or MP3 (.mp3) through AudioTrack to the speakers.
        String r;
        try {
          r = dev.apksim.video.SelfTest.scale();
        } catch (ReflectiveOperationException | LinkageError e) {
          r = "scale: no libwebrtc in this app";
        }
        r += " | " + dev.apksim.video.SelfTest.colour();
        if (a.length > 1) r += " | " + dev.apksim.video.SelfTest.file(app, a[1]);
        return r;
      }
      case "tap-text" -> {
        // tap-text TEXT: a tap at the centre of the first element showing TEXT (or described by it).
        String text = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        int[] at = Nodes.centreOf(rootViews(), text);
        if (at == null) return "not found: " + text;
        pendingScript.add(new SimWindow.Event(SimWindow.DOWN, at[0], at[1], 0));
        pendingScript.add(new SimWindow.Event(SimWindow.UP, at[0], at[1], 0));
        return "tapped " + at[0] + "," + at[1];
      }
      case "nodes" -> {
        return Nodes.dump(rootViews());
      }
      case "logcat" -> {
        // logcat on|off: the app's android.util.Log output to this process's stderr (Robolectric drops it otherwise).
        org.robolectric.shadows.ShadowLog.stream = a.length > 1 && a[1].equals("off") ? null : System.err;
        return "ok";
      }
      case "video" -> {
        return dev.apksim.video.VideoSurfaces.report();
      }
      case "webrtclog" -> {
        // libwebrtc's own log (ICE, networks, codecs) to stderr: webrtclog [verbose|info|warning|error].
        Class<?> logging = Class.forName("org.webrtc.Logging", true, Host.class.getClassLoader());
        Class<?> severity = Class.forName("org.webrtc.Logging$Severity", true, Host.class.getClassLoader());
        Object level = severity.getField("LS_" + (a.length > 1 ? a[1] : "info").toUpperCase()).get(null);
        logging.getMethod("enableLogToDebugOutput", severity).invoke(null, level);
        return "ok";
      }
      case "slop" -> {
        android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(app);
        return "touch=" + vc.getScaledTouchSlop() + " paging=" + vc.getScaledPagingTouchSlop() + " doubleTap=" + vc.getScaledDoubleTapSlop()
            + " minFling=" + vc.getScaledMinimumFlingVelocity() + " maxFling=" + vc.getScaledMaximumFlingVelocity();
      }
      case "insets" -> {
        // What the app sees: system bar insets as delivered to each root view.
        StringBuilder b = new StringBuilder();
        for (View r : rootViews()) {
          android.view.WindowInsets wi = r.getRootWindowInsets();
          b.append(r.getClass().getSimpleName()).append(' ').append(wi == null ? "null"
              : wi.getInsets(android.view.WindowInsets.Type.systemBars()) + " ime=" + wi.getInsets(android.view.WindowInsets.Type.ime()))
              .append("; ");
        }
        return b.toString();
      }
      case "frames" -> {
        return hw.frameIntervals() + " | " + SimWindow.vsyncStats();
      }
      case "display" -> {
        return SimWindow.displayInfo();
      }
      case "shaders" -> {
        return TrackedRuntimeShader.report() + " effects: runtime=" + dev.apksim.gpu.GpuEffects.runtimeEffects + " blur=" + dev.apksim.gpu.GpuEffects.otherEffects;
      }
      case "fps" -> {
        // Frames that reached the window since the last `fps`, and what each stage cost on average.
        long now = System.nanoTime();
        double secs = (now - windowStart) / 1e9;
        long shown = hw.presented - windowPresented;
        double n = Math.max(1, shown) * 1e6;
        String r = String.format("fps=%.1f frames=%d overBudget=%d | main: looper=%.2f record=%.2f (gpu=%.2f: wait=%.2f copy=%.2f) syncWait=%.2f | raster=%.2f present=%.2f",
            shown / secs, shown, hw.overBudget, looperNanos / n, hw.recordNanos / n, TrackedRuntimeShader.renderNanos / n,
            TrackedRuntimeShader.collectNanos / n, TrackedRuntimeShader.publishNanos / n,
            hw.syncWaitNanos / n, hw.renderNanos / n, hw.presentNanos / n);
        windowStart = now;
        windowPresented = hw.presented;
        hw.overBudget = 0;
        hw.recordNanos = 0;
        hw.renderNanos = 0;
        hw.presentNanos = 0;
        hw.syncWaitNanos = 0;
        looperNanos = 0;
        TrackedRuntimeShader.renderNanos = 0;
        TrackedRuntimeShader.collectNanos = 0;
        TrackedRuntimeShader.publishNanos = 0;
        return r;
      }
      case "stats" -> {
        Runtime rt = Runtime.getRuntime();
        return String.format("frames=%d heapUsedMb=%d pid=%d data=%s", frames, (rt.totalMemory() - rt.freeMemory()) >> 20,
            ProcessHandle.current().pid(), dataDir);
      }
      case "redraw" -> {
        // Draws a frame now even if nothing changed (a frame stream that just started needs the current screen).
        for (View r : rootViews()) r.invalidate();
      }
      default -> {
        String r = controls.run(a);
        return r != null ? r : "unknown command: " + a[0];
      }
    }
    return "ok";
  }
}
