package dev.apksim.gpu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Looper;

/**
 * App code's Canvas draws come through here (the DEX translator rewrites the calls). Anything without a
 * tracked shader passes straight through; a tracked shader's draw uses its GPU-made pixels instead.
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

  public static void drawBitmap(Canvas c, Bitmap b, Rect src, Rect dst, Paint p) {
    Bitmap scaled = scaledFor(c, b, src, dst.left, dst.top, dst.right, dst.bottom, p);
    if (scaled == null) {
      c.drawBitmap(b, src, dst, p);
      return;
    }
    // Dithering only hides banding in gradients; on a 1:1 image it just forces a slower blit.
    boolean dither = p.isDither();
    if (dither) p.setDither(false);
    c.drawBitmap(scaled, null, dst, p);
    if (dither) p.setDither(true);
  }

  public static void drawBitmap(Canvas c, Bitmap b, Rect src, RectF dst, Paint p) {
    Bitmap scaled = scaledFor(c, b, src, dst.left, dst.top, dst.right, dst.bottom, p);
    if (scaled == null) {
      c.drawBitmap(b, src, dst, p);
      return;
    }
    boolean dither = p.isDither();
    if (dither) p.setDither(false);
    c.drawBitmap(scaled, null, dst, p);
    if (dither) p.setDither(true);
  }

  private static final Matrix MATRIX = new Matrix();
  private static final float[] M = new float[9];

  /**
   * A GPU-scaled copy of [b] for a filtered draw that scales it onto whole device pixels of a recording canvas
   * (scale and translation only), or null to draw it as it is.
   */
  @SuppressWarnings("deprecation")
  private static Bitmap scaledFor(Canvas c, Bitmap b, Rect src, float l, float t, float r, float bottom, Paint p) {
    if (!GpuBitmaps.ENABLED || p == null || !p.isFilterBitmap() || !c.isHardwareAccelerated()
        || Looper.myLooper() != Looper.getMainLooper()) {
      return null;
    }
    c.getMatrix(MATRIX);
    MATRIX.getValues(M);
    if (M[1] != 0 || M[3] != 0 || M[6] != 0 || M[7] != 0 || M[8] != 1 || M[0] <= 0 || M[4] <= 0) {
      GpuBitmaps.passed[3]++;
      return null;
    }
    float dl = M[0] * l + M[2];
    float dt = M[4] * t + M[5];
    float dr = M[0] * r + M[2];
    float db = M[4] * bottom + M[5];
    int il = Math.round(dl);
    int it = Math.round(dt);
    int ir = Math.round(dr);
    int ib = Math.round(db);
    // Off whole pixels the copy would be sampled again, half a pixel off; leave those to HWUI.
    if (Math.abs(dl - il) > 1e-3f || Math.abs(dt - it) > 1e-3f || Math.abs(dr - ir) > 1e-3f || Math.abs(db - ib) > 1e-3f) {
      GpuBitmaps.passed[3]++;
      return null;
    }
    return GpuBitmaps.scaled(b, src, ir - il, ib - it);
  }
}
