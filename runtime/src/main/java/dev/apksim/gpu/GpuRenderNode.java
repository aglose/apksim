package dev.apksim.gpu;

import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.media.Image;
import android.media.ImageReader;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stands in for RenderNode in app code (the DEX translator rewrites `new RenderNode`), which is how Compose's
 * graphicsLayer reaches HWUI. A layer whose RenderEffect is a runtime shader over its own content
 * (`createRuntimeShaderEffect(shader, "content")`) would have HWUI run the shader per pixel on the CPU. Here
 * such a node keeps the app's drawing in a separate content node instead; every frame that content is
 * rasterized by its own HardwareRenderer, uploaded as a Metal texture, the translated shader runs on the GPU,
 * and this node draws the result as a bitmap. Content can change without this node being recorded again
 * (video in a TextureView, child layers), so it is redrawn on every frame while the effect is set.
 *
 * <p>Nodes without such an effect behave exactly like RenderNode. Whenever the GPU cannot do it (the shader
 * did not translate, Metal failed, the layer is huge), the real effect goes to HWUI as before.
 */
public class GpuRenderNode extends RenderNode {
  private static final List<WeakReference<GpuRenderNode>> ACTIVE = new ArrayList<>();
  /** Rasters to close at the next frame (see closeRaster). */
  private static final List<Raster> RETIRED = new ArrayList<>();
  private static final Comparator<GpuRenderNode> INNER_FIRST = Comparator.comparingLong(n -> n.order);
  private static final Cleaner CLEANER = Cleaner.create();
  private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0};
  private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);
  private static ByteBuffer scratch = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
  private static long sequence;
  private static float[] light;

  static long frames;
  static long reused;
  static long fallbacks;
  static long rasterNanos;
  static long shaderNanos;
  static long publishNanos;
  static long discards;
  static long rastersOpen;

  /** The app's drawing, once moved out of this node; null while the app draws straight into it. */
  private RenderNode content;
  private boolean recordingContent;
  /** The effect running on the GPU, or null. */
  private TrackedRuntimeShader.EffectProgram gpu;
  /** A GPU effect set while the app's drawing was still in this node: HWUI applies it until the next recording. */
  private TrackedRuntimeShader.EffectProgram waiting;
  private RenderEffect effect;
  private boolean failed;
  private boolean registered;
  /** Content recorded first gets a lower number: nested layers record inside their parents, so inner ones go first. */
  private long order = Long.MAX_VALUE;
  private Raster raster;

  public GpuRenderNode(String name) {
    super(name);
  }

  /** Light for shadows inside effect layers, the same as the screen's (HwRenderer). */
  public static void setLight(float x, float y, float z, float radius, float ambientAlpha, float spotAlpha) {
    light = new float[] {x, y, z, radius, ambientAlpha, spotAlpha};
  }

  @Override
  public boolean setRenderEffect(RenderEffect e) {
    TrackedRuntimeShader.EffectProgram p = failed ? null : GpuEffects.gpuProgram(e);
    effect = e;
    if (p == null) {
      boolean was = gpu != null;
      gpu = null;
      waiting = null;
      if (was && content != null) passThrough();
      return super.setRenderEffect(e) | was;
    }
    if (content == null && hasDisplayList()) {
      waiting = p;
      return super.setRenderEffect(e);
    }
    gpu = p;
    waiting = null;
    super.setRenderEffect(null);
    register();
    return true;
  }

  @Override
  public RecordingCanvas beginRecording(int width, int height) {
    if (content == null && gpu == null && waiting == null) return super.beginRecording(width, height);
    if (content == null) {
      content = new RenderNode("apksim-effect-content");
      content.setClipToBounds(false);
    }
    recordingContent = true;
    content.setPosition(0, 0, width, height);
    return content.beginRecording(width, height);
  }

  @Override
  public RecordingCanvas beginRecording() {
    return beginRecording(getWidth(), getHeight());
  }

  @Override
  public void endRecording() {
    if (!recordingContent) {
      super.endRecording();
      return;
    }
    recordingContent = false;
    content.endRecording();
    if (order == Long.MAX_VALUE) order = ++sequence;
    if (waiting != null) {
      gpu = waiting;
      waiting = null;
      super.setRenderEffect(null);
      register();
    }
    // With the effect on, beforeSync() records the shader's output here before this frame is drawn.
    if (gpu == null) passThrough();
  }

  @Override
  public void discardDisplayList() {
    if (gpu != null) discards++;
    super.discardDisplayList();
    if (content != null) content.discardDisplayList();
    closeRaster();
  }

  /** This node shows its content node as it is: no effect, or one HWUI applies. */
  private void passThrough() {
    RecordingCanvas c = super.beginRecording(getWidth(), getHeight());
    c.drawRenderNode(content);
    super.endRecording();
  }

  private void register() {
    if (registered) return;
    registered = true;
    ACTIVE.add(new WeakReference<>(this));
  }

  /**
   * Stops rasterizing this node's content. The renderer is closed a frame later: a node whose last parent goes
   * away loses its display list in HWUI (it draws nothing until recorded again), so the content must first be
   * drawn by this node in the screen's tree (passThrough), which the next sync does.
   */
  private void closeRaster() {
    if (raster != null) {
      RETIRED.add(raster);
      raster = null;
    }
  }

  /** Gives the effect back to HWUI (CPU) for good. */
  private void fallBack(String why) {
    fallbacks++;
    System.err.println("[apksim] layer effect stays on the CPU: " + why);
    failed = true;
    gpu = null;
    closeRaster();
    if (content != null) passThrough();
    super.setRenderEffect(effect);
  }

  /**
   * Called on the main thread once the frame's display lists are recorded and before HWUI syncs them:
   * brings every GPU effect layer's output up to date with its content and uniforms.
   */
  public static void beforeSync() {
    TrackedRuntimeShader.sweep();
    if (SELF_TEST) {
      SELF_TEST = false;
      selfTest();
    }
    if (!RETIRED.isEmpty()) {
      for (Raster r : RETIRED) r.close();
      RETIRED.clear();
    }
    if (ACTIVE.isEmpty()) return;
    // Strong references first, so the collector cannot change the order while it is sorted.
    List<GpuRenderNode> live = new ArrayList<>(ACTIVE.size());
    for (int i = 0; i < ACTIVE.size(); i++) {
      GpuRenderNode n = ACTIVE.get(i).get();
      if (n == null || n.gpu == null) {
        if (n != null) {
          n.registered = false;
          n.closeRaster();
        }
        ACTIVE.remove(i--);
      } else {
        live.add(n);
      }
    }
    if (live.size() > 1) live.sort(INNER_FIRST);
    for (GpuRenderNode n : live) {
      try {
        n.frame();
      } catch (RuntimeException | LinkageError e) {
        n.fallBack(e.toString());
      }
    }
  }

  private void frame() {
    int w = getWidth();
    int h = getHeight();
    if (content == null || !content.hasDisplayList() || w <= 0 || h <= 0) return;
    if ((long) w * h * 4 > (1L << 28)) {
      fallBack("layer " + w + "x" + h + " too large");
      return;
    }
    long t0 = System.nanoTime();
    Raster r = raster;
    if (r == null || r.w != w || r.h != h) {
      // The old one is closed after the new one has drawn the content, so it always has a parent.
      closeRaster();
      r = raster = new Raster(w, h, content);
      long texture = r.input;
      r.cleanable = CLEANER.register(this, () -> GpuNative.releaseInput(texture));
    }
    boolean fresh = r.draw();
    long t1 = System.nanoTime();
    rasterNanos += t1 - t0;
    if (!r.hasInput) return;
    TrackedRuntimeShader.EffectProgram p = gpu;
    if (!fresh && r.shown && r.lastPipeline == p.pipeline() && p.uniforms().equals(r.lastUniforms)) {
      reused++;
      return;
    }
    long handle = GpuNative.submitEffect(p.pipeline(), p.uniforms(), p.uniformBytes(), IDENTITY, w, h, r.input);
    ByteBuffer pixels = scratchFor(w, h);
    if (!GpuNative.collect(handle, pixels)) {
      fallBack("Metal could not run the shader");
      return;
    }
    long t2 = System.nanoTime();
    shaderNanos += t2 - t1;
    if (r.out == null) r.out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    pixels.position(0).limit(w * h * 4);
    r.out.copyPixelsFromBuffer(pixels);
    pixels.clear();
    RecordingCanvas c = super.beginRecording(w, h);
    c.drawBitmap(r.out, 0, 0, FILTER);
    super.endRecording();
    r.shown = true;
    r.lastPipeline = p.pipeline();
    r.lastUniforms = p.uniforms();
    p.uses()[0]++;
    frames++;
    publishNanos += System.nanoTime() - t2;
  }

  private static ByteBuffer scratchFor(int w, int h) {
    int bytes = w * h * 4;
    if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    return scratch;
  }

  private static boolean SELF_TEST = Boolean.getBoolean("apksim.effectSelfTest");

  /**
   * -Dapksim.effectSelfTest=true: draws test content through effects that sample inside, between and outside its
   * pixels, once with HWUI's CPU effect and once here, and prints how far apart they are.
   */
  private static void selfTest() {
    String[][] cases = {
      {"shift", "uniform shader content; uniform float2 off; half4 main(float2 p) { return content.eval(p + off); }"},
      {"zoom", "uniform shader content; uniform float2 off; half4 main(float2 p) { return content.eval((p - 50.0) * 0.37 + 50.0 + off); }"},
      {"premul", "uniform shader content; uniform float2 off; half4 main(float2 p) { half4 c = content.eval(p + off); return half4(c.rgb * 0.5, c.a * 0.75); }"},
    };
    float[][] offsets = {{0, 0}, {-3.5f, 2.25f}, {7.75f, -5.5f}, {0.5f, 0.5f}};
    int w = 101;
    int h = 77;
    RenderNode content = new RenderNode("selftest-content");
    content.setPosition(0, 0, w, h);
    recordSelfTestContent(content, w, h);
    {
      // Whether a node's display list survives a renderer letting go of it (Raster.close relies on it).
      Raster a = new Raster(w, h, content);
      long before = sum(a.capture());
      a.close();
      Raster b = new Raster(w, h, content);
      long after = sum(b.capture());
      b.close();
      System.err.printf("[apksim] effect self-test: content drawn %d, again after a renderer let go %d%n", before, after);
    }
    for (String[] k : cases) {
      for (float[] off : offsets) {
        TrackedRuntimeShader shader = new TrackedRuntimeShader(k[1]);
        shader.setFloatUniform("off", off[0], off[1]);
        RenderEffect effect = GpuEffects.createRuntimeShaderEffect(shader, "content");
        // CPU: HWUI applies the effect to a plain node.
        RenderNode cpu = new RenderNode("selftest-cpu");
        cpu.setPosition(0, 0, w, h);
        RecordingCanvas cc = cpu.beginRecording(w, h);
        cc.drawRenderNode(content);
        cpu.endRecording();
        cpu.setRenderEffect(effect);
        recordSelfTestContent(content, w, h);
        Raster ref = new Raster(w, h, cpu);
        ByteBuffer expected = ref.capture();
        ref.close();
        // GPU: this class's path.
        recordSelfTestContent(content, w, h);
        GpuRenderNode gpu = new GpuRenderNode("selftest-gpu");
        gpu.setPosition(0, 0, w, h);
        gpu.setRenderEffect(effect);
        RecordingCanvas gc = gpu.beginRecording(w, h);
        gc.drawRenderNode(content);
        gpu.endRecording();
        String result;
        if (gpu.gpu == null) {
          result = "not on the GPU";
        } else {
          gpu.frame();
          ByteBuffer got = ByteBuffer.allocateDirect(w * h * 4);
          gpu.raster.out.copyPixelsToBuffer(got);
          result = difference(expected, got);
          gpu.closeRaster();
        }
        ACTIVE.removeIf(r -> r.get() == gpu);
        System.err.printf("[apksim] effect self-test %s off=(%.2f,%.2f): %s%n", k[0], off[0], off[1], result);
      }
    }
    // Layers as the screen draws them: inside a parent, transformed, and one effect layer inside another.
    for (String arrangement : new String[] {"scaled", "rotated", "nested"}) {
      ByteBuffer expected = arrangedFrame(arrangement, false, cases, content, w, h);
      ByteBuffer got = arrangedFrame(arrangement, true, cases, content, w, h);
      System.err.printf("[apksim] effect self-test %s layer: %s%n", arrangement,
          got == null ? "not on the GPU" : difference(expected, got));
    }
  }

  private static final int ARRANGED_W = 180;
  private static final int ARRANGED_H = 150;

  /**
   * Draws effect layers into a parent the way a screen does (CPU: HWUI applies the effects; GPU: this class) and
   * returns the parent's pixels, or null if a GPU layer did not take its effect.
   */
  private static ByteBuffer arrangedFrame(String arrangement, boolean onGpu, String[][] cases, RenderNode content, int w, int h) {
    recordSelfTestContent(content, w, h);
    List<RenderNode> layers = new ArrayList<>();
    RenderNode layer = selfTestLayer(onGpu, cases[1][1], 2.5f, -1.75f, layers);
    layer.setPosition(40, 35, 40 + w, 35 + h);
    RecordingCanvas lc = layer.beginRecording(w, h);
    lc.drawRenderNode(content);
    layer.endRecording();
    RenderNode top = layer;
    switch (arrangement) {
      case "scaled" -> {
        layer.setScaleX(1.4f);
        layer.setScaleY(0.8f);
      }
      case "rotated" -> layer.setRotationZ(17);
      default -> {
        // The zoom layer sits inside a shift layer that also draws a frame of its own around it.
        layer.setPosition(10, 12, 10 + w, 12 + h);
        RenderNode outer = selfTestLayer(onGpu, cases[0][1], -4.25f, 3.5f, layers);
        outer.setPosition(20, 15, 20 + w + 20, 15 + h + 24);
        RecordingCanvas oc = outer.beginRecording(w + 20, h + 24);
        Paint frame = new Paint();
        frame.setColor(0xff3060c0);
        oc.drawRect(0, 0, w + 20, h + 24, frame);
        oc.drawRenderNode(layer);
        outer.endRecording();
        top = outer;
      }
    }
    try {
      if (onGpu) {
        // Inner first, as beforeSync orders them.
        for (RenderNode n : layers) {
          GpuRenderNode g = (GpuRenderNode) n;
          if (g.gpu == null) return null;
          g.frame();
        }
      }
      RenderNode parent = new RenderNode("selftest-parent");
      parent.setPosition(0, 0, ARRANGED_W, ARRANGED_H);
      RecordingCanvas pc = parent.beginRecording(ARRANGED_W, ARRANGED_H);
      pc.drawColor(0xff202020);
      pc.drawRenderNode(top);
      parent.endRecording();
      Raster r = new Raster(ARRANGED_W, ARRANGED_H, parent);
      ByteBuffer pixels = r.capture();
      r.close();
      return pixels;
    } finally {
      for (RenderNode n : layers) {
        if (n instanceof GpuRenderNode g) {
          g.closeRaster();
          ACTIVE.removeIf(ref -> ref.get() == g);
        }
      }
    }
  }

  private static RenderNode selfTestLayer(boolean onGpu, String source, float dx, float dy, List<RenderNode> layers) {
    TrackedRuntimeShader shader = new TrackedRuntimeShader(source);
    shader.setFloatUniform("off", dx, dy);
    RenderNode n = onGpu ? new GpuRenderNode("selftest-gpu") : new RenderNode("selftest-cpu");
    n.setRenderEffect(GpuEffects.createRuntimeShaderEffect(shader, "content"));
    layers.add(n);
    return n;
  }

  /** How far two packed RGBA buffers of the same size are apart, per byte. */
  private static String difference(ByteBuffer expected, ByteBuffer got) {
    int n = expected.capacity();
    long sum = 0;
    int max = 0;
    long se = 0;
    long sg = 0;
    for (int i = 0; i < n; i++) {
      int e = expected.get(i) & 0xff;
      int g = got.get(i) & 0xff;
      se += e;
      sg += g;
      sum += Math.abs(e - g);
      max = Math.max(max, Math.abs(e - g));
    }
    return String.format("mean=%.3f max=%d (average value: cpu %.1f, gpu %.1f)", sum / (double) n, max, se / (double) n,
        sg / (double) n);
  }

  private static long sum(ByteBuffer b) {
    long s = 0;
    for (int i = 0; i < b.capacity(); i++) s += b.get(i) & 0xff;
    return s;
  }

  private static void recordSelfTestContent(RenderNode content, int w, int h) {
    RecordingCanvas c = content.beginRecording(w, h);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setShader(new android.graphics.LinearGradient(0, 0, w, h, 0xffff0000, 0x400000ff, android.graphics.Shader.TileMode.CLAMP));
    c.drawRect(0, 0, w, h, paint);
    paint.setShader(null);
    paint.setColor(0x8000ff00);
    c.drawCircle(30, 40, 20, paint);
    paint.setColor(0xffffffff);
    c.drawRect(60, 5, 61, 70, paint);
    paint.setColor(0xff000000);
    paint.setTextSize(18);
    c.drawText("Ag", 66, 60, paint);
    content.endRecording();
  }

  /** Debug summary for the `shaders` command. */
  static String report() {
    double n = Math.max(1, frames) * 1e6;
    return String.format("layer effects: active=%d rasters=%d discards=%d gpuFrames=%d unchanged=%d cpuFallbacks=%d, per GPU frame: raster=%.2fms shader=%.2fms publish=%.2fms",
        ACTIVE.size(), rastersOpen, discards, frames, reused, fallbacks, rasterNanos / n, shaderNanos / n, publishNanos / n);
  }

  /** One effect layer's content raster: its own HardwareRenderer into an ImageReader, then a Metal texture. */
  private static final class Raster {
    final int w;
    final int h;
    final HardwareRenderer renderer = new HardwareRenderer();
    final ImageReader reader;
    final RenderNode root = RenderNode.create("apksim-effect-root", null);
    final long input;
    /** Frees the texture once, whether closed or garbage collected with its node. */
    Cleaner.Cleanable cleanable;
    Bitmap out;
    boolean hasInput;
    boolean shown;
    long lastPipeline;
    ByteBuffer lastUniforms;

    Raster(int w, int h, RenderNode content) {
      this.w = w;
      this.h = h;
      rastersOpen++;
      GpuNative.ensureLoaded();
      input = GpuNative.newInput(w, h);
      if (input == 0) throw new IllegalStateException("no Metal texture " + w + "x" + h);
      reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
      renderer.setOpaque(false);
      renderer.setSurface(reader.getSurface());
      root.setPosition(0, 0, w, h);
      RecordingCanvas c = root.beginRecording(w, h);
      // The layer starts transparent, as HWUI's own offscreen layer for the effect does.
      c.drawColor(0, PorterDuff.Mode.CLEAR);
      c.drawRenderNode(content);
      root.endRecording();
      renderer.setContentRoot(root);
      float[] l = light;
      if (l != null) {
        renderer.setLightSourceGeometry(l[0], l[1], l[2], l[3]);
        renderer.setLightSourceAlpha(l[4], l[5]);
      }
    }

    /** Rasterizes the content; false if there was no image. */
    boolean draw() {
      renderer.createRenderRequest().syncAndDraw();
      // Not acquireLatestImage: this ImageReader always has one more, so that never returns.
      Image image = reader.acquireNextImage();
      if (image == null) return false;
      try {
        Image.Plane plane = image.getPlanes()[0];
        GpuNative.upload(input, plane.getBuffer(), plane.getRowStride());
        hasInput = true;
      } finally {
        image.close();
      }
      return true;
    }

    /** Rasterizes once and returns the pixels, packed w * 4 bytes a row (self-test). */
    ByteBuffer capture() {
      renderer.createRenderRequest().syncAndDraw();
      Image image = reader.acquireNextImage();
      ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
      Image.Plane plane = image.getPlanes()[0];
      ByteBuffer in = plane.getBuffer();
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w * 4; x++) out.put(y * w * 4 + x, in.get(y * plane.getRowStride() + x));
      }
      image.close();
      return out;
    }

    void close() {
      rastersOpen--;
      // Let go of the content first: destroying a renderer deletes the display lists of everything it draws.
      root.beginRecording(w, h);
      root.endRecording();
      renderer.createRenderRequest().syncAndDraw();
      renderer.destroy();
      reader.close();
      if (cleanable != null) cleanable.clean();
      else GpuNative.releaseInput(input);
      // Its pixels now, not at the next full GC: HotSpot does not count native memory towards collecting. A display
      // list still drawing it keeps its own reference.
      if (out != null) out.recycle();
    }
  }
}
