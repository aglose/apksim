package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The plain-Views half of apksim://faults (scripts/crawl.py's resilience sweep): "taps" is an Activity field that
 * onSaveInstanceState does not keep, "saved taps" is kept; a TextView that fits at font scale 1.0 and is ellipsized at
 * 2.0 and one that wraps.
 */
class FaultsViewsActivity : Activity() {
  private var taps = 0
  private var savedTaps = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    savedTaps = savedInstanceState?.getInt("savedTaps") ?: 0
    val density = resources.displayMetrics.density
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * density).toInt(), (32 * density).toInt(), (16 * density).toInt(), (16 * density).toInt()) }
    val tapsView = TextView(this).apply { text = "taps: $taps" }
    val savedView = TextView(this).apply { text = "saved taps: $savedTaps" }
    root.addView(TextView(this).apply { text = "Faults in Views"; textSize = 22f })
    root.addView(tapsView)
    root.addView(Button(this).apply { text = "Tap"; setOnClickListener { taps++; tapsView.text = "taps: $taps" } })
    root.addView(savedView)
    root.addView(Button(this).apply {
      text = "Tap saved"
      setOnClickListener { savedTaps++; savedView.text = "saved taps: $savedTaps" }
    })
    root.addView(TextView(this).apply {
      text = "Checked baggage"
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
      layoutParams = ViewGroup.LayoutParams((170 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    root.addView(TextView(this).apply { text = "This one wraps and always fits, whatever the font size is" })
    setContentView(ScrollView(this).apply { addView(root) })
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt("savedTaps", savedTaps)  // "taps" is left out on purpose
  }
}
