package dev.apksim.composeapp

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://bluetooth: the Bluetooth adapter as an app sees it ("bluetooth: on, scanner yes"), the ACTION_STATE_CHANGED
 * broadcasts received since the screen opened ("broadcasts: TURNING_OFF OFF"), and the features an app checks first
 * ("features: bluetooth yes, ble yes"). scripts/adb-check.py switches it with `svc bluetooth` and reads these lines.
 */
class BluetoothActivity : Activity() {
  private lateinit var state: TextView
  private lateinit var broadcasts: TextView
  private val seen = mutableListOf<String>()

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      seen += name(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
      show()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    state = TextView(this).apply { textSize = 20f }
    broadcasts = TextView(this).apply { textSize = 20f }
    val features = TextView(this).apply {
      textSize = 20f
      text = "features: bluetooth ${yes(PackageManager.FEATURE_BLUETOOTH)}, ble ${yes(PackageManager.FEATURE_BLUETOOTH_LE)}"
    }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.rgb(0x20, 0x40, 0x80))
      addView(state)
      addView(broadcasts)
      addView(features)
    })
    registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_EXPORTED)
    show()
  }

  override fun onDestroy() {
    unregisterReceiver(receiver)
    super.onDestroy()
  }

  private fun show() {
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter
    state.text = if (adapter == null) "bluetooth: no adapter"
    else "bluetooth: ${if (adapter.isEnabled) "on" else "off"}, scanner ${if (adapter.bluetoothLeScanner != null) "yes" else "no"}"
    broadcasts.text = "broadcasts: ${if (seen.isEmpty()) "none" else seen.joinToString(" ")}"
  }

  private fun yes(feature: String) = if (packageManager.hasSystemFeature(feature)) "yes" else "no"

  companion object {
    fun name(state: Int) = when (state) {
      BluetoothAdapter.STATE_OFF -> "OFF"
      BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
      BluetoothAdapter.STATE_ON -> "ON"
      BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
      else -> "state$state"
    }
  }
}
