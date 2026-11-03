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

  static native boolean render(long pipeline, ByteBuffer uniforms, int uniformBytes, float[] transform, int w, int h, ByteBuffer out);
}
