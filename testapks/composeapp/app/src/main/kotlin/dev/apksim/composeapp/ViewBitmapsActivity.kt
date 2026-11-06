package dev.apksim.composeapp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * apksim://bitmaps: framework View drawing of scaled bitmaps, as a large app's full-screen screens do it (apksim's
 * GpuBitmaps, AGENTS.md). A BitmapDrawable background stretched over the whole window, an ImageView (FIT_XY) with a
 * translucent gradient image over the bottom, an ImageView (CENTER_CROP, matrix scale 1.5), a custom view drawing
 * through Canvas.drawBitmap(Bitmap, Matrix, Paint), and one redrawing a mutable bitmap it draws scaled (its copy
 * must follow the content). A spinner keeps frames coming; `apksim://bitmaps?animate=0` keeps everything still, for
 * comparing pixels. `?video=1` adds the 640x360 test clip, looping, in a TextureView 1600 pixels wide (a picture
 * smaller than its view, scaled up every frame).
 */
class ViewBitmapsActivity : Activity() {
  private var player: androidx.media3.exoplayer.ExoPlayer? = null

  override fun onDestroy() {
    player?.release()
    super.onDestroy()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val animate = intent?.data?.getQueryParameter("animate") != "0"
    val density = resources.displayMetrics.density
    val root = FrameLayout(this)
    root.background = BitmapDrawable(resources, photo(1280, 800))

    val overlay = ImageView(this).apply {
      scaleType = ImageView.ScaleType.FIT_XY
      setImageBitmap(gradient(256, 160))
    }
    root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
    root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
      val h = (bottom - top) * 2 / 5
      if (overlay.layoutParams.height != h) overlay.post { overlay.layoutParams = overlay.layoutParams.also { it.height = h } }
    }

    // 600 x 400 at 1.5 = 900 x 600 pixels: CENTER_CROP's matrix scale lands on whole pixels.
    val card = ImageView(this).apply {
      scaleType = ImageView.ScaleType.CENTER_CROP
      setImageBitmap(photo(600, 400, hue = 200f))
    }
    root.addView(card, FrameLayout.LayoutParams(px(900, density), px(600, density), Gravity.TOP or Gravity.START).apply {
      leftMargin = px(40, density); topMargin = px(120, density)
    })
    root.addView(MatrixView(this), FrameLayout.LayoutParams(px(480, density), px(240, density), Gravity.TOP or Gravity.END).apply {
      rightMargin = px(40, density); topMargin = px(120, density)
    })
    val live = LiveBitmapView(this, animate)
    root.addView(live, FrameLayout.LayoutParams(px(400, density), px(240, density), Gravity.TOP or Gravity.END).apply {
      rightMargin = px(40, density); topMargin = px(400, density)
    })
    root.addView(TextView(this).apply {
      text = "Bitmaps (Views)"
      textSize = 22f
      setTextColor(Color.WHITE)
      setPadding(px(40, density), px(40, density), 0, 0)
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    if (intent?.data?.getQueryParameter("video") == "1") {
      val texture = android.view.TextureView(this)
      root.addView(texture, FrameLayout.LayoutParams(px(1600, density), px(900, density), Gravity.BOTTOM or Gravity.START).apply {
        leftMargin = px(40, density); bottomMargin = px(40, density)
      })
      player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build().apply {
        setVideoTextureView(texture)
        repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        volume = 0f
        setMediaItem(androidx.media3.common.MediaItem.fromUri("android.resource://$packageName/${R.raw.clip}"))
        prepare()
        play()
      }
    }
    val spinner = View(this).apply { setBackgroundColor(Color.YELLOW) }
    root.addView(spinner, FrameLayout.LayoutParams(px(48, density), px(48, density), Gravity.BOTTOM or Gravity.END).apply {
      rightMargin = px(40, density); bottomMargin = px(40, density)
    })
    setContentView(root)
    if (animate) {
      ObjectAnimator.ofFloat(spinner, View.ROTATION, 0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        start()
      }
    }
  }

  private fun px(dpPixels: Int, density: Float) = (dpPixels * density / 2f).toInt()

  /** Canvas.drawBitmap(Bitmap, Matrix, Paint) at a scale of 3 (an app's own drawing, not a Drawable's). */
  private class MatrixView(context: Context) : View(context) {
    private val bitmap = photo(160, 80, hue = 30f)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    override fun onDraw(canvas: Canvas) {
      matrix.setScale(width / 160f, height / 80f)
      canvas.drawBitmap(bitmap, matrix, paint)
    }
  }

  /** A mutable bitmap drawn into on every frame (a moving bar) and drawn scaled 2x. */
  private class LiveBitmapView(context: Context, private val animate: Boolean) : View(context) {
    private val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
    private val into = Canvas(bitmap)
    private val bar = Paint().apply { color = Color.MAGENTA }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var step = 0

    override fun onDraw(canvas: Canvas) {
      into.drawColor(Color.rgb(20, 40, 60))
      val x = (step * 3 % 200).toFloat()
      into.drawRect(x, 10f, x + 30f, 110f, bar)
      dst.set(0, 0, width, height)
      canvas.drawBitmap(bitmap, null, dst, paint)
      if (animate) {
        step++
        postInvalidateOnAnimation()
      }
    }
  }

  companion object {
    /** A photo-like image: soft colour fields and fine detail, so bilinear differences show. Immutable, as decoded. */
    fun photo(w: Int, h: Int, hue: Float = 140f): Bitmap {
      val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val c = Canvas(b)
      val p = Paint(Paint.ANTI_ALIAS_FLAG)
      p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.5f)),
        Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.7f, 0.9f)), Shader.TileMode.CLAMP)
      c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
      p.shader = RadialGradient(w * 0.7f, h * 0.35f, h * 0.4f, Color.argb(200, 255, 240, 200), Color.TRANSPARENT,
        Shader.TileMode.CLAMP)
      c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
      p.shader = null
      p.color = Color.argb(90, 0, 0, 0)
      var x = 0f
      while (x < w) {
        c.drawLine(x, 0f, x + h * 0.3f, h.toFloat(), p)
        x += 7f
      }
      return b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() }
    }

    /** A translucent top-to-bottom gradient image, as a scrim drawn over a background. */
    fun gradient(w: Int, h: Int): Bitmap {
      val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val p = Paint()
      p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(230, 10, 10, 30), Shader.TileMode.CLAMP)
      Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
      return b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() }
    }
  }
}
