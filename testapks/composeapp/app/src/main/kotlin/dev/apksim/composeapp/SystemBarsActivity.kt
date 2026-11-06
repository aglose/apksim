package dev.apksim.composeapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://systembars: the system bar insets this window gets ("bars: status=48 nav=48", 0 for a bar the device lacks or
 * the app hid), and buttons that hide and show the bars the ways apps do (SystemBarsTest, `bars` and `insets`).
 */
class SystemBarsActivity : Activity() {
  lateinit var label: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setDecorFitsSystemWindows(false)  // edge to edge: the content gets the insets
    label = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER }
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.rgb(0x20, 0x60, 0xa0))
      addView(label)
      addView(button("Hide bars") { hide(WindowInsetsController.BEHAVIOR_DEFAULT) })
      addView(button("Immersive sticky") { hide(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) })
      addView(button("Show bars") { window.insetsController?.show(WindowInsets.Type.systemBars()) })
      @Suppress("DEPRECATION")
      addView(button("Legacy fullscreen") {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
          View.SYSTEM_UI_FLAG_IMMERSIVE
      })
      @Suppress("DEPRECATION")
      addView(button("Flag fullscreen") { window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) })
    }
    column.setOnApplyWindowInsetsListener { v, insets ->
      val bars = insets.getInsets(WindowInsets.Type.systemBars())
      label.text = "bars: status=${bars.top} nav=${bars.bottom}"
      v.onApplyWindowInsets(insets)
    }
    setContentView(column)
  }

  private fun hide(behavior: Int) {
    window.insetsController?.let {
      it.systemBarsBehavior = behavior
      it.hide(WindowInsets.Type.systemBars())
    }
  }

  private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    setOnClickListener { onClick() }
  }
}
