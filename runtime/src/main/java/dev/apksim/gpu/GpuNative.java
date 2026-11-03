package dev.apksim.gpu;

import java.nio.ByteBuffer;

/** native/gpushader.m. */
final class GpuNative {
  private static boolean loaded;

  private GpuNative() {}

  static synchronized void ensureLoaded() {
    if (loaded) return;
    System.load(dev.apksim.HostOs.runtimeLib("libgpushader").toString());
    loaded = true;
  }

  static native long compile(String source, String[] errorOut);

  static native long submit(long pipeline, ByteBuffer uniforms, int uniformBytes, float[] transform, int w, int h);

  static native boolean collect(long handle, ByteBuffer out);

  /** A w x h BGRA texture for an effect's input image, or 0. */
  static native long newInput(int w, int h);

  /** Copies pixels (rows of [stride] bytes) into an input texture. */
  static native void upload(long input, ByteBuffer pixels, int stride);

  static native void releaseInput(long input);

  /** As submit, with [inputs] bound to the shader's `uniform shader` inputs in order; bit i of clampMask: input i clamps. */
  static native long submitInputs(long pipeline, ByteBuffer uniforms, int uniformBytes, float[] transform, int w, int h,
      long[] inputs, int clampMask);

  /** As submit, with [input] bound to the shader's first `uniform shader`. */
  static native long submitEffect(long pipeline, ByteBuffer uniforms, int uniformBytes, float[] transform, int w, int h, long input);

  /**
   * Resamples [input] bilinearly into w x h packed pixels in [out]: target pixel (x, y) reads texel coordinate
   * (u0, v0) + (x + 0.5, y + 0.5) * (du, dv).
   */
  static native boolean resample(long input, float u0, float v0, float du, float dv, int w, int h, ByteBuffer out);

  static native boolean render(long pipeline, ByteBuffer uniforms, int uniformBytes, float[] transform, int w, int h, ByteBuffer out);
}
