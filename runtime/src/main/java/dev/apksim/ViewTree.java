package dev.apksim;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.robolectric.util.ReflectionHelpers;

/**
 * Diagnostics for what is on screen at the View level, below what `nodes` (accessibility) shows: `viewtree` lists
 * every view of every window, and `windows` says which activity owns each window and where it is in its lifecycle.
 * Views that draw nothing (alpha 0, off screen, behind another window) and windows of activities that are stopped or
 * finished are what these find.
 */
final class ViewTree {
  private ViewTree() {}

  /**
   * `viewtree [FILE]`: per view its depth, class, id, visibility, alpha, translation, scale, position on screen, size
   * and whether it has a click listener. Without FILE one line ("; " between views, as `nodes`); with FILE one view
   * per line, indented by depth, and the answer says how many.
   */
  static String run(List<View> roots, String[] a) throws java.io.IOException {
    StringBuilder b = new StringBuilder();
    boolean toFile = a.length > 1;
    int[] count = {0};
    for (int w = 0; w < roots.size(); w++) {
      View r = roots.get(w);
      if (toFile) b.append("window ").append(w).append(": ").append(window(r)).append('\n');
      else b.append("window ").append(w).append(": ").append(window(r)).append("; ");
      walk(r, 0, b, toFile, count);
    }
    if (!toFile) return b.toString();
    Path out = Storage.absolute(a[1]);
    Files.writeString(out, b);
    return "ok " + count[0] + " views in " + roots.size() + " windows: " + out;
  }

  private static void walk(View v, int depth, StringBuilder b, boolean lines, int[] count) {
    count[0]++;
    if (lines) b.append("  ".repeat(depth + 1));
    else b.append(depth).append(' ');
    describe(v, b);
    b.append(lines ? "\n" : "; ");
    if (v instanceof ViewGroup g) {
      for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, b, lines, count);
    }
  }

  private static void describe(View v, StringBuilder b) {
    b.append(v.getClass().getSimpleName().isEmpty() ? v.getClass().getName() : v.getClass().getSimpleName());
    String id = idName(v);
    if (id != null) b.append(" #").append(id);
    int[] at = new int[2];
    v.getLocationOnScreen(at);
    b.append(" @").append(at[0]).append(',').append(at[1]).append(' ').append(v.getWidth()).append('x').append(v.getHeight());
    if (v.getVisibility() == View.INVISIBLE) b.append(" invisible");
    else if (v.getVisibility() == View.GONE) b.append(" gone");
    if (v.getAlpha() != 1f) b.append(" alpha=").append(num(v.getAlpha()));
    if (v.getTranslationX() != 0f || v.getTranslationY() != 0f) {
      b.append(" translate=").append(num(v.getTranslationX())).append(',').append(num(v.getTranslationY()));
    }
    if (v.getScaleX() != 1f || v.getScaleY() != 1f) b.append(" scale=").append(num(v.getScaleX())).append(',').append(num(v.getScaleY()));
    if (v.hasOnClickListeners()) b.append(" click");
    if (!v.isEnabled()) b.append(" disabled");
  }

  private static String idName(View v) {
    int id = v.getId();
    if (id == View.NO_ID) return null;
    try {
      return v.getResources().getResourceEntryName(id);
    } catch (Resources.NotFoundException | NullPointerException e) {
      return "0x" + Integer.toHexString(id);  // a generated id (View.generateViewId) has no name
    }
  }

  private static String num(float f) {
    return f == Math.rint(f) ? String.valueOf((long) f) : String.format(Locale.ROOT, "%.2f", f);
  }

  /** The window's owner as `windows` shows it: the activity and its lifecycle flags, or the context's class. */
  static String window(View root) {
    StringBuilder b = new StringBuilder(root.getClass().getSimpleName());
    Activity owner = owner(root);
    if (owner == null) {
      b.append(" owner=").append(root.getContext().getClass().getSimpleName());
      return b.toString();
    }
    b.append(" activity=").append(owner.getClass().getName());
    if (ReflectionHelpers.<Boolean>getField(owner, "mResumed")) b.append(" resumed");
    if (ReflectionHelpers.<Boolean>getField(owner, "mStopped")) b.append(" stopped");
    if (owner.isFinishing()) b.append(" finished");
    if (owner.isDestroyed()) b.append(" destroyed");
    return b.toString();
  }

  /**
   * The activity whose window this root is (its decor view), or which a dialog or popup was created with: a phone
   * window's context is its activity, and a dialog's is a ContextThemeWrapper around one.
   */
  static Activity owner(View root) {
    Object window = null;
    try {
      window = ReflectionHelpers.getField(root, "mWindow");  // DecorView's PhoneWindow
    } catch (RuntimeException e) {
      // not a DecorView (a popup's or a toast's root)
    }
    Context c = window instanceof android.view.Window w ? w.getContext() : root.getContext();
    for (int i = 0; c != null && i < 16; i++) {
      if (c instanceof Activity act) return act;
      if (c.getClass().getSimpleName().equals("DecorContext")) {
        Object ref = ReflectionHelpers.getField(c, "mContext");
        Object inner = ref instanceof java.lang.ref.WeakReference<?> wr ? wr.get() : ref;
        if (inner instanceof Context ic && ic != c) {
          c = ic;
          continue;
        }
      }
      if (!(c instanceof ContextWrapper cw)) return null;
      c = cw.getBaseContext();
    }
    return null;
  }
}
