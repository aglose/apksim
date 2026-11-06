package dev.apksim;

import android.graphics.Point;
import android.graphics.Region;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Which of this process's windows accessibility reports (UiAutomation.getWindows, uiautomator dump, `nodes`), as the
 * window manager's WindowsForAccessibilityObserver computes them (AccessibilityController.computeWindowsForAccessibility,
 * API 33+): top down, with the display's whole area unaccounted for at first, a window is reported when it has the
 * focus, or when it is touchable and its frame still meets unaccounted space; each reported window then takes its
 * touchable region out of that space, and the walk ends once nothing is left and the focused window is in. A
 * touch-modal window's touchable region (focusable, without FLAG_NOT_TOUCH_MODAL: any Dialog, a focusable popup) is its
 * whole task, here the display (WindowState.getSurfaceTouchableRegion), so nothing under it is reported; any other
 * window's is its frame. So a full-screen or modal dialog hides the activity under it, while a non-focusable popup or a
 * FLAG_NOT_TOUCH_MODAL window leaves what is around it reported. Touch input does not use this: Host's dispatch
 * follows InputDispatcher, which sends a touch past a window it misses only when that window is not touch-modal.
 *
 * <p>Simplified: a window's touchable insets (ViewTreeObserver's InternalInsetsInfo) are not read, its frame stands for
 * them; there are no system or accessibility overlay windows in the process to exempt.
 */
final class AccessibleWindows {
  private AccessibleWindows() {}

  /**
   * For windows listed bottom to top ([views] with their [params]), whether accessibility reports each one. The
   * focused window is the topmost focusable one, as UiAutomation's active window here.
   */
  static boolean[] reported(List<View> views, List<WindowManager.LayoutParams> params) {
    int n = Math.min(views.size(), params.size());
    boolean[] out = new boolean[n];
    if (n == 0) return out;
    int focused = -1;
    for (int i = n - 1; i >= 0 && focused < 0; i--) {
      if ((params.get(i).flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) focused = i;
    }
    Point size = displaySize(views.get(n - 1));
    Region display = new Region(0, 0, size.x, size.y);
    Region unaccounted = new Region(display);
    boolean focusedAdded = false;
    for (int i = n - 1; i >= 0; i--) {
      View v = views.get(i);
      WindowManager.LayoutParams p = params.get(i);
      int[] at = Host.screenPosition(v);
      Region frame = new Region(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
      boolean touchable = (p.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
      boolean isFocused = i == focused;
      if (isFocused || (touchable && !frame.isEmpty() && !unaccounted.quickReject(frame))) {
        out[i] = true;
        // An app window is never a trusted overlay, so even an untouchable one (only reached when focused) counts.
        unaccounted.op(touchModal(p) ? display : frame, Region.Op.DIFFERENCE);
        focusedAdded |= isFocused;
      }
      if (unaccounted.isEmpty() && focusedAdded) break;
    }
    return out;
  }

  /**
   * The shown windows' root views, bottom to top in the stack Host draws them in and sends touches down
   * (WindowManagerGlobal's order, in which they were added), each with its window's layout params as its own. Every
   * list of windows (UiAutomation, uiautomator dump, `nodes`, failure bundles) is in this order, so what accessibility
   * reports as covered is what the screen shows covered: sorting by window type put a popup opened before a dialog
   * above the dialog, where it is neither drawn nor touched.
   */
  static List<View> stack() {
    return shown(Host.rootViews());
  }

  /** Of root views in WindowManagerGlobal's order, the shown ones (visible, attached, with window layout params). */
  static List<View> shown(List<View> roots) {
    List<View> out = new ArrayList<>();
    for (View v : roots) {
      if (v.getVisibility() == View.VISIBLE && v.isAttachedToWindow()
          && v.getLayoutParams() instanceof WindowManager.LayoutParams) out.add(v);
    }
    return out;
  }

  /** Of root views in WindowManagerGlobal's order ({@link #stack}), the shown ones accessibility reports, bottom to top. */
  static List<View> reported(List<View> roots) {
    List<View> shown = shown(roots);
    List<WindowManager.LayoutParams> params = new ArrayList<>();
    for (View v : shown) params.add((WindowManager.LayoutParams) v.getLayoutParams());
    boolean[] keep = reported(shown, params);
    List<View> out = new ArrayList<>();
    for (int i = 0; i < shown.size(); i++) if (keep[i]) out.add(shown.get(i));
    return out;
  }

  /** WindowManager.LayoutParams.isModal: focusable and without FLAG_NOT_TOUCH_MODAL, so it takes every touch. */
  static boolean touchModal(WindowManager.LayoutParams p) {
    return (p.flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) == 0;
  }

  private static Point displaySize(View v) {
    Point size = new Point();
    Display d = v.getDisplay();
    if (d != null) {
      d.getRealSize(size);
    } else {
      android.util.DisplayMetrics m = v.getResources().getDisplayMetrics();
      size.set(m.widthPixels, m.heightPixels);
    }
    return size;
  }
}
