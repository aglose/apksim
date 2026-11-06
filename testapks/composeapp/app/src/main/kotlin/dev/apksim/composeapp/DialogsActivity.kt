package dev.apksim.composeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://dialogs: dialogs over an activity, for which windows accessibility reports (issue 97). On a device a window
 * whose whole area is taken by touch-modal or full-screen windows above it is left out of UiAutomation.getWindows,
 * uiautomator dump and the like:
 * - "Full-screen dialog": a Dialog filling the screen, dim behind. The activity is not reported.
 * - "Modal dialog": a small Dialog as Dialog makes it, touch-modal (focusable, no FLAG_NOT_TOUCH_MODAL). It takes
 *   every touch on the screen, so the window manager counts the whole screen as its own: the activity is not reported.
 * - "Non-modal dialog": a small Dialog with FLAG_NOT_TOUCH_MODAL: touches outside it reach the activity, which is
 *   reported, and "Tap behind" there counts them.
 * `?open=full|modal|nonmodal` (or the "open" extra) opens one at start. Plain views, for `viewtree` and `nodes`.
 */
class DialogsActivity : Activity() {
  private var taps = 0
  private var opened = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val count = TextView(this).apply { text = "behind taps: 0" }
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 160, 48, 48)
      addView(TextView(this@DialogsActivity).apply { text = "Behind the dialogs"; textSize = 24f })
      // Near the top, clear of the small dialogs in the middle of the screen.
      addView(button("Tap behind") { taps++; count.text = "behind taps: $taps" })
      addView(count)
      addView(button("Full-screen dialog") { open("full") })
      addView(button("Modal dialog") { open("modal") })
      addView(button("Non-modal dialog") { open("nonmodal") })
    }
    setContentView(root)
  }

  override fun onResume() {
    super.onResume()
    if (opened) return
    opened = true
    val which = intent.getStringExtra("open") ?: intent.data?.getQueryParameter("open") ?: return
    window.decorView.post { open(which) }
  }

  private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false  // accessibility reports the text as shown
    setOnClickListener { onClick() }
  }

  private fun open(which: String) {
    val dialog = Dialog(this)
    val title = when (which) {
      "full" -> "Full-screen dialog open"
      "modal" -> "Small modal dialog open"
      else -> "Small non-modal dialog open"
    }
    dialog.setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 48, 48, 48)
      setBackgroundColor(if (which == "full") Color.rgb(40, 40, 90) else Color.rgb(60, 60, 60))
      addView(TextView(this@DialogsActivity).apply { text = title; setTextColor(Color.WHITE) })
      addView(button("Close") { dialog.dismiss() })
    })
    val w = dialog.window!!
    w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    when (which) {
      "full" -> w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      "nonmodal" -> w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }
    dialog.show()
  }
}
