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
    if (failures > 0) {
      System.out.println(failures + " rule check(s) failed");
      System.exit(1);
    }
    System.out.println("rule check passed");
  }
}
