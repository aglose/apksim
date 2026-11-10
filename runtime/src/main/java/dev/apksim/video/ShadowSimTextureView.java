package dev.apksim.video;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowTextureView;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * A TextureView that works without GL texture layers. On a device the view gets its SurfaceTexture when its
 * layer is first made, during draw; here the same happens, but the SurfaceTexture is only a name for
 * {@link VideoSurfaces}, and the view draws the latest picture published under it, stretched to its bounds.
 */
@Implements(TextureView.class)
public class ShadowSimTextureView extends ShadowTextureView {
  private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);

  @RealObject private TextureView real;

  private SurfaceTexture surface() {
    return ReflectionHelpers.getField(real, "mSurface");
  }

  private SurfaceTexture ensureSurface() {
    SurfaceTexture s = surface();
    if (s != null || real.getWidth() <= 0 || real.getHeight() <= 0) return s;
    s = new SurfaceTexture(false);
    ReflectionHelpers.setField(real, "mSurface", s);
    VideoSurfaces.attach(s, real);
    TextureView.SurfaceTextureListener l = ReflectionHelpers.getField(real, "mListener");
    if (l != null) l.onSurfaceTextureAvailable(s, real.getWidth(), real.getHeight());
    return s;
  }

  @Implementation
  @Override
  protected void draw(Canvas canvas) {
    Bitmap picture = VideoSurfaces.picture(ensureSurface());
    if (picture == null) return;
    // The view's transform (setTransform) maps the content in its bounds, as HWUI applies it to the texture layer: a
    // camera preview (CameraX's PreviewView) turns and scales its picture with it.
    android.graphics.Matrix m = real.getTransform(null);
    int save = canvas.save();
    if (!m.isIdentity()) canvas.concat(m);
    canvas.drawBitmap(picture, null, new Rect(0, 0, real.getWidth(), real.getHeight()), FILTER);
    canvas.restoreToCount(save);
  }

  @Implementation
  protected Bitmap getBitmap(Bitmap bitmap) {
    Bitmap picture = VideoSurfaces.picture(surface());
    if (bitmap != null && picture != null) {
      bitmap.eraseColor(0);
      new Canvas(bitmap).drawBitmap(picture, null, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), FILTER);
    }
    return bitmap;
  }

  /** Runs as the view leaves its window: the listener hears the surface is gone, and so does the registry. */
  @Implementation
  protected void releaseSurfaceTexture() {
    SurfaceTexture s = surface();
    reflector(TextureViewReflector.class, real).releaseSurfaceTexture();
    if (s != null) VideoSurfaces.detach(s);
  }

  @ForType(TextureView.class)
  interface TextureViewReflector {
    @Direct
    void releaseSurfaceTexture();
  }
}
