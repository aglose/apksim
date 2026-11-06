package dev.apksim.gpu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Scaled bitmap draws resampled on Metal. HWUI here samples a scaled bitmap with Skia's CPU pipeline, about 10 ns a
 * pixel: map and photo tiles drawn scaled took most of a frame (a map screen, ~9 ms per full-screen layer), and so did
 * full-screen ImageView and BitmapDrawable backgrounds on a tablet (~40 ms). Here a filtered draw of a bitmap scaled
 * onto a recording canvas gets a copy already at its device-pixel size, made once on the GPU with the same bilinear
 * sampling, which HWUI then draws 1:1 as a plain blit. Every `drawBitmap` of a bitmap on a recording canvas comes
 * through here ({@link ShadowGpuRecordingCanvas}): the framework's (ImageView, BitmapDrawable) and the app's
 * (Compose's drawImage).
 *
 * <p>A copy is made only for a draw that holds still: asked for again within a few frames (Compose records again
 * every frame it changes anything), or recorded once and not asked for at another size or position within those
 * frames by its bitmap or by the node it was recorded in, in which case the view that recorded it is invalidated so it
 * records the copy (Views record once and keep their display list). So a zoom (a new size every frame) stays on the CPU. (Tiles placed on rounded edges alternate
 * between two widths as they move, so "again" cannot mean the very next frame.) Copies are kept while they are drawn
 * and dropped longest-unused first past a budget.
 *
 * <p>Only once Host's frame loop runs ({@link #framesRun}; test runs draw no frames). Main thread only (where Views and
 * Compose record). A node scaled again by HWUI (a graphicsLayer scale) samples
 * the copy a second time, slightly softer than one pass from the original. `-Dapksim.gpuBitmaps=false` turns it off.
 */
final class GpuBitmaps {
  static final boolean ENABLED = !"false".equals(System.getProperty("apksim.gpuBitmaps"))
      && !Boolean.getBoolean("apksim.noGpu") && !dev.apksim.Snapshot.ON && dev.apksim.HostOs.MAC;
  private static final int MIN_PIXELS = 64 * 64;
  private static final long BUDGET_BYTES = 96L << 20;
  /** Frames a copy or an uploaded source survives without being drawn. */
  private static final int KEEP_FRAMES = 120;
  /** A size asked for again within this many frames gets a copy; one asked for once and left alone that long, too. */
  private static final int REPEAT_FRAMES = 8;

  /**
   * One resampling: target pixel (x, y) of a w x h copy reads the source at (u0, v0) + (x + 0.5, y + 0.5) * (du, dv),
   * in source pixels. Where the copy sits on the screen is not part of it: a draw that moves by whole pixels keeps
   * its copy.
   */
  private record Key(Bitmap bitmap, int generation, float u0, float v0, float du, float dv, int w, int h) {}

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

  /** A bitmap's last draw: what was asked and when. */
  private record Ask(Key key, long frame) {}

  /** A node's latest recording that asked for anything here ([frame]), and the draws in it asked for the first time. */
  private static final class Recording {
    final long frame;
    final List<Key> firstAsks = new ArrayList<>(2);

    Recording(long frame) {
      this.frame = frame;
    }
  }

  private static final Map<Key, Entry> SCALED = new HashMap<>();
  /** Sizes asked for and not made yet, with the frame they were last asked for. */
  private static final Map<Key, Long> SEEN = new HashMap<>();
  private static final Map<Bitmap, Ask> LAST = new IdentityHashMap<>();
  /** By node: a recording supersedes the one before, so only draws a node has kept for REPEAT_FRAMES are promoted. */
  private static final Map<RenderNode, Recording> RECORDINGS = new IdentityHashMap<>();
  /**
   * The copies each node's latest recording draws. A View records once and keeps its display list, which keeps the
   * copy's pixels however long ago it was asked for, so a copy stays (counted in the budget, never recycled) while a
   * node that drew it in its latest recording still has a display list. (A node that records again without asking
   * for any bitmap here is not seen to let go: its copies stay until it loses its display list or is collected.)
   */
  private static final Map<RenderNode, List<Key>> HELD = new java.util.WeakHashMap<>();
  /** Draws that held still, whose view was invalidated to record them again: the copy is made then. */
  private static final Map<Key, Long> PROMOTED = new HashMap<>();
  private static final Map<Bitmap, Source> SOURCES = new IdentityHashMap<>();
  private static ByteBuffer scratch = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
  private static long frame;
  private static long bytes;
  /** Set once Host's frame loop starts: test runs raster no frames, and nothing would let go of a copy there. */
  private static boolean framesRun;
  /** Set while drawing a copy (whose own draw comes back through here), and by the self-test for HWUI's draws. */
  static boolean bypass;

