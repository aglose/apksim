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
import android.util.Range;
import android.util.Rational;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowCameraManager;

/**
 * The simulated camera (`--camera off|fake|fake=IMAGE`, -Dapksim.camera; issue 219). With {@code fake} the device
 * declares android.hardware.camera (a camera facing away from the screen), camera.any and camera.front
 * (SystemFeatures), and CameraManager lists the two cameras those mean, as an emulator does: "0" facing back (sensor
 * orientation 90) and "1" facing front (270), each with the characteristics an app reads to choose a stream: 1280x720
 * and 1920x1080 in YUV_420_888, JPEG and PRIVATE (and SurfaceTexture/SurfaceView outputs), AE, AF and AWB modes, frame
 * rate ranges, LIMITED hardware level. A front camera alone with android.hardware.camera declared made CameraX refuse
 * to start ("Expected camera missing from device"). Opening one needs the CAMERA permission
 * ({@code camera.ShadowSimCameraManager}), and its capture sessions deliver frames at 30 fps ({@code camera.FakeCamera}):
 * a moving test pattern, or the still image {@code fake=IMAGE} names. Off (the default) changes nothing. The `camera`
 * command (and Features > Camera) switches what the cameras see while the app runs: `pattern`, `image PATH`, or `off`
 * (black frames, as Android's camera privacy toggle gives an app whose camera is open).
 */
final class Cameras {
  private Cameras() {}

  static final String[] FEATURES = {
    "android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.front",
  };

  /** The launch's choice: true for {@code fake} and {@code fake=IMAGE}. */
  static boolean fake() {
    String v = System.getProperty("apksim.camera");
    return v != null && (v.equals("fake") || v.startsWith("fake="));
  }

  /** Lists the fake camera in CameraManager (or no camera at all when off), as a reset leaves it, and sets its source. */
  static void install(Context context) {
    CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    if (cm == null) return;
    ShadowCameraManager shadow = Shadows.shadowOf(cm);
    if (!fake()) return;
    shadow.addCamera("0", characteristics(CameraMetadata.LENS_FACING_BACK, 90));
    shadow.addCamera("1", characteristics(CameraMetadata.LENS_FACING_FRONT, 270));
    String v = System.getProperty("apksim.camera");
    if (v.startsWith("fake=")) {
      String path = Storage.absolute(v.substring(5)).toString();
      String error = dev.apksim.camera.FakeCamera.image(path);
      if (error != null) {
        System.err.println("[apksim] camera: " + error.substring(7) + "; showing the test pattern");
        dev.apksim.camera.FakeCamera.pattern();
      }
    } else {
      dev.apksim.camera.FakeCamera.pattern();
    }
    if (System.getProperty("apksim.instrument") == null) menuState();
  }

  // Features > Camera (native/simwindow.m), as in DeviceControls.MENU.
  static final int TAG_OFF = 43;
  static final int TAG_PATTERN = 44;
  static final int TAG_IMAGE = 45;

  /** `camera [pattern|image PATH|off]`: what the camera sees, or (alone) its state. */
  static String command(String[] a) {
    if (!fake()) return "error: no camera (launch with --camera fake)";
    String sub = a.length > 1 ? a[1] : "";
    String r;
    switch (sub) {
      case "" -> {
        return "camera " + dev.apksim.camera.FakeCamera.report();
      }
      case "pattern" -> {
        dev.apksim.camera.FakeCamera.pattern();
        r = "camera: test pattern";
      }
      case "off" -> {
        dev.apksim.camera.FakeCamera.block();
        r = "camera: off (black frames)";
      }
      case "image" -> {
        if (a.length < 3) return "usage: camera image PATH";
        String path = Storage.absolute(String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length))).toString();
        String error = dev.apksim.camera.FakeCamera.image(path);
        if (error != null) return error;
        r = "camera: image " + path;
      }
      default -> {
        return "usage: camera [pattern|image PATH|off]";
      }
    }
    menuState();
    return r;
  }

  static void menuState() {
    String kind = dev.apksim.camera.FakeCamera.source().kind();
    SimWindow.setMenuState(TAG_OFF, kind.equals("off"));
    SimWindow.setMenuState(TAG_PATTERN, kind.equals("pattern"));
    SimWindow.setMenuState(TAG_IMAGE, kind.equals("image"));
  }

  /** Features > Camera > Image File...: the Mac's file panel, then `camera image PATH` once a file is chosen. */
  static void pickImage() {
    if (!fake()) {
      System.err.println("[apksim] camera: no camera (launch with --camera fake)");
      return;
    }
    int ticket = SimWindow.pickFiles("image/*", false);
    if (ticket < 0) return;
    Thread t = new Thread(() -> {
      String r;
      while ((r = SimWindow.pickResult(ticket)) == null) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          return;
        }
      }
      if (r.isEmpty()) return;
      System.err.println("[apksim] " + command(new String[] {"camera", "image", r.split("\n")[0]}));
    }, "apksim-camera-pick");
    t.setDaemon(true);
    t.start();
  }

  static CameraCharacteristics characteristics(int facing, int sensorOrientation) {
    CameraCharacteristics c = ShadowCameraCharacteristics.newCameraCharacteristics();
    ShadowCameraCharacteristics s = Shadows.shadowOf(c);
    s.set(CameraCharacteristics.LENS_FACING, facing);
    s.set(CameraCharacteristics.SENSOR_ORIENTATION, sensorOrientation);
    s.set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
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
    // What CameraX's controls read (frame rates, exposure compensation, zoom, metering regions) and the clock the
    // frames' timestamps are on (SystemClock.elapsedRealtimeNanos).
    s.set(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        new Range[] {new Range<>(15, 30), new Range<>(30, 30)});
    s.set(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, new Range<>(0, 0));
    s.set(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, new Rational(1, 1));
    s.set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1f);
    s.set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, new Range<>(1f, 1f));
    s.set(CameraCharacteristics.CONTROL_MAX_REGIONS_AE, 0);
    s.set(CameraCharacteristics.CONTROL_MAX_REGIONS_AF, 0);
    s.set(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB, 0);
    s.set(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE, CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
    s.set(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, 0f);
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
