package dev.apksim;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.util.ReflectionHelpers;

/**
 * In a test run window focus moves as a device's window manager moves it: a focusable window added on top (a dialog,
 * a focusable popup) takes it from the window that had it, and when the focused window goes, the topmost focusable
 * window left gets it back. Robolectric gives focus only to an activity's window, when the activity becomes visible,
 * so a dialog never had it, and Espresso, which looks in the focused window, found the activity's views under the
 * dialog instead of the dialog's.
 */
@Implements(value = WindowManagerImpl.class, isInAndroidSdk = false)
public class ShadowSimWindowManagerImpl extends ShadowWindowManagerImpl {
  @Implementation
  @Override
  public void addView(View view, ViewGroup.LayoutParams params) {
    super.addView(view, params);
    if (TestActivities.testRun() && params instanceof WindowManager.LayoutParams lp && focusable(lp)) focus(view);
  }

  @Implementation
  @Override
  public void removeView(View view) {
    boolean had = view.hasWindowFocus();
    super.removeView(view);
    if (had) refocus(view);
  }

  @Implementation
  @Override
  protected void removeViewImmediate(View view) {
    boolean had = view.hasWindowFocus();
    super.removeViewImmediate(view);
    if (had) refocus(view);
  }

  private static boolean focusable(WindowManager.LayoutParams lp) {
    return (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
  }

  /** Gives {@code view}'s window the focus, taking it from every other window. */
  private static void focus(View view) {
    for (View other : roots()) {
      if (other != view && other.hasWindowFocus()) changed(other, false);
    }
    changed(view, true);
  }

  /**
   * A hidden window was shown again (TestActivities: a stopped activity back on top, after the focused window above
   * it went while it was still hidden): the topmost visible focusable window gets the focus, if it lacks it.
   */
  static void focusTop() {
    List<View> roots = roots();
    View top = null;
    for (int i = roots.size() - 1; i >= 0 && top == null; i--) {
      View v = roots.get(i);
      if (v.getVisibility() == View.VISIBLE && v.getLayoutParams() instanceof WindowManager.LayoutParams lp && focusable(lp)) top = v;
    }
    // A hidden window can hold it too: one added (and so focused) before it was hidden.
    for (View v : roots) if (v != top && v.hasWindowFocus()) changed(v, false);
    if (top != null && !top.hasWindowFocus()) changed(top, true);
  }

  /**
   * Puts the windows back in an order (the activity stack's, after windows were added again for recreated
   * activities): a stable sort by {@code rank}, of WindowManagerGlobal's views, roots and params together, which is
   * the order windows are drawn, touched and found in. A window {@code rank} gives -1 keeps its place after the window
   * before it.
   */
  static void reorder(java.util.function.ToIntFunction<View> rank) {
    Object global = android.view.WindowManagerGlobal.getInstance();
    synchronized (ReflectionHelpers.<Object>getField(global, "mLock")) {
      List<View> views = ReflectionHelpers.getField(global, "mViews");
      List<ViewRootImpl> impls = ReflectionHelpers.getField(global, "mRoots");
      List<WindowManager.LayoutParams> params = ReflectionHelpers.getField(global, "mParams");
      int n = views.size();
      if (impls.size() != n || params.size() != n) return;
      Integer[] order = new Integer[n];
      int[] ranks = new int[n];
      int last = -1;
      for (int i = 0; i < n; i++) {
        int r = rank.applyAsInt(views.get(i));
        last = r >= 0 ? r : last;
        ranks[i] = last;
        order[i] = i;
      }
      java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> ranks[i]));  // stable
      List<View> v2 = new java.util.ArrayList<>(n);
      List<ViewRootImpl> r2 = new java.util.ArrayList<>(n);
      List<WindowManager.LayoutParams> p2 = new java.util.ArrayList<>(n);
      for (int i : order) {
        v2.add(views.get(i));
        r2.add(impls.get(i));
        p2.add(params.get(i));
      }
      views.clear();
      views.addAll(v2);
      impls.clear();
      impls.addAll(r2);
      params.clear();
      params.addAll(p2);
    }
  }

  /** The focused window {@code gone} went: the topmost focusable window left gets the focus. */
  private static void refocus(View gone) {
    if (!TestActivities.testRun()) return;
    List<View> roots = roots();
    for (int i = roots.size() - 1; i >= 0; i--) {
      View v = roots.get(i);
      if (v == gone || v.getVisibility() != View.VISIBLE) continue;
      if (v.getLayoutParams() instanceof WindowManager.LayoutParams lp && focusable(lp)) {
        if (!v.hasWindowFocus()) changed(v, true);
        return;
      }
    }
  }

  private static void changed(View root, boolean focused) {
    ViewRootImpl vri = rootImplOf(root);
    if (vri != null) ((ShadowViewRootImpl) Shadow.extract(vri)).callWindowFocusChanged(focused);
  }

  /** The window's ViewRootImpl; View.getViewRootImpl is null until the window's first traversal attaches it. */
  private static ViewRootImpl rootImplOf(View root) {
    Object global = android.view.WindowManagerGlobal.getInstance();
    List<View> views = ReflectionHelpers.getField(global, "mViews");
    List<ViewRootImpl> impls = ReflectionHelpers.getField(global, "mRoots");
    int i = views.indexOf(root);
    return i >= 0 && i < impls.size() ? impls.get(i) : root.getViewRootImpl();
  }

  private static List<View> roots() {
    Object global = android.view.WindowManagerGlobal.getInstance();
    return new java.util.ArrayList<>(ReflectionHelpers.<List<View>>getField(global, "mViews"));
  }
}
