package dev.apksim.composeapp

import android.os.Build
import androidx.core.view.GravityCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Espresso acts while a DrawerLayout is still settling (issue 215). The drawer slides in through ViewDragHelper's
 * Scroller, with no animator; under `--realtime-clock` with `--precise-waits on` a tap right after openDrawer reached
 * an item that had not arrived (the same suite passed with `--precise-waits off`). On a device Espresso's idle wait
 * does not return until the drawer has settled, and the click lands. Runs on apksim only with `-e realtimeClock true`.
 */
@RunWith(AndroidJUnit4::class)
class DrawerSettleTest {
  private val onApksim = Build.FINGERPRINT == "robolectric"

  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  @Test
  fun aTapRightAfterOpeningTheDrawerHitsTheItem() {
    assumeTrue(!onApksim || InstrumentationRegistry.getArguments().getString("realtimeClock") == "true")
    ActivityScenario.launch(DrawerActivity::class.java).use { scenario ->
      scenario.onActivity { it.drawer.openDrawer(GravityCompat.START) }
      if (InstrumentationRegistry.getArguments().getString("drawerWait") == "true") Thread.sleep(2000)
      // No isOpen wait: Espresso's own idling has to cover the settle.
      onView(withText("Item 1")).perform(click())
      var clicks = -1
      scenario.onActivity { clicks = it.clicks }
      assertEquals("the item's clicks", 1, clicks)
    }
  }
}
