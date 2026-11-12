package dev.apksim.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The tab relay (docs/beam.md "The tab relay", issue 276): in a beam session that asked for it (`--tab-relay`), every
 * plain-HTTP request the app makes to {@link #HOST} is carried to the browser tab that started the session, which
 * answers it with its own credentials. For a page behind a sign-in proxy, which the app on the visitor's Mac cannot pass.
 *
 * <p>The hop stays inside the JVM, as the beam sandbox denies loopback and a standby fixes its sandbox before any
 * session exists: the resolver gives the host a placeholder address ({@link NetResolver}), a plain socket factory's
 * socket for it is a {@link RelaySocket} (in-memory pipes; any other address is passed to a real socket), and
 * `URL.openConnection` gets a {@link RelayConnection}. Both end in {@link #fetch}, which sends one line
 * `y ID {json}` over the instance's `relaywatch` control connection (the agent's Session passes it to the tab as a
 * text message) and waits for the tab's `y ID {json}` answer.
 *
 * <p>One request per connection (`Connection: close`), GET/HEAD/POST/PUT/PATCH/DELETE, bodies up to {@link #MAX_BODY}
 * each way (a base64 message then stays under the WebSocket's 1 MiB), {@link #ANSWER_MS} for the tab to answer (504),
 * {@link #MAX_IN_FLIGHT} requests at once (503), 502 for anything pending when the tab goes. The agent filters no paths:
 * what to answer is the page's decision.
 */
public final class TabRelay {
  private TabRelay() {}

  /** The reserved host: `.invalid` never resolves anywhere else. */
  public static final String HOST = "page.apksim.invalid";
  public static final int MAX_BODY = 512 * 1024;
  static final int MAX_IN_FLIGHT = 16;
  static final long ANSWER_MS = Long.getLong("apksim.tabRelay.answerMs", 15_000);
  private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
  /** Headers that belong to one hop of the app's connection, or that the tab's fetch sets itself. */
  private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "content-length", "transfer-encoding", "expect",
      "proxy-connection", "proxy-authorization", "keep-alive", "te", "upgrade");
  private static final Set<String> NOT_RETURNED = Set.of("connection", "content-length", "transfer-encoding", "content-encoding",
      "keep-alive", "proxy-connection", "upgrade");

  /** Set at launch by `--tab-relay` (`-Dapksim.tabRelay=true`), and again when a standby takes the launch. */
  public static volatile boolean on;

  public static void load() {
    on = Boolean.getBoolean("apksim.tabRelay");
  }

  private static volatile PrintWriter sender;
  /** A tab has attached at least once (the agent attaches just after the control port opens, around app start). */
  private static volatile boolean linked;
  private static final long FIRST_LINK_MS = Long.getLong("apksim.tabRelay.firstLinkMs", 5_000);
  private static final Map<Long, CompletableFuture<Reply>> pending = new ConcurrentHashMap<>();
  private static final AtomicLong ids = new AtomicLong();
  private static final AtomicInteger inFlight = new AtomicInteger();

  record Reply(int status, List<String[]> headers, byte[] body) {}

  public static boolean isHost(String h) {
    return h != null && h.equalsIgnoreCase(HOST);
  }

  /** Whether this address is the relay's: named so, or the placeholder the resolver gave it. */
  static boolean matches(InetSocketAddress a) {
    if (a == null) return false;
    if (isHost(a.getHostString())) return true;
    return a.getAddress() != null && isHost(NetResolver.hostFor(a.getAddress().getHostAddress()));
  }

  // ---------------------------------------------------------------- the link to the tab (Control: relaywatch)

  /**
   * `relaywatch`: this connection is the relay's link until it closes. Lines out are `y ID {json}` requests, lines in
   * the tab's answers. A new link replaces an old one (the old session's pending requests fail with 502).
   */
  public static void serve(BufferedReader in, PrintWriter out) throws IOException {
    if (!on) {
      out.println("error: the tab relay is off (--tab-relay)");
      return;
    }
    out.println("ok relaywatch");
    synchronized (TabRelay.class) {
      sender = out;
      linked = true;
      TabRelay.class.notifyAll();
    }
    try {
      for (String line; (line = in.readLine()) != null; ) answer(line);
    } finally {
      synchronized (TabRelay.class) {
        if (sender == out) sender = null;
      }
      failAll(502, "the tab went away");
    }
  }

  /** The tab's `y ID {json}`: {"status", "headers", "body"} (base64). Anything wrong with it is a 502 for that request. */
  static void answer(String line) {
    if (!line.startsWith("y ")) return;
    int sp = line.indexOf(' ', 2);
    if (sp < 0) return;
    long id;
    try {
      id = Long.parseLong(line.substring(2, sp));
    } catch (NumberFormatException e) {
      return;
    }
    CompletableFuture<Reply> f = pending.get(id);
    if (f == null) return;  // answered late (504 went already), or never asked
    try {
      if (!(Json.parse(line.substring(sp + 1)) instanceof Map<?, ?> m)) throw new IllegalArgumentException("not an object");
      int status = m.get("status") instanceof Number n ? n.intValue() : 0;
      if (status < 100 || status > 599) throw new IllegalArgumentException("status " + status);
      List<String[]> headers = new ArrayList<>();
      if (m.get("headers") instanceof Map<?, ?> h) {
        for (Map.Entry<?, ?> e : h.entrySet()) {
          String name = String.valueOf(e.getKey());
          if (e.getValue() instanceof List<?> l) for (Object v : l) headers.add(new String[] {name, String.valueOf(v)});
          else if (e.getValue() != null) headers.add(new String[] {name, String.valueOf(e.getValue())});
        }
      }
      String b64 = m.get("body") instanceof String s ? s : "";
      if (b64.length() > (MAX_BODY / 3 + 1) * 4) throw new IllegalArgumentException("body over " + MAX_BODY / 1024 + " KB");
      f.complete(new Reply(status, headers, Base64.getDecoder().decode(b64)));
    } catch (RuntimeException e) {
      f.complete(text(502, "the tab's answer could not be used: " + e.getMessage()));
    }
  }

  private static void failAll(int status, String why) {
    for (CompletableFuture<Reply> f : pending.values()) f.complete(text(status, why));
  }

  static Reply text(int status, String body) {
    return new Reply(status, List.<String[]>of(new String[] {"Content-Type", "text/plain; charset=utf-8"}), body.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * One request to the tab and its answer, or the relay's own 4xx/5xx: 405 for a method not carried, 413 for a body over
   * the limit, 503 beyond {@link #MAX_IN_FLIGHT} in flight, 502 with no tab or one that goes, 504 after {@link #ANSWER_MS}.
   */
  static Reply fetch(String method, String path, List<String[]> headers, byte[] body) {
    if (!METHODS.contains(method)) return text(405, "the tab relay carries " + String.join(", ", new java.util.TreeSet<>(METHODS)));
    if (body.length > MAX_BODY) return text(413, "the tab relay carries bodies up to " + MAX_BODY / 1024 + " KB");
    PrintWriter out = sender;
    if (out == null && !linked) {  // a request from Application.onCreate can come before the agent has attached
      synchronized (TabRelay.class) {
        long end = System.currentTimeMillis() + FIRST_LINK_MS;
        try {
          while (sender == null && !linked && System.currentTimeMillis() < end) TabRelay.class.wait(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        out = sender;
      }
    }
    if (out == null) return text(502, "no tab to relay to");
    if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
      inFlight.decrementAndGet();
      return text(503, "too many requests in flight through the tab relay (" + MAX_IN_FLIGHT + ")");
    }
    long id = ids.incrementAndGet();
    CompletableFuture<Reply> f = new CompletableFuture<>();
    pending.put(id, f);
    try {
      StringBuilder b = new StringBuilder(256 + body.length * 4 / 3);
      b.append("y ").append(id).append(" {\"method\":");
      quote(b, method);
      b.append(",\"path\":");
      quote(b, path);
      b.append(",\"headers\":{");
      Map<String, StringBuilder> merged = new java.util.LinkedHashMap<>();
      for (String[] h : headers) {
        if (NOT_FORWARDED.contains(h[0].toLowerCase(Locale.ROOT))) continue;
        StringBuilder v = merged.get(h[0]);
        if (v == null) merged.put(h[0], new StringBuilder(h[1]));
        else v.append(", ").append(h[1]);
      }
      boolean first = true;
      for (Map.Entry<String, StringBuilder> e : merged.entrySet()) {
        if (!first) b.append(',');
        first = false;
        quote(b, e.getKey());
        b.append(':');
        quote(b, e.getValue().toString());
      }
      b.append("},\"body\":\"").append(Base64.getEncoder().encodeToString(body)).append("\"}");
      // Sent on a thread of its own: a stalled link holds only this send, and the deadline below still applies.
      PrintWriter w = out;
      Thread.ofVirtual().name("apksim-relay-send").start(() -> {
        synchronized (w) {
          w.println(b);
          if (w.checkError()) f.complete(text(502, "the tab went away"));
        }
      });
      try {
        return f.get(ANSWER_MS, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        return text(504, "the tab did not answer in " + ANSWER_MS / 1000 + " s");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return text(502, "interrupted");
      } catch (java.util.concurrent.ExecutionException e) {
        return text(502, String.valueOf(e.getCause()));
      }
    } finally {
      pending.remove(id);
      inFlight.decrementAndGet();
    }
  }

  private static void quote(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    b.append('"');
  }

  // ---------------------------------------------------------------- the app's side: one HTTP/1.1 request on a pipe

  /** Bytes between the app and the relay, in memory: read with the reader's timeout, closed from either side. */
  static final class Pipe {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private byte[] data = new byte[0];
    private int pos;
    private boolean closed;
    private final long cap;

    Pipe(long cap) {
      this.cap = cap;
    }

    synchronized void write(byte[] b, int off, int len) throws IOException {
      if (closed) throw new IOException("Broken pipe");
      if (buf.size() + (data.length - pos) + len > cap) throw new IOException("the tab relay takes requests up to " + cap / 1024 + " KB");
      buf.write(b, off, len);
      notifyAll();
    }

    /** -1 at the end (closed and drained); throws SocketTimeoutException after `timeoutMs` (0: wait for ever). */
    synchronized int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
      long deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;
      while (pos >= data.length) {
        if (buf.size() > 0) {
          data = buf.toByteArray();
          buf.reset();
          pos = 0;
          break;
        }
        if (closed) return -1;
        try {
          if (deadline == 0) {
            wait();
          } else {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) throw new java.net.SocketTimeoutException("Read timed out");
            wait(left);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new java.io.InterruptedIOException();
        }
      }
      int n = Math.min(len, data.length - pos);
      System.arraycopy(data, pos, b, off, n);
      pos += n;
      return n;
    }

    synchronized int available() {
      return (data.length - pos) + buf.size();
    }

    synchronized void close() {
      closed = true;
      notifyAll();
    }

    synchronized boolean isClosed() {
      return closed;
    }
  }

  static InputStream reader(Pipe p, java.util.function.IntSupplier timeout) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        return p.read(b, off, len, timeout.getAsInt());
      }

      @Override
      public int available() {
        return p.available();
      }

      @Override
      public void close() {
        p.close();
      }
    };
  }

  /**
   * The relay's end of a connection: reads one request from `req`, asks the tab, writes the answer to `resp`, closes
   * both. Runs on a thread of its own (a virtual one named apksim-relay-N).
   */
  static void serveConnection(Pipe req, Pipe resp) {
    try {
      InputStream in = reader(req, () -> 0);
      String line = readLine(in);
      if (line == null) return;  // closed without a request
      String[] parts = line.split(" ");
      if (parts.length != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/")) {
        respond(resp, text(400, "the tab relay takes plain HTTP/1.1 requests to " + HOST), "GET");
        return;
      }
      String method = parts[0];
      List<String[]> headers = new ArrayList<>();
      long length = 0;
      boolean chunked = false, expect = false;
      for (int n = 0; ; n++) {
        String h = readLine(in);
        if (h == null) return;
        if (h.isEmpty()) break;
        int c = h.indexOf(':');
        if (c <= 0 || n > 200) {
          respond(resp, text(400, "bad header"), method);
          return;
        }
        String name = h.substring(0, c).trim(), value = h.substring(c + 1).trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("content-length")) {
          try {
            length = Long.parseLong(value);
          } catch (NumberFormatException e) {
            respond(resp, text(400, "bad Content-Length"), method);
            return;
          }
        } else if (lower.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
          chunked = true;
        } else if (lower.equals("expect") && value.equalsIgnoreCase("100-continue")) {
          expect = true;
        }
        headers.add(new String[] {name, value});
      }
      if (!METHODS.contains(method)) {
        respond(resp, text(405, "the tab relay carries " + String.join(", ", new java.util.TreeSet<>(METHODS))), method);
        return;
      }
      if (length > MAX_BODY) {
        respond(resp, text(413, "the tab relay carries bodies up to " + MAX_BODY / 1024 + " KB"), method);
        return;
      }
      if (expect) resp.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0, 25);
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (chunked) {
        while (true) {
          String size = readLine(in);
          if (size == null) return;
          int semi = size.indexOf(';');
          long n = Long.parseLong((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
          if (n == 0) {
            while (true) {  // trailers
              String t = readLine(in);
              if (t == null || t.isEmpty()) break;
            }
            break;
          }
          if (body.size() + n > MAX_BODY) {
            respond(resp, text(413, "the tab relay carries bodies up to " + MAX_BODY / 1024 + " KB"), method);
            return;
          }
          body.write(in.readNBytes((int) n));
          readLine(in);
        }
      } else if (length > 0) {
        byte[] b = in.readNBytes((int) length);
        if (b.length < length) return;
        body.write(b);
      }
      respond(resp, fetch(method, parts[1], headers, body.toByteArray()), method);
    } catch (IOException | RuntimeException e) {
      try {
        respond(resp, text(502, "tab relay: " + e), "GET");
      } catch (IOException ignored) {
        // the app has gone
      }
    } finally {
      resp.close();
      req.close();
    }
  }

  private static void respond(Pipe resp, Reply r, String method) throws IOException {
    boolean noBody = method.equals("HEAD") || r.status == 204 || r.status == 304 || r.status < 200;
    StringBuilder h = new StringBuilder("HTTP/1.1 ").append(r.status).append(' ').append(Net.statusText(r.status)).append("\r\n");
    String headLength = null;
    for (String[] e : r.headers) {
      String lower = e[0].toLowerCase(Locale.ROOT);
      if (method.equals("HEAD") && lower.equals("content-length")) headLength = e[1];
      if (NOT_RETURNED.contains(lower) || e[0].indexOf('\r') >= 0 || e[0].indexOf('\n') >= 0 || e[1].indexOf('\r') >= 0
          || e[1].indexOf('\n') >= 0) {
        continue;
      }
      h.append(e[0]).append(": ").append(e[1]).append("\r\n");
    }
    if (r.status >= 200 && r.status != 204 && r.status != 304) {
      h.append("Content-Length: ").append(method.equals("HEAD") && headLength != null ? headLength : String.valueOf(r.body.length)).append("\r\n");
    }
    h.append("Connection: close\r\n\r\n");
    byte[] head = h.toString().getBytes(StandardCharsets.ISO_8859_1);
    resp.write(head, 0, head.length);
    if (!noBody && r.body.length > 0) resp.write(r.body, 0, r.body.length);
  }

  /** A line up to CRLF or LF as ISO-8859-1 (null at the end of input); a line over 16 KB is an error. */
  private static String readLine(InputStream in) throws IOException {
    StringBuilder b = new StringBuilder();
    for (int c; (c = in.read()) >= 0; ) {
      if (c == '\n') {
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == '\r') b.setLength(n - 1);
        return b.toString();
      }
      b.append((char) c);
      if (b.length() > 16 * 1024) throw new IOException("a line over 16 KB");
    }
    return b.length() == 0 ? null : b.toString();
  }

  private static final AtomicLong threads = new AtomicLong();

  /** Starts the relay's end of a new connection on a thread of its own. */
  static void start(Pipe req, Pipe resp) {
    Thread.ofVirtual().name("apksim-relay-" + threads.incrementAndGet()).start(() -> serveConnection(req, resp));
  }
}
