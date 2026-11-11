package dev.apksim;

import android.text.Layout;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether the text a node shows is cut off: ellipsized, clipped by its box, or dropped by a line limit. The `t` flag
 * of `nodes tree` and the "text cut off" lines of `a11y` come from here (scripts/crawl.py's font-scale and locale
 * sweeps read it).
 *
 * Views: the TextView's layout (an ellipsized line, text left after the last line, a line (without its trailing spaces) wider or a layout taller than
 * the room inside the view). Compose: the semantics tree's GetTextLayoutResult action, read by reflection (the runtime
 * has no Compose to compile against; a minified app's renamed classes just report nothing): the layout result's
 * hasVisualOverflow, which Compose sets when text is ellipsized or clipped by its constraints. A merged node (a
 * Button's) is cut off if any text inside it is.
 */
final class TextTruncation {
  private TextTruncation() {}

  /** Per Compose host view: semantics node id -> cut off, computed once per walk. */
  static final class Cache {
    private final Map<View, Map<Integer, Boolean>> compose = new HashMap<>();

    boolean cutOff(Nodes.Node n) {
      if (n.host instanceof TextView tv && n.virtualId == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) {
        return view(tv);
      }
      if (n.virtualId == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) return false;
      Map<Integer, Boolean> ids = compose.computeIfAbsent(n.host, TextTruncation::composeIds);
      return Boolean.TRUE.equals(ids.get(n.virtualId));
    }
  }

  static boolean view(TextView tv) {
    CharSequence text = tv.getText();
    Layout l = tv.getLayout();
    if (l == null || text == null || text.length() == 0 || tv.getVisibility() != View.VISIBLE) return false;
    int lines = l.getLineCount();
    int room = tv.getWidth() - tv.getTotalPaddingLeft() - tv.getTotalPaddingRight();
    // Text that scrolls sideways on purpose (a marquee, a single-line field) is not cut off by its width.
    boolean sideways = tv.getEllipsize() == android.text.TextUtils.TruncateAt.MARQUEE || tv instanceof android.widget.EditText;
    for (int i = 0; i < lines; i++) {
      if (l.getEllipsisCount(i) > 0) return true;
      if (room > 0 && !sideways && l.getLineMax(i) > room + 1) return true;
    }
    if (l.getLineEnd(lines - 1) < text.length()) return true;  // a maxLines limit dropped the rest
    int high = tv.getHeight() - tv.getTotalPaddingTop() - tv.getTotalPaddingBottom();
    return high > 0 && l.getHeight() > high + 1 && tv.getMovementMethod() == null;  // a scrolling TextView is not cut off
  }

  /** Semantics node id -> whether the text in it (and what it merges) is cut off; empty when this is not Compose. */
  private static Map<Integer, Boolean> composeIds(View host) {
    Map<Integer, Boolean> out = new HashMap<>();
    try {
      Object owner = host.getClass().getMethod("getSemanticsOwner").invoke(host);
      Object root = owner.getClass().getMethod("getUnmergedRootSemanticsNode").invoke(owner);
      ClassLoader cl = host.getClass().getClassLoader();
      Class<?> fn1 = Class.forName("kotlin.jvm.functions.Function1", true, cl);
      walk(root, fn1.getMethod("invoke", Object.class), out);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      // Not Compose, or a Compose these names do not fit: nothing is reported (-Dapksim.debugTruncation=true says why).
      if (Boolean.getBoolean("apksim.debugTruncation")) e.printStackTrace();
    }
    return out;
  }

  /**
   * A TextLayoutResult cut off: a line ellipsized, the line limit dropped text, the box shorter than the text or
   * narrower than a line. (Its own hasVisualOverflow compares the box with the width the text was laid out for, so it
   * is true for any text that does not fill its row.)
   */
  private static boolean cutOff(Object r) throws ReflectiveOperationException {
    Class<?> c = r.getClass();
    Object mp = c.getMethod("getMultiParagraph").invoke(r);
    long size = 0;
    for (Method m : c.getMethods()) {
      if (m.getName().startsWith("getSize") && m.getReturnType() == long.class && m.getParameterCount() == 0) size = (Long) m.invoke(r);
    }
    int width = (int) (size >>> 32), height = (int) size;
    if (Boolean.TRUE.equals(mp.getClass().getMethod("getDidExceedMaxLines").invoke(mp))) return true;
    if (height < ((Number) mp.getClass().getMethod("getHeight").invoke(mp)).floatValue() - 1) return true;
    int lines = (Integer) c.getMethod("getLineCount").invoke(r);
    for (int i = 0; i < lines; i++) {
      if (Boolean.TRUE.equals(c.getMethod("isLineEllipsized", int.class).invoke(r, i))) return true;
      if (((Number) c.getMethod("getLineRight", int.class).invoke(r, i)).floatValue() > width + 1) return true;
      if (((Number) c.getMethod("getLineLeft", int.class).invoke(r, i)).floatValue() < -1) return true;  // right-to-left text runs out on the left
    }
    return false;
  }

  private static boolean walk(Object node, Method invoke, Map<Integer, Boolean> out)
      throws ReflectiveOperationException {
    boolean cut = false;
    Object config = node.getClass().getMethod("getConfig").invoke(node);
    // SemanticsConfiguration is an Iterable of (key, value) entries; its getOrNull is internal in newer Compose.
    Object action = null;
    for (Object entry : (Iterable<?>) config) {
      Object k = ((Map.Entry<?, ?>) entry).getKey();
      if ("GetTextLayoutResult".equals(k.getClass().getMethod("getName").invoke(k))) action = ((Map.Entry<?, ?>) entry).getValue();
    }
    Object fn = action == null ? null : action.getClass().getMethod("getAction").invoke(action);
    if (fn != null) {
      List<Object> results = new ArrayList<>();
      invoke.invoke(fn, results);
      for (Object r : results) cut |= cutOff(r);
    }
    List<?> kids = (List<?>) node.getClass().getMethod("getChildren").invoke(node);
    for (Object k : kids) cut |= walk(k, invoke, out);
    out.put((Integer) node.getClass().getMethod("getId").invoke(node), cut);
    return cut;
  }
}
