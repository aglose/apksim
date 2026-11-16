package dev.apksim.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCameraManager;

/**
 * Opening a camera needs the CAMERA permission, as the camera service checks it: without it openCamera throws
 * SecurityException (Robolectric opened it anyway). Availability callbacks are reported as CameraService reports them
 * (Robolectric only stores them): on the first registration every listed camera, unavailable while a client has it
 * open; unavailable when one opens, available when it closes. The rest is Robolectric's CameraManager.
 */
@Implements(value = CameraManager.class, minSdk = 28)
public class ShadowSimCameraManager extends ShadowCameraManager {
  /** Registered availability callbacks and the executor each hears on. */
  private static final Map<CameraManager.AvailabilityCallback, Executor> CALLBACKS = new LinkedHashMap<>();
  /** Camera ids a client has open. */
  private static final java.util.Set<String> OPEN = new java.util.LinkedHashSet<>();

  /** What the app asked of CameraManager, shown by the `camera` command and logged on stderr (issue 402). */
  private static final java.util.concurrent.atomic.AtomicInteger ID_LISTS = new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicInteger REGISTERED = new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicInteger TOLD = new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicInteger OPENS = new java.util.concurrent.atomic.AtomicInteger();

  private static void log(String line) {
    if (System.getProperty("apksim.instrument") == null) System.err.println("[apksim] camera: " + line);
  }

  /** One line for the `camera` command: calls made, callbacks held and how many announcements were delivered. */
  public static String report() {
    synchronized (CALLBACKS) {
      return "manager: idLists " + ID_LISTS.get() + " availabilityCallbacks " + CALLBACKS.size() + " (registered "
          + REGISTERED.get() + ") announced " + TOLD.get() + " opens " + OPENS.get() + " open " + OPEN;
    }
  }

  @Implementation
  @Override
  protected String[] getCameraIdList() throws android.hardware.camera2.CameraAccessException {
    String[] ids = super.getCameraIdList();
    if (ID_LISTS.incrementAndGet() <= 3) log("getCameraIdList -> " + java.util.Arrays.toString(ids));
    return ids;
  }

  /** Robolectric resets between tests; so do we. */
  @org.robolectric.annotation.Resetter
  public static void reset() {
    ShadowCameraManager.reset();
    synchronized (CALLBACKS) {
      CALLBACKS.clear();
      OPEN.clear();
      ID_LISTS.set(0);
      REGISTERED.set(0);
      TOLD.set(0);
      OPENS.set(0);
    }
  }

  @Implementation
  @Override
  protected void registerAvailabilityCallback(CameraManager.AvailabilityCallback callback, Handler handler) {
    // No Handler means the calling thread's Looper (CameraManager's checkHandler), else the main one.
    Looper looper = handler != null ? handler.getLooper()
        : Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
    Handler h = handler != null ? handler : new Handler(looper);
    register(callback, h::post);
  }

  @Implementation
  @Override
  protected void registerAvailabilityCallback(Executor executor, CameraManager.AvailabilityCallback callback) {
    register(callback, executor);
  }

  @Implementation
  @Override
  protected void unregisterAvailabilityCallback(CameraManager.AvailabilityCallback callback) {
    super.unregisterAvailabilityCallback(callback);
    synchronized (CALLBACKS) {
      CALLBACKS.remove(callback);
    }
  }

  /** The first registration of a callback is told the state of every camera at once; a repeat is a no-op. */
  private void register(CameraManager.AvailabilityCallback callback, Executor executor) {
    List<String> ids = new ArrayList<>();
    List<Boolean> open = new ArrayList<>();
    synchronized (CALLBACKS) {
      REGISTERED.incrementAndGet();
      if (CALLBACKS.containsKey(callback)) {
        log("registerAvailabilityCallback: " + String.valueOf(callback == null ? null : callback.getClass().getName()) + " already registered");
        return;
      }
      CALLBACKS.put(callback, executor);
      try {
        for (String id : super.getCameraIdList()) {
          ids.add(id);
          open.add(OPEN.contains(id));
        }
      } catch (android.hardware.camera2.CameraAccessException e) {
        // no cameras listed
      }
    }
    log("registerAvailabilityCallback: " + String.valueOf(callback == null ? null : callback.getClass().getName()) + ", telling it about " + ids
        + (ids.isEmpty() ? " (no cameras listed: nothing will be announced)" : ""));
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      boolean isOpen = open.get(i);
      executor.execute(() -> {
        TOLD.incrementAndGet();
        if (isOpen) callback.onCameraUnavailable(id);
        else callback.onCameraAvailable(id);
      });
    }
  }

  private static void announce(String id, boolean available) {
    List<Map.Entry<CameraManager.AvailabilityCallback, Executor>> to;
    synchronized (CALLBACKS) {
      if (available ? !OPEN.remove(id) : !OPEN.add(id)) return;
      to = new ArrayList<>(CALLBACKS.entrySet());
    }
    for (Map.Entry<CameraManager.AvailabilityCallback, Executor> e : to) {
      CameraManager.AvailabilityCallback cb = e.getKey();
      e.getValue().execute(() -> {
        TOLD.incrementAndGet();
        if (available) cb.onCameraAvailable(id);
        else cb.onCameraUnavailable(id);
      });
    }
  }

  /** ShadowSimCameraDevice: the camera was closed (or disconnected). */
  static void closed(String id) {
    announce(id, true);
  }

  /** Every openCamera overload Robolectric shadows ends here. */
  @Override
  protected CameraDevice openCameraDeviceUserAsync(String cameraId, CameraDevice.StateCallback callback,
      Executor executor, int uid, int oomScoreOffset) {
    Context app = RuntimeEnvironment.getApplication();
    if (app != null && app.checkPermission(Manifest.permission.CAMERA, Process.myPid(), Process.myUid())
        != PackageManager.PERMISSION_GRANTED) {
      log("openCamera " + cameraId + ": denied, no CAMERA permission");
      throw new SecurityException("validateClientPermissionsLocked: Permission Denial: can't use the camera pid="
          + Process.myPid() + ", uid=" + Process.myUid());
    }
    CameraDevice device = super.openCameraDeviceUserAsync(cameraId, callback, executor, uid, oomScoreOffset);
    OPENS.incrementAndGet();
    log("openCamera " + cameraId);
    announce(cameraId, false);
    return device;
  }
}
