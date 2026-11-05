package dev.apksim.composeapp

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A suite's polling helper, `runBlocking { withTimeout { while (!done) delay(poll) } }`, while the app's work comes
 * from the main thread: a postDelayed callback and a Dispatchers.Main coroutine with a delay. On a device the test
 * thread waits and the main thread runs them as their time comes; the poll must see both.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadRunBlockingTest {
  private fun pollUntil(timeoutMs: Long, done: () -> Boolean) = runBlocking {
    withTimeout(timeoutMs) {
      while (!done()) delay(20)
    }
  }

  @Test
  fun aPostDelayedCallbackRunsWhileRunBlockingWaits() {
    var ran = false
    Handler(Looper.getMainLooper()).postDelayed({ ran = true }, 300)
    pollUntil(5_000) { ran }
    assertTrue(ran)
  }

  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun aMainDispatcherDelayRunsWhileRunBlockingWaits() {
    var ran = false
    GlobalScope.launch(Dispatchers.Main) {
      delay(300)
      ran = true
    }
    pollUntil(5_000) { ran }
    assertTrue(ran)
  }
}
