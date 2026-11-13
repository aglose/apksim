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
import java.net.InetAddress;
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
 * default route becomes Wi-Fi; tunnel interfaces with an IPv4 address (WireGuard-style VPNs) become VPNs.
 */
final class HostNetworks {
  private HostNetworks() {}

  static void install(Context context) throws Exception {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    var shadow = shadowOf(cm);
    shadow.clearAllNetworks();
    int netId = 100;
    NetworkInfo active = null;
    NetworkCapabilities activeCaps = null;
    LinkProperties activeLinks = null;
    for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!ni.isUp() || ni.isLoopback() || !hasIpv4(ni)) continue;
      boolean tunnel = ni.getName().startsWith("utun") || ni.isPointToPoint();
      // Bridges and virtual adapters are not the way out. Ethernet is en* on macOS and eth* on Linux (a container's
      // eth0: without it the app saw no network at all there).
      if (!tunnel && !ni.getName().startsWith("en") && !ni.getName().startsWith("eth")) continue;
      int type = tunnel ? ConnectivityManager.TYPE_VPN : ConnectivityManager.TYPE_WIFI;
      NetworkInfo info = ShadowNetworkInfo.newInstance(
          NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED);
      // Robolectric finds the active network by its type (getActiveNetwork looks netId TYPE_WIFI up), so the default
      // one gets that netId; otherwise the app was handed Robolectric's own stand-in, which has no capabilities but a
      // transport (no NET_CAPABILITY_INTERNET or VALIDATED, which apps check before going online).
      Network network = ShadowNetwork.newInstance(active == null && !tunnel ? type : netId++);
      shadow.addNetwork(network, info);

      NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
      var capsShadow = shadowOf(caps);
      capsShadow.addTransportType(tunnel ? NetworkCapabilities.TRANSPORT_VPN : NetworkCapabilities.TRANSPORT_WIFI);
      capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      if (!tunnel) capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
      // What a device's Wi-Fi network also has, and a NetworkRequest asks for by default (NOT_RESTRICTED, TRUSTED,
      // NOT_VPN, NOT_VCN_MANAGED): without them no request matched the network (ShadowSimConnectivityManager).
      for (int c : new int[] {NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_TRUSTED,
          NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
          NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED, NetworkCapabilities.NET_CAPABILITY_FOREGROUND,
          /* SPIKE: NOT_VCN_MANAGED, API 31 */ Api.LEVEL >= 31 ? 28 : NetworkCapabilities.NET_CAPABILITY_TRUSTED}) {
        capsShadow.addCapability(c);
      }
      if (!tunnel) {
        capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        // As ConnectivityService shows a Wi-Fi network to an app: its WifiInfo (location fields redacted) and signal.
        InetAddress v4 = null;
        for (InterfaceAddress a : ni.getInterfaceAddresses()) {
          if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLinkLocalAddress()) v4 = a.getAddress();
        }
        if (Api.LEVEL >= 31) caps.setTransportInfo(SimWifi.connected(false, v4));  // SPIKE: WifiInfo is a TransportInfo from 31
        caps.setSignalStrength(SimWifi.RSSI);
      }
      shadow.setNetworkCapabilities(network, caps);

      LinkProperties lp = new LinkProperties();
      lp.setInterfaceName(ni.getName());
      for (InterfaceAddress a : ni.getInterfaceAddresses()) {
        if (a.getAddress().isLinkLocalAddress()) continue;
        lp.addLinkAddress(new LinkAddress(a.getAddress(), a.getNetworkPrefixLength()));
      }
      shadow.setLinkProperties(network, lp);
      if (active == null && !tunnel) {
        active = info;
        activeCaps = caps;
        activeLinks = lp;
      }
      if (Boolean.getBoolean("apksim.verbose")) System.err.println("[apksim] network " + ni.getName() + " as " + info.getTypeName());
    }
    if (active != null) {
      shadow.setActiveNetworkInfo(active);
      // Robolectric's getActiveNetwork() answers with its own Network for the active type, not the one added above:
      // give that one the same capabilities (INTERNET, VALIDATED), as apps ask getNetworkCapabilities(activeNetwork).
      Network reported = cm.getActiveNetwork();
      if (reported != null) {
        shadow.setNetworkCapabilities(reported, activeCaps);
        shadow.setLinkProperties(reported, activeLinks);
      }
    }
    // Callbacks registered before (in Application.onCreate, when Host installs these after it) hear of them now, and
    // the sticky broadcasts say so. Not with --network-profile offline: NetConnectivity.networksReplaced, next, takes
    // these away again, then tells everyone there is no network (else they would hear connected, then not).
    if (!dev.apksim.net.Net.offline()) {
      ShadowSimConnectivityManager.rematch();
      SystemBroadcasts.network(context);
    }
  }

  private static boolean hasIpv4(NetworkInterface ni) {
    for (InterfaceAddress a : ni.getInterfaceAddresses()) {
      if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLinkLocalAddress()) return true;
    }
    return false;
  }
}
