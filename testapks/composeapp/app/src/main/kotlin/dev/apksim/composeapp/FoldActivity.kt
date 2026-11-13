package dev.apksim.composeapp

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * apksim://fold: the fold as apps read it, through Jetpack WindowManager's WindowInfoTracker: "fold: none" on a
 * device without one or a closed one, else its state, orientation and bounds in the window ("fold: flat vertical
 * 1038,0-1038,2152"), with the window's size in dp, how many layouts were heard, and the hinge-angle sensor's last
 * value ("hinge: 180", or "hinge: no sensor"). Not declared to handle size
 * changes, so folding recreates it, as most apps (apksim: `--device pixel-10-pro-fold`, `fold open|half|closed`).
 */
class FoldActivity : Activity() {
  private val scope = MainScope()
  private var updates = 0
  private var hinge: TextView? = null
  private val hingeListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      hinge?.text = "hinge: ${event.values[0].toInt()}"
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val fold = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; text = "fold: waiting" }
    val window = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
    val heard = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; text = "layouts: 0" }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      addView(fold)
      addView(window)
      addView(heard)
      addView(TextView(this@FoldActivity).apply { textSize = 18f; gravity = Gravity.CENTER }.also { hinge = it })
    })
    val sensors = getSystemService(SensorManager::class.java)
    val sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    if (sensor == null) hinge?.text = "hinge: no sensor"
    else {
      hinge?.text = "hinge: waiting"
      sensors.registerListener(hingeListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }
    val c = resources.configuration
    window.text = "window: ${c.screenWidthDp}x${c.screenHeightDp} dp"
    scope.launch {
      WindowInfoTracker.getOrCreate(this@FoldActivity).windowLayoutInfo(this@FoldActivity).collect { info ->
        val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        fold.text = if (f == null) "fold: none" else {
          val state = if (f.state == FoldingFeature.State.HALF_OPENED) "half-opened" else "flat"
          val orientation = if (f.orientation == FoldingFeature.Orientation.VERTICAL) "vertical" else "horizontal"
          val b = f.bounds
          "fold: $state $orientation ${b.left},${b.top}-${b.right},${b.bottom}"
        }
        heard.text = "layouts: ${++updates}"
      }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    getSystemService(SensorManager::class.java).unregisterListener(hingeListener)
    super.onDestroy()
  }
}
