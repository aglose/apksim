package dev.apksim.composeapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The app's Application. The androidTest runner swaps in a subclass (HarnessRunner). It records what onCreate sees,
 * before any test code runs: the network ConnectivityManager reports (NetworkActivity shows it: apksim's
 * --network-profile offline must hold from before onCreate), AndroidIdTest's and InstallPermissionsTest's values, and
 * the device identity (DeviceIdentityTest; logged as "identity: ..." for running instances), and the data slot's
 * Settings value and /sdcard file (SlotStateActivity).
 */
open class ComposeApp : Application() {
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
    Log.i("ComposeApp", "identity: serial=$serialAtCreate SERIAL=${Build.SERIAL} camera.any=$cameraAnyAtCreate " +
        "model=$modelAtCreate release=${Build.VERSION.RELEASE}")
    slotAtCreate = SlotStateActivity.describe(this)
  }

  companion object {
    /** ConnectivityManager's default network in Application.onCreate, or null before it ran. */
    @JvmStatic var networkAtStart: String? = null
    @Volatile var androidIdAtCreate: String? = null
    @Volatile var vibrateAtCreate: Int? = null
    @Volatile var providerBeforeCreate = false
    @Volatile var serialAtCreate: String? = null
    @Volatile var cameraAnyAtCreate: Boolean? = null
    @Volatile var modelAtCreate: String? = null
    /** The slot's Settings value and /sdcard file in onCreate (SlotStateActivity, apksim://slot). */
    @Volatile var slotAtCreate: String? = null
  }
}
