package dev.apksim.serve;

/**
 * Checks {@link Pacer} on a simulated clock (scripts/check-pacer.sh, CI): a slow tab gets a pace from its measured rate,
 * one drop alone gets none, a too-low pace is raised, easing after a quiet spell, a failed trial restoring the pace that
 * held with a doubling wait (capped), the one-frame window's round-trip fallback, and the app's idle time and the link's
 * delay kept out of the tab's measured time. Exits 1 if any check fails.
 */
public final class PacerCheck {
  private static int failures;
  private static final long S = 1_000_000_000L;
  private static final long MS = 1_000_000L;

  private PacerCheck() {}

  public static void main(String[] args) {
    oneDropAlone();
    repeatedDrops();
    raisedWhenTooLow();
    easingAndBackoff();
    easedToNothing();
    oneFrameWindow();
    ackGapIgnoresIdle();
    ackGapIgnoresTheLink();
    if (failures > 0) {
      System.out.println(failures + " pacer check(s) failed");
      System.exit(1);
    }
    System.out.println("pacer checks passed");
  }

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  private static void oneDropAlone() {
    Pacer p = new Pacer();
    check(p.dropped(10 * S, 40) == -1 && p.paceMs == 0, "one drop alone sets no pace");
    check(p.dropped(13 * S, 40) == -1 && p.paceMs == 0, "nor does another 3 s later");
    check(p.keyDrops == 2, "both drops are counted");
  }

  private static void repeatedDrops() {
    Pacer p = new Pacer();
    p.dropped(10 * S, -1);
    check(p.dropped(10 * S + 500 * MS, -1) == -1, "repeated drops with no measured tab rate set no pace");
    check(p.dropped(11 * S, 40) == 46 && p.paceMs == 46, "repeated drops ask for 1.15x the tab's 40 ms (46)");
    check(p.dropped(11 * S + 200 * MS, 40) == -1, "a drop before the pace took effect asks for no more");
    Pacer q = new Pacer();
    q.dropped(10 * S, 900);
    check(q.dropped(10 * S + 100 * MS, 900) == Pacer.MAX_PACE_MS, "the pace is at most 250 ms");
  }

  private static void raisedWhenTooLow() {
    Pacer p = new Pacer();
    p.dropped(10 * S, 40);
    p.dropped(10 * S + 100 * MS, 40);  // pace 46
    check(p.dropped(12 * S, 40) == 58 && p.paceMs == 58, "a drop under a settled pace makes it a quarter slower (46 -> 58)");
  }

  private static void easingAndBackoff() {
    Pacer p = new Pacer();
    p.dropped(10 * S, 40);
    p.dropped(10 * S + 100 * MS, 40);  // pace 46 at 10.1 s
    check(p.frame(17 * S) == -1, "no easing before 8 s without a drop");
    check(p.frame(19 * S) == 39 && p.eased && p.goodPaceMs == 46, "after 8 s quiet, a trial 15% quicker (46 -> 39)");
    check(p.frame(20 * S) == -1, "one trial at a time");
    check(p.dropped(21 * S, 40) == 46 && !p.eased, "a drop during the trial puts back the pace that held (46)");
    check(p.easeWaitNanos == 16 * S, "and doubles the wait before the next trial (16 s)");
    check(p.frame(30 * S) == -1 && p.frame(38 * S) == 39, "the next trial waits 16 s");
    long t = 38 * S;
    for (int i = 0; i < 6; i++) {
      p.dropped(t + S, 40);  // the trial fails
      t += 2 * S + p.easeWaitNanos;
      p.frame(t);
    }
    check(p.easeWaitNanos == Pacer.MAX_EASE_WAIT_NANOS, "the wait stops doubling at 128 s");
  }

  private static void easedToNothing() {
    Pacer p = new Pacer();
    p.dropped(10 * S, 8);
    p.dropped(10 * S + 100 * MS, 8);  // pace 10
    check(p.paceMs == 10, "a fast tab's pace (10)");
    check(p.frame(19 * S) == 0 && p.paceMs == 0, "eased under 8 ms it goes: the instance's own interval");
    check(p.frame(40 * S) == -1, "and no pace eases no further");
  }

