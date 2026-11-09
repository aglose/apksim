package dev.apksim.rec;

import java.util.ArrayList;
import java.util.List;

/**
 * When a flight-recorder channel was on, as wall-clock intervals, for channels whose data is kept by something else
 * that goes on without them: logcat (AdbLog's ring, which adb also reads) and net (a --network record file). A save
 * keeps only what falls inside an interval, and `rec clear` moves every interval's start to the moment of the clear.
 */
public final class Spans {
  private final List<long[]> spans = new ArrayList<>();  // {from, to}; to is Long.MAX_VALUE while on

  public synchronized void on(long wallMs) {
    if (!isOn()) spans.add(new long[] {wallMs, Long.MAX_VALUE});
  }

  public synchronized void off(long wallMs) {
    if (isOn()) spans.get(spans.size() - 1)[1] = wallMs;
  }

  public synchronized boolean isOn() {
    return !spans.isEmpty() && spans.get(spans.size() - 1)[1] == Long.MAX_VALUE;
  }

  /** Nothing before wallMs counts any more. */
  public synchronized void clear(long wallMs) {
    spans.removeIf(s -> s[1] <= wallMs);
    for (long[] s : spans) s[0] = Math.max(s[0], wallMs);
  }

  public synchronized boolean contains(long wallMs) {
    for (long[] s : spans) if (wallMs >= s[0] && wallMs < s[1]) return true;
    return false;
  }

  /** Whether the channel was on at any time from wallMs on. */
  public synchronized boolean anySince(long wallMs) {
    for (long[] s : spans) if (s[1] > wallMs) return true;
    return false;
  }

  /** Forgets intervals that ended before the rings' cutoff. */
  public synchronized void trim(long keepFrom) {
    spans.removeIf(s -> s[1] <= keepFrom);
  }
}
