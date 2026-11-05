package dev.apksim.dex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which classes run code, per test: test-impact recording (`instrument.py --record-deps`, docs/affected-tests.md).
 * Off unless {@code -Dapksim.deps=true}; then the translator gives every class of the chosen packages a probe at the
 * start of each method ({@link ClassTranslator}), and the classes it reports since the last {@link #mark} are the ones
 * whose code ran in that span.
 *
 * <p>A class's probe compares its own static {@code $apksimDeps} with {@link #epoch} and calls {@link #hit} only when they
 * differ, which stores the current epoch in it: one call per class per span, two static reads otherwise. {@link #mark}
 * moves the epoch on, so every class reports again in the next span, however long ago it was loaded or initialized
 * (classes load once per process; their code runs in every test that uses them). Interfaces cannot have a static field
 * of their own that is not final, so their methods (default and static ones) call {@link #hitInterface}, which keeps
 * the epoch in a ClassValue.
 */
public final class Probes {
  /** -Dapksim.deps=true: Instrument reports what each test reached, on stdout (see Instrument.DepsListener). */
  public static final boolean ON = Boolean.getBoolean("apksim.deps");

  /** Read by every probed method. Moved on by {@link #mark}, on the main thread, between tests. */
  public static int epoch = 1;

  private static final Object LOCK = new Object();
  private static Set<String> current = new HashSet<>();
  private static final ClassValue<int[]> INTERFACE_EPOCH = new ClassValue<>() {
    @Override
    protected int[] computeValue(Class<?> type) {
      return new int[1];
    }
  };

  private Probes() {}

  /** Called by a class's probe the first time its code runs in a span; returns the epoch for it to keep. */
  public static int hit(Class<?> c) {
    int e = epoch;
    synchronized (LOCK) {
      current.add(c.getName());
    }
    return e;
  }

  /** An interface's probe (it has no field of its own to keep the epoch in). */
  public static void hitInterface(Class<?> c) {
    int[] seen = INTERFACE_EPOCH.get(c);
    if (seen[0] != epoch) seen[0] = hit(c);
  }

  /** The classes reached since the last mark, sorted; and starts a new span. */
  public static List<String> mark() {
    Set<String> got;
    synchronized (LOCK) {
      got = current;
      current = new HashSet<>();
      epoch++;
    }
    List<String> out = new ArrayList<>(got);
    out.sort(null);
    return out;
  }

  /**
   * Whether classes of this package get probes: -Dapksim.deps.packages (comma-separated prefixes, `com.example`), else
   * the app package's first two parts (`com.example` for `com.example.app`), which takes in its modules and its test
   * APK. Library code outside them gets none: a change there is a dependency change, which selects every test anyway.
   */
  static boolean probed(String internalName) {
    for (String p : Packages.LIST) {
      if (internalName.startsWith(p)) return true;
    }
    return false;
  }

  /** Read on first use: the app package is known only once the APK's manifest is read. */
  private static final class Packages {
    static final String[] LIST = list();

    private static String[] list() {
      String given = System.getProperty("apksim.deps.packages");
      if (given == null || given.isBlank()) {
        String pkg = System.getProperty("apksim.package", "");
        String[] parts = pkg.split("\\.");
        given = parts.length > 2 ? parts[0] + "." + parts[1] : pkg;
      }
      List<String> out = new ArrayList<>();
      for (String p : given.split(",")) {
        if (!p.isBlank()) out.add(p.trim().replace('.', '/') + "/");
      }
      return out.toArray(new String[0]);
    }
  }

  /** The probed packages, as internal-name prefixes (`com/example/`), comma-separated. */
  public static String packages() {
    return String.join(",", Packages.LIST);
  }

  /** The class cache's key part for probed classes, so they are never used for an ordinary run, or the reverse. */
  public static String cacheKey() {
    return !ON ? "" : "-deps" + Integer.toHexString(String.join(",", Packages.LIST).hashCode());
  }
}
