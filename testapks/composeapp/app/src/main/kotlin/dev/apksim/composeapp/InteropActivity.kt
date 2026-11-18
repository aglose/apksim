package dev.apksim.composeapp

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView

/**
 * apksim://interop: classic Views hosted in Compose through AndroidView (issue 478). `nodes` and `tap-text` must list
 * "Row 0", "Row 1", ... as `uiautomator dump` does; tapping one shows "tapped: Row N".
 */
class InteropActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      var tapped by remember { mutableIntStateOf(-1) }
      LazyColumn {
        item {
          AndroidView({ ctx -> TextView(ctx).apply { textSize = 20f } }, update = {
            it.text = if (tapped < 0) "tapped: none" else "tapped: Row $tapped"
          })
        }
        items((0 until 20).toList()) { i ->
          AndroidView({ ctx ->
            TextView(ctx).apply {
              textSize = 24f
              setPadding(32, 32, 32, 32)
              text = "Row $i"
              setOnClickListener { tapped = i }
            }
          })
        }
      }
    }
  }
}
