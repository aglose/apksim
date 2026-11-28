package dev.apksim.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Checks the network tap (docs/network.md "The tap") the way translated app code meets it: sockets connected, layered
 * with TLS and read through Net's hooks, against servers on this machine (`-Dapksim.netCheckLoopback=true` makes
 * loopback count as remote). What must hold: capturing changes nothing the app can see (its socket is the one its
 * factory made, with the protocol the server picked), and what passed is in the recording: HTTP/1.1, HTTP/2 whose
 * header blocks the JDK's own HPACK encoder wrote, WebSocket messages, exchanges that failed, connections; that a rule
 * sends only its own host through the engine; and that a decoder that is stuck never makes the app wait.
 * Run by scripts/check-net-tap.sh; exits 1 on any failure.
 */
public final class TapCheck {
  private static int failures;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  private static SSLContext serverTls, clientTls;

  interface Handler {
    void serve(Socket s, InputStream in, OutputStream out) throws Exception;
  }

  /** A server on loopback: TLS offering `alpn` (null: plain TCP), each connection handed to `h`. */
  private static int serve(String[] alpn, Handler h) throws Exception {
    ServerSocket ss = alpn == null ? new ServerSocket(0, 20, InetAddress.getLoopbackAddress())
        : serverTls.getServerSocketFactory().createServerSocket(0, 20, InetAddress.getLoopbackAddress());
    if (ss instanceof SSLServerSocket tls && alpn.length > 0) {
      SSLParameters p = tls.getSSLParameters();
      p.setApplicationProtocols(alpn);
      tls.setSSLParameters(p);
    }
    Thread t = new Thread(() -> {
      while (true) {
        try {
          Socket c = ss.accept();
          Thread.ofVirtual().start(() -> {
            try (c) {
              if (c instanceof SSLSocket s) s.startHandshake();
              h.serve(c, c.getInputStream(), c.getOutputStream());
            } catch (Exception ignored) {
              // the client went away
            }
          });
        } catch (IOException e) {
          return;
        }
      }
    }, "check-server");
    t.setDaemon(true);
    t.start();
    return ss.getLocalPort();
  }

  /** As app code connects: a plain socket through Net.connect, TLS layered by the app's factory through Net.createSocket. */
  private static Socket connect(String host, int port, String[] alpn) throws IOException {
    Socket raw = new Socket();
    Net.connect(raw, new InetSocketAddress(host, port), 5000);
    if (alpn == null) return raw;
    SSLSocket s = (SSLSocket) Net.createSocket(clientTls.getSocketFactory(), raw, host, port, true);
    if (alpn.length > 0) {
      SSLParameters p = s.getSSLParameters();
      p.setApplicationProtocols(alpn);
      s.setSSLParameters(p);
    }
    s.startHandshake();
    return s;
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Reads up to and including the blank line that ends a head. */
  private static String head(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) >= 0) {
      b.write(c);
      byte[] a = b.toByteArray();
      int n = a.length;
      if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
    }
    return b.toString(StandardCharsets.ISO_8859_1);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> entries() {
    Net.drain(3000);
    Map<String, Object> root = (Map<String, Object>) Json.parse(new String(Net.captured(w -> true).get(), StandardCharsets.UTF_8));
    lastLog = (Map<String, Object>) root.get("log");
    return (List<Map<String, Object>>) lastLog.get("entries");
  }

  private static Map<String, Object> lastLog;

