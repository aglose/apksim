package dev.apksim.composeapp.conformance

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A test waiting on a CountDownLatch for work on the main thread, as device tests do: on a device the test thread
 * waits while the main thread runs. In an apksim test run the body is on the main thread, so the wait has to let the
 * main queue run (docs/conformance.md). Checked on an API 36 emulator first.
 */
@RunWith(AndroidJUnit4::class)
class LatchConformanceTest {
  private val main = Handler(Looper.getMainLooper())

  /** A post to the main thread runs while the test waits for it. */
  @Test
  fun aTimedAwaitSeesAPostToTheMainThread() {
    val done = CountDownLatch(1)
    main.post { done.countDown() }
    assertTrue(done.await(5, TimeUnit.SECONDS))
  }

  /** A delayed post comes due while the test waits, about when it would on a device. */
  @Test
  fun aTimedAwaitSeesADelayedPost() {
    val done = CountDownLatch(1)
    val start = System.nanoTime()
    main.postDelayed({ done.countDown() }, 200)
    assertTrue(done.await(5, TimeUnit.SECONDS))
    val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    assertTrue("took $tookMs ms", tookMs < 4_000)
  }

  /** await() with no timeout returns once the main thread counts down. */
  @Test
  fun anUntimedAwaitSeesAPostToTheMainThread() {
    val done = CountDownLatch(2)
    main.post { done.countDown(); main.post { done.countDown() } }
    done.await()
    assertEquals(0L, done.count)
  }

  /** A latch nobody counts down times out after about its timeout, the main queue running meanwhile. */
  @Test
  fun aLatchNobodyCountsDownTimesOut() {
    val never = CountDownLatch(1)
    val ran = CountDownLatch(1)
    main.post { ran.countDown() }
    val start = System.nanoTime()
    assertFalse(never.await(300, TimeUnit.MILLISECONDS))
    val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    assertTrue("took $tookMs ms", tookMs in 250..3_000)
    assertEquals(0L, ran.count)
  }

  /** A worker thread counting down ends the wait, as it always did. */
  @Test
  fun aWorkerCountingDownEndsTheWait() {
    val done = CountDownLatch(1)
    Thread { Thread.sleep(50); done.countDown() }.start()
    assertTrue(done.await(5, TimeUnit.SECONDS))
  }

  /** An interrupt ends the wait with InterruptedException, as on a device. */
  @Test
  fun anInterruptEndsTheWait() {
    val never = CountDownLatch(1)
    Thread.currentThread().interrupt()
    val thrown = runCatching { never.await(5, TimeUnit.SECONDS) }.exceptionOrNull()
    assertTrue("got $thrown", thrown is InterruptedException)
    assertFalse(Thread.interrupted())
  }

  /** FullyDrawnReporter's listener, awaited the usual way: its report comes from a frame after the last reporter. */
  @Test
  fun fullyDrawnReporterListenerAwaited() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      val drawn = CountDownLatch(1)
      scenario.onActivity { a ->
        a.fullyDrawnReporter.addReporter()
        a.fullyDrawnReporter.addOnReportDrawnListener { drawn.countDown() }
        a.fullyDrawnReporter.removeReporter()
      }
      assertTrue(drawn.await(5, TimeUnit.SECONDS))
      scenario.onActivity { a -> assertTrue(a.fullyDrawnReporter.isFullyDrawnReported) }
    }
  }
}
