package dev.apksim.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The app's network, made controllable (docs/network.md): record, replay and conditions. The translator points app
 * calls to Socket.connect, SocketFactory/SSLSocketFactory.createSocket and URL.openConnection here
 * (MethodTranslator.call); with no --network option all of them do exactly what they did, after one field read.
 * Only JDK types: this runs for every app, and an APK without OkHttp must not meet a reference to it.
 */
public final class Net {
  private Net() {}

  enum Mode { SHAPE, RECORD, REPLAY }

  /** Any --network option was given: the hooks below act. */
  static volatile boolean active;
  static Mode mode = Mode.SHAPE;
  static volatile Conditions conditions = Conditions.ONLINE;
  static Conditions launchConditions = Conditions.ONLINE;
  static Recording recording;
  static NetEngine engine;
  static Map<String, InetSocketAddress> hostMap = new LinkedHashMap<>();
  static Set<String> redact = new HashSet<>(Set.of("authorization", "proxy-authorization", "cookie", "set-cookie"));
  static boolean failUnmatched = true;
  static boolean recordedTiming;
  static Path trust;
  /** Sockets this redirected to the engine, which FakeTlsSocket may wrap. */
  private static final Set<Socket> redirected = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  /**
   * Remote connections the hooks made directly (before the layer was on, or TLS-first sockets), which going offline
   * must drop as well: the engine never saw them.
   */
  private static final Set<Socket> direct = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  /** Unmatched replay requests since launch or the current test started (docs/network.md "Matching"). */
  static final List<String> unmatched = new CopyOnWriteArrayList<>();
  private static final List<Consumer<Boolean>> offlineListeners = new CopyOnWriteArrayList<>();

  // ------------------------------------------------------------------ setup

  /** Reads the launcher's -Dapksim.network* properties. Called once, before any app code runs. */
  public static synchronized void configure() {
    String spec = System.getProperty("apksim.network");
    String profile = System.getProperty("apksim.networkProfile");
    String hosts = System.getProperty("apksim.networkHost");
    if (spec == null && profile == null && hosts == null) return;
    try {
      for (String r : list(System.getProperty("apksim.networkRedact"))) {
        if (r.startsWith("-")) redact.remove(r.substring(1));
        else redact.add(r);
      }
      for (String h : list(hosts)) {
        int eq = h.indexOf('=');
        if (eq < 0) throw new IllegalArgumentException("--network-host takes HOST=ADDR[:PORT], not " + h);
        String to = h.substring(eq + 1);
        int colon = to.lastIndexOf(':');
        boolean hasPort = colon > 0 && !to.endsWith("]") && to.indexOf(':') == colon;
        // Port 0: keep the port the app connects to (NetEngine.target).
        hostMap.put(h.substring(0, eq), InetSocketAddress.createUnresolved(hasPort ? to.substring(0, colon) : to,
            hasPort ? Integer.parseInt(to.substring(colon + 1)) : 0));
      }
      String t = System.getProperty("apksim.networkTrust");
      if (t != null) trust = Path.of(t);
      failUnmatched = !"respond".equals(System.getProperty("apksim.networkUnmatched", "fail"));
      recordedTiming = "recorded".equals(System.getProperty("apksim.networkReplayTiming", "none"));
      if (profile != null) launchConditions = Conditions.parse(profile);
      conditions = launchConditions;
      if (spec != null) {
        int colon = spec.indexOf(':');
        String m = colon < 0 ? spec : spec.substring(0, colon);
        if (colon < 0) throw new IllegalArgumentException("--network takes record FILE or replay FILE");
        Path file = Path.of(spec.substring(colon + 1));
        Set<String> fields = list(System.getProperty("apksim.networkMatch", "method,url,body"));
        Recording.Match match = new Recording.Match(fields, names(System.getProperty("apksim.networkIgnoreQuery")),
            list(System.getProperty("apksim.networkIgnoreHeader")), redact);
        switch (m) {
          case "record" -> {
            mode = Mode.RECORD;
            recording = new Recording(file, match);
          }
          case "replay" -> {
            mode = Mode.REPLAY;
            recording = Recording.load(file, match);
          }
          default -> throw new IllegalArgumentException("--network takes record FILE or replay FILE, not " + m);
        }
      }
      engine = new NetEngine();
      engine.start();
      active = true;
      // Saved on the way out by Standby.halt (Runtime.halt runs no hooks), and here on an ordinary exit too:
      // Ctrl-C, or the app calling System.exit.
      if (mode == Mode.RECORD) Runtime.getRuntime().addShutdownHook(new Thread(Net::flush, "apksim-net-save"));
      log("network " + describe());
    } catch (Exception e) {
      throw new IllegalStateException("[apksim] network setup: " + e.getMessage(), e);
    }
  }

