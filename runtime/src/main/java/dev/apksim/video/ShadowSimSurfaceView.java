package dev.apksim.video;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowSurfaceView;

/**
 * A SurfaceView whose surface works without a compositor. On a device the view's surface is a separate layer behind
 * the window, created once the view is attached and laid out; Robolectric's holder never creates one, so a video
 * player given a SurfaceView decoded every frame and had nowhere to show it. Here the holder's Surface is named after a
 * SurfaceTexture registered with {@link VideoSurfaces}, as {@link ShadowSimTextureView}'s is, its callbacks hear
 * surfaceCreated/surfaceChanged once the view has a size and surfaceDestroyed when it leaves the window, and the view
 * draws the latest picture published under that name in its bounds (in the window, where the hole would be).
 * Running instances only: test runs keep Robolectric's holder, which their tests drive (getFakeSurfaceHolder).
 */
@Implements(SurfaceView.class)
public class ShadowSimSurfaceView extends ShadowSurfaceView {
  private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);

  @RealObject private SurfaceView real;
  private final Holder holder = new Holder();
  private SurfaceTexture texture;
  private Surface surface;
  private int width;
  private int height;

  /** Test runs keep Robolectric's SurfaceView: an app's tests may drive its fake holder. Read each time: a standby
   * JVM loads this class before it knows whether its launch is a test run. */
  private static boolean testRun() {
    return System.getProperty("apksim.instrument") != null;
  }

  @Implementation
  @Override
  protected SurfaceHolder getHolder() {
    return testRun() ? super.getHolder() : holder;
  }

  @Implementation
  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (!testRun()) real.setWillNotDraw(false);  // SurfaceView skips draw(): the window has a hole there on a device
  }

  /** Creates the surface once the view has a size, and reports size changes, as the system does after layout. */
  private void update() {
    int w = real.getWidth(), h = real.getHeight();
    if (w <= 0 || h <= 0 || !real.isAttachedToWindow()) return;
    if (surface == null) {
      texture = new SurfaceTexture(false);
      VideoSurfaces.attach(texture, real);
      surface = new Surface(texture);
      for (SurfaceHolder.Callback c : holder.callbacks) c.surfaceCreated(holder);
    }
    if (w != width || h != height) {
      width = w;
      height = h;
      for (SurfaceHolder.Callback c : holder.callbacks) c.surfaceChanged(holder, PixelFormat.RGBX_8888, w, h);
    }
  }

  @Implementation
  protected void onDetachedFromWindow() {
    if (surface != null) {
      for (SurfaceHolder.Callback c : holder.callbacks) c.surfaceDestroyed(holder);
      VideoSurfaces.detach(texture);
      surface.release();
      surface = null;
      texture = null;
      width = height = 0;
    }
    org.robolectric.util.reflector.Reflector.reflector(SurfaceViewReflector.class, real).onDetachedFromWindow();
  }

  @Implementation
  protected void draw(Canvas canvas) {
    if (testRun()) {
      org.robolectric.util.reflector.Reflector.reflector(SurfaceViewReflector.class, real).draw(canvas);
      return;
    }
    update();
    Bitmap picture = texture == null ? null : VideoSurfaces.picture(texture);
    if (picture != null) canvas.drawBitmap(picture, null, new Rect(0, 0, real.getWidth(), real.getHeight()), FILTER);
  }

  @org.robolectric.util.reflector.ForType(SurfaceView.class)
  interface SurfaceViewReflector {
    @org.robolectric.util.reflector.Direct
    void onDetachedFromWindow();

    @org.robolectric.util.reflector.Direct
    void draw(Canvas canvas);
  }

  private final class Holder implements SurfaceHolder {
    final Set<Callback> callbacks = new CopyOnWriteArraySet<>();

    @Override public void addCallback(Callback callback) {
      if (!callbacks.add(callback)) return;
      // A callback added after the surface exists hears about it later, from the main loop, as from the system: a
      // player that adds its callback and then reads getSurface() (ExoPlayer) must not hear surfaceCreated inside
      // addCallback, or what it does after the read (no valid surface: back to its placeholder) undoes it.
      real.post(() -> {
        if (!callbacks.contains(callback)) return;
        if (surface != null) {
          callback.surfaceCreated(this);
          callback.surfaceChanged(this, PixelFormat.RGBX_8888, width, height);
        } else {
          update();
        }
      });
    }
    @Override public void removeCallback(Callback callback) { callbacks.remove(callback); }
    @Override public boolean isCreating() { return false; }
    @Override public void setType(int type) {}
    @Override public void setFixedSize(int w, int h) {}
    @Override public void setSizeFromLayout() {}
    @Override public void setFormat(int format) {}
    @Override public void setKeepScreenOn(boolean screenOn) {}
    @Override public Canvas lockCanvas() { return null; }
    @Override public Canvas lockCanvas(Rect dirty) { return null; }
    @Override public void unlockCanvasAndPost(Canvas canvas) {}
    @Override public Rect getSurfaceFrame() { return new Rect(0, 0, width, height); }
    @Override public Surface getSurface() { return surface; }
  }
}
