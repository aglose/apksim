package dev.apksim.composeapp

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device's serial, system features and Build fields, set before Application.onCreate (apksim's --serial,
 * --feature, --build). Without them the serial is a device-like default; with them (CI runs this class with
 * `-e serial S -e feature F -e model M` and the same launch options) the app sees exactly what was given. apksim
 * only: a device or emulator refuses Build.getSerial() to an app without privileged access.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("HardwareIds", "MissingPermission")
class DeviceIdentityTest {
  private val args = InstrumentationRegistry.getArguments()

  @Before
  fun onApksimOnly() {
    assumeTrue("apksim's device identity", Build.FINGERPRINT == "robolectric")
  }

  @Test
  fun theSerialLooksLikeADevicesAndIsThereBeforeTheApplicationStarts() {
    val serial = Build.getSerial()
    assertEquals(serial, Build.SERIAL)
    assertEquals("Build.getSerial() in Application.onCreate", serial, ComposeApp.serialAtCreate)
    val expected = args.getString("serial")
    if (expected != null) {
      assertEquals(expected, serial)
    } else {
      assertTrue("16 letters and digits: $serial", serial.matches(Regex("[A-Z][0-9][A-Z0-9]{14}")))
    }
  }

  @Test
  fun aFeatureGivenAtLaunchIsThereBeforeTheApplicationStarts() {
    val feature = args.getString("feature")
    assumeTrue("run with -e feature NAME and --feature NAME", feature != null)
    val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
    assertTrue(feature, pm.hasSystemFeature(feature!!))
    if (feature == PackageManager.FEATURE_CAMERA_ANY) assertEquals(true, ComposeApp.cameraAnyAtCreate)
  }

  @Test
  fun aBuildFieldGivenAtLaunchIsThereBeforeTheApplicationStarts() {
    val model = args.getString("model")
    assumeTrue("run with -e model M and --build MODEL=M", model != null)
    assertEquals(model, Build.MODEL)
    assertEquals(model, ComposeApp.modelAtCreate)
  }
}
