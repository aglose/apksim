package dev.apksim;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.media.Image;
import android.media.ImageReader;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import com.android.internal.R;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws through Android's real render pipeline (HWUI display lists on its render thread), which is what
 * gives elevation shadows and RenderEffect. All windows are composed under one root node.
 *
 * <p>The main thread only records. HWUI's syncAndDraw blocks its caller until the whole frame is
 * rasterized, so it runs on its own thread: the main thread waits only until HWUI has synced (taken its
 * copy of the display lists), then starts on the next frame while this one rasterizes. Frame rate is then
 * limited by the slower stage instead of the sum of both.
 */
final class HwRenderer {
  /** Positions a window on screen. */
  interface Placer {
    int[] position(View root);
  }

  long recordNanos;
  volatile long renderNanos;
  volatile long presentNanos;
  volatile long syncWaitNanos;
  volatile long presented;
  volatile long overBudget;

  private static final long BUDGET_NANOS = 1_000_000_000L / 120;

  private int width;
  private int height;
  private ImageReader reader;
  private final HardwareRenderer renderer = new HardwareRenderer();
  private final RenderNode rootNode = RenderNode.create("apksim-root", null);
  private final Semaphore idle = new Semaphore(1);
  private final Semaphore go = new Semaphore(0);
  private final Semaphore synced = new Semaphore(0);
  private final Object frameLock = new Object();
  private final AtomicBoolean syncSignalled = new AtomicBoolean();
  private Image current;
  private boolean renderThreadBoosted;
  private final long[] intervals = new long[8192];
  private long intervalCount;
  private long lastPresent;

