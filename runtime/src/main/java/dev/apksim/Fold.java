package dev.apksim;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A folding device: two displays, of which the app sees one at a time as its one logical display, and the posture
 * (`fold open|half|closed`, `--fold`). Closed, the app has the cover display; open or half-open, the inner one with a
 * fold across its middle, which apps read through Jetpack WindowManager (the androidx.window.extensions stand-in:
 * {@link FoldExtensions}). Switching between the displays is a display size change, as on a device
 * (DeviceControls.reshape). Plain Java: Device uses it before the framework exists, and in serve's JVM.
 */
public final class Fold {
  /**
   * How a device folds. BOOK: one hinge down the middle of the inner display (natural orientation), the left half closing
   * over the right with the cover display on its back. CLAMSHELL: one hinge across the middle, the inner display folding
   * in half top to bottom, the cover display on the back of the top half (upright with the hinge at its bottom).
   * TRIFOLD: two hinges, at a third and two thirds of the inner display's width, folded inward one after the other (a G
   * fold): the left panel first, onto the middle one, then the right over both, with the cover display on its back.
   */
  enum Hinge { BOOK, CLAMSHELL, TRIFOLD }

  /**
   * A folding device's displays in pixels, natural orientation, each with its density and its diagonal in inches (the
   * panel's physical size: the Mac window keeps the device's size on screen through a fold), and the thickness of one
   * panel unfolded against the open device's height in its natural orientation (the fold animation's edges).
   */
  record Spec(String device, Hinge hinge, int innerW, int innerH, float innerDensity, float innerInches,
      int coverW, int coverH, float coverDensity, float coverInches, float panelMm, float openHeightMm) {
    Device display(State s) {
      boolean closed = s == State.CLOSED;
      float density = closed ? coverDensity : innerDensity;
      int wDp = Math.round((closed ? coverW : innerW) / density);
      int hDp = Math.round((closed ? coverH : innerH) / density);
      // The pixels the framework makes of whole dp at this density (it rounds down), so the window and the display
      // agree: the Pixel's cover display comes out 1079 px wide, one short of the panel's 1080.
      return new Device(device, "foldable", wDp, hDp, density, (int) (wDp * density), (int) (hDp * density));
    }

    /** The panel's pixels per inch, open (the inner display) or closed (the cover display). */
    double ppi(boolean closed) {
      return closed ? Math.hypot(coverW, coverH) / coverInches : Math.hypot(innerW, innerH) / innerInches;
    }

    /** The hinges' positions across the inner display, as fractions of its width (BOOK, TRIFOLD) or height (CLAMSHELL). */
    float[] hinges() {
      return hinge == Hinge.TRIFOLD ? new float[] {1 / 3f, 2 / 3f} : new float[] {0.5f};
    }
  }

  enum State { OPEN, HALF, CLOSED }

  // The panels' pixels and sizes are the makers' published ones; the densities are not read from devices. Pixel 10 Pro
  // Fold: 390 dpi is the Pixel 9 Pro Fold's emulator definition (852x883 dp open, 443x970 dp closed). Samsung: 420 dpi
  // (Samsung's usual for FHD+ panels), the TriFold's 10-inch inner display 320 dpi (a tablet's: 1080x792 dp).
  private static final Spec[] SPECS = {
    new Spec("pixel-10-pro-fold", Hinge.BOOK, 2076, 2152, 390 / 160f, 8.0f, 1080, 2364, 390 / 160f, 6.4f, 5.2f, 155.2f),
    // Galaxy Z Fold7: 8.0" 1968x2184 inside, 6.5" 1080x2520 cover; 143.2 x 158.4 x 4.2 mm open.
    new Spec("galaxy-z-fold7", Hinge.BOOK, 1968, 2184, 420 / 160f, 8.0f, 1080, 2520, 420 / 160f, 6.5f, 4.2f, 158.4f),
    // Galaxy Z Flip7: 6.9" 1080x2520 inside, 4.1" 948x1048 FlexWindow; 75.2 x 166.7 x 6.5 mm open.
    new Spec("galaxy-z-flip7", Hinge.CLAMSHELL, 1080, 2520, 420 / 160f, 6.9f, 948, 1048, 420 / 160f, 4.1f, 6.5f, 166.7f),
    // Galaxy Z TriFold: 10.0" 2160x1584 inside (wider than tall), 6.5" 1080x2520 cover; 214.1 x 159.2 mm open, its
    // thinnest panel 3.9 mm.
    new Spec("galaxy-z-trifold", Hinge.TRIFOLD, 2160, 1584, 320 / 160f, 10.0f, 1080, 2520, 420 / 160f, 6.5f, 3.9f, 159.2f),
  };

  /** Set by the `fold` command; until then the launch's --fold (read each time: a standby boots before it is known). */
  private static volatile State state;
  /** The hinge's angle in degrees when `fold angle N` gave one; else the posture's (0, 90, 180). */
  private static volatile Float angle;
  private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

  private Fold() {}

  static Spec spec(String device) {
    for (Spec s : SPECS) if (s.device.equals(device)) return s;
    return null;
  }

  static Spec spec(Device d) {
    return d == null ? null : spec(d.name());
  }

  /** Every folding device, open: Device's presets. */
  static List<Device> devices() {
    List<Device> out = new java.util.ArrayList<>();
    for (Spec s : SPECS) out.add(s.display(State.OPEN));
    return out;
  }

  /** The folding devices' names, for messages. */
  static String names() {
    StringBuilder sb = new StringBuilder();
    for (Spec s : SPECS) sb.append(sb.length() > 0 ? ", " : "").append(s.device);
    return sb.toString();
  }

  static State state() {
    State s = state;
    return s != null ? s : parse(System.getProperty("apksim.fold", "open"));
  }

