package dev.apksim.net;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A network condition: offline, extra round-trip time (with jitter), bandwidth each way, and loss (refused
 * connections and exchanges reset before their response). Immutable; Net swaps the current one.
 */
record Conditions(String name, boolean offline, int rttMs, int jitterMs, int downKbps, int upKbps,
    double refusePercent, double resetPercent) {

  static final Conditions ONLINE = new Conditions("online", false, 0, 0, 0, 0, 0, 0);
  static final Conditions OFFLINE = new Conditions("offline", true, 0, 0, 0, 0, 0, 0);
  static final Conditions THREE_G = new Conditions("3g", false, 300, 0, 750, 250, 0, 0);
  static final Conditions LOSSY = new Conditions("lossy", false, 150, 100, 1000, 1000, 2, 3);

  /** online|offline|3g|lossy|latency:MS|bandwidth:KBPS (a space works as well as the colon). */
  static Conditions parse(String spec) {
    String s = spec.trim().toLowerCase(Locale.ROOT).replace(' ', ':');
    return switch (s) {
      case "online", "none", "" -> ONLINE;
      case "offline", "airplane" -> OFFLINE;
      case "3g" -> THREE_G;
      case "lossy" -> LOSSY;
      default -> {
        int colon = s.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("unknown network profile " + spec);
        int n = Integer.parseInt(s.substring(colon + 1));
        yield switch (s.substring(0, colon)) {
          case "latency" -> new Conditions("latency " + n, false, n, 0, 0, 0, 0, 0);
          case "bandwidth" -> new Conditions("bandwidth " + n, false, 0, 0, n, n, 0, 0);
          default -> throw new IllegalArgumentException("unknown network profile " + spec);
        };
      }
    };
  }

  boolean shaped() {
    return rttMs > 0 || downKbps > 0 || upKbps > 0 || refusePercent > 0 || resetPercent > 0;
  }

  /** One round trip, with jitter. */
  int rtt() {
    if (rttMs <= 0) return 0;
    if (jitterMs <= 0) return rttMs;
    return Math.max(0, rttMs + ThreadLocalRandom.current().nextInt(-jitterMs, jitterMs + 1));
  }

  boolean refuse() {
    return refusePercent > 0 && ThreadLocalRandom.current().nextDouble(100) < refusePercent;
  }

  boolean reset() {
    return resetPercent > 0 && ThreadLocalRandom.current().nextDouble(100) < resetPercent;
  }

  /** Sleeps as long as n bytes take at kbps (0: unlimited). */
  static void pace(int n, int kbps) {
    if (kbps <= 0 || n <= 0) return;
    sleep(n * 8L / kbps);
  }

  static void sleep(long ms) {
    if (ms <= 0) return;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  String describe() {
    if (offline) return "offline";
    if (!shaped()) return "online";
    StringBuilder b = new StringBuilder(name).append(" (");
    if (rttMs > 0) b.append("rtt ").append(rttMs).append(jitterMs > 0 ? "±" + jitterMs : "").append(" ms, ");
    if (downKbps > 0) b.append(downKbps).append('/').append(upKbps).append(" kbps, ");
    if (refusePercent > 0) b.append(refusePercent).append("% refused, ");
    if (resetPercent > 0) b.append(resetPercent).append("% reset, ");
    b.setLength(b.length() - 2);
    return b.append(')').toString();
  }
}
