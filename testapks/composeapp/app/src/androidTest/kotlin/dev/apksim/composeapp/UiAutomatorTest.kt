package dev.apksim.composeapp

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UiAutomator, as Macrobenchmark journeys and device-level E2E suites drive an app: no Compose rule, only what the
 * accessibility tree shows (apksim's ShadowSimUiAutomation on apksim, the system's UiAutomation on a device).
 */
@RunWith(AndroidJUnit4::class)
class UiAutomatorTest {
  private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  @Test
  fun findsByTextAndClicksByDescription() {
    ActivityScenario.launch(MainActivity::class.java).use {
      assertNotNull(device.wait(Until.findObject(By.text("FAB clicks: 0")), 5_000))
      device.findObject(By.desc("Add")).click()
      device.findObject(By.desc("Add")).click()
      assertTrue(device.wait(Until.hasObject(By.text("FAB clicks: 2")), 5_000))
    }
  }

  @Test
  fun findsByResourceId() {
    ActivityScenario.launch(MainActivity::class.java).use {
      val fab = device.wait(Until.findObject(By.res("fab")), 5_000)
      assertNotNull(fab)
      assertTrue(fab.isClickable)
      assertTrue(fab.visibleBounds.width() > 0)
    }
  }

  @Test
  fun opensATabAndReadsTheList() {
    ActivityScenario.launch(MainActivity::class.java).use {
      device.wait(Until.findObject(By.text("Trips")), 5_000).click()
      assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(Italian|Roman) vacation"))), 5_000))
    }
  }

  /**
   * UiDevice.swipe (and drag, UiObject.swipe*, UiScrollable) waits for a Choreographer frame after every step: it
   * posts to the main looper and blocks on a latch. A test body runs on the main thread here, where that wait used to
   * time out after 5 s a step ("Timed out waiting for VSync frame."): 20 steps took 100 s.
   */
  @Test
  fun swipeScrollsTheListWithoutWaitingForFrames() {
    ActivityScenario.launch(MainActivity::class.java).use {
      val item = Pattern.compile("Item (\\d+)")
      assertNotNull(device.wait(Until.findObject(By.text("Item 1")), 5_000))
      val start = System.nanoTime()
      val x = device.displayWidth / 2
      assertTrue(device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 4, 20))
      device.waitForIdle()
      val ms = (System.nanoTime() - start) / 1_000_000
      assertTrue("swipe took $ms ms", ms < 5_000)
      // The list moved: the first item on screen is further down it. With --realtime-clock the list may still be
      // settling, so a row matched by text can have none when read again: skip it.
      val first = device.findObjects(By.text(item)).mapNotNull { it.text }
        .minOf { item.matcher(it).also { m -> m.find() }.group(1).toInt() }
      assertTrue("first item on screen: $first", first > 3)
    }
  }

  @Test
  fun rotationTurnsTheDisplayAndTheApp() {
    ActivityScenario.launch(MainActivity::class.java).use {
      val size = Pattern.compile("(compact|medium|expanded) (\\d+)×(\\d+)")
      assertNotNull(device.wait(Until.findObject(By.text(size)), 5_000))
      try {
        device.setOrientationLeft()
        assertTrue("display ${device.displayWidth}x${device.displayHeight}", device.displayWidth > device.displayHeight)
        // The top bar shows the window size class and size in dp: on its side, wider than tall.
        val label = device.wait(Until.findObject(By.text(size)), 5_000).text
        val m = size.matcher(label).also { it.find() }
        assertTrue(label, m.group(2).toInt() > m.group(3).toInt())
        device.setOrientationNatural()
        assertTrue(device.displayWidth < device.displayHeight)
        assertEquals(0, device.displayRotation)
      } finally {
        device.setOrientationNatural()
        device.unfreezeRotation()
      }
    }
  }
}
