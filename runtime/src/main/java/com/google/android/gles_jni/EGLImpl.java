package com.google.android.gles_jni;

import android.opengl.EGL14;
import android.opengl.EGLObjectHandle;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * apksim's copy of the framework's EGLImpl (ahead of it on the class path), the legacy EGL10 that
 * `EGLContext.getEGL()` returns. The framework's is a set of natives, and its class initializer called one that
 * nothing here implements, so touching EGL10 at all ended the app (issue 574: an older camera preview's GL thread).
 * This one is EGL10 over android.opengl.EGL14, which {@code dev.apksim.gl.ShadowSimEGL14} runs on a real EGL where
 * there is one (test runs, `--gles on`) and Robolectric's stand-ins elsewhere, so the class always loads and calls
 * never crash. Handles are EGL14's native pointers, as on a device. Not done: pixmap surfaces, eglCopyBuffers, GL
 * through `EGLContext.getGL()` (the GL10/GL11 interface).
 */
public class EGLImpl implements EGL10 {
  private EGLContextImpl mContext = new EGLContextImpl(-1);
  private EGLDisplayImpl mDisplay = new EGLDisplayImpl(-1);
  private EGLSurfaceImpl mSurface = new EGLSurfaceImpl(-1);

  public EGLImpl() {}

  private static android.opengl.EGLDisplay d14(EGLDisplay d) {
    return handle(android.opengl.EGLDisplay.class, d == null ? 0 : ((EGLDisplayImpl) d).mEGLDisplay);
  }

  private static android.opengl.EGLContext c14(EGLContext c) {
    return handle(android.opengl.EGLContext.class, c == null ? 0 : ((EGLContextImpl) c).mEGLContext);
  }

  private static android.opengl.EGLSurface s14(EGLSurface s) {
    return handle(android.opengl.EGLSurface.class, s == null ? 0 : ((EGLSurfaceImpl) s).mEGLSurface);
  }

  private static android.opengl.EGLConfig cfg14(EGLConfig c) {
    return handle(android.opengl.EGLConfig.class, c == null ? 0 : ((EGLConfigImpl) c).get());
  }

  private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Constructor<?>> CTORS =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static <T extends EGLObjectHandle> T handle(Class<T> type, long h) {
    try {
      java.lang.reflect.Constructor<?> k = CTORS.get(type);
      if (k == null) {
        k = type.getDeclaredConstructor(long.class);
        k.setAccessible(true);
        CTORS.put(type, k);
      }
      return type.cast(k.newInstance(h));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long h(EGLObjectHandle o) {
    return o == null ? 0 : o.getNativeHandle();
  }

  @Override
  public boolean eglInitialize(EGLDisplay display, int[] majorMinor) {
    int[] v = new int[2];
    boolean r = EGL14.eglInitialize(d14(display), v, 0, v, 1);
    if (majorMinor != null && majorMinor.length >= 2) {
      majorMinor[0] = v[0];
      majorMinor[1] = v[1];
    }
    return r;
  }

  @Override
  public boolean eglQueryContext(EGLDisplay display, EGLContext context, int attribute, int[] value) {
    return EGL14.eglQueryContext(d14(display), c14(context), attribute, value, 0);
  }

  @Override
  public boolean eglQuerySurface(EGLDisplay display, EGLSurface surface, int attribute, int[] value) {
    return EGL14.eglQuerySurface(d14(display), s14(surface), attribute, value, 0);
  }

  @Override
  public boolean eglReleaseThread() {
    return EGL14.eglReleaseThread();
  }

  @Override
  public boolean eglChooseConfig(EGLDisplay display, int[] attribList, EGLConfig[] configs, int configSize, int[] numConfig) {
    android.opengl.EGLConfig[] out = configs == null ? null : new android.opengl.EGLConfig[configs.length];
    int[] n = new int[1];
    boolean r = EGL14.eglChooseConfig(d14(display), attribList, 0, out, 0, configSize, n, 0);
    if (numConfig != null && numConfig.length > 0) numConfig[0] = n[0];
    if (out != null) {
      for (int i = 0; i < Math.min(n[0], configs.length); i++) {
        if (out[i] != null) configs[i] = new EGLConfigImpl(out[i].getNativeHandle());
      }
    }
    return r;
  }

  @Override
  public boolean eglGetConfigAttrib(EGLDisplay display, EGLConfig config, int attribute, int[] value) {
    return EGL14.eglGetConfigAttrib(d14(display), cfg14(config), attribute, value, 0);
  }

  @Override
  public boolean eglGetConfigs(EGLDisplay display, EGLConfig[] configs, int configSize, int[] numConfig) {
    return eglChooseConfig(display, new int[] {EGL14.EGL_NONE}, configs, configSize, numConfig);
  }

  @Override
  public int eglGetError() {
    return EGL14.eglGetError();
  }

  @Override
  public boolean eglDestroyContext(EGLDisplay display, EGLContext context) {
    return EGL14.eglDestroyContext(d14(display), c14(context));
  }

  @Override
  public boolean eglDestroySurface(EGLDisplay display, EGLSurface surface) {
    return EGL14.eglDestroySurface(d14(display), s14(surface));
  }

  @Override
  public boolean eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context) {
    return EGL14.eglMakeCurrent(d14(display), s14(draw), s14(read), c14(context));
  }

  @Override
  public String eglQueryString(EGLDisplay display, int name) {
    return EGL14.eglQueryString(d14(display), name);
  }

  @Override
  public boolean eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
    return EGL14.eglSwapBuffers(d14(display), s14(surface));
  }

  @Override
  public boolean eglTerminate(EGLDisplay display) {
    return EGL14.eglTerminate(d14(display));
  }

  @Override
  public boolean eglCopyBuffers(EGLDisplay display, EGLSurface surface, Object nativePixmap) {
    return false;
  }

  @Override
  public boolean eglWaitGL() {
    return EGL14.eglWaitGL();
  }

  @Override
  public boolean eglWaitNative(int engine, Object bindTarget) {
    return true;
  }

  public static int getInitCount(EGLDisplay display) {
    return 1;
  }

  @Override
  public EGLContext eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext shareContext, int[] attribList) {
    long ctx = h(EGL14.eglCreateContext(d14(display), cfg14(config), c14(shareContext), attribList, 0));
    return ctx == 0 ? EGL10.EGL_NO_CONTEXT : new EGLContextImpl(ctx);
  }

  @Override
  public EGLSurface eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, int[] attribList) {
    long s = h(EGL14.eglCreatePbufferSurface(d14(display), cfg14(config), attribList, 0));
    return s == 0 ? EGL10.EGL_NO_SURFACE : new EGLSurfaceImpl(s);
  }

  @Override
  public EGLSurface eglCreatePixmapSurface(EGLDisplay display, EGLConfig config, Object nativePixmap, int[] attribList) {
    return EGL10.EGL_NO_SURFACE;
  }

  /** A SurfaceHolder, SurfaceView, Surface or SurfaceTexture as EGL14 takes a window: a pbuffer, 512 square unless a SurfaceView or SurfaceHolder gives its size (a SurfaceTexture's default buffer size is not read), unseen. */
  @Override
  public EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object nativeWindow, int[] attribList) {
    long s = h(EGL14.eglCreateWindowSurface(d14(display), cfg14(config), nativeWindow, attribList, 0));
    return s == 0 ? EGL10.EGL_NO_SURFACE : new EGLSurfaceImpl(s);
  }

  @Override
  public synchronized EGLDisplay eglGetDisplay(Object nativeDisplay) {
    long d = h(EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY));
    if (d == 0) return EGL10.EGL_NO_DISPLAY;
    if (mDisplay.mEGLDisplay != d) mDisplay = new EGLDisplayImpl(d);
    return mDisplay;
  }

  @Override
  public synchronized EGLContext eglGetCurrentContext() {
    long c = h(EGL14.eglGetCurrentContext());
    if (c == 0) return EGL10.EGL_NO_CONTEXT;
    if (mContext.mEGLContext != c) mContext = new EGLContextImpl(c);
    return mContext;
  }

  @Override
  public synchronized EGLDisplay eglGetCurrentDisplay() {
    long d = h(EGL14.eglGetCurrentDisplay());
    if (d == 0) return EGL10.EGL_NO_DISPLAY;
    if (mDisplay.mEGLDisplay != d) mDisplay = new EGLDisplayImpl(d);
    return mDisplay;
  }

  @Override
  public synchronized EGLSurface eglGetCurrentSurface(int readdraw) {
    long s = h(EGL14.eglGetCurrentSurface(readdraw));
    if (s == 0) return EGL10.EGL_NO_SURFACE;
    if (mSurface.mEGLSurface != s) mSurface = new EGLSurfaceImpl(s);
    return mSurface;
  }
}
