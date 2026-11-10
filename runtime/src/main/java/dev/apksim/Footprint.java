package dev.apksim;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This process's memory as the system counts it, for `stats` and scripts/bench.py. On macOS the physical footprint
 * (what Activity Monitor and `footprint PID` show: dirty and compressed memory, the figure memory pressure acts on),
 * read with proc_pid_rusage; on Linux the resident set (VmRSS). Java's own counters miss most of an instance: the
 * AOT cache's written pages, metaspace, the JIT's code and the framework's native allocations.
 */
final class Footprint {
  private Footprint() {}

  /** rusage_info_v0: uuid[16], then u64 user, system, pkg_idle_wkups, interrupt_wkups, pageins, wired, resident, phys_footprint. */
  private static final long PHYS_FOOTPRINT = 72;
  private static final int RUSAGE_SIZE = 96;
  private static MethodHandle rusage;
  private static boolean looked;

  /** Bytes, or -1 if it cannot be read. */
  static long bytes() {
    try {
      if (!HostOs.MAC) return linuxRss();
      MethodHandle h = handle();
      if (h == null) return -1;
      try (Arena a = Arena.ofConfined()) {
        MemorySegment info = a.allocate(RUSAGE_SIZE, 8);
        int r = (int) h.invokeExact((int) ProcessHandle.current().pid(), 0, info);
        return r == 0 ? info.get(ValueLayout.JAVA_LONG, PHYS_FOOTPRINT) : -1;
      }
    } catch (Throwable e) {
      return -1;
    }
  }

  private static synchronized MethodHandle handle() {
    if (!looked) {
      looked = true;
      Linker l = Linker.nativeLinker();
      rusage = l.defaultLookup().find("proc_pid_rusage").map(s -> l.downcallHandle(s,
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
          .orElse(null);
    }
    return rusage;
  }

  private static long linuxRss() throws java.io.IOException {
    for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
      if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
    }
    return -1;
  }

  /** CPU time this process has used, in ms, or -1. */
  static long cpuMs() {
    return ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toMillis).orElse(-1L);
  }
}
