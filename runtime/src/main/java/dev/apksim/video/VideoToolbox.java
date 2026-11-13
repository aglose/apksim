package dev.apksim.video;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H.264, HEVC and VP9 decoding and picture conversion on VideoToolbox (native/videotoolbox.m), and on Linux on libavcodec
 * (native/linux/avdecoder_linux.c, the same library name) for MediaCodec only. Kept free of any library an APK brings,
 * so that every app can load it: libwebrtc's adapter is {@link VtH264Decoder}, MediaCodec's is
 * {@link ShadowSimMediaCodec}.
 */
public final class VideoToolbox {
  private VideoToolbox() {}

  private static final boolean AVAILABLE = load();
  static final AtomicLong decoded = new AtomicLong();
  static final AtomicLong failed = new AtomicLong();

  /**
   * Whether the native decoder reorders: libavcodec hands pictures back in presentation order, later than the input
   * that carried them ({@link #nativeSend}, {@link #nativeReceive}), where VideoToolbox gives one per input in decode
   * order and {@link ShadowSimMediaCodec} reorders them.
   */
  static final boolean REORDERS = !dev.apksim.HostOs.MAC;

  /** Whether MediaCodec has H.264 and HEVC decoders here. */
  static boolean available() {
    return AVAILABLE;
  }

  /**
   * Whether there is an HEVC decoder too: always with VideoToolbox; on Linux only in an image built with it
   * (docker/Dockerfile's VIDEO_DECODERS: the published image has H.264 alone), found by opening one.
   */
  static boolean hevcAvailable() {
    return AVAILABLE && (dev.apksim.HostOs.MAC || Hevc.PRESENT);
  }

  private static final class Hevc {
    static final boolean PRESENT = probe(CODEC_HEVC);
  }

  private static final class Vp9 {
    static final boolean PRESENT = probe(CODEC_VP9);
  }

  private static boolean probe(int codec) {
    long h = nativeCreate(codec);
    if (h != 0) nativeRelease(h);
    return h != 0;
  }

  static final int CODEC_AVC = 0, CODEC_HEVC = 1, CODEC_VP9 = 2;

  /**
   * Whether there is a VP9 decoder: on the Mac VideoToolbox's supplemental one, taken as present as HEVC is (every
   * Apple Silicon macOS has it; a trial session costs ~85 ms, too much for every launch, so {@link #create} makes it
   * when a codec is first configured and answers 0 if the system has none); on Linux libavcodec's in an image built
   * with it (VIDEO_DECODERS=h264,vp9), found by opening one.
   */
  static boolean vp9Available() {
    return AVAILABLE && (dev.apksim.HostOs.MAC || Vp9.PRESENT);
  }

  /** Whether libwebrtc's H.264 decoder ({@link VtH264Decoder}) can run here: VideoToolbox only. */
  static boolean webrtcAvailable() {
    return dev.apksim.HostOs.MAC && AVAILABLE;
  }

  private static boolean load() {
    try {
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

  /** An H.264 decoder session, or 0 when VideoToolbox is unavailable. */
  static long create() {
    return create(false);
  }

  /** An H.264 or HEVC decoder session, or 0 when VideoToolbox is unavailable. */
  static long create(boolean hevc) {
    return create(hevc ? CODEC_HEVC : CODEC_AVC);
  }

  /** A decoder session for CODEC_AVC, CODEC_HEVC or CODEC_VP9, or 0. */
  static long create(int codec) {
    return AVAILABLE ? nativeCreate(codec) : 0;
  }

  static void destroy(long handle) {
    if (handle != 0) nativeRelease(handle);
  }

  static native long nativeCreate(int codec);

  static native void nativeRelease(long handle);

  /** Decodes one access unit (Annex B; parameter sets update the session) from a direct buffer. Returns 1 with [size] set when a picture came out, 0 when none, -1 on error. */
  static native int nativeDecode(long handle, ByteBuffer data, int offset, int length, int[] size);

  /**
   * Linux only. Sends one access unit (Annex B) or parameter sets from a direct buffer; [length] 0 (the buffer may be
   * null) starts draining at end of stream. A pts of Long.MIN_VALUE means none (parameter sets). Negative: error.
   */
  static native int nativeSend(long handle, ByteBuffer data, int offset, int length, long ptsUs);

  /** Linux only. The next finished picture in presentation order (release it with {@link #nativeReleasePicture}), its pts in pts[0]; 0 when none. */
  static native long nativeReceive(long handle, long[] pts);

  /** Linux only. A seek: drops everything inside the decoder. */
  static native void nativeReset(long handle);

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
