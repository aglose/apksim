package dev.apksim;

import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code --broadcast 'ACTION;es:KEY=VALUE;ez:KEY=true;ei:...;el:...;ef:...'} (repeatable, -Dapksim.broadcast.N): one
 * broadcast sent to the app's own package once Application.onCreate has returned and before the first activity starts,
 * as {@code adb shell am broadcast -a ACTION -p PKG --es KEY VALUE} would, for debug receivers that set flag overrides.
 * It reaches receivers registered at run time and manifest ones. Values are one plain argument (no ';', so serve's
 * catalog lines and beam allow it). Logs {@code [apksim] broadcast ACTION: result=N}.
 */
public final class LaunchBroadcasts {
  public static final int COUNT_MAX = BroadcastSpec.COUNT_MAX;

  private LaunchBroadcasts() {}

  /** Parses a spec; throws IllegalArgumentException with the reason. */
  static BroadcastSpec.Spec parse(String s) {
    return BroadcastSpec.parse(s);
  }

  /** The Intent for a spec, aimed at PKG only. */
  static Intent intent(BroadcastSpec.Spec spec, String pkg) {
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

  /**
   * Sends the launch's broadcasts, in order, from the main thread, and runs the main queue until each has been
   * delivered. A bad spec or a receiver that throws is logged and the launch goes on.
   */
  static void send(android.app.Application app) {
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.broadcast." + i);
      if (s == null) return;
      BroadcastSpec.Spec spec;
      try {
        spec = parse(s);
      } catch (IllegalArgumentException e) {
        System.err.println("[apksim] " + e.getMessage());
        continue;
      }
      try {
        Intent in = intent(spec, app.getPackageName());
        String[] result = {null};
        app.sendOrderedBroadcast(in, null, new android.content.BroadcastReceiver() {
          @Override
          public void onReceive(android.content.Context c, Intent i) {
            result[0] = "result=" + getResultCode();
          }

          // Robolectric's instrumented BroadcastReceiver has this, which javac does not see (as AdbShell's).
          public Object $$robo$getData() {
            try {
              return android.content.BroadcastReceiver.class.getField("__robo_data__").get(this);
            } catch (ReflectiveOperationException e) {
              throw new IllegalStateException(e);
            }
          }
        }, null, 0, null, null);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        System.err.println("[apksim] broadcast " + spec.action() + ": " + (result[0] != null ? result[0] : "result=0"));
      } catch (Throwable t) {
        System.err.println("[apksim] broadcast " + spec.action() + ": failed: " + t);
      }
    }
  }
}
