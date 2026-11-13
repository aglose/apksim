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
  /** A folding device's displays in pixels, natural orientation, at one density. */
  record Spec(String device, int innerW, int innerH, int coverW, int coverH, float density) {
    Device display(State s) {
      int wDp = Math.round((s == State.CLOSED ? coverW : innerW) / density);
      int hDp = Math.round((s == State.CLOSED ? coverH : innerH) / density);
      // The pixels the framework makes of whole dp at this density (it rounds down), so the window and the display
      // agree: the cover display comes out 1079 px wide, one short of the panel's 1080.
      return new Device(device, "foldable", wDp, hDp, density, (int) (wDp * density), (int) (hDp * density));
    }
  }

  enum State { OPEN, HALF, CLOSED }

  // Pixel 10 Pro Fold: the panels' pixels are Google's; 390 dpi is the Pixel 9 Pro Fold's emulator definition, not
  // read from a device (852x883 dp open, 443x970 dp closed).
  private static final Spec[] SPECS = {
    new Spec("pixel-10-pro-fold", 2076, 2152, 1080, 2364, 390 / 160f),
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

  static List<Device> devices() {
    List<Device> out = new java.util.ArrayList<>();
    for (Spec s : SPECS) out.add(s.display(State.OPEN));
    return out;
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
   * The fold of a display w x h pixels at this rotation, as {left, top, right, bottom} in display pixels (a line: no
   * width), or null when the device has none or is closed. In the natural orientation it runs top to bottom.
   */
  public static int[] bounds(int w, int h, int rotation) {
    if (spec(Device.peek()) == null || state() == State.CLOSED) return null;
    return rotation % 2 == 0 ? new int[] {w / 2, 0, w / 2, h} : new int[] {0, h / 2, w, h / 2};
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
    if (spec(d) == null) return "error: " + d.name() + " does not fold (--device " + SPECS[0].device + ")";
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
    if (spec(d) == null) return d.name() + " does not fold (device " + SPECS[0].device + ")";
    int[] b = bounds(w, h, rotation);
    String posture = switch (state()) {
      case OPEN -> "open";
      case HALF -> "half";
      case CLOSED -> "closed";
    };
    float deg = angle();
    return posture + ", hinge " + (deg == Math.rint(deg) ? String.valueOf((int) deg) : String.valueOf(deg)) + " (" + d.describe() + ", " + w + "x" + h + " px"
        + (b == null ? ", cover display" : ", fold " + (b[0] == b[2] ? "x=" + b[0] : "y=" + b[1])) + ")";
  }
}
