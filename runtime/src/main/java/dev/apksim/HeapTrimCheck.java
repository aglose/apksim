package dev.apksim;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks HeapTrim on a plain JVM with the serial collector and a small heap (scripts/check-heap-trim.sh, CI): that it
 * finds the heap's spaces on this JDK (a layout it does not know turns it off without a word, so the check is where
 * that shows), that a trim gives eden's pages back, and that the heap survives trims at every collection while three
 * threads allocate, drop and verify checksummed objects: young and full collections, objects promoted, arrays too big
 * for eden allocated straight into the old generation, arming and disarming in between. Memory discarded that a
 * thread was using would show as a failed checksum or a crash. Exits 1 on any failure.
 */
public final class HeapTrimCheck {
  private HeapTrimCheck() {}

  private static final AtomicReference<String> failure = new AtomicReference<>();
  private static final AtomicBoolean stop = new AtomicBoolean();
  private static final AtomicLong verified = new AtomicLong();

  private static void fail(String what) {
    failure.compareAndSet(null, what);
  }

  /** A block whose bytes follow from its seed, tied to the block made before it. */
  private static final class Item {
    final int seed;
    final byte[] data;
    final Item before;
    final int beforeSeed;

    Item(int seed, int length, Item before) {
      this.seed = seed;
      this.data = new byte[length];
      for (int i = 0; i < length; i++) data[i] = value(seed, i);
      this.before = before;
      this.beforeSeed = before == null ? 0 : before.seed;
    }

    static byte value(int seed, int i) {
      return (byte) (seed * 31 + i * 7 + (i >>> 8));
    }

    boolean intact() {
      for (int i = 0; i < data.length; i++) {
        if (data[i] != value(seed, i)) return false;
      }
      return before == null || before.seed == beforeSeed;
    }
  }

  private static void mutate(int thread, int slots, int bigLength) {
    Random random = new Random(thread * 7919L + 1);
    Item[] ring = new Item[slots];
    Item big = null;
    Item last = null;
    for (long n = 0; !stop.get() && failure.get() == null; n++) {
      int i = random.nextInt(slots);
      Item old = ring[i];
      if (old != null && !old.intact()) {
        fail("thread " + thread + ": an object of " + old.data.length + " bytes changed after " + n + " allocations");
        return;
      }
      // Chains two deep at most, so dropped objects die: the previous object only while it is still in the ring.
      last = new Item(random.nextInt(), 64 + random.nextInt(16 * 1024), n % 3 == 0 ? null : unchained(last));
      ring[i] = last;
      verified.incrementAndGet();
      if (bigLength > 0 && n % 400 == 0) {
        if (big != null && !big.intact()) {
          fail("thread " + thread + ": an array allocated in the old generation changed");
          return;
        }
        big = null;  // one at a time: two would not fit beside the rings
        big = new Item(random.nextInt(), bigLength, null);
      }
    }
    for (Item item : ring) {
      if (item != null && !item.intact()) {
        fail("thread " + thread + ": an object changed (found at the end)");
        return;
      }
    }
    if (big != null && !big.intact()) fail("thread " + thread + ": the last big array changed");
  }

  private static Item unchained(Item item) {
    return item == null ? null : new Item(item.seed, item.data.length, null);
  }

  private static long collections(String... names) {
    long n = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      for (String name : names) {
        if (gc.getName().equals(name)) n += gc.getCollectionCount();
      }
    }
    return n;
  }

  public static void main(String[] args) throws Exception {
    long seconds = args.length > 0 ? Long.parseLong(args[0]) : 6;
    if (!HeapTrim.available()) {
      System.err.println("FAIL: HeapTrim did not find the heap's spaces on this JDK (" + System.getProperty("java.vm.version")
          + "): run with -Dapksim.heapTrim.log=true for the reason");
      System.exit(1);
    }
    long eden = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getName().equals("Eden Space")) eden = pool.getUsage().getCommitted();
    }
    long max = Runtime.getRuntime().maxMemory();

    // A trim gives back what eden held: fill most of it with garbage, then compare the footprint around a trim.
    byte[] garbage = null;
    for (long done = 0; done < eden * 3 / 4; done += 1 << 20) {
      garbage = new byte[1 << 20];
      java.util.Arrays.fill(garbage, (byte) 1);
    }
    long before = Footprint.bytes();
    HeapTrim.trim(true);
    long after = Footprint.bytes();
    HeapTrim.busy();
    if (garbage == null || before < 0 || after < 0 || before - after < eden / 3) {
      fail("a trim returned " + ((before - after) >> 20) + " MB of a " + (eden >> 20) + " MB eden that was 3/4 full ("
          + (before >> 20) + " -> " + (after >> 20) + " MB)");
    }

    // Up to ~40% of the heap live (each slot's block and its copy of the one before, ~16 KB), so the old generation
    // fills and full collections happen by themselves.
    int threads = 3;
    int slots = (int) Math.max(64, max * 20 / 100 / threads / 8192);
    // One thread's arrays are larger than eden, so they are allocated in the old generation directly.
    int bigLength = eden + (1 << 20) < max / 5 ? (int) (eden + (1 << 20)) : 0;
    Thread[] workers = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      int thread = t;
      workers[t] = new Thread(() -> {
        try {
          mutate(thread, slots, thread == 0 ? bigLength : 0);
        } catch (Throwable e) {
          fail("thread " + thread + ": " + e);
        }
      }, "apksim-heap-trim-check-" + t);
      workers[t].start();
    }
    long young0 = collections("Copy");
    long full0 = collections("MarkSweepCompact");
    long seen0 = HeapTrim.collections();
    Random random = new Random(42);
    int idles = 0;
    int fulls = 0;
    int busies = 0;
    long end = System.nanoTime() + seconds * 1_000_000_000L;
    while (System.nanoTime() < end && failure.get() == null) {
      int pick = random.nextInt(10);
      if (pick < 6) {
        HeapTrim.idle();  // armed: every collection from here trims, and one young collection is forced
        idles++;
      } else if (pick < 8) {
        HeapTrim.trim(true);
        fulls++;
      } else {
        HeapTrim.busy();
        busies++;
      }
      Thread.sleep(5 + random.nextInt(40));
    }
    stop.set(true);
    for (Thread worker : workers) worker.join();
    HeapTrim.trim(true);
    long young = collections("Copy") - young0;
    long full = collections("MarkSweepCompact") - full0;
    if (young == 0 || full == 0) fail("no collections of both kinds while armed (" + young + " young, " + full + " full)");
    if (HeapTrim.collections() - seen0 < young + full) {
      fail("the native side saw " + (HeapTrim.collections() - seen0) + " collections of " + (young + full));
    }
    if (failure.get() != null) {
      System.err.println("FAIL: " + failure.get());
      System.exit(1);
    }
    System.out.printf("heap trim check passed: heap %d MB, eden %d MB, a trim returned %d MB; %d objects verified through %d young"
        + " and %d full collections, armed %d times, full trims %d, disarmed %d%n", max >> 20, eden >> 20,
        (before - after) >> 20, verified.get(), young, full, idles, fulls, busies);
  }
}
