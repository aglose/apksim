package dev.apksim.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The beam tab relay (docs/beam.md "Tab relay"): plain HTTP the app sends to {@link #HOST} is carried to the browser
 * tab that started the session, which answers it (typically by fetching its own site, signed in). On only when the agent
 * launched the instance with `-Dapksim.beamRelay=true` for a page that asked; otherwise the name resolves nowhere, as
 * any `.invalid` name does.
 *
 * Nothing leaves the JVM (the beam sandbox refuses loopback connections anyway): the hooks in {@link Net} give an app
 * socket for the host an in-memory connection ({@link RelaySocketImpl}), and an HttpURLConnection one of
 * {@link RelayUrlConnection}. One request a connection is read, handed to the control connection that said `relay
 * attach` as a line `relay ID BASE64(JSON)`, and the answer that comes back as `relayed ID BASE64(JSON)` is the
 * response (`Connection: close`).
 *
 * An app's network security config does not stop it: apksim installs none (libcore's default policy permits cleartext),
 * so an app that denies plain http everywhere reaches the relay too (the test app does, beam-check.py --relay). Should
 * that change, this host must stay exempt: it never reaches a network.
 */
public final class TabRelay {
  private TabRelay() {}

  /** The reserved name (RFC 2606: `.invalid` never resolves). */
  public static final String HOST = "page.apksim.invalid";
  static final int MAX_IN_FLIGHT = 16;
  static final int MAX_BODY = 512 * 1024;
  /** How long the tab has to answer (`-Dapksim.beamRelay.timeoutMs`, for checks only). */
  static final long TIMEOUT_MS = Long.getLong("apksim.beamRelay.timeoutMs", 15_000);
  static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
  /** Not passed either way: hop-by-hop headers, Host (the tab's fetch names its own) and Content-Length (recomputed). */
  static final Set<String> DROPPED = Set.of("connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailer",
      "host", "content-length");
  /**
   * Not passed to the tab: Accept-Encoding (a page's fetch cannot set it, and decodes what it gets). Not passed to the
   * app: Content-Encoding, as the body a tab sends is the decoded one (fetch's), whatever its server's header said.
   */
  static final String REQUEST_ONLY_DROPPED = "accept-encoding";
  static final String ANSWER_ONLY_DROPPED = "content-encoding";

  static volatile boolean on;
  /** Where requests go: the control connection that attached (one), else null (answered 502). */
  private static volatile Consumer<String> sink;
  private static final Map<Long, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
  private static final AtomicLong ids = new AtomicLong();
  private static final AtomicInteger inFlight = new AtomicInteger();
  private static final AtomicLong connections = new AtomicLong();

  /** From Net.configure: `-Dapksim.beamRelay=true` turns it on (the agent's own launch property, never a page's). */
  static synchronized void configure() {
    if (on || !Boolean.getBoolean("apksim.beamRelay")) return;
    on = true;
    Net.log("tab relay: http://" + HOST + " goes to the tab");
  }

