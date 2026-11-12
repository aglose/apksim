package dev.apksim;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import dev.apksim.rec.JsonLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A recording turned into a journey that targets nodes rather than coordinates (`apksim replay --journey`,
 * docs/replay.md "Recording to journey"), for `scripts/journey.py`. Built during a replay, not while the user
 * records: reading the accessibility tree builds Compose's node caches and adds a view (issue 73), which a recording
 * must not do to the app it watches.
 *
 * <p>At each point where a step starts (a first finger down, a key, Back, a command, the first of a run of
 * characters), the texts on screen are read; the step before gets as its expectations the texts that appeared since
 * it started (time-like ones left out). A tap is resolved to the node under it: the deepest one there with a text,
 * else a content description, else a resource id (when clickable or editable, as `nodes` lists it), with its index
 * among nodes with the same label when there are several. Taps that follow one another as a double tap does (each
 * within 300 ms of the last one's lift and 100 dp of the first) are one step with their timing (`times`), played as
 * recorded: as separate steps, with a settle between, they would be two single taps. Drags and long presses keep
 * coordinates (a long press on a node is resolved too); typed characters become one `type` step; keys, Back, wheel
 * steps and commands are kept as they are. Two-finger gestures and window resizes are left out (noted). A replay that
 * crashes writes the steps it got to (`crashed`).
 */
final class Journey {
  /** Texts that change by themselves: times and durations. Found anywhere in a text, on any of its lines. */
  private static final Pattern VOLATILE = Pattern.compile("\\d{1,2}:\\d{2}|\\d+(\\.\\d+)? ?(ms|s)\\b|\\d{4}-\\d{2}-\\d{2}");
  /** Texts that depend on the device: a size (a window size class's "411×891", "1280x800 px"). */
  private static final Pattern DEVICE = Pattern.compile("\\d+ ?[×x] ?\\d+");
  /** ViewConfiguration's double tap timeout (ms) and slop (dp): a tap this soon and near after one is part of it. */
  private static final int DOUBLE_TAP_MS = 300;
  private static final int DOUBLE_TAP_SLOP_DP = 100;

  private final Path file;
  private final float slopPx;
  private final List<Map<String, Object>> steps = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private final Map<String, Object> head = new LinkedHashMap<>();
  private Map<String, Object> open;  // the step the last point started
  private List<String> textsAtOpen;
  private final java.util.Set<String> everSeen = new java.util.HashSet<>();
  private Gesture gesture;
  private int lastType = -1;
  /** The texts at the last point, applied once the item after it starts a step (not a further tap of a tap step). */
  private List<String> pending;
  /** The last tap step's first finger down and the last lift in it (uptime ms), and where it was. */
  private long tapT0, tapUp;
  private float tapX, tapY;
  /** The finger now down may be a further tap of the tap step before it. */
  private boolean anotherTap;

  /** A finger's gesture while it is down. */
  private static final class Gesture {
    float x0, y0, x, y;
    long t0;
    float maxDist;
    Map<String, Object> target;
    /** The finger's recent points (uptime ms, x, y), for its speed when it lifts. */
    final java.util.ArrayDeque<float[]> recent = new java.util.ArrayDeque<>();
    /** Every point, as [ms since down, x, y]: a swipe is played back along it, with its timing. */
    final List<List<Integer>> path = new ArrayList<>();
  }

  /** The screen as a journey reads it: the accessibility tree here, a stand-in in JourneyCheck. */
  interface Screen {
    List<String> texts();

    Map<String, Object> target(float x, float y);
  }

  private static final Screen TREE = new Screen() {
    @Override
    public List<String> texts() {
      return Journey.texts();
    }

    @Override
    public Map<String, Object> target(float x, float y) {
      return Journey.target(x, y);
    }
  };

  private final Screen screen;
  /** The time of the last item (ms on the replay's clock). */
  private long lastT;

  Journey(Path file, float density, Map<String, Object> manifest, String bundle) {
    this(file, density, manifest, bundle, TREE);
  }

  Journey(Path file, float density, Map<String, Object> manifest, String bundle, Screen screen) {
    this.screen = screen;
    this.file = file;
    this.slopPx = 8 * density;  // the framework's touch slop
    head.put("format", "apksim-journey/1");
    head.put("package", manifest.get("package"));
    head.put("bundle", bundle);
    head.put("start", "checkpoint");
  }

  /** At a point: the texts now close the step before it (main thread), once the next item starts a step. */
  void point() {
    pending = screen.texts();
  }

  /** The item after the last point starts a step: the point's texts close the step before. */
  private void startStep() {
    if (pending == null) return;
    close(pending);
    textsAtOpen = pending;
    pending = null;
  }

  /** Whether a finger down at (x, y) at t may be a further tap of the tap step just before (a double tap's second). */
  private boolean tapFollows(float x, float y, long t) {
    return open != null && "tap".equals(open.get("do")) && lastType == SimWindow.UP && t - tapUp <= DOUBLE_TAP_MS
        && Math.hypot(x - tapX, y - tapY) <= DOUBLE_TAP_SLOP_DP * slopPx / 8;
  }

  /** Whether a text can be expected on screen: not a time, a duration, a date or a size, on any of its lines. */
  static boolean expectable(String t) {
    return t.length() <= 80 && !VOLATILE.matcher(t).find() && !DEVICE.matcher(t).find();
  }

  /**
   * The step that ended expects the texts that appeared on screen for the first time in the journey (up to 6), else
   * the first 3 that appeared since it started (Back to an earlier screen): what a returning screen showed besides its
   * own content (a window size class, a clock) would tie the journey to one device.
   */
  private void close(List<String> now) {
    if (open == null || textsAtOpen == null) {
      everSeen.addAll(now);
      return;
    }
    List<String> first = new ArrayList<>();
    List<String> back = new ArrayList<>();
    for (String t : now) {
      if (textsAtOpen.contains(t) || !expectable(t)) continue;
      if (!everSeen.contains(t) && first.size() < 6) first.add(t);
      if (back.size() < 3) back.add(t);
    }
    everSeen.addAll(now);
    List<String> expect = first.isEmpty() ? back : first;
    if (Boolean.TRUE.equals(open.get("fling"))) {
      // Where a fling ends differs from run to run, even live (docs/replay.md): what it scrolled into view is no
      // expectation a test can keep.
      if (!expect.isEmpty()) notes.add("step " + open.get("n") + " (a fling) has no expectations: how far a fling goes varies");
      return;
    }
    if (!expect.isEmpty()) open.put("expect", expect);
  }

  /** Every item the replay plays, in order (main thread), with the item's uptime. */
  void event(SimWindow.Event e, long t) {
    int type = e.type();
    if (type == SimWindow.DOWN && gesture == null) anotherTap = tapFollows(e.x(), e.y(), t);
    if (!anotherTap) startStep();
    switch (type) {
      case SimWindow.DOWN -> {
        if (gesture != null) return;  // a second finger: the first one's gesture goes on
        gesture = new Gesture();
        gesture.x0 = gesture.x = e.x();
        gesture.y0 = gesture.y = e.y();
        gesture.t0 = t;
        gesture.recent.add(new float[] {t, e.x(), e.y()});
        gesture.path.add(List.of(0, Math.round(e.x()), Math.round(e.y())));
        gesture.target = screen.target(e.x(), e.y());
      }
      case SimWindow.MOVE -> {
        if (gesture == null) return;
        gesture.x = e.x();
        gesture.y = e.y();
        gesture.maxDist = Math.max(gesture.maxDist, (float) Math.hypot(e.x() - gesture.x0, e.y() - gesture.y0));
        gesture.recent.add(new float[] {t, e.x(), e.y()});
        while (gesture.recent.size() > 2 && t - gesture.recent.peekFirst()[0] > 100) gesture.recent.pollFirst();
        // Every point, however long the gesture (a minute's drag at 8 ms is ~7,500): a cut-off path would hold still
        // and then jump to where the finger lifted, changing the drag and its release speed.
        gesture.path.add(List.of((int) (t - gesture.t0), Math.round(e.x()), Math.round(e.y())));
      }
      case SimWindow.UP -> {
        if (gesture == null) return;
        Gesture g = gesture;
        gesture = null;
        lift(g, e.x(), e.y(), t);
      }
      case SimWindow.CANCEL -> {
        gesture = null;
        anotherTap = false;
      }
      case SimWindow.CHAR -> {
        String c = new String(Character.toChars((int) e.value()));
        if (lastType == SimWindow.CHAR && open != null && "type".equals(open.get("do"))) {
          open.put("text", open.get("text") + c);
        } else {
          Map<String, Object> s = new LinkedHashMap<>();
          s.put("do", "type");
          s.put("text", c);
          add(s);
        }
      }
      case SimWindow.KEY -> {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("do", "key");
        String name = KeyEvent.keyCodeToString((int) e.value());
        s.put("key", name.startsWith("KEYCODE_") ? name.substring(8) : name);
        int meta = (int) e.x();
        List<String> mods = new ArrayList<>();
        if ((meta & KeyEvent.META_CTRL_ON) != 0) mods.add("ctrl");
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) mods.add("shift");
        if ((meta & KeyEvent.META_ALT_ON) != 0) mods.add("alt");
        if ((meta & KeyEvent.META_META_ON) != 0) mods.add("meta");
        if (!mods.isEmpty()) s.put("mods", mods);
        add(s);
      }
      case SimWindow.BACK -> {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("do", "back");
        add(s);
      }
      case SimWindow.WHEEL, SimWindow.ROTARY -> {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("do", type == SimWindow.WHEEL ? "wheel" : "rotary");
        if (type == SimWindow.WHEEL) s.put("at", List.of(Math.round(e.x()), Math.round(e.y())));
        // The control command `rotary N` sends -N (Host): a rotary step keeps the command's sign, so it turns the same way.
        s.put("amount", type == SimWindow.ROTARY ? -e.value() : e.value());
        add(s);
      }
      case SimWindow.TOUCH2_CENTRE -> notes.add("a two-finger gesture at +" + t + " ms uptime was left out");
      case SimWindow.RESIZE -> notes.add("a window resize to " + Math.round(e.x()) + "x" + Math.round(e.y()) + " was left out");
      default -> {
      }
    }
    lastType = type;
    lastT = t;
  }

  /** A finger lifting at (x, y) at t: a tap, a long press or a swipe, or one more tap of a double tap. */
  private void lift(Gesture g, float x, float y, long t) {
    g.maxDist = Math.max(g.maxDist, (float) Math.hypot(x - g.x0, y - g.y0));
    long ms = t - g.t0;
    boolean tap = g.maxDist <= slopPx && ms < 500;
    if (anotherTap) {
      anotherTap = false;
      // One more tap of the step before, on the same node (or, with none, the same point): its time from the first
      // down and how long it was down. A tap on another control nearby is a step of its own: `times` has one target.
      if (tap && java.util.Objects.equals(open.get("target"), g.target)
          && (g.target != null || Math.hypot(g.x0 - tapX, g.y0 - tapY) <= slopPx)) {
        @SuppressWarnings("unchecked")
        List<List<Long>> times = (List<List<Long>>) open.get("times");
        if (times == null) {
          times = new ArrayList<>();
          times.add(List.of(0L, tapUp - tapT0));
          open.put("times", times);
        }
        times.add(List.of(g.t0 - tapT0, ms));
        tapUp = t;
        pending = null;  // the screen between its taps: the step is closed against the one before its first
        return;
      }
      startStep();  // a drag, a long press or another control after a tap: a step of its own
    }
    Map<String, Object> s = new LinkedHashMap<>();
    if (tap) {
      tapT0 = g.t0;
      tapUp = t;
      tapX = g.x0;
      tapY = g.y0;
    }
    if (g.maxDist <= slopPx) {
      s.put("do", ms >= 500 ? "longpress" : "tap");
      if (g.target != null) s.put("target", g.target);
      s.put("at", List.of(Math.round(g.x0), Math.round(g.y0)));
      if (ms >= 500) s.put("ms", ms);
    } else {
      s.put("do", "swipe");
      s.put("from", List.of(Math.round(g.x0), Math.round(g.y0)));
      s.put("to", List.of(Math.round(x), Math.round(y)));
      s.put("ms", Math.max(1, ms));
      // Whether it flung: its speed over the last 50-100 ms before lifting, against the framework's minimum fling
      // velocity (50 dp/s). Not its whole duration: a slow drag can end in a flick, a quick one in a hold.
      float[] from = g.recent.isEmpty() ? new float[] {g.t0, g.x0, g.y0} : g.recent.peekFirst();
      for (float[] p : g.recent) if (t - p[0] >= 50) from = p;
      double dt = Math.max(1, t - from[0]);
      double speed = Math.hypot(x - from[1], y - from[2]) / dt * 1000;  // px/s
      s.put("fling", speed > 50 * slopPx / 8);
      // The finger's path with its timing (the lift last): journey.py plays it as touches, so a fling flies as far
      // as it can and a drag stops where it stopped, whichever it was.
      List<List<Integer>> path = new ArrayList<>(g.path);
      path.add(List.of((int) ms, Math.round(x), Math.round(y)));
      s.put("path", path);
    }
    add(s);
  }

  /** A command the replay ran (`open URL`, `rotate`...). */
  void command(String[] a) {
    startStep();
    // The recording keeps clipboard text as "<N characters>", which the replay sets as N x's: so does the journey, or
    // the app would get the marker itself and not what the replay that made the expectations gave it.
    if (a.length > 2 && a[0].equals("clipboard") && a[1].equals("set") && a[2].matches("<\\d+ characters>")) {
      a = new String[] {"clipboard", "set", "x".repeat(Integer.parseInt(a[2].replaceAll("\\D", "")))};
    }
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("do", "command");
    s.put("command", List.of(a));
    add(s);
    lastType = -1;
  }

  private void add(Map<String, Object> s) {
    s.put("n", steps.size() + 1);
    // n first, for reading
    Map<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("n", s.remove("n"));
    ordered.putAll(s);
    steps.add(ordered);
    open = ordered;
  }

  /** The end of the replay: the last step's expectations, then the file. */
  Path finish() throws IOException {
    startStep();
    close(screen.texts());
    return write();
  }

  /**
   * The app crashed during the replay (any thread, the app going down) at t on the replay's clock (-1: unknown): the
   * steps it got to, without reading the screen again, and why the journey ends there. A finger still down (a long
   * press whose onLongClick crashed, a double tap's second tap) is lifted there, so the step that crashed is kept.
   */
  Path crashed(String why, long t) throws IOException {
    if (gesture != null) {
      Gesture g = gesture;
      gesture = null;
      lift(g, g.x, g.y, Math.max(t, lastT));
      notes.add("step " + open.get("n") + " ends at the crash: its finger was still down");
    }
    startStep();
    notes.add("the replay ended here: " + why);
    return write();
  }

  List<Map<String, Object>> steps() {
    return steps;
  }

  List<String> notes() {
    return notes;
  }

  private Path write() throws IOException {
    Map<String, Object> m = new LinkedHashMap<>(head);
    m.put("steps", steps);
    if (!notes.isEmpty()) m.put("notes", notes);
    Files.createDirectories(file.getParent());
    StringBuilder b = new StringBuilder("{\n");
    int i = 0;
    for (Map.Entry<String, Object> e : m.entrySet()) {
      b.append("  ").append(JsonLine.write(e.getKey())).append(": ");
      if (e.getKey().equals("steps")) {
        b.append("[\n");
        for (int k = 0; k < steps.size(); k++) b.append("    ").append(JsonLine.write(steps.get(k))).append(k + 1 < steps.size() ? ",\n" : "\n");
        b.append("  ]");
      } else {
        b.append(JsonLine.write(e.getValue()));
      }
      b.append(++i < m.size() ? ",\n" : "\n");
    }
    b.append("}\n");
    Files.writeString(file, b.toString());
    return file;
  }

  // ---------------------------------------------------------------- the screen as accessibility reports it

  private static List<View> roots() {
    return AccessibleWindows.reported(Host.rootViews());
  }

  /** The texts on screen, in the order `nodes` lists them, each once (password fields left out). */
  static List<String> texts() {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    try {
      Nodes.walk(roots(), n -> {
        // Only what is on screen: a label scrolled away is not "there" for an expectation (nodes marks it offscreen).
        if (!n.info.isPassword() && !n.text().isBlank() && n.info.isVisibleToUser()) out.add(n.text());
      });
    } catch (RuntimeException e) {
      // a tree that cannot be read now: no expectations from it
    }
    return new ArrayList<>(out);
  }

  /**
   * The node under (x, y): the deepest that `nodes` lists with a label there, as {text|desc|id: VALUE, index: K} (K
   * among the nodes with that label, in `nodes` order, only when there are several), or null.
   */
  static Map<String, Object> target(float x, float y) {
    List<Nodes.Node> all = new ArrayList<>();
    try {
      Nodes.walk(roots(), all::add);
    } catch (RuntimeException e) {
      return null;
    }
    Rect r = new Rect();
    String kind = null;
    String value = null;
    for (Nodes.Node n : all) {
      n.info.getBoundsInScreen(r);
      // Only a node the user could see there: an invisible one keeping its bounds must not give the tap its label.
      if (r.width() <= 0 || r.height() <= 0 || !r.contains(Math.round(x), Math.round(y)) || !n.info.isVisibleToUser()) continue;
      String[] l = label(n);
      if (l != null) {  // walk order is depth first: a later match is deeper (or a later sibling on top)
        kind = l[0];
        value = l[1];
      }
    }
    if (kind == null) return null;
    // Its index as journey.py counts: among the nodes `nodes` lists with a positive size whose attribute of that kind
    // (whatever else they carry) has that value, in `nodes` order; the last of them under the point (the deepest).
    int count = 0;
    int index = 0;
    for (Nodes.Node n : all) {
      if (!listed(n) || !value.equals(attribute(n, kind))) continue;
      n.info.getBoundsInScreen(r);
      if (r.width() <= 0 || r.height() <= 0) continue;
      // Every match counts (journey.py counts them all), but only a visible one under the point is the target.
      if (r.contains(Math.round(x), Math.round(y)) && n.info.isVisibleToUser()) index = count;
      count++;
    }
    Map<String, Object> t = new LinkedHashMap<>();
    t.put(kind, value);
    if (count > 1) t.put("index", index);
    return t;
  }

  /** Whether `nodes` lists the node (Nodes.dump's test). */
  private static boolean listed(Nodes.Node n) {
    return !n.text().isEmpty() || !n.hint().isEmpty() || !n.description().isEmpty() || n.info.isEditable() || n.info.isClickable();
  }

  /** The node's text, description or id as `nodes` prints it (null when it has none; a password's text is masked). */
  private static String attribute(Nodes.Node n, String kind) {
    return switch (kind) {
      case "text" -> n.info.isPassword() || n.text().isEmpty() ? null : n.text();
      case "desc" -> n.description().isEmpty() ? null : n.description();
      default -> n.info.getViewIdResourceName() == null ? null : n.info.getViewIdResourceName().toString();
    };
  }

  /** What a node is found by: its text, description, or (clickable or editable) resource id, as `nodes` shows them. */
  private static String[] label(Nodes.Node n) {
    if (n.info.isPassword()) return null;
    if (!n.text().isBlank()) return new String[] {"text", n.text()};
    if (!n.description().isBlank()) return new String[] {"desc", n.description()};
    CharSequence id = n.info.getViewIdResourceName();
    if (id != null && id.length() > 0 && (n.info.isClickable() || n.info.isEditable())) return new String[] {"id", id.toString()};
    return null;
  }
}
