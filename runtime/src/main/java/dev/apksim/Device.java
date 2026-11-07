package dev.apksim;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen shapes selectable with --device and the `device` command: a preset name, or a custom size "WxH" in dp with
 * an optional "@density" (e.g. 1024x768@1.5). The kind decides what the app is told besides the size: a TV has no
 * touchscreen, a D-pad and UI_MODE_TYPE_TELEVISION; desktop and TV have no status or navigation bar.
 */
record Device(String name, String kind, int widthDp, int heightDp, float density) {
  private static final Device[] ALL = {
    new Device("phone", "phone", 411, 891, 2f),
    new Device("foldable", "foldable", 841, 701, 2f),
    new Device("tablet", "tablet", 1280, 800, 2f),
    new Device("desktop", "desktop", 1440, 900, 2f),
    new Device("tv", "tv", 960, 540, 2f),  // 1080p at xhdpi, as Android TV reports it
  };

  /** The device the app runs on now: --device at launch, then the `device` command or the Device Type menu. */
  private static volatile Device current;

  static Device current() {
    if (current == null) current = named(System.getProperty("apksim.device", "phone"));
    return current;
  }

  /**
   * current() without fixing it: a standby's warm-up sets the device up before the launch's --device is known, and
   * must not leave its phone behind for the launch.
   */
  static Device peek() {
    Device d = current;
    return d != null ? d : named(System.getProperty("apksim.device", "phone"));
  }

  static void setCurrent(Device d) {
    current = d;
  }

  static Device named(String name) {
    for (Device d : ALL) {
      if (d.name.equals(name)) return d;
    }
    // WxHpx@Ndpi: pixels and dpi as `wm size` and `wm density` report them (1920x1080px@240dpi).
    java.util.regex.Matcher px = java.util.regex.Pattern.compile("(\\d+)x(\\d+)px@([\\d.]+)dpi").matcher(name);
    if (px.matches()) {
      float density = Float.parseFloat(px.group(3)) / 160f;
      int w = Math.round(Integer.parseInt(px.group(1)) / density);
      int h = Math.round(Integer.parseInt(px.group(2)) / density);
      if (w < 160 || h < 160 || density < 0.75f || density > 4f) throw new IllegalArgumentException("bad size: " + name);
      return new Device(name, Math.min(w, h) < 600 ? "phone" : "tablet", w, h, density);
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)(?:@([\\d.]+))?").matcher(name);
    if (m.matches()) {
      int w = Integer.parseInt(m.group(1));
      int h = Integer.parseInt(m.group(2));
      float density = m.group(3) == null ? 2f : Float.parseFloat(m.group(3));
      if (w < 160 || h < 160 || density < 0.75f || density > 4f) throw new IllegalArgumentException("bad size: " + name);
      // A custom size is a phone below 600 dp (the compact width class), otherwise a tablet: both have system bars.
      return new Device(name, Math.min(w, h) < 600 ? "phone" : "tablet", w, h, density);
    }
    throw new IllegalArgumentException("unknown device: " + name + " (" + names() + ", or WxH[@density] in dp, or WxHpx@Ndpi)");
  }

  static String names() {
    List<String> n = new ArrayList<>();
    for (Device d : ALL) n.add(d.name);
    return String.join(", ", n);
  }

  /** The same kind of device with another display size (the window resized, or `size`). */
  Device withSize(int wDp, int hDp) {
    return new Device(name, kind, wDp, hDp, density);
  }

  int widthPx() {
    return Math.round(widthDp * density);
  }

  int heightPx() {
    return Math.round(heightDp * density);
  }

  /** Phones, foldables and tablets have a status bar and gesture navigation; desktop and TV have neither. */
  boolean systemBars() {
    return !kind.equals("desktop") && !kind.equals("tv");
  }

  boolean tv() {
    return kind.equals("tv");
  }

  /** What `adb shell getprop ro.build.characteristics` says, which some apps read to pick a layout. */
  String characteristics() {
    return switch (kind) {
      case "tablet", "desktop" -> "tablet";
      case "tv" -> "tv";
      default -> "default";
    };
  }

  /** The qualifiers the app starts with. */
  String qualifiers() {
    return withQualifiers("");
  }

  /**
   * The full qualifier string for this device, keeping from Robolectric's current one (RuntimeEnvironment.getQualifiers)
   * what is not the device's: the locale and layout direction before the size, and night mode. Everything else
   * (size, smallest width, size bucket, orientation, UI mode type, density, touchscreen, navigation) is this device's.
   * A full string, not "+...": there is no qualifier for the normal UI mode type, so leaving a TV only works by
   * leaving "television" out.
   */
  String withQualifiers(String previous) {
    int shortDp = Math.min(widthDp, heightDp);
    int longDp = Math.max(widthDp, heightDp);
    // Android's screen size buckets (Configuration.SCREENLAYOUT_SIZE_*), from the display in dp.
    String size = longDp >= 960 && shortDp >= 720 ? "xlarge" : longDp >= 640 && shortDp >= 480 ? "large"
        : longDp >= 470 && shortDp >= 320 ? "normal" : "small";
    String isLong = longDp * 3 / 5 >= shortDp - 1 ? "long" : "notlong";
    String orientation = widthDp > heightDp ? "land" : widthDp < heightDp ? "port" : "square";
    List<String> out = new ArrayList<>();
    String night = null;
    boolean beforeSize = true;
    for (String t : previous.isEmpty() ? new String[0] : previous.split("-")) {
      if (t.matches("(sw|w|h)\\d+dp")) beforeSize = false;
      if (beforeSize) out.add(t);
      if (t.matches("night|notnight")) night = t;
    }
    out.add(screen(shortDp, size, isLong, orientation));
    if (tv()) out.add("television");
    if (night != null) out.add(night);
    out.add(density == 2f ? "xhdpi" : Math.round(density * 160) + "dpi");
    out.add(tv() ? "notouch" : "finger");
    out.add(tv() ? "dpad" : "nonav");
    return String.join("-", out);
  }

  private String screen(int shortDp, String size, String isLong, String orientation) {
    return "sw" + shortDp + "dp-w" + widthDp + "dp-h" + heightDp + "dp-" + size + "-" + isLong + "-" + orientation;
  }

  /** "phone 411x891 dp" */
  String describe() {
    return name + " " + widthDp + "x" + heightDp + " dp" + (density == 2f ? "" : " @" + density);
  }
}
