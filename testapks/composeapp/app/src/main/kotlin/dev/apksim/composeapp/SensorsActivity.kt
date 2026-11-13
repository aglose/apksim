package dev.apksim.composeapp

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * apksim://sensors[?rate=fastest|game|ui|normal]: every sensor SensorManager lists, one line each with its type, name,
 * range, resolution and minimum delay, the number of events it has delivered and its last values; a registration that
 * is refused (a sensor that needs a permission) says so. "sensors: N" is the count, "default accelerometer: ..." what
 * getDefaultSensor(TYPE_ACCELEROMETER) returns ("none" when the device has none). `scripts/ci-smoke.sh` drives it and
 * SensorsTest checks the same on a device.
 */
class SensorsActivity : Activity(), SensorEventListener {
  private val handler = Handler(Looper.getMainLooper())
  private val counts = HashMap<Int, Int>()
  private val last = HashMap<Int, String>()
  private val refused = HashSet<Int>()
  private lateinit var sm: SensorManager
  private lateinit var list: List<Sensor>
  private val views = ArrayList<TextView>()
  private lateinit var header: TextView
  private lateinit var default: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    list = sm.getSensorList(Sensor.TYPE_ALL)
    val rate = when (intent?.data?.getQueryParameter("rate")) {
      "fastest" -> SensorManager.SENSOR_DELAY_FASTEST
      "game" -> SensorManager.SENSOR_DELAY_GAME
      "normal" -> SensorManager.SENSOR_DELAY_NORMAL
      else -> SensorManager.SENSOR_DELAY_UI
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 48, 16, 16) }
    header = TextView(this).apply { textSize = 11f }
    default = TextView(this).apply { textSize = 11f }
    column.addView(header)
    column.addView(default)
    for (s in list) column.addView(TextView(this).apply { textSize = 10f }.also { views += it })
    setContentView(ScrollView(this).apply { addView(column) })
    for ((i, s) in list.withIndex()) {
      // A sensor with a handle of its own: the same type may be listed twice (hardware and software orientation).
      if (!sm.registerListener(this, s, rate)) refused += i
    }
    show()
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    sm.unregisterListener(this)
    super.onDestroy()
  }

  private fun index(s: Sensor) = list.indexOfFirst { it == s || it.name == s.name }

  override fun onSensorChanged(event: SensorEvent) {
    val i = index(event.sensor)
    counts[i] = (counts[i] ?: 0) + 1
    last[i] = event.values.joinToString(" ") { String.format(Locale.ROOT, "%.2f", if (it == 0f) 0f else it) }
    if (!pending) {  // one redraw per 100 ms: a pending one is not postponed by the events that follow
      pending = true
      handler.postDelayed(refresh, 100)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

  private var pending = false
  private val refresh = Runnable {
    pending = false
    show()
  }

  private fun show() {
    header.text = "sensors: ${list.size}"
    val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    default.text = "default accelerometer: ${acc?.name ?: "none"}"
    for ((i, s) in list.withIndex()) {
      views[i].text = String.format(
        Locale.ROOT, "%s (type %d, range %s, res %s, min %d us): %s, n=%d%s", s.name, s.type, s.maximumRange, s.resolution,
        s.minDelay, last[i] ?: "no value", counts[i] ?: 0, if (i in refused) " (refused)" else "",
      )
    }
  }
}
