// Draws the built-in Samsung skins (runtime/src/main/resources/dev/apksim/skins/galaxy-*), frames and skin.json:
//   java scripts/gen-samsung-skins.java [OUT_DIR]
// Each frame is drawn here from the device's published sizes (the body's millimetres, the panel's pixels and diagonal),
// not taken from any artwork, at the panel's own pixels per millimetre, so a skin's bezels are the device's. The phones
// run at the resolution Samsung ships them with (FHD+), so their panels are drawn at that resolution's density. The
// folding ones have a cover frame too (skin.json's "cover"): the Z Fold7 closes as a book, its hinge down the cover
// side's left edge; the Z Flip7 as a clamshell, its FlexWindow on the top half's back with the hinge below it and the
// cameras inside the window's bottom left; the Z TriFold in three, a hinge down each side of its cover. Fold.java has
// the same devices' panels: keep the two in step.

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

public class GenSamsungSkins {
  static final int MARGIN = 10;  // room outside the body for the buttons

  /** A camera drawn over the screen: centre in screen pixels, radius; a lens (a big ring) or a punch hole. */
  record Camera(double x, double y, double r, boolean lens) {}

  /** A button on the body's right edge, from and to as fractions of the body's height. */
  record Button(double from, double to) {}

  /**
   * One frame: the screen's pixels and the body around it in mm. Bezels in mm (left, top, right, bottom), radii in mm.
   * hinges: "left", "right", "bottom" (a closed device's barrel along that edge); gapsX/gapsY: an open device's hinge
   * breaks in the top and bottom rails (fractions of the screen's width) or the side rails (of its height).
   */
  static final class Part {
    int sw, sh;
    double pxPerMm, left, top, right, bottom, bodyR, screenR;
    List<Camera> cameras = new ArrayList<>();
    List<Button> buttons = new ArrayList<>();
    List<String> hinges = new ArrayList<>();
    double[] gapsX = {}, gapsY = {};
    boolean flash;  // a flash beside the lenses (the Flip's cover)
    // Results, in the frame's pixels.
    int frameW, frameH, screenX, screenY, screenRadius;

    Part(int sw, int sh, double inches) {
      this.sw = sw;
      this.sh = sh;
      this.pxPerMm = Math.hypot(sw, sh) / inches / 25.4;
    }

    /** The body's size in mm with the screen in the middle of it: sets the bezels from the body's outer size. */
    Part body(double wMm, double hMm, double leftExtra, double topExtra) {
      double screenW = sw / pxPerMm, screenH = sh / pxPerMm;
      double side = (wMm - screenW - leftExtra) / 2, end = (hMm - screenH - topExtra) / 2;
      left = side + leftExtra;
      right = side;
      top = end + Math.max(0, topExtra);
      bottom = end;
      return this;
    }

    Part bottomExtra(double mm) {  // more below the screen than above (a chin, a hinge), the body's height kept
      top -= mm / 2;
      bottom += mm / 2;
      return this;
    }

    Part rightExtra(double mm) {
      left -= mm / 2;
      right += mm / 2;
      return this;
    }

    Part radii(double body, double screen) {
      bodyR = body;
      screenR = screen;
      return this;
    }

    Part hole(double fx, double yMm, double dMm) {
      cameras.add(new Camera(fx * sw, yMm * pxPerMm, dMm / 2 * pxPerMm, false));
      return this;
    }

    Part button(double from, double to) {
      buttons.add(new Button(from, to));
      return this;
    }

    int px(double mm) {
      return (int) Math.round(mm * pxPerMm);
    }
  }

