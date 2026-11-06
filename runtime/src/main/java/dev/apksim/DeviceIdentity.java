package dev.apksim;

import android.app.Application;
import android.os.Build;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.util.ReflectionHelpers;

/**
 * What the device says it is, beyond the display: `--serial VALUE` (Build.getSerial() and Build.SERIAL, "unknown"
 * otherwise, as an ordinary app sees on a device) and `--feature NAME` (PackageManager.hasSystemFeature; NAME=false
 * takes one away). Applied before Application.onCreate in a running instance and before each test's, since Robolectric
 * resets both between tests (ShadowSimInstrumentation.callApplicationOnCreate). An app that sends its serial to a
 * backend was rejected for "unknown"; one that tells two device variants apart by FEATURE_CAMERA_ANY always saw the
 * one without.
 */
final class DeviceIdentity {
  private DeviceIdentity() {}

  static void apply(Application app) {
    String serial = System.getProperty("apksim.serial");
    if (serial != null && !serial.isEmpty()) {
      ShadowBuild.setSerial(serial);
      // Robolectric's instrumented framework has no final fields, so the old field (read by apps targeting < 26) is set too.
      ReflectionHelpers.setStaticField(Build.class, "SERIAL", serial);
    }
    String features = System.getProperty("apksim.features");
    if (features == null || features.isEmpty()) return;
    var pm = org.robolectric.Shadows.shadowOf(app.getPackageManager());
    for (String f : features.split(",")) {
      f = f.strip();
      if (f.isEmpty()) continue;
      boolean on = !f.endsWith("=false");
      String name = f.replaceFirst("=(true|false)$", "");
      // The framework's, short: hardware.camera.any is android.hardware.camera.any. Others (a vendor's) as given.
      if (name.startsWith("hardware.") || name.startsWith("software.")) name = "android." + name;
      pm.setSystemFeature(name, on);
    }
  }
}
