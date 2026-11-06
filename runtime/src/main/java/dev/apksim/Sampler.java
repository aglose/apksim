package dev.apksim;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Debug aid (-Dapksim.profile=true): samples startup threads, prints the frames that dominate. */
final class Sampler {
  private static final Map<String, Integer> INCLUSIVE = new HashMap<>();
  private static int samples;

  private Sampler() {}

  static void start() {
    Thread t = new Thread(() -> {
      Set<String> seen = new HashSet<>();
      while (true) {
        for (var e : Thread.getAllStackTraces().entrySet()) {
          String n = e.getKey().getName();
          if (!n.startsWith("main") && !n.startsWith("SDK")) continue;
          StackTraceElement[] st = e.getValue();
          if (st.length == 0) continue;
          seen.clear();
          for (StackTraceElement f : st) {
            String key = f.getClassName() + "." + f.getMethodName();
            if (seen.add(key)) INCLUSIVE.merge(key, 1, Integer::sum);
          }
        }
        samples++;
        try {
          Thread.sleep(5);
        } catch (InterruptedException ex) {
          return;
        }
      }
    }, "apksim-sampler");
    t.setDaemon(true);
    t.start();
  }

  static void report() {
    System.err.println("[apksim] samples=" + samples);
    INCLUSIVE.entrySet().stream()
        .filter(e -> !e.getKey().startsWith("org.junit") && !e.getKey().startsWith("java.lang.Thread")
            && !e.getKey().startsWith("java.util.concurrent"))
        .sorted((a, b) -> b.getValue() - a.getValue())
        .limit(Integer.getInteger("apksim.profile.top", 70))
        .forEach(e -> System.err.printf("%6d  %s%n", e.getValue(), e.getKey()));
  }
}
