package dev.apksim.composeapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The app's Application. The androidTest runner swaps in a subclass (HarnessRunner). It records what onCreate sees,
 * before any test code runs: the network ConnectivityManager reports (NetworkActivity shows it: apksim's
 * --network-profile offline must hold from before onCreate), AndroidIdTest's and InstallPermissionsTest's values, and
 * the device identity (DeviceIdentityTest; logged as "identity: ..." for running instances), the animator scale
 * (AnimScaleOptionTest), and the data slot's Settings value and /sdcard file (SlotStateActivity).
 */
open class ComposeApp : Application() {
  /**
   * With `--jvm -Ddev.apksim.testapp.crash_in_attach=true` (apksim only: a device has no such property), throws here,
   * before onCreate: apksim must crash as a device does, FATAL EXCEPTION and the process ended (issue 104).
   */
  override fun attachBaseContext(base: android.content.Context) {
    super.attachBaseContext(base)
    if (System.getProperty(CRASH_IN_ATTACH).toBoolean()) throw IllegalStateException("thrown in attachBaseContext")
  }

  @SuppressLint("HardwareIds", "MissingPermission")
  override fun onCreate() {
    super.onCreate()
    networkAtStart = GreetingClient.connectivity(getSystemService(ConnectivityManager::class.java))
    androidIdAtCreate = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    vibrateAtCreate = checkSelfPermission(Manifest.permission.VIBRATE)
    providerBeforeCreate = StartupProvider.created
    // A device or emulator refuses an app without privileged access (SecurityException); apksim answers.
    serialAtCreate = try { Build.getSerial() } catch (e: SecurityException) { null }
    cameraAnyAtCreate = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    modelAtCreate = Build.MODEL
    // isEnabled() needs no permission on API 31+ (@RequiresNoPermission), but the app must start whatever a device says.
    bluetoothOnAtCreate = try {
      getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    } catch (e: SecurityException) {
      Log.w("ComposeApp", "bluetooth: unknown ($e)")
      null
    }
    animScaleAtCreate = android.animation.ValueAnimator.getDurationScale()
    Log.i("ComposeApp", "identity: serial=$serialAtCreate SERIAL=${Build.SERIAL} camera.any=$cameraAnyAtCreate " +
        "model=$modelAtCreate release=${Build.VERSION.RELEASE} bluetooth=$bluetoothOnAtCreate")
    slotAtCreate = SlotStateActivity.describe(this)
    if (packageManager.hasSystemFeature(LAUNCH_OVERLAY)) addLaunchOverlay()
    listenForTheNetwork()
    listenForFlags()
  }

  /**
   * A debug flag-override receiver as apps have (`am broadcast -a dev.apksim.testapp.SET_FLAG --es name X --ez value true`),
   * registered in onCreate: apksim's `--broadcast` must reach it before the first activity (log: "flag NAME=VALUE").
   */
  private fun listenForFlags() {
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(c: android.content.Context, i: android.content.Intent) {
        Log.i("ComposeApp", "flag ${i.getStringExtra("name")}=${i.getBooleanExtra("value", false)} n=${i.getIntExtra("n", -1)}")
        resultCode = 7
      }
    }
    registerReceiver(receiver, android.content.IntentFilter("dev.apksim.testapp.SET_FLAG"), RECEIVER_EXPORTED)
  }

  /**
   * A network callback registered in onCreate, as an app's connectivity monitor does: a running instance installs the
   * host's networks after onCreate, and the callback must still hear of them (apksim://system-sources shows it).
   */
  private fun listenForTheNetwork() {
    startupNetwork.value = null
    val cm = getSystemService(ConnectivityManager::class.java)
    val request = android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: android.net.Network) { startupNetwork.value = "available" }
    })
    // What a monitor registered here hears first: on a connected device, never "lost" or "down" before "up".
    startupHeard.clear()
    cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: android.net.Network) { synchronized(startupHeard) { startupHeard += "default up" } }
      override fun onLost(network: android.net.Network) { synchronized(startupHeard) { startupHeard += "default lost" } }
    })
    @Suppress("DEPRECATION")
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(c: android.content.Context, i: android.content.Intent) {
        val heard = when (i.action) {
          ConnectivityManager.CONNECTIVITY_ACTION ->
            if (i.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) "connectivity down" else "connectivity up"
          else -> {
            val info = i.getParcelableExtra<android.net.NetworkInfo>(android.net.wifi.WifiManager.EXTRA_NETWORK_INFO)
            if (info?.isConnected == true) "wifi up" else "wifi down"
          }
        }
        synchronized(startupHeard) { startupHeard += heard }
      }
    }
    @Suppress("DEPRECATION")
    val filter = android.content.IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
      .apply { addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION) }
    registerReceiver(receiver, filter, RECEIVER_EXPORTED)
  }

  /**
   * With `--feature dev.apksim.testapp.launch_overlay`: an empty overlay window added from a window context before
   * any activity, as some apps add one (a ComposeView that never gets content), so it is the bottom window and stays
   * 0x0. apksim's first frame waited for the bottom window to be laid out, and the screen stayed black (issue 83).
   */
  private fun addLaunchOverlay() {
    val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    val context = try {
      createWindowContext(getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY), type, null)
    } catch (e: Exception) {
      Log.w("ComposeApp", "launch overlay: no window context ($e), using the application")
      this
    }
    val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT)
    params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    context.getSystemService(WindowManager::class.java).addView(FrameLayout(context), params)
    Log.i("ComposeApp", "launch overlay added")
  }

  companion object {
    const val LAUNCH_OVERLAY = "dev.apksim.testapp.launch_overlay"
    const val CRASH_IN_ATTACH = "dev.apksim.testapp.crash_in_attach"
    /** ConnectivityManager's default network in Application.onCreate, or null before it ran. */
    @JvmStatic var networkAtStart: String? = null
    /** ValueAnimator's duration scale in onCreate (AnimScaleOptionTest: apksim's --anim-scale holds from before it). */
    @Volatile var animScaleAtCreate: Float? = null
    @Volatile var androidIdAtCreate: String? = null
    @Volatile var vibrateAtCreate: Int? = null
    @Volatile var providerBeforeCreate = false
    @Volatile var serialAtCreate: String? = null
    @Volatile var cameraAnyAtCreate: Boolean? = null
    @Volatile var modelAtCreate: String? = null
    /** "available" once the callback listenForTheNetwork registered heard of a network. */
    val startupNetwork = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** What a default callback and a connectivity/Wi-Fi receiver registered in onCreate heard, in order. */
    val startupHeard = mutableListOf<String>()
    /** The slot's Settings value and /sdcard file in onCreate (SlotStateActivity, apksim://slot). */
    @Volatile var slotAtCreate: String? = null
    /** BluetoothAdapter.isEnabled() in onCreate (null: no adapter, or the device refused: "unknown"). */
    @Volatile var bluetoothOnAtCreate: Boolean? = null
  }
}
