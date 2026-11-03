package dev.apksim.composeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI tests, run on a device or by apksim's `--instrument`. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun fabCountsClicks() {
    compose.onNodeWithText("FAB clicks: 0").assertIsDisplayed()
    repeat(3) { compose.onNodeWithContentDescription("Add").performClick() }
    compose.onNodeWithText("FAB clicks: 3").assertIsDisplayed()
  }

  @Test
  fun tripsTabShowsNoteField() {
    compose.onNodeWithText("Trips").performClick()
    compose.onNodeWithText("Trip note").assertIsDisplayed()
  }

  @Test
  fun tripRenameIsSaved() {
    compose.onNodeWithText("Trips").performClick()
    val before = if (compose.onAllNodesWithTextExists("Italian vacation")) "Italian vacation" else "Roman vacation"
    val after = if (before == "Italian vacation") "Roman vacation" else "Italian vacation"
    compose.onNodeWithText(before).performClick()
    compose.onNodeWithText(after).assertIsDisplayed()
  }

  private fun AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(text: String) =
    onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
}

/** Plain Espresso and ActivityScenario, as View-based suites use them. */
@RunWith(AndroidJUnit4::class)
class EspressoTest {
  @Test
  fun scenarioReachesResumed() {
    ActivityScenario.launch(MainActivity::class.java).use { s ->
      assertEquals(Lifecycle.State.RESUMED, s.state)
      onView(isRoot()).check(matches(isDisplayed()))
      s.moveToState(Lifecycle.State.CREATED)
      assertEquals(Lifecycle.State.CREATED, s.state)
    }
  }

  @Test
  fun screenAndArguments() {
    val inst = InstrumentationRegistry.getInstrumentation()
    val m = inst.targetContext.resources.displayMetrics
    // Logged so a run shows which screen the app had (apksim --device, or the emulator's profile).
    android.util.Log.i("EspressoTest", "screen ${m.widthPixels}x${m.heightPixels} density ${m.density} args ${InstrumentationRegistry.getArguments()}")
    assert(m.widthPixels > 0 && m.heightPixels > 0)
  }

  @Test
  fun targetContextIsTheApp() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("dev.apksim.composeapp", ctx.packageName)
  }
}
