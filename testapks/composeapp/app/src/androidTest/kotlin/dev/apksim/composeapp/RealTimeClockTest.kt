package dev.apksim.composeapp

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.not
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Timed UI as on a device: a banner that closes itself after 5 s, shown while an animation runs, is still there when
 * the test checks it right away, and gone once 5 s have passed. On a device that is the clock; on apksim it needs
 * `--realtime-clock` (by default a test run skips idle time, and one Espresso check ran the whole animation's frames,
 * closing the banner first). So on apksim it runs only with `-e realtimeClock true`.
 */
@RunWith(AndroidJUnit4::class)
class RealTimeClockTest {
  private val onApksim = Build.FINGERPRINT == "robolectric"

  @Test
  fun aBannerThatClosesItselfAfter5sIsShownUntilThen() {
    assumeTrue(!onApksim || InstrumentationRegistry.getArguments().getString("realtimeClock") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      var shownAt = 0L
      scenario.onActivity { activity ->
        val banner = TextView(activity).apply { text = BANNER }
        (activity.window.decorView as ViewGroup).addView(banner)
        banner.postDelayed({ banner.visibility = View.GONE }, 5_000)
        ValueAnimator.ofFloat(0f, 1f).apply {
          duration = 6_000
          addUpdateListener { banner.alpha = 0.5f + 0.5f * it.animatedFraction }
          start()
        }
        shownAt = System.nanoTime()
      }
      onView(withText(BANNER)).check(matches(isDisplayed()))
      val checkedMs = (System.nanoTime() - shownAt) / 1_000_000
      assertTrue("the first check took $checkedMs ms", checkedMs < 4_000)
      val deadline = shownAt + 9_000_000_000L
      while (System.nanoTime() < deadline && bannerShown()) Thread.sleep(100)
      onView(withText(BANNER)).check(matches(not(isDisplayed())))
      val goneMs = (System.nanoTime() - shownAt) / 1_000_000
      assertTrue("the banner closed after $goneMs ms, not 5 s", goneMs >= 4_900)
    }
  }

  private fun bannerShown(): Boolean = try {
    onView(withText(BANNER)).check(matches(isDisplayed()))
    true
  } catch (e: AssertionError) {
    false
  }

  private companion object {
    const val BANNER = "Banner closes itself in 5 s"
  }
}
