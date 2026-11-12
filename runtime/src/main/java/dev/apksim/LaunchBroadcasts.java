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
  public static final int COUNT_MAX = 8;
  public static final int LENGTH_MAX = 1024;

  private LaunchBroadcasts() {}

  /** A parsed spec: the action and typed extras as `type`, `key`, `value` triples. */
  public record Spec(String action, List<String[]> extras) {}

  /** Parses a spec; throws IllegalArgumentException with the reason. */
  public static Spec parse(String s) {
    if (s.length() > LENGTH_MAX) throw new IllegalArgumentException("--broadcast: over " + LENGTH_MAX + " characters");
    String[] parts = s.split(";", -1);
    String action = parts[0];
    if (!action.matches("[A-Za-z0-9_.$]+")) {
      throw new IllegalArgumentException("--broadcast " + abbreviate(s) + ": expected ACTION[;es|ez|ei|el|ef:KEY=VALUE]...");
    }
    List<String[]> extras = new ArrayList<>();
    for (int i = 1; i < parts.length; i++) {
      String p = parts[i];
      int colon = p.indexOf(':');
      int eq = p.indexOf('=');
      if (colon != 2 || eq < 4) throw new IllegalArgumentException("--broadcast " + abbreviate(p) + ": expected es|ez|ei|el|ef:KEY=VALUE");
      String type = p.substring(0, 2), key = p.substring(3, eq), value = p.substring(eq + 1);
      try {
        switch (type) {
          case "es" -> { }
          case "ez" -> {
            if (!value.equals("true") && !value.equals("false")) throw new NumberFormatException(value);
          }
          case "ei" -> Integer.parseInt(value);
          case "el" -> Long.parseLong(value);
          case "ef" -> Float.parseFloat(value);
          default -> throw new IllegalArgumentException("--broadcast " + abbreviate(p) + ": the type is es, ez, ei, el or ef");
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("--broadcast " + abbreviate(p) + ": the value does not fit " + type);
      }
      extras.add(new String[] {type, key, value});
    }
    return new Spec(action, extras);
  }

  /** The Intent for a spec, aimed at PKG only. */
  static Intent intent(Spec spec, String pkg) {
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
      Spec spec;
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

  private static String abbreviate(String s) {
    return s.length() > 80 ? s.substring(0, 80) + "..." : s;
  }
}
