package dev.apksim.composeapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

/**
 * apksim://ble: a heart-rate app in miniature, against apksim's scripted peripherals (`--ble heart-rate`, docs/ble.md).
 * It asks for BLUETOOTH_SCAN, BLUETOOTH_CONNECT and fine location (the manifest does not mark the scan
 * neverForLocation, so a device delivers scan results only with location), scans for the Heart Rate service (0x180D),
 * connects to the first peripheral found, discovers its services, reads the body sensor location, subscribes to the
 * heart rate measurement (setCharacteristicNotification and the CCC descriptor), asks for a 247-byte MTU and shows
 * every notification: "heart rate: 74 bpm (3 notifications)". `?legacy=1` uses the callbacks deprecated in API 33
 * (the value read from the characteristic), as an older app does. Buttons write the control point (0x2A39 = 01),
 * read the RSSI and disconnect. scripts/ble-check.py drives it over --control.
 */
@SuppressLint("MissingPermission")
class BleActivity : Activity() {
  private val lines = LinkedHashMap<String, TextView>()
  private var gatt: BluetoothGatt? = null
  private var notifications = 0
  private var scanning = false
  private val legacy get() = intent.data?.getQueryParameter("legacy") == "1"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.rgb(0x60, 0x20, 0x30))
    }
    for (key in listOf("ble", "found", "state", "sensor", "mtu", "heart rate", "rssi", "write")) {
      val v = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
      lines[key] = v
      root.addView(v)
    }
    root.addView(Button(this).apply { text = "Write control point"; setOnClickListener { writeControlPoint() } })
    root.addView(Button(this).apply { text = "Read RSSI"; setOnClickListener { gatt?.readRemoteRssi() } })
    root.addView(Button(this).apply { text = "Disconnect"; setOnClickListener { gatt?.disconnect() } })
    setContentView(root)
    show("ble", "starting")
    val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) scan() else requestPermissions(missing.toTypedArray(), 1)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scan() else show("ble", "permission denied")
  }

  override fun onDestroy() {
    stopScan()
    gatt?.close()
    super.onDestroy()
  }

  private fun show(key: String, text: String) = runOnUiThread { lines[key]?.text = "$key: $text" }

  private val scanner get() = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

  private fun scan() {
    val s = scanner ?: return show("ble", "no scanner (Bluetooth off?)")
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
    s.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    scanning = true
    show("ble", "scanning")
  }

  private fun stopScan() {
    if (scanning) runCatching { scanner?.stopScan(scanCallback) }
    scanning = false
  }

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      if (gatt != null) return
      stopScan()
      show("ble", "found, connecting")
      show("found", "${result.device.name} ${result.device.address} ${result.rssi} dBm")
      gatt = result.device.connectGatt(this@BleActivity, false, if (legacy) legacyCallback else gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun onScanFailed(errorCode: Int) = show("ble", "scan failed $errorCode")
  }

  /** What both callback styles share: the order of a typical heart-rate app's setup. */
  private fun onState(g: BluetoothGatt, status: Int, newState: Int) {
    if (newState == BluetoothProfile.STATE_CONNECTED) {
      show("state", "connected")
      g.discoverServices()
    } else {
      show("state", "disconnected (status $status)")
    }
  }

  private fun onDiscovered(g: BluetoothGatt) {
    show("state", "connected, ${g.services.size} services")
    val sensor = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(BODY_SENSOR_LOCATION) ?: return
    g.readCharacteristic(sensor)
  }

  private fun onSensorRead(g: BluetoothGatt, value: ByteArray) {
    show("sensor", if (value.isNotEmpty() && value[0].toInt() == 1) "chest" else "code ${value.firstOrNull()}")
    val hr = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return
    g.setCharacteristicNotification(hr, true)
    val ccc = hr.getDescriptor(CCC) ?: return
    if (legacy) {
      @Suppress("DEPRECATION")
      ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      @Suppress("DEPRECATION")
      g.writeDescriptor(ccc)
    } else {
      g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }
  }

  private fun onHeartRate(value: ByteArray) {
    notifications++
    val flags = value[0].toInt()
    val bpm = if (flags and 1 != 0) (value[1].toInt() and 0xff) or ((value[2].toInt() and 0xff) shl 8) else value[1].toInt() and 0xff
    show("heart rate", "$bpm bpm ($notifications notifications)")
  }

  private fun writeControlPoint() {
    val g = gatt ?: return
    val cp = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(CONTROL_POINT) ?: return
    if (legacy) {
      @Suppress("DEPRECATION")
      cp.value = byteArrayOf(1)
      @Suppress("DEPRECATION")
      g.writeCharacteristic(cp)
    } else {
      g.writeCharacteristic(cp, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onState(g, status, newState)
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onDiscovered(g)
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = onSensorRead(g, value)
    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { g.requestMtu(247) }
    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = show("mtu", "$mtu")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onHeartRate(value)
    override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = show("rssi", "$rssi dBm")
    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = show("write", "status $status")
  }

  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  private val legacyCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onState(g, status, newState)
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onDiscovered(g)
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = onSensorRead(g, c.value)
    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { g.requestMtu(247) }
    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = show("mtu", "$mtu")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = onHeartRate(c.value)
    override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = show("rssi", "$rssi dBm")
    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = show("write", "status $status")
  }

  companion object {
    val PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    private fun uuid16(short: Int): UUID = UUID.fromString(String.format("%08X-0000-1000-8000-00805F9B34FB", short))
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val BODY_SENSOR_LOCATION: UUID = uuid16(0x2A38)
    val CONTROL_POINT: UUID = uuid16(0x2A39)
    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)
    val CCC: UUID = uuid16(0x2902)
  }
}
