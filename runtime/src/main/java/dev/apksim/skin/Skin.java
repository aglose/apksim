package dev.apksim.skin;

import dev.apksim.DeviceSpec;
import dev.apksim.serve.Json;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** A device skin (docs/skins.md): a frame image with a screen hole, and the device it frames. Read from a folder's skin.json. */
public final class Skin {
  public static final Pattern NAME = Pattern.compile("[a-z0-9-]+");
  static final List<String> KINDS = List.of("phone", "foldable", "tablet", "desktop", "tv", "watch");

  public String name;
  public String title;
  public String frame = "frame.png";
  public String frameLand;
  public String mask;
  public int x, y, width, height;
  public int cornerRadius;
  public String device;
  public String kind;
  /** type, x, y, radius; null when the skin has none. */
  public Map<String, Object> cutout;
  public Map<String, Object> source;
  /** The folder it was read from. */
  public Path dir;
  /** "local" (the skin store) or "shared" (a folder named by APKSIM_SKINS). */
  public String origin = "local";

  /** Reads dir/skin.json; the reason is the message of the IOException. */
  @SuppressWarnings("unchecked")
  public static Skin read(Path dir) throws IOException {
    Path f = dir.resolve("skin.json");
    if (!Files.isRegularFile(f)) throw new IOException(dir + " has no skin.json");
    Object o;
    try {
      o = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IOException("skin.json: " + e.getMessage());
    }
    if (!(o instanceof Map)) throw new IOException("skin.json is not an object");
    Map<String, Object> m = (Map<String, Object>) o;
    Skin s = new Skin();
    s.dir = dir;
    s.name = str(m, "name");
    s.title = m.get("title") instanceof String t ? t : s.name;
    if (m.get("frame") instanceof String fr) s.frame = fr;
    s.frameLand = m.get("frame-land") instanceof String fl ? fl : (m.get("frameLand") instanceof String fl2 ? fl2 : null);
    s.mask = m.get("mask") instanceof String mk ? mk : null;
    Object scr = m.get("screen");
    if (!(scr instanceof Map)) throw new IOException("skin.json has no \"screen\" object");
    Map<String, Object> sm = (Map<String, Object>) scr;
    s.x = num(sm, "x", "screen");
    s.y = num(sm, "y", "screen");
    s.width = num(sm, "width", "screen");
    s.height = num(sm, "height", "screen");
    s.cornerRadius = sm.get("cornerRadius") == null ? 0 : num(sm, "cornerRadius", "screen");
    s.device = str(m, "device");
    s.kind = m.get("kind") instanceof String k ? k : null;
    s.cutout = m.get("cutout") instanceof Map ? (Map<String, Object>) m.get("cutout") : null;
    s.source = m.get("source") instanceof Map ? (Map<String, Object>) m.get("source") : null;
    return s;
  }

  private static String str(Map<String, Object> m, String k) throws IOException {
    if (!(m.get(k) instanceof String s) || s.isEmpty()) throw new IOException("skin.json has no \"" + k + "\"");
    return s;
  }

  private static int num(Map<String, Object> m, String k, String where) throws IOException {
    if (!(m.get(k) instanceof Number n)) throw new IOException("skin.json: " + where + "." + k + " must be a number");
    double d = n.doubleValue();
    if (d != Math.rint(d)) throw new IOException("skin.json: " + where + "." + k + " must be a whole number of pixels, not " + d);
    return (int) d;
  }

  /** The resolved device, or the reason it does not resolve. */
  public DeviceSpec spec() throws IOException {
    try {
      return DeviceSpec.resolve(device);
    } catch (IllegalArgumentException e) {
      if (device.endsWith(".json") || device.contains("/")) {
        throw new IOException("device \"" + device + "\": device profile files are not supported yet; use a preset, WxH[@density] or WxHpx@Ndpi");
      }
      throw new IOException("device \"" + device + "\": " + e.getMessage());
    }
  }

  /** The kind: as given, else the device's. */
  public String effectiveKind() throws IOException {
    return kind != null ? kind : spec().kind();
  }

