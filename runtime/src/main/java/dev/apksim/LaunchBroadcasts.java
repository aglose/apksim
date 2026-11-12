package dev.apksim;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code --broadcast 'ACTION;es:KEY=VALUE;ez:KEY=true;ei:...;el:...;ef:...'} (repeatable, -Dapksim.broadcast.N): one
 * broadcast sent to the app's own package once Application.onCreate has returned and before the first activity starts,
 * as {@code adb shell am broadcast -a ACTION -p PKG --es KEY VALUE} would, for debug receivers that set flag overrides.
 * It reaches receivers registered at run time and manifest ones. Values are one plain argument (no ';', so serve's
 * catalog lines and beam allow it). Logs {@code [apksim] broadcast ACTION: result=N}.
 * This class is JDK-only, as serve and beam (which run without Android classes) validate options with it;
 * {@link LaunchBroadcastSender} delivers.
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

  private static String abbreviate(String s) {
    return s.length() > 80 ? s.substring(0, 80) + "..." : s;
  }
}
