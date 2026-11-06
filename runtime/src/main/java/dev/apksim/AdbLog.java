package dev.apksim;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.robolectric.shadows.ShadowLog;

/**
 * The log `adb logcat` reads: a ring of the app's android.util.Log lines, timestamped as they arrive. Robolectric
 * writes them to ShadowLog.stream, so that stream is replaced with a tee that keeps each line and still passes it
 * on to whatever was there (stderr after `logcat on`). The `logcat` control command sets the stream again; a
 * watcher puts the tee back in front of whatever it set.
 */
final class AdbLog {
  /** One log line: level V D I W E F, its tag, and the line in logcat's threadtime format. */
  record Line(char level, String tag, String text) {}

  private static final int CAPACITY = 20_000;
  private static final ArrayDeque<Line> ring = new ArrayDeque<>();
  private static long seq;  // lines ever added; the ring holds the last ring.size() of them
  private static volatile PrintStream delegate;
  private static PrintStream tee;
  private static final long PID = ProcessHandle.current().pid();

  private AdbLog() {}

  /**
   * Starts keeping the log (once; later calls only put the tee back in front at once). The adb device does it at
   * start, a test run too (a test's `logcat -c`/`-d` through UiAutomation), and a running instance on the first
   * `logcat` a shell command asks for.
   */
  static synchronized void install() {
    if (tee != null) {
      intercept();
      return;
    }
    tee = new PrintStream(new TeeOut(), true, StandardCharsets.UTF_8);
    intercept();
    Thread t = new Thread(() -> {
      while (true) {
        intercept();
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          return;
        }
      }
    }, "adb-log");
    t.setDaemon(true);
    t.start();
  }

  private static synchronized void intercept() {
    PrintStream current = ShadowLog.stream;
    if (current != tee) {
      delegate = current;
      ShadowLog.stream = tee;
    }
  }

  /** Whether what the app logs reaches this process's stderr (`logcat on`, APKSIM_LOGCAT), directly or past the tee. */
  static synchronized boolean reachesStderr() {
    PrintStream s = ShadowLog.stream;
    return s == System.err || (s != null && s == tee && delegate == System.err);
  }

  static synchronized void clear() {
    ring.clear();
  }

  /** Failure bundles: the first lines from a mark on, kept whatever the ring drops (keepHead, head). */
  private static long headFrom = Long.MAX_VALUE;
  private static int headCap;
  private static final List<Line> head = new ArrayList<>();

  /** Keeps the first [cap] lines from sequence number [from] on (one mark at a time: a new one replaces it). */
  static synchronized void keepHead(long from, int cap) {
    headFrom = from;
    headCap = cap;
    head.clear();
  }

  static synchronized List<Line> head() {
    return new ArrayList<>(head);
  }

  private static synchronized void add(Line line) {
    if (seq >= headFrom && head.size() < headCap) head.add(line);
    ring.addLast(line);
    seq++;
    if (ring.size() > CAPACITY) ring.removeFirst();
    AdbLog.class.notifyAll();
  }

  /** The sequence number the next line will get. */
  static synchronized long next() {
    return seq;
  }

  /** Lines read: what arrived from the asked-for position on (or the oldest kept), and where the next read starts. */
  record Batch(List<Line> lines, long next) {}

  /** Lines from sequence number `from` on, waiting up to `waitMs` for one to arrive if there are none yet. */
  static synchronized Batch since(long from, long waitMs) throws InterruptedException {
    if (seq <= from && waitMs > 0) AdbLog.class.wait(waitMs);
    long oldest = seq - ring.size();
    List<Line> out = new ArrayList<>();
    long i = oldest;
    for (Line l : ring) {
      if (i++ >= from) out.add(l);
    }
    return new Batch(out, seq);
  }

  /** Splits what Robolectric writes ("D/Tag: message", then any stack trace) into lines, one buffer per thread. */
  private static final class TeeOut extends OutputStream {
    private final ThreadLocal<ByteArrayOutputStream> pending = ThreadLocal.withInitial(ByteArrayOutputStream::new);
    private final ThreadLocal<Line> last = new ThreadLocal<>();
    private final SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      PrintStream d = delegate;
      if (d != null) d.write(b, off, len);
      ByteArrayOutputStream buf = pending.get();
      for (int i = off; i < off + len; i++) {
        if (b[i] == '\n') {
          line(buf.toString(StandardCharsets.UTF_8));
          buf.reset();
        } else {
          buf.write(b[i]);
        }
      }
    }

    @Override
    public void flush() {
      PrintStream d = delegate;
      if (d != null) d.flush();
    }

    private void line(String s) {
      char level;
      String tag;
      String msg;
      int slash = s.indexOf('/');
      int colon = s.indexOf(": ");
      // ShadowLog writes "[TIME ]L/TAG: MSG"; anything else continues the previous line (a stack trace).
      if (slash >= 1 && colon > slash && "VDIWEAF".indexOf(s.charAt(slash - 1)) >= 0 && (slash == 1 || s.charAt(slash - 2) == ' ')) {
        level = s.charAt(slash - 1) == 'A' ? 'F' : s.charAt(slash - 1);
        tag = s.substring(slash + 1, colon);
        msg = s.substring(colon + 2);
      } else {
        Line prev = last.get();
        level = prev == null ? 'I' : prev.level();
        tag = prev == null ? "System.err" : prev.tag();
        msg = s;
      }
      String stamp;
      synchronized (time) {
        stamp = time.format(new Date());
      }
      long tid = Thread.currentThread().getId();
      Line l = new Line(level, tag, String.format("%s %5d %5d %c %-8s: %s", stamp, PID, tid, level, tag, msg));
      last.set(l);
      add(l);
    }
  }
}
