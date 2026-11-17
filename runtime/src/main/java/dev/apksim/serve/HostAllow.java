package dev.apksim.serve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which addresses a session's `--network-host H=ADDR[:PORT]` may point a host at (issue 429): `serve
 * --network-host-allow ADDR[:PORT]` (repeatable) and `apksim beam config allow-host ADDR[:PORT]`. None unless the
 * operator or the Mac's owner lists them, so a page cannot use an instance as a way into the network it runs on.
 * An entry names an address (a host name, an IPv4 address or an IPv6 one in brackets) and, optionally, a port: without
 * one it covers every port, and a target that keeps the app's own port (no port, as `H=ADDR`) too. Names are compared as
 * written (lower case, no resolving), so an entry for a name allows that name only. Plain Java: checked by HostAllowCheck.
 */
public final class HostAllow {
  /** An address (lower case, IPv6 without brackets) and a port; 0: none, any. */
  public record Target(String addr, int port) {
    @Override
    public String toString() {
      String a = addr.indexOf(':') >= 0 ? "[" + addr + "]" : addr;
      return port == 0 ? a : a + ":" + port;
    }
  }

  private final List<Target> entries = new ArrayList<>();

  public HostAllow() {}

  public HostAllow(List<String> entries) {
    for (String e : entries) {
      Target t = parse(e);
      if (t != null && !this.entries.contains(t)) this.entries.add(t);
    }
  }

  /** Adds an entry; an IllegalArgumentException says what is wrong with it. */
  public void add(String entry) {
    Target t = parse(entry);
    if (t == null) throw new IllegalArgumentException("a host allow entry is ADDR[:PORT] (a name, an IPv4 address or [IPV6], and a port of 1 to 65535), not " + entry);
    if (!entries.contains(t)) entries.add(t);
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  public List<String> list() {
    return entries.stream().map(Target::toString).toList();
  }

  /** Whether a target (ADDR, ADDR:PORT, [IPV6] or [IPV6]:PORT) is covered. */
  public boolean allows(String target) {
    Target t = parse(target);
    if (t == null) return false;
    for (Target e : entries) {
      if (e.addr.equals(t.addr) && (e.port == 0 || e.port == t.port)) return true;
    }
    return false;
  }

  /** The target as a plain address and port, or null when it is not one this list can judge. */
  public static Target parse(String s) {
    if (s == null || s.isEmpty() || s.length() > 260 || !s.matches("[\\x21-\\x7e]+")) return null;
    String addr = s;
    int port = 0;
    if (s.startsWith("[")) {
      int close = s.indexOf(']');
      if (close < 0 || !(close == s.length() - 1 || s.charAt(close + 1) == ':')) return null;
      addr = s.substring(1, close);
      if (!addr.matches("[0-9a-fA-F:.]+") || addr.indexOf(':') < 0) return null;
      if (close < s.length() - 1) port = port(s.substring(close + 2));
    } else {
      int colon = s.indexOf(':');
      if (colon >= 0) {
        if (colon != s.lastIndexOf(':')) return null;  // a bare IPv6 address has no port: write it in brackets
        addr = s.substring(0, colon);
        port = port(s.substring(colon + 1));
      }
      if (!addr.matches("[A-Za-z0-9]([A-Za-z0-9.-]{0,250}[A-Za-z0-9])?")) return null;
    }
    if (port < 0) return null;
    return new Target(addr.toLowerCase(Locale.ROOT), port);
  }

  /** The port of 1 to 65535, else -1. */
  private static int port(String p) {
    if (!p.matches("[0-9]{1,5}")) return -1;
    int n = Integer.parseInt(p);
    return n >= 1 && n <= 65535 ? n : -1;
  }
}
