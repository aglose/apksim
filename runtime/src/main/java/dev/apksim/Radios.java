package dev.apksim;

import android.app.Application;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import java.util.Locale;
import org.robolectric.RuntimeEnvironment;

/**
 * Wi-Fi and mobile data as a device's two switches, and Wi-Fi's signal strength: `network wifi on|off|toggle`,
 * `network wifi signal DBM|level N`, `network cellular on|off|toggle` (`mobile` and `data` are the same), and
 * `--wifi on|off`, `--cellular on|off`, `--wifi-signal DBM|level:N` at launch. `network offline` stays the whole
 * network (airplane mode); these are what an app on a phone sees when one radio goes:
 * - Wi-Fi off: WifiManager disabled (WIFI_STATE_CHANGED DISABLING, then DISABLED), the Wi-Fi network (and a VPN over
 *   it) lost; with mobile data on, that becomes the default network, metered (no NOT_METERED, TRANSPORT_CELLULAR,
 *   ConnectivityManager.TYPE_MOBILE, LTE), else the app is offline as with `network offline`.
 * - Mobile data on: a cellular network alongside Wi-Fi, as a phone keeps both (the default stays Wi-Fi; a listen for
 *   cellular hears it). Off by default: an emulator has one, but apksim never showed one and a suite counting
 *   networks would see a change. TelephonyManager says data is connected (LTE) while it is up.
 * - The signal: WifiInfo's RSSI, the Wi-Fi network's signal strength (onCapabilitiesChanged), and RSSI_CHANGED_ACTION.
 *   -50 dBm (level 4 of 4) by default; `level N` picks a value inside level N of RssiUtil's thresholds.
 * The app's bytes still go out the Mac's own network whichever radio it is told it has: degrade them with `network 3g`,
 * `lossy`, `latency MS` or `bandwidth KBPS`. Switching the default network (Wi-Fi to mobile data and back) closes the
 * connections the network layer sees, as a device's change of network breaks them (Net.networkChanged).
 * Running instances and test runs alike: each test starts with the launch's values (`NetConnectivity.beforeApplication`).
 */
final class Radios {
  private Radios() {}

  static final int DEFAULT_RSSI = -50;
  /** A value inside each level 0..4 of SimWifi's thresholds (-88, -77, -66, -55). */
  private static final int[] LEVEL_DBM = {-95, -82, -71, -60, -50};

  static volatile boolean wifi = true;
  static volatile boolean cellular;
  static volatile int rssi = DEFAULT_RSSI;

  /** The launch's values, before each Application (a test's too). The launcher refuses a bad value (exit 2). */
  static void reset() {
    wifi = onOff("apksim.wifi", true);
    cellular = onOff("apksim.cellular", false);
    String s = System.getProperty("apksim.wifiSignal");
    rssi = s == null ? DEFAULT_RSSI : parseSignal(s.replace(':', ' '));
    dev.apksim.net.Net.setRadiosDown(!wifi && !cellular);
  }

  /** Anything other than a device's usual state (Wi-Fi on at full signal, no mobile data): ConnectivityManager is ours to set. */
  static boolean custom() {
    return !wifi || cellular || rssi != DEFAULT_RSSI;
  }

  private static boolean onOff(String property, boolean dflt) {
    String v = System.getProperty(property);
    if (v == null) return dflt;
    return switch (v.trim().toLowerCase(Locale.ROOT)) {
      case "on", "true", "1", "enabled" -> true;
      case "off", "false", "0", "disabled" -> false;
      default -> throw new IllegalArgumentException("--" + (property.equals("apksim.wifi") ? "wifi" : "cellular") + " takes on or off, not " + v);
    };
  }

  /** DBM (-126..-1) or `level N` (0..4). */
  static int parseSignal(String spec) {
    String[] p = spec.trim().split("\\s+");
    try {
      if (p.length == 2 && p[0].equals("level")) {
        int n = Integer.parseInt(p[1]);
        if (n >= 0 && n < LEVEL_DBM.length) return LEVEL_DBM[n];
      } else if (p.length == 1) {
        int dbm = Integer.parseInt(p[0]);
        if (dbm < 0 && dbm > -127) return dbm;
      }
    } catch (NumberFormatException e) {
      // the message below
    }
    throw new IllegalArgumentException("Wi-Fi signal takes dBm (-126..-1, e.g. -70) or level N (0-4), not " + spec);
  }

