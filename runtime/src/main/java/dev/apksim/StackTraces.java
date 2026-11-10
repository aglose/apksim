package dev.apksim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Stack traces as a device shows them: each framework method once, under its own name. The instrumented framework runs
 * a method as a trampoline (no line number) that calls {@code $$robo$$<class>$<method>}; Robolectric rewrote every
 * exception passing through a call site to hide that, which cost ~145 ms of a cold launch in method-handle chains (see
 * org.robolectric.internal.bytecode.InvokeDynamicSupport). apksim cleans the traces it reports instead, by
 * Robolectric's own rules (ShadowWrangler.stripStackTrace): a {@code $$robo$$} frame takes its method's name, the
 * trampoline frame right after it goes, and so do reflection frames. Causes and suppressed exceptions too.
 */
final class StackTraces {
  private static final String ROBO = "$$robo$$";

  private StackTraces() {}

  /** Cleans {@code t} in place (as Robolectric did) and returns it. */
  static <T extends Throwable> T clean(T t) {
    if (t != null) clean(t, Collections.newSetFromMap(new IdentityHashMap<>()));
    return t;
  }

  private static void clean(Throwable t, Set<Throwable> seen) {
    if (t == null || !seen.add(t)) return;
    try {
      StackTraceElement[] st = t.getStackTrace();
      if (st != null && needs(st)) t.setStackTrace(cleaned(st));
    } catch (RuntimeException e) {
      // A Throwable whose stack trace cannot be set (writableStackTrace false): shown as it is.
    }
    clean(t.getCause(), seen);
    for (Throwable s : t.getSuppressed()) clean(s, seen);
  }

  private static boolean needs(StackTraceElement[] st) {
    for (StackTraceElement e : st) {
      String m = e.getMethodName();
      String c = e.getClassName();
      if (m.startsWith(ROBO) || c.startsWith("sun.reflect.") || c.startsWith("java.lang.reflect.")) return true;
    }
    return false;
  }

  static StackTraceElement[] cleaned(StackTraceElement[] st) {
    List<StackTraceElement> out = new ArrayList<>(st.length);
    StackTraceElement previous = null;
    for (StackTraceElement e : st) {
      String method = e.getMethodName();
      String cls = e.getClassName();
      String file = e.getFileName();
      // The trampoline that called the $$robo$$ body just kept: same method, same class, no line number.
      if (previous != null && method.equals(previous.getMethodName()) && cls.equals(previous.getClassName())
          && file != null && file.equals(previous.getFileName()) && e.getLineNumber() < 0) {
        continue;
      }
      if (method.startsWith(ROBO)) {
        method = method.substring(method.indexOf('$', ROBO.length()) + 1);
        e = new StackTraceElement(cls, method, file, e.getLineNumber());
      } else if (cls.startsWith("sun.reflect.") || cls.startsWith("java.lang.reflect.")) {
        continue;
      }
      out.add(e);
      previous = e;
    }
    return out.toArray(new StackTraceElement[0]);
  }
}