  @SuppressWarnings("unchecked")
  private static Map<String, Object> find(String urlPart) {
    for (Map<String, Object> e : entries()) {
      if (String.valueOf(((Map<String, Object>) e.get("request")).get("url")).contains(urlPart)) return e;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> req(Map<String, Object> e) {
    return (Map<String, Object>) e.get("request");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> resp(Map<String, Object> e) {
    return (Map<String, Object>) e.get("response");
  }

  @SuppressWarnings("unchecked")
  private static String body(Map<String, Object> e) {
    return String.valueOf(((Map<String, Object>) resp(e).get("content")).get("text"));
  }

  @SuppressWarnings("unchecked")
  private static String header(Map<String, Object> side, String name) {
    for (Object h : (List<Object>) side.get("headers")) {
      Map<String, Object> m = (Map<String, Object>) h;
      if (name.equalsIgnoreCase(String.valueOf(m.get("name")))) return String.valueOf(m.get("value"));
    }
    return null;
  }

  // ------------------------------------------------------------------ HTTP/2 frames, with the JDK's HPACK encoder

  /** jdk.internal.net.http.hpack.Encoder, by reflection (the check runs with --add-opens for it). */
  private static final class JdkHpack {
    private final Object encoder;
    private final Method header, encode;

    JdkHpack() throws Exception {
      Class<?> c = Class.forName("jdk.internal.net.http.hpack.Encoder");
      encoder = c.getConstructor(int.class).newInstance(4096);
      header = c.getMethod("header", CharSequence.class, CharSequence.class);
      encode = c.getMethod("encode", ByteBuffer.class);
    }

    byte[] block(String... nv) throws Exception {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteBuffer b = ByteBuffer.allocate(256);
      for (int i = 0; i < nv.length; i += 2) {
        header.invoke(encoder, nv[i], nv[i + 1]);
        boolean done;
        do {
          done = (Boolean) encode.invoke(encoder, b);
          b.flip();
          out.write(b.array(), 0, b.limit());
          b.clear();
        } while (!done);
      }
      return out.toByteArray();
    }
  }

  private static byte[] frame(int type, int flags, int stream, byte[] payload) {
    ByteBuffer b = ByteBuffer.allocate(9 + payload.length);
    b.put((byte) (payload.length >> 16)).put((byte) (payload.length >> 8)).put((byte) payload.length);
    b.put((byte) type).put((byte) flags).putInt(stream).put(payload);
    return b.array();
  }

  private static byte[] cat(byte[]... parts) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (byte[] p : parts) b.writeBytes(p);
    return b.toByteArray();
  }

  /** Issue 470: which connect failures make the engine try a host's other addresses. */
  private static void noRouteTable() {
    java.io.IOException[] yes = {new java.net.NoRouteToHostException("x"), new java.net.SocketException("Network is unreachable"),
        new java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)"),
        new java.net.SocketException("No route to host"), new java.net.SocketException("Cannot assign requested address")};
    java.io.IOException[] no = {new java.net.ConnectException("Connection refused"), new java.net.SocketTimeoutException("connect timed out"),
        new java.net.SocketException("Connection reset"), new java.io.IOException("Network is unreachable")};
    for (java.io.IOException e : yes) check(NetEngine.noRoute(e), "fallback to other addresses on " + e);
    for (java.io.IOException e : no) check(!NetEngine.noRoute(e), "no fallback on " + e);
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("tapcheck");
    Path ks = dir.resolve("ks.p12");
    new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-dname",
        "CN=localhost", "-keystore", ks.toString(), "-storepass", "changeit", "-storetype", "PKCS12", "-validity", "2")
        .redirectErrorStream(true).start().waitFor();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(ks)) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(store, "changeit".toCharArray());
    serverTls = SSLContext.getInstance("TLS");
    serverTls.init(kmf.getKeyManagers(), null, null);
    clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, new TrustManager[] {new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] c, String a) {}
      public void checkServerTrusted(X509Certificate[] c, String a) {}
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }}, null);

    noRouteTable();
    System.setProperty("apksim.networkInspect", "true");
    Net.configure();
    if (args.length == 2 && args[0].equals("allowed-loopback")) {
      allowedLoopback(Integer.parseInt(args[1]));
      System.out.println(failures == 0 ? "all ok" : failures + " FAILED");
      System.exit(failures == 0 ? 0 : 1);
    }
    check(Tap.on && Inspector.on && Net.engine == null, "the inspector and an in-memory capture are on, and no engine was started for them");

    http1();
    http2();
    webSocket();
    failures();
    jdkClient();
    opaque();
    routing();
    neverBlocks();

    System.out.println(failures == 0 ? "all ok" : failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }

  /**
   * Issue 440: run with APKSIM_BEAM_LOOPBACK=PORT and without apksim.netCheckLoopback, as a beamed instance whose owner
   * opened PORT is: a plain request to it is a row, a request to another loopback port is not read, and a loopback connect that fails is a row.
   */
  private static void allowedLoopback(int port) throws Exception {
    System.out.println("== allow-loopback");
    ServerSocket other = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    ServerSocket open = new ServerSocket(port, 5, InetAddress.getLoopbackAddress());
    for (ServerSocket ss : new ServerSocket[] {open, other}) {
      Thread t = new Thread(() -> {
        try (Socket c = ss.accept()) {
          head(c.getInputStream());
          c.getOutputStream().write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"));
          c.getOutputStream().flush();
          Thread.sleep(300);
        } catch (Exception ignored) {
        }
      });
      t.setDaemon(true);
      t.start();
    }
    for (int p : new int[] {port, other.getLocalPort()}) {
      Socket s = new Socket();
      Net.connect(s, new InetSocketAddress("127.0.0.1", p), 5000);
      InputStream in = Net.inputStream(s);
      Net.outputStream(s).write(ascii("GET /loop" + p + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"));
      head(in);
      new DataInputStream(in).readFully(new byte[2]);
      s.close();
    }
    Net.drain(2000);
    check(find("/loop" + port) != null && "ok".equals(body(find("/loop" + port))), "a request to the opened port is an exchange in the inspector");
    check(find("/loop" + other.getLocalPort()) == null, "another loopback port stays untouched");
    // A loopback connect that fails is a row all the same: a dev server that isn't running says so in the inspector.
    ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    int dead = closed.getLocalPort();
    closed.close();
    try {
      Net.connect(new Socket(), new InetSocketAddress("127.0.0.1", dead), 5000);
      check(false, "a loopback connect to a closed port fails");
    } catch (IOException expected) {
      check(Inspector.eventsForCheck().stream().anyMatch(e -> e.contains("\"CONNECT\"") && e.contains("127.0.0.1:" + dead) && e.contains("\"status\":0")),
          "a loopback connect that failed is a row in the inspector");
    }
  }

  private static void http1() throws Exception {
    System.out.println("== HTTP/1.1");
    ByteArrayOutputStream gz = new ByteArrayOutputStream();
    try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
      g.write(ascii("{\"zipped\":true}"));
    }
    byte[] zipped = gz.toByteArray();
    int port = serve(new String[] {"h2", "http/1.1"}, (s, in, out) -> {
      String h = head(in);
      out.write(ascii("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nSet-Cookie: sid=secret\r\nContent-Length: 5\r\n\r\nhello"));
      out.flush();
      h = head(in);
      new DataInputStream(in).readFully(new byte[7]);
      // chunked and gzip, in two chunks, with a 100 Continue ahead of it
      out.write(ascii("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n"));
      out.write(ascii(Integer.toHexString(4) + "\r\n"));
      out.write(zipped, 0, 4);
      out.write(ascii("\r\n" + Integer.toHexString(zipped.length - 4) + ";ext=1\r\n"));
      out.write(zipped, 4, zipped.length - 4);
      out.write(ascii("\r\n0\r\nX-Trailer: 1\r\n\r\n"));
      out.flush();
      head(in);
      out.write(ascii("HTTP/1.1 204 No Content\r\n\r\n"));
      out.flush();
    });
    Socket s = connect("localhost", port, new String[] {"http/1.1"});
    check(s.getClass().getName().startsWith("sun.security.ssl."), "the app's TLS socket is the one its factory made (" + s.getClass().getSimpleName() + ")");
    check("http/1.1".equals(((SSLSocket) s).getApplicationProtocol()), "the server's ALPN choice reaches the app");
    InputStream in = Net.inputStream(s);
    OutputStream out = Net.outputStream(s);
    out.write(ascii("GET /one?x=1 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer token\r\n\r\n"));
    out.flush();
    head(in);
    new DataInputStream(in).readFully(new byte[5]);
    // a head and a body written in pieces, across the tap's copies
    out.write(ascii("POST /two HTTP/1.1\r\nHost: localhost\r\nCont"));
    out.write(ascii("ent-Length: 7\r\nContent-Type: text/plain\r\n\r\nabc"));
    out.write(ascii("defg"));
    out.flush();
    head(in);  // the 100
    String h2 = head(in);
    byte[] chunkedBody = new byte[zipped.length + 64];
    int got = 0;
    while (!new String(chunkedBody, 0, got, StandardCharsets.ISO_8859_1).endsWith("0\r\nX-Trailer: 1\r\n\r\n")) got += in.read(chunkedBody, got, chunkedBody.length - got);
    out.write(ascii("HEAD /three HTTP/1.1\r\nHost: localhost\r\n\r\n"));
    out.flush();
    head(in);
    s.close();

    Map<String, Object> one = find("/one"), two = find("/two"), three = find("/three");
    check(one != null && "https://localhost/one?x=1".equals(req(one).get("url")) && "GET".equals(req(one).get("method")), "GET: the URL from the Host header");
    check(one != null && "hello".equals(body(one)) && ((Number) resp(one).get("status")).intValue() == 200, "GET: status and body");
    check(one != null && "REDACTED".equals(header(req(one), "Authorization")) && "REDACTED".equals(header(resp(one), "Set-Cookie")), "Authorization and Set-Cookie are redacted");
    check(two != null && ((Number) resp(two).get("status")).intValue() == 201 && "{\"zipped\":true}".equals(body(two)), "POST: the 100 Continue skipped, a chunked gzip body decoded" + (two == null ? "" : " (" + body(two) + ")"));
    check(two != null && "abcdefg".equals(((Map<?, ?>) req(two).get("postData")).get("text")) && Recording.sha256(ascii("abcdefg")).equals(req(two).get("_bodySha256")), "POST: the request body, and its hash");
    check(three != null && ((Number) resp(three).get("status")).intValue() == 204 && "HEAD".equals(req(three).get("method")), "HEAD: no body waited for");
    check(one != null && two != null && one.get("connection") != null && one.get("connection").equals(two.get("connection")), "both went over one connection");
    check(lastLog.get("_apksimCertificates") instanceof Map<?, ?> certs && certs.containsKey("localhost"), "the server's certificates are in the recording");
    List<?> conns = (List<?>) lastLog.get("_apksimConnections");
    Map<?, ?> conn = conns == null || conns.isEmpty() ? Map.of() : (Map<?, ?>) conns.get(conns.size() - 1);
    check("http/1.1".equals(conn.get("protocol")) && conn.get("tls") instanceof Map<?, ?> t && String.valueOf(t.get("version")).startsWith("TLS") && "http/1.1".equals(t.get("alpn")),
        "the connection is listed with its TLS version and ALPN choice (" + conn + ")");
  }

  private static void http2() throws Exception {
    System.out.println("== HTTP/2");
    JdkHpack serverHpack = new JdkHpack(), clientHpack = new JdkHpack();
    byte[] big = new byte[40_000];
    for (int i = 0; i < big.length; i++) big[i] = (byte) ('a' + i % 26);
    // The server's side, written out ahead: SETTINGS, then stream 1 (headers split over a CONTINUATION, DATA in two
    // frames, one padded), stream 3 (gRPC: headers, a message, trailers), and stream 5 left open.
    byte[] h1 = serverHpack.block(":status", "200", "content-type", "text/plain", "x-long", "v".repeat(300), "set-cookie", "a=b");
    byte[] padded = cat(new byte[] {4}, java.util.Arrays.copyOfRange(big, 30_000, 40_000), new byte[4]);
    byte[] answers = cat(
        frame(4, 0, 0, new byte[0]),
        frame(1, 0, 1, java.util.Arrays.copyOfRange(h1, 0, 10)), frame(9, 0x4, 1, java.util.Arrays.copyOfRange(h1, 10, h1.length)),
        frame(1, 0x4, 3, serverHpack.block(":status", "200", "content-type", "application/grpc")),
        frame(0, 0, 1, java.util.Arrays.copyOfRange(big, 0, 30_000)),
        frame(0, 0, 3, new byte[] {0, 0, 0, 0, 2, 8, 1}),
        frame(0, 0x1 | 0x8, 1, padded),
        frame(1, 0x4 | 0x1, 3, serverHpack.block("grpc-status", "0", "grpc-message", "fine")),
        frame(1, 0x4, 5, serverHpack.block(":status", "200", "content-type", "text/event-stream")),
        frame(0, 0, 5, ascii("data: tick\n\n")));
    int[] clientBytes = {0};
    int port = serve(new String[] {"h2"}, (s, in, out) -> {
      // Answers once the client's requests are in (it says how many bytes they are), then holds the connection.
      byte[] buf = new byte[8192];
      int n = 0;
      while (clientBytes[0] == 0 || n < clientBytes[0]) {
        int r = in.read(buf);
        if (r < 0) return;
        n += r;
      }
      out.write(answers);
      out.flush();
      while (in.read(buf) >= 0) {
        // until the client closes
      }
    });
    Socket s = connect("localhost", port, new String[] {"h2", "http/1.1"});
    check("h2".equals(((SSLSocket) s).getApplicationProtocol()), "offering h2 and http/1.1 while capturing: the server's choice, h2 (issue 367)");
    InputStream in = Net.inputStream(s);
    OutputStream out = Net.outputStream(s);
    byte[] requests = cat(TapHttp2.PREFACE, frame(4, 0, 0, new byte[0]),
        frame(1, 0x4 | 0x1, 1, clientHpack.block(":method", "GET", ":scheme", "https", ":authority", "h2.test", ":path", "/big", "authorization", "Bearer t", "accept", "*/*")),
        frame(1, 0x4, 3, clientHpack.block(":method", "POST", ":scheme", "https", ":authority", "h2.test", ":path", "/pkg.Service/Call", "content-type", "application/grpc", "te", "trailers", "accept", "*/*")),
        frame(0, 0x1, 3, new byte[] {0, 0, 0, 0, 1, 7}),
        frame(1, 0x4 | 0x1, 5, clientHpack.block(":method", "GET", ":scheme", "https", ":authority", "h2.test", ":path", "/events", "accept", "*/*")));
    clientBytes[0] = requests.length;
    // in odd pieces, so frames and header blocks straddle the tap's copies
    for (int off = 0; off < requests.length; ) {
      int n = Math.min(requests.length - off, 7 + off % 23);
      out.write(requests, off, n);
      off += n;
    }
    out.flush();
    new DataInputStream(in).readFully(new byte[answers.length]);

    Map<String, Object> bigE = find("/big"), grpc = find("/pkg.Service/Call");
    check(bigE != null && "https://h2.test/big".equals(req(bigE).get("url")) && "HTTP/2".equals(req(bigE).get("httpVersion")), "stream 1: method, URL and version from the pseudo-headers");
    check(bigE != null && new String(big, StandardCharsets.ISO_8859_1).equals(body(bigE)), "stream 1: 40,000 bytes of DATA in two frames, one padded, interleaved with another stream");
    check(bigE != null && "v".repeat(300).equals(header(resp(bigE), "x-long")) && "REDACTED".equals(header(resp(bigE), "set-cookie")) && "REDACTED".equals(header(req(bigE), "authorization")),
        "stream 1: a header block over a CONTINUATION (Huffman, as the JDK's encoder writes it), cookies and authorization redacted");
    check(grpc != null && "*/*".equals(header(req(grpc), "accept")) && "trailers".equals(header(req(grpc), "te")), "stream 3: a header the first request added to the dynamic table, read back from it");
    check(grpc != null && resp(grpc).get("_apksimTrailers") instanceof List<?> t && t.toString().contains("grpc-status") && t.toString().contains("fine"), "stream 3: gRPC's trailers are kept");
    check(grpc != null && "AAAAAAEH".equals(((Map<?, ?>) req(grpc).get("postData")).get("text")) && "AAAAAAIIAQ==".equals(body(grpc)), "stream 3: the gRPC messages, both ways");
    Map<String, Object> events = find("/events");
    check(events != null && Boolean.TRUE.equals(events.get("_apksimUnfinished")) && "data: tick\n\n".equals(body(events)), "stream 5, still open: in the recording as it stands, marked unfinished");
    check(Net.inFlight() == 1, "one request is in flight (" + Net.inFlight() + ")");
    // The app cancels the open stream, then the connection goes.
    out.write(frame(3, 0, 5, new byte[] {0, 0, 0, 8}));
    out.flush();
    events = find("/events");
    check(events != null && String.valueOf(events.get("_apksimError")).contains("CANCEL") && events.get("_apksimUnfinished") == null, "RST_STREAM by the app: the exchange is closed with the reason (" + (events == null ? null : events.get("_apksimError")) + ")");
    s.close();
    Recording replay = new Recording(null, new Recording.Match(java.util.Set.of("method", "url", "body"), java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));
    Recording.Entry failed = new Recording.Entry();
    failed.method = "GET";
    failed.url = "https://h2.test/events";
    failed.error = "reset";
    replay.add(failed);
    check(replay.find("GET", "https://h2.test/events", List.of(), null) == null, "an exchange that failed is never what replay answers with");
  }

  private static void webSocket() throws Exception {
    System.out.println("== WebSocket");
    Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    byte[] text = ascii("{\"compressed\":\"" + "z".repeat(200) + "\"}");
    d.setInput(text);
    byte[] buf = new byte[512];
    int n = d.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
    byte[] deflated = java.util.Arrays.copyOf(buf, n - 4);  // without the 00 00 ff ff, as permessage-deflate sends it
    int port = serve(null, (s, in, out) -> {
      head(in);
      out.write(ascii("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"));
      out.write(cat(new byte[] {(byte) 0x81, 5}, ascii("hello")));   // a text frame right behind the 101
      out.flush();
      new DataInputStream(in).readFully(new byte[6 + 4]);
      out.write(cat(new byte[] {(byte) 0xc1, (byte) deflated.length}, deflated));  // compressed (RSV1)
      out.write(cat(new byte[] {0x02, 2, 1, 2}, new byte[] {(byte) 0x80, 1, 3}));  // binary, in two frames
      out.write(new byte[] {(byte) 0x88, 2, 0x03, (byte) 0xe8});  // close 1000
      out.flush();
    });
    Socket s = connect("localhost", port, null);
    InputStream in = Net.inputStream(s);
    OutputStream out = Net.outputStream(s);
    out.write(ascii("GET /socket HTTP/1.1\r\nHost: ws.test\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: x\r\n\r\n"));
    out.flush();
    head(in);
    new DataInputStream(in).readFully(new byte[7]);
    byte[] mask = {1, 2, 3, 4};
    byte[] masked = ascii("ping!!");
    for (int i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    out.write(cat(new byte[] {(byte) 0x81, (byte) (0x80 | 6)}, mask, masked));
    out.flush();
    new DataInputStream(in).readFully(new byte[2 + deflated.length + 4 + 3 + 4]);
    s.close();
    Map<String, Object> e = find("/socket");
    List<?> ms = e == null ? null : (List<?>) e.get("_webSocketMessages");
    check(e != null && ((Number) resp(e).get("status")).intValue() == 101 && "http://ws.test/socket".equals(req(e).get("url")), "the upgrade is an entry of its own");
    check(ms != null && ms.size() == 5, "five messages (" + (ms == null ? null : ms.size()) + ")");
    if (ms != null && ms.size() == 5) {
      Map<?, ?> m0 = (Map<?, ?>) ms.get(0), m1 = (Map<?, ?>) ms.get(1), m2 = (Map<?, ?>) ms.get(2), m3 = (Map<?, ?>) ms.get(3), m4 = (Map<?, ?>) ms.get(4);
      check("receive".equals(m0.get("type")) && "hello".equals(m0.get("data")), "a text frame right behind the 101");
      check("send".equals(m1.get("type")) && "ping!!".equals(m1.get("data")), "the app's frame, unmasked");
      check(new String(text, StandardCharsets.ISO_8859_1).equals(m2.get("data")), "a permessage-deflate message, inflated");
      check(((Number) m3.get("opcode")).intValue() == 2 && "AQID".equals(m3.get("data")), "a binary message from two frames");
      check(((Number) m4.get("opcode")).intValue() == 8, "the close frame");
    }
  }

  private static void failures() throws Exception {
    System.out.println("== failures");
    long failed0 = NetFailures.count();
    int port = serve(new String[0], (s, in, out) -> {
      head(in);
      out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nonly this much"));
      out.flush();
    });
    Socket s = connect("localhost", port, new String[0]);
    InputStream in = Net.inputStream(s);
    OutputStream out = Net.outputStream(s);
    out.write(ascii("GET /cut HTTP/1.1\r\nHost: fail.test\r\n\r\n"));
    out.flush();
    while (in.read() >= 0) {
      // until the server closes, 86 bytes short
    }
    s.close();
    Map<String, Object> cut = find("/cut");
    check(cut != null && cut.get("_apksimError") != null && "only this much".equals(body(cut)), "a body cut short is kept with what came and why it ended (" + (cut == null ? null : cut.get("_apksimError")) + ")");
    check(NetFailures.count() > failed0, "an exchange the tap saw end without its answer counts as a failed request (network failures)");
    int silent = serve(null, (s2, in2, out2) -> {
      head(in2);
      Thread.sleep(3000);  // never answers
    });
    Socket t = connect("localhost", silent, null);
    Net.outputStream(t).write(ascii("GET /silence HTTP/1.1\r\nHost: fail.test\r\n\r\n"));
    t.setSoTimeout(200);
    try {
      Net.inputStream(t).read();
    } catch (java.net.SocketTimeoutException expected) {
      // the app gives up
    }
    t.close();
    Thread.sleep(2500);  // closed without a read or write to say so: the tap notices on its own
    Map<String, Object> silence = find("/silence");
    check(silence != null && String.valueOf(silence.get("_apksimError")).contains("SocketTimeoutException") && ((Number) resp(silence).get("status")).intValue() == 0,
        "a request that never got an answer is kept, with the timeout that ended it (" + (silence == null ? null : silence.get("_apksimError")) + ")");
    // Issue 440: TLS the app's factory refuses (an untrusted certificate) leaves a row, with the reason.
    int selfSigned = serve(new String[] {"http/1.1"}, (s3, in3, out3) -> Thread.sleep(500));
    Socket raw = new Socket();
    Net.connect(raw, new InetSocketAddress("localhost", selfSigned), 5000);
    SSLSocket refused = (SSLSocket) Net.createSocket((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLContext.getDefault().getSocketFactory(), raw, "localhost", selfSigned, true);
    try {
      refused.startHandshake();
      check(false, "an untrusted certificate fails the handshake");
    } catch (IOException expected) {
      refused.close();
    }
    Thread.sleep(2500);
    Net.drain(2000);
    check(Inspector.eventsForCheck().stream().anyMatch(e -> e.contains("\"CONNECT\"") && e.contains(":" + selfSigned)),
        "a TLS handshake that failed is a row in the inspector");
    ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    int dead = closed.getLocalPort();
    closed.close();
    try {
      connect("localhost", dead, null);
      check(false, "a connect to a closed port fails");
    } catch (IOException expected) {
      Net.drain(2000);
      check(Inspector.eventsForCheck().stream().anyMatch(e -> e.contains("\"CONNECT\"") && e.contains(":" + dead)), "a connect that failed is a row in the inspector");
      String said = Net.command(new String[] {"network", "failures"});
      check(said.matches("\\d+ failed: .*") && said.contains(":" + dead + " connect failed") && !said.contains("the app reset"),
          "`network failures` names the connect that failed, and not the stream the app cancelled (" + said + ")");
    }
  }

  private static void jdkClient() throws Exception {
    System.out.println("== HttpsURLConnection");
    int port = serve(new String[0], (s, in, out) -> {
      while (true) {
        String h = head(in);
        if (h.isEmpty()) return;
        out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\njdk"));
        out.flush();
      }
    });
    HttpsURLConnection c = (HttpsURLConnection) Net.openConnection(new URL("https://localhost:" + port + "/from-jdk"));
    Net.setSSLSocketFactory(c, clientTls.getSocketFactory());
    c.setHostnameVerifier((h, session) -> true);
    String got = new String(c.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    check("jdk".equals(got), "the JDK's client gets its answer");
    Map<String, Object> e = find("/from-jdk");
    check(e != null && "jdk".equals(body(e)) && String.valueOf(req(e).get("url")).startsWith("https://localhost:" + port), "and the exchange is captured, with no proxy in between");
    check(Net.engine == null, "still no engine");
  }

  private static void opaque() throws Exception {
    System.out.println("== not HTTP");
    int port = serve(null, (s, in, out) -> {
      byte[] b = new byte[4];
      new DataInputStream(in).readFully(b);
      out.write(new byte[] {9, 8, 7, 6, 5});
      out.flush();
    });
    Socket s = connect("localhost", port, null);
    Net.outputStream(s).write(new byte[] {0, 1, 2, 3});
    byte[] back = new byte[5];
    new DataInputStream(Net.inputStream(s)).readFully(back);
    s.close();
    check(back[0] == 9 && back[4] == 5, "bytes that are not HTTP pass as they are");
    Thread.sleep(2500);
    Net.drain(2000);
    check(Inspector.eventsForCheck().stream().anyMatch(e -> e.contains(":" + port) && e.contains("\"CONNECT\"")), "and the connection is a row of its own");
  }

  private static void routing() throws Exception {
    System.out.println("== rules send only their own host through the engine");
    check(Net.route("api.example.com") == Net.Route.DIRECT, "no rule: direct");
    String b64 = java.util.Base64.getEncoder().encodeToString(ascii("{}"));
    String r = Net.command(new String[] {"network", "rule", "add", "GET", "https://api.example.com/v1/*", "200", "0", "application/json", b64});
    check(r.startsWith("{") && Net.route("api.example.com") == Net.Route.MITM && Net.route("API.example.com") == Net.Route.MITM, "a rule naming a host: that host is answered by the engine");
    check(Net.route("cdn.example.com") == Net.Route.DIRECT && Net.route("grpc.example.com") == Net.Route.DIRECT, "every other host stays direct");
    Net.command(new String[] {"network", "rule", "clear"});
    check(Net.route("api.example.com") == Net.Route.DIRECT, "rules cleared: direct again");
    Net.command(new String[] {"network", "rule", "add", "*", "/v1/items", "0", "250", "-", "-"});
    check(Net.route("anything.example.org") == Net.Route.MITM, "a pattern that names no host concerns every host");
    Net.command(new String[] {"network", "rule", "clear"});
    check(Inspector.Rule.hostOf("https://a.b/c") != null && Inspector.Rule.hostOf("https://a.b:8443/c*").equals("a.b") && Inspector.Rule.hostOf("*://a.b/c") == null
        && Inspector.Rule.hostOf("https://a.b") == null && Inspector.Rule.hostOf("a.b/c") == null && Inspector.Rule.hostOf("https://*.b/c") == null,
        "which host a pattern names");
    Net.command(new String[] {"network", "latency", "40"});
    check(Net.route("api.example.com") == Net.Route.RELAY && Net.engine != null, "network conditions: every host is relayed, and the engine starts for it");
    // Relayed, the TLS is still the app's own, end to end, and still read.
    int port = serve(new String[] {"h2", "http/1.1"}, (s, in, out) -> {
      head(in);
      out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\nrelayed"));
      out.flush();
    });
    long t0 = System.nanoTime();
    Socket s = connect("localhost", port, new String[] {"http/1.1"});
    check(!(s instanceof FakeTlsSocket) && "http/1.1".equals(((SSLSocket) s).getApplicationProtocol()), "a relayed connection keeps the app's own TLS and ALPN");
    Net.outputStream(s).write(ascii("GET /shaped HTTP/1.1\r\nHost: relay.test\r\n\r\n"));
    InputStream in = Net.inputStream(s);
    head(in);
    new DataInputStream(in).readFully(new byte[7]);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    s.close();
    Map<String, Object> shaped = find("/shaped");
    check(shaped != null && "relayed".equals(body(shaped)), "and the tap reads it on the app's side");
    check(ms >= 40, "the latency was added (" + ms + " ms)");
    Net.command(new String[] {"network", "online"});
    check(Net.route("api.example.com") == Net.Route.DIRECT, "online again: direct");
  }

  private static void neverBlocks() throws Exception {
    System.out.println("== a stuck decoder never makes the app wait");
    int port = serve(null, (s, in, out) -> {
      byte[] b = new byte[64 * 1024];
      while (in.read(b) >= 0) {
        // a sink
      }
    });
    Socket s = connect("localhost", port, null);
    OutputStream out = Net.outputStream(s);
    out.write(ascii("POST /upload HTTP/1.1\r\nHost: sink.test\r\nContent-Length: 100000000\r\n\r\n"));
    Thread stall = Thread.ofVirtual().start(() -> Tap.sync(() -> {
      try {
        Thread.sleep(4000);
      } catch (InterruptedException e) {
        // done
      }
    }, 10_000));
    Thread.sleep(100);
    byte[] block = new byte[64 * 1024];
    long t0 = System.nanoTime();
    long sent = 0;
    while (sent < 80L << 20) {
      out.write(block);
      sent += block.length;
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    check(ms < 3500, "80 MB written while the decoder stood still for 4 s: " + ms + " ms");
    s.close();
    stall.join();
    Net.drain(5000);
    Map<String, Object> upload = find("/upload");
    check(upload != null && String.valueOf(upload.get("_apksimError")).contains("fell behind"), "the connection's capture was given up, and says so (" + (upload == null ? null : upload.get("_apksimError")) + ")");
    Socket again = connect("localhost", port, null);
    Net.outputStream(again).write(ascii("GET /after HTTP/1.1\r\nHost: sink.test\r\n\r\n"));
    again.close();
    Thread.sleep(2500);
    check(find("/after") != null, "connections made afterwards are captured again");
  }
}
