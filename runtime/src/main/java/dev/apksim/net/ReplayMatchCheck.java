package dev.apksim.net;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks what a replay does with requests that cannot match their recorded ones byte for byte (docs/replay.md "A
 * session with a sign-in"), on a plain JVM, with a recording written and loaded as a flight recorder's HAR is:
 * left-out input (a typed password) is taken out of the requests that carried it, as written, form-encoded and
 * JSON-escaped, and such an entry answers a request whose body differs; a request that differs from an entry only in
 * query values is unmatched with the option that matches it named, and answered with the loose match on; a failed
 * connect is an entry, fails again in a replay, and not for a host the recording has answers from; and the report
 * counts every entry and every unmatched request. Run by scripts/check-replay-match.sh; exits 1 on any failure.
 */
public final class ReplayMatchCheck {
  private static int failures;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  private static Recording.Entry entry(long wall, String method, String url, String body, int status) {
    Recording.Entry e = new Recording.Entry();
    e.wallMs = wall;
    e.method = method;
    e.url = url;
    e.reqHeaders = new ArrayList<>();
    e.reqHeaders.add(new String[] {"Content-Length", body == null ? "0" : String.valueOf(body.length())});
    e.reqBody = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
    e.status = status;
    e.respBody = "an answer".getBytes(StandardCharsets.UTF_8);  // answers are kept as they came: only requests are searched
    return e;
  }

  private static String sha(String body) {
    return Recording.sha256(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String text(byte[] b) {
    return b == null ? null : new String(b, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Recording.Match match = new Recording.Match(Set.of("method", "url", "body"), Set.of("t"), Set.of(), Set.of());

    // ---- scrub: every form the text can take in a request
    String secret = "p@ss \"w\" 1+1";
    String x = "x".repeat(secret.length());
    check(("{\"password\":\"" + x + "\"}").equals(text(Recording.scrub("{\"password\":\"p@ss \\\"w\\\" 1+1\"}".getBytes(StandardCharsets.UTF_8), secret))),
        "scrub: JSON-escaped");
    check(("user=ada&password=" + x).equals(text(Recording.scrub("user=ada&password=p%40ss+%22w%22+1%2B1".getBytes(StandardCharsets.UTF_8), secret))),
        "scrub: form-encoded (+ for a space)");
    check(("/login?p=" + x + "&u=1").equals(text(Recording.scrub("/login?p=p%40ss%20%22w%22%201%2B1&u=1".getBytes(StandardCharsets.UTF_8), secret))),
        "scrub: percent-encoded (%20 for a space)");
    check((x + " and " + x).equals(text(Recording.scrub((secret + " and " + secret).getBytes(StandardCharsets.UTF_8), secret))), "scrub: as written, each time");
    check(Recording.scrub("nothing here".getBytes(StandardCharsets.UTF_8), secret) == null, "scrub: null when the text is not there");
    check(Recording.scrub("abc abc".getBytes(StandardCharsets.UTF_8), "abc") == null, "scrub: text under " + Recording.TYPED_MIN + " characters is not looked for");
    byte[] binary = {0, (byte) 0xff, 'h', 'u', 'n', 't', 'e', 'r', (byte) 0xfe};
    byte[] cleaned = Recording.scrub(binary, "hunter");
    check(cleaned != null && cleaned.length == binary.length && cleaned[1] == (byte) 0xff && cleaned[2] == 'x' && cleaned[8] == (byte) 0xfe,
        "scrub: a body that is not text keeps its other bytes");

    // ---- a recording as the flight recorder saves it
    Recording rec = new Recording(null, match);
    rec.add(entry(1000, "POST", "https://api.example.test/v1/early", "{\"note\":\"hunter2!\"}", 200));  // before it was typed
    rec.add(entry(2000, "POST", "https://api.example.test/v1/signin", "{\"user\":\"ada\",\"password\":\"hunter2!\"}", 200));
    rec.add(entry(2100, "GET", "https://api.example.test/v1/profile?device=abc123&lang=en&t=1", null, 200));
    rec.add(entry(2200, "GET", "https://api.example.test/v1/profile?device=abc123&lang=de&t=2", null, 200));
    rec.add(entry(2300, "GET", "https://api.example.test/v1/token?hunter2!", null, 200));
    Recording.Entry down = new Recording.Entry();
    down.wallMs = 2400;
    down.method = "CONNECT";
    down.url = "down.example.test:9";
    down.error = Recording.CONNECT_FAILED + "java.net.ConnectException: Connection refused";
    rec.add(down);
    Recording.Entry flaky = new Recording.Entry();
    flaky.wallMs = 2500;
    flaky.method = "CONNECT";
    flaky.url = "api.example.test:443";
    flaky.error = Recording.CONNECT_FAILED + "java.net.SocketTimeoutException: Connect timed out";
    rec.add(flaky);
    int[] scrubbed = {0};
    String har = rec.harLater(w -> true, List.of(), null, List.of(new Recording.Typed(1500, "hunter2!")), scrubbed).get();
    Map<String, Object> root = (Map<String, Object>) Json.parse(har);
    List<Object> entries = (List<Object>) ((Map<String, Object>) root.get("log")).get("entries");
    check(scrubbed[0] == 2, "the HAR marks the two requests that carried the text (" + scrubbed[0] + ")");
    check(har.indexOf("hunter2!") == har.lastIndexOf("hunter2!") && har.indexOf("hunter2!") > 0 && har.indexOf("hunter2!") < har.indexOf("/v1/signin"),
        "the text is left only in the request from before it was typed");
    Map<String, Object> signin = (Map<String, Object>) entries.get(1);
    Map<String, Object> req = (Map<String, Object>) signin.get("request");
    check(Boolean.TRUE.equals(signin.get("_apksimRedactedInput"))
        && "{\"user\":\"ada\",\"password\":\"xxxxxxxx\"}".equals(((Map<String, Object>) req.get("postData")).get("text")),
        "the sign-in's body holds the placeholder a replay types");
    check(sha("{\"user\":\"ada\",\"password\":\"xxxxxxxx\"}").equals(req.get("_bodySha256")), "and its hash is the placeholder body's");
    check("https://api.example.test/v1/token?xxxxxxxx".equals(((Map<String, Object>) ((Map<String, Object>) entries.get(4)).get("request")).get("url")),
        "a URL that carried the text holds the placeholder");
    check(rec.entries.get(1).reqBody.length == "{\"user\":\"ada\",\"password\":\"hunter2!\"}".length() && !rec.entries.get(1).redactedInput,
        "the entry in memory is not changed");
    Map<String, Object> failed = (Map<String, Object>) entries.get(5);
    check("CONNECT".equals(((Map<String, Object>) failed.get("request")).get("method")) && String.valueOf(failed.get("_apksimError")).startsWith("connect failed: "),
        "a failed connect is an entry with _apksimError");

    // ---- replayed
    Path file = Files.createTempFile("apksim-replay-match", ".har");
    try {
      Files.writeString(file, har);
      Recording r = Recording.load(file, match);
      List<String[]> none = List.of();
      Recording.Entry e = r.find("POST", "https://api.example.test/v1/signin", none, sha("{\"user\":\"ada\",\"password\":\"xxxxxxxx\"}"));
      check(e != null && e.how.contains("exact"), "the replay's placeholder body matches the sign-in exactly");
      e = r.find("POST", "https://api.example.test/v1/signin", none, sha("{\"user\":\"ada\",\"password\":\"sha256:0f3a\"}"));
      check(e != null && e.how.contains("redacted-input"), "a body that differs still matches an entry marked as carrying left-out input");
      check(r.find("POST", "https://api.example.test/v1/early", none, sha("{\"note\":\"other\"}")) == null, "an entry not marked so still needs its body");
      Recording.Closest c = r.closest("POST", "https://api.example.test/v1/early", none, sha("{\"note\":\"other\"}"));
      check(c != null && c.bodyOnly() && c.ignoreQuery() == null && c.text().contains("body differs"), "its closest entry differs in the body only");
      r.missed("POST", "https://api.example.test/v1/early", c, null);

      String now = "https://api.example.test/v1/profile?device=zzz999&lang=en&t=77";
      check(r.find("GET", now, none, null) == null, "a query value made at run time does not match");
      c = r.closest("GET", now, none, null);
      check(c != null && List.of("device").equals(c.ignoreQuery()) && !c.bodyOnly() && c.entry().url.contains("lang=en"),
          "the closest entry differs only in device's value: " + (c == null ? null : c.text()));
      check(c != null && c.hint().contains("--network-ignore-query device"), "and the line names --network-ignore-query device");
      r.missed("GET", now, c, null);
      c = r.closest("GET", "https://api.example.test/v1/profile?device=zzz999&extra=1", none, null);
      check(c != null && c.ignoreQuery() == null, "other parameter names are not a value made at run time");
      r.looseQuery = true;
      e = r.find("GET", now, none, null);
      check(e != null && e.url.contains("lang=en") && e.how.contains("loose-query: device"), "loose: answered by the entry that differs in device alone");
      e = r.find("GET", "https://api.example.test/v1/profile?device=zzz999&lang=de&t=78", none, null);
      check(e != null && e.url.contains("lang=de"), "loose: the fewest differing values win (lang=de)");
      check(r.find("GET", "https://api.example.test/v1/profile?device=zzz999&extra=1", none, null) == null, "loose: other parameter names still do not match");
      check(r.find("POST", "https://api.example.test/v1/profile?device=zzz999&lang=en", none, null) == null, "loose: another method does not match");

      Recording.Entry f = r.connectFailure("down.example.test", 9);
      check(f != null && "ERR ConnectException Connection refused\r\n".equals(Recording.connectReply(f)), "a recorded failed connect fails again, the same way");
      check(Net.exception(Recording.connectReply(f).substring(4).trim(), false) instanceof java.net.ConnectException, "as the exception the app got");
      check(r.connectFailure("down.example.test", 10) == null, "another port of that host is not failed");
      check(r.connectFailure("api.example.test", 443) == null, "a host with recorded answers is connected, whatever failed once");

      Map<String, Object> report = r.report();
      List<Object> es = (List<Object>) report.get("entries");
      List<Object> un = (List<Object>) report.get("unmatchedRequests");
      // early (never answered), signin, two profiles, token (not asked), down, flaky (not asked): 7 a replay can ask for
      check(Integer.valueOf(7).equals(report.get("recorded")) && Integer.valueOf(4).equals(report.get("asked")) && Integer.valueOf(2).equals(report.get("unmatched")),
          "the report counts 4 of 7 asked again, 2 unmatched: " + report.get("asked") + " of " + report.get("recorded") + ", " + report.get("unmatched"));
      Map<String, Object> s = (Map<String, Object>) es.get(1);
      check(Integer.valueOf(2).equals(s.get("asked")) && List.of("exact", "redacted-input").equals(s.get("matched")) && Integer.valueOf(1).equals(s.get("first")),
          "the sign-in entry: asked twice, how each matched, first in order");
      check(Integer.valueOf(0).equals(((Map<String, Object>) es.get(4)).get("asked")), "an entry never asked for says so");
      Map<String, Object> u = (Map<String, Object>) un.get(1);
      check(List.of("device").equals(u.get("ignoreQuery")) && Integer.valueOf(2).equals(((Map<String, Object>) u.get("closest")).get("entry")),
          "an unmatched request names its closest entry and the parameter that differs");
      check(Boolean.TRUE.equals(((Map<String, Object>) un.get(0)).get("bodyOnly")), "and one that differs in its body only says so");
    } finally {
      Files.deleteIfExists(file);
    }
    System.out.println(failures == 0 ? "replay match check: all good" : "replay match check: " + failures + " failure(s)");
    System.exit(failures == 0 ? 0 : 1);
  }
}
