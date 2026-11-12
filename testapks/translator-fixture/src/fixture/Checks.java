package fixture;

/**
 * apksim's translator check (scripts/check-translator.sh): R8-built code whose shapes the DEX translator once
 * miscompiled, run after translation. Returns "" when every check passes, else what went wrong.
 */
public final class Checks {
  private Checks() {}

  public static String run() {
    StringBuilder out = new StringBuilder();
    try {
      SyncCache c = new SyncCache();
      expect(out, "SyncCache.lookup(4) empty", 0, c.lookup(4));
      expect(out, "SyncCache.lookup(5)", 1, c.lookup(5));
      expect(out, "SyncCache.lookup(4) cached", 0, c.lookup(4));
      expect(out, "SyncCache.lookup(3) newer entry", 0, c.lookup(3));
      expect(out, "SyncCache.computed", 2, c.computed());
    } catch (RuntimeException e) {
      out.append("SyncCache threw ").append(e).append('\n');
    }
    try {
      expect(out, "super call through an indirect superinterface", "impl+base", new SuperCall.Impl().who());
    } catch (RuntimeException | LinkageError e) {
      out.append("SuperCall threw ").append(e).append('\n');
    }
    try {
      expect(out, "super call through the superclass's interface", "impl+base", new SuperCall2.Impl().who());
    } catch (RuntimeException | LinkageError e) {
      out.append("SuperCall2 threw ").append(e).append('\n');
    }
    try {
      Animate.Ref last = new Animate.Ref();
      float half = Animate.animate(1f, 0f, 100, 1f, now -> now + 50, last);
      expect(out, "animate to its end", 0f, half);
      expect(out, "animate frames", 100L, last.element);
      float cancelled = Animate.animate(1f, 0f, 100, 1f, now -> {
        if (now >= 50) throw new IllegalStateException("cancelled");
        return now + 25;
      }, last);
      expect(out, "animate cancelled midway", 0.5f, cancelled);
      expect(out, "animate cancel reason", "cancelled", last.element);
      expect(out, "animate with scale 0 ends at once", 0f, Animate.animate(1f, 0f, 100, 0f, now -> now + 16, last));
      expect(out, "scaledOrCancelled", 5f, Animate.scaledOrCancelled(0.5f, f -> f + 1));
      expect(out, "scaledOrCancelled cancelled", -3f, Animate.scaledOrCancelled(1.5f, f -> {
        throw new IllegalStateException();
      }));
    } catch (RuntimeException e) {
      out.append("Animate threw ").append(e).append('\n');
    }
    try {
      java.util.HashMap<Integer, Integer> map = new java.util.HashMap<>();
      expect(out, "putIfAbsent new", 1, Shapes.putIfAbsent(map, 7));
      expect(out, "putIfAbsent present", 2, Shapes.putIfAbsent(map, 7));
      java.util.ArrayList<Integer> list = new java.util.ArrayList<>(java.util.List.of(1, 5, 9));
      expect(out, "countUnder", 2, Shapes.countUnder(list, 6));
      expect(out, "sizeTimes", 30L, Shapes.sizeTimes(list, 10));
      float[] acc = new float[1];
      expect(out, "lastLabel", "b", Shapes.lastLabel(1f, "a", "b", f -> {
        if (f == 3) throw new IllegalStateException();
        return f;
      }, acc));
      expect(out, "lastLabel acc", 31f, acc[0]);
      expect(out, "lastLabel no throw", null, Shapes.lastLabel(1f, "a", "b", f -> f, acc));
      Animate.Ref ref = new Animate.Ref();
      expect(out, "lastScale", 5.0625f, Shapes.lastScale(1f, f -> f, ref));
      expect(out, "lastScale cancelled", -2.25f, Shapes.lastScale(1f, f -> {
        if (f == 1) throw new IllegalStateException();
        return f;
      }, ref));
      Animate.Frame twice = f -> {
        if (f == 2) throw new IllegalStateException();
        return f * 2;
      };
      expect(out, "pick a", "a", Shapes.pick(1.5f, "a", "b", f -> f * 2));
      expect(out, "pick b", "b", Shapes.pick(0.5f, "a", "b", f -> f * 2));
      expect(out, "pick a cancelled", "a", Shapes.pick(1.5f, "a", "b", twice));
      expect(out, "pick b cancelled", "b", Shapes.pick(0.5f, "a", "b", twice));
      expect(out, "pickScale a", 2f, Shapes.pickScale(1.5f, "a", "b", f -> f * 2, ref));
      expect(out, "pickScale a label", "a", ref.element);
      expect(out, "pickScale b cancelled", -2f, Shapes.pickScale(0.5f, "a", "b", twice, ref));
      expect(out, "pickScale b label", "b", ref.element);
      java.util.ArrayList<Object> log = new java.util.ArrayList<>();
      Shapes.level = 31;
      Shapes.unregister(log, "x");
      Shapes.level = 29;
      Shapes.LISTENERS.put("y", "t");
      Shapes.unregister(log, "y");
      expect(out, "unregister by level", java.util.List.of("direct", "transport t", "removed"), log);
    } catch (RuntimeException e) {
      out.append("Shapes threw ").append(e).append('\n');
    }
    return out.toString();
  }

  private static void expect(StringBuilder out, String what, Object want, Object got) {
    if (!java.util.Objects.equals(want, got)) out.append(what).append(": expected ").append(want).append(", got ").append(got).append('\n');
  }
}