  public static void main(String[] a) throws Exception {
    File root = new File(a.length > 0 ? a[0] : "runtime/src/main/resources/dev/apksim/skins");
    // Galaxy S25: 6.2" panel, 1080x2340 as shipped; 70.5 x 146.9 mm.
    phone(root, "galaxy-s25", "Galaxy S25", new Part(1080, 2340, 6.2).body(70.5, 146.9, 0, 0).radii(9.2, 7.0)
        .hole(0.5, 4.1, 2.9).button(0.24, 0.33).button(0.35, 0.40));
    // Galaxy S25+: 6.7", 1440x3120 shipped at 1080x2340; 75.8 x 158.4 mm.
    phone(root, "galaxy-s25-plus", "Galaxy S25+", new Part(1080, 2340, 6.7).body(75.8, 158.4, 0, 0).radii(9.6, 7.3)
        .hole(0.5, 4.2, 3.0).button(0.24, 0.33).button(0.35, 0.40));
    // Galaxy S25 Ultra: 6.9", 1440x3120 shipped at 1080x2340; 77.6 x 162.8 mm, flat and squarer.
    phone(root, "galaxy-s25-ultra", "Galaxy S25 Ultra", new Part(1080, 2340, 6.9).body(77.6, 162.8, 0, 0).radii(6.6, 4.8)
        .hole(0.5, 4.0, 3.0).button(0.23, 0.32).button(0.34, 0.39));
    // Galaxy A56: 6.7" 1080x2340; 77.5 x 162.2 mm, a thicker chin, its keys on a raised island.
    phone(root, "galaxy-a56", "Galaxy A56", new Part(1080, 2340, 6.7).body(77.5, 162.2, 0, 0).bottomExtra(1.5).radii(10.2, 7.6)
        .hole(0.5, 4.6, 3.2).button(0.26, 0.34).button(0.36, 0.40));

    // Galaxy Z Fold7: open 143.2 x 158.4 mm around an 8.0" 1968x2184 panel, the selfie camera in the right half's top;
    // closed 72.8 x 158.4 mm around a 6.5" 1080x2520 one, the hinge down the left edge (2 mm more there).
    Part foldOpen = new Part(1968, 2184, 8.0).body(143.2, 158.4, 0, 0).radii(6.2, 3.9).hole(0.79, 3.9, 2.4)
        .button(0.25, 0.34).button(0.37, 0.44);
    foldOpen.gapsX = new double[] {0.5};
    Part foldCover = new Part(1080, 2520, 6.5).body(72.8, 158.4, 2.0, 0).radii(6.6, 5.2).hole(0.5, 4.0, 2.8)
        .button(0.25, 0.34).button(0.37, 0.44);
    foldCover.hinges.add("left");
    foldable(root, "galaxy-z-fold7", "Galaxy Z Fold7", foldOpen, foldCover);

    // Galaxy Z Flip7: open 75.2 x 166.7 mm around a 6.9" 1080x2520 panel; closed 75.2 x 85.5 mm, the 4.1" 948x1048
    // FlexWindow edge to edge above the hinge, the two lenses inside its bottom left.
    Part flipOpen = new Part(1080, 2520, 6.9).body(75.2, 166.7, 0, 0).radii(8.8, 6.4).hole(0.5, 4.0, 2.7)
        .button(0.20, 0.27).button(0.29, 0.36);
    flipOpen.gapsY = new double[] {0.5};
    Part flipCover = new Part(948, 1048, 4.1).body(75.2, 85.5, 0, 0).bottomExtra(3.4).radii(8.8, 6.0)
        .button(0.40, 0.55).button(0.58, 0.72);
    flipCover.hinges.add("bottom");
    double lensR = 6.1 * flipCover.pxPerMm, lensY = flipCover.sh - 8.6 * flipCover.pxPerMm;
    flipCover.cameras.add(new Camera(8.6 * flipCover.pxPerMm, lensY, lensR, true));
    flipCover.cameras.add(new Camera(23.4 * flipCover.pxPerMm, lensY, lensR, true));
    flipCover.flash = true;
    foldable(root, "galaxy-z-flip7", "Galaxy Z Flip7", flipOpen, flipCover);

    // Galaxy Z TriFold: open 214.1 x 159.2 mm around a 10.0" 2160x1584 panel (two hinges, at its thirds), the selfie
    // camera at the top of the right panel; closed 75.0 x 159.2 mm around a 6.5" 1080x2520 cover, hinges on both sides.
    Part triOpen = new Part(2160, 1584, 10.0).body(214.1, 159.2, 0, 0).radii(6.4, 4.2).hole(5 / 6.0, 3.6, 2.4)
        .button(0.20, 0.30).button(0.33, 0.41);
    triOpen.gapsX = new double[] {1 / 3.0, 2 / 3.0};
    Part triCover = new Part(1080, 2520, 6.5).body(75.0, 159.2, 0, 0).radii(6.4, 5.0).hole(0.5, 4.0, 2.8)
        .button(0.25, 0.34).button(0.37, 0.44);
    triCover.hinges.add("left");
    triCover.hinges.add("right");
    foldable(root, "galaxy-z-trifold", "Galaxy Z TriFold", triOpen, triCover);
  }

