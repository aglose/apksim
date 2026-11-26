package dev.apksim.composeapp

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * `apksim://software`: a window Android draws in software (android:hardwareAccelerated="false" in the manifest, as
 * every window of an app targeting API 13 or lower is). Its whole background is one colour, a custom view draws a
 * square of another in onDraw, and "Swap" changes both, so a screenshot shows whether the window reached the screen
 * and was drawn again after an invalidate. The label says what the views see: "software: hw=false, swaps N"
 * (View.isHardwareAccelerated(), false on a device for such a window). scripts/software-window-check.py drives it.
 */
class SoftwareWindowActivity : Activity() {
  private var swaps = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    val label = TextView(this).apply {
      textSize = 20f
      setTextColor(Color.WHITE)
    }
    val square = Square(this)
    fun show() {
      root.setBackgroundColor(if (swaps % 2 == 0) BACKGROUNDS[0] else BACKGROUNDS[1])
      square.colour = if (swaps % 2 == 0) SQUARES[0] else SQUARES[1]
      square.invalidate()
      label.text = "software: hw=${root.isHardwareAccelerated}, swaps $swaps"
    }
    val swap = Button(this).apply {
      text = "Swap"
      setOnClickListener { swaps++; show() }
    }
    root.addView(label)
    root.addView(swap)
    // 200x200 dp, centred below the button: the check reads its middle.
    root.addView(square, LinearLayout.LayoutParams((200 * resources.displayMetrics.density).toInt(), (200 * resources.displayMetrics.density).toInt()))
    setContentView(root)
    // isHardwareAccelerated is only known once the view is attached to its window.
    root.post { show() }
    show()
  }

  private class Square(context: Context) : View(context) {
    var colour = SQUARES[0]
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
      paint.color = colour
      canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
  }

  companion object {
    /** Background, then after one swap: green, then blue. */
    val BACKGROUNDS = intArrayOf(0xff2e7d32.toInt(), 0xff1565c0.toInt())
    /** The square's: orange, then magenta. */
    val SQUARES = intArrayOf(0xffef6c00.toInt(), 0xffad1457.toInt())
  }
}
