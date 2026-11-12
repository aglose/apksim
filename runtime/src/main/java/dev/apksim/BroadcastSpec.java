package dev.apksim;

import java.util.ArrayList;
import java.util.List;

/**
 * The grammar of {@code --broadcast} ({@link LaunchBroadcasts}), with no Android types: `apksim serve` and the beam agent
 * run on a plain JVM without the framework and validate launch options with it.
 */
public final class BroadcastSpec {
  public static final int COUNT_MAX = 8;
  public static final int LENGTH_MAX = 1024;

  private BroadcastSpec() {}

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
