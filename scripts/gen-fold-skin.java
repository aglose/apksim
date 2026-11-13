// Draws the built-in pixel-10-pro-fold skin's two frames (runtime/src/main/resources/dev/apksim/skins/):
//   java scripts/gen-fold-skin.java [OUT_DIR]
// frame.png is the device open (the inner display), cover.png the device closed (the cover display, the hinge on its
// left). Both are drawn here from the device's published sizes, not taken from any artwork: the open device is
// 150.4 x 155.2 mm around a 2076x2152 px display, closed 76.3 x 155.2 mm around a 1080x2364 px one, so the bezels
// are ~66 px and ~73 px at the sides and 64 px above and below. The numbers in skin.json beside them must agree
// with the constants here.

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class GenFoldSkin {
  static final int MARGIN = 8;  // room outside the body for the buttons
  static final int RIM = 7;     // the metal rail around the glass

  public static void main(String[] a) throws Exception {
    File out = new File(a.length > 0 ? a[0] : "runtime/src/main/resources/dev/apksim/skins/pixel-10-pro-fold");
    out.mkdirs();
    // Open: screen 2076x2152 at 74,72; the camera in the screen's top right corner.
    ImageIO.write(frame(2076, 2152, 66, 64, 168, 104, 2076 - 170, 92, false), "png", new File(out, "frame.png"));
    // Closed: screen 1080x2364 at 81,72; the camera top centre; the hinge down the left side.
    ImageIO.write(frame(1080, 2364, 73, 64, 150, 96, 540, 84, true), "png", new File(out, "cover.png"));
  }

  static BufferedImage frame(int sw, int sh, int bezelX, int bezelY, int bodyRadius, int screenRadius, int camX, int camY, boolean hinge) {
    int w = sw + 2 * bezelX + 2 * MARGIN, h = sh + 2 * bezelY + 2 * MARGIN;
    int sx = MARGIN + bezelX, sy = MARGIN + bezelY;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    int bw = w - 2 * MARGIN, bh = h - 2 * MARGIN;
    // Buttons on the right rail, behind the body: power, then the volume rocker.
    g.setPaint(new GradientPaint(w - MARGIN - 6, 0, new Color(0x8f8c86), w - 1, 0, new Color(0x5c5a56)));
    g.fill(new RoundRectangle2D.Float(w - MARGIN - 8, h * 0.27f, 14, h * 0.07f, 8, 8));
    g.fill(new RoundRectangle2D.Float(w - MARGIN - 8, h * 0.38f, 14, h * 0.13f, 8, 8));
    // The rail: brushed metal, lit from the top left.
    g.setPaint(new GradientPaint(0, 0, new Color(0xd8d5cf), w, h, new Color(0x7d7a75)));
    g.fill(new RoundRectangle2D.Float(MARGIN, MARGIN, bw, bh, 2 * bodyRadius, 2 * bodyRadius));
    g.setPaint(new Color(255, 255, 255, 70));
    g.setStroke(new BasicStroke(1.5f));
    g.draw(new RoundRectangle2D.Float(MARGIN + 1.5f, MARGIN + 1.5f, bw - 3, bh - 3, 2 * bodyRadius - 3, 2 * bodyRadius - 3));
    if (hinge) {
      // The hinge's barrel: the left rail is wider and rounder, with a line where it meets the glass.
      g.setPaint(new GradientPaint(MARGIN, 0, new Color(0x6e6b67), MARGIN + 22, 0, new Color(0xc9c6c0)));
      g.setClip(new RoundRectangle2D.Float(MARGIN, MARGIN, bw, bh, 2 * bodyRadius, 2 * bodyRadius));
      g.fillRect(MARGIN, MARGIN, 22, bh);
      g.setClip(null);
    }
    // The glass: black to the screen's edge.
    int left = hinge ? RIM + 18 : RIM;
    float gr = bodyRadius - RIM;
    g.setPaint(new Color(0x08080a));
    g.fill(new RoundRectangle2D.Float(MARGIN + left, MARGIN + RIM, bw - left - RIM, bh - 2 * RIM, 2 * gr, 2 * gr));
    g.setPaint(new Color(255, 255, 255, 22));
    g.setStroke(new BasicStroke(2f));
    g.draw(new RoundRectangle2D.Float(MARGIN + left + 3, MARGIN + RIM + 3, bw - left - RIM - 6, bh - 2 * RIM - 6, 2 * gr - 6, 2 * gr - 6));
    // The screen: a hole.
    g.setComposite(AlphaComposite.Clear);
    g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, 2 * screenRadius, 2 * screenRadius));
    g.setComposite(AlphaComposite.SrcOver);
    // The camera, drawn over the screen.
    int cx = sx + camX, cy = sy + camY;
    g.setPaint(new Color(0x050506));
    g.fill(new Ellipse2D.Float(cx - 30, cy - 30, 60, 60));
    g.setPaint(new Color(0x1b1d24));
    g.fill(new Ellipse2D.Float(cx - 15, cy - 15, 30, 30));
    g.setPaint(new Color(120, 140, 190, 90));
    g.fill(new Ellipse2D.Float(cx - 9, cy - 11, 8, 8));
    g.dispose();
    return img;
  }
}
