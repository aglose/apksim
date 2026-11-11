package dev.apksim;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import dev.apksim.rec.JsonLine;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;

/**
 * `apksim replay BUNDLE` inside the app's process (docs/replay.md). {@link ReplayPlan} has unpacked the bundle into
 * `-Dapksim.replay` and its checkpoint's data into this instance's data slot, and launched with the checkpoint's
 * device. Here: the checkpoint's activity stack is rebuilt in place of the start activity (each activity from its
 * parcelled intent and saved instance state, as `process-death` rebuilds them), the wall clock set to the
 * checkpoint's, then `input.jsonl` goes to the Host loop at its recorded uptime offsets from the checkpoint, through
 * the same dispatch as live input, and device commands run as commands. Before each gesture, and at the end, the
 * screen is compared with the recorded frames (`screen.avi`), scaled and JPEG-encoded as the recorder kept them; the
 * first point where it differs is reported with both frames and a difference map. Without `--control` the process
 * then ends: 0 when every compared point matched, 3 when one diverged, 10 for a crash (as any crash). With
 * `--control` the app stays up for a person to take over, and `replay` reports.
 */
final class Replay {
  /** Exit status of a replay that diverged. */
  static final int DIVERGED = 3;
  /** A pixel counts as different when a channel differs by more than this (the frames went through JPEG). */
  static final int DELTA = 64;

  /** What the Host lends the replay (main thread). */
  interface HostSide {
    String run(String[] command) throws Exception;

    /** The latest frame presented (app pixels, as the recorder kept them), or null. The caller recycles it. */
    Bitmap screen();

    /** Frames drawn so far. */
    long frames();

    void exit(int code, String why);
  }

  private static volatile Replay current;

  private final Path dir;
  private final Path out;
  private final Properties plan = new Properties();
  private final Map<String, Object> manifest;
  private final Map<String, Object> checkpoint;
  private final long cpUptime;
  private final long cpWall;
  private final long endUptime;
  private final long windowTo;
  private final List<Item> items = new ArrayList<>();
  private final Movie movie;
  private final String compareMode;
  private final double threshold;
  private final boolean control = System.getProperty("apksim.control") != null;

  /** One line of input.jsonl: an event to dispatch or a command to run, and whether a gesture starts with it. */
  record Item(long t, long w, SimWindow.Event event, String[] command, boolean point, String what) {}

  /** One compared point: where, how many pixels differ in the best recorded candidate, and the verdict. */
  record Point(int n, String label, long offsetMs, long over, long total, boolean diverged, String files) {}

  private final List<Point> points = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private Point firstDivergence;
  private long base = -1;
  private int next;
  private int dispatched;
  private int commandsRun;
  private long endSince;
  private long framesAtEnd = -1;
  private long quietSince;
  private String result;  // null while running
  private int pointers;

