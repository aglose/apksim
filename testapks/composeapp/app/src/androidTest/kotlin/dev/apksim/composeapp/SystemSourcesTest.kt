package dev.apksim.composeapp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a quick-settings panel awaits before it shows anything (issue 96), as apksim://system-sources awaits it:
 * Wi-Fi's and the network's sticky broadcasts, the battery's, network callbacks, VOLUME_CHANGED_ACTION after
 * setStreamVolume, a settings observer, a callback registered in Application.onCreate. On a device each answers at once; one that never does leaves the panel empty.
 * (Bluetooth's profile proxy connects only while Bluetooth is on, as on a device.)
 */
@RunWith(AndroidJUnit4::class)
class SystemSourcesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun everySourceAnswers() {
    val waiting = mutableListOf<String>()
    for ((name, source) in SystemSources.all(context)) {
      if (name == "bluetooth profile") continue
      val v = runBlocking { withTimeoutOrNull(5_000) { source() } }
      if (v == null || v.startsWith("error")) waiting += "$name: $v"
    }
    assertEquals("sources that never answered or failed", emptyList<String>(), waiting)
  }

  /**
   * Wi-Fi's signal as an API 31+ device gives it (issue 96): levels 0..4, so a quick-settings library's
   * `calculateSignalLevel(rssi) / (maxSignalLevel / 4)` does not divide by zero; a connection with a real RSSI.
   */
  @Test
  fun wifiSignalLevelsAreADevicesZeroToFour() {
    val wm = context.getSystemService(WifiManager::class.java)
    assertEquals("getMaxSignalLevel()", 4, wm.maxSignalLevel)
    @Suppress("DEPRECATION") val info = wm.connectionInfo
    assertEquals("connected RSSI", -50, info.rssi)
    assertTrue("frequency ${info.frequency}", info.frequency > 0)
    assertEquals("SSID without location permission", WifiManager.UNKNOWN_SSID, info.ssid)
    val level = wm.calculateSignalLevel(info.rssi) / (wm.maxSignalLevel / 4)
    assertTrue("level $level", level in 0..4)
    assertEquals(4, wm.calculateSignalLevel(-50))
    assertEquals(0, wm.calculateSignalLevel(-95))
    @Suppress("DEPRECATION") val classic = WifiManager.calculateSignalLevel(-70, 5)
    assertEquals("static calculateSignalLevel(-70, 5)", 2, classic)
    assertTrue(SystemSources.wifiLevel(context), SystemSources.wifiLevel(context).startsWith("level 4 of 4"))
  }

  @Test
  fun offlineWifiIsDisconnected() {
    shell("apksim network offline")
    val wm = context.getSystemService(WifiManager::class.java)
    @Suppress("DEPRECATION") val info = wm.connectionInfo
    assertEquals(-127, info.rssi)
    assertEquals(-1, info.networkId)
    assertEquals(0, wm.calculateSignalLevel(info.rssi))
  }

  @Test
  fun aStartupProviderReadsTheBattery() {
    assertEquals("battery level a provider's onCreate read", 100, StartupProvider.batteryAtCreate)
  }

  /** An active request is served by one network (the default one), a listen hears of every network it matches. */
  @Test
  fun requestNetworkGetsOneNetwork() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    // Without NOT_VPN, so the host's VPNs (if any) match too.
    val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
    val matching = cm.allNetworks.count { n -> cm.getNetworkCapabilities(n)?.let { request.canBeSatisfiedBy(it) } == true }
    assertTrue("networks matching $request", matching >= 1)
    val requested = mutableListOf<Network>()
    val listened = mutableListOf<Network>()
    val requestCb = object : ConnectivityManager.NetworkCallback() { override fun onAvailable(n: Network) { requested += n } }
    val listenCb = object : ConnectivityManager.NetworkCallback() { override fun onAvailable(n: Network) { listened += n } }
    cm.requestNetwork(request, requestCb)
    cm.registerNetworkCallback(request, listenCb)
    try {
      pumpMainThread()
      assertEquals("requestNetwork's onAvailable calls", listOf(cm.activeNetwork), requested)
      assertEquals("registerNetworkCallback's onAvailable calls", matching, listened.size)
    } finally {
      cm.unregisterNetworkCallback(requestCb)
      cm.unregisterNetworkCallback(listenCb)
    }
  }

  /**
   * Offline and back before the registration's announcement ran: the callback hears all three, in order, as from the
   * system server's one queue.
   */
  @Test
  fun offlineAndOnlineBeforeTheFirstCallbackArriveInOrder() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val events = mutableListOf<String>()
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { events += "available" }
      override fun onLost(n: Network) { events += "lost" }
    }
    cm.registerDefaultNetworkCallback(cb)
    try {
      shell("apksim network offline")
      shell("apksim network online")
      pumpMainThread()
      assertEquals(listOf("available", "lost", "available"), events)
    } finally {
      cm.unregisterNetworkCallback(cb)
    }
  }

  private fun shell(command: String) =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()

  /** Lets the main thread run what was posted to it (runBlocking's wait runs the main queue here, as a device would). */
  private fun pumpMainThread() = runBlocking { delay(200) }

  @Test
  fun theBatteryIsAStickyBroadcast() {
    @Suppress("UnspecifiedRegisterReceiverFlag")
    val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    assertNotNull("registerReceiver(null, ACTION_BATTERY_CHANGED)", battery)
    assertEquals(100, battery!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
  }

  @Test
  fun offlineTheStickyConnectivityBroadcastSaysSo() {
    shell("apksim network offline")
    val conn = runBlocking {
      withTimeoutOrNull(5_000) { SystemSources.sticky(context, ConnectivityManager.CONNECTIVITY_ACTION).first() }
    }
    assertNotNull("CONNECTIVITY_ACTION", conn)
    assertTrue(conn!!.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false))
    // No network, so a default network callback hears nothing, as on a device.
    val cb = runBlocking { withTimeoutOrNull(1_000) { SystemSources.defaultNetwork(context).first() } }
    assertEquals(null, cb)
    val cm = context.getSystemService(ConnectivityManager::class.java)
    assertFalse(cm.activeNetworkInfo?.isConnected == true)
  }
}
