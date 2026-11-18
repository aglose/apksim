package dev.apksim.composeapp

import android.media.audiofx.Visualizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * android.media.audiofx.Visualizer's static answers (apksim's AudioFx, issue 514): an app sizing its capture from
 * getCaptureSizeRange()[0] and getMaxCaptureRate() must not meet null or 0. AOSP: sizes 128..1024, 20000 mHz.
 */
@RunWith(AndroidJUnit4::class)
class VisualizerTest {
  @Test
  fun captureSizeRangeIsNeverNull() {
    val range = Visualizer.getCaptureSizeRange()
    assertNotNull(range)
    assertEquals(2, range.size)
    assertEquals(128, range[0])
    assertEquals(1024, range[1])
  }

  @Test
  fun maxCaptureRateIsPositive() {
    assertEquals(20000, Visualizer.getMaxCaptureRate())
  }

  @Test
  fun captureSizeIsValidatedAndReadBack() {
    // On a device the output mix needs RECORD_AUDIO and MODIFY_AUDIO_SETTINGS, which the test app does not hold.
    val v = try { Visualizer(0) } catch (e: RuntimeException) { assumeTrue("no Visualizer here: $e", false); return }
    try {
      assertEquals(Visualizer.SUCCESS, v.setCaptureSize(512))
      assertEquals(512, v.captureSize)
      // The framework turns a native error into IllegalArgumentException.
      assertThrows(IllegalArgumentException::class.java) { v.setCaptureSize(100) }
      assertThrows(IllegalArgumentException::class.java) { v.setCaptureSize(2048) }
      assertEquals(512, v.captureSize)
    } finally {
      v.release()
    }
  }
}
