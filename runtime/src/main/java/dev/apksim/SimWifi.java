package dev.apksim;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import org.robolectric.util.ReflectionHelpers;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowWifiManager;

/**
 * The connected Wi-Fi a device reports. HostNetworks shows the host's default interface as Wi-Fi, but WifiManager
 * still answered as no device does: getMaxSignalLevel() 0 (the stub IWifiManager's calculateSignalLevel), every
 * calculateSignalLevel 0 or a fixed fraction, and a blank WifiInfo. Code that buckets the level as
 * `calculateSignalLevel(rssi) / (maxSignalLevel / 4)` on API 31+ divided by zero (issue 96), and an
 * ArithmeticException in a coroutine may never be reported.
 * Here, as API 31+ devices: levels 0..4 from the platform's default thresholds (config_wifiRssiLevelThresholds,
 * RssiUtil), the static calculateSignalLevel(rssi, numLevels) by the classic formula (MIN_RSSI -100, MAX_RSSI -55),
 * and getConnectionInfo a connection at -50 dBm on 5 GHz while there is a network (`network offline`: disconnected,
 * rssi -127), its SSID, BSSID and network id redacted without ACCESS_FINE_LOCATION, its MAC always, as a device does.
 * HostNetworks gives the Wi-Fi network's capabilities the same (redacted) WifiInfo and signal strength.
 */
final class SimWifi {
  private SimWifi() {}

  static final int RSSI = -50;
  static final int FREQUENCY = 5180;
  static final int LINK_SPEED = 866;
  /** The emulator's network name. */
  static final String SSID = "AndroidWifi";
  private static final String BSSID = "02:15:b2:00:01:00";
  private static final String REDACTED_MAC = "02:00:00:00:00:00";
  /** RssiUtil's default thresholds (frameworks/opt/net/wifi, config_wifiRssiLevelThresholds): levels 0..4. */
  private static final int[] LEVEL_THRESHOLDS = {-88, -77, -66, -55};

  static int level(int rssi) {
    for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) if (rssi < LEVEL_THRESHOLDS[i]) return i;
    return LEVEL_THRESHOLDS.length;
  }

  /** The connection as an app is shown it now: connected while the active network is the Wi-Fi one. */
  static WifiInfo info(Context context, boolean located) {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    @SuppressWarnings("deprecation")
    NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
    @SuppressWarnings("deprecation")
    boolean connected = active != null && active.isConnected() && active.getType() == ConnectivityManager.TYPE_WIFI;
    if (!connected) {
      WifiInfo info = new WifiInfo();
      info.setMacAddress(REDACTED_MAC);
      info.setSupplicantState(SupplicantState.DISCONNECTED);
      info.setRssi(-127);  // WifiInfo.INVALID_RSSI
      info.setBSSID(REDACTED_MAC);
      info.setNetworkId(-1);
      return info;
    }
    Network n = cm.getActiveNetwork();
    LinkProperties link = n == null ? null : cm.getLinkProperties(n);
    InetAddress address = null;
    if (link != null) {
      for (LinkAddress a : link.getLinkAddresses()) {
        if (a.getAddress() instanceof Inet4Address) {
          address = a.getAddress();
          break;
        }
      }
    }
    return connected(located, address);
  }

  /** A connection at RSSI on 5 GHz, its SSID, BSSID and network id shown only when `located`. */
  static WifiInfo connected(boolean located, InetAddress address) {
    WifiInfo info = new WifiInfo();
    info.setMacAddress(REDACTED_MAC);
    info.setSupplicantState(SupplicantState.COMPLETED);
    info.setRssi(RSSI);
    info.setFrequency(FREQUENCY);
    info.setLinkSpeed(LINK_SPEED);
    info.setTxLinkSpeedMbps(LINK_SPEED);
    info.setRxLinkSpeedMbps(LINK_SPEED);
    info.setWifiStandard(5);  // ScanResult.WIFI_STANDARD_11AC
    if (located) {
      if (Api.LEVEL >= 33) info.setSSID(WifiSsid.fromBytes(SSID.getBytes(StandardCharsets.UTF_8)));
      else info.setSSID(ReflectionHelpers.callStaticMethod(WifiSsid.class, "createFromAsciiEncoded", ReflectionHelpers.ClassParameter.from(String.class, SSID)));  // SPIKE
      info.setBSSID(BSSID);
      info.setNetworkId(0);
    } else {
      info.setBSSID(REDACTED_MAC);  // as WifiInfo redacts it (a new WifiInfo has none); SSID stays <unknown ssid>
      info.setNetworkId(-1);
    }
    if (address != null) info.setInetAddress(address);
    return info;
  }

  @Implements(WifiManager.class)
  public static class ShadowSimWifiManager extends ShadowWifiManager {
    @Implementation
    protected int getMaxSignalLevel() {
      return LEVEL_THRESHOLDS.length;
    }

    @Implementation
    protected int calculateSignalLevel(int rssi) {
      return level(rssi);
    }

    @Implementation
    protected static int calculateSignalLevel(int rssi, int numLevels) {
      // WifiManager's static (deprecated) formula, which Robolectric replaced by a fixed fraction of numLevels.
      if (rssi <= -100) return 0;
      if (rssi >= -55) return numLevels - 1;
      return (int) ((float) (rssi + 100) * (numLevels - 1) / 45f);
    }

    @Implementation
    @Override
    protected WifiInfo getConnectionInfo() {
      WifiInfo set = super.getConnectionInfo();  // its ACCESS_WIFI_STATE check, as a device's
      Context app = org.robolectric.RuntimeEnvironment.getApplication();
      if (app == null) return set;
      boolean located = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
      return info(app, located);
    }
  }
}
