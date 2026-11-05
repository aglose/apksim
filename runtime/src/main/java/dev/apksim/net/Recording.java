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

  /** What a replayed request must match (docs/network.md "Matching"). */
  record Match(Set<String> fields, Set<String> ignoreQuery, Set<String> ignoreHeaders, Set<String> redact) {}

  static final class Entry {
    String started = Instant.now().toString();
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

    String header(List<String[]> hs, String name) {
      for (String[] h : hs) if (h[0].equalsIgnoreCase(name)) return h[1];
      return null;
    }
  }

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
    StringBuilder k = new StringBuilder();
    if (match.fields.contains("method")) k.append(method.toUpperCase(Locale.ROOT)).append(' ');
    if (match.fields.contains("url")) k.append(normalizeUrl(url, match.ignoreQuery)).append(' ');
    if (match.fields.contains("body")) k.append(bodySha == null ? EMPTY_SHA : bodySha).append(' ');
    if (match.fields.contains("headers")) {
      TreeSet<String> hs = new TreeSet<>();
      for (String[] h : headers) {
        String n = h[0].toLowerCase(Locale.ROOT);
        if (HOP.contains(n) || match.ignoreHeaders.contains(n) || match.redact.contains(n) || recordedRedactions.contains(n)) continue;
        hs.add(n + ":" + h[1].trim());
      }
      k.append(hs);
    }
    return k.toString();
  }

  /** scheme://host[:port]/path?query with the default port dropped and the query's parameters sorted. */
  static String normalizeUrl(String url, Set<String> ignoreQuery) {
    URI u = URI.create(url);
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

  private void index(Entry e) {
    if (e.reqSha == null) e.reqSha = e.reqBody == null ? EMPTY_SHA : sha256(e.reqBody);
    e.key = key(e.method, e.url, e.reqHeaders, e.reqSha);
    byKey.computeIfAbsent(e.key, k -> new ArrayList<>()).add(e);
  }

  /** The answer for a request: entries with its key in recorded order, the last repeating; null if none. */
  synchronized Entry find(String method, String url, List<String[]> headers, String bodySha) {
    List<Entry> list = byKey.get(key(method, url, headers, bodySha));
    if (list == null) return null;
    for (Entry e : list) {
      if (e.served == 0) {
        e.served++;
        return e;
      }
    }
    Entry last = list.get(list.size() - 1);
    last.served++;
    return last;
  }

  /** A recorded answer given before the body (Expect: 100-continue) to this request, or null. */
  synchronized Entry findExpectAnswered(String method, String url, List<String[]> headers) {
    // The entry find would serve next for an empty body (recorded order, the last repeating), but only if it was
    // answered before its body: otherwise the body is read and matched as for any request.
    List<Entry> list = byKey.get(key(method, url, headers, EMPTY_SHA));
    if (list == null) return null;
    Entry next = list.get(list.size() - 1);
    for (Entry e : list) {
      if (e.served == 0) {
        next = e;
        break;
      }
    }
    if (!next.expectAnswered) return null;
    next.served++;
    return next;
  }

  /** The recorded entry most like a request that matched none, and what differs; null if nothing is close. */
  synchronized String closest(String method, String url, List<String[]> headers, String bodySha) {
    URI want = URI.create(url);
    Entry best = null;
    int bestScore = -1;
    for (Entry e : entries) {
      URI u = URI.create(e.url);
      int score = 0;
      if (e.method.equalsIgnoreCase(method)) score += 4;
      if (u.getHost().equalsIgnoreCase(want.getHost())) score += 2; else continue;
      if (String.valueOf(u.getRawPath()).equals(String.valueOf(want.getRawPath()))) score += 8;
      if (score > bestScore) {
        bestScore = score;
        best = e;
      }
    }
    if (best == null) return null;
    List<String> diffs = new ArrayList<>();
    if (!best.method.equalsIgnoreCase(method)) diffs.add("method " + best.method + " recorded");
    String a = normalizeUrl(best.url, match.ignoreQuery), b = normalizeUrl(url, match.ignoreQuery);
    if (!a.equals(b)) diffs.add(URI.create(a).getRawPath().equals(URI.create(b).getRawPath())
        ? "query differs: " + URI.create(a).getRawQuery() + " recorded" : "path differs");
    String ha = best.reqSha == null ? EMPTY_SHA : best.reqSha, hb = bodySha == null ? EMPTY_SHA : bodySha;
    if (match.fields.contains("body") && !ha.equals(hb)) diffs.add("body differs: " + ha.substring(0, 8) + ".. recorded, " + hb.substring(0, 8) + ".. now");
    if (diffs.isEmpty() && match.fields.contains("headers")) diffs.add("headers differ");
    return best.method + " " + best.url + (diffs.isEmpty() ? "" : " (" + String.join("; ", diffs) + ")");
  }

  // ------------------------------------------------------------------ recording

  synchronized void add(Entry e) {
    entries.add(e);
    index(e);
  }

  synchronized void certificates(String host, List<String> chain) {
    if (!chain.isEmpty()) certificates.put(host.toLowerCase(Locale.ROOT), chain);
  }

  synchronized List<String> certificates(String host) {
    return certificates.get(host.toLowerCase(Locale.ROOT));
  }

  synchronized void save() throws IOException {
    List<Object> list = new ArrayList<>();
    for (Entry e : entries) list.add(toHar(e));
    Map<String, Object> log = new LinkedHashMap<>();
    log.put("version", "1.2");
    log.put("creator", Map.of("name", "apksim", "version", "1"));
    log.put("entries", list);
    if (!certificates.isEmpty()) log.put("_apksimCertificates", certificates);
    log.put("_apksimRedacted", new ArrayList<>(new java.util.TreeSet<>(match.redact)));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("log", log);
    // A fixed name beside the file: the launcher's sandbox allows writing exactly these two paths (one recording
    // process per file).
    Path tmp = path.resolveSibling(path.getFileName() + ".apksim-tmp");
    Files.writeString(tmp, Json.write(root));
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

  private Map<String, Object> toHar(Entry e) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("method", e.method);
    req.put("url", e.url);
    req.put("httpVersion", e.httpVersion);
    req.put("cookies", List.of());
    req.put("headers", headers(e.reqHeaders));
    List<Object> qs = new ArrayList<>();
    String q = URI.create(e.url).getRawQuery();
    if (q != null && !q.isEmpty()) {
      for (String p : q.split("&")) {
        int eq = p.indexOf('=');
        qs.add(nv(URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8),
            eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8)));
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
    req.put("bodySize", e.reqBody == null ? 0 : e.reqBody.length);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("status", e.status);
    resp.put("statusText", e.statusText);
    resp.put("httpVersion", "HTTP/1.1");
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
    String location = e.header(e.respHeaders, "Location");
    resp.put("redirectURL", location == null ? "" : location);
    resp.put("headersSize", -1);
    resp.put("bodySize", e.respBody.length);

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
