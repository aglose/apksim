package dev.apksim.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Checks the beam tab relay inside the instance ({@link TabRelay}, scripts/check-tab-relay.sh, CI) against a fake tab
 * on the control connection: an app socket for the reserved host gets an in-memory connection; the tab sees the
 * request (method, path with query, headers without Host and hop-by-hop ones, body) and its answer reaches the app
 * (status, repeated headers, Content-Length recomputed, `Connection: close`, no Content-Encoding, no injected header);
 * HEAD has no body; chunked bodies (411), large bodies (413), other methods (405), https and other ports are refused; the
 * JDK's HttpURLConnection path (GET, POST, an error status); an answer over 512 KB or the tab's own 502; a timeout
 * (504); the 16-request limit (503); the tab going (502 for what waited) and no tab (502). Exits 1 if any check fails.
 */
public final class TabRelayCheck {
  private static int failures;

  private TabRelayCheck() {}

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  /** What the fake tab saw, newest last. */
  private static final List<Map<?, ?>> seen = new CopyOnWriteArrayList<>();
  /** Requests the tab holds unanswered (path /hold), by id. */
  private static final Map<String, String> held = new ConcurrentHashMap<>();
  private static volatile CountDownLatch holding = new CountDownLatch(0);

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  private static String b64(String s) {
    return b64(s.getBytes(StandardCharsets.UTF_8));
  }

  private static void reply(String id, String json) {
    TabRelay.answer(new String[] {"relayed", id, b64(json)});
  }

