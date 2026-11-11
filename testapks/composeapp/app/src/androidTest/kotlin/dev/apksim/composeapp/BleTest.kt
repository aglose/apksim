package dev.apksim.composeapp

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * apksim's scripted BLE peripherals (docs/ble.md) as an app reaches them through the framework's own
 * BluetoothLeScanner and BluetoothGatt: scans with filters, callback types, batching and a PendingIntent; a connection,
 * discovery, reads, writes, notifications only once both setCharacteristicNotification and the CCC descriptor ask for
 * them; MTU, RSSI, the peripheral going away, a direct connection timing out, bonding, permissions and location as a
 * device enforces them, and the adapter switched off. Peripherals come from `apksim ble ...` shell calls and are gone at
 * the next test's setup. apksim only: an emulator has no BLE peripherals to find.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BleTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
  private val scanner: BluetoothLeScanner get() = adapter.bluetoothLeScanner
  private val gatts = mutableListOf<BluetoothGatt>()

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }.trim()
  }

  /** Runs the main queue until [done], moving the simulated clock [stepMs] a turn (timelines follow it), 10 s at most. */
  private fun waitFor(what: String, stepMs: Long = 0, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!done()) {
      assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
      if (stepMs > 0) SystemClock.sleep(stepMs)
      instrumentation.waitForIdleSync()
      Thread.sleep(20)
    }
  }

  /** Lets [ms] of the simulated clock pass, running what it brings. */
  private fun pass(ms: Long) {
    var left = ms
    while (left > 0) {
      SystemClock.sleep(minOf(left, 100))
      left -= 100
      instrumentation.waitForIdleSync()
      Thread.sleep(5)
    }
    Thread.sleep(50)
    instrumentation.waitForIdleSync()
  }

  @Before
  fun setUp() {
    assumeTrue("apksim only: an emulator has no scripted peripherals", Build.FINGERPRINT == "robolectric")
    for (p in BleActivity.PERMISSIONS) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
  }

  @After
  fun tearDown() {
    gatts.forEach { it.close() }
  }

  private class Scan : ScanCallback() {
    val results: MutableList<Pair<Int, ScanResult>> = Collections.synchronizedList(mutableListOf())
    val batches: MutableList<List<ScanResult>> = Collections.synchronizedList(mutableListOf())
    @Volatile var failed = 0
    @Volatile var onMain = true
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      onMain = onMain && Looper.myLooper() == Looper.getMainLooper()
      results += callbackType to result
    }
    override fun onBatchScanResults(results: MutableList<ScanResult>) { batches += results.toList() }
    override fun onScanFailed(errorCode: Int) { failed = errorCode }
  }

  private fun filter(short: Int) = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid16(short))).build()
  private fun uuid16(short: Int) = java.util.UUID.fromString(String.format("%08X-0000-1000-8000-00805F9B34FB", short))

  /** Records every GATT callback (API 33 signatures) as a line, and the thread each came on. */
  private open class Recorder : BluetoothGattCallback() {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var thread: Thread? = null
    fun has(prefix: String) = synchronized(events) { events.any { it.startsWith(prefix) } }
    fun count(prefix: String) = synchronized(events) { events.count { it.startsWith(prefix) } }
    private fun add(e: String) { thread = Thread.currentThread(); events += e }
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = add("state $status $newState")
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = add("discovered $status ${g.services.size}")
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
      add("read ${hex(value)} $status")
    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = add("write $status")
    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = add("descriptor $status")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
      add("changed ${hex(value)}")
    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = add("mtu $mtu $status")
    override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = add("rssi $rssi $status")
    override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = add("phy update $txPhy $rxPhy $status")
    override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = add("phy read $txPhy $rxPhy $status")
  }

  private fun connect(address: String, cb: BluetoothGattCallback, handler: Handler? = null, auto: Boolean = false): BluetoothGatt {
    val device = adapter.getRemoteDevice(address)
    val g = if (handler != null) {
      device.connectGatt(context, auto, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
    } else {
      device.connectGatt(context, auto, cb)
    }
    assertNotNull("connectGatt", g)
    gatts += g
    return g
  }

  private fun connected(address: String, cb: Recorder = Recorder()): Pair<BluetoothGatt, Recorder> {
    val g = connect(address, cb)
    waitFor("connected") { cb.has("state 0 2") }
    g.discoverServices()
    waitFor("discovery") { cb.has("discovered") }
    return g to cb
  }

  @Test
  fun a_aScanFindsThePeripheralWithItsAdvertisementOnTheMainThread() {
    assertTrue(shell("apksim ble add heart-rate"), shell("apksim ble").contains("C0:FF:EE:00:18:0D"))
    val scan = Scan()
    scanner.startScan(listOf(filter(0x180D)), ScanSettings.Builder().build(), scan)
    waitFor("a scan result") { scan.results.isNotEmpty() }
    scanner.stopScan(scan)
    val (type, r) = scan.results[0]
    assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, type)
    assertTrue("results on the main thread, as BluetoothLeScanner posts them", scan.onMain)
    assertEquals("C0:FF:EE:00:18:0D", r.device.address)
    assertEquals("apksim HR", r.device.name)
    assertEquals("apksim HR", r.scanRecord?.deviceName)
    assertEquals(-58, r.rssi)
    assertTrue(r.isConnectable)
    assertEquals(listOf(ParcelUuid(uuid16(0x180D))), r.scanRecord?.serviceUuids)
  }

  @Test
  fun a_bFiltersAndRepeatsFollowTheAdvertisingIntervalOnTheClock() {
    shell("apksim ble add heart-rate")
    shell("apksim ble add battery")
    val scan = Scan()
    scanner.startScan(listOf(filter(0x180F)), ScanSettings.Builder().build(), scan)
    waitFor("a scan result") { scan.results.isNotEmpty() }
    pass(3_000)  // the battery preset advertises every second
    scanner.stopScan(scan)
    assertTrue("only the battery: ${scan.results.map { it.second.device.address }}",
      scan.results.all { it.second.device.address == "C0:FF:EE:00:18:0F" })
    assertTrue("a result per advertising interval: ${scan.results.size}", scan.results.size in 3..5)
  }

  @Test
  fun a_cFirstMatchAndMatchLost() {
    shell("apksim ble add heart-rate")
    val scan = Scan()
    val settings = ScanSettings.Builder()
      .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST).build()
    scanner.startScan(listOf(filter(0x180D)), settings, scan)
    waitFor("found") { scan.results.isNotEmpty() }
    pass(2_000)
    assertEquals(1, scan.results.size)
    assertEquals(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, scan.results[0].first)
    shell("apksim ble remove C0:FF:EE:00:18:0D")
    waitFor("lost") { scan.results.size == 2 }
    assertEquals(ScanSettings.CALLBACK_TYPE_MATCH_LOST, scan.results[1].first)
    scanner.stopScan(scan)
  }

  @Test
  fun a_dBatchedResultsAndAPendingIntentScan() {
    shell("apksim ble add heart-rate")
    val scan = Scan()
    scanner.startScan(null, ScanSettings.Builder().setReportDelay(2_000).build(), scan)
    pass(2_100)
    waitFor("a batch") { scan.batches.isNotEmpty() }
    assertTrue(scan.results.isEmpty())
    assertEquals("C0:FF:EE:00:18:0D", scan.batches[0][0].device.address)
    scanner.stopScan(scan)

    val action = "dev.apksim.composeapp.BLE_SCAN"
    val got = Collections.synchronizedList(mutableListOf<ScanResult>())
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        got += intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
      }
    }
    context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
    val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    assertEquals(0, scanner.startScan(listOf(filter(0x180D)), ScanSettings.Builder().build(), pi))
    waitFor("a result by PendingIntent") { got.isNotEmpty() }
    scanner.stopScan(pi)
    context.unregisterReceiver(receiver)
    assertEquals("C0:FF:EE:00:18:0D", got[0].device.address)
  }

  @Test
  fun b_aConnectDiscoverReadWriteOnABinderThread() {
    shell("apksim ble add heart-rate")
    val (g, cb) = connected("C0:FF:EE:00:18:0D")
    assertTrue(cb.events.toString(), cb.has("discovered 0 2"))
    val t = cb.thread!!
    assertFalse("callbacks without a Handler come on a binder thread", t == Looper.getMainLooper().thread)
    val hr = g.getService(BleActivity.HEART_RATE_SERVICE)
    assertNotNull(hr)
    assertEquals(3, hr.characteristics.size)
    g.readCharacteristic(hr.getCharacteristic(BleActivity.BODY_SENSOR_LOCATION))
    waitFor("read") { cb.has("read") }
    assertTrue(cb.events.toString(), cb.has("read 01 0"))
    val status = g.writeCharacteristic(hr.getCharacteristic(BleActivity.CONTROL_POINT), byteArrayOf(1),
      BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    assertEquals(0, status)
    waitFor("write") { cb.has("write") }
    assertTrue(cb.events.toString(), cb.has("write 0"))
    assertTrue(shell("apksim ble"), shell("apksim ble").contains("written 2A39=01"))
    // A write the characteristic does not allow is refused by BluetoothGatt itself.
    assertEquals(200 /* ERROR_GATT_WRITE_NOT_ALLOWED */, g.writeCharacteristic(
      hr.getCharacteristic(BleActivity.BODY_SENSOR_LOCATION), byteArrayOf(2), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
    g.requestMtu(247)
    waitFor("mtu") { cb.has("mtu") }
    assertTrue(cb.events.toString(), cb.has("mtu 247 0"))
    g.readRemoteRssi()
    waitFor("rssi") { cb.has("rssi") }
    shell("apksim ble rssi C0:FF:EE:00:18:0D -80")
    g.readRemoteRssi()
    waitFor("second rssi") { cb.count("rssi") == 2 }
    assertEquals(listOf("rssi -58 0", "rssi -80 0"), cb.events.filter { it.startsWith("rssi") })
    g.readPhy()
    waitFor("phy") { cb.has("phy read") }
    g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
    waitFor("phy update") { cb.has("phy update") }
    g.readPhy()
    waitFor("second phy read") { cb.count("phy read") == 2 }
    assertEquals("the PHY read back is the one the update reported",
      listOf("phy read 1 1 0", "phy update 2 2 0", "phy read 2 2 0"), cb.events.filter { it.startsWith("phy") })
  }

  @Test
  fun b_dAScanStartedFromAGattCallbackWithoutAHandler() {
    shell("apksim ble add heart-rate")
    shell("apksim ble add battery")
    val scan = Scan()
    val started = java.util.concurrent.CountDownLatch(1)
    val cb = object : Recorder() {
      override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(g, status)
        // On the binder thread: BluetoothLeScanner waits there for the scanner's registration.
        scanner.startScan(listOf(filter(0x180F)), ScanSettings.Builder().build(), scan)
        started.countDown()
      }
    }
    val g = connect("C0:FF:EE:00:18:0D", cb)
    waitFor("connected") { cb.has("state 0 2") }
    g.discoverServices()
    waitFor("the scan started from the callback") { started.count == 0L }
    waitFor("a result for that scan") { scan.results.isNotEmpty() || scan.failed != 0 }
    scanner.stopScan(scan)
    assertEquals("the registration answered in time, not SCAN_FAILED_APPLICATION_REGISTRATION_FAILED", 0, scan.failed)
    assertEquals("C0:FF:EE:00:18:0F", scan.results[0].second.device.address)
  }

  @Test
  fun b_bNotificationsNeedTheCccDescriptorAndFollowTheTimeline() {
    shell("apksim ble add heart-rate")
    val (g, cb) = connected("C0:FF:EE:00:18:0D")
    val hr = g.getService(BleActivity.HEART_RATE_SERVICE).getCharacteristic(BleActivity.HEART_RATE_MEASUREMENT)
    assertTrue(g.setCharacteristicNotification(hr, true))
    pass(3_000)
    assertFalse("no notifications before the CCC write: ${cb.events}", cb.has("changed"))
    assertEquals(0, g.writeDescriptor(hr.getDescriptor(BleActivity.CCC), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    waitFor("descriptor") { cb.has("descriptor") }
    pass(3_000)
    waitFor("three notifications") { cb.count("changed") >= 3 }
    val changed = cb.events.filter { it.startsWith("changed") }.map { it.removePrefix("changed ") }.take(3)
    assertTrue("three steps of the preset's timeline in order: $changed",
      HR_TIMELINE.indices.any { k -> changed == (0..2).map { HR_TIMELINE[(k + it) % HR_TIMELINE.size] } })
    shell("apksim ble notify C0:FF:EE:00:18:0D 2A37 00C8")
    waitFor("the scripted notification") { cb.has("changed 00C8") }
    assertTrue(shell("apksim ble"), shell("apksim ble").contains("notifying 2A37"))
  }

  @Test
  fun b_cTheDeprecatedCallbacksAndAHandler() {
    shell("apksim ble add heart-rate")
    val events = Collections.synchronizedList(mutableListOf<String>())
    var mainOnly = true
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    val cb = object : BluetoothGattCallback() {
      fun add(e: String) { mainOnly = mainOnly && Looper.myLooper() == Looper.getMainLooper(); events += e }
      override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        add("state $newState"); if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
      }
      override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        add("discovered")
        val c = g.getService(BleActivity.HEART_RATE_SERVICE).getCharacteristic(BleActivity.BODY_SENSOR_LOCATION)
        g.readCharacteristic(c)
      }
      override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        add("read ${hex(c.value)}")
        val hr = g.getService(BleActivity.HEART_RATE_SERVICE).getCharacteristic(BleActivity.HEART_RATE_MEASUREMENT)
        g.setCharacteristicNotification(hr, true)
        val d = hr.getDescriptor(BleActivity.CCC)
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
      }
      override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = add("changed ${hex(c.value)}")
    }
    connect("C0:FF:EE:00:18:0D", cb, Handler(Looper.getMainLooper()))
    waitFor("subscribed") { events.contains("read 01") }
    pass(1_000)
    waitFor("a notification", 100) { events.any { it.startsWith("changed") } }
    assertTrue(events.toString(), events.any { it.removePrefix("changed ") in HR_TIMELINE })
    assertTrue("every callback on the Handler's thread", mainOnly)
  }

  @Test
  fun c_aThePeripheralGoingAwayOrHangingUp() {
    shell("apksim ble add heart-rate")
    val (_, cb) = connected("C0:FF:EE:00:18:0D")
    shell("apksim ble disconnect C0:FF:EE:00:18:0D")
    waitFor("the peer's disconnect") { cb.has("state 19 0") }
    shell("apksim ble add battery")
    val (_, cb2) = connected("C0:FF:EE:00:18:0F")
    shell("apksim ble remove C0:FF:EE:00:18:0F")
    waitFor("the link timing out") { cb2.has("state 8 0") }
  }

  @Test
  fun c_aaAnswerDelayedByLatencyIsDroppedWhenTheLinkEndsFirst() {
    val file = java.io.File(context.cacheDir, "slow-peripheral.json")
    file.writeText("""[{"address": "C0:FF:EE:00:00:51", "name": "slow\nstrap \"two\"", "latencyMs": 1000,
      "advertisement": {"serviceUuids": ["180D"]},
      "services": [{"uuid": "180D", "characteristics": [{"uuid": "2A38", "properties": ["read"], "value": "01"}]}]}]""")
    shell("apksim ble add ${file.absolutePath}")
    val status = shell("apksim ble")
    assertFalse("one line, whatever the name holds: $status", status.contains('\n'))
    assertTrue(status, status.contains("\"slow\\nstrap \\\"two\\\"\""))
    val cb = Recorder()
    connect("C0:FF:EE:00:00:51", cb)
    pass(300)
    assertFalse("the connection's answer waits for the latency: ${cb.events}", cb.has("state"))
    shell("apksim ble remove C0:FF:EE:00:00:51")
    waitFor("the link timing out") { cb.has("state 8 0") }
    pass(2_000)
    assertEquals("no late 'connected' for a link that already ended", listOf("state 8 0"), cb.events.filter { it.startsWith("state") })
  }

  @Test
  fun c_bADirectConnectionToNothingFailsAfterThirtySeconds() {
    val cb = Recorder()
    connect("C0:FF:EE:00:00:99", cb)
    pass(29_000)
    assertFalse(cb.events.toString(), cb.has("state"))
    pass(1_500)
    waitFor("GATT_ERROR") { cb.has("state 133 0") }
  }

  @Test
  fun c_cAnAutoConnectWaitsForThePeripheral() {
    val cb = Recorder()
    connect("C0:FF:EE:00:18:0D", cb, auto = true)
    pass(40_000)
    assertFalse(cb.events.toString(), cb.has("state"))
    shell("apksim ble add heart-rate")
    waitFor("connected once it advertises") { cb.has("state 0 2") }
  }

  @Test
  fun d_aBonding() {
    shell("apksim ble add heart-rate")
    val states = Collections.synchronizedList(mutableListOf<Int>())
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        states += intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
      }
    }
    context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    val device = adapter.getRemoteDevice("C0:FF:EE:00:18:0D")
    assertTrue(device.createBond())
    pass(200)
    waitFor("bonded") { states.size == 2 }
    context.unregisterReceiver(receiver)
    assertEquals(listOf(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED), states.toList())
    assertEquals(BluetoothDevice.BOND_BONDED, device.bondState)
    assertTrue(adapter.bondedDevices.any { it.address == "C0:FF:EE:00:18:0D" })
  }

  @Test
  fun e_aScanningWithoutBluetoothScanThrows() {
    shell("pm revoke ${context.packageName} ${Manifest.permission.BLUETOOTH_SCAN}")
    try {
      scanner.startScan(Scan())
      fail("startScan without BLUETOOTH_SCAN")
    } catch (e: SecurityException) {
      assertTrue(e.message, e.message!!.contains("BLUETOOTH_SCAN"))
    }
  }

  @Test
  fun e_bWithoutLocationAScanRunsAndFindsNothing() {
    shell("apksim ble add heart-rate")
    shell("pm revoke ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
    val scan = Scan()
    scanner.startScan(scan)
    pass(3_000)
    scanner.stopScan(scan)
    assertEquals(0, scan.failed)
    assertTrue("no results without location (the scan is not neverForLocation)", scan.results.isEmpty())
  }

  @Test
  fun e_bbRevokingLocationMidScanStopsTheResults() {
    shell("apksim ble add heart-rate")
    val scan = Scan()
    scanner.startScan(scan)
    waitFor("a result") { scan.results.isNotEmpty() }
    shell("pm revoke ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
    instrumentation.waitForIdleSync()
    val before = scan.results.size
    pass(3_000)
    scanner.stopScan(scan)
    assertEquals("no results once location is revoked", before, scan.results.size)
  }

  @Test
  fun e_cConnectingWithoutBluetoothConnectThrows() {
    shell("apksim ble add heart-rate")
    shell("pm revoke ${context.packageName} ${Manifest.permission.BLUETOOTH_CONNECT}")
    try {
      adapter.getRemoteDevice("C0:FF:EE:00:18:0D").connectGatt(context, false, Recorder())
      fail("connectGatt without BLUETOOTH_CONNECT")
    } catch (e: SecurityException) {
      assertTrue(e.message, e.message!!.contains("BLUETOOTH_CONNECT"))
    }
  }

  @Test
  fun e_dASixthScanWithinThirtySecondsHearsNothing() {
    shell("apksim ble add heart-rate")
    repeat(5) {
      val s = Scan()
      scanner.startScan(s)
      waitFor("scan ${it + 1}") { s.results.isNotEmpty() }
      scanner.stopScan(s)
    }
    val sixth = Scan()
    scanner.startScan(sixth)
    pass(2_000)
    assertTrue("throttled scans report nothing, not even onScanFailed", sixth.results.isEmpty() && sixth.failed == 0)
    scanner.stopScan(sixth)
  }

  @Test
  fun f_aTheAdapterGoingOffDropsConnectionsAndTheScanner() {
    shell("apksim ble add heart-rate")
    val (_, cb) = connected("C0:FF:EE:00:18:0D")
    shell("svc bluetooth disable")
    waitFor("the connection dropped") { cb.has("state 22 0") }
    // apksim keeps an LE scanner while off (BLE scanning always available, Bluetooth.java); the stack has no scan
    // service then, so BluetoothLeScanner reports SCAN_FAILED_INTERNAL_ERROR.
    adapter.bluetoothLeScanner?.let { s ->
      val scan = Scan()
      s.startScan(scan)
      waitFor("the scan failing") { scan.failed != 0 }
      assertEquals(ScanCallback.SCAN_FAILED_INTERNAL_ERROR, scan.failed)
      assertTrue(scan.results.isEmpty())
    }
    assertNull("no GATT while Bluetooth is off", adapter.getRemoteDevice("C0:FF:EE:00:18:0D").connectGatt(context, false, Recorder()))
    assertEquals(BluetoothAdapter.STATE_OFF, adapter.state)
  }

  @Test
  fun z_eachTestStartsWithTheLaunchsPeripheralsOnly() {
    assertTrue(shell("apksim ble"), shell("apksim ble").startsWith("no peripherals"))
  }

  companion object {
    val HR_TIMELINE = listOf("0048", "004A", "004C", "004E", "0050", "004E", "004C", "004A")
    fun hex(b: ByteArray?) = b?.joinToString("") { String.format("%02X", it) } ?: "null"
  }
}