  HwRenderer(Context context, int w, int h) {
    width = w;
    height = h;
    reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
    renderer.setSurface(reader.getSurface());
    rootNode.setPosition(0, 0, w, h);
    renderer.setContentRoot(rootNode);
    DisplayMetrics m = context.getResources().getDisplayMetrics();
    TypedArray a = context.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
    float lightY = a.getDimension(R.styleable.Lighting_lightY, 0f);
    float lightZ = a.getDimension(R.styleable.Lighting_lightZ, 600f * m.density);
    float lightRadius = a.getDimension(R.styleable.Lighting_lightRadius, 800f * m.density);
    float ambient = a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0.039f);
    float spot = a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0.19f);
    renderer.setLightSourceGeometry(m.widthPixels / 2f, lightY, lightZ, lightRadius);
    renderer.setLightSourceAlpha(ambient, spot);
    dev.apksim.gpu.GpuRenderNode.setLight(m.widthPixels / 2f, lightY, lightZ, lightRadius, ambient, spot);
    a.recycle();
    Thread worker = new Thread(this::rasterLoop, "apksim-raster");
    worker.setDaemon(true);
    worker.setPriority(Thread.MAX_PRIORITY);
    worker.start();
  }

  private void signalSynced() {
    if (syncSignalled.compareAndSet(false, true)) synced.release();
  }

  private void rasterLoop() {
    SimWindow.boostThread();
    while (true) {
      go.acquireUninterruptibly();
      long t0 = System.nanoTime();
      renderer.createRenderRequest().syncAndDraw();
      signalSynced(); // In case HWUI skipped the frame and never called back.
      long t1 = System.nanoTime();
      Image next = reader.acquireNextImage();
      if (next != null) {
        SimWindow.present(next.getPlanes()[0].getBuffer());
        synchronized (frameLock) {
          if (current != null) current.close();
          current = next;
        }
      }
      long t2 = System.nanoTime();
      renderNanos += t1 - t0;
      presentNanos += t2 - t1;
      if (t2 - t0 > BUDGET_NANOS) overBudget++;
      presented++;
      long interval = t2 - lastPresent;
      lastPresent = t2;
      // Gaps longer than this are the app having nothing to draw, not slow frames.
      if (interval < 100_000_000L) intervals[(int) (intervalCount++ % intervals.length)] = interval;
      idle.release();
    }
  }

  /** Records the windows bottom-to-top and starts rasterizing them; returns once HWUI has synced. */
  void draw(List<View> roots, Placer placer) {
    long t0 = System.nanoTime();
    RecordingCanvas c = rootNode.beginRecording(width, height);
    // An opaque first window covers everything, so clearing first would only waste a full-screen fill.
    boolean covered = !roots.isEmpty() && roots.get(0).isOpaque() && roots.get(0).getWidth() >= width && roots.get(0).getHeight() >= height;
    if (!covered) c.drawColor(0xff000000);
    for (View root : roots) {
      if (root.getVisibility() != View.VISIBLE || root.getWidth() == 0) continue;
      int save = c.save();
      if (root.getLayoutParams() instanceof WindowManager.LayoutParams lp
          && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
        c.drawColor((int) (lp.dimAmount * 255) << 24);
      }
      int[] at = placer.position(root);
      c.translate(at[0], at[1]);
      RenderNode node = root.updateDisplayListIfDirty();
      c.drawRenderNode(node);
      c.restoreToCount(save);
    }
    rootNode.endRecording();
    // Layers with a runtime-shader RenderEffect over their content: rasterize that content and run the shader
    // on Metal now, so HWUI only draws the result (see GpuRenderNode).
    dev.apksim.gpu.GpuRenderNode.beforeSync();
    long t1 = System.nanoTime();
    // Recording may overlap the previous frame's raster, but HWUI takes one frame at a time.
    idle.acquireUninterruptibly();
    syncSignalled.set(false);
    renderer.setFrameCallback(frame -> {
      // Runs on HWUI's render thread, the only way to reach that native thread from here.
      if (!renderThreadBoosted) {
        renderThreadBoosted = true;
        SimWindow.boostThread();
      }
      signalSynced();
    });
    go.release();
    try {
      // The callback fires after HWUI has taken its copy; the timeout is only a safety net.
      synced.tryAcquire(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    recordNanos += t1 - t0;
    syncWaitNanos += System.nanoTime() - t1;
  }

  /** Percentiles of the time between frames reaching the window since the last call. */
  String frameIntervals() {
    int n = (int) Math.min(intervalCount, intervals.length);
    if (n == 0) return "no frames";
    long[] sorted = java.util.Arrays.copyOf(intervals, n);
    java.util.Arrays.sort(sorted);
    intervalCount = 0;
    double p50 = sorted[n / 2] / 1e6;
    double p95 = sorted[Math.min(n - 1, n * 95 / 100)] / 1e6;
    double p99 = sorted[Math.min(n - 1, n * 99 / 100)] / 1e6;
    // The clock's 1 ms steps make a 120 Hz grid alternate 8 and 9 ms, so 9 ms is still on pace.
    long within = java.util.Arrays.stream(sorted).filter(v -> v <= 9_500_000L).count();
    return String.format("frames=%d avg=%.1f fps, interval p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms; on 120 Hz pace: %d%%",
        n, n / (java.util.Arrays.stream(sorted).sum() / 1e9), p50, p95, p99, sorted[n - 1] / 1e6, within * 100 / n);
  }

  /** A new display size (rotation, resizing): swaps the surface and the window's buffers while no frame is in flight. */
  void resize(int w, int h, boolean actualSize) {
    idle.acquireUninterruptibly();
    try {
      synchronized (frameLock) {
        if (current != null) current.close();
        current = null;
      }
      ImageReader old = reader;
      reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
      renderer.setSurface(reader.getSurface());
      rootNode.setPosition(0, 0, w, h);
      width = w;
      height = h;
      old.close();
      SimWindow.resize(w, h, actualSize);
    } finally {
      idle.release();
    }
  }

  /** Frees the renderer and its surface (a standby's warm-up renderer). Its raster thread stays parked. */
  void close() {
    finish();
    renderer.destroy();
    synchronized (frameLock) {
      if (current != null) current.close();
      current = null;
    }
    reader.close();
  }

  /** Waits for the frame in flight, for when the exact pixels matter. */
  void finish() {
    idle.acquireUninterruptibly();
    idle.release();
  }

  /** Runs with the latest frame's pixels, or does nothing if there is no frame yet. */
  void withLatestFrame(java.util.function.Consumer<ByteBuffer> use) {
    finish();
    synchronized (frameLock) {
      if (current != null) use.accept(current.getPlanes()[0].getBuffer());
    }
  }
}