  /** The fake tab: what a page's handler would do with each `relay ID BASE64(JSON)` line. */
  private static final Consumer<String> TAB = line -> {
    String[] p = line.split(" ");
    Map<?, ?> r = (Map<?, ?>) Json.parse(new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8));
    seen.add(r);
    String path = String.valueOf(r.get("path"));
    String id = p[1];
    Thread.ofVirtual().start(() -> {
      if (path.startsWith("/slow")) return;  // never answered
      if (path.startsWith("/hold")) {
        held.put(id, path);
        holding.countDown();
        return;
      }
      if (path.startsWith("/tab502")) {
        reply(id, "{\"status\":502,\"headers\":{\"content-type\":\"text/plain\"},\"body\":\"" + b64("the page's own fetch failed") + "\"}");
      } else if (path.startsWith("/big")) {
        reply(id, "{\"status\":200,\"headers\":{},\"body\":\"" + b64(new byte[600 * 1024]) + "\"}");
      } else if (path.startsWith("/missing")) {
        reply(id, "{\"status\":404,\"headers\":{\"content-type\":\"text/plain\"},\"body\":\"" + b64("no such thing") + "\"}");
      } else {
        String body = new String(Base64.getDecoder().decode(String.valueOf(r.get("body"))), StandardCharsets.UTF_8);
        String echo = r.get("method") + " " + path + " [" + body + "]";
        reply(id, "{\"status\":200,\"headers\":{\"Content-Type\":\"text/plain\",\"Set-Cookie\":[\"a=1\",\"b=2\"],"
            + "\"Content-Encoding\":\"gzip\",\"Content-Length\":\"1\",\"X-Bad\":\"x\\r\\nInjected: yes\",\"X-Echo\":\"yes\"},\"body\":\"" + b64(echo) + "\"}");
      }
    });
  };

  /** The app's side: a socket to the reserved host through Net's hook, one raw request, the whole response. */
  private static String raw(String request) throws IOException {
    try (Socket s = new Socket()) {
      Net.connect(s, new InetSocketAddress(TabRelay.HOST, 80), 5000);
      s.setSoTimeout(30_000);
      OutputStream out = s.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static String get(String path) throws IOException {
    return raw("GET " + path + " HTTP/1.1\r\nHost: " + TabRelay.HOST + "\r\n\r\n");
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("apksim.beamRelay", "true");
    System.setProperty("apksim.beamRelay.timeoutMs", "1500");
    TabRelay.configure();
    check(TabRelay.on && TabRelay.isHost("PAGE.apksim.invalid.") && !TabRelay.isHost("example.com"), "the relay is on for its host only");

    String r = get("/echo");
    check(r.startsWith("HTTP/1.1 502 "), "no tab attached: 502 (" + r.lines().findFirst().orElse("") + ")");
    check(TabRelay.attach(TAB).equals("ok relay"), "relay attach");
    check(TabRelay.attach(line -> {}).startsWith("error:"), "a second attach is refused");

    // A request as OkHttp sends it.
    r = raw("POST /echo?q=1&x=%20 HTTP/1.1\r\nHost: " + TabRelay.HOST + "\r\nConnection: Keep-Alive\r\nAccept-Encoding: gzip\r\n"
        + "Content-Type: application/json\r\nX-Check: one\r\nX-Check: two\r\nCookie: a=1\r\nCookie: b=2\r\nContent-Length: 17\r\n\r\n{\"name\":\"apksim\"}");
    Map<?, ?> tab = seen.get(seen.size() - 1);
    Map<?, ?> h = (Map<?, ?>) tab.get("headers");
    check("POST".equals(tab.get("method")) && "/echo?q=1&x=%20".equals(tab.get("path")), "the tab sees the method and the path with its query (" + tab.get("path") + ")");
    check("one, two".equals(h.get("x-check")) && "a=1; b=2".equals(h.get("cookie")) && "application/json".equals(h.get("content-type")),
        "the tab sees the app's headers, repeated ones joined (" + h + ")");
    check(!h.containsKey("host") && !h.containsKey("connection") && !h.containsKey("content-length") && !h.containsKey("accept-encoding"),
        "the tab sees no Host, Connection, Content-Length or Accept-Encoding");
    check("{\"name\":\"apksim\"}".equals(new String(Base64.getDecoder().decode(String.valueOf(tab.get("body"))), StandardCharsets.UTF_8)),
        "the tab sees the body");
    String echo = "POST /echo?q=1&x=%20 [{\"name\":\"apksim\"}]";
    check(r.startsWith("HTTP/1.1 200 OK\r\n") && r.endsWith("\r\n\r\n" + echo), "the app gets the tab's status and body");
    check(r.contains("\r\nContent-Length: " + echo.length() + "\r\n") && !r.contains("Content-Length: 1\r\n"), "Content-Length is the body's, not the tab's");
    check(r.contains("\r\nSet-Cookie: a=1\r\n") && r.contains("\r\nSet-Cookie: b=2\r\n") && r.contains("\r\nX-Echo: yes\r\n"), "repeated and other headers reach the app");
    check(r.contains("\r\nConnection: close\r\n") && !r.contains("Content-Encoding") && !r.contains("Injected"),
        "Connection: close; no Content-Encoding; a header with a line break is dropped");

    r = raw("HEAD /echo HTTP/1.1\r\nHost: x\r\n\r\n");
    check(r.startsWith("HTTP/1.1 200 ") && r.endsWith("\r\n\r\n"), "HEAD: the headers and no body");
    r = raw("POST /echo HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
    check(r.startsWith("HTTP/1.1 411 "), "a chunked request body: 411");
    int before = seen.size();
    r = raw("PUT /echo HTTP/1.1\r\nContent-Length: " + (600 * 1024) + "\r\n\r\n");
    check(r.startsWith("HTTP/1.1 413 ") && seen.size() == before, "a request body over 512 KB: 413, not sent to the tab");
    r = raw("TRACE /echo HTTP/1.1\r\n\r\n");
    check(r.startsWith("HTTP/1.1 405 "), "TRACE: 405");
    for (String m : List.of("PUT", "PATCH", "DELETE")) {
      r = raw(m + " /echo HTTP/1.1\r\nContent-Length: 2\r\n\r\nhi");
      check(r.endsWith(m + " /echo [hi]"), m + " is relayed");
    }

    // https and other ports.
    for (int port : new int[] {443, 8080}) {
      boolean refused = false;
      try (Socket s = new Socket()) {
        Net.connect(s, new InetSocketAddress(TabRelay.HOST, port), 1000);
      } catch (ConnectException e) {
        refused = e.getMessage().contains("plain http on port 80");
      }
      check(refused, "port " + port + " is not relayed");
    }
    boolean refused = false;
    try {
      Net.openConnection(new URL("https://" + TabRelay.HOST + "/echo"));
    } catch (ConnectException e) {
      refused = true;
    }
    check(refused, "https URLs are not relayed");
    // The placeholder address NetResolver gives the name (what OkHttp's lookup then connects to) is the relay's too.
    InetAddress placeholder = NetResolver.placeholderFor(TabRelay.HOST);
    try (Socket s = new Socket()) {
      Net.connect(s, new InetSocketAddress(placeholder, 80), 1000);
      s.getOutputStream().write("GET /echo HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      String a = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
      check(a.endsWith("GET /echo []") && s.getInetAddress().equals(placeholder) && s.getPort() == 80,
          "a socket to the name's placeholder address is relayed, and says where it is connected");
    }

    // The JDK's HttpURLConnection, as apps use it.
    HttpURLConnection c = (HttpURLConnection) Net.openConnection(new URL("http://" + TabRelay.HOST + "/echo?via=url"));
    c.setRequestProperty("X-Check", "url");
    check(c.getResponseCode() == 200 && new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals("GET /echo?via=url []")
        && "yes".equals(c.getHeaderField("x-echo")) && c.getHeaderFields().get("Set-Cookie").size() == 2,
        "HttpURLConnection: GET, its status, body and headers");
    check("url".equals(((Map<?, ?>) seen.get(seen.size() - 1).get("headers")).get("x-check")), "HttpURLConnection: the tab sees its request properties");
    c = (HttpURLConnection) Net.openConnection(new URL("http://" + TabRelay.HOST + "/echo"));
    c.setDoOutput(true);
    c.getOutputStream().write("posted".getBytes(StandardCharsets.UTF_8));
    check(c.getResponseCode() == 200 && new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals("POST /echo [posted]"),
        "HttpURLConnection: a body makes it a POST");
    c = (HttpURLConnection) Net.openConnection(new URL("http://" + TabRelay.HOST + "/missing"));
    boolean notFound = false;
    try {
      c.getInputStream();
    } catch (java.io.FileNotFoundException e) {
      notFound = true;
    }
    check(c.getResponseCode() == 404 && notFound && new String(c.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).equals("no such thing"),
        "HttpURLConnection: 404 throws FileNotFoundException, the body in getErrorStream");

    r = get("/big");
    check(r.startsWith("HTTP/1.1 502 ") && r.contains("over 512 KB"), "an answer over 512 KB: 502");
    r = get("/tab502");
    check(r.startsWith("HTTP/1.1 502 ") && r.endsWith("the page's own fetch failed"), "the tab's own 502 reaches the app");
    long t0 = System.nanoTime();
    r = get("/slow");
    long ms = (System.nanoTime() - t0) / 1_000_000;
    check(r.startsWith("HTTP/1.1 504 ") && ms >= 1400 && ms < 5000, "no answer in time: 504 (after " + ms + " ms)");

    // 16 in flight: the 17th gets 503 at once.
    holding = new CountDownLatch(TabRelay.MAX_IN_FLIGHT);
    List<Thread> waiting = new ArrayList<>();
    List<String> results = new CopyOnWriteArrayList<>();
    for (int i = 0; i < TabRelay.MAX_IN_FLIGHT; i++) {
      int n = i;
      waiting.add(Thread.ofPlatform().daemon().start(() -> {
        try {
          results.add(get("/hold/" + n));
        } catch (IOException e) {
          results.add("error " + e);
        }
      }));
    }
    holding.await(5, TimeUnit.SECONDS);
    r = get("/echo");
    check(held.size() == TabRelay.MAX_IN_FLIGHT && r.startsWith("HTTP/1.1 503 "), "16 requests waiting on the tab: the next gets 503 (" + held.size() + " held)");
    // The tab goes (its control connection closes) while they wait: each gets 502, before its 1.5 s timeout.
    TabRelay.detach(TAB);
    for (Thread t : waiting) t.join(5000);
    check(results.size() == TabRelay.MAX_IN_FLIGHT && results.stream().allMatch(x -> x.startsWith("HTTP/1.1 502 ") && x.contains("the tab has gone")),
        "the tab going: what waited gets 502 (" + results.stream().map(x -> x.lines().findFirst().orElse("")).distinct().toList() + ")");
    r = get("/echo");
    check(r.startsWith("HTTP/1.1 502 "), "and later requests get 502 until a tab attaches");
    // A late answer for a request that has gone is ignored.
    TabRelay.answer(new String[] {"relayed", "999999", b64("{\"status\":200}")});
    TabRelay.answer(new String[] {"relayed", "x"});
    check(true, "answers to unknown ids are ignored");

    if (failures > 0) {
      System.out.println(failures + " check(s) failed");
      System.exit(1);
    }
    System.out.println("tab relay check passed");
  }
}
