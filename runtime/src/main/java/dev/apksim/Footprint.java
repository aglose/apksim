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

  /** The system's memory pressure: 1 normal, 2 warning, 4 critical (macOS's levels); 1 where it cannot be read. */
  static int pressure() {
    if (!HostOs.MAC) return 1;
    try {
      MethodHandle h = sysctl();
      if (h == null) return 1;
      try (Arena a = Arena.ofConfined()) {
        MemorySegment value = a.allocate(ValueLayout.JAVA_INT);
        MemorySegment size = a.allocate(ValueLayout.JAVA_LONG);
        size.set(ValueLayout.JAVA_LONG, 0, 4);
        int r = (int) h.invokeExact(a.allocateFrom("kern.memorystatus_vm_pressure_level"), value, size, MemorySegment.NULL, 0L);
        return r == 0 ? value.get(ValueLayout.JAVA_INT, 0) : 1;
      }
    } catch (Throwable e) {
      return 1;
    }
  }

  /**
   * Whether the machine (or its container) is short of memory, for a standby to give way: macOS's memory pressure
   * above normal; on Linux, memory still to be had below a tenth of the limit, and below 256 MB. The limit is the
   * cgroup's (v2 memory.max, or v1's), what a pod's memory budget is, with its reclaimable file cache counted free;
   * without one, the machine's (MemAvailable of MemTotal). Only standbys ask: an instance's own reactions to pressure
   * (Host.Memory) stay macOS's.
   */
  static boolean memoryShort() {
    if (HostOs.MAC) return pressure() > 1;
    long[] m = linuxMemory();
    if (m == null) return false;
    long free = m[0], total = m[1];
    return free < total / 10 && free < 256L << 20;
  }

  /** {free, total} bytes on Linux by the rule above, or null when neither cgroup nor /proc says. */
  static long[] linuxMemory() {
    try {
      long[] cg = cgroupMemory(Path.of("/sys/fs/cgroup/memory.max"), Path.of("/sys/fs/cgroup/memory.current"),
          Path.of("/sys/fs/cgroup/memory.stat"), "inactive_file");
      if (cg == null) {
        cg = cgroupMemory(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"),
            Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"), Path.of("/sys/fs/cgroup/memory/memory.stat"),
            "total_inactive_file");
      }
      if (cg != null) return cg;
      long avail = -1, total = -1;
      for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
        if (line.startsWith("MemAvailable:")) avail = Long.parseLong(line.replaceAll("[^0-9]", "")) << 10;
        else if (line.startsWith("MemTotal:")) total = Long.parseLong(line.replaceAll("[^0-9]", "")) << 10;
      }
      return avail >= 0 && total > 0 ? new long[] {avail, total} : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** A cgroup's {limit - (usage - inactive file cache), limit}, or null without a limit (max, or v1's huge number). */
  static long[] cgroupMemory(Path max, Path current, Path stat, String inactiveKey) throws java.io.IOException {
    if (!Files.isReadable(max) || !Files.isReadable(current)) return null;
    String lim = Files.readString(max).strip();
    if (lim.equals("max") || lim.isEmpty()) return null;
    long limit = Long.parseLong(lim);
    if (limit <= 0 || limit >= 1L << 60) return null;
    long used = Long.parseLong(Files.readString(current).strip());
    if (Files.isReadable(stat)) {
      for (String line : Files.readAllLines(stat)) {
        if (line.startsWith(inactiveKey + " ")) {
          used -= Long.parseLong(line.substring(inactiveKey.length() + 1).strip());
          break;
        }
      }
    }
    return new long[] {Math.max(0, limit - Math.max(0, used)), limit};
  }

  private static MethodHandle sysctl;
  private static boolean sysctlLooked;

  private static synchronized MethodHandle sysctl() {
    if (!sysctlLooked) {
      sysctlLooked = true;
      Linker l = Linker.nativeLinker();
      sysctl = l.defaultLookup().find("sysctlbyname").map(s -> l.downcallHandle(s, FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))).orElse(null);
    }
    return sysctl;
  }

  /** CPU time this process has used, in ms, or -1. */
  static long cpuMs() {
    return ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toMillis).orElse(-1L);
  }
}