  private static void oneFrameWindow() {
    check(Pacer.tabFrameMs(-1, 1, 41) == 41, "a window of one: the tab's time is the ack round trip");
    check(Pacer.tabFrameMs(-1, 2, 41) == -1, "a wider window without a gap sample: not known");
    check(Pacer.tabFrameMs(43, 1, 80) == 43, "a gap sample wins over the round trip");
    Pacer p = new Pacer();
    p.dropped(10 * S, Pacer.tabFrameMs(-1, 1, 41));
    check(p.dropped(10 * S + 300 * MS, Pacer.tabFrameMs(-1, 1, 41)) == 48, "so a window of one gets a pace (48)");
  }

  private static void ackGapIgnoresIdle() {
    // A tab taking 40 ms a frame, no link delay, frames queued at it: acks 40 ms apart, each frame sent long before.
    Pacer.AckGap g = new Pacer.AckGap(-1, 0);
    long t = 100 * S;
    g = Pacer.ackGap(g.gapMs(), g.baselineNanos(), t, t - 40 * MS, 1);
    for (int i = 1; i <= 10; i++) g = Pacer.ackGap(g.gapMs(), g.baselineNanos(), t + i * 40 * MS, t, 1);
    check(Math.abs(g.gapMs() - 40) < 0.5, "a tab 40 ms a frame with frames queued: 40 ms (" + g.gapMs() + ")");
    t += 400 * MS;
    // 10 s of still screen, then a frame to an idle tab: its own 40 ms, not the 10 s.
    g = Pacer.ackGap(g.gapMs(), g.baselineNanos(), t + 10 * S + 41 * MS, t + 10 * S, 1);
    check(Math.abs(g.gapMs() - 40) < 0.5, "a still spell is not sampled (" + g.gapMs() + ")");
  }

  private static void ackGapIgnoresTheLink() {
    // A 200 ms link, a tab that decodes at once, an app drawing every 140 ms: always a frame in flight, but the tab
    // is idle, so its time is ~0 and a drop (the link's window full) gets no pace from the app's interval.
    Pacer.AckGap g = new Pacer.AckGap(-1, 0);
    long t = 100 * S;
    for (int i = 0; i < 20; i++) g = Pacer.ackGap(g.gapMs(), g.baselineNanos(), t + i * 140 * MS + 201 * MS, t + i * 140 * MS, 200);
    check(g.gapMs() < 2, "a 200 ms link, an idle tab, a frame every 140 ms: ~0 ms (" + g.gapMs() + ")");
    Pacer p = new Pacer();
    p.dropped(10 * S, Pacer.tabFrameMs(g.gapMs(), 4, 201));
    check(p.dropped(10 * S + 300 * MS, Pacer.tabFrameMs(g.gapMs(), 4, 201)) <= 2, "so the link's drops are not paced to the app's interval");
    // The same link and a tab taking 40 ms a frame with frames queued: 40 ms.
    g = new Pacer.AckGap(-1, 0);
    for (int i = 1; i <= 20; i++) g = Pacer.ackGap(g.gapMs(), g.baselineNanos(), t + 200 * MS + i * 40 * MS, t, 200);
    check(Math.abs(g.gapMs() - 40) < 0.5, "a 200 ms link and a tab 40 ms a frame: 40 ms (" + g.gapMs() + ")");
    // A window of one on that link: each frame sent at the last ack, back 200 ms plus the tab's 40 ms later.
    g = new Pacer.AckGap(-1, 0);
    long ack = t;
    for (int i = 0; i < 10; i++) {
      long sent = ack;
      ack = sent + 240 * MS;
      g = Pacer.ackGap(g.gapMs(), i == 0 ? 0 : g.baselineNanos(), ack, sent, 200);
    }
    check(Math.abs(g.gapMs() - 40) < 0.5, "a window of one on a 200 ms link: the tab's 40 ms, not the round trip (" + g.gapMs() + ")");
  }
}
