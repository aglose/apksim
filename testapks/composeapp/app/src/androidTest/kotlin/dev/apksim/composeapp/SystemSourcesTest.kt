package dev.apksim.composeapp

import android.content.Intent
import android.net.ConnectivityManager
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * setStreamVolume, a settings observer. On a device each answers at once; one that never does leaves the panel empty.
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
      if (v == null) waiting += name
    }
    assertEquals("sources that never answered", emptyList<String>(), waiting)
  }

  @Test
  fun theBatteryIsAStickyBroadcast() {
    @Suppress("UnspecifiedRegisterReceiverFlag")
    val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    assertNotNull("registerReceiver(null, ACTION_BATTERY_CHANGED)", battery)
    assertEquals(100, battery!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
  }

  @Test
  fun offlineTheStickyConnectivityBroadcastSaysSo() {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("apksim network offline").close()
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
