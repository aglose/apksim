package dev.apksim.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The network inspector (`--network-inspect`, docs/pr-preview.md "Network inspector"): the app's HTTP(S) exchanges as
 * they happen, for a viewer, and rules that answer a URL pattern with a status and body or slow it down. It sits on
 * the engine's in-memory capture (record mode with no file), so what it shows is what a recording would hold, with
 * the same header redaction ({@link Net#redact}); bodies are shown only when asked for (`network detail ID`).
 *
 * <p>Events go to `netwatch` connections (Control) as one JSON line each: the last {@link #KEEP} first (the app's
 * startup traffic came before the viewer connected), then each as it completes. Rules and details are `network rule
 * ...` and `network detail ID` commands (Net.command). Off, nothing here is reached.
 */
public final class Inspector {
  private Inspector() {}

  /** Set once at launch (`-Dapksim.networkInspect=true`): the engine is capturing and exchanges are reported. */
  static volatile boolean on;
  /** Exchanges remembered for late viewers and for `detail`. */
  static final int KEEP = 300;
  /** Captured bodies `detail` shows are cut at this many bytes (a detail line carries two, base64, under the WebSocket's 1 MB). */
  static final int BODY_MAX = 256 * 1024;
  /** A rule's body is at most this many bytes (the tab relay takes the same; serve checks the same limit). */
  static final int RULE_BODY_MAX = 512 * 1024;
  /** All the bodies `detail` keeps together; the oldest exchanges lose theirs first, whatever KEEP says. */
  static final long DETAILS_BYTES = 32L << 20;
  /** The most of a URL an event carries (`detail` has all of it). */
  static final int URL_SHOWN = 1500;
  /** Rules at most; each costs a pattern match per request. */
  static final int MAX_RULES = 32;

  private static final List<Viewer> viewers = new CopyOnWriteArrayList<>();
  private static final AtomicLong ids = new AtomicLong();
  private static final ArrayDeque<String> events = new ArrayDeque<>();
  /** What `detail` answers from: bounded copies (headers, and at most BODY_MAX of each body), never the capture's own entry. */
  private record Detail(String method, String url, int status, List<String[]> reqHeaders, List<String[]> respHeaders,
      byte[] reqBody, boolean reqCut, byte[] respBody, boolean respCut) {
    long size() {
      return reqBody.length + respBody.length + 1024;
    }
  }
  private static long detailBytes;  // guarded by details

  private static final Map<Long, Detail> details = new LinkedHashMap<>();
  private static final List<Rule> rules = new CopyOnWriteArrayList<>();
  private static final AtomicInteger ruleIds = new AtomicInteger();

  /** One rule: requests whose method and URL match get `status` and `body` (0: the real server's answer), after `latencyMs`. */
  static final class Rule {
    final int id;
    final String method, pattern, type;
    final int status, latencyMs;
    final byte[] body;
    final String[] parts;
    final AtomicInteger hits = new AtomicInteger();

    Rule(int id, String method, String pattern, int status, int latencyMs, String type, byte[] body) {
      this.id = id;
      this.method = method;
      this.pattern = pattern;
      this.status = status;
      this.latencyMs = latencyMs;
      this.type = type;
      this.body = body;
      this.parts = pattern.split("\\*", -1);
    }

    /**
     * `*` matches anything; a pattern with none matches anywhere in the URL. Parts between the stars are found in order, each
     * at its first place (linear scans: no backtracking, whatever the pattern, since a tab chooses it).
     */
    boolean test(String url) {
      if (parts.length == 1) return url.contains(pattern);
      String first = parts[0], last = parts[parts.length - 1];
      if (!url.startsWith(first) || url.length() < first.length() + last.length() || !url.endsWith(last)) return false;
      int at = first.length(), end = url.length() - last.length();
      for (int i = 1; i < parts.length - 1; i++) {
        int found = parts[i].isEmpty() ? at : url.indexOf(parts[i], at);
        if (found < 0 || found + parts[i].length() > end) return false;
        at = found + parts[i].length();
      }
      return true;
    }

    String json() {
      return "{\"id\":" + id + ",\"method\":" + q(method) + ",\"pattern\":" + q(pattern) + ",\"status\":" + status + ",\"latencyMs\":" + latencyMs
          + ",\"type\":" + q(type) + ",\"bytes\":" + body.length + ",\"hits\":" + hits.get() + "}";
    }
  }

  /** Whether a rule answers requests itself (a status): a connection to a server that is not there may then go on. */
  static boolean hasAnswerRules() {
    for (Rule r : rules) if (r.status > 0) return true;
    return false;
  }

  /** A connection the engine could not make: shown as a row (CONNECT, status 0) whose detail says why. */
  static void failure(String host, int port, String err) {
    Recording.Entry e = new Recording.Entry();
    e.method = port > 0 ? "CONNECT" : "DNS";
    e.url = port > 0 ? host + ":" + port : host;
    e.status = 0;
    e.respBody = err.replaceAll("[\\r\\n]+$", "").getBytes(StandardCharsets.UTF_8);
    exchange(e, 0, 0);
  }

  /** The first rule that takes this request, or null. */
  static Rule match(String method, String url) {
    for (Rule r : rules) {
      if ((r.method.equals("*") || r.method.equalsIgnoreCase(method)) && r.test(url)) {
        r.hits.incrementAndGet();
        return r;
      }
    }
    return null;
  }

  /**
   * One finished exchange: told to every viewer. `rule` is the id of the rule that answered or slowed it (0: none),
   * `addedMs` the latency a rule added before the request went on.
   */
  static void exchange(Recording.Entry e, int rule, long addedMs) {
    if (!on) return;
    long id = ids.incrementAndGet();
    String type = e.header(e.respHeaders, "Content-Type");
    long reqBytes = e.reqBody == null ? 0 : e.reqBody.length;
    String cl = e.header(e.reqHeaders, "Content-Length");
    if (e.reqTruncated && cl != null) reqBytes = parse(cl);
    // A body too big to keep has only its Content-Length to say how big; without one the size is unknown (-1).
    long respBytes = e.truncated ? (e.header(e.respHeaders, "Content-Length") == null ? -1 : parse(e.header(e.respHeaders, "Content-Length"))) : e.respBody.length;
    // The row shows at most URL_SHOWN characters of a URL (the engine takes request lines of 16 KB): the event stays small
    // enough for the server to relay, and `detail` has the whole address.
    boolean longUrl = e.url.length() > URL_SHOWN;
    String url = longUrl ? e.url.substring(0, URL_SHOWN) + "…" : e.url;
    String line = "{\"id\":" + id + ",\"t\":" + e.wallMs + ",\"method\":" + q(e.method) + ",\"url\":" + q(url) + (longUrl ? ",\"urlCut\":true" : "") + ",\"status\":" + e.status
        + ",\"ms\":" + (e.timeMs + addedMs) + ",\"reqBytes\":" + reqBytes + ",\"respBytes\":" + respBytes
        + (type == null ? "" : ",\"type\":" + q(type)) + (rule > 0 ? ",\"rule\":" + rule : "") + (addedMs > 0 ? ",\"addedMs\":" + addedMs : "") + "}";
    Detail d = new Detail(e.method, e.url, e.status, List.copyOf(e.reqHeaders), List.copyOf(e.respHeaders), cut(e.reqBody), e.reqTruncated || big(e.reqBody),
        cut(e.respBody), e.truncated || big(e.respBody));
    // History and each viewer's queue change together, under one lock, so a viewer that joins gets every event once and in
    // order; nothing is written to a socket here (a viewer that stops reading is dropped, never waited for).
    synchronized (events) {
      events.addLast(line);
      while (events.size() > KEEP) events.removeFirst();
      synchronized (details) {
        details.put(id, d);
        detailBytes += d.size();
        while (details.size() > KEEP || (detailBytes > DETAILS_BYTES && details.size() > 1)) {
          var it = details.entrySet().iterator();
          detailBytes -= it.next().getValue().size();
          it.remove();
        }
      }
      for (Viewer v : viewers) v.offer(line);
    }
  }

  private static boolean big(byte[] b) {
    return b != null && b.length > BODY_MAX;
  }

  private static byte[] cut(byte[] b) {
    return b == null ? new byte[0] : b.length > BODY_MAX ? java.util.Arrays.copyOf(b, BODY_MAX) : b;
  }

  /** One `netwatch` connection: a bounded queue and a thread of its own that writes it; a full queue ends the viewer. */
  private static final class Viewer {
    final java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.ArrayBlockingQueue<>(KEEP * 2);
    final Socket socket;
    volatile boolean dead;

    Viewer(Socket socket) {
      this.socket = socket;
    }

    void offer(String line) {
      if (dead) return;
      if (!queue.offer(line)) {
        dead = true;  // it is not reading: closed, so the writer's blocked write ends
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private static long parse(String s) {
    try {
      return s == null ? 0 : Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** The remembered events, for scripts/check-inspector-rules.sh. */
  static List<String> eventsForCheck() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  /** `netwatch`: this connection becomes the event stream (the remembered events first) until the viewer closes it. */
  static void watch(Socket s, PrintWriter out) throws IOException {
    Viewer v = new Viewer(s);
    synchronized (events) {
      for (String line : events) v.queue.offer(line);
      viewers.add(v);
    }
    Thread reader = Thread.ofVirtual().name("apksim-netwatch-reader").start(() -> {
      try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
        while (in.readLine() != null) {
          // nothing is expected
        }
      } catch (IOException ignored) {
      }
      v.dead = true;
    });
    try {
      while (!v.dead) {
        String line = v.queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (line == null) continue;
        out.println(line);
        if (out.checkError()) break;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      v.dead = true;
      viewers.remove(v);
      reader.interrupt();
    }
  }

  /** `network rule add METHOD PATTERN STATUS LATENCY_MS TYPE BODY64 | remove ID | clear | list`; one line back. */
  static String rule(String[] a) {
    if (!on) return "error: the network inspector is off";
    String sub = a.length > 2 ? a[2] : "list";
    switch (sub) {
      case "list" -> {
        StringBuilder b = new StringBuilder("[");
        for (Rule r : rules) b.append(b.length() > 1 ? "," : "").append(r.json());
        return b.append(']').toString();
      }
      case "clear" -> {
        rules.clear();
        return "ok";
      }
      case "remove" -> {
        if (a.length != 4 || !a[3].matches("[0-9]{1,9}")) return "error: network rule remove ID";
        int id = Integer.parseInt(a[3]);
        return rules.removeIf(r -> r.id == id) ? "ok" : "error: no rule " + id;
      }
      case "add" -> {
        if (a.length != 9) return "error: network rule add METHOD PATTERN STATUS LATENCY_MS TYPE BODY64";
        if (rules.size() >= MAX_RULES) return "error: at most " + MAX_RULES + " rules";
        try {
          int status = Integer.parseInt(a[5]), latency = Integer.parseInt(a[6]);
          if (status != 0 && (status < 200 || status > 599)) return "error: a status is 200-599, or 0 to keep the server's answer";
          if (latency < 0 || latency > 60_000) return "error: latency is 0-60000 ms";
          if (status == 0 && latency == 0) return "error: a rule needs a status or a latency";
          if (a[4].length() > 300 || a[4].isEmpty()) return "error: the pattern is 1-300 characters";
          String type = a[7].equals("-") ? "application/octet-stream" : a[7];
          if (!type.matches("[A-Za-z0-9.+/;=_-]{1,100}")) return "error: a content type is letters, digits and . + / ; = _ -";
          byte[] body = a[8].equals("-") ? new byte[0] : Base64.getDecoder().decode(a[8]);
          if (body.length > RULE_BODY_MAX) return "error: a body is at most " + RULE_BODY_MAX + " bytes";
          if (!(a[3].equals("*") || a[3].matches("[A-Za-z]{3,10}"))) return "error: the method is * or a name such as GET";
          Rule r = new Rule(ruleIds.incrementAndGet(), a[3].toUpperCase(Locale.ROOT), a[4], status, latency, type, body);
          rules.add(r);
          // A lookup that failed a moment ago is remembered by the JDK (10 s): forgotten, so the rule is met at the next try.
          if (status > 0) Net.flushDnsCache();
          return r.json();
        } catch (IllegalArgumentException e) {
          return "error: " + e.getMessage();
        }
      }
      default -> {
        return "error: network rule add|remove|clear|list";
      }
    }
  }

  /** `network detail ID`: the exchange's headers (redacted as recordings are) and bodies (at most {@link #BODY_MAX}), one JSON line. */
  static String detail(String[] a) {
    if (!on) return "error: the network inspector is off";
    if (a.length != 3 || !a[2].matches("[0-9]{1,18}")) return "error: network detail ID";
    Detail e;
    synchronized (details) {
      e = details.get(Long.parseLong(a[2]));
    }
    if (e == null) return "error: no exchange " + a[2] + " (the last " + KEEP + " are kept)";
    StringBuilder b = new StringBuilder("{\"id\":").append(a[2]).append(",\"method\":").append(q(e.method)).append(",\"url\":").append(q(e.url))
        .append(",\"status\":").append(e.status).append(",\"reqHeaders\":").append(headers(e.reqHeaders))
        .append(",\"respHeaders\":").append(headers(e.respHeaders));
    body(b, "req", e.reqBody, e.reqCut);
    body(b, "resp", e.respBody, e.respCut);
    return b.append('}').toString();
  }

  private static void body(StringBuilder b, String side, byte[] shown, boolean cut) {
    b.append(",\"").append(side).append("Body\":\"").append(Base64.getEncoder().encodeToString(shown)).append('"');
    b.append(",\"").append(side).append("Cut\":").append(cut);
  }

  private static String headers(List<String[]> hs) {
    StringBuilder b = new StringBuilder("[");
    for (String[] h : hs) b.append(b.length() > 1 ? "," : "").append('[').append(q(h[0])).append(',').append(q(h[1])).append(']');
    return b.append(']').toString();
  }

  /** A JSON string: quotes, backslashes, control characters and anything outside ASCII escaped, so a line stays one line. */
  static String q(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }
}