  /** A comma-separated list, lowercased: header names, hosts, match fields. */
  private static Set<String> list(String s) {
    Set<String> out = new java.util.LinkedHashSet<>();
    for (String p : names(s)) out.add(p.toLowerCase(Locale.ROOT));
    return out;
  }

  /** A comma-separated list as given: query parameter names, which are case-sensitive. */
  private static Set<String> names(String s) {
    Set<String> out = new java.util.LinkedHashSet<>();
    if (s == null) return out;
    for (String p : s.split(",")) {
      if (!p.isBlank()) out.add(p.trim());
    }
    return out;
  }

  static String describe() {
    StringBuilder b = new StringBuilder();
    b.append(switch (mode) {
      case RECORD -> "recording to " + recording.path;
      case REPLAY -> "replaying " + recording.path + " (" + recording.entries.size() + " entries)";
      case SHAPE -> "live";
    });
    b.append(", ").append(conditions.describe());
    if (!hostMap.isEmpty()) b.append(", hosts ").append(hostMap);
    return b.toString();
  }

  static void log(String s) {
    System.err.println("[apksim] " + s);
  }

  /** Called with true when the network goes offline and false when it comes back (ConnectivityManager, natives). */
  public static void onOfflineChange(Consumer<Boolean> l) {
    offlineListeners.add(l);
  }

  public static boolean offline() {
    return active && conditions.offline();
  }

  static void setConditions(Conditions c) {
    boolean was = conditions.offline();
    conditions = c;
    if (c.offline()) {
      if (engine != null) engine.closeAll();
      List<Socket> drop;
      synchronized (direct) {
        drop = new ArrayList<>(direct);
        direct.clear();
      }
      for (Socket s : drop) {
        try {
          s.close();
        } catch (IOException ignored) {
          // closing anyway
        }
      }
    }
    if (was != c.offline()) {
      flushDnsCache();
      for (Consumer<Boolean> l : offlineListeners) l.accept(c.offline());
    }
  }

  /**
   * Forgets the JDK's cached lookups, as Android flushes its resolver cache when the network changes: otherwise
   * failures looked up while offline stay failures for 10 s after coming back (networkaddress.cache.negative.ttl), and
   * names looked up before going offline go on resolving.
   */
  private static void flushDnsCache() {
    for (String name : new String[] {"cache", "expirySet"}) {
      try {
        java.lang.reflect.Field f = InetAddress.class.getDeclaredField(name);
        f.setAccessible(true);
        Object o = f.get(null);
        if (o instanceof Map<?, ?> m) m.clear();
        else if (o instanceof java.util.Collection<?> col) col.clear();
      } catch (ReflectiveOperationException | RuntimeException e) {
        // A JDK without these fields: lookups age out on their own.
      }
    }
  }

  /** A test starts: conditions back to the launch profile, no unmatched requests yet. */
  public static void testStarted() {
    if (!active) return;
    unmatched.clear();
    if (conditions != launchConditions) setConditions(launchConditions);
  }

  /** Unmatched replay requests the current test made, to fail it with (Instrument); empty unless policy is fail. */
  public static List<String> takeUnmatchedFailures() {
    if (!active || !failUnmatched || unmatched.isEmpty()) return List.of();
    List<String> out = new ArrayList<>(unmatched);
    unmatched.clear();
    return out;
  }

  /** Writes the recording now; on the way out (Standby.halt). */
  public static void flush() {
    if (engine != null) engine.flushNow();
  }

  // ------------------------------------------------------------------ control command

  /** network [online|offline|3g|lossy|latency MS|bandwidth KBPS|unmatched [clear]|save]. */
  public static String command(String[] a) {
    if (!active) {
      if (a.length > 1 && !a[1].equals("status")) {
        // Conditions can start at any time; recording and replaying need their launch options.
        System.setProperty("apksim.networkProfile", "online");
        configure();
      } else {
        return "network: off (no --network option); network offline|3g|lossy|latency MS|bandwidth KBPS turns it on";
      }
    }
    if (a.length == 1 || a[1].equals("status")) {
      String counts = engine.counts();
      return "network: " + describe() + (counts.isEmpty() ? "" : "; " + counts);
    }
    switch (a[1]) {
      case "unmatched" -> {
        String r = unmatched.isEmpty() ? "no unmatched requests" : String.join("\n", unmatched);
        if (a.length > 2 && a[2].equals("clear")) unmatched.clear();
        return r;
      }
      case "save" -> {
        if (mode != Mode.RECORD) return "error: not recording";
        engine.flushNow();
        return "saved " + recording.entries.size() + " entries to " + recording.path;
      }
      default -> {
        setConditions(Conditions.parse(String.join(" ", Arrays.copyOfRange(a, 1, a.length))));
        return "network: " + conditions.describe();
      }
    }
  }

