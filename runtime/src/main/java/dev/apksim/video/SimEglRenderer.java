package dev.apksim.video;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.EglThread;
import org.webrtc.RendererCommon;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;

/**
 * libwebrtc's renderer without GLES: each frame becomes a Bitmap that the target TextureView draws (see
 * {@link VideoSurfaces}). Frames are stretched to the view, which is what the GL renderer does unless told an
 * aspect ratio. Apps get this class wherever they create or extend an EglRenderer (see MethodTranslator).
 */
public class SimEglRenderer extends EglRenderer {
  /** Pictures in rotation: one being filled, one published, and one the render thread may still be reading. */
  private static final int POOL = 3;

  private final Object lock = new Object();
  private final Bitmap[] pool = new Bitmap[POOL];
  private int next;
  private ByteBuffer rgba;
  /** A frame of ours is on the view (decode thread only): `video blank` takes it off. */
  private boolean shown;
  private SurfaceTexture surface;
  private final List<Object[]> listeners = new ArrayList<>();

  public SimEglRenderer(String name) {
    super(name);
  }

  public SimEglRenderer(String name, VideoFrameDrawer videoFrameDrawer) {
    super(name, videoFrameDrawer);
  }

  @Override
  public void init(EglBase.Context sharedContext, int[] configAttributes, RendererCommon.GlDrawer drawer) {}

  @Override
  public void init(EglBase.Context sharedContext, int[] configAttributes, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {}

  @Override
  public void init(EglThread eglThread, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {}

  @Override
  public void createEglSurface(SurfaceTexture surfaceTexture) {
    synchronized (lock) {
      surface = surfaceTexture;
    }
  }

  @Override
  public void createEglSurface(Surface s) {}

  @Override
  public void releaseEglSurface(Runnable completionCallback) {
    synchronized (lock) {
      surface = null;
    }
    if (completionCallback != null) completionCallback.run();
  }

  @Override
  public void release() {
    synchronized (lock) {
      surface = null;
      listeners.clear();
    }
  }

  @Override
  public void printStackTrace() {}

  @Override
  public void setMirror(boolean mirror) {}

  @Override
  public void setMirrorVertically(boolean mirrorVertically) {}

  @Override
  public void setLayoutAspectRatio(float layoutAspectRatio) {}

  @Override
  public void setFpsReduction(float fps) {}

  @Override
  public void disableFpsReduction() {}

  @Override
  public void pauseVideo() {}

  @Override
  public void addFrameListener(FrameListener listener, float scale) {
    addFrameListener(listener, scale, null, false);
  }

  @Override
  public void addFrameListener(FrameListener listener, float scale, RendererCommon.GlDrawer drawerParam) {
    addFrameListener(listener, scale, drawerParam, false);
  }

  @Override
  public void addFrameListener(FrameListener listener, float scale, RendererCommon.GlDrawer drawerParam, boolean applyFpsReduction) {
    synchronized (lock) {
      listeners.add(new Object[] {listener, scale});
    }
  }

  @Override
  public void removeFrameListener(FrameListener listener) {
    synchronized (lock) {
      listeners.removeIf(l -> l[0] == listener);
    }
  }

  @Override
  public void onFrame(VideoFrame frame) {
    SurfaceTexture target;
    List<Object[]> waiting;
    synchronized (lock) {
      target = surface;
      waiting = listeners.isEmpty() ? List.of() : new ArrayList<>(listeners);
      listeners.clear();
    }
    if (target == null && waiting.isEmpty()) return;  // No surface: dropped, as the GL renderer does.
    // --video blank (Features): nothing drawn, so no conversion either. A frame the app asked for (a listener) is
    // still converted for it, but not drawn: the view stays empty.
    if (dev.apksim.Features.video() == dev.apksim.Features.Video.BLANK) {
      VideoSurfaces.webrtcBlank.incrementAndGet();
      // Switched to blank while frames were drawn (`video blank`): the last one comes off the view, once.
      if (target != null && shown) {
        shown = false;
        VideoSurfaces.publish(target, null);
      }
      if (waiting.isEmpty()) return;
      target = null;
    }
    // Scaled down to the view before converting, with libyuv on this (the decode) thread: drawing a 4K picture
    // into a phone-sized view cost the raster thread ~50 ms a frame.
    VideoFrame.Buffer source = frame.getBuffer();
    int[] view = VideoSurfaces.viewSize(target);
    VideoFrame.Buffer scaled = null;
    if (view != null && waiting.isEmpty() && (source.getWidth() > view[0] || source.getHeight() > view[1])) {
      int sw = Math.min(source.getWidth(), view[0]) & ~1;
      int sh = Math.min(source.getHeight(), view[1]) & ~1;
      scaled = source.cropAndScale(0, 0, source.getWidth(), source.getHeight(), Math.max(2, sw), Math.max(2, sh));
    }
    VideoFrame.I420Buffer i420 = (scaled != null ? scaled : source).toI420();
    if (scaled != null) scaled.release();
    try {
      int w = i420.getWidth();
      int h = i420.getHeight();
      Bitmap picture = nextPicture(w, h);
      if (rgba == null || rgba.capacity() < w * h * 4) rgba = ByteBuffer.allocateDirect(w * h * 4);
      rgba.clear();
      VideoToolbox.i420ToRgba(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(),
          i420.getDataV(), i420.getStrideV(), w, h, rgba, VideoToolbox.BITMAP_BGRA);
      rgba.limit(w * h * 4);
      picture.copyPixelsFromBuffer(rgba);
      for (Object[] l : waiting) {
        float scale = (Float) l[1];
        int sw = Math.max(1, Math.round(w * scale));
        int sh = Math.max(1, Math.round(h * scale));
        ((FrameListener) l[0]).onFrame(Bitmap.createScaledBitmap(picture, sw, sh, true));
      }
      if (target != null) {
        VideoSurfaces.publish(target, picture);
        shown = true;
      }
    } finally {
      i420.release();
    }
  }

  private Bitmap nextPicture(int w, int h) {
    Bitmap b = pool[next];
    if (b == null || b.getWidth() != w || b.getHeight() != h) {
      b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      pool[next] = b;
    }
    next = (next + 1) % POOL;
    return b;
  }

  @Override
  public void clearImage() {
    SurfaceTexture target;
    synchronized (lock) {
      target = surface;
    }
    if (target != null) VideoSurfaces.publish(target, null);
  }

  @Override
  public void clearImage(float r, float g, float b, float a) {
    clearImage();
  }
}
