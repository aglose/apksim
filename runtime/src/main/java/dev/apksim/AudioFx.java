package dev.apksim;

import android.media.audiofx.Visualizer;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * android.media.audiofx.Visualizer (issue 514). Robolectric leaves its native methods unshadowed, so the constructor
 * succeeded but {@code getCaptureSizeRange()} answered null and {@code getMaxCaptureRate()} 0: an app that sizes its
 * capture from them (a waveform view on a Media3 player's audio session) died with a NullPointerException on the first
 * play. They answer as AOSP's: sizes 128..1024, a maximum rate of 20000 mHz, a 44.1 kHz sampling rate. The setters
 * return SUCCESS, only the capture size reads back (getEnabled and the modes read as defaults), and no data is
 * delivered (the audio never reaches a capture here).
 */
@Implements(value = Visualizer.class, isInAndroidSdk = false)
public class AudioFx {
  /** Per Visualizer: the capture size last set (AOSP's default is 1024). */
  private int captureSize = 1024;

  @Implementation
  protected static int[] getCaptureSizeRange() {
    return new int[] {128, 1024};
  }

  @Implementation
  protected static int getMaxCaptureRate() {
    return 20000;
  }

  @Implementation
  protected int native_setCaptureSize(int size) {
    if (size < 128 || size > 1024 || (size & (size - 1)) != 0) return Visualizer.ERROR_BAD_VALUE;
    captureSize = size;
    return Visualizer.SUCCESS;
  }

  @Implementation
  protected int native_getCaptureSize() {
    return captureSize;
  }

  @Implementation
  protected int native_getSamplingRate() {
    return 44100000;
  }
}
