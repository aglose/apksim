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
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
  /** Bodies shown are cut at this many bytes (the instance and the link carry no more). */
  static final int BODY_MAX = 64 * 1024;
  /** Rules at most; each costs a pattern match per request. */
  static final int MAX_RULES = 32;

  private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
  private static final AtomicLong ids = new AtomicLong();
  private static final ArrayDeque<String> events = new ArrayDeque<>();
  private static final Map<Long, Recording.Entry> details = new LinkedHashMap<>();
  private static final List<Rule> rules = new CopyOnWriteArrayList<>();
  private static final AtomicInteger ruleIds = new AtomicInteger();

  /** One rule: requests whose method and URL match get `status` and `body` (0: the real server's answer), after `latencyMs`. */
  static final class Rule {
    final int id;
    final String method, pattern, type;
    final int status, latencyMs;
    final byte[] body;
    final Pattern re;
    final AtomicInteger hits = new AtomicInteger();

    Rule(int id, String method, String pattern, int status, int latencyMs, String type, byte[] body) {
      this.id = id;
      this.method = method;
      this.pattern = pattern;
      this.status = status;
      this.latencyMs = latencyMs;
      this.type = type;
      this.body = body;
      // `*` matches anything; a pattern with none matches anywhere in the URL.
      StringBuilder re = new StringBuilder();
      String[] parts = pattern.split("\\*", -1);
      for (int i = 0; i < parts.length; i++) {
        if (i > 0) re.append(".*");
        re.append(Pattern.quote(parts[i]));
      }
      this.re = Pattern.compile(parts.length == 1 ? ".*" + re + ".*" : re.toString(), Pattern.DOTALL);
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
      if ((r.method.equals("*") || r.method.equalsIgnoreCase(method)) && r.re.matcher(url).matches()) {
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
    long respBytes = e.truncated ? parse(e.header(e.respHeaders, "Content-Length")) : e.respBody.length;
    String line = "{\"id\":" + id + ",\"t\":" + e.wallMs + ",\"method\":" + q(e.method) + ",\"url\":" + q(e.url) + ",\"status\":" + e.status
        + ",\"ms\":" + (e.timeMs + addedMs) + ",\"reqBytes\":" + reqBytes + ",\"respBytes\":" + respBytes
        + (type == null ? "" : ",\"type\":" + q(type)) + (rule > 0 ? ",\"rule\":" + rule : "") + (addedMs > 0 ? ",\"addedMs\":" + addedMs : "") + "}";
    synchronized (events) {
      events.addLast(line);
      while (events.size() > KEEP) events.removeFirst();
      synchronized (details) {
        details.put(id, e);
        if (details.size() > KEEP) details.remove(details.keySet().iterator().next());
      }
    }
    for (Consumer<String> l : listeners) l.accept(line);
  }

  private static long parse(String s) {
    try {
      return s == null ? 0 : Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** `netwatch`: this connection becomes the event stream (the remembered events first) until the viewer closes it. */
  static void watch(Socket s, PrintWriter out) throws IOException {
    Consumer<String> l = line -> {
      synchronized (out) {
        out.println(line);
      }
    };
    synchronized (events) {
      for (String line : events) l.accept(line);
      listeners.add(l);
    }
    try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
      while (in.readLine() != null) {
        // nothing is expected
      }
    } finally {
      listeners.remove(l);
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
          if (status != 0 && (status < 100 || status > 599)) return "error: a status is 100-599, or 0 to keep the server's answer";
          if (latency < 0 || latency > 60_000) return "error: latency is 0-60000 ms";
          if (status == 0 && latency == 0) return "error: a rule needs a status or a latency";
          if (a[4].length() > 300 || a[4].isEmpty()) return "error: the pattern is 1-300 characters";
          String type = a[7].equals("-") ? "application/octet-stream" : a[7];
          if (!type.matches("[A-Za-z0-9.+/;=_-]{1,100}")) return "error: a content type is letters, digits and . + / ; = _ -";
          byte[] body = a[8].equals("-") ? new byte[0] : Base64.getDecoder().decode(a[8]);
          if (body.length > BODY_MAX) return "error: a body is at most " + BODY_MAX + " bytes";
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
    Recording.Entry e;
    synchronized (details) {
      e = details.get(Long.parseLong(a[2]));
    }
    if (e == null) return "error: no exchange " + a[2] + " (the last " + KEEP + " are kept)";
    StringBuilder b = new StringBuilder("{\"id\":").append(a[2]).append(",\"method\":").append(q(e.method)).append(",\"url\":").append(q(e.url))
        .append(",\"status\":").append(e.status).append(",\"reqHeaders\":").append(headers(e.reqHeaders))
        .append(",\"respHeaders\":").append(headers(e.respHeaders));
    body(b, "req", e.reqBody, e.reqTruncated);
    body(b, "resp", e.respBody, e.truncated);
    return b.append('}').toString();
  }

  private static void body(StringBuilder b, String side, byte[] body, boolean truncated) {
    byte[] shown = body == null ? new byte[0] : body.length > BODY_MAX ? java.util.Arrays.copyOf(body, BODY_MAX) : body;
    b.append(",\"").append(side).append("Body\":\"").append(Base64.getEncoder().encodeToString(shown)).append('"');
    b.append(",\"").append(side).append("Cut\":").append(truncated || body != null && body.length > BODY_MAX);
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
