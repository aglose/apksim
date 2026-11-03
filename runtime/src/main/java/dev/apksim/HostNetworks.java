package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * Shows the app the host's real network interfaces as Android networks. Robolectric's stand-ins have no
 * interface names or addresses, and libwebrtc ignores every interface that ConnectivityManager does not
 * name, so without this a WebRTC call only ever offered loopback candidates. The interface with the
 * default route becomes Wi-Fi; tunnel interfaces with an IPv4 address (Tailscale's) become VPNs.
 */
final class HostNetworks {
  private HostNetworks() {}

  static void install(Context context) throws Exception {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    var shadow = shadowOf(cm);
    shadow.clearAllNetworks();
    int netId = 100;
    NetworkInfo active = null;
    for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!ni.isUp() || ni.isLoopback() || !hasIpv4(ni)) continue;
      boolean tunnel = ni.getName().startsWith("utun") || ni.isPointToPoint();
      if (!tunnel && !ni.getName().startsWith("en")) continue;  // Bridges and virtual adapters: not the way out.
      int type = tunnel ? ConnectivityManager.TYPE_VPN : ConnectivityManager.TYPE_WIFI;
      NetworkInfo info = ShadowNetworkInfo.newInstance(
          NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED);
      Network network = ShadowNetwork.newInstance(netId++);
      shadow.addNetwork(network, info);

      NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
      var capsShadow = shadowOf(caps);
      capsShadow.addTransportType(tunnel ? NetworkCapabilities.TRANSPORT_VPN : NetworkCapabilities.TRANSPORT_WIFI);
      capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      if (!tunnel) capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
      shadow.setNetworkCapabilities(network, caps);

      LinkProperties lp = new LinkProperties();
      lp.setInterfaceName(ni.getName());
      for (InterfaceAddress a : ni.getInterfaceAddresses()) {
        if (a.getAddress().isLinkLocalAddress()) continue;
        lp.addLinkAddress(new LinkAddress(a.getAddress(), a.getNetworkPrefixLength()));
      }
      shadow.setLinkProperties(network, lp);
      if (active == null && !tunnel) active = info;
      if (Boolean.getBoolean("apksim.verbose")) System.err.println("[apksim] network " + ni.getName() + " as " + info.getTypeName());
    }
    if (active != null) shadow.setActiveNetworkInfo(active);
  }

  private static boolean hasIpv4(NetworkInterface ni) {
    for (InterfaceAddress a : ni.getInterfaceAddresses()) {
      if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLinkLocalAddress()) return true;
    }
    return false;
  }
}
