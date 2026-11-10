package dev.apksim;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.ReprocessFormatsMap;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowCameraManager;

/**
 * The simulated camera (`--camera off|fake`, -Dapksim.camera): with {@code fake} the device declares
 * android.hardware.camera, camera.any and camera.front (SystemFeatures) and CameraManager lists one front-facing
 * camera, id "0", with the characteristics an app reads to choose a stream: 1280x720 and 1920x1080 in YUV_420_888,
 * JPEG and PRIVATE (and SurfaceTexture/SurfaceView outputs), AE and AF modes, LENS_FACING_FRONT and a 270 degree
 * sensor orientation. That is enough for an app to detect a camera and pick its configuration; no frames are
 * produced yet (Robolectric's CameraDevice opens and takes sessions but delivers none). Off (the default) changes
 * nothing.
 */
final class Cameras {
  private Cameras() {}

  static final String[] FEATURES = {
    "android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.front",
  };

  /** The launch's choice: true for {@code fake}. */
  static boolean fake() {
    String v = System.getProperty("apksim.camera");
    return v != null && (v.equals("fake") || v.startsWith("fake="));
  }

  /** Lists the fake camera in CameraManager (or no camera at all when off), as a reset leaves it. */
  static void install(Context context) {
    CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    if (cm == null) return;
    ShadowCameraManager shadow = Shadows.shadowOf(cm);
    if (!fake()) return;
    shadow.addCamera("0", characteristics());
  }

  static CameraCharacteristics characteristics() {
    CameraCharacteristics c = ShadowCameraCharacteristics.newCameraCharacteristics();
    ShadowCameraCharacteristics s = Shadows.shadowOf(c);
    s.set(CameraCharacteristics.LENS_FACING, CameraMetadata.LENS_FACING_FRONT);
    s.set(CameraCharacteristics.SENSOR_ORIENTATION, 270);
    s.set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
    s.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, 1920, 1080));
    s.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, new Size(1920, 1080));
    s.set(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
        new int[] {CameraMetadata.CONTROL_AE_MODE_OFF, CameraMetadata.CONTROL_AE_MODE_ON});
    s.set(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        new int[] {CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE});
    s.set(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        new int[] {CameraMetadata.CONTROL_AWB_MODE_OFF, CameraMetadata.CONTROL_AWB_MODE_AUTO});
    s.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, false);
    s.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        new int[] {CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE});
    s.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, streams());
    return c;
  }

  /** 1280x720 and 1920x1080 at 30 fps in the three formats apps ask for (PRIVATE also serves SurfaceTexture/View). */
  private static StreamConfigurationMap streams() {
    // The HAL's pixel formats, which the map takes: YCbCr_420_888, BLOB (JPEG) and IMPLEMENTATION_DEFINED (PRIVATE).
    int[] formats = {0x23, 0x21, 0x22};
    int[][] sizes = {{1920, 1080}, {1280, 720}};
    List<StreamConfiguration> configs = new ArrayList<>();
    List<StreamConfigurationDuration> durations = new ArrayList<>();
    for (int f : formats) {
      for (int[] sz : sizes) {
        configs.add(new StreamConfiguration(f, sz[0], sz[1], false));
        durations.add(new StreamConfigurationDuration(f, sz[0], sz[1], 33_333_333L));
      }
    }
    StreamConfiguration[] noConfigs = new StreamConfiguration[0];
    StreamConfigurationDuration[] noDurations = new StreamConfigurationDuration[0];
    StreamConfiguration[] c = configs.toArray(noConfigs);
    StreamConfigurationDuration[] d = durations.toArray(noDurations);
    return new StreamConfigurationMap(
        c, d, noDurations, // the format outputs, their min frame and stall durations
        noConfigs, noDurations, noDurations, // depth
        noConfigs, noDurations, noDurations, // depth jpeg
        noConfigs, noDurations, noDurations, // dynamic depth
        noConfigs, noDurations, noDurations, // heic
        noConfigs, noDurations, noDurations, // jpeg/r
        null, new ReprocessFormatsMap(new int[0]), false);
  }
}
