package dev.apksim.composeapp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layout that blocks the main thread on a read from another thread, as Compose Multiplatform's stringResource does
 * (runBlocking around a resource load on Dispatchers.IO; a lazy list's items compose during measure). Under the
 * Compose rule's test clock that measure runs in a frame the clock steps, which on a device is a main-thread message:
 * the thread waits and nothing else runs on it. apksim's main-thread parks ran the main queue there, a window frame
 * among it, whose draw measured the Compose view again inside its own measure ("performMeasureAndLayout called during
 * measure layout").
 */
@RunWith(AndroidJUnit4::class)
class ComposeBlockingReadTest {
  @get:Rule val compose = createComposeRule()

  /** A width read during measure, then a blocking read: a new width remeasures the node without recomposing it. */
  private fun Modifier.blockingWidth(width: State<Int>) = layout { measurable, constraints ->
    val w = width.value
    runBlocking { withContext(Dispatchers.IO) { delay(30) } }
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = maxOf(w, constraints.minWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }

  /** Two such nodes follow an animation: while the first blocks, the second is still waiting for its measure. */
  @Test
  fun measuresThatBlockOnAnotherThreadFollowAnAnimationOnTheTestClock() {
    var target by mutableIntStateOf(10)
    compose.setContent {
      val width = animateIntAsState(target, tween(200, easing = LinearEasing), label = "width")
      Column(Modifier.fillMaxSize()) {
        Text("first", Modifier.blockingWidth(width))
        Text("second", Modifier.blockingWidth(width))
      }
    }
    compose.mainClock.autoAdvance = false
    target = 200
    compose.mainClock.advanceTimeBy(300)
    val expected = with(compose.density) { 200.toDp() }
    compose.onNodeWithText("first").assertWidthIsEqualTo(expected)
    compose.onNodeWithText("second").assertWidthIsEqualTo(expected)
  }
}
