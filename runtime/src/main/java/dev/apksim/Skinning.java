package dev.apksim;

import dev.apksim.skin.Skin;
import dev.apksim.skin.Skins;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The skin drawn around the screen (`--skin NAME`, the `skin` command, Device > Skin; docs/skins.md "Drawing"): which
 * stored skin is on, and where its frame, screen, corners and cutout fall for the display as it is now (size and
 * rotation). Plain Java with no Android types, so Main can use it before the framework exists; what the app is told
 * (DisplayCutout, RoundedCorners) is {@link SkinShape}'s.
 *
 * <p>Geometry: the skin's frame image is in its own pixels with the screen at {@code screen}. The display's pixels set
 * the scale: {@code k} device pixels per frame pixel, from the screen's long side. The frame turns with the display's
 * rotation as a device does (ROTATION_90: the device's top edge, and its camera, on the left), plus a quarter turn
 * when the display's orientation still differs from the frame's (a landscape tablet in a portrait frame).
 */
final class Skinning {
  private Skinning() {}

  /** The skin on now, or null. */
  private static volatile Skin skin;
  /** Its images, read once when it is turned on. */
  private static byte[] frame, frameLand, mask;
  private static int frameW, frameH, frameLandW, frameLandH;

  /**
   * Where everything falls for one display size and rotation, in device pixels of the turned frame. The hole is the
   * frame's screen rectangle scaled; the display (screenW x screenH) is centred in it, which can leave a pixel or a
   * few of the hole around it (the shapes agree within 1%): that shows black, as a device's glass does. cutout is
   * its bounds on the display (left, top, right, bottom), or null.
   */
  record Layout(int quarterTurns, double k, int outerW, int outerH, int holeX, int holeY, int holeW, int holeH,
      int screenX, int screenY, int screenW, int screenH, int cornerRadius, int[] cutout) {}

  static Skin current() {
    return skin;
  }

  static String name() {
    Skin s = skin;
    return s == null ? null : s.name;
  }

  /**
   * --skin NAME at launch, before the device is fixed: the skin's device unless --device names one, which must then
   * fit the skin (else a warning and no skin). An unknown or broken skin stops the launch (exit 2), as a bad --device.
   * True to go on.
   */
  static boolean launch() {
    // `apksim replay` (ReplayPlan): the recorded skin's shape, without its images, with the device's kind restored
    // before anything runs, whether or not the skin is stored here.
    String shape = System.getProperty("apksim.skinShape");
    if (shape != null && !shape.isEmpty() && (System.getProperty("apksim.skin") == null)) {
      java.util.Map<String, Object> m = decodeShape(shape);
      if (m != null) {
        restoreShape(m);
        Skin s = skin;
        String given = System.getProperty("apksim.device");
        if (s != null && given != null) {
          try {
            Device d = Device.named(given), k = deviceOf(s);
            if (d.widthPx() == k.widthPx() && d.heightPx() == k.heightPx() && !d.kind().equals(k.kind())) Device.setCurrent(k);
          } catch (IllegalArgumentException e) {
            // Main reports a bad --device
          }
        }
      }
      return true;
    }
    String name = System.getProperty("apksim.skin");
    if (name == null || name.isEmpty() || name.equals("off")) return true;
    Skin s;
    try {
      s = Skins.lookup(name);
      s.spec();  // the device resolves
      read(s);
    } catch (IOException e) {
      System.err.println("[apksim] --skin: " + e.getMessage());
      return false;
    }
    String given = System.getProperty("apksim.device");
    if (given == null || given.isEmpty()) {
      System.setProperty("apksim.device", s.device);
      // With its kind (a custom-size watch or TV skin), which the size alone would make a phone or a tablet.
      Device.setCurrent(deviceOf(s));
    } else {
      Device d;
      try {
        d = Device.named(given);
      } catch (IllegalArgumentException e) {
        return true;  // Main reports the bad --device
      }
      if (!fits(s, d.widthPx(), d.heightPx())) {
        System.err.println("[apksim] --skin " + name + ": --device " + given + " (" + d.widthPx() + "x" + d.heightPx()
            + " px) does not have the skin's screen shape (" + s.width + "x" + s.height + "), so no skin is drawn");
        clear();
        return true;
      }
      // The skin's own device named again (`apksim serve` passes the session's device with the skin): its kind too.
      Device k = deviceOf(s);
      if (d.widthPx() == k.widthPx() && d.heightPx() == k.heightPx() && d.density() == k.density() && !d.kind().equals(k.kind())) {
        Device.setCurrent(k);
      }
    }
    skin = s;
    return true;
  }

  /**
   * The skin's device, of the skin's own kind when it names one: `227x227@2` with `"kind": "watch"` is a watch (round,
   * UI_MODE_TYPE_WATCH, no bars), not the phone the size alone gives.
   */
  static Device deviceOf(Skin s) {
    Device d = Device.named(s.device);
    if (s.kind == null || s.kind.equals(d.kind()) || !d.name().equals(s.device) || !java.util.List.of("phone", "foldable",
        "tablet", "desktop", "tv", "watch").contains(s.kind)) return d;
    return new Device(d.name(), s.kind, d.widthDp(), d.heightDp(), d.density(), d.exactWidthPx(), d.exactHeightPx());
  }

  /**
   * The skin's shape, without its images, for a flight recording's checkpoint (FlightRecorder.device): what the app was
   * told (cutout, corners) depends on it, and a replay elsewhere may not have the skin stored.
   */
  static java.util.Map<String, Object> shape() {
    Skin s = skin;
    if (s == null) return null;
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("name", s.name);
    m.put("device", s.device);
    if (s.kind != null) m.put("kind", s.kind);
    m.put("frameWidth", frameW);
    m.put("frameHeight", frameH);
    m.put("x", s.x);
    m.put("y", s.y);
    m.put("width", s.width);
    m.put("height", s.height);
    m.put("cornerRadius", s.cornerRadius);
    if (s.cutout != null) m.put("cutout", new java.util.LinkedHashMap<>(s.cutout));
    return m;
  }

  /** The shape as one token for a command line or a property: base64url of its JSON. */
  static String encodeShape(java.util.Map<String, Object> m) {
    StringBuilder b = new StringBuilder("{");
    for (java.util.Map.Entry<String, Object> e : m.entrySet()) {
      if (b.length() > 1) b.append(',');
      b.append(jq(e.getKey())).append(':');
      Object v = e.getValue();
      if (v instanceof java.util.Map<?, ?> inner) {
        StringBuilder c = new StringBuilder("{");
        for (java.util.Map.Entry<?, ?> f : inner.entrySet()) {
          if (c.length() > 1) c.append(',');
          c.append(jq(String.valueOf(f.getKey()))).append(':');
          c.append(f.getValue() instanceof Number n ? n.toString() : jq(String.valueOf(f.getValue())));
        }
        b.append(c.append('}'));
      } else {
        b.append(v instanceof Number n ? n.toString() : jq(String.valueOf(v)));
      }
    }
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b.append('}').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static String jq(String v) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : v.toCharArray()) {
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }

  @SuppressWarnings("unchecked")
  static java.util.Map<String, Object> decodeShape(String token) {
    try {
      Object o = dev.apksim.serve.Json.parse(new String(java.util.Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.UTF_8));
      return o instanceof java.util.Map<?, ?> m ? (java.util.Map<String, Object>) m : null;
    } catch (IOException | IllegalArgumentException e) {
      return null;
    }
  }

  /** Two shapes alike, numbers compared as numbers (a decoded one has JSON's, a fresh one Java's). */
  static boolean sameShape(java.util.Map<?, ?> a, java.util.Map<?, ?> b) {
    if (a == null || b == null || !a.keySet().equals(b.keySet())) return false;
    for (Object k : a.keySet()) {
      Object x = a.get(k), y = b.get(k);
      if (x instanceof Number n && y instanceof Number m) {
        if (n.doubleValue() != m.doubleValue()) return false;
      } else if (x instanceof java.util.Map<?, ?> mx && y instanceof java.util.Map<?, ?> my) {
        if (!sameShape(mx, my)) return false;
      } else if (!java.util.Objects.equals(x == null ? null : String.valueOf(x), y == null ? null : String.valueOf(y))) {
        return false;
      }
    }
    return true;
  }

  /** The shape of a stored skin, as {@link #shape()} gives the current one (a recorded `skin NAME`), or null. */
  static java.util.Map<String, Object> shapeOf(String name) {
    try {
      Skin s = Skins.lookup(name);
      int[] f;
      try (java.io.InputStream in = Files.newInputStream(s.dir.resolve(s.frame))) {
        f = pngSize(in.readNBytes(24), s.frame);
      }
      java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("name", s.name);
      m.put("device", s.device);
      if (s.kind != null) m.put("kind", s.kind);
      m.put("frameWidth", f[0]);
      m.put("frameHeight", f[1]);
      m.put("x", s.x);
      m.put("y", s.y);
      m.put("width", s.width);
      m.put("height", s.height);
      m.put("cornerRadius", s.cornerRadius);
      if (s.cutout != null) m.put("cutout", new java.util.LinkedHashMap<>(s.cutout));
      return m;
    } catch (IOException e) {
      return null;
    }
  }

  /** A recorded shape (`apksim replay`): the app is told the same cutout and corners; nothing is drawn (no images). */
  @SuppressWarnings("unchecked")
  static void restoreShape(java.util.Map<String, Object> m) {
    if (m == null) return;
    Skin s = new Skin();
    s.name = String.valueOf(m.get("name"));
    s.title = s.name;
    s.device = String.valueOf(m.get("device"));
    s.kind = m.get("kind") instanceof String k ? k : null;
    s.x = (int) num(m.get("x"));
    s.y = (int) num(m.get("y"));
    s.width = (int) num(m.get("width"));
    s.height = (int) num(m.get("height"));
    s.cornerRadius = (int) num(m.get("cornerRadius"));
    s.cutout = m.get("cutout") instanceof java.util.Map<?, ?> c ? (java.util.Map<String, Object>) c : null;
    if (s.width <= 0 || s.height <= 0) return;
    clear();
    frameW = (int) num(m.get("frameWidth"));
    frameH = (int) num(m.get("frameHeight"));
    skin = s;
  }

  /** Turns a skin on (the `skin` command); the caller then switches the device if it has to. */
  static Skin use(String name) throws IOException {
    Skin s = Skins.lookup(name);
    if (!Skin.NAME.matcher(s.name).matches()) throw new IOException("skin \"" + s.name + "\" has a bad name");
    s.spec();
    read(s);
    skin = s;
    return s;
  }

  static void off() {
    skin = null;
    clear();
  }

  private static void clear() {
    frame = frameLand = mask = null;
  }

  /** Every stored skin's name, for the menu (the store, then APKSIM_SKINS's folders). */
  static List<String> names() {
    List<String> out = new ArrayList<>();
    try {
      for (Skin s : Skins.all(null)) out.add(s.name);
    } catch (IOException e) {
      // no store: no skins
    }
    return out;
  }

  static List<String> titles(List<String> names) {
    List<String> out = new ArrayList<>();
    try {
      List<Skin> all = Skins.all(null);
      for (String n : names) {
        String t = n;
        for (Skin s : all) if (s.name.equals(n)) t = s.title;
        out.add(t);
      }
    } catch (IOException e) {
      out.addAll(names);
    }
    return out;
  }

  private static void read(Skin s) throws IOException {
    for (String f : new String[] {s.frame, s.frameLand, s.mask}) {
      if (f != null && (f.contains("..") || f.startsWith("/") || f.contains("\\"))) throw new IOException("skin " + s.name + ": " + f + " must be a file inside the skin's folder");
    }
    byte[] f = Files.readAllBytes(s.dir.resolve(s.frame));
    int[] fs = pngSize(f, s.frame);
    // What `apksim skin add` refuses (Skin.check), without decoding the images in the app's process: a skin edited or
    // shared by hand is held to the same rules before it is drawn or told to the app.
    DeviceSpec spec = s.spec();
    if (s.width <= 0 || s.height <= 0) throw new IOException("skin " + s.name + ": the screen is " + s.width + "x" + s.height);
    if (s.cornerRadius < 0) throw new IOException("skin " + s.name + ": screen.cornerRadius is negative");
    if (s.x < 0 || s.y < 0 || s.x + s.width > fs[0] || s.y + s.height > fs[1]) {
      throw new IOException("skin " + s.name + ": the screen (" + s.x + "," + s.y + " " + s.width + "x" + s.height + ") does not fit in "
          + s.frame + " (" + fs[0] + "x" + fs[1] + ")");
    }
    double a = (double) s.width / s.height, b = (double) spec.widthPx() / spec.heightPx();
    if (Math.abs(a / b - 1) > 0.01) {
      throw new IOException("skin " + s.name + ": the screen is " + s.width + "x" + s.height + " px but device " + s.device + " is "
          + spec.widthPx() + "x" + spec.heightPx() + " px (the aspect may differ by at most 1%)");
    }
    if (s.kind != null && !java.util.List.of("phone", "foldable", "tablet", "desktop", "tv", "watch").contains(s.kind)) {
      throw new IOException("skin " + s.name + ": kind \"" + s.kind + "\" is not one apksim knows");
    }
    byte[] fl = null;
    int[] fls = null;
    if (s.frameLand != null) {
      fl = Files.readAllBytes(s.dir.resolve(s.frameLand));
      fls = pngSize(fl, s.frameLand);
      if (fls[0] != fs[1] || fls[1] != fs[0]) fl = null;  // not the frame turned: turn the frame instead
    }
    byte[] m = s.mask == null ? null : Files.readAllBytes(s.dir.resolve(s.mask));
    if (m != null) {
      int[] ms = pngSize(m, s.mask);
      if (ms[0] != s.width || ms[1] != s.height) {
        throw new IOException("skin " + s.name + ": mask " + s.mask + " is " + ms[0] + "x" + ms[1] + " but the screen is " + s.width + "x" + s.height);
      }
    }
    frame = f;
    frameW = fs[0];
    frameH = fs[1];
    frameLand = fl;
    if (fl != null) {
      frameLandW = fls[0];
      frameLandH = fls[1];
    }
    mask = m;
  }

  /** A PNG's width and height from its header; skins store PNG only (docs/skins.md). */
  private static int[] pngSize(byte[] png, String what) throws IOException {
    if (png.length < 24 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
      throw new IOException(what + " is not a PNG");
    }
    ByteBuffer b = ByteBuffer.wrap(png);
    return new int[] {b.getInt(16), b.getInt(20)};
  }

  /** The display (either orientation) has the skin's screen shape, within 1% as `skin add` requires. */
  static boolean fits(Skin s, int w, int h) {
    double a = (double) Math.max(s.width, s.height) / Math.min(s.width, s.height);
    double b = (double) Math.max(w, h) / Math.min(w, h);
    return Math.abs(a / b - 1) <= 0.01;
  }

  /** The layout for a display of w x h pixels at that rotation (0-3, Surface.ROTATION_*), or null with no skin. */
  static Layout layout(int w, int h, int rotation) {
    Skin s = skin;
    if (s == null) return null;
    int q = rotation & 3;
    // The screen's orientation once turned, against the display's: a quarter turn more when they differ.
    boolean screenLand = (q % 2 == 1) ? s.height > s.width : s.width > s.height;
    boolean displayLand = w > h;
    if (w != h && s.width != s.height && screenLand != displayLand) q = (q + 1) & 3;
    int[] sr = turnRect(s.x, s.y, s.width, s.height, q, frameW, frameH);
    // The scale from the long sides, so the display fills the hole that way and is centred the other.
    double k = w >= h ? (double) w / Math.max(sr[2], 1) : (double) h / Math.max(sr[3], 1);
    if (w == h) k = Math.min((double) w / sr[2], (double) h / sr[3]);
    int outerW = (int) Math.round((q % 2 == 1 ? frameH : frameW) * k);
    int outerH = (int) Math.round((q % 2 == 1 ? frameW : frameH) * k);
    int hx = (int) Math.round(sr[0] * k), hy = (int) Math.round(sr[1] * k);
    int hw = (int) Math.round(sr[2] * k), hh = (int) Math.round(sr[3] * k);
    int sx = hx + (hw - w) / 2;
    int sy = hy + (hh - h) / 2;
    int radius = (int) Math.round(s.cornerRadius * k);
    return new Layout(q, k, outerW, outerH, hx, hy, hw, hh, sx, sy, w, h, radius, cutout(s, q, k, sx, sy, w, h));
  }

  /**
   * The cutout's bounds on the display: a hole is its centre and radius in frame pixels (`cutout` in skin.json); one
   * given with width and height instead of a radius is that rectangle around its centre (a notch, a pill).
   */
  private static int[] cutout(Skin s, int q, double k, int sx, int sy, int w, int h) {
    Map<String, Object> c = s.cutout;
    if (c == null) return null;
    double cx = num(c.get("x")), cy = num(c.get("y"));
    double hw, hh;
    if (c.get("width") instanceof Number && c.get("height") instanceof Number) {
      hw = num(c.get("width")) / 2;
      hh = num(c.get("height")) / 2;
    } else if (c.get("radius") instanceof Number) {
      hw = hh = num(c.get("radius"));
    } else {
      return null;
    }
    int[] r = turnRect((int) Math.round(cx - hw), (int) Math.round(cy - hh), (int) Math.round(hw * 2), (int) Math.round(hh * 2), q, frameW, frameH);
    int l = (int) Math.floor(r[0] * k) - sx, t = (int) Math.floor(r[1] * k) - sy;
    int rr = (int) Math.ceil((r[0] + r[2]) * k) - sx, b = (int) Math.ceil((r[1] + r[3]) * k) - sy;
    l = Math.max(0, l);
    t = Math.max(0, t);
    rr = Math.min(w, rr);
    b = Math.min(h, b);
    if (rr <= l || b <= t) return null;  // outside the screen
    return new int[] {l, t, rr, b};
  }

  private static double num(Object o) {
    return o instanceof Number n ? n.doubleValue() : 0;
  }

  /**
   * A rectangle (x, y, w, h) in the frame of fw x fh, turned by q quarter turns as the frame is (q 1: the frame's
   * top edge on the left), in the turned frame's pixels.
   */
  static int[] turnRect(int x, int y, int w, int h, int q, int fw, int fh) {
    return switch (q & 3) {
      case 1 -> new int[] {y, fw - x - w, h, w};
      case 2 -> new int[] {fw - x - w, fh - y - h, w, h};
      case 3 -> new int[] {fh - y - h, x, h, w};
      default -> new int[] {x, y, w, h};
    };
  }

  /** The frame image for this layout and the quarter turns still to apply to it (frame-land.png is the frame at q 1). */
  static byte[] frameImage(Layout l, int[] turnsOut) {
    if (frame == null) return null;  // a recorded shape: nothing to draw
    if (frameLand != null && l.quarterTurns() % 2 == 1) {
      turnsOut[0] = l.quarterTurns() == 1 ? 0 : 2;
      return frameLand;
    }
    turnsOut[0] = l.quarterTurns();
    return frame;
  }

  static byte[] maskImage() {
    return mask;
  }

  /** "pixel-9: frame 1208x2540 at 0.92, screen 64,58 1080x2424, corners 96, cutout 520,40-560,80" */
  static String describe(int w, int h, int rotation) {
    Layout l = layout(w, h, rotation);
    if (l == null) return "off";
    int[] c = l.cutout();
    return skin.name + ": frame " + l.outerW() + "x" + l.outerH() + " px (x" + String.format("%.3f", l.k()) + ", "
        + l.quarterTurns() * 90 + " deg), screen at " + l.screenX() + "," + l.screenY() + " " + l.screenW() + "x" + l.screenH()
        + ", corners " + l.cornerRadius() + (mask != null ? ", mask" : "")
        + (c == null ? "" : ", cutout " + c[0] + "," + c[1] + "-" + c[2] + "," + c[3]);
  }
}
