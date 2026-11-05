package dev.apksim.composeapp

import android.Manifest
import android.app.Application
import android.provider.Settings

/**
 * The app's Application. The androidTest runner swaps in a subclass (HarnessRunner). It records what onCreate sees,
 * before any test code runs (AndroidIdTest, InstallPermissionsTest).
 */
open class ComposeApp : Application() {
  override fun onCreate() {
    super.onCreate()
    androidIdAtCreate = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    vibrateAtCreate = checkSelfPermission(Manifest.permission.VIBRATE)
    providerBeforeCreate = StartupProvider.created
  }

  companion object {
    @Volatile var androidIdAtCreate: String? = null
    @Volatile var vibrateAtCreate: Int? = null
    @Volatile var providerBeforeCreate = false
  }
}
