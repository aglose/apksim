package dev.apksim.composeapp

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * apksim://bitmaps: a full-screen View screen whose pixels are mostly scaled bitmaps, drawn the framework's way rather
 * than Compose's: a BitmapDrawable background stretched over the window (Canvas.drawBitmap with src/dst rects), a
 * centerCrop ImageView over it (its matrix on the canvas, then the drawable's rect draw), and a custom View drawing a
 * vignette image with Canvas.drawBitmap(Bitmap, Matrix, Paint), and two thumbnails of the backdrop bitmap at one size
 * (one copy for both views). A small dot moves every frame, so frames keep coming
 * while the big views are recorded once. On a large display (`--device tablet`) the CPU's bilinear scaling of these
 * draws was most of each frame.
 */
class BitmapsActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    val backdrop = gradientImage(480, 300)
    root.background = BitmapDrawable(resources, backdrop)
    root.addView(
      ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageBitmap(photo(900, 1200))
        alpha = 0.85f
      },
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    root.addView(VignetteView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    // Two thumbnails of one bitmap at one size: one copy, which both views must pick up.
    for (i in 0 until 2) {
      root.addView(
        ImageView(this).apply {
          scaleType = ImageView.ScaleType.FIT_XY
          setImageBitmap(backdrop)
        },
        FrameLayout.LayoutParams(720, 450, Gravity.BOTTOM or Gravity.START).apply { leftMargin = 32 + i * 752; bottomMargin = 160 },
      )
    }
    root.addView(DotView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 96, Gravity.BOTTOM))
    root.addView(
      TextView(this).apply {
        text = "bitmaps: ImageView, BitmapDrawable, drawBitmap(Matrix)"
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(32, 96, 32, 32)
      },
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
    )
    setContentView(root)
  }

  /** A smooth background gradient with soft shapes, as an app ships for a screen's backdrop. */
  private fun gradientImage(w: Int, h: Int): Bitmap {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xff1d2b64.toInt(), 0xfff8cdda.toInt(), Shader.TileMode.CLAMP) })
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    for (i in 0 until 6) {
      p.color = Color.argb(60, 255, 255 - i * 30, 120 + i * 20)
      c.drawCircle(w * (0.15f + i * 0.15f), h * (0.3f + (i % 2) * 0.4f), h * 0.18f, p)
    }
    return b
  }

  /** Photo-like detail (fine stripes and noise), so scaling it is visible. */
  private fun photo(w: Int, h: Int): Bitmap {
    val pixels = IntArray(w * h)
    var seed = 12345
    for (y in 0 until h) {
      for (x in 0 until w) {
        seed = seed * 1103515245 + 12345
        val n = (seed ushr 24) and 0x3f
        val r = (x * 255 / w + n) and 0xff
        val g = ((y * 255 / h) + ((x / 7) % 2) * 40) and 0xff
        val bl = (((x + y) / 5 % 2) * 120 + n) and 0xff
        pixels[y * w + x] = Color.argb(255, r, g, bl)
      }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
  }

  /** A translucent vignette, drawn with Canvas.drawBitmap(Bitmap, Matrix, Paint) scaled to the view. */
  private class VignetteView(context: Context) : View(context) {
    private val image: Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also {
      Canvas(it).drawPaint(Paint().apply {
        shader = RadialGradient(128f, 128f, 181f, intArrayOf(0x00000000, 0x00000000, 0xa0000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
      })
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    override fun onDraw(canvas: Canvas) {
      matrix.setScale(width / 256f, height / 256f)
      canvas.drawBitmap(image, matrix, paint)
    }
  }

  /** A dot that moves every frame; only this view is recorded again. */
  private class DotView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var frame = 0

    override fun onDraw(canvas: Canvas) {
      val x = (frame++ * 6) % (width.coerceAtLeast(1) + 80) - 40f
      canvas.drawCircle(x, height / 2f, 24f, paint)
      postInvalidateOnAnimation()
    }
  }
}
