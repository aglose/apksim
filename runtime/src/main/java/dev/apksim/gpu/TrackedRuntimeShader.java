package dev.apksim.gpu;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
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

  private final Compiled compiled;
  private final ByteBuffer uniforms;
  private final float[] transform = new float[6];
  private final float[] values = new float[9];
  private final Matrix local = new Matrix();
  private final Matrix inverse = new Matrix();
  private Bitmap bitmap;
  private BitmapShader output;
  private boolean changed = true;
  private int lastX;
  private int lastY;
  private int lastW;
  private int lastH;
  private float[] lastLocal;

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
    if (Boolean.getBoolean("apksim.noGpu") || !dev.apksim.HostOs.MAC) {
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
    int size = f.type().equals("float3x3") ? 12 : v.length;
    int at = f.offset();
    if (f.type().equals("float3x3")) {
      for (int col = 0; col < 3; col++) {
        for (int row = 0; row < 3; row++) put(at + col * 16 + row * 4, v[col * 3 + row]);
      }
      return;
    }
    for (int i = 0; i < size && i * 4 < f.size(); i++) {
      if (f.isInt()) {
        int asInt = (int) v[i];
        if (uniforms.getInt(at + i * 4) != asInt) changed = true;
        uniforms.putInt(at + i * 4, asInt);
      } else {
        put(at + i * 4, v[i]);
      }
    }
  }

  private void put(int at, float v) {
    if (uniforms.getFloat(at) != v) {
      uniforms.putFloat(at, v);
      changed = true;
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
    put(name, new float[] {a});
  }

  @Override
  public void setIntUniform(String name, int a, int b) {
    super.setIntUniform(name, a, b);
    put(name, new float[] {a, b});
  }

  @Override
  public void setIntUniform(String name, int a, int b, int c) {
    super.setIntUniform(name, a, b, c);
    put(name, new float[] {a, b, c});
  }

  @Override
  public void setIntUniform(String name, int a, int b, int c, int d) {
    super.setIntUniform(name, a, b, c, d);
    put(name, new float[] {a, b, c, d});
  }

  /** Inputs given with setInputShader/setInputBuffer, by name. */
  private final Map<String, Shader> inputs = new HashMap<>();
  /** The input images' identities and generations when last drawn: a change means new pixels. */
  private int inputSignature;

  @Override
  public void setInputShader(String name, Shader shader) {
    super.setInputShader(name, shader);
    inputs.put(name, shader);
    changed = true;
  }

  @Override
  public void setInputBuffer(String name, BitmapShader shader) {
    super.setInputBuffer(name, shader);
    inputs.put(name, shader);
    changed = true;
  }

  /**
   * The textures for this shader's `uniform shader` inputs, in order, or null when any is not an image the GPU can
   * sample as Skia would: a BitmapShader of an ARGB_8888 bitmap, CLAMP both ways, linear filtering, no local matrix.
   */
  private long[] inputTextures() {
    java.util.List<String> names = compiled.program().children();
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
    if (signature != inputSignature) {
      inputSignature = signature;
      changed = true;
    }
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

  /**
   * The shader's pixels for this area of the canvas, as an ordinary bitmap shader that is cheap for the
   * CPU renderer to draw, or null if this one must draw on the CPU.
   */
  Shader gpuOutput(int x, int y, int w, int h) {
    if (uniforms == null || w <= 0 || h <= 0 || (long) w * h * 4 > (1L << 28)) return null;
    // Inputs (`uniform shader`) drawn as a paint: image BitmapShaders are bound as textures; anything else, the CPU.
    long[] inputTextures = null;
    if (!compiled.program().children().isEmpty() && (inputTextures = inputTextures()) == null) return null;
    getLocalMatrix(local);
    local.getValues(values);
    boolean sameMatrix = lastLocal != null && Arrays.equals(values, lastLocal);
    if (!changed && sameMatrix && output != null && x == lastX && y == lastY && w == lastW && h == lastH) {
      reuses++;
      return output;
    }
    if (!local.invert(inverse)) return null;
    inverse.getValues(values);
    // Target pixel (px, py) is canvas point (px + x, py + y); carry that into shader space.
    transform[0] = values[0];
    transform[1] = values[1];
    transform[2] = values[0] * x + values[1] * y + values[2];
    transform[3] = values[3];
    transform[4] = values[4];
    transform[5] = values[3] * x + values[4] * y + values[5];
    long t0 = System.nanoTime();
    boolean sameArea = output != null && sameMatrix && x == lastX && y == lastY && w == lastW && h == lastH;
    if (pending != 0) {
      // Last frame's GPU work: normally done by now.
      long c0 = System.nanoTime();
      boolean ok = GpuNative.collect(pending, pendingFits(w, h) ? scratchFor(w, h) : null);
      collectNanos += System.nanoTime() - c0;
      long done = pending;
      pending = 0;
      long p0 = System.nanoTime();
      if (ok && done != 0 && pendingFits(w, h)) publish(x, y, w, h);
      publishNanos += System.nanoTime() - p0;
    }
    lastDrawn = System.nanoTime();
    if (sameArea) {
      // An animation: start this frame's pixels and show last frame's, one frame behind, so the
      // main thread never waits for the GPU.
      pending = submit(inputTextures, w, h);
      pendingW = w;
      pendingH = h;
      IN_FLIGHT.add(this);
    } else {
      // Something moved or appeared: the pixels must match this frame, so wait for them.
      long handle = submit(inputTextures, w, h);
      if (!GpuNative.collect(handle, scratchFor(w, h))) return null;
      publish(x, y, w, h);
    }
    renderNanos += System.nanoTime() - t0;
    renders++;
    compiled.uses()[0]++;
    changed = false;
    lastX = x;
    lastY = y;
    lastW = w;
    lastH = h;
    getLocalMatrix(local);
    lastLocal = new float[9];
    local.getValues(lastLocal);
    return output;
  }

  private long pending;
  private int pendingW;
  private int pendingH;
  private long lastDrawn;

  /** Shaders with a frame on the GPU that no later draw has collected yet. */
  private static final java.util.Set<TrackedRuntimeShader> IN_FLIGHT =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

  /**
   * Once a frame, on the main thread: collects the GPU frame of every shader not drawn for a second. A shader the app
   * stops drawing mid-animation (a page closing) would otherwise hold its full-size target texture for good, since
   * only its next draw collects it; an app grew by its sky shaders on every visit to a screen that animates them. The pixels are kept,
   * so a shader drawn again later still shows its latest frame.
   */
  public static void sweep() {
    if (IN_FLIGHT.isEmpty()) return;
    long stale = System.nanoTime() - 1_000_000_000L;
    for (java.util.Iterator<TrackedRuntimeShader> i = IN_FLIGHT.iterator(); i.hasNext(); ) {
      TrackedRuntimeShader s = i.next();
      if (s.pending != 0 && s.lastDrawn - stale > 0) continue;
      i.remove();
      if (s.pending != 0) {
        boolean ok = GpuNative.collect(s.pending, scratchFor(s.pendingW, s.pendingH));
        s.pending = 0;
        if (ok && s.pendingFits(s.lastW, s.lastH)) s.publish(s.lastX, s.lastY, s.lastW, s.lastH);
      }
    }
  }

  private boolean pendingFits(int w, int h) {
    return pendingW == w && pendingH == h;
  }

  private static ByteBuffer scratchFor(int w, int h) {
    int bytes = w * h * 4;
    if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    return scratch;
  }

  /** Turns the pixels in scratch into the bitmap shader drawn in place of this shader. */
  private void publish(int x, int y, int w, int h) {
    int bytes = w * h * 4;
    if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
      bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }
    scratch.position(0).limit(bytes);
    bitmap.copyPixelsFromBuffer(scratch);
    scratch.clear();
    output = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    output.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
    Matrix place = new Matrix();
    place.setTranslate(x, y);
    output.setLocalMatrix(place);
  }
}
