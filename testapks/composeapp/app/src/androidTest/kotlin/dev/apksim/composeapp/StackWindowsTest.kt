package dev.apksim.composeapp

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A stopped activity's window is hidden, as on a device: UiAutomator finds only the top activity's views and
 * UiAutomation lists only its window (screenshots and failure bundles draw the windows it lists). Before, every
 * activity below the top was still listed and searched.
 */
@RunWith(AndroidJUnit4::class)
class StackWindowsTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)
  private val pkg = instrumentation.targetContext.packageName

  /** The windows UiAutomation lists whose views are this app's. */
  private fun appWindows() = instrumentation.uiAutomation.windows.filter { it.root?.packageName == pkg }

  /** Back on a launcher activity at the root of its task sends the task to the background, stopped. */
  @Test
  fun aTaskSentToTheBackShowsNoWindow() {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, MainActivity::class.java.name)
    ActivityScenario.launch<MainActivity>(intent).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      device.pressBack()
      instrumentation.waitForIdleSync()
      assertEquals(Lifecycle.State.CREATED, scenario.state)
      assertFalse("a stopped activity's views are not found", device.hasObject(By.text("FAB clicks: 0")))
      assertEquals(emptyList<Any>(), appWindows())
    }
  }

  /** Resumed again (ActivityScenario, as from the recents screen), the task's activity shows its window. */
  @Test
  fun aTaskBroughtBackShowsItsWindow() {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, MainActivity::class.java.name)
    ActivityScenario.launch<MainActivity>(intent).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      device.pressBack()
      instrumentation.waitForIdleSync()
      assertEquals(Lifecycle.State.CREATED, scenario.state)
      scenario.moveToState(Lifecycle.State.RESUMED)
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      assertEquals(1, appWindows().size)
    }
  }

  /** Stopped by ActivityScenario, rotated (recreated, still stopped), then resumed: its window is shown. */
  @Test
  fun aStoppedActivityRotatedAndResumedShowsItsWindow() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      try {
        scenario.moveToState(Lifecycle.State.CREATED)
        device.setOrientationLeft()
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
        assertEquals(1, appWindows().size)
      } finally {
        device.setOrientationNatural()
        device.unfreezeRotation()
      }
    }
  }

  /** With `-e startActivities true` (apksim's --start-activities): an activity started over another hides it until it finishes. */
  @Test
  fun onlyTheTopActivitysWindowIsShown() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      scenario.onActivity { it.startActivity(Intent(it, SecondActivity::class.java)) }
      assertNotNull(device.wait(Until.findObject(By.text("Stack 1: 0 taps")), 5_000))
      assertFalse("the stopped activity's views are not found", device.hasObject(By.text("FAB clicks: 0")))
      assertEquals(1, appWindows().size)

      device.findObject(By.text(Pattern.compile("finish", Pattern.CASE_INSENSITIVE))).click()
      assertNotNull("the activity below is shown again", device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      assertEquals(Lifecycle.State.RESUMED, scenario.state)
      assertEquals(1, appWindows().size)
      // It has the window focus again: Espresso looks in the focused window.
      var focused = false
      scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
      assertTrue("the window shown again has the focus", focused)
    }
  }

  /** The top activity finishing after hiding its own window (setVisible(false)) still brings back the one below. */
  @Test
  fun aTopThatHidItselfStillUncoversTheOneBelow() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      var second: android.app.Activity? = null
      scenario.onActivity { it.startActivity(Intent(it, SecondActivity::class.java)) }
      assertNotNull(device.wait(Until.findObject(By.text("Stack 1: 0 taps")), 5_000))
      instrumentation.runOnMainSync {
        second = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
        // As a device's setVisible(false) does (Robolectric's only records it).
        second!!.setVisible(false)
        second!!.window.decorView.visibility = android.view.View.INVISIBLE
        second!!.finish()
      }
      assertNotNull("the activity below is back", device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }
  }

  /** A dialog of an activity that is stopped is hidden with it, and shown again when it comes back. */
  @Test
  fun aStoppedActivitysDialogIsHiddenWithIt() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      scenario.onActivity { android.app.AlertDialog.Builder(it).setMessage("A dialog over main").show() }
      assertNotNull(device.wait(Until.findObject(By.text("A dialog over main")), 5_000))
      scenario.onActivity { it.startActivity(Intent(it, SecondActivity::class.java)) }
      assertNotNull(device.wait(Until.findObject(By.text("Stack 1: 0 taps")), 5_000))
      assertFalse("the stopped activity's dialog is hidden", device.hasObject(By.text("A dialog over main")))
      assertEquals(1, appWindows().size)
      device.findObject(By.text(Pattern.compile("finish", Pattern.CASE_INSENSITIVE))).click()
      assertNotNull("the dialog is back", device.wait(Until.findObject(By.text("A dialog over main")), 5_000))
    }
  }

  /** Whether the scenario's activity has its window shown (not hidden as a stopped activity's is). */
  private fun shown(scenario: ActivityScenario<*>): Boolean {
    var shown = false
    scenario.onActivity { shown = it.window.decorView.isAttachedToWindow && it.window.decorView.visibility == android.view.View.VISIBLE }
    return shown
  }

  /**
   * A floating (dialog-themed) activity leaves the one below visible and paused; an opaque one started over both
   * hides them, and finishing it shows them again. UiAutomation lists only the floating one's window: it is
   * touch-modal, so the window manager counts the whole task as covered and leaves the one below out (issue 97).
   */
  @Test
  fun aFloatingActivityLeavesTheOneBelowVisible() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      scenario.onActivity { it.startActivity(Intent(it, FloatingActivity::class.java)) }
      assertNotNull(device.wait(Until.findObject(By.text("Floating")), 5_000))
      assertEquals(Lifecycle.State.STARTED, scenario.state)
      assertTrue("the one below is shown", shown(scenario))
      assertEquals(1, appWindows().size)
      // A rotation recreates both; the one below stays paused under the floating one, its window under it.
      try {
        device.setOrientationLeft()
        assertNotNull(device.wait(Until.findObject(By.text("Floating")), 5_000))
        assertEquals(Lifecycle.State.STARTED, scenario.state)
        assertTrue("the one below is shown", shown(scenario))
        assertEquals(1, appWindows().size)
        assertTrue("the floating window is the one listed", appWindows().first().root.findAccessibilityNodeInfosByText("Floating").isNotEmpty())
      } finally {
        device.setOrientationNatural()
        device.unfreezeRotation()
      }

      val context = instrumentation.targetContext
      context.startActivity(Intent(context, SecondActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      assertNotNull(device.wait(Until.findObject(By.text("Stack 1: 0 taps")), 5_000))
      assertEquals(Lifecycle.State.CREATED, scenario.state)
      assertFalse("the one below is hidden", shown(scenario))
      assertEquals(1, appWindows().size)

      device.findObject(By.text(Pattern.compile("finish", Pattern.CASE_INSENSITIVE))).click()
      assertNotNull(device.wait(Until.findObject(By.text("Floating")), 5_000))
      assertEquals(Lifecycle.State.STARTED, scenario.state)
      assertTrue("the one below is shown again", shown(scenario))
      assertEquals(1, appWindows().size)
    }
  }

  /**
   * Rotating when only the stopped activity below is recreated (the top one, TripChooserActivity, handles the change
   * itself): the stack keeps its order, so the next activity started goes over the top one, which is stopped.
   */
  @Test
  fun aRotationKeepsTheStackOrder() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      scenario.onActivity { it.startActivity(Intent(it, TripChooserActivity::class.java)) }
      assertNotNull(device.wait(Until.findObject(By.text("Choose a trip")), 5_000))
      try {
        device.setOrientationLeft()
        assertNotNull(device.wait(Until.findObject(By.text("Choose a trip")), 5_000))
        assertEquals(1, appWindows().size)
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, SecondActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Stack 1: 0 taps")), 5_000))
        assertFalse("the activity below is not shown", device.hasObject(By.text("Choose a trip")))
        assertEquals(1, appWindows().size)
        var focused = false
        instrumentation.runOnMainSync {
          focused = appWindows().singleOrNull()?.isFocused == true
        }
        assertTrue("the top window has the focus", focused)
      } finally {
        device.setOrientationNatural()
        device.unfreezeRotation()
      }
    }
  }

  /** Rotating with an activity stopped below the top: it gets the new configuration too, and comes back with it. */
  @Test
  fun aStoppedActivityFollowsARotation() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      scenario.onActivity { it.startActivity(Intent(it, SecondActivity::class.java)) }
      assertNotNull(device.wait(Until.findObject(By.text("Stack 1: 0 taps")), 5_000))
      try {
        device.setOrientationLeft()
        assertEquals("the stopped activity stays stopped", Lifecycle.State.CREATED, scenario.state)
        assertEquals(1, appWindows().size)
        device.findObject(By.text(Pattern.compile("finish", Pattern.CASE_INSENSITIVE))).click()
        // The top bar shows the window size in dp: on its side, wider than tall.
        val size = Pattern.compile("(compact|medium|expanded) (\\d+)×(\\d+)")
        val label = device.wait(Until.findObject(By.text(size)), 5_000).text
        val m = size.matcher(label).also { it.find() }
        assertTrue(label, m.group(2).toInt() > m.group(3).toInt())
        var landscape = false
        scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        assertTrue("the activity below has the new configuration", landscape)
      } finally {
        device.setOrientationNatural()
        device.unfreezeRotation()
      }
    }
  }
}
