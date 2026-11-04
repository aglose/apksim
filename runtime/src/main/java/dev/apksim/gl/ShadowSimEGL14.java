package dev.apksim.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLObjectHandle;
import android.opengl.EGLSurface;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEGL14;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * android.opengl.EGL14 on a real EGL (see {@link Gl}); Robolectric's stand-ins where there is none. Handles are the
 * native pointers, as on a device, and the EGL_NO_* objects have handle 0. A window surface (there is no GLES
 * window to draw into here) is a pbuffer of the same size, so code that renders into one runs, unseen.
 */
@Implements(EGL14.class)
public class ShadowSimEGL14 extends ShadowEGL14 {
  private static volatile boolean noObjects;

  private static boolean on() {
    if (!Gl.on()) return false;
    if (!noObjects) {
      synchronized (ShadowSimEGL14.class) {
        if (!noObjects) {
          // Android's static initializer (native there) makes these; Robolectric leaves them null.
          EGL14.EGL_NO_DISPLAY = handle(EGLDisplay.class, 0);
          EGL14.EGL_NO_CONTEXT = handle(EGLContext.class, 0);
          EGL14.EGL_NO_SURFACE = handle(EGLSurface.class, 0);
          noObjects = true;
        }
      }
    }
    return true;
  }

  private static <T extends EGLObjectHandle> T handle(Class<T> type, long h) {
    return ReflectionHelpers.callConstructor(type, ClassParameter.from(long.class, h));
  }

  private static MemorySegment ptr(EGLObjectHandle h) {
    return h == null ? MemorySegment.NULL : MemorySegment.ofAddress(h.getNativeHandle());
  }

  private static long addr(Object p) {
    return ((MemorySegment) p).address();
  }

  private static boolean ok(Object r) {
    return (int) r != 0;
  }

  @Implementation
  protected static int eglGetError() {
    if (!on()) return ShadowEGL14.eglGetError();
    return (int) Gl.call("eglGetError", "I");
  }

  @Implementation
  protected static EGLDisplay eglGetDisplay(int displayId) {
    if (!on()) return ShadowEGL14.eglGetDisplay(displayId);
    return handle(EGLDisplay.class, addr(Gl.call("eglGetDisplay", "PP", MemorySegment.ofAddress(displayId))));
  }

  @Implementation
  protected static boolean eglInitialize(EGLDisplay dpy, int[] major, int majorOffset, int[] minor, int minorOffset) {
    if (!on()) return ShadowEGL14.eglInitialize(dpy, major, majorOffset, minor, minorOffset);
    try (Arena a = Arena.ofConfined()) {
      MemorySegment ma = a.allocate(ValueLayout.JAVA_INT);
      MemorySegment mi = a.allocate(ValueLayout.JAVA_INT);
      boolean r = ok(Gl.call("eglInitialize", "IPPP", ptr(dpy), ma, mi));
      if (major != null) major[majorOffset] = ma.get(ValueLayout.JAVA_INT, 0);
      if (minor != null) minor[minorOffset] = mi.get(ValueLayout.JAVA_INT, 0);
      return r;
    }
  }

  @Implementation
  protected static boolean eglTerminate(EGLDisplay dpy) {
    if (!on()) return true;
    return ok(Gl.call("eglTerminate", "IP", ptr(dpy)));
  }

  @Implementation
  protected static String eglQueryString(EGLDisplay dpy, int name) {
    if (!on()) return "";
    return Gl.string(Gl.call("eglQueryString", "PPI", ptr(dpy), name));
  }

  @Implementation
  protected static boolean eglChooseConfig(EGLDisplay dpy, int[] attribList, int attribOffset, EGLConfig[] configs,
      int configsOffset, int configSize, int[] numConfig, int numConfigOffset) {
    if (!on()) {
      return ShadowEGL14.eglChooseConfig(dpy, attribList, attribOffset, configs, configsOffset, configSize, numConfig, numConfigOffset);
    }
    try (Arena a = Arena.ofConfined()) {
      Gl.Copy attribs = Gl.array(a, pbufferConfig(attribList, attribOffset), 0);
      MemorySegment out = a.allocate(ValueLayout.ADDRESS, Math.max(1, configSize));
      MemorySegment n = a.allocate(ValueLayout.JAVA_INT);
      boolean r = ok(Gl.call("eglChooseConfig", "IPPPIP", ptr(dpy), attribs.seg(),
          configs == null ? MemorySegment.NULL : out, configSize, n));
      int count = n.get(ValueLayout.JAVA_INT, 0);
      if (numConfig != null) numConfig[numConfigOffset] = count;
      if (configs != null) {
        for (int i = 0; i < Math.min(count, configSize); i++) {
          configs[configsOffset + i] = handle(EGLConfig.class, out.getAtIndex(ValueLayout.ADDRESS, i).address());
        }
      }
      return r;
    }
  }

