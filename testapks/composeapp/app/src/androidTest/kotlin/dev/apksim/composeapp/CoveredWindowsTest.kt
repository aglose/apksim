package dev.apksim.composeapp

import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility reports only the windows not covered by touch-modal or full-screen windows above them (issue 97), as
 * the window manager computes them for UiAutomation.getWindows: behind a full-screen dialog, or a small one that is
 * touch-modal (a Dialog as made), UiAutomator finds nothing of the activity; behind a FLAG_NOT_TOUCH_MODAL one it does.
 */
@RunWith(AndroidJUnit4::class)
class CoveredWindowsTest {
  private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  /** The app's windows UiAutomation reports (a device also lists its system bars). */
  private val appWindows
    get() = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

  private fun launch(open: String?): ActivityScenario<DialogsActivity> {
    val intent = Intent(ApplicationProvider.getApplicationContext(), DialogsActivity::class.java)
    if (open != null) intent.putExtra("open", open)
    return ActivityScenario.launch(intent)
  }

  @Test
  fun withNoDialogTheActivityIsReported() {
    launch(null).use {
      assertNotNull(device.wait(Until.findObject(By.text("Behind the dialogs")), 5_000))
      assertEquals(1, appWindows.size)
    }
  }

  @Test
  fun aFullScreenDialogHidesTheActivity() {
    launch("full").use {
      assertNotNull("the dialog", device.wait(Until.findObject(By.text("Full-screen dialog open")), 5_000))
      assertNull("text behind the dialog", device.findObject(By.text("Behind the dialogs")))
      assertEquals("windows: $appWindows", 1, appWindows.size)
      device.findObject(By.text("Close")).click()
      assertNotNull("the activity again", device.wait(Until.findObject(By.text("Behind the dialogs")), 5_000))
    }
  }

  /**
   * A popup the activity opened before the dialog is under the dialog, though its window type (a panel) is higher:
   * windows stack in the order they were added, as Host draws them and a device shows them.
   */
  @Test
  fun aPopupOpenedBeforeAFullScreenDialogIsCoveredToo() {
    launch("popupfull").use {
      assertNotNull("the dialog", device.wait(Until.findObject(By.text("Full-screen dialog open")), 5_000))
      assertNull("the popup under the dialog", device.findObject(By.text("Popup under the dialog")))
      assertEquals("windows: $appWindows", 1, appWindows.size)
      device.findObject(By.text("Close")).click()
      assertNotNull("the popup again", device.wait(Until.findObject(By.text("Popup under the dialog")), 5_000))
    }
  }

  @Test
  fun aSmallModalDialogHidesTheActivity() {
    launch("modal").use {
      assertNotNull("the dialog", device.wait(Until.findObject(By.text("Small modal dialog open")), 5_000))
      assertNull("text behind the dialog", device.findObject(By.text("Behind the dialogs")))
      assertEquals("windows: $appWindows", 1, appWindows.size)
    }
  }

  @Test
  fun aNonModalDialogLeavesTheActivityReported() {
    launch("nonmodal").use {
      assertNotNull("the dialog", device.wait(Until.findObject(By.text("Small non-modal dialog open")), 5_000))
      assertEquals("windows: $appWindows", 2, appWindows.size)
      val behind = device.findObject(By.text("Tap behind"))
      assertNotNull("text behind the dialog", behind)
      behind.click()
      assertTrue("the tap reached the activity", device.wait(Until.hasObject(By.text("behind taps: 1")), 5_000))
    }
  }

  /**
   * A centred dialog's nodes report where they are on the screen, inside the dialog's window, so a click at their
   * bounds lands on them. They used to report their place in the window (the dialog at the top left).
   */
  @Test
  fun aCentredDialogsNodesAreOnTheScreenWhereItIs() {
    launch("modal").use {
      val count = device.wait(Until.findObject(By.text("Count")), 5_000)
      assertNotNull("the dialog's button", count)
      val window = android.graphics.Rect().also { appWindows.single().getBoundsInScreen(it) }
      assertTrue("the dialog is centred: $window", window.left > 0 && window.top > 0)
      val bounds = count.visibleBounds
      assertTrue("the button $bounds is inside the dialog $window", window.contains(bounds))
      count.click()
      assertTrue("the click reached the button", device.wait(Until.hasObject(By.text("dialog taps: 1")), 5_000))
    }
  }

  /** The same for a Compose Dialog, whose nodes take their screen position from the window's. */
  @Test
  fun aComposeDialogsButtonTakesAClick() {
    launch("compose").use {
      val count = device.wait(Until.findObject(By.text("Compose count")), 5_000)
      assertNotNull("the Compose dialog's button", count)
      val window = android.graphics.Rect().also { appWindows.single().getBoundsInScreen(it) }
      assertTrue("the button ${count.visibleBounds} is inside the dialog $window", window.contains(count.visibleBounds))
      count.click()
      assertTrue("the click reached the button", device.wait(Until.hasObject(By.text("compose dialog taps: 1")), 5_000))
    }
  }
}
