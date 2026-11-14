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
  /** Captured bodies `detail` shows are cut at this many bytes (a detail line carries two, base64: about 0.7 MB, and the browser decodes it). */
  static final int BODY_MAX = 256 * 1024;
  /** A rule's body is at most this many bytes (the tab relay takes the same; serve checks the same limit). */
  static final int RULE_BODY_MAX = 512 * 1024;
  /** All the bodies `detail` keeps together; the oldest exchanges lose theirs first, whatever KEEP says. */
  static final long DETAILS_BYTES = 32L << 20;
  /** The most of a URL an event carries (`detail` has all of it). */
  static final int URL_SHOWN = 1500;
  /** Rules at most by default; each costs a pattern match per request. */
  public static final int MAX_RULES = 32;
  /** All the rules' bodies together: what 32 rules of {@link #RULE_BODY_MAX} came to before the limit could be raised. */
  static final long RULE_BODIES_MAX = 16L << 20;
  /** The most `--network-inspect-rules N` (serve --inspect-max-rules) may ask for. */
  public static final int MAX_RULES_LIMIT = 256;
  /** Rules at most in this instance: {@link #MAX_RULES}, or the launch's `-Dapksim.networkInspectRules` (1 to {@link #MAX_RULES_LIMIT}). */
  static volatile int maxRules = MAX_RULES;
  /** A rule's extra response headers: this many at most, and this many bytes of names and values together. */
  public static final int RULE_HEADERS_MAX = 20, RULE_HEADER_BYTES = 2048;

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
  static long detailBytesForCheck() {
    synchronized (details) {
      return detailBytes;
    }
  }
  private static long detailBytes;  // guarded by details

  private static final Map<Long, Detail> details = new LinkedHashMap<>();
  /**
   * The rules, in order: an immutable list replaced whole under {@link #rulesLock}, so a request (which reads the field
   * once) is matched against the list as it was before a change or after it, never a `rule set` half applied.
   */
  private static volatile List<Rule> rules = List.of();
  private static final Object rulesLock = new Object();
  private static final AtomicInteger ruleIds = new AtomicInteger();

  /** A forward rule's target is at most this long, and a request body it sends on at most this big (it is held in memory). */
  static final int FORWARD_URL_MAX = 2000;
  static final int FORWARD_BODY_MAX = 16 * 1024 * 1024;

  /**
   * One rule: requests whose method and URL match get `status` and `body` (0: the real server's answer), after `latencyMs`;
   * with `forward` (issue 363) they are sent to that http(s) URL instead and its answer goes back to the app.
   */
  static final class Rule {
    final int id;
    final String method, pattern, type;
    /** Where a forward rule sends its requests, or null. */
    final String forward;
    final int status, latencyMs;
    final byte[] body;
    /** Response headers the answer carries beside its content type (name, value), in the order given. */
    final List<String[]> headers;
    final String[] parts;
    final AtomicInteger hits = new AtomicInteger();

    Rule(int id, String method, String pattern, int status, int latencyMs, String type, byte[] body) {
      this(id, method, pattern, status, latencyMs, type, body, List.of());
    }

    Rule(int id, String method, String pattern, int status, int latencyMs, String type, byte[] body, List<String[]> headers) {
      this(id, method, pattern, status, latencyMs, type, body, headers, null);
    }

    Rule(int id, String method, String pattern, int status, int latencyMs, String type, byte[] body, List<String[]> headers, String forward) {
      this.forward = forward;
      this.headers = headers;
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

    /** Whether the rule answers requests itself (a status, or another server's answer): nothing reaches the app's server. */
    boolean answers() {
      return status > 0 || forward != null;
    }

    String json() {
      return "{\"id\":" + id + ",\"method\":" + q(method) + ",\"pattern\":" + q(pattern) + ",\"status\":" + status + ",\"latencyMs\":" + latencyMs
          + ",\"type\":" + q(type) + ",\"bytes\":" + body.length + (forward == null ? "" : ",\"forward\":" + q(forward)) + (headers.isEmpty() ? "" : ",\"headers\":" + headers(headers)) + ",\"hits\":" + hits.get() + "}";
    }
  }

  /** Whether a rule answers requests itself (a status or a forward): a connection to a server that is not there may then go on. */
  static boolean hasAnswerRules() {
    for (Rule r : rules) if (r.answers()) return true;
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

  /** A connection relayed as an opaque tunnel (the client offered h2): a row, status 200, whose detail says it was not read. */
  static void tunnel(String host, int port) {
    Recording.Entry e = new Recording.Entry();
    e.method = "CONNECT";
    e.url = host + ":" + port;
    e.status = 200;
    e.respBody = "opaque tunnel: the client offered h2, so its TLS is end to end and not captured".getBytes(StandardCharsets.UTF_8);
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
    exchange(e, rule, addedMs, false);
  }

  /** As above; `forwarded`: the answer (or the 502) came from a forward rule's target, not the app's server. */
  static void exchange(Recording.Entry e, int rule, long addedMs, boolean forwarded) {
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
        + (type == null ? "" : ",\"type\":" + q(type)) + (rule > 0 ? ",\"rule\":" + rule : "") + (forwarded ? ",\"forwarded\":true" : "") + (addedMs > 0 ? ",\"addedMs\":" + addedMs : "") + "}";
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

  /**
   * Why a rule may not set this response header, or null. The name is an HTTP token; the engine writes the answer's framing
   * (length, connection) and its content type itself, so those names are refused, and a value is one line of printable ASCII.
   */
  public static String headerProblem(String name, String value) {
    if (!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")) return "a header name is 1-64 letters, digits and !#$%&'*+.^_`|~-";
    String n = name.toLowerCase(Locale.ROOT);
    if (FRAMING_HEADERS.contains(n) || n.startsWith("proxy-")) {
      return "a rule cannot set " + name + (n.equals("content-type") ? " (the rule's content type is its own field)" : " (the engine frames the answer)");
    }
    if (!value.matches("[\\t\\x20-\\x7e]{0,1024}")) return "a header value is at most 1024 printable ASCII characters on one line";
    return null;
  }

  private static final java.util.Set<String> FRAMING_HEADERS = java.util.Set.of("content-length", "transfer-encoding", "content-type", "connection",
      "keep-alive", "upgrade", "trailer", "te", "x-apksim-rule");

  /** A rule's headers as the command carries them: `-`, or base64 of `Name: value` lines. */
  private static List<String[]> parseHeaders(String token) {
    if (token.equals("-")) return List.of();
    String text = new String(Base64.getDecoder().decode(token), StandardCharsets.ISO_8859_1);
    List<String[]> out = new java.util.ArrayList<>();
    int bytes = 0;
    for (String line : text.split("\n")) {
      if (line.isBlank()) continue;
      int colon = line.indexOf(':');
      if (colon < 1) throw new IllegalArgumentException("a header is Name: value");
      String name = line.substring(0, colon), value = line.substring(colon + 1).strip();
      String why = headerProblem(name, value);
      if (why != null) throw new IllegalArgumentException(why);
      bytes += name.length() + value.length();
      out.add(new String[] {name, value});
    }
    if (out.size() > RULE_HEADERS_MAX) throw new IllegalArgumentException("a rule has at most " + RULE_HEADERS_MAX + " headers");
    if (bytes > RULE_HEADER_BYTES) throw new IllegalArgumentException("a rule's headers are at most " + RULE_HEADER_BYTES + " bytes together");
    return List.copyOf(out);
  }

  /** One rule from `METHOD PATTERN STATUS LATENCY_MS TYPE BODY64 [HEADERS64]` at a[at]; an IllegalArgumentException says what is wrong. */
  private static Rule parseRule(String[] a, int at, boolean headers) {
    if (a[at + 2].equals("forward")) return parseForward(a, at, headers);
    int status, latency;
    try {
      status = Integer.parseInt(a[at + 2]);
      latency = Integer.parseInt(a[at + 3]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("a status and a latency are numbers");
    }
    if (status != 0 && (status < 200 || status > 599)) throw new IllegalArgumentException("a status is 200-599, or 0 to keep the server's answer");
    if (latency < 0 || latency > 60_000) throw new IllegalArgumentException("latency is 0-60000 ms");
    if (status == 0 && latency == 0) throw new IllegalArgumentException("a rule needs a status or a latency");
    if (a[at + 1].length() > 300 || a[at + 1].isEmpty()) throw new IllegalArgumentException("the pattern is 1-300 characters");
    String type = a[at + 4].equals("-") ? "application/octet-stream" : a[at + 4];
    if (!type.matches("[A-Za-z0-9.+/;=_-]{1,100}")) throw new IllegalArgumentException("a content type is letters, digits and . + / ; = _ -");
    byte[] body = a[at + 5].equals("-") ? new byte[0] : Base64.getDecoder().decode(a[at + 5]);
    if (body.length > RULE_BODY_MAX) throw new IllegalArgumentException("a body is at most " + RULE_BODY_MAX + " bytes");
    if (!(a[at].equals("*") || a[at].matches("[A-Za-z]{3,10}"))) throw new IllegalArgumentException("the method is * or a name such as GET");
    List<String[]> hs = headers ? parseHeaders(a[at + 6]) : List.of();
    return new Rule(ruleIds.incrementAndGet(), a[at].toUpperCase(Locale.ROOT), a[at + 1], status, latency, type, body, hs);
  }

  private static long bodies(List<Rule> rs) {
    long n = 0;
    for (Rule r : rs) n += r.body.length;
    return n;
  }

  private static String list(List<Rule> rs) {
    StringBuilder b = new StringBuilder("[");
    for (Rule r : rs) b.append(b.length() > 1 ? "," : "").append(r.json());
    return b.append(']').toString();
  }

  /**
   * `network rule add METHOD PATTERN STATUS LATENCY_MS TYPE BODY64 [HEADERS64] | set COUNT (METHOD PATTERN STATUS LATENCY_MS
   * TYPE BODY64 HEADERS64)... | remove ID | clear | list | max`; one line back. `set` replaces every rule with the ones given,
   * in that order, at once (new ids, hits from 0), and answers the new list; nothing changes when one of them is refused.
   * A STATUS of `forward` makes a forward rule: BODY64 is then the target URL (base64), TYPE and HEADERS64 `-`. Which targets
   * a tab may name is the server's to decide (serve --inspect-forward-allow): this command is the owner's.
   */
  static String rule(String[] a) {
    if (!on) return "error: the network inspector is off";
    String sub = a.length > 2 ? a[2] : "list";
    switch (sub) {
      case "list" -> {
        return list(rules);
      }
      case "max" -> {
        return String.valueOf(maxRules);
      }
      case "clear" -> {
        synchronized (rulesLock) {
          rules = List.of();
        }
        return "ok";
      }
      case "remove" -> {
        if (a.length != 4 || !a[3].matches("[0-9]{1,9}")) return "error: network rule remove ID";
        int id = Integer.parseInt(a[3]);
        synchronized (rulesLock) {
          List<Rule> next = new java.util.ArrayList<>(rules);
          if (!next.removeIf(r -> r.id == id)) return "error: no rule " + id;
          rules = List.copyOf(next);
        }
        return "ok";
      }
      case "add" -> {
        if (a.length != 9 && a.length != 10) return "error: network rule add METHOD PATTERN STATUS|forward LATENCY_MS TYPE BODY64|URL64 [HEADERS64]";
        try {
          Rule r;
          synchronized (rulesLock) {
            if (rules.size() >= maxRules) return "error: at most " + maxRules + " rules";
            r = parseRule(a, 3, a.length == 10);
            List<Rule> next = new java.util.ArrayList<>(rules);
            next.add(r);
            if (bodies(next) > RULE_BODIES_MAX) return "error: the rules' bodies are at most " + RULE_BODIES_MAX + " bytes together";
            rules = List.copyOf(next);
          }
          // A lookup that failed a moment ago is remembered by the JDK (10 s): forgotten, so the rule is met at the next try.
          if (r.answers()) Net.flushDnsCache();
          return r.json();
        } catch (IllegalArgumentException e) {
          return "error: " + e.getMessage();
        }
      }
      case "set" -> {
        if (a.length < 4 || !a[3].matches("[0-9]{1,4}")) return "error: network rule set COUNT (METHOD PATTERN STATUS LATENCY_MS TYPE BODY64 HEADERS64)...";
        int n = Integer.parseInt(a[3]);
        if (a.length != 4 + 7 * n) return "error: network rule set: " + n + " rules are " + 7 * n + " words, got " + (a.length - 4);
        if (n > maxRules) return "error: at most " + maxRules + " rules";
        List<Rule> next = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
          try {
            next.add(parseRule(a, 4 + 7 * i, true));
          } catch (IllegalArgumentException e) {
            return "error: rule " + (i + 1) + ": " + e.getMessage();
          }
        }
        if (bodies(next) > RULE_BODIES_MAX) return "error: the rules' bodies are at most " + RULE_BODIES_MAX + " bytes together";
        List<Rule> now = List.copyOf(next);
        synchronized (rulesLock) {
          rules = now;
        }
        for (Rule r : now) {
          if (r.answers()) {
            Net.flushDnsCache();
            break;
          }
        }
        return list(now);
      }
      default -> {
        return "error: network rule add|set|remove|clear|list|max";
      }
    }
  }

  /** A forward rule from the same words: STATUS `forward`, TYPE `-`, the target URL (base64) where a body would be, no headers. */
  private static Rule parseForward(String[] a, int at, boolean headers) {
    int latency;
    try {
      latency = Integer.parseInt(a[at + 3]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("a latency is a number");
    }
    if (latency < 0 || latency > 60_000) throw new IllegalArgumentException("latency is 0-60000 ms");
    if (a[at + 1].length() > 300 || a[at + 1].isEmpty()) throw new IllegalArgumentException("the pattern is 1-300 characters");
    if (!(a[at].equals("*") || a[at].matches("[A-Za-z]{3,10}"))) throw new IllegalArgumentException("the method is * or a name such as GET");
    if (!a[at + 4].equals("-") || headers && !a[at + 6].equals("-")) {
      throw new IllegalArgumentException("a forward rule has no content type or headers (-): its target's answer has its own");
    }
    String url = a[at + 5].equals("-") ? "" : new String(Base64.getDecoder().decode(a[at + 5]), StandardCharsets.UTF_8);
    String why = forwardProblem(url);
    if (why != null) throw new IllegalArgumentException(why);
    return new Rule(ruleIds.incrementAndGet(), a[at].toUpperCase(Locale.ROOT), a[at + 1], 0, latency, "-", new byte[0], List.of(), url);
  }

  /** Why a URL cannot be a forward rule's target, or null: an absolute http(s) URL with a host, no user info, no fragment. */
  public static String forwardProblem(String url) {
    if (url.isEmpty() || url.length() > FORWARD_URL_MAX) return "a forward target is a URL of 1-" + FORWARD_URL_MAX + " characters";
    if (!url.matches("[\\x21-\\x7e]+")) return "a forward target has no spaces or characters outside ASCII";
    java.net.URI u;
    try {
      u = new java.net.URI(url);
    } catch (java.net.URISyntaxException e) {
      return "a forward target is an http(s) URL";
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getHost().isEmpty()) return "a forward target is an http(s) URL";
    if (u.getRawUserInfo() != null || u.getRawFragment() != null) return "a forward target has no user info and no fragment";
    return null;
  }

  /** `network detail ID`: the exchange's headers (redacted as recordings are) and bodies (at most {@link #BODY_MAX}), one JSON line. */
  static String detail(String[] a) {
    if (!on) return "error: the network inspector is off";
    if (a.length != 3 || !a[2].matches("[0-9]{1,18}")) return "error: network detail ID";
    Detail e;
    synchronized (details) {
      e = details.get(Long.parseLong(a[2]));
    }
    if (e == null) return "error: no exchange " + a[2] + " (only recent exchanges are kept: at most " + KEEP + ", fewer when their bodies are large)";
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
