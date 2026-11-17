package dev.apksim;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

/**
 * The loopback ports the owner of a beam agent opened to the app (`apksim beam config allow-loopback`, issue 428): the
 * launcher passes them in {@code APKSIM_BEAM_LOOPBACK} and puts a rule for each in the sandbox profile. A port the app
 * may reach must never be one of this instance's own listeners (its control and adb ports), so those are opened on a
 * free port that is not one of them.
 */
public final class LoopbackPorts {
  private LoopbackPorts() {}

  /** The ports opened to the app, none outside a beamed instance or when the owner opened none. */
  public static Set<Integer> allowed() {
    Set<Integer> out = new HashSet<>();
    String v = System.getenv("APKSIM_BEAM_LOOPBACK");
    if (v == null || v.isEmpty()) return out;
    for (String p : v.split(",")) {
      try {
        out.add(Integer.parseInt(p.trim()));
      } catch (NumberFormatException e) {
        // the launcher validated it; ignore anything else
      }
    }
    return out;
  }

  /** A listening socket on {@code port}, or, for port 0, on a free port that is none of the allowed ones. */
  public static ServerSocket listen(int port, InetAddress address) throws IOException {
    Set<Integer> allowed = port == 0 ? allowed() : Set.of();
    for (int attempt = 0; ; attempt++) {
      ServerSocket s = new ServerSocket(port, 8, address);
      if (allowed.isEmpty() || !allowed.contains(s.getLocalPort()) || attempt >= 100) return s;
      s.close();
    }
  }
}
