package dev.apksim.composeapp

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.graphics.ImageFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * apksim's simulated camera (`--camera fake`; run with `-e camera fake`, or `-e camera off` for the default): the
 * camera features and one front camera in CameraManager, or neither. apksim only. Frames are not covered yet.
 */
@RunWith(AndroidJUnit4::class)
class CameraTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val mode = InstrumentationRegistry.getArguments().getString("camera")

  @Before
  fun onApksimOnly() {
    assumeTrue("apksim's camera", Build.FINGERPRINT == "robolectric")
    assumeTrue("run with -e camera fake|off and --camera fake|off", mode != null)
  }

  @Test
  fun theCameraFeaturesFollowTheOption() {
    val pm = context.packageManager
    val want = mode == "fake"
    assertEquals(want, pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
    assertEquals(want, pm.hasSystemFeature(PackageManager.FEATURE_CAMERA))
    assertEquals(want, pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
    assertEquals(want, ComposeApp.cameraAnyAtCreate)
  }

  @Test
  fun cameraManagerListsOneFrontCameraOrNone() {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    if (mode != "fake") {
      assertTrue(cm.cameraIdList.isEmpty())
      return
    }
    assertEquals(listOf("0"), cm.cameraIdList.toList())
    val c = cm.getCameraCharacteristics("0")
    assertEquals(CameraMetadata.LENS_FACING_FRONT, c.get(CameraCharacteristics.LENS_FACING))
    assertEquals(270, c.get(CameraCharacteristics.SENSOR_ORIENTATION))
    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    for (format in intArrayOf(ImageFormat.YUV_420_888, ImageFormat.JPEG)) {
      val sizes = map.getOutputSizes(format).map { "${it.width}x${it.height}" }
      assertTrue("$format: $sizes", "1280x720" in sizes && "1920x1080" in sizes)
    }
    assertFalse(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)!!.isEmpty())
  }
}
