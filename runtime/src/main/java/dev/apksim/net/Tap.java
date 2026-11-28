package dev.apksim.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongPredicate;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * The tap: how the app's network is observed (docs/network.md "The tap"). Recording, the flight recorder's net channel
 * and the network inspector all read what the app's own connections carry, without standing between the app and the
 * server: the app's socket is the socket it made, connected where it connected it, with the TLS, the ALPN protocol and
 * the timing it would have had. The translator sends the app's `Socket.getInputStream()` and `getOutputStream()` calls
 * here ({@link Net#inputStream}); for a socket the hooks saw connect, the stream handed back copies each read and write
 * (above TLS, so in the clear) into a queue, and returns. One thread, `apksim-net-tap`, turns the copies into exchanges:
 * HTTP/1.1 ({@link TapHttp1}), HTTP/2 with HPACK and so gRPC ({@link TapHttp2}), WebSocket messages
 * ({@link TapWebSocket}); anything else is counted as an opaque connection.
 *
 * <p>What the app's threads pay per read or write is one array copy and one queue node. Nothing here can fail a
 * connection or make it wait: a decoder that meets bytes it cannot read gives that connection up (the app's bytes go on),
 * and when the decoder falls behind by {@link #MAX_QUEUED} bytes the connections that would add more stop being
 * captured instead of waiting for it. Off, a hooked call is the original call after one field read.
 */
final class Tap {
  private Tap() {}

  /** Whether connections are being captured. */
  static volatile boolean on;

  /** The error of an exchange whose socket the app closed under it (a cancelled request): not a failure (NetFailures). */
  static final String CLOSED_BY_APP = "closed by the app";

  /** Bodies bigger than this are counted and hashed but not kept (as the engine's). */
  static final int MAX_BODY = 32 << 20;
  /** A request or response head, or an HTTP/2 control frame, bigger than this cannot be one: the connection is given up. */
  static final int MAX_HEAD = 1 << 20;
  /** Copies waiting for the decoder, at most: past it, a connection that would add more is no longer captured. */
  static final long MAX_QUEUED = 48L << 20;
  /** Connections remembered for a recording's `_apksimConnections` once they have closed. */
  private static final int CLOSED_KEPT = 400;

  /** What reads the copies of one connection; called on the tap's thread only. */
  interface Decoder {
    void bytes(boolean out, byte[] d, long nanos) throws IOException;

    /** The connection is over: `eof` when the server ended its stream, else why (`error`). */
    void end(boolean eof, String error, long nanos);

    /** Exchanges begun and not finished, as they stand. */
    void unfinished(List<Recording.Entry> into);

    String protocol();
  }

  // ------------------------------------------------------------------ connections

  private static final AtomicInteger ids = new AtomicInteger();
  /** Sockets the hooks saw connect (or layer TLS), and where they go. Weak: a socket the app drops is forgotten. */
  private static final Map<Socket, Conn> known = Collections.synchronizedMap(new WeakHashMap<>());

  static final class Conn {
    final int id = ids.incrementAndGet();
    final String host;
    final int port;
    final boolean tls;
    /** How long the TCP connect took, or -1. */
    final long connectMs;
    final long openedWall = System.currentTimeMillis();
    /** Through the engine's relay: the socket's own address is the engine's, not the server's. */
    boolean relayed;
    /** Not HTTP: its bytes are only counted from here on, not copied (set by the tap's thread, read by the app's). */
    volatile boolean countOnly;
    final AtomicLong uncopiedIn = new AtomicLong(), uncopiedOut = new AtomicLong();
    final WeakReference<Socket> socket;
    /** Set once: no longer captured (the tap went off, the queue overflowed, or the decoder gave up). */
    volatile boolean dead;
    /** App data has flowed, so the TLS handshake is over and the session can be read without starting one. */
    volatile boolean established;
    /** TLS layered by the app's factory whose handshake has not been seen to finish (a listener on the socket says so). */
    volatile boolean handshakePending;
    private volatile boolean queuedEnd;

    // The tap's thread only, from here.
    Decoder decoder;
    private List<Chunk> undecided;
    private int undecidedBytes;
    boolean live, ended, sessionRead, swept;
    long bytesIn, bytesOut, closedWall;
    int exchanges;
    String address, tlsVersion, cipher, alpn, error;
    /** A read that timed out, or another failure the connection may survive: why an exchange never got its answer. */
    String note;

    Conn(Socket s, String host, int port, boolean tls, long connectMs) {
      this.socket = new WeakReference<>(s);
      this.host = host;
      this.port = port;
      this.tls = tls;
      this.connectMs = connectMs;
    }

    /** The app's thread: bytes it is about to send, or has just received. */
    void feed(boolean out, byte[] b, int off, int n) {
      if (dead || n <= 0) return;
      if (!on) {
        dead = true;
        return;
      }
      if (countOnly) {
        (out ? uncopiedOut : uncopiedIn).addAndGet(n);
        return;
      }
      if (queued.get() > MAX_QUEUED) {
        dead = true;
        put(new Chunk(this, LOST, System.nanoTime(), null, null));
        return;
      }
      put(new Chunk(this, out ? OUT : IN, System.nanoTime(), Arrays.copyOfRange(b, off, off + n), null));
    }

    /** The app's thread: the connection is over (once). */
    void end(boolean eof, String error) {
      if (dead || queuedEnd) return;
      queuedEnd = true;
      put(new Chunk(this, eof ? EOF : END, System.nanoTime(), null, error));
    }

    /** The app's thread: something the connection may survive (a read timeout). */
    void note(String text) {
      if (dead || queuedEnd) return;
      put(new Chunk(this, NOTE, System.nanoTime(), null, text));
    }

    void failed(boolean out, IOException e) {
      if (e instanceof java.net.SocketTimeoutException) {
        note(e.toString());
      } else if (e instanceof java.net.SocketException && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("closed")) {
        end(false, null);  // the app closed the socket under its own read or write
      } else {
        end(false, (out ? "write failed: " : "read failed: ") + e);
      }
    }

    @Override
    public String toString() {
      return host + ":" + port;
    }
  }

  /** A socket connected by the hooks, directly or through the engine's relay: its streams may be tapped. */
  static void connected(Socket s, String host, int port, long connectMs) {
    known.put(s, new Conn(s, host, port, s instanceof SSLSocket, connectMs));
  }

  /** A socket connected through the engine's relay: carried untouched, so read as a direct one is. */
  static void relayed(Socket s, String host, int port) {
    Conn c = new Conn(s, host, port, s instanceof SSLSocket, -1);
    c.relayed = true;
    known.put(s, c);
  }

  /** TLS layered on `raw` by the app's factory: `tls` carries what the app says, in the clear, above it. */
  static void layered(Socket tls, Socket raw, String host, int port) {
    Conn under = raw == null ? null : known.get(raw);
    Conn c = new Conn(tls, host, port, true, under == null ? -1 : under.connectMs);
    c.relayed = under != null && under.relayed;
    known.put(tls, c);
    if (under != null && tls instanceof SSLSocket ssl) {
      c.handshakePending = true;
      handshaking.add(c);
      if (parked) LockSupport.unpark(thread);  // it may be parked for 30 s: a closed socket is noticed within a second
      try {
        ssl.addHandshakeCompletedListener(e -> c.handshakePending = false);
      } catch (RuntimeException | LinkageError e) {
        c.handshakePending = false;  // a provider's socket that takes none: no claim either way
      }
    }
  }

  /** A connection for a socket whose streams the caller wraps itself ({@link TapSslSocket}). */
  static Conn open(Socket s, String host, int port, long connectMs) {
    return new Conn(s, host, port, true, connectMs);
  }

  /** A connect that failed: a row for the inspector and a line in the recording's connections. */
  static void connectFailed(String host, int port, IOException e) {
    if (!on) return;
    Conn c = new Conn(null, host, port, false, -1);
    put(new Chunk(c, END, System.nanoTime(), null, "connect failed: " + e));
  }

  static InputStream in(Socket s, InputStream in) {
    Conn c = known.get(s);
    return c == null || c.dead ? in : new In(in, c);
  }

  static OutputStream out(Socket s, OutputStream out) {
    Conn c = known.get(s);
    return c == null || c.dead ? out : new Out(out, c);
  }

  static final class In extends InputStream {
    private final InputStream in;
    private final Conn c;

    In(InputStream in, Conn c) {
      this.in = in;
      this.c = c;
    }

    @Override
    public int read() throws IOException {
      int v;
      try {
        v = in.read();
      } catch (IOException e) {
        c.failed(false, e);
        throw e;
      }
      if (v < 0) {
        c.end(true, null);
      } else if (!c.dead) {
        c.established = true;
        c.feed(false, new byte[] {(byte) v}, 0, 1);
      }
      return v;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n;
      try {
        n = in.read(b, off, len);
      } catch (IOException e) {
        c.failed(false, e);
        throw e;
      }
      if (n > 0) {
        if (!c.dead) {
          c.established = true;
          c.feed(false, b, off, n);
        }
      } else if (n < 0) {
        c.end(true, null);
      }
      return n;
    }

    @Override
    public int available() throws IOException {
      return in.available();
    }

    @Override
    public void close() throws IOException {
      c.end(false, null);
      in.close();
    }
  }

  static final class Out extends OutputStream {
    private final OutputStream out;
    private final Conn c;

    Out(OutputStream out, Conn c) {
      this.out = out;
      this.c = c;
    }

    @Override
    public void write(int v) throws IOException {
      // Queued before it is sent, so the decoder has a request before the answer the server sends to it.
      if (!c.dead) c.feed(true, new byte[] {(byte) v}, 0, 1);
      try {
        out.write(v);
      } catch (IOException e) {
        c.failed(true, e);
        throw e;
      }
      c.established = true;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (!c.dead) c.feed(true, b, off, len);
      try {
        out.write(b, off, len);
      } catch (IOException e) {
        c.failed(true, e);
        throw e;
      }
      c.established = true;
    }

    @Override
    public void flush() throws IOException {
      out.flush();
    }

    @Override
    public void close() throws IOException {
      c.end(false, null);
      out.close();
    }
  }

  // ------------------------------------------------------------------ the queue and its thread

  private static final int IN = 0, OUT = 1, EOF = 2, END = 3, NOTE = 4, LOST = 5, RUN = 6;

  private record Chunk(Conn conn, int kind, long nanos, byte[] data, Object arg) {
    long size() {
      return 64 + (data == null ? 0 : data.length);
    }
  }

  private static final ConcurrentLinkedQueue<Chunk> queue = new ConcurrentLinkedQueue<>();
  private static final AtomicLong queued = new AtomicLong();
  private static volatile Thread thread;
  private static volatile boolean parked;
  /**
   * The wall clock at a nanoTime: now, less how long ago that was. Not from an anchor taken once: nanoTime stands still
   * while the machine sleeps, and every time after a night with the lid closed would be hours behind the clock a save
   * filters by.
   */
  static long wall(long nanos) {
    return System.currentTimeMillis() - (System.nanoTime() - nanos) / 1_000_000;
  }

  private static void put(Chunk c) {
    queued.addAndGet(c.size());
    queue.add(c);
    if (parked) LockSupport.unpark(thread);
  }

  /** Turns capturing on or off. Off, the connections being captured are let go: what they carry later is not read. */
  static synchronized void set(boolean v) {
    if (on == v) return;
    on = v;
    if (v) {
      if (thread == null) {
        Thread t = new Thread(Tap::run, "apksim-net-tap");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
      }
    } else {
      put(new Chunk(null, RUN, 0, null, (Runnable) Tap::letGo));
    }
  }

  /** Runs `r` on the tap's thread once everything queued before it is decoded; false if that took longer than `ms`. */
  static boolean sync(Runnable r, long ms) {
    Thread t = thread;
    if (t == null || Thread.currentThread() == t) {  // no thread yet: nothing it owns is in use
      r.run();
      return true;
    }
    CountDownLatch done = new CountDownLatch(1);
    put(new Chunk(null, RUN, 0, null, (Runnable) () -> {
      try {
        r.run();
      } finally {
        done.countDown();
      }
    }));
    try {
      return done.await(ms, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** The connections that have carried bytes and are not over. */
  private static final Set<Conn> live = new LinkedHashSet<>();
  /** Connections whose app-layered TLS handshake has not been seen to finish; read only for what they leave if they end first. */
  private static final Set<Conn> handshaking = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final ArrayDeque<Map<String, Object>> closed = new ArrayDeque<>();
  private static long sweptAt;

  private static void run() {
    while (true) {
      Chunk c = queue.poll();
      if (c == null) {
        try {
          sweep();
        } catch (Throwable t) {
          Net.log("network tap: " + t);
        }
        parked = true;
        if (queue.isEmpty()) LockSupport.parkNanos(Tap.class, live.isEmpty() && handshaking.isEmpty() ? 30_000_000_000L : 1_000_000_000L);
        parked = false;
        continue;
      }
      queued.addAndGet(-c.size());
      try {
        try {
          if (c.kind == RUN) ((Runnable) c.arg).run();
          else accept(c);
        } catch (Throwable t) {
          // Nothing the decoder meets may reach the app: the connection's capture ends here, its bytes go on.
          if (c.conn != null) giveUp(c.conn, t.toString(), c.nanos);
        }
        if (System.nanoTime() - sweptAt > 2_000_000_000L) sweep();
      } catch (Throwable t) {
        // Whatever it is, this thread goes on: without it every later connection would go unread.
        if (c.conn != null) {
          c.conn.dead = true;
          c.conn.ended = true;
          live.remove(c.conn);
        }
        Net.log("network tap: " + t);
      }
    }
  }

  private static void accept(Chunk k) throws IOException {
    Conn c = k.conn;
    if (c.ended) return;
    if (!c.live && (k.kind == IN || k.kind == OUT)) {
      c.live = true;
      live.add(c);
      Socket s = c.socket.get();
      InetAddress a = s == null || c.relayed ? null : s.getInetAddress();
      if (a != null) c.address = a.getHostAddress();
    }
    if (c.tls && !c.sessionRead && c.established) session(c);
    switch (k.kind) {
      case IN, OUT -> {
        boolean out = k.kind == OUT;
        if (out) c.bytesOut += k.data.length;
        else c.bytesIn += k.data.length;
        c.note = null;
        if (c.decoder == null && !decide(c, k)) return;
        c.decoder.bytes(out, k.data, k.nanos);
      }
      case NOTE -> c.note = (String) k.arg;
      case LOST -> giveUp(c, "the tap fell behind by " + (MAX_QUEUED >> 20) + " MB: this connection is no longer captured", k.nanos);
      default -> finish(c, k.kind == EOF, (String) k.arg, k.nanos);
    }
  }

  /**
   * Which protocol the connection speaks, from what the client sends first: the HTTP/2 preface, or an HTTP/1 request
   * line. Until that is clear the copies are held (a few hundred bytes); a server that speaks first, or a first line
   * that is neither, makes it opaque. False while still undecided.
   */
  private static boolean decide(Conn c, Chunk k) throws IOException {
    if (c.undecided == null) c.undecided = new ArrayList<>();
    c.undecided.add(k);
    c.undecidedBytes += k.data.length;
    ByteArrayOutputStream sent = new ByteArrayOutputStream();
    for (Chunk u : c.undecided) if (u.kind == OUT) sent.writeBytes(u.data);
    byte[] b = sent.toByteArray();
    Decoder d = null;
    byte[] preface = TapHttp2.PREFACE;
    int same = 0;
    while (same < b.length && same < preface.length && b[same] == preface[same]) same++;
    if (same == preface.length) {
      d = new TapHttp2(c);
    } else if (same == b.length && b.length > 0) {
      return false;  // the preface so far
    } else if (b.length == 0) {
      if (c.undecidedBytes > 0 && k.kind == IN) d = new Opaque("the server spoke first");
    } else {
      int eol = -1;
      for (int i = 0; i < b.length && eol < 0; i++) if (b[i] == '\n') eol = i;
      if (eol >= 0) {
        String line = new String(b, 0, eol, StandardCharsets.ISO_8859_1).trim();
        d = line.matches("[A-Z][A-Z-]{1,31} \\S+ HTTP/1\\.[01]") ? new TapHttp1(c) : new Opaque("not HTTP");
      } else {
        // No line yet: still a request line in the making only while it starts with a method (capitals, then a space).
        boolean method = b.length <= 16 * 1024;
        for (int i = 0; i < b.length && i < 33 && method; i++) {
          if (b[i] == ' ' && i > 1) break;
          if (!(b[i] >= 'A' && b[i] <= 'Z' || b[i] == '-') || i == 32) method = false;
        }
        if (method) return false;
        d = new Opaque("not HTTP");
      }
    }
    if (d == null) return false;
    if (d instanceof Opaque) c.countOnly = true;  // nothing will read its bytes: the app's threads stop copying them
    c.decoder = d;
    List<Chunk> held = c.undecided;
    c.undecided = null;
    for (int i = 0; i < held.size() - 1; i++) d.bytes(held.get(i).kind == OUT, held.get(i).data, held.get(i).nanos);
    return true;
  }

  /** A connection whose bytes are not HTTP: counted, and listed as one connection. */
  private static final class Opaque implements Decoder {
    final String why;

    Opaque(String why) {
      this.why = why;
    }

    @Override public void bytes(boolean out, byte[] d, long nanos) {}
    @Override public void end(boolean eof, String error, long nanos) {}
    @Override public void unfinished(List<Recording.Entry> into) {}
    @Override public String protocol() { return "opaque (" + why + ")"; }
  }

  /** The TLS session's protocol, cipher suite, ALPN choice and the server's certificates, read once the handshake is over. */
  private static void session(Conn c) {
    c.sessionRead = true;
    Socket s = c.socket.get();
    if (!(s instanceof SSLSocket ssl)) return;
    try {
      SSLSession session = ssl.getSession();
      c.tlsVersion = session.getProtocol();
      c.cipher = session.getCipherSuite();
      List<String> chain = new ArrayList<>();
      for (Certificate cert : session.getPeerCertificates()) chain.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
      Recording r = Net.recording;
      if (r != null && Net.mode == Net.Mode.LIVE) r.certificates(c.host, chain);
    } catch (Exception | LinkageError e) {
      // a session that cannot be read (closed meanwhile, no peer certificates): the exchanges are recorded without it
    }
    try {
      c.alpn = ssl.getApplicationProtocol();
    } catch (Exception | LinkageError e) {
      // a provider's socket that cannot say
    }
  }

  private static void giveUp(Conn c, String why, long nanos) {
    c.dead = true;
    if (c.ended) return;
    Net.log("network tap: " + c + " is no longer captured: " + why);
    finish(c, false, "capture stopped: " + why, nanos);
  }

  private static void finish(Conn c, boolean eof, String error, long nanos) {
    if (c.ended) return;
    c.ended = true;
    c.dead = true;
    c.closedWall = nanos == 0 ? System.currentTimeMillis() : wall(nanos);
    if (c.tls && !c.sessionRead && c.established) session(c);
    if (!eof) c.error = error != null ? error : c.note;
    boolean never = !c.live;  // a connect that failed
    if (c.decoder != null) {
      try {
        c.decoder.end(eof, c.error != null ? c.error : c.note != null ? c.note : eof ? null : CLOSED_BY_APP, nanos);
      } catch (RuntimeException e) {
        Net.log("network tap: " + c + ": " + e);
      }
    }
    live.remove(c);
    c.bytesIn += c.uncopiedIn.get();
    c.bytesOut += c.uncopiedOut.get();
    if (never && error != null && error.startsWith("connect failed")) {
      Inspector.failure(c.host, c.port, error);
      Net.connectFailed(c.host, c.port, error, c.openedWall, Math.max(0, c.closedWall - c.openedWall));
    }
    else if (never && c.handshakePending && !c.established) {
      // Issue 440: TLS the app layered on a connected socket that ended before any byte crossed it and before its
      // handshake completed: a row, as the app got an error and the server saw a connection.
      Inspector.failure(c.host, c.port, "TLS closed before the handshake was seen to finish" + (error != null ? ": " + error : ""));
    }
    else if (c.decoder instanceof Opaque o && c.bytesIn + c.bytesOut > 0) Inspector.opaque(c.host, c.port, c.bytesOut, c.bytesIn, o.why);
    if (never && c.error == null) return;  // made and closed with nothing said on it: not worth a line
    closed.addLast(describe(c));
    while (closed.size() > CLOSED_KEPT) closed.removeFirst();
  }

  /**
   * Connections the app closed (or dropped) without a read or write noticing: over, as far as the tap goes. Their end
   * is queued behind whatever copies of theirs still wait, so it is read after them.
   */
  private static void sweep() {
    sweptAt = System.nanoTime();
    for (Conn c : handshaking) {  // TLS layered on a socket that has carried nothing yet (issue 440)
      Socket s = c.socket.get();
      if (!c.handshakePending || c.live || c.ended) {
        handshaking.remove(c);
      } else if (s == null || s.isClosed()) {
        handshaking.remove(c);
        c.swept = true;
        put(new Chunk(c, END, sweptAt, null, null));
      }
    }
    if (live.isEmpty()) return;
    for (Conn c : live) {
      if (c.swept) continue;
      Socket s = c.socket.get();
      if (s == null || s.isClosed()) {
        c.swept = true;
        put(new Chunk(c, END, sweptAt, null, null));
      }
    }
  }

  /** The tap went off: connections in progress are forgotten, unreported. */
  private static void letGo() {
    for (Conn c : live) {
      c.dead = true;
      c.ended = true;
    }
    live.clear();
    for (Conn c : handshaking) {
      c.dead = true;
      c.ended = true;
    }
    handshaking.clear();
  }

  private static Map<String, Object> describe(Conn c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", c.id);
    m.put("host", c.host);
    m.put("port", c.port);
    if (c.address != null) m.put("address", c.address);
    m.put("opened", java.time.Instant.ofEpochMilli(c.openedWall).toString());
    if (c.closedWall > 0) m.put("closed", java.time.Instant.ofEpochMilli(c.closedWall).toString());
    if (c.connectMs >= 0) m.put("connectMs", c.connectMs);
    m.put("protocol", c.decoder == null ? (c.live ? "undecided" : "none") : c.decoder.protocol());
    if (c.tls) {
      Map<String, Object> tls = new LinkedHashMap<>();
      if (c.tlsVersion != null) tls.put("version", c.tlsVersion);
      if (c.cipher != null) tls.put("cipherSuite", c.cipher);
      if (c.alpn != null) tls.put("alpn", c.alpn);
      m.put("tls", tls);
    }
    m.put("bytesSent", c.bytesOut + (c.ended ? 0 : c.uncopiedOut.get()));
    m.put("bytesReceived", c.bytesIn + (c.ended ? 0 : c.uncopiedIn.get()));
    m.put("exchanges", c.exchanges);
    if (c.error != null) m.put("error", c.error);
    m.put("_opened", c.openedWall);
    m.put("_closed", c.closedWall > 0 ? c.closedWall : c.openedWall);
    return m;
  }

  /** The connections opened or closed since `keep` accepts (epoch ms), newest last, for a recording's `_apksimConnections`. */
  static List<Object> connections(LongPredicate keep) {
    List<Object> out = new ArrayList<>();
    List<Map<String, Object>> some = Collections.synchronizedList(new ArrayList<>());
    sync(() -> {
      for (Map<String, Object> m : closed) {
        if (keep.test((Long) m.get("_opened")) || keep.test((Long) m.get("_closed"))) some.add(m);
      }
      for (Conn c : live) some.add(describe(c));
    }, 500);
    List<Map<String, Object>> sorted = new ArrayList<>(some);
    sorted.sort(java.util.Comparator.comparingInt(m -> (Integer) m.get("id")));
    for (Map<String, Object> m : sorted) {
      Map<String, Object> shown = new LinkedHashMap<>(m);
      shown.remove("_opened");
      shown.remove("_closed");
      out.add(shown);
    }
    return out;
  }

  /** Exchanges begun and not finished (a stream still open, a request with no answer yet), as they stand now. */
  static List<Recording.Entry> unfinished() {
    List<Recording.Entry> out = Collections.synchronizedList(new ArrayList<>());
    sync(() -> {
      for (Conn c : live) {
        if (c.decoder == null) continue;
        List<Recording.Entry> some = new ArrayList<>();
        c.decoder.unfinished(some);
        for (Recording.Entry e : some) out.add(stamp(c, e));
      }
    }, 500);
    return new ArrayList<>(out);
  }

  /** How many exchanges are in flight now, as the decoder last saw them (no waiting): a busy network, to who asks. */
  static int inFlight() {
    return inFlight;
  }

  private static volatile int inFlight;

  private static volatile long flightAt, newestAt;

  /** A decoder's exchange began (+1) or is over (-1); the tap's thread only. */
  static void flight(int d) {
    inFlight = Math.max(0, inFlight + d);
    long now = System.nanoTime();
    flightAt = now;
    if (d > 0) newestAt = now;
  }

  /**
   * Whether the app's network is at work: a request began or ended within `quietMs`, or one that began within `youngMs`
   * still has no whole answer (a stream that stays open for minutes is not work in progress). False when the tap is off:
   * nothing is known then.
   */
  static boolean busy(long quietMs, long youngMs) {
    if (!on) return false;
    long now = System.nanoTime(), at = flightAt;
    if (at != 0 && now - at < quietMs * 1_000_000) return true;
    return inFlight > 0 && now - newestAt < youngMs * 1_000_000;
  }

  // ------------------------------------------------------------------ what decoders share

  /** A body as it passes: counted, optionally hashed, at most {@link #MAX_BODY} kept. */
  static final class Body {
    private ByteArrayOutputStream kept;
    private final MessageDigest digest;
    long total;

    Body(boolean hashed) {
      MessageDigest d = null;
      if (hashed) {
        try {
          d = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
          // matched by its bytes then
        }
      }
      digest = d;
    }

    void add(byte[] b, int off, int len) {
      if (len <= 0) return;
      if (digest != null) digest.update(b, off, len);
      if (kept == null) kept = new ByteArrayOutputStream(Math.max(len, 256));
      if (kept.size() < MAX_BODY) kept.write(b, off, Math.min(len, MAX_BODY - kept.size()));
      total += len;
    }

    byte[] bytes() {
      return kept == null ? new byte[0] : kept.toByteArray();
    }

    boolean truncated() {
      return total > (kept == null ? 0 : kept.size());
    }

    /** The hash of everything so far (the digest goes on: a copy is finished). */
    String sha256() {
      if (digest == null) return null;
      try {
        return java.util.HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
      } catch (CloneNotSupportedException e) {
        return null;
      }
    }
  }

  /** Bytes held between copies: the part of a head or frame that has come so far. */
  static final class Held {
    byte[] a = new byte[0];
    int n;

    void add(byte[] d) {
      if (a.length < n + d.length) a = Arrays.copyOf(a, Math.max(n + d.length, a.length * 2));
      System.arraycopy(d, 0, a, n, d.length);
      n += d.length;
    }

    /** Keeps src[off, end) for the next copy; src may be this buffer itself. */
    void keep(byte[] src, int off, int end) {
      int m = end - off;
      if (m == 0) {
        n = 0;
        if (a.length > 64 * 1024) a = new byte[0];
        return;
      }
      if (src != a) {
        if (a.length < m) a = new byte[Math.max(m, 256)];
        System.arraycopy(src, off, a, 0, m);
      } else if (off > 0) {
        System.arraycopy(a, off, a, 0, m);
      }
      n = m;
    }
  }

  /** One exchange as a decoder sees it, turned into a recording's entry by {@link #entry}. */
  static final class Ex {
    String method = "", url = "", version = "HTTP/1.1", respVersion;
    List<String[]> reqHeaders = new ArrayList<>(), respHeaders = new ArrayList<>(), trailers;
    Body reqBody, respBody;
    boolean reqDone, expectAnswered;
    int status;
    String statusText = "";
    /** nanoTime: the request's first byte, its last, the answer's first byte. */
    long t0, tSent, tFirst;
    final boolean keep = keeping();
    /** WebSocket messages after a 101, appended as they pass (the entry is added at the 101). */
    Recording.Entry entry;
  }

  /** Whether an exchange that begins now is kept by an in-memory capture (`rec off net` pauses it). */
  private static boolean keeping() {
    Recording r = Net.recording;
    return r == null || r.keep;
  }

  /** The entry for an exchange: finished at `now`, or as it stands (`error`: why it has no whole answer). */
  static Recording.Entry entry(Ex x, long now, String error, boolean unfinished) {
    Recording.Entry e = new Recording.Entry();
    e.wallMs = wall(x.t0);
    e.started = java.time.Instant.ofEpochMilli(e.wallMs).toString();
    e.keepAtStart = x.keep;
    e.method = x.method;
    e.url = x.url;
    e.httpVersion = x.version;
    e.respVersion = x.respVersion != null ? x.respVersion : x.version;
    e.reqHeaders = x.reqHeaders;
    if (x.reqBody != null && !x.expectAnswered) {
      e.reqTruncated = x.reqBody.truncated();
      e.reqBody = e.reqTruncated ? new byte[0] : x.reqBody.bytes();
      e.reqSha = x.reqBody.sha256();
      e.reqSize = x.reqBody.total;
    }
    e.expectAnswered = x.expectAnswered;
    e.status = x.status;
    e.statusText = x.statusText;
    e.respHeaders = x.respHeaders;
    e.trailers = x.trailers;
    e.error = error;
    e.unfinished = unfinished;
    if (x.respBody != null) {
      byte[] bytes = x.respBody.bytes();
      e.truncated = x.respBody.truncated();
      e.respSize = x.respBody.total;
      if (!e.truncated && !unfinished && error == null && "gzip".equalsIgnoreCase(e.header(e.respHeaders, "Content-Encoding"))) {
        // Stored decoded while that stays within MAX_BODY (as the engine does); else as sent.
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
    }
    long sent = x.tSent > 0 ? x.tSent : x.t0;
    e.send = Math.max(0, (sent - x.t0) / 1_000_000);
    if (x.tFirst > 0) {
      e.wait = Math.max(0, (x.tFirst - sent) / 1_000_000);
      e.receive = Math.max(0, (now - x.tFirst) / 1_000_000);
    }
    e.timeMs = Math.max(0, (now - x.t0) / 1_000_000);
    return e;
  }

  private static Recording.Entry stamp(Conn c, Recording.Entry e) {
    e.conn = c.id;
    e.serverIp = c.address;
    e.reqHeaders = redacted(e.reqHeaders);
    e.respHeaders = redacted(e.respHeaders);
    if (e.trailers != null) e.trailers = redacted(e.trailers);
    return e;
  }

  /** A decoder's finished exchange (or one cut short): to the recording and the inspector. */
  static void emit(Conn c, Recording.Entry e) {
    stamp(c, e);
    if (c.exchanges++ == 0) e.connect = c.connectMs;
    Net.tapped(e);
  }

  static List<String[]> redacted(List<String[]> hs) {
    List<String[]> out = new ArrayList<>(hs.size());
    for (String[] h : hs) out.add(Net.redact.contains(h[0].toLowerCase(Locale.ROOT)) ? new String[] {h[0], Recording.REDACTED} : h);
    return out;
  }

  static String header(List<String[]> hs, String name) {
    for (String[] h : hs) if (h[0].equalsIgnoreCase(name)) return h[1];
    return null;
  }
}
