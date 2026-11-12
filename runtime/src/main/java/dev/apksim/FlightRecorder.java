package dev.apksim;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.TextView;
import dev.apksim.net.Net;
import dev.apksim.rec.JsonLine;
import dev.apksim.rec.Rec;
import dev.apksim.rec.Ring;
import dev.apksim.rec.Spans;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.robolectric.android.controller.ActivityController;

/**
 * The flight recorder (docs/flight-recorder.md): what the device did lately, kept in memory so the last N seconds
 * can be saved as a bundle someone else opens, with a checkpoint to start a replay from. Each channel can be turned on
 * and off while the app runs (`rec on|off CHANNEL...`, launch: `--rec CHANNELS`):
 * <ul>
 *   <li>input: every touch, wheel, key, character and Back the app is given, stamped with the simulated clock (the
 *       uptime the app sees), and the device commands that change what it sees (rotate, appearance, open URL...);
 *       what is typed into a password field is left out.
 *   <li>logcat: the app's log lines ({@link AdbLog}'s ring, which the channel turns on).
 *   <li>net: HTTP(S) exchanges, through the network layer in record mode with no file ({@link Net#capture}).
 *   <li>db: SQLite calls through the framework ({@link dev.apksim.rec.Db}); `db+values` also keeps bound values.
 *   <li>screen: a few frames a second, scaled down and JPEG-encoded off the raster thread, saved as a movie.
 * </ul>
 * A <b>checkpoint</b> is what a replay starts from: the activity stack (each one's intent and saved instance state)
 * and a clone of the app's data folder (APFS: no bytes copied). Saving state is done as Android does it when the user
 * leaves for a moment and comes back: the visible activities are stopped, every activity saves its state, and they are
 * started and resumed again, in one turn of the main thread (no frame shows it). That is the one way a checkpoint
 * shows in the app: its onStop/onStart/onResume run. Checkpoints are taken every {@code --rec-checkpoint} seconds
 * (default 30) while input is recorded, only once the user has been idle for a second and only if there was input
 * since the last one, and on `rec checkpoint now`.
 *
 * <p>The rings keep {@code --rec-window} seconds (default 120), and back to the checkpoint before that, so a save of
 * the whole window always has a checkpoint to start from. Each ring also has a byte budget.
 */
final class FlightRecorder {
  static final String FORMAT = "apksim-flight-recording/1";

  // Channels. Static: the raster thread reads screen, translated app code reads Rec.db.
  private static volatile boolean input;
  private static volatile boolean logcat;
  private static volatile boolean net;
  static volatile boolean screen;

  private static volatile long windowMs = 120_000;
  private static volatile long checkpointEveryMs = 30_000;
  // Read per launch (the constructor), not at class init: a standby loads this class before the launch's options.
  private static volatile int screenFps = 5;
  private static volatile int screenMax = 960;  // longest side, pixels
  private static final int SCREEN_QUALITY = 70;

  /** Input events and device commands, as JSON lines. */
  private static final Ring<Map<String, Object>> INPUT = new Ring<>(16L << 20, Rec::keepFromWallMs);
  /** JPEG frames of the screen; the value's first 8 bytes are width and height (two ints). */
  private static final Ring<byte[]> SCREEN = new Ring<>(64L << 20, Rec::keepFromWallMs);
  /** When the logcat channel was on: AdbLog's ring goes on for adb whatever the channel does. */
  private static final Spans LOGCAT = new Spans();

  private final Application app;
  private final ArrayDeque<ActivityController<? extends Activity>> stack;
  private final DeviceControls controls;

  /**
   * The launch options: -Dapksim.rec=CHANNELS (--rec), apksim.rec.window, apksim.rec.checkpoint. Net and db were
   * turned on by {@link Rec#launch} before the app started; input, logcat and screen start here, with the Host.
   */
  FlightRecorder(Application app, ArrayDeque<ActivityController<? extends Activity>> stack, DeviceControls controls) {
    this.app = app;
    this.stack = stack;
    this.controls = controls;
    current = this;
    String w = System.getProperty("apksim.rec.window");
    if (w != null) windowMs = Math.max(5, Long.parseLong(w)) * 1000;
    String cp = System.getProperty("apksim.rec.checkpoint");
    if (cp != null) checkpointEveryMs = Math.max(0, Long.parseLong(cp)) * 1000;
    screenFps = Math.max(1, Math.min(15, Integer.getInteger("apksim.rec.screenFps", 5)));
    screenMax = Math.max(64, Integer.getInteger("apksim.rec.screenMax", 960));
    List<String> on = Rec.launchChannels();
    if (on.isEmpty()) return;
    net = Rec.NET.isOn();
    List<String> rest = new ArrayList<>(on);
    rest.removeAll(List.of("net", "db", "db+values"));
    String r = rest.isEmpty() ? "" : set(true, rest);
    System.err.println("[apksim] rec: " + status().split(" \\| ")[0] + (r.contains("error") ? "; " + r : ""));
    if (input) wantCheckpoint = true;
  }

  // ---------------------------------------------------------------- the command

  /** rec [status] | on|off [CHANNEL...] | window S | checkpoint [now|every S|off] | save [SECONDS] [FILE] | clear. */
  String command(String[] a) throws Exception {
    String op = a.length > 1 ? a[1] : "status";
    List<String> rest = List.of(a).subList(Math.min(a.length, 2), a.length);
    switch (op) {
      case "status" -> {
        return status();
      }
      case "on", "off" -> {
        String r = set(op.equals("on"), rest.isEmpty() ? List.of("all") : rest);
        if (op.equals("on") && input && checkpoints.isEmpty()) wantCheckpoint = true;  // something to start from
        return r;
      }
      case "window" -> {
        if (rest.isEmpty()) return "window " + windowMs / 1000 + " s";
        windowMs = Math.max(5, Long.parseLong(rest.get(0))) * 1000;
        return "window " + windowMs / 1000 + " s";
      }
      case "checkpoint" -> {
        if (rest.isEmpty() || rest.get(0).equals("now")) return checkpoint("asked");
        if (rest.get(0).equals("verify")) return verify(rest.size() > 1 ? Integer.parseInt(rest.get(1).replace("#", "")) : -1);
        if (rest.get(0).equals("off")) {
          checkpointEveryMs = 0;
          return "checkpoints: only when asked";
        }
        if (rest.get(0).equals("every") && rest.size() > 1) {
          checkpointEveryMs = Math.max(5, Long.parseLong(rest.get(1))) * 1000;
          return "checkpoints: every " + checkpointEveryMs / 1000 + " s";
        }
        return "error: usage: rec checkpoint [now|every SECONDS|off]";
      }
      case "save" -> {
        long seconds = windowMs / 1000;
        String file = null;
        for (String s : rest) {
          if (s.matches("\\d+")) seconds = Long.parseLong(s);
          else file = s;
        }
        return save(seconds, file);
      }
      case "clear" -> {
        long now = System.currentTimeMillis();
        INPUT.clear();
        synchronized (FRAME_LOCK) {
          screenGen++;  // a frame being encoded from before the clear is not added after it
          pendingWall = 0;
          SCREEN.clear();
        }
        if (screen) screenBaseline();
        Rec.DB.clear();
        LOGCAT.clear(now);  // AdbLog's ring stays for adb; a save just takes nothing from before now
        Rec.NET.clear(now);
        Net.clearCaptured();  // the in-memory capture; a --network record file keeps its own
        redacted = 0;
        List<Checkpoint> gone;
        synchronized (checkpoints) {
          gone = retire(new ArrayList<>(checkpoints));
          checkpoints.clear();
        }
        for (Checkpoint c : gone) Storage.deleteTree(c.dir);
        return "cleared";
      }
      default -> {
        return "error: usage: rec [status] | on|off [input|logcat|net|db|db+values|screen|all]... | window SECONDS"
            + " | checkpoint [now|every SECONDS|off] | save [SECONDS] [FILE] | clear";
      }
    }
  }

