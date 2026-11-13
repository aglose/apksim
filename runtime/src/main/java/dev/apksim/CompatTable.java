package dev.apksim;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The platform's compat changes as a device answers them for an app with no override: Robolectric's Compatibility
 * shadow answers `true` for every id but three, so an app targeting 28 got every target-gated behaviour of the latest SDK.
 * The table (`compat-table.tsv`, written by scripts/gen-compat-table.py from a device's `dumpsys platform_compat`) gives
 * each change's enableSinceTargetSdk or disabled flag; {@link #answer} decides from it and the running app's targetSdk
 * clamped to the platform's SDK, as a device's CompatChange.isEnabled does (min(targetSdk, platform) >= since, so a
 * change enabled since 37 is off on an API 36 device for every app, one targeting 37 too),
 * which AppInfo reads from the manifest ({@link #configure}, per launch: a standby boots before the APK is known).
 * Plain Java, no Android types, so scripts/check-compat-table.sh runs it on a plain JVM. The lookup is a binary search
 * and an array increment: it allocates nothing (it sits on hot framework paths).
 * Control command `compat` lists the ids asked about; see {@link #report}.
 */
public final class CompatTable {
  private CompatTable() {}

  /** Answer codes of {@link #answer}. */
  public static final int OFF = 0, ON = 1, UNKNOWN = -1;
  /** The platform's own SDK level: what a package other than the running app counts as (system apps target it). */
  public static final int PLATFORM_SDK = 36;
  /** Robolectric forces CALL_ACTIVITY_RESULT_BEFORE_RESUME off (its activity controller delivers results itself). */
  public static final long CALL_ACTIVITY_RESULT_BEFORE_RESUME = 78294732L;

  private static final int NEVER = Integer.MAX_VALUE;  // disabled
  private static volatile boolean enabled = true;  // -Dapksim.compatTable=false, read at each launch (a warm launch sets it after the hand-off)

  private static volatile Table table;
  private static volatile int targetSdk = -1;
  private static volatile String packageName = "";

  /** Sorted ids with their since level (0: always on, NEVER: disabled), names and per-id counters. */
  static final class Table {
    final long[] ids;
    final int[] since;
    final String[] names;
    final AtomicIntegerArray on, off;
    // Ids asked about that the table lacks: a fixed array scanned linearly (misses are rare, and boxing is not free).
    final long[] unknown = new long[128];
    volatile int unknownCount;
    final AtomicIntegerArray unknownHits = new AtomicIntegerArray(128);

    Table(long[] ids, int[] since, String[] names) {
      this.ids = ids;
      this.since = since;
      this.names = names;
      on = new AtomicIntegerArray(ids.length);
      off = new AtomicIntegerArray(ids.length);
    }
  }

  /** Reads the tab separated table: id, name, since, flags (`disabled`, `logging` or `-`); `#` lines are comments. */
  static Table parse(java.io.Reader in) throws java.io.IOException {
    BufferedReader r = new BufferedReader(in);
    java.util.TreeMap<Long, Object[]> rows = new java.util.TreeMap<>();
    for (String line; (line = r.readLine()) != null; ) {
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] f = line.split("\t");
      int since = f[3].equals("disabled") ? NEVER : Integer.parseInt(f[2]);
      rows.put(Long.parseLong(f[0]), new Object[] {f[1], since});
    }
    int n = rows.size(), i = 0;
    long[] ids = new long[n];
    int[] since = new int[n];
    String[] names = new String[n];
    for (var e : rows.entrySet()) {
      ids[i] = e.getKey();
      names[i] = (String) e.getValue()[0];
      since[i++] = (Integer) e.getValue()[1];
    }
    return new Table(ids, since, names);
  }

  /** A device's CompatChange.isEnabled: the app's target, clamped to the platform's SDK, reaches the change's. */
  static boolean enabledAt(int since, int target) {
    return Math.min(target, PLATFORM_SDK) >= since;
  }

  /** Parsed ahead of the first question (a standby's warm-up, a cold launch's helper thread): it needs no APK. */
  public static void preload() {
    table();
  }

  private static Table table() {
    Table t = table;
    if (t != null) return t;
    synchronized (CompatTable.class) {
      if (table == null) {
        try (InputStream in = CompatTable.class.getResourceAsStream("/dev/apksim/compat-table.tsv")) {
          if (in == null) throw new java.io.FileNotFoundException("compat-table.tsv");
          table = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException e) {  // a malformed line too: log once, answer as before
          System.err.println("[apksim] compat table not loaded: " + e);
          table = new Table(new long[0], new int[0], new String[0]);
        }
      }
      return table;
    }
  }

  /**
   * Called when a launch's manifest is read (AppInfo.load) or by a check: the app whose target the changes follow.
   * Nothing is kept from an earlier launch, the standby's included. A target below 1 means unknown.
   */
  public static void configure(String pkg, int target) {
    packageName = pkg == null ? "" : pkg;
    enabled = !"false".equals(System.getProperty("apksim.compatTable"));
    targetSdk = enabled ? target : -1;
    Table t = table;
    if (t != null) reset(t);
  }

  /** Between tests: the counters start again (the target stays, it is the APK's). */
  public static void resetCounts() {
    Table t = table;
    if (t != null) reset(t);
  }

  private static void reset(Table t) {
    for (int i = 0; i < t.ids.length; i++) {
      t.on.set(i, 0);
      t.off.set(i, 0);
    }
    synchronized (t) {  // countUnknown appends under it
      t.unknownCount = 0;
    }
  }

  /** The target SDK in use, or -1 when none is known (the table is then not consulted). */
  public static int targetSdk() {
    return targetSdk;
  }

  public static String packageName() {
    return packageName;
  }

  /** Whether the table is consulted at all: -Dapksim.compatTable=false, or no target known yet, says no. */
  public static boolean active() {
    return targetSdk > 0;
  }

  /** The answer for the running app: ON, OFF, or UNKNOWN (the id is not in the table, or no target is known). */
  public static int answer(long id) {
    return answer(id, targetSdk, true);
  }

  /** The answer for an app (or the platform) targeting {@code target}; counted when {@code count}. */
  public static int answer(long id, int target, boolean count) {
    if (target <= 0) return UNKNOWN;
    Table t = table();
    int i = Arrays.binarySearch(t.ids, id);
    if (i < 0) {
      if (count) countUnknown(t, id);
      return UNKNOWN;
    }
    // Robolectric keeps this one off whatever the app targets (its activity controller delivers results itself).
    boolean r = id != CALL_ACTIVITY_RESULT_BEFORE_RESUME && enabledAt(t.since[i], target);
    if (count) (r ? t.on : t.off).incrementAndGet(i);
    return r ? ON : OFF;
  }

  /**
   * The answer for a package that is not installed: a device ignores enableSinceTargetSdk then and takes the change's
   * default, on unless it is disabled. UNKNOWN for an id the table lacks (the default is on, which the caller says).
   */
  public static int answerNotInstalled(long id) {
    Table t = table();
    int i = Arrays.binarySearch(t.ids, id);
    if (i < 0) return UNKNOWN;
    return t.since[i] == NEVER ? OFF : ON;
  }

  private static void countUnknown(Table t, long id) {
    int n = t.unknownCount;
    for (int k = 0; k < n; k++) {
      if (t.unknown[k] == id) {
        t.unknownHits.incrementAndGet(k);
        return;
      }
    }
    synchronized (t) {
      n = t.unknownCount;
      for (int k = 0; k < n; k++) {
        if (t.unknown[k] == id) {
          t.unknownHits.incrementAndGet(k);
          return;
        }
      }
      if (n < t.unknown.length) {
        t.unknown[n] = id;
        t.unknownHits.set(n, 1);
        t.unknownCount = n + 1;  // published after the slot is written
      }
    }
  }

  /** How many changes the table has. */
  public static int size() {
    return table().ids.length;
  }

  /**
   * `compat` reply: the changes this process has asked about, one per line, "NAME (ID): on|off xN", ids the table lacks
   * as "ID (not in table): xN"; `compat all` adds the table's size, its count of changes on and off at this target,
   * and the target in use.
   */
  public static String report(boolean all) {
    Table t = table();
    StringBuilder sb = new StringBuilder();
    sb.append("compat: target ").append(targetSdk > 0 ? Integer.toString(targetSdk) : "unknown")
        .append(" of ").append(packageName.isEmpty() ? "?" : packageName)
        .append(enabled ? "" : " (table off: Robolectric's answers)");
    if (all) {
      int on = 0, off = 0;
      sb.append("; table ").append(t.ids.length).append(" changes");
      if (targetSdk > 0) {
        for (int s : t.since) {
          if (enabledAt(s, targetSdk)) on++; else off++;
        }
        sb.append(", ").append(on).append(" on and ").append(off).append(" off at this target");
      }
    }
    int asked = 0;
    for (int i = 0; i < t.ids.length; i++) {
      int a = t.on.get(i), b = t.off.get(i);
      if (a == 0 && b == 0) continue;
      asked++;
      sb.append('\n').append(t.names[i]).append(" (").append(t.ids[i]).append("): ")
          .append(a > 0 && b > 0 ? "on x" + a + ", off x" + b : a > 0 ? "on x" + a : "off x" + b);
      if (t.since[i] == NEVER) sb.append(" [disabled]"); else if (t.since[i] > 0) sb.append(" [since ").append(t.since[i]).append(']');
      if (t.ids[i] == CALL_ACTIVITY_RESULT_BEFORE_RESUME) sb.append(" [forced off by Robolectric]");
    }
    int n = t.unknownCount;
    for (int k = 0; k < n; k++) {
      asked++;
      sb.append('\n').append(t.unknown[k]).append(" (not in table): x").append(t.unknownHits.get(k));
    }
    if (asked == 0) sb.append("; no change asked about yet");
    return sb.toString();
  }
}
