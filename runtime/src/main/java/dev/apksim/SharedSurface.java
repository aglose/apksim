package dev.apksim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

/**
 * Spike S1 (docs/spike-s1-shared-surfaces.md), Mac only, nothing runs unless a host asks: `surfacewatch global` or
 * `surfacewatch mach NAME` turns a control connection into a feed of the screen as IOSurfaces. Each presented frame is
 * copied (one memcpy, on the raster thread) into the next of three BGRA IOSurfaces, and the connection gets one line,
 * `f SEQ INDEX EPOCH_US COPY_US`. When the ring is (re)made (first frame, a new display size) it first gets
 * `s GEN W H` plus, for `global`, the three global IOSurface IDs ({@code IOSurfaceLookup} in the host; deprecated), or,
 * for `mach`, nothing more: the three surfaces' Mach ports went to the Mach service NAME the host checked in
 * ({@code IOSurfaceCreateMachPort}, {@code bootstrap_look_up}). One watcher at a time; it ends when the connection
 * closes. A prototype: no back-pressure (the host must read a surface within two frames), no skins or watch mask.
 */
final class SharedSurface {
  private static volatile PrintWriter out;  // the watcher's connection, or null
  private static int mode;                  // 1 global, 2 mach
  private static long seq;
  private static int gen;
  private static boolean loaded;

  private SharedSurface() {}

  static boolean active() {
    return out != null;
  }

  private static native int nativeStart(int mode, String name);

  /** Copies the frame into the ring; returns the index used, or index | 0x100 when the ring was made anew, or -1. */
  private static native int nativeFrame(ByteBuffer pixels, int w, int h);

  private static native int[] nativeIds();

  private static native void nativeStop();

  /** From the raster thread, after {@link SimWindow#present}. */
  static void frame(ByteBuffer pixels, int w, int h) {
    PrintWriter o = out;
    if (o == null) return;
    java.time.Instant i = java.time.Instant.now();  // the present time, as FrameStream's header has it
    long now = i.getEpochSecond() * 1_000_000L + i.getNano() / 1000;
    long t0 = System.nanoTime();
    int r = nativeFrame(pixels, w, h);
    int copyUs = (int) ((System.nanoTime() - t0) / 1000);
    if (r < 0) return;
    StringBuilder b = new StringBuilder(64);
    if ((r & 0x100) != 0) {
      b.append("s ").append(++gen).append(' ').append(w).append(' ').append(h);
      if (mode == 1) for (int id : nativeIds()) b.append(' ').append(id);
      b.append('\n');
    }
    b.append("f ").append(++seq).append(' ').append(r & 0xff).append(' ').append(now).append(' ').append(copyUs);
    o.println(b);
  }

  /** Runs on the control connection's thread until it closes. */
  static void serve(String[] args, BufferedReader in, PrintWriter o) throws IOException {
    if (!HostOs.MAC) {
      o.println("error: surfacewatch is Mac only");
      return;
    }
    int m = args.length > 1 && args[1].equals("mach") ? 2 : args.length > 1 && args[1].equals("global") ? 1 : 0;
    if (m == 0 || (m == 2 && args.length < 3)) {
      o.println("error: usage: surfacewatch global | surfacewatch mach NAME");
      return;
    }
    synchronized (SharedSurface.class) {
      if (out != null) {
        o.println("error: another surfacewatch is attached");
        return;
      }
      if (!loaded) {
        System.load(HostOs.runtimeLib("libsharedsurface").toString());
        loaded = true;
      }
      int rc = nativeStart(m, m == 2 ? args[2] : null);
      if (rc != 0) {
        o.println("error: surfacewatch: native start failed (" + rc + ")");
        return;
      }
      mode = m;
      o.println("ok surfacewatch");
      out = o;
    }
    Control.submit(new Control.Pending(new String[] {"redraw"}));
    try {
      while (in.readLine() != null) {
        // nothing is read: the end of the stream is how a watcher leaves
      }
    } finally {
      synchronized (SharedSurface.class) {
        out = null;
        nativeStop();
      }
    }
  }
}
