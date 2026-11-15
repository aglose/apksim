package dev.apksim;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gives the Java heap's unused pages back to the system while an instance (or a standby) is idle. HotSpot's serial
 * collector keeps every page it has touched: the 64 MB young generation is dirty for good after its first fill, empty
 * or not, and the old generation keeps what it held before a full collection shrank it. None of it is in use between
 * frames of a still screen, which is where an instance spends most of its life (~45-60 MB of an idle instance).
 *
 * <p>{@link #idle} (Host, once the screen has been still for a few seconds) arms the native side (native/heaptrim.c)
 * and runs one young collection; {@link #trim} with a full one is for a standby that has just warmed up and for memory
 * pressure. While armed, the end of every collection replaces the free part of each heap space with fresh pages,
 * inside the collection's pause, so no thread can be allocating there. {@link #busy} (the next frame or input, a launch
 * handed to the standby) disarms it: a running app's collections cost what they did, and the young generation is
 * written again as the app allocates, a page fault per 16 KB, once.
 *
 * <p>Off with {@code -Dapksim.heapTrim=false} or {@code APKSIM_HEAP_TRIM=0}, with another collector than the serial one, and on a JDK whose
 * structure table lacks the names read (see heaptrim.c). {@code -Dapksim.heapTrim.log=true} prints each trim.
 */
final class HeapTrim {
  private HeapTrim() {}

  /** The environment variable is for A/B runs of warm launches: a standby boots before any launch's options exist. */
  private static final boolean ON = !"false".equals(System.getProperty("apksim.heapTrim")) && !"0".equals(System.getenv("APKSIM_HEAP_TRIM"));
  private static final boolean LOG = Boolean.getBoolean("apksim.heapTrim.log");
  /** 0 not tried, 1 the heap's spaces were found, -1 unavailable. Written under the class lock, read by busy() without it. */
  private static volatile int state;
  private static final AtomicBoolean running = new AtomicBoolean();
  /** Keeps the JIT from dropping the allocations that fill eden. */
  @SuppressWarnings("unused")
  private static volatile byte[] sink;

  private static synchronized boolean ready() {
    if (state != 0) return state > 0;
    state = -1;
    if (!ON) return false;
    try {
      var hotspot = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
      if (hotspot == null || !"true".equals(hotspot.getVMOption("UseSerialGC").getValue())) return false;
      System.load(HostOs.runtimeLib("libheaptrim").toString());
      Runtime rt = Runtime.getRuntime();
      // What the native side read must be what the JVM says its heap is (eden, a survivor space and the old
      // generation): the proof that it read the right structures. The old generation may grow between the two reads.
      long before = rt.totalMemory();
      long found = nativeInit();
      long after = rt.totalMemory();
      if (found <= 0 || (found != before && found != after)) {
        if (LOG) System.err.println("[apksim] heap trim: off (found " + found + ", the heap is " + after + ")");
        // The callbacks may be registered: they stay disarmed, and only count collections.
        return false;
      }
      state = 1;
    } catch (Throwable e) {
      if (LOG) System.err.println("[apksim] heap trim: off (" + e + ")");
    }
    return state > 0;
  }

  /** Whether the instance is idle, as its owner last said: the trim thread arms only while it still is. */
  private static volatile boolean wanted;

  /** Counts idle() calls, so the trim thread can tell that one came while it was at work. */
  private static final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

  /** The instance has gone idle: trims on a thread of its own, at most one at a time. */
  static void idle() {
    wanted = true;
    requests.incrementAndGet();
    if (!running.compareAndSet(false, true)) return;
    Thread t = new Thread(() -> {
      int seen;
      do {
        // A request that arrives during a trim (busy, then idle again) found this thread running and left: it is
        // served by another round, here or, if it came after the last look, by taking the flag back.
        do {
          seen = requests.get();
          try {
            trim(false);
          } catch (Throwable e) {
            running.set(false);
            return;
          }
        } while (requests.get() != seen);
        running.set(false);
      } while (requests.get() != seen && running.compareAndSet(false, true));
    }, "apksim-heap-trim");
    t.setDaemon(true);
    t.start();
  }

  /** Whether the heap's spaces were found, so trims do something (the check's first question). */
  static boolean available() {
    return ready();
  }

  /** Collections the native side has seen end, 0 before the first trim (for the check). */
  static long collections() {
    return state > 0 ? nativeCollections() : 0;
  }

  /**
   * Arms and collects, on the calling thread. {@code full}: a full collection (a standby that has just warmed up,
   * memory pressure), which also compacts the old generation, and leaves it armed until {@link #busy}; else a young
   * one, a pause of a few milliseconds (up to ~20 just after a launch, whose objects are still young).
   */
  static void trim(boolean full) {
    if (full) wanted = true;
    long t0 = System.nanoTime();
    long fp = LOG ? Footprint.bytes() : 0;
    boolean heap = ready();
    if (heap) {
      nativeArm(true);
      // busy() may have run between idle() and here: it disarmed first, so check again now that this has armed.
      if (!wanted) nativeArm(false);
      if (full) System.gc(); else if (wanted) youngCollection();
    } else if (full) {
      System.gc();
    }
    if (LOG) {
      System.err.printf("[apksim] heap trim: %s, %d ms, footprint %d -> %d MB%n",
          heap ? (full ? "full" : "young") : "unavailable", (System.nanoTime() - t0) / 1_000_000, fp >> 20, Footprint.bytes() >> 20);
    }
  }

  /** The instance is in use again: collections stop trimming. */
  static void busy() {
    wanted = false;
    if (state > 0) nativeArm(false);
  }

  /**
   * Fills what is left of eden with garbage, which makes the next allocation collect the young generation: the JVM
   * has no call for a young collection alone, and a full one stops the app for 100 ms and more.
   */
  private static void youngCollection() {
    long start = nativeCollections();
    long chunks = nativeEdenBytes() / CHUNK + 8;
    for (long i = 0; i < chunks && nativeCollections() == start; i++) sink = new byte[CHUNK];
    sink = null;
  }

  private static final int CHUNK = 1 << 20;

  private static native long nativeInit();

  private static native void nativeArm(boolean on);

  private static native long nativeCollections();

  private static native long nativeEdenBytes();
}
