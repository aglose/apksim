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
                          [--max-upload MB] [--quality Q] [--bitrate KBPS] [--codec auto|jpeg] [--no-network]
                          [--allow-origin ORIGIN]... [--warm auto|on|off] [--core-dumps] [--move-shared-data] [--in-flight N]
                          [--trust-proxy] [--public-origin URL] [--base-path /PATH] [--device-size NAME=WxH@DENSITY]...
                          [--allow-beam] [--beam-port N]
             apksim beam [--port N] [--max N] [--idle SECONDS] [--quality Q] [--bitrate KBPS] [--codec auto|jpeg]
                         [--keep-days N] [--save-sessions on|off] [--keep-saved-days N|never]
             apksim beam trust [list | allow ORIGIN | deny ORIGIN | revoke ORIGIN]
             apksim beam saved | run ID [OPTIONS] | forget ID | sweep
             apksim beam config [keep-days N] [save-sessions on|off] [keep-saved-days N|never]""";
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
    if (args.length > 1 && args[0].equals("--beam") && List.of("saved", "run", "forget", "config", "sweep", "status").contains(args[1])) {
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
        case "--beam" -> {
          s.beam = true;
          i--;
        }
        case "--allow-beam" -> {
          s.allowBeam = true;
          i--;
        }
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
            + " --max-upload, --no-network, --launch-timeout, --keep-days, --save-sessions, --keep-saved-days (it listens"
            + " on 127.0.0.1 and asks before a site runs an app)");
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
          saveSessions != null ? saveSessions : c.saveSessions(), keepSavedDays != null ? keepSavedDays : c.keepSavedDays());
      if (!store.lockAgent(port)) {
        int other = store.agentPort();
        System.err.println("apksim beam: an agent already runs for " + state + (other > 0 ? " (port " + other + ")" : "")
            + "; stop it first (two would delete each other's builds)");
        System.exit(2);
      }
    } else if (keepDays != null || saveSessions != null || keepSavedDays != null) {
      usage("--keep-days, --save-sessions and --keep-saved-days are apksim beam's");
    }
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
      page = in.readAllBytes();
    }
    Files.createDirectories(logs);
    Crash.sweep(logs, 7);  // core files and crash reports an earlier run left, and old logs
    warmer = new Warmer(this);
    catalog.onReady = warmer::enqueue;
    ServerSocket server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(addr, port), 64);
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
      else catalog.sweep(24 * 3_600_000L);
      warmer.prune(catalog.listed().stream().map(Catalog.App::id).collect(java.util.stream.Collectors.toSet()));
    }, 0, 1, TimeUnit.HOURS);
    timer.scheduleWithFixedDelay(() -> Crash.sweep(logs, 7), 1, 1, TimeUnit.HOURS);
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
      Thread.ofVirtual().name("serve-http").start(() -> {
        try {
          handle(c);
        } finally {
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
    if (beam) pb.environment().put("APKSIM_BEAM_SANDBOX", "1");
    else pb.environment().remove("APKSIM_BEAM_SANDBOX");
  }

  /** The agent's cache for one build (translated classes), kept and deleted with it. */
  Path beamCache(String sha256) {
    return state.resolve("beam/cache").resolve(sha256);
  }

  void ended(Session s) {
    sessions.remove(s.id);
    if (beam) {
      // Its build's hold starts now (a saved session's, or another running session's, keeps it anyway).
      catalog.released(catalog.buildHash(s.app.path()));
      Thread.ofVirtual().name("beam-sweep").start(this::sweepBeam);
    }
  }

  /** The agent's sweep (docs/beam.md): at its start, hourly, and as sessions start and end. */
  void sweepBeam() {
    try {
      store.sweep(catalog, sha -> agent.holds(sha)
          || sessions.values().stream().anyMatch(x -> sha.equals(catalog.buildHash(x.app.path()))));
    } catch (RuntimeException e) {
      System.err.println("[beam] sweep failed: " + e);
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
        Http.respond(out, 204, "text/plain", new byte[0], "Access-Control-Allow-Methods: GET, POST",
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
      Http.json(out, 200, "{\"port\":" + beamPort + ",\"protocol\":" + Beam.PROTOCOL + "}");
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
    if (r.method().equals("GET") && r.path().matches("/api/sessions/[0-9a-f]{12}/log")) {
      sessionLog(r.path().substring("/api/sessions/".length(), "/api/sessions/".length() + 12), out);
      return;
    }
    switch (r.method() + " " + r.path()) {
      case "GET /" -> {
        // --allow-beam: the page also asks this Mac's agent (/beam/hello, /beam/start); its WebSocket is under ws: already.
        String agentSrc = allowBeam ? " http://127.0.0.1:" + beamPort : "";
        String csp = "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob: data:; connect-src 'self' ws: wss:"
            + agentSrc + "; base-uri 'none'; form-action 'none'; frame-ancestors ";
        // Framed only by the origins named (--allow-origin), and then without X-Frame-Options, which cannot name them.
        if (allowedOrigins.isEmpty()) Http.respond(out, 200, "text/html; charset=utf-8", page, csp + "'none'", "X-Frame-Options: DENY");
        else Http.respond(out, 200, "text/html; charset=utf-8", page, csp + "'self' " + String.join(" ", allowedOrigins));
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
        Http.json(out, 200, b.append(']').toString());
      }
      case "GET /api/status" -> Http.json(out, 200, status());
      case "POST /api/upload" -> upload(r, out);
      case "POST /api/apps" -> addApp(r, out);
      case "GET /ws" -> websocket(r, s, in, out);
      default -> Http.text(out, 404, "not found");
    }
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
      Catalog.App a = catalog.upload(r.content(), length, name, List.of());
      System.err.printf("[serve] upload %s (%d MB)%n", a.name(), Files.size(a.path()) >> 20);
      Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name()) + "}");
    } catch (IOException e) {
      Http.text(out, 400, e.getMessage());
    }
  }

  /**
   * `POST /api/apps` `{"url": "https://...", "name": "...", "headers": {...}, "options": ["--activity", ".Main", ...]}`:
   * serve downloads the APK itself and answers its id at once (docs/serve.md).
   */
  private void addApp(Http.Request r, OutputStream out) throws IOException {
    if (r.contentLength() < 0 && !r.chunked()) {
      Http.text(out, 411, "send a JSON body, with Content-Length or Transfer-Encoding: chunked");
      return;
    }
    long length = r.chunked() ? -1 : r.contentLength();
    byte[] raw = r.content().readNBytes((int) Math.min(64 * 1024 + 1, length < 0 ? Long.MAX_VALUE : length));
    if (raw.length > 64 * 1024) {
      Http.text(out, 413, "the request body is limited to 64 KB");
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
      Catalog.App a = catalog.addUrl(url, name, headers, Catalog.launchOptions(given));
      Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name()) + ",\"state\":\"downloading\"}");
    } catch (Catalog.Busy e) {
      Http.respond(out, 503, "text/plain; charset=utf-8", (e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8), "Retry-After: 30");
    } catch (IOException e) {
      Http.text(out, 400, e.getMessage());
    }
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

  /** `GET /api/apps/ID`: an uploaded or added app's state (downloading, ready, failed), for a service that added it. */
  private void appState(String id, OutputStream out) throws IOException {
    Catalog.Download d = catalog.download(id);
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
      Http.json(out, 200, b.append(",\"received\":").append(d.received).append(",\"total\":").append(d.total)
          .append(",\"cache\":\"none\"}").toString());
      return;
    }
    Catalog.App a = catalog.find(id);
    if (a == null) {
      Http.text(out, 404, "no such app (an upload is kept for a day after its last use)");
      return;
    }
    String detail = warmer.detail(a);
    Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name())
        + (Session.appDevice(a) != null ? ",\"device\":" + Http.q(Session.appDevice(a)) : "") + ",\"state\":\"ready\",\"cache\":"
        + Http.q(warmer.state(a)) + (detail.isEmpty() ? "" : ",\"cacheDetail\":" + Http.q(detail)) + "}");
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
    websocket(r, s, in, out, null);
  }

  /** `site`: the agent's session's page origin (beam), else null. */
  private void websocket(Http.Request r, Socket s, InputStream in, OutputStream out, String site) throws IOException {
    String key = r.header("sec-websocket-key");
    if (key == null || !"websocket".equalsIgnoreCase(r.header("upgrade"))) {
      Http.text(out, 400, "expected a WebSocket upgrade");
      return;
    }
    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
        + WebSocket.acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    s.setSoTimeout(0);
    WebSocket ws = new WebSocket(s, in, out);
    Catalog.App app = catalog.find(r.param("app"));
    if (app == null) {
      ws.sendText("x no such app (an upload is kept for a day after its last use)");
      ws.close("no such app");
      return;
    }
    Map<String, String> options = new LinkedHashMap<>();
    for (String k : List.of("device", "appearance", "locale", "fontscale", "open", "orientation", "permissions", "codec")) {
      String v = r.param(k);
      if (v != null && !v.isEmpty()) options.put(k, v);
    }
    // Only a device this server names (built in, --device-size, or the app's own default), never a client's own string;
    // anything else, or none, is the app's default device, else the phone.
    String device = options.get("device");
    if (device != null && !Session.deviceAllowed(this, app, device)) options.remove("device");
    Session session;
    synchronized (sessions) {
      if (sessions.size() >= max) {
        ws.sendText("x all " + max + " sessions are in use: try again in a minute");
        ws.close("busy");
        return;
      }
      byte[] id = new byte[6];
      RANDOM.nextBytes(id);
      session = new Session(this, HexFormat.of().formatHex(id), app, options, ws);
      if (beam) {
        session.site = site;
        session.keep = store.config.saveSessions();
      }
      sessions.put(session.id, session);
    }
    if (beam) agent.settled(catalog.buildHash(app.path()));
    System.err.printf("[serve] session %s: %s on %s%n", session.id, app.name(), session.device);
    ws.sendText("i {\"id\":" + Http.q(session.id) + ",\"app\":" + Http.q(app.name()) + ",\"device\":" + Http.q(session.device)
        + ",\"idleLimitS\":" + idleSeconds + (beam ? ",\"keep\":" + session.keep : "") + "}");
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
    StringBuilder opts = new StringBuilder("[");
    for (String o : a.launch()) opts.append(opts.length() > 1 ? "," : "").append(Http.q(o));
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
   * The agent's routes (`apksim beam`). Any page may ask `/beam/hello` (it says only whether that page's origin is
   * allowed); `/beam/start` runs the prompt for an origin not yet allowed, and answers a session grant for an APK the
   * agent has by its hash, else an upload grant; `/beam/upload` takes the APK once and checks its hash; `/ws` opens
   * the session for its grant only. Every grant is for one origin and one use.
   */
  private void beamRoute(Http.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
    String sent = r.header("origin");
    String o = sent == null ? null : origin(sent);
    // A page's own answers only, so any page may read them (each says nothing beyond what that page may know).
    if (o != null) Http.CORS_ORIGIN.set(sent);
    if (r.method().equals("OPTIONS")) {
      // The browser's preflight for the JSON start and the upload; and Private Network Access's, for browsers that send it.
      Http.respond(out, 204, "text/plain", new byte[0], "Access-Control-Allow-Methods: GET, POST",
          "Access-Control-Allow-Headers: Content-Type, X-Filename", "Access-Control-Allow-Private-Network: true",
          "Access-Control-Max-Age: 600");
      return;
    }
    if (r.method().equals("GET") && r.path().equals("/beam/hello")) {
      Http.json(out, 200, "{\"apksim\":" + Http.q(Beam.version(root)) + ",\"protocol\":" + Beam.PROTOCOL + ",\"trusted\":"
          + (o != null && agent.trusted(o)) + "}");
      return;
    }
    // `apksim beam status` (docs/beam.md, phase 6): to a local tool only, never a page (a browser always sends Origin).
    if (sent == null && r.method().equals("GET") && r.path().equals("/beam/status")) {
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
    switch (r.method() + " " + r.path()) {
      case "POST /beam/start" -> beamStart(r, o, out);
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
        Http.json(out, 200, "{\"app\":" + Http.q(a.id()) + ",\"token\":" + Http.q(agent.grant(Beam.Kind.SESSION, o, a.id(), got, a.name(), a.launch())) + "}");
      }
      case "GET /ws" -> {
        Beam.Grant g = agent.take(r.param("token"), Beam.Kind.SESSION, o);
        if (g == null || !g.app().equals(r.param("app"))) {
          Http.text(out, 403, "this session was not started by this page (start it again)");
          return;
        }
        websocket(r, s, in, out, o);
      }
      default -> Http.text(out, 404, "not found");
    }
  }

  /** `POST /beam/start {"sha256", "name", "size", "options"}`: the prompt (for a new origin), then a grant. */
  private void beamStart(Http.Request r, String o, OutputStream out) throws IOException {
    long length = r.chunked() ? -1 : r.contentLength();
    byte[] raw = r.content().readNBytes((int) Math.min(64 * 1024 + 1, length < 0 ? Long.MAX_VALUE : length));
    if (raw.length > 64 * 1024) {
      Http.text(out, 413, "the request body is limited to 64 KB");
      return;
    }
    String sha, name, ticket, from;
    long size;
    List<String> launch;
    try {
      if (!(Json.parse(new String(raw, StandardCharsets.UTF_8)) instanceof Map<?, ?> body)) throw new IOException("expected a JSON object");
      ticket = body.get("ticket") instanceof String t && t.length() <= 300 ? t : null;
      from = body.get("url") instanceof String u && u.length() <= 2000 ? u : null;
      if (!(body.get("sha256") instanceof String h) || !h.matches("[0-9a-f]{64}")) throw new IOException("\"sha256\" (64 hex digits) is required");
      sha = h;
      name = body.get("name") instanceof String n ? n : null;
      size = body.get("size") instanceof Number n ? n.longValue() : -1;
      List<String> given = new ArrayList<>();
      if (body.get("options") instanceof List<?> l) {
        for (Object x : l) {
          if (!(x instanceof String str)) throw new IOException("\"options\" takes strings");
          given.add(str);
        }
      }
      launch = Catalog.launchOptions(given);
    } catch (IOException | RuntimeException e) {
      Http.text(out, 400, String.valueOf(e.getMessage()));
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
    if (a == null && ticket != null && from != null) {
      // Phase 2: from the page's own server, with the ticket it was given; the tab relays it only if this fails.
      try {
        a = beamFetch(o, from, ticket, sha, name, size, launch);
        via = "download";
      } catch (IOException e) {
        why = e.getMessage();
        System.err.println("[beam] could not download " + (name == null ? sha.substring(0, 12) : name) + " from " + o + ": " + why);
      }
    }
    if (a != null) {
      Http.json(out, 200, "{\"app\":" + Http.q(a.id()) + ",\"token\":" + Http.q(agent.grant(Beam.Kind.SESSION, o, a.id(), sha, a.name(), launch))
          + ",\"via\":" + Http.q(via) + "}");
      sweepBeam();  // after the answer, with this start's grant holding its build
      return;
    }
    Http.json(out, 200, "{\"need\":\"upload\",\"upload\":" + Http.q(agent.grant(Beam.Kind.UPLOAD, o, null, sha, name, launch))
        + (why == null ? "" : ",\"why\":" + Http.q(why)) + "}");
  }

  /**
   * The agent downloads a build from the server the page came from (docs/beam.md, phase 2): only from that page's own
   * origin (a page could make it fetch nothing it could not fetch itself), only `/api/beam/apk` with the ticket, no
   * redirects (a sign-in page behind single sign-on is a failure, not a place to go), an APK's content type, within
   * --max-upload, and the hash the page named checked before the file is stored. Throws why it could not.
   */
  private Catalog.App beamFetch(String origin, String from, String ticket, String sha, String name, long size, List<String> launch)
      throws IOException {
    java.net.URI u;
    try {
      u = new java.net.URI(from);
    } catch (java.net.URISyntaxException e) {
      throw new IOException("not a URL");
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null
        || u.getRawQuery() != null || u.getRawFragment() != null || u.getRawPath() == null || !u.getRawPath().endsWith("/api/beam/apk")) {
      throw new IOException("not the server's APK address");
    }
    int port = u.getPort();
    boolean defaultPort = port == -1 || scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443;
    String own = scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    if (!own.equals(origin.toLowerCase(Locale.ROOT))) throw new IOException("the address is not the page's own site (" + own + ")");
    if (size > maxUploadMb << 20) throw new IOException("too large");
    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(10)).build();
    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(u.resolve(u.getRawPath() + "?ticket="
            + java.net.URLEncoder.encode(ticket, StandardCharsets.UTF_8)))
        .timeout(java.time.Duration.ofMinutes(10)).header("Accept", "application/vnd.android.package-archive").GET().build();
    java.net.http.HttpResponse<InputStream> resp;
    try {
      resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted");
    }
    try (InputStream body = resp.body()) {
      if (resp.statusCode() != 200) throw new IOException("the server answered " + resp.statusCode());
      String type = resp.headers().firstValue("content-type").orElse("");
      if (!type.startsWith("application/vnd.android.package-archive")) throw new IOException("the server sent " + (type.isEmpty() ? "no content type" : type) + ", not an APK");
      long length = resp.headers().firstValueAsLong("content-length").orElse(-1);
      if (length > maxUploadMb << 20) throw new IOException("too large: " + (length >> 20) + " MB");
      Catalog.App a = catalog.upload(body, length, name, launch, sha);
      System.err.printf("[beam] %s: downloaded %s (%d MB) itself%n", origin, a.name(), Files.size(a.path()) >> 20);
      return a;
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
