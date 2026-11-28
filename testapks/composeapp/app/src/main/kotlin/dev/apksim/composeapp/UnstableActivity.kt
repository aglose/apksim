package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket

/**
 * apksim://unstable?mode=tick|fail: screens a crawl must not keep as a baseline (scripts/crawl.py, docs/crawl.md
 * "Unstable screens"). `tick`: a counter that changes every 100 ms, so two reads of the screen never agree. `fail`: a
 * request that fails as the screen opens, and again on Retry (a connect to a loopback port nothing listens on), the screen
 * otherwise still.
 */
class UnstableActivity : Activity() {
  private val main = Handler(Looper.getMainLooper())
  // From a different number in every process, so two walks never read the same text (crawl-check step 15).
  private var ticks = (System.nanoTime() / 1_000_000 % 9000).toInt()
  private lateinit var tick: Runnable

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val mode = intent?.data?.getQueryParameter("mode") ?: "tick"
    val title = TextView(this).apply { text = "Unstable: $mode" }
    val line = TextView(this)
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(title)
      addView(line)
    })
    if (mode == "fail") {
      fun load() {
        line.text = "load: pending"
        Thread {
          val result = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9), 2000) } }
          main.post { line.text = if (result.isSuccess) "load: ok" else "load: failed" }
        }.start()
      }
      (line.parent as LinearLayout).addView(Button(this).apply { text = "Retry"; setOnClickListener { load() } })
      load()
    } else {
      tick = Runnable {
        line.text = "tick %04d".format(ticks++)
        main.postDelayed(tick, 100)
      }
      tick.run()
    }
  }

  override fun onDestroy() {
    main.removeCallbacksAndMessages(null)
    super.onDestroy()
  }
}
