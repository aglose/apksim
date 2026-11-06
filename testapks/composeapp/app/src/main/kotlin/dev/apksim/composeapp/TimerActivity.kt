package dev.apksim.composeapp

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * apksim://timer: the clock an app's timers run on against the wall clock, and onUserInteraction for each input path
 * (scripts/timer-check.py). An app-wide inactivity timer is a CountDownTimer reset from onUserInteraction, so it fires
 * on time only if SystemClock keeps to real time and every touch and key reaches the activity.
 *
 * - "timer": a 60 s CountDownTimer started with the activity; when it finishes, the wall time it took.
 * - "drift": SystemClock.elapsedRealtime() and uptimeMillis() moved, less the wall time that passed, since the start.
 * - "interactions": onUserInteraction calls, and the touch downs and key downs the activity dispatched.
 * - "Sleep worker": a background thread sleeps 20 x 100 ms with SystemClock.sleep, as a polling worker would; it
 *   should take 2 s of wall time and move the clock by as much as wall time passes, no more.
 * - "Sleep on main": the main thread waits for an uptime deadline 500 ms away with SystemClock.sleep(10), which ends
 *   only if sleeping there moves the clock; it should take about 500 ms of wall time.
 */
class TimerActivity : androidx.activity.ComponentActivity() {
  private val startWall = System.currentTimeMillis()
  private val startElapsed = SystemClock.elapsedRealtime()
  private val startUptime = SystemClock.uptimeMillis()
  private var timer by mutableStateOf("running")
  private var drift by mutableStateOf("")
  private var interactions by mutableStateOf(0)
  private var touches by mutableStateOf(0)
  private var keys by mutableStateOf(0)
  private var sleeper by mutableStateOf("idle")
  private var mainSleeper by mutableStateOf("idle")
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var countDown: CountDownTimer

  private val tick = object : Runnable {
    override fun run() {
      val wall = System.currentTimeMillis() - startWall
      drift = "drift: elapsed ${SystemClock.elapsedRealtime() - startElapsed - wall} ms, uptime ${SystemClock.uptimeMillis() - startUptime - wall} ms after ${wall / 1000} s"
      handler.postDelayed(this, 500)
    }
  }

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    countDown = object : CountDownTimer(60_000, 1_000) {
      override fun onTick(left: Long) {
        timer = "running, ${(left + 999) / 1000} s left"
      }

      override fun onFinish() {
        // Locale.ROOT: scripts/timer-check.py reads the number, whatever --locale the app runs with.
        timer = "finished after %.1f s of wall time".format(java.util.Locale.ROOT, (System.currentTimeMillis() - startWall) / 1000.0)
      }
    }.start()
    tick.run()
    setContent {
      MaterialTheme {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
          Text("Timers", style = MaterialTheme.typography.titleLarge)
          Text("timer: $timer", Modifier.padding(top = 12.dp))
          Text(drift, Modifier.padding(top = 8.dp))
          Text("interactions: $interactions, touches $touches, keys $keys", Modifier.padding(top = 8.dp))
          Row(Modifier.padding(top = 12.dp)) {
            Button({ sleepWorker() }) { Text("Sleep worker") }
            Button({ sleepOnMain() }, Modifier.padding(start = 8.dp)) { Text("Sleep on main") }
          }
          Text("sleep: $sleeper", Modifier.padding(top = 8.dp))
          Text("main sleep: $mainSleeper", Modifier.padding(top = 8.dp))
        }
      }
    }
  }

  private fun sleepWorker() {
    sleeper = "sleeping"
    Thread {
      val wall0 = System.nanoTime()
      val clock0 = SystemClock.elapsedRealtime()
      repeat(20) { SystemClock.sleep(100) }
      val wall = (System.nanoTime() - wall0) / 1_000_000
      val clock = SystemClock.elapsedRealtime() - clock0
      runOnUiThread { sleeper = "20 x 100 ms took $wall ms of wall time, clock moved $clock ms" }
    }.start()
  }

  private fun sleepOnMain() {
    val wall0 = System.nanoTime()
    val end = SystemClock.uptimeMillis() + 500
    var naps = 0
    while (SystemClock.uptimeMillis() < end && naps < 1000) {
      SystemClock.sleep(10)
      naps++
    }
    val wall = (System.nanoTime() - wall0) / 1_000_000
    mainSleeper = if (naps >= 1000) "deadline never came after $wall ms" else "500 ms deadline after $wall ms of wall time"
  }

  override fun onUserInteraction() {
    super.onUserInteraction()
    interactions++
  }

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) touches++
    return super.dispatchTouchEvent(ev)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) keys++
    return super.dispatchKeyEvent(event)
  }

  override fun onDestroy() {
    countDown.cancel()
    handler.removeCallbacks(tick)
    super.onDestroy()
  }
}
