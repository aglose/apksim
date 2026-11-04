package dev.apksim.composeapp

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A test that polls with Thread.sleep (waiting for a request to reach a fake server, say) while the app's main thread
 * has work another thread posted: on a device the main thread runs beside the test's own and the work gets done. On
 * apksim the test body is on the main thread, so the sleep has to let the main queue run.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadSleepTest {
  @Test
  fun workPostedToTheMainThreadRunsWhileTheTestSleeps() {
    val ran = AtomicBoolean(false)
    val main = Handler(Looper.getMainLooper())
    thread { main.post { ran.set(true) } }
    val deadline = System.nanoTime() + 3_000_000_000L
    while (!ran.get() && System.nanoTime() < deadline) Thread.sleep(10)
    assertTrue("the main thread's work did not run while the test slept", ran.get())
  }
}
