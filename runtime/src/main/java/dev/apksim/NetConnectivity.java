package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.RuntimeEnvironment;

/**
 * `network offline` as ConnectivityManager shows it: the networks go away (no active network, callbacks get onLost,
 * the CONNECTIVITY_ACTION broadcast says there is no connectivity), and come back with `network online`
 * (onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged), as on a device whose Wi-Fi is turned off and on.
 */
final class NetConnectivity {
  private NetConnectivity() {}

  private record Saved(Network network, NetworkInfo info, NetworkCapabilities caps, LinkProperties link, boolean active) {}

  private static boolean listening;
  private static List<Saved> saved;

  /** After the app's ConnectivityManager has its networks (Host, and each test's setup). */
  static void install() {
    if (!listening) {
      listening = true;
      dev.apksim.net.Net.onOfflineChange(offline -> {
        if (Looper.getMainLooper().isCurrentThread()) apply(offline);
        else new Handler(Looper.getMainLooper()).post(() -> apply(offline));
      });
    }
    saved = null;  // a new Application (each test) has its own networks
    if (dev.apksim.net.Net.offline()) apply(true);
  }

  static void apply(boolean offline) {
    Application app = RuntimeEnvironment.getApplication();
    if (app == null) return;
    ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
    var shadow = shadowOf(cm);
    if (offline) {
      if (saved != null) return;
      saved = new ArrayList<>();
      Network active = cm.getActiveNetwork();
      for (Network n : cm.getAllNetworks()) {
        saved.add(new Saved(n, cm.getNetworkInfo(n), cm.getNetworkCapabilities(n), cm.getLinkProperties(n), n.equals(active)));
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
    @SuppressWarnings("deprecation")
    Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION).putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, offline);
    app.sendBroadcast(intent);
  }
}
