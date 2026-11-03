package dev.apksim.video;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H.264 decoding and picture conversion on VideoToolbox (native/videotoolbox.m). Kept free of any library an APK
 * brings, so that every app can load it: libwebrtc's adapter is {@link VtH264Decoder}, MediaCodec's is
 * {@link ShadowSimMediaCodec}.
 */
public final class VideoToolbox {
  private VideoToolbox() {}

  private static final boolean AVAILABLE = load();
  static final AtomicLong decoded = new AtomicLong();
  static final AtomicLong failed = new AtomicLong();

  static boolean available() {
    return AVAILABLE;
  }

  private static boolean load() {
    try {
      if (!dev.apksim.HostOs.MAC) return false;
      System.load(dev.apksim.HostOs.runtimeLib("libvtdecoder").toString());
      return true;
    } catch (Throwable t) {
      System.err.println("[apksim] no H.264 decoder: " + t);
      return false;
    }
  }

  /**
   * Whether an ARGB_8888 Bitmap's bytes run B, G, R, A here. On Android they run R, G, B, A, but Skia keeps its native
   * byte order, which on macOS is BGRA, so pixels copied in as RGBA came out with red and blue swapped.
   */
  static final boolean BITMAP_BGRA = bitmapIsBgra();

  private static boolean bitmapIsBgra() {
    Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    b.setPixel(0, 0, 0xffff0000);
    ByteBuffer px = ByteBuffer.allocate(4);
    b.copyPixelsToBuffer(px);
    return (px.get(0) & 0xff) == 0 && (px.get(2) & 0xff) == 0xff;
  }

  /** A decoder session, or 0 when VideoToolbox is unavailable. */
  static long create() {
    return AVAILABLE ? nativeCreate() : 0;
  }

  static void destroy(long handle) {
    if (handle != 0) nativeRelease(handle);
  }

  static native long nativeCreate();

  static native void nativeRelease(long handle);

  /** Decodes one access unit (Annex B) from a direct buffer. Returns 1 with [size] set when a picture came out, 0 when none, -1 on error. */
  static native int nativeDecode(long handle, ByteBuffer data, int offset, int length, int[] size);

  static native void nativeCopyI420(long handle, ByteBuffer y, int strideY, ByteBuffer u, int strideU, ByteBuffer v, int strideV);

  /** A reference to the latest decoded picture (0 if none), kept until {@link #nativeReleasePicture}. */
  static native long nativeTakePicture(long handle);

  static native void nativeReleasePicture(long picture);

  static native int nativePictureWidth(long picture);

  static native int nativePictureHeight(long picture);

  /** The picture scaled to [width]x[height] in the byte order a Bitmap holds ({@link #BITMAP_BGRA}). */
  static native boolean nativePictureToRgba(long picture, int width, int height, ByteBuffer rgba, boolean bgra);

  /** I420 to the bytes an ARGB_8888 Bitmap holds, at the same size. */
  static native void i420ToRgba(ByteBuffer y, int strideY, ByteBuffer u, int strideU, ByteBuffer v, int strideV,
      int width, int height, ByteBuffer rgba, boolean bgra);
}
