package dev.apksim.composeapp

import android.graphics.Bitmap

/**
 * The native library testapks/bitmap-fixture builds (libbitmapfixture.so): Bitmaps read and written through
 * libjnigraphics' AndroidBitmap_* functions, as image libraries do. NativeBitmapTest runs it. Results are the NDK's
 * ANDROID_BITMAP_RESULT_* codes (0 for success).
 */
object BitmapFixture {
  init {
    System.loadLibrary("bitmapfixture")
  }

  /** {result, width, height, stride, format, flags} from AndroidBitmap_getInfo. */
  external fun info(bitmap: Bitmap?): IntArray

  /** [length] bytes at [offset] of the locked pixels, or null with [lastResult] saying why. */
  external fun read(bitmap: Bitmap, offset: Int, length: Int): ByteArray?

  /** [bytes] written at [offset] of the locked pixels. */
  external fun write(bitmap: Bitmap, offset: Int, bytes: ByteArray): Int

  /** [src] copied into [dst] with both locked at once, row by row (Fresco's Bitmaps.copyBitmap). */
  external fun copy(dst: Bitmap, src: Bitmap): Int

  /** [value] written as the first pixel through a lock nested in another. */
  external fun nested(bitmap: Bitmap, value: Int): Int

  /** One thread per row, each locking, writing its row and unlocking 32 times. */
  external fun threads(bitmap: Bitmap, rows: Int): Int

  external fun dataSpace(bitmap: Bitmap): Int

  /** The pixels encoded by AndroidBitmap_compress (0 JPEG, 1 PNG, 3 WEBP_LOSSY, 4 WEBP_LOSSLESS), or null. */
  external fun compress(bitmap: Bitmap, format: Int, quality: Int): ByteArray?

  /** 0 when getInfo, lockPixels and unlockPixels each answer BAD_PARAMETER for a null bitmap. */
  external fun badParameters(): Int

  external fun lastResult(): Int
}
