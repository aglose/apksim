package dev.apksim.serve;

/**
 * How fast the instance should encode for an H.264 tab that decodes slower than the app draws (issue 232). Each drop to
 * a keyframe is a freeze, and a keyframe is several times a delta, so dropping to one only makes a slow tab slower: the
 * relay instead tells the encoder `pace MS`, at most one new frame per MS, and the stream stays a valid chain at the
 * tab's rate. Pure state, with the clock passed in, so {@link PacerCheck} can drive it; the caller synchronizes.
 *
 * <ul>
 *   <li>A drop that repeats (a second within 2 s) or one under a pace already set asks for 1.15 times the tab's measured
 *       time per frame, at most 250 ms. One drop alone (a tab busy while its page starts) asks for nothing.
 *   <li>A drop under a pace that has had a second to take effect makes it a quarter slower: the estimate was too low.
 *   <li>After {@link #easeWaitNanos} without a drop the pace is tried 15% quicker (none once under 8 ms, the instance's
 *       own frame interval at 120 Hz). A drop during that trial puts back the pace that held and doubles the wait, to
 *       128 s at most, so a tab at its limit is probed ever more rarely instead of freezing every few seconds.
 * </ul>
 */
final class Pacer {
  static final long EASE_WAIT_NANOS = 8_000_000_000L;
  static final long MAX_EASE_WAIT_NANOS = 16 * EASE_WAIT_NANOS;
  private static final long REPEAT_NANOS = 2_000_000_000L;
  private static final long SETTLE_NANOS = 1_000_000_000L;
  static final int MAX_PACE_MS = 250;

  /** Drops to a keyframe so far. */
  int keyDrops;
  /** The pace asked of the instance, ms between new frames (0: none). */
  int paceMs;
  long easeWaitNanos = EASE_WAIT_NANOS;
  /** A quicker pace is being tried; {@link #goodPaceMs} is the one before it. */
  boolean eased;
  int goodPaceMs;
  private long lastDropNanos;
  private long lastPaceNanos;

  /**
   * A frame arrived from the instance at now: may ease the pace after a quiet spell. Returns the pace to send the
   * instance, or -1 for no change.
   */
  int frame(long now) {
    if (paceMs <= 0 || now - lastDropNanos <= easeWaitNanos || now - lastPaceNanos <= easeWaitNanos) return -1;
    goodPaceMs = paceMs;
    eased = true;
    int quicker = paceMs * 85 / 100;
    paceMs = quicker <= 8 ? 0 : quicker;
    lastPaceNanos = now;
    return paceMs;
  }

  /**
   * The relay dropped to a keyframe at now; tabFrameMs is what the tab takes per frame (-1 if not known yet). Returns
   * the pace to send the instance, or -1 for no change.
   */
  int dropped(long now, double tabFrameMs) {
    keyDrops++;
    boolean repeated = paceMs > 0 || eased || (lastDropNanos != 0 && now - lastDropNanos < REPEAT_NANOS);
    lastDropNanos = now;
    if (eased) {
      eased = false;
      easeWaitNanos = Math.min(easeWaitNanos * 2, MAX_EASE_WAIT_NANOS);
      paceMs = goodPaceMs;
      lastPaceNanos = now;
      return paceMs;
    }
    // Only from the tab's own measured rate: the app's frame interval says nothing about the tab.
    if (!repeated || tabFrameMs <= 0) return -1;
    double want = tabFrameMs * 1.15;
    if (paceMs > 0 && now - lastPaceNanos > SETTLE_NANOS) want = Math.max(want, paceMs * 1.25);
    int ms = (int) Math.min(MAX_PACE_MS, Math.ceil(want));
    if (ms <= paceMs) return -1;
    paceMs = ms;
    lastPaceNanos = now;
    return ms;
  }

  /**
   * What a frame costs the path to the tab, in ms, or -1 before it is known: the larger of the tab's busy time per frame
   * (busyMs) and the link's, a window's worth of frames per ack round trip. On a link with delay and a fast tab the link
   * is the limit (200 ms with 4 in flight: 50 ms a frame); a slow decoder is its own. Before the first busy samples, a
   * window of one has the quickest recent ack round trip.
   */
  static double tabFrameMs(double busyMs, int window, double ackRttMs) {
    if (busyMs >= 0) return Math.max(busyMs, ackRttMs > 0 && window > 0 ? ackRttMs / window : 0);
    return window <= 1 ? ackRttMs : -1;
  }

  /**
   * The tab's busy time per frame, ms: a ring of the last {@link #SAMPLES}, whose plain mean is used. A frame's work
   * begins at the later of the previous ack and its own arrival (sent plus the link's round trip, the quickest ping,
   * which a tab answers without waiting for its decoder), so neither the app's idle time nor the link's delay counts.
   * Timing every gap between acks while a frame was in flight took, on a 200 ms link where a frame nearly always is,
   * the app's own frame interval (a still screen's ~140 ms) for the tab's time.
   */
  private static final int SAMPLES = 16;
  private final double[] busy = new double[SAMPLES];
  private int busyCount;
  private long lastAckNanos;

  /** An ack arrived at now for a frame sent at sentNanos, on a link whose round trip is linkMs (-1: not known yet). */
  void acked(long now, long sentNanos, double linkMs) {
    long arrived = sentNanos + (linkMs > 0 ? (long) (linkMs * 1e6) : 0);
    busy[busyCount++ % SAMPLES] = Math.max(0, now - Math.max(lastAckNanos, arrived)) / 1e6;
    lastAckNanos = now;
  }

  /** The mean of the recent busy times in ms, or -1 before there are 4. */
  double busyMs() {
    int n = Math.min(busyCount, SAMPLES);
    if (n < 4) return -1;
    double sum = 0;
    for (int i = 0; i < n; i++) sum += busy[i];
    return sum / n;
  }
}
