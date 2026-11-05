package dev.apksim.composeapp

import android.content.Context
import android.view.View
import android.widget.OverScroller
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The window's views are drawn after a traversal, as on a device, so a move driven from View.computeScroll (a
 * Scroller's, as a drawer's or a list's fling) runs to its end.
 */
@RunWith(AndroidJUnit4::class)
class WindowDrawTest {
  /** Scrolls itself 2,000 px down over 250 ms, from computeScroll, as framework and AndroidX views do. */
  private class Slider(context: Context) : View(context) {
    private val scroller = OverScroller(context)

    fun start() {
      scroller.startScroll(0, 0, 0, 2000, 250)
      postInvalidateOnAnimation()
    }

    override fun computeScroll() {
      if (scroller.computeScrollOffset()) {
        scrollTo(scroller.currX, scroller.currY)
        postInvalidateOnAnimation()
      }
    }
  }

  @Test
  fun aScrollerDrivenMoveRunsToItsEnd() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var slider: Slider
      scenario.onActivity {
        slider = Slider(it)
        it.setContentView(slider)
      }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      scenario.onActivity { slider.start() }
      val deadline = System.currentTimeMillis() + 2_000
      while (slider.scrollY != 2000 && System.currentTimeMillis() < deadline) {
        Thread.sleep(50)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      }
      assertEquals(2000, slider.scrollY)
    }
  }
}
