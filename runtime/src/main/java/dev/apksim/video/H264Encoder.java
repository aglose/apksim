package dev.apksim.video;

/**
 * H.264 encoding of the screen for `apksim serve`'s video stream ({@code FrameStream}): VideoToolbox on the Mac
 * (native/h264enc.m), OpenH264 on Linux (native/linux/h264enc_linux.c, which opens libopenh264 at run time). Each call
 * gives one access unit in Annex B, the parameter sets in front of every keyframe; no frame reordering, so a frame
 * comes out as it goes in. Null from {@link #open} when there is no encoder here: the stream stays JPEG.
 */
public final class H264Encoder implements AutoCloseable {
  private static final boolean AVAILABLE = load();
  private final long handle;
  public final int width;
  public final int height;

  private H264Encoder(long handle, int width, int height) {
    this.handle = handle;
    this.width = width;
    this.height = height;
  }

  private static boolean load() {
    try {
      System.load(dev.apksim.HostOs.runtimeLib("libh264enc").toString());
      return nativeAvailable();
    } catch (Throwable t) {
      System.err.println("[apksim] no H.264 encoder: " + t.getMessage());
      return false;
    }
  }

  public static boolean available() {
    return AVAILABLE;
  }

  private static volatile Boolean works;

  /**
   * Whether an encoder opens and gives a keyframe here (once per process: a small grey frame), so that a stream is
   * offered as H.264 only where it can be.
   */
  public static boolean works() {
    Boolean w = works;
    if (w != null) return w;
    synchronized (H264Encoder.class) {
      if (works != null) return works;
      boolean ok = false;
      try (H264Encoder e = open(64, 64, 500)) {
        if (e != null) {
          byte[] grey = new byte[64 * 64 * 4];
          java.util.Arrays.fill(grey, (byte) 0x80);
          // Straight to the native call: the pixel order (VideoToolbox.BITMAP_BGRA) makes a Bitmap, which this
          // control thread must not do while the app may still be starting; grey is the same either way.
          byte[] unit = nativeEncode(e.handle, grey, 64 * 4, true, 0, true);
          ok = unit != null && unit.length > 1 && unit[0] == 1;
        }
      } catch (Throwable t) {
        System.err.println("[apksim] no H.264 encoder: " + t);
      }
      if (AVAILABLE && !ok) System.err.println("[apksim] the H.264 encoder did not start: streams stay JPEG");
      works = ok;
      return ok;
    }
  }

  /**
   * An encoder for frames of at least this size (it encodes the even part: H.264's 4:2:0 needs even sizes), at about
   * kbps kilobits a second, or null.
   */
  public static H264Encoder open(int w, int h, int kbps) {
    if (!AVAILABLE) return null;
    int ew = w & ~1;
    int eh = h & ~1;
    if (ew < 16 || eh < 16) return null;
    long handle = nativeOpen(ew, eh, kbps);
    return handle == 0 ? null : new H264Encoder(handle, ew, eh);
  }

  /**
   * Encodes a frame (Bitmap memory order, rows of stride bytes) presented at micros. Returns a keyframe flag byte (1
   * for a keyframe) followed by the access unit, or null on failure.
   */
  public byte[] encode(byte[] pixels, int stride, long micros, boolean key) {
    return nativeEncode(handle, pixels, stride, VideoToolbox.BITMAP_BGRA, micros, key);
  }

  @Override
  public void close() {
    nativeClose(handle);
  }

  private static native boolean nativeAvailable();

  private static native long nativeOpen(int w, int h, int kbps);

  private static native byte[] nativeEncode(long handle, byte[] pixels, int stride, boolean bgra, long micros, boolean key);

  private static native void nativeClose(long handle);
}
