package dev.apksim;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;

/**
 * The Bluetooth adapter, on as an emulator boots (Robolectric's adapter has state 0, neither on nor off, so
 * isEnabled() was false and nothing could turn it on). Off at launch with {@code --bluetooth off}; switched with the
 * `bluetooth on|off|toggle` command, `adb shell svc bluetooth enable|disable`, `cmd bluetooth_manager
 * enable|disable`, `settings put global bluetooth_on 1|0` and the app's own BluetoothAdapter.enable()/disable()
 * (ShadowSimBluetoothAdapter), each going TURNING_ON then ON (TURNING_OFF then OFF) with an ACTION_STATE_CHANGED
 * broadcast for each step, as a device sends them, and Settings.Global.BLUETOOTH_ON following. The adapter's state is
 * the only record of whether it is on: nothing here keeps a copy that could go stale. A device without
 * android.hardware.bluetooth (`--feature android.hardware.bluetooth=false`) has no adapter: getDefaultAdapter() and
 * BluetoothManager.getAdapter() are null. The adapter's name is Build.MODEL (--build MODEL=...) and its address
 * 02:00:00:00:00:00, what a device tells an app without LOCAL_MAC_ADDRESS. Its LE scanner is Robolectric's (non-null:
 * BLE scanning stays available while Bluetooth is off, as Robolectric's adapter says), and scans find nothing. Set
 * before Application.onCreate, so per test in a test run, where Robolectric's reset brings a new adapter: each test
 * starts with the launch's state.
 */
public final class Bluetooth {
  private Bluetooth() {}

  private static boolean launchState() {
    String v = System.getProperty("apksim.bluetooth", "on").trim().toLowerCase(java.util.Locale.ROOT);
    return !(v.equals("off") || v.equals("false") || v.equals("0") || v.equals("disabled"));
  }

  /** At the application's (a test's) start: the launch's state. */
  static void beforeApplicationOnCreate(Application app) {
    apply(app, launchState());
  }

  /** The device's features changed (`device` switched kinds): Bluetooth or none, an adapter keeping its state. */
  static void featuresChanged(Application app) {
    apply(app, null);
  }

  /**
   * Held for each whole switch: the adapter's state, what it broadcasts (queued, in order) and bluetooth_on, so a
   * switch on one thread (the app's enable()/disable(), on whatever thread it calls them) and one on another (the shell
   * and control commands, on the main thread) never leave the adapter on with the setting 0. Never held while
   * broadcasting or notifying observers, which may call back into the adapter from another thread.
   */
  private static final Object LOCK = new Object();

  /** Held while sending what the switches queued, so their broadcasts go out in the order the switches happened. */
  private static final Object SEND = new Object();

  /** Broadcasts and setting notifications the switches decided on, oldest first. Guarded by LOCK. */
  private static final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<>();

  /** The adapter there or not (its feature), in state [on], or (null) in the state it has. */
  private static void apply(Application app, Boolean on) {
    synchronized (LOCK) {
      boolean supported = supported(app);
      ShadowBluetoothAdapter.setIsBluetoothSupported(supported);
      if (!supported) {
        writeSetting(app, false);
      } else {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        ShadowBluetoothAdapter s = Shadow.extract(a);
        int state = a.getState();
        // An adapter never set up (the device had no Bluetooth until now) starts as the launch asked.
        boolean want = on != null ? on : state == 0 ? launchState() : isOn(state);
        if (state == 0 || want != isOn(state)) s.setState(want ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF);
        s.setAddress("02:00:00:00:00:00");
        a.setName(Build.MODEL);
        writeSetting(app, want);
      }
    }
    flush();
  }

  private static boolean isOn(int state) {
    return state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON;
  }

  private static boolean supported(Application app) {
    return app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
  }

  /** bluetooth [on|off|toggle]: the state, or a switch to another. Main thread (or a test run's test thread). */
  static String command(String[] a) {
    Application app = RuntimeEnvironment.getApplication();
    String op = a.length > 1 ? a[1] : "";
    if (op.isEmpty()) return status(app);
    if (!op.matches("on|enable|1|off|disable|0|toggle")) return "usage: bluetooth [on|off|toggle]";
    if (!supported(app)) return "error: the device has no Bluetooth (android.hardware.bluetooth is off)";
    boolean want;
    synchronized (LOCK) {
      // The adapter's own state, which the app may have changed (enable()/disable()), read and switched in one go.
      want = op.equals("toggle") ? !BluetoothAdapter.getDefaultAdapter().isEnabled() : op.matches("on|enable|1");
      switchTo(app, want);
    }
    flush();
    return "ok (" + (want ? "on" : "off") + ")";
  }

  static String status(Application app) {
    if (!supported(app)) return "none (no android.hardware.bluetooth)";
    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
    return (a.isEnabled() ? "on" : "off") + " (" + stateName(a.getState()) + ", name " + a.getName() + ", address "
        + a.getAddress() + ")";
  }

  /** Switches the adapter (see switchTo), from any thread. */
  static void set(Application app, boolean want) {
    synchronized (LOCK) {
      switchTo(app, want);
    }
    flush();
  }

  /**
   * Switches the adapter, queueing a broadcast for each step: TURNING_ON then ON, or TURNING_OFF then OFF.
   * bluetooth_on is set to [want] even when the adapter is in that state already (it may have been written behind the
   * adapter's back). Under LOCK; the caller flushes after releasing it.
   */
  private static void switchTo(Application app, boolean want) {
    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
    ShadowBluetoothAdapter s = Shadow.extract(a);
    int now = a.getState();
    int target = want ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
    if (now != target) {
      for (int step : new int[] {want ? BluetoothAdapter.STATE_TURNING_ON : BluetoothAdapter.STATE_TURNING_OFF, target}) {
        s.setState(step);
        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, step)
            .putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, now);
        pending.add(() -> app.sendBroadcast(intent));
        now = step;
      }
    }
    writeSetting(app, want);
  }

  /** bluetooth_on written now (under LOCK), its observers told on the next flush. */
  private static void writeSetting(Application app, boolean value) {
    Settings.Global.putInt(app.getContentResolver(), Settings.Global.BLUETOOTH_ON, value ? 1 : 0);
    pending.add(() -> app.getContentResolver().notifyChange(Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON), null));
  }

  /** Sends what the switches queued, oldest first, without LOCK. */
  private static void flush() {
    synchronized (SEND) {
      while (true) {
        Runnable r;
        synchronized (LOCK) {
          r = pending.poll();
        }
        if (r == null) return;
        r.run();
      }
    }
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

  /**
   * The app's own BluetoothAdapter.enable()/disable() (and Robolectric's setEnabled, which calls them) go through the
   * same switch, with its broadcasts and setting: Robolectric's shadow only set the state. Allowed whatever the app
   * targets (a device returns false to apps targeting 33+), as Robolectric allowed it. Robolectric's own resetter
   * (ShadowBluetoothAdapter.reset, which its generated Shadows.reset calls) still runs between tests.
   */
  @Implements(BluetoothAdapter.class)
  public static class ShadowSimBluetoothAdapter extends ShadowBluetoothAdapter {
    @Implementation
    @Override
    protected boolean enable() {
      set(RuntimeEnvironment.getApplication(), true);
      return true;
    }

    @Implementation
    @Override
    protected boolean disable() {
      set(RuntimeEnvironment.getApplication(), false);
      return true;
    }
  }
}
