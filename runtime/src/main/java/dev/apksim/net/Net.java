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
 * The app's network, observed and made controllable (docs/network.md). The translator points app calls to
 * Socket.connect, Socket.getInputStream/getOutputStream, SocketFactory/SSLSocketFactory.createSocket and
 * URL.openConnection here (MethodTranslator.call); with no --network option all of them do exactly what they did,
 * after one field read. Only JDK types: this runs for every app, and an APK without OkHttp must not meet a reference
 * to it.
 *
 * <p>Two things, kept apart. <b>Observing</b> (recording to a file, the flight recorder's net channel, the network
 * inspector) is the {@link Tap}: the app's own connections, made as the app makes them, read as they pass. It changes
 * nothing the app can see: not the protocol (HTTP/2 stays HTTP/2), not the TLS, not the route. <b>Intervening</b>
 * (replaying, network conditions, a host sent elsewhere, an inspector rule that answers or changes a request) needs
 * something between the app and the server, and that is the {@link NetEngine}: only the connections an intervention
 * concerns go through it ({@link #route}), and only while it lasts.
 */
public final class Net {
  private Net() {}

  enum Mode { LIVE, REPLAY }

  /** How a connection is made: as the app made it, through the engine untouched but shaped, or answered by the engine. */
  enum Route { DIRECT, RELAY, MITM }

  /**
   * The beam sandbox (docs/beam.md) denies loopback, where the engine listens: app sockets reach it through in-memory
   * pipes (RelaySocket#connectEngine), and the JDK's own HttpURLConnection, which would need its proxy on loopback,
   * goes out directly (not captured). `-Dapksim.netInMemory=true` does the same outside the sandbox, to check it.
   */
  static final boolean inMemory = Boolean.getBoolean("apksim.beamSandbox") || Boolean.getBoolean("apksim.netInMemory");

  /** Something is on: an option, a recording, a condition. The resolver and the status line look at it. */
  static volatile boolean active;
  static volatile Mode mode = Mode.LIVE;
  static volatile Conditions conditions = Conditions.ONLINE;
  static Conditions launchConditions = Conditions.ONLINE;
  /** Where exchanges are kept (a file, or the flight recorder's and inspector's memory), or what replay answers from. */
  static volatile Recording recording;
  /** Started when the first connection needs it ({@link #engine()}); null until then. */
  static volatile NetEngine engine;
  /** Exchanges the tap added to the recording. */
  static final java.util.concurrent.atomic.AtomicInteger tapped = new java.util.concurrent.atomic.AtomicInteger();
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
  /** `--network-trust inline:BASE64`: the certificate's own bytes (PEM or DER), for launches that may name no host file. */
  static byte[] trustInline;

  /** Whether `--network-trust` gave a certificate, as a file or inline. */
  static boolean hasTrust() {
    return trust != null || trustInline != null;
  }

  static java.io.InputStream trustStream() throws IOException {
    return trustInline != null ? new java.io.ByteArrayInputStream(trustInline) : java.nio.file.Files.newInputStream(trust);
  }

  /** The option's value as the log names it. */
  static String trustName() {
    return trustInline != null ? "(inline certificate)" : String.valueOf(trust);
  }
  /** Sockets connected to the engine, and how: answered by it (FakeTlsSocket wraps them) or relayed. */
  private static final Map<Socket, Route> redirected = Collections.synchronizedMap(new WeakHashMap<>());
  /**
   * Remote connections made directly, by the host they go to: what going offline, new conditions or a new rule for
   * that host must drop, since the engine never saw them.
   */
  private static final Map<Socket, String> direct = Collections.synchronizedMap(new WeakHashMap<>());
  /** App TLS factories on their way to the engine (FakeTlsSocket's hello names one by its token). */
  private static final Map<String, SSLSocketFactory> lentTls = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong tlsTokens = new java.util.concurrent.atomic.AtomicLong();

  /** The raw socket a tunnelling FakeTlsSocket is layering real TLS on (this thread): not wrapped again. */
  static final ThreadLocal<Socket> tunnelling = new ThreadLocal<>();

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
      Inspector.maxRules = Math.max(1, Math.min(Inspector.MAX_RULES_LIMIT, Integer.getInteger("apksim.networkInspectRules", Inspector.MAX_RULES)));
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
    if (t != null && t.startsWith("inline:")) {
      try {
        trustInline = java.util.Base64.getDecoder().decode(t.substring(7));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("--network-trust inline: takes the certificate (PEM or DER) as base64");
      }
    }
    else if (t != null) trust = Path.of(t);
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
          case "record" -> recording = new Recording(file, match);
          case "replay" -> {
            mode = Mode.REPLAY;
            recording = Recording.load(file, match);
          }
          default -> throw new IllegalArgumentException("--network takes record FILE or replay FILE, not " + m);
        }
      }
      active = true;
      // Started now where every connection will need it, so a port that cannot be had stops the launch, not a request.
      if (mode == Mode.REPLAY || !hostMap.isEmpty() || conditions.shaped()) engine();
      // Saved on the way out by Standby.halt (Runtime.halt runs no hooks), and here on an ordinary exit too:
      // Ctrl-C, or the app calling System.exit.
      if (mode == Mode.LIVE && recording != null) Runtime.getRuntime().addShutdownHook(new Thread(Net::flush, "apksim-net-save"));
      retap();
      log("network " + describe());
    } catch (Exception e) {
      throw new IllegalStateException("[apksim] network setup: " + e.getMessage(), e);
    }
  }

  /** The engine, started at its first use. */
  static NetEngine engine() throws IOException {
    NetEngine e = engine;
    if (e != null) return e;
    synchronized (Net.class) {
      if (engine == null) {
        NetEngine n = new NetEngine();
        n.start();
        engine = n;
      }
      return engine;
    }
  }

  /** The tap is on while something reads what it sees: a recording that is kept, or the inspector. */
  private static void retap() {
    Recording r = recording;
    Tap.set(mode == Mode.LIVE && r != null && (r.path != null || r.keep || Inspector.on));
  }

  /**
   * The flight recorder's net channel (dev.apksim.FlightRecorder, `rec on net`): every exchange kept in memory, as
   * `--network record` would write it, from now on. It turns the tap on and nothing else: no connection is closed or
   * made differently, so the app cannot tell. Connections the app already has open were not seen from their start
   * (HTTP/2 cannot be read from the middle) and stay unread until the app replaces them; `network reconnect` closes
   * them, for who wants everything from now. A launch recording to a file (--network record) is captured as it is.
   * Replaying, there is no network to capture: the answer says so.
   */
  public static synchronized String capture(boolean on) {
    return capture(on, false);
  }

  /** `atLaunch`: from Main, right after configure() and before any app code: no connection exists yet. */
  public static synchronized String capture(boolean on, boolean atLaunch) {
    if (mode == Mode.REPLAY) return on ? "error: net: the network layer is replaying; capturing needs a live network (launch with --rec net)" : "net: off";
    Recording r = recording;
    if (r == null) {
      if (!on) return "net: off";
      r = new Recording(null, new Recording.Match(Set.of("method", "url", "body"), Set.of(), Set.of(), redact));
      recording = r;
      active = true;
      if (!atLaunch) log("network " + describe());
    } else if (r.path != null) {
      // A --network record file is the user's own recording: it goes on whatever the flight recorder does, which
      // reads it (only the exchanges made while its channel was on: dev.apksim.rec.Rec.NET).
      return on ? "net: capturing (from the --network record file)" : "net: off (the --network record file goes on)";
    }
    boolean was = Tap.on;
    r.keep = on;
    retap();
    if (!on) return "net: paused";
    int before = was || atLaunch ? 0 : openDirect();
    return "net: capturing" + (before == 0 ? "" : " (" + before + " connection" + (before == 1 ? "" : "s")
        + " opened before stay unread until the app replaces them; `network reconnect` closes them)");
  }

  /**
   * The exchanges captured whose start `keep` accepts (epoch ms), taken now, as a HAR file built when the result is
   * called (on the flight recorder's save thread: a capture can hold 64 MB of bodies); null when nothing is captured.
   * Exchanges still going on (a stream that stays open) are in it as they stand, marked unfinished.
   */
  public static java.util.function.Supplier<byte[]> captured(java.util.function.LongPredicate keep) {
    Recording r = recording;
    if (r == null || mode != Mode.LIVE) return null;
    // No lock of Net's is held while the tap's thread is asked (it may be behind): only the recording's own, after.
    List<Recording.Entry> unfinished = Tap.unfinished();
    List<Object> connections = Tap.connections(keep);
    java.util.function.Supplier<String> har = r.harLater(keep, unfinished, connections);
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

  /** Waits up to `ms` for exchanges whose answer the app may already have but which are not written down yet. */
  public static void drain(long ms) {
    NetEngine e = engine;
    if (e != null) e.awaitInFlight(ms);
    Tap.sync(() -> {}, ms);
  }

  /** `rec clear`: forgets what the flight recorder's in-memory capture holds (a --network record file keeps its own). */
  public static synchronized void clearCaptured() {
    Recording r = recording;
    if (r != null && mode == Mode.LIVE && r.path == null) r.clearCaptured();
  }

  /** How many exchanges are held now (captured or recorded); -1 with no recording. */
  public static synchronized int capturedCount() {
    Recording r = recording;
    return r != null && mode == Mode.LIVE ? r.size() : -1;
  }

  /** How many requests are on their way right now, as far as the tap has read (0 when it is off). */
  public static int inFlight() {
    return Tap.on ? Tap.inFlight() : 0;
  }

  /**
   * Whether requests are under way, as far as the tap has read: one began or ended within `quietMs`, or one younger than
   * `youngMs` is still unanswered. The flight recorder asks before a checkpoint, which must not land in the middle of
   * what the app is doing (issue 320). False while nothing reads the network.
   */
  public static boolean busy(long quietMs, long youngMs) {
    return Tap.busy(quietMs, youngMs);
  }

  /** A tapped exchange, finished or cut short: kept by the recording, shown by the inspector. The tap's thread. */
  static void tapped(Recording.Entry e) {
    Recording r = recording;
    if (r != null && mode == Mode.LIVE) {
      r.add(e);
      tapped.incrementAndGet();
      changed();
    }
    if (Boolean.getBoolean("apksim.networkTrace")) log("network tap: " + (e.error != null ? "failed (" + e.error + ")" : e.status) + " " + e.method + " " + e.url);
    Inspector.exchange(e, 0, 0);
  }

  // ------------------------------------------------------------------ saving a recording file

  private static volatile boolean dirty, flushPending;
  private static final Object saveLock = new Object();

  /** The recording changed: its file is written 300 ms from now (once for all that changes meanwhile). */
  static void changed() {
    Recording r = recording;
    if (r == null || r.path == null || mode != Mode.LIVE) return;
    dirty = true;
    if (flushPending) return;
    flushPending = true;
    Thread.ofVirtual().name("apksim-net-flush").start(() -> {
      Conditions.sleep(300);
      flushPending = false;
      flushNow();
    });
  }

  static void flushNow() {
    Recording r = recording;
    if (r == null || r.path == null || mode != Mode.LIVE) return;  // no file: the flight recorder's, in memory
    synchronized (saveLock) {
      try {
        if (dirty || !java.nio.file.Files.exists(r.path)) {
          dirty = false;
          r.save();
        }
      } catch (IOException e) {
        dirty = true;  // still unsaved: the next save (a later exchange, `network save`, the way out) tries again
        log("network record: cannot write " + r.path + ": " + e);
      }
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
    Recording r = recording;
    if (mode == Mode.REPLAY) b.append("replaying ").append(r.path).append(" (").append(r.entries.size()).append(" entries)");
    else if (r == null) b.append("live");
    else if (r.path == null) b.append("capturing for the flight recorder (in memory").append(r.keep ? "" : ", paused").append(")");
    else b.append("recording to ").append(r.path);
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
    return conditions.offline();
  }

  static void setConditions(Conditions c) {
    Conditions before = conditions;
    boolean was = before.offline();
    conditions = c;
    if (c.offline()) {
      NetEngine e = engine;
      if (e != null) e.closeAll();
      closeDirect(h -> true);
    } else if (c.shaped() && !before.shaped()) {
      // Connections made directly would carry on unshaped: they close, and the app reconnects through the engine, as
      // after a change of network on a device.
      int n = closeDirect(h -> true);
      if (n > 0) log("network: " + n + " connection" + (n == 1 ? "" : "s") + " made before the conditions closed");
    }
    if (was != c.offline()) {
      flushDnsCache();
      for (Consumer<Boolean> l : offlineListeners) l.accept(c.offline());
    }
  }

  /**
   * The inspector's rules changed. Connections to the hosts a rule now concerns were made directly and would pass it
   * by: they close, and the app's next request goes through the engine. Connections the engine holds for hosts no rule
   * concerns any more close too, so the app is back on its own connections (and its own HTTP/2) at once.
   */
  static void rulesChanged() {
    if (mode != Mode.LIVE) return;
    int n = closeDirect(h -> route(h) != Route.DIRECT);
    NetEngine e = engine;
    if (e != null) n += e.close(h -> route(h) != Route.MITM, false);
    if (n > 0) log("network inspector: " + n + " connection" + (n == 1 ? "" : "s") + " closed for the new rules; the app reconnects");
  }

  /** `network reconnect`: every connection closes, as when a device changes network; the app makes them again. */
  private static int reconnect() {
    int n = closeDirect(h -> true);
    NetEngine e = engine;
    if (e != null) n += e.close(h -> true, true);
    return n;
  }

  /** How many remote connections made directly are open. */
  private static int openDirect() {
    int n = 0;
    synchronized (direct) {
      for (Socket s : direct.keySet()) if (s.isConnected() && !s.isClosed()) n++;
    }
    return n;
  }

  /** Closes the remote connections made without the engine to the hosts `which` accepts (loopback stays); how many. */
  private static int closeDirect(java.util.function.Predicate<String> which) {
    List<Socket> drop = new ArrayList<>();
    synchronized (direct) {
      // Loopback stays up; a socket not connected yet (one the JDK connects itself) goes too.
      for (java.util.Iterator<Map.Entry<Socket, String>> it = direct.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<Socket, String> d = it.next();
        Socket s = d.getKey();
        if (s == null) continue;
        InetAddress to = s.getInetAddress();
        if (to != null && (to.isLoopbackAddress() || to.isAnyLocalAddress()) && !loopbackToo) continue;
        if (!which.test(d.getValue())) continue;
        if (!s.isClosed()) drop.add(s);
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

  /**
   * Writes the recording now; on the way out (Standby.halt). First up to 2 s for exchanges still being written down
   * (the app may already have read an answer that is still being decoded), so none is lost when the process ends
   * right after it.
   */
  public static void flush() {
    Recording r = recording;
    if (r == null || r.path == null || mode != Mode.LIVE) return;
    drain(2000);
    flushNow();
  }

  private static String counts() {
    NetEngine e = engine;
    if (mode == Mode.REPLAY) return e == null ? "" : e.replayed.get() + " replayed, " + e.missed.get() + " unmatched";
    if (recording != null) return (tapped.get() + (e == null ? 0 : e.recorded.get())) + " recorded";
    return e == null ? "" : e.relayed.get() + " connections relayed";
  }

  // ------------------------------------------------------------------ control command

  /** network [online|offline|3g|lossy|latency MS|bandwidth KBPS|unmatched [clear]|save|reconnect]. */
  public static String command(String[] a) {
    if (a.length == 1 || a[1].equals("status")) {
      if (!active) return "network: off (no --network option); network offline|3g|lossy|latency MS|bandwidth KBPS turns it on";
      String counts = counts();
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
        Recording r = recording;
        if (mode != Mode.LIVE || r == null || r.path == null) return "error: not recording to a file";
        drain(500);
        dirty = true;
        flushNow();
        return "saved " + r.size() + " entries to " + r.path;
      }
      case "reconnect" -> {
        int n = reconnect();
        return "network: " + n + " connection" + (n == 1 ? "" : "s") + " closed";
      }
      default -> {
        // Conditions can start at any time; recording and replaying need their launch options.
        Conditions c = Conditions.parse(String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
        if (c.shaped()) {
          try {
            engine();
          } catch (IOException e) {
            return "error: the network engine did not start: " + e;
          }
        }
        active = true;
        setConditions(c);
        return "network: " + conditions.describe();
      }
    }
  }

  // ------------------------------------------------------------------ hooks (translated app code calls these)

  /** Checks only (scripts/check-net-tap.sh): loopback counts as any other address, so a server on this machine stands in for a remote one. */
  private static final boolean loopbackToo = Boolean.getBoolean("apksim.netCheckLoopback");

  /** The loopback ports the owner opened to the app (empty outside a beamed instance). */
  private static final java.util.Set<Integer> ALLOWED_LOOPBACK = dev.apksim.LoopbackPorts.allowed();

  /**
   * Issue 440: a loopback port the beam agent's owner opened (`allow-loopback`) is a server the app is meant to reach:
   * connected directly as any loopback is (no offline, conditions or rules), but read by the tap.
   */
  static boolean openedLoopback(InetSocketAddress a) {
    return !ALLOWED_LOOPBACK.isEmpty() && ALLOWED_LOOPBACK.contains(a.getPort()) && bypass(a);
  }

  static boolean bypass(InetSocketAddress a) {
    if (loopbackToo) return false;
    boolean loopback;
    if (a.isUnresolved()) {
      String h = a.getHostString().toLowerCase(Locale.ROOT);
      loopback = h.equals("localhost") || h.endsWith(".localhost");
    } else {
      InetAddress ip = a.getAddress();
      loopback = ip.isLoopbackAddress() || ip.isAnyLocalAddress();
    }
    return loopback;
  }

  public static void connect(Socket s, SocketAddress a) throws IOException {
    connect(s, a, 0);
  }

  /**
   * How a connection to `host` is made right now: answered by the engine while replaying, for a host an inspector rule
   * concerns, and for a mapped host while its exchanges are recorded (the engine does the TLS to the fixture);
   * relayed by it for a mapped host otherwise, and for every host under network conditions; else directly.
   */
  static Route route(String host) {
    if (mode == Mode.REPLAY) return Route.MITM;
    String h = host.toLowerCase(Locale.ROOT);
    if (Inspector.on && Inspector.concerns(h)) return Route.MITM;
    boolean mapped = !hostMap.isEmpty() && hostMap.containsKey(h);
    if (mapped && recording != null) return Route.MITM;
    return mapped || conditions.shaped() ? Route.RELAY : Route.DIRECT;
  }

  public static void connect(Socket s, SocketAddress a, int timeout) throws IOException {
    Socket raw = s;  // what connects directly
    if (s instanceof RelaySocket rs) {  // the tab relay is on (docs/beam.md "The tab relay"), or the engine is in memory
      if (rs.relayConnect(a)) return;
      raw = rs.real();
    }
    if (!(a instanceof InetSocketAddress isa) || bypass(isa)) {
      long t0 = System.nanoTime();
      raw.connect(a, timeout);
      if (Tap.on && a instanceof InetSocketAddress o && openedLoopback(o)) Tap.connected(s, hostOf(o), o.getPort(), (System.nanoTime() - t0) / 1_000_000);
      return;
    }
    Conditions c = conditions;
    String host = hostOf(isa);
    String where = host + (isa.isUnresolved() ? "" : "/" + isa.getAddress().getHostAddress()) + " (port " + isa.getPort() + ")";
    if (c.offline()) throw new ConnectException("failed to connect to " + where + ": connect failed: ENETUNREACH (Network is unreachable)");
    Route route = route(host);
    if (route != Route.DIRECT && s instanceof SSLSocket) {
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
      route = Route.DIRECT;
    }
    if (route != Route.DIRECT && inMemory && !(s instanceof RelaySocket) && mode != Mode.REPLAY) {
      // No way to the engine for this socket (the app made it itself, or before the engine was wanted; loopback is
      // denied): it connects as it would have, past the engine, rather than fail. Replaying, nothing may leave.
      if (!directLogged) {
        directLogged = true;
        log("network: a socket the app made itself (" + s.getClass().getName() + ") to " + where
            + " connects directly in the beam sandbox: no rule or condition reaches it (said once)");
      }
      route = Route.DIRECT;
    }
    if (route == Route.DIRECT) {
      boolean chosen = route(host) == Route.DIRECT;  // not direct only for want of a way to the engine
      long t0 = System.nanoTime();
      try {
        raw.connect(a, timeout);
      } catch (IOException e) {
        Tap.connectFailed(host, isa.getPort(), e);
        throw e;
      }
      if (Tap.on) Tap.connected(s, host, isa.getPort(), (System.nanoTime() - t0) / 1_000_000);
      directly(s, isa, host);
      if (chosen) stillDirect(s, isa, host);
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
    NetEngine engine = engine();
    if (s instanceof RelaySocket rs && inMemory) rs.connectEngine(engine, isa);
    else raw.connect(engine.address(), left);
    int before = s.getSoTimeout();
    try {
      OutputStream out = s.getOutputStream();
      // The host name (for TLS and recording), the address the app chose (custom DNS, one of several), if any, and
      // whether the engine answers this connection itself or only carries it.
      String ip = isa.isUnresolved() || NetResolver.hostFor(isa.getAddress().getHostAddress()) != null
          ? "-" : isa.getAddress().getHostAddress();
      out.write(("APKSIM " + host + " " + isa.getPort() + " " + left + " " + ip + " " + (route == Route.MITM ? "answer" : "relay") + "\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
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
    redirected.put(s, route);
    if (s instanceof RelaySocket rs && !inMemory) redirected.put(rs.real(), route);  // TLS is layered on the real one
    // Carried untouched: what the app says on it (or on the TLS it layers on it) is read by the tap, as on a direct one.
    if (route == Route.RELAY && Tap.on) Tap.relayed(s, host, isa.getPort());
  }

  private static String hostOf(InetSocketAddress isa) {
    String h = isa.getHostString();
    String original = NetResolver.hostFor(h);
    return original != null ? original : h;
  }

  /** `factory.createSocket()`: with the tab relay on, a plain factory's socket can be connected to the relay's host. */
  public static Socket createSocket(SocketFactory f) throws IOException {
    Socket s = f.createSocket();
    return (TabRelay.on || inMemory && engineWanted()) && !(f instanceof SSLSocketFactory) && s.getClass() == Socket.class ? new RelaySocket(s) : s;
  }

  /** Whether some connection would go through the engine now (in the beam sandbox its sockets must be made for that). */
  private static boolean engineWanted() {
    return mode == Mode.REPLAY || !hostMap.isEmpty() || conditions.shaped() || Inspector.on && Inspector.hasRules();
  }

  /** `socket.getInputStream()` in app code: the socket's own stream, read by the tap as it passes while that is on. */
  public static InputStream inputStream(Socket s) throws IOException {
    InputStream in = s.getInputStream();
    return Tap.on ? Tap.in(s, in) : in;
  }

  public static OutputStream outputStream(Socket s) throws IOException {
    OutputStream out = s.getOutputStream();
    return Tap.on ? Tap.out(s, out) : out;
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
    if (host == null) return f.createSocket(host, port);  // loopback, as the JDK reads it: never touched
    if (TabRelay.on && TabRelay.isHost(host) && !(f instanceof SSLSocketFactory)) return relayed(f, new InetSocketAddress(host, port));
    if (!conditions.offline() && route(host) == Route.DIRECT) {
      long t0 = System.nanoTime();
      return made(f.createSocket(host, port), host, port, t0);  // as the factory makes it
    }
    return createSocket(f, new InetSocketAddress(host, port), host);
  }

  /** A socket its factory made connected, directly: remembered (for offline) and, while the tap is on, read. */
  private static Socket made(Socket s, String host, int port, long t0) throws IOException {
    if (s.getRemoteSocketAddress() instanceof InetSocketAddress a && !bypass(a)) {
      if (Tap.on) Tap.connected(s, host, port, (System.nanoTime() - t0) / 1_000_000);
      directly(s, a, host);
      stillDirect(s, a, host);
    }
    return s;
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
      // that means failing (nothing may leave); otherwise it connects directly, past whatever the engine would have done.
      if (conditions.offline()) throw new ConnectException("failed to connect to " + host + " (port " + port + "): connect failed: ENETUNREACH (Network is unreachable)");
      if (mode == Mode.REPLAY) {
        String msg = "network replay: " + f.getClass().getName() + " makes only connected sockets, so its connection to "
            + host + ":" + port + " cannot be replayed";
        log(msg);
        unmatched.add(msg);
        throw new ConnectException(msg);
      }
      log("network: " + host + ":" + port + " connected directly by " + f.getClass().getName() + " (it makes only connected sockets): no rule, mapping or condition reaches it");
      long t0 = System.nanoTime();
      return made(dest.isUnresolved() ? f.createSocket(host, port) : f.createSocket(dest.getAddress(), port), host, port, t0);
    }
    connect(s, dest, 0);
    if (f instanceof SSLSocketFactory ssl && !(s instanceof SSLSocket)) return createSocket(ssl, s, host, port, true);
    return s;
  }

  /** A socket connected without the engine: remembered, so going offline closes it. */
  private static Socket tracked(Socket s) throws IOException {
    if (s.getRemoteSocketAddress() instanceof InetSocketAddress a && !bypass(a)) directly(s, a, hostOf(a));
    return s;
  }

  /**
   * Registers a socket connected without the engine, then looks at the conditions: a connect that finished while
   * `network offline` was taking its snapshot of these sockets is closed here instead of staying usable.
   */
  private static void directly(Socket s, InetSocketAddress a, String host) throws IOException {
    direct.put(s, host.toLowerCase(Locale.ROOT));
    if (conditions.offline()) {
      direct.remove(s);
      s.close();
      throw new ConnectException("failed to connect to " + a + ": connect failed: ENETUNREACH (Network is unreachable)");
    }
  }

  /**
   * A connection made directly because nothing concerned its host when its connect began: if that changed meanwhile
   * (conditions set, a rule added for the host), whoever changed it closed the direct connections it could see, and
   * this one, registered a moment later, is closed here. The app sees a connection that failed as the network
   * changed, and retries.
   */
  private static void stillDirect(Socket s, InetSocketAddress a, String host) throws IOException {
    if (route(host) == Route.DIRECT) return;
    direct.remove(s);
    s.close();
    throw new ConnectException("connection reset: the network changed while connecting to " + a);
  }

  public static Socket createSocket(SocketFactory f, InetAddress address, int port) throws IOException {
    if (TabRelay.on && !(f instanceof SSLSocketFactory) && TabRelay.matches(new InetSocketAddress(address, port))) {
      return relayed(f, new InetSocketAddress(address, port));
    }
    // The address as given (no new lookup); its name, if it has one, without a reverse lookup.
    InetSocketAddress dest = new InetSocketAddress(address, port);
    String host = hostOf(dest);
    if (!conditions.offline() && route(host) == Route.DIRECT) {
      long t0 = System.nanoTime();
      return made(f.createSocket(address, port), host, port, t0);
    }
    return createSocket(f, dest, dest.getHostString());
  }

  public static Socket createSocket(SSLSocketFactory f, Socket s, String host, int port, boolean autoClose) throws IOException {
    Socket under = s;  // the socket the app holds
    if (s instanceof RelaySocket rs) {
      // TLS over a plain factory's socket (OkHttp's https): layered on the socket the factory made; the relay is plain HTTP only.
      if (rs.isRelay()) throw new ConnectException(TabRelay.HOST + " is plain HTTP only");
      if (!rs.isEngineLink()) s = rs.real();
    }
    // Answered by the engine: a TLS socket that does no TLS, so the engine reads the requests. Anything else gets the
    // TLS the app's factory makes, end to end (through the engine's relay too), which the tap reads above it.
    if (redirected.get(s) == Route.MITM && tunnelling.get() != s) return new FakeTlsSocket(s, host, port, autoClose, f);
    Socket tls = f.createSocket(s, host, port, autoClose);
    if (Tap.on && tunnelling.get() != s) Tap.layered(tls, under, host, port);
    return tls;
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

  private static volatile boolean urlConnectionLogged, directLogged;

  private static URLConnection open(URL u, Proxy p) throws IOException {
    String proto = u.getProtocol();
    String host = u.getHost();
    if (TabRelay.on && TabRelay.isHost(host)) {
      if (!proto.equals("http")) throw new ConnectException(TabRelay.HOST + " is plain HTTP only");
      return new RelayConnection(u);
    }
    int urlPort = u.getPort() > 0 ? u.getPort() : u.getDefaultPort();
    // An opened loopback port's plain-http URLConnection is not listed (the JDK's client has no socket the tap reads).
    if (!(proto.equals("http") || proto.equals("https")) || bypass(InetSocketAddress.createUnresolved(host, urlPort))
        || isLiteralLoopback(host)) {
      return p == null ? u.openConnection() : u.openConnection(p);
    }
    // Offline, a connection that fails when used, without the JDK: its cached keep-alive sockets and a numeric address
    // (no lookup to fail) would otherwise still reach the server.
    if (conditions.offline()) return OfflineConnection.of(u, p);
    Route route = route(host);
    boolean ownProxy = p != null && p.type() != Proxy.Type.DIRECT;
    // Plain http through the JDK's client has no socket the tap could read: while exchanges are kept it goes through
    // the engine, which reads HTTP/1.1 as it stands (what the JDK's client speaks anyway).
    if (route == Route.DIRECT && proto.equals("http") && !ownProxy && !inMemory && Tap.on) route = Route.MITM;
    if (route == Route.DIRECT) return trackedTls(p == null ? u.openConnection() : u.openConnection(p));
    if (inMemory && mode != Mode.REPLAY && !ownProxy) {
      // The engine's proxy would be on loopback, which the beam sandbox denies: out directly (read by the tap, if https).
      if (!urlConnectionLogged) {
        urlConnectionLogged = true;
        log("network: HttpURLConnection requests go out directly in the beam sandbox: no rule or condition reaches them");
      }
      return trackedTls(u.openConnection());
    }
    if (ownProxy && mode != Mode.REPLAY) {
      // The app's own proxy is its route out: kept, so the request goes through it as it would have (no rule or
      // condition reaches it). Replaying, nothing goes out, so the engine answers as for any other request.
      log("network: " + u.getHost() + " through the app's proxy " + p + ": no rule or condition reaches it");
      return trackedTls(u.openConnection(p));
    }
    URLConnection c = u.openConnection(new Proxy(Proxy.Type.HTTP, engine().address()));
    if (c instanceof HttpsURLConnection https) {
      if (route == Route.MITM) {
        // The factory the connection starts with is the app's if it set a default one (setDefaultSSLSocketFactory):
        // it does the real handshake, so its trust manager decides. The JVM's own default needs no handing on.
        SSLSocketFactory current = https.getSSLSocketFactory();
        https.setSSLSocketFactory(current == null || current == SSLSocketFactory.getDefault() ? FakeTlsSocket.FACTORY : fakeFor(current));
        https.setHostnameVerifier((name, session) -> true);
      } else {
        trackedTls(c);  // relayed: its own TLS through the engine's tunnel, read by the tap
      }
    }
    return c;
  }

  /**
   * A JDK HttpsURLConnection whose TLS is its own (made directly, through the app's proxy, or relayed): its socket
   * factory is wrapped so the socket under its TLS is remembered, and `network offline` closes it, a download in
   * progress included, and so the tap can read it ({@link TapSslSocket}). (Plain http through the JDK makes its socket
   * out of reach; Android apps rarely use cleartext.)
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
      return tapped(inner.createSocket(s, host, port, autoClose), host, port);
    }

    /** Not connected yet (the JDK connects it itself): remembered now, and going offline closes it either way. */
    @Override
    public Socket createSocket() throws IOException {
      Socket s = inner.createSocket();
      direct.put(s, "");
      // Registered after `network offline` took its snapshot: closed here, before the JDK can connect it.
      if (conditions.offline()) {
        direct.remove(s);
        s.close();
        throw new ConnectException("connect failed: ENETUNREACH (Network is unreachable)");
      }
      return tapped(s, null, 0);
    }
    @Override public Socket createSocket(String h, int p) throws IOException { return tapped(tracked(inner.createSocket(h, p)), h, p); }
    @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return tapped(tracked(inner.createSocket(h, p, l, lp)), h, p); }
    @Override public Socket createSocket(InetAddress h, int p) throws IOException { return tapped(tracked(inner.createSocket(h, p)), h.getHostName(), p); }
    @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return tapped(tracked(inner.createSocket(h, p, l, lp)), h.getHostName(), p); }

    /** The JDK reads its socket's streams itself, where no hook reaches: while the tap is on it gets a socket that copies them. */
    private static Socket tapped(Socket s, String host, int port) {
      return Tap.on && s instanceof SSLSocket ssl ? new TapSslSocket(ssl, host, port) : s;
    }
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
