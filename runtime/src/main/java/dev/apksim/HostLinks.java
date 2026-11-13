package dev.apksim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Web links the app opens outside itself, told to whoever watches this instance (issue 336): `apksim serve` and
 * `apksim beam` attach a `hostwatch` control connection, and get a line `o URL [REDIRECT]` for each link the instance
 * has no browser of its own for (headless: there is no Mac window to open one from). REDIRECT is the link's
 * `redirect_uri` when one of the app's own filters takes it: a sign-in, whose final address the watcher's user can
 * hand back with `open URL`. The watcher's tab shows the link; nothing is opened here.
 */
final class HostLinks {
  private HostLinks() {}

  private static final List<PrintWriter> WATCHERS = new CopyOnWriteArrayList<>();
  /** The last links nobody watched for, with when: a watcher attaching within {@link #EARLY_NANOS} hears of them. */
  private static final List<Object[]> EARLY = new java.util.ArrayList<>();
  private static final long EARLY_NANOS = 30_000_000_000L;
  /** A link's longest form sent on: what a tab's `open URL` may be, too (Session.check). */
  private static final int MAX = 4000;

  /** The control connection's `hostwatch`: lines go out until the watcher hangs up. */
  static void serve(BufferedReader in, PrintWriter out) {
    out.println("ok hostwatch");
    synchronized (EARLY) {
      // What the app opened before anyone watched (serve attaches as the app starts): told now, if recent.
      long now = System.nanoTime();
      for (Object[] e : EARLY) if (now - (long) e[0] < EARLY_NANOS) out.println((String) e[1]);
      EARLY.clear();
      WATCHERS.add(out);
    }
    try {
      while (in.readLine() != null) {
        // nothing is read: the watcher only listens
      }
    } catch (IOException e) {
      // the watcher went away
    } finally {
      WATCHERS.remove(out);
    }
  }

  /** Tells the watchers of a link; false when nobody watches, or the link cannot be one line. */
  static boolean offer(String url, String redirect) {
    if (!oneToken(url) || (redirect != null && !oneToken(redirect))) return false;
    String line = "o " + url + (redirect == null ? "" : " " + redirect);
    synchronized (EARLY) {
      if (WATCHERS.isEmpty()) {
        if (EARLY.size() >= 4) EARLY.remove(0);
        EARLY.add(new Object[] {System.nanoTime(), line});
        return false;
      }
    }
    boolean told = false;
    for (PrintWriter w : WATCHERS) {
      synchronized (w) {
        w.println(line);
        if (w.checkError()) WATCHERS.remove(w);
        else told = true;
      }
    }
    return told;
  }

  private static boolean oneToken(String s) {
    if (s.isEmpty() || s.length() > MAX) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c <= ' ' || c == 0x7f || Character.isWhitespace(c)) return false;
    }
    return true;
  }
}