  static void phone(File root, String name, String title, Part p) throws Exception {
    File dir = new File(root, name);
    dir.mkdirs();
    ImageIO.write(draw(p), "png", new File(dir, "frame.png"));
    StringBuilder j = new StringBuilder("{\n");
    j.append("  \"name\": \"").append(name).append("\",\n  \"title\": \"").append(title).append("\",\n");
    j.append("  \"frame\": \"frame.png\",\n  \"screen\": ").append(screen(p)).append(",\n");
    j.append("  \"device\": \"").append(name).append("\",\n  \"kind\": \"phone\",\n");
    j.append("  \"cutout\": ").append(cutout(p)).append(",\n");
    j.append("  \"source\": { \"author\": \"apksim (drawn by scripts/gen-samsung-skins.java)\", \"license\": \"Apache-2.0\" }\n}\n");
    Files.writeString(new File(dir, "skin.json").toPath(), j);
  }

  static void foldable(File root, String name, String title, Part open, Part cover) throws Exception {
    File dir = new File(root, name);
    dir.mkdirs();
    ImageIO.write(draw(open), "png", new File(dir, "frame.png"));
    ImageIO.write(draw(cover), "png", new File(dir, "cover.png"));
    StringBuilder j = new StringBuilder("{\n");
    j.append("  \"name\": \"").append(name).append("\",\n  \"title\": \"").append(title).append("\",\n");
    j.append("  \"frame\": \"frame.png\",\n  \"screen\": ").append(screen(open)).append(",\n");
    j.append("  \"device\": \"").append(name).append("\",\n  \"kind\": \"foldable\",\n");
    j.append("  \"cutout\": ").append(cutout(open)).append(",\n");
    j.append("  \"cover\": {\n    \"frame\": \"cover.png\",\n    \"screen\": ").append(screen(cover));
    String cc = cutout(cover);
    if (!cc.equals("null")) j.append(",\n    \"cutout\": ").append(cc);
    j.append("\n  },\n");
    j.append("  \"source\": { \"author\": \"apksim (drawn by scripts/gen-samsung-skins.java)\", \"license\": \"Apache-2.0\" }\n}\n");
    Files.writeString(new File(dir, "skin.json").toPath().toAbsolutePath(), j.toString().replace(",\n  \"cutout\": null", ""));
  }

  static String screen(Part p) {
    return String.format(Locale.ROOT, "{ \"x\": %d, \"y\": %d, \"width\": %d, \"height\": %d, \"cornerRadius\": %d }",
        p.screenX, p.screenY, p.sw, p.sh, p.screenRadius);
  }

  /** The punch hole the app is told of (frame pixels); none for lenses, which the app's window is not cut around. */
  static String cutout(Part p) {
    for (Camera c : p.cameras) {
      if (!c.lens) return String.format(Locale.ROOT, "{ \"type\": \"hole\", \"x\": %d, \"y\": %d, \"radius\": %d }",
          (int) Math.round(p.screenX + c.x), (int) Math.round(p.screenY + c.y), (int) Math.ceil(c.r + 0.6 * p.pxPerMm));
    }
    return "null";
  }

