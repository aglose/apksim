package dev.apksim.serve;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which targets a tab's forward rule may name (`serve --inspect-forward-allow PREFIX`, repeatable; issue 363): none unless
 * the operator lists them, so a tab cannot use the server's instances as an open proxy into its network. A prefix is an
 * http(s) URL: its scheme, host and port must be the target's exactly, and its path the target's or a folder above it
 * (`https://mocks.internal/api` allows `/api` and `/api/x`, not `/apix` and not `mocks.internal.example.com`). A target
 * with user info, a fragment, dot segments, an empty segment, a `;` (a path parameter: some servers read `..;` as `..`), a backslash or an encoded `.`, `/` or `\` in its path is
 * refused, as a server may read those as another path. Plain Java: checked by InspectorRuleCheck.
 */
public final class ForwardAllow {
  private record Prefix(String scheme, String host, int port, String path) {}

  private final List<Prefix> prefixes = new ArrayList<>();

  /** Adds a prefix; an IllegalArgumentException says what is wrong with it. */
  public void add(String prefix) {
    URI u = parse(prefix);
    if (u == null || u.getRawQuery() != null) {
      throw new IllegalArgumentException("--inspect-forward-allow takes an http(s) URL prefix without user info, query or fragment, such as https://mocks.internal/api: " + prefix);
    }
    prefixes.add(new Prefix(u.getScheme().toLowerCase(Locale.ROOT), u.getHost().toLowerCase(Locale.ROOT), port(u), u.getRawPath() == null ? "" : u.getRawPath()));
  }

  public boolean isEmpty() {
    return prefixes.isEmpty();
  }

  /** Whether a forward rule may send requests to this URL. */
  public boolean allows(String url) {
    URI u = parse(url);
    if (u == null) return false;
    String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
    for (Prefix p : prefixes) {
      if (!p.scheme.equals(u.getScheme().toLowerCase(Locale.ROOT)) || !p.host.equals(u.getHost().toLowerCase(Locale.ROOT)) || p.port != port(u)) continue;
      if (p.path.isEmpty() || p.path.equals("/") || path.equals(p.path) || path.startsWith(p.path.endsWith("/") ? p.path : p.path + "/")) return true;
    }
    return false;
  }

  private static int port(URI u) {
    return u.getPort() >= 0 ? u.getPort() : u.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  /** The URL when it is one this list can judge, else null. */
  private static URI parse(String url) {
    if (url == null || url.isEmpty() || url.length() > 2000 || !url.matches("[\\x21-\\x7e]+")) return null;
    URI u;
    try {
      u = new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getHost().isEmpty()) return null;
    if (u.getRawUserInfo() != null || u.getRawFragment() != null) return null;
    String path = u.getRawPath() == null ? "" : u.getRawPath();
    String lower = path.toLowerCase(Locale.ROOT);
    if (path.contains("\\") || path.contains(";") || path.contains("//") || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) return null;
    for (String seg : path.split("/", -1)) if (seg.equals(".") || seg.equals("..")) return null;
    return u;
  }
}
