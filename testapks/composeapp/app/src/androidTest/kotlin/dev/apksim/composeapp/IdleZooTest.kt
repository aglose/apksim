package dev.apksim.composeapp

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.apksim.composeapp.IdleZooActivity.Expect
import dev.apksim.composeapp.IdleZooActivity.Source
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * The Espresso side of the idle zoo (`apksim://idle-zoo`, [IdleZooActivity]; `scripts/idle-zoo-check.py` is the `settle`
 * side): for each source alone, launch the screen and make one Espresso check and one click (Refresh), each of which waits for the main looper to be
 * idle first. A source a device user calls idle (the table in the activity) must not keep that wait from returning:
 * the check has to finish within [LIMIT_MS] of wall time. Run it on both clocks (`-e realtimeClock true` for
 * `--realtime-clock`).
 *
 * None of the sources hangs or fails here today (2026-11-18, both clocks). Sources a user calls busy (an endless animation)
 * run only with `-e idleZooBusy true`: on a device Espresso's behaviour with them is not the point here.
 */
@RunWith(AndroidJUnit4::class)
class IdleZooTest {
  @get:Rule val timeout: Timeout = Timeout.seconds(90)

  private fun args() = InstrumentationRegistry.getArguments()

  private fun check(s: Source) {
    assumeTrue("a busy source: -e idleZooBusy true", s.expect != Expect.BUSY || args().getString("idleZooBusy") == "true")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(context, IdleZooActivity::class.java).setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("apksim://idle-zoo?on=${s.name}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val t0 = System.nanoTime()
    ActivityScenario.launch<IdleZooActivity>(intent).use {
      onView(withText(startsWith("${s.name}: on"))).check(matches(isDisplayed()))
      // An action too: a tap goes through the injection path and its own idle waits (Espresso's, then the touch's).
      onView(withContentDescription("refresh")).perform(scrollTo(), click())
      onView(withText(startsWith("${s.name}: on"))).check(matches(isDisplayed()))
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("idle-zoo: ${s.name} (${s.expect}) checked in $ms ms")
    assertTrue("${s.name}: the Espresso check took $ms ms of wall time", ms < LIMIT_MS)
  }

  @Test fun ticker() = check(Source.ticker)
  @Test fun slowticker() = check(Source.slowticker)
  @Test fun caret() = check(Source.caret)
  @Test fun scroller() = check(Source.scroller)
  @Test fun animator() = check(Source.animator)
  @Test fun viewanim() = check(Source.viewanim)
  @Test fun sensor() = check(Source.sensor)
  @Test fun overlay() = check(Source.overlay)
  @Test fun popup() = check(Source.popup)
  @Test fun worker() = check(Source.worker)
  @Test fun progress() = check(Source.progress)

  companion object {
    const val LIMIT_MS = 20_000L
  }
}