  static long made;
  static long hits;
  static long promoted;
  /** Draws left to HWUI, by reason: [config or size, first ask, over budget, AA edge off whole pixels or not scale+translate]. */
  static final long[] passed = new long[4];

  private GpuBitmaps() {}

  private static final Matrix MATRIX = new Matrix();
  private static final float[] M = new float[9];
  private static final Rect CLIP = new Rect();
  private static final Rect DST = new Rect();

  /**
   * Draws [b]'s [src] (null: all of it), scaled into the local rect l, t, r, bottom of recording canvas [c], as a
   * GPU-scaled copy drawn 1:1, and returns true; false: draw it as asked. Each edge of the draw must fall on a whole
   * device pixel or be clipped away (a centerCrop image): a fractional edge would be rounded here, before any parent
   * node's transform, where HWUI rounds or anti-aliases it after. The copy is cut to the clip when most of it is
   * clipped away, and samples at the same places as HWUI.
   */
  @SuppressWarnings("deprecation")
  static boolean draw(Canvas c, Bitmap b, Rect src, float l, float t, float r, float bottom, Paint p) {
    if (!ENABLED || bypass || !framesRun || p == null || !p.isFilterBitmap() || !Looper.getMainLooper().isCurrentThread()) {
      return false;
    }
    if (b.isRecycled() || b.getConfig() != Bitmap.Config.ARGB_8888 || b.hasMipMap()) {
      passed[0]++;
      return false;
    }
    // Effects that depend on the transform (a shadow's radius and offset, a blur mask) would change with the
    // identity matrix the copy is drawn under.
    if (p.hasShadowLayer() || p.getMaskFilter() != null || p.getShader() != null) {
      passed[3]++;
      return false;
    }
    int sl = src == null ? 0 : src.left;
    int st = src == null ? 0 : src.top;
    int sr = src == null ? b.getWidth() : src.right;
    int sb = src == null ? b.getHeight() : src.bottom;
    if (sr <= sl || sb <= st || r <= l || bottom <= t) return false;
    c.getMatrix(MATRIX);
    MATRIX.getValues(M);
    if (M[1] != 0 || M[3] != 0 || M[6] != 0 || M[7] != 0 || M[8] != 1 || M[0] <= 0 || M[4] <= 0) {
      passed[3]++;
      return false;
    }
    float dl = M[0] * l + M[2];
    float dt = M[4] * t + M[5];
    float dr = M[0] * r + M[2];
    float db = M[4] * bottom + M[5];
    float du = (sr - sl) / (dr - dl);
    float dv = (sb - st) / (db - dt);
    if (du == 1f && dv == 1f) return false;
    // The device pixels HWUI fills, where an edge is clipped away: Skia rounds a non-AA rect's edges to the nearest
    // pixel, and the clip cuts an anti-aliased one.
    int x0 = (int) Math.floor(dl + 0.5f);
    int y0 = (int) Math.floor(dt + 0.5f);
    int x1 = (int) Math.floor(dr + 0.5f);
    int y1 = (int) Math.floor(db + 0.5f);
    // The clip in device pixels, rounded out (getClipBounds rounds out in local units, so this may be larger).
    if (!c.getClipBounds(CLIP)) return false;
    int cl = (int) Math.floor(M[0] * CLIP.left + M[2]);
    int ct = (int) Math.floor(M[4] * CLIP.top + M[5]);
    int cr = (int) Math.ceil(M[0] * CLIP.right + M[2]);
    int cb = (int) Math.ceil(M[4] * CLIP.bottom + M[5]);
    // Whole pixels, or clipped away (the clip moves with any parent transform, so it stays clipped).
    if (!whole(dl) && dl > cl || !whole(dt) && dt > ct || !whole(dr) && dr < cr || !whole(db) && db < cb) {
      passed[3]++;
      return false;
    }
    int vl = Math.max(x0, cl), vt = Math.max(y0, ct), vr = Math.min(x1, cr), vb = Math.min(y1, cb);
    if (vr <= vl || vb <= vt) return false;
    if ((long) (x1 - x0) * (y1 - y0) > 2L * (vr - vl) * (vb - vt)) {
      x0 = vl;
      y0 = vt;
      x1 = vr;
      y1 = vb;
    }
    int w = x1 - x0;
    int h = y1 - y0;
    if ((long) w * h < MIN_PIXELS) {
      passed[0]++;
      return false;
    }
    RenderNode node = c instanceof RecordingCanvas rc ? rc.mNode : null;
    Bitmap scaled = copy(b, sl + (x0 - dl) * du, st + (y0 - dt) * dv, du, dv, w, h, node);
    if (scaled == null) return false;
    c.save();
    c.setMatrix(null);
    // Dithering only hides banding in gradients; on a 1:1 image it just forces a slower blit.
    boolean dither = p.isDither();
    if (dither) p.setDither(false);
    bypass = true;
    try {
      // A dst rect, not (left, top): that overload would scale the copy for a canvas density that is not its own.
      DST.set(x0, y0, x0 + w, y0 + h);
      c.drawBitmap(scaled, null, DST, p);
    } finally {
      bypass = false;
      if (dither) p.setDither(true);
      c.restore();
    }
    return true;
  }

