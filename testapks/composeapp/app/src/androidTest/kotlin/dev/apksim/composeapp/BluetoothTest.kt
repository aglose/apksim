package dev.apksim.composeapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The Bluetooth adapter and the device's features as an emulator has them (apksim's Bluetooth and Features): the
 * adapter on, with a BLE scanner, android.hardware.bluetooth and bluetooth_le present, `pm list features` listing them,
 * and `svc bluetooth disable|enable` switching the adapter with ACTION_STATE_CHANGED broadcasts. In a test run each
 * test starts again from the launch's state (on), as Robolectric resets the adapter. Passes on an emulator too, but for
 * that reset, which is apksim's alone.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BluetoothTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val onApksim = Build.FINGERPRINT == "robolectric"
  private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  /** Runs the main queue (apksim: the test is on the main thread) until [done], for up to 10 s. */
  private fun waitFor(what: String, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!done()) {
      assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
      instrumentation.waitForIdleSync()
      Thread.sleep(50)
    }
  }

  /**
   * The runtime permission getName(), getAddress() and the app's own enable()/disable() need from API 31 (the app
   * targets 36; isEnabled(), getState() and getBluetoothLeScanner() need none), granted as a user would.
   */
  @Before
  fun grantBluetoothConnect() {
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
    assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT))
  }

  @After
  fun bluetoothBackOn() {
    if (!onApksim) shell("svc bluetooth enable")  // a device keeps what a test left
  }

  @Test
  fun a_theAdapterIsOnWithAScannerAsOnAnEmulator() {
    val pm = context.packageManager
    assertTrue(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
    assertTrue(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
    val a = adapter
    assertNotNull("BluetoothManager.getAdapter()", a)
    assertTrue("enabled", a.isEnabled)
    assertEquals(BluetoothAdapter.STATE_ON, a.state)
    assertNotNull("getBluetoothLeScanner()", a.bluetoothLeScanner)
    assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON))
    if (onApksim) {
      assertEquals(Build.MODEL, a.name)
      assertEquals("02:00:00:00:00:00", a.address)
    }
  }

  @Test
  fun b_pmListsTheDevicesFeatures() {
    val lines = shell("pm list features").lines()
    for (f in listOf(PackageManager.FEATURE_BLUETOOTH, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_TOUCHSCREEN, PackageManager.FEATURE_WIFI)) {
      assertTrue("feature:$f in $lines", "feature:$f" in lines)
    }
    // What PackageManager reports to the app is what pm prints.
    val available = context.packageManager.systemAvailableFeatures.mapNotNull { it.name }.toSet()
    val printed = lines.filter { it.startsWith("feature:") && !it.startsWith("feature:reqGlEsVersion") }
      .map { it.removePrefix("feature:").substringBefore('=') }.toSet()
    assertEquals(printed, available)
  }

  @Test
  fun c_svcSwitchesTheAdapterWithStateBroadcasts() {
    val seen = mutableListOf<Int>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        seen += intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
      }
    }
    context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    try {
      shell("svc bluetooth disable")
      waitFor("STATE_OFF broadcast") { BluetoothAdapter.STATE_OFF in seen }
      assertEquals(listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF), seen)
      assertTrue("disabled", !adapter.isEnabled)
      assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON))
      seen.clear()
      shell("svc bluetooth enable")
      waitFor("STATE_ON broadcast") { BluetoothAdapter.STATE_ON in seen }
      assertEquals(listOf(BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_ON), seen)
      assertTrue("enabled", adapter.isEnabled)
      // Left off on purpose: the next test must start with it on again.
      shell("svc bluetooth disable")
      waitFor("disabled") { !adapter.isEnabled }
    } finally {
      context.unregisterReceiver(receiver)
    }
  }

  @Test
  fun d_eachTestStartsWithTheLaunchsState() {
    assumeTrue("Robolectric's per-test reset", onApksim)
    assertTrue("on again after the last test turned it off", adapter.isEnabled)
  }

  @Test
  fun e_settingsAndCmdSwitchItToo() {
    assumeTrue("settings put global bluetooth_on switches the adapter on apksim, not on a device", onApksim)
    shell("settings put global bluetooth_on 0")
    assertTrue("off", !adapter.isEnabled)
    shell("cmd bluetooth_manager enable")
    assertTrue("on", adapter.isEnabled)
    assertTrue(shell("dumpsys bluetooth_manager"), shell("dumpsys bluetooth_manager").contains("enabled: true"))
  }

  @Test
  @Suppress("DEPRECATION", "MissingPermission")
  fun f_theAppsOwnDisableBroadcastsAndToggleReadsTheAdapter() {
    assumeTrue("apksim's `bluetooth` command", onApksim)
    val seen = mutableListOf<Int>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        seen += intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
      }
    }
    context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    try {
      assertTrue(adapter.disable())
      waitFor("STATE_OFF broadcast") { BluetoothAdapter.STATE_OFF in seen }
      assertEquals(listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF), seen)
      assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON))
      // toggle reads the adapter, which the app turned off: it turns it on.
      shell("apksim bluetooth toggle")
      assertTrue("on after toggle", adapter.isEnabled)
    } finally {
      context.unregisterReceiver(receiver)
    }
  }

  @Test
  @Suppress("DEPRECATION", "MissingPermission")
  fun g_bluetoothOnIsWrittenEvenWithNoSwitchToMake() {
    assumeTrue("settings put global bluetooth_on switches the adapter on apksim, not on a device", onApksim)
    assertTrue(adapter.disable())
    // Written behind the adapter's back, as an app with WRITE_SECURE_SETTINGS (or a test) might.
    Settings.Global.putInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 1)
    shell("settings put global bluetooth_on 0")
    assertTrue("still off", !adapter.isEnabled)
    assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON))
  }
}
