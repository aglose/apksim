package dev.apksim;

import android.app.Application;
import android.net.ConnectivityManager;
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
      // Robolectric keeps no request with a callback, so each hears of the default network, as a default network
      // callback does (the common kind); one registered for a VPN alone is told of the default network too.
      for (ConnectivityManager.NetworkCallback cb : new ArrayList<>(shadow.getNetworkCallbacks())) {
        for (Saved s : saved) if (s.active) cb.onLost(s.network);
      }
    } else {
      if (saved == null) return;
      // Before there is an active network again: with one, Robolectric calls onAvailable itself, and the callbacks
      // below would hear of it twice (it also skips onCapabilitiesChanged and onLinkPropertiesChanged).
      shadow.setDefaultNetworkActive(true);
      for (Saved s : saved) {
        shadow.addNetwork(s.network, s.info);
        if (s.caps != null) shadow.setNetworkCapabilities(s.network, s.caps);
        if (s.link != null) shadow.setLinkProperties(s.network, s.link);
        if (s.active) shadow.setActiveNetworkInfo(s.info);
      }
      for (ConnectivityManager.NetworkCallback cb : new ArrayList<>(shadow.getNetworkCallbacks())) {
        for (Saved s : saved) {
          if (!s.active) continue;
          cb.onAvailable(s.network);
          if (s.caps != null) cb.onCapabilitiesChanged(s.network, s.caps);
          if (s.link != null) cb.onLinkPropertiesChanged(s.network, s.link);
        }
      }
      saved = null;
    }
    Application app = RuntimeEnvironment.application;
    if (app == null) return;  // before the Application: there is no one to tell yet
    SystemBroadcasts.network(app);  // CONNECTIVITY_ACTION and Wi-Fi's NETWORK_STATE_CHANGED, sticky as on a device
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
