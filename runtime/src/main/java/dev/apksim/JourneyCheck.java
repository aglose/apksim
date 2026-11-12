package dev.apksim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks recording-to-journey (Journey, docs/replay.md "Recording to journey") on a plain JVM, with a stand-in screen:
 * which texts a journey may expect (a time, a duration, a date or a size anywhere in a text, on any of its lines, keeps
 * it out), and how taps and presses become steps: a double tap on one control is one step with its timing, taps on two
 * controls are two, a double tap that ends the journey is not closed against the screen between its taps, and a crash
 * with a finger still down keeps that press. Exits 1 on any failure. scripts/check-journey.sh runs it (CI).
 */
public final class JourneyCheck {
  private JourneyCheck() {}

  private static int failed;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    if (!ok) failed++;
  }

  /** The screen: its texts, and two buttons side by side 80 px apart (labels "A" at x < 100, "B" from 100 to 200). */
  private static final class Stage implements Journey.Screen {
    List<String> shown = new ArrayList<>(List.of("Home"));

    @Override
    public List<String> texts() {
      return new ArrayList<>(shown);
    }

    @Override
    public Map<String, Object> target(float x, float y) {
      if (y > 300) return null;  // below the buttons: no node
      return x < 100 ? Map.of("text", "A") : x < 200 ? Map.of("text", "B") : null;
    }
  }

  private static Journey journey(Stage s) throws Exception {
    return new Journey(Files.createTempFile("journey-check", ".json"), 2, Map.of("package", "check"), "check.apkrec", s);
  }

  /** A finger down at t, as the replay plays it (a point first), and up ms later. */
  private static void down(Journey j, float x, float y, long t) {
    j.point();
    j.event(new SimWindow.Event(SimWindow.DOWN, x, y, 0), t);
  }

  private static void up(Journey j, float x, float y, long t) {
    j.event(new SimWindow.Event(SimWindow.UP, x, y, 0), t);
  }

  private static void texts() {
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
      check(Journey.expectable(c[0]) == want, (want ? "expects " : "leaves out ") + c[0].replace("\n", "\\n").replace("\r", "\\r"));
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("== texts a journey may expect");
    texts();

    System.out.println("== a double tap on one control");
    Stage s = new Stage();
    Journey j = journey(s);
    down(j, 50, 100, 1000);
    up(j, 50, 100, 1060);
    s.shown.add("Clicked 1");
    down(j, 52, 101, 1160);
    up(j, 52, 101, 1210);
    s.shown.add("Clicked 2");
    j.finish();
    List<Map<String, Object>> steps = j.steps();
    check(steps.size() == 1 && List.of(List.of(0L, 60L), List.of(160L, 50L)).equals(steps.get(0).get("times")),
        "one step with its timing: " + steps);

    System.out.println("== taps on two controls, as quickly");
    s = new Stage();
    j = journey(s);
    down(j, 50, 100, 1000);
    up(j, 50, 100, 1060);
    down(j, 130, 100, 1160);
    up(j, 130, 100, 1210);
    j.finish();
    steps = j.steps();
    check(steps.size() == 2 && Map.of("text", "A").equals(steps.get(0).get("target")) && Map.of("text", "B").equals(steps.get(1).get("target"))
        && !steps.get(0).containsKey("times"), "two steps, one per control: " + steps);
    s = new Stage();
    j = journey(s);
    down(j, 400, 500, 1000);  // no node there: by position
    up(j, 400, 500, 1060);
    down(j, 450, 500, 1160);
    up(j, 450, 500, 1210);
    j.finish();
    check(j.steps().size() == 2, "taps 50 px apart with no node: two steps: " + j.steps());

    System.out.println("== a double tap that ends the journey");
    s = new Stage();
    j = journey(s);
    down(j, 50, 100, 1000);
    up(j, 50, 100, 1060);
    s.shown.add("Selected");  // the first tap shows it, the second hides it again
    down(j, 50, 100, 1160);
    up(j, 50, 100, 1210);
    s.shown.remove("Selected");
    j.finish();
    check(!j.steps().get(0).containsKey("expect"), "expects nothing from the screen between its taps: " + j.steps());

    System.out.println("== a crash with a finger still down");
    s = new Stage();
    j = journey(s);
    down(j, 50, 100, 1000);
    up(j, 50, 100, 1060);
    down(j, 130, 100, 2000);  // a long press whose onLongClick crashes
    j.crashed("the app crashed", 2600);
    steps = j.steps();
    check(steps.size() == 2 && "longpress".equals(steps.get(1).get("do")) && Long.valueOf(600).equals(steps.get(1).get("ms"))
        && Map.of("text", "B").equals(steps.get(1).get("target")), "keeps the long press, held 600 ms: " + steps);
    check(j.notes().stream().anyMatch(n -> n.contains("finger was still down")) && j.notes().stream().anyMatch(n -> n.contains("crashed")),
        "and says so: " + j.notes());
    s = new Stage();
    j = journey(s);
    down(j, 50, 100, 1000);
    up(j, 50, 100, 1060);
    down(j, 50, 100, 1160);  // a double tap's second tap, the crash before it lifts
    j.crashed("the app crashed", 1200);
    check(j.steps().size() == 1 && List.of(List.of(0L, 60L), List.of(160L, 40L)).equals(j.steps().get(0).get("times")),
        "keeps a double tap's unfinished second tap: " + j.steps());

    System.out.println(failed == 0 ? "journey check: all good" : "journey check: " + failed + " problem(s)");
    System.exit(failed == 0 ? 0 : 1);
  }
}
