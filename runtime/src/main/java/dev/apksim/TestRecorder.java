package dev.apksim;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.runner.Description;

/**
 * A movie of each test in an `--instrument` run (`--record failed|all --record-dir DIR`, issue 132): Motion-JPEG in
 * DIR/&lt;class&gt;/&lt;method&gt;-&lt;hash&gt;.avi ({@link Bundles#folder}'s name), kept for a failed test (or every test with
 * `all`) and deleted otherwise. A test run has no window or Host loop, so frames are not presented: after each window
 * traversal ({@link ShadowSimViewRootImpl}) the screen is drawn, as a failure bundle's screen.png is but on a CPU canvas
 * ({@link Bundles#screenBitmap}), at most {@code --record-fps} (default 15) times a second of real time, scaled to fit
 * {@code --record-size} (default 1280x720, never up). {@link MjpegRecorder} repeats the last frame at that rate while
 * nothing changes, so the movie runs in real time; the simulated clock's jumps over idle time do not show in it.
 * The recorder starts at a test's first frame (a test that never shows a window leaves no file) and ends at its end.
 * A process that dies mid-test leaves its movie unfinished (no index): players that can read AVI streams still play it.
 *
 * <p>A recording must never be what fails a test (issue 144: a capture taken inside ViewRootImpl.performDraw, through
 * HWUI's HardwareRenderingScreenshot, never returned from nSyncAndDrawFrame and held the test past its timeout). So a
 * frame is taken from a main-thread message posted after the traversal, never inside a window draw, and drawn in
 * software, never through the render thread. A frame slower than {@link #SLOW_MS} ends that test's movie there.
 */
final class TestRecorder {
  private TestRecorder() {}

  private static final String MODE = System.getProperty("apksim.record");
  private static final String DIR = System.getProperty("apksim.record.dir");
  static final boolean ON = ("failed".equals(MODE) || "all".equals(MODE)) && DIR != null && TestActivities.testRun()
      && !Boolean.parseBoolean(System.getProperty("apksim.instrument.log"));
  private static final boolean ALL = "all".equals(MODE);
  private static final int FPS = Math.max(1, Math.min(30, Integer.getInteger("apksim.record.fps", 15)));
  private static final int[] MAX = size(System.getProperty("apksim.record.size", "1280x720"));
  /** A frame that took this long stops the test's movie (the rest of the test runs without one). */
  static final long SLOW_MS = Long.getLong("apksim.record.slowMs", 2_000);  // a property for the check of this path
  private static boolean posted;  // a capture message is queued
  private static boolean cutOff;  // the writer was stopped mid-test (a slow frame): finish() keeps its result
  private static long cutOffMs;

  private static Description current;
  private static Path file;
  private static boolean started, capturing, active, pending, scheduled, failed;
  private static android.os.Handler main;
  private static java.util.concurrent.ScheduledExecutorService timer;
  private static java.util.concurrent.ScheduledFuture<?> timerTask;
  private static int generation;  // bumped at each test boundary: a callback from an earlier test does nothing
  private static long lastNanos;
  private static int w, h;
  private static Bitmap scaled;
  private static Paint filter;  // made at the first frame: android.graphics needs the sandbox

  private static Paint filter() {
    if (filter == null) filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    return filter;
  }

  private static int[] size(String s) {
    String[] p = s.split("x");
    try {
      return new int[] {Math.max(16, Integer.parseInt(p[0])), Math.max(16, Integer.parseInt(p[1]))};
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("--record-size WxH: " + s);
    }
  }

  static void testStarted(Description d) {
    if (!ON) return;
    finish(false);
    resetScheduling();
    current = d;
    file = Paths.get(DIR, d.getClassName(), Bundles.folder(d.getClassName(), d.getMethodName()) + ".avi");
    started = false;
    lastNanos = 0;
    active = true;
    pending = false;
    failed = false;
    cutOff = false;
  }

