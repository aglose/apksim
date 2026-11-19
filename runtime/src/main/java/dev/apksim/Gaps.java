package dev.apksim;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gap tripwire: framework native methods that no shadow covers and that the app (or the framework on its behalf)
 * actually called. Robolectric leaves such a native with its default body (0, false, null), so the app goes on with a
 * wrong answer and fails later with nothing naming the cause (issue 514: Visualizer's natives). InvokeDynamicSupport
 * binds only those sites through {@link #count}; every other site is bound as before, so nothing is added to a call
 * that is not a gap. {@code gaps} lists them, and the exit line (Exits.log) says how many.
 * {@code -Dapksim.gaps=false} (cold launches; a standby has read it by then) binds them plainly.
 */
public final class Gaps {
  private Gaps() {}

  /** Read by InvokeDynamicSupport when a site binds. */
  public static final boolean ON = !"false".equals(System.getProperty("apksim.gaps"));

  private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
  private static final MethodHandle HIT;

  static {
    try {
      HIT = MethodHandles.lookup().findVirtual(AtomicLong.class, "incrementAndGet", MethodType.methodType(long.class))
          .asType(MethodType.methodType(void.class, AtomicLong.class));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * {@code mh} (the unshadowed native's body, whose type is the site's) with a count of its calls under
   * "Class.method(descriptor)" in front of it; {@code type} has the receiver first for an instance method.
   */
  public static MethodHandle count(Class<?> owner, String name, MethodType type, boolean isStatic, MethodHandle mh) {
    MethodType sig = isStatic ? type : type.dropParameterTypes(0, 1);
    String key = owner.getName() + "." + name + sig.toMethodDescriptorString();
    AtomicLong n = COUNTS.computeIfAbsent(key, k -> new AtomicLong());
    return MethodHandles.foldArguments(mh, MethodHandles.insertArguments(HIT, 0, n));
  }

  /** A new launch's counts (the standby's warm-up and the previous test run's calls are not the app's). */
  public static void reset() {
    for (AtomicLong n : COUNTS.values()) n.set(0);
  }

  /** The gaps called so far, most called first. */
  private static List<Map.Entry<String, Long>> called() {
    List<Map.Entry<String, Long>> out = new ArrayList<>();
    for (Map.Entry<String, AtomicLong> e : COUNTS.entrySet()) {
      long c = e.getValue().get();
      if (c > 0) out.add(Map.entry(e.getKey(), c));
    }
    out.sort((a, b) -> !a.getValue().equals(b.getValue()) ? Long.compare(b.getValue(), a.getValue())
        : a.getKey().compareTo(b.getKey()));
    return out;
  }

  /** {@code gaps}: one "Class.method(desc): xN" per unshadowed native called, joined as every answer is. */
  public static String report() {
    List<Map.Entry<String, Long>> l = called();
    if (l.isEmpty()) return "no unshadowed framework natives called" + (ON ? "" : " (apksim.gaps=false: not counted)");
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Long> e : l) sb.append(sb.length() > 0 ? "\n" : "").append(e.getKey()).append(": x").append(e.getValue());
    return sb.toString();
  }

  /** The exit line, or null when nothing was called. */
  static String exitLine() {
    List<Map.Entry<String, Long>> l = called();
    if (l.isEmpty()) return null;
    StringBuilder sb = new StringBuilder("[apksim] gaps: " + l.size() + " unshadowed framework natives called: ");
    for (int i = 0; i < l.size() && i < 8; i++) {
      String k = l.get(i).getKey();
      sb.append(i > 0 ? ", " : "").append(k, 0, k.indexOf('(')).append(" x").append(l.get(i).getValue());
    }
    if (l.size() > 8) sb.append(", ...");
    return sb.append(" (apksim gaps)").toString();
  }
}
