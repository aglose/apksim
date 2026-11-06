package dev.apksim.gpu;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowNativeBaseRecordingCanvas;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import org.robolectric.util.reflector.Static;

/**
 * Bitmap draws on HWUI's recording canvases (every View's and Compose layer's display list) go through
 * {@link GpuBitmaps}, which swaps a scaled draw that holds still for a GPU-scaled copy drawn 1:1. Framework code is
 * not translated, so this is where ImageView, BitmapDrawable and the app's own draws all meet. Everything else is
 * Robolectric's native canvas (`callNativeMethodsByDefault`, as its own shadow: without it the natives do nothing).
 */
@Implements(className = "android.graphics.BaseRecordingCanvas", minSdk = 29, isInAndroidSdk = false, callNativeMethodsByDefault = true)
public class ShadowGpuRecordingCanvas extends ShadowNativeBaseRecordingCanvas {
  @RealObject private Canvas real;
  private final RectF dst = new RectF();
  private final float[] m = new float[9];

  @Implementation
  protected void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
    if (!GpuBitmaps.draw(real, bitmap, src, dst.left, dst.top, dst.right, dst.bottom, paint)) {
      reflector(Direct_.class, real).drawBitmap(bitmap, src, dst, paint);
    }
  }

  @Implementation
  protected void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
    if (!GpuBitmaps.draw(real, bitmap, src, dst.left, dst.top, dst.right, dst.bottom, paint)) {
      reflector(Direct_.class, real).drawBitmap(bitmap, src, dst, paint);
    }
  }

  @Implementation
  protected void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
    // Drawn at its own size unless the canvas scales it (a density mismatch is HWUI's to scale).
    int density = real.getDensity();
    boolean plain = density == Bitmap.DENSITY_NONE || bitmap.getDensity() == Bitmap.DENSITY_NONE || density == bitmap.getDensity();
    if (!GpuBitmaps.ENABLED || GpuBitmaps.bypass || !plain
        || !GpuBitmaps.draw(real, bitmap, null, left, top, left + bitmap.getWidth(), top + bitmap.getHeight(), paint)) {
      reflector(Direct_.class, real).drawBitmap(bitmap, left, top, paint);
    }
  }

  @Implementation
  protected void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
    if (GpuBitmaps.ENABLED && !GpuBitmaps.bypass && paint != null) {
      matrix.getValues(m);
      if (m[1] == 0 && m[3] == 0 && m[6] == 0 && m[7] == 0 && m[8] == 1 && m[0] > 0 && m[4] > 0) {
        dst.set(m[2], m[5], m[2] + m[0] * bitmap.getWidth(), m[5] + m[4] * bitmap.getHeight());
        if (GpuBitmaps.draw(real, bitmap, null, dst.left, dst.top, dst.right, dst.bottom, paint)) return;
      }
    }
    reflector(Direct_.class, real).drawBitmap(bitmap, matrix, paint);
  }

  /**
   * RecordingCanvas's shadow, which must be one: Robolectric gives an object the shadow of its own class and casts
   * it to the shadow of the class declaring the method called. Robolectric's own (ShadowNativeRecordingCanvas) has
   * only natives up to API 34, which call through on later APIs anyway.
   */
  @Implements(value = android.graphics.RecordingCanvas.class, minSdk = 29, callNativeMethodsByDefault = true)
  public static class Recording extends ShadowGpuRecordingCanvas {
    /** RenderNode.beginRecording: the node's last recording, and the copies it drew, are about to be replaced. */
    @Implementation
    protected static android.graphics.RecordingCanvas obtain(android.graphics.RenderNode node, int width, int height) {
      if (GpuBitmaps.ENABLED) GpuBitmaps.recordingStarts(node);
      return reflector(Obtain_.class).obtain(node, width, height);
    }
  }

  @ForType(android.graphics.RecordingCanvas.class)
  interface Obtain_ {
    @Static
    @Direct
    android.graphics.RecordingCanvas obtain(android.graphics.RenderNode node, int width, int height);
  }

  @ForType(className = "android.graphics.BaseRecordingCanvas")
  interface Direct_ {
    @Direct
    void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint);

    @Direct
    void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint);

    @Direct
    void drawBitmap(Bitmap bitmap, float left, float top, Paint paint);

    @Direct
    void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint);
  }
}