  /** `network ...`: the radios' subcommands here, the rest Net's; the status line says both. */
  static String networkCommand(String[] a) {
    if (a.length >= 2) {
      switch (a[1]) {
        case "wifi" -> {
          return wifiCommand(a);
        }
        case "cellular", "mobile", "data" -> {
          return cellularCommand(a);
        }
        default -> { }
      }
    }
    String r = dev.apksim.net.Net.command(a);
    if (a.length == 1 || a[1].equals("status")) r += "; " + describe();
    return r;
  }

  private static String wifiCommand(String[] a) {
    if (a.length == 2) return describe();
    switch (a[2]) {
      case "on", "off", "toggle" -> {
        boolean on = a[2].equals("toggle") ? !wifi : a[2].equals("on");
        setWifi(on);
        return "ok: " + describe();
      }
      case "signal", "rssi" -> {
        if (a.length < 4) return "wifi signal " + rssi + " dBm (level " + SimWifi.level(rssi) + " of 4)";
        int dbm;
        try {
          dbm = parseSignal(String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)));
        } catch (IllegalArgumentException e) {
          return "error: " + e.getMessage();
        }
        setSignal(dbm);
        return "ok: " + describe();
      }
      default -> {
        return "usage: network wifi [on|off|toggle|signal DBM|signal level 0-4]";
      }
    }
  }

  private static String cellularCommand(String[] a) {
    if (a.length == 2) return describe();
    if (!a[2].matches("on|off|toggle")) return "usage: network cellular [on|off|toggle]";
    setCellular(a[2].equals("toggle") ? !cellular : a[2].equals("on"));
    return "ok: " + describe();
  }

  /** "wifi on, connected, -50 dBm (level 4 of 4); mobile data off". */
  static String describe() {
    boolean offline = dev.apksim.net.Net.offline();
    String defaultType = activeType();
    StringBuilder b = new StringBuilder("wifi ");
    if (!wifi) b.append("off");
    else b.append("on, ").append(offline ? "not connected (offline)" : "connected").append(", ").append(rssi)
        .append(" dBm (level ").append(SimWifi.level(rssi)).append(" of 4)");
    b.append("; mobile data ");
    if (!cellular) b.append("off");
    else b.append("on, ").append(offline ? "not connected (offline)" : "connected (LTE)")
        .append("mobile".equals(defaultType) ? ", the default network" : "");
    return b.toString();
  }

  @SuppressWarnings("deprecation")
  private static String activeType() {
    Application app = RuntimeEnvironment.getApplication();
    ConnectivityManager cm = app == null ? null : app.getSystemService(ConnectivityManager.class);
    NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
    if (info == null || !info.isConnected()) return null;
    return info.getType() == ConnectivityManager.TYPE_MOBILE ? "mobile" : info.getType() == ConnectivityManager.TYPE_WIFI ? "wifi" : "other";
  }

  static int wifiState() {
    return wifi ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED;
  }

  /** Wi-Fi switched (the command, `svc wifi`, the app's own WifiManager.setWifiEnabled). */
  static void setWifi(boolean on) {
    if (wifi == on) return;
    Application app = RuntimeEnvironment.getApplication();
    if (app != null) SystemBroadcasts.wifiState(app, on ? WifiManager.WIFI_STATE_ENABLING : WifiManager.WIFI_STATE_DISABLING);
    wifi = on;
    changed();
  }

  static void setCellular(boolean on) {
    if (cellular == on) return;
    cellular = on;
    changed();
  }

  static void setSignal(int dbm) {
    if (rssi == dbm) return;
    rssi = dbm;
    NetConnectivity.publish(false);  // the Wi-Fi network's capabilities (its signal strength and WifiInfo), no broadcasts
    Application app = RuntimeEnvironment.getApplication();
    if (app != null && wifi && !dev.apksim.net.Net.offline()) {
      // As the Wi-Fi stack sends it while connected: registered receivers only, not sticky.
      app.sendBroadcast(new Intent(WifiManager.RSSI_CHANGED_ACTION)
          .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
          .putExtra(WifiManager.EXTRA_NEW_RSSI, dbm));
    }
  }

  /** A radio switched: offline when neither is up, ConnectivityManager's networks as the radios now are. */
  private static void changed() {
    String before = activeType();
    // Being offline changing tells NetConnectivity itself (Net's offline listener), which publishes.
    if (!dev.apksim.net.Net.setRadiosDown(!wifi && !cellular)) NetConnectivity.publish();
    String after = activeType();
    if (before != null && after != null && !before.equals(after)) dev.apksim.net.Net.networkChanged();
    if (System.getProperty("apksim.instrument") == null) DeviceControls.showRadios();  // the app's own switch too
  }
}
