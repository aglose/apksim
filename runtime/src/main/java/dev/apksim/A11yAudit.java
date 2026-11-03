package dev.apksim;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks an accessibility scanner makes first, over what TalkBack would see (Nodes): controls a screen reader
 * cannot name, touch targets smaller than 48 dp, and images without a description that can be focused.
 */
final class A11yAudit {
  private A11yAudit() {}

  private record Seen(Rect bounds, boolean actionable, boolean named, Nodes.Node node) {}

  static String run(List<View> roots, float density) {
    List<Seen> all = new ArrayList<>();
    Nodes.walk(roots, n -> {
      if (!n.info.isVisibleToUser()) return;
      Rect r = new Rect();
      n.info.getBoundsInScreen(r);
      boolean actionable = n.info.isClickable() || n.info.isLongClickable() || n.info.isCheckable();
      boolean named = !n.text().isEmpty() || !n.description().isEmpty() || n.info.getLabeledBy() != null;
      all.add(new Seen(r, actionable, named, n));
    });
    List<String> issues = new ArrayList<>();
    int min = Math.round(48 * density);
    for (Seen s : all) {
      Rect r = s.bounds();
      String where = "[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]";
      // A screen reader merges a control's children: a label inside it names it (a Button's Text, a tab's title).
      boolean named = s.named() || all.stream().anyMatch(o -> o != s && o.named() && !o.actionable() && r.contains(o.bounds()));
      if (s.actionable() && !named) issues.add(where + " control with no label (" + kind(s.node().info) + ")");
      if (s.actionable() && r.width() > 0 && (r.width() < min || r.height() < min)) {
        issues.add(where + " touch target " + Math.round(r.width() / density) + "x" + Math.round(r.height() / density)
            + " dp, under 48 dp: " + label(s.node()));
      }
      if (String.valueOf(s.node().info.getClassName()).endsWith("ImageView") && s.node().info.isFocusable() && !named) {
        issues.add(where + " focusable image with no description");
      }
    }
    return issues.isEmpty() ? "no issues in " + all.size() + " nodes"
        : issues.size() + " issue(s) in " + all.size() + " nodes: " + String.join(" ; ", issues);
  }

  private static String kind(AccessibilityNodeInfo info) {
    String c = String.valueOf(info.getClassName());
    return c.substring(c.lastIndexOf('.') + 1);
  }

  private static String label(Nodes.Node n) {
    String t = !n.text().isEmpty() ? n.text() : n.description();
    return t.isEmpty() ? kind(n.info) : "\"" + t + "\"";
  }
}
