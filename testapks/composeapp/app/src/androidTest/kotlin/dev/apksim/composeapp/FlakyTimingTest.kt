package dev.apksim.composeapp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A test that depends on timing, for `scripts/flake-hunt.py` (docs/flake-hunt.md): it posts a task 2 s ahead, waits 2 s
 * on the uptime clock and expects the task to have run in under 1 s of real time. That holds on a simulated clock that
 * jumps over idle time (a test run's default) and fails when the clock follows real time (`--realtime-clock`, or a
 * device), as a suite written against one clock fails on the other. Its order in the run does not matter. Only with
 * `-e flakyOnPurpose true`: otherwise skipped.
 */
@RunWith(AndroidJUnit4::class)
class FlakyTimingTest {
  @Test
  fun aDelayedTaskRunsWithoutWaiting() {
    flakyOnPurpose()
    val ran = AtomicBoolean()
    Handler(Looper.getMainLooper()).postDelayed({ ran.set(true) }, 2_000)
    val start = System.nanoTime()
    SystemClock.sleep(2_000)
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    val ms = (System.nanoTime() - start) / 1_000_000
    assertTrue("the task ran: ${ran.get()}, after $ms ms of real time (expected under 1000)", ran.get() && ms < 1_000)
  }
}