  private Replay(Path dir) throws IOException {
    this.dir = dir;
    try (InputStream in = Files.newInputStream(dir.resolve("plan.properties"))) {
      plan.load(in);
    }
    out = Path.of(plan.getProperty("out"));
    manifest = ReplayPlan.map(json(Files.readString(dir.resolve("manifest.json"))));
    checkpoint = ReplayPlan.map(manifest.get("checkpoint"));
    cpUptime = (long) num(checkpoint.get("uptime"));
    cpWall = (long) num(checkpoint.get("wall"));
    Map<String, Object> window = ReplayPlan.map(manifest.get("window"));
    endUptime = window != null && window.get("uptimeAtSave") != null ? (long) num(window.get("uptimeAtSave")) : -1;
    windowTo = window != null && window.get("to") != null ? (long) num(window.get("to")) : 0;
    compareMode = plan.getProperty("compare", "all");
    Path avi = dir.resolve("screen.avi");
    movie = Files.isRegularFile(avi) && !compareMode.equals("none") ? Movie.read(avi, dir.resolve("screen-times.txt"),
        window != null && window.get("from") != null ? (long) num(window.get("from")) : 0) : null;
    // Pixels over DELTA that still count as the same screen: a fraction of the frame (under 1) or a count. The default
    // lets a blinking caret or a ripple's last step through and catches a changed word.
    String t = plan.getProperty("threshold");
    long total = movie == null ? 0 : (long) movie.w * movie.h;
    double th = t == null ? 0.00005 : Double.parseDouble(t);
    threshold = th < 1 ? Math.max(16, th * total) : th;
    Path input = dir.resolve("input.jsonl");
    if (Files.isRegularFile(input)) {
      int down = 0;
      for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
        if (line.isBlank()) continue;
        Map<String, Object> m = ReplayPlan.map(json(line));
        long at = (long) num(m.get("t"));
        long wall = (long) num(m.get("w"));
        if (at < cpUptime) continue;  // from before the checkpoint (none are saved, but an older bundle might)
        if (m.get("cmd") instanceof List<?> c) {
          String[] a = c.stream().map(String::valueOf).toArray(String[]::new);
          if (a.length == 0) continue;
          items.add(new Item(at, wall, null, a, true, String.join(" ", a)));
          continue;
        }
        int type = (int) num(m.get("type"));
        if (type == SimWindow.CLOSE) continue;  // closing the window ended the recording: not repeated
        float x = (float) num(m.get("x"));
        float y = (float) num(m.get("y"));
        float v = (float) num(m.get("v"));
        if (Boolean.TRUE.equals(m.get("redacted"))) {
          // Left out of the recording (a password field, pasted text): a placeholder of the same kind.
          if (type == SimWindow.KEY) {
            x = 0;
            v = android.view.KeyEvent.KEYCODE_X;
          } else {
            type = SimWindow.CHAR;
            x = 0;
            y = 0;
            v = 'x';
          }
        }
        boolean point = switch (type) {
          case SimWindow.DOWN, SimWindow.TOUCH2_CENTRE -> down == 0;
          case SimWindow.KEY, SimWindow.BACK, SimWindow.RESIZE -> true;
          case SimWindow.CHAR, SimWindow.WHEEL, SimWindow.ROTARY -> items.isEmpty() || items.get(items.size() - 1).event == null
              || items.get(items.size() - 1).event.type() != type;  // the first of a run
          default -> false;
        };
        if (type == SimWindow.DOWN || type == SimWindow.TOUCH2_DOWN) down++;
        else if (type == SimWindow.UP || type == SimWindow.CANCEL || type == SimWindow.TOUCH2_UP) down = Math.max(0, down - 1);
        String what = String.valueOf(m.get("ev")) + (type == SimWindow.CHAR || type == SimWindow.KEY ? "" : " " + Math.round(x) + "," + Math.round(y));
        items.add(new Item(at, wall, new SimWindow.Event(type, x, y, v), null, point, what));
      }
    }
    items.sort(java.util.Comparator.comparingLong(Item::t));
  }

  /** The replay this launch asked for (-Dapksim.replay), or null. Main thread, before the first activity. */
  static Replay load() throws IOException {
    String d = System.getProperty("apksim.replay");
    if (d == null || d.isEmpty()) return null;
    current = new Replay(Path.of(d));
    return current;
  }

  // ---------------------------------------------------------------- the checkpoint

  /**
   * In place of the start activity: the checkpoint's stack, bottom first, each activity built with its intent and
   * created, started and restored from its saved state, the top one resumed (DeviceControls.rebuildFromSavedState's
   * order). The device's rotation first, so the activities are created in it; then the wall clock.
   */
  void restore(android.app.Application app, ArrayDeque<ActivityController<? extends Activity>> stack, DeviceControls controls)
      throws Exception {
    String pkg = String.valueOf(manifest.get("package"));
    if (!pkg.equals(app.getPackageName())) {
      throw new IllegalStateException("the recording is of " + pkg + ", the APK is " + app.getPackageName());
    }
    Map<String, Object> device = ReplayPlan.map(checkpoint.get("device"));
    int rotation = device != null && device.get("rotation") != null ? (int) num(device.get("rotation")) : 0;
    if (rotation == 0 && device != null && String.valueOf(device.get("qualifiers")).contains("-land-")
        && app.getResources().getDisplayMetrics().widthPixels < app.getResources().getDisplayMetrics().heightPixels) {
      rotation = 1;  // an older bundle: landscape on a portrait device
    }
    for (int i = 0; i < rotation; i++) controls.run(new String[] {"rotate", "right"});
    SimClock.set(String.valueOf(cpWall));
    ClassLoader loader = app.getClassLoader();
    List<String> names = new ArrayList<>();
    List<Intent> intents = new ArrayList<>();
    List<Bundle> states = new ArrayList<>();
    try (DataInputStream in = new DataInputStream(Files.newInputStream(dir.resolve("checkpoint/state.bin")))) {
      int n = in.readInt();
      for (int i = 0; i < n; i++) {
        String name = in.readUTF();
        byte[] intent = bytes(in);
        byte[] state = bytes(in);
        names.add(name);
        Intent it = null;
        if (intent != null) {
          Parcel p = Parcel.obtain();
          try {
            p.unmarshall(intent, 0, intent.length);
            p.setDataPosition(0);
            it = Intent.CREATOR.createFromParcel(p);
            it.setExtrasClassLoader(loader);
          } finally {
            p.recycle();
          }
        } else {
          notes.add(name + ": its intent was not saved; started with its component alone");
          it = new Intent().setClassName(pkg, name);
        }
        intents.add(it);
        Bundle b = null;
        if (state != null) {
          Parcel p = Parcel.obtain();
          try {
            p.unmarshall(state, 0, state.length);
            p.setDataPosition(0);
            b = p.readBundle(loader);
          } finally {
            p.recycle();
          }
        } else {
          notes.add(name + ": its saved state was not kept; created without it");
        }
        states.add(b);
      }
    }
    if (names.isEmpty()) throw new IllegalStateException("the checkpoint has no activity");
    Crashes.app(() -> {
      for (int i = 0; i < names.size(); i++) {
        Class<? extends Activity> cls;
        try {
          cls = Class.forName(names.get(i), true, Host.class.getClassLoader()).asSubclass(Activity.class);
        } catch (ClassNotFoundException e) {
          throw new IllegalStateException("this build has no activity " + names.get(i) + " (the recording's)");
        }
        Bundle b = states.get(i);
        ActivityController<? extends Activity> c = Robolectric.buildActivity(cls, intents.get(i));
        if (b != null) c.create(b).start().restoreInstanceState(b).postCreate(b);
        else c.create().start().postCreate(null);
        if (i == names.size() - 1) c.resume().visible();
        else c.stop();
        stack.push(c);
      }
      Host.arrange(new ArrayList<>(stack), false);
      return null;
    });
    String how = names.size() + " activit" + (names.size() == 1 ? "y" : "ies") + " from checkpoint #" + str(checkpoint.get("id"))
        + ", " + items.size() + " input events and commands, network " + plan.getProperty("network")
        + ", compare " + (movie == null ? "none (no screen channel)" : compareMode);
    System.err.println("[apksim] replay: " + how);
  }

  // ---------------------------------------------------------------- the journal

  /**
   * Each loop turn (main thread): runs the commands due, and returns the input event due now (dispatched by the Host
   * as live input), or null. The journal's clock starts once the rebuilt stack's first frame is drawn.
   */
  SimWindow.Event next(boolean firstFrameDrawn, HostSide host) {
    if (result != null || !firstFrameDrawn) return null;
    long now = SystemClock.uptimeMillis();
    if (base < 0) base = now;
    while (next < items.size()) {
      Item it = items.get(next);
      if (now - base < it.t - cpUptime) return null;
      if (it.point && pointers == 0 && compareMode.equals("all")) compare(host, "before " + it.what, it.w - 20, it.t - cpUptime);
      next++;
      if (it.command != null) {
        runCommand(it, host);
        continue;
      }
      SimWindow.Event e = it.event;
      if (e.type() == SimWindow.DOWN || e.type() == SimWindow.TOUCH2_DOWN) pointers++;
      else if (e.type() == SimWindow.UP || e.type() == SimWindow.CANCEL || e.type() == SimWindow.TOUCH2_UP) pointers = Math.max(0, pointers - 1);
      dispatched++;
      return e;
    }
    // The journal is done: wait for the recording's end (the save), then for the screen to be still (300 ms without a
    // frame, at most 3 s), and compare the last frame.
    long end = endUptime >= 0 ? endUptime - cpUptime : 0;
    if (now - base < end) return null;
    long real = System.nanoTime();
    if (endSince == 0) {
      endSince = real;
      framesAtEnd = host.frames();
      quietSince = real;
    }
    if (host.frames() != framesAtEnd) {
      framesAtEnd = host.frames();
      quietSince = real;
    }
    if (real - quietSince < 300_000_000L && real - endSince < 3_000_000_000L) return null;
    if (!compareMode.equals("none")) compare(host, "end", windowTo, now - base);
    finish(host);
    return null;
  }

  private void runCommand(Item it, HostSide host) {
    String[] a = it.command;
    switch (a[0]) {
      case "paste" -> {
        return;  // its characters are in the journal, as placeholders
      }
      case "clipboard" -> {
        if (a.length > 2 && a[1].equals("set") && a[2].matches("<\\d+ characters>")) {
          a = new String[] {"clipboard", "set", "x".repeat(Integer.parseInt(a[2].replaceAll("\\D", "")))};
        }
      }
      default -> {
      }
    }
    try {
      String r = host.run(a);
      commandsRun++;
      if (r != null && r.startsWith("error")) notes.add("+" + (it.t - cpUptime) + " ms " + it.what + ": " + r);
    } catch (Crashes.AppCrash crash) {
      throw crash;
    } catch (Exception e) {
      notes.add("+" + (it.t - cpUptime) + " ms " + it.what + ": " + e);
    }
  }

  // ---------------------------------------------------------------- frames

  /**
   * Compares the screen now with what the recording showed at wall time `at` (and up to 1.5 s before it: animations
   * do not run at the same phase in a replay), each scaled and JPEG-encoded as the recorder keeps frames. The best of
   * those candidates decides. Both frames are written to the report's `points/`; for a divergence, the difference map.
   */
  private void compare(HostSide host, String label, long at, long offset) {
    if (movie == null || movie.frames.isEmpty()) return;
    Bitmap shot = host.screen();
    if (shot == null) return;
    int n = points.size() + 1;
    Bitmap mine = null;
    try {
      byte[] mineJpeg = encode(shot, movie.w, movie.h);
      mine = BitmapFactory.decodeByteArray(mineJpeg, 0, mineJpeg.length);
      int[] mp = new int[movie.w * movie.h];
      mine.getPixels(mp, 0, movie.w, 0, 0, movie.w, movie.h);
      long best = Long.MAX_VALUE;
      byte[] bestJpeg = null;
      int[] bestPixels = null;
      for (byte[] f : movie.around(at, 1500)) {
        Bitmap r = BitmapFactory.decodeByteArray(f, 0, f.length);
        if (r == null) continue;
        try {
          if (r.getWidth() != movie.w || r.getHeight() != movie.h) continue;
          int[] rp = new int[movie.w * movie.h];
          r.getPixels(rp, 0, movie.w, 0, 0, movie.w, movie.h);
          long over = ImageDiff.diff(rp, mp, DELTA, null).over();
          if (over < best) {
            best = over;
            bestJpeg = f;
            bestPixels = rp;
          }
        } finally {
          r.recycle();
        }
      }
      if (bestJpeg == null) return;
      boolean diverged = best > threshold;
      String stem = String.format("%02d", n);
      Path pts = out.resolve("points");
      Files.createDirectories(pts);
      Files.write(pts.resolve(stem + "-recorded.jpg"), bestJpeg);
      Files.write(pts.resolve(stem + "-replayed.jpg"), mineJpeg);
      String files = "points/" + stem + "-recorded.jpg points/" + stem + "-replayed.jpg";
      if (diverged) {
        int[] map = new int[mp.length];
        ImageDiff.diff(bestPixels, mp, DELTA, map);
        Bitmap mb = Bitmap.createBitmap(map, movie.w, movie.h, Bitmap.Config.ARGB_8888);
        try (FileOutputStream f = new FileOutputStream(pts.resolve(stem + "-diff.png").toFile())) {
          mb.compress(Bitmap.CompressFormat.PNG, 100, f);
        } finally {
          mb.recycle();
        }
        files += " points/" + stem + "-diff.png";
      }
      Point p = new Point(n, label, offset, best, (long) movie.w * movie.h, diverged, files);
      points.add(p);
      if (diverged && firstDivergence == null) {
        firstDivergence = p;
        System.err.println("[apksim] replay: diverged at point " + n + " (" + label + ", +" + offset + " ms): " + best
            + " pixels differ (threshold " + Math.round(threshold) + "); frames in " + out.resolve("points"));
      }
      writeReport("running");
    } catch (IOException | RuntimeException e) {
      notes.add("point " + n + " (" + label + ") not compared: " + e);
    } finally {
      shot.recycle();
      if (mine != null) mine.recycle();
    }
  }

  /** A frame scaled to the movie's size with a filtering paint and JPEG-encoded at the recorder's quality. */
  static byte[] encode(Bitmap src, int w, int h) {
    Bitmap scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    try {
      new Canvas(scaled).drawBitmap(src, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
      ByteArrayOutputStream b = new ByteArrayOutputStream(1 << 16);
      scaled.compress(Bitmap.CompressFormat.JPEG, 70, b);
      return b.toByteArray();
    } finally {
      scaled.recycle();
    }
  }

  /**
   * The bundle's screen.avi and screen-times.txt: the frames the recorder kept, each with the wall time it was
   * presented. The movie repeats each kept frame in its 200 ms slots (slot j stands for start + j * step and shows the
   * newest frame kept by then), so kept frame k is the one slot ceil((wall_k - start) / step) shows.
   */
  static final class Movie {
    final int w;
    final int h;
    /** Kept frames, oldest first: when each was presented, and its JPEG. */
    final List<Long> walls = new ArrayList<>();
    final List<byte[]> frames = new ArrayList<>();

    private Movie(int w, int h) {
      this.w = w;
      this.h = h;
    }

    /** MjpegRecorder's AVI: avih for the size and rate, idx1 for the slots (offsets from the movi list). */
    static Movie read(Path avi, Path times, long windowFrom) throws IOException {
      byte[] d = Files.readAllBytes(avi);
      ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
      int avih = indexOf(d, "avih", 0);
      int usec = b.getInt(avih + 8);
      int w = b.getInt(avih + 8 + 32);
      int h = b.getInt(avih + 8 + 36);
      long step = Math.max(1, usec / 1000);
      int movi = indexOf(d, "movi", 0);
      int idx = indexOf(d, "idx1", movi);
      int n = b.getInt(idx + 4) / 16;
      List<byte[]> slots = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int off = b.getInt(idx + 8 + i * 16 + 8);
        int size = b.getInt(idx + 8 + i * 16 + 12);
        int at = movi + off + 8;
        slots.add(Arrays.copyOfRange(d, at, at + size));
      }
      Movie m = new Movie(w, h);
      List<Long> kept = new ArrayList<>();
      if (Files.isRegularFile(times)) {
        for (String l : Files.readAllLines(times)) if (!l.isBlank()) kept.add(Long.parseLong(l.strip()));
      }
      if (slots.isEmpty()) return m;
      long start = kept.isEmpty() ? windowFrom : Math.max(windowFrom, kept.get(0));
      if (kept.isEmpty()) {
        // No times (an older or hand-made bundle): each slot as a frame of its own.
        for (int j = 0; j < slots.size(); j++) {
          m.walls.add(start + j * step);
          m.frames.add(slots.get(j));
        }
        return m;
      }
      int last = -1;
      for (long wall : kept) {
        int j = (int) Math.max(0, Math.min(slots.size() - 1, Math.floorDiv(wall - start + step - 1, step)));
        if (j == last) continue;  // two kept within one slot: only the newer is in the movie
        if (last >= 0 && Arrays.equals(slots.get(j), m.frames.get(m.frames.size() - 1))) continue;
        last = j;
        m.walls.add(wall);
        m.frames.add(slots.get(j));
      }
      return m;
    }

    /**
     * The frames the screen may have shown at wall time `at`: those kept from `backMs` before it, the newest kept by
     * then, and the next one if kept within 250 ms after (the recorder keeps a presented frame up to 200 ms late).
     */
    List<byte[]> around(long at, long backMs) {
      List<byte[]> out = new ArrayList<>();
      int newest = -1;
      for (int k = 0; k < walls.size(); k++) if (walls.get(k) <= at) newest = k;
      if (newest + 1 < walls.size() && walls.get(newest + 1) <= at + 250) out.add(frames.get(newest + 1));
      for (int k = newest; k >= 0; k--) {
        out.add(frames.get(k));
        if (walls.get(k) < at - backMs) break;
      }
      if (out.isEmpty() && !frames.isEmpty()) out.add(frames.get(0));
      return out;
    }

    private static int indexOf(byte[] d, String tag, int from) throws IOException {
      byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
      outer:
      for (int i = from; i + t.length <= d.length; i++) {
        for (int k = 0; k < t.length; k++) if (d[i + k] != t[k]) continue outer;
        return i;
      }
      throw new IOException("screen.avi has no " + tag);
    }
  }

  // ---------------------------------------------------------------- the report

  private void finish(HostSide host) {
    try {
      String unmatched = host.run(new String[] {"network", "unmatched"});
      if (unmatched != null && !unmatched.startsWith("no unmatched") && !unmatched.startsWith("error") && !unmatched.contains("not")) {
        notes.add("requests the recording has no answer for: " + unmatched.replace('\n', ';'));
      }
    } catch (Exception e) {
      // not replaying the network
    }
    result = firstDivergence != null ? "diverged" : "matched";
    writeReport(result);
    String line = firstDivergence != null
        ? "diverged at point " + firstDivergence.n + " of " + points.size() + " (" + firstDivergence.label + ", +" + firstDivergence.offsetMs + " ms)"
        : points.isEmpty() ? "done (nothing compared)" : "matched at all " + points.size() + " points";
    System.err.println("[apksim] replay: " + line + "; " + dispatched + " events, " + commandsRun + " commands; report "
        + out.resolve("report.json") + (notes.isEmpty() ? "" : "; " + notes.size() + " notes"));
    for (String n : notes) System.err.println("[apksim] replay note: " + n);
    if (control) {
      System.err.println("[apksim] replay: the app is yours (--control); `replay` reports, `quit` ends it");
    } else {
      host.exit(firstDivergence != null ? DIVERGED : 0, "replay " + result);
    }
  }

  /** The app crashed during the replay (Crashes.terminate): the report says where. */
  static void crashed(Throwable e) {
    Replay r = current;
    if (r == null || r.result != null) return;
    r.result = "crashed";
    String where = r.next > 0 && r.next <= r.items.size() ? r.items.get(r.next - 1).what + " at +" + (r.items.get(r.next - 1).t - r.cpUptime) + " ms"
        : "before the first event";
    r.notes.add("crashed after " + where + ": " + e);
    r.writeReport("crashed");
    System.err.println("[apksim] replay: the app crashed after " + where + "; report " + r.out.resolve("report.json"));
  }

  /** `replay`: where the replay is, or its result. */
  String command() {
    String state = result != null ? result : base < 0 ? "starting" : "running";
    StringBuilder b = new StringBuilder("replay: ").append(state).append("; ").append(next).append('/').append(items.size())
        .append(" events and commands; ").append(points.size()).append(" points compared");
    if (firstDivergence != null) {
      b.append("; first divergence at point ").append(firstDivergence.n).append(" (").append(firstDivergence.label)
          .append(", +").append(firstDivergence.offsetMs).append(" ms, ").append(firstDivergence.over).append(" pixels)");
    }
    b.append("; report ").append(out.resolve("report.json"));
    return b.toString();
  }

  static String commandNow() {
    Replay r = current;
    return r == null ? "error: not a replay (apksim replay BUNDLE)" : r.command();
  }

  private void writeReport(String state) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("result", state);
    m.put("bundle", plan.getProperty("bundle"));
    m.put("apk", plan.getProperty("apk"));
    m.put("package", manifest.get("package"));
    m.put("checkpoint", checkpoint.get("id"));
    m.put("network", plan.getProperty("network"));
    m.put("data", plan.getProperty("data"));
    m.put("events", items.size());
    m.put("done", next);
    m.put("threshold", Math.round(threshold));
    if (movie != null) m.put("frameSize", movie.w + "x" + movie.h);
    List<Object> ps = new ArrayList<>();
    for (Point p : points) {
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("n", p.n);
      x.put("label", p.label);
      x.put("offsetMs", p.offsetMs);
      x.put("pixels", p.over);
      x.put("diverged", p.diverged);
      x.put("files", p.files);
      ps.add(x);
    }
    m.put("points", ps);
    if (firstDivergence != null) m.put("firstDivergence", firstDivergence.n);
    m.put("notes", notes);
    try {
      Files.createDirectories(out);
      Path tmp = out.resolve("report.json" + Storage.tmpTag());
      Files.writeString(tmp, JsonLine.write(m) + "\n");
      Files.move(tmp, out.resolve("report.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      System.err.println("[apksim] replay: report not written: " + e);
    }
  }

  // ---------------------------------------------------------------- helpers

  private static Object json(String s) throws IOException {
    return dev.apksim.serve.Json.parse(s);
  }

  private static double num(Object o) {
    return o instanceof Number n ? n.doubleValue() : 0;
  }

  private static String str(Object o) {
    return o instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(o);
  }

  private static byte[] bytes(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0) return null;
    byte[] b = new byte[n];
    in.readFully(b);
    return b;
  }
}
