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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * apksim://dialogs: dialogs over an activity, for which windows accessibility reports (issue 97). On a device a window
 * whose whole area is taken by touch-modal or full-screen windows above it is left out of UiAutomation.getWindows,
 * uiautomator dump and the like:
 * - "Full-screen dialog": a Dialog filling the screen, dim behind. The activity is not reported.
 * - "Modal dialog": a small Dialog as Dialog makes it, touch-modal (focusable, no FLAG_NOT_TOUCH_MODAL). It takes
 *   every touch on the screen, so the window manager counts the whole screen as its own: the activity is not reported.
 * - "Non-modal dialog": a small Dialog with FLAG_NOT_TOUCH_MODAL: touches outside it reach the activity, which is
 *   reported, and "Tap behind" there counts them.
 * - "Compose dialog": a small Compose Dialog (androidx.compose.ui.window.Dialog), touch-modal too.
 * Each dialog counts the taps on its "Count" button ("dialog taps: N"): a centred dialog's nodes must report where the
 * dialog is on the screen, so that a tap at their bounds (`tap-text`, UiAutomator's click) lands on them.
 * `?open=full|modal|nonmodal|compose` (or the "open" extra) opens one at start. Plain views but for the Compose dialog,
 * for `viewtree` and `nodes`.
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
      addView(button("Compose dialog") { open("compose") })
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
    if (which == "compose") return openCompose()
    val dialog = Dialog(this)
    var dialogTaps = 0
    val count = TextView(this).apply { text = "dialog taps: 0"; setTextColor(Color.WHITE) }
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
      addView(button("Count") { dialogTaps++; count.text = "dialog taps: $dialogTaps" })
      addView(count)
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

  /** A Compose Dialog, hosted by a ComposeView of the activity's (ComponentDialog would need a ComponentActivity). */
  private fun openCompose() {
    val host = ComposeView(this)
    val owner = ComposeHost()
    // On the window's root: Compose's window recomposer looks for the lifecycle there.
    window.decorView.setViewTreeLifecycleOwner(owner)
    window.decorView.setViewTreeSavedStateRegistryOwner(owner)
    host.setContent {
      var open by remember { mutableStateOf(true) }
      var count by remember { mutableIntStateOf(0) }
      if (open) {
        Dialog(onDismissRequest = { open = false }) {
          Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(24.dp)) {
              Text("Compose dialog open")
              androidx.compose.material3.Button(onClick = { count++ }) { Text("Compose count") }
              Text("compose dialog taps: $count")
              androidx.compose.material3.Button(onClick = { open = false }) { Text("Compose close") }
            }
          }
        }
      }
    }
    (window.decorView as ViewGroup).addView(host, ViewGroup.LayoutParams(0, 0))
  }

  /** The lifecycle and saved state a ComposeView outside a ComponentActivity needs: resumed for as long as it lives. */
  private class ComposeHost : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
      saved.performRestore(null)
      registry.currentState = Lifecycle.State.RESUMED
    }
  }
}
