package dev.apksim.gpu;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Stands in for RuntimeShader in app code (the DEX translator rewrites `new RuntimeShader`). It remembers
 * the source and uniforms so it can produce its pixels on the GPU. The real shader underneath still gets
 * every update, and is what draws if this one cannot.
 */
public class TrackedRuntimeShader extends RuntimeShader {
  /** One compiled program; null pipeline means it stays on the CPU. */
  private record Compiled(AgslTranslator.Program program, long pipeline, String why, long[] uses) {}

  private static final Map<String, Compiled> PROGRAMS = new HashMap<>();
  private static ByteBuffer scratch = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());

  /** Debug summary: for each shader program, whether it runs on the GPU and how much it was used. */
  public static synchronized String report() {
    StringBuilder b = new StringBuilder();
    PROGRAMS.forEach((src, c) -> {
      String first = src.replaceAll("\\s+", " ");
      String where = c.pipeline() == 0 ? "CPU" : c.program().children().isEmpty() ? "GPU" : "GPU(inputs)";
      b.append(String.format("%s gpuRuns=%d created=%d effect=%d %s :: %s | ", where, c.uses()[0], c.uses()[1], c.uses()[2],
          c.why() == null ? "" : c.why(), first.substring(0, Math.min(70, first.length()))));
    });
    return b.append(GpuRenderNode.report()).append(" | ").append(GpuBitmaps.report()).toString();
  }

  public static long renders;
  public static long collectNanos;
  public static long publishNanos;
  public static long reuses;
  public static long renderNanos;

  private boolean traced;

  /** Debug aid (-Dapksim.traceCpuShader=true): shows who is drawing this shader the slow way. */
  @Override
  public long getNativeInstance() {
    if (!traced && Boolean.getBoolean("apksim.traceCpuShader")) {
      traced = true;
      new Throwable("CPU shader request; uniforms " + (compiled.program() == null ? "?" : compiled.program().fields().keySet())).printStackTrace();
    }
    return super.getNativeInstance();
  }

  void countEffect() {
    compiled.uses()[2]++;
  }

  /**
   * What a RenderEffect made from this shader now would run on the GPU: the program and a copy of the
   * uniforms (an effect keeps the uniforms it was created with). Null when it must stay on the CPU: no Metal
   * pipeline, or inputs other than the one layer-content [input].
   */
  EffectProgram effectProgram(String input) {
    if (uniforms == null || compiled.program().children().size() != 1 || !compiled.program().children().get(0).equals(input)) return null;
    ByteBuffer copy = ByteBuffer.allocateDirect(uniforms.capacity()).order(ByteOrder.nativeOrder());
    copy.put(uniforms.duplicate().clear()).clear();
    return new EffectProgram(compiled.pipeline(), compiled.program().uniformBytes(), copy, compiled.uses());
  }

  /** A runtime-shader effect's GPU program with its uniforms as they were when the effect was made. */
  record EffectProgram(long pipeline, int uniformBytes, ByteBuffer uniforms, long[] uses) {}

  private static final int MAX_SLOTS = 32;
  /** Frame counter incremented once per rendered frame in {@link #sweep()}. */
  private static long currentFrame;

  /**
   * Per-draw-site state. One RuntimeShader instance may be shared by many cards/draws in the same frame,
   * each mutating uniforms right before its draw (as Android's RuntimeShader snapshots them into the display
   * list). Giving each (owner, draw-index-in-frame) its own slot with double-buffered bitmaps prevents draws
   * from overwriting one another's pixels or racing HWUI's render thread on the previous frame's bitmap.
   */
  private static final class Slot {
    final WeakReference<Object> owner;
    final Bitmap[] bitmaps = new Bitmap[2];
    int nextBuf;
    BitmapShader output;
    long lastFrame = -1;
    long lastDrawn;
    int lastX;
    int lastY;
    int lastW;
    int lastH;
    float[] lastLocal;
    final byte[] lastUniforms;
    boolean hasUniforms;
    int lastInputSignature;
    long pending;
    int pendingW;
    int pendingH;

    Slot(Object owner, int uniformBytes) {
      this.owner = new WeakReference<>(owner);
      this.lastUniforms = uniformBytes > 0 ? new byte[uniformBytes] : null;
    }

    boolean pendingFits(int w, int h) {
      return pendingW == w && pendingH == h;
    }

    boolean uniformsMatch(ByteBuffer uniforms, int uniformBytes) {
      if (!hasUniforms) return false;
      if (uniformBytes <= 0) return true;
      for (int i = 0; i < uniformBytes; i++) {
        if (lastUniforms[i] != uniforms.get(i)) return false;
      }
      return true;
    }

    void saveUniforms(ByteBuffer uniforms, int uniformBytes) {
      for (int i = 0; i < uniformBytes; i++) lastUniforms[i] = uniforms.get(i);
      hasUniforms = true;
    }

    /** Turns the pixels in scratch into the bitmap shader drawn in place of this shader. */
    void publish(int x, int y, int w, int h) {
      int bytes = w * h * 4;
      int idx = nextBuf;
      nextBuf ^= 1;
      Bitmap b = bitmaps[idx];
      if (b == null || b.getWidth() != w || b.getHeight() != h) {
        b = bitmaps[idx] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      }
      scratch.position(0).limit(bytes);
      b.copyPixelsFromBuffer(scratch);
      scratch.clear();
      output = makeOutput(b, x, y);
    }
  }

  private static BitmapShader makeOutput(Bitmap b, int x, int y) {
    BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    s.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
    Matrix place = new Matrix();
    place.setTranslate(x, y);
    s.setLocalMatrix(place);
    return s;
  }

  private final Compiled compiled;
  private final ByteBuffer uniforms;
  private final float[] transform = new float[6];
  private final float[] values = new float[9];
  private final float[] localValues = new float[9];
  private final Matrix local = new Matrix();
  private final Matrix inverse = new Matrix();
  private final List<Slot> slots = new ArrayList<>(1);

  public TrackedRuntimeShader(String source) {
    super(source);
    compiled = compile(source);
    compiled.uses()[1]++;
    uniforms = compiled.pipeline() != 0
        ? ByteBuffer.allocateDirect(Math.max(16, compiled.program().uniformBytes())).order(ByteOrder.nativeOrder())
        : null;
  }

  private static synchronized Compiled compile(String source) {
    Compiled c = PROGRAMS.get(source);
    if (c != null) return c;
    if (Boolean.getBoolean("apksim.noGpu") || dev.apksim.Snapshot.ON || !dev.apksim.HostOs.MAC) {
      c = new Compiled(null, 0, "disabled", new long[3]);
    } else {
      try {
        GpuNative.ensureLoaded();
        AgslTranslator.Program p = AgslTranslator.translate(source);
        String[] error = new String[1];
        long pipeline = GpuNative.compile(p.msl(), error);
        if (pipeline == 0) {
          System.err.println("[apksim] shader stays on the CPU, Metal rejected it: " + firstLine(error[0]));
        }
        c = new Compiled(p, pipeline, pipeline == 0 ? firstLine(error[0]) : null, new long[3]);
      } catch (AgslTranslator.Unsupported e) {
        c = new Compiled(null, 0, e.getMessage(), new long[3]);
      }
    }
    PROGRAMS.put(source, c);
    return c;
  }

  private static String firstLine(String s) {
    return s == null ? "" : s.lines().filter(l -> l.contains("error:")).findFirst().orElse(s);
  }

  private void put(String name, float[] v) {
    if (uniforms == null) return;
    AgslTranslator.Field f = compiled.program().fields().get(name);
    if (f == null) return;
    int at = f.offset();
    if (f.type().equals("float3x3")) {
      int elems = Math.min(v.length / 9, f.size() / 48);
      for (int e = 0; e < elems; e++) {
        for (int col = 0; col < 3; col++) {
          for (int row = 0; row < 3; row++) {
            uniforms.putFloat(at + e * 48 + col * 16 + row * 4, v[e * 9 + col * 3 + row]);
          }
        }
      }
      return;
    }
    if (f.type().equals("float3") && f.size() > 16) {
      int elems = Math.min(v.length / 3, f.size() / 16);
      for (int e = 0; e < elems; e++) {
        for (int c = 0; c < 3; c++) uniforms.putFloat(at + e * 16 + c * 4, v[e * 3 + c]);
      }
      return;
    }
    for (int i = 0; i < v.length && i * 4 < f.size(); i++) {
      if (f.isInt()) uniforms.putInt(at + i * 4, (int) v[i]);
      else uniforms.putFloat(at + i * 4, v[i]);
    }
  }

  private void putInt(String name, int[] v) {
    if (uniforms == null) return;
    AgslTranslator.Field f = compiled.program().fields().get(name);
    if (f == null) return;
    int at = f.offset();
    if (f.type().equals("int3") && f.size() > 16) {
      int elems = Math.min(v.length / 3, f.size() / 16);
      for (int e = 0; e < elems; e++) {
        for (int c = 0; c < 3; c++) uniforms.putInt(at + e * 16 + c * 4, v[e * 3 + c]);
      }
      return;
    }
    for (int i = 0; i < v.length && i * 4 < f.size(); i++) {
      if (f.isInt()) uniforms.putInt(at + i * 4, v[i]);
      else uniforms.putFloat(at + i * 4, v[i]);
    }
  }

  @Override
  public void setFloatUniform(String name, float a) {
    super.setFloatUniform(name, a);
    put(name, new float[] {a});
  }

  @Override
  public void setFloatUniform(String name, float a, float b) {
    super.setFloatUniform(name, a, b);
    put(name, new float[] {a, b});
  }

  @Override
  public void setFloatUniform(String name, float a, float b, float c) {
    super.setFloatUniform(name, a, b, c);
    put(name, new float[] {a, b, c});
  }

  @Override
  public void setFloatUniform(String name, float a, float b, float c, float d) {
    super.setFloatUniform(name, a, b, c, d);
    put(name, new float[] {a, b, c, d});
  }

  @Override
  public void setFloatUniform(String name, float[] v) {
    super.setFloatUniform(name, v);
    put(name, v);
  }

  @Override
  public void setIntUniform(String name, int a) {
    super.setIntUniform(name, a);
    putInt(name, new int[] {a});
  }

  @Override
  public void setIntUniform(String name, int a, int b) {
    super.setIntUniform(name, a, b);
    putInt(name, new int[] {a, b});
  }

  @Override
  public void setIntUniform(String name, int a, int b, int c) {
    super.setIntUniform(name, a, b, c);
    putInt(name, new int[] {a, b, c});
  }

  @Override
  public void setIntUniform(String name, int a, int b, int c, int d) {
    super.setIntUniform(name, a, b, c, d);
    putInt(name, new int[] {a, b, c, d});
  }

  @Override
  public void setIntUniform(String name, int[] v) {
    super.setIntUniform(name, v);
    putInt(name, v);
  }

  /** Inputs given with setInputShader/setInputBuffer, by name. */
  private final Map<String, Shader> inputs = new HashMap<>();

  @Override
  public void setInputShader(String name, Shader shader) {
    super.setInputShader(name, shader);
    inputs.put(name, shader);
  }

  @Override
  public void setInputBuffer(String name, BitmapShader shader) {
    super.setInputBuffer(name, shader);
    inputs.put(name, shader);
  }

  /**
   * The textures for this shader's `uniform shader` inputs, in order, or null when any is not an image the GPU can
   * sample as Skia would: a BitmapShader of an ARGB_8888 bitmap, CLAMP both ways, linear filtering, no local matrix.
   */
  private long[] inputTextures(int[] signatureOut) {
    List<String> names = compiled.program().children();
    long[] textures = new long[names.size()];
    int signature = 0;
    for (int i = 0; i < textures.length; i++) {
      if (!(inputs.get(names.get(i)) instanceof BitmapShader bs) || bs.getLocalMatrix(local)) return null;
      Bitmap b = org.robolectric.util.ReflectionHelpers.getField(bs, "mBitmap");
      if (b == null || !clamps(bs, "mTileX") || !clamps(bs, "mTileY") || bs.getFilterMode() != BitmapShader.FILTER_MODE_LINEAR) {
        return null;
      }
      textures[i] = GpuBitmaps.texture(b);
      if (textures[i] == 0) return null;
      signature = signature * 31 + System.identityHashCode(b) * 17 + b.getGenerationId();
    }
    signatureOut[0] = signature;
    return textures;
  }

  /** Whether a BitmapShader tile mode field is CLAMP: a TileMode, or (as here) its native value, 0. */
  private static boolean clamps(BitmapShader bs, String field) {
    Object mode = org.robolectric.util.ReflectionHelpers.getField(bs, field);
    return mode == Shader.TileMode.CLAMP || Integer.valueOf(0).equals(mode);
  }

  private long submit(long[] inputTextures, int w, int h) {
    if (inputTextures == null) return GpuNative.submit(compiled.pipeline(), uniforms, compiled.program().uniformBytes(), transform, w, h);
    return GpuNative.submitInputs(compiled.pipeline(), uniforms, compiled.program().uniformBytes(), transform, w, h, inputTextures,
        (1 << inputTextures.length) - 1);
  }

  @Override
  public void setColorUniform(String name, int color) {
    super.setColorUniform(name, color);
    float a = Color.alpha(color) / 255f;
    put(name, new float[] {Color.red(color) / 255f * a, Color.green(color) / 255f * a, Color.blue(color) / 255f * a, a});
  }

  @Override
  public void setColorUniform(String name, long color) {
    super.setColorUniform(name, color);
    putColor(name, Color.valueOf(color));
  }

  @Override
  public void setColorUniform(String name, Color color) {
    super.setColorUniform(name, color);
    if (color != null) putColor(name, color);
  }

  private void putColor(String name, Color color) {
    Color srgb = color.convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
    float a = srgb.alpha();
    put(name, new float[] {srgb.red() * a, srgb.green() * a, srgb.blue() * a, a});
  }

  private Slot takeSlot(Object owner) {
    for (int i = 0; i < slots.size(); i++) {
      Slot s = slots.get(i);
      if (s.owner.get() == owner && s.lastFrame != currentFrame) {
        s.lastFrame = currentFrame;
        return s;
      }
    }
    if (slots.size() >= MAX_SLOTS) return null;
    Slot s = new Slot(owner, compiled.program().uniformBytes());
    s.lastFrame = currentFrame;
    slots.add(s);
    WITH_SLOTS.add(this);
    return s;
  }

  /**
   * The shader's pixels for this area of the canvas, as an ordinary bitmap shader that is cheap for the
   * CPU renderer to draw, or null if this one must draw on the CPU.
   */
  Shader gpuOutput(Canvas c, int x, int y, int w, int h) {
    if (uniforms == null || w <= 0 || h <= 0 || (long) w * h * 4 > (1L << 28)) return null;
    synchronized (TrackedRuntimeShader.class) {
      // Inputs (`uniform shader`) drawn as a paint: image BitmapShaders are bound as textures; anything else, the CPU.
      long[] inputTextures = null;
      int[] sig = {0};
      if (!compiled.program().children().isEmpty() && (inputTextures = inputTextures(sig)) == null) return null;
      int inputSig = sig[0];
      getLocalMatrix(local);
      local.getValues(localValues);
      if (localValues[6] != 0f || localValues[7] != 0f || localValues[8] != 1f) return null;
      boolean allowPipeline = c instanceof RecordingCanvas;
      Object owner = (c instanceof RecordingCanvas rc && rc.mNode != null) ? rc.mNode : c;
      Slot slot = takeSlot(owner);
      int uniformBytes = compiled.program().uniformBytes();
      if (slot == null) {
        // Many distinct draws of one shader in one frame: render synchronously into a one-off bitmap.
        if (!buildTransform(x, y)) return null;
        long handle = submit(inputTextures, w, h);
        if (!GpuNative.collect(handle, scratchFor(w, h))) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        scratch.position(0).limit(w * h * 4);
        b.copyPixelsFromBuffer(scratch);
        scratch.clear();
        renders++;
        compiled.uses()[0]++;
        return makeOutput(b, x, y);
      }
      boolean sameMatrix = slot.lastLocal != null && Arrays.equals(localValues, slot.lastLocal);
      boolean sameArea = slot.output != null && sameMatrix && x == slot.lastX && y == slot.lastY && w == slot.lastW && h == slot.lastH;
      if (sameArea && slot.uniformsMatch(uniforms, uniformBytes) && inputSig == slot.lastInputSignature) {
        if (slot.pending != 0) {
          // Animation just stopped on the previous frame: publish its pending result now.
          long c0 = System.nanoTime();
          boolean ok = GpuNative.collect(slot.pending, slot.pendingFits(w, h) ? scratchFor(w, h) : null);
          collectNanos += System.nanoTime() - c0;
          slot.pending = 0;
          long p0 = System.nanoTime();
          if (ok && slot.pendingFits(w, h)) slot.publish(x, y, w, h);
          publishNanos += System.nanoTime() - p0;
        }
        slot.lastDrawn = System.nanoTime();
        reuses++;
        return slot.output;
      }
      if (!buildTransform(x, y)) return null;
      long t0 = System.nanoTime();
      boolean pipeline = allowPipeline && sameArea;
      if (slot.pending != 0) {
        // Last frame's GPU work: normally done by now. Only read back if we will publish it.
        boolean keep = pipeline && slot.pendingFits(w, h);
        long c0 = System.nanoTime();
        boolean ok = GpuNative.collect(slot.pending, keep ? scratchFor(w, h) : null);
        collectNanos += System.nanoTime() - c0;
        slot.pending = 0;
        if (ok && keep) {
          long p0 = System.nanoTime();
          slot.publish(x, y, w, h);
          publishNanos += System.nanoTime() - p0;
        }
      }
      slot.lastDrawn = System.nanoTime();
      if (pipeline) {
        // An animation: start this frame's pixels and show last frame's, one frame behind, so the
        // main thread never waits for the GPU.
        slot.pending = submit(inputTextures, w, h);
        slot.pendingW = w;
        slot.pendingH = h;
        IN_FLIGHT.add(this);
      } else {
        // First appearance, move/resize, or offscreen Canvas: wait for exact pixels for this draw.
        long handle = submit(inputTextures, w, h);
        if (!GpuNative.collect(handle, scratchFor(w, h))) return null;
        slot.publish(x, y, w, h);
      }
      renderNanos += System.nanoTime() - t0;
      renders++;
      compiled.uses()[0]++;
      slot.lastX = x;
      slot.lastY = y;
      slot.lastW = w;
      slot.lastH = h;
      if (slot.lastLocal == null) slot.lastLocal = new float[9];
      System.arraycopy(localValues, 0, slot.lastLocal, 0, 9);
      slot.saveUniforms(uniforms, uniformBytes);
      slot.lastInputSignature = inputSig;
      return slot.output;
    }
  }

  private boolean buildTransform(int x, int y) {
    if (!local.invert(inverse)) return false;
    inverse.getValues(values);
    // Target pixel (px, py) is canvas point (px + x, py + y); carry that into shader space.
    transform[0] = values[0];
    transform[1] = values[1];
    transform[2] = values[0] * x + values[1] * y + values[2];
    transform[3] = values[3];
    transform[4] = values[4];
    transform[5] = values[3] * x + values[4] * y + values[5];
    return true;
  }

  /** Shaders with at least one pending GPU frame: held strongly until collected so native targets never leak. */
  private static final java.util.Set<TrackedRuntimeShader> IN_FLIGHT =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  /** Shaders holding cached slots: held weakly so abandoned shaders collect, while live ones drop dead owners. */
  private static final java.util.Set<TrackedRuntimeShader> WITH_SLOTS =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  /**
   * Once a frame, on the main thread: advances the frame counter and sweeps stale slots. A shader the app
   * stops drawing mid-animation (a page closing) would otherwise hold its full-size target texture for good,
   * and slots whose RenderNodes were discarded would hold their bitmaps until the shader itself is collected.
   */
  public static void sweep() {
    synchronized (TrackedRuntimeShader.class) {
      currentFrame++;
      if (IN_FLIGHT.isEmpty() && WITH_SLOTS.isEmpty()) return;
      long stale = System.nanoTime() - 1_000_000_000L;
      for (Iterator<TrackedRuntimeShader> it = IN_FLIGHT.iterator(); it.hasNext(); ) {
        TrackedRuntimeShader shader = it.next();
        boolean anyPending = false;
        for (Iterator<Slot> si = shader.slots.iterator(); si.hasNext(); ) {
          Slot slot = si.next();
          if (slot.pending == 0) continue;
          boolean dead = slot.owner.get() == null;
          boolean idle = slot.lastDrawn - stale <= 0;
          if (dead || idle) {
            boolean keep = !dead && slot.pendingFits(slot.lastW, slot.lastH);
            boolean ok = GpuNative.collect(slot.pending, keep ? scratchFor(slot.pendingW, slot.pendingH) : null);
            slot.pending = 0;
            if (ok && keep) slot.publish(slot.lastX, slot.lastY, slot.lastW, slot.lastH);
          } else {
            anyPending = true;
          }
        }
        if (!anyPending) it.remove();
      }
      for (Iterator<TrackedRuntimeShader> it = WITH_SLOTS.iterator(); it.hasNext(); ) {
        TrackedRuntimeShader shader = it.next();
        for (Iterator<Slot> si = shader.slots.iterator(); si.hasNext(); ) {
          Slot slot = si.next();
          if (slot.pending != 0) continue;
          boolean dead = slot.owner.get() == null;
          boolean idle = slot.lastDrawn - stale <= 0;
          if (dead || (idle && shader.slots.size() > 1)) si.remove();
        }
        if (shader.slots.isEmpty()) it.remove();
      }
    }
  }

  private static ByteBuffer scratchFor(int w, int h) {
    int bytes = w * h * 4;
    if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    return scratch;
  }
}
