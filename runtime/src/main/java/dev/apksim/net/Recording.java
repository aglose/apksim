package dev.apksim.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** A HAR 1.2 file of HTTP exchanges: what record mode writes and replay mode answers from (docs/network.md). */
final class Recording {
  /** Headers that describe one hop, not the request: never part of a header match. */
  private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding",
      "te", "trailer", "upgrade", "content-length", "host", "expect");
  static final String REDACTED = "REDACTED";
  /** How a failed connect's `_apksimError` begins; the exception follows, as its toString(). */
  static final String CONNECT_FAILED = "connect failed: ";

  /** What a replayed request must match (docs/network.md "Matching"). */
  record Match(Set<String> fields, Set<String> ignoreQuery, Set<String> ignoreHeaders, Set<String> redact) {}

  static final class Entry {
    /** When the request began (the engine sets it from its start; the flight recorder's channel spans test it). */
    long wallMs = System.currentTimeMillis();
    String started = Instant.ofEpochMilli(wallMs).toString();
    /** Whether the flight recorder's capture was on when the request began (`rec off net` pauses it). */
    boolean keepAtStart = true;
    long timeMs;
    String method;
    String url;
    String httpVersion = "HTTP/1.1";
    List<String[]> reqHeaders = new ArrayList<>();
    byte[] reqBody;
    /** The SHA-256 of the whole request body (reqBody may be only its first MAX_BODY bytes: reqTruncated). */
    String reqSha;
    boolean reqTruncated;
    /** Answered before its body was sent (Expect: 100-continue and a final status): the body is not part of it. */
    boolean expectAnswered;
    int status;
    String statusText = "";
    List<String[]> respHeaders = new ArrayList<>();
    byte[] respBody = new byte[0];
    boolean gzip;
    boolean truncated;
    long connect = -1, send, wait, receive;
    String key;
    int served;
    /** The answer's protocol version when it is not the request's (null: the same). */
    String respVersion;
    /** The whole sizes of the bodies as they passed (-1: what is kept is all there was). */
    long reqSize = -1, respSize = -1;
    /** Trailers after the answer's body (HTTP/2: gRPC's status), or null. */
    List<String[]> trailers;
    /** Why the exchange has no whole answer (the connection closed, a stream was reset, a read timed out), or null. */
    String error;
    /** Still going on when the recording was written (a stream that stays open): what there was of it by then. */
    boolean unfinished;
    /** The tap's connection it went over (0: through the engine) and the server's address. */
    int conn;
    String serverIp;
    /** WebSocket messages after a 101, appended as they pass, or null. */
    List<WsMessage> ws;
    /**
     * The answer's redacted headers with the values the server gave (a login's Set-Cookie), or null: only for what
     * `--network-unmatched fetch:FILE` fetched, kept in FILE.secrets and never in the HAR. A replay sends them to the app.
     */
    List<String[]> secretResp;
    /**
     * Typed input the flight recorder left out (a password) was in this request: its body and URL hold as many `x`
     * instead, as a replay types them (`_apksimRedactedInput`). Replay matches it without its body if the body differs.
     */
    boolean redactedInput;
    /** Replaying: the order number of the first request this entry answered (0: none yet), and how each was matched. */
    int firstSeq;
    java.util.Set<String> how;

    /** A connect that failed (no request was ever sent): `CONNECT host:port` with the failure as its error. */
    boolean connectFailed() {
      return "CONNECT".equals(method) && status == 0 && error != null && error.startsWith(CONNECT_FAILED);
    }

    /** Whether replay can answer with this entry: a whole answer from a server, not an upgrade or a tunnel. */
    boolean replayable() {
      return error == null && !unfinished && status >= 200 && !"CONNECT".equals(method);
    }

    String header(List<String[]> hs, String name) {
      for (String[] h : hs) if (h[0].equalsIgnoreCase(name)) return h[1];
      return null;
    }
  }

  /** One WebSocket message: sent by the app or received, when, its opcode, its bytes (at most 1 MB) and its whole size. */
  record WsMessage(boolean sent, long wallMs, int opcode, byte[] data, long size, boolean cut) {}

  final Path path;
  final Match match;
  final List<Entry> entries = new ArrayList<>();
  /** Each host's certificate chain as the server sent it (base64 DER), for replayed TLS sessions. */
  final Map<String, List<String>> certificates = new LinkedHashMap<>();
  private final Map<String, List<Entry>> byKey = new HashMap<>();
  /**
   * Header names redacted in the recording itself (its `_apksimRedacted`, and any request header stored as REDACTED):
   * never matched, whatever this launch's --network-redact says, since their recorded values are gone.
   */
  final Set<String> recordedRedactions = new java.util.HashSet<>();

  Recording(Path path, Match match) {
    this.path = path;
    this.match = match;
  }

  // ------------------------------------------------------------------ matching

  static final String EMPTY_SHA = sha256(new byte[0]);

  /** bodySha: the SHA-256 of the request's whole body (EMPTY_SHA, or null, for none). */
  String key(String method, String url, List<String[]> headers, String bodySha) {
    return key(method, url, headers, bodySha, true, true);
  }

  /** The key without its URL's query (`query` false) or without the body (`body` false), for the looser matches. */
  private String key(String method, String url, List<String[]> headers, String bodySha, boolean query, boolean body) {
    StringBuilder k = new StringBuilder();
    if (match.fields.contains("method")) k.append(method.toUpperCase(Locale.ROOT)).append(' ');
    if (match.fields.contains("url")) k.append(query ? normalizeUrl(url, match.ignoreQuery) : withoutQuery(normalizeUrl(url, match.ignoreQuery))).append(' ');
    if (body && match.fields.contains("body")) k.append(bodySha == null ? EMPTY_SHA : bodySha).append(' ');
    if (match.fields.contains("headers")) {
      List<String> hs = new ArrayList<>();
      for (String[] h : headers) {
        String n = h[0].toLowerCase(Locale.ROOT);
        if (HOP.contains(n) || match.ignoreHeaders.contains(n) || match.redact.contains(n) || recordedRedactions.contains(n)) continue;
        String v = h[1].trim();
        // Length-prefixed, so no value can pass for a separator (a value "1, x-b:2" is not two headers).
        hs.add(n.length() + ":" + n + v.length() + ":" + v);
      }
      java.util.Collections.sort(hs);
      for (String h : hs) k.append(h).append(';');
    }
    return k.toString();
  }

  /** scheme://host[:port]/path?query with the default port dropped and the query's parameters sorted. */
  static String normalizeUrl(String url, Set<String> ignoreQuery) {
    URI u;
    try {
      u = URI.create(url);
    } catch (IllegalArgumentException e) {
      return url;  // characters a URI may not hold (an app that does not escape `|` or `{`): matched as written
    }
    if (u.getScheme() == null || u.getHost() == null) return url;
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    int port = u.getPort();
    boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
    StringBuilder b = new StringBuilder(scheme).append("://").append(u.getHost().toLowerCase(Locale.ROOT));
    if (!defaultPort) b.append(':').append(port);
    b.append(u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath());
    String q = u.getRawQuery();
    if (q != null && !q.isEmpty()) {
      // Sorted, but repeats kept: ?id=1&id=1 is not ?id=1.
      List<String> params = new ArrayList<>();
      for (String p : q.split("&")) {
        String name = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
        if (ignoreQuery.contains(URLDecoder.decode(name, StandardCharsets.UTF_8))) continue;
        params.add(p);
      }
      java.util.Collections.sort(params);
      if (!params.isEmpty()) b.append('?').append(String.join("&", params));
    }
    return b.toString();
  }

  static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String withoutQuery(String url) {
    int q = url.indexOf('?');
    return q < 0 ? url : url.substring(0, q);
  }

  /** Entries marked `_apksimRedactedInput`, by their key without the body. */
  private final Map<String, List<Entry>> byRedacted = new HashMap<>();
  /** Connects that failed while recording, by "host:port" (lower case), and the "host:port"s some entry has an answer from. */
  private final Map<String, List<Entry>> failedConnects = new HashMap<>();
  private final Set<String> answeredHosts = new java.util.HashSet<>();
  /**
   * `apksim replay --loose` (`--network-loose`): a request no entry matches is answered by one that differs from it only
   * in the values of query parameters (docs/replay.md "Values made at run time").
   */
  boolean looseQuery;

  private void index(Entry e) {
    if (e.reqSha == null) e.reqSha = e.reqBody == null ? EMPTY_SHA : sha256(e.reqBody);
    e.key = key(e.method, e.url, e.reqHeaders, e.reqSha);
    if (e.connectFailed()) {
      failedConnects.computeIfAbsent(e.url.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
      return;
    }
    if (!e.replayable()) return;
    byKey.computeIfAbsent(e.key, k -> new ArrayList<>()).add(e);
    if (e.redactedInput) byRedacted.computeIfAbsent(key(e.method, e.url, e.reqHeaders, null, true, false), k -> new ArrayList<>()).add(e);
    String hp = hostPort(e.url);
    if (hp != null) answeredHosts.add(hp);
  }

  /** "host:port" of an http(s) URL, lower case, with the scheme's port when it names none; null without a host. */
  static String hostPort(String url) {
    try {
      URI u = URI.create(url);
      if (u.getHost() == null || u.getScheme() == null) return null;
      int port = u.getPort() > 0 ? u.getPort() : u.getScheme().equalsIgnoreCase("https") ? 443 : 80;
      return u.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ what a replay asked for

  /** Requests answered or failed so far, in order; each entry keeps the number of its first. */
  private int seq;
  /** Requests no entry answered, oldest first (the first MISSED_KEPT of them; `missedCount` has them all). */
  private final List<Map<String, Object>> missed = new ArrayList<>();
  private int missedCount;
  private static final int MISSED_KEPT = 500;

  private Entry serve(Entry e, String how) {
    e.served++;
    seq++;
    if (e.firstSeq == 0) e.firstSeq = seq;
    if (e.how == null) e.how = new java.util.LinkedHashSet<>();
    e.how.add(how);
    return e;
  }

  /** The next of these to serve: in recorded order, the last repeating; null for none. */
  private static Entry next(List<Entry> list) {
    if (list == null || list.isEmpty()) return null;
    for (Entry e : list) if (e.served == 0) return e;
    return list.get(list.size() - 1);
  }

  /**
   * The answer for a request: entries with its key in recorded order, the last repeating. Then, for a request whose
   * recorded body had typed input left out, the entry marked so with its method and URL (the body is what the replay
   * could not type). Then, with `looseQuery`, the entry that differs in the fewest query values. Null if none.
   */
  synchronized Entry find(String method, String url, List<String[]> headers, String bodySha) {
    Entry e = next(byKey.get(key(method, url, headers, bodySha)));
    if (e != null) return serve(e, "exact");
    if (match.fields.contains("body")) {
      e = next(byRedacted.get(key(method, url, headers, null, true, false)));
      if (e != null) return serve(e, "redacted-input");
    }
    if (looseQuery && match.fields.contains("url")) {
      List<String> names = new ArrayList<>();
      e = loose(method, url, headers, bodySha, names);
      if (e != null) return serve(e, "loose-query: " + String.join(",", names));
    }
    return null;
  }

  /** The replayable entry that differs from the request only in query values, in the fewest; `names`: which. */
  private Entry loose(String method, String url, List<String[]> headers, String bodySha, List<String> names) {
    String want = key(method, url, headers, bodySha, false, true);
    String wantNoBody = key(method, url, headers, null, false, false);
    Entry best = null;
    List<String> bestNames = null;
    for (Entry e : entries) {
      if (!e.replayable() || e.connectFailed()) continue;
      boolean same = e.redactedInput ? key(e.method, e.url, e.reqHeaders, null, false, false).equals(wantNoBody)
          : key(e.method, e.url, e.reqHeaders, e.reqSha, false, true).equals(want);
      if (!same) continue;
      List<String> d = queryDiff(e.url, url);
      if (d == null || d.isEmpty()) continue;
      // Fewer differing values first; among those, one not served yet (recorded order), as find serves repeats.
      if (best == null || d.size() < bestNames.size() || d.size() == bestNames.size() && best.served > 0 && e.served == 0) {
        best = e;
        bestNames = d;
      }
    }
    if (best != null) names.addAll(bestNames);
    return best;
  }

  /**
   * The query parameters whose values differ between two URLs that have the same parameter names (each as often),
   * `--network-ignore-query` names left out; null when the names differ or a URL cannot be read.
   */
  List<String> queryDiff(String recorded, String now) {
    Map<String, List<String>> a = query(recorded), b = query(now);
    if (a == null || b == null || !a.keySet().equals(b.keySet())) return null;
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, List<String>> p : a.entrySet()) {
      List<String> other = b.get(p.getKey());
      if (other.size() != p.getValue().size()) return null;
      if (!other.equals(p.getValue())) out.add(p.getKey());
    }
    return out;
  }

  /** A URL's query as name -> its raw values, sorted, names decoded and sorted; null for a URL that cannot be read. */
  private Map<String, List<String>> query(String url) {
    String q;
    try {
      q = URI.create(url).getRawQuery();
    } catch (IllegalArgumentException e) {
      return null;
    }
    Map<String, List<String>> out = new java.util.TreeMap<>();
    if (q == null || q.isEmpty()) return out;
    for (String p : q.split("&")) {
      int eq = p.indexOf('=');
      String name;
      try {
        name = URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        name = eq < 0 ? p : p.substring(0, eq);
      }
      if (match.ignoreQuery.contains(name)) continue;
      out.computeIfAbsent(name, k -> new ArrayList<>()).add(eq < 0 ? "" : p.substring(eq + 1));
    }
    for (List<String> v : out.values()) java.util.Collections.sort(v);
    return out;
  }

  /** A recorded answer given before the body (Expect: 100-continue) to this request, or null. */
  synchronized Entry findExpectAnswered(String method, String url, List<String[]> headers) {
    // The entry find would serve next for an empty body (recorded order, the last repeating), but only if it was
    // answered before its body: otherwise the body is read and matched as for any request.
    Entry next = next(byKey.get(key(method, url, headers, EMPTY_SHA)));
    if (next == null || !next.expectAnswered) return null;
    return serve(next, "exact");
  }

  /**
   * A connect to host:port that failed while recording, to fail again: only for a host and port the recording has no
   * answer from at all (one that failed now and then is connected, and its requests answered). Null otherwise.
   */
  synchronized Entry connectFailure(String host, int port) {
    String hp = host.toLowerCase(Locale.ROOT) + ":" + port;
    if (answeredHosts.contains(hp)) return null;
    Entry e = next(failedConnects.get(hp));
    return e == null ? null : serve(e, "connect-failed");
  }

  /** "ERR Class message" (the engine's reply to a connect) for a recorded failure, as Net.exception reads it. */
  static String connectReply(Entry e) {
    String x = e.error.substring(CONNECT_FAILED.length());
    int colon = x.indexOf(": ");
    String cls = colon < 0 ? x : x.substring(0, colon);
    String msg = colon < 0 ? "" : x.substring(colon + 2);
    cls = cls.substring(cls.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9_$]", "");
    return "ERR " + (cls.isEmpty() ? "ConnectException" : cls) + " " + msg.replace('\r', ' ').replace('\n', ' ') + "\r\n";
  }

  /**
   * The recorded entry most like a request that matched none: what differs, and what would make it match
   * (`ignoreQuery`: it differs only in these query parameters' values; `bodyOnly`: only in its body).
   */
  record Closest(Entry entry, List<String> diffs, List<String> ignoreQuery, boolean bodyOnly) {
    String text() {
      return entry.method + " " + entry.url + (diffs.isEmpty() ? "" : " (" + String.join("; ", diffs) + ")");
    }

    /** What would make the entry match, when one option does: a line after the unmatched one ("" otherwise). */
    String hint() {
      if (ignoreQuery == null) return "";
      return "\n         --network-ignore-query " + String.join(",", ignoreQuery) + " matches it (a value made at run time; `apksim replay --loose` for all such)";
    }
  }

  /** The recorded entry most like a request that matched none, and what differs; null if nothing is close. */
  synchronized Closest closest(String method, String url, List<String[]> headers, String bodySha) {
    URI want;
    try {
      want = URI.create(url);
    } catch (IllegalArgumentException x) {
      return null;
    }
    String hb = bodySha == null ? EMPTY_SHA : bodySha;
    Entry best = null;
    int bestScore = -1;
    for (Entry e : entries) {
      URI u;
      try {
        u = URI.create(e.url);
      } catch (IllegalArgumentException x) {
        continue;
      }
      if (u.getHost() == null || want.getHost() == null) continue;
      int score = 0;
      if (e.method.equalsIgnoreCase(method)) score += 16;
      if (u.getHost().equalsIgnoreCase(want.getHost())) score += 8; else continue;
      if (String.valueOf(u.getRawPath()).equals(String.valueOf(want.getRawPath()))) {
        score += 32;
        // Among entries for the same path: one with the same parameter names, the same body, an answer to give.
        if (queryDiff(e.url, url) != null) score += 4;
        if ((e.reqSha == null ? EMPTY_SHA : e.reqSha).equals(hb)) score += 2;
        if (e.replayable()) score += 1;
      }
      if (score > bestScore) {
        bestScore = score;
        best = e;
      }
    }
    if (best == null) return null;
    List<String> diffs = new ArrayList<>();
    boolean methodSame = best.method.equalsIgnoreCase(method);
    if (!methodSame) diffs.add("method " + best.method + " recorded");
    String a = normalizeUrl(best.url, match.ignoreQuery), b = normalizeUrl(url, match.ignoreQuery);
    boolean urlSame = a.equals(b);
    List<String> names = null;
    if (!urlSame) {
      boolean samePath = withoutQuery(a).equals(withoutQuery(b));
      names = samePath ? queryDiff(best.url, url) : null;
      if (names != null && names.isEmpty()) names = null;
      diffs.add(!samePath ? "path differs" : "query differs: " + (a.indexOf('?') < 0 ? "none" : a.substring(a.indexOf('?') + 1)) + " recorded"
          + (names == null ? "" : " (only the value" + (names.size() == 1 ? "" : "s") + " of " + String.join(", ", names) + ")"));
    }
    String ha = best.reqSha == null ? EMPTY_SHA : best.reqSha;
    boolean bodySame = !match.fields.contains("body") || ha.equals(hb) || best.redactedInput;
    if (!bodySame) diffs.add("body differs: " + ha.substring(0, 8) + ".. recorded, " + hb.substring(0, 8) + ".. now");
    if (diffs.isEmpty() && match.fields.contains("headers")) diffs.add("headers differ");
    if (diffs.isEmpty() && !best.replayable()) {
      diffs.add(best.error != null ? "recorded without an answer: " + best.error : best.unfinished ? "still going on when the recording was saved"
          : "not an exchange a replay can answer (status " + best.status + ")");
    }
    boolean rest = methodSame && !match.fields.contains("headers") && best.replayable();
    return new Closest(best, diffs, rest && bodySame && names != null ? names : null, rest && urlSame && !bodySame);
  }

  /** A request nothing answered (the 599): kept for the report, with the closest entry and what would match it. */
  synchronized void missed(String method, String url, Closest c, String why) {
    seq++;
    missedCount++;
    if (missed.size() >= MISSED_KEPT) return;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("seq", seq);
    m.put("method", method);
    m.put("url", url);
    if (why != null) m.put("why", why);
    if (c != null) {
      Map<String, Object> cl = new LinkedHashMap<>();
      cl.put("entry", entries.indexOf(c.entry));
      cl.put("method", c.entry.method);
      cl.put("url", c.entry.url);
      m.put("closest", cl);
      m.put("differs", c.diffs);
      if (c.ignoreQuery != null) m.put("ignoreQuery", c.ignoreQuery);
      if (c.bodyOnly) m.put("bodyOnly", true);
    }
    missed.add(m);
  }

  /**
   * What the replay asked of this recording so far (`report.json`'s `requests`, docs/replay.md): each entry with how
   * often it was asked for and how it matched, the requests no entry answered, in order (`seq` numbers every request
   * the replay made), and the counts.
   */
  synchronized Map<String, Object> report() {
    List<Object> recorded = new ArrayList<>();
    int can = 0, askedEntries = 0;
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("entry", i);
      m.put("method", e.method);
      m.put("url", e.url);
      boolean failed = e.connectFailed();
      if (failed || e.error != null) m.put("error", e.error);
      else m.put("status", e.status);
      if (e.redactedInput) m.put("redactedInput", true);
      m.put("asked", e.served);
      if (e.served > 0) {
        m.put("first", e.firstSeq);
        m.put("matched", new ArrayList<>(e.how));
        askedEntries++;
      }
      if (failed || e.replayable()) can++;
      else m.put("replayable", false);
      recorded.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("recorded", can);
    out.put("asked", askedEntries);
    out.put("unmatched", missedCount);
    out.put("total", seq);
    if (looseQuery) out.put("loose", true);
    out.put("entries", recorded);
    out.put("unmatchedRequests", new ArrayList<>(missed));
    return out;
  }

  // ------------------------------------------------------------------ recording

  /** False while the flight recorder's capture is paused (`rec off net`): exchanges go on, unkept. */
  volatile boolean keep = true;
  /** What a recording with no file (the flight recorder's) holds at most, bodies included; the oldest go first. */
  private static final long CAPTURE_BYTES = 64L << 20;
  private long bytes;

  synchronized void add(Entry e) {
    if (!e.keepAtStart) return;  // begun while the capture was paused: what counts is the state at the request's start
    entries.add(e);
    index(e);
    if (path == null) {
      bytes += size(e);
      long from = dev.apksim.rec.Rec.keepFromWallMs();
      while (!entries.isEmpty() && (entries.get(0).wallMs < from || bytes > CAPTURE_BYTES)) {  // one too big goes too
        Entry old = entries.remove(0);
        bytes -= size(old);
        List<Entry> same = byKey.get(old.key);
        if (same != null) {
          same.remove(old);
          if (same.isEmpty()) byKey.remove(old.key);
        }
        if (old.connectFailed()) {
          List<Entry> failed = failedConnects.get(old.url.toLowerCase(Locale.ROOT));
          if (failed != null) {
            failed.remove(old);
            if (failed.isEmpty()) failedConnects.remove(old.url.toLowerCase(Locale.ROOT));
          }
        }
      }
    }
  }

  /** What an entry holds: bodies, and the URL, index key and headers, which can be large too. */
  private static long size(Entry e) {
    long n = 512 + (e.reqBody == null ? 0 : e.reqBody.length) + (e.respBody == null ? 0 : e.respBody.length);
    n += 2L * ((e.url == null ? 0 : e.url.length()) + (e.key == null ? 0 : e.key.length()));
    for (List<String[]> hs : java.util.Arrays.asList(e.reqHeaders, e.respHeaders, e.trailers)) {
      if (hs != null) for (String[] h : hs) for (String x : h) n += 16 + 2L * (x == null ? 0 : x.length());
    }
    if (e.ws != null) n += TapWebSocket.MAX_BYTES / 8;  // its messages come later: an allowance, not a count
    return n;
  }

  synchronized int size() {
    return entries.size();
  }

  /**
   * The entries whose start (epoch ms) `keep` accepts, chosen now, as a HAR file written when the result is called
   * (the flight recorder's bundle, on its save thread). Entries do not change once added.
   */
  synchronized java.util.function.Supplier<String> harLater(java.util.function.LongPredicate keep, List<Entry> unfinished,
      List<Object> connections, List<Typed> typed, int[] scrubbed) {
    List<Entry> some = new ArrayList<>();
    for (Entry e : entries) if (keep.test(e.wallMs)) some.add(e);
    for (Entry e : unfinished) if (keep.test(e.wallMs)) some.add(e);
    some.sort(java.util.Comparator.comparingLong(e -> e.wallMs));
    // Only the certificates of the hosts these exchanges went to, not every host the app has reached.
    Map<String, List<String>> certs = new LinkedHashMap<>();
    for (Entry e : some) {
      try {
        String host = URI.create(e.url).getHost();
        if (host != null && certificates.containsKey(host.toLowerCase(Locale.ROOT))) {
          certs.put(host.toLowerCase(Locale.ROOT), certificates.get(host.toLowerCase(Locale.ROOT)));
        }
      } catch (IllegalArgumentException x) {
        // no host to match
      }
    }
    return () -> Json.write(harRoot(some, certs, connections, typed, scrubbed));
  }

  /**
   * Text the flight recorder left out of its input journal (typed into a password field, pasted), and when its first
   * character was dispatched: requests begun from then on that carry it are written with as many `x` in its place,
   * which is what a replay types, and marked `_apksimRedactedInput` (docs/flight-recorder.md "What is left out").
   */
  record Typed(long fromWallMs, String text) {}

  /** Shorter left-out text is not looked for: two or three characters turn up in any body by chance. */
  static final int TYPED_MIN = 4;

  /**
   * `data` with `secret` replaced by as many `x` wherever it stands as written, form- or percent-encoded, or escaped
   * as JSON; null when it is not there. Bytes, so a body that is not text is searched too.
   */
  static byte[] scrub(byte[] data, String secret) {
    if (data == null || data.length == 0 || secret.length() < TYPED_MIN) return null;
    String form = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < secret.length(); i++) {
      char c = secret.charAt(i);
      if (c == '"' || c == '\\') json.append('\\');
      json.append(c);
    }
    Set<String> forms = new java.util.LinkedHashSet<>();
    // The longest first: an encoded form holds the plain one when nothing in it needed encoding.
    for (String f : new String[] {form.replace("+", "%20").replace("*", "%2A"), form.replace("+", "%20"), form,
        json.toString().replace("/", "\\/"), json.toString(), secret}) {  // org.json writes a slash as \/
      forms.add(new String(f.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }
    String hay = new String(data, StandardCharsets.ISO_8859_1);
    String x = "x".repeat(secret.length());
    boolean found = false;
    for (String f : forms) {
      if (!hay.contains(f)) continue;
      hay = hay.replace(f, x);
      found = true;
    }
    return found ? hay.getBytes(StandardCharsets.ISO_8859_1) : null;
  }

  /** Forgets every entry (the flight recorder's `rec clear`; only for a recording with no file). */
  synchronized void clearCaptured() {
    entries.clear();
    byKey.clear();
    byRedacted.clear();
    failedConnects.clear();
    answeredHosts.clear();
    bytes = 0;
  }

  synchronized void certificates(String host, List<String> chain) {
    if (!chain.isEmpty()) certificates.put(host.toLowerCase(Locale.ROOT), chain);
  }

  synchronized List<String> certificates(String host) {
    return certificates.get(host.toLowerCase(Locale.ROOT));
  }

  /** What replay fetched (--network-unmatched fetch:FILE): the engine's exchanges alone, nothing of the tap's. */
  volatile boolean tapless;

  /**
   * Fetched answers left out of this file (--network-unmatched fetch:FILE): a 401 or 403 the server gave after the
   * recording's own answer could not hand the app its credential, or handed it one the server no longer takes. The
   * first NOT_KEPT_LISTED are listed in the file's `_apksimNotKept`, all are counted.
   */
  private final List<Map<String, Object>> notKept = new ArrayList<>();
  private int notKeptCount;
  static final int NOT_KEPT_LISTED = 100;

  synchronized void notKept(String method, String url, int status, String reason) {
    notKeptCount++;
    if (notKept.size() >= NOT_KEPT_LISTED) return;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("method", method);
    m.put("url", url);
    m.put("status", status);
    m.put("reason", reason);
    notKept.add(m);
  }

  /** FILE.secrets beside a recording: see {@link Entry#secretResp}. */
  static Path secretsPath(Path har) {
    return har.resolveSibling(har.getFileName() + ".secrets");
  }

  /** What names an entry in FILE.secrets: fields a HAR shows as they are, so a script merging recordings can carry them over. */
  private static String secretKey(String method, String url, String started) {
    return method + " " + url + " " + started;
  }

  private boolean secretsFailed;

  /**
   * Writes FILE.secrets, readable by its owner alone, when an entry has redacted answer headers to keep. When none has,
   * a file left by an earlier launch goes: FILE is this launch's now, and that one's sessions are not to linger beside it.
   */
  private void saveSecrets(List<Entry> all) throws IOException {
    List<Object> list = new ArrayList<>();
    for (Entry e : all) {
      if (e.secretResp == null || e.secretResp.isEmpty()) continue;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("method", e.method);
      m.put("url", e.url);
      m.put("startedDateTime", e.started);
      m.put("responseHeaders", headers(e.secretResp));
      list.add(m);
    }
    if (list.isEmpty()) {
      Files.deleteIfExists(secretsPath(path));
      return;
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("comment", "apksim: the values of the headers redacted in " + path.getFileName()
        + ", so a replay can sign the app in again. Private: do not share or commit this file.");
    root.put("entries", list);
    Path file = secretsPath(path);
    Path tmp = file.resolveSibling(file.getFileName() + ".apksim-tmp");
    Files.deleteIfExists(tmp);
    try {
      Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException noPosix) {
      Files.createFile(tmp);
    }
    Files.writeString(tmp, Json.write(root));
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Gives the loaded entries the header values FILE.secrets has for them. A file that cannot be read is as none. */
  @SuppressWarnings("unchecked")
  private static void loadSecrets(Path har, List<Entry> loaded) {
    Path file = secretsPath(har);
    if (!Files.isRegularFile(file)) return;
    try {
      Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(file));
      Map<String, List<String[]>> by = new HashMap<>();
      for (Object o : (List<Object>) root.get("entries")) {
        Map<String, Object> m = (Map<String, Object>) o;
        by.put(secretKey(String.valueOf(m.get("method")), String.valueOf(m.get("url")), String.valueOf(m.get("startedDateTime"))),
            readHeaders(m.get("responseHeaders")));
      }
      for (Entry e : loaded) {
        List<String[]> hs = by.get(secretKey(e.method, e.url, e.started));
        if (hs != null && !hs.isEmpty()) e.secretResp = hs;
      }
    } catch (IOException | RuntimeException bad) {
      Net.log("network replay: cannot read " + file + " (" + bad + "): answers are replayed without their redacted headers");
    }
  }

  void save() throws IOException {
    // Asked of the tap's thread before this recording is locked: that thread adds entries under the same lock.
    List<Entry> more = tapless ? List.of() : Tap.unfinished();
    List<Object> connections = tapless ? null : Tap.connections(w -> true);
    String json;
    synchronized (this) {
      List<Entry> all = new ArrayList<>(entries);
      all.addAll(more);
      all.sort(java.util.Comparator.comparingLong(e -> e.wallMs));
      json = Json.write(harRoot(all, certificates, connections, null, null));
      // Before the HAR: a reader that finds an entry finds its secrets.
      if (tapless) {
        try {
          saveSecrets(all);
        } catch (IOException e) {
          // The recording is still worth writing: replayed without the file, its sign-in sets no cookie (and says so).
          if (!secretsFailed) Net.log("network fetch: cannot write " + secretsPath(path) + ": " + e);
          secretsFailed = true;
        }
      }
    }
    // A fixed name beside the file: the launcher's sandbox allows writing exactly these two paths (one recording
    // process per file).
    Path tmp = path.resolveSibling(path.getFileName() + ".apksim-tmp");
    Files.writeString(tmp, json);
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private Map<String, Object> harRoot(List<Entry> some, Map<String, List<String>> certificates, List<Object> connections,
      List<Typed> typed, int[] scrubbed) {
    List<Object> list = new ArrayList<>();
    for (Entry e : some) {
      Map<String, Object> m = toHar(e, typed);
      if (scrubbed != null && Boolean.TRUE.equals(m.get("_apksimRedactedInput"))) scrubbed[0]++;
      list.add(m);
    }
    Map<String, Object> log = new LinkedHashMap<>();
    log.put("version", "1.2");
    log.put("creator", Map.of("name", "apksim", "version", "1"));
    log.put("entries", list);
    if (!certificates.isEmpty()) log.put("_apksimCertificates", certificates);
    if (connections != null && !connections.isEmpty()) log.put("_apksimConnections", connections);
    log.put("_apksimRedacted", new ArrayList<>(new java.util.TreeSet<>(match.redact)));
    if (notKeptCount > 0) {
      Map<String, Object> nk = new LinkedHashMap<>();
      nk.put("count", notKeptCount);
      nk.put("requests", new ArrayList<>(notKept));
      log.put("_apksimNotKept", nk);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("log", log);
    return root;
  }

  private static List<Object> headers(List<String[]> hs) {
    List<Object> out = new ArrayList<>();
    for (String[] h : hs) out.add(nv(h[0], h[1]));
    return out;
  }

  private static Map<String, Object> nv(String name, String value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("value", value);
    return m;
  }

  private Map<String, Object> toHar(Entry e0, List<Typed> typed) {
    Entry e = e0;
    if (typed != null && !e0.connectFailed()) {
      // Left-out input this request carries: a copy of the entry with placeholders (the entry itself never changes).
      String url = e0.url;
      byte[] body = e0.reqTruncated ? null : e0.reqBody;
      boolean changed = false;
      for (Typed t : typed) {
        if (e0.wallMs < t.fromWallMs()) continue;
        byte[] u = scrub(url.getBytes(StandardCharsets.UTF_8), t.text());
        if (u != null) {
          url = new String(u, StandardCharsets.UTF_8);
          changed = true;
        }
        byte[] b = scrub(body, t.text());
        if (b != null) {
          body = b;
          changed = true;
        }
      }
      if (changed) {
        e = new Entry();
        e.wallMs = e0.wallMs;
        e.started = e0.started;
        e.timeMs = e0.timeMs;
        e.method = e0.method;
        e.url = url;
        e.httpVersion = e0.httpVersion;
        e.reqHeaders = new ArrayList<>();
        for (String[] h : e0.reqHeaders) {
          boolean length = h[0].equalsIgnoreCase("Content-Length") && body != null && e0.reqBody != null && body.length != e0.reqBody.length;
          e.reqHeaders.add(length ? new String[] {h[0], String.valueOf(body.length)} : h);
        }
        e.reqBody = e0.reqTruncated ? e0.reqBody : body;
        e.reqSha = e0.reqTruncated || body == null ? e0.reqSha : sha256(body);
        e.reqTruncated = e0.reqTruncated;
        e.expectAnswered = e0.expectAnswered;
        e.status = e0.status;
        e.statusText = e0.statusText;
        e.respHeaders = e0.respHeaders;
        e.respBody = e0.respBody;
        e.gzip = e0.gzip;
        e.truncated = e0.truncated;
        e.connect = e0.connect;
        e.send = e0.send;
        e.wait = e0.wait;
        e.receive = e0.receive;
        e.respVersion = e0.respVersion;
        e.reqSize = e0.reqSize >= 0 && body != null && e0.reqBody != null ? e0.reqSize + body.length - e0.reqBody.length : e0.reqSize;
        e.respSize = e0.respSize;
        e.trailers = e0.trailers;
        e.error = e0.error;
        e.unfinished = e0.unfinished;
        e.conn = e0.conn;
        e.serverIp = e0.serverIp;
        e.ws = e0.ws;
        e.redactedInput = true;
      }
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("method", e.method);
    req.put("url", e.url);
    req.put("httpVersion", e.httpVersion);
    req.put("cookies", List.of());
    req.put("headers", headers(e.reqHeaders));
    List<Object> qs = new ArrayList<>();
    int mark = e.url.indexOf('?');
    int frag = e.url.indexOf('#', Math.max(mark, 0));
    String q = mark < 0 ? null : e.url.substring(mark + 1, frag < 0 ? e.url.length() : frag);
    if (q != null && !q.isEmpty()) {
      for (String p : q.split("&")) {
        int eq = p.indexOf('=');
        try {
          qs.add(nv(URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8),
              eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException bad) {
          qs.add(nv(eq < 0 ? p : p.substring(0, eq), eq < 0 ? "" : p.substring(eq + 1)));  // a % that escapes nothing
        }
      }
    }
    req.put("queryString", qs);
    if (e.reqBody != null) {
      Map<String, Object> post = new LinkedHashMap<>();
      String mime = e.header(e.reqHeaders, "Content-Type");
      post.put("mimeType", mime == null ? "" : mime);
      String text = e.reqTruncated ? null : text(e.reqBody);
      if (e.reqTruncated) {
        post.put("text", "");  // over MAX_BODY: only its hash (below) is kept, and matched
        post.put("_apksimTruncated", true);
      } else if (text != null) {
        post.put("text", text);
      } else {
        post.put("text", Base64.getEncoder().encodeToString(e.reqBody));
        post.put("_encoding", "base64");
      }
      req.put("postData", post);
      req.put("_bodySha256", e.reqSha != null ? e.reqSha : sha256(e.reqBody));
    }
    if (e.expectAnswered) req.put("_apksimExpectAnswered", true);
    req.put("headersSize", -1);
    req.put("bodySize", e.reqSize >= 0 ? e.reqSize : e.reqBody == null ? 0 : e.reqBody.length);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("status", e.status);
    resp.put("statusText", e.statusText);
    resp.put("httpVersion", e.respVersion != null ? e.respVersion : e.httpVersion);
    resp.put("cookies", List.of());
    resp.put("headers", headers(e.respHeaders));
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("size", e.respBody.length);
    String mime = e.header(e.respHeaders, "Content-Type");
    content.put("mimeType", mime == null ? "" : mime);
    String text = text(e.respBody);
    if (text != null) {
      content.put("text", text);
    } else {
      content.put("text", Base64.getEncoder().encodeToString(e.respBody));
      content.put("encoding", "base64");
    }
    if (e.gzip) content.put("_apksimGzip", true);
    if (e.truncated) content.put("_apksimTruncated", true);
    resp.put("content", content);
    if (e.trailers != null && !e.trailers.isEmpty()) resp.put("_apksimTrailers", headers(e.trailers));
    String location = e.header(e.respHeaders, "Location");
    resp.put("redirectURL", location == null ? "" : location);
    resp.put("headersSize", -1);
    resp.put("bodySize", e.respSize >= 0 ? e.respSize : e.respBody.length);

    Map<String, Object> timings = new LinkedHashMap<>();
    timings.put("blocked", -1);
    timings.put("dns", -1);
    timings.put("connect", e.connect);
    timings.put("send", e.send);
    timings.put("wait", e.wait);
    timings.put("receive", e.receive);
    timings.put("ssl", -1);

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("startedDateTime", e.started);
    m.put("time", e.timeMs);
    m.put("request", req);
    m.put("response", resp);
    m.put("cache", Map.of());
    m.put("timings", timings);
    if (e.serverIp != null) m.put("serverIPAddress", e.serverIp);
    if (e.conn > 0) m.put("connection", String.valueOf(e.conn));
    if (e.error != null) m.put("_apksimError", e.error);
    if (e.redactedInput) m.put("_apksimRedactedInput", true);
    if (e.unfinished) m.put("_apksimUnfinished", true);
    if (e.ws != null) {
      List<Object> messages = new ArrayList<>();
      List<WsMessage> copy;
      synchronized (e.ws) {
        copy = new ArrayList<>(e.ws);
      }
      for (WsMessage w : copy) {
        Map<String, Object> wm = new LinkedHashMap<>();
        wm.put("type", w.sent() ? "send" : "receive");
        wm.put("time", w.wallMs() / 1000.0);
        wm.put("opcode", w.opcode());
        String t = w.opcode() == 1 ? text(w.data()) : null;
        wm.put("data", t != null ? t : Base64.getEncoder().encodeToString(w.data()));
        if (w.cut()) wm.put("_apksimSize", w.size());
        messages.add(wm);
      }
      m.put("_webSocketMessages", messages);
    }
    return m;
  }

  /** The bytes as text if they are UTF-8 with no control characters but tab, CR and LF; else null. */
  static String text(byte[] b) {
    try {
      String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c < 0x20 && c != '\t' && c != '\r' && c != '\n') return null;
      }
      return s;
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ loading

  @SuppressWarnings("unchecked")
  static Recording load(Path path, Match match) throws IOException {
    Recording r = new Recording(path, match);
    Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(path));
    Map<String, Object> log = (Map<String, Object>) root.get("log");
    Object certs = log.get("_apksimCertificates");
    if (certs instanceof Map<?, ?> cm) {
      for (Map.Entry<?, ?> c : cm.entrySet()) r.certificates.put(c.getKey().toString().toLowerCase(Locale.ROOT), (List<String>) c.getValue());
    }
    if (log.get("_apksimRedacted") instanceof List<?> names) {
      for (Object n : names) r.recordedRedactions.add(String.valueOf(n).toLowerCase(Locale.ROOT));
    }
    List<Entry> loaded = new ArrayList<>();
    for (Object o : (List<Object>) log.get("entries")) {
      Map<String, Object> m = (Map<String, Object>) o;
      Map<String, Object> req = (Map<String, Object>) m.get("request");
      Map<String, Object> resp = (Map<String, Object>) m.get("response");
      Entry e = new Entry();
      e.started = String.valueOf(m.getOrDefault("startedDateTime", e.started));
      e.timeMs = num(m.get("time"));
      e.method = (String) req.get("method");
      e.url = (String) req.get("url");
      e.reqHeaders = readHeaders(req.get("headers"));
      e.expectAnswered = Boolean.TRUE.equals(req.get("_apksimExpectAnswered"));
      Map<String, Object> post = (Map<String, Object>) req.get("postData");
      if (post != null && post.get("text") != null) {
        String t = (String) post.get("text");
        e.reqBody = "base64".equals(post.get("_encoding")) ? Base64.getDecoder().decode(t) : t.getBytes(StandardCharsets.UTF_8);
        // A body too big to store matches by the hash recorded with it; any other, by its text (hand edits count).
        if (Boolean.TRUE.equals(post.get("_apksimTruncated")) && req.get("_bodySha256") instanceof String sha) {
          e.reqSha = sha;
          e.reqTruncated = true;
        }
      }
      e.error = m.get("_apksimError") instanceof String why ? why : null;
      e.redactedInput = Boolean.TRUE.equals(m.get("_apksimRedactedInput"));
      e.unfinished = Boolean.TRUE.equals(m.get("_apksimUnfinished"));
      e.status = (int) num(resp.get("status"));
      e.statusText = String.valueOf(resp.getOrDefault("statusText", ""));
      e.respHeaders = readHeaders(resp.get("headers"));
      Map<String, Object> content = (Map<String, Object>) resp.get("content");
      if (content != null && content.get("text") != null) {
        String t = (String) content.get("text");
        e.respBody = "base64".equals(content.get("encoding")) ? Base64.getDecoder().decode(t) : t.getBytes(StandardCharsets.UTF_8);
        e.gzip = Boolean.TRUE.equals(content.get("_apksimGzip"));
      }
      // A body that was too big to store: replaying it fails the request (NetEngine), never an empty success.
      if (content != null) e.truncated = Boolean.TRUE.equals(content.get("_apksimTruncated"));
      Map<String, Object> timings = (Map<String, Object>) m.get("timings");
      if (timings != null) e.wait = Math.max(0, num(timings.get("wait")));
      for (String[] h : e.reqHeaders) if (REDACTED.equals(h[1])) r.recordedRedactions.add(h[0].toLowerCase(Locale.ROOT));
      loaded.add(e);
    }
    loadSecrets(path, loaded);
    for (Entry e : loaded) r.add(e);  // indexed once every redacted name is known
    return r;
  }

  private static long num(Object o) {
    return o instanceof Number n ? n.longValue() : 0;
  }

  @SuppressWarnings("unchecked")
  private static List<String[]> readHeaders(Object o) {
    List<String[]> out = new ArrayList<>();
    if (o instanceof List<?> l) {
      for (Object h : l) {
        Map<String, Object> m = (Map<String, Object>) h;
        out.add(new String[] {String.valueOf(m.get("name")), String.valueOf(m.get("value"))});
      }
    }
    return out;
  }
}
