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
import android.Manifest
import android.content.Intent
import android.graphics.ImageFormat as Formats
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * apksim's simulated camera (`--camera fake`; run with `-e camera fake`, or `-e camera off` for the default): the
 * camera features and a back and a front camera in CameraManager, or neither; with it, opening the camera needs the CAMERA
 * permission, a Camera2 repeating request fills an ImageReader with changing YUV frames whose timestamps match the
 * capture results, and CameraX binds a Preview and an ImageAnalysis (apksim://camera) and gets frames. apksim only.
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
  fun cameraManagerListsABackAndAFrontCameraOrNone() {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    if (mode != "fake") {
      assertTrue(cm.cameraIdList.isEmpty())
      return
    }
    // android.hardware.camera is a camera facing away from the screen, camera.front one facing the user: both, as an
    // emulator lists them.
    assertEquals(listOf("0", "1"), cm.cameraIdList.toList())
    for ((id, facing, orientation) in listOf(Triple("0", CameraMetadata.LENS_FACING_BACK, 90), Triple("1", CameraMetadata.LENS_FACING_FRONT, 270))) {
      val c = cm.getCameraCharacteristics(id)
      assertEquals(facing, c.get(CameraCharacteristics.LENS_FACING))
      assertEquals(orientation, c.get(CameraCharacteristics.SENSOR_ORIENTATION))
      val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
      for (format in intArrayOf(ImageFormat.YUV_420_888, ImageFormat.JPEG)) {
        val sizes = map.getOutputSizes(format).map { "${it.width}x${it.height}" }
        assertTrue("$format: $sizes", "1280x720" in sizes && "1920x1080" in sizes)
      }
      assertFalse(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)!!.isEmpty())
    }
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val thread = HandlerThread("camera-test").apply { start() }
  private val handler = Handler(thread.looper)

  @After
  fun tearDown() {
    thread.quitSafely()
    if (mode == "fake") shell("pm revoke ${context.packageName} ${Manifest.permission.CAMERA}")
  }

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  /**
   * Waits on the main thread (where the test runs here) until [done], letting the main queue run and the simulated
   * clock move as a device's would meanwhile.
   */
  private fun waitFor(what: String, seconds: Int = 15, done: () -> Boolean) {
    val until = System.nanoTime() + seconds * 1_000_000_000L
    while (!done()) {
      if (System.nanoTime() > until) fail("timed out waiting for $what")
      SystemClock.sleep(10)
      Thread.sleep(10)
    }
  }

  private fun open(cm: CameraManager): CameraDevice {
    var device: CameraDevice? = null
    var error: String? = null
    cm.openCamera("0", object : CameraDevice.StateCallback() {
      override fun onOpened(camera: CameraDevice) { device = camera }
      override fun onDisconnected(camera: CameraDevice) { error = "disconnected" }
      override fun onError(camera: CameraDevice, e: Int) { error = "error $e" }
    }, handler)
    waitFor("the camera to open") { device != null || error != null }
    assertEquals(null, error)
    return device!!
  }

  @Test
  fun openingTheCameraNeedsThePermission() {
    assumeTrue(mode == "fake")
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    shell("pm revoke ${context.packageName} ${Manifest.permission.CAMERA}")
    try {
      cm.openCamera("0", object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {}
        override fun onDisconnected(camera: CameraDevice) {}
        override fun onError(camera: CameraDevice, e: Int) {}
      }, handler)
      fail("opened without the CAMERA permission")
    } catch (e: SecurityException) {
      // as the camera service refuses it
    }
    shell("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
    open(cm).close()
  }

  @Test
  fun aRepeatingRequestFillsAnImageReaderWithChangingFrames() {
    assumeTrue(mode == "fake")
    shell("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val device = open(cm)
    val reader = ImageReader.newInstance(1280, 720, Formats.YUV_420_888, 4)
    val lumas = CopyOnWriteArrayList<Long>()
    val imageTimes = CopyOnWriteArrayList<Long>()
    var layout = ""
    var triangle = -1
    var beside = -1
    reader.setOnImageAvailableListener({ r ->
      r.acquireLatestImage()?.use { image ->
        val planes = image.planes
        layout = "${image.width}x${image.height} ${planes.size} planes, strides " +
          planes.joinToString { "${it.rowStride}/${it.pixelStride}" }
        val y = planes[0].buffer
        var sum = 0L
        for (i in 0 until y.remaining() step 97) sum += (y.get(i).toInt() and 0xff) * (i % 13 + 1)
        if (lumas.isEmpty()) {
          // Camera 0 faces back with its sensor at 90 degrees: the buffer is the scene turned 90 degrees anticlockwise,
          // so the pattern's black triangle at the top centre is at the buffer's left, halfway down.
          triangle = y.get(359 * planes[0].rowStride + 60).toInt() and 0xff
          beside = y.get(359 * planes[0].rowStride + 200).toInt() and 0xff
        }
        lumas.add(sum)
        imageTimes.add(image.timestamp)
      }
    }, handler)
    val still = ImageReader.newInstance(1920, 1080, Formats.JPEG, 2)
    var jpeg: ByteArray? = null
    still.setOnImageAvailableListener({ r ->
      r.acquireNextImage()?.use { image ->
        val b = image.planes[0].buffer
        jpeg = ByteArray(b.remaining()).also { b.get(it) }
      }
    }, handler)
    var session: CameraCaptureSession? = null
    val states = CopyOnWriteArrayList<String>()
    val outputs = listOf(OutputConfiguration(reader.surface), OutputConfiguration(still.surface))
    device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) },
      object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) { session = s }
        override fun onConfigureFailed(s: CameraCaptureSession) {}
        override fun onActive(s: CameraCaptureSession) { states.add("active") }
        override fun onReady(s: CameraCaptureSession) { states.add("ready") }
        override fun onClosed(s: CameraCaptureSession) { states.add("closed") }
      }))
    waitFor("the session") { session != null }
    val results = ConcurrentHashMap<Long, TotalCaptureResult>()
    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
      addTarget(reader.surface)
      set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }.build()
    session!!.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
        results[result.get(CaptureResult.SENSOR_TIMESTAMP)!!] = result
      }
    }, handler)
    waitFor("10 frames") { lumas.size >= 10 }
    // A still capture between the repeating frames, as an app takes a picture.
    var stillDone = false
    session!!.capture(device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
      addTarget(still.surface)
      set(CaptureRequest.JPEG_ORIENTATION, 90)
    }.build(),
      object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) { stillDone = true }
      }, handler)
    waitFor("a JPEG") { jpeg != null && stillDone }
    val picture = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
    assertEquals("1920x1080", "${picture.width}x${picture.height}")
    // The request's JPEG_ORIENTATION as EXIF, as a camera that leaves the pixels sensor-oriented writes it.
    val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
    assertEquals(android.media.ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0))
    session!!.stopRepeating()
    // As a device's camera: active while it has requests, ready once the last one is done, closed after that.
    waitFor("onReady after stopRepeating ($states)") { states.lastOrNull() == "ready" }
    session!!.close()
    waitFor("onClosed ($states)") { states.lastOrNull() == "closed" }
    assertEquals("active", states.first())
    device.close()
    reader.close()
    still.close()
    assertEquals("1280x720 3 planes, strides 1280/1, 1280/2, 1280/2", layout)
    assertTrue("frames change: $lumas", lumas.toSet().size > 1)
    assertTrue("the scene's top is at the buffer's left: luma $triangle there, $beside beside it", triangle < 40 && beside > 60)
    val result = results[imageTimes.first()]
    assertNotNull("a result with the image's timestamp ${imageTimes.first()}: ${results.keys}", result)
    assertEquals(CaptureResult.CONTROL_AE_STATE_CONVERGED, result!!.get(CaptureResult.CONTROL_AE_STATE))
    assertEquals(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, result.get(CaptureResult.CONTROL_AF_STATE))
    assertEquals(request, result.request)
    assertNotEquals(imageTimes[0], imageTimes[1])
  }

  @Test
  fun cameraXBindsAPreviewAndAnAnalysisAndGetsFrames() {
    assumeTrue(mode == "fake")
    shell("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
    CameraActivity.frames.set(0)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("apksim://camera?mode=compatible")).setClass(context, CameraActivity::class.java)
    ActivityScenario.launch<CameraActivity>(intent).use { scenario ->
      waitFor("ImageAnalysis frames (${CameraActivity.last})") { CameraActivity.frames.get() >= 5 }
      assertEquals("1280x720 YUV, rotation 270", CameraActivity.last)
      var colours = 0
      waitFor("a preview picture") {
        scenario.onActivity { a ->
          val b = a.previewBitmap()
          if (b != null) {
            val seen = HashSet<Int>()
            for (y in 0 until b.height step 37) for (x in 0 until b.width step 37) seen.add(b.getPixel(x, y))
            colours = seen.size
          }
        }
        colours > 4
      }
    }
  }
}
