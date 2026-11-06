package dev.apksim.composeapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A lazy list prefetches the item past the viewport in the direction it scrolled, in idle time between frames, as on a
 * device (LazyListPrefetchStrategy: one item ahead). Compose turns prefetch off when Build.FINGERPRINT is
 * "robolectric", which a test run's is, so apksim gives it the device's scheduler (dev.apksim.TestActivities.prefetchFingerprint).
 * A suite waiting for two "loading more" rows at a list's end, only one on screen, counted the second one on a device.
 */
@RunWith(AndroidJUnit4::class)
class LazyPrefetchTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun aScrolledListComposesTheNextItemPastTheViewport() {
    val composed = ConcurrentHashMap.newKeySet<Int>()
    lateinit var state: LazyListState
    compose.setContent {
      state = rememberLazyListState()
      LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state) {
        items(60) { i ->
          composed.add(i)
          Text("row $i", Modifier.fillMaxWidth().height(120.dp).testTag("row$i"))
        }
      }
    }
    compose.onNodeWithTag("list").performScrollToNode(hasTestTag("row30"))
    compose.waitForIdle()
    val last = compose.runOnIdle { state.layoutInfo.visibleItemsInfo.last().index }
    compose.waitUntil(2_000) { (last + 1) in composed }
    assertTrue("row ${last + 1} composed, highest ${composed.max()}", (last + 1) in composed)
    compose.onAllNodes(hasTestTag("row${last + 1}")).assertCountEquals(1)
  }
}
