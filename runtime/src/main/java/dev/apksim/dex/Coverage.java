package dev.apksim.dex;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Code coverage of test runs (`instrument.py --coverage`, docs/coverage.md). Off unless {@code -Dapksim.coverage=true};
 * then the translator gives each class of the probed packages ({@link Probes#probed}, the same choice as test-impact
 * recording) one {@code int[]} of hit counters, a slot per method and per (method, source line) the DEX has a line
 * table entry for, and a counter increment at each method's start and at each line's first instruction. Off, classes are
 * byte-identical to before.
 *
 * <p>What the slots mean travels in the class itself, as String constants ({@link #META_FIELD}), so a class from the
 * class cache needs no translating to be reported, and a class nobody loaded can be read from its bytes without
 * running its static initializers ({@code dev.apksim.CoverageDump}).
 */
public final class Coverage {
  public static final boolean ON = Boolean.getBoolean("apksim.coverage") && !Probes.ON;
  /** Static fields {@code $apksimCovMap0, 1, ...} hold the slot table, chunked to stay under the class file's string limit. */
  public static final String META_FIELD = "$apksimCovMap";
  static final String COUNTERS_FIELD = "$apksimCov";
  static final int CHUNK = 12000;

  private static final Map<String, int[]> COUNTERS = new ConcurrentHashMap<>();

  private Coverage() {}

  /** Called once per class, by its first method to run: makes the counters, which the class keeps in its own field. */
  public static int[] init(Class<?> c, int slots) {
    return COUNTERS.computeIfAbsent(c.getName(), k -> new int[slots]);
  }

  /** An interface has no field of its own to keep them in. */
  public static int[] arr(Class<?> c, int slots) {
    return init(c, slots);
  }

  public static int[] get(Class<?> c) {
    return COUNTERS.get(c.getName());
  }

  /** The counters of every class that ran code, by internal class name (dots for slashes), for the dump. */
  public static Map<String, int[]> counters() {
    return new HashMap<>(COUNTERS);
  }

  /** The class cache's key part for covered classes, so they are never used for an ordinary run, or the reverse. */
  public static String cacheKey() {
    return !ON ? "" : "-cov" + Integer.toHexString(Probes.packages().hashCode());
  }

  /** Slots of the class being translated, and the table that says what they are. */
  static final class Slots {
    private final StringBuilder table = new StringBuilder();
    int count;

    int method(String name, String desc) {
      table.append("M\t").append(count).append('\t').append(name).append('\t').append(desc).append('\n');
      return count++;
    }

    int line(int method, int line) {
      table.append("L\t").append(count).append('\t').append(method).append('\t').append(line).append('\n');
      return count++;
    }

    String[] chunks() {
      String all = table.toString();
      int n = (all.length() + CHUNK - 1) / CHUNK;
      String[] out = new String[n];
      for (int i = 0; i < n; i++) out[i] = all.substring(i * CHUNK, Math.min(all.length(), (i + 1) * CHUNK));
      return out;
    }
  }
}
