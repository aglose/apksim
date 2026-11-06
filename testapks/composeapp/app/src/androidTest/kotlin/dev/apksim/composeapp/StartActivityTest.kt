package dev.apksim.composeapp

import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With `-e startActivities true` (apksim's --start-activities): an activity the app starts comes up, as on a device,
 * and the one that started it is stopped behind it.
 */
@RunWith(AndroidJUnit4::class)
class StartActivityTest {
  @Test
  fun anActivityTheAppStartsComesUp() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    SecondActivity.createdWith = null
    SecondActivity.resumed = false
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { it.startActivity(Intent(it, SecondActivity::class.java).putExtra("from", "main")) }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals("main", SecondActivity.createdWith)
      assertTrue(SecondActivity.resumed)
      assertEquals(Lifecycle.State.CREATED, scenario.state)
      // As ActivityThread on stop: the stopped activity's window is hidden (UiAutomator and drawing skip it).
      scenario.onActivity { assertEquals(View.INVISIBLE, it.window.decorView.visibility) }
      // Once the one on top finishes, it is shown again and its window has the focus (Espresso looks for it).
      InstrumentationRegistry.getInstrumentation().runOnMainSync { SecondActivity.current!!.finish() }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(Lifecycle.State.RESUMED, scenario.state)
      scenario.onActivity {
        assertEquals(View.VISIBLE, it.window.decorView.visibility)
        assertTrue("the window has the focus", it.window.decorView.hasWindowFocus())
      }
    }
  }

  /** NEW_TASK | CLEAR_TASK (an app restarting itself): a new instance comes up and the old one is destroyed. */
  @Test
  fun aClearTaskStartRecreatesTheActivity() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("startActivities") == "true")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var first: MainActivity
      scenario.onActivity {
        first = it
        it.startActivity(Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
      }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertTrue("the first instance is destroyed", first.isDestroyed)
      // The lifecycle monitor answers on the main thread only; a device runs this body on the instrumentation's.
      var resumed: Collection<android.app.Activity> = emptyList()
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).toList()
      }
      assertTrue("a new MainActivity is resumed: $resumed", resumed.any { it is MainActivity && it !== first })
    }
  }
}
