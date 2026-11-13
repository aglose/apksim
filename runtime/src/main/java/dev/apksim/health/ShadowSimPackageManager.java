package dev.apksim.health;

import android.app.ApplicationPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;

/**
 * The app's own PackageInfo says which of its health permissions are granted (REQUESTED_PERMISSION_GRANTED), as the
 * granted set the app's checkSelfPermission reads. Robolectric keeps the two apart: grants (ShadowApplication's, which
 * the permission prompt, {@code permission grant}, {@code pm grant} and {@code --permissions grant} change) never
 * reached the PackageInfo flags, and AndroidX's HealthConnectClient reads exactly those for
 * {@code getGrantedPermissions()} on API 34+, so an app that had been granted everything still saw nothing granted.
 * Only {@code android.permission.health.*}: other permissions' flags stay as Robolectric has them.
 */
@Implements(value = ApplicationPackageManager.class, isInAndroidSdk = false)
public class ShadowSimPackageManager extends ShadowApplicationPackageManager {
  private static final String HEALTH = "android.permission.health.";

  @Implementation
  @Override
  protected PackageInfo getPackageInfo(String packageName, int flags) throws PackageManager.NameNotFoundException {
    syncHealthGrants(packageName);
    return super.getPackageInfo(packageName, flags);
  }

  @Implementation(minSdk = 33)
  @Override
  protected PackageInfo getPackageInfo(String packageName, Object flags) throws PackageManager.NameNotFoundException {
    syncHealthGrants(packageName);
    return super.getPackageInfo(packageName, flags);
  }

  private void syncHealthGrants(String packageName) {
    android.app.Application app = RuntimeEnvironment.getApplication();
    if (app == null || packageName == null || !packageName.equals(app.getPackageName())) return;
    PackageInfo info = getInternalMutablePackageInfo(packageName);
    if (info == null || info.requestedPermissions == null) return;
    if (info.requestedPermissionsFlags == null || info.requestedPermissionsFlags.length != info.requestedPermissions.length) {
      int[] f = new int[info.requestedPermissions.length];
      if (info.requestedPermissionsFlags != null) {
        System.arraycopy(info.requestedPermissionsFlags, 0, f, 0, Math.min(f.length, info.requestedPermissionsFlags.length));
      }
      info.requestedPermissionsFlags = f;
    }
    for (int i = 0; i < info.requestedPermissions.length; i++) {
      String p = info.requestedPermissions[i];
      if (p == null || !p.startsWith(HEALTH)) continue;
      boolean granted = app.checkPermission(p, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
      info.requestedPermissionsFlags[i] = granted
          ? info.requestedPermissionsFlags[i] | PackageInfo.REQUESTED_PERMISSION_GRANTED
          : info.requestedPermissionsFlags[i] & ~PackageInfo.REQUESTED_PERMISSION_GRANTED;
    }
  }
}