  private static synchronized String set(boolean on, List<String> names) {
    List<String> done = new ArrayList<>();
    for (String n : names) {
      switch (n) {
        case "all" -> {
          for (String c : List.of("input", "logcat", "net", "db", "screen")) done.add(one(on, c));
        }
        case "input", "logcat", "net", "db", "db+values", "screen" -> done.add(one(on, n));
        default -> done.add("error: no channel " + n + " (input, logcat, net, db, db+values, screen, all)");
      }
    }
    return String.join("; ", done);
  }

  private static String one(boolean on, String c) {
    switch (c) {
      case "input" -> input = on;
      case "logcat" -> {
        if (on) AdbLog.install();
        logcat = on;
        if (on) LOGCAT.on(System.currentTimeMillis());
        else LOGCAT.off(System.currentTimeMillis());
      }
      case "net" -> {
        String r = Net.capture(on);
        net = on && !r.startsWith("error");
        if (net) Rec.NET.on(System.currentTimeMillis());
        else Rec.NET.off(System.currentTimeMillis());
        return r;
      }
      case "db" -> {
        Rec.db = on;
        if (!on) Rec.dbValues = false;
      }
      case "db+values" -> {
        Rec.db = on || Rec.db;
        Rec.dbValues = on;
      }
      case "screen" -> {
        screen = on;
        if (on) screenBaseline();
      }
      default -> {
      }
    }
    return c + ": " + (on ? "on" : "off");
  }

  private String status() {
    List<String> on = new ArrayList<>();
    if (input) on.add("input");
    if (logcat) on.add("logcat");
    if (net) on.add("net");
    if (Rec.db) on.add(Rec.dbValues ? "db+values" : "db");
    if (screen) on.add("screen");
    StringBuilder b = new StringBuilder();
    b.append("rec: ").append(on.isEmpty() ? "off" : String.join(" ", on));
    b.append("; window ").append(windowMs / 1000).append(" s");
    b.append("; checkpoints ").append(checkpointEveryMs == 0 ? "when asked" : "every " + checkpointEveryMs / 1000 + " s");
    b.append(" | input ").append(INPUT.size()).append(" events");
    if (logcat) b.append(", logcat ").append(AdbLog.installed() ? "kept" : "off");
    if (net) b.append(", net ").append(Net.capturedCount()).append(" exchanges");
    if (Rec.db || Rec.DB.size() > 0) b.append(", db ").append(Rec.DB.size()).append(" calls");
    b.append(", screen ").append(SCREEN.size()).append(" frames (").append(SCREEN.bytes() >> 10).append(" KB)");
    synchronized (checkpoints) {
      b.append(" | checkpoints:");
      if (checkpoints.isEmpty()) b.append(" none");
      for (Checkpoint c : checkpoints) {
        b.append(String.format(" #%d %ds ago (%s)", c.id, (System.currentTimeMillis() - c.wallMs) / 1000, c.why));
      }
    }
    String s = lastSave;
    if (s != null) b.append(" | last save: ").append(s);
    return b.toString();
  }

  // ---------------------------------------------------------------- input

  private static final String[] EVENTS = new String[32];

  static {
    EVENTS[SimWindow.DOWN] = "down";
    EVENTS[SimWindow.UP] = "up";
    EVENTS[SimWindow.MOVE] = "move";
    EVENTS[SimWindow.WHEEL] = "wheel";
    EVENTS[SimWindow.BACK] = "back";
    EVENTS[SimWindow.CLOSE] = "close";
    EVENTS[SimWindow.CHAR] = "char";
    EVENTS[SimWindow.KEY] = "key";
    EVENTS[SimWindow.RESIZE] = "resize";
    EVENTS[SimWindow.CANCEL] = "cancel";
    EVENTS[SimWindow.ROTARY] = "rotary";
    EVENTS[SimWindow.TOUCH2_CENTRE] = "touch2-centre";
    EVENTS[SimWindow.TOUCH2_DOWN] = "touch2-down";
    EVENTS[SimWindow.TOUCH2_MOVE] = "touch2-move";
    EVENTS[SimWindow.TOUCH2_UP] = "touch2-up";
  }

  private static long lastInputWall;
  private static long inputSinceCheckpoint;
  private static int pointersDown;
  private static int redacted;

  /** An input event about to be dispatched to the app (main thread). `uptime`: the app's SystemClock.uptimeMillis. */
  static void input(SimWindow.Event e, long uptime) {
    int t = e.type();
    if (t == SimWindow.DOWN || t == SimWindow.TOUCH2_DOWN) pointersDown++;
    else if (t == SimWindow.UP || t == SimWindow.CANCEL || t == SimWindow.TOUCH2_UP) pointersDown = Math.max(0, pointersDown - 1);
    if (!input) return;
    long wall = System.currentTimeMillis();
    lastInputWall = wall;
    inputSinceCheckpoint++;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("t", uptime);
    m.put("w", wall);
    m.put("ev", t >= 0 && t < EVENTS.length && EVENTS[t] != null ? EVENTS[t] : "type-" + t);
    m.put("type", t);
    boolean typed = t == SimWindow.CHAR || (t == SimWindow.KEY && printableKey((int) e.value()));
    if (t == SimWindow.CHAR && e.y() == SimWindow.PASTED) {
      // Pasted text is the clipboard's, which stays on this Mac wherever it goes; a replay pastes a placeholder.
      m.put("pasted", true);
      m.put("redacted", true);
      redacted++;
    } else if (typed && passwordFocused()) {
      // What goes into a password field stays on this Mac; a replay types a placeholder there.
      m.put("redacted", true);
      redacted++;
    } else {
      m.put("x", e.x());
      m.put("y", e.y());
      m.put("v", e.value());
    }
    INPUT.add(wall, 96, m);
  }

  private static boolean printableKey(int code) {
    return (code >= android.view.KeyEvent.KEYCODE_0 && code <= android.view.KeyEvent.KEYCODE_9)
        || (code >= android.view.KeyEvent.KEYCODE_A && code <= android.view.KeyEvent.KEYCODE_Z)
        || (code >= android.view.KeyEvent.KEYCODE_COMMA && code <= android.view.KeyEvent.KEYCODE_AT && code != android.view.KeyEvent.KEYCODE_TAB
            && code != android.view.KeyEvent.KEYCODE_ENTER && code != android.view.KeyEvent.KEYCODE_DEL)
        || code == android.view.KeyEvent.KEYCODE_PLUS || code == android.view.KeyEvent.KEYCODE_STAR
        || code == android.view.KeyEvent.KEYCODE_POUND || code == android.view.KeyEvent.KEYCODE_SPACE
        || (code >= android.view.KeyEvent.KEYCODE_NUMPAD_0 && code <= android.view.KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN);
  }

  /** Device commands a replay must repeat: they change what the app sees. Input commands arrive as their events. */
  private static final Set<String> COMMANDS = Set.of("appearance", "locale", "fontscale", "device", "skin", "size", "window",
      "rotate", "animscale", "memory", "home", "resume", "lock", "unlock", "open", "push", "permission", "location",
      "biometrics", "recreate", "process-death", "keyboard", "clock", "timezone", "bluetooth", "notifications", "jobs",
      "pick", "clipboard", "paste", "bars", "thermal", "restart", "network", "ambient", "camera");

