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
    if (failures > 0) {
      System.out.println(failures + " rule check(s) failed");
      System.exit(1);
    }
    System.out.println("rule check passed");
  }
}
