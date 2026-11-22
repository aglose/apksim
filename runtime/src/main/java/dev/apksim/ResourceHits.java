package dev.apksim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which resources the app resolved since the last reset (-Dapksim.deps=true; docs/crawl-deps-spike.md): every id passed
 * to CppAssetManager2.GetResource (values, references followed, layout and drawable files, strings) or GetBag (styles,
 * themes and their parents), recorded in apksim's copy of that class. Names are looked up only when read.
 */
public final class ResourceHits {
  /**
   * Running instances only: `instrument.py --record-deps` test runs probe classes too, and nothing there reads resources,
   * so the hook on the hot resource path stays off (the launcher sets apksim.instrument on the JVM's command line).
   */
  public static final boolean ON = Boolean.getBoolean("apksim.deps") && System.getProperty("apksim.instrument") == null;

  private static final Object LOCK = new Object();
  /** Open-addressed set of ids; 0 is never a resource id. */
  private static int[] table = new int[4096];
  private static int size;

  private ResourceHits() {}

  public static void hit(int resid) {
    if (resid == 0) return;
    synchronized (LOCK) {
      int[] t = table;
      int mask = t.length - 1;
      int i = mix(resid) & mask;
      while (true) {
        int v = t[i];
        if (v == resid) return;
        if (v == 0) break;
        i = (i + 1) & mask;
      }
      t[i] = resid;
      if (++size * 2 > t.length) grow();
    }
  }

  private static int mix(int x) {
    x *= 0x9E3779B9;
    return x ^ (x >>> 16);
  }

  private static void grow() {
    int[] old = table;
    table = new int[old.length * 2];
    size = 0;
    for (int v : old) {
      if (v != 0) {
        int mask = table.length - 1;
        int i = mix(v) & mask;
        while (table[i] != 0) i = (i + 1) & mask;
        table[i] = v;
        size++;
      }
    }
  }

  /** The ids seen since the last reset; with reset, starts a new span. */
  public static int[] ids(boolean reset) {
    synchronized (LOCK) {
      int[] out = Arrays.stream(table).filter(v -> v != 0).sorted().toArray();
      if (reset) {
        table = new int[4096];
        size = 0;
      }
      return out;
    }
  }

  /** `type/name` for each id (app's own packages only unless all: framework ids are 0x01......), sorted. */
  public static List<String> names(int[] ids, boolean all) {
    android.content.res.Resources r = org.robolectric.RuntimeEnvironment.getApplication().getResources();
    List<String> out = new ArrayList<>();
    for (int id : ids) {
      if (!all && (id >>> 24) == 0x01) continue;
      try {
        String pkg = r.getResourcePackageName(id);
        String n = r.getResourceTypeName(id) + "/" + r.getResourceEntryName(id);
        out.add((id >>> 24) == 0x7f ? n : pkg + ":" + n);
      } catch (RuntimeException e) {
        out.add(String.format("0x%08x", id));
      }
    }
    out.sort(null);
    return out;
  }
}
