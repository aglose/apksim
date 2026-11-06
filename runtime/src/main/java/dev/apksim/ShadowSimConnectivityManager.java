package dev.apksim;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowConnectivityManager;

/**
 * A network callback hears of the networks it matches as it registers, as ConnectivityService tells it: onAvailable,
 * onCapabilitiesChanged, onLinkPropertiesChanged and onBlockedStatusChanged, on the callback's handler (the main
 * thread without one), once the call has returned. Robolectric only recorded the callback, so an app that awaits the
 * first callback (a callbackFlow and first(), as a Wi-Fi tile or an "am I online" check does) waited for ever.
 * A default network callback hears of the active network; registerNetworkCallback, requestNetwork and
 * registerBestMatchingNetworkCallback of each network whose capabilities satisfy the request (best matching: the
 * active one first). Offline (`network offline`) there is none, and `network online` tells them (NetConnectivity).
 */
@Implements(ConnectivityManager.class)
public class ShadowSimConnectivityManager extends ShadowConnectivityManager {
  @RealObject private ConnectivityManager cm;

  @Implementation
  @Override
  protected void registerNetworkCallback(NetworkRequest request, ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerNetworkCallback(request, cb, handler);
    announce(cb, handler, matching(request, false));
  }

  // requestNetwork goes through the two-argument registerNetworkCallback in Robolectric, which calls the one above.

  @Implementation
  @Override
  protected void registerDefaultNetworkCallback(ConnectivityManager.NetworkCallback cb) {
    registerDefaultNetworkCallback(cb, null);
  }

  @Implementation
  @Override
  protected void registerDefaultNetworkCallback(ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerDefaultNetworkCallback(cb, handler);
    Network active = cm.getActiveNetwork();
    announce(cb, handler, active == null || cm.getNetworkCapabilities(active) == null ? List.of() : List.of(active));
  }

  @Implementation
  @Override
  protected void registerBestMatchingNetworkCallback(NetworkRequest request, ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerBestMatchingNetworkCallback(request, cb, handler);
    List<Network> best = matching(request, true);
    announce(cb, handler, best.isEmpty() ? best : best.subList(0, 1));
  }

  /** The networks whose capabilities satisfy the request, the active one first when asked. */
  private List<Network> matching(NetworkRequest request, boolean activeFirst) {
    List<Network> out = new ArrayList<>();
    Network active = cm.getActiveNetwork();
    for (Network n : cm.getAllNetworks()) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(n);
      if (caps == null || request == null || !request.canBeSatisfiedBy(caps)) continue;
      if (activeFirst && n.equals(active)) out.add(0, n); else out.add(n);
    }
    return out;
  }

  private void announce(ConnectivityManager.NetworkCallback cb, Handler handler, List<Network> networks) {
    if (cb == null || networks.isEmpty()) return;
    Handler h = handler != null ? handler : new Handler(Looper.getMainLooper());
    h.post(() -> {
      for (Network n : networks) {
        // Unregistered meanwhile, or the network went away (offline): nothing to tell, as on a device.
        if (!getNetworkCallbacks().contains(cb)) return;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        if (caps == null) continue;
        LinkProperties link = cm.getLinkProperties(n);
        cb.onAvailable(n);
        cb.onCapabilitiesChanged(n, caps);
        if (link != null) cb.onLinkPropertiesChanged(n, link);
        cb.onBlockedStatusChanged(n, false);
      }
    });
  }
}
