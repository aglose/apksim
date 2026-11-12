package dev.apksim.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.SocketFactory;

/**
 * Checks the tab relay (scripts/check-tab-relay.sh, CI; docs/beam.md "The tab relay") with a stand-in for the tab on the
 * `relaywatch` link: a plain socket factory's socket and a URL connection to page.apksim.invalid, a body each way
 * (declared, chunked), the limits (405, 413, 503, 504, 502), a read timeout, a socket for any other address, and the
 * relay off. Exits 1 if any check fails.
 */
public final class TabRelayCheck {
  private static int failures;

  private TabRelayCheck() {}

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  /** The tab: reads the relay's `y` lines, answers each (on a thread of its own) with what `handler` returns, or nothing. */
  private static final class Tab implements AutoCloseable {
    final PrintWriter answers;
    final List<Map<?, ?>> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Thread link;
    final Socket tabSide;

    Tab(Function<Map<?, ?>, String> handler) throws IOException {
      // The link is a loopback pair of sockets (a pipe would refuse writes from threads that have ended).
      Socket relaySide;
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        tabSide = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        relaySide = server.accept();
      }
      PrintWriter out = new PrintWriter(relaySide.getOutputStream(), true);
      BufferedReader relayIn = new BufferedReader(new InputStreamReader(relaySide.getInputStream(), StandardCharsets.UTF_8));
      answers = new PrintWriter(tabSide.getOutputStream(), true);
      link = Thread.ofVirtual().start(() -> {
        try {
          TabRelay.serve(relayIn, out);
        } catch (IOException ignored) {
        }
      });
      BufferedReader r = new BufferedReader(new InputStreamReader(tabSide.getInputStream(), StandardCharsets.UTF_8));
      String first = r.readLine();
      if (!"ok relaywatch".equals(first)) throw new IOException("no link: " + first);
      Thread.ofVirtual().start(() -> {
        try {
          for (String line; (line = r.readLine()) != null; ) {
            String l = line;
            Thread.ofVirtual().start(() -> {
              int sp = l.indexOf(' ', 2);
              String id = l.substring(2, sp);
              Map<?, ?> req = (Map<?, ?>) Json.parse(l.substring(sp + 1));
              seen.add(req);
              String reply = handler.apply(req);
              if (reply != null) answers.println("y " + id + " " + reply);
            });
          }
        } catch (IOException ignored) {
        }
      });
    }

