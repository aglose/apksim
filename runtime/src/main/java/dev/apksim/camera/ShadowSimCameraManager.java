package dev.apksim.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Process;
import java.util.concurrent.Executor;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCameraManager;

/**
 * Opening a camera needs the CAMERA permission, as the camera service checks it: without it openCamera throws
 * SecurityException (Robolectric opened it anyway). The rest is Robolectric's CameraManager.
 */
@Implements(value = CameraManager.class, minSdk = 28)
public class ShadowSimCameraManager extends ShadowCameraManager {
  /** Every openCamera overload Robolectric shadows ends here. */
  @Override
  protected CameraDevice openCameraDeviceUserAsync(String cameraId, CameraDevice.StateCallback callback,
      Executor executor, int uid, int oomScoreOffset) {
    Context app = RuntimeEnvironment.getApplication();
    if (app != null && app.checkPermission(Manifest.permission.CAMERA, Process.myPid(), Process.myUid())
        != PackageManager.PERMISSION_GRANTED) {
      throw new SecurityException("validateClientPermissionsLocked: Permission Denial: can't use the camera pid="
          + Process.myPid() + ", uid=" + Process.myUid());
    }
    return super.openCameraDeviceUserAsync(cameraId, callback, executor, uid, oomScoreOffset);
  }
}
