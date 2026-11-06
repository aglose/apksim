package dev.apksim.composeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native code reading and writing Bitmaps through libjnigraphics (testapks/bitmap-fixture): AndroidBitmapInfo for
 * each config, pixels in Android's byte order (R, G, B, A for ARGB_8888, premultiplied), writes that Java sees after
 * the unlock, Fresco's copy of one locked bitmap into another, nested locks, threads, data spaces and
 * AndroidBitmap_compress. Each passes on a device; apksim copies the pixels out on lock and back on unlock.
 */
@RunWith(AndroidJUnit4::class)
class NativeBitmapTest {
  private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

  @Test
  fun infoDescribesEachConfig() {
    val argb = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    assertArrayEquals(intArrayOf(0, 4, 3, 16, FORMAT_RGBA_8888, FLAGS_PREMUL), BitmapFixture.info(argb))
    val opaque = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    assertEquals(FLAGS_OPAQUE, BitmapFixture.info(opaque)[5])
    val unpremul = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { isPremultiplied = false }
    assertEquals(FLAGS_UNPREMUL, BitmapFixture.info(unpremul)[5])
    assertArrayEquals(intArrayOf(0, 4, 3, 8, FORMAT_RGB_565, FLAGS_OPAQUE),
      BitmapFixture.info(Bitmap.createBitmap(4, 3, Bitmap.Config.RGB_565)))
    assertArrayEquals(intArrayOf(0, 4, 3, 4, FORMAT_A_8, FLAGS_PREMUL),
      BitmapFixture.info(Bitmap.createBitmap(4, 3, Bitmap.Config.ALPHA_8)))
    assertArrayEquals(intArrayOf(0, 4, 3, 32, FORMAT_RGBA_F16, FLAGS_PREMUL),
      BitmapFixture.info(Bitmap.createBitmap(4, 3, Bitmap.Config.RGBA_F16)))
    assertEquals(0, BitmapFixture.badParameters())
  }

  @Test
  fun lockedPixelsAreRgbaAndPremultiplied() {
    val b = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    b.setPixel(1, 2, Color.argb(255, 0x11, 0x22, 0x33))
    b.setPixel(0, 0, Color.argb(0x80, 0xff, 0, 0))
    val stride = b.rowBytes
    assertArrayEquals(bytes(0x11, 0x22, 0x33, 0xff), BitmapFixture.read(b, 2 * stride + 4, 4))
    assertArrayEquals(bytes(0x80, 0, 0, 0x80), BitmapFixture.read(b, 0, 4))
  }

  @Test
  fun writesReachJavaAfterTheUnlock() {
    val b = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    val generation = b.generationId
    assertEquals(0, BitmapFixture.write(b, b.rowBytes + 2 * 4, bytes(0x40, 0x50, 0x60, 0xff)))
    assertEquals(Color.argb(255, 0x40, 0x50, 0x60), b.getPixel(2, 1))
    assertEquals(0, b.getPixel(1, 1))
    assertEquals(0, b.getPixel(3, 1))
    // Caches keyed by it (HWUI's, apksim's GPU copies) must not keep the old pixels.
    assertNotEquals(generation, b.generationId)
  }

  @Test
  fun otherConfigsKeepTheirLayout() {
    val rgb = Bitmap.createBitmap(4, 1, Bitmap.Config.RGB_565)
    rgb.setPixel(0, 0, Color.RED)
    assertArrayEquals(bytes(0x00, 0xf8), BitmapFixture.read(rgb, 0, 2))
    assertEquals(0, BitmapFixture.write(rgb, 2, bytes(0x1f, 0x00)))
    assertEquals(Color.BLUE, rgb.getPixel(1, 0))

    val alpha = Bitmap.createBitmap(4, 1, Bitmap.Config.ALPHA_8)
    alpha.eraseColor(Color.argb(0x33, 0, 0, 0))
    assertArrayEquals(bytes(0x33, 0x33), BitmapFixture.read(alpha, 0, 2))
    assertEquals(0, BitmapFixture.write(alpha, 1, bytes(0x7f)))
    assertEquals(0x7f, Color.alpha(alpha.getPixel(1, 0)))
    assertEquals(0x33, Color.alpha(alpha.getPixel(2, 0)))

    val half = Bitmap.createBitmap(2, 1, Bitmap.Config.RGBA_F16)
    half.eraseColor(Color.WHITE)
    // 1.0 as a half float is 0x3c00, little-endian, for each of R, G, B and A.
    assertArrayEquals(bytes(0, 0x3c, 0, 0x3c, 0, 0x3c, 0, 0x3c), BitmapFixture.read(half, 8, 8))
  }

