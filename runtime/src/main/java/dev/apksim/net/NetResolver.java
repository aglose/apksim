package dev.apksim.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Name lookups for the whole JVM (META-INF/services), so the JDK's own HttpURLConnection and every library see the
 * network as Net has it: offline, a lookup fails as on a device in airplane mode; replaying, a recorded host needs no
 * DNS (it gets a placeholder address in 198.18.0.0/15, the benchmarking range, which Net.connect maps back); a host
 * --network-host sends elsewhere resolves the same way. Inactive, it is the JDK's resolver.
 */
public final class NetResolver extends InetAddressResolverProvider {
  private static final Map<String, String> hostByAddress = new ConcurrentHashMap<>();
  private static final Map<String, InetAddress> addressByHost = new ConcurrentHashMap<>();

  @Override
  public InetAddressResolver get(Configuration configuration) {
    InetAddressResolver builtin = configuration.builtinResolver();
    return new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        // The beam tab relay's host (TabRelay): a placeholder, which Net.connect sends to the relay, whatever the layer does.
        if (TabRelay.isHost(host)) return Stream.of(placeholder(host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "")));
        if (!Net.active || local(host)) return builtin.lookupByName(host, policy);
        if (Net.conditions.offline()) {
          throw new UnknownHostException("Unable to resolve host \"" + host + "\": No address associated with hostname");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (Net.mode == Net.Mode.REPLAY || Net.hostMap.containsKey(h)) {
          // IPv4 placeholders only: a caller asking for IPv6 alone gets none, which no app here does.
          if ((policy.characteristics() & LookupPolicy.IPV4) == 0 && (policy.characteristics() & LookupPolicy.IPV6) != 0) {
            throw new UnknownHostException(host);
          }
          return Stream.of(placeholder(h));
        }
        try {
          Stream<InetAddress> found = builtin.lookupByName(host, policy);
          unresolved.remove(h);
          return found;
        } catch (UnknownHostException e) {
          if (!Inspector.on) throw e;
          unresolved.add(h);
          // The network inspector (docs/pr-preview.md): the failed lookup is a row, and while a rule answers requests the host
          // gets a placeholder like a replayed one, so a rule can stand in for a server that is not there (yet).
          Inspector.failure(host, 0, "DNS lookup failed: " + e.getMessage());
          if (!Inspector.hasAnswerRules()) throw e;
          return Stream.of(placeholder(h));
        }
      }

      @Override
      public String lookupByAddress(byte[] addr) throws UnknownHostException {
        // Offline or replaying, a reverse lookup (getCanonicalHostName) of anything but loopback would ask a DNS
        // server: a placeholder answers locally, anything else fails.
        if (Net.active && (Net.conditions.offline() || Net.mode == Net.Mode.REPLAY)) {
          InetAddress a = InetAddress.getByAddress(addr);
          if (!a.isLoopbackAddress()) {
            String host = hostByAddress.get(a.getHostAddress());
            if (host != null) return host;
            throw new UnknownHostException(a.getHostAddress());
          }
        }
        return builtin.lookupByAddress(addr);
      }
    };
  }

  @Override
  public String name() {
    return "apksim";
  }

  private static boolean local(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost") || h.endsWith(".localhost") || h.equals(InetAddress.getLoopbackAddress().getHostName());
  }

  /** The next placeholder: 198.18.0.1 on, one per host, never two hosts on one address. */
  private static final java.util.concurrent.atomic.AtomicInteger nextPlaceholder = new java.util.concurrent.atomic.AtomicInteger(1);

  private static InetAddress placeholder(String host) {
    return addressByHost.computeIfAbsent(host, h -> {
      int n = nextPlaceholder.getAndIncrement() & 0x1ffff;  // 198.18.0.0/15: 131072 addresses
      byte[] ip = {(byte) 198, (byte) (18 + (n >> 16)), (byte) (n >> 8), (byte) n};
      try {
        InetAddress a = InetAddress.getByAddress(h, ip);
        hostByAddress.put(a.getHostAddress(), h);
        return a;
      } catch (UnknownHostException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /** Hosts whose last lookup failed and got a placeholder (the inspector), not a replay's or a mapped host's. */
  private static final java.util.Set<String> unresolved = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Whether the destination is a placeholder given to a host whose lookup failed: there is no server to connect to. */
  static boolean lookupFailed(String host, String address) {
    if (unresolved.isEmpty() || host == null) return false;
    String h = host.toLowerCase(Locale.ROOT);
    if (Net.hostMap.containsKey(h)) return false;
    if (address == null) return unresolved.contains(h);
    String stands = hostByAddress.get(address);
    return stands != null && unresolved.contains(stands);
  }

  /** The placeholder address for a host (made now if it has none). */
  static InetAddress placeholderFor(String host) {
    return placeholder(host.toLowerCase(Locale.ROOT));
  }

  /** The host a placeholder address stands for, or null. */
  static String hostFor(String address) {
    return hostByAddress.get(address);
  }
}
