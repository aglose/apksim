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

  /** A sleep ends on time while the main thread has a frame loop (a Compose Popup's, an endless animation's). */
  @Test
  fun aSleepEndsDuringAnEndlessFrameLoop() {
    // On the main thread's Choreographer, through a post: on a device the test body is on the instrumentation thread,
    // which has none (and runOnMainSync waits for the main thread to go idle here, which this loop never lets it).
    val frames = java.util.concurrent.atomic.AtomicInteger()
    val running = AtomicBoolean(true)
    val main = Handler(Looper.getMainLooper())
    val loop = object : android.view.Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        frames.incrementAndGet()
        if (running.get()) android.view.Choreographer.getInstance().postFrameCallback(this)
      }
    }
    main.post { android.view.Choreographer.getInstance().postFrameCallback(loop) }
    try {
      val start = System.nanoTime()
      Thread.sleep(200)
      val tookMs = (System.nanoTime() - start) / 1_000_000
      assertTrue("took $tookMs ms", tookMs in 200..3_000)
      assertTrue("frames ${frames.get()}", frames.get() > 0)
    } finally {
      running.set(false)
      main.post { android.view.Choreographer.getInstance().removeFrameCallback(loop) }
    }
  }
}
