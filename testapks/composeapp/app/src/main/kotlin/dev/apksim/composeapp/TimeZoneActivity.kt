package dev.apksim.composeapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.TimeZone
import org.joda.time.DateTimeZone

/**
 * apksim://timezone: every zone source an app reads, each on its own line: the JDK's default, android.icu's, the
 * `user.timezone` property and Joda-Time's default (up to 2.10 Joda reads that property before the JDK's default:
 * apksim's `--timezone` once left it at the host's in warm launches, issue 91). ACTION_TIMEZONE_CHANGED is heard as
 * joda-time-android hears it, setting Joda's default to the zone broadcast, and counted ("tz changes: 1 to Asia/Tokyo"
 * after `timezone Asia/Tokyo`).
 */
class TimeZoneActivity : Activity() {
  private val lines = List(5) { lazy { TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER } } }
  private val handler = Handler(Looper.getMainLooper())
  private var changes = "tz changes: 0"
  private var count = 0
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val zone = intent.getStringExtra(Intent.EXTRA_TIMEZONE)
      if (zone != null) DateTimeZone.setDefault(DateTimeZone.forID(zone))
      changes = "tz changes: ${++count} to $zone"
      show()
    }
  }
  private val tick = object : Runnable {
    override fun run() {
      show()
      handler.postDelayed(this, 250)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      lines.forEach { addView(it.value) }
    })
    registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), Context.RECEIVER_EXPORTED)
    tick.run()
  }

  override fun onDestroy() {
    handler.removeCallbacks(tick)
    unregisterReceiver(receiver)
    super.onDestroy()
  }

  private fun show() {
    val text = listOf(
      "jdk zone: " + TimeZone.getDefault().id,
      "icu zone: " + android.icu.util.TimeZone.getDefault().id,
      "user.timezone: " + System.getProperty("user.timezone"),
      "joda zone: " + DateTimeZone.getDefault().id,
      changes,
    )
    lines.forEachIndexed { i, l -> l.value.text = text[i] }
  }
}
