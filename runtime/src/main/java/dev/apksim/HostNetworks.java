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
 * default route becomes Wi-Fi; tunnel interfaces with an IPv4 address (WireGuard-style VPNs) become VPNs that also carry the
 * underlying Wi-Fi's transport, WifiInfo and signal, as a device's VPN network does (issue 347); VPN-only without one.
 */
final class HostNetworks {
  private HostNetworks() {}

  static void install(Context context) throws Exception {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    var shadow = shadowOf(cm);
    shadow.clearAllNetworks();
    int netId = 100;
    // A device's VPN network carries the underlying network's transports too (WIFI|VPN), and an app classifying the
    // last callback's transports must see Wi-Fi there (issue 347). The underlying one is the first non-tunnel network.
    NetworkInterface underlying = null;
    var interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
    for (NetworkInterface ni : interfaces) {
      if (isDirect(ni)) { underlying = ni; break; }
    }
    NetworkInfo active = null;
    NetworkCapabilities activeCaps = null;
    LinkProperties activeLinks = null;
    for (NetworkInterface ni : interfaces) {
      if (!ni.isUp() || ni.isLoopback() || !hasIpv4(ni)) continue;
      boolean tunnel = isTunnel(ni);
      // Bridges and virtual adapters are not the way out.
      if (!tunnel && !isDirect(ni)) continue;
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
      if (tunnel) capsShadow.addTransportType(NetworkCapabilities.TRANSPORT_VPN);
      if (!tunnel || underlying != null) capsShadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
      capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      if (!tunnel) capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
      // What a device's Wi-Fi network also has, and a NetworkRequest asks for by default (NOT_RESTRICTED, TRUSTED,
      // NOT_VPN, NOT_VCN_MANAGED): without them no request matched the network (ShadowSimConnectivityManager).
      for (int c : new int[] {NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_TRUSTED,
          NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
          NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED, NetworkCapabilities.NET_CAPABILITY_FOREGROUND,
          NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED}) {
        capsShadow.addCapability(c);
      }
      if (!tunnel || underlying != null) {
        capsShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        // As ConnectivityService shows a Wi-Fi network to an app: its WifiInfo (location fields redacted) and signal;
        // a VPN network over it shows the underlying network's.
        InetAddress v4 = null;
        for (InterfaceAddress a : (tunnel ? underlying : ni).getInterfaceAddresses()) {
          if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLinkLocalAddress()) v4 = a.getAddress();
        }
        caps.setTransportInfo(SimWifi.connected(false, v4));
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

  private static boolean isTunnel(NetworkInterface ni) throws java.net.SocketException {
    return ni.getName().startsWith("utun") || ni.isPointToPoint();
  }

  /** Ethernet is en* on macOS and eth* on Linux (a container's eth0: without it the app saw no network at all there). */
  private static boolean isDirect(NetworkInterface ni) throws java.net.SocketException {
    return ni.isUp() && !ni.isLoopback() && hasIpv4(ni) && !isTunnel(ni)
        && (ni.getName().startsWith("en") || ni.getName().startsWith("eth"));
  }

  private static boolean hasIpv4(NetworkInterface ni) {
    for (InterfaceAddress a : ni.getInterfaceAddresses()) {
      if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLinkLocalAddress()) return true;
    }
    return false;
  }
}
