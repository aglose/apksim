package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.view.InsetsController;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import java.util.List;

/**
 * The on-screen keyboard. As in the iOS Simulator, a hardware keyboard counts as connected by default, so focusing a
 * text field shows none; with the software keyboard on (Cmd+K, `keyboard soft on`, `--soft-keyboard`) a focused field
 * raises one: the app gets IME insets as on a device (imePadding, adjustResize), the window draws a keyboard over the
 * bottom of the screen, and clicks on it type. Back hides it first, as on a device.
 */
final class SoftKeyboard {
  static volatile boolean enabled = Boolean.getBoolean("apksim.softKeyboard");
  private final Application app;
  private boolean shown;
  private int height;

  SoftKeyboard(Application app) {
    this.app = app;
  }

  boolean shown() {
    return shown;
  }

  /** Follows what the app asked for: InputMethodManager.showSoftInput, or WindowInsetsController.show(ime()). */
  void tick(List<View> roots, int w, int h) {
    if (roots.isEmpty()) return;
    InsetsController c = controller(roots);
    boolean requested = shadowOf(app.getSystemService(InputMethodManager.class)).isSoftInputVisible()
        || (c != null && (c.getRequestedVisibleTypes() & WindowInsets.Type.ime()) != 0);
    boolean wanted = enabled && requested;
    if (wanted && !shown) {
      height = Math.round(h * (w > h ? 0.5f : 0.36f));
      apply(c, true, w, h);
      SimWindow.showKeyboard(height);
      shown = true;
    } else if (!wanted && shown) {
      apply(c, false, w, h);
      SimWindow.hideKeyboard();
      shown = false;
    }
  }

  /** Back, or `keyboard hide`: what tapping away the keyboard does. */
  void dismiss(List<View> roots) {
    if (roots.isEmpty()) return;
    View top = roots.get(roots.size() - 1);
    InsetsController c = controller(roots);
    if (c != null) c.hide(WindowInsets.Type.ime());
    app.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(top.getWindowToken(), 0);
  }

  /** Forces the keyboard up for the focused view (`keyboard show`). */
  void request(List<View> roots) {
    if (roots.isEmpty()) return;
    View focus = roots.get(roots.size() - 1).findFocus();
    InsetsController c = controller(roots);
    if (c != null) c.show(WindowInsets.Type.ime());
    if (focus != null) app.getSystemService(InputMethodManager.class).showSoftInput(focus, 0);
  }

  /** The IME's frame in the window's insets state: views see WindowInsets.Type.ime() of the keyboard's height. */
  private void apply(InsetsController c, boolean visible, int w, int h) {
    if (c == null) return;
    InsetsState state = new InsetsState(c.getState(), true);
    if (visible) {
      InsetsSource ime = state.getOrCreateSource(InsetsSource.ID_IME, WindowInsets.Type.ime());
      ime.setFrame(0, h - height, w, h);
      ime.setVisible(true);
      c.setRequestedVisibleTypes(WindowInsets.Type.ime(), WindowInsets.Type.ime());
    } else {
      state.removeSource(InsetsSource.ID_IME);
      c.setRequestedVisibleTypes(0, WindowInsets.Type.ime());
    }
    c.onStateChanged(state);
  }

  private static InsetsController controller(List<View> roots) {
    ViewRootImpl vri = roots.get(roots.size() - 1).getViewRootImpl();
    return vri == null ? null : vri.getInsetsController();
  }

  /** keyboard soft on|off | show | hide */
  String run(String[] a, List<View> roots) {
    String op = a.length > 1 ? a[1] : "";
    switch (op) {
      case "soft" -> {
        enabled = a.length > 2 ? a[2].equals("on") : !enabled;
        return "software keyboard " + (enabled ? "on" : "off (hardware keyboard connected)");
      }
      case "show" -> {
        request(roots);
        return "ok";
      }
      case "hide" -> {
        dismiss(roots);
        return "ok";
      }
      default -> {
        return "software keyboard " + (enabled ? "on" : "off") + ", " + (shown ? "showing (" + height + " px)" : "hidden");
      }
    }
  }
}
