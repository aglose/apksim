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
        return builtin.lookupByName(host, policy);
      }

      @Override
      public String lookupByAddress(byte[] addr) throws UnknownHostException {
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

  private static InetAddress placeholder(String host) {
    return addressByHost.computeIfAbsent(host, h -> {
      int n = (h.hashCode() & 0x1ffff) | 1;
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

  /** The host a placeholder address stands for, or null. */
  static String hostFor(String address) {
    return hostByAddress.get(address);
  }
}
