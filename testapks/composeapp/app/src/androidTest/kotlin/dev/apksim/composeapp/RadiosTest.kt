package dev.apksim.composeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * apksim's radios (Radios.java): Wi-Fi and mobile data switched apart from `network offline`, and Wi-Fi's signal, as
 * ConnectivityManager, WifiManager, TelephonyManager and their broadcasts show them, through the test's shell (`apksim
 * network wifi|cellular ...`, `svc wifi|data`, `cmd wifi`). Each test starts with the launch's radios (Wi-Fi on at
 * -50 dBm, no mobile data). apksim only: an emulator has mobile data of its own, and these commands are apksim's.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RadiosTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val cm get() = context.getSystemService(ConnectivityManager::class.java)
  private val wm get() = context.getSystemService(WifiManager::class.java)
  private val main = Handler(Looper.getMainLooper())

  @Before
  fun onApksim() = assumeTrue("apksim's radio commands", Build.FINGERPRINT == "robolectric")

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  /** Runs the main queue (the test is on the main thread) until [done], for up to 10 s. */
  private fun waitFor(what: String, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!done()) {
      assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
      instrumentation.waitForIdleSync()
      Thread.sleep(20)
    }
  }

  private fun cellularNetworks() =
    cm.allNetworks.count { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }

  private class Recorder : ConnectivityManager.NetworkCallback() {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var caps: NetworkCapabilities? = null
    override fun onAvailable(network: Network) { events += "available" }
    override fun onLost(network: Network) { events += "lost" }
    override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) { caps = c }
  }

  @Suppress("DEPRECATION")
  @Test
  fun a_wifiOffWithoutMobileDataLeavesNoNetwork() {
    val cb = Recorder()
    cm.registerDefaultNetworkCallback(cb, main)
    try {
      waitFor("the default network") { cb.events == listOf("available") }
      shell("apksim network wifi off")
      waitFor("onLost") { cb.events == listOf("available", "lost") }
      assertNull(cm.activeNetwork)
      assertFalse(wm.isWifiEnabled)
      assertEquals(WifiManager.WIFI_STATE_DISABLED, wm.wifiState)
      assertEquals(-127, wm.connectionInfo.rssi)
      assertTrue(shell("apksim network").contains("wifi off; mobile data off"))

      shell("apksim network wifi on")
      waitFor("onAvailable again") { cb.events == listOf("available", "lost", "available") }
      assertTrue(wm.isWifiEnabled)
      assertTrue(cm.getNetworkCapabilities(cm.activeNetwork)!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    } finally {
      cm.unregisterNetworkCallback(cb)
    }
  }

  @Suppress("DEPRECATION")
  @Test
  fun b_mobileDataTakesOverWhenWifiGoesOff() {
    val cellular = Recorder()
    val default = Recorder()
    cm.registerNetworkCallback(
      NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(), cellular, main)
    cm.registerDefaultNetworkCallback(default, main)
    try {
      waitFor("the default network") { default.events == listOf("available") }
      shell("apksim network cellular on")
      waitFor("a cellular network") { cellular.events == listOf("available") }
      // Wi-Fi stays the default while it is up, as a phone keeps both.
      assertTrue(cm.getNetworkCapabilities(cm.activeNetwork)!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
      assertEquals(1, cellularNetworks())

      shell("apksim network wifi off")
      waitFor("the default moving to mobile data") {
        default.caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
      }
      // Wi-Fi disconnected: onLost for it, then onAvailable for mobile data, as ConnectivityService tells a default
      // callback whose network went away (a switch between two connected networks is onAvailable alone).
      assertEquals(listOf("available", "lost", "available"), default.events.toList())
      val caps = cm.getNetworkCapabilities(cm.activeNetwork)!!
      assertTrue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
      assertTrue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
      assertFalse("mobile data is metered", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
      assertTrue(cm.isActiveNetworkMetered)
      assertEquals(ConnectivityManager.TYPE_MOBILE, cm.activeNetworkInfo!!.type)
      val tm = context.getSystemService(TelephonyManager::class.java)
      assertEquals(TelephonyManager.DATA_CONNECTED, tm.dataState)
      assertEquals(-127, wm.connectionInfo.rssi)

      shell("apksim network cellular off")
      waitFor("no network left") { cm.activeNetwork == null }
      assertEquals(TelephonyManager.DATA_DISCONNECTED, tm.dataState)
      assertEquals(listOf("available", "lost"), cellular.events.toList())
    } finally {
      cm.unregisterNetworkCallback(cellular)
      cm.unregisterNetworkCallback(default)
    }
  }

  @Suppress("DEPRECATION")
  @Test
  fun c_theWifiSignalReachesWifiInfoCapabilitiesAndRssiChanged() {
    assertEquals(-50, wm.connectionInfo.rssi)
    val heard = Collections.synchronizedList(mutableListOf<Int>())
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) { heard += i.getIntExtra(WifiManager.EXTRA_NEW_RSSI, 0) }
    }
    val cb = Recorder()
    context.registerReceiver(receiver, IntentFilter(WifiManager.RSSI_CHANGED_ACTION))
    cm.registerDefaultNetworkCallback(cb, main)
    try {
      waitFor("the default network") { cb.caps != null }
      shell("apksim network wifi signal -70")
      waitFor("onCapabilitiesChanged at -70") { cb.caps?.signalStrength == -70 }
      waitFor("RSSI_CHANGED_ACTION") { heard.toList() == listOf(-70) }
      assertEquals(-70, wm.connectionInfo.rssi)
      assertEquals(2, wm.calculateSignalLevel(wm.connectionInfo.rssi))
      assertEquals(-70, cm.getNetworkCapabilities(cm.activeNetwork)!!.signalStrength)
      assertEquals("no network was lost", listOf("available"), cb.events.toList())

      shell("apksim network wifi signal level 1")
      waitFor("level 1") { wm.connectionInfo.rssi == -82 }
      assertEquals(1, wm.calculateSignalLevel(-82))
      assertTrue(shell("apksim network wifi").contains("-82 dBm (level 1 of 4)"))
      assertTrue(shell("apksim network wifi signal 5").startsWith("error:"))
    } finally {
      context.unregisterReceiver(receiver)
      cm.unregisterNetworkCallback(cb)
    }
  }

  @Test
  fun d_wifiStateChangedSaysDisablingThenDisabled() {
    val states = Collections.synchronizedList(mutableListOf<String>())
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        states += "${i.getIntExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, -1)}>${i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)}"
      }
    }
    context.registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
    try {
      waitFor("the sticky state") { states.isNotEmpty() }
      assertTrue(states[0].endsWith(">${WifiManager.WIFI_STATE_ENABLED}"))
      states.clear()
      shell("apksim network wifi off")
      waitFor("disabled") { states.lastOrNull()?.endsWith(">${WifiManager.WIFI_STATE_DISABLED}") == true }
      assertEquals(
        listOf("${WifiManager.WIFI_STATE_ENABLED}>${WifiManager.WIFI_STATE_DISABLING}",
          "${WifiManager.WIFI_STATE_DISABLING}>${WifiManager.WIFI_STATE_DISABLED}"),
        states.toList())
    } finally {
      context.unregisterReceiver(receiver)
    }
  }

  @Suppress("DEPRECATION")
  @Test
  fun e_svcAndCmdSwitchTheRadiosAsOnADevice() {
    assertEquals("", shell("svc wifi disable"))
    waitFor("Wi-Fi off") { !wm.isWifiEnabled && cm.activeNetwork == null }
    assertEquals("", shell("svc data enable"))
    waitFor("mobile data") { cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE }
    assertEquals("", shell("cmd wifi set-wifi-enabled enabled"))
    waitFor("Wi-Fi back as the default") { cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI }
    assertEquals("", shell("cmd phone data disable"))
    waitFor("no mobile data") { cellularNetworks() == 0 }
  }

  @Suppress("DEPRECATION")
  @Test
  fun f_theAppsOwnSetWifiEnabledSwitchesIt() {
    assertTrue(wm.setWifiEnabled(false))
    waitFor("Wi-Fi off") { !wm.isWifiEnabled && cm.activeNetwork == null }
    assertTrue(wm.setWifiEnabled(true))
    waitFor("Wi-Fi on") { wm.isWifiEnabled && cm.activeNetwork != null }
  }

  /** Runs last: what the tests above switched is back to the launch's radios at this test's setup. */
  @Suppress("DEPRECATION")
  @Test
  fun z_eachTestStartsWithTheLaunchRadios() {
    assertTrue(wm.isWifiEnabled)
    assertEquals(-50, wm.connectionInfo.rssi)
    assertNotNull(cm.activeNetwork)
    assertEquals(ConnectivityManager.TYPE_WIFI, cm.activeNetworkInfo!!.type)
    assertEquals(0, cellularNetworks())
  }
}