  private static final int EGL_SURFACE_TYPE = 0x3033;
  private static final int EGL_WINDOW_BIT = 0x0004;
  private static final int EGL_PBUFFER_BIT = 0x0001;
  private static final int EGL_RECORDABLE_ANDROID = 0x3142;

  /**
   * An eglChooseConfig attribute list as this EGL can answer it. A window surface here is a pbuffer
   * (eglCreateWindowSurface), so a config asked for windows, or for no surface type at all (EGL then means windows: WebRTC's
   * EglBase does this), is asked for pbuffers instead; and EGL_RECORDABLE_ANDROID, which only Android's EGL knows, is left
   * out. Mesa's surfaceless platform has no window configs, so these found none and WebRTC threw.
   */
  static int[] pbufferConfig(int[] attribList, int offset) {
    if (attribList == null) return new int[] {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL14.EGL_NONE};
    java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
    boolean surfaceType = false;
    for (int i = offset; i + 1 < attribList.length && attribList[i] != EGL14.EGL_NONE; i += 2) {
      int key = attribList[i];
      int value = attribList[i + 1];
      if (key == EGL_RECORDABLE_ANDROID) continue;
      if (key == EGL_SURFACE_TYPE) {
        surfaceType = true;
        if (value != -1 /* EGL_DONT_CARE */ && (value & EGL_WINDOW_BIT) != 0) value = (value & ~EGL_WINDOW_BIT) | EGL_PBUFFER_BIT;
      }
      out.add(key);
      out.add(value);
    }
    if (!surfaceType) {
      out.add(EGL_SURFACE_TYPE);
      out.add(EGL_PBUFFER_BIT);
    }
    out.add(EGL14.EGL_NONE);
    return out.stream().mapToInt(Integer::intValue).toArray();
  }

