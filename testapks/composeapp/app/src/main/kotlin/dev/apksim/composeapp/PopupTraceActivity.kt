package dev.apksim.composeapp

import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * apksim://popup-trace: two things a large app did that stopped a running instance (issue 52).
 * - "Trace twice" begins the same Trace async section (name and cookie) twice, as a screen-load tracker that starts
 *   again before it ended does. On a device nobody is tracing every Trace call is a no-op; Robolectric's ShadowTrace
 *   threw "Section is already running".
 * - Dragging the slider shows its value in a Popup that is not focusable and stays up, as a slider's value tooltip
 *   can. Taps outside it must reach this window ("Tap me" counts them): touches outside a non-focusable window go to
 *   the windows behind it (InputDispatcher's touch-modal rule). Tapping it also closes the tooltip: in a test run an
 *   open Compose Popup never lets the main looper idle (its frame loop runs at once with Robolectric's Choreographer),
 *   so ActivityScenario.close() would wait for ever.
 */
class PopupTraceActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
  }

  @Composable
  private fun Screen() {
    var traced by remember { mutableIntStateOf(0) }
    var taps by remember { mutableIntStateOf(0) }
    var value by remember { mutableFloatStateOf(0.2f) }
    var tooltip by remember { mutableStateOf(false) }
    var outside by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
      Text("Popup and Trace", style = MaterialTheme.typography.titleLarge)
      Button(onClick = {
        Trace.beginAsyncSection("popup-trace-load", 1)
        Trace.beginAsyncSection("popup-trace-load", 1)
        traced++
      }, Modifier.testTag("traceTwice")) { Text("Trace twice") }
      Text("trace: begun twice $traced times")
      Box(Modifier.fillMaxWidth().padding(top = 48.dp)) {
        Slider(value = value, onValueChange = { value = it; tooltip = true }, modifier = Modifier.testTag("slider"))
        if (tooltip) {
          // Above the slider, as a value label: not focusable, so it never takes touches outside its own bounds.
          Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -120), onDismissRequest = { outside++ }, properties = PopupProperties(focusable = false)) {
            Text("value ${(value * 100).toInt()}", Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp))
          }
        }
      }
      Text(if (tooltip) "tooltip: shown" else "tooltip: hidden")
      Button(onClick = { taps++; tooltip = false }, Modifier.padding(top = 48.dp).testTag("tapMe")) { Text("Tap me") }
      Text("taps: $taps")
      Text("outside: $outside")
    }
  }
}
