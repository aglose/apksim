package org.webrtc;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
public class EglRenderer implements VideoSink {
  public interface FrameListener { void onFrame(Bitmap frame); }
  public interface ErrorCallback { void onGlOutOfMemory(); }
  public EglRenderer(String name) {}
  public EglRenderer(String name, VideoFrameDrawer videoFrameDrawer) {}
  public void init(EglBase.Context sharedContext, int[] configAttributes, RendererCommon.GlDrawer drawer) {}
  public void init(EglBase.Context sharedContext, int[] configAttributes, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {}
  public void init(EglThread eglThread, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {}
  public void createEglSurface(Surface surface) {}
  public void createEglSurface(SurfaceTexture surfaceTexture) {}
  public void release() {}
  public void releaseEglSurface(Runnable completionCallback) {}
  public void printStackTrace() {}
  public void setMirror(boolean mirror) {}
  public void setMirrorVertically(boolean mirrorVertically) {}
  public void setLayoutAspectRatio(float layoutAspectRatio) {}
  public void setFpsReduction(float fps) {}
  public void disableFpsReduction() {}
  public void pauseVideo() {}
  public void addFrameListener(FrameListener listener, float scale) {}
  public void addFrameListener(FrameListener listener, float scale, RendererCommon.GlDrawer drawerParam) {}
  public void addFrameListener(FrameListener listener, float scale, RendererCommon.GlDrawer drawerParam, boolean applyFpsReduction) {}
  public void removeFrameListener(FrameListener listener) {}
  public void setErrorCallback(ErrorCallback errorCallback) {}
  public void onFrame(VideoFrame frame) {}
  public void clearImage() {}
  public void clearImage(float r, float g, float b, float a) {}
}
