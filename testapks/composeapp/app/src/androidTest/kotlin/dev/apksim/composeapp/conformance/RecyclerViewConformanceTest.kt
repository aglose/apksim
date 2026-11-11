package dev.apksim.composeapp.conformance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.recyclerview as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class RecyclerViewConformanceTest {
  class Rows : ListAdapter<String, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(a: String, b: String) = a == b
    override fun areContentsTheSame(a: String, b: String) = a == b
  }) {
    var clicked = -1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
      val density = parent.resources.displayMetrics.density
      val v = TextView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (100 * density).toInt())
      }
      return object : RecyclerView.ViewHolder(v) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      (holder.itemView as TextView).text = getItem(position)
      holder.itemView.setOnClickListener { clicked = holder.bindingAdapterPosition }
    }
  }

  class ListFragment : Fragment() {
    lateinit var list: RecyclerView
    val rows = Rows()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
      list = RecyclerView(inflater.context).apply {
        id = R_ID
        layoutManager = LinearLayoutManager(context)
        adapter = rows
      }
      rows.submitList((0 until 100).map { "row $it" })
      return list
    }
  }

  companion object {
    val R_ID = View.generateViewId()
    val INNER_ID = View.generateViewId()
  }

  private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

  /** Only the rows in the viewport (and a few around it) have view holders. */
  @Test
  fun onlyVisibleRowsAreBound() {
    launchFragmentInContainer<ListFragment>().onFragment { f ->
      assertNotNull(f.list.findViewHolderForAdapterPosition(0))
      assertNull(f.list.findViewHolderForAdapterPosition(80))
    }
  }

  /** RecyclerViewActions scroll to and click a row far down the list. */
  @Test
  fun espressoScrollsToAndClicksARow() {
    val scenario = launchFragmentInContainer<ListFragment>()
    onView(withId(R_ID)).perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(60,
      androidx.test.espresso.action.ViewActions.click()))
    scenario.onFragment { f -> assertEquals(60, f.rows.clicked) }
  }

  /** A swipe up flings the list: it ends idle, scrolled past the rows the finger moved. */
  @Test
  fun aSwipeFlingsAndSettles() {
    val scenario = launchFragmentInContainer<ListFragment>()
    onView(withId(R_ID)).perform(swipeUp())
    val deadline = System.currentTimeMillis() + 5_000
    var state = -1
    var first = -1
    while (System.currentTimeMillis() < deadline) {
      idle()
      scenario.onFragment { f ->
        state = f.list.scrollState
        first = (f.list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
      }
      if (state == RecyclerView.SCROLL_STATE_IDLE) break
      Thread.sleep(20)
    }
    assertEquals(RecyclerView.SCROLL_STATE_IDLE, state)
    assertTrue("first $first", first > 2)
  }

  /** smoothScrollToPosition ends with that row on screen and the list idle. */
  @Test
  fun smoothScrollEndsOnTheRow() {
    val scenario = launchFragmentInContainer<ListFragment>()
    scenario.onFragment { f -> f.list.smoothScrollToPosition(50) }
    val deadline = System.currentTimeMillis() + 5_000
    var shown = false
    var state = -1
    while (System.currentTimeMillis() < deadline) {
      idle()
      scenario.onFragment { f ->
        state = f.list.scrollState
        val lm = f.list.layoutManager as LinearLayoutManager
        shown = 50 in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()
      }
      if (shown && state == RecyclerView.SCROLL_STATE_IDLE) break
      Thread.sleep(20)
    }
    assertTrue(shown)
    assertEquals(RecyclerView.SCROLL_STATE_IDLE, state)
  }

  /** ListAdapter diffs on a background thread and calls the commit callback on the main thread with the new list. */
  @Test
  fun submitListCommitsOnTheMainThread() {
    val scenario = launchFragmentInContainer<ListFragment>()
    var committedOnMain: Boolean? = null
    scenario.onFragment { f ->
      f.rows.submitList((0 until 100).filter { it % 2 == 0 }.map { "row $it" }) {
        committedOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
      }
    }
    val deadline = System.currentTimeMillis() + 5_000
    while (committedOnMain == null && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(10) }
    assertEquals(true, committedOnMain)
    scenario.onFragment { f -> assertEquals(50, f.rows.itemCount) }
    onView(withText("row 2")).check { v, e -> if (v == null) throw e }
  }

  /** The item animator finishes its removal animation and the removed row's view goes. */
  @Test
  fun aRemovalAnimationFinishes() {
    val scenario = launchFragmentInContainer<ListFragment>()
    scenario.onFragment { f -> f.rows.submitList((1 until 100).map { "row $it" }) }
    val deadline = System.currentTimeMillis() + 5_000
    var animating = true
    var first = ""
    while (System.currentTimeMillis() < deadline) {
      idle()
      scenario.onFragment { f ->
        animating = f.list.isAnimating
        first = (f.list.findViewHolderForAdapterPosition(0)?.itemView as? TextView)?.text?.toString() ?: ""
      }
      if (!animating && first == "row 1") break
      Thread.sleep(20)
    }
    assertFalse(animating)
    assertEquals("row 1", first)
  }

  /** ItemTouchHelper's swipe to dismiss reports the swiped row. */
  @Test
  fun itemTouchHelperSwipeDismisses() {
    val scenario = launchFragmentInContainer<ListFragment>()
    var swiped = -1
    scenario.onFragment { f ->
      ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
        override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) { swiped = h.bindingAdapterPosition }
      }).attachToRecyclerView(f.list)
    }
    onView(withId(R_ID)).perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(1, swipeLeft()))
    val deadline = System.currentTimeMillis() + 5_000
    while (swiped < 0 && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
    assertEquals(1, swiped)
  }

  /** LinearLayoutManager's position comes back after the activity is recreated. */
  @Test
  fun scrollPositionSurvivesRecreate() {
    val scenario = launchFragmentInContainer<ListFragment>()
    scenario.onFragment { f -> (f.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(30, 0) }
    idle()
    scenario.recreate()
    scenario.onFragment { f ->
      assertEquals(30, (f.list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
    }
  }
  /** A GridLayoutManager's span size lookup puts a full-width header above two-column rows. */
  @Test
  fun aGridSpanLookupLaysOutRows() {
    val scenario = launchFragmentInContainer<ListFragment>()
    scenario.onFragment { f ->
      f.list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(f.requireContext(), 2).apply {
        spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
          override fun getSpanSize(position: Int) = if (position == 0) 2 else 1
        }
      }
    }
    idle()
    scenario.onFragment { f ->
      val header = f.list.findViewHolderForAdapterPosition(0)!!.itemView
      val a = f.list.findViewHolderForAdapterPosition(1)!!.itemView
      val b = f.list.findViewHolderForAdapterPosition(2)!!.itemView
      assertEquals(f.list.width, header.width)
      assertEquals(a.top, b.top)
      assertEquals(header.bottom, a.top)
      assertEquals(0, a.left)
      assertEquals(a.right, b.left)
    }
  }

  /**
   * The accessibility scroll-forward action pages the list on, as TalkBack would. It is a smooth scroll (RecyclerView
   * 1.4's LayoutManager.performAccessibilityAction calls smoothScrollBy), so the test waits for it to settle: on a device
   * waitForIdleSync returns while it runs, and here it does with --realtime-clock (docs/conformance.md).
   */
  @Test
  fun anAccessibilityScrollPagesTheList() {
    val scenario = launchFragmentInContainer<ListFragment>()
    var handled = false
    scenario.onFragment { f ->
      handled = f.list.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
    }
    assertTrue(handled)
    val deadline = System.currentTimeMillis() + 5_000
    var state = -1
    var first = -1
    while (System.currentTimeMillis() < deadline) {
      idle()
      scenario.onFragment { f ->
        state = f.list.scrollState
        first = (f.list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
      }
      if (state == RecyclerView.SCROLL_STATE_IDLE && first > 0) break
      Thread.sleep(20)
    }
    assertEquals(RecyclerView.SCROLL_STATE_IDLE, state)
    assertTrue("first $first", first > 3)
  }

  class NestedFragment : Fragment() {
    lateinit var outer: RecyclerView
    lateinit var inner: RecyclerView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
      val density = inflater.context.resources.displayMetrics.density
      inner = RecyclerView(inflater.context).apply {
        id = INNER_ID
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
          override fun getItemCount() = 30
          override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(TextView(p.context).apply {
            layoutParams = ViewGroup.LayoutParams((150 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
          }) {}
          override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) { (h.itemView as TextView).text = "card $pos" }
        }
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt())
      }
      outer = RecyclerView(inflater.context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
          override fun getItemCount() = 20
          override fun getItemViewType(position: Int) = if (position == 0) 1 else 0
          override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
            if (t == 1) inner else TextView(p.context).apply {
              layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (100 * density).toInt())
            }) {}
          override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) { (h.itemView as? TextView)?.text = "row $pos" }
        }
      }
      return outer
    }
  }

  /** A horizontal swipe on a horizontal list inside a vertical one scrolls only the inner list. */
  @Test
  fun aSwipeOnANestedListScrollsOnlyIt() {
    val scenario = launchFragmentInContainer<NestedFragment>()
    onView(withId(INNER_ID)).perform(swipeLeft())
    val deadline = System.currentTimeMillis() + 5_000
    var innerFirst = 0
    var innerIdle = false
    var outerOffset = -1
    while (System.currentTimeMillis() < deadline) {
      idle()
      scenario.onFragment { f ->
        innerFirst = (f.inner.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        innerIdle = f.inner.scrollState == RecyclerView.SCROLL_STATE_IDLE
        outerOffset = f.outer.computeVerticalScrollOffset()
      }
      if (innerFirst > 0 && innerIdle) break
      Thread.sleep(20)
    }
    assertTrue("inner first $innerFirst", innerFirst > 0)
    assertTrue("the inner list never settled", innerIdle)
    assertEquals(0, outerOffset)
  }
}
