package dev.apksim;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRootImpl;
import org.robolectric.shadows.ShadowWindowManagerGlobal;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Touch mode as a device's window manager keeps it (issue 424). A handheld's windows start in touch mode
 * (config_defaultInTouchMode) and stay there while the user touches the screen; a navigation or typing key leaves it,
 * the next touch enters it again. Nothing changed it here:
 * Host hands input straight to the views, past ViewRootImpl's input stages, which is where a device switches. Out of
 * touch mode the framework gives a window an initially focused view. Robolectric's window session already starts in
 * touch mode here (real graphics); what was missing is the leaving and re-entering, a TV's start, and the framework's
 * "always assign focus" compatibility flag following the app rather than the process's first window ({@link #start}).
 *
 * <p>The mode is the display's: the window session's answer for windows added later (ADD_FLAG_IN_TOUCH_MODE), and
 * every window already up is told, as the window manager's touchModeChanged tells them. A device without a
 * touchscreen (TV) starts out of touch mode. {@code -Dapksim.touchMode=false} leaves Robolectric's state alone.
 */
final class TouchMode {
  private TouchMode() {}

  static boolean on() {
    return !"false".equals(System.getProperty("apksim.touchMode"));
  }

  /** Before any window exists (each launch, each test's setup, once the Application is up): the device's starting mode. */
  static void start() {
    if (!on()) return;
    try {
      // ViewRootImpl decides once per process, from its first window's app, whether focus is always assigned
      // (targetSdk below 28: sAlwaysAssignFocus). That first window was a standby's warm-up's, or an earlier test's
      // or the runner probe's plain Application, none with this app's target: every warm launch then gave the first
      // focusable view focus at launch, and again whenever a window gained focus with none. Decided again now.
      ReflectionHelpers.setStaticField(ViewRootImpl.class, "sCompatibilityDone", false);
      force(!Device.peek().tv());  // a window the Application added itself hears of it too
    } catch (Throwable t) {
      System.err.println("[apksim] touch mode not set: " + t);
    }
  }

  static boolean get() {
    return ShadowWindowManagerGlobal.getInTouchMode();
  }

  /**
   * `touchmode [on|off]`: the display's mode, then each window's own view of it, whether it has the window focus and
   * which view has focus there; "focus always assigned" when the framework gives initial focus in touch mode too
   * (ViewRootImpl.sAlwaysAssignFocus: an app targeting below API 28).
   */
  static String command(String[] a) {
    if (a.length > 1) {
      if (!a[1].equals("on") && !a[1].equals("off")) return "error: usage: touchmode [on|off]";
      force(a[1].equals("on"));
    }
    StringBuilder b = new StringBuilder("touch mode " + (get() ? "on" : "off"));
    if ((boolean) ReflectionHelpers.getStaticField(ViewRootImpl.class, "sAlwaysAssignFocus")) b.append(", focus always assigned");
    for (View r : Host.rootViews()) {
      View f = r.findFocus();
      b.append("; ").append(r.getClass().getSimpleName()).append(r.isInTouchMode() ? " touch" : " keys")
          .append(r.hasWindowFocus() ? " window-focus" : "").append(f == null ? "" : " focus=" + f.getClass().getSimpleName());
    }
    return b.toString();
  }

  /** Main thread. The display's touch mode, told to every window; nothing when display and windows are already so. */
  static void set(boolean inTouch) {
    if (!on()) return;
    boolean same = get() == inTouch;
    // A window may have switched by itself (View.requestFocusFromTouch): the framework's own switch is local here.
    for (View r : Host.rootViews()) same &= r.getViewRootImpl() == null || r.isInTouchMode() == inTouch;
    if (!same) force(inTouch);
  }

  /** As {@link #set}, whatever the switch and the current mode say (the `touchmode` command). */
  static void force(boolean inTouch) {
    global(inTouch);
    for (View r : Host.rootViews()) {
      ViewRootImpl vri = r.getViewRootImpl();
      if (vri == null) continue;
      // The window's own switch: AttachInfo, the tree's touch-mode listeners, and focus cleared or given as the
      // mode asks (enterTouchMode/leaveTouchMode).
      ReflectionHelpers.callInstanceMethod(vri, "ensureTouchModeLocally", ClassParameter.from(boolean.class, inTouch));
    }
  }

  /**
   * A key on its way to ROOT's window, as ViewRootImpl's EarlyPostImeInputStage sees it first: a navigation key
   * (D-pad, Tab...) or a typing key leaves touch mode. True when the event is used up by that: leaving touch mode
   * with a navigation key gave a view focus, and the key does not also move it.
   */
  static boolean key(View root, KeyEvent event) {
    ViewRootImpl vri = root.getViewRootImpl();
    if (!on() || vri == null || !root.isInTouchMode()) return false;
    boolean consumed = ReflectionHelpers.callInstanceMethod(vri, "checkForLeavingTouchModeAndConsume",
        ClassParameter.from(KeyEvent.class, event));
    if (!root.isInTouchMode()) force(false);  // the display's mode: the other windows, and those added from now on
    return consumed;
  }

  /**
   * The window session's answer for windows added from now on. Not IWindowManager.setInTouchMode, which ViewRootImpl
   * itself calls: Robolectric's stand-in for the window manager does not pass that on to its session.
   */
  private static void global(boolean inTouch) {
    ReflectionHelpers.callStaticMethod(ShadowWindowManagerGlobal.class, "setInTouchMode", ClassParameter.from(boolean.class, inTouch));
  }
}
