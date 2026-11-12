package dev.apksim.composeapp.conformance

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A ValueAnimator set to repeat INFINITE times repeats for ever and says so, as its documentation has it. Robolectric
 * turns INFINITE into 1; apksim honours it where frames come at vsyncs (running instances, `--realtime-clock`). Default
 * test runs keep Robolectric's cap, since their unpaused Choreographer runs every frame at once and an endless animator
 * would never let the main thread go idle, so on apksim this runs only with `-e realtimeClock true`.
 * Checked on an API 36 emulator first.
 */
@RunWith(AndroidJUnit4::class)
class ValueAnimatorConformanceTest {
  private val main = Handler(Looper.getMainLooper())
  private val animator = AtomicReference<ValueAnimator>()

  @Before
  fun onlyWhereFramesComeAtVsyncs() {
    val onApksim = Build.FINGERPRINT == "robolectric"
    assumeTrue(!onApksim || InstrumentationRegistry.getArguments().getString("realtimeClock") == "true")
  }

  @After
  fun cancel() {
    val done = CountDownLatch(1)
    main.post { animator.get()?.cancel(); done.countDown() }
    done.await(5, TimeUnit.SECONDS)
  }

  /** getRepeatCount gives back INFINITE. */
  @Test
  fun anInfiniteRepeatCountReadsBackAsInfinite() {
    val count = AtomicInteger()
    val done = CountDownLatch(1)
    main.post {
      val a = ValueAnimator.ofFloat(0f, 1f).apply { repeatCount = ValueAnimator.INFINITE }
      animator.set(a)
      count.set(a.repeatCount)
      done.countDown()
    }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertEquals(ValueAnimator.INFINITE, count.get())
  }

  /** A 50 ms animator repeating for ever is still repeating after its fifth cycle, and never ends by itself. */
  @Test
  fun anInfiniteAnimatorKeepsRepeating() {
    val repeats = CountDownLatch(5)
    val ended = AtomicInteger()
    main.post {
      animator.set(ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 50
        repeatCount = ValueAnimator.INFINITE
        addListener(object : AnimatorListenerAdapter() {
          override fun onAnimationRepeat(animation: Animator) = repeats.countDown()
          override fun onAnimationEnd(animation: Animator) { ended.incrementAndGet() }
        })
        start()
      })
    }
    assertTrue("only ${5 - repeats.count} repeats", repeats.await(5, TimeUnit.SECONDS))
    assertEquals("ended by itself", 0, ended.get())
  }
}
