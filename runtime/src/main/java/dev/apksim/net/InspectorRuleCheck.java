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
    if (failures > 0) {
      System.out.println(failures + " rule check(s) failed");
      System.exit(1);
    }
    System.out.println("rule check passed");
  }
}
