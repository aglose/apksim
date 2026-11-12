package dev.apksim;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
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
    walkDepth(roots, (n, depth) -> visit.accept(n));
  }

  /** Visits every node under [roots], depth first, with its depth (a window's root view is 0). */
  static void walkDepth(List<View> roots, BiConsumer<Node, Integer> visit) {
    for (View root : roots) walkView(root, 0, visit);
  }

  private static void walkView(View v, int depth, BiConsumer<Node, Integer> visit) {
    if (v.getVisibility() != View.VISIBLE) return;
    AccessibilityNodeProvider p = v.getAccessibilityNodeProvider();
    if (p != null) {
      walkVirtual(v, p, AccessibilityNodeProvider.HOST_VIEW_ID, depth, visit);
      return;
    }
    AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
    // A View reports its id only to a service that asked for ids (FLAG_REPORT_VIEW_IDS): given here, as UiAutomator
    // would get it, so `nodes` and journeys can find an unlabelled button by its id.
    if (info.getViewIdResourceName() == null && v.getId() != View.NO_ID && v.getId() > 0) {
      try {
        info.setViewIdResourceName(v.getResources().getResourceName(v.getId()));
      } catch (RuntimeException e) {
        // a generated id with no resource name
      }
    }
    visit.accept(new Node(v, AccessibilityNodeProvider.HOST_VIEW_ID, info), depth);
    if (v instanceof android.view.ViewGroup g) {
      for (int i = 0; i < g.getChildCount(); i++) walkView(g.getChildAt(i), depth + 1, visit);
    }
  }

  private static void walkVirtual(View host, AccessibilityNodeProvider p, int root, int depth0, BiConsumer<Node, Integer> visit) {
    // Depth first, each virtual id once (issue 114): a provider may list a node under several parents or an ancestor
    // as a child. The visited set bounds the walk, so no depth cap (which could hide a node a shorter path reaches).
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
    todo.push(new int[] {root, depth0});
    while (!todo.isEmpty()) {
      int[] top = todo.pop();
      int id = top[0];
      if (!seen.add(id)) continue;
      AccessibilityNodeInfo info = p.createAccessibilityNodeInfo(id);
      if (info == null) continue;
      visit.accept(new Node(host, id, info), top[1]);
      for (int i = info.getChildCount() - 1; i >= 0; i--) {
        long child = info.getChildId(i);  // Hidden API: the child's id within the provider.
        todo.push(new int[] {AccessibilityNodeInfo.getVirtualDescendantId(child), top[1] + 1});
      }
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

  /**
   * A value as `nodes` prints it: a backslash, quote or line break escaped (\\, \", \n), so a label with quotes or a
   * semicolon reads back whole and the reply stays one line. Most labels have none and print unchanged.
   */
  static String quote(String v) {
    if (v.indexOf('\\') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
    return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
      if (!i.isVisibleToUser()) b.append("offscreen ");  // in the tree, not on screen (a list item scrolled away)
      if (!n.text().isEmpty()) b.append("text=\"").append(i.isPassword() ? "•••" : quote(n.text())).append("\" ");
      if (!n.hint().isEmpty()) b.append("hint=\"").append(quote(n.hint())).append("\" ");
      if (!n.description().isEmpty()) b.append("desc=\"").append(quote(n.description())).append("\" ");
      CharSequence id = i.getViewIdResourceName();  // a View's id, or Compose's testTag with testTagsAsResourceId
      if (id != null && id.length() > 0) b.append("id=\"").append(quote(id.toString())).append("\" ");
      b.append("; ");
    }
    return b.toString();
  }

  /**
   * `nodes tree`: every node, with its depth, class, view id name, state flags and bounds, whether or not it has
   * anything to say (scripts/crawl.py hashes the structure and finds what to tap). Records are separated by \u001e,
   * fields by a tab: depth, class, id, flags (c clickable, l long-clickable, e editable, k checkable, K checked,
   * s scrollable, f focusable, p password, d disabled, v visible to the user, t text cut off: TextTruncation), l,t,r,b, text, description, hint
   * (tabs and line breaks in them become spaces).
   */
  static String tree(List<View> roots) {
    StringBuilder b = new StringBuilder();
    Rect r = new Rect();
    TextTruncation.Cache cut = new TextTruncation.Cache();
    walkDepth(roots, (n, depth) -> {
      AccessibilityNodeInfo i = n.info;
      i.getBoundsInScreen(r);
      StringBuilder f = new StringBuilder();
      if (i.isClickable()) f.append('c');
      if (i.isLongClickable()) f.append('l');
      if (i.isEditable()) f.append('e');
      if (i.isCheckable()) f.append('k');
      if (i.isChecked()) f.append('K');
      if (i.isScrollable()) f.append('s');
      if (i.isFocusable()) f.append('f');
      if (i.isPassword()) f.append('p');
      if (!i.isEnabled()) f.append('d');
      if (i.isVisibleToUser()) f.append('v');
      if (!n.text().isEmpty() && cut.cutOff(n)) f.append('t');
      b.append(depth).append('\t').append(i.getClassName()).append('\t')
          .append(i.getViewIdResourceName() == null ? "" : i.getViewIdResourceName()).append('\t').append(f).append('\t')
          .append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append('\t')
          .append(flat(i.isPassword() ? "" : n.text())).append('\t').append(flat(n.description())).append('\t')
          .append(flat(n.hint())).append('\u001e');
    });
    return b.toString();
  }

  private static String flat(String s) {
    return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('\u001e', ' ');
  }
}
