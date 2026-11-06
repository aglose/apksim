package dev.apksim;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Xml;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlSerializer;

/**
 * `uiautomator dump`'s XML, as the stock tool writes it (AOSP's cmds/uiautomator DumpCommand and
 * AccessibilityNodeInfoDumper): the active window's accessibility tree read through the same linked nodes UiAutomator
 * gets here ({@link ShadowSimUiAutomation#windowInfos}), so Compose semantics and testTagsAsResourceId ids show. The
 * attributes, their order, the NAF mark, visible-only children, bounds clipped to the display and the serializer
 * (Android's, so escaping and the XML declaration match) are the tool's. `--compressed` leaves out views not important
 * for accessibility, their children taking their place, as the system does when a service does not ask for them;
 * `--windows` dumps every window under `<displays>`, as the tool's option of that name does. Main thread only.
 */
final class UiDump {
  private UiDump() {}

  private static final String[] NAF_EXCLUDED_CLASSES = {
      "android.widget.GridView", "android.widget.GridLayout", "android.widget.ListView", "android.widget.TableLayout"};

  /** The dump, or null when there is no window to dump. */
  static String xml(boolean compressed, boolean allWindows) throws IOException {
    try {
      return dump(compressed, allWindows);
    } finally {
      views = null;
    }
  }

  private static String dump(boolean compressed, boolean allWindows) throws IOException {
    Display display = AdbShell.app().getSystemService(WindowManager.class).getDefaultDisplay();
    Point size = new Point();
    display.getRealSize(size);
    int rotation = display.getRotation();
    XmlSerializer s = Xml.newSerializer();
    StringWriter w = new StringWriter();
    s.setOutput(w);
    s.startDocument("UTF-8", true);
    if (allWindows) {
      List<AccessibilityWindowInfo> windows = ShadowSimUiAutomation.windowInfos();
      if (windows.isEmpty()) return null;
      s.startTag("", "displays");
      s.startTag("", "display");
      attr(s, "id", Integer.toString(Display.DEFAULT_DISPLAY));
      for (int i = 0; i < windows.size(); i++) window(windows.get(i), s, i, size, rotation, compressed);
      s.endTag("", "display");
      s.endTag("", "displays");
    } else {
      // The active window's root read through its connection, as a service gets it: that one lists the views not
      // important for accessibility among its children (the view's own createAccessibilityNodeInfo leaves them out).
      AccessibilityNodeInfo root = null;
      for (AccessibilityWindowInfo win : ShadowSimUiAutomation.windowInfos()) {
        if (win.isActive()) {
          root = win.getRoot();
          break;
        }
      }
      if (root == null) root = ShadowSimUiAutomation.activeRoot();
      if (root == null) return null;
      s.startTag("", "hierarchy");
      attr(s, "rotation", Integer.toString(rotation));
      node(root, s, 0, size, compressed);
      s.endTag("", "hierarchy");
    }
    s.endDocument();
    return w.toString();
  }

  private static void window(AccessibilityWindowInfo win, XmlSerializer s, int index, Point size, int rotation,
      boolean compressed) throws IOException {
    s.startTag("", "window");
    attr(s, "index", Integer.toString(index));
    CharSequence title = win.getTitle();
    attr(s, "title", title != null ? title.toString() : "");
    Rect bounds = new Rect();
    win.getBoundsInScreen(bounds);
    attr(s, "bounds", bounds.toShortString());
    attr(s, "active", Boolean.toString(win.isActive()));
    attr(s, "focused", Boolean.toString(win.isFocused()));
    attr(s, "accessibility-focused", Boolean.toString(win.isAccessibilityFocused()));
    attr(s, "id", Integer.toString(win.getId()));
    attr(s, "layer", Integer.toString(win.getLayer()));
    attr(s, "type", AccessibilityWindowInfo.typeToString(win.getType()));
    AccessibilityNodeInfo root = win.getRoot();
    if (root != null) {
      s.startTag("", "hierarchy");
      attr(s, "rotation", Integer.toString(rotation));
      node(root, s, 0, size, compressed);
      s.endTag("", "hierarchy");
    }
    s.endTag("", "window");
  }

  private static void node(AccessibilityNodeInfo n, XmlSerializer s, int index, Point size, boolean compressed)
      throws IOException {
    s.startTag("", "node");
    if (!nafExcluded(n) && !nafCheck(n, compressed)) attr(s, "NAF", "true");
    attr(s, "index", Integer.toString(index));
    attr(s, "text", safe(n.getText()));
    attr(s, "resource-id", safe(n.getViewIdResourceName()));
    attr(s, "class", safe(n.getClassName()));
    attr(s, "package", safe(n.getPackageName()));
    attr(s, "content-desc", safe(n.getContentDescription()));
    attr(s, "checkable", Boolean.toString(n.isCheckable()));
    attr(s, "checked", Boolean.toString(n.isChecked()));
    attr(s, "clickable", Boolean.toString(n.isClickable()));
    attr(s, "enabled", Boolean.toString(n.isEnabled()));
    attr(s, "focusable", Boolean.toString(n.isFocusable()));
    attr(s, "focused", Boolean.toString(n.isFocused()));
    attr(s, "scrollable", Boolean.toString(n.isScrollable()));
    attr(s, "long-clickable", Boolean.toString(n.isLongClickable()));
    attr(s, "password", Boolean.toString(n.isPassword()));
    attr(s, "selected", Boolean.toString(n.isSelected()));
    attr(s, "bounds", visibleBounds(n, size).toShortString());
    List<AccessibilityNodeInfo> kids = children(n, compressed);
    for (int i = 0; i < kids.size(); i++) {
      AccessibilityNodeInfo c = kids.get(i);
      // The tool skips null children and those not visible to the user, keeping the others' indices.
      if (c != null && c.isVisibleToUser()) node(c, s, i, size, compressed);
    }
    s.endTag("", "node");
  }