  private static boolean whole(float v) {
    return Math.abs(v - Math.round(v)) <= 1e-3f;
  }

  /** The copy of [b] for a Key's resampling, or null (not made yet, or cannot be). [node]: where it is recorded. */
  private static Bitmap copy(Bitmap b, float u0, float v0, float du, float dv, int w, int h, RenderNode node) {
    Key key = new Key(b, b.getGenerationId(), u0, v0, du, dv, w, h);
    Recording rec = null;
    if (node != null) {
      rec = RECORDINGS.get(node);
      if (rec == null || rec.frame != frame) {
        RECORDINGS.put(node, rec = new Recording(frame));
        HELD.remove(node);  // a new recording: the last one's copies are no longer drawn from it
      }
    }
    Entry e = SCALED.get(key);
    if (e != null) {
      e.used = frame;
      hits++;
      held(node, key);
      return e.scaled;
    }
    LAST.put(b, new Ask(key, frame));
    if (PROMOTED.remove(key) == null) {
      Long seen = SEEN.put(key, frame);
      if (seen == null || seen < frame - REPEAT_FRAMES) {
        if (rec != null) rec.firstAsks.add(key);
        passed[1]++;
        return null;
      }
    }
    if (bytes + (long) w * h * 4 > BUDGET_BYTES && !makeRoom((long) w * h * 4)) {
      passed[2]++;
      return null;
    }
    Source s = source(b, key.generation());
    if (s == null) return null;
    ByteBuffer out = scratchFor(w * h * 4);
    if (!GpuNative.resample(s.texture, u0, v0, du, dv, w, h, out)) return null;
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
    held(node, key);
    return scaled;
  }

  private static void held(RenderNode node, Key key) {
    if (node != null) HELD.computeIfAbsent(node, n -> new ArrayList<>(2)).add(key);
  }

