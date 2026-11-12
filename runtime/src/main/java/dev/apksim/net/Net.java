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

  /**
   * The beam sandbox (docs/beam.md) denies loopback, where the engine listens: app sockets reach it through in-memory
   * pipes (RelaySocket#connectEngine), and the JDK's own HttpURLConnection, which would need its proxy on loopback,
   * goes out directly (not captured). `-Dapksim.netInMemory=true` does the same outside the sandbox, to check it.
   */
  static final boolean inMemory = Boolean.getBoolean("apksim.beamSandbox") || Boolean.getBoolean("apksim.netInMemory");

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
  /**
   * Whether a replayed answer carries `X-Apksim-Replay: recorded` (the default). `apksim replay` turns it off
   * (`--network-replay-mark off`): the app gets the answers as it got them while recording (docs/replay.md).
   */
  static boolean replayMark = true;
  static Path trust;
  /** Sockets this redirected to the engine, which FakeTlsSocket may wrap. */
  private static final Set<Socket> redirected = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  /**
   * Remote connections the hooks made directly (before the layer was on, or TLS-first sockets), which going offline
   * must drop as well: the engine never saw them.
   */
  private static final Set<Socket> direct = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  /** App TLS factories on their way to the engine (FakeTlsSocket's hello names one by its token). */
  private static final Map<String, SSLSocketFactory> lentTls = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong tlsTokens = new java.util.concurrent.atomic.AtomicLong();

  static String lendTls(SSLSocketFactory f) {
    String token = "t" + tlsTokens.incrementAndGet();
    lentTls.put(token, f);
    return token;
  }

  static SSLSocketFactory takeTls(String token) {
    return token == null ? null : lentTls.remove(token);
  }

  /** Unmatched replay requests since launch or the current test started (docs/network.md "Matching"). */
  static final List<String> unmatched = new CopyOnWriteArrayList<>();
  private static final List<Consumer<Boolean>> offlineListeners = new CopyOnWriteArrayList<>();

  // ------------------------------------------------------------------ setup

  /** Reads the launcher's -Dapksim.network* properties. Called once, before any app code runs. */
  public static synchronized void configure() {
    configureOptions();
    // `--network-inspect` (serve --inspect-network): the engine captures in memory and the inspector reports (docs/pr-preview.md).
    if (Boolean.getBoolean("apksim.networkInspect")) {
      Inspector.on = true;
      log("network inspector: " + capture(true, true));
    }
  }

  private static void configureOptions() {
    String spec = System.getProperty("apksim.network");
    String profile = System.getProperty("apksim.networkProfile");
    String hosts = System.getProperty("apksim.networkHost");
    // First: the flight recorder's in-memory capture (--rec net) redacts them too, with the layer otherwise off.
    for (String r : list(System.getProperty("apksim.networkRedact"))) {
      if (r.startsWith("-")) redact.remove(r.substring(1));
      else redact.add(r);
    }
    String t = System.getProperty("apksim.networkTrust");  // and trusts this certificate when it starts the engine
    if (t != null) trust = Path.of(t);
    if (spec == null && profile == null && hosts == null) return;
    try {
      for (String h : list(hosts)) {
        int eq = h.indexOf('=');
        if (eq < 0) throw new IllegalArgumentException("--network-host takes HOST=ADDR[:PORT], not " + h);
        String to = h.substring(eq + 1);
        // ADDR, ADDR:PORT, [IPV6] or [IPV6]:PORT; a bare IPv6 address has no port.
        int colon = to.lastIndexOf(':');
        boolean hasPort = to.startsWith("[") ? colon > to.indexOf(']') : colon > 0 && to.indexOf(':') == colon;
        // Port 0: keep the port the app connects to (NetEngine.target).
        String addr = hasPort ? to.substring(0, colon) : to;
        if (addr.startsWith("[") && addr.endsWith("]")) addr = addr.substring(1, addr.length() - 1);
        hostMap.put(h.substring(0, eq), InetSocketAddress.createUnresolved(addr, hasPort ? Integer.parseInt(to.substring(colon + 1)) : 0));
      }
      failUnmatched = !"respond".equals(System.getProperty("apksim.networkUnmatched", "fail"));
      recordedTiming = "recorded".equals(System.getProperty("apksim.networkReplayTiming", "none"));
      replayMark = !"off".equals(System.getProperty("apksim.networkReplayMark", "on"));
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

  /**
   * The flight recorder's net channel (dev.apksim.FlightRecorder, `rec on net`): every exchange kept in memory, as
   * record mode would write it, from now on. With the network layer off it starts it in record mode with no file;
   * connections made before then close, and the app reconnects through the engine, as after a change of network. A
   * launch recording to a file (--network record) is captured as it is. Shaping (`network 3g`...) and replay cannot
   * change mode underneath their connections: the answer says so.
   */
  public static synchronized String capture(boolean on) {
    return capture(on, false);
  }

  /**
   * `atLaunch`: from Main, right after configure() and before any app code: a layer shaping or mapping hosts since
   * launch (--network-profile, --network-host) has no connections yet, so it can change to recording in memory.
   */
  public static synchronized String capture(boolean on, boolean atLaunch) {
    if (atLaunch && on && active && mode == Mode.SHAPE) {
      mode = Mode.RECORD;
      recording = new Recording(null, new Recording.Match(Set.of("method", "url", "body"), Set.of(), Set.of(), redact));
      log("network " + describe());
      return "net: capturing";
    }
    if (!active) {
      if (!on) return "net: off";
      Recording.Match match = new Recording.Match(Set.of("method", "url", "body"), Set.of(), Set.of(), redact);
      mode = Mode.RECORD;
      recording = new Recording(null, match);
      engine = new NetEngine();
      try {
        engine.start();
      } catch (IOException e) {
        engine = null;
        mode = Mode.SHAPE;
        recording = null;
        return "error: net: the network layer did not start: " + e;
      }
      active = true;
      int n = closeDirect();
      log("network " + describe() + (n > 0 ? "; " + n + " connection" + (n == 1 ? "" : "s") + " made before closed" : ""));
      return "net: capturing";
    }
    if (mode != Mode.RECORD) {
      return on ? "error: net: the network layer is " + (mode == Mode.REPLAY ? "replaying" : "shaping (network conditions)")
          + " since before; capturing needs it off or recording (launch with --rec net)" : "net: off";
    }
    if (recording.path != null) {
      // A --network record file is the user's own recording: it goes on whatever the flight recorder does, which
      // reads it (only the exchanges made while its channel was on: dev.apksim.rec.Rec.NET).
      return on ? "net: capturing (from the --network record file)" : "net: off (the --network record file goes on)";
    }
    recording.keep = on;
    return on ? "net: capturing" : "net: paused";
  }

  /**
   * The exchanges captured whose start `keep` accepts (epoch ms), taken now, as a HAR file built when the result is
   * called (on the flight recorder's save thread: a capture can hold 64 MB of bodies); null when nothing is captured.
   */
  public static synchronized java.util.function.Supplier<byte[]> captured(java.util.function.LongPredicate keep) {
    if (!active || mode != Mode.RECORD) return null;
    java.util.function.Supplier<String> har = recording.harLater(keep);
    return () -> har.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Whether the network inspector is on (`--network-inspect`). */
  public static boolean inspecting() {
    return Inspector.on;
  }

  /** `netwatch`: the inspector's events to `out` until the viewer closes `s`. */
  public static void watch(Socket s, java.io.PrintWriter out) throws IOException {
    Inspector.watch(s, out);
  }

  /** Waits up to `ms` for exchanges whose answer the app may already have but the engine has not recorded yet. */
  public static void drain(long ms) {
    NetEngine e = engine;
    if (e != null) e.awaitInFlight(ms);
  }

  /** `rec clear`: forgets what the flight recorder's in-memory capture holds (a --network record file keeps its own). */
  public static synchronized void clearCaptured() {
    if (active && mode == Mode.RECORD && recording.path == null) recording.clearCaptured();
  }

  /** How many exchanges are held now (captured or recorded); -1 with no recording. */
  public static synchronized int capturedCount() {
    return active && mode == Mode.RECORD ? recording.size() : -1;
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
      case RECORD -> recording.path == null ? "capturing for the flight recorder (in memory" + (recording.keep ? "" : ", paused") + ")"
          : "recording to " + recording.path;
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
      closeDirect();
    }
    if (was != c.offline()) {
      flushDnsCache();
      for (Consumer<Boolean> l : offlineListeners) l.accept(c.offline());
    }
  }

  /** Closes the remote connections made without the engine (loopback stays); how many. */
  private static int closeDirect() {
    List<Socket> drop = new ArrayList<>();
    synchronized (direct) {
      // Loopback stays up; a socket not connected yet (one the JDK connects itself) goes too.
      for (java.util.Iterator<Socket> it = direct.iterator(); it.hasNext(); ) {
        Socket s = it.next();
        InetAddress to = s.getInetAddress();
        if (to != null && (to.isLoopbackAddress() || to.isAnyLocalAddress())) continue;
        drop.add(s);
        it.remove();
      }
    }
    for (Socket s : drop) {
      try {
        s.close();
      } catch (IOException ignored) {
        // closing anyway
      }
    }
    return drop.size();
  }

  /**
   * Forgets the JDK's cached lookups, as Android flushes its resolver cache when the network changes: otherwise
   * failures looked up while offline stay failures for 10 s after coming back (networkaddress.cache.negative.ttl), and
   * names looked up before going offline go on resolving.
   */
  static void flushDnsCache() {
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
    if (engine != null) engine.flushAtExit();
  }

  // ------------------------------------------------------------------ control command

  /** network [online|offline|3g|lossy|latency MS|bandwidth KBPS|unmatched [clear]|save]. */
  public static String command(String[] a) {
    if (!active) {
      if (a.length > 1 && !a[1].equals("status")) {
        // Conditions can start at any time; recording and replaying need their launch options.
        System.setProperty("apksim.networkProfile", "online");
        configure();
        // Connections made before (directly) would carry on unshaped: they close, and the app reconnects through
        // the engine, as after a change of network on a device.
        int n = closeDirect();
        if (n > 0) log("network: " + n + " connection" + (n == 1 ? "" : "s") + " made before the network layer was on closed");
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
      case "rule" -> {
        return Inspector.rule(a);
      }
      case "detail" -> {
        return Inspector.detail(a);
      }
      case "save" -> {
        if (mode != Mode.RECORD || recording.path == null) return "error: not recording to a file";
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
    Socket raw = s;  // what connects directly
    if (s instanceof RelaySocket rs) {  // the tab relay is on (docs/beam.md "The tab relay"), or the engine is in memory
      if (rs.relayConnect(a)) return;
      raw = rs.real();
    }
    if (!(a instanceof InetSocketAddress isa) || bypass(isa)) {
      raw.connect(a, timeout);
      return;
    }
    if (!active) {
      raw.connect(a, timeout);
      directly(s, isa);  // for a `network offline` later
      startedMeanwhile(s);
      return;
    }
    Conditions c = conditions;
    String host = hostOf(isa);
    String where = host + (isa.isUnresolved() ? "" : "/" + isa.getAddress().getHostAddress()) + " (port " + isa.getPort() + ")";
    if (c.offline()) throw new ConnectException("failed to connect to " + where + ": connect failed: ENETUNREACH (Network is unreachable)");
    if (s instanceof SSLSocket) {
      // TLS from the start, named by the address it connects to: going through the engine would lose that name (SNI,
      // hostname checks). Connects directly; Net.createSocket layers TLS on a plain socket instead where it can.
      // Replaying, nothing may leave the process: such a connection fails, and counts as unmatched.
      if (mode == Mode.REPLAY) {
        String msg = "network replay: a socket that does TLS from the start (" + s.getClass().getName() + ") to " + where
            + " cannot be replayed";
        log(msg);
        unmatched.add(msg);
        throw new ConnectException(msg);
      }
      raw.connect(a, timeout);
      directly(s, isa);
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
    if (s instanceof RelaySocket rs && inMemory) rs.connectEngine(engine, isa);
    else raw.connect(engine.address(), left);
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
    if (s instanceof RelaySocket rs && !inMemory) redirected.add(rs.real());  // TLS is layered on the real one
  }

  private static String hostOf(InetSocketAddress isa) {
    String h = isa.getHostString();
    String original = NetResolver.hostFor(h);
    return original != null ? original : h;
  }

  /** `factory.createSocket()`: with the tab relay on, a plain factory's socket can be connected to the relay's host. */
  public static Socket createSocket(SocketFactory f) throws IOException {
    Socket s = f.createSocket();
    return (TabRelay.on || inMemory) && !(f instanceof SSLSocketFactory) && s.getClass() == Socket.class ? new RelaySocket(s) : s;
  }

  /** A socket already connected to the relay's host (the factory's two-argument forms). */
  private static Socket relayed(SocketFactory f, InetSocketAddress dest) throws IOException {
    Socket s = createSocket(f);
    if (!(s instanceof RelaySocket)) {
      s.close();
      throw new ConnectException("the tab relay needs a plain socket factory");
    }
    connect(s, dest, 0);
    return s;
  }

  public static Socket createSocket(SocketFactory f, String host, int port) throws IOException {
    if (TabRelay.on && TabRelay.isHost(host) && !(f instanceof SSLSocketFactory)) return relayed(f, new InetSocketAddress(host, port));
    if (!active) return startedMeanwhile(tracked(f.createSocket(host, port)));
    return createSocket(f, new InetSocketAddress(host, port), host);
  }

  /** dest: where to connect (an address the caller chose stays chosen); host: the name for TLS and the log. */
  private static Socket createSocket(SocketFactory f, InetSocketAddress dest, String host) throws IOException {
    int port = dest.getPort();
    Socket s;
    try {
      s = f instanceof SSLSocketFactory ? new Socket() : f.createSocket();
      if (inMemory && s.getClass() == Socket.class) s = new RelaySocket(s);
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
      return tracked(dest.isUnresolved() ? f.createSocket(host, port) : f.createSocket(dest.getAddress(), port));
    }
    connect(s, dest, 0);
    if (f instanceof SSLSocketFactory ssl && !(s instanceof SSLSocket)) return createSocket(ssl, s, host, port, true);
    return s;
  }

  /** A socket connected without the engine: remembered, so going offline closes it. */
  /**
   * A socket connected directly because the layer was off when the connect began. If it came on meanwhile (`rec on
   * net`), its closeDirect may have looked at the sockets before this one was registered: closed here instead, so
   * nothing goes on past the engine. The app sees a connection that failed as the network changed, and retries.
   * `active` is set before closeDirect runs, and this socket is registered before `active` is read, so one of the two
   * closes it.
   */
  private static Socket startedMeanwhile(Socket s) throws IOException {
    if (!active) return s;
    // What the layer leaves alone stays: loopback and bypassed addresses (as tracked() and closeDirect do).
    if (!(s.getRemoteSocketAddress() instanceof InetSocketAddress a) || bypass(a) || a.getAddress() == null
        || a.getAddress().isLoopbackAddress() || a.getAddress().isAnyLocalAddress()) {
      return s;
    }
    direct.remove(s);
    s.close();
    throw new ConnectException("connection reset: the network layer started while connecting");
  }

  private static Socket tracked(Socket s) throws IOException {
    if (s.getRemoteSocketAddress() instanceof InetSocketAddress a && !bypass(a)) directly(s, a);
    return s;
  }

  /**
   * Registers a socket connected without the engine, then looks at the conditions: a connect that finished while
   * `network offline` was taking its snapshot of these sockets is closed here instead of staying usable.
   */
  private static void directly(Socket s, InetSocketAddress a) throws IOException {
    direct.add(s);
    if (conditions.offline()) {
      direct.remove(s);
      s.close();
      throw new ConnectException("failed to connect to " + a + ": connect failed: ENETUNREACH (Network is unreachable)");
    }
  }

  public static Socket createSocket(SocketFactory f, InetAddress address, int port) throws IOException {
    if (TabRelay.on && !(f instanceof SSLSocketFactory) && TabRelay.matches(new InetSocketAddress(address, port))) {
      return relayed(f, new InetSocketAddress(address, port));
    }
    if (!active) return startedMeanwhile(tracked(f.createSocket(address, port)));
    // The address as given (no new lookup); its name, if it has one, without a reverse lookup.
    InetSocketAddress dest = new InetSocketAddress(address, port);
    return createSocket(f, dest, dest.getHostString());
  }

  public static Socket createSocket(SSLSocketFactory f, Socket s, String host, int port, boolean autoClose) throws IOException {
    if (s instanceof RelaySocket rs) {
      // TLS over a plain factory's socket (OkHttp's https): layered on the socket the factory made; the relay is plain HTTP only.
      if (rs.isRelay()) throw new ConnectException(TabRelay.HOST + " is plain HTTP only");
      if (!rs.isEngineLink()) s = rs.real();
    }
    if (active && mode != Mode.SHAPE && redirected.contains(s)) return new FakeTlsSocket(s, host, port, autoClose, f);
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

  private static volatile boolean urlConnectionLogged;

  private static URLConnection open(URL u, Proxy p) throws IOException {
    String proto = u.getProtocol();
    String host = u.getHost();
    if (TabRelay.on && TabRelay.isHost(host)) {
      if (!proto.equals("http")) throw new ConnectException(TabRelay.HOST + " is plain HTTP only");
      return new RelayConnection(u);
    }
    if (!(proto.equals("http") || proto.equals("https")) || bypass(InetSocketAddress.createUnresolved(host, 1))
        || isLiteralLoopback(host)) {
      return p == null ? u.openConnection() : u.openConnection(p);
    }
    if (!active) return trackedTls(p == null ? u.openConnection() : u.openConnection(p));
    // Offline, a connection that fails when used, without the JDK: its cached keep-alive sockets and a numeric address
    // (no lookup to fail) would otherwise still reach the server.
    if (conditions.offline()) return OfflineConnection.of(u, p);
    if (inMemory && mode != Mode.REPLAY && (p == null || p.type() == Proxy.Type.DIRECT)) {
      // The engine's proxy would be on loopback, which the beam sandbox denies: out directly, not captured.
      if (!urlConnectionLogged) {
        urlConnectionLogged = true;
        log("network: HttpURLConnection requests go out directly in the beam sandbox: not captured, recorded or shaped");
      }
      return trackedTls(u.openConnection());
    }
    if (p != null && p.type() != Proxy.Type.DIRECT && mode != Mode.REPLAY) {
      // The app's own proxy is its route out: kept, so the request goes through it as it would have (not recorded or
      // shaped). Replaying, nothing goes out, so the engine answers as for any other request.
      log("network: " + u.getHost() + " through the app's proxy " + p + ": not " + (mode == Mode.RECORD ? "recorded" : "shaped"));
      return trackedTls(u.openConnection(p));
    }
    URLConnection c = u.openConnection(new Proxy(Proxy.Type.HTTP, engine.address()));
    if (c instanceof HttpsURLConnection https && mode != Mode.SHAPE) {
      // The factory the connection starts with is the app's if it set a default one (setDefaultSSLSocketFactory):
      // it does the real handshake, so its trust manager decides. The JVM's own default needs no handing on.
      SSLSocketFactory current = https.getSSLSocketFactory();
      https.setSSLSocketFactory(current == null || current == SSLSocketFactory.getDefault() ? FakeTlsSocket.FACTORY : fakeFor(current));
      https.setHostnameVerifier((name, session) -> true);
    }
    return c;
  }

  /**
   * A JDK HttpsURLConnection that does not go through the engine (the layer off, or the app's own proxy): its socket
   * factory is wrapped so the socket under its TLS is remembered, and `network offline` closes it, a download in
   * progress included. (Plain http through the JDK makes its socket out of reach; Android apps rarely use cleartext.)
   */
  private static URLConnection trackedTls(URLConnection c) {
    if (c instanceof HttpsURLConnection https && !(https.getSSLSocketFactory() instanceof TrackingFactory)) {
      // One wrapper per factory, kept: the JDK reuses a kept-alive HTTPS connection only for the same factory.
      https.setSSLSocketFactory(trackingFactories.computeIfAbsent(https.getSSLSocketFactory(), TrackingFactory::new));
    }
    return c;
  }

  private static final Map<SSLSocketFactory, TrackingFactory> trackingFactories = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<SSLSocketFactory, FakeTlsSocket.Factory> fakeFactories = new java.util.concurrent.ConcurrentHashMap<>();

  /** The fake transport for an app factory: one per factory, so the JDK still reuses its kept-alive connections. */
  private static FakeTlsSocket.Factory fakeFor(SSLSocketFactory app) {
    return fakeFactories.computeIfAbsent(app, FakeTlsSocket.Factory::new);
  }

  /**
   * The app setting an HTTPS connection's socket factory (translated app code calls this). On a connection Net gave
   * the fake transport (recording, replaying), the transport stays and the app's factory does the real handshake; on
   * one it tracks, the app's factory is tracked too. Otherwise it is set as asked.
   */
  public static void setSSLSocketFactory(HttpsURLConnection c, SSLSocketFactory f) {
    if (f == null || f instanceof FakeTlsSocket.Factory || f instanceof TrackingFactory) {
      c.setSSLSocketFactory(f);
      return;
    }
    SSLSocketFactory now = c.getSSLSocketFactory();
    if (now instanceof FakeTlsSocket.Factory) c.setSSLSocketFactory(fakeFor(f));
    else if (now instanceof TrackingFactory) c.setSSLSocketFactory(trackingFactories.computeIfAbsent(f, TrackingFactory::new));
    else c.setSSLSocketFactory(f);
  }

  /** Remembers each socket its TLS is layered on (or opened by it), as Net.connect does for app sockets. */
  static final class TrackingFactory extends SSLSocketFactory {
    private final SSLSocketFactory inner;

    TrackingFactory(SSLSocketFactory inner) {
      this.inner = inner;
    }

    @Override public String[] getDefaultCipherSuites() { return inner.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return inner.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
      tracked(s);
      return inner.createSocket(s, host, port, autoClose);
    }

    /** Not connected yet (the JDK connects it itself): remembered now, and going offline closes it either way. */
    @Override
    public Socket createSocket() throws IOException {
      Socket s = inner.createSocket();
      direct.add(s);
      // Registered after `network offline` took its snapshot: closed here, before the JDK can connect it.
      if (conditions.offline()) {
        direct.remove(s);
        s.close();
        throw new ConnectException("connect failed: ENETUNREACH (Network is unreachable)");
      }
      return s;
    }
    @Override public Socket createSocket(String h, int p) throws IOException { return tracked(inner.createSocket(h, p)); }
    @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return tracked(inner.createSocket(h, p, l, lp)); }
    @Override public Socket createSocket(InetAddress h, int p) throws IOException { return tracked(inner.createSocket(h, p)); }
    @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return tracked(inner.createSocket(h, p, l, lp)); }
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
