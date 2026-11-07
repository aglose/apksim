package dev.apksim;

import android.content.Context;
import android.content.Intent;

/**
 * ACTION_TIME_TICK, which a device's system server broadcasts at the start of every minute to receivers registered at
 * runtime (an in-app clock redraws from it). Sent from Host's loop whenever the minute of the clock the app sees
 * (SimClock: the wall clock, or `--clock`'s) changes. Not under --snapshot, whose screens must not move.
 */
final class TimeTick {
  private TimeTick() {}

  private static long minute = Long.MIN_VALUE;

  static void tick(Context app) {
    if (Snapshot.ON || app == null) return;
    long now = Math.floorDiv(SimClock.currentTimeMillis(), 60_000L);
    if (minute == Long.MIN_VALUE) {
      minute = now;  // the minute the app started in has begun already
      return;
    }
    if (now == minute) return;
    minute = now;
    app.sendBroadcast(new Intent(Intent.ACTION_TIME_TICK).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY));
  }
}
