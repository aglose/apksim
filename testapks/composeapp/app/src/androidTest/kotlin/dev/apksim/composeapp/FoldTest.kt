package dev.apksim.composeapp

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.startsWith
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A folding device's fold as Jetpack WindowManager reports it (apksim's androidx.window.extensions stand-in), on
 * apksim://fold. Run with `--device pixel-10-pro-fold [--fold closed]` and `-e fold open|closed`; without the argument
 * only the first test runs, which holds on any device that does not fold. Another folding device: its name in
 * `-e foldDevice` too (galaxy-z-fold7, galaxy-z-flip7, galaxy-z-trifold), for its folds and window sizes.
 */
@RunWith(AndroidJUnit4::class)
class FoldTest {
  private val expected = InstrumentationRegistry.getArguments().getString("fold")

  /** A device's folds open (each "ORIENTATION BOUNDS"), and its window open and closed, as FoldActivity shows them. */
  private class Shape(val folds: List<String>, val open: String, val closed: String) {
    fun fold(state: String) = "fold: " + folds.joinToString(" | ") { "$state $it" }
  }

  private val shapes = mapOf(
    "pixel-10-pro-fold" to Shape(listOf("vertical 1038,0-1038,2152"), "852x883", "443x970"),
    "galaxy-z-fold7" to Shape(listOf("vertical 984,0-984,2184"), "750x832", "411x960"),
    "galaxy-z-flip7" to Shape(listOf("horizontal 0,1260-1078,1260"), "411x960", "361x399"),
    "galaxy-z-trifold" to Shape(listOf("vertical 720,0-720,1584", "vertical 1440,0-1440,1584"), "1080x792", "411x960"),
  )
  private val shape = shapes.getValue(InstrumentationRegistry.getArguments().getString("foldDevice") ?: "pixel-10-pro-fold")
  private val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, FoldActivity::class.java)

  private fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()).trim() }
  }

  @Test
  fun aDeviceThatDoesNotFoldReportsNoFold() {
    assumeTrue("a folding device: see the other tests", expected == null)
    ActivityScenario.launch<FoldActivity>(intent).use {
      onView(withText("fold: none")).check(matches(isDisplayed()))
      onView(withText("hinge: no sensor")).check(matches(isDisplayed()))
    }
  }

  @Test
  fun openItReportsAFlatVerticalFoldAcrossTheMiddle() {
    assumeTrue("run with --device pixel-10-pro-fold and -e fold open", expected == "open")
    ActivityScenario.launch<FoldActivity>(intent).use {
      onView(withText(shape.fold("flat"))).check(matches(isDisplayed()))
      onView(withText("window: ${shape.open} dp")).check(matches(isDisplayed()))
      onView(withText("hinge: 180")).check(matches(isDisplayed()))
    }
  }

  @Test
  fun halfOpenReachesARunningActivityAndTheNextTestStartsOpenAgain() {
    assumeTrue("run with --device pixel-10-pro-fold and -e fold open", expected == "open")
    ActivityScenario.launch<FoldActivity>(intent).use {
      onView(withText(startsWith("fold: flat"))).check(matches(isDisplayed()))
      assumeTrue("apksim's fold command", shell("apksim fold half") == "ok")
      onView(withText(shape.fold("half-opened"))).check(matches(isDisplayed()))
      onView(withText("layouts: 2")).check(matches(isDisplayed()))
      onView(withText("hinge: 90")).check(matches(isDisplayed()))
      // An angle inside the half-open range moves the hinge sensor alone: the posture, so the layout, is the same.
      assumeTrue(shell("apksim fold angle 120") == "ok")
      onView(withText("hinge: 120")).check(matches(isDisplayed()))
      onView(withText("layouts: 2")).check(matches(isDisplayed()))
      assumeTrue(shell("apksim fold angle 170") == "ok")
      onView(withText(shape.fold("flat"))).check(matches(isDisplayed()))
    }
  }

  @Test
  fun closedTheCoverDisplayHasNoFold() {
    assumeTrue("run with --device pixel-10-pro-fold --fold closed and -e fold closed", expected == "closed")
    ActivityScenario.launch<FoldActivity>(intent).use {
      onView(withText("fold: none")).check(matches(isDisplayed()))
      onView(withText("window: ${shape.closed} dp")).check(matches(isDisplayed()))
      onView(withText("hinge: 0")).check(matches(isDisplayed()))
    }
  }
}