  static State parse(String s) {
    return switch (s) {
      case "open", "flat" -> State.OPEN;
      case "half", "half-open", "half-opened" -> State.HALF;
      case "closed", "close", "folded" -> State.CLOSED;
      default -> null;
    };
  }

  /** The posture, with the hinge at its usual angle; null: the launch's --fold again (a test's setup). */
  static void set(State s) {
    set(s, null);
  }

  static void set(State s, Float degrees) {
    state = s;
    angle = degrees;
    if (spec(Device.peek()) != null) Sensors.hingeChanged();  // only with the framework up: a `fold` command, a test
  }

  /** The hinge's angle in degrees: 0 closed, 180 flat. What the hinge-angle sensor reports (Sensors). */
  static float angle() {
    Float a = angle;
    if (a != null) return a;
    return switch (state()) {
      case OPEN -> 180f;
      case HALF -> 90f;
      case CLOSED -> 0f;
    };
  }

  /** The posture a hinge angle is, by the emulator's ranges for a foldable: 0-30 closed, 30-150 half-open, 150-180 open. */
  static State stateAt(float degrees) {
    return degrees < 30 ? State.CLOSED : degrees < 150 ? State.HALF : State.OPEN;
  }

  /** `fold`'s argument as a posture and, for `angle N`, the angle; null when it is neither. */
  static Object[] target(String[] a, State was) {
    if (a[1].equals("toggle")) return new Object[] {was == State.CLOSED ? State.OPEN : State.CLOSED, null};
    if (a[1].equals("angle")) {
      if (a.length < 3) return null;
      float deg;
      try {
        deg = Float.parseFloat(a[2]);
      } catch (NumberFormatException e) {
        return null;
      }
      if (!(deg >= 0 && deg <= 180)) return null;
      return new Object[] {stateAt(deg), deg};
    }
    State to = parse(a[1]);
    return to == null ? null : new Object[] {to, null};
  }

  /** The display a launch starts on: a folding device launched with --fold closed has its cover display. */
  static Device initial(Device d) {
    Spec s = spec(d);
    State st = state();
    if (s == null) return d;
    if (st == null) throw new IllegalArgumentException("--fold takes open, half or closed");
    return s.display(st);
  }

  /**
   * The folds of a display w x h pixels, each {left, top, right, bottom} in display pixels (a line: no width), or null
   * when the device has none or is closed. In the natural orientation a book's and a tri-fold's run top to bottom, a
   * clamshell's left to right. Whether the display is turned is read from its shape against the inner panel's, not
   * from the rotation: the framework counts a display wider than tall as turned (ROTATION_90), and the tri-fold's
   * inner display is wider than tall in its natural orientation. (rotation is kept for callers; it is not needed.)
   */
  public static int[][] bounds(int w, int h, int rotation) {
    Spec s = spec(Device.peek());
    if (s == null || state() == State.CLOSED) return null;
    boolean turned = w != h && (w > h) != (s.innerW > s.innerH);
    boolean vertical = (s.hinge == Hinge.CLAMSHELL) == turned;  // the line runs top to bottom
    float[] at = s.hinges();
    int[][] out = new int[at.length][];
    for (int i = 0; i < at.length; i++) {
      // A quarter turn the other way puts the first hinge at the other end: ROTATION_90 turns the natural left to the
      // bottom, ROTATION_270 to the top. Thirds and halves come out the same either way.
      if (vertical) {
        int x = Math.round(w * at[i]);
        out[i] = new int[] {x, 0, x, h};
      } else {
        int y = Math.round(h * at[i]);
        out[i] = new int[] {0, y, w, y};
      }
    }
    return out;
  }

  public static boolean halfOpened() {
    return state() == State.HALF;
  }

  /** Called on the main thread after the posture or the display changed. */
  public static void onChange(Runnable r) {
    LISTENERS.add(r);
  }

  static void changed() {
    for (Runnable r : LISTENERS) r.run();
  }

  /**
   * `fold` in a test run, where nothing resizes the display: open and half-open change places (the fold's state), but
   * the cover display is only a launch's (--fold closed).
   */
  static String testRunCommand(String[] a) {
    Device d = Device.current();
    if (spec(d) == null) return "error: " + d.name() + " does not fold (--device " + names() + ")";
    State was = state();
    if (a.length < 2) return was.name().toLowerCase(java.util.Locale.ROOT);
    Object[] target = target(a, was);
    if (target == null) return "usage: fold [open|half|angle DEGREES]";
    State to = (State) target[0];
    if ((to == State.CLOSED) != (was == State.CLOSED)) {
      return "error: a test run keeps its display: launch with --fold " + (to == State.CLOSED ? "closed" : "open");
    }
    set(to, (Float) target[1]);
    changed();
    return "ok";
  }

  static String describe(int w, int h, int rotation) {
    Device d = Device.current();
    if (spec(d) == null) return d.name() + " does not fold (device " + names() + ")";
    int[][] b = bounds(w, h, rotation);
    String posture = switch (state()) {
      case OPEN -> "open";
      case HALF -> "half";
      case CLOSED -> "closed";
    };
    float deg = angle();
    return posture + ", hinge " + (deg == Math.rint(deg) ? String.valueOf((int) deg) : String.valueOf(deg)) + " (" + d.describe() + ", " + w + "x" + h + " px"
        + (b == null ? ", cover display" : folds(b)) + ")";
  }

  private static String folds(int[][] b) {
    StringBuilder sb = new StringBuilder(b.length == 1 ? ", fold " : ", folds ");
    for (int i = 0; i < b.length; i++) sb.append(i > 0 ? " " : "").append(b[i][0] == b[i][2] ? "x=" + b[i][0] : "y=" + b[i][1]);
    return sb.toString();
  }
}
