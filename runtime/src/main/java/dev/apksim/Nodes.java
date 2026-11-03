package dev.apksim;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The screen as accessibility services see it: every view and every virtual node (Compose publishes its
 * semantics this way), with text, hint, state and actions. Framework API only, so it works on minified apps.
 */
final class Nodes {
  private Nodes() {}

  /** One node and how to act on it. */
  static final class Node {
    final View host;
    final int virtualId;  // AccessibilityNodeProvider.HOST_VIEW_ID for the view itself.
    final AccessibilityNodeInfo info;

    Node(View host, int virtualId, AccessibilityNodeInfo info) {
      this.host = host;
      this.virtualId = virtualId;
      this.info = info;
    }

    String text() {
      return info.getText() == null ? "" : info.getText().toString();
    }

    String hint() {
      CharSequence h = info.getHintText();
      return h == null ? "" : h.toString();
    }

    String description() {
      return info.getContentDescription() == null ? "" : info.getContentDescription().toString();
    }

    /** The same node read again, or null if it is gone. */
    Node refresh() {
      AccessibilityNodeProvider p = host.getAccessibilityNodeProvider();
      AccessibilityNodeInfo i = p != null && virtualId != AccessibilityNodeProvider.HOST_VIEW_ID
          ? p.createAccessibilityNodeInfo(virtualId) : host.createAccessibilityNodeInfo();
      return i == null ? null : new Node(host, virtualId, i);
    }

    boolean perform(int action, Bundle args) {
      AccessibilityNodeProvider p = host.getAccessibilityNodeProvider();
      if (p != null && virtualId != AccessibilityNodeProvider.HOST_VIEW_ID) return p.performAction(virtualId, action, args);
      return host.performAccessibilityAction(action, args);
    }

    boolean setText(String value) {
      Bundle args = new Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
      return perform(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    boolean click() {
      return perform(AccessibilityNodeInfo.ACTION_CLICK, null);
    }
  }

  /** Visits every node under [roots], depth first. */
  static void walk(List<View> roots, Consumer<Node> visit) {
    for (View root : roots) walkView(root, visit);
  }

  private static void walkView(View v, Consumer<Node> visit) {
    if (v.getVisibility() != View.VISIBLE) return;
    AccessibilityNodeProvider p = v.getAccessibilityNodeProvider();
    if (p != null) {
      walkVirtual(v, p, AccessibilityNodeProvider.HOST_VIEW_ID, visit, 0);
      return;
    }
    visit.accept(new Node(v, AccessibilityNodeProvider.HOST_VIEW_ID, v.createAccessibilityNodeInfo()));
    if (v instanceof android.view.ViewGroup g) {
      for (int i = 0; i < g.getChildCount(); i++) walkView(g.getChildAt(i), visit);
    }
  }

  private static void walkVirtual(View host, AccessibilityNodeProvider p, int id, Consumer<Node> visit, int depth) {
    AccessibilityNodeInfo info = p.createAccessibilityNodeInfo(id);
    if (info == null || depth > 64) return;
    visit.accept(new Node(host, id, info));
    for (int i = 0; i < info.getChildCount(); i++) {
      long child = info.getChildId(i);  // Hidden API: the child's id within the provider.
      walkVirtual(host, p, AccessibilityNodeInfo.getVirtualDescendantId(child), visit, depth + 1);
    }
  }

  /**
   * For the `tap-text` control command: the screen centre of the first node whose text or description is [text]
   * (case-insensitive), or null. Tapping there behaves as a finger would, whichever element handles it.
   */
  static int[] centreOf(List<View> roots, String text) {
    String want = text.trim().toLowerCase(java.util.Locale.ROOT);
    int[] hit = new int[2];
    boolean[] found = {false};
    Rect r = new Rect();
    walk(roots, n -> {
      if (found[0]) return;
      if (n.text().trim().toLowerCase(java.util.Locale.ROOT).equals(want)
          || n.description().trim().toLowerCase(java.util.Locale.ROOT).equals(want)) {
        n.info.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return;
        hit[0] = r.centerX();
        hit[1] = r.centerY();
        found[0] = true;
      }
    });
    return found[0] ? hit : null;
  }

  /** A text dump for the `nodes` control command: one line per node with something to say or do. */
  static String dump(List<View> roots) {
    StringBuilder b = new StringBuilder();
    List<Node> all = new ArrayList<>();
    walk(roots, all::add);
    Rect r = new Rect();
    for (Node n : all) {
      AccessibilityNodeInfo i = n.info;
      boolean useful = !n.text().isEmpty() || !n.hint().isEmpty() || !n.description().isEmpty() || i.isEditable()
          || i.isClickable();
      if (!useful) continue;
      i.getBoundsInScreen(r);
      b.append('[').append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append("] ");
      if (i.isEditable()) b.append(i.isPassword() ? "password-field " : "field ");
      if (i.isClickable()) b.append("clickable ");
      if (!n.text().isEmpty()) b.append("text=\"").append(i.isPassword() ? "•••" : n.text()).append("\" ");
      if (!n.hint().isEmpty()) b.append("hint=\"").append(n.hint()).append("\" ");
      if (!n.description().isEmpty()) b.append("desc=\"").append(n.description()).append("\" ");
      b.append("; ");
    }
    return b.toString();
  }
}