  /** Every problem with the skin (empty: fine). The images are opened, so the list also says what is missing. */
  public List<String> check() {
    List<String> p = new ArrayList<>();
    if (!NAME.matcher(name).matches()) p.add("name \"" + name + "\" must match [a-z0-9-]+");
    if (kind != null && !KINDS.contains(kind)) p.add("kind \"" + kind + "\" is not one of " + String.join(", ", KINDS));
    DeviceSpec spec = null;
    try {
      spec = spec();
    } catch (IOException e) {
      p.add(e.getMessage());
    }
    if (width <= 0 || height <= 0) p.add("screen is " + width + "x" + height + ": width and height must be positive");
    if (cornerRadius < 0) p.add("screen.cornerRadius is negative");
    if (spec != null && width > 0 && height > 0) {
      double a = (double) width / height;
      double b = (double) spec.widthPx() / spec.heightPx();
      if (Math.abs(a / b - 1) > 0.01) {
        p.add("the screen is " + width + "x" + height + " px but device " + device + " is " + spec.widthPx() + "x" + spec.heightPx()
            + " px: the aspect differs by " + String.format("%.1f%%", Math.abs(a / b - 1) * 100) + " (at most 1%)");
      }
    }
    if (cutout != null) {
      Object t = cutout.get("type");
      if (!(t instanceof String) || !(t.equals("hole") || t.equals("notch") || t.equals("pill"))) p.add("cutout.type must be hole, notch or pill");
    }
    BufferedImage fr = image(frame, "frame", p);
    if (fr != null) {
      if (width > 0 && (x < 0 || y < 0 || x + width > fr.getWidth() || y + height > fr.getHeight())) {
        p.add("the screen (" + x + "," + y + " " + width + "x" + height + ") does not fit in the " + frame + " image (" + fr.getWidth() + "x" + fr.getHeight() + ")");
      }
    }
    if (frameLand != null) image(frameLand, "frame-land", p);
    if (mask != null) {
      BufferedImage mk = image(mask, "mask", p);
      if (mk != null && width > 0 && (mk.getWidth() != width || mk.getHeight() != height)) {
        p.add("mask " + mask + " is " + mk.getWidth() + "x" + mk.getHeight() + " but the screen is " + width + "x" + height);
      }
    }
    return p;
  }

  private BufferedImage image(String file, String what, List<String> problems) {
    if (file.contains("..") || file.startsWith("/") || file.contains("\\")) {
      problems.add(what + " \"" + file + "\" must be a file inside the skin's folder");
      return null;
    }
    Path f = dir.resolve(file);
    if (!Files.isRegularFile(f)) {
      problems.add(what + " image " + file + " is missing");
      return null;
    }
    try {
      BufferedImage img = ImageIO.read(f.toFile());
      if (img == null) problems.add(what + " image " + file + " is not a PNG, JPEG or GIF");
      return img;
    } catch (IOException e) {
      problems.add(what + " image " + file + " cannot be read: " + e.getMessage());
      return null;
    }
  }

  /** skin.json's text. */
  public String toJson() {
    StringBuilder b = new StringBuilder("{\n");
    b.append("  \"name\": ").append(q(name)).append(",\n");
    b.append("  \"title\": ").append(q(title)).append(",\n");
    b.append("  \"frame\": ").append(q(frame)).append(",\n");
    if (frameLand != null) b.append("  \"frame-land\": ").append(q(frameLand)).append(",\n");
    if (mask != null) b.append("  \"mask\": ").append(q(mask)).append(",\n");
    b.append("  \"screen\": { \"x\": ").append(x).append(", \"y\": ").append(y).append(", \"width\": ").append(width)
        .append(", \"height\": ").append(height).append(", \"cornerRadius\": ").append(cornerRadius).append(" },\n");
    b.append("  \"device\": ").append(q(device));
    if (kind != null) b.append(",\n  \"kind\": ").append(q(kind));
    if (cutout != null) b.append(",\n  \"cutout\": ").append(obj(cutout));
    if (source != null && !source.isEmpty()) b.append(",\n  \"source\": ").append(obj(source));
    return b.append("\n}\n").toString();
  }

  /** The skin as `list --json` and `show --json` give it, with the resolved device. */
  public String describeJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    StringBuilder b = new StringBuilder("{\"name\":").append(q(name)).append(",\"title\":").append(q(title));
    b.append(",\"origin\":").append(q(origin)).append(",\"device\":").append(q(device));
    try {
      DeviceSpec s = spec();
      b.append(",\"kind\":").append(q(effectiveKind()));
      b.append(",\"deviceResolved\":{\"widthDp\":").append(s.widthDp()).append(",\"heightDp\":").append(s.heightDp())
          .append(",\"density\":").append(s.density()).append(",\"widthPx\":").append(s.widthPx()).append(",\"heightPx\":")
          .append(s.heightPx()).append("}");
    } catch (IOException e) {
      b.append(",\"kind\":").append(kind == null ? "null" : q(kind));
    }
    b.append(",\"frame\":").append(q(frame));
    if (frameLand != null) b.append(",\"frameLand\":").append(q(frameLand));
    if (mask != null) b.append(",\"mask\":").append(q(mask));
    b.append(",\"screen\":{\"x\":").append(x).append(",\"y\":").append(y).append(",\"width\":").append(width).append(",\"height\":")
        .append(height).append(",\"cornerRadius\":").append(cornerRadius).append("}");
    if (cutout != null) b.append(",\"cutout\":").append(obj(cutout));
    if (source != null && !source.isEmpty()) b.append(",\"source\":").append(obj(source));
    b.append(",\"dir\":").append(q(dir.toString())).append("}");
    return b.toString();
  }

  static String obj(Map<String, Object> m) {
    StringBuilder b = new StringBuilder("{ ");
    boolean first = true;
    for (Map.Entry<String, Object> e : m.entrySet()) {
      if (!first) b.append(", ");
      first = false;
      b.append(q(e.getKey())).append(": ");
      Object v = e.getValue();
      if (v instanceof Number n) b.append(n.doubleValue() == Math.rint(n.doubleValue()) ? String.valueOf(n.longValue()) : n.toString());
      else b.append(q(String.valueOf(v)));
    }
    return b.append(" }").toString();
  }

  static String q(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }
}
