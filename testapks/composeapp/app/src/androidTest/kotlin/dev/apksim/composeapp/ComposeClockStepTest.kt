package dev.apksim.composeapp

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Compose rule's test clock steps its frames without waiting for the screen: a second of its time takes far less
 * than a second, even while the content animates, a view updated from posted work keeps asking the window for
 * frames. On apksim with `--realtime-clock` each step ran the main queue and waited out the window's
 * next frame in real time: a second of test-clock time took about a second (a check meant to see a short-lived state
 * came too late for it), and with work due behind every frame the step never ended. So on apksim it runs only with `-e realtimeClock true`.
 */
@RunWith(AndroidJUnit4::class)
class ComposeClockStepTest {
  @get:Rule val compose = createComposeRule()

  private val onApksim = Build.FINGERPRINT == "robolectric"
  private var ticking: TextView? = null

  @Test(timeout = 20_000)
  fun aSecondOfTestClockTimeTakesLessThanASecond() {
    assumeTrue(!onApksim || InstrumentationRegistry.getArguments().getString("realtimeClock") == "true")
    compose.mainClock.autoAdvance = false
    compose.setContent {
      val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 20f,
        targetValue = 200f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "width",
      )
      Column {
        Text("pulsing", Modifier.width(pulse.dp))
        AndroidView(factory = { context -> TextView(context).also { ticking = it } })
      }
    }
    // And a view updated from posted main-thread work, as an app's timers and flows update one: each update asks the
    // window for a frame outside of one.
    val handler = Handler(Looper.getMainLooper())
    val tick = object : Runnable {
      var ticks = 0
      override fun run() {
        ticking?.text = "tick ${ticks++}"
        handler.postDelayed(this, 5)
      }
    }
    compose.runOnUiThread { handler.post(tick) }
    val start = System.nanoTime()
    compose.mainClock.advanceTimeBy(1_000)
    val tookMs = (System.nanoTime() - start) / 1_000_000
    compose.runOnUiThread { handler.removeCallbacks(tick) }
    assertTrue("1,000 ms of test-clock time took $tookMs ms", tookMs < 500)
  }
}
