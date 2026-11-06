package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * apksim://clock?seconds=60[&sleeper=1]: a CountDownTimer of that length, as an app's inactivity timer is one (a
 * Handler chain on elapsedRealtime), restarted from onUserInteraction, and how long it took in wall-clock time.
 * `sleeper=1` adds a thread that polls with SystemClock.sleep(100), as libraries do. scripts/clock-check.py reads the
 * text through `nodes`: "timer: finished after W s wall, E s elapsed", "interactions: N".
 */
class ClockCheckActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var count: TextView
  private var timer: CountDownTimer? = null
  private var startWall = 0L
  private var startElapsed = 0L
  private var interactions = 0
  private var finished: String? = null
  @Volatile private var alive = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Two views, so `nodes` shows each line as its own text.
    status = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 16) }
    count = TextView(this).apply { textSize = 18f; setPadding(32, 16, 32, 32) }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      addView(status)
      addView(count)
    })
    if (intent.data?.getQueryParameter("sleeper") == "1") {
      Thread({ while (alive) SystemClock.sleep(100) }, "clock-check-sleeper").apply { isDaemon = true }.start()
    }
    restart()
    tick()
  }

  private val seconds: Long get() = intent.data?.getQueryParameter("seconds")?.toLongOrNull() ?: 60

  private fun restart() {
    timer?.cancel()
    finished = null
    startWall = System.currentTimeMillis()
    startElapsed = SystemClock.elapsedRealtime()
    timer = object : CountDownTimer(seconds * 1000, 1000) {
      override fun onTick(millisUntilFinished: Long) {}

      override fun onFinish() {
        finished = String.format(Locale.ROOT, "timer: finished after %.1f s wall, %.1f s elapsed",
            (System.currentTimeMillis() - startWall) / 1000.0, (SystemClock.elapsedRealtime() - startElapsed) / 1000.0)
        show()
      }
    }.start()
  }

  // A device calls this for every touch and key the activity's window dispatches (Activity.dispatchTouchEvent).
  override fun onUserInteraction() {
    super.onUserInteraction()
    interactions++
    restart()
    show()
  }

  private fun tick() {
    show()
    if (alive) status.postDelayed({ tick() }, 500)
  }

  private fun show() {
    val running = String.format(Locale.ROOT, "timer: running %.1f s wall, %.1f s elapsed",
        (System.currentTimeMillis() - startWall) / 1000.0, (SystemClock.elapsedRealtime() - startElapsed) / 1000.0)
    status.text = finished ?: running
    count.text = "interactions: $interactions"
  }

  override fun onDestroy() {
    alive = false
    timer?.cancel()
    super.onDestroy()
  }
}
