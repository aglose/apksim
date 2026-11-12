package dev.apksim;

import java.util.HashSet;
import java.util.Set;

/**
 * Whether the app's other threads are at work, for {@link Uia}: a UiAutomator wait on a device leaves them real time
 * (its idle wait is half a second of silence, its polls sleep), and tests lean on that without saying so: a click,
 * then a look for what a coroutine on another dispatcher loads. The simulated clock gives them none.
 *
 * <p>At work means RUNNABLE and either running Java code, as the JVM reports each thread (ThreadInfo.isInNative,
 * read for the RUNNABLE ones in one call), or inside a native call with its CPU time still moving: SQLite, image
 * decoding and an interpreted System.nanoTime are native work, and a socket's read or accept is a native wait, both
 * RUNNABLE. macOS updates another thread's CPU time only every few milliseconds (measured: steps of 3-12 ms), so a
 * thread in native counts as at work until its CPU time has stood still for {@link #STILL_NANOS}; one never seen
 * before is watched that long once. A thread waiting for the network's answer therefore does not count, which only
 * the network hooks could tell from an idle connection; nor does one asleep or parked with a timeout (a delay on
 * another dispatcher), which a thread pool's idle workers are too. JIT and GC threads are not Java threads and never
 * count; apksim's own are left out by name. Main thread.
 */
final class UiaWorkers {
  private UiaWorkers() {}

  private static final java.lang.management.ThreadMXBean BEAN = bean();
  /** A thread in a native call is at work until its CPU time has not moved for this long. */
  private static final long STILL_NANOS = 20_000_000L;
  /** Per thread seen in a native call: its CPU time, and when (System.nanoTime) that last changed. Kept across tests. */
  private static final java.util.Map<Long, long[]> NATIVE = new java.util.HashMap<>();
  private static final Set<Long> NOISE = new HashSet<>();
  private static final Set<Long> LAST_BUSY = new HashSet<>();
  private static Thread[] threads = new Thread[64];

  private static java.lang.management.ThreadMXBean bean() {
    try {
      return java.lang.management.ManagementFactory.getThreadMXBean();
    } catch (RuntimeException | LinkageError e) {
      return null;  // a runtime without java.management: workers are not watched
    }
  }

  static void reset() {
    NOISE.clear();
    LAST_BUSY.clear();
  }

  /** The threads found at work by the last {@link #busy} are background noise from here on (until the next test). */
  static void busyIsNoise() {
    NOISE.addAll(LAST_BUSY);
  }

  private static boolean ours(String name) {
    return name.startsWith("apksim-") || name.startsWith("SDK ") || name.equals("main") || name.equals("Reference Handler")
        || name.equals("Finalizer") || name.equals("Signal Dispatcher") || name.equals("Common-Cleaner")
        || name.equals("Attach Listener") || name.equals("Notification Thread") || name.startsWith("JFR ")
        || name.startsWith("JDWP ") || name.equals("process reaper")
        // The framework's own: the render thread works because the main thread draws, which an idle wait itself does.
        || name.equals("RenderThread") || name.startsWith("hwuiTask");
  }

  /** Whether any of the app's threads other than this one is running Java code right now. */
  static boolean busy() {
    if (BEAN == null) return false;
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root.getParent() != null) root = root.getParent();
    int n;
    while ((n = root.enumerate(threads, true)) == threads.length) threads = new Thread[threads.length * 2];
    Thread self = Thread.currentThread();
    long[] ids = null;
    int count = 0;
    for (int i = 0; i < n; i++) {
      Thread t = threads[i];
      threads[i] = null;
      if (t == self || t.getState() != Thread.State.RUNNABLE) continue;
      long id = t.threadId();
      if (NOISE.contains(id) || ours(t.getName())) continue;
      if (ids == null) ids = new long[n];
      ids[count++] = id;
    }
    LAST_BUSY.clear();
    if (Boolean.getBoolean("apksim.uia.traceWorkers")) {
      StringBuilder sb = new StringBuilder("[apksim] uia workers:");
      Thread[] all = new Thread[256];
      int m = root.enumerate(all, true);
      for (int i = 0; i < m; i++) if (!ours(all[i].getName())) sb.append(' ').append(all[i].getName()).append('=').append(all[i].getState());
      System.err.println(sb);
    }
    if (count == 0) return false;
    java.lang.management.ThreadInfo[] infos = BEAN.getThreadInfo(java.util.Arrays.copyOf(ids, count), 0);
    long now = System.nanoTime();
    if (NATIVE.size() > 1024) NATIVE.clear();  // threads long gone
    for (java.lang.management.ThreadInfo info : infos) {
      if (info == null || info.getThreadState() != Thread.State.RUNNABLE || info.isSuspended()) continue;
      long id = info.getThreadId();
      if (!info.isInNative()) {
        LAST_BUSY.add(id);
        continue;
      }
      long cpu = BEAN.getThreadCpuTime(id);
      long[] seen = NATIVE.get(id);
      if (seen == null) {
        NATIVE.put(id, new long[] {cpu, now});
        LAST_BUSY.add(id);
      } else if (seen[0] != cpu) {
        seen[0] = cpu;
        seen[1] = now;
        LAST_BUSY.add(id);
      } else if (now - seen[1] < STILL_NANOS) {
        LAST_BUSY.add(id);
      }
    }
    return !LAST_BUSY.isEmpty();
  }
}
