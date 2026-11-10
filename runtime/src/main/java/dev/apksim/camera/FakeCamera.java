package dev.apksim.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import dev.apksim.video.VideoSurfaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

/**
 * The fake camera's sensor (`--camera fake`, issue 219). Robolectric's CameraDevice opens and configures sessions but
 * never produces a frame. Here a capture session's requests ({@link ShadowSimCameraCaptureSession}) are run on a thread
 * of their own at 30 frames a second, as a camera device runs them: each frame takes the next single capture if one is
 * waiting, else the repeating request; it draws the {@link Scene} into every target Surface the request names and
 * tells the request's CaptureCallback onCaptureStarted and onCaptureCompleted with a TotalCaptureResult (sensor
 * timestamp, converged 3A states, the request's control modes echoed), on the callback's handler or executor.
 *
 * <p>Targets: an ImageReader's Surface gets an Image of the reader's size and format ({@link ShadowSimImageReader}:
 * YUV_420_888 laid out as a device's NV21-backed planes, JPEG, RGBA_8888, or PRIVATE without planes), whose timestamp
 * is the result's SENSOR_TIMESTAMP, which CameraX matches them by. A TextureView's or SurfaceView's Surface (a preview)
 * gets a picture in {@link VideoSurfaces}, as a decoded video does. Anything else (an encoder's input Surface) gets
 * nothing. The buffer an ImageReader gets is the scene turned so that rotating it by the rotation apps compute from
 * the sensor orientation and the display's rotation makes it upright (front: (270 + display) mod 360, back: (90 -
 * display) mod 360); a preview is shown as a device's compositor shows the buffer, turned into the display's natural
 * orientation, and mirrored for the front camera.
 */
public final class FakeCamera {
  private FakeCamera() {}

  public static final int FPS = 30;
  /** The size a preview Surface's buffers have: the largest stream the camera lists in its 16:9 shape. */
  private static final int PREVIEW_W = 1280;
  private static final int PREVIEW_H = 720;

  /** What the camera sees. */
  public record Source(String kind, Bitmap image, String path) {
    boolean blocked() {
      return kind.equals("off");
    }
  }

  static volatile Source source = new Source("pattern", null, null);

  /** The test pattern. */
  public static void pattern() {
    source = new Source("pattern", null, null);
  }

  /** Black frames, as Android's camera privacy toggle gives an app whose camera is open. */
  public static void block() {
    source = new Source("off", null, null);
  }

  /** A still image the camera points at; null if it cannot be decoded. */
  public static String image(String path) {
    Bitmap b = BitmapFactory.decodeFile(path);
    if (b == null) return "error: cannot read an image from " + path;
    source = new Source("image", b, path);
    return null;
  }

  public static Source source() {
    return source;
  }

  // ---------------------------------------------------------------- capture sequences

  /** A capture or repeating request (or burst) submitted to a session, with the callback it reports to. */
  static final class Sequence {
    final CameraCaptureSession session;
    final List<CaptureRequest> requests;
    final CameraCaptureSession.CaptureCallback callback;
    final Executor executor;
    final int id;
    final boolean repeating;
    int next;
    long lastFrame = -1;

    Sequence(CameraCaptureSession session, List<CaptureRequest> requests, CameraCaptureSession.CaptureCallback callback,
        Executor executor, int id, boolean repeating) {
      this.session = session;
      this.requests = requests;
      this.callback = callback;
      this.executor = executor;
      this.id = id;
      this.repeating = repeating;
    }

    void tell(java.util.function.Consumer<CameraCaptureSession.CaptureCallback> call) {
      if (callback == null) return;
      try {
        executor.execute(() -> call.accept(callback));
      } catch (Throwable t) {
        appCallbackFailed(t);
      }
    }
  }

