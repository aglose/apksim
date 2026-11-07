package dev.apksim.composeapp

import android.animation.ValueAnimator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Espresso swipes over a screen that never stops changing (issue 131). Espresso runs its action as a message on the main
 * thread, and the action idles the looper again from inside it after each motion event. Under `--realtime-clock`
 * apksim waits out a sync barrier's frame when the looper peeks, and counts barriers as idle only after a 250 ms streak
 * of them; a view changed from a message between frames (a 16 ms ticker, a post from each frame) put that message at
 * the queue's head, or due behind the barrier, between every two frames, which started the streak again each time, so
 * the nested idle waited frame after frame until the test's timeout. A device's Espresso is idle between those frames.
 * Runs on apksim only with `-e realtimeClock true`.
 */
@RunWith(AndroidJUnit4::class)
class RealTimeSwipeTest {
  private val onApksim = Build.FINGERPRINT == "robolectric"

  // Without the fix the swipe never returns: the timeout turns that into a failure.
  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  /** A ValueAnimator that never ends: the barrier comes back every frame with nothing between (worked before). */
  @Test
  fun aSwipeOverAnEndlessAnimationReturns() = swipeWhile { ticker, _ ->
    ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 1_000
      repeatCount = ValueAnimator.INFINITE
      addUpdateListener { ticker.alpha = 0.5f + 0.5f * it.animatedFraction }
      start()
    }
  }

  /** A Handler ticker changing a text every 16 ms: its next tick is due behind nearly every barrier. */
  @Test
  fun aSwipeOverA16msHandlerTickerReturns() = swipeWhile { ticker, handler ->
    var n = 0
    val tick = object : Runnable {
      override fun run() {
        ticker.text = "tick ${n++}"
        handler.postDelayed(this, 16)
      }
    }
    handler.post(tick)
  }

  /** Each frame posts a message that changes a view: that message is at the queue's head between frames. */
  @Test
  fun aSwipeOverAPostFromEachFrameReturns() = swipeWhile { ticker, handler ->
    var n = 0
    val callback = object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        handler.post { ticker.text = "frame ${n++}" }
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
    Choreographer.getInstance().postFrameCallback(callback)
  }

  private fun swipeWhile(start: (TextView, Handler) -> Unit) {
    assumeTrue(!onApksim || InstrumentationRegistry.getArguments().getString("realtimeClock") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        repeat(200) { rows.addView(TextView(activity).apply { text = "row $it"; textSize = 24f }) }
        val decor = activity.window.decorView as ViewGroup
        decor.addView(ScrollView(activity).apply { tag = SCROLL; addView(rows) },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // A fixed size, so a new text only invalidates it: Espresso's root picker waits for a root with no layout
        // requested, which a view relaid out on every tick seldom is.
        val ticker = TextView(activity).apply { text = "ticker"; textSize = 24f }
        decor.addView(ticker, ViewGroup.LayoutParams(400, 100))
        start(ticker, Handler(Looper.getMainLooper()))
      }
      repeat(3) { i ->
        val startedAt = System.nanoTime()
        onView(withTagValue(`is`(SCROLL))).perform(swipeUp())
        val ms = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("swipe $i took $ms ms", ms < 10_000)
      }
    }
  }

  private companion object {
    const val SCROLL = "scroll"
  }
}
