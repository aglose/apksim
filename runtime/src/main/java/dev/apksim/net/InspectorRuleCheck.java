package dev.apksim.net;

/**
 * Checks the inspector's URL patterns ({@link Inspector.Rule#test}, scripts/check-ttl.sh, CI): `*` matches anything, a pattern
 * with none matches anywhere, the parts between stars are found in order, and a pattern made to make a regex backtrack
 * (many stars, a long URL that nearly matches) answers at once. Exits 1 if any check fails.
 */
public final class InspectorRuleCheck {
  private static int failures;

  private InspectorRuleCheck() {}

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  private static boolean m(String pattern, String url) {
    return new Inspector.Rule(1, "*", pattern, 200, 0, "text/plain", new byte[0]).test(url);
  }

  private static void forwardChecks() {
    // Forward rules (issue 363): a target is an http(s) URL; the list says "forward"; a forward answers requests itself.
    java.util.function.Function<String, String> fwd = url -> Inspector.rule(("network rule add POST /items* forward 0 - "
        + java.util.Base64.getEncoder().encodeToString(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))).split(" "));
    Inspector.rule("network rule clear".split(" "));
    check(!Inspector.hasAnswerRules(), "no rules: nothing answers");
    String added = fwd.apply("https://mocks.example.test/answer?x=1");
    check(added.startsWith("{") && added.contains("\"forward\":\"https://mocks.example.test/answer?x=1\"") && added.contains("\"status\":0"), "a forward rule is added: " + added);
    check(Inspector.hasAnswerRules(), "a forward rule answers requests itself");
    for (String bad : new String[] {"ftp://mocks.example.test/x", "mocks.example.test/x", "https://user:pw@mocks.example.test/x", "https://mocks.example.test/x#frag",
        "https:///x", "file:///etc/passwd", "https://mocks.example.test/a b"}) {
      check(fwd.apply(bad).startsWith("error"), "refused as a forward target: " + bad);
    }
    check(Inspector.rule("network rule add GET /x forward 0 text/plain aHR0cDovL3gv".split(" ")).startsWith("error"), "a forward rule takes no content type");
    check(Inspector.rule("network rule add GET /x forward 0 - -".split(" ")).startsWith("error"), "a forward rule needs a target");
    // In a set: with status rules, in order; a bad target refuses the whole set.
    String u64 = java.util.Base64.getEncoder().encodeToString("http://127.0.0.1:8099/mock".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String set = Inspector.rule(("network rule set 2 GET /a 200 0 text/plain - - POST /b forward 5 - " + u64 + " -").split(" "));
    check(set.startsWith("[") && set.contains("\"forward\":\"http://127.0.0.1:8099/mock\"") && set.indexOf("/a") < set.indexOf("/b"), "a set with a forward rule: " + set);
    String badSet = Inspector.rule(("network rule set 1 POST /b forward 5 - " + java.util.Base64.getEncoder().encodeToString("ftp://x/".getBytes()) + " -").split(" "));
    check(badSet.startsWith("error: rule 1") && Inspector.rule("network rule list".split(" ")).contains("/a"), "a set with a bad forward target changes nothing: " + badSet);
    check(Inspector.rule(("network rule set 1 POST /b forward 5 - " + u64 + " WC1BOiAx").split(" ")).startsWith("error: rule 1"), "a forward rule in a set takes no headers");
    Inspector.rule("network rule clear".split(" "));
    // Which targets a server lets a tab name (serve --inspect-forward-allow).
    dev.apksim.serve.ForwardAllow none = new dev.apksim.serve.ForwardAllow();
    check(!none.allows("https://mocks.example.test/api"), "no prefix: no target is allowed");
    dev.apksim.serve.ForwardAllow allow = new dev.apksim.serve.ForwardAllow();
    allow.add("https://mocks.example.test/api");
    allow.add("http://127.0.0.1:8099");
    for (String ok : new String[] {"https://mocks.example.test/api", "https://MOCKS.example.test:443/api/answer?x=1", "https://mocks.example.test/api/", "http://127.0.0.1:8099",
        "http://127.0.0.1:8099/anything/at/all?q=//x"}) {
      check(allow.allows(ok), "allowed: " + ok);
    }
    for (String no : new String[] {"https://mocks.example.test/apix", "https://mocks.example.test/", "http://mocks.example.test/api", "https://mocks.example.test:8443/api",
        "https://mocks.example.test.evil.example/api", "https://mocks.example.test@evil.example/api", "https://evil.example/?https://mocks.example.test/api",
        "https://mocks.example.test/api/../admin", "https://mocks.example.test/api/%2e%2e/admin", "https://mocks.example.test/api/..%2fadmin", "https://mocks.example.test//api", "https://mocks.example.test/api/..;/admin", "https://mocks.example.test/api/x;v=1",
        "https://mocks.example.test/api\\x", "http://127.0.0.1:80990/", "http://127.0.0.1/", "http://127.0.0.1:8099#x", "", "http://[::1]:8099/", "HTTPS://mocks.example.test/API"}) {
      check(!allow.allows(no), "not allowed: " + no);
    }
    for (String bad : new String[] {"mocks.example.test", "ftp://mocks.example.test/", "https://u@mocks.example.test/", "https://mocks.example.test/?q", "https://mocks.example.test/a/../b"}) {
      boolean threw = false;
      try {
        new dev.apksim.serve.ForwardAllow().add(bad);
      } catch (IllegalArgumentException ex) {
        threw = true;
      }
      check(threw, "not a prefix: " + bad);
    }
  }

