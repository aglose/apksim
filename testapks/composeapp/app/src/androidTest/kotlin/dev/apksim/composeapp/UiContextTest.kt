package dev.apksim.composeapp

import android.os.StrictMode
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Views made with an Activity (or a wrapper over it) do not trip StrictMode's incorrect-context check, as on a device
 * where its base is an activity-type ContextImpl, while the Application stays non-UI (issue 341). Only the StrictMode
 * check is covered: Context.isUiContext() on the activity itself still answers false here.
 */
@RunWith(AndroidJUnit4::class)
class UiContextTest {
  @Test
  fun theApplicationContextStaysNonUi() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertFalse("a4", activity.applicationContext.isUiContext)
        assertFalse("a5", InstrumentationRegistry.getInstrumentation().targetContext.applicationContext.isUiContext)
      }
    }
  }

  @Test
  fun viewsMadeWithAnActivityPassStrictModeWithPenaltyDeath() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val old = StrictMode.getVmPolicy()
        try {
          StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectIncorrectContextUse().penaltyDeath().build())
          View(activity)
          ViewConfiguration.get(ContextThemeWrapper(activity, 0))
        } finally {
          StrictMode.setVmPolicy(old)
        }
      }
    }
  }
}
