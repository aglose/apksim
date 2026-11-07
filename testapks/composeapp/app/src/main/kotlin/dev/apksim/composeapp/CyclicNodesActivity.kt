package dev.apksim.composeapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * apksim://cyclic-nodes (issue 114): a view whose accessibility provider lists a node under several parents and an
 * ancestor as a child, as a Compose screen's did. `nodes`, `tap-text` and autofill must visit each virtual id once
 * and finish; before, the walk repeated every listing down to its depth cap and never ended.
 */
class CyclicNodesActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(CyclicView(this))
  }
}

private class CyclicView(context: Context) : View(context) {
  // Virtual node id -> the ids it lists as children. 1 and 2 list each other, 3 lists the root again.
  private val children = mapOf(
    View.NO_ID to listOf(1, 2),
    1 to listOf(2, 3),
    2 to listOf(1, 3),
    3 to listOf(View.NO_ID, 1, 2),
  )

  private val provider = object : AccessibilityNodeProvider() {
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
      val id = if (virtualViewId == HOST_VIEW_ID) View.NO_ID else virtualViewId
      val list = children[id] ?: return null
      val info = AccessibilityNodeInfo.obtain(this@CyclicView, virtualViewId)
      info.text = if (id == View.NO_ID) "cyclic: root" else "cyclic: node $id"
      info.setVisibleToUser(true)
      for (c in list) info.addChild(this@CyclicView, if (c == View.NO_ID) HOST_VIEW_ID else c)
      return info
    }
  }

  override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider
}
