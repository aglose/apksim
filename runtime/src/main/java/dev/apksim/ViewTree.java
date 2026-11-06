package dev.apksim;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The `viewtree` command: every view of every window, with what decides whether it is seen and touched (visibility,
 * alpha, translation, scale, screen bounds, click listener), and for `windows`, which activity owns each window and
 * where it is in its lifecycle. Found what an accessibility dump hides: alpha-0 views drawn every frame, a
 * SurfaceView inside a Compose AndroidView, stopped activities' windows still up.
 */
final class ViewTree {
  private ViewTree() {}

  /** Each window, then its views indented by depth. */
  static String dump(List<View> roots, Function<View, int[]> position) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < roots.size(); i++) {
      View root = roots.get(i);
      int[] at = position.apply(root);
      b.append("window ").append(i).append(": ").append(window(root, at)).append('\n');
      int[] count = {0};
      walk(root, at, 1, -1, b, count);
      b.append("  (").append(count[0]).append(count[0] == 1 ? " view)\n" : " views)\n");
    }
    return b.length() == 0 ? "(no windows)\n" : b.toString();
  }

  /** A window's line, as `windows` prints it: class, position, size, focus, visibility and owner. */
  static String window(View root, int[] at) {
    StringBuilder b = new StringBuilder();
    b.append(root.getClass().getSimpleName()).append('@').append(at[0]).append(',').append(at[1])
        .append(' ').append(root.getWidth()).append('x').append(root.getHeight())
        .append(root.hasWindowFocus() ? " focused" : "").append(visibility(root));
    String owner = owner(root);
    if (owner != null) b.append(' ').append(owner);
    return b.toString();
  }

  /**
   * The activity a window belongs to (its Window's context: an activity's own window, or a dialog's), as
   * "Name{resumed}", with the lifecycle flags Activity keeps (mResumed, mStopped, mFinished, mDestroyed); "paused"
   * when none is set (between pause and stop, or created and not yet resumed). Null for windows of no activity.
   */
  static String owner(View root) {
    Activity a = activityOf(root);
    if (a == null) return null;
    StringBuilder flags = new StringBuilder();
    for (String[] f : FLAGS) {
      if (flag(a, f[0])) flags.append(flags.length() == 0 ? "" : ",").append(f[1]);
    }
    String name = a.getClass().getName();
    String pkg = a.getPackageName();
    if (pkg != null && name.startsWith(pkg + ".")) name = name.substring(pkg.length());
    return name + "{" + (flags.length() == 0 ? "paused" : flags) + "}";
  }

  private static final String[][] FLAGS = {
    {"mResumed", "resumed"}, {"mStopped", "stopped"}, {"mFinished", "finishing"}, {"mDestroyed", "destroyed"}};

  private static Activity activityOf(View root) {
    Context c = null;
    try {
      // DecorView.mWindow: its context is the activity (or the context a dialog was made with).
      Field f = root.getClass().getDeclaredField("mWindow");
      f.setAccessible(true);
      Object w = f.get(root);
      if (w instanceof android.view.Window win) c = win.getContext();
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Not a DecorView (a toast's or a popup's own view): its context is the next best.
    }
    if (c == null) c = root.getContext();
    for (int i = 0; i < 20 && c != null; i++) {
      if (c instanceof Activity a) return a;
      if (!(c instanceof ContextWrapper w)) return null;
      c = w.getBaseContext();
    }
    return null;
  }

  private static boolean flag(Activity a, String name) {
    try {
      Field f = Activity.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.getBoolean(a);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  /**
   * One view per line: class, id name, screen bounds, then only what differs from a plain visible view. composeDepth
   * is the depth below the nearest AndroidComposeView (-1 outside one): its children are AndroidViewsHandler, whose
   * children are the AndroidView holders (each a ViewGroup holding the app's view), and RippleContainer.
   */
  private static void walk(View v, int[] window, int depth, int composeDepth, StringBuilder b, int[] count) {
    count[0]++;
    b.append("  ".repeat(depth)).append(name(v));
    String id = idName(v);
    if (id != null) b.append(" #").append(id);
    int[] loc = new int[2];
    v.getLocationInWindow(loc);
    b.append(" @").append(window[0] + loc[0]).append(',').append(window[1] + loc[1])
        .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
    b.append(visibility(v));
    if (v.getAlpha() != 1f) b.append(" alpha=").append(num(v.getAlpha()));
    if (v.getTranslationX() != 0f || v.getTranslationY() != 0f) {
      b.append(" translate=").append(num(v.getTranslationX())).append(',').append(num(v.getTranslationY()));
    }
    if (v.getTranslationZ() != 0f) b.append(" z=").append(num(v.getTranslationZ()));
    if (v.getScaleX() != 1f || v.getScaleY() != 1f) b.append(" scale=").append(num(v.getScaleX())).append(',').append(num(v.getScaleY()));
    if (v.getRotation() != 0f) b.append(" rotation=").append(num(v.getRotation()));
    if (v.hasOnClickListeners()) b.append(" click");
    if (v.isLongClickable()) b.append(" longclick");
    if (v.isFocused()) b.append(" focused");
    boolean compose = isCompose(v);
    if (compose) b.append(" [compose]");
    else if (composeDepth == 1 && v instanceof ViewGroup) b.append(" [AndroidView]");  // not RippleContainer's RippleHostViews
    b.append('\n');
    if (v instanceof ViewGroup g) {
      int next = compose ? 0 : composeDepth < 0 ? -1 : composeDepth + 1;
      for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), window, depth + 1, next, b, count);
    }
  }

  /** Compose's own view, by name or, in minified apps, by the tag its composition sets on it. */
  private static boolean isCompose(View v) {
    if (v.getClass().getName().endsWith("AndroidComposeView")) return true;
    int tag = composeTag(v);
    return tag != 0 && v.getTag(tag) != null;
  }

  private static int composeTagId = -1;

  /** androidx.compose.ui's R.id.wrapped_composition_tag, set on each AndroidComposeView. */
  private static int composeTag(View v) {
    if (composeTagId < 0) {
      Context c = v.getContext().getApplicationContext();
      composeTagId = c == null ? 0 : c.getResources().getIdentifier("wrapped_composition_tag", "id", c.getPackageName());
    }
    return composeTagId;
  }

  private static String name(View v) {
    String n = v.getClass().getSimpleName();
    return n.isEmpty() ? v.getClass().getName() : n;
  }

  private static String idName(View v) {
    int id = v.getId();
    if (id == View.NO_ID) return null;
    try {
      return v.getResources().getResourceEntryName(id);
    } catch (Resources.NotFoundException | NullPointerException e) {
      return "0x" + Integer.toHexString(id);
    }
  }

  private static String visibility(View v) {
    return v.getVisibility() == View.VISIBLE ? "" : v.getVisibility() == View.INVISIBLE ? " invisible" : " gone";
  }

  private static String num(float f) {
    return f == Math.rint(f) ? Integer.toString((int) f) : String.format(Locale.ROOT, "%.2f", f);
  }
}