  @Test
  fun rgba1010102IsOnePackedWordPerPixel() {
    val b = Bitmap.createBitmap(4, 3, Bitmap.Config.RGBA_1010102)
    assertArrayEquals(intArrayOf(0, 4, 3, 16, FORMAT_RGBA_1010102, FLAGS_PREMUL), BitmapFixture.info(b))
    b.eraseColor(Color.GREEN)
    b.setPixel(1, 0, Color.RED)
    // Little-endian words, red in bits 0-9, green 10-19, blue 20-29, alpha 30-31: red is 0xc00003ff.
    assertArrayEquals(bytes(0xff, 0x03, 0x00, 0xc0), BitmapFixture.read(b, 4, 4))
    assertArrayEquals(bytes(0x00, 0xfc, 0x0f, 0xc0), BitmapFixture.read(b, 0, 4))
    // Blue (0xfff00000) written at (2, 1).
    assertEquals(0, BitmapFixture.write(b, b.rowBytes + 8, bytes(0x00, 0x00, 0xf0, 0xff)))
    assertEquals(Color.BLUE, b.getPixel(2, 1))
    val png = BitmapFixture.compress(b, COMPRESS_PNG, 100)
    assertNotNull("compress: ${BitmapFixture.lastResult()}", png)
    val decoded = BitmapFactory.decodeByteArray(png, 0, png!!.size)
    assertEquals(Color.RED, decoded.getPixel(1, 0))
    assertEquals(Color.BLUE, decoded.getPixel(2, 1))
    assertEquals(Color.GREEN, decoded.getPixel(3, 2))
  }

  @Test
  fun copiesOneLockedBitmapIntoAnother() {
    val src = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888)
    src.setPixels(IntArray(20) { Color.argb(255, it * 12, 255 - it * 12, it * 3) }, 0, 5, 0, 0, 5, 4)
    val dst = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888)
    assertEquals(0, BitmapFixture.copy(dst, src))
    assertTrue(dst.sameAs(src))
  }

  @Test
  fun nestedLocksShareThePixels() {
    val b = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    assertEquals(0, BitmapFixture.nested(b, 0xff332211.toInt()))
    assertEquals(Color.argb(255, 0x11, 0x22, 0x33), b.getPixel(0, 0))
  }

  @Test
  fun threadsLockingOneBitmapKeepEveryWrite() {
    val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    assertEquals(0, BitmapFixture.threads(b, 8))
    for (y in 0 until 8) for (x in 0 until 8) assertEquals("($x, $y)", Color.argb(255, y * 16 + 15, 0, 0), b.getPixel(x, y))
  }

  @Test
  fun dataSpacesFollowTheColorSpace() {
    assertEquals(ADATASPACE_SRGB, BitmapFixture.dataSpace(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
    val p3 = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    assertEquals(ADATASPACE_DISPLAY_P3, BitmapFixture.dataSpace(p3))
    assertEquals(0, BitmapFixture.dataSpace(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)))
  }

  @Test
  fun compressEncodesTheLockedPixels() {
    val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    b.eraseColor(Color.BLUE)
    b.setPixel(1, 2, Color.RED)
    val png = BitmapFixture.compress(b, COMPRESS_PNG, 100)
    assertNotNull("compress: ${BitmapFixture.lastResult()}", png)
    val decoded = BitmapFactory.decodeByteArray(png, 0, png!!.size)
    assertEquals(Color.RED, decoded.getPixel(1, 2))
    assertEquals(Color.BLUE, decoded.getPixel(3, 3))
    val jpegBytes = BitmapFixture.compress(b, COMPRESS_JPEG, 90)
    assertNotNull("compress: ${BitmapFixture.lastResult()}", jpegBytes)
    val jpeg = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes!!.size)
    assertTrue("jpeg ${Integer.toHexString(jpeg.getPixel(3, 0))}", Color.blue(jpeg.getPixel(3, 0)) > 200)
  }

  private companion object {
    const val FORMAT_RGBA_8888 = 1
    const val FORMAT_RGB_565 = 4
    const val FORMAT_A_8 = 8
    const val FORMAT_RGBA_F16 = 9
    const val FORMAT_RGBA_1010102 = 10
    const val FLAGS_PREMUL = 0
    const val FLAGS_OPAQUE = 1
    const val FLAGS_UNPREMUL = 2
    const val ADATASPACE_SRGB = 142671872
    const val ADATASPACE_DISPLAY_P3 = 143261696
    const val COMPRESS_JPEG = 0
    const val COMPRESS_PNG = 1
  }
}
