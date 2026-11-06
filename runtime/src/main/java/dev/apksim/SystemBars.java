package dev.apksim;

import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.util.List;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The status bar and gesture navigation bar: whether the device has them, and whether the app has hidden them.
 *
 * <p>The device's presets (phone, foldable and tablet have both, desktop and TV neither) can be overridden at launch
 * ({@code --status-bar on|off}, {@code --nav-bar gesture|off}) and while running ({@code bars}). A bar the device does not
 * have gets no inset (Robolectric's {@code SystemUi} behaviour is NO_STATUS_BAR / NO_NAVIGATION_BAR) and is not drawn in
 * the Mac window.
 *
 * <p>The app hiding a bar ({@code WindowInsetsController.hide}, and through ViewRootImpl's compatibility code the legacy
 * {@code SYSTEM_UI_FLAG_FULLSCREEN}/{@code HIDE_NAVIGATION} flags and {@code FLAG_FULLSCREEN}) reaches Robolectric's window
 * session as the window's requested visible types, which sets the bar invisible in {@code SystemUi}: its inset goes
 * to zero there, and this class stops drawing it. A swipe in from a hidden bar's edge shows the bars as the system does
 * for the window's behaviour: for BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (legacy IMMERSIVE_STICKY) they are drawn over the
 * app for a while and the insets stay as they are; otherwise the window is told to show them
 * ({@code IWindow.showInsets} on a device), so its insets come back. With the deprecated "show by touch" behaviour (legacy
 * flags without IMMERSIVE) any touch does that.
 */
final class SystemBars {
  private SystemBars() {}

  /** Overrides of the device's preset (launch options, the {@code bars} command); null follows the preset. */
  private static Boolean status;
  private static Boolean nav;
  /** In a test run the preset is no bars, as Robolectric has them; only the launch options add them. */
  private static boolean noPreset;
  /** Until when (System.nanoTime) bars the app hid are drawn anyway after a swipe in immersive-sticky mode. */
  private static long transientUntil;
  /** SystemUI's AutoHideController hides transient bars after 2.25 s. */
  private static final long TRANSIENT_NANOS = 2_250_000_000L;
  // The swipe being followed: where it started and from which edge (0 none, 1 top, 2 bottom).
  private static int swipeEdge;
  private static float swipeStartY;

  /** The launch options, read after a standby takes the launch. */
  static void launchOptions() {
    status = parse(System.getProperty("apksim.statusBar"), "on");
    nav = parse(System.getProperty("apksim.navBar"), "gesture");
  }

  private static Boolean parse(String v, String on) {
    if (v == null || v.isEmpty()) return null;
    if (v.equals(on) || v.equals("on")) return true;
    if (v.equals("off")) return false;
    throw new IllegalArgumentException("system bar option: " + v + " (" + on + " or off)");
  }

  static boolean statusBar(Device d) {
    return status != null ? status : !noPreset && d.systemBars();
  }

  static boolean navBar(Device d) {
    return nav != null ? nav : !noPreset && d.systemBars();
  }

  /** Each bar is 24 dp tall, as Robolectric's SystemUi makes it (and the emulator's API 36 phone). */
  static int barPx(Device d) {
    return Math.round(24 * d.density());
  }

  /** Gives Robolectric's SystemUi the bars this device has, so windows get their insets. */
  static void apply(Device d) {
    Object ui = systemUi();
    ReflectionHelpers.callInstanceMethod(ui, "setBehavior",
        ClassParameter.from(robolectric("SystemUi$StatusBarBehavior"), behavior(statusBar(d) ? "STANDARD_STATUS_BAR" : "NO_STATUS_BAR")),
        ClassParameter.from(robolectric("SystemUi$NavigationBarBehavior"), behavior(navBar(d) ? "GESTURAL_NAVIGATION" : "NO_NAVIGATION_BAR")));
  }

  /** At each test's setup in a test run: Robolectric's no bars, or the launch options' bars, all of them showing. */
  static void testSetUp() {
    noPreset = true;
    launchOptions();
    transientUntil = 0;
    Object ui = systemUi();
    setVisible(ReflectionHelpers.callInstanceMethod(ui, "getStatusBar"), true);
    setVisible(ReflectionHelpers.callInstanceMethod(ui, "getNavigationBar"), true);
    apply(Device.current());
  }

  /** {@code bars [status on|off|toggle] [nav gesture|off|toggle]}: changes take effect in every window at once. */
  static String command(String[] a) {
    Device d = Device.current();
    String usage = "usage: bars [status on|off|toggle] [nav gesture|off|toggle]";
    if (a.length % 2 == 0) return usage;
    // Every argument is checked before either bar changes, so a rejected command leaves bars and insets as they were.
    Boolean newStatus = status;
    Boolean newNav = nav;
    for (int i = 1; i + 1 < a.length; i += 2) {
      String v = a[i + 1];
      try {
        switch (a[i]) {
          case "status" -> newStatus = v.equals("toggle") ? !statusBar(d) : parse(v, "on");
          case "nav" -> newNav = v.equals("toggle") ? !navBar(d) : parse(v, "gesture");
          default -> {
            return usage;
          }
        }
      } catch (IllegalArgumentException e) {
        return usage;
      }
      if ((a[i].equals("status") ? newStatus : newNav) == null) return usage;
    }
    status = newStatus;
    nav = newNav;
    if (a.length > 1) {
      apply(d);
      notifyWindows();
    }
    return describe(d);
  }

  /** What the device has and what the app sees, e.g. "status on (hidden by the app), nav gesture (shown)". */
  static String describe(Device d) {
    Object ui = systemUi();
    return "status " + (statusBar(d) ? "on (" + shown(ReflectionHelpers.callInstanceMethod(ui, "getStatusBar")) + ")" : "off")
        + ", nav " + (navBar(d) ? "gesture (" + shown(ReflectionHelpers.callInstanceMethod(ui, "getNavigationBar")) + ")" : "off")
        + (System.nanoTime() < transientUntil ? ", shown transiently" : "");
  }

  private static String shown(Object bar) {
    return visible(bar) ? "shown" : "hidden by the app";
  }

  /** The bars to draw in the Mac window now (pixels, 0 = none): the device's, less those the app hid. */
  static int[] drawn(boolean homeScreen) {
    Device d = Device.current();
    Object ui = systemUi();
    boolean transientShown = System.nanoTime() < transientUntil;
    boolean s = statusBar(d) && (homeScreen || transientShown || visible(ReflectionHelpers.callInstanceMethod(ui, "getStatusBar")));
    boolean n = navBar(d) && (homeScreen || transientShown || visible(ReflectionHelpers.callInstanceMethod(ui, "getNavigationBar")));
    int px = barPx(d);
    return new int[] {s ? px : 0, n ? px : 0};
  }

  /**
   * A touch on the screen (display pixels), before the app gets it: a swipe in from the edge of a bar the app hid shows
   * the bars, as SystemUI's gesture listener does. The app gets the touch either way.
   */
  static void touch(int type, float x, float y, List<View> roots, int displayH) {
    if (type == SimWindow.UP || type == SimWindow.CANCEL) {
      swipeEdge = 0;
      return;
    }
    Device d = Device.current();
    int band = barPx(d);
    if (type == SimWindow.DOWN) {
      swipeEdge = 0;
      View top = topRoot(roots);
      WindowInsetsController c = top == null ? null : top.getWindowInsetsController();
      int hidden = hiddenTypes(d);
      if (c == null || hidden == 0) return;
      if (c.getSystemBarsBehavior() == 0) {  // BEHAVIOR_SHOW_BARS_BY_TOUCH: legacy flags without IMMERSIVE
        c.show(hidden);
        return;
      }
      // Only an edge where the device has a bar: SystemUI listens for swipes from its bars.
      if (y < band && statusBar(d)) swipeEdge = 1;
      else if (y >= displayH - band && navBar(d)) swipeEdge = 2;
      swipeStartY = y;
      return;
    }
    if (swipeEdge == 0 || Math.abs(y - swipeStartY) < band) return;
    boolean inward = swipeEdge == 1 ? y > swipeStartY : y < swipeStartY;
    swipeEdge = 0;
    if (!inward) return;
    View top = topRoot(roots);
    WindowInsetsController c = top == null ? null : top.getWindowInsetsController();
    int hidden = hiddenTypes(d);
    if (c == null || hidden == 0) return;
    if (c.getSystemBarsBehavior() == WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
      transientUntil = System.nanoTime() + TRANSIENT_NANOS;
    } else {
      c.show(hidden);
    }
  }

  /** The bar types this device has that the app has hidden. */
  private static int hiddenTypes(Device d) {
    Object ui = systemUi();
    int t = 0;
    if (statusBar(d) && !visible(ReflectionHelpers.callInstanceMethod(ui, "getStatusBar"))) t |= WindowInsets.Type.statusBars();
    if (navBar(d) && !visible(ReflectionHelpers.callInstanceMethod(ui, "getNavigationBar"))) t |= WindowInsets.Type.navigationBars();
    return t;
  }

  private static View topRoot(List<View> roots) {
    for (int i = roots.size() - 1; i >= 0; i--) {
      if (roots.get(i).getVisibility() == View.VISIBLE) return roots.get(i);
    }
    return null;
  }

  /**
   * Every window gets its insets again, as Robolectric's window session does when a window changes what it shows (its
   * notifyInsetsChanges: new insets state, the bars' controls, a resize).
   */
  static void notifyWindows() {
    Object session = ReflectionHelpers.callStaticMethod(robolectric("ShadowWindowManagerGlobal"), "getWindowSessionDelegate");
    if (session == null) return;
    Object ui = systemUi();
    for (String bar : new String[] {"getStatusBar", "getNavigationBar"}) {
      int id = ReflectionHelpers.callInstanceMethod(ReflectionHelpers.callInstanceMethod(ui, bar), "getId");
      ReflectionHelpers.callInstanceMethod(session, "notifyInsetsChanges",
          ClassParameter.from(robolectric("SystemUi"), ui), ClassParameter.from(Integer.class, id));
    }
  }

  // Robolectric keeps SystemUi package-private.
  private static Object systemUi() {
    return ReflectionHelpers.callStaticMethod(robolectric("SystemUi"), "systemUiForDefaultDisplay");
  }

  private static Object behavior(String name) {
    return ReflectionHelpers.getStaticField(robolectric("SystemUi"), name);
  }

  private static boolean visible(Object bar) {
    return ReflectionHelpers.callInstanceMethod(bar, "isVisible");
  }

  private static void setVisible(Object bar, boolean v) {
    ReflectionHelpers.callInstanceMethod(bar, "setVisible", ClassParameter.from(boolean.class, v));
  }

  private static Class<?> robolectric(String name) {
    try {
      return Class.forName("org.robolectric.shadows." + name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
