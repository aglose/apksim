package dev.apksim.composeapp

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * apksim://system-sources: the system state a quick-settings panel (Wi-Fi, Bluetooth, volume, brightness, battery)
 * awaits in coroutines, each as an app wraps it (callbackFlow + first(), suspendCancellableCoroutine), with a timeout.
 * On a device every one answers at once: sticky broadcasts are delivered to a receiver as it registers, a network
 * callback hears of the networks that match it (one registered in Application.onCreate too), a profile proxy
 * connects. One that never answers leaves a panel waiting for ever with nothing on screen, and so does one that
 * throws in a coroutine whose exception the app swallows: "wifi level" divides by getMaxSignalLevel() / 4 as a
 * quick-settings library does (issue 96). Each line reads "name: value", "name: timed out" or "name: error ...";
 * SystemSourcesTest awaits the same sources in a test run.
 */
class SystemSourcesActivity : ComponentActivity() {
  private val lines = mutableStateListOf<String>()

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
            Text("System sources", style = MaterialTheme.typography.titleLarge)
            for (l in lines) Text(l)
          }
        }
      }
    }
    for ((name, source) in SystemSources.all(this)) {
      lifecycleScope.launch {
        val v = withTimeoutOrNull(SystemSources.TIMEOUT_MS) { source() }
        val line = "$name: ${v ?: "timed out"}"
        android.util.Log.i("SystemSources", line)
        lines += line
      }
    }
  }
}

/** The sources, each a suspend function returning a short description of the first value it gets. */
object SystemSources {
  const val TIMEOUT_MS = 3000L

  fun all(context: Context): List<Pair<String, suspend () -> String>> = listOf(
    "wifi level" to { wifiLevel(context) },
    "wifi state" to { sticky(context, WifiManager.WIFI_STATE_CHANGED_ACTION).first().let { "state ${it.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)}" } },
    "wifi network" to { sticky(context, WifiManager.NETWORK_STATE_CHANGED_ACTION).first().let { "connected ${netInfo(it)?.isConnected}" } },
    "connectivity" to { sticky(context, ConnectivityManager.CONNECTIVITY_ACTION).first().let { "no connectivity ${it.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)}" } },
    "battery" to { sticky(context, Intent.ACTION_BATTERY_CHANGED).first().let { "level ${it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/${it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)}" } },
    "default network" to { defaultNetwork(context).first() },
    "startup callback" to { ComposeApp.startupNetwork.filterNotNull().first() },
    "wifi callback" to { wifiCallback(context).first() },
    "audio devices" to { audioDevices(context).first() },
    "volume" to { volumeChange(context) },
    "brightness" to { brightnessChange(context) },
    "bluetooth profile" to { profileProxy(context) },
  )

  /**
   * The signal as a quick-settings library computes it on API 31+ (issue 96): levels 0..getMaxSignalLevel() cut into
   * four. A WifiManager whose getMaxSignalLevel() is under 4 makes that a division by zero.
   */
  fun wifiLevel(context: Context): String {
    val wm = context.getSystemService(WifiManager::class.java)
    @Suppress("DEPRECATION") val info = wm.connectionInfo
    val max = wm.maxSignalLevel
    return try {
      "level ${wm.calculateSignalLevel(info.rssi) / (max / 4)} of 4, max $max, rssi ${info.rssi}"
    } catch (e: ArithmeticException) {
      "error $e, max $max"
    }
  }

  @Suppress("DEPRECATION")
  private fun netInfo(i: Intent): android.net.NetworkInfo? = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)

  /** A receiver's broadcasts as a flow: for a sticky one, the current value first, as the system delivers it. */
  fun sticky(context: Context, action: String): Flow<Intent> = callbackFlow {
    val r = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) { trySend(i) }
    }
    context.registerReceiver(r, IntentFilter(action), Context.RECEIVER_EXPORTED)
    awaitClose { context.unregisterReceiver(r) }
  }

  fun defaultNetwork(context: Context): Flow<String> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
        trySend("validated ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
      }
    }
    cm.registerDefaultNetworkCallback(cb)
    awaitClose { cm.unregisterNetworkCallback(cb) }
  }

  fun wifiCallback(context: Context): Flow<String> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) { trySend("available") }
    }
    cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
    awaitClose { cm.unregisterNetworkCallback(cb) }
  }

  fun audioDevices(context: Context): Flow<String> = callbackFlow {
    val am = context.getSystemService(AudioManager::class.java)
    val cb = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { trySend("${added.size} devices") }
    }
    am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    awaitClose { am.unregisterAudioDeviceCallback(cb) }
  }

  /** A volume tile: set the music volume, wait for the system's VOLUME_CHANGED_ACTION. */
  suspend fun volumeChange(context: Context): String {
    val am = context.getSystemService(AudioManager::class.java)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    val target = if (now < max) now + 1 else now - 1
    val flow = callbackFlow {
      val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { trySend(i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)) }
      }
      context.registerReceiver(r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED)
      am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
      awaitClose { context.unregisterReceiver(r) }
    }
    return "volume ${flow.first()} of $max"
  }

  /** A brightness tile: observe the setting, change it, wait for the observer. */
  suspend fun brightnessChange(context: Context): String {
    val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
    val flow = callbackFlow {
      val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)) }
      }
      context.contentResolver.registerContentObserver(uri, false, o)
      try {
        val now = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, if (now < 255) now + 1 else now - 1)
      } catch (e: SecurityException) {
        trySend(-2)  // a device without WRITE_SETTINGS refuses the write: nothing to wait for
      }
      awaitClose { context.contentResolver.unregisterContentObserver(o) }
    }
    return "brightness ${flow.first()}"
  }

  suspend fun profileProxy(context: Context): String {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return "no adapter"
    return suspendCancellableCoroutine { cont ->
      val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
          adapter.closeProfileProxy(profile, proxy)
          if (cont.isActive) cont.resume("connected")
        }
        override fun onServiceDisconnected(profile: Int) {}
      }, BluetoothProfile.A2DP)
      if (!ok && cont.isActive) cont.resume("refused")
    }
  }
}
