package dev.apksim.composeapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installing an APK grants the permissions of protection level normal it asks for; runtime ones wait for the user.
 * A framework call that checks a normal permission (VIBRATE here, BLUETOOTH in another app) must not throw.
 */
@RunWith(AndroidJUnit4::class)
class InstallPermissionsTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun normalPermissionsAreGrantedAtInstall() {
    assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.VIBRATE))
  }

  /** Granted at install, so the app's own startup can rely on it: its Application's onCreate saw it granted. */
  @Test
  fun normalPermissionsAreGrantedBeforeTheApplicationStarts() {
    assertEquals(PackageManager.PERMISSION_GRANTED, ComposeApp.vibrateAtCreate)
  }

  /** A signature permission of the platform is not an ordinary APK's, whatever it asks for (even with `-e permissions grant`). */
  @Test
  fun signaturePermissionsAreNot() {
    assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS))
  }

  /** Not with `-e permissions grant` (apksim's --permissions grant, an emulator's `adb install -g`). */
  @Test
  fun runtimePermissionsAreNot() {
    assumeFalse(InstrumentationRegistry.getArguments().getString("permissions") == "grant")
    assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
  }
}
