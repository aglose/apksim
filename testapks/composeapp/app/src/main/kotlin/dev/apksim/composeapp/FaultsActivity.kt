package dev.apksim.composeapp

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * apksim://faults: a screen with deliberate faults for scripts/crawl.py's resilience sweep (docs/crawl.md): each is
 * something the sweep must report, beside a control that does the same thing right.
 * - "count" is kept with `remember` and lost on a recreate or process death; "saved" is `rememberSaveable` and kept.
 * - A label that fits at font scale 1.0 and is ellipsized or clipped at 2.0 (Compose Text with maxLines, one with a
 *   fixed height; "Views version" has the same in plain Views), and one that fits at any size.
 * - "Loading" shows a spinner for ever when the device has no network (no error, no timeout) and the list when it has.
 * - A control of 24 dp with no label (the a11y audit).
 * - "Back"/"Next" placed with absolute positions, so a right-to-left locale does not mirror them.
 */
class FaultsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
  }

  @Composable
  private fun Screen() {
    var count by remember { mutableIntStateOf(0) }
    var saved by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
      Text("Faults", style = MaterialTheme.typography.titleLarge)
      Text("count: $count")
      Button(onClick = { count++ }) { Text("Add to count") }
      Text("saved: $saved")
      Button(onClick = { saved++ }) { Text("Add to saved") }
      // Clipped at large fonts: wide enough at 1.0, one line, ellipsized at 2.0.
      Box(Modifier.width(170.dp).padding(top = 8.dp)) {
        Text("Boarding gate B12", maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      // A fixed height that holds one line at 1.0 and cuts the second line at 2.0.
      Text("Your table is ready, come in", Modifier.padding(top = 4.dp).height(26.dp), overflow = TextOverflow.Clip)
      // The same faults in plain Views, one screen on.
      Button(onClick = { startActivity(android.content.Intent(this@FaultsActivity, FaultsViewsActivity::class.java)) }) {
        Text("Views version")
      }
      // A parent with a label of its own over a clipped child: the child is cut off, the parent's label is not.
      Column(Modifier.padding(top = 8.dp).semantics { text = AnnotatedString("Panel label") }) {
        Text("Departure gate", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
      }
      // Fits at any size: it wraps.
      Text("This one wraps and always fits, whatever the font size is", Modifier.padding(top = 8.dp))
      Loading()
      Box(Modifier.padding(top = 8.dp).size(24.dp).clickable { count++ })
      Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
        Text("Back")
        Text("Next")
      }
    }
  }

  @Composable
  private fun Loading() {
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
      // The fault: with no network nothing ever ends the wait (no error state, no timeout).
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      if (cm.activeNetwork != null) {
        delay(100)
        loaded = true
      }
    }
    Column(Modifier.padding(top = 8.dp)) {
      if (loaded) Text("Trips loaded") else Row { CircularProgressIndicator(Modifier.size(24.dp)); Text("  Loading trips") }
    }
  }
}
