package dev.apksim.camera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.impl.CameraDeviceImpl;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.view.Surface;
import android.hardware.camera2.params.OutputConfiguration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowCameraDeviceImpl;

/**
 * A new capture session closes the device's earlier ones first (FakeCamera.replacingSessions), as the camera service
 * replaces a session: their requests stop and their onClosed is called. The rest is Robolectric's CameraDevice.
 */
@Implements(value = CameraDeviceImpl.class, isInAndroidSdk = false)
public class ShadowSimCameraDevice extends ShadowCameraDeviceImpl {
  @RealObject private CameraDeviceImpl device;

  @Implementation
  @Override
  protected void createCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler)
      throws CameraAccessException {
    checkIfCameraClosedOrInError();
    long creation = FakeCamera.replacingSessions(device);
    Executor state = handler != null ? handler::post : Runnable::run;
    super.createCaptureSession(outputs, new Registering(callback, new java.util.HashSet<>(outputs), state, creation), handler);
  }

  @Implementation
  @Override
  protected void createCaptureSession(SessionConfiguration config) throws CameraAccessException {
    checkIfCameraClosedOrInError();
    long creation = FakeCamera.replacingSessions(device);
    SessionConfiguration c = new SessionConfiguration(config.getSessionType(), config.getOutputConfigurations(),
        config.getExecutor(), new Registering(config.getStateCallback(), surfaces(config), config.getExecutor(), creation));
    if (config.getInputConfiguration() != null) c.setInputConfiguration(config.getInputConfiguration());
    if (config.getSessionParameters() != null) c.setSessionParameters(config.getSessionParameters());
    super.createCaptureSession(c);
  }

  /** The configuration's output Surfaces, or null when one is deferred (added later) and they cannot all be known. */
  private static Set<Surface> surfaces(SessionConfiguration config) {
    Set<Surface> all = new HashSet<>();
    for (OutputConfiguration o : config.getOutputConfigurations()) {
      List<Surface> s = o.getSurfaces();
      if (s.isEmpty() || s.contains(null)) return null;
      all.addAll(s);
    }
    return all;
  }

  /**
   * The app's session callback, with the session registered on the device as it is configured: a later session then
   * closes it even if it never queued a request.
   */
  private final class Registering extends CameraCaptureSession.StateCallback {
    private final CameraCaptureSession.StateCallback app;
    private final FakeCamera.SessionInfo info;
    private final long creation;

    Registering(CameraCaptureSession.StateCallback app, Set<Surface> outputs, Executor stateExecutor, long creation) {
      this.app = app;
      this.creation = creation;
      this.info = new FakeCamera.SessionInfo(outputs, stateExecutor);
    }

    /** StateCallback is instrumented, and javac does not see its (synthetic) implementation of this. */
    @Override public Object $$robo$getData() {
      try {
        return CameraCaptureSession.StateCallback.class.getDeclaredField("__robo_data__").get(this);
      } catch (ReflectiveOperationException e) {
        return null;
      }
    }

    @Override public void onConfigured(CameraCaptureSession session) {
      // A session created again before this one's callback ran (both configurations pending) was replaced before it
      // was configured: it only closes, as the camera service closes a replaced session.
      if (!FakeCamera.configured(device, session, info, creation)) {
        session.close();
        return;
      }
      app.onConfigured(session);
    }

    @Override public void onConfigureFailed(CameraCaptureSession session) {
      app.onConfigureFailed(session);
    }

    @Override public void onReady(CameraCaptureSession session) {
      app.onReady(session);
    }

    @Override public void onActive(CameraCaptureSession session) {
      app.onActive(session);
    }

    @Override public void onCaptureQueueEmpty(CameraCaptureSession session) {
      app.onCaptureQueueEmpty(session);
    }

    @Override public void onClosed(CameraCaptureSession session) {
      app.onClosed(session);
    }

    @Override public void onSurfacePrepared(CameraCaptureSession session, android.view.Surface surface) {
      app.onSurfacePrepared(session, surface);
    }
  }
}
