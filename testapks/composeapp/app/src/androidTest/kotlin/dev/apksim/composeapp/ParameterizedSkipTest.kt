package dev.apksim.composeapp

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A @BeforeParam assumption skips its parameter set, and each of the set's tests is reported skipped, as JUnit's
 * Parameterized does: the run accounts for every test (instrument.py would rerun ones with no result).
 */
@RunWith(Parameterized::class)
class ParameterizedSkipTest(private val name: String) {
  @Test
  fun runsOnlyForTheSetsBeforeParamAccepted() {
    assertEquals("kept", name)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<Array<Any>> = listOf(arrayOf("kept"), arrayOf("assumed away"))

    @JvmStatic
    @Parameterized.BeforeParam
    fun onlyKept(name: String) {
      assumeTrue("this set is skipped on purpose", name == "kept")
    }
  }
}
