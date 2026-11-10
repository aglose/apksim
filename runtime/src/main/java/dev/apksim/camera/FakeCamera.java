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
      if (callback != null) executor.execute(() -> call.accept(callback));
    }
  }

  private static final class DeviceState {
    Sequence repeating;
    final ArrayDeque<Sequence> singles = new ArrayDeque<>();
    long frameNumber;
    long lastTimestamp;
  }

  private static final Map<CameraDevice, DeviceState> DEVICES = new ConcurrentHashMap<>();
  private static final AtomicInteger SEQUENCES = new AtomicInteger();
  private static ScheduledExecutorService thread;

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
      for (Sequence s : dropped) s.tell(cb -> cb.onCaptureSequenceAborted(s.session, s.id));
    }
  }

  private static void end(Sequence s) {
    long last = s.lastFrame;
    if (last >= 0) {
      s.tell(cb -> cb.onCaptureSequenceCompleted(s.session, s.id, last));
    } else {
      s.tell(cb -> cb.onCaptureSequenceAborted(s.session, s.id));
    }
  }

  private static synchronized void start() {
    if (thread != null) return;
    ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> {
      Thread th = new Thread(r, "apksim-camera");
      th.setDaemon(true);
      return th;
    });
    t.scheduleAtFixedRate(FakeCamera::tick, 0, 1_000_000_000L / FPS, TimeUnit.NANOSECONDS);
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

  private static final Scene SCENE = new Scene();
  private static final Map<Long, Bitmap> RAW = new HashMap<>();
  private static final Map<SurfaceTexture, Bitmap[]> PREVIEWS = new WeakHashMap<>();
  private static final Map<SurfaceTexture, Integer> PREVIEW_NEXT = new WeakHashMap<>();
  private static long sceneFrame;
  private static int displayRotation;

  private static void tick() {
    try {
      long now = SystemClock.elapsedRealtimeNanos();
      long wall = System.currentTimeMillis();
      displayRotation = displayRotation();
      for (Map.Entry<CameraDevice, DeviceState> e : DEVICES.entrySet()) {
        CameraDevice device = e.getKey();
        DeviceState d = e.getValue();
        if (closing(device)) {
          DEVICES.remove(device);
          continue;
        }
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
        frame(device, seq, request, frameNumber, timestamp, wall, lastOfSequence);
      }
    } catch (Throwable t) {
      System.err.println("[apksim] camera: " + t);
    }
  }

  private static void frame(CameraDevice device, Sequence seq, CaptureRequest request, long frameNumber,
      long timestamp, long wall, boolean lastOfSequence) {
    long n = sceneFrame++;
    frames.incrementAndGet();
    CameraCaptureSession session = seq.session;
    seq.tell(cb -> cb.onCaptureStarted(session, request, timestamp, frameNumber));
    for (Surface target : request.getTargets()) {
      try {
        deliver(target, device.getId(), n, wall, timestamp);
      } catch (RuntimeException ex) {
        System.err.println("[apksim] camera: a frame for " + target + ": " + ex);
      }
    }
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
    int sensor = sensorOrientation(cameraId);
    return front(cameraId) ? (sensor + displayRotation) % 360 : (sensor - displayRotation + 360) % 360;
  }

  private static void deliver(Surface target, String cameraId, long n, long wall, long timestamp) {
    ImageReader reader = readerOf(target);
    if (reader != null) {
      int w = reader.getWidth(), h = reader.getHeight(), format = reader.getImageFormat();
      outputs.put("reader@" + Integer.toHexString(System.identityHashCode(reader)), ShadowSimImageReader.formatName(format) + " " + w + "x" + h);
      if (!ShadowSimImageReader.wants(reader)) {
        dropped.incrementAndGet();
        return;
      }
      Bitmap raw = raw(w, h, imageRotation(cameraId), n, wall);
      if (ShadowSimImageReader.offer(reader, raw, timestamp)) {
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
    boolean surfaceView = VideoSurfaces.viewOf(texture) instanceof android.view.SurfaceView;
    boolean sideways = displayRotation % 180 != 0;
    int pw = surfaceView && sideways ? PREVIEW_W : PREVIEW_H, ph = surfaceView && sideways ? PREVIEW_H : PREVIEW_W;
    int turn = surfaceView ? 0 : (sensorOrientation(cameraId) - imageRotation(cameraId) + 360) % 360;
    outputs.put("preview@" + Integer.toHexString(System.identityHashCode(texture)), pw + "x" + ph);
    // Three pictures per preview, used in turn: the view may still be drawing the last one.
    Bitmap[] pool = PREVIEWS.computeIfAbsent(texture, k -> new Bitmap[3]);
    int i = PREVIEW_NEXT.merge(texture, 1, (a, b) -> (a + b) % 3);
    if (pool[i] == null || pool[i].getWidth() != pw || pool[i].getHeight() != ph) {
      pool[i] = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
    }
    Bitmap picture = pool[i];
    SCENE.draw(new Canvas(picture), pw, ph, turn, front(cameraId), n, wall);
    VideoSurfaces.publish(texture, picture);
    previews.incrementAndGet();
  }

  /** The scene as the sensor's buffer holds it, w x h, drawn once per frame and size (camera thread only). */
  private static final Map<Long, Long> RAW_FRAME = new HashMap<>();

  private static Bitmap raw(int w, int h, int rotation, long n, long wall) {
    long key = (long) w << 32 | h;
    Bitmap b = RAW.get(key);
    if (b == null) {
      b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      RAW.put(key, b);
    }
    Long drawn = RAW_FRAME.get(key);
    if (drawn == null || drawn != n) {
      SCENE.draw(new Canvas(b), w, h, (360 - rotation) % 360, false, n, wall);
      RAW_FRAME.put(key, n);
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
