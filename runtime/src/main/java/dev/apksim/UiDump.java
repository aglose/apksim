package dev.apksim;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Xml;
import android.view.Display;
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
      s.attribute("", "id", Integer.toString(Display.DEFAULT_DISPLAY));
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
      s.attribute("", "rotation", Integer.toString(rotation));
      node(root, s, 0, size, compressed);
      s.endTag("", "hierarchy");
    }
    s.endDocument();
    return w.toString();
  }

  private static void window(AccessibilityWindowInfo win, XmlSerializer s, int index, Point size, int rotation,
      boolean compressed) throws IOException {
    s.startTag("", "window");
    s.attribute("", "index", Integer.toString(index));
    CharSequence title = win.getTitle();
    s.attribute("", "title", title != null ? title.toString() : "");
    Rect bounds = new Rect();
    win.getBoundsInScreen(bounds);
    s.attribute("", "bounds", bounds.toShortString());
    s.attribute("", "active", Boolean.toString(win.isActive()));
    s.attribute("", "focused", Boolean.toString(win.isFocused()));
    s.attribute("", "accessibility-focused", Boolean.toString(win.isAccessibilityFocused()));
    s.attribute("", "id", Integer.toString(win.getId()));
    s.attribute("", "layer", Integer.toString(win.getLayer()));
    s.attribute("", "type", AccessibilityWindowInfo.typeToString(win.getType()));
    AccessibilityNodeInfo root = win.getRoot();
    if (root != null) {
      s.startTag("", "hierarchy");
      s.attribute("", "rotation", Integer.toString(rotation));
      node(root, s, 0, size, compressed);
      s.endTag("", "hierarchy");
    }
    s.endTag("", "window");
  }

  private static void node(AccessibilityNodeInfo n, XmlSerializer s, int index, Point size, boolean compressed)
      throws IOException {
    s.startTag("", "node");
    if (!nafExcluded(n) && !nafCheck(n)) s.attribute("", "NAF", "true");
    s.attribute("", "index", Integer.toString(index));
    s.attribute("", "text", safe(n.getText()));
    s.attribute("", "resource-id", safe(n.getViewIdResourceName()));
    s.attribute("", "class", safe(n.getClassName()));
    s.attribute("", "package", safe(n.getPackageName()));
    s.attribute("", "content-desc", safe(n.getContentDescription()));
    s.attribute("", "checkable", Boolean.toString(n.isCheckable()));
    s.attribute("", "checked", Boolean.toString(n.isChecked()));
    s.attribute("", "clickable", Boolean.toString(n.isClickable()));
    s.attribute("", "enabled", Boolean.toString(n.isEnabled()));
    s.attribute("", "focusable", Boolean.toString(n.isFocusable()));
    s.attribute("", "focused", Boolean.toString(n.isFocused()));
    s.attribute("", "scrollable", Boolean.toString(n.isScrollable()));
    s.attribute("", "long-clickable", Boolean.toString(n.isLongClickable()));
    s.attribute("", "password", Boolean.toString(n.isPassword()));
    s.attribute("", "selected", Boolean.toString(n.isSelected()));
    s.attribute("", "bounds", visibleBounds(n, size).toShortString());
    List<AccessibilityNodeInfo> kids = children(n, compressed);
    for (int i = 0; i < kids.size(); i++) {
      AccessibilityNodeInfo c = kids.get(i);
      // The tool skips children that are not visible to the user, keeping the others' indices.
      if (c.isVisibleToUser()) node(c, s, i, size, compressed);
    }
    s.endTag("", "node");
  }

  /** The node's children; compressed, a view not important for accessibility is replaced by its own children. */
  private static List<AccessibilityNodeInfo> children(AccessibilityNodeInfo n, boolean compressed) {
    List<AccessibilityNodeInfo> out = new ArrayList<>();
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo c = n.getChild(i);
      if (c == null) continue;
      if (compressed && isView(c) && !c.isImportantForAccessibility()) out.addAll(children(c, true));
      else out.add(c);
    }
    return out;
  }

  /** A view's own node, not a virtual one of a provider (Compose semantics are always important). */
  private static boolean isView(AccessibilityNodeInfo n) {
    int virtual = AccessibilityNodeInfo.getVirtualDescendantId(n.getSourceNodeId());
    return virtual == AccessibilityNodeInfo.UNDEFINED_ITEM_ID || virtual == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID;
  }

  /** The tool's: bounds in screen intersected with the display, left as they are when outside it. */
  private static Rect visibleBounds(AccessibilityNodeInfo n, Point size) {
    Rect r = new Rect();
    n.getBoundsInScreen(r);
    r.intersect(new Rect(0, 0, size.x, size.y));
    return r;
  }

  /** NAF ("not accessibility friendly"): clickable and enabled with no text or description, nor any below it. */
  private static boolean nafCheck(AccessibilityNodeInfo n) {
    boolean naf = n.isClickable() && n.isEnabled() && safe(n.getContentDescription()).isEmpty() && safe(n.getText()).isEmpty();
    return !naf || childNafCheck(n);
  }

  private static boolean childNafCheck(AccessibilityNodeInfo n) {
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo c = n.getChild(i);
      if (c == null) continue;
      if (!safe(c.getContentDescription()).isEmpty() || !safe(c.getText()).isEmpty()) return true;
      if (childNafCheck(c)) return true;
    }
    return false;
  }

  private static boolean nafExcluded(AccessibilityNodeInfo n) {
    String cls = safe(n.getClassName());
    for (String x : NAF_EXCLUDED_CLASSES) if (cls.endsWith(x)) return true;
    return false;
  }

  /** The tool's safeCharSeqToString: characters XML 1.1 discourages become '.'. */
  private static String safe(CharSequence cs) {
    if (cs == null) return "";
    StringBuilder b = new StringBuilder(cs.length());
    for (int i = 0; i < cs.length(); i++) {
      char ch = cs.charAt(i);
      boolean bad = (ch >= 0x1 && ch <= 0x8) || (ch >= 0xB && ch <= 0xC) || (ch >= 0xE && ch <= 0x1F)
          || (ch >= 0x7F && ch <= 0x84) || (ch >= 0x86 && ch <= 0x9F) || (ch >= 0xFDD0 && ch <= 0xFDDF);
      b.append(bad ? '.' : ch);
    }
    return b.toString();
  }
}
