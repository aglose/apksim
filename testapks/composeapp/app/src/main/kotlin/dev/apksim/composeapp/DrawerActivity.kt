package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout

/**
 * apksim://drawer: a [DrawerLayout] with three items, for issue 215. Opening or closing it settles through
 * ViewDragHelper's Scroller, driven by computeScroll on each frame: there is no animator, so `animator_duration_scale`
 * does not touch it. An item counts its clicks ("clicks: N" in the content, [clicks]); a click that lands while the
 * drawer is still sliding in misses it.
 */
class DrawerActivity : Activity() {
  lateinit var drawer: DrawerLayout
  private lateinit var status: TextView
  var clicks = 0
    private set

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    drawer = DrawerLayout(this)
    status = TextView(this).apply { text = "clicks: 0"; textSize = 24f; tag = "status" }
    drawer.addView(FrameLayout(this).apply { addView(status) },
        DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val menu = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(0xFFEEEEEE.toInt())
      repeat(3) { i ->
        addView(TextView(context).apply {
          text = "Item $i"
          textSize = 24f
          setPadding(32, 48, 32, 48)
          setOnClickListener { status.text = "clicks: ${++clicks}" }
        })
      }
    }
    drawer.addView(menu, DrawerLayout.LayoutParams(600, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    setContentView(drawer)
  }
}
