package dev.apksim;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;

/**
 * The Bluetooth adapter, on as an emulator boots (Robolectric's adapter has state 0, neither on nor off, so
 * isEnabled() was false and nothing could turn it on). Off at launch with {@code --bluetooth off}; switched with the
 * `bluetooth on|off|toggle` command, `adb shell svc bluetooth enable|disable`, `cmd bluetooth_manager
 * enable|disable` and `settings put global bluetooth_on 1|0`, each going TURNING_ON then ON (TURNING_OFF then OFF) with
 * an ACTION_STATE_CHANGED broadcast for each step, as a device sends them, and Settings.Global.BLUETOOTH_ON following.
 * A device without android.hardware.bluetooth (`--feature android.hardware.bluetooth=false`) has no adapter:
 * getDefaultAdapter() and BluetoothManager.getAdapter() are null. The adapter's name is Build.MODEL (--build MODEL=...)
 * and its address 02:00:00:00:00:00, what a device tells an app without LOCAL_MAC_ADDRESS. Its LE scanner is
 * Robolectric's (non-null: BLE scanning stays available while Bluetooth is off, as Robolectric's adapter says), and
 * scans find nothing. Set before Application.onCreate, so per test in a test run, where Robolectric's reset brings a
 * new adapter: each test starts with the launch's state.
 */
final class Bluetooth {
  private Bluetooth() {}

  /** The state asked for: the launch's, then the last command's. */
  static volatile boolean on = launchState();

  private static boolean launchState() {
    String v = System.getProperty("apksim.bluetooth", "on").trim().toLowerCase(java.util.Locale.ROOT);
    return !(v.equals("off") || v.equals("false") || v.equals("0") || v.equals("disabled"));
  }

  static void beforeApplicationOnCreate(Application app) {
    on = launchState();
    featuresChanged(app);
  }

  /** The device has Bluetooth or not (its feature), and its adapter in the state asked for. */
  static void featuresChanged(Application app) {
    boolean supported = supported(app);
    ShadowBluetoothAdapter.setIsBluetoothSupported(supported);
    setSetting(app, supported && on);
    if (!supported) return;
    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
    ShadowBluetoothAdapter s = Shadow.extract(a);
    s.setState(on ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF);
    s.setAddress("02:00:00:00:00:00");
    a.setName(Build.MODEL);
  }

  private static boolean supported(Application app) {
    return app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
  }

  /** bluetooth [on|off|toggle]: the state, or a switch to another. Main thread (or a test run's test thread). */
  static String command(String[] a) {
    Application app = RuntimeEnvironment.getApplication();
    String op = a.length > 1 ? a[1] : "";
    if (op.isEmpty()) return status(app);
    boolean want = switch (op) {
      case "on", "enable", "1" -> true;
      case "off", "disable", "0" -> false;
      case "toggle" -> !on;
      default -> throw new IllegalArgumentException("usage: bluetooth [on|off|toggle]");
    };
    if (!supported(app)) return "error: the device has no Bluetooth (android.hardware.bluetooth is off)";
    set(app, want);
    return "ok (" + (want ? "on" : "off") + ")";
  }

  static String status(Application app) {
    if (!supported(app)) return "none (no android.hardware.bluetooth)";
    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
    return (a.isEnabled() ? "on" : "off") + " (" + stateName(a.getState()) + ", name " + a.getName() + ", address "
        + a.getAddress() + ")";
  }

  /** Switches the adapter, broadcasting each step: TURNING_ON then ON, or TURNING_OFF then OFF. */
  private static void set(Application app, boolean want) {
    on = want;
    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
    ShadowBluetoothAdapter s = Shadow.extract(a);
    int now = a.getState();
    int target = want ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
    if (now == target) return;
    for (int step : new int[] {want ? BluetoothAdapter.STATE_TURNING_ON : BluetoothAdapter.STATE_TURNING_OFF, target}) {
      s.setState(step);
      app.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
          .putExtra(BluetoothAdapter.EXTRA_STATE, step)
          .putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, now));
      now = step;
    }
    setSetting(app, want);
  }

  private static void setSetting(Application app, boolean value) {
    Settings.Global.putInt(app.getContentResolver(), Settings.Global.BLUETOOTH_ON, value ? 1 : 0);
    app.getContentResolver().notifyChange(Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON), null);
  }

  static String stateName(int state) {
    return switch (state) {  // BluetoothAdapter's (the framework jar's fields are not compile-time constants)
      case 10 -> "STATE_OFF";
      case 11 -> "STATE_TURNING_ON";
      case 12 -> "STATE_ON";
      case 13 -> "STATE_TURNING_OFF";
      default -> "state " + state;
    };
  }
}