  /** A control command (or menu item) about to run (main thread): kept if a replay must repeat it. */
  static void commandRan(String[] a) {
    if (!input || a.length == 0 || !COMMANDS.contains(a[0])) return;
    if (a[0].equals("network") && (a.length == 1 || a[1].equals("status") || a[1].equals("unmatched") || a[1].equals("save"))) return;
    if (a[0].equals("ambient") && a.length == 1) return;  // the status query
    if ((a[0].equals("jobs") || a[0].equals("notifications")) && a.length == 1) return;  // listings
    List<String> args = new ArrayList<>(List.of(a));
    if (a[0].equals("clipboard") && a.length > 2 && a[1].equals("set")) {
      // The text stays here: a replay sets a placeholder of the same length.
      int n = String.join(" ", List.of(a).subList(2, a.length)).length();
      args = new ArrayList<>(List.of("clipboard", "set"));
      args.add("<" + n + " characters>");
      redacted++;
    }
    long wall = System.currentTimeMillis();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("t", SystemClock.uptimeMillis());
    m.put("w", wall);
    m.put("cmd", args);
    long size = 64;
    for (String x : args) size += 16 + 2L * x.length();  // a push payload or an open URL can be long
    INPUT.add(wall, size, m);
    inputSinceCheckpoint++;
  }

  /**
   * Whether the field that has the focus takes a password (main thread). Looked at for every typed character: focus
   * can move between two of them without a touch (a PIN field that moves on by itself after each digit).
   */
  private static boolean passwordFocused() {
    boolean pw = false;
    try {
      // The window the characters go to (Host.sendChar's), not merely the topmost one.
      View root = Host.keyTarget(Host.rootViews());
      View f = root == null ? null : root.findFocus();
      if (f != null) {
        if (f instanceof TextView tv) {
          pw = password(tv);
        } else if (f.getAccessibilityNodeProvider() != null) {
          // Compose: the focused editable node of the semantics tree. Asked for directly first (one node), else the
          // whole tree is walked.
          android.view.accessibility.AccessibilityNodeInfo n =
              f.getAccessibilityNodeProvider().findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
          if (n != null && n.isFocused()) {
            pw = n.isEditable() && n.isPassword();
          } else {
            boolean[] found = {false};
            Nodes.walk(List.of(f), k -> {
              if (k.info.isFocused() && k.info.isEditable() && k.info.isPassword()) found[0] = true;
            });
            pw = found[0];
          }
        }
      }
    } catch (RuntimeException e) {
      pw = true;  // could not tell: leave it out
    }
    return pw;
  }

  private static boolean password(TextView tv) {
    if (tv.getTransformationMethod() instanceof PasswordTransformationMethod) return true;
    int type = tv.getInputType();
    int cls = type & InputType.TYPE_MASK_CLASS;
    int variation = type & InputType.TYPE_MASK_VARIATION;
    return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
  }

  // ---------------------------------------------------------------- screen

  private static final Object FRAME_LOCK = new Object();
  private static byte[] pending;  // raw BGRA pixels waiting for the encoder, guarded by FRAME_LOCK
  private static int pendingW;
  private static int pendingH;
  private static long pendingWall;
  private static volatile long lastFrameNanos;
  /**
   * When the newest presented frame was passed over for the rate limit (0: none since the last one kept). A screen that
   * goes still right after such a frame presents no other, so its final state would never be kept: the recording then
   * showed a screen from before a change (a fetch's result drawn 150 ms after the frame kept for its button's ripple).
   */
  private static volatile long skippedNanos;
  private static Thread encoder;
  /** Moves on at `rec clear`: a frame taken before it is not kept after it. Guarded by FRAME_LOCK. */
  private static int screenGen;
  private static int pendingGen;

  /**
   * After `rec on screen` or `rec clear` (main thread): a still screen presents no frame, so the windows are drawn
   * again and the next one presented is kept whatever the frame rate limit says.
   */
  private static void screenBaseline() {
    lastFrameNanos = 0;
    try {
      for (View r : Host.rootViews()) r.invalidate();
    } catch (RuntimeException e) {
      // the next change draws one anyway
    }
  }

  /** From the raster thread: the frame just presented. Copied at most screenFps times a second. */
  static void frame(ByteBuffer pixels, int w, int h) {
    long now = System.nanoTime();
    if (pixels.remaining() != w * h * 4) return;
    if (now - lastFrameNanos < 1_000_000_000L / screenFps) {
      skippedNanos = now;
      return;
    }
    lastFrameNanos = now;
    skippedNanos = 0;
    synchronized (FRAME_LOCK) {
      if (pending == null || pending.length != pixels.remaining()) pending = new byte[pixels.remaining()];
      pixels.duplicate().get(pending);
      pendingW = w;
      pendingH = h;
      pendingWall = System.currentTimeMillis();
      pendingGen = screenGen;
      if (encoder == null) {
        encoder = new Thread(FlightRecorder::encodeLoop, "apksim-rec-screen");
        encoder.setDaemon(true);
        encoder.setPriority(Thread.MIN_PRIORITY);
        encoder.start();
      }
      FRAME_LOCK.notifyAll();
    }
  }

  private static void encodeLoop() {
    Bitmap src = null;
    Bitmap scaled = null;
    byte[] raw = null;
    Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1 << 18);
    while (true) {
      int w;
      int h;
      long wall;
      int gen;
      synchronized (FRAME_LOCK) {
        while (pendingWall == 0) {
          try {
            FRAME_LOCK.wait();
          } catch (InterruptedException e) {
            return;
          }
        }
        if (raw == null || raw.length != pending.length) raw = new byte[pending.length];
        System.arraycopy(pending, 0, raw, 0, pending.length);
        w = pendingW;
        h = pendingH;
        wall = pendingWall;
        gen = pendingGen;
        pendingWall = 0;
      }
      try {
        if (src == null || src.getWidth() != w || src.getHeight() != h) {
          if (src != null) src.recycle();
          src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        src.copyPixelsFromBuffer(ByteBuffer.wrap(raw));
        float s = Math.min(1f, screenMax / (float) Math.max(w, h));
        int sw = Math.max(2, Math.round(w * s) & ~1);
        int sh = Math.max(2, Math.round(h * s) & ~1);
        if (scaled == null || scaled.getWidth() != sw || scaled.getHeight() != sh) {
          if (scaled != null) scaled.recycle();
          scaled = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        }
        new Canvas(scaled).drawBitmap(src, null, new Rect(0, 0, sw, sh), filter);
        jpeg.reset();
        jpeg.write(ByteBuffer.allocate(8).putInt(sw).putInt(sh).array());
        scaled.compress(Bitmap.CompressFormat.JPEG, SCREEN_QUALITY, jpeg);
        byte[] f = jpeg.toByteArray();
        synchronized (FRAME_LOCK) {
          if (gen == screenGen) SCREEN.add(wall, f.length, f);
        }
      } catch (RuntimeException | IOException e) {
        System.err.println("[apksim] rec: screen frame not kept: " + e);
      }
    }
  }

  // ---------------------------------------------------------------- checkpoints

  /** One checkpoint: where its files are and when (both clocks). */
  record Checkpoint(int id, long wallMs, long uptimeMs, String why, Path dir, List<String> activities, List<String> notes,
      Map<String, Object> device) {}

  private static final List<Checkpoint> checkpoints = new ArrayList<>();  // oldest first; guarded by itself
  private static int nextId = 1;
  private static boolean wantCheckpoint;
  private static long lastCheckpointWall;
  /**
   * How many saves in progress are reading each checkpoint (by id), and the checkpoints already let go (aged out or
   * cleared) whose files wait for their last reader. Both guarded by `checkpoints`.
   */
  private static final Map<Integer, Integer> readers = new java.util.HashMap<>();
  private static final Map<Integer, Checkpoint> doomed = new java.util.HashMap<>();

  /** Of checkpoints being let go (caller holds `checkpoints`): those to delete now; the rest wait for their readers. */
  private static List<Checkpoint> retire(List<Checkpoint> gone) {
    List<Checkpoint> now = new ArrayList<>();
    for (Checkpoint c : gone) {
      if (readers.containsKey(c.id)) doomed.put(c.id, c);
      else now.add(c);
    }
    return now;
  }

  /** A save is done reading `c`: deleted if it was let go meanwhile and this was its last reader. */
  private static void unpin(Checkpoint c) {
    Checkpoint delete = null;
    synchronized (checkpoints) {
      int n = readers.merge(c.id, -1, Integer::sum);
      if (n <= 0) {
        readers.remove(c.id);
        delete = doomed.remove(c.id);
      }
    }
    if (delete != null) Storage.deleteTree(delete.dir);
  }

