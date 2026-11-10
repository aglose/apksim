package dev.apksim.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What the fake camera sees, drawn upright as a person holding the phone would see it: the test pattern (colour bars, a
 * white bar sweeping across, the frame number and time), a still image (centre-cropped), or black while the camera is
 * blocked (`camera off`). {@link #draw} puts it into a buffer turned and mirrored as the camera's buffer is. Used by the
 * camera thread only.
 */
final class Scene {
  private static final int[] BARS = {
    0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0,
  };
  private final Paint fill = new Paint();
  private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Matrix matrix = new Matrix();
  private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

  Scene() {
    text.setColor(Color.WHITE);
    text.setTypeface(Typeface.MONOSPACE);
    text.setTextAlign(Paint.Align.CENTER);
  }

  /**
   * Draws the scene into a {@code cw} x {@code ch} canvas, turned {@code rotation} degrees clockwise and then mirrored
   * left to right if {@code mirror}: the scene is {@code ch} x {@code cw} when the turn is 90 or 270 degrees.
   */
  void draw(Canvas c, int cw, int ch, int rotation, boolean mirror, long frame, long wallMillis) {
    boolean sideways = rotation == 90 || rotation == 270;
    int sw = sideways ? ch : cw, sh = sideways ? cw : ch;
    matrix.setRotate(rotation);
    switch (rotation) {
      case 90 -> matrix.postTranslate(cw, 0);
      case 180 -> matrix.postTranslate(cw, ch);
      case 270 -> matrix.postTranslate(0, ch);
      default -> {}
    }
    if (mirror) {
      matrix.postScale(-1, 1);
      matrix.postTranslate(cw, 0);
    }
    c.save();
    c.concat(matrix);
    upright(c, sw, sh, frame, wallMillis);
    c.restore();
  }

  private void upright(Canvas c, int w, int h, long frame, long wallMillis) {
    FakeCamera.Source source = FakeCamera.source;
    if (source.blocked()) {
      c.drawColor(Color.BLACK);
      return;
    }
    Bitmap still = source.image();
    if (still != null) {
      // Centre-cropped to fill the frame, as a camera pointed at it would see it.
      float scale = Math.max((float) w / still.getWidth(), (float) h / still.getHeight());
      float dw = still.getWidth() * scale, dh = still.getHeight() * scale;
      c.drawColor(Color.BLACK);
      c.drawBitmap(still, null, new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), image);
      return;
    }
    // Colour bars, a white bar sweeping left to right once every two seconds, and a panel with the frame number and
    // the time: every frame differs, so a pipeline waiting for a new picture keeps going.
    int bw = (w + BARS.length - 1) / BARS.length;
    for (int i = 0; i < BARS.length; i++) {
      fill.setColor(BARS[i]);
      c.drawRect(i * bw, 0, Math.min(w, (i + 1) * bw), h, fill);
    }
    int sweep = Math.max(8, w / 16);
    long x = (frame * (w + sweep) / (2L * FakeCamera.FPS)) % (w + sweep) - sweep;
    fill.setColor(Color.WHITE);
    c.drawRect(x, 0, x + sweep, h, fill);
    float unit = Math.min(w, h) / 12f;
    fill.setColor(0xC0000000);
    c.drawRect(new Rect((int) (w / 2 - 4.5f * unit), (int) (h / 2 - 1.8f * unit), (int) (w / 2 + 4.5f * unit),
        (int) (h / 2 + 1.8f * unit)), fill);
    text.setTextSize(unit * 0.8f);
    c.drawText("apksim camera", w / 2f, h / 2f - 0.7f * unit, text);
    text.setTextSize(unit * 0.9f);
    c.drawText(String.format(Locale.ROOT, "frame %d", frame), w / 2f, h / 2f + 0.35f * unit, text);
    text.setTextSize(unit * 0.7f);
    c.drawText(clock.format(new Date(wallMillis)), w / 2f, h / 2f + 1.3f * unit, text);
    // Which way is up, for checking rotation: a triangle at the top edge.
    android.graphics.Path up = new android.graphics.Path();
    up.moveTo(w / 2f, unit * 0.3f);
    up.lineTo(w / 2f - unit, unit * 1.3f);
    up.lineTo(w / 2f + unit, unit * 1.3f);
    up.close();
    fill.setColor(Color.BLACK);
    c.drawPath(up, fill);
  }
}
