package dev.apksim.composeapp.conformance

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.lifecycle as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class LifecycleConformanceTest {
  private fun processState(): Lifecycle.State {
    var s: Lifecycle.State? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { s = ProcessLifecycleOwner.get().lifecycle.currentState }
    return s!!
  }

  /** ProcessLifecycleOwner follows the app's activities: RESUMED while one is. */
  @Test
  fun processLifecycleIsResumedWithAnActivity() {
    ActivityScenario.launch(ComponentActivity::class.java).use {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(Lifecycle.State.RESUMED, processState())
    }
  }

  /**
   * "ON_PAUSE, ON_STOP will be dispatched with a delay after a last activity passed through them" (700 ms): the
   * process goes to CREATED once that delay has passed after the last activity stopped.
   */
  @Test
  fun processLifecycleStopsAfterItsDelay() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.moveToState(Lifecycle.State.CREATED)
      SystemClock.sleep(1500)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(Lifecycle.State.CREATED, processState())
      scenario.moveToState(Lifecycle.State.RESUMED)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(Lifecycle.State.RESUMED, processState())
    }
  }

  /** repeatOnLifecycle(STARTED) runs its block while started, cancels it below, and runs it again on return. */
  @Test
  fun repeatOnLifecycleRestartsItsBlock() {
    val starts = AtomicInteger()
    val cancels = AtomicInteger()
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { a ->
        a.lifecycleScope.launch {
          a.repeatOnLifecycle(Lifecycle.State.STARTED) {
            starts.incrementAndGet()
            try { awaitCancellation() } finally { cancels.incrementAndGet() }
          }
        }
      }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(1, starts.get())
      scenario.moveToState(Lifecycle.State.CREATED)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(1, cancels.get())
      scenario.moveToState(Lifecycle.State.RESUMED)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(2, starts.get())
    }
  }

  /** lifecycleScope is cancelled when the activity is destroyed. */
  @Test
  fun lifecycleScopeEndsWithTheActivity() {
    val ended = AtomicInteger()
    val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    scenario.onActivity { a ->
      a.lifecycleScope.launch { try { awaitCancellation() } finally { ended.incrementAndGet() } }
    }
    scenario.close()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    assertEquals(1, ended.get())
  }

  /** withStarted runs its block only once the lifecycle is at least STARTED. */
  @Test
  fun withStartedWaitsForStarted() {
    val ran = AtomicBoolean()
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onActivity { a -> a.lifecycleScope.launch { a.lifecycle.withStarted { ran.set(true) } } }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(false, ran.get())
      scenario.moveToState(Lifecycle.State.STARTED)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(true, ran.get())
    }
  }

  /** Lifecycle.currentStateFlow follows the state. */
  @Test
  fun currentStateFlowFollowsTheLifecycle() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var flow: kotlinx.coroutines.flow.StateFlow<Lifecycle.State>
      scenario.onActivity { a -> flow = a.lifecycle.currentStateFlow }
      assertEquals(Lifecycle.State.RESUMED, flow.value)
      scenario.moveToState(Lifecycle.State.CREATED)
      assertEquals(Lifecycle.State.CREATED, flow.value)
    }
  }
}
