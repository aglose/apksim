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
import kotlinx.coroutines.launch
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

/** Code shapes apksim's DEX translator has got wrong before (see [SyncCache]). */
@RunWith(AndroidJUnit4::class)
class TranslatorTest {
  @Test
  fun anIntReturnedBesideASynchronizedBlockStaysAnInt() {
    val c = SyncCache()
    assertEquals(0, c.lookup(4)) // empty cache: the loop must not run
    assertEquals(1, c.lookup(5))
    assertEquals(0, c.lookup(4)) // cached
    assertEquals(0, c.lookup(3)) // a newer entry that is 0 answers for it
    assertEquals(2, c.computed())
  }
}

/**
 * Compose animations under the test clock run at their own pace, as on a device: with autoAdvance off, a state
 * change made in runOnIdle reaches the composition (the main looper runs beside the test, Robolectric's
 * INSTRUMENTATION_TEST mode) and the spring it starts is part of the way after 48 ms.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AnimationClockTest {
  @Test
  fun aSpringStartedInAnEffectTakesItsTime() = androidx.compose.ui.test.runComposeUiTest {
    var value = 1f
    val go = androidx.compose.runtime.mutableStateOf(false)
    mainClock.autoAdvance = false
    setContent {
      androidx.compose.runtime.LaunchedEffect(go.value) {
        if (go.value) {
          androidx.compose.animation.core.animate(1f, 0f, animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { v, _ -> value = v }
        }
      }
    }
    mainClock.advanceTimeByFrame()
    runOnIdle { go.value = true }
    mainClock.advanceTimeByFrame()
    mainClock.advanceTimeBy(48)
    val part = runOnIdle { value }
    assert(part > 0f && part < 1f) { "after 48 ms the spring was at $part" }
    mainClock.advanceTimeBy(2_000)
    assertEquals(0f, runOnIdle { value })
  }

  /** The same spring launched from a callback, in the composition's own scope (a back gesture's cancel does this). */
  @Test
  fun aSpringLaunchedFromACallbackTakesItsTime() = androidx.compose.ui.test.v2.runComposeUiTest {
    var value = 1f
    lateinit var scope: kotlinx.coroutines.CoroutineScope
    mainClock.autoAdvance = false
    setContent { scope = androidx.compose.runtime.rememberCoroutineScope() }
    mainClock.advanceTimeByFrame()
    runOnIdle {
      scope.launch {
        androidx.compose.animation.core.animate(1f, 0f, animationSpec = androidx.compose.animation.core.spring(
          dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { v, _ -> value = v }
      }
    }
    assertEquals(1f, runOnIdle { value })
    mainClock.advanceTimeBy(48)
    val part = runOnIdle { value }
    android.util.Log.i("AnimationClockTest", "callback spring after 48 ms: $part")
    assert(part > 0f && part < 1f) { "after 48 ms the spring was at $part" }
    mainClock.advanceTimeBy(2_000)
    assertEquals(0f, runOnIdle { value })
  }
}