  private static NetEngine.Response resp(int status, String... headers) {
    NetEngine.Response r = new NetEngine.Response();
    r.status = status;
    r.text = "OK";
    for (int i = 0; i < headers.length; i += 2) r.headers.add(new String[] {headers[i], headers[i + 1]});
    return r;
  }

  private static byte[] gz(String s) throws java.io.IOException {
    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
    try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(o)) {
      g.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return o.toByteArray();
  }

  private static String run(NetEngine.Response r, byte[] wire, Object patch, ResponsePatch.Outcome[] out) throws java.io.IOException {
    ResponsePatch.Outcome o = ResponsePatch.apply("GET", r, new java.io.ByteArrayInputStream(wire), patch, 7);
    out[0] = o;
    return new String(o.src.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Patch rules (issue 373): RFC 7386 merging, numbers kept as written, what is patched and what passes through. */
  private static void patchChecks() throws java.io.IOException {
    Object patch = JsonMerge.parse("{\"banner\":{\"title\":\"New\",\"cta\":{\"x\":1}},\"old\":null,\"list\":[1]}");
    ResponsePatch.Outcome[] o = new ResponsePatch.Outcome[1];
    String json = "{\"id\":9007199254740993,\"old\":1,\"banner\":{\"title\":\"A\",\"keep\":true},\"f\":1.50e2,\"list\":[1,2,3]}";
    byte[] wire = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String body = run(resp(200, "Content-Type", "application/json; charset=utf-8", "Content-Length", String.valueOf(wire.length)), wire, patch, o);
    check(o[0].patched, "a 2xx JSON answer is patched");
    check(body.equals("{\"id\":9007199254740993,\"banner\":{\"title\":\"New\",\"keep\":true,\"cta\":{\"x\":1}},\"f\":1.50e2,\"list\":[1]}"),
        "a key added, one removed with null, nested objects merged, arrays replaced, numbers as written: " + body);
    String head = new String(o[0].resp.head, java.nio.charset.StandardCharsets.ISO_8859_1);
    check(o[0].resp.header("Content-Length").equals(String.valueOf(body.length())) && head.contains("Content-Length: " + body.length() + "\r\n"), "a Content-Length that fits the patched body");
    // gzip: decoded, patched, sent unencoded.
    byte[] z = gz(json);
    body = run(resp(200, "Content-Type", "application/json", "Content-Encoding", "gzip", "Content-Length", String.valueOf(z.length)), z, patch, o);
    check(o[0].patched && body.contains("\"title\":\"New\"") && o[0].resp.header("Content-Encoding") == null, "a gzip answer is decoded, patched and sent unencoded");
    // chunked: framing removed, a Content-Length put in its place.
    String chunked = Integer.toHexString(10) + "\r\n" + json.substring(0, 10) + "\r\n" + Integer.toHexString(json.length() - 10) + "\r\n" + json.substring(10) + "\r\n0\r\n\r\n";
    body = run(resp(200, "Content-Type", "application/vnd.api+json", "Transfer-Encoding", "chunked"), chunked.getBytes(java.nio.charset.StandardCharsets.UTF_8), patch, o);
    check(o[0].patched && o[0].resp.header("Transfer-Encoding") == null && o[0].resp.header("Content-Length") != null && body.contains("\"title\":\"New\""),
        "a chunked answer is patched and gets a Content-Length");
    // passed through unchanged
    byte[] text = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    body = run(resp(200, "Content-Type", "text/plain", "Content-Length", "5"), text, patch, o);
    check(!o[0].patched && body.equals("hello") && o[0].note.contains("not JSON"), "a non-JSON answer passes through: " + o[0].note);
    body = run(resp(500, "Content-Type", "application/json", "Content-Length", String.valueOf(wire.length)), wire, patch, o);
    check(!o[0].patched && body.equals(json) && o[0].note.contains("not 2xx"), "an error answer passes through: " + o[0].note);
    body = run(resp(200, "Content-Type", "application/json", "Content-Length", "5"), text, patch, o);
    check(!o[0].patched && body.equals("hello") && o[0].note.contains("not JSON"), "a JSON type with a body that is not: " + o[0].note);
    body = run(resp(200, "Content-Type", "application/json", "Content-Encoding", "br", "Content-Length", "5"), text, patch, o);
    check(!o[0].patched && body.equals("hello") && o[0].note.contains("br"), "a brotli answer passes through: " + o[0].note);
    String big = "{\"a\":\"" + "x".repeat(ResponsePatch.LIMIT) + "\"}";
    byte[] bigWire = big.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    body = run(resp(200, "Content-Type", "application/json", "Content-Length", String.valueOf(bigWire.length)), bigWire, patch, o);
    check(!o[0].patched && body.length() == big.length() && o[0].note.contains("over"), "a body over the limit passes through: " + o[0].note);
    String bigChunked = Integer.toHexString(5) + "\r\nhello\r\n" + Integer.toHexString(ResponsePatch.LIMIT) + "\r\n" + "x".repeat(ResponsePatch.LIMIT) + "\r\n0\r\n\r\n";
    ResponsePatch.Outcome oc = ResponsePatch.apply("GET", resp(200, "Content-Type", "application/json", "Transfer-Encoding", "chunked"),
        new java.io.ByteArrayInputStream(bigChunked.getBytes(java.nio.charset.StandardCharsets.UTF_8)), patch, 7);
    java.io.ByteArrayOutputStream relayed = new java.io.ByteArrayOutputStream();
    NetEngine.copyChunked(oc.src, relayed, new NetEngine.Capture(1 << 20));
    check(!oc.patched && relayed.toString(java.nio.charset.StandardCharsets.ISO_8859_1).equals(bigChunked), "a chunked body over the limit is relayed chunk for chunk");
    // RFC 7386 appendix A: a few of its cases.
    String[][] rfc = {{"{\"a\":\"b\"}", "{\"a\":\"c\"}", "{\"a\":\"c\"}"}, {"{\"a\":\"b\"}", "{\"b\":\"c\"}", "{\"a\":\"b\",\"b\":\"c\"}"},
        {"{\"a\":\"b\"}", "{\"a\":null}", "{}"}, {"{\"a\":[\"b\"]}", "{\"a\":\"c\"}", "{\"a\":\"c\"}"}, {"{\"a\":\"c\"}", "{\"a\":[\"b\"]}", "{\"a\":[\"b\"]}"},
        {"{\"a\":{\"b\":\"c\"}}", "{\"a\":{\"b\":\"d\",\"c\":null}}", "{\"a\":{\"b\":\"d\"}}"}, {"[\"a\",\"b\"]", "[\"c\",\"d\"]", "[\"c\",\"d\"]"},
        {"{\"a\":\"foo\"}", "null", "null"}, {"{\"e\":null}", "{\"a\":1}", "{\"e\":null,\"a\":1}"}, {"[1,2]", "{\"a\":\"b\",\"c\":null}", "{\"a\":\"b\"}"},
        {"{}", "{\"a\":{\"bb\":{\"ccc\":null}}}", "{\"a\":{\"bb\":{}}}"}};
    for (String[] c : rfc) check(JsonMerge.write(JsonMerge.merge(JsonMerge.parse(c[0]), JsonMerge.parse(c[1]))).equals(c[2]), "RFC 7386: " + c[0] + " + " + c[1]);
    for (String bad : new String[] {"", "{", "{\"a\":}", "[1,]", "01", "{\"a\":1} x", "\"\\q\""}) {
      boolean threw = false;
      try {
        JsonMerge.parse(bad);
      } catch (IllegalArgumentException e) {
        threw = true;
      }
      check(threw, "not JSON: " + bad);
    }
    // The command: a patch rule is added (it does not answer by itself), listed, and a bad patch is refused.
    Inspector.rule("network rule clear".split(" "));
    String p64 = java.util.Base64.getEncoder().encodeToString("{\"banner\":{\"title\":\"x\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String added = Inspector.rule(("network rule add GET https://api.example.test/v3/schedule* patch 0 application/merge-patch+json " + p64).split(" "));
    check(added.startsWith("{") && added.contains("\"patch\":true") && added.contains("\"status\":0"), "a patch rule is added: " + added);
    check(!Inspector.hasAnswerRules(), "a patch rule does not answer by itself");
    check(Inspector.match("GET", "https://api.example.test/v3/schedule?x=1").patching, "a patch rule matches its URL");
    check(Inspector.rule(("network rule add GET /x patch 0 - " + java.util.Base64.getEncoder().encodeToString("{nope".getBytes()) ).split(" ")).startsWith("error"), "a patch that is not JSON is refused");
    check(Inspector.rule("network rule add GET /x patch 0 - -".split(" ")).startsWith("error"), "a patch rule needs a patch");
    String set = Inspector.rule(("network rule set 1 GET /s patch 5 - " + p64 + " -").split(" "));
    check(set.startsWith("[") && set.contains("\"patch\":true"), "a set with a patch rule: " + set);
    Inspector.rule("network rule clear".split(" "));
  }

  public static void main(String[] args) {
    String u = "https://api.example.com/v1/greeting?name=x";
    check(m("/v1/greeting", u), "no star: matches anywhere in the URL");
    check(!m("/v2/", u), "no star: a part that is not there");
    check(m("https://*/v1/*", u), "stars between parts");
    check(m("*greeting*", u), "stars at both ends");
    check(m("*", u), "a lone star");
    check(!m("v1*example", u), "parts in order (example comes before v1 here)");
    check(m("https*x", u) && !m("https*y", u), "a prefix and a suffix");
    check(!m("api*api*api", u), "a part cannot be used twice");
    check(m("a**b", "ab") && m("a**b", "axxb"), "adjacent stars");
    check(!m("ab*ba", "aba"), "prefix and suffix may not overlap");
    String long1 = "a".repeat(100_000);
    long t = System.nanoTime();
    boolean r = m("*a*a*a*a*a*a*a*a*a*a*a*ab", long1);
    long ms = (System.nanoTime() - t) / 1_000_000;
    check(!r && ms < 1000, "a pattern built to make a regex backtrack, on a 100 KB URL: " + ms + " ms");
    // A long URL: the row carries a cut address, the detail all of it.
    Inspector.on = true;
    Recording.Entry e = new Recording.Entry();
    e.method = "GET";
    e.url = "https://example.com/?q=" + "é".repeat(14_000);
    e.status = 200;
    Inspector.exchange(e, 0, 0);
    String d = Inspector.detail(new String[] {"network", "detail", "1"});
    check(d.contains("\\u00e9".repeat(14_000)), "detail has the whole 14,000-character URL");
    check(Inspector.eventsForCheck().get(0).length() < 12_000 && Inspector.eventsForCheck().get(0).contains("\"urlCut\":true"), "a row cuts a long URL and says so");
    // Rule bodies: 512 KB accepted, one byte more refused with the limit named.
    String big = java.util.Base64.getEncoder().encodeToString(new byte[512 * 1024]);
    String big1 = java.util.Base64.getEncoder().encodeToString(new byte[512 * 1024 + 1]);
    check(Inspector.rule(("network rule add GET /big 200 0 text/plain " + big).split(" ")).startsWith("{"), "a 512 KB rule body is accepted");
    check(Inspector.rule(("network rule add GET /big 200 0 text/plain " + big1).split(" ")).contains("at most 524288 bytes"), "512 KB + 1 is refused, naming the limit");
    Inspector.rule("network rule clear".split(" "));
    // `rule set`: exactly the rules given, in their order; a bad one changes nothing; the limit holds for set and add.
    String hs = java.util.Base64.getEncoder().encodeToString("ETag: \"v1\"\nRetry-After: 3\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    String set = Inspector.rule(("network rule set 3 GET /a 200 0 text/plain - - POST /b 201 5 - - " + hs + " * /c 0 10 - - -").split(" "));
    check(set.indexOf("\"/a\"") > 0 && set.indexOf("\"/a\"") < set.indexOf("\"/b\"") && set.indexOf("\"/b\"") < set.indexOf("\"/c\""), "set: three rules in the order given");
    check(set.contains("\"headers\":[[\"ETag\",\"\\\"v1\\\"\"],[\"Retry-After\",\"3\"]]"), "set: a rule's headers are listed");
    check(set.equals(Inspector.rule("network rule list".split(" "))), "set answers the new list");
    Inspector.Rule hit = Inspector.match("POST", "https://h/b");
    check(hit != null && hit.headers.size() == 2 && hit.headers.get(1)[0].equals("Retry-After") && hit.headers.get(1)[1].equals("3"), "the matched rule carries its headers");
    String bad = Inspector.rule("network rule set 2 GET /x 200 0 - - - GET /y 999 0 - - -".split(" "));
    check(bad.startsWith("error: rule 2:") && Inspector.rule("network rule list".split(" ")).contains("\"/c\""), "set with a bad rule is refused whole (" + bad + ")");
    for (String name : new String[] {"Content-Length", "transfer-encoding", "Content-Type", "Connection", "Proxy-Authenticate", "X-Apksim-Rule", "Bad Name"}) {
      String h = java.util.Base64.getEncoder().encodeToString((name + ": 1").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
      check(Inspector.rule(("network rule add GET /h 200 0 - - " + h).split(" ")).startsWith("error"), "a rule may not set the header " + name);
    }
    String crlf = java.util.Base64.getEncoder().encodeToString("X-A: 1\rX-B: 2".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    check(Inspector.rule(("network rule add GET /h 200 0 - - " + crlf).split(" ")).startsWith("error"), "a header value with a carriage return is refused");
    String many = java.util.Base64.getEncoder().encodeToString("X-A: 1\n".repeat(21).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    check(Inspector.rule(("network rule add GET /h 200 0 - - " + many).split(" ")).contains("at most 20 headers"), "21 headers are refused");
    check(Inspector.rule("network rule set 0".split(" ")).equals("[]"), "set with no rules clears them");
    // Bodies together: 32 rules of 512 KB fit (as before the limit could be raised), a 33rd of that size does not.
    Inspector.maxRules = 40;
    for (int i = 0; i < 32; i++) Inspector.rule(("network rule add GET /big" + i + " 200 0 text/plain " + big).split(" "));
    check(Inspector.rule("network rule list".split(" ")).split("\"id\"").length == 33, "32 rules with 512 KB bodies are taken");
    check(Inspector.rule(("network rule add GET /big32 200 0 text/plain " + big).split(" ")).contains("bytes together"), "a 33rd is over the bodies' total");
    Inspector.rule("network rule clear".split(" "));
    Inspector.maxRules = 40;
    StringBuilder forty = new StringBuilder("network rule set 40");
    for (int i = 0; i < 40; i++) forty.append(" GET /r").append(i).append(" 200 0 - - -");
    check(Inspector.rule(forty.toString().split(" ")).startsWith("[{") && Inspector.rule("network rule max".split(" ")).equals("40"), "a limit of 40 takes a set of 40");
    check(Inspector.rule("network rule add GET /more 200 0 - -".split(" ")).equals("error: at most 40 rules"), "and refuses a 41st add");
    check(Inspector.rule((forty + " GET /more 200 0 - - -").replace("set 40", "set 41").split(" ")).equals("error: at most 40 rules"), "and a set of 41");
    // A request racing a swap is matched against the old set or the new one: sets A (a-first, a-second) and B (b-first, b-second)
    // alternate; whichever rule matches first, the snapshot it came from holds its partner and not the other set's.
    String[] setA = "network rule set 2 GET /race-a1 200 0 - - - GET /shared 200 0 text/a - -".split(" ");
    String[] setB = "network rule set 2 GET /race-b1 200 0 - - - GET /shared 200 0 text/b - -".split(" ");
    Inspector.rule(setA);
    java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger mixed = new java.util.concurrent.atomic.AtomicInteger(), none = new java.util.concurrent.atomic.AtomicInteger();
    Thread[] readers = new Thread[4];
    for (int i = 0; i < readers.length; i++) {
      readers[i] = new Thread(() -> {
        while (!stop.get()) {
          // One URL that only a whole set answers: both sets have exactly one rule for it, so no match means a request saw
          // the list between a clear and an add.
          Inspector.Rule got = Inspector.match("GET", "https://h/shared");
          if (got == null) none.incrementAndGet();
          else if (!got.type.equals("text/a") && !got.type.equals("text/b")) mixed.incrementAndGet();
        }
      });
      readers[i].start();
    }
    for (int i = 0; i < 20_000; i++) Inspector.rule(i % 2 == 0 ? setB : setA);
    stop.set(true);
    for (Thread th : readers) {
      try {
        th.join();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
    check(none.get() == 0 && mixed.get() == 0, "20,000 swaps under 4 matching threads: every request met a whole set (" + none.get() + " met none)");
    Inspector.rule("network rule clear".split(" "));
    Inspector.maxRules = Inspector.MAX_RULES;
    // The detail history stays under its byte cap while the newest exchange is kept.
    for (int i = 0; i < 200; i++) {
      Recording.Entry b = new Recording.Entry();
      b.method = "GET";
      b.url = "https://example.com/" + i;
      b.status = 200;
      b.respBody = new byte[300 * 1024];
      Inspector.exchange(b, 0, 0);
    }
    check(Inspector.detailBytesForCheck() <= Inspector.DETAILS_BYTES, "detail bodies stay under the byte cap");
    check(!Inspector.detail(new String[] {"network", "detail", "201"}).startsWith("error"), "the newest exchange keeps its detail");
    forwardChecks();
    try {
      patchChecks();
    } catch (Exception ex) {
      check(false, "patch checks threw " + ex);
    }
    if (failures > 0) {
      System.out.println(failures + " rule check(s) failed");
      System.exit(1);
    }
    System.out.println("rule check passed");
  }
}
