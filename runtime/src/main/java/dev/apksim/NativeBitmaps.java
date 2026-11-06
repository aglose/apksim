package dev.apksim;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * The Java side of libjnigraphics for loaded ELF code (native/jnigraphics.c: AndroidBitmap_getInfo, lockPixels,
 * unlockPixels, getDataSpace, compress). Robolectric's Bitmap gives no address for its pixels, so a lock copies them
 * out ({@link #copyOut}) and the last unlock copies them back ({@link #copyIn}), which bumps Skia's generation ID as a
 * device's unlock does. Only called from native code; error results are the NDK's ANDROID_BITMAP_RESULT_* codes.
 */
final class NativeBitmaps {
  static final int BAD_PARAMETER = -1, JNI_EXCEPTION = -2;
  // AndroidBitmapFormat.
  static final int NONE = 0, RGBA_8888 = 1, RGB_565 = 4, RGBA_4444 = 7, A_8 = 8, RGBA_F16 = 9, RGBA_1010102 = 10;
  // AndroidBitmapInfo.flags.
  static final int PREMUL = 0, OPAQUE = 1, UNPREMUL = 2, IS_HARDWARE = 1 << 31;

  private static volatile int bgra = -1;

  private NativeBitmaps() {}

  /**
   * Whether ARGB_8888 pixels run B, G, R, A in memory here: Skia keeps its platform's order (BGRA on macOS), where
   * Android's is R, G, B, A, which is what native code reads and writes.
   */
  static boolean bgra() {
    if (bgra < 0) {
      Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
      b.setPixel(0, 0, 0xffff0000);
      ByteBuffer px = ByteBuffer.allocate(4);
      b.copyPixelsToBuffer(px);
      b.recycle();
      bgra = (px.get(0) & 0xff) == 0 && (px.get(2) & 0xff) == 0xff ? 1 : 0;
    }
    return bgra == 1;
  }

  static int format(Bitmap.Config c) {
    if (c == null) return NONE;
    return switch (c) {
      case ARGB_8888, HARDWARE -> RGBA_8888;
      case RGB_565 -> RGB_565;
      case ARGB_4444 -> RGBA_4444;
      case ALPHA_8 -> A_8;
      case RGBA_F16 -> RGBA_F16;
      case RGBA_1010102 -> RGBA_1010102;
      default -> NONE;
    };
  }

  /** AndroidBitmapInfo into out: width, height, stride, format, flags. */
  static int info(Bitmap b, int[] out) {
    if (b == null || out == null || out.length < 5) return BAD_PARAMETER;
    Bitmap.Config c = b.getConfig();
    out[0] = b.getWidth();
    out[1] = b.getHeight();
    out[2] = b.getRowBytes();
    out[3] = format(c);
    int flags = !b.hasAlpha() ? OPAQUE : b.isPremultiplied() ? PREMUL : UNPREMUL;
    if (c == Bitmap.Config.HARDWARE) flags |= IS_HARDWARE;
    out[4] = flags;
    return 0;
  }

  /** Bytes a lock copies (stride × height), or an error: a recycled or hardware bitmap has no pixels to lock. */
  static int size(Bitmap b) {
    if (b == null) return BAD_PARAMETER;
    if (b.isRecycled() || b.getConfig() == Bitmap.Config.HARDWARE) return JNI_EXCEPTION;
    return b.getRowBytes() * b.getHeight();
  }

  /** The pixels into dst as the Bitmap holds them; 1 when red and blue must be swapped for native code, else 0. */
  static int copyOut(Bitmap b, ByteBuffer dst) {
    if (b.isRecycled()) return JNI_EXCEPTION;
    b.copyPixelsToBuffer(dst);
    return b.getConfig() == Bitmap.Config.ARGB_8888 && bgra() ? 1 : 0;
  }

  /** Pixels back from src (already in the Bitmap's own order). */
  static int copyIn(Bitmap b, ByteBuffer src) {
    if (b.isRecycled()) return JNI_EXCEPTION;
    b.copyPixelsFromBuffer(src);
    return 0;
  }

  // ADataSpace: standard << 16 | transfer << 22 | range << 27.
  private static int ds(int standard, int transfer, int range) {
    return standard << 16 | transfer << 22 | range << 27;
  }

  private static final int BT709 = 1, BT2020 = 6, DCI_P3 = 10, ADOBE = 11;
  private static final int LINEAR = 1, SRGB = 2, SMPTE_170M = 3, GAMMA2_2 = 4, GAMMA2_6 = 5, ST2084 = 7, HLG = 8;
  private static final int FULL = 1, LIMITED = 2, EXTENDED = 3;
  private static final Object[][] SPACES = {
    {ColorSpace.Named.SRGB, ds(BT709, SRGB, FULL)},
    {ColorSpace.Named.LINEAR_SRGB, ds(BT709, LINEAR, FULL)},
    {ColorSpace.Named.EXTENDED_SRGB, ds(BT709, SRGB, EXTENDED)},
    {ColorSpace.Named.LINEAR_EXTENDED_SRGB, ds(BT709, LINEAR, EXTENDED)},
    {ColorSpace.Named.DISPLAY_P3, ds(DCI_P3, SRGB, FULL)},
    {ColorSpace.Named.DCI_P3, ds(DCI_P3, GAMMA2_6, FULL)},
    {ColorSpace.Named.BT709, ds(BT709, SMPTE_170M, LIMITED)},
    {ColorSpace.Named.BT2020, ds(BT2020, SMPTE_170M, FULL)},
    {ColorSpace.Named.BT2020_PQ, ds(BT2020, ST2084, FULL)},
    {ColorSpace.Named.BT2020_HLG, ds(BT2020, HLG, FULL)},
    {ColorSpace.Named.ADOBE_RGB, ds(ADOBE, GAMMA2_2, FULL)},
  };

  /** The bitmap's colour space as an ADataSpace; ADATASPACE_UNKNOWN (0) for none or one without a data space. */
  static int dataSpace(Bitmap b) {
    if (b == null) return 0;
    ColorSpace cs = b.getColorSpace();
    if (cs == null) return 0;
    for (Object[] s : SPACES) {
      if (ColorSpace.get((ColorSpace.Named) s[0]) == cs) return (Integer) s[1];
    }
    return 0;
  }

  private static ColorSpace colorSpace(int dataSpace) {
    if (dataSpace == 0) return ColorSpace.get(ColorSpace.Named.SRGB);
    for (Object[] s : SPACES) {
      if ((Integer) s[1] == dataSpace) return ColorSpace.get((ColorSpace.Named) s[0]);
    }
    return null;
  }

  /**
   * AndroidBitmap_compress: pixels (stride × height bytes, in Android's byte order) encoded as JPEG (0), PNG (1),
   * WEBP_LOSSY (3) or WEBP_LOSSLESS (4). Null for a parameter the NDK refuses; empty when the encoder failed.
   */
  static byte[] compress(ByteBuffer pixels, int width, int height, int stride, int format, int flags, int dataSpace,
      int compressFormat, int quality) {
    Bitmap.Config config = switch (format) {
      case RGBA_8888 -> Bitmap.Config.ARGB_8888;
      case RGB_565 -> Bitmap.Config.RGB_565;
      case A_8 -> Bitmap.Config.ALPHA_8;
      case RGBA_F16 -> Bitmap.Config.RGBA_F16;
      default -> null;
    };
    Bitmap.CompressFormat cf = switch (compressFormat) {
      case 0 -> Bitmap.CompressFormat.JPEG;
      case 1 -> Bitmap.CompressFormat.PNG;
      case 3 -> Bitmap.CompressFormat.WEBP_LOSSY;
      case 4 -> Bitmap.CompressFormat.WEBP_LOSSLESS;
      default -> null;
    };
    ColorSpace cs = config == Bitmap.Config.ALPHA_8 ? null : colorSpace(dataSpace);
    if (config == null || cf == null || width <= 0 || height <= 0 || quality < 0 || quality > 100
        || (cs == null && config != Bitmap.Config.ALPHA_8)) return null;
    boolean opaque = (flags & 3) == OPAQUE;
    Bitmap b = cs == null ? Bitmap.createBitmap(width, height, config)
        : Bitmap.createBitmap(width, height, config, !opaque, cs);
    try {
      int rowBytes = b.getRowBytes(), bpp = switch (format) {
        case RGBA_8888 -> 4;
        case RGB_565 -> 2;
        case A_8 -> 1;
        default -> 8;
      };
      if (stride < width * bpp) return null;
      if ((flags & 3) == UNPREMUL) b.setPremultiplied(false);
      byte[] row = new byte[rowBytes];
      ByteBuffer packed = ByteBuffer.allocate(rowBytes * height);
      boolean swap = config == Bitmap.Config.ARGB_8888 && bgra();
      for (int y = 0; y < height; y++) {
        pixels.get(y * stride, row, 0, width * bpp);
        if (swap) {
          for (int i = 0; i < width * 4; i += 4) {
            byte r = row[i];
            row[i] = row[i + 2];
            row[i + 2] = r;
          }
        }
        packed.put(row);
      }
      packed.rewind();
      b.copyPixelsFromBuffer(packed);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      return b.compress(cf, quality, out) ? out.toByteArray() : new byte[0];
    } finally {
      b.recycle();
    }
  }
}
