package dev.apksim.composeapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a UiAutomator test leans on from the device without saying so, and gets from apksim's UiAutomator engine
 * (`--uia engine`; on apksim the tests that need it run only with `-e uiaEngine true`): accessibility is on while
 * UiAutomation is connected, an action's own accessibility event reaches a waiting filter, a wait's time is time
 * for the app's other threads too, and a tap on a view that is still moving lands on it.
 * The last test only takes time: a screen that never stops animating, looked at twenty times.
 */
@RunWith(AndroidJUnit4::class)
class UiaEngineTest {
  class Screen : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
      val c = inflater.context
      val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
      val status = TextView(c).apply { text = "nothing yet"; contentDescription = "status" }
      column.addView(status)
      var taps = 0
      column.addView(Button(c).apply { text = "Tap me"; isAllCaps = false; setOnClickListener { status.text = "taps ${++taps}" } })
      // A result from another thread: 80 ms of work there, then a post to the main thread.
      column.addView(Button(c).apply {
        text = "Compute"; isAllCaps = false
        setOnClickListener {
          Thread({
            var x = 0.0
            val end = System.nanoTime() + 80_000_000L
            while (System.nanoTime() < end) x += Math.sqrt(x + 1)
            Handler(Looper.getMainLooper()).post { status.text = "computed ${x > 0}" }
          }, "uia-engine-test-worker").start()
        }
      })
      // A view that takes 600 ms to arrive where it can be tapped: it slides across the screen.
      val target = Button(c).apply { text = "Target"; isAllCaps = false; setOnClickListener { status.text = "target hit" } }
      column.addView(Button(c).apply {
        text = "Slide"; isAllCaps = false
        setOnClickListener {
          target.translationX = 500f
          target.animate().translationX(0f).setDuration(600).setInterpolator(LinearInterpolator()).start()
        }
      })
      column.addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      return column
    }
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)
  private val onApksim = Build.FINGERPRINT == "robolectric"
  private val engine = InstrumentationRegistry.getArguments().getString("uiaEngine") == "true"
  private var scenario: FragmentScenario<Screen>? = null

  private fun launch() = launchFragmentInContainer<Screen>(themeResId = android.R.style.Theme_Material_Light_NoActionBar)
    .also { scenario = it }

  @After
  fun close() {
    scenario?.close()
  }

  /** While UiAutomation is connected accessibility is enabled, with no service listed (its own is not among them). */
  @Test
  fun accessibilityIsOnOnceUiAutomationIsUsed() {
    assumeTrue(!onApksim || engine)
    launch()
    assertNotNull(device.findObject(By.text("Tap me")))
    val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    assertTrue("accessibility enabled", manager.isEnabled)
    assertEquals("enabled services", emptyList<AccessibilityServiceInfo>(),
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
  }

  /** A click's TYPE_VIEW_CLICKED, sent by the button, reaches the filter that waits for it, with its text and source. */
  @Test
  fun aFilterGetsTheClicksOwnEvent() {
    assumeTrue(!onApksim || engine)
    launch()
    val button = device.findObject(By.text("Tap me"))
    val event = instrumentation.uiAutomation.executeAndWaitForEvent(
        { button.click() }, { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }, 5_000)
    assertEquals("android.widget.Button", event.className?.toString())
    assertEquals(listOf("Tap me"), event.text.map { it.toString() })
    assertEquals("Tap me", event.source?.text?.toString())
  }

  /**
   * A one-second wait finds what another thread takes 80 ms to work out: the wait's sleeps are time for that thread.
   * (A look with no wait does not find it, on a device either.) With the simulated clock the whole second used to
   * pass in a few real milliseconds.
   */
  @Test
  fun aWaitGivesAWorkerTheTimeItNeeds() {
    assumeTrue(!onApksim || engine)
    launch()
    device.findObject(By.text("Compute")).click()
    assertTrue("computed", device.wait(Until.hasObject(By.text("computed true")), 1_000))
  }

  /** A tap on a view that is still sliding to its place lands on it. */
  @Test
  fun aTapOnAViewStillSlidingInLandsOnIt() {
    launch()
    device.findObject(By.text("Slide")).click()
    device.findObject(By.text("Target")).click()
    assertTrue("target hit", device.wait(Until.hasObject(By.text("target hit")), 5_000))
  }

  /** Twenty looks at a screen that never stops animating (the Shader tab): each finds the tab bar. */
  @Test
  fun looksAtAScreenThatNeverStopsAnimating() {
    ActivityScenario.launch(MainActivity::class.java).use {
      device.wait(Until.findObject(By.text("Shader")), 5_000).click()
      val start = System.nanoTime()
      repeat(20) { assertTrue("look $it", device.hasObject(By.text("Trips"))) }
      android.util.Log.i("UiaEngineTest", "20 looks took ${(System.nanoTime() - start) / 1_000_000} ms")
      // Away from the endless animation before the scenario closes: its close waits for the main thread to go idle.
      device.findObject(By.text("Trips")).click()
      assertTrue(device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("(Italian|Roman) vacation"))), 5_000))
    }
  }
}
