package dev.apksim;

import android.app.Application;
import android.content.pm.FeatureInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

/**
 * The device's system features (PackageManager.hasSystemFeature, getSystemAvailableFeatures, `pm list features`),
 * set before Application.onCreate (DeviceIdentity) and again when the `device` command switches devices. Robolectric's
 * package manager starts each test with a few (touchscreen, wifi*, telephony*); a device has many more, and apps
 * read them to decide what to offer: one treated a device without android.hardware.bluetooth as having no Bluetooth.
 * apksim adds what it simulates (Bluetooth and BLE, location, multitouch, audio output, a fingerprint sensor, both
 * screen orientations), and per device kind what an emulator of that kind reports: a TV has no touchscreen,
 * telephony, GPS or fingerprint, and has leanback; a desktop is android.hardware.type.pc. No camera unless `--camera fake`
 * (Cameras: the three camera features; the permission can be granted, but no frames come yet), and
 * `--feature android.hardware.camera.any` is for an app that must see one without a CameraManager camera. Then the launch's {@code --feature NAME[=false]} (-Dapksim.features=a,b=false), which
 * can also take a default away.
 */
final class SystemFeatures {
  private SystemFeatures() {}

  /** What apksim adds on every device kind but a TV. */
  private static final String[] HANDHELD = {
    "android.hardware.bluetooth", "android.hardware.bluetooth_le", "android.hardware.location",
    "android.hardware.location.gps", "android.hardware.location.network", "android.hardware.faketouch",
    "android.hardware.touchscreen", "android.hardware.touchscreen.multitouch",
    "android.hardware.touchscreen.multitouch.distinct", "android.hardware.touchscreen.multitouch.jazzhand",
    "android.hardware.screen.portrait", "android.hardware.screen.landscape", "android.hardware.audio.output",
    "android.hardware.fingerprint", "android.software.input_methods", "android.software.secure_lock_screen",
  };

  /** A TV's (an Android TV emulator's, of the features above). */
  private static final String[] TV = {
    "android.hardware.bluetooth", "android.hardware.bluetooth_le", "android.hardware.location",
    "android.hardware.location.network", "android.hardware.screen.landscape", "android.hardware.audio.output",
    "android.hardware.type.television", "android.software.leanback", "android.software.leanback_only",
    "android.software.input_methods",
  };

  /**
   * The values Robolectric had for the keys apksim changed since the last reset (null: it had none), so a switch to
   * another device kind starts again from Robolectric's. Main thread only.
   */
  private static final Map<String, Boolean> robolectric = new HashMap<>();

  /** Before Application.onCreate, after Robolectric's reset: the launch device's features and the --features. */
  static void beforeApplicationOnCreate(Application app) {
    robolectric.clear();
    apply(app, Device.peek());
  }

  /** The `device` command switched to another kind of device (a TV and back). */
  static void deviceChanged(Application app, Device d) {
    Map<String, Boolean> list = list();
    for (Map.Entry<String, Boolean> e : robolectric.entrySet()) {
      if (e.getValue() == null) list.remove(e.getKey());
      else list.put(e.getKey(), e.getValue());
    }
    robolectric.clear();
    apply(app, d);
    Bluetooth.featuresChanged(app);
  }

  private static void apply(Application app, Device d) {
    Map<String, Boolean> want = new LinkedHashMap<>();
    if (d.tv()) {
      for (String f : HANDHELD) want.put(f, false);
      for (String f : list().keySet()) if (f.startsWith("android.hardware.telephony")) want.put(f, false);
      for (String f : TV) want.put(f, true);
    } else {
      for (String f : HANDHELD) want.put(f, true);
      if (d.kind().equals("desktop")) want.put("android.hardware.type.pc", true);
    }
    if (!d.tv() && Cameras.fake()) for (String f : Cameras.FEATURES) want.put(f, true);  // --camera fake
    want.putAll(launchOptions());
    Map<String, Boolean> list = list();
    for (Map.Entry<String, Boolean> e : want.entrySet()) {
      if (!robolectric.containsKey(e.getKey())) robolectric.put(e.getKey(), list.get(e.getKey()));
      list.put(e.getKey(), e.getValue());
    }
    // getSystemAvailableFeatures: Robolectric keeps a separate list (empty, so null), which a device fills from the same.
    ShadowPackageManager pm = Shadows.shadowOf(app.getPackageManager());
    pm.clearSystemAvailableFeatures();
    for (String f : present()) {
      FeatureInfo fi = new FeatureInfo();
      fi.name = f;
      pm.addSystemAvailableFeature(fi);
    }
  }

  /** -Dapksim.features=a,b,c=false: each one made present (or, with =false, absent). */
  private static Map<String, Boolean> launchOptions() {
    Map<String, Boolean> out = new LinkedHashMap<>();
    String list = System.getProperty("apksim.features");
    if (list == null || list.isBlank()) return out;
    for (String f : list.split(",")) {
      f = f.trim();
      if (f.isEmpty()) continue;
      boolean on = true;
      int eq = f.indexOf('=');
      if (eq > 0) {
        on = !f.substring(eq + 1).trim().equalsIgnoreCase("false");
        f = f.substring(0, eq).trim();
      }
      out.put(f, on);
    }
    return out;
  }

  /** The features hasSystemFeature answers true for, sorted (what `pm list features` prints). */
  static List<String> present() {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Boolean> e : new TreeMap<>(list()).entrySet()) if (Boolean.TRUE.equals(e.getValue())) out.add(e.getKey());
    return out;
  }

  /** Robolectric's own table, which hasSystemFeature and a test's setSystemFeature read and write. */
  private static Map<String, Boolean> list() {
    return ReflectionHelpers.getStaticField(ShadowPackageManager.class, "systemFeatureList");
  }
}
