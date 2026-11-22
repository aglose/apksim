package dev.apksim.gpu;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * App code's Canvas draws come through here (the DEX translator rewrites the calls). Anything without a
 * tracked shader passes straight through; a tracked shader's draw uses its GPU-made pixels instead.
 */
public final class GpuCanvas {
  private GpuCanvas() {}

  /** Swaps the paint's shader for GPU output; returns what to restore, or null if nothing changed. */
  private static Shader swap(Canvas c, Paint p, float l, float t, float r, float b) {
    Shader original = p.getShader();
    if (!(original instanceof TrackedRuntimeShader tracked)) return null;
    if (l > r) {
      float tmp = l;
      l = r;
      r = tmp;
    }
    if (t > b) {
      float tmp = t;
      t = b;
      b = tmp;
    }
    if (p.getStyle() != Paint.Style.FILL) {
      float sw = p.getStrokeWidth();
      // Half the width, times the miter limit for MITER joins, or sqrt(2) for SQUARE caps (a cap's corner), plus a pixel.
      float reach = Math.max(p.getStrokeJoin() == Paint.Join.MITER ? Math.max(1f, p.getStrokeMiter()) : 1f,
          p.getStrokeCap() == Paint.Cap.SQUARE ? 1.4143f : 1f);
      float pad = sw <= 0f ? 1f : sw * 0.5f * reach + 1f;
      l -= pad;
      t -= pad;
      r += pad;
      b += pad;
    }
    int x0 = (int) Math.floor(l);
    int y0 = (int) Math.floor(t);
    int x1 = (int) Math.ceil(r);
    int y1 = (int) Math.ceil(b);
    // RecordingCanvas.getClipBounds starts from the RenderNode's dimensions even when clipToBounds is false;
    // intersecting with it would cut off bleed draws (glows/effects extending past a node's bounds) and make
    // BitmapShader's CLAMP mode streak the cut edges outward. A clip the app set itself (clipRect inside the node)
    // still bounds the draw: only that default one is passed over.
    Rect clip = new Rect();
    if (c.getClipBounds(clip) && (clipsToBounds(c) || !isNodeDefaultClip((RecordingCanvas) c, clip))) {
      x0 = Math.max(x0, clip.left);
      y0 = Math.max(y0, clip.top);
      x1 = Math.min(x1, clip.right);
      y1 = Math.min(y1, clip.bottom);
    }
    Shader gpu = tracked.gpuOutput(c, x0, y0, x1 - x0, y1 - y0);
    if (gpu == null) return null;
    p.setShader(gpu);
    return original;
  }

  /** Whether [clip] (local coordinates) is the node's own bounds, (0, 0, width, height) on the device: no clip of the app's. */
  private static boolean isNodeDefaultClip(RecordingCanvas c, Rect clip) {
    if (c.mNode == null) return false;
    android.graphics.Matrix m = new android.graphics.Matrix();
    c.getMatrix(m);
    RectF r = new RectF(clip);
    m.mapRect(r);
    return Math.abs(r.left) <= 1 && Math.abs(r.top) <= 1 && Math.abs(r.right - c.mNode.getWidth()) <= 1
        && Math.abs(r.bottom - c.mNode.getHeight()) <= 1;
  }

  private static boolean clipsToBounds(Canvas c) {
    return !(c instanceof RecordingCanvas rc) || (rc.mNode != null && rc.mNode.getClipToBounds());
  }

  private static void restore(Paint p, Shader original, boolean wasDithered) {
    if (original == null) return;
    p.setShader(original);
    if (wasDithered) p.setDither(true);
  }

  private static boolean clearDither(Paint p, Shader swapped) {
    if (swapped == null || !p.isDither()) return false;
    // Dithering only hides banding in gradients; on a finished image it just forces a slower blit.
    p.setDither(false);
    return true;
  }

  public static void drawRect(Canvas c, float l, float t, float r, float b, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    boolean d = clearDither(p, s);
    c.drawRect(l, t, r, b, p);
    restore(p, s, d);
  }

  public static void drawRect(Canvas c, RectF rect, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    boolean d = clearDither(p, s);
    c.drawRect(rect, p);
    restore(p, s, d);
  }

  public static void drawRect(Canvas c, Rect rect, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    boolean d = clearDither(p, s);
    c.drawRect(rect, p);
    restore(p, s, d);
  }

  public static void drawRoundRect(Canvas c, float l, float t, float r, float b, float rx, float ry, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    boolean d = clearDither(p, s);
    c.drawRoundRect(l, t, r, b, rx, ry, p);
    restore(p, s, d);
  }

  public static void drawRoundRect(Canvas c, RectF rect, float rx, float ry, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    boolean d = clearDither(p, s);
    c.drawRoundRect(rect, rx, ry, p);
    restore(p, s, d);
  }

  public static void drawOval(Canvas c, float l, float t, float r, float b, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    boolean d = clearDither(p, s);
    c.drawOval(l, t, r, b, p);
    restore(p, s, d);
  }

  public static void drawOval(Canvas c, RectF rect, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    boolean d = clearDither(p, s);
    c.drawOval(rect, p);
    restore(p, s, d);
  }

  public static void drawCircle(Canvas c, float cx, float cy, float radius, Paint p) {
    Shader s = swap(c, p, cx - radius, cy - radius, cx + radius, cy + radius);
    boolean d = clearDither(p, s);
    c.drawCircle(cx, cy, radius, p);
    restore(p, s, d);
  }

  public static void drawArc(Canvas c, float l, float t, float r, float b, float start, float sweep, boolean center, Paint p) {
    Shader s = swap(c, p, l, t, r, b);
    boolean d = clearDither(p, s);
    c.drawArc(l, t, r, b, start, sweep, center, p);
    restore(p, s, d);
  }

  public static void drawArc(Canvas c, RectF rect, float start, float sweep, boolean center, Paint p) {
    Shader s = swap(c, p, rect.left, rect.top, rect.right, rect.bottom);
    boolean d = clearDither(p, s);
    c.drawArc(rect, start, sweep, center, p);
    restore(p, s, d);
  }

  public static void drawPath(Canvas c, Path path, Paint p) {
    Shader s = null;
    if (p.getShader() instanceof TrackedRuntimeShader) {
      RectF bounds = new RectF();
      path.computeBounds(bounds, true);
      s = swap(c, p, bounds.left, bounds.top, bounds.right, bounds.bottom);
    }
    boolean d = clearDither(p, s);
    c.drawPath(path, p);
    restore(p, s, d);
  }

  public static void drawPaint(Canvas c, Paint p) {
    Shader s = null;
    if (p.getShader() instanceof TrackedRuntimeShader) {
      Rect clip = new Rect();
      if (c.getClipBounds(clip)) s = swap(c, p, clip.left, clip.top, clip.right, clip.bottom);
    }
    boolean d = clearDither(p, s);
    c.drawPaint(p);
    restore(p, s, d);
  }
}
