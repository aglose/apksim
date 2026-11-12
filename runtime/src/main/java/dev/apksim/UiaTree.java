package dev.apksim;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Checkable;
import android.widget.TextView;

/**
 * A hash of what UiAutomator can see, for {@link Uia#idle}: every node of the windows accessibility reports, with
 * where it is and what it says. Two equal hashes a moment apart mean the screen, as a test reads it, has stopped
 * changing, whatever is still being drawn (a spinner, a shader, a blinking caret). Views are read from their own
 * fields (cheap: no node is built); a view with a node provider (Compose) through the nodes it provides, as
 * UiAutomator reads them. A progress value is left out on purpose: a bar filling up is not the screen moving.
 * Main thread.
 */
final class UiaTree {
  private UiaTree() {}

  static long fingerprint() {
    long t = System.nanoTime();
    long h = 1125899906842597L;
    try {
      Rect r = new Rect();
      for (View root : ShadowSimUiAutomation.reportedViews()) {
        int[] at = Host.screenPosition(root);
        h = mix(mix(h, at[0]), at[1]);
        h = view(root, h, r);
      }
    } catch (RuntimeException e) {
      h = t;  // a tree that cannot be read counts as changed
    }
    Uia.Stats.fingerprints++;
    Uia.Stats.fingerprintNanos += System.nanoTime() - t;
    return h;
  }

  private static long mix(long h, long v) {
    return (h ^ v) * 0x100000001b3L;
  }

  private static long text(long h, CharSequence c) {
    if (c == null) return mix(h, -1);
    int n = c.length();
    h = mix(h, n);
    for (int i = 0; i < n; i++) h = mix(h, c.charAt(i));
    return h;
  }

  private static long view(View v, long h, Rect r) {
    if (v.getVisibility() != View.VISIBLE) return h;
    h = mix(h, System.identityHashCode(v));
    h = mix(mix(mix(mix(h, v.getLeft()), v.getTop()), v.getRight()), v.getBottom());
    h = mix(mix(h, v.getScrollX()), v.getScrollY());
    h = mix(mix(h, Float.floatToIntBits(v.getTranslationX())), Float.floatToIntBits(v.getTranslationY()));
    h = mix(mix(h, Float.floatToIntBits(v.getScaleX())), Float.floatToIntBits(v.getScaleY()));
    int flags = (v.isEnabled() ? 1 : 0) | (v.isSelected() ? 2 : 0) | (v.isFocused() ? 4 : 0) | (v.getAlpha() == 0f ? 8 : 0);
    if (v instanceof Checkable c && c.isChecked()) flags |= 16;
    h = mix(h, flags);
    if (v instanceof TextView t) h = text(h, t.getText());
    h = text(h, v.getContentDescription());
    AccessibilityNodeProvider p = v.getAccessibilityNodeProvider();
    if (p != null) return virtual(p, h, r);
    if (v instanceof ViewGroup g) {
      for (int i = 0, n = g.getChildCount(); i < n; i++) h = view(g.getChildAt(i), h, r);
    }
    return h;
  }

  /** A provider's nodes, each once (a provider may list one under several parents: issue 114), depth first. */
  private static long virtual(AccessibilityNodeProvider p, long h, Rect r) {
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
    todo.push(AccessibilityNodeProvider.HOST_VIEW_ID);
    while (!todo.isEmpty()) {
      int id = todo.pop();
      if (!seen.add(id)) continue;
      AccessibilityNodeInfo info = p.createAccessibilityNodeInfo(id);
      if (info == null) continue;
      info.getBoundsInScreen(r);
      h = mix(mix(mix(mix(mix(h, id), r.left), r.top), r.right), r.bottom);
      int flags = (info.isEnabled() ? 1 : 0) | (info.isSelected() ? 2 : 0) | (info.isFocused() ? 4 : 0)
          | (info.isVisibleToUser() ? 8 : 0) | (info.isChecked() ? 16 : 0);
      h = mix(h, flags);
      h = text(h, info.getText());
      h = text(h, info.getContentDescription());
      for (int i = info.getChildCount() - 1; i >= 0; i--) {
        todo.push(AccessibilityNodeInfo.getVirtualDescendantId(info.getChildId(i)));  // hidden: the child's id
      }
    }
    return h;
  }
}
