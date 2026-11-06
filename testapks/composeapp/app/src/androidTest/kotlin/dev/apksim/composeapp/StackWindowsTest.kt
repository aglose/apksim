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
    }
  }
}
