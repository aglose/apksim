package dev.apksim;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.InsetsController;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.RoundedCorners;
import android.view.WindowInsets;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * What a skin tells the app (issue 245): its cutout as the display's {@link DisplayCutout} and its corner radius as
 * {@link RoundedCorners}, from {@link Display#getCutout}/{@link Display#getRoundedCorner} and from every window's
 * {@code WindowInsets} (getDisplayCutout, getRoundedCorner, Type.displayCutout()), as a device's window manager puts
 * them in each window's insets state. With no skin nothing changes: Robolectric's display has neither. In display
 * pixels, for the display's size and rotation now (Skinning).
 */
final class SkinShape {
  private SkinShape() {}

  /** A skin has been on: from then on every insets state gets the current values, none once it is off again. */
  private static volatile boolean used;

  static Skinning.Layout layout() {
    if (Skinning.current() == null) return null;
    int[] d = display();
    Skinning.Layout l = Skinning.layout(d[0], d[1], d[2]);
    if (l != null) used = true;
    return l;
  }

  /**
   * The display as the framework has it (width, height, rotation): what the app's windows are laid out on, in running
   * instances and test runs alike (UiDevice's rotation turns it without DeviceControls).
   */
  static int[] display() {
    try {
      android.view.DisplayInfo di = android.hardware.display.DisplayManagerGlobal.getInstance().getDisplayInfo(Display.DEFAULT_DISPLAY);
      if (di != null && di.logicalWidth > 0) return new int[] {di.logicalWidth, di.logicalHeight, di.rotation};
    } catch (RuntimeException e) {
      // before the framework is up: the device
    }
    Device d = Device.current();
    return new int[] {d.widthPx(), d.heightPx(), rotation};
  }

  /** The display's rotation (Surface.ROTATION_*), kept by DeviceControls as it turns the display. */
  static volatile int rotation;

  /** The skin's cutout on the display, NO_CUTOUT when it has none, null with no skin. */
  static DisplayCutout cutout() {
    Skinning.Layout l = layout();
    if (l == null) return null;
    int[] c = l.cutout();
    if (c == null) return DisplayCutout.NO_CUTOUT;
    int w = l.screenW(), h = l.screenH();
    Rect r = new Rect(c[0], c[1], c[2], c[3]);
    // The side it is on is the nearest edge to its centre; the safe inset on that side clears it.
    double cx = r.exactCenterX(), cy = r.exactCenterY();
    double[] dist = {cx, cy, w - cx, h - cy};
    int side = 0;
    for (int i = 1; i < 4; i++) if (dist[i] < dist[side]) side = i;
    return switch (side) {
      case 0 -> new DisplayCutout(Insets.of(r.right, 0, 0, 0), r, null, null, null);
      case 1 -> new DisplayCutout(Insets.of(0, r.bottom, 0, 0), null, r, null, null);
      case 2 -> new DisplayCutout(Insets.of(0, 0, w - r.left, 0), null, null, r, null);
      default -> new DisplayCutout(Insets.of(0, 0, 0, h - r.top), null, null, null, r);
    };
  }

  /** The screen's corners (the skin's cornerRadius), NO_ROUNDED_CORNERS for square ones, null with no skin. */
  static RoundedCorners corners() {
    Skinning.Layout l = layout();
    if (l == null) return null;
    int r = l.cornerRadius();
    if (r <= 0) return RoundedCorners.NO_ROUNDED_CORNERS;
    int w = l.screenW(), h = l.screenH();
    RoundedCorner[] c = new RoundedCorner[4];
    c[RoundedCorner.POSITION_TOP_LEFT] = new RoundedCorner(RoundedCorner.POSITION_TOP_LEFT, r, r, r);
    c[RoundedCorner.POSITION_TOP_RIGHT] = new RoundedCorner(RoundedCorner.POSITION_TOP_RIGHT, r, w - r, r);
    c[RoundedCorner.POSITION_BOTTOM_RIGHT] = new RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, r, w - r, h - r);
    c[RoundedCorner.POSITION_BOTTOM_LEFT] = new RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT, r, r, h - r);
    return new RoundedCorners(c);
  }

  /** A window's insets state as the window manager sends it: the skin's cutout and corners on the display. */
  static void into(InsetsState state) {
    if (!used && Skinning.current() == null) return;
    DisplayCutout cutout = cutout();
    RoundedCorners corners = corners();
    int[] dd = display();
    state.setDisplayCutout(cutout != null ? cutout : DisplayCutout.NO_CUTOUT);
    // The cutout's safe insets as insets sources of type displayCutout(), one per side, as DisplayPolicy adds them:
    // WindowInsets.getInsets(Type.displayCutout()) is made from these, not from the cutout itself.
    int w = dd[0], h = dd[1];
    int[] sides = cutout == null ? new int[4]
        : new int[] {cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom()};
    for (int i = 0; i < 4; i++) {
      int id = InsetsSource.createId(null, i, WindowInsets.Type.displayCutout());
      if (sides[i] <= 0) {
        state.removeSource(id);
        continue;
      }
      Rect f = switch (i) {
        case 0 -> new Rect(0, 0, sides[i], h);
        case 1 -> new Rect(0, 0, w, sides[i]);
        case 2 -> new Rect(w - sides[i], 0, w, h);
        default -> new Rect(0, h - sides[i], w, h);
      };
      state.getOrCreateSource(id, WindowInsets.Type.displayCutout()).setFrame(f).setVisible(true);
    }
    state.setRoundedCorners(corners != null ? corners : RoundedCorners.NO_ROUNDED_CORNERS);
    state.setRoundedCornerFrame(new Rect(0, 0, w, h));
  }

  /** Every window gets its insets again (the skin, or the display under it, changed). */
  static void changed() {
    SystemBars.notifyWindows();
  }

  /** Display: the skin's cutout and rounded corners, Robolectric's (none) without a skin. */
  @Implements(Display.class)
  public static class ShadowSimDisplay extends ShadowDisplay {
    @RealObject private Display display;

    @Implementation(minSdk = 29)
    protected DisplayCutout getCutout() {
      DisplayCutout c = cutout();
      if (c != null) return c == DisplayCutout.NO_CUTOUT ? null : c;
      return Shadow.directlyOn(display, Display.class, "getCutout");
    }

    @Implementation(minSdk = 31)
    protected RoundedCorner getRoundedCorner(int position) {
      RoundedCorners c = corners();
      if (c != null) return c.getRoundedCorner(position);
      return Shadow.directlyOn(display, Display.class, "getRoundedCorner", ClassParameter.from(int.class, position));
    }
  }

  /** Each window's insets state, as it arrives from the window session, gets the skin's cutout and corners. */
  @Implements(value = InsetsController.class, isInAndroidSdk = false)
  public static class ShadowSimInsetsController {
    @RealObject private InsetsController controller;

    @Implementation
    protected boolean onStateChanged(InsetsState state) {
      into(state);
      return Shadow.directlyOn(controller, InsetsController.class, "onStateChanged", ClassParameter.from(InsetsState.class, state));
    }
  }
}
