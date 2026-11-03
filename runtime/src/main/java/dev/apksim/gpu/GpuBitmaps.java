package dev.apksim.gpu;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Scaled bitmap draws resampled on Metal. HWUI here samples a scaled bitmap with Skia's CPU pipeline, about 10 ns a
 * pixel: map and photo tiles drawn scaled took most of a frame (the large app's Atlas, ~9 ms per full-screen layer). Here
 * a draw of a bitmap scaled to w x h device pixels gets a copy already that size, made once on the GPU with the same
 * bilinear sampling, which HWUI then draws 1:1 as a plain blit. Copies are kept while they are drawn, and made only
 * for a size asked for again within a few frames, so a zoom (a new size every frame) stays on the CPU. (Tiles placed
 * on rounded edges alternate between two widths as they move, so "again" cannot mean the very next frame.)
 *
 * <p>Main thread only (where Compose records). A node scaled again by HWUI (a graphicsLayer scale) samples the copy a
 * second time, slightly softer than one pass from the original. `-Dapksim.gpuBitmaps=false` turns it off.
 */
final class GpuBitmaps {
  static final boolean ENABLED = !"false".equals(System.getProperty("apksim.gpuBitmaps"))
      && !Boolean.getBoolean("apksim.noGpu") && dev.apksim.HostOs.MAC;
  private static final int MIN_PIXELS = 64 * 64;
  private static final long BUDGET_BYTES = 96L << 20;
  /** Frames a copy or an uploaded source survives without being drawn. */
  private static final int KEEP_FRAMES = 120;
  /** A size asked for again within this many frames gets a copy. */
  private static final int REPEAT_FRAMES = 8;

  private record Key(Bitmap bitmap, int generation, int sl, int st, int sr, int sb, int w, int h) {}

  private static final class Entry {
    final Bitmap scaled;
    long used;

    Entry(Bitmap scaled) {
      this.scaled = scaled;
    }
  }

  private static final class Source {
    final long texture;
    final int generation;
    long used;

    Source(long texture, int generation) {
      this.texture = texture;
      this.generation = generation;
    }
  }

  private static final Map<Key, Entry> SCALED = new HashMap<>();
  /** Sizes asked for and not made yet, with the frame they were last asked for. */
  private static final Map<Key, Long> SEEN = new HashMap<>();
  private static final Map<Bitmap, Source> SOURCES = new IdentityHashMap<>();
  private static ByteBuffer scratch = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
  private static long frame;
  private static long bytes;

  static long made;
  static long hits;
  /** Draws left to HWUI, by reason: [config or size, first ask, over budget, off whole pixels or not scale+translate]. */
  static final long[] passed = new long[4];

  private GpuBitmaps() {}

  /** The bitmap to draw in place of [b] scaled from [src] (null: all of it) to w x h device pixels, or null. */
  static Bitmap scaled(Bitmap b, Rect src, int w, int h) {
    if (!ENABLED || b.isRecycled() || b.getConfig() != Bitmap.Config.ARGB_8888 || (long) w * h < MIN_PIXELS) {
      passed[0]++;
      return null;
    }
    int sl = src == null ? 0 : src.left;
    int st = src == null ? 0 : src.top;
    int sr = src == null ? b.getWidth() : src.right;
    int sb = src == null ? b.getHeight() : src.bottom;
    if (sr - sl == w && sb - st == h || sr <= sl || sb <= st) return null;
    Key key = new Key(b, b.getGenerationId(), sl, st, sr, sb, w, h);
    Entry e = SCALED.get(key);
    if (e != null) {
      e.used = frame;
      hits++;
      return e.scaled;
    }
    Long seen = SEEN.put(key, frame);
    if (seen == null || seen < frame - REPEAT_FRAMES) {
      passed[1]++;
      return null;
    }
    if (bytes + (long) w * h * 4 > BUDGET_BYTES && !makeRoom((long) w * h * 4)) {
      passed[2]++;
      return null;
    }
    Source s = source(b, key.generation());
    if (s == null) return null;
    ByteBuffer out = scratchFor(w * h * 4);
    if (!GpuNative.resample(s.texture, sl, st, (sr - sl) / (float) w, (sb - st) / (float) h, w, h, out)) return null;
    Bitmap scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    out.position(0).limit(w * h * 4);
    scaled.copyPixelsFromBuffer(out);
    out.clear();
    // An opaque source stays opaque, which keeps HWUI on its faster opaque blit.
    scaled.setHasAlpha(b.hasAlpha());
    e = new Entry(scaled);
    e.used = frame;
    SCALED.put(key, e);
    SEEN.remove(key);
    bytes += (long) w * h * 4;
    made++;
    return scaled;
  }

  /** Drops the copies drawn longest ago, but none drawn this frame, until [need] more bytes fit. */
  private static boolean makeRoom(long need) {
    java.util.List<Map.Entry<Key, Entry>> old = new java.util.ArrayList<>();
    for (Map.Entry<Key, Entry> e : SCALED.entrySet()) {
      if (e.getValue().used < frame) old.add(e);
    }
    old.sort(java.util.Comparator.comparingLong(e -> e.getValue().used));
    for (Map.Entry<Key, Entry> e : old) {
      if (bytes + need <= BUDGET_BYTES) break;
      drop(e.getKey());
    }
    return bytes + need <= BUDGET_BYTES;
  }

  private static void drop(Key key) {
    Entry e = SCALED.remove(key);
    bytes -= e.scaled.getByteCount();
    // A display list still drawing it keeps its own reference to the pixels.
    e.scaled.recycle();
  }

  private static Source source(Bitmap b, int generation) {
    Source s = SOURCES.get(b);
    if (s != null && s.generation == generation) {
      s.used = frame;
      return s;
    }
    if (s != null) GpuNative.releaseInput(s.texture);
    SOURCES.remove(b);
    GpuNative.ensureLoaded();
    long texture = GpuNative.newInput(b.getWidth(), b.getHeight());
    if (texture == 0) return null;
    ByteBuffer pixels = scratchFor(b.getByteCount());
    b.copyPixelsToBuffer(pixels);
    GpuNative.upload(texture, pixels, b.getRowBytes());
    pixels.clear();
    s = new Source(texture, generation);
    s.used = frame;
    SOURCES.put(b, s);
    return s;
  }

  private static ByteBuffer scratchFor(int bytes) {
    if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    scratch.clear();
    return scratch;
  }

  /** Once a frame, on the main thread: lets go of copies and sources no longer drawn. */
  static void tick() {
    frame++;
    if (SCALED.isEmpty() && SEEN.isEmpty() && SOURCES.isEmpty()) return;
    long stale = frame - KEEP_FRAMES;
    for (Iterator<Entry> i = SCALED.values().iterator(); i.hasNext(); ) {
      Entry e = i.next();
      if (e.used < stale) {
        bytes -= e.scaled.getByteCount();
        e.scaled.recycle();
        i.remove();
      }
    }
    SEEN.values().removeIf(f -> f < frame - REPEAT_FRAMES);
    for (Iterator<Source> i = SOURCES.values().iterator(); i.hasNext(); ) {
      Source s = i.next();
      if (s.used < stale) {
        GpuNative.releaseInput(s.texture);
        i.remove();
      }
    }
  }

  /** For the `shaders` command. */
  static String report() {
    return String.format("scaled bitmaps: cached=%d (%d MB) made=%d hits=%d sources=%d; to HWUI: small/config=%d new=%d budget=%d"
        + " unaligned=%d", SCALED.size(), bytes >> 20, made, hits, SOURCES.size(), passed[0], passed[1], passed[2], passed[3]);
  }
}
