package dev.apksim.composeapp

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * A slider's value tooltip is a Popup that is not focusable and stays up: a tap outside it goes to the window behind
 * (InputDispatcher's touch-modal rule), on a device and here. Running instances route touches in Host, test runs
 * through UiAutomation's injection; scripts/ci-smoke.sh checks the first, this the second.
 */
@RunWith(AndroidJUnit4::class)
class PopupTouchTest {
  private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  // If the tap stops landing, the tooltip stays open and ActivityScenario.close() waits for ever (an open Popup never
  // lets the main looper idle in a test run): the watchdog then reports the failure rather than hanging the run.
  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  @Test
  fun aTapOutsideANonFocusablePopupReachesTheWindowBehind() {
    ActivityScenario.launch(PopupTraceActivity::class.java).use {
      val above = device.wait(Until.findObject(By.textStartsWith("trace: ")), 5_000).visibleBounds
      val below = device.findObject(By.text("tooltip: hidden")).visibleBounds
      val y = (above.bottom + below.top) / 2  // the slider sits between the two
      drag(device.displayWidth / 4f, y.toFloat(), device.displayWidth / 2f)
      assertNotNull("the tooltip popup", device.wait(Until.findObject(By.textStartsWith("value ")), 5_000))
      assertTrue(device.wait(Until.hasObject(By.text("tooltip: shown")), 5_000))
      device.findObject(By.text("Tap me")).click()
      assertTrue("the tap reached the activity", device.wait(Until.hasObject(By.text("taps: 1")), 5_000))
      assertTrue("the tap closed the tooltip", device.wait(Until.hasObject(By.text("tooltip: hidden")), 5_000))
    }
  }

  /**
   * A horizontal drag injected through UiAutomation, as UiDevice.swipe does, without its wait for a frame after each
   * step: that posts to the main thread and waits up to 5 s for it, and in a test run the test is the main thread.
   */
  private fun drag(fromX: Float, y: Float, toX: Float) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val down = SystemClock.uptimeMillis()
    val finger = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
    fun send(action: Int, x: Float) {
      val at = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
      val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, finger, at, 0, 0, 1f, 1f, 0, 0,
          InputDevice.SOURCE_TOUCHSCREEN, 0)
      automation.injectInputEvent(e, true)
      e.recycle()
      SystemClock.sleep(8)  // a frame's worth between events, as a finger moves
    }
    send(MotionEvent.ACTION_DOWN, fromX)
    for (i in 1..10) send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 10)
    send(MotionEvent.ACTION_UP, toX)
  }
}
