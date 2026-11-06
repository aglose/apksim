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
    // A device shows the SSID, BSSID and network id only to an app with ACCESS_FINE_LOCATION (--permissions grant).
    val located = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED
    if (located) {
      assertEquals("SSID with location permission", "\"AndroidWifi\"", info.ssid)
      assertTrue("BSSID with location permission: ${info.bssid}", info.bssid != null && info.bssid != "02:00:00:00:00:00")
      assertTrue("network id with location permission", info.networkId >= 0)
    } else {
      assertEquals("SSID without location permission", WifiManager.UNKNOWN_SSID, info.ssid)
      assertEquals("BSSID without location permission", "02:00:00:00:00:00", info.bssid)
      assertEquals("network id without location permission", -1, info.networkId)
      val cm = context.getSystemService(ConnectivityManager::class.java)
      val transport = cm.getNetworkCapabilities(cm.activeNetwork)?.transportInfo as? android.net.wifi.WifiInfo
      assertEquals("the network capabilities' WifiInfo BSSID", "02:00:00:00:00:00", transport?.bssid)
    }
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

  /**
   * The default network moves from Wi-Fi to Ethernet (Ethernet comes up and takes over, then Wi-Fi goes): a default
   * callback hears only onAvailable(Ethernet), as Android tells a registration that moved to another network, while a
   * listen hears Ethernet come and Wi-Fi go.
   */
  @Test
  fun aDefaultNetworkSwitchIsOnlyOnAvailable() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val wifi = cm.activeNetwork!!
    val defaults = mutableListOf<String>()
    val listens = mutableListOf<String>()
    fun recorder(into: MutableList<String>) = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { into += "available ${name(n, wifi)}" }
      override fun onLost(n: Network) { into += "lost ${name(n, wifi)}" }
    }
    val defaultCb = recorder(defaults)
    val listenCb = recorder(listens)
    cm.registerDefaultNetworkCallback(defaultCb)
    cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), listenCb)
    try {
      pumpMainThread()
      // apksim's hook: no device control does this (a device is plugged into Ethernet).
      Class.forName("dev.apksim.NetConnectivity").getDeclaredMethod("switchDefaultToEthernetForTest")
        .apply { isAccessible = true }.invoke(null)
      pumpMainThread()
      assertEquals(listOf("available wifi", "available ethernet"), defaults)
      // The host's other networks (a second interface) come and stay: only Wi-Fi and Ethernet count here.
      assertEquals(listOf("available wifi", "available ethernet", "lost wifi"), listens.filter { !it.endsWith("other") })
    } finally {
      cm.unregisterNetworkCallback(defaultCb)
      cm.unregisterNetworkCallback(listenCb)
    }
  }

  /**
   * A broad request (clearCapabilities) is told only of connected networks: a disconnected one with capabilities, as
   * Robolectric's own Wi-Fi is before a running instance installs the host's networks, is not offered.
   */
  @Test
  fun aBroadRequestHearsOnlyOfConnectedNetworks() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val netId = Class.forName("dev.apksim.NetConnectivity").getDeclaredMethod("addDisconnectedNetworkForTest")
      .apply { isAccessible = true }.invoke(null) as Int
    val told = mutableListOf<String>()
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { told += n.toString() }
    }
    cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)
    try {
      pumpMainThread()
      assertFalse("told of the disconnected network $netId: $told", netId.toString() in told)
      assertTrue("told of the active network ${cm.activeNetwork}: $told", cm.activeNetwork.toString() in told)
    } finally {
      cm.unregisterNetworkCallback(cb)
    }
  }

  /** The hook's Ethernet network is netId TYPE_ETHERNET (Robolectric finds the default network by its type). */
  @Suppress("DEPRECATION")
  private fun name(n: Network, wifi: Network) =
    if (n == wifi) "wifi" else if (n.toString() == ConnectivityManager.TYPE_ETHERNET.toString()) "ethernet" else "other"

  /** A callback that goes offline from inside onAvailable still gets that announcement whole, then onLost. */
  @Test
  fun goingOfflineFromOnAvailableKeepsTheOrder() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val events = mutableListOf<String>()
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) {
        events += "available"
        if (events.size == 1) shell("apksim network offline")
      }
      override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { events += "caps" }
      override fun onLinkPropertiesChanged(n: Network, link: android.net.LinkProperties) { events += "link" }
      override fun onLost(n: Network) { events += "lost" }
    }
    cm.registerDefaultNetworkCallback(cb)
    try {
      pumpMainThread()
      assertEquals(listOf("available", "caps", "link", "lost"), events)
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
