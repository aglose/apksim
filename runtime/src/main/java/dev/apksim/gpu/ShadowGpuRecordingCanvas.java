package dev.apksim.gpu;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.BaseRecordingCanvas;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowNativeBaseRecordingCanvas;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * Every bitmap drawn into a RenderNode recording (View drawing in the framework: ImageView, BitmapDrawable
 * backgrounds, and app code's own Canvas calls) goes through {@link GpuBitmaps}: a filtered draw that scales it onto
 * whole device pixels draws a copy made at that size on Metal instead, which HWUI then blits 1:1. Robolectric's own
 * shadow of the class stays underneath (its natives are the real HWUI ones). An object has the shadow of its own
 * class, which must extend the one of the class declaring the method, so RecordingCanvas (the only concrete
 * subclass) gets {@link Recording} in place of Robolectric's ShadowNativeRecordingCanvas, whose methods all stop at
 * API 34 (the natives are called as they are).
 */
@Implements(value = BaseRecordingCanvas.class, callNativeMethodsByDefault = true, isInAndroidSdk = false)
public class ShadowGpuRecordingCanvas extends ShadowNativeBaseRecordingCanvas {
  private static final Matrix MATRIX = new Matrix();
  private static final float[] M = new float[9];
  private static final RectF DST = new RectF();

  @RealObject private BaseRecordingCanvas real;

  @Implementation
  protected void drawBitmap(Bitmap b, Rect src, Rect dst, Paint p) {
    Bitmap scaled = dst == null ? null : scaledFor(b, src, dst.left, dst.top, dst.right, dst.bottom, null, p);
    if (scaled == null) direct().drawBitmap(b, src, dst, p);
    else drawCopy(scaled, dst.left, dst.top, dst.right, dst.bottom, p);
  }

  @Implementation
  protected void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
    Bitmap scaled = dst == null ? null : scaledFor(b, src, dst.left, dst.top, dst.right, dst.bottom, null, p);
    if (scaled == null) direct().drawBitmap(b, src, dst, p);
    else drawCopy(scaled, dst.left, dst.top, dst.right, dst.bottom, p);
  }

  @Implementation
  protected void drawBitmap(Bitmap b, float left, float top, Paint p) {
    Bitmap scaled = scaledFor(b, null, left, top, left + b.getWidth(), top + b.getHeight(), null, p);
    if (scaled == null) direct().drawBitmap(b, left, top, p);
    else drawCopy(scaled, left, top, left + b.getWidth(), top + b.getHeight(), p);
  }

  /** A matrix that only scales (positively) and translates is a draw into the rectangle it maps the bitmap to. */
  @Implementation
  protected void drawBitmap(Bitmap b, Matrix matrix, Paint p) {
    Bitmap scaled = null;
    if (matrix != null) {
      matrix.getValues(M);
      if (M[1] == 0 && M[3] == 0 && M[6] == 0 && M[7] == 0 && M[8] == 1 && M[0] > 0 && M[4] > 0) {
        float l = M[2];
        float t = M[5];
        float r = M[0] * b.getWidth() + M[2];
        float bottom = M[4] * b.getHeight() + M[5];
        scaled = scaledFor(b, null, l, t, r, bottom, DST, p);
      } else if (wanted(b, p)) {
        GpuBitmaps.passed[3]++;
      }
    }
    if (scaled == null) direct().drawBitmap(b, matrix, p);
    else drawCopy(scaled, DST.left, DST.top, DST.right, DST.bottom, p);
  }

  private void drawCopy(Bitmap scaled, float l, float t, float r, float b, Paint p) {
    DST.set(l, t, r, b);
    // Dithering only hides banding in gradients; on a 1:1 image it just forces a slower blit.
    boolean dither = p.isDither();
    if (dither) p.setDither(false);
    direct().drawBitmap(scaled, null, DST, p);
    if (dither) p.setDither(true);
  }

  private BaseRecordingCanvasReflector direct() {
    return reflector(BaseRecordingCanvasReflector.class, real);
  }

  private static boolean wanted(Bitmap b, Paint p) {
    return GpuBitmaps.ENABLED && !GpuBitmaps.suspended && b != null && p != null && p.isFilterBitmap()
        && Looper.myLooper() == Looper.getMainLooper();
  }

  /**
   * A GPU-scaled copy of [b] for a filtered draw that scales it onto whole device pixels of this recording (scale
   * and translation only), or null to draw it as it is. [keep], if given, gets the local rectangle.
   */
  @SuppressWarnings("deprecation")
  private Bitmap scaledFor(Bitmap b, Rect src, float l, float t, float r, float bottom, RectF keep, Paint p) {
    if (!wanted(b, p)) return null;
    real.getMatrix(MATRIX);
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
    int sw = src == null ? b.getWidth() : src.width();
    int sh = src == null ? b.getHeight() : src.height();
    if (ir - il == sw && ib - it == sh) return null;
    // Off whole pixels the copy would be sampled again, half a pixel off; leave those to HWUI.
    if (Math.abs(dl - il) > 1e-3f || Math.abs(dt - it) > 1e-3f || Math.abs(dr - ir) > 1e-3f || Math.abs(db - ib) > 1e-3f) {
      GpuBitmaps.passed[3]++;
      return null;
    }
    if (keep != null) keep.set(l, t, r, bottom);
    return GpuBitmaps.scaled(b, src, ir - il, ib - it);
  }

  @Implements(value = android.graphics.RecordingCanvas.class, callNativeMethodsByDefault = true, isInAndroidSdk = false)
  public static class Recording extends ShadowGpuRecordingCanvas {}

  @ForType(BaseRecordingCanvas.class)
  interface BaseRecordingCanvasReflector {
    @Direct
    void drawBitmap(Bitmap b, Rect src, Rect dst, Paint p);

    @Direct
    void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p);

    @Direct
    void drawBitmap(Bitmap b, float left, float top, Paint p);

    @Direct
    void drawBitmap(Bitmap b, Matrix matrix, Paint p);
  }
}
