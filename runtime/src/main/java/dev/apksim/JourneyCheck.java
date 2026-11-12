package dev.apksim;

/**
 * Checks which texts a journey (Journey, docs/replay.md "Recording to journey") may expect on screen, on a plain JVM:
 * a time, a duration, a date or a size anywhere in a text, on any of its lines, keeps it out, as the screen shows it
 * differently on the next run or device. Exits 1 on any failure. scripts/check-journey-texts.sh runs it (CI).
 */
public final class JourneyCheck {
  private JourneyCheck() {}

  public static void main(String[] args) {
    int failed = 0;
    String[][] cases = {
      {"Roman vacation", "yes"},
      {"FAB clicks: 2", "yes"},
      {"Line one\nline two", "yes"},
      {"Fetched in 327 ms", "no"},
      {"Fetched successfully\n327 ms", "no"},
      {"Fetched successfully\r\n327 ms", "no"},
      {"Updated\n9:41", "no"},
      {"Saved\n2026-11-11", "no"},
      {"compact\n411×891", "no"},
      {"x".repeat(81), "no"},
    };
    for (String[] c : cases) {
      boolean want = c[1].equals("yes");
      boolean got = Journey.expectable(c[0]);
      System.out.println((got == want ? "  ok    " : "  FAIL  ") + (want ? "expects " : "leaves out ") + c[0].replace("\n", "\\n").replace("\r", "\\r"));
      if (got != want) failed++;
    }
    System.out.println(failed == 0 ? "journey texts: all good" : "journey texts: " + failed + " problem(s)");
    System.exit(failed == 0 ? 0 : 1);
  }
}
