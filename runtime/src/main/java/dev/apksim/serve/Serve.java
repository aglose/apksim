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
import java.net.URLEncoder;
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
                          [--max-upload MB] [--quality Q] [--no-network]""";
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
  long maxUploadMb = 300;
  int launchTimeoutSeconds = 180;
  boolean noNetwork;
  boolean loopbackBind;
  private final List<String> apps = new ArrayList<>();
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
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
        case "--quality" -> s.quality = Integer.parseInt(need(a, v));
        case "--launch-timeout" -> s.launchTimeoutSeconds = Integer.parseInt(need(a, v));
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
    ServerSocket server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(addr, port), 64);
    String host = loopbackBind ? (addr instanceof java.net.Inet6Address ? "[::1]" : "localhost") : bind;
    System.err.printf("[serve] http://%s:%d/%s  (%d apps listed, up to %d sessions, idle %d s, uploads up to %d MB)%n",
        host, server.getLocalPort(), token == null ? "" : "?token=...", catalog.listed().size(), max, idleSeconds, maxUploadMb);
    warmStandby();
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "serve-timer");
      t.setDaemon(true);
      return t;
    });
    timer.scheduleWithFixedDelay(() -> sessions.values().forEach(Session::tick), 2, 2, TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(() -> catalog.sweep(24 * 3_600_000L), 0, 1, TimeUnit.HOURS);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      List<Thread> stopping = new ArrayList<>();
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
      Thread.ofVirtual().name("serve-http").start(() -> handle(c));
    }
  }

  /** On the Mac, a warm-launch standby for the first session (each instance starts the next after its first frame). */
  private void warmStandby() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return;
    if ("0".equals(System.getenv("APKSIM_STANDBY"))) return;
    try {
      new ProcessBuilder(launcher.toString(), "--spawn-standby", noNetwork ? "n" : "")
          .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(10, TimeUnit.SECONDS);
    } catch (IOException | InterruptedException e) {
      System.err.println("[serve] no standby: " + e);
    }
  }

  void ended(Session s) {
    sessions.remove(s.id);
  }

  // ---------------------------------------------------------------- HTTP

  private void handle(Socket socket) {
    try (Socket s = socket) {
      s.setTcpNoDelay(true);
      s.setSoTimeout(30_000);
      InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
      OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
      Http.Request r = Http.read(in);
      if (r == null) return;
      route(r, s, in, out);
    } catch (IOException | RuntimeException e) {
      // the client went away
    }
  }

  private void route(Http.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
    if (!hostAllowed(r)) {
      Http.text(out, 403, "this server answers only to localhost");
      return;
    }
    // The one-time link: keep the token as a cookie and take it out of the address bar.
    if (r.method().equals("GET") && r.path().equals("/") && r.param("token") != null) {
      if (!tokenMatches(r.param("token"))) {
        Http.text(out, 401, "wrong token");
        return;
      }
      Map<String, String> rest = new LinkedHashMap<>(r.query());
      rest.remove("token");
      StringBuilder to = new StringBuilder("/");
      for (Map.Entry<String, String> e : rest.entrySet()) {
        to.append(to.length() == 1 ? '?' : '&').append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
      }
      Http.respond(out, 302, "text/plain", new byte[0], "Location: " + to,
          "Set-Cookie: apksim_token=" + r.param("token") + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000");
      return;
    }
    if (!authorized(r)) {
      Http.text(out, 401, "this server needs its token: open the link with ?token=... once (or send Authorization: Bearer ...)");
      return;
    }
    boolean write = !r.method().equals("GET") || r.path().equals("/ws");
    if (write && !sameOrigin(r)) {
      Http.text(out, 403, "cross-origin request refused");
      return;
    }
    switch (r.method() + " " + r.path()) {
      case "GET /" -> Http.respond(out, 200, "text/html; charset=utf-8", page,
          "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob: data:; connect-src 'self' ws: wss:; "
              + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
          "X-Frame-Options: DENY");
      case "GET /api/health" -> Http.text(out, 200, "ok");
      case "GET /api/apps" -> {
        StringBuilder b = new StringBuilder("[");
        for (Catalog.App a : catalog.listed()) {
          if (b.length() > 1) b.append(',');
          b.append("{\"id\":").append(Http.q(a.id())).append(",\"name\":").append(Http.q(a.name())).append('}');
        }
        Http.json(out, 200, b.append(']').toString());
      }
      case "GET /api/status" -> Http.json(out, 200, status());
      case "POST /api/upload" -> upload(r, out);
      case "GET /ws" -> websocket(r, s, in, out);
      default -> Http.text(out, 404, "not found");
    }
  }

  private void upload(Http.Request r, OutputStream out) throws IOException {
    long length = r.contentLength();
    if (length < 0) {
      Http.text(out, 411, "send the APK as the request body, with Content-Length");
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
      Catalog.App a = catalog.upload(r.body(), length, name);
      System.err.printf("[serve] upload %s (%d MB)%n", a.name(), length >> 20);
      Http.json(out, 200, "{\"id\":" + Http.q(a.id()) + ",\"name\":" + Http.q(a.name()) + "}");
    } catch (IOException e) {
      Http.text(out, 400, e.getMessage());
    }
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
    for (String k : List.of("device", "appearance", "locale", "fontscale", "open", "orientation", "permissions")) {
      String v = r.param(k);
      if (v != null && !v.isEmpty()) options.put(k, v);
    }
    if (!Session.DEVICES.contains(options.getOrDefault("device", "phone"))) options.put("device", "phone");
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
    System.err.printf("[serve] session %s: %s on %s%n", session.id, app.name(), options.getOrDefault("device", "phone"));
    ws.sendText("i {\"id\":" + Http.q(session.id) + ",\"app\":" + Http.q(app.name()) + ",\"idleLimitS\":" + idleSeconds + "}");
    session.run();
  }

  private String status() {
    StringBuilder b = new StringBuilder("{\"max\":").append(max).append(",\"idleLimitS\":").append(idleSeconds)
        .append(",\"maxUploadMb\":").append(maxUploadMb).append(",\"uptimeS\":").append((System.currentTimeMillis() - startedAt) / 1000)
        .append(",\"warmLaunches\":").append(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"))
        .append(",\"sessions\":[");
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
    String host = r.header("host");
    if (host == null) return false;
    String h = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.replaceFirst(":\\d+$", "");
    return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.endsWith(".localhost");
  }

  /** A browser names the page a request came from: refuse WebSockets and POSTs from any page but this server's. */
  private static boolean sameOrigin(Http.Request r) {
    String origin = r.header("origin");
    if (origin == null) return true;  // not a browser (curl, scripts): nothing to forge
    String host = r.header("host");
    return host != null && (origin.equals("http://" + host) || origin.equals("https://" + host));
  }

  private boolean authorized(Http.Request r) {
    if (token == null) return true;
    String auth = r.header("authorization");
    if (auth != null && auth.startsWith("Bearer ") && tokenMatches(auth.substring(7).strip())) return true;
    String cookie = r.cookie("apksim_token");
    return cookie != null && tokenMatches(cookie);
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