  /** Each turn of the Host loop (main thread). */
  void tick(boolean firstFrameDrawn) {
    long skipped = skippedNanos;
    if (screen && skipped != 0 && System.nanoTime() - skipped >= 1_000_000_000L / screenFps) {
      // The screen has been still since a frame the rate limit passed over: drawn again, so its final state is kept.
      skippedNanos = 0;
      screenBaseline();
    }
    long now = System.currentTimeMillis();
    moveCutoff(now);
    if (!input || !firstFrameDrawn || stack.isEmpty()) return;
    boolean due = wantCheckpoint
        || (checkpointEveryMs > 0 && inputSinceCheckpoint > 0 && now - lastCheckpointWall >= checkpointEveryMs);
    // Never in the middle of a gesture or right after input: a checkpoint waits for a second of quiet.
    if (due && pointersDown == 0 && now - lastInputWall >= 1000 && !Host.inputQueued()) {
      wantCheckpoint = false;
      String r = checkpoint(checkpoints.isEmpty() ? "first" : "periodic");
      if (r.startsWith("error")) System.err.println("[apksim] rec: " + r);
    }
  }

  /** The rings keep the window, and back to the newest checkpoint at or before its start; older checkpoints go. */
  private static void moveCutoff(long now) {
    long from = now - windowMs;
    long keep = from;
    List<Checkpoint> gone;
    synchronized (checkpoints) {
      int base = -1;
      for (int i = 0; i < checkpoints.size(); i++) if (checkpoints.get(i).wallMs <= from) base = i;
      if (base >= 0) keep = checkpoints.get(base).wallMs;
      List<Checkpoint> old = new ArrayList<>(checkpoints.subList(0, Math.max(0, base)));
      checkpoints.subList(0, Math.max(0, base)).clear();
      gone = retire(old);
    }
    Rec.keepFrom(keep);
    LOGCAT.trim(keep);
    Rec.NET.trim(keep);
    for (Checkpoint c : gone) Storage.deleteTree(c.dir);
  }

