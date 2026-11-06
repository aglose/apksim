package dev.apksim;

import android.app.Application;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowConnectivityManager;

/**
 * `network offline` as ConnectivityManager shows it: the networks go away (no active network, callbacks get onLost,
 * the sticky CONNECTIVITY_ACTION broadcast says there is no connectivity), and come back with `network online`
 * (onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged), as on a device whose Wi-Fi is turned off and on.
 * Robolectric keeps that state in ShadowConnectivityManager's static fields, so it can be set before the Application
 * exists: with --network-profile offline, Application.onCreate already sees no network.
 */
final class NetConnectivity {
  private NetConnectivity() {}

  private record Saved(Network network, NetworkInfo info, NetworkCapabilities caps, LinkProperties link, boolean active) {}

  private static boolean listening;
  private static List<Saved> saved;

  /** Before the Application is created (each test's setup, Host's too): the launch profile, from the start. */
  static void beforeApplication() {
    if (!listening) {
      listening = true;
      dev.apksim.net.Net.onOfflineChange(offline -> {
        if (Looper.getMainLooper().isCurrentThread()) apply(offline);
        else new Handler(Looper.getMainLooper()).post(() -> apply(offline));
      });
    }
    saved = null;  // the state Robolectric reset to after the last test
    if (dev.apksim.net.Net.offline()) apply(true);
  }

  /** HostNetworks has put the host's networks in: offline, they are what comes back with `network online`. */
  static void networksReplaced() {
    if (!dev.apksim.net.Net.offline()) return;
    saved = null;
    apply(true);
  }

  static void apply(boolean offline) {
    ShadowConnectivityManager shadow = new ShadowConnectivityManager();  // its state is static: any instance has it
    if (offline) {
      if (saved != null) return;
      saved = new ArrayList<>();
      NetworkInfo activeInfo = staticField("activeNetworkInfo");
      Map<Integer, Network> networks = staticField("netIdToNetwork");
      Map<Integer, NetworkInfo> infos = staticField("netIdToNetworkInfo");
      Map<Network, NetworkCapabilities> caps = staticField("networkCapabilitiesMap");
      Map<Network, LinkProperties> links = staticField("linkPropertiesMap");
      // Robolectric finds the active network by its type (HostNetworks gives the default one that netId).
      Network active = activeInfo == null ? null : networks.get(activeInfo.getType());
      for (Map.Entry<Integer, Network> e : networks.entrySet()) {
        Network n = e.getValue();
        saved.add(new Saved(n, infos.get(e.getKey()), caps.get(n), links.get(n), n.equals(active)));
      }
      shadow.clearAllNetworks();
      shadow.setActiveNetworkInfo(null);  // first: with no active network Robolectric notifies no one below
      shadow.setDefaultNetworkActive(false);
    } else {
      if (saved == null) return;
      // Before there is an active network again: with one, Robolectric calls onAvailable itself, and the callbacks
      // would hear of it twice (ShadowSimConnectivityManager.rematch below tells them).
      shadow.setDefaultNetworkActive(true);
      for (Saved s : saved) {
        shadow.addNetwork(s.network, s.info);
        if (s.caps != null) shadow.setNetworkCapabilities(s.network, s.caps);
        if (s.link != null) shadow.setLinkProperties(s.network, s.link);
        if (s.active) shadow.setActiveNetworkInfo(s.info);
      }
      saved = null;
    }
    // Each callback hears of what it gains or loses (onLost; onAvailable, onCapabilitiesChanged, ...), as its request
    // says: a default callback of the default network, a listen of every network it matches.
    ShadowSimConnectivityManager.rematch();
    Application app = RuntimeEnvironment.application;
    if (app == null) return;  // before the Application: there is no one to tell yet
    SystemBroadcasts.network(app);  // CONNECTIVITY_ACTION and Wi-Fi's NETWORK_STATE_CHANGED, sticky as on a device
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
