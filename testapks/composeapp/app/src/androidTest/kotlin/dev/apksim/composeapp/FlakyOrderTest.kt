package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two test classes that depend on the order they run in, for `scripts/flake-hunt.py` (docs/flake-hunt.md) to find:
 * [FlakyStateTest.leavesAStaticSet] sets a static and never clears it, and [FlakyOrderTest.expectsAFreshStatic] fails
 * when it runs after that in the same process (app and test statics carry over between a process's tests, as on a
 * device). In the suite's order (classes by name) FlakyOrderTest runs first and passes; shuffled, or with the polluter
 * named first, it fails. The other tests are bystanders the hunter must not blame. Only with `-e flakyOnPurpose true`:
 * otherwise every one is skipped.
 */
internal object FlakyState {
  @JvmStatic var polluted = false
}

internal fun flakyOnPurpose() =
  assumeTrue(InstrumentationRegistry.getArguments().getString("flakyOnPurpose") == "true")

@RunWith(AndroidJUnit4::class)
class FlakyOrderTest {
  @Test
  fun expectsAFreshStatic() {
    flakyOnPurpose()
    assertFalse("FlakyState.polluted was left set by a test that ran before this one in the process", FlakyState.polluted)
  }

  @Test
  fun bystanderOne() {
    flakyOnPurpose()
    assertEquals(4, 2 + 2)
  }
}

@RunWith(AndroidJUnit4::class)
class FlakyStateTest {
  @Test
  fun bystanderTwo() {
    flakyOnPurpose()
    assertEquals("dev.apksim.composeapp", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
  }

  @Test
  fun leavesAStaticSet() {
    flakyOnPurpose()
    FlakyState.polluted = true
  }

  @Test
  fun bystanderThree() {
    flakyOnPurpose()
    assertEquals(6, 2 * 3)
  }
}
