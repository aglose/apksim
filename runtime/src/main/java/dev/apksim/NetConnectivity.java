package dev.apksim;

import android.app.Application;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.telephony.TelephonyManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowConnectivityManager;

/**
 * `network offline` and the radios (Radios: `network wifi|cellular ...`) as ConnectivityManager shows them. Offline,
 * the networks go away (no active network, callbacks get onLost, the sticky CONNECTIVITY_ACTION broadcast says there is
 * no connectivity) and come back with `network online` (onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged),
 * as on a device whose Wi-Fi is turned off and on. With Wi-Fi off the Wi-Fi network (and a VPN over it) is gone, with
 * mobile data on a cellular network is there, the default once Wi-Fi is off; Wi-Fi's signal is in its capabilities.
 * Robolectric keeps that state in ShadowConnectivityManager's static fields, so it can be set before the Application
 * exists: with --network-profile offline or --wifi off, Application.onCreate already sees it.
 * The host's networks (HostNetworks) are kept as installed (`host`); {@link #publish} puts in what may show now. While
 * the device is online with its usual radios nothing is touched: Robolectric's state is HostNetworks' as installed.
 */
final class NetConnectivity {
  private NetConnectivity() {}

  private record Saved(Network network, NetworkInfo info, NetworkCapabilities caps, LinkProperties link, boolean active) {}

  private static boolean listening;
  /** The networks as installed (the host's, or Robolectric's before HostNetworks), once something hid some; else null. */
  private static List<Saved> host;

  /** Before the Application is created (each test's setup, Host's too): the launch profile and radios, from the start. */
  static void beforeApplication() {
    if (!listening) {
      listening = true;
      dev.apksim.net.Net.onOfflineChange(offline -> {
        if (Looper.getMainLooper().isCurrentThread()) publish();
        else new Handler(Looper.getMainLooper()).post(NetConnectivity::publish);
      });
    }
    host = null;  // the state Robolectric reset to after the last test
    Radios.reset();  // may go offline: that publishes (Net's listener)
    if (dev.apksim.net.Net.offline() || Radios.custom()) publish();
  }

  /** HostNetworks has put the host's networks in: they are what shows, less what is off. */
  static void networksReplaced() {
    if (!dev.apksim.net.Net.offline() && !Radios.custom()) {
      host = null;
      return;
    }
    host = null;
    publish();
  }

  /** Whether HostNetworks should announce what it installed itself (else {@link #networksReplaced} does, after hiding). */
  static boolean announcesInstall() {
    return !dev.apksim.net.Net.offline() && !Radios.custom();
  }

  /** ConnectivityManager's networks as the device now is: offline, which radios are on, Wi-Fi's signal. */
  @SuppressWarnings("deprecation")
  static void publish() {
    ShadowConnectivityManager shadow = new ShadowConnectivityManager();  // its state is static: any instance has it
    if (host == null) host = snapshot();
    shadow.clearAllNetworks();
    shadow.setActiveNetworkInfo(null);  // with no active network Robolectric notifies no one below
    List<Saved> show = new ArrayList<>();
    Saved active = null;
    if (!dev.apksim.net.Net.offline()) {
      Saved wifiActive = null;
      for (Saved s : host) {
        int type = s.info == null ? -1 : s.info.getType();
        if (type == ConnectivityManager.TYPE_MOBILE) continue;  // Robolectric's own; ours is made below
        if ((type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_VPN || wifiTransport(s.caps)) && !Radios.wifi) continue;
        Saved shown = new Saved(s.network, s.info, withSignal(s.caps), s.link, s.active);
        show.add(shown);
        if (s.active) wifiActive = shown;
      }
      Saved cell = Radios.cellular ? cellular(host) : null;
      if (cell != null) show.add(cell);
      active = wifiActive != null ? wifiActive : cell;
    }
    if (show.isEmpty()) {
      shadow.setDefaultNetworkActive(false);
    } else {
      // Before there is an active network again: with one, Robolectric calls onAvailable itself, and the callbacks
      // would hear of it twice (ShadowSimConnectivityManager.rematch below tells them).
      if (!Boolean.TRUE.equals(staticField("defaultNetworkActive"))) shadow.setDefaultNetworkActive(true);
      for (Saved s : show) {
        shadow.addNetwork(s.network, s.info);
        if (s.caps != null) shadow.setNetworkCapabilities(s.network, s.caps);
        if (s.link != null) shadow.setLinkProperties(s.network, s.link);
      }
      if (active != null) shadow.setActiveNetworkInfo(active.info);
    }
    // Each callback hears of what it gains or loses (onLost; onAvailable, onCapabilitiesChanged, ...), as its request
    // says: a default callback of the default network, a listen of every network it matches.
    ShadowSimConnectivityManager.rematch();
    Application app = RuntimeEnvironment.application;
    if (app == null) return;  // before the Application: there is no one to tell yet
    telephony(app, Radios.cellular && !dev.apksim.net.Net.offline());
    SystemBroadcasts.network(app);  // CONNECTIVITY_ACTION and Wi-Fi's NETWORK_STATE_CHANGED, sticky as on a device
  }