  /**
   * An app's callback threw where it ran on the camera thread (a direct executor). On a device that is one of the
   * app's binder threads, so the exception is the app's crash: it goes to the default handler on a thread named like
   * one (the app's handler, or Crashes' FATAL EXCEPTION and exit), not to apksim's own-thread logging.
   */
  static void appCallbackFailed(Throwable t) {
    Thread binder = new Thread(() -> {
      Thread self = Thread.currentThread();
      self.getUncaughtExceptionHandler().uncaughtException(self, t);
    }, "binder:" + ProcessHandle.current().pid() + "_camera");
    binder.start();
    try {
      binder.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class DeviceState {
    /** Sessions that have queued requests on this device: a new session closes them, as on a device. */
    final java.util.Set<CameraCaptureSession> sessions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** createCaptureSession calls so far: a session configured for an earlier one was replaced before it was ready. */
    long creations;
    /** Sessions with requests being processed, told onActive; told onReady once they have none (camera thread). */
    final java.util.Set<CameraCaptureSession> active = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    Sequence repeating;
    final ArrayDeque<Sequence> singles = new ArrayDeque<>();
    long frameNumber;
    long lastTimestamp;
  }

  private static final Map<CameraDevice, DeviceState> DEVICES = new ConcurrentHashMap<>();
  private static final AtomicInteger SEQUENCES = new AtomicInteger();
  private static volatile ScheduledExecutorService thread;

  // Counters for the `camera` command.
  static final AtomicLong frames = new AtomicLong();
  static final AtomicLong images = new AtomicLong();
  static final AtomicLong dropped = new AtomicLong();
  static final AtomicLong previews = new AtomicLong();
  /** The last few outputs frames went to, for the report. */
  private static final Map<String, String> outputs = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
      return size() > 6;
    }
  });

  /** Queues requests on the session's device; returns the sequence id, as CameraCaptureSession does. */
  static int submit(CameraCaptureSession session, CameraDevice device, List<CaptureRequest> requests,
      CameraCaptureSession.CaptureCallback callback, Executor executor, boolean repeating) {
    Sequence s = new Sequence(session, new ArrayList<>(requests), callback, executor, SEQUENCES.incrementAndGet(), repeating);
    DeviceState d = DEVICES.computeIfAbsent(device, k -> new DeviceState());
    Sequence ended = null;
    synchronized (d) {
      d.sessions.add(session);
      if (repeating) {
        ended = d.repeating;
        d.repeating = s;
      } else {
        d.singles.add(s);
      }
    }
    if (ended != null) end(ended);
    start();
    return s.id;
  }

  /**
   * A new capture session on the device (ShadowSimCameraDevice): the earlier ones close first, their requests ending
   * and their onClosed called, as the camera service replaces a session (Robolectric's left them streaming). Returns
   * this creation's number, which its configuration must still match.
   */
  /** What a session was configured with: its output Surfaces (null if any was deferred) and its callback's executor. */
  record SessionInfo(java.util.Set<Surface> outputs, Executor stateExecutor, CameraCaptureSession.StateCallback callback) {}

  private static final Map<CameraCaptureSession, SessionInfo> SESSIONS =
      java.util.Collections.synchronizedMap(new WeakHashMap<>());

  /** A session configured on the device (ShadowSimCameraDevice), before it queues anything. */
  static boolean configured(CameraDevice device, CameraCaptureSession session, SessionInfo info, long creation) {
    SESSIONS.put(session, info);
    DeviceState d = DEVICES.computeIfAbsent(device, k -> new DeviceState());
    synchronized (d) {
      if (creation != d.creations) return false;  // a newer createCaptureSession replaced it already
      d.sessions.add(session);
      return true;
    }
  }

  static SessionInfo info(CameraCaptureSession session) {
    return SESSIONS.get(session);
  }

  /**
   * Waits (up to a second) until the camera thread has finished what it is doing, the frame in flight and the
   * sequence ends queued before this call: a session's onClosed comes after its last results, as on a device.
   */
  static void awaitCameraThread() {
    ScheduledExecutorService t = thread;
    if (t == null || Thread.currentThread().getName().equals("apksim-camera")) return;
    try {
      t.submit(() -> {}).get(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      // stopped by a reset, or slower than a second: close anyway
    }
  }

  static long replacingSessions(CameraDevice device) {
    DeviceState d = DEVICES.computeIfAbsent(device, k -> new DeviceState());
    List<CameraCaptureSession> old;
    long creation;
    synchronized (d) {
      creation = ++d.creations;
      old = new ArrayList<>(d.sessions);
      d.sessions.clear();
    }
    for (CameraCaptureSession s : old) s.close();
    return creation;
  }

  /**
   * onActive for a session that has requests to process and had none, onReady for one that has none left, on the
   * session's callback executor as a device's camera reports them. Runs on the camera thread, around each frame and
   * after stopRepeating and abortCaptures, so onReady follows the session's last results.
   */
  private static void states(DeviceState d) {
    List<Runnable> calls = new ArrayList<>();
    synchronized (d) {
      java.util.Set<CameraCaptureSession> busy = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      if (d.repeating != null) busy.add(d.repeating.session);
      for (Sequence s : d.singles) busy.add(s.session);
      for (CameraCaptureSession s : busy) if (d.active.add(s)) calls.add(() -> tellState(s, true));
      for (java.util.Iterator<CameraCaptureSession> it = d.active.iterator(); it.hasNext(); ) {
        CameraCaptureSession s = it.next();
        if (busy.contains(s)) continue;
        it.remove();
        calls.add(() -> tellState(s, false));
      }
    }
    for (Runnable r : calls) r.run();
  }

  private static void tellState(CameraCaptureSession s, boolean active) {
    SessionInfo info = SESSIONS.get(s);
    if (info == null || info.callback() == null) return;
    try {
      if (ReflectionHelpers.<Boolean>getField(s, "mClosed")) return;  // a closed session hears onClosed only
    } catch (RuntimeException ignored) {
      // not a session impl: tell it anyway
    }
    try {
      info.stateExecutor().execute(() -> {
        if (active) info.callback().onActive(s);
        else info.callback().onReady(s);
      });
    } catch (Throwable t) {
      appCallbackFailed(t);
    }
  }

  /** CameraCaptureSession.stopRepeating: the repeating request ends after the frame in flight. */
  static void stopRepeating(CameraCaptureSession session) {
    for (DeviceState d : DEVICES.values()) {
      Sequence ended = null;
      synchronized (d) {
        if (d.repeating != null && d.repeating.session == session) {
          ended = d.repeating;
          d.repeating = null;
        }
      }
      if (ended != null) end(ended);
      onCameraThread(() -> states(d));
    }
  }

  /** abortCaptures and close: the session's repeating request ends and its waiting captures are dropped. */
  static void abort(CameraCaptureSession session) {
    stopRepeating(session);
    for (DeviceState d : DEVICES.values()) {
      List<Sequence> dropped = new ArrayList<>();
      synchronized (d) {
        d.singles.removeIf(s -> s.session == session && dropped.add(s));
      }
      // A burst that already delivered a frame ends completed with its last frame, one that delivered none aborted.
      for (Sequence s : dropped) end(s);
      onCameraThread(() -> states(d));
    }
  }

  /**
   * Tells a sequence's callback it ended, on the camera thread after the frame in flight (if any) has been delivered:
   * a device reports onCaptureSequenceCompleted only after the sequence's last onCaptureCompleted.
   */
  private static void end(Sequence s) {
    onCameraThread(() -> ended(s));
  }

  private static void onCameraThread(Runnable r) {
    // Twice: the thread may have just stopped itself, all cameras closed (stopIfIdle), and a new one then starts.
    for (int attempt = 0; attempt < 2; attempt++) {
      start();
      ScheduledExecutorService t = thread;
      try {
        if (t != null) t.execute(r);
        return;
      } catch (java.util.concurrent.RejectedExecutionException e) {
        // stopped meanwhile (idle, or a reset): try the next thread once
      }
    }
  }

  /**
   * Once every camera is closed the thread stops, dropping what it drew with (its thread-local buffers, preview pools
   * and pixel scratch), instead of waking 30 times a second for nothing; work queued on it still runs, and the next
   * request starts a new one.
   */
  private static synchronized void stopIfIdle(long gen) {
    if (gen != generation || !DEVICES.isEmpty() || thread == null) return;
    thread.shutdown();
    thread = null;
  }

  private static void ended(Sequence s) {
    long last = s.lastFrame;
    if (last >= 0) {
      s.tell(cb -> cb.onCaptureSequenceCompleted(s.session, s.id, last));
    } else {
      s.tell(cb -> cb.onCaptureSequenceAborted(s.session, s.id));
    }
  }

  /**
   * Between tests (SimRunner's reset): the camera thread stops (waiting for a frame in flight), and the devices, their
   * requests and the picture caches go, so a test that left a repeating request running sends nothing into the next.
   * The next submission starts a new thread.
   */
  public static void reset() {
    ScheduledExecutorService t;
    synchronized (FakeCamera.class) {
      generation++;  // a thread that outlasts the wait below delivers nothing more, and keeps its caches to itself
      t = thread;
      thread = null;
    }
    if (t != null) {
      t.shutdownNow();
      try {
        t.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    DEVICES.clear();
    outputs.clear();
    pattern();
  }

  private static synchronized void start() {
    if (thread != null) return;
    ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> {
      Thread th = new Thread(r, "apksim-camera");
      th.setDaemon(true);
      return th;
    });
    long gen = generation;
    t.scheduleAtFixedRate(() -> tick(gen), 0, 1_000_000_000L / FPS, TimeUnit.NANOSECONDS);
    thread = t;
  }

  private static boolean closing(CameraDevice device) {
    try {
      AtomicBoolean c = ReflectionHelpers.getField(device, "mClosing");
      return c != null && c.get();
    } catch (RuntimeException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- frames

  /**
   * What one camera thread draws with: the scene, buffers and preview pools, its frame count and the display's rotation.
   * Each thread has its own, so a thread a reset could not stop in time never shares them with the next one.
   */
  private static final class Worker {
    final Scene scene = new Scene();
    final Map<Long, Bitmap> raw = new HashMap<>();
    final Map<Long, Long> rawFrame = new HashMap<>();
    final Map<SurfaceTexture, Bitmap[]> previews = new WeakHashMap<>();
    final Map<SurfaceTexture, Integer> previewNext = new WeakHashMap<>();
    long sceneFrame;
    int displayRotation;
  }

  private static final ThreadLocal<Worker> WORKER = ThreadLocal.withInitial(Worker::new);
  /** Bumped by each reset: a camera thread of an earlier generation delivers nothing more. */
  private static volatile long generation;

  private static boolean stale(long gen) {
    return gen != generation;
  }

  private static void tick(long gen) {
    try {
      if (stale(gen)) return;
      long now = SystemClock.elapsedRealtimeNanos();
      long wall = System.currentTimeMillis();
      WORKER.get().displayRotation = displayRotation();
      for (Map.Entry<CameraDevice, DeviceState> e : DEVICES.entrySet()) {
        CameraDevice device = e.getKey();
        DeviceState d = e.getValue();
        if (closing(device)) {
          DEVICES.remove(device);
          continue;
        }
        states(d);
        Sequence seq;
        CaptureRequest request;
        boolean lastOfSequence = false;
        long frameNumber, timestamp;
        synchronized (d) {
          seq = d.singles.peek();
          if (seq != null) {
            request = seq.requests.get(seq.next++);
            if (seq.next == seq.requests.size()) {
              d.singles.poll();
              lastOfSequence = true;
            }
          } else if (d.repeating != null) {
            seq = d.repeating;
            request = seq.requests.get(seq.next++ % seq.requests.size());
          } else {
            continue;
          }
          frameNumber = d.frameNumber++;
          // Strictly increasing even when the simulated clock stands still (a test run): CameraX pairs images with
          // results by timestamp.
          timestamp = Math.max(now, d.lastTimestamp + 1);
          d.lastTimestamp = timestamp;
          seq.lastFrame = frameNumber;
        }
        frame(gen, device, seq, request, frameNumber, timestamp, wall, lastOfSequence);
        states(d);
      }
      if (DEVICES.isEmpty()) stopIfIdle(gen);
    } catch (Throwable t) {
      System.err.println("[apksim] camera: " + t);
    }
  }

  private static void frame(long gen, CameraDevice device, Sequence seq, CaptureRequest request, long frameNumber,
      long timestamp, long wall, boolean lastOfSequence) {
    long n = WORKER.get().sceneFrame++;
    frames.incrementAndGet();
    CameraCaptureSession session = seq.session;
    seq.tell(cb -> cb.onCaptureStarted(session, request, timestamp, frameNumber));
    for (Surface target : request.getTargets()) {
      if (stale(gen)) return;
      try {
        Integer jpeg = request.get(CaptureRequest.JPEG_ORIENTATION);
        deliver(target, device.getId(), n, wall, timestamp, jpeg == null ? 0 : jpeg);
      } catch (RuntimeException ex) {
        System.err.println("[apksim] camera: a frame for " + target + ": " + ex);
      }
    }
    if (stale(gen)) return;
    TotalCaptureResult result = result(device.getId(), request, seq.id, frameNumber, timestamp);
    seq.tell(cb -> cb.onCaptureCompleted(session, request, result));
    if (lastOfSequence) seq.tell(cb -> cb.onCaptureSequenceCompleted(session, seq.id, frameNumber));
  }

  /** Camera "0" faces back with its sensor at 90 degrees, "1" front at 270 (Cameras). */
  private static boolean front(String cameraId) {
    return !"0".equals(cameraId);
  }

  private static int sensorOrientation(String cameraId) {
    return front(cameraId) ? 270 : 90;
  }

  /** The clockwise turn an app gives the camera's image to show it upright on the display, as CameraX computes it. */
  private static int imageRotation(String cameraId) {
    int displayRotation = WORKER.get().displayRotation;
    int sensor = sensorOrientation(cameraId);
    return front(cameraId) ? (sensor + displayRotation) % 360 : (sensor - displayRotation + 360) % 360;
  }

  private static void deliver(Surface target, String cameraId, long n, long wall, long timestamp, int jpegOrientation) {
    ImageReader reader = readerOf(target);
    if (reader != null) {
      int w = reader.getWidth(), h = reader.getHeight(), format = reader.getImageFormat();
      outputs.put("reader@" + Integer.toHexString(System.identityHashCode(reader)), ShadowSimImageReader.formatName(format) + " " + w + "x" + h);
      if (!ShadowSimImageReader.wants(reader)) {
        dropped.incrementAndGet();
        return;
      }
      Bitmap raw = raw(w, h, imageRotation(cameraId), n, wall);
      if (ShadowSimImageReader.offer(reader, raw, timestamp, jpegOrientation)) {
        images.incrementAndGet();
      } else {
        dropped.incrementAndGet();
      }
      return;
    }
    SurfaceTexture texture = VideoSurfaces.textureOf(target);
    if (texture == null) return;
    // What the view shows, in the window's coordinates. A TextureView shows the buffer as the camera's transform turns
    // it (by the sensor orientation into the display's natural orientation, mirrored for a front camera), and CameraX's
    // PreviewView corrects for the display's rotation with the view's transform. A SurfaceView's buffer is composited
    // with the display's rotation as well, so it shows the scene upright (mirrored for a front camera) in the window.
    Worker w = WORKER.get();
    boolean surfaceView = VideoSurfaces.viewOf(texture) instanceof android.view.SurfaceView;
    int displayRotation = w.displayRotation;
    boolean sideways = displayRotation % 180 != 0;
    int pw = surfaceView && sideways ? PREVIEW_W : PREVIEW_H, ph = surfaceView && sideways ? PREVIEW_H : PREVIEW_W;
    int turn = surfaceView ? 0 : (sensorOrientation(cameraId) - imageRotation(cameraId) + 360) % 360;
    outputs.put("preview@" + Integer.toHexString(System.identityHashCode(texture)), pw + "x" + ph);
    // Three pictures per preview, used in turn: the view may still be drawing the last one.
    Bitmap[] pool = w.previews.computeIfAbsent(texture, k -> new Bitmap[3]);
    int i = w.previewNext.merge(texture, 1, (a, b) -> (a + b) % 3);
    if (pool[i] == null || pool[i].getWidth() != pw || pool[i].getHeight() != ph) {
      pool[i] = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
    }
    Bitmap picture = pool[i];
    w.scene.draw(new Canvas(picture), pw, ph, turn, front(cameraId), n, wall);
    VideoSurfaces.publish(texture, picture);
    previews.incrementAndGet();
  }

  /** The scene as the sensor's buffer holds it, w x h, drawn once per frame and size (camera thread only). */

  private static Bitmap raw(int w, int h, int rotation, long n, long wall) {
    long key = (long) w << 32 | h;
    Worker worker = WORKER.get();
    Bitmap b = worker.raw.get(key);
    if (b == null) {
      b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      worker.raw.put(key, b);
    }
    Long drawn = worker.rawFrame.get(key);
    if (drawn == null || drawn != n) {
      worker.scene.draw(new Canvas(b), w, h, (360 - rotation) % 360, false, n, wall);
      worker.rawFrame.put(key, n);
    }
    return b;
  }

  private static int displayRotation() {
    try {
      Context app = RuntimeEnvironment.getApplication();
      WindowManager wm = app == null ? null : (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
      return wm == null ? 0 : wm.getDefaultDisplay().getRotation() * 90;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** The ImageReader a Surface belongs to: Robolectric's native Surface shadow keeps it (ImageReader.getSurface). */
  static ImageReader readerOf(Surface surface) {
    try {
      Object shadow = Shadow.extract(surface);
      if (shadow == null) return null;
      Object ref = ReflectionHelpers.getField(shadow, "containerImageReader");
      return ref instanceof java.util.concurrent.atomic.AtomicReference<?> r && r.get() instanceof ImageReader ir ? ir : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------- results

  /**
   * What a device reports for a frame: the sensor timestamp, 3A converged (or inactive when off), the request's modes
   * echoed, no flash. CaptureResult's metadata here is Robolectric's map ({@code ShadowCameraMetadataNative}).
   */
  private static TotalCaptureResult result(String cameraId, CaptureRequest request, int sequence, long frameNumber,
      long timestamp) {
    TotalCaptureResult r = new TotalCaptureResult(cameraId, new CameraMetadataNative(), request, sequence, frameNumber,
        new ArrayList<>(), 0, new PhysicalCaptureResultInfo[0]);
    CameraMetadataNative m = ReflectionHelpers.getField(r, "mResults");
    m.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);
    m.set(CaptureResult.SENSOR_EXPOSURE_TIME, 1_000_000_000L / FPS / 2);
    m.set(CaptureResult.SENSOR_FRAME_DURATION, 1_000_000_000L / FPS);
    m.set(CaptureResult.SENSOR_SENSITIVITY, 100);
    m.set(CaptureResult.LENS_STATE, CaptureResult.LENS_STATE_STATIONARY);
    m.set(CaptureResult.FLASH_STATE, CaptureResult.FLASH_STATE_UNAVAILABLE);
    m.set(CaptureResult.FLASH_MODE, CaptureResult.FLASH_MODE_OFF);
    Integer controlMode = request.get(CaptureRequest.CONTROL_MODE);
    Integer ae = request.get(CaptureRequest.CONTROL_AE_MODE);
    Integer af = request.get(CaptureRequest.CONTROL_AF_MODE);
    Integer awb = request.get(CaptureRequest.CONTROL_AWB_MODE);
    m.set(CaptureResult.CONTROL_MODE, controlMode == null ? CaptureResult.CONTROL_MODE_AUTO : controlMode);
    m.set(CaptureResult.CONTROL_AE_MODE, ae == null ? CaptureResult.CONTROL_AE_MODE_ON : ae);
    m.set(CaptureResult.CONTROL_AF_MODE, af == null ? CaptureResult.CONTROL_AF_MODE_OFF : af);
    m.set(CaptureResult.CONTROL_AWB_MODE, awb == null ? CaptureResult.CONTROL_AWB_MODE_AUTO : awb);
    boolean aeOff = ae != null && ae == CaptureResult.CONTROL_AE_MODE_OFF;
    m.set(CaptureResult.CONTROL_AE_STATE, aeOff ? CaptureResult.CONTROL_AE_STATE_INACTIVE : CaptureResult.CONTROL_AE_STATE_CONVERGED);
    int afState = af == null || af == CaptureResult.CONTROL_AF_MODE_OFF ? CaptureResult.CONTROL_AF_STATE_INACTIVE
        : af == CaptureResult.CONTROL_AF_MODE_AUTO || af == CaptureResult.CONTROL_AF_MODE_MACRO
            ? CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED : CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED;
    m.set(CaptureResult.CONTROL_AF_STATE, afState);
    boolean awbOff = awb != null && awb == CaptureResult.CONTROL_AWB_MODE_OFF;
    m.set(CaptureResult.CONTROL_AWB_STATE, awbOff ? CaptureResult.CONTROL_AWB_STATE_INACTIVE : CaptureResult.CONTROL_AWB_STATE_CONVERGED);
    Integer jpeg = request.get(CaptureRequest.JPEG_ORIENTATION);
    if (jpeg != null) m.set(CaptureResult.JPEG_ORIENTATION, jpeg);
    android.graphics.Rect crop = request.get(CaptureRequest.SCALER_CROP_REGION);
    m.set(CaptureResult.SCALER_CROP_REGION, crop != null ? crop : new android.graphics.Rect(0, 0, 1920, 1080));
    android.util.Range<Integer> fps = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
    if (fps != null) m.set(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE, fps);
    Float zoom = request.get(CaptureRequest.CONTROL_ZOOM_RATIO);
    if (zoom != null) m.set(CaptureResult.CONTROL_ZOOM_RATIO, zoom);
    return r;
  }

  /** For the `camera` command. */
  public static String report() {
    int open = 0, streaming = 0;
    for (Map.Entry<CameraDevice, DeviceState> e : DEVICES.entrySet()) {
      if (closing(e.getKey())) continue;
      open++;
      synchronized (e.getValue()) {
        if (e.getValue().repeating != null) streaming++;
      }
    }
    Source s = source;
    return "source " + (s.kind().equals("image") ? "image " + s.path() : s.kind()) + ", " + FPS + " fps"
        + " | devices " + open + " streaming " + streaming + " | frames " + frames.get() + " images " + images.get()
        + " dropped " + dropped.get() + " previews " + previews.get() + " | outputs " + new ArrayList<>(outputs.values());
  }
}
