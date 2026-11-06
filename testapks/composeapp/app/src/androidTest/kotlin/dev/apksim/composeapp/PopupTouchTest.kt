package dev.apksim.composeapp

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A slider's value tooltip is a Popup that is not focusable and stays up: a tap outside it goes to the window behind
 * (InputDispatcher's touch-modal rule), on a device and here. Running instances route touches in Host, test runs
 * through UiAutomation's injection; scripts/ci-smoke.sh checks the first, this the second.
 */
@RunWith(AndroidJUnit4::class)
class PopupTouchTest {
  private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  @Test
  fun aTapOutsideANonFocusablePopupReachesTheWindowBehind() {
    ActivityScenario.launch(PopupTraceActivity::class.java).use {
      val above = device.wait(Until.findObject(By.textStartsWith("trace: ")), 5_000).visibleBounds
      val below = device.findObject(By.text("tooltip: hidden")).visibleBounds
      val y = (above.bottom + below.top) / 2  // the slider sits between the two
      device.swipe(device.displayWidth / 4, y, device.displayWidth / 2, y, 20)
      assertNotNull("the tooltip popup", device.wait(Until.findObject(By.textStartsWith("value ")), 5_000))
      assertTrue(device.wait(Until.hasObject(By.text("tooltip: shown")), 5_000))
      device.findObject(By.text("Tap me")).click()
      assertTrue("the tap reached the activity", device.wait(Until.hasObject(By.text("taps: 1")), 5_000))
      assertTrue("the tap closed the tooltip", device.wait(Until.hasObject(By.text("tooltip: hidden")), 5_000))
    }
  }
}
