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
                          [--trust-proxy] [--public-origin URL] [--base-path /PATH] [--device-size NAME=WxH@DENSITY]...""";
  private static final SecureRandom RANDOM = new SecureRandom();

  final Path root;
  final Path state;
  final Path launcher;
  final Path logs;
  Catalog catalog;
  int port = 8080;
  String bind = "127.0.0.1";
  String token;
  int max = 4;
  int idleSeconds = 600;
  int quality = 80;
  /** `--in-flight N`: the most frames in flight to a tab (the window is sized from the round trip, up to this). */
  int inFlightMax = 8;
  /** H.264 streams' bit rate in kilobits a second (`--bitrate`). */
  int bitrateKbps = 4000;
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

  private Serve(Path root, Path state) {
    this.root = root;
    this.state = state;
    this.launcher = root.resolve("apksim");
    this.logs = state.resolve("serve/logs");
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(System.getProperty("apksim.root", ".")).toAbsolutePath().normalize();
    Path state = Path.of(System.getProperty("apksim.state", root.resolve("work").toString())).toAbsolutePath().normalize();
    Serve s = new Serve(root, state);
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      String v = i + 1 < args.length ? args[i + 1] : null;
      switch (a) {
        case "--port" -> s.port = Integer.parseInt(need(a, v));
        case "--bind" -> s.bind = need(a, v);
        case "--token" -> s.token = Files.readAllLines(Path.of(need(a, v))).stream().findFirst().orElse("").strip();
        case "--max" -> s.max = Integer.parseInt(need(a, v));
        case "--idle" -> s.idleSeconds = Integer.parseInt(need(a, v));
        case "--apps" -> s.apps.add(need(a, v));
        case "--max-upload" -> s.maxUploadMb = Long.parseLong(need(a, v));
        case "--in-flight" -> s.inFlightMax = Math.max(1, Math.min(32, Integer.parseInt(need(a, v))));
        case "--quality" -> s.quality = Integer.parseInt(need(a, v));
        case "--bitrate" -> s.bitrateKbps = Math.max(100, Math.min(100_000, Integer.parseInt(need(a, v))));
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

  private static String need(String option, String value) {
    if (value == null) {
      System.err.println(option + " needs a value\n" + USAGE);
      System.exit(2);
    }
    return value;
  }

  private void run() throws Exception {
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
    catalog = new Catalog(apps, state, maxUploadMb << 20);
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
    System.err.printf("[serve] %s/%s  (%d apps listed, up to %d sessions, idle %d s, uploads up to %d MB)%n",
        at, token == null ? "" : "?token=...", catalog.listed().size(), max, idleSeconds, maxUploadMb);
    if (trustProxy) System.err.println("[serve] trusting X-Forwarded-Host/-Proto from loopback proxies");
    if (!allowedOrigins.isEmpty()) System.err.println("[serve] pages on " + String.join(", ", allowedOrigins) + " may embed it");
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
      catalog.sweep(24 * 3_600_000L);
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
  private void warmStandby() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return;
    if ("0".equals(System.getenv("APKSIM_STANDBY"))) return;
    try {
      // The standby is the JVM sessions' instances become: it gets the no-core policy too (APKSIM_NO_CORE reaches its flags).
      ProcessBuilder pb = new ProcessBuilder(launcher.toString(), "--spawn-standby", (noNetwork ? "n" : "") + (coreDumps ? "" : "k"))
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
  }

  void ended(Session s) {
    sessions.remove(s.id);
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
    if (!authorized(r)) {
      Http.text(out, 401, "this server needs its token: open the link with ?token=... once (or send Authorization: Bearer ...)");
      return;
    }
    boolean write = !r.method().equals("GET") || r.path().equals("/ws");
    if (write && !originAllowed(r)) {
      Http.text(out, 403, "cross-origin request refused");
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
        String csp = "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob: data:; connect-src 'self' ws: wss:; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors ";
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
    Path dir = state.resolve("serve");
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
      sessions.put(session.id, session);
    }
    System.err.printf("[serve] session %s: %s on %s%n", session.id, app.name(), session.device);
    ws.sendText("i {\"id\":" + Http.q(session.id) + ",\"app\":" + Http.q(app.name()) + ",\"device\":" + Http.q(session.device)
        + ",\"idleLimitS\":" + idleSeconds + "}");
    session.run();
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
