package dev.apksim.composeapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso's wait for the app to be idle also waits for Compose, as on a device, where the Compose rule registers its
 * idling resource with Espresso: a Compose animation started by the app has run to its end when Espresso is idle.
 */
@RunWith(AndroidJUnit4::class)
class ComposeEspressoIdleTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun espressoWaitsForAComposeAnimation() {
    val target = mutableStateOf(0f)
    val shown = floatArrayOf(-1f)
    compose.setContent {
      val value by animateFloatAsState(target.value, tween(durationMillis = 500), label = "value")
      shown[0] = value
      Text("value $value")
    }
    compose.runOnIdle { target.value = 1f }
    // Espresso alone, as a suite's view checks are, not the Compose rule.
    Espresso.onIdle()
    assertEquals(1f, shown[0])
  }
}