  /** Robolectric's networks as they are now: what publish() shows a subset of. */
  private static List<Saved> snapshot() {
    List<Saved> out = new ArrayList<>();
    NetworkInfo activeInfo = staticField("activeNetworkInfo");
    Map<Integer, Network> networks = staticField("netIdToNetwork");
    Map<Integer, NetworkInfo> infos = staticField("netIdToNetworkInfo");
    Map<Network, NetworkCapabilities> caps = staticField("networkCapabilitiesMap");
    Map<Network, LinkProperties> links = staticField("linkPropertiesMap");
    // Robolectric finds the active network by its type (HostNetworks gives the default one that netId).
    Network active = activeInfo == null ? null : networks.get(activeInfo.getType());
    for (Map.Entry<Integer, Network> e : networks.entrySet()) {
      Network n = e.getValue();
      out.add(new Saved(n, infos.get(e.getKey()), caps.get(n), links.get(n), n.equals(active)));
    }
    return out;
  }

  private static boolean wifiTransport(NetworkCapabilities caps) {
    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
  }

  /** A Wi-Fi network's capabilities with the signal Radios has now (its strength and WifiInfo's RSSI). */
  private static NetworkCapabilities withSignal(NetworkCapabilities caps) {
    if (!wifiTransport(caps) || !(caps.getTransportInfo() instanceof WifiInfo old)) return caps;
    if (caps.getSignalStrength() == Radios.rssi && old.getRssi() == Radios.rssi) return caps;
    NetworkCapabilities c = new NetworkCapabilities(caps);
    WifiInfo info = new WifiInfo(old);
    info.setRssi(Radios.rssi);
    c.setTransportInfo(info);
    c.setSignalStrength(Radios.rssi);
    return c;
  }

