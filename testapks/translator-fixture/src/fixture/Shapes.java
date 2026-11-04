package fixture;

/**
 * Shapes around exception handlers and register reuse. [putIfAbsent] (the R8 and D8 builds) and [unregister] (the D8
 * debug build) failed with the translator before its liveness fix; the others pass either way but keep handler paths
 * (one handler stub per throwing call, constants rematerialized in handlers) covered.
 */
final class Shapes {
  private Shapes() {}

  /**
   * Returns a constant after a synchronized block: R8 and D8 put it in the lock's register (`const/4 v1, 1; return v1`)
   * inside the catch-all range whose handler does `monitor-exit v1`. Counting the return as throwing merged the int
   * with the lock, and both constants came back as 0.
   */
  static int putIfAbsent(java.util.HashMap<Integer, Integer> map, int key) {
    synchronized (map) {
      if (map.containsKey(key)) return 2;
      map.put(key, key);
    }
    return 1;
  }

  static int countUnder(java.util.ArrayList<Integer> list, int limit) {
    int n = 0;
    synchronized (list) {
      for (Integer v : list) if (v < limit) n++;
    }
    return n;
  }

  static long sizeTimes(java.util.ArrayList<Integer> list, long k) {
    long s;
    synchronized (list) {
      s = list.size();
    }
    return s * k;
  }

  /** The handler reads [label], redefined before every call that can throw. */
  static Object lastLabel(float scale, Object a, Object b, Animate.Frame frames, float[] out) {
    Object label = null;
    float acc = scale;
    int i = 0;
    try {
      while (true) {
        float t = acc * 2f;
        acc = t + 1f;
        label = (i & 1) == 0 ? a : b;
        frames.next(i);
        i++;
        if (acc > 100f) break;
      }
    } catch (IllegalStateException e) {
      out[0] = acc;
      return label;
    }
    out[0] = acc;
    return null;
  }

  static float lastScale(float scale, Animate.Frame frames, Animate.Ref ref) {
    float result = scale;
    try {
      for (int i = 0; i < 4; i++) {
        float s = result * 1.5f;
        Object box = ref;
        result = s;
        ref = new Animate.Ref();
        ref.element = box;
        frames.next(i);
      }
    } catch (IllegalStateException e) {
      ref.element = e;
      return -result;
    }
    return result;
  }

  /** Two calls that can throw with a branch between them; the handler reads what the branch chose. */
  static Object pick(float scale, Object a, Object b, Animate.Frame frames) {
    Object label = null;
    try {
      long t = frames.next(1);
      float f = scale * t;
      label = f > 2f ? a : b;
      frames.next(2);
      return label;
    } catch (IllegalStateException e) {
      return label;
    }
  }

  static float pickScale(float scale, Object a, Object b, Animate.Frame frames, Animate.Ref out) {
    Object label = null;
    float f = scale;
    try {
      long t = frames.next(1);
      f = scale * t;
      if (f > 2f) {
        f -= 1f;
        label = a;
      } else {
        f += 1f;
        label = b;
      }
      frames.next(2);
    } catch (IllegalStateException e) {
      out.element = label;
      return -f;
    }
    out.element = label;
    return f;
  }

  static int level = 31;
  static final java.util.HashMap<Object, Object> LISTENERS = new java.util.HashMap<>();

  /**
   * The shape of AndroidX's LocationManagerCompat.unregisterGnssMeasurementsCallback: an SDK-level check, then a
   * synchronized block, and one return for both. D8 puts that return inside the lock's catch-all range and gives the
   * level and the lock one register; counting the handler's registers live at the return block's entry merged the
   * int with the lock, so the check compared null with null and always took the old-SDK path.
   */
  static void unregister(java.util.List<Object> log, Object callback) {
    if (level >= 30) {
      log.add("direct");
    } else {
      synchronized (LISTENERS) {
        Object t = LISTENERS.remove(callback);
        if (t != null) {
          if (t instanceof String) log.add("transport " + t);
          log.add("removed");
        }
      }
    }
  }
}
