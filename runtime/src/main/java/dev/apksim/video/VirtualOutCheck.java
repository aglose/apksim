package dev.apksim.video;

/** scripts/check-virtual-out.sh: {@link VirtualOut}'s timing against real time, with generous bounds for loaded runners. */
public final class VirtualOutCheck {
  private static int failed;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok: " : "FAIL: ") + what);
    if (!ok) failed++;
  }

  public static void main(String[] args) throws Exception {
    VirtualOut o = new VirtualOut(48000, 4, 48000 * 4 / 5);  // 200 ms buffer
    check(o.write(4800, false) == 4800 && o.played() == 0, "an idle track takes a write and its head stays at 0");
    o.play(true);
    Thread.sleep(100);
    long p = o.played();
    check(p >= 3500 && p <= 4800, "the head moves at the rate once playing (" + p + " of ~4800 after 100 ms)");
    long t = System.nanoTime();
    int w = o.write(48000, true);
    double s = (System.nanoTime() - t) / 1e9;
    check(w == 48000 && s > 0.6 && s < 1.5, "a blocking write of 1 s into a 200 ms buffer takes ~0.8 s (" + w + " frames, " + s + " s)");
    Thread.sleep(400);
    check(o.pending() == 0 && o.underruns() == 2, "drained: nothing pending, two underruns (the first 100 ms write ran dry, then the second) (" + o.underruns() + ")");
    o.play(false);
    long held = o.played();
    Thread.sleep(100);
    check(held == o.played(), "a paused head stays");
    o.flush();
    check(o.played() == 0 && o.pending() == 0, "flush resets the head");
    o.play(true);
    int[] got = {-1};
    Thread th = new Thread(() -> got[0] = o.write(1_000_000, true));
    th.start();
    Thread.sleep(100);
    o.play(false);
    th.join(3000);
    check(!th.isAlive() && got[0] > 0 && got[0] < 1_000_000, "a blocked writer returns what it took when paused (" + got[0] + ")");
    Thread.currentThread().interrupt();
    o.play(true);
    o.flush();
    t = System.nanoTime();
    o.write(48000 / 2, true);
    check(Thread.interrupted() && (System.nanoTime() - t) / 1e9 > 0.1, "an interrupt does not cut a blocking write short");
    System.exit(failed == 0 ? 0 : 1);
  }
}