  @Implementation
  protected static boolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config, int attribute, int[] value, int offset) {
    if (!on()) return false;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment v = a.allocate(ValueLayout.JAVA_INT);
      boolean r = ok(Gl.call("eglGetConfigAttrib", "IPPIP", ptr(dpy), ptr(config), attribute, v));
      value[offset] = v.get(ValueLayout.JAVA_INT, 0);
      return r;
    }
  }

  @Implementation
  protected static EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config, EGLContext share, int[] attribList, int offset) {
    if (!on()) return ShadowEGL14.eglCreateContext(dpy, config, share, attribList, offset);
    try (Arena a = Arena.ofConfined()) {
      Gl.Copy attribs = Gl.array(a, attribList, offset);
      return handle(EGLContext.class, addr(Gl.call("eglCreateContext", "PPPPP", ptr(dpy), ptr(config), ptr(share), attribs.seg())));
    }
  }

  @Implementation
  protected static boolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) {
    if (!on()) return true;
    return ok(Gl.call("eglDestroyContext", "IPP", ptr(dpy), ptr(ctx)));
  }

  @Implementation
  protected static boolean eglQueryContext(EGLDisplay dpy, EGLContext ctx, int attribute, int[] value, int offset) {
    if (!on()) return ShadowEGL14.eglQueryContext(dpy, ctx, attribute, value, offset);
    try (Arena a = Arena.ofConfined()) {
      MemorySegment v = a.allocate(ValueLayout.JAVA_INT);
      boolean r = ok(Gl.call("eglQueryContext", "IPPIP", ptr(dpy), ptr(ctx), attribute, v));
      value[offset] = v.get(ValueLayout.JAVA_INT, 0);
      return r;
    }
  }

  @Implementation
  protected static EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config, int[] attribList, int offset) {
    if (!on()) return ShadowEGL14.eglCreatePbufferSurface(dpy, config, attribList, offset);
    try (Arena a = Arena.ofConfined()) {
      Gl.Copy attribs = Gl.array(a, attribList, offset);
      return handle(EGLSurface.class, addr(Gl.call("eglCreatePbufferSurface", "PPPP", ptr(dpy), ptr(config), attribs.seg())));
    }
  }

  /** No window to present to: a pbuffer of the window's size (512 square when that is unknown). */
  @Implementation
  protected static EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config, Object win, int[] attribList, int offset) {
    if (!on()) return ShadowEGL14.eglCreateWindowSurface(dpy, config, win, attribList, offset);
    int w = 512;
    int h = 512;
    if (win instanceof android.view.SurfaceView sv && sv.getWidth() > 0) {
      w = sv.getWidth();
      h = sv.getHeight();
    } else if (win instanceof android.view.SurfaceHolder holder && holder.getSurfaceFrame().width() > 0) {
      w = holder.getSurfaceFrame().width();
      h = holder.getSurfaceFrame().height();
    }
    return eglCreatePbufferSurface(dpy, config, new int[] {0x3057 /* EGL_WIDTH */, w, 0x3056 /* EGL_HEIGHT */, h, EGL14.EGL_NONE}, 0);
  }

  @Implementation
  protected static boolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) {
    if (!on()) return true;
    return ok(Gl.call("eglDestroySurface", "IPP", ptr(dpy), ptr(surface)));
  }

  @Implementation
  protected static boolean eglQuerySurface(EGLDisplay dpy, EGLSurface surface, int attribute, int[] value, int offset) {
    if (!on()) return false;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment v = a.allocate(ValueLayout.JAVA_INT);
      boolean r = ok(Gl.call("eglQuerySurface", "IPPIP", ptr(dpy), ptr(surface), attribute, v));
      value[offset] = v.get(ValueLayout.JAVA_INT, 0);
      return r;
    }
  }

  @Implementation
  protected static boolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    if (!on()) return ShadowEGL14.eglMakeCurrent(dpy, draw, read, ctx);
    return ok(Gl.call("eglMakeCurrent", "IPPPP", ptr(dpy), ptr(draw), ptr(read), ptr(ctx)));
  }

  @Implementation
  protected static EGLContext eglGetCurrentContext() {
    if (!on()) return ShadowEGL14.eglGetCurrentContext();
    return handle(EGLContext.class, addr(Gl.call("eglGetCurrentContext", "P")));
  }

  @Implementation
  protected static EGLSurface eglGetCurrentSurface(int readdraw) {
    if (!on()) return null;
    return handle(EGLSurface.class, addr(Gl.call("eglGetCurrentSurface", "PI", readdraw)));
  }

  @Implementation
  protected static EGLDisplay eglGetCurrentDisplay() {
    if (!on()) return null;
    return handle(EGLDisplay.class, addr(Gl.call("eglGetCurrentDisplay", "P")));
  }

  @Implementation
  protected static boolean eglSwapBuffers(EGLDisplay dpy, EGLSurface surface) {
    if (!on()) return ShadowEGL14.eglSwapBuffers(dpy, surface);
    return ok(Gl.call("eglSwapBuffers", "IPP", ptr(dpy), ptr(surface)));
  }

  @Implementation
  protected static boolean eglSwapInterval(EGLDisplay dpy, int interval) {
    if (!on()) return true;
    return ok(Gl.call("eglSwapInterval", "IPI", ptr(dpy), interval));
  }

  @Implementation
  protected static boolean eglBindAPI(int api) {
    if (!on()) return true;
    return ok(Gl.call("eglBindAPI", "II", api));
  }

  @Implementation
  protected static int eglQueryAPI() {
    if (!on()) return 0x30A0; // EGL_OPENGL_ES_API
    return (int) Gl.call("eglQueryAPI", "I");
  }

  @Implementation
  protected static boolean eglReleaseThread() {
    if (!on()) return true;
    return ok(Gl.call("eglReleaseThread", "I"));
  }

  @Implementation
  protected static boolean eglWaitGL() {
    if (!on()) return true;
    return ok(Gl.call("eglWaitGL", "I"));
  }

  @Implementation
  protected static boolean eglWaitClient() {
    if (!on()) return true;
    return ok(Gl.call("eglWaitClient", "I"));
  }

  @Implementation
  protected static boolean eglSurfaceAttrib(EGLDisplay dpy, EGLSurface surface, int attribute, int value) {
    if (!on()) return true;
    return ok(Gl.call("eglSurfaceAttrib", "IPPII", ptr(dpy), ptr(surface), attribute, value));
  }
}
