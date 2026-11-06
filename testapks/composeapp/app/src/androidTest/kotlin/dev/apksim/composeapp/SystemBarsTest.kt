package dev.apksim.composeapp

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System bars as a device has them (apksim's `bars` command, SystemBars.java): a bar the device lacks gives no inset, one
 * it has gives 24 dp, and the app hiding the bars (WindowInsetsController, the legacy flags) takes their insets away.
 * A test run starts each test with no bars, as Robolectric has them, unless --status-bar/--nav-bar say otherwise.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarsTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val bar = Math.round(24 * instrumentation.targetContext.resources.displayMetrics.density)

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  /** The system bar insets the activity's window gets, top (status) and bottom (navigation). */
  private fun insets(scenario: ActivityScenario<SystemBarsActivity>): Pair<Int, Int> {
    instrumentation.waitForIdleSync()
    var r = 0 to 0
    scenario.onActivity {
      val i = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
      r = i.top to i.bottom
      assertEquals("bars: status=${i.top} nav=${i.bottom}", it.label.text.toString())
    }
    return r
  }

  @After
  fun noBars() {
    shell("apksim bars status off nav off")
  }

  @Test
  fun barsTheDeviceLacksHaveNoInsets() {
    shell("apksim bars status off nav off")
    ActivityScenario.launch(SystemBarsActivity::class.java).use { scenario ->
      assertEquals(0 to 0, insets(scenario))
      assertEquals("status on (shown), nav gesture (shown)", shell("apksim bars status on nav gesture").trim())
      assertEquals(bar to bar, insets(scenario))
      shell("apksim bars nav off")
      assertEquals(bar to 0, insets(scenario))
    }
  }

  @Test
  fun hidingSystemBarsTakesTheirInsetsAway() {
    shell("apksim bars status on nav gesture")
    ActivityScenario.launch(SystemBarsActivity::class.java).use { scenario ->
      assertEquals(bar to bar, insets(scenario))
      scenario.onActivity { it.window.insetsController!!.hide(WindowInsets.Type.systemBars()) }
      assertEquals(0 to 0, insets(scenario))
      assertEquals("status on (hidden by the app), nav gesture (hidden by the app)", shell("apksim bars").trim())
      scenario.onActivity { it.window.insetsController!!.show(WindowInsets.Type.statusBars()) }
      assertEquals(bar to 0, insets(scenario))
      scenario.onActivity { it.window.insetsController!!.show(WindowInsets.Type.navigationBars()) }
      assertEquals(bar to bar, insets(scenario))
    }
  }

  @Suppress("DEPRECATION")
  @Test
  fun legacyFullscreenFlagsHideTheBars() {
    shell("apksim bars status on nav gesture")
    ActivityScenario.launch(SystemBarsActivity::class.java).use { scenario ->
      scenario.onActivity {
        it.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      }
      assertEquals(0 to 0, insets(scenario))
      scenario.onActivity { it.window.decorView.systemUiVisibility = 0 }
      assertEquals(bar to bar, insets(scenario))
      scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) }
      assertEquals(0 to bar, insets(scenario))
      assertTrue(shell("apksim bars").contains("status on (hidden by the app)"))
    }
  }
}
