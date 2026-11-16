package dev.apksim.video;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.SurfaceTexture;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowSurfaceTexture;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * Keeps the buffer size a SurfaceTexture was given (setDefaultBufferSize) for the views {@link VideoSurfaces} knows:
 * it is the size of the camera stream an app opened into it (CameraX sets it to the resolution it chose before handing
 * the Surface to the camera), which the fake camera draws its preview at (issue 414). The call itself goes on as before.
 */
@Implements(value = SurfaceTexture.class, isInAndroidSdk = false)
public class ShadowSimSurfaceTexture extends ShadowSurfaceTexture {
  @RealObject private SurfaceTexture real;

  @Implementation
  protected void setDefaultBufferSize(int width, int height) {
    VideoSurfaces.bufferSize(real, width, height);
    try {
      reflector(Direct_.class, real).setDefaultBufferSize(width, height);
    } catch (UnsatisfiedLinkError e) {
      // nothing native to tell
    }
  }

  @ForType(SurfaceTexture.class)
  interface Direct_ {
    @Direct
    void setDefaultBufferSize(int width, int height);
  }
}
