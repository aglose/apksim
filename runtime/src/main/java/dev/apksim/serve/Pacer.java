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
   * The tab's time per frame, in ms, or -1 before it is known: the gap between acks while another frame was in flight
   * (ackGapMs); with a window of one no frame ever is, and the quickest recent ack round trip is that time instead.
   */
  static double tabFrameMs(double ackGapMs, int window, double ackRttMs) {
    if (ackGapMs > 0) return ackGapMs;
    return window <= 1 ? ackRttMs : -1;
  }

  /**
   * Gaps between acks while another frame was in flight, ms: a ring of the last {@link #GAPS}. Their plain mean is the
   * tab's time per frame. Not a smoothed average: on a link with delay the acks come in bursts (a window's worth close
   * together, then a round trip), and smoothing jumped at each long gap, pacing a 20 fps link at ~10 (issue 232).
   */
  private static final int GAPS = 16;
  private final double[] gaps = new double[GAPS];
  private int gapCount;
  private long lastAckNanos;

  /**
   * An ack arrived at now and inFlight frames are still in flight. The baseline goes when the flight drains, so the
   * time to the next ack, which is the app's (a still screen) rather than the tab's, is never sampled.
   */
  void acked(long now, int inFlight) {
    if (inFlight > 0 && lastAckNanos != 0) gaps[gapCount++ % GAPS] = (now - lastAckNanos) / 1e6;
    lastAckNanos = inFlight > 0 ? now : 0;
  }

  /** The mean of the recent ack gaps in ms, or -1 before there are 4. */
  double ackGapMs() {
    int n = Math.min(gapCount, GAPS);
    if (n < 4) return -1;
    double sum = 0;
    for (int i = 0; i < n; i++) sum += gaps[i];
    return sum / n;
  }
}