  /**
   * Takes a checkpoint now (main thread): the stack's intents and saved states, and a clone of the app's data folder.
   * The visible activities go to stopped and back, as when the user leaves the app for a moment.
   */
  String checkpoint(String why) {
    if (stack.isEmpty()) return "error: no activity to checkpoint";
    if (pointersDown > 0) return "error: a touch is down; try again after it";
    long t0 = System.nanoTime();
    long wall = System.currentTimeMillis();
    long uptime = SystemClock.uptimeMillis();
    int id;
    synchronized (checkpoints) {
      id = nextId++;
    }
    Path dir = Storage.scratch().resolve("rec").resolve("cp-" + id);
    List<String> notes = new ArrayList<>();
    List<String> names = new ArrayList<>();
    List<ActivityController<? extends Activity>> topFirst = new ArrayList<>(stack);
    List<ActivityController<? extends Activity>> bottomFirst = new ArrayList<>(topFirst);
    java.util.Collections.reverse(bottomFirst);
    List<byte[]> intents = new ArrayList<>();
    List<byte[]> states = new ArrayList<>();
    try {
      Files.createDirectories(dir);
      int[] before = new int[topFirst.size()];
      for (int i = 0; i < topFirst.size(); i++) before[i] = Host.state(topFirst.get(i).get());
      Crashes.app(() -> {
        try {
          // Down first (top first, as a device pauses and stops), every one saves (bottom first), then back up.
          for (ActivityController<? extends Activity> c : topFirst) Host.moveTo(c, 0);
          for (ActivityController<? extends Activity> c : bottomFirst) {
            Activity act = c.get();
            names.add(act.getClass().getName());
            intents.add(marshall(p -> act.getIntent().writeToParcel(p, 0), act.getClass().getName() + "'s intent", notes));
            Bundle b = new Bundle();
            c.saveInstanceState(b);
            states.add(marshall(p -> p.writeBundle(b), act.getClass().getName() + "'s state", notes));
          }
        } finally {
          for (int i = topFirst.size() - 1; i >= 0; i--) Host.moveTo(topFirst.get(i), before[i]);
        }
      });
      ActivityController<? extends Activity> top = stack.peek();
      if (top != null && top.get().getWindow() != null) {
        View decor = top.get().getWindow().peekDecorView();
        if (decor != null) decor.invalidate();
      }
      long stateMs = (System.nanoTime() - t0) / 1_000_000;
      try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("state.bin")))) {
        out.writeInt(names.size());
        for (int i = 0; i < names.size(); i++) {
          out.writeUTF(names.get(i));
          writeBytes(out, intents.get(i));
          writeBytes(out, states.get(i));
        }
      }
      Path data = Host.dataDir();
      if (data != null) {
        // The databases are held in a read transaction while the folder is cloned, so each is cloned as of one
        // commit even with a worker writing or checkpointing its WAL meanwhile (see holdDatabases).
        SlotDevice.flush();  // Settings values changed in the last 250 ms (or by the lifecycle above) are on disk
        List<Path> live = openDatabases(data);
        Map<Path, android.database.sqlite.SQLiteDatabase> held = holdDatabases(data, live, notes);
        try {
          cloneTree(data, dir.resolve("slot"), live, notes);
          Path device = data.resolveSibling(data.getFileName() + ".device");
          if (Files.isDirectory(device)) cloneTree(device, dir.resolve("slot.device"), List.of(), notes);
        } finally {
          release(held.values());
        }
        dropLockFiles(data, dir.resolve("slot"), held.keySet());
      } else {
        notes.add("no data folder");
      }
      Map<String, Object> device = device();
      Files.writeString(dir.resolve("device.json"), JsonLine.write(device) + "\n");
      Checkpoint c = new Checkpoint(id, wall, uptime, why, dir, names, notes, device);
      synchronized (checkpoints) {
        checkpoints.add(c);
      }
      lastCheckpointWall = wall;
      inputSinceCheckpoint = 0;
      long ms = (System.nanoTime() - t0) / 1_000_000;
      String r = "checkpoint #" + id + " (" + why + ", " + names.size() + " activit" + (names.size() == 1 ? "y" : "ies")
          + ", state " + stateMs + " ms, all " + ms + " ms" + (notes.isEmpty() ? "" : "; " + String.join("; ", notes)) + ")";
      System.err.println("[apksim] rec: " + r);
      return r;
    } catch (IOException | RuntimeException e) {
      if (e instanceof Crashes.AppCrash crash) throw crash;
      Storage.deleteTree(dir);
      return "error: checkpoint failed: " + e;
    }
  }

  /**
   * Reads a checkpoint's state back as a replay will (`rec checkpoint verify [ID]`, the newest by default): each
   * intent and Bundle unparcelled with the app's class loader, and every value in the Bundle read, so a Parcelable
   * the app cannot recreate shows here and not at replay time.
   */
  private String verify(int id) throws IOException {
    Checkpoint c = null;
    synchronized (checkpoints) {
      for (Checkpoint k : checkpoints) if (id < 0 || k.id == id) c = k;
    }
    if (c == null) return "error: no checkpoint " + (id < 0 ? "yet" : "#" + id);
    ClassLoader loader = app.getClassLoader();
    List<String> out = new ArrayList<>();
    try (java.io.DataInputStream in = new java.io.DataInputStream(Files.newInputStream(c.dir.resolve("state.bin")))) {
      int n = in.readInt();
      for (int i = 0; i < n; i++) {
        String name = in.readUTF();
        byte[] intent = readBytes(in);
        byte[] state = readBytes(in);
        String what = name.substring(name.lastIndexOf('.') + 1);
        if (intent != null) {
          Parcel p = Parcel.obtain();
          try {
            p.unmarshall(intent, 0, intent.length);
            p.setDataPosition(0);
            android.content.Intent restored = android.content.Intent.CREATOR.createFromParcel(p);
            restored.setExtrasClassLoader(loader);
            Bundle extras = restored.getExtras();
            int values = extras == null ? 0 : readAll(extras, loader);
            what += " intent " + (restored.getComponent() != null ? restored.getComponent().getShortClassName() : restored.getAction())
                + (values > 0 ? " (" + values + " extras)" : "");
          } finally {
            p.recycle();
          }
        }
        if (state != null) {
          Parcel p = Parcel.obtain();
          try {
            p.unmarshall(state, 0, state.length);
            p.setDataPosition(0);
            Bundle b = p.readBundle(loader);
            int values = readAll(b, loader);
            what += ", state " + b.size() + " keys (" + values + " values), " + state.length + " bytes";
          } finally {
            p.recycle();
          }
        }
        out.add(what);
      }
    }
    long files;
    try (var w = Files.walk(c.dir.resolve("slot"))) {
      files = w.filter(Files::isRegularFile).count();
    } catch (IOException e) {
      files = 0;
    }
    return "checkpoint #" + c.id + " ok: " + String.join("; ", out) + "; data " + files + " files";
  }

  /**
   * The databases under the data folder the app has open through the framework's SQLite
   * (SQLiteDatabase.getActiveDatabases): the ones whose files hold its locks. Found without opening a file: closing any
   * descriptor of a file drops every POSIX lock the process holds on it, SQLite's included. Empty for an app with its
   * own SQLite library (Natives.ownSqlite), whose databases the framework does not see.
   */
  @SuppressWarnings("unchecked")
  private static List<Path> openDatabases(Path data) {
    List<Path> out = new ArrayList<>();
    try {
      // Real paths (no file opened): the app reaches its data folder through a link (Host's <pkg>-dataDir).
      Path base = data.toRealPath();
      java.lang.reflect.Method m = android.database.sqlite.SQLiteDatabase.class.getDeclaredMethod("getActiveDatabases");
      m.setAccessible(true);
      for (android.database.sqlite.SQLiteDatabase db : (List<android.database.sqlite.SQLiteDatabase>) m.invoke(null)) {
        String path = db.getPath();
        if (path == null || !db.isOpen()) continue;
        Path p;
        try {
          p = Path.of(path).toRealPath();
        } catch (IOException e) {
          continue;  // deleted meanwhile
        }
        if (p.startsWith(base) && !out.contains(p)) out.add(p);
      }
    } catch (ReflectiveOperationException | RuntimeException | IOException e) {
      // none known: every file is cloned as a file
    }
    return out;
  }

  /**
   * Opens each database the app has open a second time, read-only through the framework's SQLite (which shares one
   * set of locks per file between its connections), and starts a read transaction on it, for the time the folder is
   * cloned. Each database file and its WAL then hold one commit's state between them: while a reader holds a
   * snapshot, a WAL checkpoint copies no frame past it into the database file and the WAL is not restarted, so the WAL
   * only grows; a rollback-journal database cannot be written at all. Recovery on open (the -shm file is left out)
   * replays the WAL clone's committed frames. Databases of an app with its own SQLite library are cloned as files, as
   * are those that do not open.
   */
  private static Map<Path, android.database.sqlite.SQLiteDatabase> holdDatabases(Path data, List<Path> live, List<String> notes) {
    Map<Path, android.database.sqlite.SQLiteDatabase> held = new LinkedHashMap<>();
    if (dev.apksim.Natives.ownSqlite) {
      notes.add("databases cloned as files (the app has its own SQLite library)");
      return held;
    }
    for (Path p : live) {
      android.database.sqlite.SQLiteDatabase db = null;
      try {
        db = android.database.sqlite.SQLiteDatabase.openDatabase(p.toString(), null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        db.beginTransactionReadOnly();
        try (android.database.Cursor c = db.rawQuery("SELECT count(*) FROM sqlite_master", null)) {
          c.moveToFirst();  // the first read takes the snapshot
        }
        held.put(p, db);
      } catch (RuntimeException | LinkageError e) {
        if (db != null) release(List.of(db));
        notes.add(p.getFileName() + " cloned as a file: " + e.getMessage());
      }
    }
    return held;
  }

  private static void release(java.util.Collection<android.database.sqlite.SQLiteDatabase> held) {
    for (android.database.sqlite.SQLiteDatabase db : held) {
      try {
        if (db.inTransaction()) db.endTransaction();
      } catch (RuntimeException e) {
        // closed below either way
      }
      try {
        db.close();
      } catch (RuntimeException e) {
        // nothing more to do
      }
    }
  }

  /**
   * For each database held while cloning, its clone's -shm file (a WAL index of the live connections, rebuilt from the
   * WAL on open) and -journal file (an unfinished write the read transaction kept out of the database file) would only
   * mislead the next open. A database cloned as a plain file keeps both: its journal may be needed to roll back.
   */
  private static void dropLockFiles(Path data, Path slot, java.util.Set<Path> held) {
    for (Path db : held) {
      try {
        Path copy = slot.resolve(data.toRealPath().relativize(db).toString());
        Files.deleteIfExists(copy.resolveSibling(copy.getFileName() + "-shm"));
        Files.deleteIfExists(copy.resolveSibling(copy.getFileName() + "-journal"));
      } catch (IOException e) {
        // left as they are
      }
    }
  }

  private static byte[] readBytes(java.io.DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0) return null;
    byte[] b = new byte[n];
    in.readFully(b);
    return b;
  }

  private interface ParcelWriter {
    void write(Parcel p);
  }

  /**
   * Reads every value of a Bundle, and of the Bundles, arrays and lists in it, with the app's class loader: values
   * stay parcelled until read, so a Parcelable the app cannot recreate fails only here. Returns how many were read.
   */
  @SuppressWarnings("deprecation")
  private static int readAll(Bundle b, ClassLoader loader) {
    b.setClassLoader(loader);
    int n = 0;
    for (String k : new ArrayList<>(b.keySet())) n += 1 + readValue(b.get(k), loader);
    return n;
  }

  private static int readValue(Object v, ClassLoader loader) {
    int n = 0;
    if (v instanceof Bundle b) {
      n += readAll(b, loader);
    } else if (v instanceof android.os.PersistableBundle pb) {
      for (String k : pb.keySet()) n += 1 + readValue(pb.get(k), loader);
    } else if (v instanceof Object[] arr) {
      for (Object o : arr) n += readValue(o, loader);
    } else if (v instanceof Iterable<?> it) {
      for (Object o : it) n += readValue(o, loader);
    } else if (v instanceof android.util.SparseArray<?> sa) {
      for (int i = 0; i < sa.size(); i++) n += readValue(sa.valueAt(i), loader);
    }
    return n;
  }

  /** A parcelable's bytes, or null (noted) when it cannot be flattened: a Binder in a Bundle, say. */
  private static byte[] marshall(ParcelWriter w, String what, List<String> notes) {
    Parcel p = Parcel.obtain();
    try {
      w.write(p);
      return p.marshall();
    } catch (RuntimeException e) {
      notes.add(what + " not saved: " + e);
      return null;
    } finally {
      p.recycle();
    }
  }

  private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
    out.writeInt(b == null ? -1 : b.length);
    if (b != null) out.write(b);
  }

  // A folder cloned (APFS: copy-on-write, no bytes copied), or copied where that is not possible (Linux, other volumes).
  private static final MethodHandle COPYFILE = copyfile();

  private static MethodHandle copyfile() {
    if (!HostOs.MAC) return null;
    try {
      Linker l = Linker.nativeLinker();
      return l.defaultLookup().find("copyfile").map(s -> l.downcallHandle(s, FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static final int COPYFILE_ALL = 15;
  private static final int COPYFILE_RECURSIVE = 1 << 15;
  private static final int COPYFILE_NOFOLLOW_SRC = 1 << 18;
  private static final int COPYFILE_CLONE = 1 << 24;

  /**
   * Clones `from` to `to`. APFS: copy-on-write (clonefile, which opens no descriptor, so the app's locks stay; checked
   * against a held fcntl lock). Elsewhere (Linux, other volumes) a `cp` process copies it: copying here would open and
   * close the app's open databases' files and drop its POSIX locks on them. Without cp, a copy here that leaves out
   * the files of the open databases in `live`, noted.
   */
  static void cloneTree(Path from, Path to, List<Path> live, List<String> notes) throws IOException {
    if (COPYFILE != null) {
      try (Arena a = Arena.ofConfined()) {
        int r = (int) COPYFILE.invokeExact(a.allocateFrom(from.toString()), a.allocateFrom(to.toString()), MemorySegment.NULL,
            COPYFILE_CLONE | COPYFILE_RECURSIVE | COPYFILE_ALL | COPYFILE_NOFOLLOW_SRC);
        if (r == 0) return;
      } catch (Throwable t) {
        // copied below
      }
      Storage.deleteTree(to);
    }
    try {
      Files.createDirectories(to.getParent());
      Process p = new ProcessBuilder("cp", "-pR", from.toString(), to.toString()).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (p.waitFor() == 0) return;
    } catch (IOException e) {
      // no cp: below
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    Storage.deleteTree(to);
    java.util.Set<String> skip = new java.util.HashSet<>();
    for (Path db : live) for (String s : List.of("", "-wal", "-shm", "-journal")) skip.add(db + s);
    try (var walk = Files.walk(from)) {
      for (Path p : (Iterable<Path>) walk::iterator) {
        Path q = to.resolve(from.relativize(p).toString());
        if (Files.isSymbolicLink(p)) continue;
        if (Files.isDirectory(p)) {
          Files.createDirectories(q);
        } else if (!skip.isEmpty() && skip.contains(p.toRealPath().toString())) {
          notes.add(from.relativize(p) + " left out (open, and no cp to copy it without dropping the app's locks)");
        } else if (Files.isRegularFile(p)) {
          Files.copy(p, q, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  // ---------------------------------------------------------------- saving

  private static volatile String lastSave;

  /**
   * Saves the last `seconds` (main thread gathers, a worker writes) to FILE, else work/screenshots: a zip with manifest.json, input.jsonl, logcat.txt,
   * network.har, db.jsonl, screen.avi, and checkpoint/ (state.bin, stack.json, data.zip in SlotPack's format) for
   * the newest checkpoint at or before the start. Answers at once with the file; `rec status` says when it is written.
   */
  String save(long seconds, String file) throws Exception {
    return save(seconds, file, null);
  }

  private static volatile FlightRecorder current;

  /**
   * The app crashed and the process is about to end (Crashes.terminate, any thread): the window is saved now, in the
   * calling thread, as `<package>-<time>-crash.apkrec`, with the exception in its manifest. Before the scratch folder
   * (and the checkpoints in it) is deleted. Does nothing when no channel is on.
   */
  static void crashed(Throwable e) {
    FlightRecorder r = current;
    java.io.StringWriter w = new java.io.StringWriter();
    if (e != null) e.printStackTrace(new java.io.PrintWriter(w));
    if (r == null) {
      crashedEarly(w.toString());
      return;
    }
    if (!(anyOn() || anyKept())) return;
    try {
      synchronized (checkpoints) {
        r.deviceNow = checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1).device;
      }
      if (r.deviceNow == null) r.deviceNow = Map.of();
      System.err.println("[apksim] rec: the app crashed: " + r.save(windowMs / 1000, null, w.toString()));
    } catch (Throwable t) {
      System.err.println("[apksim] rec: could not save the crash: " + t);
    }
    awaitSaves(30_000);  // a `rec save` from before the crash, still writing
  }

  /**
   * A crash before the Host and its recorder exist (the Application's constructor, attach or onCreate): what the net
   * and db channels, on since launch (Rec.launch), have, and the exception, without anything of the app's (it may
   * not exist): no checkpoint, input or screen.
   */
  private static void crashedEarly(String crash) {
    if (!(Rec.db || Rec.NET.isOn()) && Rec.DB.size() == 0) return;
    try {
      long to = System.currentTimeMillis();
      String pkg = String.valueOf(System.getProperty("apksim.package", "app"));
      Path out = reserve(DeviceControls.mediaDir(), pkg + "-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date(to)) + "-crash");
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("format", FORMAT);
      m.put("created", java.time.Instant.ofEpochMilli(to).toString());
      m.put("package", pkg);
      String apk = System.getProperty("apksim.apk");
      if (apk != null) m.put("apk", Path.of(apk).getFileName().toString());
      Map<String, Object> window = new LinkedHashMap<>();
      window.put("from", Long.MIN_VALUE);
      window.put("to", to);
      m.put("window", window);
      List<String> on = new ArrayList<>();
      if (Rec.NET.isOn()) on.add("net");
      if (Rec.db) on.add(Rec.dbValues ? "db+values" : "db");
      m.put("channels", on);
      m.put("notes", List.of("the app crashed before its first activity: no checkpoint, input or screen"));
      m.put("crash", crash);
      Net.drain(2000);
      try {
        write(out, m, List.of(), List.of(), Net.captured(Rec.NET::contains), Rec.DB.since(Long.MIN_VALUE), List.of(), 0, to, null, pkg);
      } catch (IOException | RuntimeException x) {
        Files.deleteIfExists(out);
        throw x;
      }
      System.err.println("[apksim] rec: the app crashed during startup: saved " + out);
    } catch (Throwable t) {
      System.err.println("[apksim] rec: could not save the crash: " + t);
    }
  }

  /**
   * A new default bundle name in `dir` for `stem`, created empty so no other save, in this process or another one
   * sharing the folder, takes it; -2, -3... when taken. The save then replaces it.
   */
  private static Path reserve(Path dir, String stem) throws IOException {
    Files.createDirectories(dir);
    for (int n = 1; ; n++) {
      Path out = dir.resolve(stem + (n == 1 ? "" : "-" + n) + ".apkrec");
      synchronized (SAVES) {
        if (writing.contains(out)) continue;
      }
      try {
        Files.createFile(out);
        return out;
      } catch (java.nio.file.FileAlreadyExistsException e) {
        // taken: the next
      }
    }
  }

  private static boolean anyOn() {
    return input || logcat || net || Rec.db || screen;
  }

  /** Whether any channel still holds something from the rings' cutoff on, on or off now. */
  private static boolean anyKept() {
    long k = Rec.keepFromWallMs();
    return INPUT.size() > 0 || SCREEN.size() > 0 || Rec.DB.size() > 0 || (LOGCAT.anySince(k) && AdbLog.installed())
        || (Rec.NET.anySince(k) && Net.capturedCount() > 0);
  }

  // Saves being written, and the files they write: the process waits for them on the way out (Host.exit,
  // Crashes.terminate), and no two write one file. Guarded by SAVES.
  private static final Object SAVES = new Object();
  private static final Set<Path> writing = new java.util.HashSet<>();
  /** Default names reserved as empty files (reserve), deleted again if their save fails. */
  private static final Set<Path> placeholders = new java.util.HashSet<>();

  /** A save of `target` is over; `ok`: it was written. */
  private static void done(Path target, boolean ok) {
    boolean placeholder;
    synchronized (SAVES) {
      placeholder = placeholders.remove(target);
      writing.remove(target);
      SAVES.notifyAll();
    }
    if (placeholder && !ok) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException e) {
        // an empty file left over
      }
    }
  }

  /** Waits up to `ms` for the saves being written (Host.exit, before the scratch folder and its checkpoints go). */
  static void awaitSaves(long ms) {
    long end = System.currentTimeMillis() + ms;
    synchronized (SAVES) {
      while (!writing.isEmpty()) {
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
          System.err.println("[apksim] rec: gave up waiting for " + writing);
          return;
        }
        try {
          SAVES.wait(left);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }

  /** `crash`: the stack trace of a crash being saved (synchronously, in this thread), else null (a worker writes). */
  private String save(long seconds, String file, String crash) throws Exception {
    if (!anyOn() && !anyKept()) {
      return "error: the flight recorder is off: rec on [CHANNEL...], or launch with --rec all";
    }
    long to = System.currentTimeMillis();
    long from = to - Math.max(1, seconds) * 1000;
    Path out;
    if (file != null) {
      out = Storage.absolute(file);
      synchronized (SAVES) {
        if (writing.contains(out)) return "error: " + out + " is being saved already";
        writing.add(out);
      }
    } else {
      // With screenshots and screen recordings (File > Show Screenshots and Recordings).
      out = reserve(DeviceControls.mediaDir(), app.getPackageName() + "-"
          + new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date(to)) + (crash != null ? "-crash" : ""));
      synchronized (SAVES) {
        writing.add(out);
        placeholders.add(out);
      }
    }
    Checkpoint cp = null;
    synchronized (checkpoints) {
      for (Checkpoint c : checkpoints) if (c.wallMs <= from) cp = c;
      if (cp == null && !checkpoints.isEmpty()) cp = checkpoints.get(0);  // none that early: the oldest there is
      if (cp != null) readers.merge(cp.id, 1, Integer::sum);
    }
    long start = cp != null ? Math.min(from, cp.wallMs) : from;
    boolean[] started = {false};
    try {
      return save(out, from, to, start, cp, crash, started);
    } catch (Exception | Error e) {
      if (started[0]) throw e;  // the job released them
      // Nothing is writing it after all.
      if (cp != null) unpin(cp);
      done(out, false);
      throw e;
    }
  }

  /** The rest of save: `out` is reserved and `cp` (if any) pinned; the job written releases both. */
  private String save(Path out, long from, long to, long start, Checkpoint cp, String crash, boolean[] started)
      throws IOException, InterruptedException {
    // Gathered here, on the main thread: what the app and the device look like now.
    Map<String, Object> manifest = manifest(from, to, start, cp);
    if (crash != null) manifest.put("crash", crash);
    List<Ring.Item<Map<String, Object>>> events = INPUT.since(cp != null ? cp.wallMs : start);
    // Logcat and net are kept by something that goes on with the channel off (adb's log, a --network record file):
    // only what came while the channel was on, and after the last `rec clear`.
    List<AdbLog.Line> lines = new ArrayList<>();
    if (AdbLog.installed()) {
      for (AdbLog.Line l : AdbLog.since(0, 0).lines()) if (l.wallMs() >= start && LOGCAT.contains(l.wallMs())) lines.add(l);
    }
    if (crash != null) Net.drain(2000);  // answers the app already has, being recorded, belong in the crash's HAR
    java.util.function.Supplier<byte[]> har = Net.captured(w -> w >= start && Rec.NET.contains(w));
    List<Ring.Item<Map<String, Object>>> db = Rec.DB.since(start);
    List<Ring.Item<byte[]>> frames = new ArrayList<>();
    Ring.Item<byte[]> first = SCREEN.atOrBefore(from);
    if (first != null) frames.add(first);
    for (Ring.Item<byte[]> f : SCREEN.since(from)) if (f != first) frames.add(f);
    Checkpoint used = cp;
    Path target = out;
    String pkg = app.getPackageName();
    lastSave = target + ": writing";
    Runnable job = () -> {
      long t0 = System.nanoTime();
      boolean ok = false;
      try {
        write(target, manifest, events, lines, har, db, frames, from, to, used, pkg);
        ok = true;
        long size = Files.size(target);
        lastSave = target + ": " + (size >> 10) + " KB in " + (System.nanoTime() - t0) / 1_000_000 + " ms";
        System.err.println("[apksim] rec: saved " + lastSave);
      } catch (IOException | RuntimeException e) {
        lastSave = target + ": failed: " + e;
        System.err.println("[apksim] rec: save failed: " + e);
      } finally {
        if (used != null) unpin(used);
        done(target, ok);
      }
    };
    started[0] = true;
    if (crash != null) {
      job.run();
      return lastSave;
    }
    Thread t = new Thread(job, "apksim-rec-save");
    t.setDaemon(true);  // Host.exit waits for it (awaitSaves): Standby.halt would not wait for any thread
    t.start();
    return "saving " + target + " (" + (to - start) / 1000 + " s" + (used == null ? ", no checkpoint" : ", from checkpoint #" + used.id) + ")";
  }

  /**
   * The device as the app sees it now: display size and density, Robolectric's qualifier string (size, density,
   * orientation, night mode, locale: what a replay sets first), and the `config` command's text. Main thread.
   */
  private Map<String, Object> device() {
    Map<String, Object> device = new LinkedHashMap<>();
    android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
    device.put("width", dm.widthPixels);
    device.put("height", dm.heightPixels);
    device.put("density", dm.density);
    device.put("fontScale", app.getResources().getConfiguration().fontScale);
    // What `apksim replay` launches with (docs/replay.md): the device preset and its rotation, the zone, the animator scale.
    Device d = Device.current();
    device.put("name", d.name());
    device.put("widthDp", d.widthDp());
    device.put("heightDp", d.heightDp());
    try {
      device.put("rotation", app.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation());
    } catch (RuntimeException e) {
      // without it
    }
    device.put("timezone", java.util.TimeZone.getDefault().getID());
    // The wall clock the app sees (SimClock: --clock, `clock`), which the checkpoint's own wall time (real) is not.
    device.put("clock", SimClock.currentTimeMillis());
    device.put("animScale", android.animation.ValueAnimator.getDurationScale());
    String features = System.getProperty("apksim.features");  // --feature: part of the device the app saw
    if (features != null && !features.isEmpty()) device.put("features", List.of(features.split(",")));
    try {
      device.put("qualifiers", org.robolectric.RuntimeEnvironment.getQualifiers());
    } catch (RuntimeException e) {
      // without them
    }
    try {
      String config = controls.run(new String[] {"config"});
      if (config != null) device.put("config", config);
    } catch (Exception e) {
      // without it
    }
    return device;
  }

  /** Set while a crash saves: the device read off the main thread would race it, so the last checkpoint's is used. */
  private Map<String, Object> deviceNow;

  private Map<String, Object> manifest(long from, long to, long start, Checkpoint cp) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("format", FORMAT);
    m.put("created", java.time.Instant.ofEpochMilli(to).toString());
    m.put("package", app.getPackageName());
    try {
      android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
      m.put("versionName", pi.versionName);
      m.put("versionCode", pi.getLongVersionCode());
    } catch (Exception e) {
      // not known
    }
    String apk = System.getProperty("apksim.apk");
    if (apk != null) {
      m.put("apk", Path.of(apk).getFileName().toString());
      m.put("apkPath", Path.of(apk).toAbsolutePath().toString());  // where `apksim replay` looks for it first
    }
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("from", from);
    window.put("to", to);
    window.put("seconds", (to - from) / 1000);
    window.put("uptimeAtSave", SystemClock.uptimeMillis());
    window.put("keptFrom", start);
    m.put("window", window);
    m.put("device", deviceNow != null ? deviceNow : device());
    List<String> on = new ArrayList<>();
    if (input) on.add("input");
    if (logcat) on.add("logcat");
    if (net) on.add("net");
    if (Rec.db) on.add(Rec.dbValues ? "db+values" : "db");
    if (screen) on.add("screen");
    m.put("channels", on);
    if (cp != null) {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("id", cp.id);
      c.put("wall", cp.wallMs);
      c.put("uptime", cp.uptimeMs);
      c.put("why", cp.why);
      c.put("activities", cp.activities);
      c.put("device", cp.device);
      if (!cp.notes.isEmpty()) c.put("notes", cp.notes);
      m.put("checkpoint", c);
    }
    List<String> notes = new ArrayList<>();
    if (cp == null) notes.add("no checkpoint: this recording cannot be replayed, only read");
    else if (cp.wallMs > from) notes.add("the oldest checkpoint is after the window's start: a replay starts " + (cp.wallMs - from) / 1000 + " s in");
    if (redacted > 0) notes.add(redacted + " typed, pasted or clipboard input" + (redacted == 1 ? " was" : "s were") + " left out (password fields, pasted and clipboard text)");
    if (INPUT.dropped() > 0) notes.add(INPUT.dropped() + " input events dropped for the memory budget");
    if (SCREEN.dropped() > 0) notes.add(SCREEN.dropped() + " screen frames dropped for the memory budget");
    if (Rec.DB.dropped() > 0) notes.add(Rec.DB.dropped() + " db calls dropped for the memory budget");
    m.put("notes", notes);
    return m;
  }

  private static void write(Path target, Map<String, Object> manifest, List<Ring.Item<Map<String, Object>>> events,
      List<AdbLog.Line> lines, java.util.function.Supplier<byte[]> har, List<Ring.Item<Map<String, Object>>> db, List<Ring.Item<byte[]>> frames,
      long from, long to, Checkpoint cp, String pkg) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    // Each save its own part file, moved into place once complete.
    // A random tag as well as the PID: containers sharing an output folder can all be PID 1.
    Path tmp = target.resolveSibling(target.getFileName() + Storage.tmpTag());
    boolean done = false;
    try (OutputStream fo = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(fo)) {
      Map<String, Object> counts = new LinkedHashMap<>();
      counts.put("input", events.size());
      counts.put("logcat", lines.size());
      counts.put("db", db.size());
      counts.put("screenFrames", frames.size());
      String apk = System.getProperty("apksim.apk");
      if (apk != null) {
        try {
          manifest.put("apkSha256", sha256(Path.of(apk)));
        } catch (IOException e) {
          // without it
        }
        // Who signed it: `apksim replay --apk` takes another build only from the same signer, as an update keeping the
        // app's data must be (ApkSigners, as apksim beam's data from another build).
        String signers = signers(Path.of(apk));
        if (signers != null) manifest.put("apkSigners", signers);
      }
      StringBuilder in = new StringBuilder();
      for (Ring.Item<Map<String, Object>> e : events) in.append(JsonLine.write(e.value())).append('\n');
      put(z, "input.jsonl", in.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder log = new StringBuilder();
      for (AdbLog.Line l : lines) log.append(l.text()).append('\n');
      put(z, "logcat.txt", log.toString().getBytes(StandardCharsets.UTF_8));
      if (har != null) put(z, "network.har", har.get());
      StringBuilder d = new StringBuilder();
      for (Ring.Item<Map<String, Object>> e : db) d.append(JsonLine.write(e.value())).append('\n');
      if (!db.isEmpty()) put(z, "db.jsonl", d.toString().getBytes(StandardCharsets.UTF_8));
      if (!frames.isEmpty()) {
        // A constant-rate movie from the frames kept (each repeated until the next), from the window's start to the save.
        ByteBuffer size = ByteBuffer.wrap(frames.get(frames.size() - 1).value(), 0, 8);
        int w = size.getInt();
        int h = size.getInt();
        // Each kept frame becomes one JPEG, which every time slot it covers shares. A frame of another size (before a
        // rotation or a resize) is fitted into the movie's, so the movie keeps its length and its timing.
        java.util.IdentityHashMap<byte[], byte[]> jpegs = new java.util.IdentityHashMap<>();
        List<byte[]> movie = new ArrayList<>();
        int k = 0;
        long step = 1000 / screenFps;
        for (long t = Math.max(from, frames.get(0).wallMs()); t <= to; t += step) {
          while (k + 1 < frames.size() && frames.get(k + 1).wallMs() <= t) k++;
          movie.add(jpegs.computeIfAbsent(frames.get(k).value(), f -> fitted(f, w, h)));
        }
        z.putNextEntry(new ZipEntry("screen.avi"));
        MjpegRecorder.writeAvi(z, w, h, screenFps, movie);
        z.closeEntry();
        StringBuilder times = new StringBuilder();
        for (Ring.Item<byte[]> f : frames) times.append(f.wallMs()).append('\n');
        put(z, "screen-times.txt", times.toString().getBytes(StandardCharsets.UTF_8));
      }
      if (cp != null) {
        put(z, "checkpoint/state.bin", Files.readAllBytes(cp.dir.resolve("state.bin")));
        Map<String, Object> stack = new LinkedHashMap<>();
        stack.put("activities", cp.activities);
        put(z, "checkpoint/stack.json", (JsonLine.write(stack) + "\n").getBytes(StandardCharsets.UTF_8));
        if (Files.isDirectory(cp.dir.resolve("slot"))) {
          ZipEntry e = new ZipEntry("checkpoint/data.zip");
          z.putNextEntry(e);
          // SlotPack's format (what apksim beam moves an app's data in): the folder handles it reads through never
          // follow a link. It closes the stream it is given, so it gets one that does not close the bundle.
          dev.apksim.serve.SlotPack.pack(cp.dir.getParent(), cp.dir.resolve("slot"), pkg, new java.io.FilterOutputStream(z) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
              out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
              flush();
            }
          });
          z.closeEntry();
        }
      }
      manifest.put("counts", counts);
      put(z, "manifest.json", (JsonLine.write(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
      done = true;
    } finally {
      if (!done) Files.deleteIfExists(tmp);
    }
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** A screen ring frame's JPEG, re-encoded letterboxed into w x h when it was kept at another size. */
  private static byte[] fitted(byte[] f, int w, int h) {
    ByteBuffer size = ByteBuffer.wrap(f, 0, 8);
    int fw = size.getInt();
    int fh = size.getInt();
    if (fw == w && fh == h) return java.util.Arrays.copyOfRange(f, 8, f.length);
    Bitmap src = android.graphics.BitmapFactory.decodeByteArray(f, 8, f.length - 8);
    if (src == null) return java.util.Arrays.copyOfRange(f, 8, f.length);
    Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    try {
      Canvas c = new Canvas(dst);
      c.drawColor(android.graphics.Color.BLACK);
      float s = Math.min(w / (float) fw, h / (float) fh);
      int dw = Math.round(fw * s);
      int dh = Math.round(fh * s);
      c.drawBitmap(src, null, new Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh),
          new Paint(Paint.FILTER_BITMAP_FLAG));
      ByteArrayOutputStream out = new ByteArrayOutputStream(f.length);
      dst.compress(Bitmap.CompressFormat.JPEG, SCREEN_QUALITY, out);
      return out.toByteArray();
    } finally {
      src.recycle();
      dst.recycle();
    }
  }

  private static void put(ZipOutputStream z, String name, byte[] b) throws IOException {
    z.putNextEntry(new ZipEntry(name));
    z.write(b);
    z.closeEntry();
  }

  /** The last APK's signers, as one value: two fields could be read as a mismatched pair across threads. */
  private record SignersOf(String apk, String signers) {}

  private static volatile SignersOf signersCache;

  /** The APK's verified signers (scheme and certificate digests), once per APK and process; null if unsigned. */
  private static String signers(Path apk) {
    String key = apk.toAbsolutePath().toString();
    SignersOf cached = signersCache;
    if (cached != null && key.equals(cached.apk())) return cached.signers();
    String v;
    try {
      dev.apksim.serve.ApkSigners.Signers s = dev.apksim.serve.ApkSigners.of(apk);
      v = s.scheme() + ":" + String.join(",", s.certs());
    } catch (IOException | RuntimeException e) {
      v = null;
    }
    signersCache = new SignersOf(key, v);
    return v;
  }

  private static String sha256(Path p) throws IOException {
    try (InputStream in = Files.newInputStream(p)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
      StringBuilder b = new StringBuilder();
      for (byte x : md.digest()) b.append(String.format("%02x", x));
      return b.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