  /** Whether this name is the relay's (and the relay is on). */
  static boolean isHost(String host) {
    return on && host != null && host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "").equals(HOST);
  }

  /** Whether an address the app connects to is the relay's: its name, or the placeholder NetResolver gave it. */
  static boolean claims(InetSocketAddress a) {
    if (!on) return false;
    if (a.isUnresolved()) return isHost(a.getHostString());
    return isHost(NetResolver.hostFor(a.getAddress().getHostAddress()));
  }

  private static ConnectException notRelayed(int port) {
    return new ConnectException("failed to connect to " + HOST + " (port " + port + "): the tab relay takes plain http on port 80 only");
  }

  /** Net.connect for the relay's host: port 80 plain HTTP only; the app's socket gets an in-memory connection. */
  static void connect(Socket s, InetSocketAddress a) throws IOException {
    if (s instanceof javax.net.ssl.SSLSocket || a.getPort() != 80) throw notRelayed(a.getPort());
    RelaySocketImpl impl = new RelaySocketImpl(a.isUnresolved() ? NetResolver.placeholderFor(HOST) : a.getAddress(), a.getPort(),
        (int) (connections.incrementAndGet() & 0x3fff) + 40000);
    try {
      // Socket's own hand-over of a connected implementation (ServerSocket's accept uses it); java.net is open to apksim.
      java.lang.reflect.Method set = Socket.class.getDeclaredMethod("setConnectedImpl", SocketImpl.class);
      set.setAccessible(true);
      set.invoke(s, impl);
    } catch (java.lang.reflect.InvocationTargetException e) {
      impl.closeBoth();
      throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
    } catch (ReflectiveOperationException | RuntimeException e) {
      impl.closeBoth();
      throw new IOException("the tab relay could not connect the socket: " + e, e);
    }
    Thread t = new Thread(() -> serve(impl.fromApp.in(), impl.toApp.out()), "apksim-relay-" + impl.localPort());
    t.setDaemon(true);
    t.start();
  }

  /** Net.open for the relay's host: an HttpURLConnection of the relay's own (the JDK's would open a real socket). */
  static java.net.URLConnection open(URL u) throws IOException {
    int port = u.getPort() < 0 ? u.getDefaultPort() : u.getPort();
    if (!u.getProtocol().equals("http") || port != 80) throw notRelayed(port);
    return new RelayUrlConnection(u);
  }

  // ------------------------------------------------------------------ the control connection (Control)

  /** `relay attach`: this control connection gets the requests from now on (the first to ask). */
  public static synchronized String attach(Consumer<String> to) {
    if (!on) return "error: relay: off (the agent starts the instance with it for a page that asks)";
    if (sink != null) return "error: relay: already attached";
    sink = to;
    return "ok relay";
  }

  /** That connection closed: what was waiting fails (502), and later requests do until another attaches. */
  public static synchronized void detach(Consumer<String> from) {
    if (sink != from) return;
    sink = null;
    for (Long id : List.copyOf(pending.keySet())) {
      CompletableFuture<String> f = pending.remove(id);
      if (f != null) f.completeExceptionally(new IOException("the tab has gone"));
    }
  }

  /** `relayed ID BASE64(JSON)`: the tab's answer to a request. Nothing is written back. */
  public static void answer(String[] a) {
    if (a.length < 2) return;
    long id;
    try {
      id = Long.parseLong(a[1]);
    } catch (NumberFormatException e) {
      return;
    }
    CompletableFuture<String> f = pending.remove(id);
    if (f == null) return;  // timed out meanwhile, or not one of ours
    try {
      f.complete(a.length > 2 ? new String(Base64.getDecoder().decode(a[2]), StandardCharsets.UTF_8) : "");
    } catch (IllegalArgumentException e) {
      f.completeExceptionally(new IOException("the answer was not base64"));
    }
  }

  // ------------------------------------------------------------------ the exchange

  /** An answer for the app: status, headers in order (a name may repeat), body. */
  record Answer(int status, List<String[]> headers, byte[] body) {
    static Answer text(int status, String text) {
      List<String[]> h = new ArrayList<>();
      h.add(new String[] {"Content-Type", "text/plain; charset=utf-8"});
      return new Answer(status, h, (text + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * One request to the tab, and its answer, or the relay's own: 502 with no tab (or it went), 503 past
   * {@link #MAX_IN_FLIGHT}, 504 after {@link #TIMEOUT_MS}, 502 for an answer it cannot use. `headers`: lowercase names.
   */
  static Answer exchange(String method, String path, Map<String, String> headers, byte[] body) {
    Consumer<String> to = sink;
    if (to == null) return Answer.text(502, "the tab relay has no tab");
    if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
      inFlight.decrementAndGet();
      return Answer.text(503, "the tab relay has " + MAX_IN_FLIGHT + " requests in flight");
    }
    long id = ids.incrementAndGet();
    CompletableFuture<String> f = new CompletableFuture<>();
    String answer;
    try {
      pending.put(id, f);
      StringBuilder j = new StringBuilder("{\"method\":").append(Json.quoted(method)).append(",\"path\":").append(Json.quoted(path))
          .append(",\"headers\":{");
      boolean first = true;
      for (Map.Entry<String, String> e : headers.entrySet()) {
        if (DROPPED.contains(e.getKey()) || e.getKey().equals(REQUEST_ONLY_DROPPED) || e.getKey().startsWith("proxy-")) continue;
        if (!first) j.append(',');
        first = false;
        j.append(Json.quoted(e.getKey())).append(':').append(Json.quoted(e.getValue()));
      }
      j.append("},\"body\":\"").append(Base64.getEncoder().encodeToString(body)).append("\"}");
      to.accept("relay " + id + " " + Base64.getEncoder().encodeToString(j.toString().getBytes(StandardCharsets.UTF_8)));
      answer = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return Answer.text(504, "the tab did not answer within " + TIMEOUT_MS / 1000 + " s");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Answer.text(502, "the tab relay was interrupted");
    } catch (java.util.concurrent.ExecutionException e) {
      return Answer.text(502, "the tab relay: " + e.getCause().getMessage());
    } finally {
      pending.remove(id);
      inFlight.decrementAndGet();
    }
    return parseAnswer(answer);
  }

  /** The tab's `{"status", "headers", "body"}`; anything malformed is a 502. */
  private static Answer parseAnswer(String json) {
    int status;
    Map<?, ?> headers;
    byte[] body;
    try {
      if (!(Json.parse(json) instanceof Map<?, ?> m)) throw new IllegalArgumentException("not an object");
      if (!(m.get("status") instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) throw new IllegalArgumentException("no status");
      status = n.intValue();
      if (status < 200 || status > 599) throw new IllegalArgumentException("status " + status);
      headers = m.get("headers") instanceof Map<?, ?> h ? h : Map.of();
      Object b = m.get("body");
      body = b == null ? new byte[0] : b instanceof String bs ? Base64.getDecoder().decode(bs) : null;
      if (body == null) throw new IllegalArgumentException("body is not base64 text");
    } catch (IllegalArgumentException e) {
      return Answer.text(502, "the tab's answer was malformed: " + e.getMessage());
    }
    if (body.length > MAX_BODY) return Answer.text(502, "the tab's answer is over " + MAX_BODY / 1024 + " KB");
    List<String[]> out = new ArrayList<>();
    for (Map.Entry<?, ?> e : headers.entrySet()) {
      String name = String.valueOf(e.getKey());
      String lower = name.toLowerCase(Locale.ROOT);
      if (DROPPED.contains(lower) || lower.equals(ANSWER_ONLY_DROPPED) || lower.startsWith("proxy-") || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) continue;
      List<?> values = e.getValue() instanceof List<?> l ? l : List.of(String.valueOf(e.getValue()));
      for (Object v : values) {
        String value = String.valueOf(v);
        if (value.indexOf('\r') < 0 && value.indexOf('\n') < 0) out.add(new String[] {name, value});  // no header injection
      }
    }
    return new Answer(status, out, body);
  }

  // ------------------------------------------------------------------ HTTP on an in-memory socket

  /** Reads the app's one request from its socket, and writes the answer back. */
  private static void serve(InputStream in, OutputStream out) {
    try (in; out) {
      String first = Net.readLine(in);
      if (first == null) return;
      Map<String, String> headers = new LinkedHashMap<>();
      int headerBytes = 0;
      for (String h; (h = Net.readLine(in)) != null && !h.isEmpty(); ) {
        headerBytes += h.length();
        if (headerBytes > 64 * 1024) {
          write(out, Answer.text(431, "the request's headers are too large"), false);
          return;
        }
        int colon = h.indexOf(':');
        if (colon <= 0) continue;
        String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = h.substring(colon + 1).trim();
        headers.merge(name, value, (x, y) -> name.equals("cookie") ? x + "; " + y : x + ", " + y);
      }
      String[] line = first.split(" ");
      if (line.length != 3 || !line[2].startsWith("HTTP/1.")) {
        write(out, Answer.text(400, "not an HTTP/1.1 request"), false);
        return;
      }
      String method = line[0];
      boolean head = method.equals("HEAD");
      String problem = requestProblem(method, line[1], headers);
      if (problem != null) {
        int status = Integer.parseInt(problem.substring(0, 3));
        write(out, Answer.text(status, problem.substring(4)), head);
        return;
      }
      long length = headers.containsKey("content-length") ? Long.parseLong(headers.get("content-length")) : 0;
      byte[] body = in.readNBytes((int) length);
      if (body.length != length) return;  // the app gave up
      write(out, exchange(method, line[1], headers, body), head);
    } catch (IOException | RuntimeException e) {
      // the app closed its connection
    }
  }

  /** Why a request cannot be relayed, as "STATUS text", or null. */
  static String requestProblem(String method, String path, Map<String, String> headers) {
    if (!METHODS.contains(method)) return "405 the tab relay takes GET, HEAD, POST, PUT, PATCH and DELETE";
    if (!path.startsWith("/")) return "400 the tab relay takes a path (/...), not " + (path.length() > 40 ? path.substring(0, 40) : path);
    if (headers.containsKey("transfer-encoding")) return "411 the tab relay needs a Content-Length (no chunked request bodies)";
    String cl = headers.get("content-length");
    if (cl != null) {
      long length;
      try {
        length = Long.parseLong(cl);
      } catch (NumberFormatException e) {
        return "400 bad Content-Length";
      }
      if (length < 0) return "400 bad Content-Length";
      if (length > MAX_BODY) return "413 the tab relay takes request bodies up to " + MAX_BODY / 1024 + " KB";
    }
    return null;
  }

  private static void write(OutputStream out, Answer a, boolean head) throws IOException {
    StringBuilder h = new StringBuilder("HTTP/1.1 ").append(a.status()).append(' ').append(reason(a.status())).append("\r\n");
    for (String[] kv : a.headers()) h.append(kv[0]).append(": ").append(kv[1]).append("\r\n");
    boolean noBody = a.status() == 204 || a.status() == 304;
    if (!noBody) h.append("Content-Length: ").append(a.body().length).append("\r\n");
    h.append("Connection: close\r\n\r\n");
    out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    if (!head && !noBody) out.write(a.body());
    out.flush();
  }

  static String reason(int status) {
    String r = switch (status) {
      case 405 -> "Method Not Allowed";
      case 411 -> "Length Required";
      case 413 -> "Content Too Large";
      case 431 -> "Request Header Fields Too Large";
      case 502 -> "Bad Gateway";
      case 503 -> "Service Unavailable";
      case 504 -> "Gateway Timeout";
      default -> Net.statusText(status);
    };
    return r.isEmpty() ? "Status" : r;
  }

  // ------------------------------------------------------------------ the in-memory connection

  /** One direction of a connection: bytes written at one end, read at the other (blocking, with a read timeout). */
  static final class Pipe {
    private byte[] buf = new byte[8192];
    private int start;
    private int end;
    private boolean writeClosed;
    private boolean readClosed;

    synchronized void write(byte[] b, int off, int len) throws IOException {
      if (readClosed || writeClosed) throw new java.net.SocketException("Socket closed");
      if (end + len > buf.length) {
        // Move what is unread to the front, growing when that is not room enough (bodies are bounded by MAX_BODY).
        int unread = end - start;
        byte[] to = unread + len > buf.length ? new byte[Math.max(buf.length * 2, unread + len)] : buf;
        System.arraycopy(buf, start, to, 0, unread);
        buf = to;
        start = 0;
        end = unread;
      }
      System.arraycopy(b, off, buf, end, len);
      end += len;
      notifyAll();
    }

    /** -1 at the end; throws SocketTimeoutException after `timeoutMs` (0: none) without data. */
    synchronized int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
      if (len == 0) return 0;
      long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;
      while (start >= end) {
        if (readClosed) throw new java.net.SocketException("Socket closed");
        if (writeClosed) return -1;
        long wait = deadline == 0 ? 0 : deadline - System.currentTimeMillis();
        if (deadline != 0 && wait <= 0) throw new java.net.SocketTimeoutException("Read timed out");
        try {
          wait(wait);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new java.io.InterruptedIOException();
        }
      }
      int n = Math.min(len, end - start);
      System.arraycopy(buf, start, b, off, n);
      start += n;
      if (start == end) start = end = 0;
      return n;
    }

    synchronized int available() {
      return end - start;
    }

    synchronized void closeWrite() {
      writeClosed = true;
      notifyAll();
    }

    synchronized void closeRead() {
      readClosed = true;
      notifyAll();
    }

    /** The reading end, for the relay's side (no timeout: the app's end closing ends it). */
    InputStream in() {
      return new InputStream() {
        @Override public int read() throws IOException {
          byte[] one = new byte[1];
          return Pipe.this.read(one, 0, 1, 0) < 0 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
          return Pipe.this.read(b, off, len, 0);
        }
        @Override public void close() {
          closeRead();
        }
      };
    }

    /** The writing end, for the relay's side. */
    OutputStream out() {
      return new OutputStream() {
        @Override public void write(int b) throws IOException {
          Pipe.this.write(new byte[] {(byte) b}, 0, 1);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
          Pipe.this.write(b, off, len);
        }
        @Override public void close() {
          closeWrite();
        }
      };
    }
  }

  /** The app's socket's implementation: connected to the relay through two pipes, nothing underneath. */
  static final class RelaySocketImpl extends SocketImpl {
    final Pipe toApp = new Pipe();
    final Pipe fromApp = new Pipe();
    private volatile int timeout;

    RelaySocketImpl(InetAddress remote, int remotePort, int local) {
      address = remote;
      port = remotePort;
      localport = local;
    }

    int localPort() {
      return localport;
    }

    void closeBoth() {
      toApp.closeRead();
      fromApp.closeWrite();
    }

    @Override protected void create(boolean stream) {}
    @Override protected void connect(String host, int port) throws IOException { throw new java.net.SocketException("already connected"); }
    @Override protected void connect(InetAddress address, int port) throws IOException { throw new java.net.SocketException("already connected"); }
    @Override protected void connect(java.net.SocketAddress address, int timeout) throws IOException { throw new java.net.SocketException("already connected"); }
    @Override protected void bind(InetAddress host, int port) throws IOException { throw new java.net.SocketException("already bound"); }
    @Override protected void listen(int backlog) throws IOException { throw new java.net.SocketException("not a server socket"); }
    @Override protected void accept(SocketImpl s) throws IOException { throw new java.net.SocketException("not a server socket"); }
    @Override protected void sendUrgentData(int data) throws IOException { throw new java.net.SocketException("no urgent data on the tab relay"); }

    @Override
    protected InputStream getInputStream() {
      return new InputStream() {
        @Override public int read() throws IOException {
          byte[] one = new byte[1];
          return toApp.read(one, 0, 1, timeout) < 0 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
          return toApp.read(b, off, len, timeout);
        }
        @Override public int available() {
          return toApp.available();
        }
      };
    }

    @Override
    protected OutputStream getOutputStream() {
      return new OutputStream() {
        @Override public void write(int b) throws IOException {
          fromApp.write(new byte[] {(byte) b}, 0, 1);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
          fromApp.write(b, off, len);
        }
      };
    }

    @Override protected int available() {
      return toApp.available();
    }

    @Override protected void close() {
      closeBoth();
    }

    @Override protected void shutdownInput() {
      toApp.closeRead();
    }

    @Override protected void shutdownOutput() {
      fromApp.closeWrite();
    }

    @Override
    public void setOption(int optID, Object value) {
      if (optID == java.net.SocketOptions.SO_TIMEOUT && value instanceof Integer t) timeout = t;
    }

    @Override
    public Object getOption(int optID) {
      return switch (optID) {
        case java.net.SocketOptions.SO_TIMEOUT -> timeout;
        case java.net.SocketOptions.TCP_NODELAY, java.net.SocketOptions.SO_KEEPALIVE, java.net.SocketOptions.SO_OOBINLINE -> Boolean.FALSE;
        case java.net.SocketOptions.SO_LINGER -> -1;
        case java.net.SocketOptions.SO_SNDBUF, java.net.SocketOptions.SO_RCVBUF -> 65536;
        case java.net.SocketOptions.SO_BINDADDR -> InetAddress.getLoopbackAddress();
        case java.net.SocketOptions.IP_TOS -> 0;
        default -> null;
      };
    }
  }

  /** HttpURLConnection to the relay's host: the request buffered, sent when the response is first asked for. */
  static final class RelayUrlConnection extends java.net.HttpURLConnection {
    private ByteArrayOutputStream body;
    private Answer answer;

    RelayUrlConnection(URL u) {
      super(u);
    }

    /** Nothing to connect: the exchange is made when the response is first asked for (`connected` stays false until then, as URLConnection reads its request properties only before it). */
    @Override public void connect() {}

    @Override public void disconnect() {}

    @Override public boolean usingProxy() {
      return false;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      if (!doOutput) throw new java.net.ProtocolException("cannot write to a URLConnection if doOutput=false - call setDoOutput(true)");
      if (answer != null) throw new java.net.ProtocolException("cannot write request body after response has been read");
      if (method.equals("GET")) method = "POST";  // as the JDK's connection does
      if (body == null) body = new ByteArrayOutputStream();
      return body;
    }

    private synchronized Answer answer() {
      if (answer != null) return answer;
      Map<String, String> headers = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> e : getRequestProperties().entrySet()) {
        if (e.getKey() != null) headers.put(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", e.getValue()));
      }
      connected = true;
      String path = (url.getPath().isEmpty() ? "/" : url.getPath()) + (url.getQuery() == null ? "" : "?" + url.getQuery());
      byte[] b = body == null ? new byte[0] : body.toByteArray();
      if (body != null) headers.put("content-length", Integer.toString(b.length));
      String problem = requestProblem(method, path, headers);
      answer = problem != null ? Answer.text(Integer.parseInt(problem.substring(0, 3)), problem.substring(4)) : exchange(method, path, headers, b);
      return answer;
    }

    @Override public int getResponseCode() {
      return answer().status();
    }

    @Override public String getResponseMessage() {
      return reason(answer().status());
    }

    @Override
    public InputStream getInputStream() throws IOException {
      Answer a = answer();
      if (a.status() >= 400) {
        if (a.status() == 404 || a.status() == 410) throw new java.io.FileNotFoundException(url.toString());
        throw new IOException("Server returned HTTP response code: " + a.status() + " for URL: " + url);
      }
      return new java.io.ByteArrayInputStream(method.equals("HEAD") ? new byte[0] : a.body());
    }

    @Override
    public InputStream getErrorStream() {
      Answer a = answer;
      return a != null && a.status() >= 400 && a.body().length > 0 ? new java.io.ByteArrayInputStream(a.body()) : null;
    }

    /** The response's fields as the JDK numbers them: 0 the status line, then the headers, Content-Length last. */
    private List<String[]> fields() {
      Answer a = answer();
      List<String[]> f = new ArrayList<>();
      f.add(new String[] {null, "HTTP/1.1 " + a.status() + " " + reason(a.status())});
      f.addAll(a.headers());
      if (a.status() != 204 && a.status() != 304) f.add(new String[] {"Content-Length", Integer.toString(a.body().length)});
      return f;
    }

    @Override public String getHeaderField(int n) {
      List<String[]> f = fields();
      return n >= 0 && n < f.size() ? f.get(n)[1] : null;
    }

    @Override public String getHeaderFieldKey(int n) {
      List<String[]> f = fields();
      return n > 0 && n < f.size() ? f.get(n)[0] : null;
    }

    @Override
    public String getHeaderField(String name) {
      String v = null;
      for (String[] kv : fields()) {
        if (kv[0] != null && kv[0].equalsIgnoreCase(name)) v = kv[1];
      }
      return v;
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      Map<String, List<String>> m = new java.util.TreeMap<>((x, y) -> x == null ? (y == null ? 0 : -1) : y == null ? 1 : x.compareToIgnoreCase(y));
      for (String[] kv : fields()) m.computeIfAbsent(kv[0], k -> new ArrayList<>()).add(kv[1]);
      return java.util.Collections.unmodifiableMap(m);
    }
  }
}
