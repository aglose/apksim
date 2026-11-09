package dev.apksim.rec;

import java.util.Map;

/**
 * The flight recorder's state that translated app code reaches ({@link Db}), and the cutoff every channel's ring keeps
 * back to. The recorder itself (commands, input, screen, checkpoints, saving) is dev.apksim.FlightRecorder.
 */
public final class Rec {
  private Rec() {}

  /** How far back the rings keep (System.currentTimeMillis); the recorder moves it as time and checkpoints pass. */
  private static volatile long keepFrom = Long.MIN_VALUE;

  public static long keepFromWallMs() {
    return keepFrom;
  }

  public static void keepFrom(long wallMs) {
    keepFrom = wallMs;
  }

  /** The launch's channels (-Dapksim.rec, --rec), split: "all" stands for every one. */
  public static java.util.List<String> launchChannels() {
    String spec = System.getProperty("apksim.rec");
    if (spec == null || spec.isBlank() || spec.equals("off")) return java.util.List.of();
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String s : spec.split("[,\\s]+")) {
      if (s.equals("all")) out.addAll(java.util.List.of("input", "logcat", "net", "db", "screen"));
      else if (!s.isEmpty()) out.add(s);
    }
    return out;
  }

  /**
   * The launch's net and db channels, from Main before any app code runs (and before Android's classes are set up,
   * so nothing here touches them): a connection or a query made by Application.onCreate is recorded too. The other
   * channels start with the recorder itself (dev.apksim.FlightRecorder, from Host).
   */
  public static void launch() {
    for (String c : launchChannels()) {
      switch (c) {
        case "net" -> {
          String r = dev.apksim.net.Net.capture(true, true);
          if (r.startsWith("error")) System.err.println("[apksim] rec: " + r);
        }
        case "db" -> db = true;
        case "db+values" -> {
          db = true;
          dbValues = true;
        }
        default -> {
        }
      }
    }
  }

  /** The db channel: SQLite calls the app makes through the framework's SQLiteDatabase and SQLiteStatement. */
  public static volatile boolean db;
  /** Bound values, ContentValues values and bind arguments in the db channel (off: their count and column names only). */
  public static volatile boolean dbValues;
  public static final Ring<Map<String, Object>> DB = new Ring<>(16L << 20, Rec::keepFromWallMs);
}
