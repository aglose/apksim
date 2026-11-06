package dev.apksim.gpu;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * App code's Canvas draws come through here (the DEX translator rewrites the calls). Anything without a
 * tracked shader passes straight through; a tracked shader's draw uses its GPU-made pixels instead. (Bitmap draws,
 * the app's and the framework's, go through ShadowGpuRecordingCanvas.)
 */
public final class GpuCanvas {
  private static final Rect CLIP = new Rect();
  private static final RectF BOUNDS = new RectF();
  private static boolean wasDithered;

  private GpuCanvas() {}

  /** Swaps the paint's shader for GPU output; returns what to restore, or null if nothing changed. */
  private static Shader swap(Canvas c, Paint p, float l, float t, float r, float b) {
    Shader original = p.getShader();
    if (!(original instanceof TrackedRuntimeShader tracked)) return null;
    int x0 = (int) Math.floor(l);
    int y0 = (int) Math.floor(t);
    int x1 = (int) Math.ceil(r);
    int y1 = (int) Math.ceil(b);
    if (c.getClipBounds(CLIP)) {
      x0 = Math.max(x0, CLIP.left);
      y0 = Math.max(y0, CLIP.top);
      x1 = Math.min(x1, CLIP.right);
      y1 = Math.min(y1, CLIP.bottom);
    }
    Shader gpu = tracked.gpuOutput(x0, y0, x1 - x0, y1 - y0);
    if (gpu == null) return null;
    p.setShader(gpu);
    // Dithering only hides banding in gradients; on a finished image it just forces a slower blit.
    wasDithered = p.isDither();
    if (wasDithered) p.setDither(false);
    return original;
  }

  private static void restore(Paint p, Shader original) {
    if (original == null) return;
    p.setShader(original);
    if (wasDithered) p.setDither(true);
  }

  public static void drawRect(Canvas c, float l, float t, float r, float b, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    c.drawRect(l, t, r, b, p);
    restore(p, s);
  }

  public static void drawRect(Canvas c, RectF rect, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    c.drawRect(rect, p);
    restore(p, s);
  }

  public static void drawRect(Canvas c, Rect rect, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    c.drawRect(rect, p);
    restore(p, s);
  }

  public static void drawRoundRect(Canvas c, float l, float t, float r, float b, float rx, float ry, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    c.drawRoundRect(l, t, r, b, rx, ry, p);
    restore(p, s);
  }

  public static void drawRoundRect(Canvas c, RectF rect, float rx, float ry, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    c.drawRoundRect(rect, rx, ry, p);
    restore(p, s);
  }

  public static void drawOval(Canvas c, float l, float t, float r, float b, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    c.drawOval(l, t, r, b, p);
    restore(p, s);
  }

  public static void drawOval(Canvas c, RectF rect, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    c.drawOval(rect, p);
    restore(p, s);
  }

  public static void drawCircle(Canvas c, float cx, float cy, float radius, Paint p) {
    Shader s = swap(c, p, cx - radius, cy - radius, cx + radius, cy + radius);
    c.drawCircle(cx, cy, radius, p);
    restore(p, s);
  }

  public static void drawArc(Canvas c, float l, float t, float r, float b, float start, float sweep, boolean center, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    c.drawArc(l, t, r, b, start, sweep, center, p);
    restore(p, s);
  }

  public static void drawArc(Canvas c, RectF rect, float start, float sweep, boolean center, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    c.drawArc(rect, start, sweep, center, p);
    restore(p, s);
  }

  public static void drawPath(Canvas c, Path path, Paint p) {
    Shader s = null;
    if (p.getShader() instanceof TrackedRuntimeShader) {
      path.computeBounds(BOUNDS, true);
      s = swap(c, p, BOUNDS.left, BOUNDS.top, BOUNDS.right, BOUNDS.bottom);
    }
    c.drawPath(path, p);
    restore(p, s);
  }

  public static void drawPaint(Canvas c, Paint p) {
    Shader s = null;
    if (p.getShader() instanceof TrackedRuntimeShader && c.getClipBounds(CLIP)) {
      s = swap(c, p, CLIP.left, CLIP.top, CLIP.right, CLIP.bottom);
    }
    c.drawPaint(p);
    restore(p, s);
  }
}