  static BufferedImage draw(Part p) {
    int bl = p.px(p.left), bt = p.px(p.top), br = p.px(p.right), bb = p.px(p.bottom);
    int bw = p.sw + bl + br, bh = p.sh + bt + bb;
    int w = bw + 2 * MARGIN, h = bh + 2 * MARGIN;
    p.frameW = w;
    p.frameH = h;
    p.screenX = MARGIN + bl;
    p.screenY = MARGIN + bt;
    p.screenRadius = p.px(p.screenR);
    double radius = p.bodyR * p.pxPerMm;
    double rim = 0.55 * p.pxPerMm;  // the aluminium rail seen from the front, around the glass
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    RoundRectangle2D body = new RoundRectangle2D.Double(MARGIN, MARGIN, bw, bh, 2 * radius, 2 * radius);

    // Keys on the right edge, behind the body: flat aluminium, as the rail.
    for (Button b : p.buttons) {
      double y0 = MARGIN + bh * b.from, y1 = MARGIN + bh * b.to;
      double out = 0.55 * p.pxPerMm;
      g.setPaint(new GradientPaint((float) (w - MARGIN), 0, new Color(0x7a7b7f), (float) (w - MARGIN + out), 0, new Color(0x3e3f43)));
      g.fill(new RoundRectangle2D.Double(w - MARGIN - 6, y0, out + 6, y1 - y0, out * 1.6, out * 1.6));
    }

    // The rail: satin aluminium, lighter along the top and left (the light), with a bright chamfer at its edge.
    g.setPaint(new LinearGradientPaint(new Point2D.Double(MARGIN, MARGIN), new Point2D.Double(MARGIN + bw * 0.35, MARGIN + bh),
        new float[] {0f, 0.45f, 1f}, new Color[] {new Color(0x9fa1a6), new Color(0x6e7075), new Color(0x46484c)}));
    g.fill(body);
    g.setStroke(new BasicStroke((float) Math.max(1.2, 0.12 * p.pxPerMm)));
    g.setPaint(new Color(255, 255, 255, 95));
    double inset = 0.1 * p.pxPerMm;
    g.draw(new RoundRectangle2D.Double(MARGIN + inset, MARGIN + inset, bw - 2 * inset, bh - 2 * inset, 2 * (radius - inset), 2 * (radius - inset)));
    g.setPaint(new Color(0, 0, 0, 120));
    g.setStroke(new BasicStroke(1f));
    g.draw(new RoundRectangle2D.Double(MARGIN + 0.5, MARGIN + 0.5, bw - 1, bh - 1, 2 * radius - 1, 2 * radius - 1));

    // Antenna breaks: short insulating bands across the rail, near the corners.
    g.setClip(body);
    g.setPaint(new Color(0x2b2c30));
    double band = Math.max(2, 0.6 * p.pxPerMm);
    for (double fy : new double[] {0.07, 0.93}) {
      double y = MARGIN + bh * fy;
      g.fill(new Rectangle2D.Double(MARGIN, y, rim + 1, band));
      g.fill(new Rectangle2D.Double(w - MARGIN - rim - 1, y, rim + 1, band));
    }

    // A closed device's hinge: a rounded barrel along that edge, darker than the rail, lit along its crest.
    for (String side : p.hinges) hinge(g, side, MARGIN, MARGIN, bw, bh, radius, p.pxPerMm);

    // An open device's hinges show as breaks in the rails across them.
    g.setPaint(new Color(0x18191b));
    double gap = Math.max(2, 0.35 * p.pxPerMm);
    for (double fx : p.gapsX) {
      double x = p.screenX + p.sw * fx - gap / 2;
      g.fill(new Rectangle2D.Double(x, MARGIN, gap, rim + 2));
      g.fill(new Rectangle2D.Double(x, MARGIN + bh - rim - 2, gap, rim + 2));
    }
    for (double fy : p.gapsY) {
      double y = p.screenY + p.sh * fy - gap / 2;
      g.fill(new Rectangle2D.Double(MARGIN, y, rim + 2, gap));
      g.fill(new Rectangle2D.Double(MARGIN + bw - rim - 2, y, rim + 2, gap));
    }
    g.setClip(null);

    // The glass: black to the screen's edge, a faint sheen towards the top left.
    double gl = rim + (p.hinges.contains("left") ? 1.6 * p.pxPerMm : 0), gr = rim + (p.hinges.contains("right") ? 1.6 * p.pxPerMm : 0);
    double gb = rim + (p.hinges.contains("bottom") ? 1.9 * p.pxPerMm : 0);
    double glassR = Math.max(radius - rim, p.screenRadius + 2);
    RoundRectangle2D glass = new RoundRectangle2D.Double(MARGIN + gl, MARGIN + rim, bw - gl - gr, bh - rim - gb, 2 * glassR, 2 * glassR);
    g.setPaint(new Color(0x050507));
    g.fill(glass);
    g.setPaint(new GradientPaint(MARGIN, MARGIN, new Color(255, 255, 255, 26), (float) (MARGIN + bw * 0.6), (float) (MARGIN + bh * 0.5), new Color(255, 255, 255, 0)));
    g.fill(glass);
    g.setPaint(new Color(255, 255, 255, 30));
    g.setStroke(new BasicStroke(1.5f));
    g.draw(new RoundRectangle2D.Double(MARGIN + gl + 1.5, MARGIN + rim + 1.5, bw - gl - gr - 3, bh - rim - gb - 3, 2 * glassR - 3, 2 * glassR - 3));

    // The screen: a hole.
    g.setComposite(AlphaComposite.Clear);
    g.fill(new RoundRectangle2D.Double(p.screenX, p.screenY, p.sw, p.sh, 2 * p.screenRadius, 2 * p.screenRadius));
    g.setComposite(AlphaComposite.SrcOver);

    // Cameras over the screen.
    for (Camera c : p.cameras) {
      double cx = p.screenX + c.x, cy = p.screenY + c.y;
      if (c.lens) lens(g, cx, cy, c.r, p.pxPerMm);
      else punchHole(g, cx, cy, c.r);
    }
    if (p.flash) {
      Camera last = p.cameras.get(p.cameras.size() - 1);
      double fx = p.screenX + last.x + last.r + 3.2 * p.pxPerMm, fy = p.screenY + last.y + last.r * 0.25;
      double fr = 1.25 * p.pxPerMm;
      g.setPaint(new Color(0x0c0c0e));
      g.fill(new Ellipse2D.Double(fx - fr - 3, fy - fr - 3, 2 * fr + 6, 2 * fr + 6));
      g.setPaint(new RadialGradientPaint(new Point2D.Double(fx, fy), (float) fr, new float[] {0f, 1f},
          new Color[] {new Color(0xf3e7c4), new Color(0xb9ad8c)}));
      g.fill(new Ellipse2D.Double(fx - fr, fy - fr, 2 * fr, 2 * fr));
    }
    g.dispose();
    return img;
  }

