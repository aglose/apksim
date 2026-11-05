package dev.apksim.composeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * JUnit's Parameterized runs inside the instrumentation, as on a device: the constructor (here a field initializer,
 * as a suite's base class does it) can read the instrumentation's arguments and the app.
 */
@RunWith(Parameterized::class)
class ParameterizedTest(private val size: String, private val count: Int) {
  private val arguments = InstrumentationRegistry.getArguments()

  @Test
  fun eachParameterSetGetsItsValuesAndTheApp() {
    assertNotNull(arguments)
    assertNotNull(InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    assertEquals(size.length, count)
  }

  /** It runs where every other test does: the app's main looper is there and an activity can be launched. */
  @Test
  fun eachParameterSetRunsInTheTestEnvironment() {
    assertNotNull("the main looper", android.os.Looper.getMainLooper())
    androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<Array<Any>> = listOf(arrayOf("phone", 5), arrayOf("tablet", 6))
  }
}
