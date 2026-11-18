package dev.apksim.composeapp

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A bottom sheet over a screen that counts taps behind it (apksim://bottomsheet?kind=KIND, KIND one of):
 * - `material` (default): Material Components' BottomSheetDialog, a full-screen window whose sheet sits at the bottom.
 * - `compose`: Material 3's ModalBottomSheet, a Compose dialog window.
 * - `plain`: a framework Dialog whose window is MATCH_PARENT x WRAP_CONTENT with BOTTOM gravity, the shape a
 *   hand-made sheet has: its window's size and place are the window manager's, which apksim does itself.
 * Each sheet has a "Count" button ("sheet taps: N") for a tap at its node's bounds. `&rows=N` adds N rows of text, so a
 * tall sheet can be compared too.
 */
class BottomSheetActivity : AppCompatActivity() {
  private var behind = 0

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val kind = intent.data?.getQueryParameter("kind") ?: "material"
    val rows = intent.data?.getQueryParameter("rows")?.toIntOrNull() ?: 3
    if (kind == "compose") {
      setContent {
        var behindTaps by remember { mutableIntStateOf(0) }
        var sheetTaps by remember { mutableIntStateOf(0) }
        var open by remember { mutableStateOf(true) }
        Column(Modifier.fillMaxSize().padding(top = 80.dp, start = 24.dp)) {
          Text("bottomsheet: compose")
          ComposeButton(onClick = { behindTaps++ }) { Text("Tap behind") }
          Text("behind taps: $behindTaps")
          ComposeButton(onClick = { open = true }) { Text("Open sheet") }
        }
        if (open) {
          ModalBottomSheet(onDismissRequest = { open = false },
              sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(24.dp)) {
              Text("Compose sheet")
              repeat(rows) { Text("Row $it") }
              ComposeButton(onClick = { sheetTaps++ }) { Text("Count") }
              Text("sheet taps: $sheetTaps")
            }
          }
        }
      }
      return
    }
    val count = TextView(this).apply { text = "behind taps: 0" }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 160, 48, 48)
      addView(TextView(this@BottomSheetActivity).apply { text = "bottomsheet: $kind"; textSize = 24f })
      addView(button("Tap behind") { behind++; count.text = "behind taps: $behind" })
      addView(count)
      addView(button("Open sheet") { open(kind, rows) })
    })
    window.decorView.post { open(kind, rows) }
  }

  private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false  // accessibility reports the text as shown
    setOnClickListener { onClick() }
  }

  private fun open(kind: String, rows: Int) {
    var taps = 0
    val tapsText = TextView(this).apply { text = "sheet taps: 0" }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 48, 48, 48)
      addView(TextView(this@BottomSheetActivity).apply { text = if (kind == "plain") "Plain sheet" else "Material sheet" })
      repeat(rows) { i -> addView(TextView(this@BottomSheetActivity).apply { text = "Row $i"; setPadding(0, 24, 0, 24) }) }
      addView(button("Count") { taps++; tapsText.text = "sheet taps: $taps" })
      addView(tapsText)
    }
    if (kind == "plain") {
      val dialog = Dialog(this)
      content.setBackgroundColor(Color.rgb(230, 230, 240))
      dialog.setContentView(content)
      dialog.window?.apply {
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
      }
      dialog.show()
    } else {
      val dialog = BottomSheetDialog(this)
      dialog.setContentView(content)
      dialog.behavior.skipCollapsed = true
      dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
      dialog.show()
    }
  }
}
