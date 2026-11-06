package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.model.Statement

/**
 * Tests that end the test process, for checking that a one-process run (`scripts/instrument.py` without --isolate)
 * reports the test that was running as failed and runs the rest in a new process. They run only with
 * `-e crashOnPurpose true`, and are skipped otherwise (on a device and in normal runs). The methods run in name order,
 * so tests of this class (and the classes after it) still have to run after each crash. One more, with
 * `-e hangOnPurpose true`, hangs for good with nothing to end it, for instrument.py's `--test-timeout`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessDeathTest {
  private fun crashOnPurpose() =
    assumeTrue(InstrumentationRegistry.getArguments().getString("crashOnPurpose") == "true")

  private fun hangOnPurpose() =
    assumeTrue(InstrumentationRegistry.getArguments().getString("hangOnPurpose") == "true")

  @Test
  fun a_runsBeforeTheCrashes() {
    assertEquals("dev.apksim.composeapp", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
  }

  /** As a native crash or an abort ends a process: no exception, no result. */
  @Test
  fun b_haltsTheProcess() {
    crashOnPurpose()
    Runtime.getRuntime().halt(1)
  }

  @Test
  fun c_runsAfterTheHalt() {
    assertEquals("dev.apksim.composeapp", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
  }

  /**
   * A test that ignores interrupts past its timeout: on a device JUnit abandons its thread and the run goes on; here
   * it holds the main thread, so apksim's FailOnTimeout watchdog ends the process 10 s past the timeout.
   */
  @Test
  fun d_ignoresInterruptsPastItsTimeout() {
    crashOnPurpose()
    val stuck = object : Statement() {
      override fun evaluate() {
        while (true) {
          Thread.interrupted()
          LockSupport.parkNanos(50_000_000)
        }
      }
    }
    Timeout.millis(300).apply(stuck, Description.EMPTY).evaluate()
  }

  @Test
  fun e_runsAfterTheWatchdog() {
    assertEquals("dev.apksim.composeapp", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
  }

  /**
   * A test that never ends and has no timeout (as one stuck in teardown, or in code JUnit's Timeout does not wrap):
   * nothing in the process ends it, so only instrument.py's `--test-timeout` does, killing the process.
   */
  @Test
  fun f_hangsWithNoTimeout() {
    hangOnPurpose()
    while (true) {
      Thread.interrupted()
      LockSupport.parkNanos(50_000_000)
    }
  }

  @Test
  fun g_runsAfterTheHang() {
    assertEquals("dev.apksim.composeapp", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
  }
}
