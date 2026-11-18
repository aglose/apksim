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
 * Only {@code android.permission.health.*}: other permissions' flags stay as Robolectric has them. It also stamps the
 * app's install times ({@link #stampInstallTime}).
 */
@Implements(value = ApplicationPackageManager.class, isInAndroidSdk = false)
public class ShadowSimPackageManager extends ShadowApplicationPackageManager {
  private static final String HEALTH = "android.permission.health.";

  @Implementation
  @Override
  protected PackageInfo getPackageInfo(String packageName, int flags) throws PackageManager.NameNotFoundException {
    syncPackageInfo(packageName);
    return super.getPackageInfo(packageName, flags);
  }

  @Implementation(minSdk = 33)
  @Override
  protected PackageInfo getPackageInfo(String packageName, Object flags) throws PackageManager.NameNotFoundException {
    syncPackageInfo(packageName);
    return super.getPackageInfo(packageName, flags);
  }

  /**
   * As after `adb install`: the app's own PackageInfo gets [millis] as firstInstallTime and lastUpdateTime where they are
   * 0 (Robolectric builds it from the manifest with both 0, so an About screen showed 12/31/69; issue 505). Set once, in
   * InstallState, so getInstalledPackages and the other copies of the internal PackageInfo see it too.
   */
  public static void stampInstallTime(android.app.Application app, long millis) {
    PackageInfo info = ((ShadowSimPackageManager) org.robolectric.shadow.api.Shadow.extract(app.getPackageManager()))
        .getInternalMutablePackageInfo(app.getPackageName());
    if (info == null) return;
    if (info.firstInstallTime == 0) info.firstInstallTime = millis;
    if (info.lastUpdateTime == 0) info.lastUpdateTime = millis;
  }

  private void syncPackageInfo(String packageName) {
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
