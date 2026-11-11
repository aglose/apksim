package dev.apksim.composeapp.conformance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.compose.foundation as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class ComposeFoundationConformanceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private fun list(state: @androidx.compose.runtime.Composable () -> LazyListState, keys: List<Int> = (0 until 100).toList()) {
    compose.setContent {
      LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state()) {
        items(keys, key = { it }) { i -> Text("row $i", Modifier.fillMaxWidth().height(100.dp)) }
      }
    }
  }

  /** performScrollToIndex puts the item first in the viewport. */
  @Test
  fun scrollToIndexMakesItFirstVisible() {
    lateinit var state: LazyListState
    list({ rememberLazyListState().also { state = it } })
    compose.onNodeWithTag("list").performScrollToIndex(40)
    compose.runOnIdle {
      assertEquals(40, state.firstVisibleItemIndex)
      assertEquals(0, state.firstVisibleItemScrollOffset)
    }
  }

  /** A swipe up flings the list on, past where the finger stopped, and it comes to rest. */
  @Test
  fun aSwipeFlingsAndSettles() {
    lateinit var state: LazyListState
    list({ rememberLazyListState().also { state = it } })
    compose.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 100) }
    compose.waitForIdle()
    compose.runOnIdle {
      assertTrue("first ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > 3)
      assertTrue(!state.isScrollInProgress)
    }
  }

  /** Keyed items keep their rememberSaveable state when the list is reordered. */
  @Test
  fun keyedItemsKeepStateWhenReordered() {
    val keys = mutableStateListOf(0, 1, 2)
    compose.setContent {
      LazyColumn(Modifier.fillMaxSize()) {
        items(keys, key = { it }) { k ->
          var taps by rememberSaveable { mutableStateOf(0) }
          Text("item $k taps $taps", Modifier.fillMaxWidth().height(80.dp).clickable { taps++ })
        }
      }
    }
    compose.onNodeWithText("item 1 taps 0").performClick()
    compose.runOnIdle { keys.reverse() }
    compose.onNodeWithText("item 1 taps 1").assertExists()
    compose.onNodeWithText("item 0 taps 0").assertExists()
  }

  /** A swipe left on a pager settles on the next page. */
  @Test
  fun aPagerSwipeSettlesOnTheNextPage() {
    lateinit var state: PagerState
    compose.setContent {
      state = rememberPagerState { 5 }
      HorizontalPager(state, Modifier.fillMaxSize().testTag("pager")) { p -> Text("page $p", Modifier.fillMaxSize()) }
    }
    compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
    compose.runOnIdle {
      assertEquals(1, state.currentPage)
      assertEquals(0f, state.currentPageOffsetFraction, 0.001f)
    }
  }

  /** animateScrollToPage ends on that page. */
  @Test
  fun animateScrollToPageEndsThere() {
    lateinit var state: PagerState
    lateinit var scope: CoroutineScope
    compose.setContent {
      state = rememberPagerState { 5 }
      scope = rememberCoroutineScope()
      HorizontalPager(state, Modifier.fillMaxSize()) { p -> Text("page $p", Modifier.fillMaxSize()) }
    }
    compose.runOnIdle { scope.launch { state.animateScrollToPage(3) } }
    compose.onNodeWithText("page 3").assertExists()
    compose.runOnIdle { assertEquals(3, state.settledPage) }
  }

  /** Text typed into a BasicTextField lands in its TextFieldState, the caret after it. */
  @Test
  fun typedTextReachesTheState() {
    lateinit var state: TextFieldState
    compose.setContent {
      state = rememberTextFieldState("ab")
      BasicTextField(state, Modifier.testTag("field").fillMaxWidth())
    }
    compose.onNodeWithTag("field").performTextInput("cd")
    compose.runOnIdle {
      assertEquals("abcd", state.text.toString())
      assertEquals(4, state.selection.start)
      assertEquals(4, state.selection.end)
    }
  }

  /** A click emits a press and its release through the interaction source. */
  @Test
  fun aClickEmitsPressAndRelease() {
    val source = MutableInteractionSource()
    val seen = mutableListOf<Interaction>()
    compose.setContent {
      LaunchedEffect(source) { source.interactions.collect { seen += it } }
      Box(Modifier.size(80.dp).testTag("b").clickable(source, indication = null) {})
    }
    compose.onNodeWithTag("b").performClick()
    compose.runOnIdle {
      assertEquals(2, seen.size)
      assertTrue(seen[0] is PressInteraction.Press)
      assertTrue(seen[1] is PressInteraction.Release)
    }
  }

  /** performScrollTo brings a node of a verticalScroll column into view, the scroll state following. */
  @Test
  fun performScrollToMovesTheScrollState() {
    lateinit var scroll: ScrollState
    compose.setContent {
      scroll = rememberScrollState()
      Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        repeat(40) { Text("line $it", Modifier.fillMaxWidth().height(100.dp)) }
      }
    }
    compose.onNodeWithText("line 30").performScrollTo()
    compose.runOnIdle { assertTrue("scroll ${scroll.value}", scroll.value > 0 && !scroll.isScrollInProgress) }
  }
}
