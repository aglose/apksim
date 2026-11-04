package dev.apksim.composeapp

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offscreen GLES 2, as a 3D viewer's device test renders: a pbuffer, a shader program, one triangle, read back with
 * glReadPixels. On apksim this reaches a real GLES (ANGLE on a Mac, Mesa on Linux), not Robolectric's stand-ins.
 */
@RunWith(AndroidJUnit4::class)
class GlesTest {
  @Test
  fun drawsATriangleIntoAPbuffer() {
    val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    assertNotEquals(EGL14.EGL_NO_DISPLAY, dpy)
    val version = IntArray(2)
    assertTrue("eglInitialize: ${EGL14.eglGetError()}", EGL14.eglInitialize(dpy, version, 0, version, 1))
    try {
      val configs = arrayOfNulls<EGLConfig>(1)
      val count = IntArray(1)
      val attribs = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
      assertTrue(EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, count, 0))
      assertEquals("configs", 1, count[0])
      val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
      assertNotEquals("eglCreateContext: ${EGL14.eglGetError()}", EGL14.EGL_NO_CONTEXT, ctx)
      val surface = EGL14.eglCreatePbufferSurface(dpy, configs[0],
        intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
      assertNotEquals("eglCreatePbufferSurface: ${EGL14.eglGetError()}", EGL14.EGL_NO_SURFACE, surface)
      assertTrue(EGL14.eglMakeCurrent(dpy, surface, surface, ctx))
      try {
        android.util.Log.i("GlesTest", "EGL ${version[0]}.${version[1]}, ${GLES20.glGetString(GLES20.GL_RENDERER)}")
        drawAndCheck()
      } finally {
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surface)
        EGL14.eglDestroyContext(dpy, ctx)
      }
    } finally {
      EGL14.eglTerminate(dpy)
    }
  }

  private fun drawAndCheck() {
    GLES20.glViewport(0, 0, SIZE, SIZE)
    GLES20.glClearColor(0f, 0f, 1f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
      "attribute vec2 pos; void main() { gl_Position = vec4(pos, 0.0, 1.0); }"))
    GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
      "precision mediump float; uniform vec4 color; void main() { gl_FragColor = color; }"))
    GLES20.glBindAttribLocation(program, 0, "pos")
    GLES20.glLinkProgram(program)
    val linked = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
    assertEquals(GLES20.glGetProgramInfoLog(program), GLES20.GL_TRUE, linked[0])
    GLES20.glUseProgram(program)
    GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "color"), 1f, 0f, 0f, 1f)
    // A triangle over the lower left half of the pbuffer (below the diagonal from top left to bottom right).
    val vertices = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    vertices.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f)).position(0)
    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertices)
    GLES20.glEnableVertexAttribArray(0)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    GLES20.glFinish()
    assertEquals("glGetError", GLES20.GL_NO_ERROR, GLES20.glGetError())

    val pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
    GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
    // glReadPixels rows start at the bottom: (8, 8) is inside the triangle, (SIZE - 8, SIZE - 8) outside it.
    assertEquals("inside the triangle", 0xffff0000.toInt(), rgba(pixels, 8, 8))
    assertEquals("outside the triangle", 0xff0000ff.toInt(), rgba(pixels, SIZE - 8, SIZE - 8))
    GLES20.glDeleteProgram(program)
  }

  private fun shader(type: Int, source: String): Int {
    val s = GLES20.glCreateShader(type)
    GLES20.glShaderSource(s, source)
    GLES20.glCompileShader(s)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
    assertEquals(GLES20.glGetShaderInfoLog(s), GLES20.GL_TRUE, ok[0])
    return s
  }

  /** The pixel as 0xAARRGGBB. */
  private fun rgba(pixels: ByteBuffer, x: Int, y: Int): Int {
    val i = (y * SIZE + x) * 4
    fun b(k: Int) = pixels.get(i + k).toInt() and 0xff
    return (b(3) shl 24) or (b(0) shl 16) or (b(1) shl 8) or b(2)
  }

  private companion object {
    const val SIZE = 64
  }
}