  /**
   * Every attribute goes through here: its value made legal XML ({@link #safe}), whatever it came from (an app's
   * window title, a class or package name), so no attribute can break the serializer or the parse.
   */
  private static void attr(XmlSerializer s, String name, String value) throws IOException {
    s.attribute("", name, safe(value));
  }

  /**
   * The node's children, a null where one could not be read (it keeps its index, as in the tool); compressed, a view
   * not important for accessibility is replaced by its own children.
   */
  private static List<AccessibilityNodeInfo> children(AccessibilityNodeInfo n, boolean compressed) {
    List<AccessibilityNodeInfo> out = new ArrayList<>();
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo c = n.getChild(i);
      if (c != null && compressed && !important(c)) out.addAll(children(c, true));
      else out.add(c);
    }
    return out;
  }

  /**
   * Whether a compressed dump keeps the node, as the system decides for a service that does not ask for views not
   * important for accessibility: a view by its own View.isImportantForAccessibility (true for a provider host such as
   * AndroidComposeView, an actionable view, one with listeners, a delegate or a live region), whatever its node says
   * (Compose marks its root node unimportant); a provider's virtual node always.
   */
  private static boolean important(AccessibilityNodeInfo n) {
    long source = n.getSourceNodeId();
    int virtual = AccessibilityNodeInfo.getVirtualDescendantId(source);
    // A provider host's own node comes from the provider (HOST_VIEW_ID), so it counts as the view's.
    boolean view = virtual == AccessibilityNodeInfo.UNDEFINED_ITEM_ID
        || virtual == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID;
    if (!view) return true;
    View v = viewById(AccessibilityNodeInfo.getAccessibilityViewId(source));
    return v != null ? v.isImportantForAccessibility() : n.isImportantForAccessibility();
  }

  /**
   * The shown windows' views by accessibility view id, made by one walk the first time a dump needs it and kept until
   * that dump ends ({@link #xml}), so each lookup is constant time. Main thread only, as dumps are.
   */
  private static java.util.Map<Integer, View> views;

  private static View viewById(int accessibilityViewId) {
    if (views == null) {
      views = new java.util.HashMap<>();
      for (View root : ShadowSimUiAutomation.rootViews()) index(root);
    }
    return views.get(accessibilityViewId);
  }

  private static void index(View v) {
    views.put(v.getAccessibilityViewId(), v);
    if (v instanceof ViewGroup g) {
      for (int i = 0; i < g.getChildCount(); i++) index(g.getChildAt(i));
    }
  }

  /** The tool's: bounds in screen intersected with the display, empty when outside it. */
  private static Rect visibleBounds(AccessibilityNodeInfo n, Point size) {
    Rect r = new Rect();
    n.getBoundsInScreen(r);
    if (!r.intersect(new Rect(0, 0, size.x, size.y))) r.setEmpty();
    return r;
  }

  /**
   * NAF ("not accessibility friendly"): clickable and enabled with no text or description, nor any below it. Below
   * means the tree being dumped: compressed, a device's service never sees the views left out, so a label only they
   * carry does not count.
   */
  private static boolean nafCheck(AccessibilityNodeInfo n, boolean compressed) {
    boolean naf = n.isClickable() && n.isEnabled() && safe(n.getContentDescription()).isEmpty() && safe(n.getText()).isEmpty();
    return !naf || childNafCheck(n, compressed);
  }

  private static boolean childNafCheck(AccessibilityNodeInfo n, boolean compressed) {
    for (AccessibilityNodeInfo c : children(n, compressed)) {
      if (c == null) continue;
      if (!safe(c.getContentDescription()).isEmpty() || !safe(c.getText()).isEmpty()) return true;
      if (childNafCheck(c, compressed)) return true;
    }
    return false;
  }

  private static boolean nafExcluded(AccessibilityNodeInfo n) {
    String cls = safe(n.getClassName());
    for (String x : NAF_EXCLUDED_CLASSES) if (cls.endsWith(x)) return true;
    return false;
  }

  /**
   * The tool's safeCharSeqToString: characters XML 1.1 discourages become '.', and so do those XML 1.0 does not allow
   * at all (U+0000, U+FFFE, U+FFFF, lone surrogates), so the dump always parses.
   */
  private static String safe(CharSequence cs) {
    if (cs == null) return "";
    StringBuilder b = new StringBuilder(cs.length());
    for (int i = 0; i < cs.length(); i++) {
      char ch = cs.charAt(i);
      boolean bad = (ch >= 0x1 && ch <= 0x8) || (ch >= 0xB && ch <= 0xC) || (ch >= 0xE && ch <= 0x1F)
          || (ch >= 0x7F && ch <= 0x84) || (ch >= 0x86 && ch <= 0x9F) || (ch >= 0xFDD0 && ch <= 0xFDDF)
          || ch == 0 || ch == 0xFFFE || ch == 0xFFFF;
      if (Character.isHighSurrogate(ch) && i + 1 < cs.length() && Character.isLowSurrogate(cs.charAt(i + 1))) {
        b.append(ch).append(cs.charAt(++i));
        continue;
      }
      if (Character.isSurrogate(ch)) bad = true;
      b.append(bad ? '.' : ch);
    }
    return b.toString();
  }
}
