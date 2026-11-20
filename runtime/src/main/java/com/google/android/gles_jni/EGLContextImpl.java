package com.google.android.gles_jni;

import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL;

/**
 * apksim's copy of the framework's EGLContextImpl (ahead of it on the class path): the framework's constructor makes a
 * GLImpl, whose class initializer is a native nothing here implements (issue 574), and EGL10's own constants are
 * EGLContextImpls. {@link #getGL()} answers null: the GL10/GL11 interface is not done, apps render through GLES20.
 */
public class EGLContextImpl extends EGLContext {
  long mEGLContext;

  public EGLContextImpl(long ctx) {
    mEGLContext = ctx;
  }

  @Override
  public GL getGL() {
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    return mEGLContext == ((EGLContextImpl) o).mEGLContext;
  }

  @Override
  public int hashCode() {
    return (int) ((mEGLContext ^ (mEGLContext >>> 32)) & 0xffffffffL);
  }
}