  static void hinge(Graphics2D g, String side, double x, double y, double bw, double bh, double radius, double pxPerMm) {
    double t = (side.equals("bottom") ? 2.6 : 2.2) * pxPerMm;
    Shape clip = new RoundRectangle2D.Double(x, y, bw, bh, 2 * radius, 2 * radius);
    Area band = new Area(clip);
    Rectangle2D r = switch (side) {
      case "left" -> new Rectangle2D.Double(x, y, t, bh);
      case "right" -> new Rectangle2D.Double(x + bw - t, y, t, bh);
      default -> new Rectangle2D.Double(x, y + bh - t, bw, t);
    };
    band.intersect(new Area(r));
    boolean vertical = !side.equals("bottom");
    float a0 = (float) (vertical ? r.getX() : r.getY()), a1 = (float) (vertical ? r.getMaxX() : r.getMaxY());
    Point2D p0 = vertical ? new Point2D.Float(a0, 0) : new Point2D.Float(0, a0);
    Point2D p1 = vertical ? new Point2D.Float(a1, 0) : new Point2D.Float(0, a1);
    boolean outerFirst = side.equals("left");  // the barrel's outside is towards the device's edge
    // Satin aluminium as the rail, a shade darker where it rolls away at the edge and at the seam.
    Color edge = new Color(0x4a4c50), crest = new Color(0xa4a6ab), inner = new Color(0x5d5f63);
    g.setPaint(new LinearGradientPaint(p0, p1, new float[] {0f, 0.45f, 1f},
        outerFirst ? new Color[] {edge, crest, inner} : new Color[] {inner, crest, edge}));
    g.fill(band);
    // The seam between the barrel and the glass side.
    g.setPaint(new Color(0, 0, 0, 160));
    g.setStroke(new BasicStroke(1.2f));
    Area seam = new Area(clip);
    Rectangle2D line = switch (side) {
      case "left" -> new Rectangle2D.Double(r.getMaxX() - 1, y, 1.4, bh);
      case "right" -> new Rectangle2D.Double(r.getX(), y, 1.4, bh);
      default -> new Rectangle2D.Double(x, r.getY(), bw, 1.4);
    };
    seam.intersect(new Area(line));
    g.fill(seam);
  }

