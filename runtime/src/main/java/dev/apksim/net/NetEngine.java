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
 * apksim's in-process network, for the connections something must stand in the way of (Net.route): they arrive here
 * on a loopback port, each saying where it was going and how it is to be treated ("APKSIM host port timeout address
 * answer|relay"), or as an HTTP proxy request (the JDK's HttpURLConnection). A relayed connection is carried
 * untouched, shaped by Net.conditions: its TLS is the app's, end to end, and the tap reads it on the app's side. An
 * answered one is read here as HTTP/1.1: replaying, the answer comes from the recording; live, the real connection
 * is made and each exchange relayed, with the inspector's rules applied and the exchange kept (docs/network.md).
 */
final class NetEngine {
  /** Bodies bigger than this are relayed but not recorded. */
  private static final int MAX_BODY = 32 << 20;
  private ServerSocket server;
  /** Live connections, both sides: going offline closes the client's and the server's. */
  private final Set<Conn> open = ConcurrentHashMap.newKeySet();
  final AtomicInteger recorded = new AtomicInteger(), replayed = new AtomicInteger(), missed = new AtomicInteger(),
      relayed = new AtomicInteger(), fetched = new AtomicInteger();
  /** One lock per request key being fetched (--network-unmatched fetch:FILE): the same request is sent once, the rest wait for its answer. */
  private final java.util.Map<String, Object> fetchLocks = new ConcurrentHashMap<>();
  /** Fetched answers not kept (--network-unmatched fetch:FILE): see {@link #lostCredential}. */
  final AtomicInteger notKept = new AtomicInteger();
  /**
   * Hosts whose replayed answer had a redacted header (a login's Set-Cookie) this launch: without its value (no
   * FILE.secrets), so the app never got the credential, or with it, so the app holds one from when it was recorded.
   */
  private final Set<String> credentialDropped = ConcurrentHashMap.newKeySet(), credentialReplayed = ConcurrentHashMap.newKeySet();
  private final Set<String> notKeptLogged = ConcurrentHashMap.newKeySet();

  private static String hostOf(String url) {
    try {
      String h = URI.create(url).getHost();
      return h == null ? "" : h.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  /** Whether a cookie one host set could be meant for the other: the same host, or names sharing their last two labels. */
  static boolean sameSite(String a, String b) {
    if (a.isEmpty() || b.isEmpty()) return false;
    if (a.equals(b)) return true;
    if (a.indexOf(':') >= 0 || b.indexOf(':') >= 0 || a.matches("[0-9.]+") || b.matches("[0-9.]+")) return false;  // addresses
    String[] x = a.split("\\."), y = b.split("\\.");
    if (x.length < 2 || y.length < 2) return false;
    return x[x.length - 1].equals(y[y.length - 1]) && x[x.length - 2].equals(y[y.length - 2]);
  }

  /**
   * Why a 401 or 403 the server gave a fetch says nothing about the request, or null when it may: this launch's app was
   * signed in by the recording, not the server, so it asked without the credential (a 401 or 403), or with the one from
   * when the recording was made (a 401: the server has ended that session). Kept, it would be every later launch's answer.
   */
  String lostCredential(String url, int status) {
    if (status != 401 && status != 403) return null;
    String h = hostOf(url);
    for (String d : credentialDropped) {
      if (sameSite(d, h)) return "the recording's answer from " + d + " had a redacted header (Set-Cookie) whose value no .secrets file beside the recording holds, so the app asked without that credential";
    }
    if (status == 401) {
      for (String d : credentialReplayed) {
        if (sameSite(d, h)) return "the app holds the credential the recording's answer from " + d + " gave it, which the server refused (an ended session?)";
      }
    }
    return null;
  }
  private SSLSocketFactory upstreamTls;

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

  /** Hosts whose h2 offer could not be tunnelled, each logged once. */
  private static final java.util.Set<String> NOT_TUNNELLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** An in-memory connection (the beam sandbox denies loopback): served on a thread of its own, as an accepted one. */
  void serveInMemory(TabRelay.Pipe fromApp, TabRelay.Pipe toApp) {
    Thread.ofVirtual().name("apksim-net-conn").start(() -> serve(new PipeSocket(fromApp, toApp)));
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

  /**
   * Closes the connections to the hosts `which` accepts; how many. `relayedToo` false: only the ones answered here (a
   * rule for their host went), so a connection only carried and shaped is left alone.
   */
  int close(java.util.function.Predicate<String> which, boolean relayedToo) {
    int n = 0;
    for (Conn c : open) {
      if ((relayedToo || c.answered) && c.host != null && which.test(c.host.toLowerCase(Locale.ROOT))) {
        c.close();
        n++;
      }
    }
    return n;
  }

  /** Exchanges whose answer is going to the client but not yet recorded. */
  final AtomicInteger inFlight = new AtomicInteger();

  void awaitInFlight(long ms) {
    long until = System.currentTimeMillis() + ms;
    synchronized (inFlight) {
      while (inFlight.get() > 0 && System.currentTimeMillis() < until) {
        try {
          inFlight.wait(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
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
    if (!Net.hasTrust()) {
      ctx.init(null, null, null);
    } else {
      KeyStore ks = KeyStore.getInstance(java.nio.file.Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toFile(), (char[]) null);
      int n = 0;
      try (InputStream in = Net.trustStream()) {
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("apksim-trust-" + n++, c);
      }
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      ctx.init(null, tmf.getTrustManagers(), null);
    }
    upstreamTls = ctx.getSocketFactory();
    return upstreamTls;
  }

  /** Where to connect: --network-host's mapping first, then the address the app chose, else host looked up here. */
  /** Whether a chain (base64 DER) holds one of --network-trust's certificates, or one issued by one of them. */
  static boolean issuedByTrust(List<String> chain) {
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      List<java.security.cert.X509Certificate> trusted = new ArrayList<>();
      try (InputStream in = Net.trustStream()) {
        for (Certificate c : cf.generateCertificates(in)) trusted.add((java.security.cert.X509Certificate) c);
      }
      for (String b64 : chain) {
        var c = (java.security.cert.X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        for (var t : trusted) {
          if (c.equals(t)) return true;
          if (c.getIssuerX500Principal().equals(t.getSubjectX500Principal())) {
            try {
              c.verify(t.getPublicKey());
              return true;
            } catch (Exception notIssued) {
              // another key under the same name
            }
          }
        }
      }
    } catch (Exception e) {
      Net.log("network record: cannot read --network-trust " + Net.trustName() + ": " + e);
    }
    return false;
  }

  static InetSocketAddress target(String host, String address, int port) throws IOException {
    InetSocketAddress m = Net.hostMap.get(host.toLowerCase(Locale.ROOT));
    if (m != null) return new InetSocketAddress(m.getHostString(), m.getPort() > 0 ? m.getPort() : port);
    if (address != null) return new InetSocketAddress(InetAddress.getByAddress(host, InetAddress.getByName(address).getAddress()), port);
    return new InetSocketAddress(host, port);
  }

  // ------------------------------------------------------------------ one client connection

  private final class Conn {
    final Socket client;
    InputStream cin;
    OutputStream cout;
    String host = "?";
    /** The address the app chose for host (its own DNS, one of several), or null to look host up here. */
    String address;
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
    /** The connect error of an upstream that is not there while an inspector rule may still answer (see run()), else null. */
    String deferred;
    /** Answered here (read as HTTP, TLS ended here) rather than carried untouched: what Net.route said for it. */
    boolean answered;

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
        if (p.length > 4 && !p[4].equals("-")) address = p[4];
        answered = Net.mode == Net.Mode.REPLAY || p.length > 5 && p[5].equals("answer");
        String err = inspected(connectUpstream());
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
        // What comes next says which: FakeTlsSocket's hello (answered here), or the JDK's own TLS (carried).
        answered = Net.mode == Net.Mode.REPLAY || Net.route(host) == Net.Route.MITM;
        String err = inspected(connectUpstream());
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
        // Plain HTTP from the JDK's client: read here whenever exchanges are kept or a rule may concern it.
        answered = Net.mode == Net.Mode.REPLAY || Net.recording != null || Net.route(host) == Net.Route.MITM;
        Conditions.sleep(Net.conditions.rtt());
        String err = inspected(connectUpstream());  // replaying: only a connect the recording has as failed
        if (err != null) {
          cout.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nX-Apksim-Error: " + err.trim() + "\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
          return;
        }
      }

      tlsHost = host;
      if (!answered) {
        relayed.incrementAndGet();
        if (pending != null) uout.write(originForm(pending));
        watching = false;
        relay(cin, cout, uin, uout, client, up);
        return;
      }

      session(pending, proxied);
    }

    /**
     * The inspector's view of a failed upstream connect (`err`, or null for none): shown as a row, and, while a rule answers
     * requests itself, put off (null back): a server that is not there (a backend not deployed yet) may still be answered by a
     * rule, and a request no rule takes then fails as the connect would have.
     */
    String inspected(String err) {
      if (err == null || !Inspector.on) return err;
      Inspector.failure(host, port, err);
      if (!answered || !Inspector.answers(host.toLowerCase(Locale.ROOT))) return err;
      deferred = err;
      return null;
    }

    /** From a new connection (or a tunnel just opened through the app's proxy): TLS if asked, then the exchanges. */
    void session(byte[] pending, boolean proxied) throws IOException {
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
          appTls = Net.takeTls(p.length > 2 && !p[2].equals("-") ? p[2] : null);
          appProtocols = p.length > 3 && !p[3].isEmpty() && !p[3].equals("-") ? p[3].split(",") : null;
          appSuites = p.length > 4 && !p[4].isEmpty() && !p[4].equals("-") ? p[4].split(",") : null;
          if (p.length > 5 && p[5].equals("tunnel") && Net.mode != Net.Mode.REPLAY && deferred == null && up != null) {
            // The client offered h2 alone, which this engine does not speak: the real TLS handshake runs over the
            // relayed bytes, so the client gets the protocol the server picks. No rule reaches it; the tap reads it
            // on the app's side, as it reads a direct connection.
            if (NOT_TUNNELLED.add("tunnel " + tlsHost + ":" + port)) {
              Net.log("network: " + tlsHost + ":" + port + " offers h2 alone: carried as a tunnel, so no inspector rule applies to it");
            }
            if (Inspector.on) Inspector.tunnel(tlsHost, port);
            relayed.incrementAndGet();
            cout.write("TUNNEL\r\n".getBytes(StandardCharsets.ISO_8859_1));
            cout.flush();
            watching = false;
            relay(cin, cout, uin, uout, client, up);
            return;
          }
          if (p.length > 5 && p[5].equals("tunnel") && NOT_TUNNELLED.add(tlsHost + ":" + port)) {
            // Said once per host and port: the client then gets no ALPN, and a server that speaks only h2 closes on it.
            Net.log("network: " + tlsHost + ":" + port + " offers h2 but cannot be tunnelled ("
                + (deferred != null ? "its connect failed" : "no upstream connection")
                + "): answered without ALPN, so an HTTP/2-only server will refuse it");
          }
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
            if (Net.mode != Net.Mode.REPLAY && upstreamPeek() > 0) return new byte[0];
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

    /** How often a read from the server that is waiting checks whether the client is still there. */
    static final int WATCH_MS = 500;
    /** Off while relaying: the relay's other thread reads the client then, and clientGone would race it. */
    volatile boolean watching = true;
    /** upstreamPeek's own short timeout, which must reach it. */
    boolean peeking;

    /**
     * The server's stream: every read that waits (for the answer to begin, or in the middle of a body) gives up once
     * the client has gone away, for instance closed by its own timeout. Otherwise a server that stalls kept this worker
     * and both its connections for good.
     */
    final class Watched extends InputStream {
      private final InputStream in;

      Watched(InputStream in) {
        this.in = in;
      }

      @Override
      public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : b[0] & 0xff;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        while (true) {
          try {
            return in.read(b, off, len);
          } catch (SocketTimeoutException e) {
            if (peeking) throw e;
            if (closed || watching && clientGone()) throw new IOException("the client went away while the server was still to answer");
          }
        }
      }

      @Override
      public int available() throws IOException {
        return in.available();
      }

      @Override
      public void close() throws IOException {
        in.close();
      }
    }

    void watch(Socket s) throws IOException {
      s.setSoTimeout(WATCH_MS);
      uin = new BufferedInputStream(new Watched(s.getInputStream()));
      uout = s.getOutputStream();
    }

    /** Without blocking: whether the client has closed its connection (bytes it sent stay to be read). */
    boolean clientGone() throws IOException {
      if (client.isClosed()) return true;
      client.setSoTimeout(1);
      try {
        cin.mark(1);
        if (cin.read() < 0) return true;
        cin.reset();
        return false;
      } catch (java.net.SocketTimeoutException e) {
        return false;
      } finally {
        if (!client.isClosed()) client.setSoTimeout(0);
      }
    }

    /** Without blocking: 1 if the server has sent something, -1 if it closed the connection, 0 if neither. */
    int upstreamPeek() throws IOException {
      if (up == null || up.isClosed()) return -1;
      up.setSoTimeout(1);
      peeking = true;
      try {
        uin.mark(1);
        if (uin.read() < 0) return -1;
        uin.reset();
        return 1;
      } catch (SocketTimeoutException e) {
        return 0;
      } finally {
        peeking = false;
        if (!up.isClosed()) up.setSoTimeout(WATCH_MS);
      }
    }

    /**
     * Connects to where the client was going; an "ERR ..." reply on failure, else null. Replaying, nothing is connected:
     * a connect the recording has as failed (and no answer from that host and port) fails again, as it did.
     */
    String connectUpstream() {
      if (Net.conditions.offline()) return "ERR ConnectException connect failed: ENETUNREACH (Network is unreachable)\r\n";
      if (Net.mode == Net.Mode.REPLAY) {
        Recording.Entry failed = Net.recording.connectFailure(host, port);
        if (failed == null) return null;
        if (Net.recordedTiming) Conditions.sleep(Math.min(failed.timeMs, timeout > 0 ? timeout : 30_000));
        replayed.incrementAndGet();
        if (Boolean.getBoolean("apksim.networkTrace")) Net.log("network replay: " + failed.error + " " + failed.url);
        return Recording.connectReply(failed);
      }
      // The placeholder a failed lookup was given (the inspector, while a rule answers): nothing is there to connect to, and
      // a connect to 198.18/15 would wait out the whole timeout. The failure is the lookup's, at once.
      if (NetResolver.lookupFailed(host, address)) {
        return Net.errorReply(new java.net.UnknownHostException("Unable to resolve host \"" + host + "\": No address associated with hostname"));
      }
      long t0 = System.nanoTime();
      long startWall = System.currentTimeMillis();
      try {
        if (closed) throw new java.net.SocketException("connection closed");
        Socket s = connectAny();
        adopt(s);
        watch(s);
        connectMs = (System.nanoTime() - t0) / 1_000_000;
        return null;
      } catch (IOException e) {
        // Kept by a recording, unless it is this side closing (the app gave up on the connection first).
        if (!closed) Net.connectFailed(host, port, e.toString(), startWall, (System.nanoTime() - t0) / 1_000_000);
        return Net.errorReply(e);
      }
    }

    /**
     * Connects to the address the client chose; when that one cannot be reached, to the host's other addresses, as the
     * client would have gone on to them itself (OkHttp's fast fallback, a browser's Happy Eyeballs). The client's own
     * connect was to this engine, so it never saw the failure: an address the machine has no route to (a host's AAAA
     * on a network without IPv6, which a client lists first) failed the whole request though the host's IPv4 address
     * works (issue 447). A mapped host or a name that has no other address fails as before.
     */
    private Socket connectAny() throws IOException {
      InetSocketAddress first = target(host, address, port);
      try {
        return connectTo(first);
      } catch (IOException e) {
        // Only for an address the machine has no route to: a timeout or a refusal is the server's answer, and an address the
        // app's own DNS chose (not one of the system's) must not be swapped for another.
        if (!NetEngine.noRoute(e)) throw e;
        if (address == null || Net.hostMap.containsKey(host.toLowerCase(Locale.ROOT)) || closed) throw e;
        InetAddress[] all;
        try {
          all = InetAddress.getAllByName(host);
        } catch (IOException lookup) {
          throw e;
        }
        InetAddress chosen = first.getAddress();
        if (!java.util.Arrays.asList(all).contains(chosen)) throw e;
        // The other family's addresses first: the chosen one failed for a route, not for a host that is down.
        boolean v6 = chosen instanceof java.net.Inet6Address;
        java.util.List<InetAddress> others = new java.util.ArrayList<>();
        for (InetAddress a : all) if (!a.equals(chosen) && (a instanceof java.net.Inet6Address) != v6) others.add(a);
        for (InetAddress a : all) if (!a.equals(chosen) && (a instanceof java.net.Inet6Address) == v6) others.add(a);
        for (InetAddress a : others) {
          try {
            return connectTo(new InetSocketAddress(a, port));
          } catch (IOException next) {
            e.addSuppressed(next);
          }
        }
        throw e;
      }
    }

    private Socket connectTo(InetSocketAddress to) throws IOException {
      if (closed) throw new java.net.SocketException("connection closed");
      Socket s = new Socket();
      try {
        s.setTcpNoDelay(true);
        s.connect(to, timeout);
        return s;
      } catch (IOException e) {
        try { s.close(); } catch (IOException ignored) { }
        throw e;
      }
    }

    /** FakeTlsSocket's handshake: the server's certificates, from the real server or the recording. */
    String handshake() {
      Conditions c = Net.conditions;
      Conditions.sleep(c.rtt());
      if (Net.mode == Net.Mode.REPLAY || deferred != null) {
        // No server: what one would have negotiated within what the app enabled, or the refusal it would have met.
        // TLS 1.3 and 1.2 only, as the JDK enables by default (servers refuse older ones too).
        String protocol = first(appProtocols, "TLSv1.3", "TLSv1.2");
        if (protocol == null) return "ERR SSLHandshakeException No appropriate protocol (the app enabled " + String.join(",", appProtocols) + ")\r\n";
        String suite = null;
        String[] offered = appSuites != null ? appSuites : new String[] {"TLS_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"};
        for (String s : offered) {
          boolean v13 = s.startsWith("TLS_AES_") || s.startsWith("TLS_CHACHA20_");
          if (v13 == protocol.equals("TLSv1.3") && !s.startsWith("TLS_EMPTY")) {
            suite = s;
            break;
          }
        }
        if (suite == null) return "ERR SSLHandshakeException No appropriate cipher suite for " + protocol + "\r\n";
        List<String> chain = Net.recording.certificates(tlsHost);
        return "OK p=" + protocol + " c=" + suite + " " + (chain == null ? "" : String.join(",", chain)) + "\r\n";
      }
      try {
        List<String> chain = upgradeUpstream();
        Recording r = Net.recording;
        if (r != null) r.certificates(tlsHost, chain);
        return "OK p=" + negotiated[0] + " c=" + negotiated[1] + " " + String.join(",", chain) + "\r\n";
      } catch (Exception e) {
        Net.log("network record: TLS to " + tlsHost + ":" + port + " failed: " + e.getMessage());
        return Net.errorReply(e);
      }
    }

    /** The app's TLS factory for this connection (FakeTlsSocket's), or null for the engine's (JDK trust + --network-trust). */
    SSLSocketFactory appTls;
    /** The protocols and cipher suites the app enabled on its socket (null: no restriction), and what was negotiated. */
    String[] appProtocols, appSuites;
    final String[] negotiated = {"TLSv1.3", "TLS_AES_128_GCM_SHA256"};

    /** The first of the preferred names that the app enabled (any of them when it named none). */
    String first(String[] enabled, String... preferred) {
      for (String p : preferred) {
        if (enabled == null || java.util.Arrays.asList(enabled).contains(p)) return p;
      }
      return null;
    }

    /** What both sides support, in the app's order; an empty result fails the handshake as a real one would. */
    static String[] common(String[] app, String[] supported, String what) throws javax.net.ssl.SSLHandshakeException {
      List<String> s = java.util.Arrays.asList(supported);
      String[] out = java.util.Arrays.stream(app).filter(s::contains).toArray(String[]::new);
      if (out.length == 0) throw new javax.net.ssl.SSLHandshakeException("No appropriate " + what + " (the app enabled " + String.join(",", app) + ")");
      return out;
    }
    /** The app's TLS refused the server, and --network-trust vouched for it: the engine's TLS from then on. */
    boolean trustOverride;

    /**
     * The real handshake with the server, with the app's own TLS when it has one: its trust manager decides, as it would
     * have without apksim (a custom one, pinning inside it). Only a server the app refuses whose chain is one of
     * --network-trust's certificates, or issued by one, is let through with the engine's TLS: that is what the option
     * is for (a fixture server with a throwaway certificate), and it is logged.
     */
    List<String> upgradeUpstream() throws Exception {
      if (appTls == null || trustOverride) return upgradeUpstream(upstreamTls());
      try {
        return upgradeUpstream(appTls);
      } catch (javax.net.ssl.SSLException refused) {
        if (!Net.hasTrust()) throw refused;
        closeUpstream();
        String err = connectUpstream();
        if (err != null) throw refused;
        List<String> chain = upgradeUpstream(upstreamTls());
        if (!issuedByTrust(chain)) {
          closeUpstream();
          throw refused;
        }
        trustOverride = true;
        Net.log("network record: " + tlsHost + " refused by the app's TLS (" + refused.getMessage() + "), accepted for --network-trust");
        return chain;
      }
    }

    List<String> upgradeUpstream(SSLSocketFactory factory) throws Exception {
      up.setSoTimeout(timeout);  // the handshake gets the app's connect timeout; the watch starts after it
      SSLSocket s = (SSLSocket) factory.createSocket(up, tlsHost, port, true);
      SSLParameters params = s.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      // The app's restrictions on its own socket hold for the real handshake: no protocol or suite it turned off.
      if (appProtocols != null) params.setProtocols(common(appProtocols, s.getSupportedProtocols(), "protocol"));
      if (appSuites != null) params.setCipherSuites(common(appSuites, s.getSupportedCipherSuites(), "cipher suite"));
      s.setSSLParameters(params);
      s.startHandshake();
      negotiated[0] = s.getSession().getProtocol();
      negotiated[1] = s.getSession().getCipherSuite();
      adopt(s);
      watch(s);
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
        if (req.method.equals("CONNECT") && !proxied) {
          // The app opening a tunnel through its own HTTP proxy (it connected to the proxy through here).
          tunnel(req);
          return;
        }
        String authority = req.header("Host");
        if (authority == null) authority = (tls ? tlsHost : host) + (port == (tls ? 443 : 80) ? "" : ":" + port);
        // An absolute target is the URL: through the engine as a proxy (proxied), or through the app's own proxy
        // (it reached the proxy through here), which then gets the request as it was.
        boolean absolute = req.target.startsWith("http://") || req.target.startsWith("https://");
        req.url = absolute ? req.target : (tls ? "https://" : "http://") + authority + req.target;
        req.toProxy = absolute && !proxied;
        if (proxied) req.target = URI.create(req.target).getRawPath() + (URI.create(req.target).getRawQuery() == null ? "" : "?" + URI.create(req.target).getRawQuery());
        Conditions c = Net.conditions;
        if (c.offline()) return;
        if (c.reset()) {
          Net.log("network: reset " + req.method + " " + req.url + " (" + c.name() + ")");
          return;
        }
        ruleId = 0;
        addedMs = 0;
        forwarded = false;
        patchRule = null;
        patchNote = null;
        boolean keep;
        Inspector.Rule rule = Inspector.on ? Inspector.match(req.method, req.url) : null;
        if (deferred != null && (rule == null || !rule.answers())) {
          // The server is not there (inspected()) and no rule answers this request: the connect's failure, as a proxy says it.
          cout.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nX-Apksim-Error: " + deferred.trim() + "\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
          cout.flush();
          return;
        }
        if (rule != null) {
          // The inspector's rule (docs/pr-preview.md): latency first, then its answer, or the server's own.
          ruleId = rule.id;
          addedMs = rule.latencyMs;
          Conditions.sleep(rule.latencyMs);
          if (rule.patching) patchRule = rule;
          keep = rule.forward != null ? answerForward(req, rule) : rule.status > 0 ? answerRule(req, rule) : Net.mode == Net.Mode.REPLAY ? replay(req) : record(req);
        } else {
          keep = Net.mode == Net.Mode.REPLAY ? replay(req) : record(req);
        }
        exchanges++;
        if (!keep || "close".equalsIgnoreCase(req.header("Connection"))) return;
        line = readLineBytes(cin);
      }
    }

    /**
     * CONNECT through the app's own proxy: recording, the proxy's answer goes back to the app; replaying, the engine
     * answers 200 itself. Once open, the tunnel is a new session: TLS for the name the app gives, then requests,
     * recorded under their own https:// URLs (so they replay as direct requests too).
     */
    void tunnel(Request req) throws IOException {
      String hp = req.target;
      int colon = hp.lastIndexOf(':');
      if (Net.mode != Net.Mode.REPLAY) {
        req.toProxy = true;
        uout.write(requestHead(req, false));
        uout.flush();
        Response r = readResponseHead(readLineBytes(uin));
        cout.write(r.head);
        cout.flush();
        if (r.status != 200) return;
      } else {
        cout.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
      }
      host = colon > 0 ? hp.substring(0, colon) : hp;
      tlsHost = host;
      port = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 443;
      tls = false;
      exchanges = 0;
      session(null, false);
    }

    /** The server's next status line, read on a thread of its own while the request body goes up. */
    final class Answer {
      private final java.util.concurrent.CompletableFuture<byte[]> line = new java.util.concurrent.CompletableFuture<>();

      Answer() {
        Thread.ofVirtual().name("apksim-net-answer").start(() -> {
          try {
            line.complete(readLineBytes(uin));
          } catch (Throwable t) {
            line.completeExceptionally(t);
          }
        });
      }

      boolean arrived() {
        return line.isDone();
      }

      /** The line, waiting at most ms (0: as long as it takes); null if it has not come by then. */
      byte[] get(long ms) throws IOException {
        try {
          return ms > 0 ? line.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) : line.get();
        } catch (java.util.concurrent.TimeoutException e) {
          return null;
        } catch (java.util.concurrent.ExecutionException e) {
          throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new java.io.InterruptedIOException();
        }
      }
    }

    /**
     * The body's way to the server: once the server has answered (an early 401 or 413), the rest is read from the
     * client and hashed but no longer sent; a write the server refuses after answering ends the sending the same way.
     */
    final class EarlyStop extends OutputStream {
      private final OutputStream out;
      private final Answer answer;
      boolean stopped;

      EarlyStop(OutputStream out, Answer answer) {
        this.out = out;
        this.answer = answer;
      }

      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (stopped || (stopped = answer.arrived())) return;
        try {
          out.write(b, off, len);
        } catch (IOException e) {
          if (answer.get(1000) == null) throw e;  // a failure with no answer: the server went away
          stopped = true;
        }
      }

      @Override
      public void flush() throws IOException {
        if (!stopped) out.flush();
      }
    }

    /** Without blocking: whether the client has sent something (bytes stay to be read) or closed its side. */
    boolean clientSent() throws IOException {
      client.setSoTimeout(1);
      try {
        cin.mark(1);
        cin.read();
        cin.reset();
        return true;
      } catch (SocketTimeoutException e) {
        return false;
      } finally {
        if (!client.isClosed()) client.setSoTimeout(0);
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

    /** Set once the server's answer starts on its way to the client, until the exchange is recorded or fails. */
    boolean answering;

    boolean record(Request req) throws IOException {
      try {
        return recordExchange(req);
      } finally {
        if (answering) {
          answering = false;
          if (inFlight.decrementAndGet() == 0) synchronized (inFlight) { inFlight.notifyAll(); }
        }
      }
    }

    boolean recordExchange(Request req) throws IOException {
      long t0 = System.nanoTime();
      long startWall = System.currentTimeMillis();
      Recording rec = Net.recording;
      boolean keepAtStart = rec == null || rec.keep;
      long sent = t0;
      boolean expect = "100-continue".equalsIgnoreCase(req.header("Expect"));
      byte[] statusLine = null;
      Response early = null;  // a final answer that came before the body (to Expect: 100-continue)
      boolean cutShort = false;  // the server answered while the body was still going up
      try {
        // A kept-alive connection the server has closed meanwhile is replaced before the request goes out. Once it
        // has gone out, a failure ends the client's connection too, and the client's own retry policy decides: a
        // request sent again from here could reach the server twice.
        if (up == null || up.isClosed() || (exchanges > 0 && upstreamPeek() < 0)) reconnect();
        uout.write(requestHead(req, expect));
        uout.flush();
        // The server's answer is read on its own thread from here, so one that comes early is heard. Meanwhile only
        // this thread reads the client: the answer's reader must not look at it (watching off until the body is up).
        watching = false;
        Answer answer = new Answer();
        if (expect) {
          // As a client would: the body waits for the server's 100 Continue or its final answer, unless the client
          // stops waiting and sends it anyway. Either way, whatever arrives is passed on as it arrives.
          while (!answer.arrived() && !clientSent()) Thread.sleep(5);
          if (answer.arrived()) {
            Response r = readResponseHead(answer.get(0));
            if (r.status == 100) {
              cout.write(r.head);
              cout.flush();
              answer = new Answer();
            } else {
              early = r;  // no body follows a final answer
            }
          }
        }
        if (early == null) {
          EarlyStop sink = new EarlyStop(uout, answer);
          streamRequestBody(req, sink);
          sink.flush();
          cutShort = sink.stopped;
          sent = System.nanoTime();
          watching = true;
          statusLine = answer.get(0);
        }
      } catch (Exception e) {
        Net.log("network record: " + req.method + " " + req.url + " failed: " + e);
        return false;
      } finally {
        watching = true;
      }
      if (early == null && statusLine == null) {
        Net.log("network record: " + req.method + " " + req.url + ": the server closed the connection without answering");
        return false;
      }
      Response resp = early != null ? early : readResponseHead(statusLine);
      // From here the client may have the answer before it is recorded: saving on the way out waits for it.
      answering = true;
      inFlight.incrementAndGet();
      while (resp.status >= 100 && resp.status < 200 && resp.status != 101) {
        cout.write(resp.head);
        resp = readResponseHead(readLineBytes(uin));
      }
      long waited = System.nanoTime();
      Conditions.sleep(Net.conditions.rtt());
      InputStream src = uin;
      if (patchRule != null && resp.status != 101) {
        // A patch rule (issue 373): the answer is read whole, merged with the rule's patch and relayed from memory (or, when it
        // cannot be patched, as it came; the row says why).
        ResponsePatch.Outcome o = ResponsePatch.apply(req.method, resp, uin, patchRule.patch, patchRule.id);
        resp = o.resp;
        src = o.src;
        patchNote = o.patched ? "" : o.note;
      }
      cout.write(resp.head);
      cout.flush();

      Recording.Entry e = new Recording.Entry();
      e.wallMs = startWall;
      e.started = java.time.Instant.ofEpochMilli(startWall).toString();
      e.keepAtStart = keepAtStart;
      e.method = req.method;
      e.url = req.url;
      e.httpVersion = req.version;
      e.reqHeaders = redacted(req.headers);
      // An upload over MAX_BODY is matched and stored by its hash alone: its kept prefix is not held for the run.
      e.reqBody = !req.hasBody ? null : req.bodyTruncated ? new byte[0] : req.body;
      e.reqSha = req.bodySha;
      e.reqTruncated = req.bodyTruncated;
      e.expectAnswered = early != null;
      e.status = resp.status;
      e.statusText = resp.text;
      e.respHeaders = redacted(resp.headers);
      e.connect = exchanges == 0 ? connectMs : -1;
      boolean keep = true;
      if (resp.status == 101) {
        // Not added: what follows is not recorded, so a replay could not answer it; replaying, it is unmatched.
        Net.log("network record: " + req.url + " switched protocols: relayed from here on, not recorded");
        watching = false;
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
        copyChunked(src, new Paced(cout, true), body);
      } else if (cl != null) {
        copy(src, new Paced(cout, true), body, Long.parseLong(cl.trim()), true);
      } else {
        copy(src, new Paced(cout, true), body, Long.MAX_VALUE, false);
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
      // After an answer that came before the whole body was sent, the server's side of the exchange is not where
      // a next request could follow.
      if ("close".equalsIgnoreCase(resp.header("Connection")) || early != null || cutShort) keep = false;
      return keep;
    }

    /** The inspector rule that slowed or answered the exchange being handled (0: none), and the latency it added. */
    int ruleId;
    long addedMs;
    /** Whether that rule is a forward rule: the exchange's row says so. */
    boolean forwarded;
    /** The patch rule that applies to the exchange being handled, and its outcome: "" patched, else why not (null: none yet). */
    Inspector.Rule patchRule;
    String patchNote;

    /**
     * A forward rule's answer (issue 363): the request goes, as it came, to the rule's URL and that server's status, headers
     * and body go back to the app; nothing reaches the app's own server. The target gets the method, the headers (not the
     * ones recordings redact, nor the connection's own: Host, Content-Length, Connection...) and the whole body, with
     * `X-Apksim-Original-Url` naming where the app sent it. A target that cannot be reached or does not answer in 30 s
     * is a 502 to the app, with the reason in `X-Apksim-Error` and the body. Redirects are the app's to follow.
     */
    boolean answerForward(Request req, Inspector.Rule rule) throws IOException {
      long t0 = System.nanoTime();
      long startWall = System.currentTimeMillis();
      forwarded = true;
      if ("100-continue".equalsIgnoreCase(req.header("Expect"))) {
        cout.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
      }
      ByteArrayOutputStream whole = new ByteArrayOutputStream();
      boolean[] over = {false};
      streamRequestBody(req, new OutputStream() {
        @Override
        public void write(int b) {
          write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
          if (over[0] || whole.size() + len > Inspector.FORWARD_BODY_MAX) {
            over[0] = true;
            whole.reset();
          } else {
            whole.write(b, off, len);
          }
        }
      });
      inFlight.incrementAndGet();
      try {
        int status;
        List<String[]> headers = new ArrayList<>();
        byte[] body;
        String error = null;
        try {
          if (over[0]) throw new IOException("a request body over " + Inspector.FORWARD_BODY_MAX + " bytes is not forwarded");
          // A name that did not resolve, or that stands for a recorded or mapped host, has a placeholder address here
          // (NetResolver): no server is there, and the client would wait out its connect timeout.
          // (Not when the agent sends it, issue 426: it resolves on the Mac, which this process's resolver says nothing of.)
          String dest = rule.forward;
          if (rule.rebase) {
            dest = Inspector.rebaseUrl(rule.forward, req.url);
            if (dest == null) throw new IOException("the app's path cannot be added to a rebase target (dot segments, empty segments, ';', '\\\\' or an encoded '.', '/' or '\\\\')");
          }
          String fh = java.net.URI.create(dest).getHost();
          if (fh.startsWith("[") && fh.endsWith("]")) fh = fh.substring(1, fh.length() - 1);
          try {
            if (!ForwardLink.on && NetResolver.hostFor(java.net.InetAddress.getByName(fh).getHostAddress()) != null) throw new java.net.UnknownHostException(fh);
          } catch (java.net.UnknownHostException e) {
            throw new IOException("unable to resolve host " + fh + " (replaying or with --network-host, name the upstream by address)");
          }
          List<String[]> sent = new ArrayList<>();
          for (String[] h : req.headers) {
            String n = h[0].toLowerCase(Locale.ROOT);
            if (HOP_HEADERS.contains(n) || n.startsWith("proxy-") || n.startsWith("x-apksim-") || Net.redact.contains(n)) continue;
            sent.add(h);
          }
          sent.add(new String[] {"X-Apksim-Original-Url", req.url});
          byte[] sentBody = req.hasBody ? whole.toByteArray() : null;
          // In a beam session (issue 426) the agent sends it: the app's sandbox cannot reach the Mac's loopback.
          ForwardSend.Result r = ForwardLink.on ? ForwardLink.fetch(req.method, dest, sent, sentBody)
              : ForwardSend.send(req.method, dest, sent, sentBody, Inspector.FORWARD_BODY_MAX);
          status = r.status();
          body = r.body();
          boolean head = req.method.equals("HEAD");
          boolean lengthSent = false;
          for (String[] h : r.headers()) {
            String n = h[0].toLowerCase(Locale.ROOT);
            // A HEAD's Content-Length is the size a GET would have: passed on (no body follows either way).
            if (head && n.equals("content-length") && !lengthSent && h[1].matches("[0-9]{1,18}")) {
              lengthSent = true;
              headers.add(new String[] {"Content-Length", h[1]});
              continue;
            }
            if (HOP_HEADERS.contains(n) || n.startsWith(":") || n.startsWith("proxy-") || n.equals("x-apksim-rule")) continue;
            headers.add(new String[] {h[0], h[1]});
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while forwarding");
        } catch (IOException | RuntimeException e) {
          error = ("forward to " + rule.forward + " failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
              .replaceAll("[^\\x20-\\x7e]", " ");
          status = 502;
          body = (error + "\n").getBytes(StandardCharsets.UTF_8);
          headers.add(new String[] {"Content-Type", "text/plain; charset=utf-8"});
          headers.add(new String[] {"X-Apksim-Error", error});
          Net.log("network inspector: rule " + rule.id + ": " + error);
        }
        headers.add(new String[] {"X-Apksim-Rule", String.valueOf(rule.id)});
        boolean none = status == 204 || status == 304 || status < 200;
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(Net.statusText(status)).append("\r\n");
        for (String[] h : headers) head.append(h[0]).append(": ").append(h[1].replaceAll("[\\r\\n]", " ")).append("\r\n");
        boolean sized = false;
        for (String[] h : headers) sized |= h[0].equals("Content-Length");
        if (!none && !sized) head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("\r\n");
        Conditions.sleep(Net.conditions.rtt());
        cout.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!none && !req.method.equals("HEAD")) new Paced(cout, true).write(body, 0, body.length);
        cout.flush();
        Recording.Entry e = new Recording.Entry();
        e.wallMs = startWall;
        e.started = java.time.Instant.ofEpochMilli(startWall).toString();
        e.method = req.method;
        e.url = req.url;
        e.httpVersion = req.version;
        e.reqHeaders = redacted(req.headers);
        e.reqBody = !req.hasBody ? null : req.bodyTruncated ? new byte[0] : req.body;
        e.reqSha = req.bodySha;
        e.reqTruncated = req.bodyTruncated;
        e.status = status;
        e.statusText = Net.statusText(status);
        e.respHeaders = redacted(headers);
        e.respBody = body;
        e.timeMs = (System.nanoTime() - t0) / 1_000_000;
        add(e);
        return !"close".equalsIgnoreCase(req.header("Connection"));
      } finally {
        if (inFlight.decrementAndGet() == 0) synchronized (inFlight) { inFlight.notifyAll(); }
      }
    }

    /** An inspector rule's answer: the request is read and dropped, the rule's status and body go back; nothing reaches the server. */
    boolean answerRule(Request req, Inspector.Rule rule) throws IOException {
      long t0 = System.nanoTime();
      long startWall = System.currentTimeMillis();
      if ("100-continue".equalsIgnoreCase(req.header("Expect"))) {
        cout.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
      }
      streamRequestBody(req, OutputStream.nullOutputStream());
      boolean none = rule.status == 204 || rule.status == 304;
      StringBuilder h = new StringBuilder("HTTP/1.1 ").append(rule.status).append(' ').append(Net.statusText(rule.status)).append("\r\n");
      if (!none) h.append("Content-Type: ").append(rule.type).append("\r\nContent-Length: ").append(rule.body.length).append("\r\n");
      for (String[] x : rule.headers) h.append(x[0]).append(": ").append(x[1]).append("\r\n");
      h.append("X-Apksim-Rule: ").append(rule.id).append("\r\n\r\n");
      // The app may have the answer before it is recorded: saving on the way out (a crash, an exit) waits for it, as for record().
      inFlight.incrementAndGet();
      try {
        return sendRule(req, rule, h.toString(), none, t0, startWall);
      } finally {
        if (inFlight.decrementAndGet() == 0) synchronized (inFlight) { inFlight.notifyAll(); }
      }
    }

    private boolean sendRule(Request req, Inspector.Rule rule, String head, boolean none, long t0, long startWall) throws IOException {
      Conditions.sleep(Net.conditions.rtt());
      cout.write(head.getBytes(StandardCharsets.ISO_8859_1));
      if (!none && !req.method.equals("HEAD")) new Paced(cout, true).write(rule.body, 0, rule.body.length);
      cout.flush();
      Recording.Entry e = new Recording.Entry();
      e.wallMs = startWall;
      e.started = java.time.Instant.ofEpochMilli(startWall).toString();
      e.method = req.method;
      e.url = req.url;
      e.httpVersion = req.version;
      e.reqHeaders = redacted(req.headers);
      e.reqBody = !req.hasBody ? null : req.bodyTruncated ? new byte[0] : req.body;
      e.reqSha = req.bodySha;
      e.reqTruncated = req.bodyTruncated;
      e.status = rule.status;
      e.statusText = Net.statusText(rule.status);
      e.respHeaders = new ArrayList<>();
      e.respHeaders.add(new String[] {"Content-Type", rule.type});
      e.respHeaders.addAll(redacted(rule.headers));  // a Set-Cookie a rule sets is kept out of recordings as a server's is
      e.respHeaders.add(new String[] {"X-Apksim-Rule", String.valueOf(rule.id)});
      e.respBody = rule.body;
      e.timeMs = (System.nanoTime() - t0) / 1_000_000;
      add(e);
      return !"close".equalsIgnoreCase(req.header("Connection"));
    }

    void add(Recording.Entry e) {
      Inspector.exchange(e, ruleId, addedMs, forwarded, patchRule == null ? null : patchNote == null ? "the answer came from a recording, not a server" : patchNote);
      // Replaying, the recording is the fixture the app is answered from: a rule's answer is shown, never added to it.
      Recording r = Net.recording;
      if (Net.mode == Net.Mode.REPLAY || r == null) return;
      r.add(e);
      recorded.incrementAndGet();
      if (Boolean.getBoolean("apksim.networkTrace")) Net.log("network record: " + e.status + " " + e.method + " " + e.url);
      Net.changed();
    }

    /** The inspector's row for a replayed answer: a snapshot of this request and answer, never the fixture's entry itself. */
    void inspectReplay(Request req, long startWall, int status, List<String[]> respHeaders, byte[] body) {
      if (!Inspector.on) return;
      Recording.Entry s = new Recording.Entry();
      s.wallMs = startWall;
      s.method = req.method;
      s.url = req.url;
      s.reqHeaders = redacted(req.headers);
      s.reqBody = !req.hasBody ? null : req.bodyTruncated ? new byte[0] : req.body;
      s.reqTruncated = req.bodyTruncated;
      s.status = status;
      s.respHeaders = new ArrayList<>(respHeaders);
      s.respBody = body;
      s.timeMs = System.currentTimeMillis() - startWall;
      Inspector.exchange(s, ruleId, addedMs, false, patchRule == null ? null : "the answer came from a recording, not a server");
    }

    boolean replay(Request req) throws IOException {
      long startWall = System.currentTimeMillis();
      if ("100-continue".equalsIgnoreCase(req.header("Expect"))) {
        // Recorded answered before its body (the server refused it at once): answered the same way, no body read.
        Recording.Entry early = Net.recording.findExpectAnswered(req.method, req.url, req.headers);
        if (early != null) {
          Conditions.sleep(Net.conditions.rtt());
          replayed.incrementAndGet();
          writeRecorded(req, early);
          inspectReplay(req, startWall, early.status, redacted(early.respHeaders), early.respBody);
          return false;
        }
        cout.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
      }
      streamRequestBody(req, OutputStream.nullOutputStream());
      req = req.blanked();
      Recording.Entry e = Net.recording.find(req.method, req.url, req.headers, req.bodySha);
      if (e == null && Net.fetched != null) return fetch(req, startWall);
      Conditions.sleep(Net.conditions.rtt() + (e != null && Net.recordedTiming ? e.wait : 0));
      if (e == null || e.truncated) {
        String msg;
        if (e != null) {
          // Its body was over the recording's 32 MB: answering 200 with what is not there would pass for a download.
          msg = "network replay: the recorded response for " + req.method + " " + req.url
              + " was too big to store (over 32 MB), so it cannot be replayed";
        } else {
          Recording.Closest closest = Net.recording.closest(req.method, req.url, req.headers, req.bodySha);
          msg = "network replay: no recorded response for " + req.method + " " + req.url
              + (closest == null ? "" : "\n         closest: " + closest.text() + closest.hint());
          return unmatched(req, startWall, msg, closest, null);
        }
        return unmatched(req, startWall, msg, null, "the recorded answer was too big to store");
      }
      replayed.incrementAndGet();
      writeRecorded(req, e);
      inspectReplay(req, startWall, e.status, redacted(e.respHeaders), e.respBody);
      return true;
    }

    /** The 599 a request nothing can answer gets: logged, listed by `network unmatched`, and (policy fail) failing a test. */
    boolean unmatched(Request req, long startWall, String msg, Recording.Closest closest, String why) throws IOException {
      Net.recording.missed(req.method, req.url, closest, why);
      missed.incrementAndGet();
      Net.log(msg);
      Net.unmatched.add(msg.replace("\n         ", "; "));
      NetFailures.note(msg.replace("\n         ", "; "));
      byte[] body = (msg + "\n").getBytes(StandardCharsets.UTF_8);
      String head = "HTTP/1.1 599 No Recorded Response\r\nX-Apksim-Replay: unmatched\r\nContent-Type: text/plain; charset=utf-8\r\n"
          + "Content-Length: " + body.length + "\r\n\r\n";
      cout.write(head.getBytes(StandardCharsets.ISO_8859_1));
      if (!req.method.equals("HEAD")) cout.write(body);
      cout.flush();
      inspectReplay(req, startWall, 599, List.of(new String[] {"Content-Type", "text/plain; charset=utf-8"}, new String[] {"X-Apksim-Replay", "unmatched"}), body);
      return true;
    }

    /**
     * A request the replayed recording lacks, with `--network-unmatched fetch:FILE` (docs/network.md): sent to the real
     * server at its own URL (or where --network-host maps its host), the answer read whole, written to the app and kept in
     * FILE as a recording keeps it. A repeat of it in this launch is answered from what was fetched (the first answer wins:
     * the server sees it once, even when two connections ask at the same time). What cannot be fetched (a server not
     * there, no answer in time, an answer over 32 MB) is unmatched, as without the policy.
     */
    boolean fetch(Request req, long startWall) throws IOException {
      Recording out = Net.fetched;
      String key = out.key(req.method, req.url, req.headers, req.bodySha);
      Object lock = fetchLocks.computeIfAbsent(key, k -> new Object());
      synchronized (lock) {
        Recording.Entry prior = out.find(req.method, req.url, req.headers, req.bodySha);
        if (prior != null) {
          Conditions.sleep(Net.conditions.rtt());
          replayed.incrementAndGet();
          writeRecorded(req, prior, "fetched");
          inspectReplay(req, startWall, prior.status, prior.respHeaders, prior.respBody);
          return true;
        }
        Recording.Entry live;
        // The app may have the answer before FILE is written: saving on the way out waits for it, as for record().
        inFlight.incrementAndGet();
        try {
          try {
            live = fetchFromServer(req, startWall);
          } catch (IOException | RuntimeException x) {
            String why = x.getMessage() == null ? x.getClass().getSimpleName() : x.getClass().getSimpleName() + ": " + x.getMessage();
            Recording.Closest closest = Net.recording.closest(req.method, req.url, req.headers, req.bodySha);
            return unmatched(req, startWall, "network replay: no recorded response for " + req.method + " " + req.url
                + ", and fetching it failed: " + why.replaceAll("[\\r\\n]", " ") + (closest == null ? "" : "\n         closest: " + closest.text()),
                closest, "fetching it failed: " + why.replaceAll("[\\r\\n]", " "));
          }
          Conditions.sleep(Net.conditions.rtt());
          String lost = lostCredential(live.url, live.status);
          if (lost != null) {
            // The app gets the server's answer; FILE does not, so no later launch is answered with it.
            notKept.incrementAndGet();
            writeRecorded(req, live, "fetched");
            out.notKept(live.method, live.url, live.status, lost);
            if (notKeptLogged.add(hostOf(live.url) + " " + live.status)) {
              Net.log("network fetch: " + live.status + " for " + live.method + " " + live.url + " is not kept: " + lost
                  + " (further ones from this host are counted, not logged)");
            }
            inspectReplay(req, startWall, live.status, redacted(live.respHeaders), live.respBody);
            Net.changed();
            return true;
          }
          fetched.incrementAndGet();
          // The app gets the server's headers as they came; FILE (and a repeat) gets them redacted, as a recording keeps
          // them, with their values beside it in FILE.secrets for the app of a later launch.
          writeRecorded(req, live, "fetched");
          List<String[]> secret = new ArrayList<>();
          for (String[] x : live.respHeaders) if (Net.redact.contains(x[0].toLowerCase(Locale.ROOT))) secret.add(x);
          live.respHeaders = redacted(live.respHeaders);
          if (!secret.isEmpty()) live.secretResp = secret;
          out.add(live);
          if (Boolean.getBoolean("apksim.networkTrace")) Net.log("network fetch: " + live.status + " " + live.method + " " + live.url);
          inspectReplay(req, startWall, live.status, live.respHeaders, live.respBody);
          Net.changed();
          return true;
        } finally {
          if (inFlight.decrementAndGet() == 0) synchronized (inFlight) { inFlight.notifyAll(); }
        }
      }
    }

    /** One request sent on a connection of its own (closed after it) and the answer read whole, as a recorded entry with the server's own headers. */
    Recording.Entry fetchFromServer(Request req, long startWall) throws IOException {
      if (Net.conditions.offline()) throw new java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)");
      if (req.bodyTruncated) throw new IOException("a request body over 32 MB is not fetched");
      URI u = URI.create(req.url);
      boolean https = "https".equalsIgnoreCase(u.getScheme());
      String h = u.getHost();
      if (h == null) throw new IOException("no host in " + req.url);
      if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
      int p = u.getPort() > 0 ? u.getPort() : https ? 443 : 80;
      long t0 = System.nanoTime();
      Socket s = fetchConnect(h, p);
      long connected = System.nanoTime();
      try {
        s.setSoTimeout(30_000);
        if (https) s = fetchTls(s, h, p);
        String target = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        StringBuilder head = new StringBuilder(req.method).append(' ').append(target).append(" HTTP/1.1\r\n");
        boolean hostSent = false;
        for (String[] x : req.headers) {
          String n = x[0].toLowerCase(Locale.ROOT);
          // One request on a connection of its own: nothing of the app's connection to the engine (or its proxy) goes on.
          if (HOP_HEADERS.contains(n) && !n.equals("host") || n.startsWith("proxy-")) continue;
          hostSent |= n.equals("host");
          head.append(x[0]).append(": ").append(x[1]).append("\r\n");
        }
        if (!hostSent) head.append("Host: ").append(u.getRawAuthority()).append("\r\n");
        byte[] body = req.hasBody ? req.body : null;
        if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        OutputStream o = s.getOutputStream();
        o.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) o.write(body);
        o.flush();
        long sent = System.nanoTime();
        InputStream in = new BufferedInputStream(s.getInputStream());
        Response resp = readHead(in);
        while (resp.status >= 100 && resp.status < 200 && resp.status != 101) resp = readHead(in);
        if (resp.status == 101) throw new IOException("the server switched protocols, which cannot be kept");
        long waited = System.nanoTime();
        Capture cap = new Capture(MAX_BODY);
        String te = resp.header("Transfer-Encoding");
        String cl = resp.header("Content-Length");
        if (req.method.equals("HEAD") || resp.status == 204 || resp.status == 304) {
          // no body
        } else if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
          copyChunked(in, OutputStream.nullOutputStream(), cap);
        } else if (cl != null) {
          copy(in, OutputStream.nullOutputStream(), cap, Long.parseLong(cl.trim()), true);
        } else {
          copy(in, OutputStream.nullOutputStream(), cap, Long.MAX_VALUE, false);
        }
        if (cap.truncated()) throw new IOException("the answer is over 32 MB, too big to keep");
        long done = System.nanoTime();
        Recording.Entry e = new Recording.Entry();
        e.wallMs = startWall;
        e.started = java.time.Instant.ofEpochMilli(startWall).toString();
        e.method = req.method;
        e.url = req.url;
        e.httpVersion = req.version;
        e.reqHeaders = redacted(req.headers);
        e.reqBody = req.hasBody ? req.body : null;
        e.reqSha = req.bodySha;
        e.status = resp.status;
        e.statusText = resp.text;
        e.respHeaders = resp.headers;
        byte[] bytes = cap.kept();
        if ("gzip".equalsIgnoreCase(resp.header("Content-Encoding"))) {
          // Stored decoded, as a recording stores it (re-encoded for a client that accepts gzip); one that does not decode is kept as sent.
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
        e.respBody = bytes;
        e.connect = (connected - t0) / 1_000_000;
        e.send = (sent - connected) / 1_000_000;
        e.wait = (waited - sent) / 1_000_000;
        e.receive = (done - waited) / 1_000_000;
        e.timeMs = (done - t0) / 1_000_000;
        return e;
      } finally {
        synchronized (this) {
          if (up == s) up = null;
        }
        try { s.close(); } catch (IOException ignored) { }
      }
    }

    /** A head read from a fetch's own connection. */
    private Response readHead(InputStream in) throws IOException {
      byte[] statusLine = readLineBytes(in);
      if (statusLine == null) throw new IOException("the server closed the connection without answering");
      Response r = new Response();
      String[] p = trimEol(statusLine).split(" ", 3);
      if (p.length < 2 || !p[0].startsWith("HTTP/")) throw new IOException("not an HTTP answer: " + trimEol(statusLine));
      r.status = Integer.parseInt(p[1].trim());
      r.text = p.length > 2 ? p[2] : "";
      while (true) {
        byte[] lb = readLineBytes(in);
        if (lb == null) throw new IOException("the server closed the connection in its headers");
        String l = trimEol(lb);
        if (l.isEmpty()) break;
        int colon = l.indexOf(':');
        if (colon > 0) r.headers.add(new String[] {l.substring(0, colon).trim(), l.substring(colon + 1).trim()});
      }
      return r;
    }

    /**
     * The fetch's own connection, to where --network-host maps the host or to the host's real addresses (the system's DNS:
     * replaying, this process's own lookups give placeholders). It is this connection's upstream while it lasts, so going
     * offline or the app closing its connection closes it.
     */
    private Socket fetchConnect(String h, int p) throws IOException {
      InetSocketAddress m = Net.hostMap.get(h.toLowerCase(Locale.ROOT));
      List<InetSocketAddress> to = new ArrayList<>();
      if (m != null) {
        to.add(new InetSocketAddress(m.getHostString(), m.getPort() > 0 ? m.getPort() : p));
      } else {
        for (InetAddress a : NetResolver.systemLookup(h)) to.add(new InetSocketAddress(InetAddress.getByAddress(h, a.getAddress()), p));
      }
      IOException failed = null;
      for (InetSocketAddress a : to) {
        if (a.isUnresolved()) {
          failed = new java.net.UnknownHostException(a.getHostString());
          continue;
        }
        try {
          Socket s = connectTo(a);
          adopt(s);
          return s;
        } catch (IOException x) {
          if (failed == null) failed = x; else failed.addSuppressed(x);
        }
      }
      throw failed != null ? failed : new java.net.UnknownHostException(h);
    }

    /**
     * TLS for a fetch: the app's own factory when it lent one (its trust decides, as recording), else the engine's (the JDK's
     * trust store); a server the app refuses gets through only when --network-trust vouches for its chain. The server's
     * chain is kept with FILE, so a replay of it presents the real certificates.
     */
    private Socket fetchTls(Socket raw, String h, int p) throws IOException {
      try {
        SSLSocket s;
        try {
          s = tlsOver(raw, appTls != null ? appTls : upstreamTls(), h, p);
        } catch (javax.net.ssl.SSLException refused) {
          if (!Net.hasTrust()) throw refused;
          raw.close();
          Socket again = fetchConnect(h, p);
          try {
            again.setSoTimeout(30_000);
            s = tlsOver(again, upstreamTls(), h, p);
            if (!issuedByTrust(chain(s))) throw refused;
          } catch (Exception x) {
            again.close();
            throw x;
          }
          Net.log("network fetch: " + h + " refused by the app's TLS (" + refused.getMessage() + "), accepted for --network-trust");
        }
        adopt(s);
        Net.fetched.certificates(tlsHost != null ? tlsHost : h, chain(s));
        return s;
      } catch (IOException x) {
        throw x;
      } catch (Exception x) {
        throw new IOException(x.getMessage(), x);
      }
    }

    private SSLSocket tlsOver(Socket raw, SSLSocketFactory factory, String h, int p) throws IOException {
      SSLSocket s = (SSLSocket) factory.createSocket(raw, h, p, true);
      SSLParameters params = s.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      s.setSSLParameters(params);
      s.startHandshake();
      return s;
    }

    private List<String> chain(SSLSocket s) throws IOException {
      List<String> chain = new ArrayList<>();
      try {
        for (Certificate cert : s.getSession().getPeerCertificates()) chain.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
      } catch (java.security.cert.CertificateEncodingException x) {
        throw new IOException(x);
      }
      return chain;
    }

    /** A recorded response, as the client asked for it (gzip only if it accepts it), at the download bandwidth. */
    void writeRecorded(Request req, Recording.Entry e) throws IOException {
      writeRecorded(req, e, "recorded");
    }

    /** `mark`: what X-Apksim-Replay says (recorded, or fetched for an answer --network-unmatched fetch: got from the server). */
    void writeRecorded(Request req, Recording.Entry e, String mark) throws IOException {
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
      boolean redactedOne = false;
      for (String[] x : e.respHeaders) {
        String n = x[0].toLowerCase(Locale.ROOT);
        redactedOne |= x[1].equals(Recording.REDACTED);
        // Content-Encoding is redone only for a body stored decoded (gzip); any other is sent as it was recorded.
        if (n.equals("content-length") || n.equals("transfer-encoding") || n.equals("connection")
            || (n.equals("content-encoding") && e.gzip) || n.equals("keep-alive") || x[1].equals(Recording.REDACTED)) {
          continue;
        }
        h.append(x[0]).append(": ").append(x[1]).append("\r\n");
      }
      // A redacted header (a login's Set-Cookie) goes to the app with the value FILE.secrets kept for it; without one it
      // is left out, and a fetch the server then refuses is not kept (lostCredential).
      if (redactedOne && e.secretResp != null) {
        for (String[] x : e.secretResp) h.append(x[0]).append(": ").append(x[1]).append("\r\n");
        if (Net.fetched != null && mark.equals("recorded")) credentialReplayed.add(hostOf(e.url));
      } else if (redactedOne && Net.fetched != null && mark.equals("recorded")) {
        credentialDropped.add(hostOf(e.url));
      }
      if (gzip) h.append("Content-Encoding: gzip\r\n");
      boolean none = e.status == 204 || e.status == 304 || (e.status >= 100 && e.status < 200);
      // A HEAD answer keeps the length the server gave (its body was never there to measure).
      String recordedLength = e.header(e.respHeaders, "Content-Length");
      if (!none) h.append("Content-Length: ").append(req.method.equals("HEAD") && recordedLength != null ? recordedLength : String.valueOf(body.length)).append("\r\n");
      h.append(Net.replayMark ? "X-Apksim-Replay: " + mark + "\r\n\r\n" : "\r\n");
      cout.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
      if (!none && !req.method.equals("HEAD")) new Paced(cout, true).write(body, 0, body.length);
      cout.flush();
    }

    void nonHttp(byte[] consumed) throws IOException {
      if (Net.mode == Net.Mode.REPLAY) {
        String msg = "network replay: no recorded response for a non-HTTP connection to " + host + ":" + port;
        missed.incrementAndGet();
        Net.log(msg);
        Net.unmatched.add(msg);
        NetFailures.note(msg);
        return;
      }
      // The JDK's own TLS through the proxy's tunnel (a relayed HttpsURLConnection) is expected, and the tap reads it.
      boolean tlsHello = consumed.length > 0 && consumed[0] == 0x16;
      if (!tlsHello && NOT_TUNNELLED.add("raw " + host + ":" + port)) Net.log("network: " + host + ":" + port + " is not HTTP/1.1: carried as it is, so no inspector rule applies to it");
      if (up == null || uout == null) return;  // no server to carry it to (a rule was to answer it)
      if (consumed.length > 0) uout.write(consumed);
      watching = false;
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

  /** Headers that belong to one connection, not to the request or answer a forward rule passes on. */
  private static final java.util.Set<String> HOP_HEADERS = java.util.Set.of("host", "content-length", "connection", "keep-alive",
      "transfer-encoding", "te", "trailer", "upgrade", "expect", "http2-settings");
  /**
   * A connect failure that means the machine has no way to the address, not that the server said no: macOS reports a
   * missing IPv6 route as NoRouteToHostException, Linux as a SocketException "Network is unreachable" (ENETUNREACH;
   * a ConnectException with that text on some JDKs), and "No route to host" (EHOSTUNREACH) or "Cannot assign requested
   * address" (EADDRNOTAVAIL) are the same kind. A refusal or a timeout is not (issue 470).
   */
  static boolean noRoute(IOException e) {
    if (e instanceof java.net.NoRouteToHostException) return true;
    if (!(e instanceof java.net.SocketException)) return false; // ConnectException is one
    String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
    return m.contains("unreachable") || m.contains("no route to host") || m.contains("cannot assign requested address");
  }

  static final class Request {
    String method, target, version, url;
    final List<String[]> headers = new ArrayList<>();
    /** The body as kept (at most MAX_BODY), the SHA-256 of all of it, and whether there was one at all. */
    byte[] body;
    String bodySha;
    boolean hasBody, bodyTruncated;
    /** For the app's own proxy (an absolute target, or CONNECT, that reached the proxy through here). */
    boolean toProxy;

    /** The request as a recording would hold it: the passwords this replay types as x (Net.blank); itself without any. */
    Request blanked() {
      Request req = this;
      String url = req.url;
      byte[] body = req.bodyTruncated ? null : req.body;
      boolean changed = false;
      for (Net.Blank b : Net.blanks) {
        byte[] u = Recording.scrub(url.getBytes(StandardCharsets.UTF_8), b.text(), b.length());
        byte[] d = Recording.scrub(body, b.text(), b.length());
        if (u != null) url = new String(u, StandardCharsets.UTF_8);
        if (d != null) body = d;
        changed |= u != null || d != null;
      }
      if (!changed) return req;
      Request r = new Request();
      r.method = req.method;
      r.target = req.target;
      r.version = req.version;
      r.url = url;
      for (String[] h : req.headers) {
        boolean length = body != null && h[0].equalsIgnoreCase("Content-Length");
        r.headers.add(length ? new String[] {h[0], String.valueOf(body.length)} : h);
      }
      r.body = req.bodyTruncated ? req.body : body;
      r.bodySha = body == null ? req.bodySha : Recording.sha256(body);
      r.hasBody = req.hasBody;
      r.bodyTruncated = req.bodyTruncated;
      r.toProxy = req.toProxy;
      return r;
    }

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

  /**
   * The request as the server gets it: Expect kept only when the server is to answer it (recording), proxy headers
   * only when the server is the app's own proxy.
   */
  static byte[] requestHead(Request req, boolean expect) {
    StringBuilder b = new StringBuilder(req.method).append(' ').append(req.target).append(' ').append(req.version).append("\r\n");
    for (String[] h : req.headers) {
      if (h[0].equalsIgnoreCase("Proxy-Connection") && !req.toProxy || h[0].equalsIgnoreCase("Expect") && !expect) continue;
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
    Thread upstream = Thread.ofVirtual().name("apksim-net-upstream").start(() -> pump(cin, uout, false, Long.MAX_VALUE, up));
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
    Thread writer = Thread.ofVirtual().name("apksim-net-writer").start(() -> {
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
        // The end of one direction: a plain socket half-closes, as TCP lets it; TLS cannot, so its socket closes (the
        // client's side gave up, with or without a WebSocket close frame), which also ends the other direction.
        if (outSocket != null && !outSocket.isClosed()) {
          if (outSocket instanceof SSLSocket) outSocket.close();
          else outSocket.shutdownOutput();
        }
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
