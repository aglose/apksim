package dev.apksim;

import android.content.Intent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Delivers the launch's {@code --broadcast} options ({@link LaunchBroadcasts} parses them): Android classes, running instances only. */
final class LaunchBroadcastSender {
  private LaunchBroadcastSender() {}

  /** The Intent for a spec, aimed at PKG only. */
  static Intent intent(LaunchBroadcasts.Spec spec, String pkg) {
    Intent in = new Intent(spec.action());
    in.setPackage(pkg);
    for (String[] e : spec.extras()) {
      switch (e[0]) {
        case "es" -> in.putExtra(e[1], e[2]);
        case "ez" -> in.putExtra(e[1], Boolean.parseBoolean(e[2]));
        case "ei" -> in.putExtra(e[1], Integer.parseInt(e[2]));
        case "el" -> in.putExtra(e[1], Long.parseLong(e[2]));
        default -> in.putExtra(e[1], Float.parseFloat(e[2]));
      }
    }
    return in;
  }

  /** Receives the ordered broadcast's final result, after every receiver (goAsync ones too) has finished. */
  private static final class Result extends android.content.BroadcastReceiver {
    final CompletableFuture<Integer> done = new CompletableFuture<>();

    @Override
    public void onReceive(android.content.Context c, Intent i) {
      done.complete(getResultCode());
    }

    // Robolectric's instrumented BroadcastReceiver has this, which javac does not see (as AdbShell's).
    public Object $$robo$getData() {
      try {
        return android.content.BroadcastReceiver.class.getField("__robo_data__").get(this);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * Sends the launch's broadcasts in order from the main thread, running the main queue until each one's final
   * result arrives (5 s at most; a timeout is logged, not reported as a result). A bad spec or a receiver that
   * throws is logged and the launch goes on.
   */
  static void send(android.app.Application app) {
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.broadcast." + i);
      if (s == null) return;
      LaunchBroadcasts.Spec spec;
      try {
        spec = LaunchBroadcasts.parse(s);
      } catch (IllegalArgumentException e) {
        System.err.println("[apksim] " + e.getMessage());
        continue;
      }
      try {
        Result r = new Result();
        app.sendOrderedBroadcast(intent(spec, app.getPackageName()), null, r, null, 0, null, null);
        TestActivities.runMainQueueUntil(r.done::isDone, TimeUnit.SECONDS.toNanos(5));
        System.err.println("[apksim] broadcast " + spec.action() + ": "
            + (r.done.isDone() ? "result=" + r.done.getNow(0) : "no result after 5 s"));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable t) {
        System.err.println("[apksim] broadcast " + spec.action() + ": failed: " + t);
      }
    }
  }
}