    @Override
    public void close() throws IOException {
      tabSide.close();
      try {
        link.join(2000);
      } catch (InterruptedException ignored) {
      }
    }
  }

  private static String reply(int status, String headers, byte[] body) {
    return "{\"status\":" + status + ",\"headers\":{" + headers + "},\"body\":\"" + Base64.getEncoder().encodeToString(body) + "\"}";
  }

  private static String text(Map<?, ?> req, String s) {
    return reply(200, "\"Content-Type\":\"text/plain\",\"X-Seen\":\"" + req.get("method") + "\"", s.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] bodyOf(Map<?, ?> req) {
    return Base64.getDecoder().decode(String.valueOf(req.get("body")));
  }

  private static Socket relaySocket() throws IOException {
    Socket s = Net.createSocket(SocketFactory.getDefault());
    Net.connect(s, new InetSocketAddress(InetAddress.getByName(TabRelay.HOST), 80), 1000);
    return s;
  }

  /** Sends raw bytes, reads to the end. */
  private static String exchange(byte[] request, int soTimeout) throws IOException {
    try (Socket s = relaySocket()) {
      s.setSoTimeout(soTimeout);
      OutputStream out = s.getOutputStream();
      out.write(request);
      out.flush();
      return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static String get(String path) throws IOException {
    return exchange(("GET " + path + " HTTP/1.1\r\nHost: " + TabRelay.HOST + "\r\nAccept: */*\r\nX-Dup: 1\r\nX-Dup: 2\r\n\r\n")
        .getBytes(StandardCharsets.ISO_8859_1), 5000);
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("apksim.tabRelay", "true");
    System.setProperty("apksim.tabRelay.answerMs", "1500");
    System.setProperty("apksim.tabRelay.firstLinkMs", "300");
    TabRelay.load();
    check(TabRelay.on, "on with -Dapksim.tabRelay=true");

    // No tab yet.
    check(get("/x").startsWith("HTTP/1.1 502 "), "no tab: 502");

    try (Tab tab = new Tab(req -> {
      String path = String.valueOf(req.get("path"));
      if (path.startsWith("/slow")) {
        try {
          Thread.sleep(700);
        } catch (InterruptedException ignored) {
        }
      }
      if (path.startsWith("/never")) return null;
      if (path.startsWith("/over")) return reply(200, "", new byte[TabRelay.MAX_BODY + 1]);
      if (path.startsWith("/missing")) return reply(404, "\"X-Why\":\"nothing\"", "gone".getBytes());
      if (path.startsWith("/echo")) return reply(201, "\"Content-Type\":\"application/octet-stream\"", bodyOf(req));
      return text(req, "hello " + path);
    })) {
      // A GET through a plain socket factory's socket.
      String r = get("/hello?x=1&y=%20");
      check(r.startsWith("HTTP/1.1 200 OK\r\n"), "GET: 200 (" + r.split("\r\n")[0] + ")");
      check(r.contains("Content-Type: text/plain\r\n") && r.contains("X-Seen: GET\r\n"), "GET: the tab's headers come back");
      check(r.contains("Connection: close\r\n") && r.contains("Content-Length: 22\r\n") && r.endsWith("\r\n\r\nhello /hello?x=1&y=%20"), "GET: close, length and body");
      Map<?, ?> seen = tab.seen.get(tab.seen.size() - 1);
      check("/hello?x=1&y=%20".equals(seen.get("path")), "GET: the path keeps its query");
      Map<?, ?> h = (Map<?, ?>) seen.get("headers");
      check("*/*".equals(h.get("Accept")) && "1, 2".equals(h.get("X-Dup")) && !h.containsKey("Host") && !h.containsKey("host"),
          "GET: the app's headers go on (repeats joined), Host does not: " + h);

      // A declared body each way.
      byte[] payload = new byte[300_000];
      new java.util.Random(7).nextBytes(payload);
      ByteArrayOutputStream req = new ByteArrayOutputStream();
      req.write(("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: " + payload.length + "\r\n\r\n").getBytes());
      req.write(payload);
      String post = exchange(req.toByteArray(), 5000);
      int split = post.indexOf("\r\n\r\n") + 4;
      System.out.println("   post: " + post.substring(0, Math.min(200, post.length())).replace("\r\n", "|"));
      check(post.startsWith("HTTP/1.1 201 ") && java.util.Arrays.equals(post.substring(split).getBytes(StandardCharsets.ISO_8859_1), payload),
          "POST: a 300 KB body goes up and comes back");

      // A chunked body, and Expect: 100-continue.
      String chunked = exchange(("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nExpect: 100-continue\r\n\r\n"
          + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").getBytes(), 5000);
      System.out.println("   chunked: " + chunked.replace("\r\n", "|"));
      check(chunked.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 ") && chunked.endsWith("\r\n\r\nhello world"), "POST: chunked, with 100-continue");

      // HEAD gets no body.
      String head = exchange("HEAD /hello HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(), 5000);
      System.out.println("   head: " + head.replace("\r\n", "|"));
      check(head.startsWith("HTTP/1.1 200 ") && head.endsWith("\r\n\r\n"), "HEAD: no body");

      // The limits.
      check(exchange("TRACE /x HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(), 5000).startsWith("HTTP/1.1 405 "), "TRACE: 405");
      check(exchange(("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: " + (TabRelay.MAX_BODY + 1) + "\r\n\r\n").getBytes(), 5000)
          .startsWith("HTTP/1.1 413 "), "a declared body over 512 KB: 413");
      check(exchange("GARBAGE\r\n\r\n".getBytes(), 5000).startsWith("HTTP/1.1 400 "), "not HTTP: 400");
      // Payload-size edges: a body of exactly the limit goes both ways; one byte over is refused each way; headers that
      // would make the line too long for the link are refused at once, not left to time out.
      byte[] full = new byte[TabRelay.MAX_BODY];
      ByteArrayOutputStream fr = new ByteArrayOutputStream();
      fr.write(("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: " + full.length + "\r\n\r\n").getBytes());
      fr.write(full);
      String fullBack = exchange(fr.toByteArray(), 5000);
      check(fullBack.startsWith("HTTP/1.1 201 ") && fullBack.contains("Content-Length: " + full.length + "\r\n"), "exactly 512 KB each way: carried");
      long t431 = System.currentTimeMillis();
      check(get("/over").startsWith("HTTP/1.1 502 ") && System.currentTimeMillis() - t431 < 1000, "an answer one byte over 512 KB: 502 at once");
      String big = "X-Big: " + "a".repeat(15_000) + "\r\n";
      check(exchange(("GET /x HTTP/1.1\r\nHost: x\r\n" + big.repeat(5) + "\r\n").getBytes(), 5000).startsWith("HTTP/1.1 431 "), "headers over 64 KB: 431");
      String one = exchange(("GET /hello HTTP/1.1\r\nHost: x\r\nAuthorization: " + "b".repeat(20_000) + "\r\n\r\n").getBytes(), 5000);
      check(one.startsWith("HTTP/1.1 200 "), "one 20 KB header within the 64 KB head: carried");
      check(exchange(("GET /x HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(70_000) + "\r\n\r\n").getBytes(), 5000).startsWith("HTTP/1.1 431 "),
          "one header line over 64 KB: 431, not a relay failure");
      check(exchange("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nhello\r\n0\r\n\r\n".getBytes(), 5000)
          .startsWith("HTTP/1.1 400 "), "a negative chunk size: 400");
      check(exchange("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n".getBytes(), 5000).startsWith("HTTP/1.1 400 "), "a negative Content-Length: 400");
      check(exchange(new byte[] {0x16, 0x03, 0x01, 0x00, 0x05, 'h', '\n'}, 5000).startsWith("HTTP/1.1 400 "), "a TLS hello: 400, plain HTTP only");
      check(get("/never").startsWith("HTTP/1.1 504 "), "no answer within the limit: 504");

      // A read timeout shorter than the tab's answer, as OkHttp's 10 s against a slow page.
      try (Socket s = relaySocket()) {
        s.setSoTimeout(200);
        s.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
        try {
          s.getInputStream().read();
          check(false, "SO_TIMEOUT: read timed out");
        } catch (SocketTimeoutException e) {
          check(true, "SO_TIMEOUT: a read waits only that long");
        }
        s.setSoTimeout(5000);
        check(new String(s.getInputStream().readAllBytes()).startsWith("HTTP/1.1 200 "), "SO_TIMEOUT: the answer is still read after");
      }

      // HttpURLConnection.
      URL u = new URL("http://" + TabRelay.HOST + "/hello?a=b");
      HttpURLConnection c = (HttpURLConnection) Net.openConnection(u);
      check(c instanceof RelayConnection, "URL.openConnection: a relay connection");
      c.setRequestProperty("X-App", "yes");
      check(c.getResponseCode() == 200 && "OK".equals(c.getResponseMessage()), "HttpURLConnection: 200");
      check("hello /hello?a=b".equals(new String(c.getInputStream().readAllBytes())), "HttpURLConnection: the body");
      check("text/plain".equals(c.getContentType()) && "X-Seen".equals(c.getHeaderFieldKey(2)) && "GET".equals(c.getHeaderField("x-seen")),
          "HttpURLConnection: headers");
      check("yes".equals(((Map<?, ?>) tab.seen.get(tab.seen.size() - 1).get("headers")).get("X-App")), "HttpURLConnection: request headers go on");
      HttpURLConnection p = (HttpURLConnection) Net.openConnection(new URL("http://" + TabRelay.HOST + "/echo"));
      p.setDoOutput(true);
      p.getOutputStream().write("posted".getBytes());
      check(p.getResponseCode() == 201 && "POST".equals(tab.seen.get(tab.seen.size() - 1).get("method"))
          && "posted".equals(new String(p.getInputStream().readAllBytes())), "HttpURLConnection: setDoOutput posts");
      HttpURLConnection m = (HttpURLConnection) Net.openConnection(new URL("http://" + TabRelay.HOST + "/missing"));
      boolean notFound = false;
      try {
        m.getInputStream();
      } catch (FileNotFoundException e) {
        notFound = true;
      }
      check(notFound && m.getResponseCode() == 404 && "gone".equals(new String(m.getErrorStream().readAllBytes())), "HttpURLConnection: 404 and its error stream");
      HttpURLConnection t = (HttpURLConnection) Net.openConnection(new URL("http://" + TabRelay.HOST + "/slow"));
      t.setReadTimeout(200);
      boolean timedOut = false;
      try {
        t.getResponseCode();
      } catch (SocketTimeoutException e) {
        timedOut = true;
      }
      check(timedOut, "HttpURLConnection: the read timeout applies");
      boolean refused = false;
      try {
        Net.openConnection(new URL("https://" + TabRelay.HOST + "/x"));
      } catch (ConnectException e) {
        refused = true;
      }
      check(refused, "https to the relay host: refused");

      // Any other address is the factory's own socket.
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        Thread.ofVirtual().start(() -> {
          try (Socket a = server.accept()) {
            a.getOutputStream().write(a.getInputStream().read());
          } catch (IOException ignored) {
          }
        });
        Socket s = Net.createSocket(SocketFactory.getDefault());
        check(s instanceof RelaySocket, "createSocket(): a stand-in while the relay is on");
        s.setTcpNoDelay(true);
        Net.connect(s, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 1000);
        s.getOutputStream().write(42);
        check(s.getInputStream().read() == 42 && s.isConnected() && ((RelaySocket) s).real().isConnected(), "another address: a real socket, connected");
        s.close();
      }

      // At most 16 in flight (503), then they finish. (First the slow ones above have been answered.)
      Thread.sleep(1200);
      List<Thread> threads = new ArrayList<>();
      AtomicInteger ok = new AtomicInteger(), busy = new AtomicInteger();
      CountDownLatch go = new CountDownLatch(1);
      for (int i = 0; i < 20; i++) {
        threads.add(Thread.ofVirtual().start(() -> {
          try {
            String x = get("/slow");
            if (x.startsWith("HTTP/1.1 200 ")) ok.incrementAndGet();
            else if (x.startsWith("HTTP/1.1 503 ")) busy.incrementAndGet();
          } catch (IOException ignored) {
          }
        }));
      }
      for (Thread th : threads) th.join();
      check(ok.get() == 16 && busy.get() == 4, "20 at once: 16 answered, 4 refused with 503 (" + ok + "/" + busy + ")");
    }

    // The tab goes while a request waits: 502.
    Thread.sleep(100);
    check(get("/x").startsWith("HTTP/1.1 502 "), "the tab gone: 502");
    try (Tab tab = new Tab(req -> null)) {
      Thread waiter = Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(300);
          tab.close();
        } catch (Exception ignored) {
        }
      });
      long t0 = System.currentTimeMillis();
      String r = get("/never");
      check(r.startsWith("HTTP/1.1 502 ") && System.currentTimeMillis() - t0 < 1400, "the tab leaves with a request pending: 502 at once");
      waiter.join();
    }

    // Off: nothing is a stand-in, and the host is not resolved.
    TabRelay.on = false;
    check(!(Net.createSocket(SocketFactory.getDefault()) instanceof RelaySocket), "off: createSocket() is the factory's own");

    if (failures > 0) {
      System.out.println(failures + " tab relay check(s) failed");
      System.exit(1);
    }
    System.out.println("tab relay: all checks passed");
    System.exit(0);
  }
}
