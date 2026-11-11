package dev.apksim.skin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Image work for skins: finding the screen hole in a frame, and drawing a test pattern into it. */
final class Frames {
  private Frames() {}

  /** Where the hole is: bounding box in the frame's pixels, and the corner radius its missing corners add up to. */
  record Hole(int x, int y, int width, int height, int cornerRadius, long pixels) {}

  /**
   * The screen: the largest region of fully transparent pixels that does not reach the image's edge (the transparent
   * pixels around a rounded frame are connected to the edge, the hole is not). Null when there is none. The corner
   * radius is what the hole lacks of its bounding box, spread over four quarter-circle corners; a camera cut-out
   * drawn opaque inside the screen inflates it, so give --corner-radius for such a frame.
   */
  static Hole findHole(BufferedImage img) {
    if (!img.getColorModel().hasAlpha()) return null;
    int w = img.getWidth(), h = img.getHeight();
    byte[] t = new byte[w * h];  // 1: transparent, not visited
    int[] row = new int[w];
    for (int y = 0; y < h; y++) {
      img.getRGB(0, y, w, 1, row, 0, w);
      for (int x = 0; x < w; x++) if ((row[x] >>> 24) == 0) t[y * w + x] = 1;
    }
    Hole best = null;
    int[] stack = new int[1024];
    for (int start = 0; start < t.length; start++) {
      if (t[start] != 1) continue;
      int sp = 0;
      stack[sp++] = start;
      t[start] = 2;
      long count = 0;
      int minX = w, minY = h, maxX = -1, maxY = -1;
      boolean edge = false;
      while (sp > 0) {
        int p = stack[--sp];
        int px = p % w, py = p / w;
        count++;
        if (px < minX) minX = px;
        if (px > maxX) maxX = px;
        if (py < minY) minY = py;
        if (py > maxY) maxY = py;
        if (px == 0 || py == 0 || px == w - 1 || py == h - 1) edge = true;
        if (sp + 4 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
        if (px > 0 && t[p - 1] == 1) { t[p - 1] = 2; stack[sp++] = p - 1; }
        if (px < w - 1 && t[p + 1] == 1) { t[p + 1] = 2; stack[sp++] = p + 1; }
        if (py > 0 && t[p - w] == 1) { t[p - w] = 2; stack[sp++] = p - w; }
        if (py < h - 1 && t[p + w] == 1) { t[p + w] = 2; stack[sp++] = p + w; }
      }
      if (edge) continue;
      if (best == null || count > best.pixels) {
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        double missing = (double) bw * bh - count;
        int r = 0;
        if (missing / ((double) bw * bh) > 0.0002) r = (int) Math.round(Math.sqrt(missing / (4 * (1 - Math.PI / 4))));
        best = new Hole(minX, minY, bw, bh, Math.min(r, Math.min(bw, bh) / 2), count);
      }
    }
    return best;
  }

  /** The share of the screen's rounded rectangle (0-1) that the frame leaves transparent. */
  static double openShare(BufferedImage frame, Skin s) {
    RoundRectangle2D shape = new RoundRectangle2D.Double(s.x, s.y, s.width, s.height, s.cornerRadius * 2.0, s.cornerRadius * 2.0);
    long total = 0, open = 0;
    int step = Math.max(1, Math.min(s.width, s.height) / 200);
    for (int y = Math.max(0, s.y); y < Math.min(frame.getHeight(), s.y + s.height); y += step) {
      for (int x = Math.max(0, s.x); x < Math.min(frame.getWidth(), s.x + s.width); x += step) {
        if (!shape.contains(x + 0.5, y + 0.5)) continue;
        total++;
        if ((frame.getRGB(x, y) >>> 24) < 8) open++;
      }
    }
    return total == 0 ? 0 : (double) open / total;
  }

  /** The frame with a test pattern in its screen: a gradient, a grid every 100 px, corner markers and the device's size. */
  static BufferedImage preview(BufferedImage frame, BufferedImage mask, Skin s, String label) {
    int w = frame.getWidth(), h = frame.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0xE8E8E8));
    g.fillRect(0, 0, w, h);
    BufferedImage scr = new BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D p = scr.createGraphics();
    p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    p.setPaint(new GradientPaint(0, 0, new Color(0x3F51B5), s.width, s.height, new Color(0xFF9800)));
    p.fillRect(0, 0, s.width, s.height);
    p.setStroke(new BasicStroke(Math.max(1f, s.width / 540f)));
    p.setColor(new Color(255, 255, 255, 110));
    for (int gx = 0; gx < s.width; gx += 100) p.drawLine(gx, 0, gx, s.height);
    for (int gy = 0; gy < s.height; gy += 100) p.drawLine(0, gy, s.width, gy);
    int m = Math.max(8, s.width / 20);
    p.setColor(Color.RED);
    p.fillRect(0, 0, m, m);
    p.setColor(Color.GREEN);
    p.fillRect(s.width - m, 0, m, m);
    p.setColor(Color.BLUE);
    p.fillRect(0, s.height - m, m, m);
    p.setColor(Color.YELLOW);
    p.fillRect(s.width - m, s.height - m, m, m);
    p.setColor(Color.WHITE);
    p.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, s.width / 14)));
    p.drawString(label, m * 2, s.height / 2);
    p.dispose();
    // The screen's shape: the mask's alpha, else a rounded rectangle.
    BufferedImage shaped = new BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D q = shaped.createGraphics();
    q.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    if (mask != null) {
      q.drawImage(mask, 0, 0, null);
    } else {
      q.setColor(Color.BLACK);
      q.fill(new RoundRectangle2D.Double(0, 0, s.width, s.height, s.cornerRadius * 2.0, s.cornerRadius * 2.0));
    }
    q.setComposite(java.awt.AlphaComposite.SrcIn);
    q.drawImage(scr, 0, 0, null);
    q.dispose();
    g.drawImage(shaped, s.x, s.y, null);
    g.drawImage(frame, 0, 0, null);
    g.dispose();
    return out;
  }
}
