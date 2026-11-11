package dev.apksim.composeapp.conformance

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Configuration changes as a device makes them, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class RotationConformanceTest {
  /**
   * A rotation while an activity is stopped does not pause it again: a device pauses only a resumed activity, so
   * ActivityLifecycleCallbacks hear one onActivityPaused for it. (A second one left AndroidX ProcessLifecycleOwner's
   * resumed count at -1 for the rest of the process.)
   */
  @Test
  fun aRotationWhileStoppedDoesNotPauseAgain() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val pauses = IdentityHashMap<Activity, Int>()
    val counter = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityPaused(activity: Activity) { pauses[activity] = (pauses[activity] ?: 0) + 1 }
      override fun onActivityCreated(activity: Activity, state: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    }
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var first: Activity
      scenario.onActivity { a -> first = a; a.application.registerActivityLifecycleCallbacks(counter) }
      try {
        scenario.moveToState(Lifecycle.State.CREATED)
        device.setOrientationLeft()
        instrumentation.waitForIdleSync()
        assertEquals(1, pauses[first])
      } finally {
        device.setOrientationNatural()
        device.unfreezeRotation()
        scenario.onActivity { a -> a.application.unregisterActivityLifecycleCallbacks(counter) }
      }
    }
  }
}
