package dev.apksim.composeapp

import android.annotation.SuppressLint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * apksim's `--serial` and `--feature`: run with the values to expect, e.g.
 * `--serial R5CT12345678ABCD --feature hardware.camera.any --feature android.hardware.nfc=false
 * -e expectSerial R5CT12345678ABCD -e expectFeature android.hardware.camera.any -e expectNoFeature android.hardware.nfc`.
 * Skipped without them (a device has its own serial and features).
 */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {
  private val args = InstrumentationRegistry.getArguments()

  @SuppressLint("HardwareIds", "MissingPermission")
  @Test
  fun serialIsTheOneLaunchedWith() {
    val want = args.getString("expectSerial")
    assumeTrue(want != null)
    assertEquals(want, Build.getSerial())
    @Suppress("DEPRECATION") assertEquals(want, Build.SERIAL)
  }

  @Test
  fun featuresAreTheOnesLaunchedWith() {
    val on = args.getString("expectFeature")
    val off = args.getString("expectNoFeature")
    assumeTrue(on != null || off != null)
    val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
    if (on != null) assertTrue("hasSystemFeature($on)", pm.hasSystemFeature(on))
    if (off != null) assertFalse("hasSystemFeature($off)", pm.hasSystemFeature(off))
  }
}
