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
    super.createCaptureSession(outputs, callback, handler);
  }

  @Implementation
  @Override
  protected void createCaptureSession(SessionConfiguration config) throws CameraAccessException {
    checkIfCameraClosedOrInError();
    FakeCamera.replacingSessions(device);
    super.createCaptureSession(config);
  }
}
