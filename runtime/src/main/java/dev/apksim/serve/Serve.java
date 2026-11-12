package dev.apksim.serve;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * `apksim serve`: APKs in a browser tab (docs/serve.md). An HTTP server with WebSocket on plain sockets, one virtual
 * thread per connection; each WebSocket is a {@link Session}, which runs one apksim instance. Runs on a plain JVM with
 * only apksim.jar: nothing here touches Robolectric or Android.
 */
public final class Serve {
  private static final String USAGE = """
      usage: apksim serve [--port N] [--bind ADDR] [--token FILE] [--max N] [--idle SECONDS] [--apps DIR|FILE]...
                          [--max-upload MB] [--max-push MB] [--allow-rec] [--issue-url TEMPLATE] [--inspect-network] [--quality Q] [--bitrate KBPS] [--codec auto|jpeg] [--no-network]
                          [--allow-origin ORIGIN]... [--warm auto|on|off] [--core-dumps] [--move-shared-data] [--in-flight N]
                          [--trust-proxy] [--public-origin URL] [--base-path /PATH] [--device-size NAME=WxH@DENSITY]...
                          [--skins DIR]... [--allow-beam] [--beam-port N] [--keep-max N] [--keep-days N]
             apksim beam [--port N] [--max N] [--idle SECONDS] [--quality Q] [--bitrate KBPS] [--codec auto|jpeg]
                         [--keep-days N] [--save-sessions on|off] [--keep-saved-days N|never]
             apksim beam trust [list | allow ORIGIN | deny ORIGIN | revoke ORIGIN]
             apksim beam saved | run ID [OPTIONS] | to-device ID [OPTIONS] | forget ID | sweep | status
             apksim beam install [--port N] [--agent-idle SECONDS] [--trust ORIGIN]... | uninstall   (launchd starts it when a page asks)
             apksim beam config [keep-days N] [save-sessions on|off] [keep-saved-days N|never] [rec input|all|off|CHANNELS]
                               [allow-download URL] [disallow-download URL]""";
  private static final SecureRandom RANDOM = new SecureRandom();

  final Path root;
  final Path state;
  final Path launcher;
  /** Sessions' logs: `STATE/serve/logs`, or `STATE/beam/logs` for the agent. */
  Path logs;
  Catalog catalog;
  int port = 8080;
  String bind = "127.0.0.1";
  String token;
  int max = 4;
  int idleSeconds = 600;
  int quality = 80;
  /** `--in-flight N`: the most frames in flight to a tab (the window is sized from the round trip, up to this). */
  int inFlightMax = 8;
  /** H.264 streams' bit rate in kilobits a second (`--bitrate`; the agent's default is {@link #BEAM_KBPS}). */
  int bitrateKbps = 4000;
  private boolean bitrateGiven;
  /**
   * The agent's stream never leaves the Mac, so it has no network to spare: 20 Mbps gives the Shader tab 16 KB a frame
   * against 4 (fine detail in motion) for ~0.6 ms more encode, with the same pacing and latency (beam-parity.py).
   */
  static final int BEAM_KBPS = 20_000;
  /** Whether a tab may ask for H.264 (`--codec auto`, the default) or always gets JPEG (`--codec jpeg`). */
  boolean allowH264 = true;
  long maxUploadMb = 300;
  /** Issue 187: a file a tab pushes into its app (MB each, MB in all per session), and whether a tab may record. */
  long maxPushMb = 16;
  long maxPushTotalMb = 64;
  boolean allowRec;
  /**
   * `--inspect-network`: instances run with the network inspector (`--network-inspect`) and a tab sees the app's HTTP(S)
   * exchanges and may set rules that answer or slow a URL (docs/pr-preview.md). Off by default: it shows the app's traffic.
   */
  boolean inspectNetwork;
  /** `--issue-url TEMPLATE`: the report button also opens this new-issue page, prefilled, in the reviewer's own browser. */
  String issueUrl;
  /** Issue 221: `--keep-max N` kept sessions in all (0: none kept, the default), and the store (null on the agent). */
  int keepMax;
  KeptStore kept;
  int launchTimeoutSeconds = 180;
  boolean noNetwork;
  /** `--core-dumps`: instances may leave core files when they crash (off by default: a large app's is gigabytes). */
  boolean coreDumps;
  /** `--move-shared-data`: every instance loads 4 KB-aligned libraries with data on a code page (macOS, experimental). */
  boolean moveSharedData;
  /** `--warm`: warm each APK's cache in the background: on Linux (no warm standby) by default, `on` also on the Mac. */
  String warm = "auto";
  Warmer warmer;
  /** `--device-size NAME=WxH@density`: named sizes besides the built-in devices, in the order given. */
  final Map<String, String> deviceSizes = new LinkedHashMap<>();
  boolean loopbackBind;
  private final List<String> apps = new ArrayList<>();
  /** Pages on these origins may frame serve's page and open its WebSockets and POSTs (`--allow-origin`). */
  final List<String> allowedOrigins = new ArrayList<>();
  /** Behind a reverse proxy: take X-Forwarded-Host/-Proto from a loopback peer as the request's own (`--trust-proxy`). */
  boolean trustProxy;
  /** The origin browsers reach serve at through a proxy (`--public-origin`): accepted as serve's own, used in links. */
  String publicOrigin;
  /** The path serve's page, /ws and /api/* are under (`--base-path /sim`): "" for none, else "/sim". */
  String basePath = "";
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  /** Connections open at once (open sessions' WebSockets among them). */
  private final java.util.concurrent.Semaphore connections = new java.util.concurrent.Semaphore(128);
  private byte[] page;
  /** `/?compare=A,B`: two sessions side by side with their pixel difference (docs/pr-preview.md). */
  private byte[] comparePage;
  private final long startedAt = System.currentTimeMillis();
  /**
   * `apksim beam` (docs/beam.md): the agent that runs a browser session's app on this Mac. Loopback only, no page of
   * its own, and only for page origins its owner allowed ({@link Beam}).
   */
  boolean beam;
  /** The state folder's part (`serve`, or `beam` for the agent) and the instances' data slot name (serve-0, beam-0...). */
  String home = "serve";
  Beam agent;
  /** The agent's builds and saved sessions, and how long it keeps them (docs/beam.md, "What the Mac keeps"). */
  BeamStore store;
  /** `apksim beam --keep-days`, `--save-sessions`, `--keep-saved-days`: this run's values over the config file's. */
  private Double keepDays;
  private Boolean saveSessions;
  private Double keepSavedDays;
  /** `--allow-beam`: this server's page offers "Run on this Mac" and serves its APKs to it (docs/beam.md). */
  boolean allowBeam;
  /**
   * `apksim beam --launchd` (`apksim beam install`): started by launchd on a page's first connection, with the listening
   * socket on stdin (inetd's way), and gone again after {@link #agentIdleSeconds} with no session or request.
   */
  boolean launchd;
  int agentIdleSeconds = 600;
  private volatile long lastActivity = System.currentTimeMillis();
  private final java.util.concurrent.atomic.AtomicInteger busy = new java.util.concurrent.atomic.AtomicInteger();
  private static final ThreadLocal<Boolean> statusRequest = ThreadLocal.withInitial(() -> false);
  /**
   * Issue 181: the copies of saved sessions reopened in a tab (`bm<hex>` slot name -> saved id), written back over the
   * saved slot when their session ends. One open at a time per saved session (see {@link #savedOpen}).
   */
  final Map<String, String> reopened = new ConcurrentHashMap<>();
  /** Each reopened copy's saved record `changed` when it was made (BeamStore.writeBack refuses data changed since). */
  final Map<String, Long> reopenedChanged = new ConcurrentHashMap<>();
  /** `--allow-beam`: the tickets that let a visitor's agent download an app's APK from here itself (phase 2). */
  final BeamTicket tickets = new BeamTicket();
  /** The agent's port the page reaches (`--beam-port`, default {@link Beam#DEFAULT_PORT}). */
  int beamPort = Beam.DEFAULT_PORT;
  private boolean portGiven, maxGiven;

