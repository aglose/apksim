package dev.apksim.camera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.impl.CameraDeviceImpl;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.view.Surface;
import java.util.List;
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
    FakeCamera.replacingSessions(device);
    super.createCaptureSession(outputs, new Registering(callback), handler);
  }

  @Implementation
  @Override
  protected void createCaptureSession(SessionConfiguration config) throws CameraAccessException {
    checkIfCameraClosedOrInError();
    FakeCamera.replacingSessions(device);
    SessionConfiguration c = new SessionConfiguration(config.getSessionType(), config.getOutputConfigurations(),
        config.getExecutor(), new Registering(config.getStateCallback()));
    if (config.getInputConfiguration() != null) c.setInputConfiguration(config.getInputConfiguration());
    if (config.getSessionParameters() != null) c.setSessionParameters(config.getSessionParameters());
    super.createCaptureSession(c);
  }

  /**
   * The app's session callback, with the session registered on the device as it is configured: a later session then
   * closes it even if it never queued a request.
   */
  private final class Registering extends CameraCaptureSession.StateCallback {
    private final CameraCaptureSession.StateCallback app;

    Registering(CameraCaptureSession.StateCallback app) {
      this.app = app;
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
      FakeCamera.configured(device, session);
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