  static void punchHole(Graphics2D g, double cx, double cy, double r) {
    g.setPaint(new Color(0x020203));
    g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    double l = r * 0.55;
    g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - l * 0.25, cy - l * 0.3), (float) l, new float[] {0f, 0.6f, 1f},
        new Color[] {new Color(0x2c3446), new Color(0x10131a), new Color(0x050608)}));
    g.fill(new Ellipse2D.Double(cx - l, cy - l, 2 * l, 2 * l));
    g.setPaint(new Color(150, 170, 220, 80));
    double s = Math.max(1.5, r * 0.18);
    g.fill(new Ellipse2D.Double(cx - l * 0.45, cy - l * 0.5, s, s));
  }

  /** A rear lens through the FlexWindow: an aluminium ring, a black barrel, glass with a violet coating. */
  static void lens(Graphics2D g, double cx, double cy, double r, double pxPerMm) {
    g.setPaint(new LinearGradientPaint(new Point2D.Double(cx - r, cy - r), new Point2D.Double(cx + r, cy + r),
        new float[] {0f, 0.5f, 1f}, new Color[] {new Color(0xb7b9be), new Color(0x6f7176), new Color(0x3c3d41)}));
    g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    double r1 = r - 0.55 * pxPerMm;
    g.setPaint(new Color(0x0b0b0d));
    g.fill(new Ellipse2D.Double(cx - r1, cy - r1, 2 * r1, 2 * r1));
    double r2 = r1 * 0.62;
    g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - r2 * 0.3, cy - r2 * 0.35), (float) r2, new float[] {0f, 0.55f, 1f},
        new Color[] {new Color(0x3d3a5c), new Color(0x15141f), new Color(0x060608)}));
    g.fill(new Ellipse2D.Double(cx - r2, cy - r2, 2 * r2, 2 * r2));
    g.setPaint(new Color(255, 255, 255, 70));
    double s = r2 * 0.32;
    g.fill(new Ellipse2D.Double(cx - r2 * 0.55, cy - r2 * 0.6, s, s * 0.8));
    g.setPaint(new Color(255, 255, 255, 40));
    g.setStroke(new BasicStroke(1.2f));
    g.draw(new Ellipse2D.Double(cx - r1, cy - r1, 2 * r1, 2 * r1));
  }
}
