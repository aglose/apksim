package dev.apksim.composeapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
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
 * the device identity (DeviceIdentityTest; logged as "identity: ..." for running instances) and the animator scale
 * (AnimScaleOptionTest).
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
    animScaleAtCreate = android.animation.ValueAnimator.getDurationScale()
    Log.i("ComposeApp", "identity: serial=$serialAtCreate SERIAL=${Build.SERIAL} camera.any=$cameraAnyAtCreate " +
        "model=$modelAtCreate release=${Build.VERSION.RELEASE}")
    if (packageManager.hasSystemFeature(LAUNCH_OVERLAY)) addLaunchOverlay()
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
  }
}