  // ------------------------------------------------------------------ hooks (translated app code calls these)

  static boolean bypass(InetSocketAddress a) {
    if (a.isUnresolved()) {
      String h = a.getHostString().toLowerCase(Locale.ROOT);
      return h.equals("localhost") || h.endsWith(".localhost");
    }
    InetAddress ip = a.getAddress();
    return ip.isLoopbackAddress() || ip.isAnyLocalAddress();
  }

  public static void connect(Socket s, SocketAddress a) throws IOException {
    connect(s, a, 0);
  }

  public static void connect(Socket s, SocketAddress a, int timeout) throws IOException {
    if (!(a instanceof InetSocketAddress isa) || bypass(isa)) {
      s.connect(a, timeout);
      return;
    }
    if (!active) {
      s.connect(a, timeout);
      direct.add(s);  // for a `network offline` later
      return;
    }
    Conditions c = conditions;
    String host = hostOf(isa);
    String where = host + (isa.isUnresolved() ? "" : "/" + isa.getAddress().getHostAddress()) + " (port " + isa.getPort() + ")";
    if (c.offline()) throw new ConnectException("failed to connect to " + where + ": connect failed: ENETUNREACH (Network is unreachable)");
    if (s instanceof SSLSocket) {
      // TLS from the start, named by the address it connects to: going through the engine would lose that name (SNI,
      // hostname checks). Connects directly; Net.createSocket layers TLS on a plain socket instead where it can.
      s.connect(a, timeout);
      direct.add(s);
      return;
    }
    if (c.refuse()) {
      Conditions.sleep(Math.min(timeout > 0 ? timeout : 10_000, 10_000));
      throw new SocketTimeoutException("failed to connect to " + where + " after " + (timeout > 0 ? timeout : 10_000) + "ms");
    }
    // The TCP handshake's round trip counts against the caller's connect timeout, as a real one would.
    int rtt = c.rtt();
    if (timeout > 0 && rtt >= timeout) {
      Conditions.sleep(timeout);
      throw new SocketTimeoutException("failed to connect to " + where + " after " + timeout + "ms");
    }
    Conditions.sleep(rtt);
    int left = timeout > 0 ? timeout - rtt : 0;
    s.connect(engine.address(), left);
    int before = s.getSoTimeout();
    try {
      OutputStream out = s.getOutputStream();
      // The host name (for TLS and recording) and the address the app chose (custom DNS, one of several), if any.
      String ip = isa.isUnresolved() || NetResolver.hostFor(isa.getAddress().getHostAddress()) != null
          ? "-" : isa.getAddress().getHostAddress();
      out.write(("APKSIM " + host + " " + isa.getPort() + " " + left + " " + ip + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      s.setSoTimeout(left > 0 ? left + 1000 : 0);
      String reply = readLine(s.getInputStream());
      if (reply == null) throw new ConnectException("failed to connect to " + where + ": connection closed");
      if (reply.startsWith("ERR ")) throw exception(reply.substring(4), false);
    } catch (IOException e) {
      s.close();
      throw e;
    } finally {
      if (!s.isClosed()) s.setSoTimeout(before);
    }
    redirected.add(s);
  }

  private static String hostOf(InetSocketAddress isa) {
    String h = isa.getHostString();
    String original = NetResolver.hostFor(h);
    return original != null ? original : h;
  }

  public static Socket createSocket(SocketFactory f, String host, int port) throws IOException {
    if (!active) return tracked(f.createSocket(host, port));
    Socket s;
    try {
      s = f instanceof SSLSocketFactory ? new Socket() : f.createSocket();
    } catch (IOException | UnsupportedOperationException e) {
      // A factory that makes only connected sockets: its connection cannot go through the engine. Offline and replaying
      // that means failing (nothing may leave); otherwise it connects directly, unrecorded.
      if (conditions.offline()) throw new ConnectException("failed to connect to " + host + " (port " + port + "): connect failed: ENETUNREACH (Network is unreachable)");
      if (mode == Mode.REPLAY) {
        String msg = "network replay: " + f.getClass().getName() + " makes only connected sockets, so its connection to "
            + host + ":" + port + " cannot be replayed";
        log(msg);
        unmatched.add(msg);
        throw new ConnectException(msg);
      }
      if (mode == Mode.RECORD) log("network record: " + host + ":" + port + " connected directly by " + f.getClass().getName() + ": not recorded");
      return tracked(f.createSocket(host, port));
    }
    connect(s, new InetSocketAddress(host, port), 0);
    if (f instanceof SSLSocketFactory ssl && !(s instanceof SSLSocket)) return createSocket(ssl, s, host, port, true);
    return s;
  }

  /** A socket connected without the engine: remembered, so going offline closes it. */
  private static Socket tracked(Socket s) {
    if (s.getRemoteSocketAddress() instanceof InetSocketAddress a && !bypass(a)) direct.add(s);
    return s;
  }

  public static Socket createSocket(SocketFactory f, InetAddress address, int port) throws IOException {
    if (!active) return tracked(f.createSocket(address, port));
    return createSocket(f, address.getHostName(), port);
  }

  public static Socket createSocket(SSLSocketFactory f, Socket s, String host, int port, boolean autoClose) throws IOException {
    if (active && mode != Mode.SHAPE && redirected.contains(s)) return new FakeTlsSocket(s, host, port, autoClose);
    return f.createSocket(s, host, port, autoClose);
  }

  public static URLConnection openConnection(URL u) throws IOException {
    return open(u, null);
  }

  public static URLConnection openConnection(URL u, Proxy p) throws IOException {
    return open(u, p);
  }

  public static InputStream openStream(URL u) throws IOException {
    return open(u, null).getInputStream();
  }

  private static URLConnection open(URL u, Proxy p) throws IOException {
    String proto = u.getProtocol();
    String host = u.getHost();
    if (!active || !(proto.equals("http") || proto.equals("https"))
        || bypass(InetSocketAddress.createUnresolved(host, 1)) || isLiteralLoopback(host)) {
      return p == null ? u.openConnection() : u.openConnection(p);
    }
    // Offline, a connection that fails when used, without the JDK: its cached keep-alive sockets and a numeric address
    // (no lookup to fail) would otherwise still reach the server.
    if (conditions.offline()) return OfflineConnection.of(u);
    if (p != null && p.type() != Proxy.Type.DIRECT && mode != Mode.REPLAY) {
      // The app's own proxy is its route out: kept, so the request goes through it as it would have (not recorded or
      // shaped). Replaying, nothing goes out, so the engine answers as for any other request.
      log("network: " + u.getHost() + " through the app's proxy " + p + ": not " + (mode == Mode.RECORD ? "recorded" : "shaped"));
      return u.openConnection(p);
    }
    URLConnection c = u.openConnection(new Proxy(Proxy.Type.HTTP, engine.address()));
    if (c instanceof HttpsURLConnection https && mode != Mode.SHAPE) {
      https.setSSLSocketFactory(FakeTlsSocket.FACTORY);
      https.setHostnameVerifier((name, session) -> true);
    }
    return c;
  }

  private static boolean isLiteralLoopback(String host) {
    if (host.isEmpty() || !(Character.isDigit(host.charAt(0)) || host.startsWith("["))) return false;
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ helpers shared with the engine

  /** A CRLF- or LF-ended line, read a byte at a time so nothing past it is consumed; null at end of stream. */
  static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(64);
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') break;
      b.write(c);
      if (b.size() > 64 * 1024) throw new IOException("line too long");
    }
    if (c == -1 && b.size() == 0) return null;
    String s = b.toString(StandardCharsets.ISO_8859_1);
    return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
  }

  /** The exception an engine reply "ERR Class message" stands for. */
  static IOException exception(String err, boolean tls) {
    int sp = err.indexOf(' ');
    String cls = sp < 0 ? err : err.substring(0, sp);
    String msg = sp < 0 ? "" : err.substring(sp + 1);
    return switch (cls) {
      case "ConnectException" -> new ConnectException(msg);
      case "UnknownHostException" -> new UnknownHostException(msg);
      case "SocketTimeoutException" -> new SocketTimeoutException(msg);
      case "NoRouteToHostException" -> new NoRouteToHostException(msg);
      case "SSLPeerUnverifiedException" -> new SSLPeerUnverifiedException(msg);
      case "SSLHandshakeException", "SSLException", "CertificateException", "ValidatorException" -> new SSLHandshakeException(msg);
      default -> tls ? new SSLHandshakeException(cls + ": " + msg) : new IOException(cls + ": " + msg);
    };
  }

  static String errorReply(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t && t.getMessage() != null && t.getCause().getMessage() != null
        && t.getMessage().contains(t.getCause().getMessage())) {
      t = t.getCause();
    }
    String msg = String.valueOf(e.getMessage()).replace('\r', ' ').replace('\n', ' ');
    return "ERR " + t.getClass().getSimpleName() + " " + msg + "\r\n";
  }

  static String statusText(int code) {
    return switch (code) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 204 -> "No Content";
      case 301 -> "Moved Permanently";
      case 302 -> "Found";
      case 304 -> "Not Modified";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 500 -> "Internal Server Error";
      case 599 -> "No Recorded Response";
      default -> "";
    };
  }
}