  /**
   * Mobile data's network: TYPE_MOBILE (Robolectric finds the active network by its type, so that is its netId),
   * LTE, TRANSPORT_CELLULAR with what a phone's default request asks for, and metered (no NOT_METERED). Its link is the
   * host's way out (the Wi-Fi network's interface): the bytes go there, and libwebrtc finds its candidates by name.
   */
  @SuppressWarnings("deprecation")
  private static Saved cellular(List<Saved> host) {
    Network n = org.robolectric.shadows.ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE);
    NetworkInfo info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
        ConnectivityManager.TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_LTE, true, NetworkInfo.State.CONNECTED);
    NetworkCapabilities caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
    var c = org.robolectric.Shadows.shadowOf(caps);
    c.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
    for (int cap : new int[] {NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        NetworkCapabilities.NET_CAPABILITY_NOT_VPN, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
        NetworkCapabilities.NET_CAPABILITY_TRUSTED, NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
        NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED, NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
        NetworkCapabilities.NET_CAPABILITY_FOREGROUND, NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED}) {
      c.addCapability(cap);
    }
    LinkProperties link = null;
    for (Saved s : host) {
      if (s.active && s.link != null) link = new LinkProperties(s.link);
    }
    return new Saved(n, info, caps, link, false);
  }

  /** TelephonyManager's data connection, as a phone's while mobile data is (not) up. */
  private static void telephony(Application app, boolean connected) {
    TelephonyManager tm = app.getSystemService(TelephonyManager.class);
    if (tm == null) return;
    var t = org.robolectric.Shadows.shadowOf(tm);
    t.setDataEnabled(Radios.cellular);
    t.setDataState(connected ? TelephonyManager.DATA_CONNECTED : TelephonyManager.DATA_DISCONNECTED);
    if (connected) t.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
  }

  /**
   * For SystemSourcesTest only (it calls this by reflection): the device moves its default network from Wi-Fi to
   * Ethernet, as when it is plugged in. Ethernet comes up with the same capabilities and becomes the default while
   * Wi-Fi stays up; then Wi-Fi disconnects. Callbacks hear each step (rematch).
   */
  static void switchDefaultToEthernetForTest() {
    ShadowConnectivityManager shadow = new ShadowConnectivityManager();
    NetworkInfo activeInfo = staticField("activeNetworkInfo");
    Map<Integer, Network> networks = staticField("netIdToNetwork");
    Map<Network, NetworkCapabilities> caps = staticField("networkCapabilitiesMap");
    Map<Network, LinkProperties> links = staticField("linkPropertiesMap");
    Network wifi = networks.get(activeInfo.getType());
    @SuppressWarnings("deprecation")
    int ethernet = android.net.ConnectivityManager.TYPE_ETHERNET;
    // Robolectric's active network is the one whose netId is the active NetworkInfo's type.
    Network eth = org.robolectric.shadows.ShadowNetwork.newInstance(ethernet);
    NetworkInfo info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, ethernet, 0, true, NetworkInfo.State.CONNECTED);
    NetworkCapabilities ethCaps = new NetworkCapabilities(caps.get(wifi));
    ethCaps.setTransportInfo(null);
    org.robolectric.Shadows.shadowOf(ethCaps).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI);
    org.robolectric.Shadows.shadowOf(ethCaps).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);
    shadow.addNetwork(eth, info);
    shadow.setNetworkCapabilities(eth, ethCaps);
    if (links.get(wifi) != null) shadow.setLinkProperties(eth, new LinkProperties(links.get(wifi)));
    shadow.setActiveNetworkInfo(info);
    ShadowSimConnectivityManager.rematch();
    shadow.removeNetwork(wifi);
    caps.remove(wifi);
    links.remove(wifi);
    ShadowSimConnectivityManager.rematch();
  }

  /**
   * For SystemSourcesTest only (by reflection): a disconnected network with capabilities, as Robolectric's own Wi-Fi
   * is before HostNetworks replaces it. Returns its netId.
   */
  @SuppressWarnings("deprecation")
  static int addDisconnectedNetworkForTest() {
    ShadowConnectivityManager shadow = new ShadowConnectivityManager();
    int netId = 4242;
    Network n = org.robolectric.shadows.ShadowNetwork.newInstance(netId);
    NetworkInfo info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.DISCONNECTED,
        android.net.ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.DISCONNECTED);
    shadow.addNetwork(n, info);
    NetworkCapabilities caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
    org.robolectric.Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
    shadow.setNetworkCapabilities(n, caps);
    return netId;
  }

  @SuppressWarnings("unchecked")
  private static <T> T staticField(String name) {
    try {
      Field f = ShadowConnectivityManager.class.getDeclaredField(name);
      f.setAccessible(true);
      return (T) f.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Robolectric's ShadowConnectivityManager has no " + name, e);
    }
  }
}
