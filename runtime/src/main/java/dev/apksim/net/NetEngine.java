package dev.apksim.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * apksim's in-process network: the connections Net redirects arrive here on a loopback port, each saying where it
 * was going ("APKSIM host port timeout"), or as an HTTP proxy request (the JDK's HttpURLConnection). Recording, it
 * makes the real connection and relays each HTTP/1.1 exchange while capturing it; replaying, it answers from the
 * recording; otherwise it relays bytes, shaped by Net.conditions (docs/network.md).
 */
final class NetEngine {
  /** Bodies bigger than this are relayed but not recorded. */
  private static final int MAX_BODY = 32 << 20;
  private ServerSocket server;
  /** Live connections, both sides: going offline closes the client's and the server's. */
  private final Set<Conn> open = ConcurrentHashMap.newKeySet();
  final AtomicInteger recorded = new AtomicInteger(), replayed = new AtomicInteger(), missed = new AtomicInteger(),
      relayed = new AtomicInteger();
  private SSLSocketFactory upstreamTls;
  private volatile boolean dirty;
  private volatile boolean flushPending;

  void start() throws IOException {
    server = new ServerSocket(0, 128, InetAddress.getLoopbackAddress());
    Thread t = new Thread(this::accept, "apksim-net");
    t.setDaemon(true);
    t.start();
  }

  InetSocketAddress address() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
  }

  private void accept() {
    while (true) {
      try {
        Socket s = server.accept();
        s.setTcpNoDelay(true);
        Thread.ofVirtual().name("apksim-net-conn").start(() -> serve(s));
      } catch (IOException e) {
        if (server.isClosed()) return;
      }
    }
  }

  private void serve(Socket client) {
    Conn c = new Conn(client);
    open.add(c);
    try {
      c.run();
    } catch (IOException e) {
      if (Boolean.getBoolean("apksim.networkTrace")) Net.log("network: " + c.host + ":" + c.port + " " + e);
    } catch (RuntimeException e) {
      Net.log("network: " + c.host + ":" + c.port + " failed: " + e);
    } finally {
      open.remove(c);
      c.close();
    }
  }

  /** Going offline: every connection drops, as when Wi-Fi goes away, upstream too (a worker blocked on it wakes). */
  void closeAll() {
    for (Conn c : open) c.close();
  }

  String counts() {
    return switch (Net.mode) {
      case RECORD -> recorded.get() + " recorded";
      case REPLAY -> replayed.get() + " replayed, " + missed.get() + " unmatched";
      case SHAPE -> relayed.get() + " connections relayed";
    };
  }

  // ------------------------------------------------------------------ saving

  private void changed() {
    dirty = true;
    if (flushPending) return;
    flushPending = true;
    Thread.ofVirtual().start(() -> {
      Conditions.sleep(300);
      flushPending = false;
      flushNow();
    });
  }

  synchronized void flushNow() {
    if (Net.mode != Net.Mode.RECORD) return;
    try {
      if (dirty || !Files.exists(Net.recording.path)) {
        dirty = false;
        Net.recording.save();
      }
    } catch (IOException e) {
      Net.log("network record: cannot write " + Net.recording.path + ": " + e);
    }
  }

  // ------------------------------------------------------------------ upstream

  private synchronized SSLSocketFactory upstreamTls() throws Exception {
    if (upstreamTls != null) return upstreamTls;
    SSLContext ctx;
    try {
      ctx = SSLContext.getInstance("TLS", "SunJSSE");
    } catch (Exception e) {
      ctx = SSLContext.getInstance("TLS");
    }
    if (Net.trust == null) {
      ctx.init(null, null, null);
    } else {
      KeyStore ks = KeyStore.getInstance(java.nio.file.Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toFile(), (char[]) null);
      int n = 0;
      try (InputStream in = Files.newInputStream(Net.trust)) {
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("apksim-trust-" + n++, c);
      }
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      ctx.init(null, tmf.getTrustManagers(), null);
    }
    upstreamTls = ctx.getSocketFactory();
    return upstreamTls;
  }

  static InetSocketAddress target(String host, int port) {
    InetSocketAddress m = Net.hostMap.get(host.toLowerCase(Locale.ROOT));
    if (m != null) return new InetSocketAddress(m.getHostString(), m.getPort() > 0 ? m.getPort() : port);
    return new InetSocketAddress(host, port);
  }

  // ------------------------------------------------------------------ one client connection

  private final class Conn {
    final Socket client;
    InputStream cin;
    OutputStream cout;
    String host = "?";
    /** The name TLS is for: FakeTlsSocket's, else the destination's. */
    String tlsHost;
    int port;
    int timeout = 10_000;
    boolean tls;
    Socket up;
    InputStream uin;
    OutputStream uout;
    long connectMs = -1;
    int exchanges;

    Conn(Socket client) {
      this.client = client;
    }

    /** Closed for good (offline, or the client is done): no upstream may be opened after this. */
    volatile boolean closed;

    void close() {
      closed = true;
      try {
        client.close();
      } catch (IOException ignored) {
      }
      closeUpstream();
    }

    synchronized void closeUpstream() {
      if (up == null) return;
      try {
        up.close();
      } catch (IOException ignored) {
      }
      up = null;
    }

    /** Takes a new upstream socket, unless the connection was closed meanwhile (then closes it and throws). */
    synchronized void adopt(Socket s) throws IOException {
      if (closed) {
        s.close();
        throw new java.net.SocketException("connection closed");
      }
      up = s;
    }

    void run() throws IOException {
      cin = new BufferedInputStream(client.getInputStream());
      cout = client.getOutputStream();
      byte[] firstBytes = readLineBytes(cin);
      if (firstBytes == null) return;
      String first = trimEol(firstBytes);
      byte[] pending = null;  // a request line already read
      boolean proxied = false;
      if (first.startsWith("APKSIM ")) {
        String[] p = first.split(" ");
        host = p[1];
        port = Integer.parseInt(p[2]);
        if (p.length > 3 && Integer.parseInt(p[3]) > 0) timeout = Integer.parseInt(p[3]);
        String err = connectUpstream();
        cout.write((err != null ? err : "OK\r\n").getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
        if (err != null) return;
      } else if (first.startsWith("CONNECT ")) {
        Conditions.sleep(Net.conditions.rtt());  // the TCP handshake Net.connect would have waited for
        String hp = first.split(" ")[1];
        int colon = hp.lastIndexOf(':');
        host = hp.substring(0, colon);
        port = Integer.parseInt(hp.substring(colon + 1));
        while (true) {
          String h = Net.readLine(cin);
          if (h == null || h.isEmpty()) break;
        }
        String err = connectUpstream();
        cout.write((err != null ? "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nX-Apksim-Error: " + err.trim() + "\r\n\r\n"
            : "HTTP/1.1 200 Connection established\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
        if (err != null) return;
      } else {
        // An HTTP proxy request, absolute form: GET http://host:port/path HTTP/1.1.
        String[] p = first.split(" ");
        if (p.length < 3 || !p[1].startsWith("http://")) return;
        URI u = URI.create(p[1]);
        host = u.getHost();
        port = u.getPort() > 0 ? u.getPort() : 80;
        pending = firstBytes;
        proxied = true;
        Conditions.sleep(Net.conditions.rtt());
        if (Net.mode != Net.Mode.REPLAY || Net.conditions.offline()) {
          String err = connectUpstream();
          if (err != null) {
            cout.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nX-Apksim-Error: " + err.trim() + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
            return;
          }
        }
      }

      tlsHost = host;
      if (Net.mode == Net.Mode.SHAPE) {
        relayed.incrementAndGet();
        if (pending != null) uout.write(originForm(pending));
        relay(cin, cout, uin, uout, client, up);
        return;
      }

      if (pending == null) {
        // Next: a TLS hello from FakeTlsSocket, or the first request.
        pending = nextLine();
        if (pending == null) return;
        String l = trimEol(pending);
        if (l.startsWith("APKSIM-TLS")) {
          tls = true;
          // The name the client's TLS checks: not the TCP destination, which may be an address.
          String[] p = l.split(" ");
          if (p.length > 1 && !p[1].isEmpty()) tlsHost = p[1];
          String reply = handshake();
          cout.write(reply.getBytes(StandardCharsets.ISO_8859_1));
          cout.flush();
          if (reply.startsWith("ERR")) return;
          pending = nextLine();
          if (pending == null) return;
        }
      }
      exchanges(pending, proxied);
    }

    /**
     * The client's next line, however long it stays idle first (a client may connect ahead and send later). Recording,
     * a server that speaks first (not HTTP) gets an empty line instead, so the connection is relayed.
     */
    byte[] nextLine() throws IOException {
      client.setSoTimeout(500);
      try {
        while (true) {
          try {
            cin.mark(1);
            if (cin.read() < 0) return null;
            cin.reset();
            break;
          } catch (SocketTimeoutException e) {
            if (Net.mode == Net.Mode.RECORD && upstreamPeek() > 0) return new byte[0];
          }
        }
      } finally {
        if (!client.isClosed()) client.setSoTimeout(0);
      }
      return readStartLine();
    }

    /**
     * A first line, read only while it can still be an HTTP request line or FakeTlsSocket's hello (a method of capital
     * letters, a space, then text up to the line's end): a binary protocol that sends a few bytes and waits stops it
     * at once, with those bytes, rather than waiting for a newline that never comes.
     */
    byte[] readStartLine() throws IOException {
      ByteArrayOutputStream b = new ByteArrayOutputStream(128);
      boolean method = true;
      int c;
      while ((c = cin.read()) != -1) {
        b.write(c);
        if (c == '\n') break;
        if (method) {
          if (c == ' ' && b.size() > 1) method = false;
          else if (!(c >= 'A' && c <= 'Z') && c != '-' || b.size() > 32) break;  // cannot be a method
        } else if (c < 0x20 && c != '\r' && c != '\t' || b.size() > 16 * 1024) {
          break;
        }
      }
      return b.size() == 0 ? null : b.toByteArray();
    }

    /** Without blocking: 1 if the server has sent something, -1 if it closed the connection, 0 if neither. */
    int upstreamPeek() throws IOException {
      if (up == null || up.isClosed()) return -1;
      up.setSoTimeout(1);
      try {
        uin.mark(1);
        if (uin.read() < 0) return -1;
        uin.reset();
        return 1;
      } catch (SocketTimeoutException e) {
        return 0;
      } finally {
        if (!up.isClosed()) up.setSoTimeout(0);
      }
    }

    /** Connects to where the client was going (not when replaying); an "ERR ..." reply on failure, else null. */
    String connectUpstream() {
      if (Net.conditions.offline()) return "ERR ConnectException connect failed: ENETUNREACH (Network is unreachable)\r\n";
      if (Net.mode == Net.Mode.REPLAY) return null;
      long t0 = System.nanoTime();
      try {
        if (closed) throw new java.net.SocketException("connection closed");
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(target(host, port), timeout);
        adopt(s);
        uin = new BufferedInputStream(s.getInputStream());
        uout = s.getOutputStream();
        connectMs = (System.nanoTime() - t0) / 1_000_000;
        return null;
      } catch (IOException e) {
        return Net.errorReply(e);
      }
    }

    /** FakeTlsSocket's handshake: the server's certificates, from the real server or the recording. */
    String handshake() {
      Conditions c = Net.conditions;
      Conditions.sleep(c.rtt());
      if (Net.mode == Net.Mode.REPLAY) {
        List<String> chain = Net.recording.certificates(tlsHost);
        return "OK " + (chain == null ? "" : String.join(",", chain)) + "\r\n";
      }
      try {
        List<String> chain = upgradeUpstream();
        Net.recording.certificates(tlsHost, chain);
        return "OK " + String.join(",", chain) + "\r\n";
      } catch (Exception e) {
        Net.log("network record: TLS to " + tlsHost + ":" + port + " failed: " + e.getMessage());
        return Net.errorReply(e);
      }
    }

    List<String> upgradeUpstream() throws Exception {
      SSLSocket s = (SSLSocket) upstreamTls().createSocket(up, tlsHost, port, true);
      SSLParameters params = s.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      s.setSSLParameters(params);
      s.startHandshake();
      adopt(s);
      uin = new BufferedInputStream(s.getInputStream());
      uout = s.getOutputStream();
      List<String> chain = new ArrayList<>();
      for (Certificate cert : s.getSession().getPeerCertificates()) chain.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
      return chain;
    }

    void reconnect() throws Exception {
      closeUpstream();
      String err = connectUpstream();
      if (err != null) throw Net.exception(err.substring(4).trim(), false);
      if (tls) upgradeUpstream();
    }

    // -------------------------------------------------------------- HTTP

    void exchanges(byte[] line, boolean proxied) throws IOException {
      while (line != null) {
        String l = trimEol(line);
        if (l.isEmpty() && line.length > 0) {
          line = readLineBytes(cin);
          continue;
        }
        String[] p = l.split(" ");
        if (line.length == 0 || p.length != 3 || !p[2].startsWith("HTTP/1.")) {
          nonHttp(line);
          return;
        }
        Request req = new Request();
        req.method = p[0];
        req.target = p[1];
        req.version = p[2];
        while (true) {
          String h = Net.readLine(cin);
          if (h == null) return;
          if (h.isEmpty()) break;
          int colon = h.indexOf(':');
          if (colon <= 0) continue;
          req.headers.add(new String[] {h.substring(0, colon).trim(), h.substring(colon + 1).trim()});
        }
        if ("100-continue".equalsIgnoreCase(req.header("Expect"))) {
          cout.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
          cout.flush();
        }
        String authority = req.header("Host");
        if (authority == null) authority = (tls ? tlsHost : host) + (port == (tls ? 443 : 80) ? "" : ":" + port);
        req.url = proxied ? req.target : (tls ? "https://" : "http://") + authority + req.target;
        if (proxied) req.target = URI.create(req.target).getRawPath() + (URI.create(req.target).getRawQuery() == null ? "" : "?" + URI.create(req.target).getRawQuery());
        Conditions c = Net.conditions;
        if (c.offline()) return;
        if (c.reset()) {
          Net.log("network: reset " + req.method + " " + req.url + " (" + c.name() + ")");
          return;
        }
        boolean keep = Net.mode == Net.Mode.RECORD ? record(req) : replay(req);
        exchanges++;
        if (!keep || "close".equalsIgnoreCase(req.header("Connection"))) return;
        line = readLineBytes(cin);
      }
    }

    /**
     * Streams the request's body to the server (or nowhere, replaying) as it arrives, at the upload bandwidth, and
     * hashes all of it while keeping at most MAX_BODY: a large upload uses no more memory than without apksim.
     */
    void streamRequestBody(Request req, OutputStream sink) throws IOException {
      String te = req.header("Transfer-Encoding");
      String cl = req.header("Content-Length");
      Capture cap = new Capture(MAX_BODY);
      OutputStream out = new Paced(sink, false);
      if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
        copyChunked(cin, out, cap);
        req.hasBody = true;
      } else if (cl != null && Long.parseLong(cl.trim()) > 0) {
        copy(cin, out, cap, Long.parseLong(cl.trim()), true);
        req.hasBody = true;
      }
      req.body = cap.kept();
      req.bodySha = cap.sha256();
      req.bodyTruncated = cap.truncated();
    }

    boolean record(Request req) throws IOException {
      long t0 = System.nanoTime();
      long sent = t0;
      byte[] head = requestHead(req);
      byte[] statusLine;
      try {
        // A kept-alive connection the server has closed meanwhile is replaced before the request goes out. Once it
        // has gone out, a failure ends the client's connection too, and the client's own retry policy decides: a
        // request sent again from here could reach the server twice.
        if (up == null || up.isClosed() || (exchanges > 0 && upstreamPeek() < 0)) reconnect();
        uout.write(head);
        streamRequestBody(req, uout);
        uout.flush();
        sent = System.nanoTime();
        statusLine = readLineBytes(uin);
      } catch (Exception e) {
        Net.log("network record: " + req.method + " " + req.url + " failed: " + e);
        return false;
      }
      if (statusLine == null) {
        Net.log("network record: " + req.method + " " + req.url + ": the server closed the connection without answering");
        return false;
      }
      Response resp = readResponseHead(statusLine);
      while (resp.status >= 100 && resp.status < 200 && resp.status != 101) {
        cout.write(resp.head);
        resp = readResponseHead(readLineBytes(uin));
      }
      long waited = System.nanoTime();
      Conditions.sleep(Net.conditions.rtt());
      cout.write(resp.head);
      cout.flush();

      Recording.Entry e = new Recording.Entry();
      e.method = req.method;
      e.url = req.url;
      e.httpVersion = req.version;
      e.reqHeaders = redacted(req.headers);
      e.reqBody = req.hasBody ? req.body : null;
      e.reqSha = req.bodySha;
      e.reqTruncated = req.bodyTruncated;
      e.status = resp.status;
      e.statusText = resp.text;
      e.respHeaders = redacted(resp.headers);
      e.connect = exchanges == 0 ? connectMs : -1;
      boolean keep = true;
      if (resp.status == 101) {
        // Not added: what follows is not recorded, so a replay could not answer it; replaying, it is unmatched.
        Net.log("network record: " + req.url + " switched protocols: relayed from here on, not recorded");
        relay(cin, cout, uin, uout, client, up);
        return false;
      }
      Capture body = new Capture(MAX_BODY);
      String te = resp.header("Transfer-Encoding");
      String cl = resp.header("Content-Length");
      boolean none = req.method.equals("HEAD") || resp.status == 204 || resp.status == 304;
      if (none) {
        // nothing
      } else if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
        copyChunked(uin, new Paced(cout, true), body);
      } else if (cl != null) {
        copy(uin, new Paced(cout, true), body, Long.parseLong(cl.trim()), true);
      } else {
        copy(uin, new Paced(cout, true), body, Long.MAX_VALUE, false);
        keep = false;
      }
      cout.flush();
      long done = System.nanoTime();
      byte[] bytes = body.kept();
      e.truncated = body.truncated();
      if ("gzip".equalsIgnoreCase(resp.header("Content-Encoding")) && !e.truncated) {
        // Decoded only while it stays within MAX_BODY; a bigger one is stored as sent (still gzip, and replayed so).
        try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
          byte[] decoded = gz.readNBytes(MAX_BODY + 1);
          if (decoded.length <= MAX_BODY) {
            bytes = decoded;
            e.gzip = true;
          }
        } catch (IOException notGzip) {
          // stored as sent
        }
      }
      e.respBody = e.truncated ? new byte[0] : bytes;
      e.send = (sent - t0) / 1_000_000;
      e.wait = (waited - sent) / 1_000_000;
      e.receive = (done - waited) / 1_000_000;
      e.timeMs = (done - t0) / 1_000_000;
      add(e);
      if ("close".equalsIgnoreCase(resp.header("Connection"))) keep = false;
      return keep;
    }

    void add(Recording.Entry e) {
      Net.recording.add(e);
      recorded.incrementAndGet();
      if (Boolean.getBoolean("apksim.networkTrace")) Net.log("network record: " + e.status + " " + e.method + " " + e.url);
      changed();
    }

    boolean replay(Request req) throws IOException {
      streamRequestBody(req, OutputStream.nullOutputStream());
      Recording.Entry e = Net.recording.find(req.method, req.url, req.headers, req.bodySha);
      Conditions.sleep(Net.conditions.rtt() + (e != null && Net.recordedTiming ? e.wait : 0));
      if (e == null || e.truncated) {
        missed.incrementAndGet();
        String msg;
        if (e != null) {
          // Its body was over the recording's 32 MB: answering 200 with what is not there would pass for a download.
          msg = "network replay: the recorded response for " + req.method + " " + req.url
              + " was too big to store (over 32 MB), so it cannot be replayed";
        } else {
          String closest = Net.recording.closest(req.method, req.url, req.headers, req.bodySha);
          msg = "network replay: no recorded response for " + req.method + " " + req.url
              + (closest == null ? "" : "\n         closest: " + closest);
        }
        Net.log(msg);
        Net.unmatched.add(msg.replace("\n         ", "; "));
        byte[] body = (msg + "\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 599 No Recorded Response\r\nX-Apksim-Replay: unmatched\r\nContent-Type: text/plain; charset=utf-8\r\n"
            + "Content-Length: " + body.length + "\r\n\r\n";
        cout.write(head.getBytes(StandardCharsets.ISO_8859_1));
        if (!req.method.equals("HEAD")) cout.write(body);
        cout.flush();
        return true;
      }
      replayed.incrementAndGet();
      if (Boolean.getBoolean("apksim.networkTrace")) Net.log("network replay: " + e.status + " " + e.method + " " + e.url);
      byte[] body = e.respBody;
      String accept = req.header("Accept-Encoding");
      boolean gzip = e.gzip && accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
      if (gzip) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(b)) {
          gz.write(body);
        }
        body = b.toByteArray();
      }
      StringBuilder h = new StringBuilder("HTTP/1.1 ").append(e.status).append(' ')
          .append(e.statusText.isEmpty() ? Net.statusText(e.status) : e.statusText).append("\r\n");
      for (String[] x : e.respHeaders) {
        String n = x[0].toLowerCase(Locale.ROOT);
        // Content-Encoding is redone only for a body stored decoded (gzip); any other is sent as it was recorded.
        if (n.equals("content-length") || n.equals("transfer-encoding") || n.equals("connection")
            || (n.equals("content-encoding") && e.gzip) || n.equals("keep-alive") || x[1].equals(Recording.REDACTED)) {
          continue;
        }
        h.append(x[0]).append(": ").append(x[1]).append("\r\n");
      }
      if (gzip) h.append("Content-Encoding: gzip\r\n");
      boolean none = e.status == 204 || e.status == 304 || (e.status >= 100 && e.status < 200);
      // A HEAD answer keeps the length the server gave (its body was never there to measure).
      String recordedLength = e.header(e.respHeaders, "Content-Length");
      if (!none) h.append("Content-Length: ").append(req.method.equals("HEAD") && recordedLength != null ? recordedLength : String.valueOf(body.length)).append("\r\n");
      h.append("X-Apksim-Replay: recorded\r\n\r\n");
      cout.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
      if (!none && !req.method.equals("HEAD")) new Paced(cout, true).write(body, 0, body.length);
      cout.flush();
      return true;
    }

    void nonHttp(byte[] consumed) throws IOException {
      if (Net.mode == Net.Mode.REPLAY) {
        String msg = "network replay: no recorded response for a non-HTTP connection to " + host + ":" + port;
        missed.incrementAndGet();
        Net.log(msg);
        Net.unmatched.add(msg);
        return;
      }
      Net.log("network record: " + host + ":" + port + " is not HTTP/1.1: relayed, not recorded");
      if (consumed.length > 0) uout.write(consumed);
      relay(cin, cout, uin, uout, client, up);
    }

    List<String[]> redacted(List<String[]> hs) {
      List<String[]> out = new ArrayList<>();
      for (String[] h : hs) {
        out.add(Net.redact.contains(h[0].toLowerCase(Locale.ROOT)) ? new String[] {h[0], Recording.REDACTED} : h);
      }
      return out;
    }

    Response readResponseHead(byte[] statusLine) throws IOException {
      if (statusLine == null) throw new IOException("server closed the connection before answering");
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      head.write(statusLine);
      Response r = new Response();
      String[] p = trimEol(statusLine).split(" ", 3);
      r.status = Integer.parseInt(p[1]);
      r.text = p.length > 2 ? p[2] : "";
      while (true) {
        byte[] lb = readLineBytes(uin);
        if (lb == null) throw new IOException("server closed the connection in its headers");
        head.write(lb);
        String h = trimEol(lb);
        if (h.isEmpty()) break;
        int colon = h.indexOf(':');
        if (colon > 0) r.headers.add(new String[] {h.substring(0, colon).trim(), h.substring(colon + 1).trim()});
      }
      r.head = head.toByteArray();
      return r;
    }
  }

  static final class Request {
    String method, target, version, url;
    final List<String[]> headers = new ArrayList<>();
    /** The body as kept (at most MAX_BODY), the SHA-256 of all of it, and whether there was one at all. */
    byte[] body;
    String bodySha;
    boolean hasBody, bodyTruncated;

    String header(String name) {
      for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
      return null;
    }
  }

  static final class Response {
    int status;
    String text;
    final List<String[]> headers = new ArrayList<>();
    byte[] head;

    String header(String name) {
      for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
      return null;
    }
  }

  /** The request as the server gets it: origin-form target, no proxy headers. */
  static byte[] requestHead(Request req) {
    StringBuilder b = new StringBuilder(req.method).append(' ').append(req.target).append(' ').append(req.version).append("\r\n");
    for (String[] h : req.headers) {
      if (h[0].equalsIgnoreCase("Proxy-Connection") || h[0].equalsIgnoreCase("Expect")) continue;
      b.append(h[0]).append(": ").append(h[1]).append("\r\n");
    }
    return b.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /** An absolute-form request line in origin form, for relaying a proxy request. */
  static byte[] originForm(byte[] line) {
    String[] p = trimEol(line).split(" ");
    URI u = URI.create(p[1]);
    String target = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    return (p[0] + " " + target + " " + p[2] + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
  }

  static String trimEol(byte[] b) {
    int n = b.length;
    while (n > 0 && (b[n - 1] == '\n' || b[n - 1] == '\r')) n--;
    return new String(b, 0, n, StandardCharsets.ISO_8859_1);
  }

  /** One line with its terminator; null at end of stream. */
  static byte[] readLineBytes(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(128);
    int c;
    while ((c = in.read()) != -1) {
      b.write(c);
      if (c == '\n') break;
      if (b.size() > 64 * 1024) break;
    }
    return b.size() == 0 ? null : b.toByteArray();
  }

  /** A body as it passes: all of it hashed, at most max bytes kept. */
  static final class Capture {
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private final java.security.MessageDigest digest;
    private final int max;
    private long total;

    Capture(int max) {
      this.max = max;
      try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    void write(byte[] b, int off, int len) {
      digest.update(b, off, len);
      if (kept.size() < max) kept.write(b, off, Math.min(len, max - kept.size()));
      total += len;
    }

    byte[] kept() {
      return kept.toByteArray();
    }

    boolean truncated() {
      return total > kept.size();
    }

    String sha256() {
      return java.util.HexFormat.of().formatHex(digest.digest());
    }
  }

  /**
   * Copies n bytes, or to end of stream when not exact, into capture as well. An exact copy that ends early throws:
   * the body is incomplete, and must neither look complete to the client nor be recorded.
   */
  static void copy(InputStream in, OutputStream out, Capture capture, long n, boolean exact) throws IOException {
    byte[] buf = new byte[16 * 1024];
    while (n > 0) {
      int r = in.read(buf, 0, (int) Math.min(buf.length, n));
      if (r < 0) {
        if (exact) throw new IOException("body ended " + n + " bytes early");
        break;
      }
      out.write(buf, 0, r);
      capture.write(buf, 0, r);
      n -= r;
    }
  }

  /** Copies a chunked body as sent, its decoded data into capture as well. */
  static void copyChunked(InputStream in, OutputStream out, Capture capture) throws IOException {
    while (true) {
      byte[] sizeLine = readLineBytes(in);
      if (sizeLine == null) throw new IOException("chunked body ended early");
      out.write(sizeLine);
      String s = trimEol(sizeLine);
      int semi = s.indexOf(';');
      int size = Integer.parseInt((semi < 0 ? s : s.substring(0, semi)).trim(), 16);
      if (size == 0) {
        while (true) {  // trailers, then the blank line
          byte[] t = readLineBytes(in);
          if (t == null) throw new IOException("chunked body ended early");
          out.write(t);
          if (trimEol(t).isEmpty()) return;
        }
      }
      copy(in, out, capture, size, true);
      byte[] crlf = readLineBytes(in);
      if (crlf != null) out.write(crlf);
    }
  }

  /** Writes at the current download (or upload) bandwidth. */
  static final class Paced extends OutputStream {
    private final OutputStream out;
    private final boolean down;

    Paced(OutputStream out, boolean down) {
      this.out = out;
      this.down = down;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      while (len > 0) {
        int kbps = down ? Net.conditions.downKbps() : Net.conditions.upKbps();
        int n = kbps <= 0 ? len : Math.min(len, Math.max(512, kbps * 16));  // ~1/8 s per slice
        Conditions.pace(n, kbps);  // before: a body that fits in one slice is slowed too
        out.write(b, off, n);
        out.flush();
        off += n;
        len -= n;
      }
    }

    @Override
    public void flush() throws IOException {
      out.flush();
    }
  }

  // ------------------------------------------------------------------ shaped relay

  /** Relays both ways until both ends are done, delaying and pacing by Net.conditions. */
  static void relay(InputStream cin, OutputStream cout, InputStream uin, OutputStream uout, Socket client, Socket up) {
    boolean cut = Net.conditions.reset();
    long cutAfter = cut ? 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(64 * 1024) : Long.MAX_VALUE;
    Thread upstream = Thread.ofVirtual().start(() -> pump(cin, uout, false, Long.MAX_VALUE, up));
    pump(uin, cout, true, cutAfter, client);
    if (cut) {
      try {
        client.close();
        up.close();
      } catch (IOException ignored) {
      }
    }
    try {
      upstream.join();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private record Chunk(long at, byte[] data) {}

  private static final Chunk END = new Chunk(0, null);

  /** At most this many chunks (16 KB each) in flight one way: past it the reader waits, and TCP pushes back. */
  private static final int IN_FLIGHT = 64;

  private static void pump(InputStream in, OutputStream out, boolean down, long cutAfter, Socket outSocket) {
    LinkedBlockingQueue<Chunk> line = new LinkedBlockingQueue<>(IN_FLIGHT);
    Thread writer = Thread.ofVirtual().start(() -> {
      try {
        while (true) {
          Chunk c = line.take();
          if (c == END) break;
          Conditions cond = Net.conditions;
          long due = c.at + cond.rtt() / 2;  // one way: half the round trip
          Conditions.sleep(due - System.currentTimeMillis());
          int kbps = down ? cond.downKbps() : cond.upKbps();
          int off = 0;
          while (off < c.data.length) {
            int n = kbps <= 0 ? c.data.length - off : Math.min(c.data.length - off, Math.max(512, kbps * 16));
            Conditions.pace(n, kbps);
            out.write(c.data, off, n);
            out.flush();
            off += n;
          }
        }
        if (outSocket != null && !(outSocket instanceof SSLSocket) && !outSocket.isClosed()) outSocket.shutdownOutput();
      } catch (IOException | InterruptedException ignored) {
        try {
          if (outSocket != null) outSocket.close();
        } catch (IOException e) {
          // closing anyway
        }
      }
    });
    long total = 0;
    byte[] buf = new byte[16 * 1024];
    try {
      int r;
      reading:
      while ((r = in.read(buf)) >= 0) {
        int n = (int) Math.min(r, cutAfter - total);
        Chunk c = new Chunk(System.currentTimeMillis(), java.util.Arrays.copyOf(buf, n));
        while (!line.offer(c, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
          if (!writer.isAlive()) break reading;  // the writer failed: nothing will take more
        }
        total += n;
        if (total >= cutAfter) break;
      }
    } catch (IOException ignored) {
      // the other side went away
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      while (writer.isAlive() && !line.offer(END, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        // the writer is still draining
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      writer.join();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }
}
