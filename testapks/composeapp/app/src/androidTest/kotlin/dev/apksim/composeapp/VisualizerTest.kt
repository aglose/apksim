package dev.apksim.composeapp

import android.media.audiofx.Visualizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    assertTrue(range[0] in 1..range[1])
  }

  @Test
  fun maxCaptureRateIsPositive() {
    assertTrue(Visualizer.getMaxCaptureRate() > 0)
  }
}