  /** For the self-test: the copy of [b]'s [src] (null: all) scaled to w x h at a whole-pixel position, or null. */
  static Bitmap scaled(Bitmap b, Rect src, int w, int h) {
    if (!ENABLED) return null;
    int sl = src == null ? 0 : src.left;
    int st = src == null ? 0 : src.top;
    int sr = src == null ? b.getWidth() : src.right;
    int sb = src == null ? b.getHeight() : src.bottom;
    return copy(b, sl, st, (sr - sl) / (float) w, (sb - st) / (float) h, w, h, null);
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

  /** [b] as a Metal texture, uploaded again when its pixels change; 0 if it cannot be. Main thread. */
  static long texture(Bitmap b) {
    if (b.isRecycled() || b.getConfig() != Bitmap.Config.ARGB_8888) return 0;
    Source s = source(b, b.getGenerationId());
    return s == null ? 0 : s.texture;
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

  /** Host's frame loop is starting: from now on frames tick, so copies can be made and let go of. */
  static void framesRun() {
    framesRun = true;
  }

  /** Once a frame, on the main thread: promotes draws that held still, lets go of copies and sources no longer drawn. */
  static void tick() {
    frame++;
    if (SCALED.isEmpty() && SEEN.isEmpty() && SOURCES.isEmpty() && RECORDINGS.isEmpty() && PROMOTED.isEmpty() && LAST.isEmpty()
        && HELD.isEmpty()) {
      return;
    }
    if (!RECORDINGS.isEmpty()) promote();
    for (Iterator<Map.Entry<RenderNode, List<Key>>> i = HELD.entrySet().iterator(); i.hasNext(); ) {
      Map.Entry<RenderNode, List<Key>> h = i.next();
      if (!h.getKey().hasDisplayList()) {
        i.remove();
        continue;
      }
      for (Key key : h.getValue()) {
        Entry e = SCALED.get(key);
        if (e != null) e.used = frame;
      }
    }
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
    LAST.values().removeIf(a -> a.frame < frame - REPEAT_FRAMES);
    // An invalidated view records again on the next frame; one that does not (detached) holds its bitmap no longer.
    PROMOTED.values().removeIf(f -> f < frame - 2 * REPEAT_FRAMES);
    for (Iterator<Source> i = SOURCES.values().iterator(); i.hasNext(); ) {
      Source s = i.next();
      if (s.used < stale) {
        GpuNative.releaseInput(s.texture);
        i.remove();
      }
    }
  }

  /**
   * Nodes that have not recorded again for REPEAT_FRAMES: each draw asked for for the first time in that recording,
   * whose bitmap has been asked for nothing else in a later frame, is promoted, and its view invalidated so that its next
   * recording makes the copy. A draw whose copy another view made meanwhile (two views showing one image) has its
   * view invalidated too, to pick that copy up.
   */
  private static void promote() {
    Map<RenderNode, List<Key>> due = null;
    for (Iterator<Map.Entry<RenderNode, Recording>> i = RECORDINGS.entrySet().iterator(); i.hasNext(); ) {
      Map.Entry<RenderNode, Recording> e = i.next();
      RenderNode node = e.getKey();
      Recording rec = e.getValue();
      if (rec.frame > frame - REPEAT_FRAMES) continue;
      // (An IdentityHashMap entry cannot be read once removed.)
      i.remove();
      for (Key key : rec.firstAsks) {
        if (!SCALED.containsKey(key)) {
          // Asked for at another size since (another view zooming it): not still. At another size in the same
          // frame (a background and a thumbnail of one image) is fine.
          Ask last = LAST.get(key.bitmap());
          if (last != null && !last.key().equals(key) && last.frame() != rec.frame || key.bitmap().isRecycled()
              || key.bitmap().getGenerationId() != key.generation()) {
            continue;
          }
        }
        if (due == null) due = new IdentityHashMap<>();
        due.computeIfAbsent(node, n -> new ArrayList<>(2)).add(key);
      }
    }
    if (due == null) return;
    for (View root : dev.apksim.Host.rootViews()) invalidateRecorders(root, due);
  }

  private static void invalidateRecorders(View v, Map<RenderNode, List<Key>> due) {
    // A view records its background drawable into a node of its own, again each time it records itself.
    List<Key> keys = due.remove(Views.node(Views.NODE, v));
    List<Key> background = due.isEmpty() ? null : due.remove(Views.node(Views.BACKGROUND, v));
    for (List<Key> list : java.util.Arrays.asList(keys, background)) {
      if (list == null) continue;
      for (Key key : list) {
        if (!SCALED.containsKey(key)) PROMOTED.put(key, frame);
        promoted++;
      }
      v.invalidate();
    }
    if (due.isEmpty() || !(v instanceof ViewGroup g)) return;
    for (int i = 0; i < g.getChildCount() && !due.isEmpty(); i++) invalidateRecorders(g.getChildAt(i), due);
  }

  /** View.mRenderNode and mBackgroundRenderNode (hidden), looked up once. */
  private static final class Views {
    static final java.lang.reflect.Field NODE;
    static final java.lang.reflect.Field BACKGROUND;

    static {
      try {
        NODE = View.class.getDeclaredField("mRenderNode");
        NODE.setAccessible(true);
        BACKGROUND = View.class.getDeclaredField("mBackgroundRenderNode");
        BACKGROUND.setAccessible(true);
      } catch (NoSuchFieldException e) {
        throw new IllegalStateException(e);
      }
    }

    static RenderNode node(java.lang.reflect.Field f, View v) {
      try {
        return (RenderNode) f.get(v);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** For the `shaders` command. */
  static String report() {
    return String.format("scaled bitmaps: cached=%d (%d MB) made=%d hits=%d promoted=%d sources=%d; to HWUI: small/config=%d"
        + " new=%d budget=%d unaligned=%d", SCALED.size(), bytes >> 20, made, hits, promoted, SOURCES.size(), passed[0],
        passed[1], passed[2], passed[3]);
  }
}
