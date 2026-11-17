package dev.apksim.composeapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Touch mode and initial focus (issue 424): a phone's window starts in touch mode, where the framework gives no view
 * focus by itself, so the Trips screen's text field is not focused when the screen opens, nor after the activity is
 * stopped and resumed or recreated. apksim once decided "always assign focus" (ViewRootImpl's compatibility flag for
 * apps targeting below API 28) from the process's first window, which was never this app's.
 */
@RunWith(AndroidJUnit4::class)
class TouchModeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val onApksim = Build.FINGERPRINT == "robolectric"

  private fun trips() = Intent(Intent.ACTION_VIEW, Uri.parse("apksim://composeapp/trips"))
    .setClass(instrumentation.targetContext, MainActivity::class.java)

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  @Test
  fun noViewIsFocusedWhenTheScreenOpensOrComesBack() {
    ActivityScenario.launch<MainActivity>(trips()).use { scenario ->
      instrumentation.waitForIdleSync()
      scenario.onActivity {
        assertTrue("the window is in touch mode", it.window.decorView.isInTouchMode)
        assertNull("focus at launch", it.window.decorView.findFocus())
      }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.moveToState(Lifecycle.State.RESUMED)
      instrumentation.waitForIdleSync()
      scenario.onActivity { assertNull("focus after a stop and resume", it.window.decorView.findFocus()) }
      scenario.recreate()
      instrumentation.waitForIdleSync()
      scenario.onActivity { assertNull("focus after a recreation", it.window.decorView.findFocus()) }
    }
  }

  @Test
  fun focusIsNotAlwaysAssignedForThisAppsTarget() {
    assumeTrue("the touchmode command is apksim's", onApksim)
    assumeTrue(instrumentation.targetContext.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.P)
    ActivityScenario.launch<MainActivity>(trips()).use {
      instrumentation.waitForIdleSync()
      val state = shell("apksim touchmode")
      assertTrue(state, state.startsWith("touch mode on"))
      assertFalse(state, state.contains("focus always assigned"))
    }
  }
}
