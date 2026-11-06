package dev.apksim.composeapp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System state apps await in coroutines (issue 96), as apksim://system-sources awaits it: Wi-Fi's and the network's
 * sticky broadcasts, the battery's, network callbacks, VOLUME_CHANGED_ACTION after setStreamVolume, a settings
 * observer, a callback registered in Application.onCreate. On a device each answers at once; one that never does
 * leaves the app waiting for ever. (Bluetooth's profile proxy connects only while Bluetooth is on, as
 * on a device.) The class also runs on a device or emulator: what needs apksim (its test hooks, `apksim` shell
 * commands, its exact values) is skipped there.
 */
@RunWith(AndroidJUnit4::class)
class SystemSourcesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val onApksim = Build.FINGERPRINT == "robolectric"

  /** A case for apksim only: its test hooks, `apksim` shell commands, its exact values. */
  private fun apksimOnly(what: String) = assumeTrue("$what: apksim only", onApksim)

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
   * Wi-Fi's signal as an API 31+ device gives it (issue 96): levels 0..4, so code that buckets it as
   * `calculateSignalLevel(rssi) / (maxSignalLevel / 4)` does not divide by zero; a connection with a real RSSI.
   */
  @Test
  fun wifiSignalLevelsAreADevicesZeroToFour() {
    val wm = context.getSystemService(WifiManager::class.java)
    assertEquals("getMaxSignalLevel()", 4, wm.maxSignalLevel)
    @Suppress("DEPRECATION") val info = wm.connectionInfo
    val level = wm.calculateSignalLevel(info.rssi) / (wm.maxSignalLevel / 4)
    assertTrue("level $level", level in 0..4)
    assertFalse(SystemSources.wifiLevel(context), SystemSources.wifiLevel(context).startsWith("error"))
    @Suppress("DEPRECATION") val classic = WifiManager.calculateSignalLevel(-70, 5)
    assertEquals("static calculateSignalLevel(-70, 5)", 2, classic)
    // A device shows the SSID and BSSID only to an app with ACCESS_FINE_LOCATION (--permissions grant).
    if (!located()) {
      assertEquals("SSID without location permission", WifiManager.UNKNOWN_SSID, info.ssid)
      assertTrue("BSSID without location permission: ${info.bssid}", info.bssid == null || info.bssid == "02:00:00:00:00:00")
    }
  }

  private fun located() = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED

  /** apksim's Wi-Fi: the host's network as a connection at -50 dBm on 5 GHz, levels on the platform's thresholds. */
  @Test
  fun apksimWifiIsAConnectedDevicesWifi() {
    apksimOnly("apksim's Wi-Fi values")
    val wm = context.getSystemService(WifiManager::class.java)
    @Suppress("DEPRECATION") val info = wm.connectionInfo
    assertEquals("connected RSSI", -50, info.rssi)
    assertTrue("frequency ${info.frequency}", info.frequency > 0)
    if (located()) {
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
    assertEquals(4, wm.calculateSignalLevel(-50))
    assertEquals(0, wm.calculateSignalLevel(-95))
    assertTrue(SystemSources.wifiLevel(context), SystemSources.wifiLevel(context).startsWith("level 4 of 4"))
  }

  @Test
  fun offlineWifiIsDisconnected() {
    apksimOnly("`apksim network offline`")
    shell("apksim network offline")
    val wm = context.getSystemService(WifiManager::class.java)
    @Suppress("DEPRECATION") val info = wm.connectionInfo
    assertEquals(-127, info.rssi)
    assertEquals(-1, info.networkId)
    assertEquals(0, wm.calculateSignalLevel(info.rssi))
  }

  @Test
  fun aStartupProviderReadsTheBattery() {
    val level = StartupProvider.batteryAtCreate
    assertTrue("battery level a provider's onCreate read: $level", level != null && level in 0..100)
    if (onApksim) assertEquals("apksim's full battery", 100, level)
  }

  /** An active request is served by one network (the default one), a listen hears of every network it matches. */
  @Test
  fun requestNetworkGetsOneNetwork() {
    apksimOnly("the host's networks as apksim offers them")  // a device's request may bring up another network
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
    cm.requestNetwork(request, requestCb, Handler(Looper.getMainLooper()))
    cm.registerNetworkCallback(request, listenCb, Handler(Looper.getMainLooper()))
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
   * requestNetwork with a timeout: nothing satisfies it (a transport the host has not), onUnavailable once its time
   * passes and nothing else; one that is satisfied never hears it; one unregistered first hears nothing.
   */
  @Test
  fun aRequestNothingSatisfiesTimesOut() {
    apksimOnly("requestNetwork as apksim offers the host's networks")
    val cm = context.getSystemService(ConnectivityManager::class.java)
    fun recorder(into: MutableList<String>) = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { into += "available" }
      override fun onLost(n: Network) { into += "lost" }
      override fun onUnavailable() { into += "unavailable" }
    }
    val unsatisfiable = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN).build()
    val missed = mutableListOf<String>()
    val met = mutableListOf<String>()
    val withdrawn = mutableListOf<String>()
    val metCb = recorder(met)
    val withdrawnCb = recorder(withdrawn)
    cm.requestNetwork(unsatisfiable, recorder(missed), mainHandler(), 500)
    cm.requestNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), metCb, mainHandler(), 500)
    cm.requestNetwork(unsatisfiable, withdrawnCb, mainHandler(), 500)
    cm.unregisterNetworkCallback(withdrawnCb)
    try {
      runBlocking { delay(1_500) }
      assertEquals("a request nothing satisfies", listOf("unavailable"), missed)
      assertEquals("a request the network satisfies", listOf("available"), met)
      assertEquals("a request unregistered before its timeout", emptyList<String>(), withdrawn)
    } finally {
      cm.unregisterNetworkCallback(metCb)
    }
  }

  /**
   * A request past its deadline is not satisfied by a network that comes later, even while its handler is too busy to
   * have run the timeout: offline, a 500 ms request on a handler blocked for 700 ms, the network back at ~600 ms, it
   * hears only onUnavailable, as Android's request had expired.
   */
  @Test
  fun anExpiredRequestIsNotSatisfiedLater() {
    apksimOnly("`apksim network offline|online`")
    val cm = context.getSystemService(ConnectivityManager::class.java)
    shell("apksim network offline")
    val thread = android.os.HandlerThread("busy-request-handler").apply { start() }
    val handler = android.os.Handler(thread.looper)
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { events += "available" }
      override fun onUnavailable() { events += "unavailable" }
    }
    try {
      handler.post { Thread.sleep(700) }  // the callback's thread is busy past the deadline
      cm.requestNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
        cb, handler, 500)
      runBlocking { delay(600) }
      shell("apksim network online")
      runBlocking { delay(1_000) }
      assertEquals(listOf("unavailable"), events.toList())
    } finally {
      runCatching { cm.unregisterNetworkCallback(cb) }
      thread.quitSafely()
    }
  }

  /**
   * A request satisfied before its deadline never expires, even when its onAvailable is still queued on a busy handler
   * as the deadline passes: offline, a 500 ms request on a handler blocked for 700 ms, the network back at ~200 ms.
   */
  @Test
  fun aRequestSatisfiedBeforeItsDeadlineOnABusyHandlerNeverExpires() {
    apksimOnly("`apksim network offline|online`")
    val cm = context.getSystemService(ConnectivityManager::class.java)
    shell("apksim network offline")
    val thread = android.os.HandlerThread("busy-request-handler").apply { start() }
    val handler = android.os.Handler(thread.looper)
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { events += "available" }
      override fun onUnavailable() { events += "unavailable" }
    }
    try {
      handler.post { Thread.sleep(700) }
      cm.requestNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
        cb, handler, 500)
      runBlocking { delay(200) }
      shell("apksim network online")
      runBlocking { delay(1_300) }
      assertEquals(listOf("available"), events.toList())
    } finally {
      cm.unregisterNetworkCallback(cb)
      thread.quitSafely()
    }
  }

  /**
   * A request once satisfied stays registered past its deadline: its network lost and back after the deadline, it
   * hears available, lost, available, and never onUnavailable.
   */
  @Test
  fun aSatisfiedRequestSurvivesALossPastItsDeadline() {
    apksimOnly("`apksim network offline|online`")
    val cm = context.getSystemService(ConnectivityManager::class.java)
    shell("apksim network offline")
    val events = mutableListOf<String>()
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { events += "available" }
      override fun onLost(n: Network) { events += "lost" }
      override fun onUnavailable() { events += "unavailable" }
    }
    try {
      cm.requestNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb, mainHandler(), 500)
      runBlocking { delay(200) }
      shell("apksim network online")
      runBlocking { delay(100) }
      shell("apksim network offline")
      runBlocking { delay(600) }  // past the deadline, with no network
      shell("apksim network online")
      runBlocking { delay(500) }
      assertEquals(listOf("available", "lost", "available"), events)
    } finally {
      cm.unregisterNetworkCallback(cb)
    }
  }

  /**
   * Offline and back before the registration's announcement ran: the callback hears all three, in order, as from the
   * system server's one queue.
   */
  @Test
  fun offlineAndOnlineBeforeTheFirstCallbackArriveInOrder() {
    apksimOnly("`apksim network offline|online`")
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val events = mutableListOf<String>()
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { events += "available" }
      override fun onLost(n: Network) { events += "lost" }
    }
    cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
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
    apksimOnly("NetConnectivity's test hook")
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
    cm.registerDefaultNetworkCallback(defaultCb, Handler(Looper.getMainLooper()))
    cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), listenCb, Handler(Looper.getMainLooper()))
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
    apksimOnly("NetConnectivity's test hook")
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val netId = Class.forName("dev.apksim.NetConnectivity").getDeclaredMethod("addDisconnectedNetworkForTest")
      .apply { isAccessible = true }.invoke(null) as Int
    val told = mutableListOf<String>()
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { told += n.toString() }
    }
    cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb, Handler(Looper.getMainLooper()))
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
    apksimOnly("`apksim network offline`")
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
    cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
    try {
      pumpMainThread()
      assertEquals(listOf("available", "caps", "link", "lost"), events)
    } finally {
      cm.unregisterNetworkCallback(cb)
    }
  }

  /** Callbacks that the test reads right after a change take the main looper, where their delivery runs. */
  private fun mainHandler() = Handler(Looper.getMainLooper())

  private fun shell(command: String) =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()

  /** Lets the main thread run what was posted to it (runBlocking's wait runs the main queue here, as a device would). */
  private fun pumpMainThread() = runBlocking { delay(200) }

  @Test
  fun theBatteryIsAStickyBroadcast() {
    @Suppress("UnspecifiedRegisterReceiverFlag")
    val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    assertNotNull("registerReceiver(null, ACTION_BATTERY_CHANGED)", battery)
    val level = battery!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    assertTrue("battery level $level", level in 0..100)
    if (onApksim) assertEquals("apksim's full battery", 100, level)
  }

  @Test
  fun offlineTheStickyConnectivityBroadcastSaysSo() {
    apksimOnly("`apksim network offline`")
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

  /**
   * A callback registered without a Handler runs on a background ConnectivityThread, not the main thread (as on a
   * device): the main thread waits here without running its looper, and onAvailable still arrives.
   */
  @Test
  fun aCallbackWithoutAHandlerArrivesWhileTheMainThreadWaits() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val latch = CountDownLatch(1)
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { latch.countDown() }
    }
    cm.registerDefaultNetworkCallback(cb)
    try {
      assertTrue("onAvailable while the main thread waits", latch.await(5, TimeUnit.SECONDS))
    } finally {
      cm.unregisterNetworkCallback(cb)
    }
  }
}