  private Serve(Path root, Path state) {
    this.root = root;
    this.state = state;
    this.launcher = root.resolve("apksim");
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(System.getProperty("apksim.root", ".")).toAbsolutePath().normalize();
    Path state = Path.of(System.getProperty("apksim.state", root.resolve("work").toString())).toAbsolutePath().normalize();
    Serve s = new Serve(root, state);
    if (args.length > 1 && args[0].equals("--beam") && args[1].equals("trust")) {
      Beam.trustCommand(state, List.of(args).subList(2, args.length));
      return;
    }
    if (args.length > 1 && args[0].equals("--beam") && List.of("install", "uninstall").contains(args[1])) {
      System.exit(BeamLaunchd.command(s.launcher, state, args[1], List.of(args).subList(2, args.length)));
    }
    if (args.length > 1 && args[0].equals("--beam") && List.of("saved", "run", "to-device", "forget", "config", "sweep", "status").contains(args[1])) {
      // The agent's own commands: its rules for saved options (a server skin's device, a skin it does not have).
      Catalog.agent = true;
      System.exit(BeamStore.command(root, state, s.launcher, args[1], List.of(args).subList(2, args.length)));
    }
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      String v = i + 1 < args.length ? args[i + 1] : null;
      switch (a) {
        case "--port" -> {
          s.port = Integer.parseInt(need(a, v));
          s.portGiven = true;
        }
        // Flags without a value: past the step over a value after the switch, to the next argument.
        case "--beam" -> {
          s.beam = true;
          Catalog.agent = true;
          continue;
        }
        case "--allow-beam" -> {
          s.allowBeam = true;
          continue;
        }
        case "--launchd" -> {
          s.launchd = true;
          continue;
        }
        case "--agent-idle" -> s.agentIdleSeconds = Math.max(5, Integer.parseInt(need(a, v)));
        case "--beam-port" -> s.beamPort = Integer.parseInt(need(a, v));
        case "--keep-days" -> {
          s.keepDays = BeamStore.days(need(a, v), false);
          if (s.keepDays == null) usage("--keep-days takes a number of days (0: as soon as the session has ended)");
        }
        case "--save-sessions" -> {
          s.saveSessions = BeamStore.onOff(need(a, v));
          if (s.saveSessions == null) usage("--save-sessions takes on or off");
        }
        case "--keep-saved-days" -> {
          s.keepSavedDays = BeamStore.days(need(a, v), true);
          if (s.keepSavedDays == null) usage("--keep-saved-days takes a number of days or never");
        }
        case "--bind" -> s.bind = need(a, v);
        case "--token" -> s.token = Files.readAllLines(Path.of(need(a, v))).stream().findFirst().orElse("").strip();
        case "--max" -> {
          s.max = Integer.parseInt(need(a, v));
          s.maxGiven = true;
        }
        case "--idle" -> s.idleSeconds = Integer.parseInt(need(a, v));
        case "--apps" -> s.apps.add(need(a, v));
        case "--max-upload" -> s.maxUploadMb = Long.parseLong(need(a, v));
        case "--max-push" -> s.maxPushMb = Math.max(1, Math.min(32, Long.parseLong(need(a, v))));
        case "--allow-rec" -> {
          s.allowRec = true;
          continue;  // no value: the next argument is an option of its own
        }
        case "--inspect-network" -> {
          s.inspectNetwork = true;
          continue;  // no value
        }
        case "--issue-url" -> s.issueUrl = checkIssueUrl(need(a, v));
        case "--keep-max" -> s.keepMax = Math.max(0, Math.min(100_000, Integer.parseInt(need(a, v))));
        case "--in-flight" -> s.inFlightMax = Math.max(1, Math.min(32, Integer.parseInt(need(a, v))));
        case "--quality" -> s.quality = Integer.parseInt(need(a, v));
        case "--bitrate" -> {
          s.bitrateKbps = Math.max(100, Math.min(100_000, Integer.parseInt(need(a, v))));
          s.bitrateGiven = true;
        }
        case "--codec" -> {
          String c = need(a, v);
          if (!c.equals("auto") && !c.equals("jpeg")) {
            System.err.println("--codec takes auto or jpeg\n" + USAGE);
            System.exit(2);
          }
          s.allowH264 = c.equals("auto");
        }
        case "--device-size" -> {
          String d = need(a, v);
          int eq = d.indexOf('=');
          String name = eq < 0 ? "" : d.substring(0, eq), size = eq < 0 ? "" : d.substring(eq + 1);
          if (!name.matches("[a-z][a-z0-9-]{0,23}") || Session.DEVICES.contains(name) || s.deviceSizes.containsKey(name)
              || Catalog.deviceSize(size) == null) {
            System.err.println("--device-size takes NAME=WxH[@density] such as hd720=1280x720@1.5 (a new lower-case name; 160-4096 dp a side,"
                + " density 0.75-4, at most 4096 pixels a side)\n" + USAGE);
            System.exit(2);
          }
          s.deviceSizes.put(name, size);
        }
        case "--skins" -> {
          // More folders of skins (docs/skins.md, docs/serve.md "Skins"), searched after ~/.apksim/skins and APKSIM_SKINS.
          Path d = Path.of(need(a, v));
          if (!Files.isDirectory(d)) {
            System.err.println("--skins: no folder " + d + "\n" + USAGE);
            System.exit(2);
          }
          dev.apksim.skin.Skins.addDirs(List.of(d));
        }
        case "--launch-timeout" -> s.launchTimeoutSeconds = Integer.parseInt(need(a, v));
        case "--trust-proxy" -> {
          s.trustProxy = true;
          i--;
        }
        case "--public-origin" -> {
          String o = origin(need(a, v));  // as a browser sends it, as for --allow-origin
          if (o == null) {
            System.err.println("--public-origin takes an origin such as https://app.example.com (no path)\n" + USAGE);
            System.exit(2);
          }
          s.publicOrigin = o;
        }
        case "--base-path" -> {
          String b = need(a, v).replaceFirst("/+$", "");
          // No "." or ".." segments (a browser resolves them, so the prefix it asks for would not be this one), and not
          // serve's own routes: behind a proxy that strips the prefix, /api/apps or /ws would be taken for prefixed.
          if (!b.isEmpty() && (!b.matches("(/[A-Za-z0-9._~-]+)+") || b.matches(".*/\\.{1,2}(/.*)?")
              || b.equals("/ws") || b.equals("/api") || b.startsWith("/api/"))) {
            System.err.println("--base-path takes a path such as /sim (no . or .. segments, not /ws or /api...)\n" + USAGE);
            System.exit(2);
          }
          s.basePath = b;
        }
        case "--allow-origin" -> {
          String o = origin(need(a, v));
          if (o == null) {
            System.err.println("--allow-origin takes an origin such as https://dashboard.example.com (no path)\n" + USAGE);
            System.exit(2);
          }
          s.allowedOrigins.add(o);
        }
        case "--warm" -> {
          s.warm = need(a, v);
          if (!List.of("auto", "on", "off").contains(s.warm)) {
            System.err.println("--warm takes auto, on or off\n" + USAGE);
            System.exit(2);
          }
        }
        case "--core-dumps" -> {
          s.coreDumps = true;
          i--;
        }
        case "--move-shared-data" -> {
          s.moveSharedData = true;
          i--;
        }
        case "--no-network" -> {
          s.noNetwork = true;
          i--;
        }
        case "-h", "--help" -> {
          System.out.println(USAGE);
          return;
        }
        default -> {
          System.err.println("unknown option " + a + "\n" + USAGE);
          System.exit(2);
        }
      }
      i++;
    }
    s.run();
  }

  /**
   * An origin as a browser sends it in `Origin`, or null when it is not one: lower case, no default port
   * (`https://a.example:443` is sent as `https://a.example`), an IPv6 address in its short form (RFC 5952:
   * `[0:0:0:0:0:0:0:1]` is sent as `[::1]`). Headers are compared with it as they come.
   */
  static String origin(String given) {
    java.net.URI u;
    try {
      u = new java.net.URI(given.strip().replaceFirst("/$", ""));
    } catch (java.net.URISyntaxException e) {
      return null;
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https") || u.getHost() == null || u.getRawUserInfo() != null
        || !(u.getRawPath() == null || u.getRawPath().isEmpty()) || u.getRawQuery() != null || u.getRawFragment() != null) {
      return null;
    }
    String host = u.getHost().toLowerCase(Locale.ROOT);
    if (host.startsWith("[")) {
      try {
        byte[] b = InetAddress.getByName(host.substring(1, host.length() - 1)).getAddress();
        if (b.length != 16) return null;
        host = "[" + ipv6(b) + "]";
      } catch (java.io.IOException e) {
        return null;
      }
    } else if (!host.matches("[a-z0-9.-]+")) {
      return null;
    }
    int port = u.getPort();
    boolean usual = port == -1 || scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443;
    return scheme + "://" + host + (usual ? "" : ":" + port);
  }

  /** RFC 5952's form: hex groups without leading zeros, the longest run of two or more zero groups as "::". */
  private static String ipv6(byte[] b) {
    int[] g = new int[8];
    for (int i = 0; i < 8; i++) g[i] = ((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff);
    int best = -1, bestLen = 0;
    for (int i = 0; i < 8; ) {
      if (g[i] != 0) {
        i++;
        continue;
      }
      int j = i;
      while (j < 8 && g[j] == 0) j++;
      if (j - i > bestLen && j - i >= 2) {
        best = i;
        bestLen = j - i;
      }
      i = j;
    }
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      if (i == best) {
        s.append("::");
        i += bestLen - 1;
        continue;
      }
      if (s.length() > 0 && s.charAt(s.length() - 1) != ':') s.append(':');
      s.append(Integer.toHexString(g[i]));
    }
    return s.toString();
  }

  private static void usage(String why) {
    System.err.println(why + "\n" + USAGE);
    System.exit(2);
  }

  private static String need(String option, String value) {
    if (value == null) {
      System.err.println(option + " needs a value\n" + USAGE);
      System.exit(2);
    }
    return value;
  }

  private void run() throws Exception {
    if (beam) {
      // Only what a page on this Mac's own browser needs: loopback, no token (the page's origin is the check), no
      // catalog, proxy or framing of its own.
      if (!bind.equals("127.0.0.1") || token != null || !apps.isEmpty() || trustProxy || publicOrigin != null
          || !basePath.isEmpty() || !allowedOrigins.isEmpty() || allowBeam) {
        System.err.println("apksim beam: takes only --port, --max, --idle, --quality, --bitrate, --codec, --in-flight,"
            + " --max-upload, --no-network, --launch-timeout, --keep-days, --save-sessions, --keep-saved-days, --launchd, --agent-idle (it listens"
            + " on 127.0.0.1 and asks before a site runs an app)");
        System.exit(2);
      }
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
        System.err.println("apksim beam: runs only on macOS for now: a site's app runs in macOS's sandbox (docs/beam.md),"
            + " and there is none on this system yet");
        System.exit(2);
      }
      home = "beam";
      if (!portGiven) port = Beam.DEFAULT_PORT;
      if (!maxGiven) max = 2;
      if (!bitrateGiven) bitrateKbps = BEAM_KBPS;
      agent = new Beam(state);
      store = new BeamStore(state);
      BeamStore.Config c = store.config;
      store.config = new BeamStore.Config(keepDays != null ? keepDays : c.keepDays(),
          saveSessions != null ? saveSessions : c.saveSessions(), keepSavedDays != null ? keepSavedDays : c.keepSavedDays(), c.rec(), c.inspectNetwork());
      if (!store.lockAgent(port)) {
        int other = store.agentPort();
        System.err.println("apksim beam: an agent already runs for " + state + (other > 0 ? " (port " + other + ")" : "")
            + "; stop it first (two would delete each other's builds)");
        System.exit(2);
      }
    } else if (saveSessions != null || keepSavedDays != null) {
      usage("--save-sessions and --keep-saved-days are apksim beam's");
    } else if (keepDays != null && keepDays <= 0) {
      usage("--keep-days takes a number of days above 0 (how long a kept session's data stays unused)");
    }
    if (beam && keepMax > 0) usage("--keep-max is apksim serve's (the agent keeps sessions with --save-sessions)");
    logs = state.resolve(home + "/logs");
    InetAddress addr = InetAddress.getByName(bind);
    loopbackBind = addr.isLoopbackAddress();
    if (!loopbackBind && (token == null || token.isEmpty())) {
      System.err.println("apksim serve: binding to " + bind + " needs --token FILE (a shared secret every request must carry)");
      System.exit(2);
    }
    if (trustProxy && (token == null || token.isEmpty())) {
      // Any loopback connection counts as the proxy: without a token, a DNS-rebinding page reaching loopback could
      // send its own X-Forwarded-Host and pass as one.
      System.err.println("apksim serve: --trust-proxy needs --token FILE (any loopback connection is taken for the proxy)");
      System.exit(2);
    }
    if (token != null && token.length() < 16) {
      System.err.println("apksim serve: the token must be at least 16 characters");
      System.exit(2);
    }
    if (!Files.isExecutable(launcher)) throw new IOException("no launcher at " + launcher);
    catalog = new Catalog(apps, state, home, maxUploadMb << 20);
    try (InputStream in = Serve.class.getResourceAsStream("serve.html")) {
      if (in == null) throw new IOException("serve.html is missing from apksim.jar");
      page = withReport(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
    try (InputStream in = Serve.class.getResourceAsStream("compare.html")) {
      if (in == null) throw new IOException("compare.html is missing from apksim.jar");
      comparePage = in.readAllBytes();
    }
    Files.createDirectories(logs);
    Crash.sweepRecordings(logs, 7);
    Crash.sweep(logs, 7);  // core files and crash reports an earlier run left, and old logs
    warmer = new Warmer(this);
    catalog.onReady = warmer::enqueue;
    if (!beam) {
      kept = new KeptStore(state.resolve(home + "/kept"), token, keepMax, keepDays != null ? keepDays : 14);
    }
    ServerSocket server;
    if (launchd) {
      // launchd listens and hands the socket over on stdin (inetdCompatibility with Wait): what the plist names.
      if (!beam || !(System.inheritedChannel() instanceof java.nio.channels.ServerSocketChannel ch)) {
        System.err.println("apksim beam --launchd: no listening socket on stdin (apksim beam install sets that up)");
        System.exit(2);
        return;
      }
      server = ch.socket();
    } else {
      server = new ServerSocket();
      server.setReuseAddress(true);
      server.bind(new InetSocketAddress(addr, port), 64);
    }
    String host = loopbackBind ? (addr instanceof java.net.Inet6Address ? "[::1]" : "localhost") : bind;
    String at = publicOrigin != null ? publicOrigin + basePath : "http://" + host + ":" + server.getLocalPort() + basePath;
    if (!beam) System.err.printf("[serve] %s/%s  (%d apps listed, up to %d sessions, idle %d s, uploads up to %d MB)%n",
        at, token == null ? "" : "?token=...", catalog.listed().size(), max, idleSeconds, maxUploadMb);
    if (trustProxy) System.err.println("[serve] trusting X-Forwarded-Host/-Proto from loopback proxies");
    if (!allowedOrigins.isEmpty()) System.err.println("[serve] pages on " + String.join(", ", allowedOrigins) + " may embed it");
    if (allowBeam) System.err.println("[serve] the page offers Run on this Mac (an apksim beam agent on port " + beamPort + ")");
    if (beam) System.err.printf("[beam] agent on 127.0.0.1:%d (protocol %d), up to %d sessions; sites allowed: %s%n", server.getLocalPort(),
        Beam.PROTOCOL, max, agent.trust().entrySet().stream().filter(e -> e.getValue().equals("always")).map(Map.Entry::getKey).toList());
    if (beam) System.err.println("[beam] keeps: " + store.config.describe() + "; " + store.saved().size() + " saved sessions");
    // Kept sessions (issue 221): only once the port is ours, and only by the one server that holds the store, so a
    // second server on this state folder (a busy port, or --keep-max 0) deletes nothing.
    if (kept != null && keepMax > 0) {
      if (kept.claim()) kept.sweep();
      else System.err.println("[serve] another server keeps sessions in " + state + ": this one keeps none");
    }
    // Every server's own leftovers (kept or restored data a stopped server unpacked), whoever owns the kept store: a
    // slot is dropped only unlocked and an hour old, so another server's session in flight is left alone.
    if (kept != null) dropUnusedSeeds();
    warmStandby();
    boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    if (warm.equals("on") || warm.equals("auto") && !mac) warmer.enqueueAll(catalog.listed());
    else catalog.onReady = null;
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "serve-timer");
      t.setDaemon(true);
      return t;
    });
    timer.scheduleWithFixedDelay(() -> sessions.values().forEach(Session::tick), 2, 2, TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(() -> {
      if (beam) sweepBeam();
      else {
        catalog.sweep(24 * 3_600_000L, name -> warmer.holdsFile(name) || sessions.values().stream()
            .anyMatch(x -> {
              // Session.app has no path while a URL add downloads: the file the session launched is launchApk.
              java.nio.file.Path f = x.launchApk != null ? x.launchApk : x.app.path();
              return f != null && name.equals(f.getFileName().toString());
            }));
        kept.sweep();  // the owner only (KeptStore.sweep)
        dropUnusedSeeds();
      }
      warmer.prune(catalog.listed().stream().map(Catalog.App::id).collect(java.util.stream.Collectors.toSet()));
    }, 0, 1, TimeUnit.HOURS);
    timer.scheduleWithFixedDelay(() -> {
      Crash.sweep(logs, 7);
      Crash.sweepRecordings(logs, 7);
    }, 1, 1, TimeUnit.HOURS);
    if (beam) timer.scheduleWithFixedDelay(agent::expire, 1, 1, TimeUnit.MINUTES);  // with the moved data of unused grants
    if (allowBeam) {
      // Sessions' data packed for a move (phase 5): gone once their tickets have expired.
      timer.scheduleWithFixedDelay(() -> {
        Path dir = state.resolve("serve/handoff");
        try (var d = Files.newDirectoryStream(dir, "d*.zip")) {
          long cutoff = System.currentTimeMillis() - (tickets.lifetimeSeconds + 60) * 1000L;
          for (Path f : d) if (Files.getLastModifiedTime(f).toMillis() < cutoff) Files.deleteIfExists(f);
        } catch (IOException e) {
          // none yet, or the next round
        }
      }, 1, 1, TimeUnit.MINUTES);
    }
    if (launchd) {
      // Nothing runs until a page asks: launchd starts the agent again on the next connection.
      // In two steps: first the lock file says "exiting", so `apksim beam status` stops connecting, and requests already
      // on their way are still answered for one more round; then it ends. A connection queued on launchd's socket as
      // the agent ended would have launchd start it again at once (a status poll every second kept it up for good). A
      // page's request meanwhile calls the exit off.
      long[] exitingSince = {0};
      timer.scheduleWithFixedDelay(() -> {
        boolean idle = sessions.isEmpty() && busy.get() == 0 && System.currentTimeMillis() - lastActivity > agentIdleSeconds * 1000L;
        if (!idle) {
          if (exitingSince[0] != 0) store.agentState(Integer.toString(port));
          exitingSince[0] = 0;
        } else if (exitingSince[0] == 0) {
          exitingSince[0] = System.currentTimeMillis();
          store.agentState("exiting");
        } else {
          System.err.printf("[beam] idle for %d s: exiting (launchd starts it again for the next page)%n", agentIdleSeconds);
          System.exit(0);
        }
      }, 5, 5, TimeUnit.SECONDS);
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      List<Thread> stopping = new ArrayList<>();
      stopping.add(Thread.ofVirtual().start(warmer::shutdown));  // ends a running warm-up and erases its data slot
      for (Session x : new ArrayList<>(sessions.values())) stopping.add(Thread.ofVirtual().start(() -> x.stop("server stopping")));
      for (Thread t : stopping) {
        try {
          t.join(15_000);
        } catch (InterruptedException e) {
          return;
        }
      }
    }));
    while (true) {
      Socket c = server.accept();
      // A bounded number of connections at once, taken before anything is allocated for one: idle or trickling
      // connections (before any token is checked) cannot use up memory or descriptors.
      if (!connections.tryAcquire()) {
        try {
          c.close();
        } catch (IOException e) {
          // nothing to do
        }
        continue;
      }
      busy.incrementAndGet();
      Thread.ofVirtual().name("serve-http").start(() -> {
        try {
          handle(c);
        } finally {
          // A page's request keeps a launchd agent up; `apksim beam status` asking about it does not (statusRequest).
          if (!statusRequest.get()) lastActivity = System.currentTimeMillis();
          statusRequest.remove();
          busy.decrementAndGet();
          connections.release();
        }
      });
    }
  }

  /** On the Mac, a warm-launch standby for the first session (each instance starts the next after its first frame). */
  void warmStandby() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return;
    if ("0".equals(System.getenv("APKSIM_STANDBY"))) return;
    try {
      // The standby is the JVM sessions' instances become: it gets the no-core policy too (APKSIM_NO_CORE reaches its flags).
      // The agent's instances run in the beam sandbox (b, the launcher's APKSIM_BEAM_SANDBOX), so their standbys too.
      ProcessBuilder pb = new ProcessBuilder(launcher.toString(), "--spawn-standby",
          (noNetwork ? "n" : "") + (coreDumps ? "" : "k") + (beam ? "b" : ""))
          .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
      instanceEnvironment(pb);
      pb.start().waitFor(10, TimeUnit.SECONDS);
    } catch (IOException | InterruptedException e) {
      System.err.println("[serve] no standby: " + e);
    }
  }

  /** What every instance serve starts (a session's, a cache warm-up's) runs with: no core dumps unless `--core-dumps`. */
  void instanceEnvironment(ProcessBuilder pb) {
    if (coreDumps) pb.environment().remove("APKSIM_NO_CORE");
    else pb.environment().put("APKSIM_NO_CORE", "1");
    // The agent's: an APK a web site sent, in a sandbox of its own (docs/beam.md "The beam sandbox").
    if (beam) {
      pb.environment().put("APKSIM_BEAM_SANDBOX", "1");
      // Nothing from the agent's own environment that would skip or weaken the sandbox (the launcher refuses then,
      // but the instance should never get them): it runs outside any sandbox, so none of these describe it.
      for (String k : List.of("APKSIM_SANDBOXED", "APKSIM_NOCACHE", "APKSIM_SANDBOX_TEST")) pb.environment().remove(k);
    } else {
      pb.environment().remove("APKSIM_BEAM_SANDBOX");
      // The skin folders this server searches (--skins too), for an instance started with --skin (issue 246).
      // Absolute: the instance runs in another folder (serve's logs), where a relative one would find nothing.
      List<String> shared = dev.apksim.skin.Skins.sharedDirs().stream().map(p -> p.toAbsolutePath().normalize().toString()).toList();
      if (!shared.isEmpty()) pb.environment().put("APKSIM_SKINS", String.join(":", shared));
      pb.environment().put("APKSIM_SKINS_DIR", dev.apksim.skin.Skins.store().toAbsolutePath().normalize().toString());
    }
  }

  /** The agent's cache for one build (translated classes), kept and deleted with it. */
  Path beamCache(String sha256) {
    return state.resolve("beam/cache").resolve(sha256);
  }

  /** Issue 187: what a download needs once its session has ended, until its recordings age out (a week). */
  private final Map<String, Object[]> keptRecs = new ConcurrentHashMap<>();

  void ended(Session s) {
    sessions.remove(s.id);
    Session.Kept k = s.kept();
    if (k != null) {
      long now = System.currentTimeMillis();
      keptRecs.values().removeIf(v -> (long) v[1] < now);
      keptRecs.put(s.id, new Object[] {k, now + 7 * 24 * 3_600_000L});
      // On disk too: an agent that exits when idle must still answer a later download (reloaded by keptOf).
      try {
        Files.createDirectories(k.dir());
        java.util.Properties pr = new java.util.Properties();
        pr.setProperty("key", k.key() == null ? "" : k.key());
        pr.setProperty("site", k.site() == null ? "" : k.site());
        pr.setProperty("rids", String.join(",", k.rids()));
        pr.setProperty("expires", Long.toString(now + 7 * 24 * 3_600_000L));
        try (java.io.OutputStream o = Files.newOutputStream(k.dir().resolve("meta.properties"))) {
          pr.store(o, null);
        }
      } catch (IOException e) {
        System.err.println("[serve] could not keep the recordings' access: " + e);
      }
    }
    if (beam) {
      // Its build's hold starts now (a saved session's, or another running session's, keeps it anyway).
      catalog.released(catalog.buildHash(s.app.path()));
      Thread.ofVirtual().name("beam-sweep").start(this::sweepBeam);
    }
  }

  /** The agent's sweep (docs/beam.md): at its start, hourly, and as sessions start and end. */
  void sweepBeam() {
    // Moved data (phase 5) left by an agent that ended between unpacking it and its session: no grant or session owns
    // it, and no instance holds its lock.
    try (var pkgs = Files.newDirectoryStream(state.resolve("cache/data"))) {
      for (Path pkg : pkgs) {
        try (var slots = Files.newDirectoryStream(pkg, "bm*-0")) {
          for (Path slot : slots) {
            String name = slot.getFileName().toString().replaceFirst("-0$", "");
            if (!name.matches("bm[0-9a-f]{12}") || agent.ownsSeed(name) || sessions.values().stream().anyMatch(x -> name.equals(x.seedSlot))
                || locked(slot)) continue;
            SlotPack.dropSeed(state, name, null, System.err::println);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // no app data yet, or the next round
    }
    try {
      store.sweep(catalog, sha -> agent.holds(sha) || warmer.holds(sha)
          || sessions.values().stream().anyMatch(x -> sha.equals(catalog.buildHash(x.app.path()))));
    } catch (RuntimeException e) {
      System.err.println("[beam] sweep failed: " + e);
    }
  }

  /** Whether an instance holds this slot's lock now. */
  private static boolean locked(Path slot) {
    Path lock = slot.resolve(".lock");
    if (!Files.isRegularFile(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE)) {
      java.nio.channels.FileLock l = ch.tryLock();
      if (l == null) return true;
      l.release();
      return false;
    } catch (java.nio.channels.OverlappingFileLockException e) {
      return true;
    } catch (IOException e) {
      return true;  // unknown: left for the next round
    }
  }

  // ---------------------------------------------------------------- HTTP

  private void handle(Socket socket) {
    try (Socket s = socket) {
      s.setTcpNoDelay(true);
      // The request's head within 10 s in all (a byte at a time does not keep it open), then 30 s per read (uploads).
      s.setSoTimeout(10_000);
      InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 14);
      OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 14);
      Http.Request r = Http.read(in, System.nanoTime() + 10_000_000_000L);
      if (r == null) return;
      s.setSoTimeout(30_000);
      try {
        r = underBase(r, out);
      if (r == null) return;
      r = forwarded(r, s);
      route(r, s, in, out);
      } finally {
        Http.CORS_ORIGIN.remove();
      }
    } catch (IOException e) {
      // the client went away
    } catch (RuntimeException e) {
      System.err.println("[serve] request failed: " + e);
    }
  }

  private void route(Http.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
    if (!hostAllowed(r)) {
      Http.text(out, 403, "this server answers only to localhost");
      return;
    }
    if (beam) {
      beamRoute(r, s, in, out);
      return;
    }
    // An --allow-origin page's requests: their responses say it may read them (health included), and its preflights (a
    // JSON POST with a bearer token sends one, without the token) are answered before authentication. The request
    // itself still needs the token.
    String origin = r.header("origin");
    if (origin != null && allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
      Http.CORS_ORIGIN.set(origin);
      if (r.method().equals("OPTIONS")) {
        Http.respond(out, 204, "text/plain", new byte[0], "Access-Control-Allow-Methods: GET, POST, DELETE",
            "Access-Control-Allow-Headers: Authorization, Content-Type, X-Filename", "Access-Control-Max-Age: 600");
        return;
      }
    }
    // Health probes (Kubernetes, load balancers) cannot send a token: "ok" or why not, nothing more.
    if (r.method().equals("GET") && r.path().equals("/api/health")) {
      String why = notReady();
      Http.text(out, why == null ? 200 : 503, why == null ? "ok" : "not ready: " + why);
      return;
    }
    // The one-time link: keep the token as a cookie and take it out of the address bar. An embedded page (embed=1)
    // keeps it instead, in memory, and sends it with its own requests: a cross-site frame gets no SameSite cookie.
    if (r.method().equals("GET") && r.path().equals("/") && r.param("token") != null && !"1".equals(r.param("embed"))) {
      if (!tokenMatches(r.param("token"))) {
        Http.text(out, 401, "wrong token");
        return;
      }
      Map<String, String> rest = new LinkedHashMap<>(r.query());
      rest.remove("token");
      // A query-only reference: the page's own path as the browser addressed it, under a proxy's prefix or
      // --base-path too, with or without its trailing slash ("./" from /sim would go to the proxy's root).
      String to = "?" + Http.query(rest);
      Http.respond(out, 302, "text/plain", new byte[0], "Location: " + to,
          "Set-Cookie: apksim_token=" + r.param("token") + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000");
      return;
    }
    // A visitor's agent downloading the APK of the app its page is starting (docs/beam.md, phase 2): the ticket, which
    // only a page with the token gets, is the authorization, for that one build and five minutes.
    if (allowBeam && r.method().equals("GET") && r.path().equals("/api/beam/apk")) {
      beamDownload(r.param("ticket"), out);
      return;
    }
    // A session's data packed for its tab's Mac (phase 5): the ticket that tab got on its own socket, as for the APK.
    if (allowBeam && r.method().equals("GET") && r.path().equals("/api/beam/data")) {
      String[] t = tickets.check(r.param("ticket"));
      Path zip = t != null && t[0].matches("d[0-9a-f]{24}") ? state.resolve("serve/handoff").resolve(t[0] + ".zip") : null;
      if (zip == null || !Files.isRegularFile(zip, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !catalog.sha256(zip).equals(t[1])) {
        Http.text(out, 403, "no such data, or its ticket has expired");
        return;
      }
      Http.file(out, zip, "application/zip");
      return;
    }
    if (!authorized(r)) {
      Http.text(out, 401, "this server needs its token: open the link with ?token=... once (or send Authorization: Bearer ...)");
      return;
    }
    boolean write = !r.method().equals("GET") || r.path().equals("/ws");
    if (write && !originAllowed(r)) {
      Http.text(out, 403, "cross-origin request refused");
      return;
    }
    if (allowBeam && r.method().equals("GET") && r.path().equals("/api/beam")) {
      int[] pr = Beam.protocols();  // the page's own (it is this build's)
      Http.json(out, 200, "{\"port\":" + beamPort + ",\"protocol\":" + pr[1] + ",\"minProtocol\":" + pr[0] + "}");
      return;
    }
    if (allowBeam && r.method().equals("GET") && r.path().matches("/api/apps/[A-Za-z0-9]{1,40}/(beam|apk)")) {
      String[] parts = r.path().split("/");
      beamApp(parts[3], parts[4].equals("apk"), out);
      return;
    }
    if (r.method().equals("GET") && r.path().startsWith("/api/apps/")) {
      appState(r.path().substring("/api/apps/".length()), out);
      return;
    }
    if (r.method().equals("GET") && r.path().startsWith("/api/skins/")) {
      skinImage(r, r.path().substring("/api/skins/".length()), out);
      return;
    }
    if (r.method().equals("GET") && r.path().matches("/api/sessions/[0-9a-f]{12}/log")) {
      sessionLog(r.path().substring("/api/sessions/".length(), "/api/sessions/".length() + 12), out);
      return;
    }
    if (r.path().startsWith("/api/kept/") && (r.method().equals("GET") || r.method().equals("DELETE"))) {
      keptRoute(r, out);
      return;
    }
    java.util.regex.Matcher fm = java.util.regex.Pattern.compile("/api/sessions/([0-9a-f]{12})/(files|recordings/[0-9a-f]{12})").matcher(r.path());
    if (fm.matches()) {
      sessionFile(r, out, sessions.get(fm.group(1)), fm.group(2), fm.group(1));
      return;
    }
    switch (r.method() + " " + r.path()) {
      case "GET /" -> {
        String compare = r.param("compare");
        if (compare != null && !compare.matches("[A-Za-z0-9]{1,40},[A-Za-z0-9]{1,40}")) {
          Http.text(out, 400, "compare: two app ids, /?compare=BASE,NEW");
          return;
        }
        byte[] body = compare != null ? comparePage : page;
        // --allow-beam: the page also asks this Mac's agent (/beam/hello, /beam/start); its WebSocket is under ws: already.
        String agentSrc = allowBeam ? " http://127.0.0.1:" + beamPort : "";
        String csp = "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob: data:; connect-src 'self' ws: wss:"
            + agentSrc + "; base-uri 'none'; form-action 'none'; frame-ancestors ";
        // Framed only by the origins named (--allow-origin), and then without X-Frame-Options, which cannot name them.
        if (allowedOrigins.isEmpty()) Http.respond(out, 200, "text/html; charset=utf-8", body, csp + "'none'", "X-Frame-Options: DENY");
        else Http.respond(out, 200, "text/html; charset=utf-8", body, csp + "'self' " + String.join(" ", allowedOrigins));
      }
      case "GET /api/apps" -> {
        StringBuilder b = new StringBuilder("[");
        for (Catalog.App a : catalog.listed()) {
          if (b.length() > 1) b.append(',');
          b.append("{\"id\":").append(Http.q(a.id())).append(",\"name\":").append(Http.q(a.name())).append('}');
        }
        Http.json(out, 200, b.append(']').toString());
      }
      case "GET /api/devices" -> {
        StringBuilder b = new StringBuilder("[");
        for (Map.Entry<String, String> d : deviceSizes.entrySet()) {
          if (b.length() > 1) b.append(',');
          b.append("{\"name\":").append(Http.q(d.getKey())).append(",\"size\":").append(Http.q(d.getValue())).append('}');
        }
        // The stored skins beside them (issue 246): a picker shows "Pixel 9" for its device, and starts with skin=NAME.
        for (dev.apksim.skin.Skin k : skins()) {
          if (b.length() > 1) b.append(',');
          b.append("{\"name\":").append(Http.q(k.device)).append(",\"size\":").append(Http.q(skinSize(k))).append(",\"skin\":")
              .append(Http.q(k.name)).append(",\"title\":").append(Http.q(k.title)).append('}');
        }
        Http.json(out, 200, b.append(']').toString());
      }
      case "GET /api/skins" -> Http.json(out, 200, skinsJson());
      case "GET /api/status" -> Http.json(out, 200, status());
      case "POST /api/upload" -> upload(r, out);
      case "POST /api/apps" -> addApp(r, out);
      case "GET /ws" -> websocket(r, s, in, out);
      default -> Http.text(out, 404, "not found");
    }
  }

  /**
   * The report button and the network inspector (report.html: their cards and their script) put into the page at its
   * `apksim:report` markers. A page without the markers is served as it is.
   */
  private static String withReport(String html) throws IOException {
    try (InputStream in = Serve.class.getResourceAsStream("report.html")) {
      if (in == null) throw new IOException("report.html is missing from apksim.jar");
      String frag = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      int cut = frag.indexOf("<!--script-->");
      if (cut < 0) throw new IOException("report.html has no <!--script--> marker between its cards and its script");
      String cards = frag.substring(0, cut), script = "<script>" + frag.substring(cut + "<!--script-->".length()) + "</script>";
      return html.replace("<!--apksim:report-->", cards).replace("<!--apksim:report-script-->", script);
    }
  }

  /**
   * An `--issue-url` template: an https address with `{title}` and/or `{body}` where the page puts the report's title and
   * text (URL-encoded), e.g. `https://github.com/OWNER/REPO/issues/new?title={title}&body={body}`. No credentials in it,
   * no other braces.
   */
  static String checkIssueUrl(String t) {
    try {
      java.net.URI u = java.net.URI.create(t.replace("{title}", "T").replace("{body}", "B"));
      boolean ok = "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
          && (t.contains("{title}") || t.contains("{body}")) && !t.replace("{title}", "").replace("{body}", "").matches("(?s).*[{}].*")
          && t.length() <= 500;
      if (ok) return t;
    } catch (IllegalArgumentException e) {
      // refused below
    }
    System.err.println("[serve] --issue-url takes an https address with {title} and/or {body}, such as "
        + "https://github.com/OWNER/REPO/issues/new?title={title}&body={body}");
    System.exit(2);
    return null;
  }

  private void upload(Http.Request r, OutputStream out) throws IOException {
    long length = r.chunked() ? -1 : r.contentLength();
    if (length < 0 && !r.chunked()) {
      Http.text(out, 411, "send the APK as the request body, with Content-Length or Transfer-Encoding: chunked");
      return;
    }
    if (length > maxUploadMb << 20) {
      Http.text(out, 413, "too large: " + (length >> 20) + " MB (limit " + maxUploadMb + " MB)");
      return;
    }
    try {
      // The page sends the name URL-encoded (a header carries only Latin-1); Catalog keeps a plain-ASCII form of it.
      String name = r.header("x-filename");
      try {
        name = name == null ? null : java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        name = null;
      }
      long ttl = ttlParam(r.param("ttl"));
      Catalog.App a = catalog.upload(r.content(), length, name, List.of());
      if (ttl > 0) catalog.expireAt(a.id(), ttl);
      System.err.printf("[serve] upload %s (%d MB)%n", a.name(), Files.size(a.path()) >> 20);
      Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name()) + "}");
    } catch (IOException e) {
      Http.text(out, 400, e.getMessage());
    }
  }

  /** A `ttl` (seconds) from a query or a JSON number: 0 for none; anything but a whole number in range is a 400. */
  private static long ttlParam(Object v) throws IOException {
    if (v == null) return 0;
    long n;
    try {
      n = v instanceof Number x ? x.longValue() : Long.parseLong(v.toString().strip());
      if (v instanceof Number x && x.doubleValue() != n) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      throw new IOException("\"ttl\" is a whole number of seconds");
    }
    if (n < Catalog.TTL_MIN_S || n > Catalog.TTL_MAX_S) {
      throw new IOException("\"ttl\" is " + Catalog.TTL_MIN_S + " to " + Catalog.TTL_MAX_S + " seconds");
    }
    return n;
  }

  /**
   * `POST /api/apps` `{"url": "https://...", "name": "...", "headers": {...}, "options": ["--activity", ".Main", ...]}`
   * (`"ttl": SECONDS` too: the id expires then, used or not):
   * serve downloads the APK itself and answers its id at once (docs/serve.md).
   */
  private void addApp(Http.Request r, OutputStream out) throws IOException {
    if (r.contentLength() < 0 && !r.chunked()) {
      Http.text(out, 411, "send a JSON body, with Content-Length or Transfer-Encoding: chunked");
      return;
    }
    long length = r.chunked() ? -1 : r.contentLength();
    byte[] raw = r.content().readNBytes((int) Math.min(BODY_MAX + 1, length < 0 ? Long.MAX_VALUE : length));
    if (raw.length > BODY_MAX) {
      Http.text(out, 413, "the request body is limited to " + BODY_MAX / 1024 + " KB");
      return;
    }
    try {
      if (!(Json.parse(new String(raw, StandardCharsets.UTF_8)) instanceof Map<?, ?> body)) throw new IOException("expected a JSON object");
      if (!(body.get("url") instanceof String url)) throw new IOException("\"url\" is required");
      String name = body.get("name") instanceof String n ? n : null;
      Map<String, String> headers = new LinkedHashMap<>();
      if (body.get("headers") instanceof Map<?, ?> h) {
        for (Map.Entry<?, ?> e : h.entrySet()) {
          String k = String.valueOf(e.getKey());
          if (!(e.getValue() instanceof String v) || !k.matches("[A-Za-z0-9-]{1,64}") || v.chars().anyMatch(c -> c < 0x20)) {
            throw new IOException("\"headers\" takes names and string values");
          }
          headers.put(k, v);
        }
      } else if (body.get("headers") != null) {
        throw new IOException("\"headers\" must be an object");
      }
      List<String> given = new ArrayList<>();
      if (body.get("options") instanceof List<?> l) {
        for (Object o : l) {
          if (!(o instanceof String x)) throw new IOException("\"options\" takes strings");
          given.add(x);
        }
      } else if (body.get("options") != null) {
        throw new IOException("\"options\" must be an array, e.g. [\"--activity\", \".MainActivity\"]");
      }
      long ttl = ttlParam(body.get("ttl"));
      Catalog.App a = catalog.addUrl(url, name, headers, Catalog.launchOptions(given));
      if (ttl > 0) catalog.expireAt(a.id(), ttl);
      Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name()) + ",\"state\":\"downloading\"}");
    } catch (Catalog.Busy e) {
      Http.respond(out, 503, "text/plain; charset=utf-8", (e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8), "Retry-After: 30");
    } catch (IOException e) {
      Http.text(out, 400, e.getMessage());
    }
  }

  private Session.Kept keptOf(String id) {
    Object[] v = keptRecs.get(id);
    if (v != null) return (long) v[1] < System.currentTimeMillis() ? null : (Session.Kept) v[0];
    if (!id.matches("[0-9a-f]{12}")) return null;
    Path dir = logs.resolve("rec-" + id);
    java.util.Properties pr = new java.util.Properties();
    try (java.io.InputStream in = Files.newInputStream(dir.resolve("meta.properties"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      pr.load(in);
      long exp = Long.parseLong(pr.getProperty("expires", "0"));
      if (exp < System.currentTimeMillis()) return null;
      String key = pr.getProperty("key", ""), site = pr.getProperty("site", "");
      Session.Kept k = new Session.Kept(key.isEmpty() ? null : key, site.isEmpty() ? null : site,
          new java.util.HashSet<>(java.util.Arrays.asList(pr.getProperty("rids", "").split(","))), dir);
      keptRecs.put(id, new Object[] {k, exp});
      return k;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Issue 187: `POST /api/sessions/ID/files?path=` (a file into the running app) and `GET /api/sessions/ID/recordings/RID`
   * (a flight recording the tab saved with `rec save`). The same on the beam agent under `/beam/sessions/ID/...`. A session
   * that is not running (or not this caller's) is a 404.
   */
  private void sessionFile(Http.Request r, OutputStream out, Session s, String what, String sid) throws IOException {
    if (what.equals("files")) {
      if (s == null) {
        Http.text(out, 404, "no such session (or it has ended)");
        return;
      }
      if (!r.method().equals("POST")) {
        Http.text(out, 405, "POST the file as the request body");
        return;
      }
      long length = r.chunked() ? -1 : r.contentLength();
      if (length < 0 && !r.chunked()) {
        Http.text(out, 411, "send the file as the request body, with Content-Length or Transfer-Encoding: chunked");
        return;
      }
      try {
        Http.json(out, 200, s.pushFile(r.param("path"), r.content(), length));
      } catch (Session.PushRefused e) {
        Http.text(out, e.status, e.getMessage());
      } catch (IOException e) {
        Http.text(out, 500, e.getMessage());
      }
      return;
    }
    if (!r.method().equals("GET")) {
      Http.text(out, 405, "GET the recording");
      return;
    }
    String rid = what.substring("recordings/".length());
    Path f = null;
    if (s != null) f = s.recording(rid);
    else {
      Session.Kept k = keptOf(sid);
      Path c = k == null || !k.rids().contains(rid) ? null : k.dir().resolve(rid + ".apkrec");
      if (c != null && Files.isRegularFile(c, java.nio.file.LinkOption.NOFOLLOW_LINKS)) f = c;
    }
    if (f == null) {
      Http.text(out, 404, "no such recording yet (rec save answers with its id; the file appears when it is written)");
      return;
    }
    Http.file(out, f, "application/octet-stream");
  }

  /**
   * `GET /api/sessions/ID/log` (token required, like everything but health): the tail of a session's log as text,
   * which has the JVM crash report's header in it when the instance crashed. Bounded ({@link Crash#TAIL_BYTES}); the
   * id is the session's random one and the file is looked up by it alone. Kept as long as the log is (a week).
   */
  private void sessionLog(String id, OutputStream out) throws IOException {
    Path f = logs.resolve(id + ".log");
    if (!Files.isRegularFile(f)) {
      Http.text(out, 404, "no such session log (logs are kept for a week)");
      return;
    }
    List<String> lines = Crash.tail(f, Crash.TAIL_LINES, Crash.TAIL_BYTES);
    Http.respond(out, 200, "text/plain; charset=utf-8", (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- skins (issue 246, docs/serve.md "Skins")

  /** This server's skins (the agent's: the visitor's own), every one that reads and whose device resolves. */
  static List<dev.apksim.skin.Skin> skins() {
    List<dev.apksim.skin.Skin> out = new ArrayList<>();
    try {
      for (dev.apksim.skin.Skin k : dev.apksim.skin.Skins.all(null)) {
        try {
          k.spec();
          out.add(k);
        } catch (IOException e) {
          // a device this build cannot resolve: not offered
        }
      }
    } catch (IOException e) {
      // no store
    }
    return out;
  }

  /** The skin of that name this server has, or null. */
  static dev.apksim.skin.Skin skin(String name) {
    if (name == null || !dev.apksim.skin.Skin.NAME.matcher(name).matches()) return null;
    for (dev.apksim.skin.Skin k : skins()) if (k.name.equals(name)) return k;
    return null;
  }

  /**
   * Launch options with `--skin NAME` replaced by that skin's `--device` (unless they name one): for a launch that cannot
   * read the skin store (a beamed instance or warm-up, whose sandbox reads nothing in the home folder) or may not have the
   * skin (a Mac start of a server's app). A skin this server does not have is just dropped.
   */
  static List<String> skinAsDevice(List<String> given) {
    List<String> launch = new ArrayList<>(given);
    int si = launch.lastIndexOf("--skin");
    if (si < 0 || si + 1 >= launch.size()) return launch;
    dev.apksim.skin.Skin k = skin(launch.get(si + 1));
    for (int i = launch.size() - 2; i >= 0; i--) {
      if (launch.get(i).equals("--skin")) {
        launch.remove(i + 1);
        launch.remove(i);
      }
    }
    int di = launch.lastIndexOf("--device");
    String device = di >= 0 && di + 1 < launch.size() ? launch.get(di + 1) : null;
    // The skin's device, unless the options name another (which keeps its own kind). Named or not, the skin's own
    // device gets its kind (a custom-size watch is a watch), which the size alone would not give; an explicit
    // --device-kind stays as it is.
    if (k != null && (device == null || device.equals(k.device))) {
      if (device == null) launch.addAll(List.of("--device", k.device));
      if (k.kind != null && !launch.contains("--device-kind")) launch.addAll(List.of("--device-kind", k.kind));
    }
    return launch;
  }

  /** "411x914@2.625": the skin's device as dp and density. */
  static String skinSize(dev.apksim.skin.Skin k) {
    try {
      dev.apksim.DeviceSpec d = k.spec();
      return d.widthDp() + "x" + d.heightDp() + "@" + (d.density() == (int) d.density() ? String.valueOf((int) d.density()) : String.valueOf(d.density()));
    } catch (IOException e) {
      return k.device;
    }
  }

  /** `GET /api/skins` (`/beam/skins` on the agent): what a page needs to draw each frame around its canvas. */
  static String skinsJson() {
    StringBuilder b = new StringBuilder("[");
    for (dev.apksim.skin.Skin k : skins()) {
      try {
        String j = k.apiJson();
        if (b.length() > 1) b.append(',');
        b.append(j);
      } catch (IOException e) {
        // its frame is missing or not a PNG: not offered
      }
    }
    return b.append(']').toString();
  }

  /**
   * `GET /api/skins/NAME/frame.png` (frame-land.png, mask.png): a skin's image, revalidated by an ETag of its contents
   * (a page caches it; a changed skin is fetched again).
   */
  static void skinImage(Http.Request r, String rest, OutputStream out) throws IOException {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-z0-9-]+)/(frame\\.png|frame-land\\.png|mask\\.png)").matcher(rest);
    dev.apksim.skin.Skin k = m.matches() ? skin(m.group(1)) : null;
    Path f = k == null ? null : k.image(m.group(2));
    if (f == null) {
      Http.text(out, 404, "no such skin image");
      return;
    }
    byte[] body = Files.readAllBytes(f);
    String etag;
    try {
      etag = "\"" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body), 0, 16) + "\"";
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
    if (etag.equals(r.header("if-none-match"))) {
      Http.respond(out, 304, "image/png", new byte[0], "ETag: " + etag, "Cache-Control: private, no-cache");
      return;
    }
    Http.respond(out, 200, "image/png", body, "ETag: " + etag, "Cache-Control: private, no-cache");
  }

  /** `GET /api/apps/ID`: an uploaded or added app's state (downloading, ready, failed), for a service that added it. */
  private void appState(String id, OutputStream out) throws IOException {
    Catalog.Download d = catalog.download(id);
    if (d != null && catalog.expiresAt(id) > 0 && catalog.expiresAt(id) < System.currentTimeMillis()) d = null;  // past its ttl
    if (d != null) {
      StringBuilder b = new StringBuilder("{\"id\":").append(Http.q(id)).append(",\"name\":").append(Http.q(d.app.name()));
      if (Session.appDevice(d.app) != null) b.append(",\"device\":").append(Http.q(Session.appDevice(d.app)));
      if (!d.done.isDone()) b.append(",\"state\":\"downloading\"");
      else if (d.done.isCompletedExceptionally()) {
        String why;
        try {
          d.done.join();
          why = "";
        } catch (java.util.concurrent.CompletionException e) {
          why = String.valueOf(e.getCause().getMessage());
        }
        b.append(",\"state\":\"failed\",\"error\":").append(Http.q(why));
      } else b.append(",\"state\":\"ready\"");
      Http.json(out, 200, b.append(",\"received\":").append(d.received()).append(",\"total\":").append(d.total())
          .append(expiry(id)).append(",\"cache\":\"none\"}").toString());
      return;
    }
    Catalog.App a = catalog.find(id);
    if (a == null) {
      Http.text(out, 404, "no such app (an upload is kept for a day after its last use)");
      return;
    }
    String detail = warmer.detail(a);
    Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name())
        + (Session.appDevice(a) != null ? ",\"device\":" + Http.q(Session.appDevice(a)) : "")
        // Its own --device rather than its skin's: a page naming the app's skin to the agent names this device with it.
        + (a.launch().contains("--device") ? ",\"deviceGiven\":true" : "")
        + (Session.appSkin(a) != null ? ",\"skin\":" + Http.q(Session.appSkin(a)) : "") + ",\"state\":\"ready\",\"cache\":"
        + Http.q(warmer.state(a)) + (detail.isEmpty() ? "" : ",\"cacheDetail\":" + Http.q(detail)) + expiry(a.id()) + "}");
  }

  /** `,"expiresInS":N` for an app given a `ttl`, else nothing. */
  private String expiry(String id) {
    long at = catalog.expiresAt(id);
    return at > 0 ? ",\"expiresInS\":" + Math.max(0, (at - System.currentTimeMillis()) / 1000) : "";
  }

  /** Why a session could not start now (the launcher, the state folder, disk space), or null when it could. */
  private String notReady() {
    if (!Files.isExecutable(launcher)) return "no launcher at " + launcher;
    Path dir = state.resolve(home);
    // Where sessions write: their logs, uploads and downloads (each made at startup).
    for (Path d : List.of(dir, logs, dir.resolve("uploads"), dir.resolve("downloads"))) {
      if (!Files.isDirectory(d) || !Files.isWritable(d)) return "the state folder " + d + " is not writable";
    }
    try {
      long free = Files.getFileStore(dir).getUsableSpace();
      // An instance's data slot and caches, and an upload of the largest size allowed.
      long need = (512L << 20) + (maxUploadMb << 20);
      if (free < need) return "low disk space: " + (free >> 20) + " MB free in " + dir + ", need " + (need >> 20);
    } catch (IOException e) {
      return "cannot read the disk's free space: " + e.getMessage();
    }
    return null;
  }

  private void websocket(Http.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
    websocket(r, s, in, out, null, null);
  }

  /**
   * Issue 221: `GET /api/kept/KEY` (what this token's key kept, per package: build, size, when saved and last used) and
   * `DELETE /api/kept/KEY` (forgets it all; `409` while a session has it). The key is the client's; only its HMAC under
   * this server's token is stored, so another token's key of the same name is another entry.
   */
  private void keptRoute(Http.Request r, OutputStream out) throws IOException {
    String key = r.path().substring("/api/kept/".length());
    if (!KeptStore.validKey(key)) {
      Http.text(out, 400, "a key is 1-128 letters, digits and . _ ~ -, starting with a letter or digit");
      return;
    }
    // Only the server that owns the store (KeptStore.claim) answers: another on the same state folder knows nothing of
    // the owner's sessions, so it could delete a key one of them holds.
    if (!kept.owned()) {
      Http.text(out, 503, "this server keeps no sessions (--keep-max, or another server on this state folder keeps them)");
      return;
    }
    String id = kept.id(key);
    if (r.method().equals("GET")) {
      Http.json(out, 200, kept.describe(id));
      return;
    }
    int n = kept.forget(id);
    if (n < 0) Http.text(out, 409, "a session is using this key: end it first");
    else if (n == 0) Http.text(out, 404, "nothing is kept under this key");
    else Http.json(out, 200, "{\"forgot\":" + n + "}");
  }

  /**
   * Serve's seed slots (`cache/data/<package>/ks<hex>-0`: kept or restored data) that no instance holds and no session
   * of this server names, untouched for an hour: left by a server that stopped between unpacking and its session's end.
   * Startup and hourly, on every server sharing the state folder. A session refreshes its slot's time as its instance
   * ends (Session.keepData), so another server's sweep never takes one being packed.
   */
  private void dropUnusedSeeds() {
    try (var pkgs = Files.newDirectoryStream(state.resolve("cache/data"))) {
      for (Path pkg : pkgs) {
        try (var slots = Files.newDirectoryStream(pkg, "ks*-0")) {
          for (Path slot : slots) {
            String name = slot.getFileName().toString().replaceFirst("-0$", "");
            if (!name.matches("ks[0-9a-f]{12}") || sessions.values().stream().anyMatch(x -> name.equals(x.seedSlot)) || locked(slot)) continue;
            if (System.currentTimeMillis() - Files.getLastModifiedTime(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() < 3_600_000L) continue;
            // Only with its hold (a session of any server keeps it from naming the slot until it is packed and erased),
            // and checked again under it.
            synchronized (seedHolds) {
              try (java.nio.channels.FileChannel hold = seedHold(name)) {
                if (hold == null || locked(slot) || !Files.isDirectory(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                SlotPack.dropSeed(state, name, null, System.err::println);
              }
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // no app data yet, or the next round
    }
    // Holds whose slot is gone (a session's ends with its slot; one that stopped leaves the file).
    try (var holds = Files.newDirectoryStream(state.resolve(home + "/seeds"), "ks*.lock")) {
      for (Path h : holds) {
        String name = h.getFileName().toString().replaceFirst("\\.lock$", "");
        if (!name.matches("ks[0-9a-f]{12}")) continue;
        synchronized (seedHolds) {
          try (java.nio.channels.FileChannel hold = seedHold(name)) {
            if (hold == null) continue;
            boolean gone;
            try (var pkgs = Files.list(state.resolve("cache/data"))) {
              gone = pkgs.noneMatch(p -> Files.exists(p.resolve(name + "-0"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            }
            if (gone) Files.deleteIfExists(h);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // none yet
    }
  }

  /**
   * Serve's seed slots (`ks`) are held, across every server sharing the state folder, by an exclusive lock on
   * `serve/seeds/<name>.lock` from before the slot is made until its session has packed and erased it: the instance's
   * own lock on the slot ends with the instance, before that. {@link #dropUnusedSeeds} deletes a slot only with its hold.
   */
  private final Map<String, java.nio.channels.FileChannel> seedHolds = new ConcurrentHashMap<>();

  /**
   * The hold on a seed slot's name, or null when another holds it (a session here or on another server). Never opens
   * the file of a name this server holds: closing any channel to a file can release every lock the JVM has on it
   * (POSIX), so a sweep's open and close would drop a running session's hold. Callers hold {@link #seedHolds}.
   */
  private java.nio.channels.FileChannel seedHold(String name) throws IOException {
    if (seedHolds.containsKey(name)) return null;
    Path dir = state.resolve(home + "/seeds");
    Files.createDirectories(dir);
    java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.resolve(name + ".lock"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
    try {
      if (ch.tryLock() != null) return ch;
    } catch (java.nio.channels.OverlappingFileLockException e) {
      // this process holds it
    }
    ch.close();
    return null;
  }

  /** Takes the hold for a session's seed slot (kept sessions and restores); false when it cannot. */
  boolean holdSeed(String name) {
    synchronized (seedHolds) {
      return holdSeedLocked(name);
    }
  }

  private boolean holdSeedLocked(String name) {
    try {
      java.nio.channels.FileChannel ch = seedHold(name);
      if (ch == null) return false;
      seedHolds.put(name, ch);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Gives a seed slot's hold back, once its session has packed and erased it (or it was never used). */
  void releaseSeed(String name) {
    if (name == null) return;
    synchronized (seedHolds) {
      releaseSeedLocked(name);
    }
  }

  private void releaseSeedLocked(String name) {
    java.nio.channels.FileChannel ch = seedHolds.get(name);
    if (ch == null) return;
    try {
      // Its slot is gone: the file goes while still held (a sweeper that opened it meanwhile then finds no slot).
      Files.deleteIfExists(state.resolve(home + "/seeds").resolve(name + ".lock"));
    } catch (IOException e) {
      // the sweep removes it
    }
    try {
      ch.close();
    } catch (IOException e) {
      // released with the channel either way
    }
    seedHolds.remove(name);  // only now: a sweep must not open its file while this channel still holds it
  }

  /** `site`: the agent's session's page origin (beam), else null; `seed`: its moved data's slot (phase 5), else null. */
  private void websocket(Http.Request r, Socket s, InputStream in, OutputStream out, String site, String seed) throws IOException {
    String key = r.header("sec-websocket-key");
    if (key == null || !"websocket".equalsIgnoreCase(r.header("upgrade"))) {
      Http.text(out, 400, "expected a WebSocket upgrade");
      return;
    }
    // Issue 221: a kept session's key (`keep=KEY`), and what a start does when another session has it (`keepBusy=fresh`,
    // the default: it runs without the kept data and keeps nothing; `refuse`: 409, before the upgrade).
    String keepKey = beam ? null : r.param("keep");
    if (keepKey != null && keepKey.isEmpty()) keepKey = null;
    if (keepKey != null && !KeptStore.validKey(keepKey)) {
      Http.text(out, 400, "keep: a key is 1-128 letters, digits and . _ ~ -, starting with a letter or digit");
      return;
    }
    boolean refuseBusy = "refuse".equals(r.param("keepBusy"));
    String keepId = keepKey == null || !kept.on() ? null : kept.id(keepKey);
    // Reserved before the upgrade, so two starts on one key cannot both pass: the loser gets 409 (refuse) or runs
    // without the kept data. Released below unless the session took it (it releases it when it ends).
    boolean took = keepId != null && kept.take(keepId);
    // The page's Start again, or a reload, opens the next session while the last one on the key still ends (its tab's
    // close may not even have arrived yet) and packs its data: waited for, 3 s for the close to come, then as long as
    // the key's session is ending (60 s at most), so the next one starts on what it kept rather than without it.
    long waitFrom = System.currentTimeMillis();
    while (keepId != null && !took) {
      long waited = System.currentTimeMillis() - waitFrom;
      if (waited > 60_000 || waited > 3_000 && !keyEnding(keepId)) break;
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      took = kept.take(keepId);
    }
    // The ending session gives the key back just before it leaves the session list, and counts against --max until
    // then: a restart on a full server (--max 1) would otherwise be told every session is in use.
    while (took && keyEnding(keepId) && System.currentTimeMillis() - waitFrom < 60_000) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (keepId != null && !took && refuseBusy) {
      Http.text(out, 409, "a session is using this key");
      return;
    }
    boolean[] handed = {false};
    try {
      upgraded(r, s, in, out, site, seed, key, keepKey, keepId, took, handed);
    } finally {
      if (took && !handed[0]) kept.release(keepId);
    }
  }

  /** Whether the session holding this kept key is ending (it gives the key back once its data is packed). */
  private boolean keyEnding(String keepId) {
    return sessions.values().stream().anyMatch(x -> keepId.equals(x.keepId) && x.ending());
  }

  /** The rest of a session's start, once its key (if any) is reserved; `handed[0]` set once a session holds it. */
  private void upgraded(Http.Request r, Socket s, InputStream in, OutputStream out, String site, String seed, String key,
      String keepKey, String keepId, boolean took, boolean[] handed) throws IOException {
    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
        + WebSocket.acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    s.setSoTimeout(0);
    WebSocket ws = new WebSocket(s, in, out);
    Catalog.App app = catalog.find(r.param("app"));
    if (app == null) {
      if (seed != null) {
        reopened.remove(seed);
        reopenedChanged.remove(seed);
      }
      SlotPack.dropSeed(state, seed, null, System.err::println);  // its moved data has no session to go to
      releaseSeed(seed);
      ws.sendText("x no such app (an upload is kept for a day after its last use)");
      ws.close("no such app");
      return;
    }
    String restore = r.param("restore");
    if (!beam && allowBeam && restore != null && seed == null) {
      try {
        seed = restoreSeed(restore, app);
        ws.sendText("r its data is back on the server: it starts with what it had saved");
      } catch (IOException e) {
        ws.sendText("r could not restore its data (" + e.getMessage() + "): it starts without it");
      }
    }
    Map<String, String> options = new LinkedHashMap<>();
    for (String k : List.of("device", "appearance", "locale", "fontscale", "open", "orientation", "permissions", "codec")) {
      String v = r.param(k);
      if (v != null && !v.isEmpty()) options.put(k, v);
    }
    // A skin (issue 246): one of this server's (the agent's: the visitor's), never a path; its device unless the page
    // names one. `off` starts without the app's own --skin.
    // The device's kind the page's skin gives (a custom-size watch), for a device this server does not have a skin for.
    String kindParam = r.param("kind");
    if (kindParam != null && kindParam.matches("phone|foldable|tablet|desktop|tv|watch")) options.put("kind", kindParam);
    String skinParam = r.param("skin");
    if ("off".equals(skinParam)) options.put("skin", "off");
    else if (skinParam != null && !skinParam.isEmpty()) {
      dev.apksim.skin.Skin k = skin(skinParam);
      if (k == null) {
        ws.sendText("r skin: no skin named " + skinParam.replaceAll("[^a-z0-9-]", "?") + " here: it starts without one");
        options.put("skin", "off");  // without one: not the app's own either
      } else {
        options.put("skin", k.name);
      }
    }
    // A reopened saved session (issue 181) starts as it was saved (device, appearance, locale, font scale, permissions),
    // as `apksim beam run` does, unless the page names them.
    Map<String, Object> reopenedRec = beam && seed != null && reopened.containsKey(seed) ? store.saved(reopened.get(seed)) : null;
    if (reopenedRec != null) {
      for (String k : List.of("device", "appearance", "locale", "fontscale", "permissions")) {
        if (reopenedRec.get(k) instanceof String v && !v.isEmpty()) options.putIfAbsent(k, v);
      }
    }
    // A size a Fill tab gave the session it was saved from (`WxH@D`): within the bounds a beam tab's `device` command has.
    boolean savedSize = reopenedRec != null && options.get("device") != null && options.get("device").equals(reopenedRec.get("device"))
        && Session.checkBeam("device " + options.get("device")) != null;
    // Only a device this server names (built in, --device-size, or the app's own default), never a client's own string;
    // anything else, or none, is the app's default device, else the phone.
    String device = options.get("device");
    if (device != null && !savedSize && !Session.deviceAllowed(this, app, device)) options.remove("device");
    // A reopened saved session (issue 181) runs only while it holds its saved session's lock: without it, it would be a
    // copy nothing puts back (its app's writes erased when it ends). A `dataFrom` copy of the same session reading it
    // holds that lock for a moment (within one JVM even two shared locks on a file collide): waited for, briefly.
    String reopenSid = beam && seed != null ? reopened.get(seed) : null;
    BeamStore.SavedHold reopenLock = reopenSid == null ? null : holdSaved(reopenSid);
    if (reopenSid != null && reopenLock == null) {
      reopened.remove(seed);
      reopenedChanged.remove(seed);
      SlotPack.dropSeed(state, seed, null, System.err::println);
      releaseSeed(seed);
      ws.sendText("x saved session " + reopenSid + " is busy on this Mac (being copied for a newer build, or forgotten): start it again");
      ws.close("busy");
      return;
    }
    Session session;
    synchronized (sessions) {
      if (sessions.size() >= max) {
        if (reopenLock != null) reopenLock.close();
        if (seed != null) {
          reopened.remove(seed);
          reopenedChanged.remove(seed);
        }
        SlotPack.dropSeed(state, seed, null, System.err::println);
        releaseSeed(seed);
        ws.sendText("x all " + max + " sessions are in use: try again in a minute");
        ws.close("busy");
        return;
      }
      byte[] id = new byte[6];
      RANDOM.nextBytes(id);
      session = new Session(this, HexFormat.of().formatHex(id), app, options, ws);
      session.seedSlot = seed;  // the agent's moved data, or the server's restored data
      if (keepKey != null) {
        if (keepId == null) session.keptState = "off";
        else if (took) {
          session.keepId = keepId;  // released when the session ends (Session.eraseData)
          handed[0] = true;
        }
        else session.keptState = "busy";
      }
      if (beam) {
        session.site = site;
        session.keep = store.config.saveSessions();
        if (reopenSid != null) {
          // Issue 181: a reopened saved session, held (shared, as `run` holds it) until it ends.
          session.savedId = reopenSid;
          session.savedLock = reopenLock;
          session.savedChanged = reopenedChanged.getOrDefault(seed, -1L);
          session.keep = true;
        }
      }
      sessions.put(session.id, session);
    }
    if (beam) agent.settled(catalog.buildHash(app.path()));
    System.err.printf("[serve] session %s: %s on %s%n", session.id, app.name(), session.device);
    ws.sendText("i {\"id\":" + Http.q(session.id) + ",\"app\":" + Http.q(app.name()) + ",\"device\":" + Http.q(session.device)
        + ",\"idleLimitS\":" + idleSeconds + (beam ? ",\"keep\":" + session.keep : "")
        // What the page may offer: the report (a recording, needs --allow-rec) and the network inspector.
        + (beam ? ",\"rec\":" + (session.recChannels() != null) + (session.recChannels() != null ? ",\"recChannels\":" + Http.q(session.recChannels()) : "")
            + ",\"inspect\":" + session.inspecting()
            : ",\"rec\":" + allowRec + (issueUrl != null ? ",\"issueUrl\":" + Http.q(issueUrl) : "") + ",\"inspect\":" + inspectNetwork) + "}");
    session.run();
  }

  // ---------------------------------------------------------------- beam (docs/beam.md)

  /**
   * `--allow-beam`: what the page tells this Mac's agent about an app (`GET /api/apps/ID/beam`: name, size, SHA-256,
   * launch options), and the APK itself (`/apk`), which the page passes on when the agent does not have it. Only for
   * an app this token holder could open a session on anyway.
   */
  private void beamApp(String id, boolean bytes, OutputStream out) throws IOException {
    Catalog.App a = catalog.find(id);
    if (a == null) {
      Http.text(out, 404, "no such app (an upload is kept for a day after its last use)");
      return;
    }
    if (catalog.downloading(a)) {
      Http.text(out, 409, "this app is still downloading to the server: try again when it is ready");
      return;
    }
    Path apk;
    try {
      apk = catalog.file(a, what -> {}, () -> false);
    } catch (IOException e) {
      Http.text(out, 409, e.getMessage());
      return;
    }
    if (bytes) {
      Http.file(out, apk, "application/vnd.android.package-archive");
      return;
    }
    // The app's options as the Mac's agent takes them. A --skin is this server's: the Mac may not have it, so it goes as
    // the skin's device (unless the app names one); the page draws the server's frame either way (issue 246).
    List<String> launch = skinAsDevice(a.launch());
    StringBuilder opts = new StringBuilder("[");
    for (String o : launch) opts.append(opts.length() > 1 ? "," : "").append(Http.q(o));
    String sha = catalog.sha256(apk);
    Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name()) + ",\"size\":" + Files.size(apk)
        + ",\"sha256\":" + Http.q(sha) + ",\"options\":" + opts.append(']') + ",\"ticket\":" + Http.q(tickets.mint(a.id(), sha))
        + ",\"ticketSeconds\":" + tickets.lifetimeSeconds + "}");
  }

  /** `GET /api/beam/apk?ticket=T`: the APK a valid ticket names, while that app's file is still that build. */
  private void beamDownload(String ticket, OutputStream out) throws IOException {
    String[] t = tickets.check(ticket);
    Catalog.App a = t == null ? null : catalog.find(t[0]);
    if (a == null || catalog.downloading(a)) {
      Http.text(out, 403, "no such ticket, or it has expired (each lasts " + tickets.lifetimeSeconds / 60 + " minutes): start again");
      return;
    }
    Path apk;
    try {
      apk = catalog.file(a, what -> {}, () -> false);
    } catch (IOException e) {
      Http.text(out, 409, e.getMessage());
      return;
    }
    if (!catalog.sha256(apk).equals(t[1])) {
      Http.text(out, 409, "this app's file has changed since the ticket was made: start again");
      return;
    }
    System.err.printf("[serve] beam: %s downloaded by a visitor's agent%n", a.name());
    Http.file(out, apk, "application/vnd.android.package-archive");
  }

  /**
   * The saved session's lock (`saved/<id>.lock`) held shared on a channel of its own, for a reopened session's life;
   * tried for up to 10 s (a copy for a newer build holds it while it reads the slot, `forget` exclusively). Null when it
   * cannot be had, or the saved session is gone.
   */
  private BeamStore.SavedHold holdSaved(String id) {
    long until = System.currentTimeMillis() + 10_000;
    while (true) {
      try {
        BeamStore.SavedHold h = store.hold(id, false);
        if (h != null) {
          // Checked again once held: `forget` (which holds it exclusively) may have erased it just before.
          if (store.saved(id) != null) return h;
          h.close();
          return null;
        }
      } catch (IOException e) {
        // try again
      }
      if (System.currentTimeMillis() > until) return null;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }

  /** A start's answer for saved data: which saved session it reopened (`saved`) or copied (`dataFrom`). */
  private static String localAnswer(String local) {
    if (local == null) return "";
    String id = local.substring(local.indexOf(' ') + 1);
    return local.startsWith("@saved ") ? ",\"saved\":" + Http.q(id) : ",\"dataFrom\":" + Http.q(id);
  }

  /** Whether a saved session is open in a tab now (its copy held by a grant or a running session). */
  boolean savedOpen(String id) {
    for (var e : List.copyOf(reopened.entrySet())) {
      String seed = e.getKey();
      boolean live = agent.ownsSeed(seed) || sessions.values().stream().anyMatch(x -> seed.equals(x.seedSlot));
      if (!live && reopened.remove(seed, e.getValue())) reopenedChanged.remove(seed);  // its grant expired or its session ended
      else if (e.getValue().equals(id)) return true;
    }
    return false;
  }

  /**
   * Issue 181: a saved session's data copied into a slot of the session's own (`bm<hex>-0`), as a moved session's is:
   * packed from the saved slot (`k<id>-0` and its `.device`, through folder handles, links left out) and unpacked by
   * {@link #seedFrom}, which checks it is the build's package. `@saved ID` reopens it (the copy goes back over the saved
   * slot when the session ends: {@link Session}); `@from ID` starts a build on a copy, which leaves the saved session as
   * it was, and only when the build is the same package signed by the same key as the saved one, as Android requires of
   * an update that keeps an app's data. The slot's name, or throws why not.
   */
  private String savedSeed(String origin, String local, Path apk) throws IOException {
    if (!local.startsWith("@saved ")) return copySaved(origin, local, apk);
    synchronized (reopened) {  // two tabs reopening one saved session at once: one gets it
      String id = local.substring(local.indexOf(' ') + 1);
      if (savedOpen(id)) throw new IOException("saved session " + id + " is open in another tab on this Mac: close it first");
      return copySaved(origin, local, apk);
    }
  }

  private String copySaved(String origin, String local, Path apk) throws IOException {
    boolean reopen = local.startsWith("@saved ");
    String id = local.substring(local.indexOf(' ') + 1);
    Map<String, Object> rec = store.saved(id);
    if (rec == null || !origin.equals(rec.get("site"))) throw new IOException("saved session " + id + " is gone");
    String pkg = String.valueOf(rec.get("package"));
    if (!pkg.matches("[A-Za-z0-9._]{1,255}")) throw new IOException("saved session " + id + " names no app");
    if (!reopen) {
      // Not while a tab has it open (an upload grant made before that tab's start reaches here only now): the copy would
      // be of data about to change, and its read would hold the lock that tab's session takes.
      if (savedOpen(id)) throw new IOException("saved session " + id + " is open in another tab on this Mac: close it first");
      String own = dev.apksim.ApkCache.manifestPackage(apk);
      if (!pkg.equals(own)) {
        throw new IOException("this build is " + own + ", saved session " + id + " is " + pkg + "'s data: an app starts only on its own");
      }
      List<String> saved = new ArrayList<>();
      if (rec.get("signers") instanceof List<?> l) for (Object x : l) saved.add(String.valueOf(x));
      String savedSha = rec.get("sha256") instanceof String h ? h : "";
      if (saved.isEmpty() && catalog.hasBuild(savedSha)) saved = ApkSigners.of(catalog.buildFile(savedSha)).certs();
      if (saved.isEmpty()) throw new IOException("saved session " + id + " does not say who signed its build, and that build is gone");
      ApkSigners.Signers now;
      try {
        now = ApkSigners.of(apk);
      } catch (IOException e) {
        throw new IOException("this build's signer cannot be read (" + e.getMessage() + ")");
      }
      if (!now.certs().equals(saved)) {
        throw new IOException("this build is signed by another key than saved session " + id + "'s (an update keeps the app's data"
            + " only when the same key signs it: debug builds made on different machines have different debug keys)");
      }
    }
    Path slot = state.resolve("cache/data").resolve(pkg).resolve("k" + id + "-0");
    if (!Files.isDirectory(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("saved session " + id + " has no data on this Mac");
    Path dir = state.resolve("beam/import");
    Files.createDirectories(dir);
    Path zip = dir.resolve("saved-" + Long.toHexString(System.nanoTime()) + ".zip");
    // Held while it is read: forget and a write-back wait (the session's hold), and a `run` opening it meanwhile takes
    // another slot instead of writing this one (its slot's lock). One copy of a saved session at a time here: a second
    // channel on the slot's lock, closed, would release the first's (POSIX).
    try (BeamStore.SavedHold shared = store.hold(id, false)) {
      if (shared == null) throw new IOException("it is busy (being forgotten, or a tab's session is being put back into it): try again");
      synchronized (store.copyMonitor(id)) {
        return copyHeld(origin, id, rec, slot, pkg, apk, zip, reopen);
      }
    } catch (IOException | RuntimeException e) {
      throw new IOException("saved session " + id + "'s data could not be copied: " + e.getMessage());
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  private String copyHeld(String origin, String id, Map<String, Object> rec, Path slot, String pkg, Path apk, Path zip, boolean reopen)
      throws IOException {
    // Checked again now that it is held: `forget` may have erased it since it was read.
    if (store.saved(id) == null || !Files.isDirectory(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("it is gone");
    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(slot.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE)) {
      java.nio.channels.FileLock own;
      try {
        own = ch.tryLock();
      } catch (java.nio.channels.OverlappingFileLockException e) {
        own = null;
      }
      if (own == null) throw new IOException("it is open on this Mac (apksim beam run): quit it first");
      try (OutputStream f = new java.io.BufferedOutputStream(Files.newOutputStream(zip, java.nio.file.StandardOpenOption.CREATE_NEW))) {
        SlotPack.pack(state, slot, pkg, f);
      }
      long changedAt = BeamStore.changed(store.saved(id));  // under the slot's lock: no run is writing it now
      String name = seedFrom(zip, apk, agent.importing::add, agent.importing::remove);
      if (reopen) {
        reopenedChanged.put(name, changedAt);
        reopened.put(name, id);
      }
      store.used(id);
      System.err.printf("[beam] %s: saved session %s's data (%d KB) %s into %s%n", origin, id, Files.size(zip) >> 10,
          reopen ? "reopened" : "copied for a newer build", name);
      return name;
    }
  }

  /**
   * Phase 5: a moved session's data (SlotPack) from the page's server, with its ticket, unpacked into a slot of its own
   * (`cache/data/<pkg>/bm<hex>-0` and its .device), which the session's instance then runs in without --fresh. The
   * slot's name, or throws why not. `spec` is "TICKET URL".
   */
  private String beamSeed(String origin, String spec, Path apk) throws IOException {
    String[] v = spec.split(" ", 2);
    if (v.length != 2) throw new IOException("bad data address");
    java.net.URI u = pageAddress(origin, v[1], "/api/beam/data");
    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(10)).build();
    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(u.resolve(u.getRawPath() + "?ticket="
            + java.net.URLEncoder.encode(v[0], StandardCharsets.UTF_8))).timeout(java.time.Duration.ofMinutes(10)).GET().build();
    Path dir = state.resolve("beam/import");
    Files.createDirectories(dir);
    Path zip = dir.resolve("data-" + Long.toHexString(System.nanoTime()) + ".zip");
    try {
      java.net.http.HttpResponse<Path> resp;
      try {
        resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(zip));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted");
      }
      if (resp.statusCode() != 200) throw new IOException("the server answered " + resp.statusCode() + " for the data");
      if (Files.size(zip) > SlotPack.MAX_BYTES) throw new IOException("the data is too large");
      return seedPack(origin, zip, apk, "");
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  /**
   * Phase 5 behind a sign-in (issue 173): the pack the agent could not download, sent by the tab, which fetched it with
   * its own credentials (`POST /beam/upload-data`). Taken as a download is: at most {@link SlotPack#MAX_BYTES}, then
   * every check of {@link #seedFrom}. The slot's name, or throws why not.
   */
  private String seedUpload(String origin, InputStream body, long length, Path apk) throws IOException {
    if (length > SlotPack.MAX_BYTES) throw new IOException("the data is too large");
    Path dir = state.resolve("beam/import");
    Files.createDirectories(dir);
    Path zip = dir.resolve("data-" + Long.toHexString(System.nanoTime()) + ".zip");
    try {
      try (java.io.OutputStream f = Files.newOutputStream(zip, java.nio.file.StandardOpenOption.CREATE_NEW)) {
        // Only the request's own body (a known length is not read past: the connection's next bytes are not its).
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (length < 0 || total < length) {
          int n = body.read(buf, 0, (int) (length < 0 ? buf.length : Math.min(buf.length, length - total)));
          if (n < 0) break;
          total += n;
          if (total > SlotPack.MAX_BYTES) throw new IOException("the data is too large");
          f.write(buf, 0, n);
        }
        if (length >= 0 && total < length) throw new IOException("the data ended early");
      }
      return seedPack(origin, zip, apk, " (sent by the tab)");
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  /** A moved session's pack, downloaded or sent, into a slot of its own; the slot's name. */
  private String seedPack(String origin, Path zip, Path apk, String how) throws IOException {
    // Until its grant owns it (the caller makes it next), the sweep leaves it.
    String name = seedFrom(zip, apk, agent.importing::add, agent.importing::remove);
    System.err.printf("[beam] %s: a moved session's data (%d KB) into %s%s%n", origin, Files.size(zip) >> 10, name, how);
    return name;
  }

  /**
   * Phase 5's answer when the agent could not download the session's data (issue 173): to a page in protocol 2 or
   * later, `need: "data"` with a grant for the tab to send the pack (`dataUpload`) and why (`dataWhy`); to an older
   * page, which would read that as a session grant, the 502 it always got. `via`: how the build came, if it did.
   */
  private void dataNeeded(OutputStream out, String o, int protocol, Catalog.App a, String sha, List<String> launch, String via,
      IOException e) throws IOException {
    if (protocol < 2) {
      Http.text(out, 502, "the session's data did not reach this Mac: " + e.getMessage());
      return;
    }
    System.err.println("[beam] could not download " + a.name() + "'s moved data from " + o + ": " + e.getMessage() + " (the tab is asked)");
    Http.json(out, 200, "{\"need\":\"data\",\"dataUpload\":" + Http.q(agent.grant(Beam.Kind.DATA, o, a.id(), sha, a.name(), launch, null, protocol))
        + (via == null ? "" : ",\"via\":" + Http.q(via)) + ",\"dataWhy\":" + Http.q(e.getMessage()) + "}");
  }

  /**
   * A hand-off pack unpacked into a fresh slot (`cache/data/<package>/bm<12 hex>-0`) for a session of `apk`: on the
   * agent (a moved session), or on the server (a move the Mac could not take, restored). Only the build's own package
   * (from its manifest), only into a plain app folder. `claim` and `unclaim` are told the slot's name around the
   * unpack (the agent's sweep must leave it meanwhile). The slot's name, or throws why not.
   */
  String seedFrom(Path zip, Path apk, java.util.function.Consumer<String> claim, java.util.function.Consumer<String> unclaim)
      throws IOException {
    return seedFrom(zip, apk, "bm", claim, unclaim);
  }

  /**
   * As above, into a slot named `<prefix><12 hex>`: `bm` for moved and restored data (the agent's sweep knows those),
   * `ks` for serve's kept sessions, which only serve's own sweep looks at (an agent sharing the state folder must not
   * take a kept session's slot for one of its own left behind).
   */
  String seedFrom(Path zip, Path apk, String prefix, java.util.function.Consumer<String> claim, java.util.function.Consumer<String> unclaim)
      throws IOException {
    String pkg = SlotPack.packageOf(zip);
    if (pkg == null) throw new IOException("the data names no app");
    // Only into the build's own app folder: a pack naming another package (the owner's own app, say) would otherwise
    // put a site's files in that app's data folder.
    String own = dev.apksim.ApkCache.manifestPackage(apk);
    if (!pkg.equals(own)) throw new IOException("the data is " + pkg + "'s, not this app's (" + own + ")");
    byte[] rnd = new byte[6];
    RANDOM.nextBytes(rnd);
    String name = prefix + HexFormat.of().formatHex(rnd);
    // Into the app's own folder, never through a link: beamed instances may make an app's folder there (the sandbox
    // lets them make a folder only, but one made before that rule, or by anything else, is checked here).
    Path data = state.resolve("cache/data"), pkgDir = data.resolve(pkg);
    Files.createDirectories(pkgDir);
    if (!Files.isDirectory(data, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(pkgDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || !pkgDir.toRealPath().equals(data.toRealPath().resolve(pkg))) {
      throw new IOException("the app's data folder is not a plain folder: " + pkgDir);
    }
    // Held (ks) only now, past the checks that can throw: from here every failure gives it back.
    if (prefix.equals("ks") && !holdSeed(name)) throw new IOException("its slot is held elsewhere: try again");
    claim.accept(name);
    try {
      SlotPack.unpack(zip, pkgDir.resolve(name + "-0"));
    } catch (IOException | RuntimeException e) {
      unclaim.accept(name);
      releaseSeed(name);
      throw e;
    }
    return name;
  }

  /**
   * A hand-off's data back on the server (docs/beam.md phase 5, "a move that fails"): the tab moved a session to its
   * Mac, the app quit here and its data was packed, then the Mac could not take it. The tab opens a session here again
   * with the hand-off's ticket (`restore=`), and the pack it names (kept until the ticket expires) is unpacked into
   * that session's own slot, then deleted: used once. The slot's name, or throws why not.
   */
  private String restoreSeed(String ticket, Catalog.App app) throws IOException {
    String[] t = tickets.check(ticket);
    if (t == null || !t[0].matches("d[0-9a-f]{24}")) throw new IOException("its ticket has expired");
    Path zip = state.resolve("serve/handoff").resolve(t[0] + ".zip");
    synchronized (this) {  // one restore per pack
      if (!Files.isRegularFile(zip, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !catalog.sha256(zip).equals(t[1])) {
        throw new IOException("its data is gone (restored already, or expired)");
      }
      try {
        // Serve's own namespace (ks): an agent sharing the state folder sweeps bm slots it does not know.
        return seedFrom(zip, app.path(), "ks", n -> {}, n -> {});
      } finally {
        Files.deleteIfExists(zip);
      }
    }
  }

  /**
   * The agent's routes (`apksim beam`). Any page may ask `/beam/hello` (it says only whether that page's origin is
   * allowed); `/beam/start` runs the prompt for an origin not yet allowed, and answers a session grant for an APK the
   * agent has by its hash, else an upload grant; `/beam/upload` takes the APK once and checks its hash;
   * `/beam/upload-data` a moved session's data the agent could not download (protocol 2); `/ws` opens the session for
   * its grant only. Every grant is for one origin and one use.
   */
  private void beamRoute(Http.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
    String sent = r.header("origin");
    String o = sent == null ? null : origin(sent);
    // A page's own answers only, so any page may read them (each says nothing beyond what that page may know).
    if (o != null && o.equals(sent)) Http.CORS_ORIGIN.set(o);  // only an origin that is already its validated form
    if (r.method().equals("OPTIONS")) {
      // The browser's preflight for the JSON start and the upload; and Private Network Access's, for browsers that send it.
      Http.respond(out, 204, "text/plain", new byte[0], "Access-Control-Allow-Methods: GET, POST",
          "Access-Control-Allow-Headers: Content-Type, X-Filename", "Access-Control-Allow-Private-Network: true",
          "Access-Control-Max-Age: 600");
      return;
    }
    if (r.method().equals("GET") && r.path().equals("/beam/hello")) {
      int[] pr = Beam.protocols();
      Http.json(out, 200, "{\"apksim\":" + Http.q(Beam.version(root)) + ",\"protocol\":" + pr[1] + ",\"minProtocol\":" + pr[0] + ",\"trusted\":"
          + (o != null && agent.trusted(o)) + ",\"asking\":" + agent.asking(o) + ",\"relay\":true}");
      return;
    }
    // `apksim beam status` (docs/beam.md, phase 6): to a local tool only, never a page (a browser always sends Origin).
    if (sent == null && r.method().equals("GET") && r.path().equals("/beam/status")) {
      statusRequest.set(true);
      StringBuilder b = new StringBuilder("{\"port\":" + port + ",\"max\":" + max + ",\"keeps\":" + Http.q(store.config.describe())
          + ",\"sessions\":[");
      for (Session x : sessions.values()) {
        if (b.charAt(b.length() - 1) != '[') b.append(',');
        b.append("{\"id\":").append(Http.q(x.id)).append(",\"app\":").append(Http.q(x.app.name())).append(",\"site\":")
            .append(Http.q(String.valueOf(x.site))).append(",\"device\":").append(Http.q(String.valueOf(x.device)))
            .append(",\"keep\":").append(x.keep).append(",\"pid\":").append(x.pid).append(",\"idleS\":")
            .append((System.currentTimeMillis() - x.lastInput) / 1000).append('}');
      }
      Http.json(out, 200, b.append("]}").toString());
      return;
    }
    if (o == null) {
      Http.text(out, 403, "the agent runs apps for browser pages only (a request with its page's Origin)");
      return;
    }
    java.util.regex.Matcher fm = java.util.regex.Pattern.compile("/beam/sessions/([0-9a-f]{12})/(files|recordings/[0-9a-f]{12})").matcher(r.path());
    if (fm.matches()) {
      // Issue 187: this page's own session only: its origin, and the key the session sent that tab on its socket.
      Session x = sessions.get(fm.group(1));
      Session.Kept k = x != null ? x.kept() : keptOf(fm.group(1));
      String fk = x != null ? x.fileKey : k == null ? null : k.key();
      String site = x != null ? x.site : k == null ? null : k.site();
      String key = r.param("key");
      if (fk == null || key == null || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
          fk.getBytes(StandardCharsets.UTF_8)) || !o.equals(site)) {
        Http.text(out, 404, "no such session (or it has ended)");
        return;
      }
      sessionFile(r, out, x, fm.group(2), fm.group(1));
      return;
    }
    // The visitor's own skins (issue 246): to a site they trust only, as their private frames are their own. The page
    // draws the frame around its canvas; nothing is uploaded anywhere.
    if (r.method().equals("GET") && (r.path().equals("/beam/skins") || r.path().startsWith("/beam/skins/"))) {
      if (!agent.trusted(o)) {
        Http.text(out, 403, "this Mac's skins are for sites it trusts (apksim beam trust allow ORIGIN)");
        return;
      }
      if (r.path().equals("/beam/skins")) Http.json(out, 200, skinsJson());
      else skinImage(r, r.path().substring("/beam/skins/".length()), out);
      return;
    }
    switch (r.method() + " " + r.path()) {
      case "POST /beam/start" -> beamStart(r, o, out);
      case "GET /beam/saved" -> {
        // Issue 181: what this site may start on (its own saved sessions only), so a page can offer "start signed in".
        StringBuilder b = new StringBuilder("[");
        if (!"never".equals(agent.trust().get(o))) {
          for (Map<String, Object> rec : store.savedFrom(o)) {
            if (b.length() > 1) b.append(',');
            String sha = rec.get("sha256") instanceof String h ? h : "";
            b.append("{\"id\":").append(Http.q(String.valueOf(rec.get("id")))).append(",\"app\":").append(Http.q(String.valueOf(rec.get("app"))))
                .append(",\"package\":").append(Http.q(String.valueOf(rec.get("package")))).append(",\"sha256\":").append(Http.q(sha))
                .append(",\"build\":").append(catalog.hasBuild(sha)).append(",\"open\":").append(savedOpen(String.valueOf(rec.get("id"))))
                .append(",\"saved\":").append(BeamStore.whole(rec.get("saved"))).append(",\"lastUsed\":").append(BeamStore.whole(rec.get("lastUsed")))
                .append('}');
          }
        }
        Http.json(out, 200, b.append(']').toString());
      }
      case "POST /beam/upload" -> {
        Beam.Grant g = agent.take(r.param("upload"), Beam.Kind.UPLOAD, o);
        if (g == null) {
          Http.text(out, 403, "no such upload (each start's lasts two minutes and is used once): start again");
          return;
        }
        long length = r.chunked() ? -1 : r.contentLength();
        if (length < 0 && !r.chunked()) {
          Http.text(out, 411, "send the APK as the request body");
          return;
        }
        Catalog.App a;
        try {
          // The hash checked before the file is stored or the warmer hears of it (it would run it).
          a = catalog.upload(r.content(), length, g.name(), g.launch(), g.sha256());
        } catch (IOException e) {
          Http.text(out, 400, e.getMessage());
          return;
        }
        String got = g.sha256();
        System.err.printf("[beam] %s sent %s (%d MB)%n", o, a.name(), Files.size(a.path()) >> 20);
        String seed = null;
        if (g.extra() != null && g.extra().startsWith("@")) {
          try {
            seed = savedSeed(o, g.extra(), a.path());
          } catch (IOException e) {
            Http.text(out, 409, e.getMessage());
            return;
          }
        } else if (g.extra() != null) {
          try {
            seed = beamSeed(o, g.extra(), a.path());
          } catch (IOException e) {
            dataNeeded(out, o, g.protocol(), a, got, a.launch(), null, e);
            return;
          }
        }
        Http.json(out, 200, "{\"app\":" + Http.q(a.id()) + ",\"token\":" + Http.q(agent.grant(Beam.Kind.SESSION, o, a.id(), got, a.name(), a.launch(), seed))
            + (seed == null ? "" : ",\"data\":true") + (g.extra() != null && g.extra().startsWith("@") ? localAnswer(g.extra()) : "") + "}");
      }
      case "POST /beam/upload-data" -> {
        // Issue 173: the pack the agent could not download (the page's server behind a sign-in), sent by the tab.
        Beam.Grant g = agent.take(r.param("upload"), Beam.Kind.DATA, o);
        if (g == null) {
          Http.text(out, 403, "no such data upload (each lasts two minutes and is used once): start again");
          return;
        }
        Catalog.App a = catalog.find(g.app());
        if (a == null || !Files.isRegularFile(a.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          Http.text(out, 410, "this Mac no longer has the app's build: start again");
          return;
        }
        long length = r.chunked() ? -1 : r.contentLength();
        if (length < 0 && !r.chunked()) {
          Http.text(out, 411, "send the data as the request body");
          return;
        }
        String seed;
        try {
          seed = seedUpload(o, r.content(), length, a.path());
        } catch (IOException | RuntimeException e) {  // a malformed zip may throw either
          Http.text(out, 400, "the session's data was refused: " + e.getMessage());
          return;
        }
        Http.json(out, 200, "{\"app\":" + Http.q(a.id()) + ",\"token\":" + Http.q(agent.grant(Beam.Kind.SESSION, o, a.id(), g.sha256(), a.name(), g.launch(), seed))
            + ",\"data\":true}");
      }
      case "GET /ws" -> {
        String why = Beam.protocolProblem(r.param("protocol"));
        if (why != null) {
          Http.text(out, 409, why);  // the grant stays for a page that asks again in one this agent speaks
          return;
        }
        Beam.Grant g = agent.take(r.param("token"), Beam.Kind.SESSION, o);
        if (g == null || !g.app().equals(r.param("app"))) {
          Http.text(out, 403, "this session was not started by this page (start it again)");
          return;
        }
        websocket(r, s, in, out, o, g.extra());
      }
      default -> Http.text(out, 404, "not found");
    }
  }

  /**
   * `POST /beam/start {"sha256", "name", "size", "options", "ticket", "url", "source", "data"}`: the prompt (for a new
   * origin), then a grant. A build the agent lacks comes from `source` when its owner allows that address (issue 177),
   * else from the page's server with the ticket, else the tab sends it (`need: upload`, with `why`).
   */
  /** JSON bodies of addApp and beamStart: room for 256 KB of --sdcard-file contents as base64, and the rest. */
  private static final int BODY_MAX = 400 * 1024;

  private void beamStart(Http.Request r, String o, OutputStream out) throws IOException {
    long length = r.chunked() ? -1 : r.contentLength();
    byte[] raw = r.content().readNBytes((int) Math.min(BODY_MAX + 1, length < 0 ? Long.MAX_VALUE : length));
    if (raw.length > BODY_MAX) {
      Http.text(out, 413, "the request body is limited to " + BODY_MAX / 1024 + " KB");
      return;
    }
    String sha, name, ticket, from, data, source, local = null;
    long size;
    int protocol;
    List<String> launch;
    try {
      if (!(Json.parse(new String(raw, StandardCharsets.UTF_8)) instanceof Map<?, ?> body)) throw new IOException("expected a JSON object");
      ticket = body.get("ticket") instanceof String t && t.length() <= 300 ? t : null;
      from = body.get("url") instanceof String u && u.length() <= 2000 ? u : null;
      source = body.get("source") instanceof String u && u.length() <= 4000 ? u : null;
      // A ticket as the server mints them (BeamTicket: letters, digits, '.', '_', '-'): nothing else, so a site's ticket
      // can never be read as the agent's own marker for saved data ("@saved ID", "@from ID") once it rides in a grant.
      data = body.get("data") instanceof Map<?, ?> d && d.get("ticket") instanceof String dt && dt.matches("[A-Za-z0-9._-]{1,300}")
          && d.get("url") instanceof String du && du.length() <= 2000 && !du.contains(" ") ? dt + " " + du : null;
      // Issue 181: a saved session reopened (`saved`), or a build started on a copy of one's data (`dataFrom`).
      for (String k : List.of("saved", "dataFrom")) {
        Object v = body.get(k);
        if (v == null) continue;
        if (!(v instanceof String id) || !id.matches("[0-9a-f]{12}")) throw new IOException("\"" + k + "\" takes a saved session's id (GET /beam/saved)");
        if (local != null || data != null) throw new IOException("\"saved\", \"dataFrom\" and \"data\" each name the session's data: only one");
        local = (k.equals("saved") ? "@saved " : "@from ") + id;
      }
      Map<String, Object> rec = null;
      if (local != null) {
        rec = store.saved(local.substring(local.indexOf(' ') + 1));
        // Only the site it came from: another learns nothing, not even that it exists.
        if (rec == null || !o.equals(rec.get("site"))) {
          Http.text(out, 404, "no saved session " + local.substring(local.indexOf(' ') + 1) + " from " + o + " on this Mac (GET /beam/saved lists them)");
          return;
        }
      }
      Object h0 = body.get("sha256");
      if (h0 == null && local != null && local.startsWith("@saved ") && rec.get("sha256") instanceof String rh) h0 = rh;  // its own build
      if (!(h0 instanceof String h) || !h.matches("[0-9a-f]{64}")) throw new IOException("\"sha256\" (64 hex digits) is required");
      sha = h;
      if (local != null && local.startsWith("@saved ") && !sha.equals(rec.get("sha256"))) {
        throw new IOException("saved session " + rec.get("id") + " is another build's: start this build on a copy of its data with \"dataFrom\"");
      }
      Object proto = body.get("protocol");
      String why = Beam.protocolProblem(proto == null ? null : proto instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(proto));
      if (why != null) {
        Http.text(out, 409, why);
        return;
      }
      protocol = proto == null ? 1 : Integer.parseInt(proto instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(proto));
      name = body.get("name") instanceof String n ? n : rec != null && local.startsWith("@saved ") && rec.get("app") instanceof String an ? an : null;
      size = body.get("size") instanceof Number n ? n.longValue() : -1;
      List<String> given = new ArrayList<>();
      // A reopened session runs with the options it was saved with unless the page names them.
      boolean fromSaved = body.get("options") == null && rec != null && local.startsWith("@saved ");
      Object opts = fromSaved ? rec.get("launch") : body.get("options");
      // Issue 276: the tab relay is the start's own opt-in, never carried over from a saved session's options.
      if (fromSaved && opts instanceof List<?> ol && ol.contains("--tab-relay")) opts = ol.stream().filter(x -> !"--tab-relay".equals(x)).toList();
      if (opts instanceof List<?> l) {
        for (Object x : l) {
          if (!(x instanceof String str)) throw new IOException("\"options\" takes strings");
          given.add(str);
        }
      }
      launch = Catalog.launchOptions(given);
      Object relay = body.get("relay");
      if (relay != null && !(relay instanceof Boolean)) throw new IOException("\"relay\" takes true or false");
      if (Boolean.TRUE.equals(relay)) {
        if (protocol < 3) {
          Http.text(out, 409, "\"relay\" is protocol 3's: name \"protocol\": 3 (GET /beam/hello says \"relay\": true when this Mac's apksim has it)");
          return;
        }
        launch = new ArrayList<>(launch);
        launch.add("--tab-relay");  // the agent's own: Catalog.launchOptions does not let a page name it
      }
    } catch (IOException | RuntimeException e) {
      Http.text(out, 400, String.valueOf(e.getMessage()));
      return;
    }
    if (local != null && savedOpen(local.substring(local.indexOf(' ') + 1))) {
      Http.text(out, 409, "saved session " + local.substring(local.indexOf(' ') + 1) + " is open in another tab on this Mac: close it first");
      return;
    }
    if (size > maxUploadMb << 20) {
      Http.text(out, 413, "too large: " + (size >> 20) + " MB (this Mac takes up to " + maxUploadMb + " MB)");
      return;
    }
    if (!agent.allowed(o, name, size)) {
      Http.text(out, 403, "this Mac's owner did not allow " + o + " to run apps here");
      return;
    }
    Catalog.App a = catalog.byHash(sha, name, launch);
    String via = "cache";
    String why = null;
    String label = name == null ? sha.substring(0, 12) : name;
    if (a == null && source != null) {
      // Issue 177: from where the page says the build is (an artifact store), only if this Mac's owner allowed it.
      java.net.URI u = agent.downloadAllowed(source);
      if (u == null) {
        why = "this Mac's owner has not allowed downloads from that address on " + sourceSite(source)
            + " (apksim beam config allow-download URL)";
      } else {
        try {
          a = beamFetchSource(o, u, sha, name, size, launch);
          via = "source";
        } catch (IOException e) {
          why = "from " + sourceSite(source) + ": " + e.getMessage();
        }
      }
      if (a == null) System.err.println("[beam] could not download " + label + " for " + o + ": " + why);
    }
    if (a == null && ticket != null && from != null) {
      // Phase 2: from the page's own server, with the ticket it was given; the tab relays it only if this fails.
      try {
        a = beamFetch(o, from, ticket, sha, name, size, launch);
        via = "download";
      } catch (IOException e) {
        why = (why == null ? "" : why + "; ") + (source == null ? "" : "from the page's server: ") + e.getMessage();
        System.err.println("[beam] could not download " + label + " from " + o + ": " + e.getMessage());
      }
    }
    if (a != null) {
      String seed = null;
      if (local != null) {
        try {
          seed = savedSeed(o, local, a.path());
        } catch (IOException e) {
          Http.text(out, 409, e.getMessage());
          sweepBeam();
          return;
        }
      } else if (data != null) {
        try {
          seed = beamSeed(o, data, a.path());
        } catch (IOException e) {
          dataNeeded(out, o, protocol, a, sha, launch, via, e);
          sweepBeam();  // after the answer, with its data grant holding the build
          return;
        }
      }
      Http.json(out, 200, "{\"app\":" + Http.q(a.id()) + ",\"token\":" + Http.q(agent.grant(Beam.Kind.SESSION, o, a.id(), sha, a.name(), launch, seed))
          + ",\"via\":" + Http.q(via) + (seed == null ? "" : ",\"data\":true") + localAnswer(local) + "}");
      sweepBeam();  // after the answer, with this start's grant holding its build
      return;
    }
    Http.json(out, 200, "{\"need\":\"upload\",\"upload\":" + Http.q(agent.grant(Beam.Kind.UPLOAD, o, null, sha, name, launch, local != null ? local : data, protocol))
        + (why == null ? "" : ",\"why\":" + Http.q(why)) + "}");
  }

  /**
   * The agent downloads a build from the server the page came from (docs/beam.md, phase 2): only from that page's own
   * origin (a page could make it fetch nothing it could not fetch itself), only `/api/beam/apk` with the ticket, no
   * redirects (a sign-in page behind single sign-on is a failure, not a place to go), an APK's content type, within
   * --max-upload, and the hash the page named checked before the file is stored. Throws why it could not.
   */
  /** A download address on the page's own site and at this path only (the page could fetch it itself), or throws. */
  private static java.net.URI pageAddress(String origin, String from, String path) throws IOException {
    java.net.URI u;
    try {
      u = new java.net.URI(from);
    } catch (java.net.URISyntaxException e) {
      throw new IOException("not a URL");
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null
        || u.getRawQuery() != null || u.getRawFragment() != null || u.getRawPath() == null || !u.getRawPath().endsWith(path)) {
      throw new IOException("not the server's address for it");
    }
    int port = u.getPort();
    boolean defaultPort = port == -1 || scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443;
    String own = scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    if (!own.equals(origin.toLowerCase(Locale.ROOT))) throw new IOException("the address is not the page's own site (" + own + ")");
    return u;
  }

  private Catalog.App beamFetch(String origin, String from, String ticket, String sha, String name, long size, List<String> launch)
      throws IOException {
    java.net.URI u = pageAddress(origin, from, "/api/beam/apk");
    return beamDownloadApk(origin, u.resolve(u.getRawPath() + "?ticket=" + java.net.URLEncoder.encode(ticket, StandardCharsets.UTF_8)),
        null, false, sha, name, size, launch);
  }

  /**
   * Issue 177: a build from where the page says it is (`source`, an artifact store the Mac reaches), on an address this
   * Mac's owner allowed (`apksim beam config allow-download`). As from the page's server, but a redirect is followed
   * when it stays on an allowed address (artifact stores hand out signed addresses), and a store's generic binary type
   * is taken too (the hash, checked before anything is stored, says what the file is). No credentials are sent.
   */
  private Catalog.App beamFetchSource(String origin, java.net.URI u, String sha, String name, long size, List<String> launch)
      throws IOException {
    return beamDownloadApk(origin, u, agent::downloadAllowed, true, sha, name, size, launch);
  }

  /** The site of a page's `source`, for messages (never its path or query, which may carry a signature). */
  private static String sourceSite(String source) {
    try {
      java.net.URI u = new java.net.URI(source);
      return u.getHost() == null ? "that address" : u.getHost();
    } catch (java.net.URISyntaxException e) {
      return "that address";
    }
  }

  /**
   * Downloads an APK into the catalog (its hash checked before it is stored). `hop`: whether a redirect may go to an
   * address (the parsed address, or null), up to 5; null follows none. `anyBinary`: also takes
   * application/octet-stream. Throws why not.
   */
  private Catalog.App beamDownloadApk(String origin, java.net.URI u, java.util.function.Function<String, java.net.URI> hop,
      boolean anyBinary, String sha, String name, long size, List<String> launch) throws IOException {
    if (size > maxUploadMb << 20) throw new IOException("too large");
    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(10)).build();
    for (int hops = 0; ; hops++) {
      java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(u).timeout(java.time.Duration.ofMinutes(10))
          .header("Accept", "application/vnd.android.package-archive" + (anyBinary ? ", application/octet-stream" : "")).GET().build();
      java.net.http.HttpResponse<InputStream> resp;
      try {
        resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted");
      }
      try (InputStream body = resp.body()) {
        int code = resp.statusCode();
        if (hop != null && List.of(301, 302, 303, 307, 308).contains(code)) {
          String loc = resp.headers().firstValue("location").orElse(null);
          if (loc == null) throw new IOException("the server answered " + code + " with no address");
          if (hops >= 5) throw new IOException("too many redirects");
          java.net.URI next;
          try {
            // A query-only address (`?sig=...`, a store refreshing a signature) keeps the path: URI.resolve would drop
            // its last segment (RFC 2396), asking for the folder instead of the build.
            String cur = u.toString(), base = cur.indexOf('?') < 0 ? cur : cur.substring(0, cur.indexOf('?'));
            next = hop.apply(loc.startsWith("?") ? base + loc : u.resolve(new java.net.URI(loc)).toString());
          } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            next = null;
          }
          if (next == null) throw new IOException("the server redirected to an address this Mac does not download from");
          u = next;
          continue;
        }
        if (code != 200) throw new IOException("the server answered " + code);
        String type = resp.headers().firstValue("content-type").orElse("");
        if (!(type.startsWith("application/vnd.android.package-archive") || anyBinary && type.startsWith("application/octet-stream"))) {
          throw new IOException("the server sent " + (type.isEmpty() ? "no content type" : type) + ", not an APK");
        }
        long length = resp.headers().firstValueAsLong("content-length").orElse(-1);
        if (length > maxUploadMb << 20) throw new IOException("too large: " + (length >> 20) + " MB");
        Catalog.App a = catalog.upload(body, length, name, launch, sha);
        System.err.printf("[beam] %s: downloaded %s (%d MB) itself%s%n", origin, a.name(), Files.size(a.path()) >> 20,
            hop != null ? " from " + u.getHost() : "");
        return a;
      }
    }
  }

  /**
   * The cache state of each app the catalog lists (`--apps`), for `/api/status`. Never an upload's or URL add's id:
   * those are unlisted capabilities, shown only to whoever has them (`GET /api/apps/ID`).
   */
  private String catalogCaches() {
    StringBuilder b = new StringBuilder("[");
    for (Catalog.App a : catalog.listed()) {
      if (b.length() > 1) b.append(',');
      b.append("{\"id\":").append(Http.q(a.id())).append(",\"name\":").append(Http.q(a.name()))
          .append(",\"cache\":").append(Http.q(warmer.state(a)));
      String d = warmer.detail(a);
      if (!d.isEmpty()) b.append(",\"detail\":").append(Http.q(d));
      b.append('}');
    }
    return b.append(']').toString();
  }

  private String status() {
    StringBuilder b = new StringBuilder("{\"max\":").append(max).append(",\"idleLimitS\":").append(idleSeconds)
        .append(",\"maxUploadMb\":").append(maxUploadMb).append(",\"uptimeS\":").append((System.currentTimeMillis() - startedAt) / 1000)
        .append(",\"warmLaunches\":").append(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"))
        .append(",\"apps\":").append(catalogCaches()).append(",\"sessions\":[");
    boolean first = true;
    for (Session s : sessions.values()) {
      if (!first) b.append(',');
      first = false;
      b.append(s.json());
    }
    return b.append("]}").toString();
  }

  // ---------------------------------------------------------------- who may ask

  /** On a loopback bind, only requests addressed to a loopback name (a DNS-rebinding page names its own host). */
  private boolean hostAllowed(Http.Request r) {
    if (!loopbackBind) return true;
    if (r.headers().containsKey(PROXIED)) return true;  // a loopback proxy's request (--trust-proxy): see forwarded()
    String host = r.header("host");
    if (host == null) return false;
    // The public origin's own host (--public-origin), as a proxy that keeps Host sends it.
    if (publicOrigin != null && publicOrigin.equals(origin(publicOrigin.substring(0, publicOrigin.indexOf(':')) + "://" + host))) return true;
    String h = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.replaceFirst(":\\d+$", "");
    return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.endsWith(".localhost");
  }

  /**
   * A browser names the page a request came from: refuse WebSockets and POSTs from any page but this server's (its
   * Host, the forwarded host and scheme from a trusted proxy, or `--public-origin`) and the `--allow-origin` ones.
   */
  private boolean originAllowed(Http.Request r) {
    String origin = r.header("origin");
    if (origin == null) return true;  // not a browser (curl, scripts): nothing to forge
    String o = origin.toLowerCase(Locale.ROOT);
    if (o.equals(publicOrigin)) return true;
    String fwHost = r.header(PROXIED);
    if (fwHost != null) {
      String proto = first(r.header("x-forwarded-proto"));
      // In the browser's form, as Origin is sent: X-Forwarded-Host app.example.com:443 over https is https://app.example.com.
      String forwarded = origin((proto == null ? "http" : proto.toLowerCase(Locale.ROOT)) + "://" + fwHost);
      if (forwarded != null && o.equals(forwarded)) return true;
      // Through a proxy, the upstream Host (127.0.0.1:8080) is not the page's origin: only the forwarded one, the
      // public one and the allowed ones are.
      return allowedOrigins.contains(o);
    }
    String host = r.header("host");
    // This server's own page: compared in the browser's form (a client may send Host localhost:80 for localhost).
    if (host != null && (o.equals(origin("http://" + host)) || o.equals(origin("https://" + host)))) return true;
    return allowedOrigins.contains(o);
  }

  /** Set on a request a trusted loopback proxy forwarded: the browser's host, from X-Forwarded-Host. */
  private static final String PROXIED = "x-apksim-forwarded-host";

  /**
   * `--trust-proxy`: a request from a loopback peer that carries X-Forwarded-Host came through a proxy beside serve.
   * Its forwarded host is what the browser addressed (Origin is checked against it), and the loopback Host check does
   * not apply. From any other peer the headers are ignored, so a remote client cannot claim them; a header of that name
   * sent by anyone is dropped first.
   */
  private Http.Request forwarded(Http.Request r, Socket s) {
    r.headers().remove(PROXIED);
    if (!trustProxy || !s.getInetAddress().isLoopbackAddress()) return r;
    String host = first(r.header("x-forwarded-host"));
    if (host == null || host.isEmpty() || !host.matches("[A-Za-z0-9.:\\[\\]-]{1,255}")) return r;
    r.headers().put(PROXIED, host);
    return r;
  }

  /** The first of a comma-separated header's values (a chain of proxies appends theirs). */
  private static String first(String v) {
    if (v == null) return null;
    int comma = v.indexOf(',');
    return (comma < 0 ? v : v.substring(0, comma)).strip();
  }

  /**
   * `--base-path`: the request with the prefix taken off its path, or null when it was answered here (outside the
   * prefix: 404; the prefix alone: a redirect to it with a slash, which the page's relative URLs need). A proxy that
   * strips the prefix itself works too: a request without it is taken as is.
   */
  private Http.Request underBase(Http.Request r, OutputStream out) throws IOException {
    if (basePath.isEmpty()) return r;
    String p = r.path();
    if (p.equals(basePath)) {
      Http.respond(out, 302, "text/plain", new byte[0], "Location: " + basePath + "/" + (r.query().isEmpty() ? "" : "?" + Http.query(r.query())));
      return null;
    }
    if (p.startsWith(basePath + "/")) {
      return new Http.Request(r.method(), p.substring(basePath.length()), r.query(), r.headers(), r.body());
    }
    return r;
  }

  private boolean authorized(Http.Request r) {
    if (token == null) return true;
    String auth = r.header("authorization");
    if (auth != null && auth.startsWith("Bearer ") && tokenMatches(auth.substring(7).strip())) return true;
    String cookie = r.cookie("apksim_token");
    if (cookie != null && tokenMatches(cookie)) return true;
    // An embedded page's own requests (embed=1 keeps the token out of cookies: see route).
    return tokenMatches(r.param("token"));
  }

  private boolean tokenMatches(String given) {
    return token != null && given != null
        && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- memory

  /** Resident memory of a process in MB (Linux: /proc; macOS: ps), or -1. */
  static int rssMb(long pid) {
    if (pid <= 0) return -1;
    try {
      Path status = Path.of("/proc/" + pid + "/status");
      if (Files.exists(status)) {
        for (String line : Files.readAllLines(status)) {
          if (line.startsWith("VmRSS:")) return (int) (Long.parseLong(line.replaceAll("\\D", "")) >> 10);
        }
        return -1;
      }
      Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
      p.waitFor(2, TimeUnit.SECONDS);
      return out.isEmpty() ? -1 : (int) (Long.parseLong(out) >> 10);
    } catch (IOException | InterruptedException | NumberFormatException e) {
      return -1;
    }
  }
}
