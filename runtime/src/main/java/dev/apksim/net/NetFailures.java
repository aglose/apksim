package dev.apksim.net;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The app's requests that failed, counted whether or not any --network option is on: a connect that failed (refused,
 * unreachable, timed out, offline), a host name that did not resolve, a request a replay had no answer for, and, while
 * the tap reads the app's connections (a recording, `--rec net`), an exchange that ended without an answer or with a
 * 5xx (not a stream the app reset itself, as a cancelled request is). `network failures` answers the count and the last
 * few; crawl.py reads it around a screen's reach (a screen reached while requests failed may show an error state that a
 * later walk does not: docs/crawl.md "Unstable screens").
 *
 * <p>Counting costs nothing on a request that works: each site is on a path that is already throwing or failing. Not
 * counted: a failed lookup the JDK answers from its cache of failed lookups (10 s), a 4xx, failures inside connections
 * the JDK's own HttpURLConnection makes while no --network option is on, and anything native code does.
 */
public final class NetFailures {
  private NetFailures() {}

  private static final AtomicLong COUNT = new AtomicLong();
  private static final int KEEP = 5;
  private static final ArrayDeque<String> LAST = new ArrayDeque<>();

  /** One failed request, [what] one line naming the host and why. */
  public static void note(String what) {
    COUNT.incrementAndGet();
    String line = what.replace('\n', ' ').replace('\r', ' ');
    if (line.length() > 200) line = line.substring(0, 200) + "...";
    synchronized (LAST) {
      if (LAST.size() == KEEP) LAST.removeFirst();
      LAST.addLast(line);
    }
  }

  public static long count() {
    return COUNT.get();
  }

  /** `network failures [clear]`: "N failed[: LAST; LAST...]" (the newest last), the count kept unless cleared. */
  static String command(boolean clear) {
    StringBuilder b = new StringBuilder();
    synchronized (LAST) {
      b.append(COUNT.get()).append(" failed");
      if (!LAST.isEmpty()) b.append(": ").append(String.join(" | ", LAST));
      if (clear) {
        COUNT.set(0);
        LAST.clear();
      }
    }
    return b.toString();
  }
}
