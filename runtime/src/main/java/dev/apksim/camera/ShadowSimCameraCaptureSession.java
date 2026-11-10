package dev.apksim.camera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraCaptureSessionImpl;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl;
import org.robolectric.util.ReflectionHelpers;

/**
 * A capture session whose requests the fake camera runs ({@link FakeCamera}): Robolectric's accepts them and returns
 * 1 without producing a frame. Capture, burst and repeating requests (with a Handler or an Executor), stopRepeating,
 * abortCaptures and close. Without `--camera fake` (no camera listed, so none can be opened) it is Robolectric's.
 */
@Implements(value = CameraCaptureSessionImpl.class, minSdk = 28, isInAndroidSdk = false)
public class ShadowSimCameraCaptureSession extends ShadowCameraCaptureSessionImpl {
  @RealObject private CameraCaptureSessionImpl session;

  private CameraDevice device() {
    return ReflectionHelpers.getField(session, "mDeviceImpl");
  }

  /** As CameraCaptureSessionImpl's checkHandler: no Handler means the calling thread's Looper, which must exist. */
  private static Executor executor(Handler handler, CameraCaptureSession.CaptureCallback callback) {
    if (callback == null) return Runnable::run;
    if (handler == null) {
      Looper looper = Looper.myLooper();
      if (looper == null) throw new IllegalArgumentException("No handler given, and current thread has no looper!");
      handler = new Handler(looper);
    }
    return handler::post;
  }

  private void checkRequest(CaptureRequest request) {
    if (request == null) throw new IllegalArgumentException("request must not be null");
    if (request.getTargets().isEmpty()) throw new IllegalArgumentException("Each request must have at least one Surface target");
    if (ReflectionHelpers.<Boolean>getField(session, "mClosed")) throw new IllegalStateException("Session has been closed; further changes are illegal.");
    FakeCamera.SessionInfo info = FakeCamera.info(session);
    if (info != null && info.outputs() != null) {
      for (android.view.Surface target : request.getTargets()) {
        if (!info.outputs().contains(target)) {
          throw new IllegalArgumentException("CaptureRequest contains unconfigured Input/Output Surface!");
        }
      }
    }
    java.util.concurrent.atomic.AtomicBoolean closing = ReflectionHelpers.getField(device(), "mClosing");
    if (closing != null && closing.get()) throw new IllegalStateException("CameraDevice was already closed");
  }

  /** Validation and queueing, and close, under this session's lock: nothing is queued once close has begun. */
  private synchronized int submit(List<CaptureRequest> requests, CameraCaptureSession.CaptureCallback callback, Executor executor,
      boolean repeating) {
    if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("Requests must have at least one element");
    for (CaptureRequest r : requests) checkRequest(r);
    return FakeCamera.submit(session, device(), requests, callback, executor, repeating);
  }

  @Implementation
  @Override
  protected int setRepeatingRequest(CaptureRequest request, CameraCaptureSession.CaptureCallback callback, Handler handler)
      throws CameraAccessException {
    return submit(List.of(request), callback, executor(handler, callback), true);
  }

  @Implementation
  @Override
  protected int setSingleRepeatingRequest(CaptureRequest request, Executor executor, CameraCaptureSession.CaptureCallback callback)
      throws CameraAccessException {
    return submit(List.of(request), callback, executor, true);
  }

  @Implementation
  @Override
  protected int capture(CaptureRequest request, CameraCaptureSession.CaptureCallback callback, Handler handler)
      throws CameraAccessException {
    return submit(List.of(request), callback, executor(handler, callback), false);
  }

  @Implementation
  @Override
  protected int captureSingleRequest(CaptureRequest request, Executor executor, CameraCaptureSession.CaptureCallback callback)
      throws CameraAccessException {
    return submit(List.of(request), callback, executor, false);
  }

  @Implementation
  protected int setRepeatingBurst(List<CaptureRequest> requests, CameraCaptureSession.CaptureCallback callback, Handler handler) {
    return submit(requests, callback, executor(handler, callback), true);
  }

  @Implementation
  protected int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor,
      CameraCaptureSession.CaptureCallback callback) {
    return submit(requests, callback, executor, true);
  }

  @Implementation
  protected int captureBurst(List<CaptureRequest> requests, CameraCaptureSession.CaptureCallback callback, Handler handler) {
    return submit(requests, callback, executor(handler, callback), false);
  }

  @Implementation
  protected int captureBurstRequests(List<CaptureRequest> requests, Executor executor,
      CameraCaptureSession.CaptureCallback callback) {
    return submit(requests, callback, executor, false);
  }

  @Implementation
  protected synchronized void stopRepeating() {
    FakeCamera.stopRepeating(session);
  }

  @Implementation
  protected synchronized void abortCaptures() {
    FakeCamera.abort(session);
  }

  @Implementation
  @Override
  protected void close() {
    // Closed at once (nothing more is queued), but onClosed comes after the frame in flight and the sequence ends, on
    // the session's callback executor: an app may release its outputs there. The wait is outside the session's lock,
    // which a capture callback on the camera thread may need.
    synchronized (this) {
      if (ReflectionHelpers.<Boolean>getField(session, "mClosed")) return;  // closing twice does nothing, as on a device
      ReflectionHelpers.setField(session, "mClosed", true);
      FakeCamera.abort(session);
    }
    FakeCamera.awaitCameraThread();
    FakeCamera.SessionInfo info = FakeCamera.info(session);
    Executor state = info == null ? Runnable::run : info.stateExecutor();
    state.execute(() -> ShadowSimCameraCaptureSession.super.close());
  }

}