  /**
   * Robolectric's reset between tests discards messages queued on the main looper, so a deadline callback posted by an
   * earlier test may never run: forget it (and the timer entry that would post it) so this test can schedule its own.
   * The handler is dropped too, in case the looper was replaced.
   */
  private static void resetScheduling() {
    generation++;
    scheduled = false;
    posted = false;
    pending = false;
    main = null;
    if (timerTask != null) {
      timerTask.cancel(false);
      timerTask = null;
    }
  }

  /** The path of the movie a finished test kept, for its status block, or null. */
  static String recordingOf(Description d) {
    return ON && keptFor != null && d.equals(current) ? keptFor.toString() : null;
  }

  private static Path keptFor;

  /** After a window traversal: takes a frame if the current test's movie is due one. */
  static void onDraw() {
    if (!ON || !active || failed || capturing || Looper.myLooper() != Looper.getMainLooper()) return;
    long now = System.nanoTime();
    long interval = 1_000_000_000L / FPS;
    if (started && now - lastNanos < interval) {
      // Too soon: keep the update and capture it at the next allowed time, so a screen that then goes idle still
      // reaches the movie. A timer thread only posts to the main looper (no clock advance; the capture runs when the
      // looper next runs).
      pending = true;
      if (!scheduled) {
        scheduled = true;
        if (main == null) main = new android.os.Handler(Looper.getMainLooper());
        if (timer == null) {
          timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "apksim-record-timer");
            t.setDaemon(true);
            return t;
          });
        }
        final int gen = generation;
        final android.os.Handler handler = main;
        timerTask = timer.schedule(() -> {
          try {
            handler.post(() -> onDeadline(gen));
          } catch (RuntimeException e) {
            // The looper went away with its test: nothing to capture.
          }
        }, lastNanos + interval - now, java.util.concurrent.TimeUnit.NANOSECONDS);
      }
      return;
    }
    // Due: take it once this draw has returned (a posted message, not now: see the class comment).
    pending = true;
    post();
  }

  private static void post() {
    if (posted) return;
    if (main == null) main = new android.os.Handler(Looper.getMainLooper());
    posted = true;
    final int gen = generation;
    main.post(() -> {
      if (gen != generation) return;  // queued by an earlier test
      posted = false;
      if (!ON || !active || failed || capturing || !pending) return;
      // Run from a looper drained inside a draw: not now, and not posted again (that drain would never go idle).
      // `pending` stays set, and the outer draw's onDraw, once it returns, posts the capture.
      if (ShadowSimViewRootImpl.drawing > 0) return;
      capture(System.nanoTime());
    });
  }

  private static void onDeadline(int gen) {
    if (gen != generation) return;  // a callback that outlived its test's boundary
    scheduled = false;
    if (!ON || !active || !pending || !started || capturing) return;
    // A traversal may have captured since this was scheduled (lastNanos moved): decide against the latest one, which
    // posts the capture if due and otherwise schedules the new deadline.
    onDraw();
  }

  private static void capture(long now) {
    capturing = true;
    pending = false;
    Bitmap screen = null;
    try {
      screen = Bundles.screenBitmap(true);
      if (!started) {
        double k = Math.min(1.0, Math.min(MAX[0] / (double) screen.getWidth(), MAX[1] / (double) screen.getHeight()));
        w = Math.max(16, (int) (screen.getWidth() * k) & ~1);
        h = Math.max(16, (int) (screen.getHeight() * k) & ~1);
        try {
          Files.createDirectories(file.getParent());
          shareWithHost(file.getParent());
          String err = MjpegRecorder.start(file.toString(), w, h, FPS);
          if (err != null) throw new IOException(err);
        } catch (IOException | RuntimeException e) {
          // Not the expected no-window case: say why there is no movie, and do not retry for this test.
          failed = true;
          pending = false;
          System.err.println("[apksim] cannot record " + (current == null ? "" : current.getClassName() + "#"
              + current.getMethodName() + " ") + "to " + file + ": " + e);
          return;
        }
        started = true;
        if (scaled == null || scaled.getWidth() != w || scaled.getHeight() != h) {
          if (scaled != null) scaled.recycle();
          scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
      }
      // Fit inside w x h (a rotation changes the display mid-test; the movie keeps its first size).
      Canvas c = new Canvas(scaled);
      c.drawColor(0xff000000);
      double k = Math.min(w / (double) screen.getWidth(), h / (double) screen.getHeight());
      int dw = (int) (screen.getWidth() * k), dh = (int) (screen.getHeight() * k);
      c.drawBitmap(screen, null, new Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh), filter());
      ByteBuffer pixels = ByteBuffer.allocate(w * h * 4);
      scaled.copyPixelsToBuffer(pixels);
      pixels.flip();
      MjpegRecorder.frame(pixels);
      lastNanos = now;
    } catch (VirtualMachineError e) {
      throw e;  // out of memory and the like: not the recorder's to hide
    } catch (Throwable e) {
      // No window yet (or any more), or a view that cannot draw on a CPU canvas (an AssertionError): the movie keeps
      // its last frame. A failing frame never fails the test.
      lastNanos = now;
    } finally {
      if (screen != null) screen.recycle();
      capturing = false;
      // Slow, whether the frame made it or the draw threw after a while: the movie ends here.
      long took = (System.nanoTime() - now) / 1_000_000;
      if (took > SLOW_MS && !failed) cutOff(took);
    }
  }

  /**
   * Stops this test's movie after a slow frame: finished now (index written), not left to repeat its last frame until
   * the test ends; finish() keeps or deletes it as for any other movie. Nothing here may fail the test.
   */
  private static void cutOff(long took) {
    failed = true;
    pending = false;
    if (started) {
      try {
        cutOffMs = MjpegRecorder.stop();
      } catch (VirtualMachineError e) {
        throw e;
      } catch (Throwable e) {
        cutOffMs = -1;  // nothing usable to keep
      }
      cutOff = true;
    }
    System.err.println("[apksim] recording " + (current == null ? "" : current.getClassName() + "#"
        + current.getMethodName() + " ") + "stopped: a frame took " + took + " ms (the test goes on without it)");
  }

  /** The test's end: one last frame if a window still shows, then the movie is kept (failed, or all) or deleted. */
  static void testFinished(Description d, boolean failed) {
    if (!ON || !d.equals(current)) return;
    if (started && !TestRecorder.failed && ShadowSimViewRootImpl.drawing == 0) capture(System.nanoTime());
    // Capture ends here: a traversal in class-level cleanup must not reopen this test's file. `current` stays for
    // recordingOf().
    active = false;
    pending = false;
    finish(failed || ALL);
  }

  /**
   * In a container apksim runs as root over a mounted folder, and the host's instrument.py deletes earlier movies
   * from it: make the class folder (and the record folder) writable by everyone, as {@link Bundles} does for bundles.
   */
  private static void shareWithHost(Path dir) {
    if (!"root".equals(System.getProperty("user.name"))) return;
    var all = java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");
    Path root = Paths.get(DIR).toAbsolutePath();
    for (Path p = dir.toAbsolutePath(); p != null && p.startsWith(root); p = p.getParent()) {
      try {
        Files.setPosixFilePermissions(p, all);
      } catch (IOException | UnsupportedOperationException e) {
        return;
      }
    }
  }

  private static void finish(boolean keep) {
    keptFor = null;
    if (!started) return;
    started = false;
    long ms;
    try {
      ms = cutOff ? cutOffMs : MjpegRecorder.stop();
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      ms = -1;  // a writer that cannot finish: the movie is not kept
      System.err.println("[apksim] could not finish the recording " + file + ": " + e);
    }
    cutOff = false;
    try {
      if (keep && ms >= 0) {
        keptFor = file;
      } else {
        Files.deleteIfExists(file);
      }
    } catch (IOException e) {
      System.err.println("[apksim] could not remove the recording " + file + ": " + e);
    }
  }
}
