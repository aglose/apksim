package dev.apksim.composeapp.conformance

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
  /** A frame loop on the main thread (a Compose Popup's, an endless animation's) does not keep a worker's countDown from ending the wait. */
  @Test
  fun aWorkerEndsTheWaitDuringAnEndlessFrameLoop() {
    val loop = FrameLoop().apply { start() }
    try {
      val done = CountDownLatch(1)
      Thread { Thread.sleep(50); done.countDown() }.start()
      val start = System.nanoTime()
      assertTrue("opened", done.await(5, TimeUnit.SECONDS))
      val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue("took $tookMs ms", tookMs < 3_000)
      assertTrue("frames ${loop.frames.get()}", loop.frames.get() > 0)
    } finally {
      loop.stop()
    }
  }

  /** Nor a timed wait from timing out on time. */
  @Test
  fun aWaitTimesOutDuringAnEndlessFrameLoop() {
    val loop = FrameLoop().apply { start() }
    try {
      val start = System.nanoTime()
      assertFalse(CountDownLatch(1).await(300, TimeUnit.MILLISECONDS))
      val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue("took $tookMs ms", tookMs in 250..3_000)
      assertTrue("frames ${loop.frames.get()}", loop.frames.get() > 0)
    } finally {
      loop.stop()
    }
  }

  /** Nor a Runnable on the main thread that posts itself again each time it runs. */
  @Test
  fun aWorkerEndsTheWaitWhileARunnableRepostsItself() {
    val runs = AtomicInteger()
    val running = AtomicBoolean(true)
    val spin = object : Runnable {
      override fun run() { runs.incrementAndGet(); if (running.get()) main.post(this) }
    }
    main.post(spin)
    try {
      val done = CountDownLatch(1)
      Thread { Thread.sleep(50); done.countDown() }.start()
      val start = System.nanoTime()
      assertTrue("opened", done.await(5, TimeUnit.SECONDS))
      val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue("took $tookMs ms", tookMs < 3_000)
      assertTrue("runs ${runs.get()}", runs.get() > 0)
    } finally {
      running.set(false)
      main.removeCallbacks(spin)
    }
  }

  /**
   * Asks for every next frame on the main thread's Choreographer, as an endless animation does. Started and stopped
   * with posts to the main thread (on a device the test body is on the instrumentation thread, which has no
   * Choreographer; not runOnMainSync, which waits for the main thread to go idle here: docs/conformance.md).
   */
  private inner class FrameLoop : android.view.Choreographer.FrameCallback {
    val frames = AtomicInteger()
    @Volatile private var running = false
    fun start() { running = true; main.post { android.view.Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; main.post { android.view.Choreographer.getInstance().removeFrameCallback(this) } }
    override fun doFrame(frameTimeNanos: Long) {
      frames.incrementAndGet()
      if (running) android.view.Choreographer.getInstance().postFrameCallback(this)
    }
  }
}
