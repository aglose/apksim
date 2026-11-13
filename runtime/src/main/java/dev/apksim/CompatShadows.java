package dev.apksim;

import android.app.compat.CompatChanges;
import android.compat.Compatibility;
import android.os.UserHandle;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCompatibility;

/**
 * Compat changes as a device answers them for the running app (CompatTable): Robolectric's shadow said `true` for every
 * id it did not know, so an app targeting 28 behaved as one targeting the latest SDK wherever the framework asks
 * Compatibility.isChangeEnabled (CompatChanges.isChangeEnabled(long) goes there too). The uid and package overloads
 * of CompatChanges ask the platform_compat service through a cache, which is not here; they answer from the same
 * table for the running app's own uid and package and, for any other, as the platform's own SDK would. An id the table
 * lacks, or no target known (a standby before its launch), gets what it got before. -Dapksim.compatTable=false: always.
 */
public final class CompatShadows {
  private CompatShadows() {}

  @Implements(value = Compatibility.class, isInAndroidSdk = false, minSdk = 30)
  public static class ShadowSimCompatibility extends ShadowCompatibility {
    @Implementation
    protected static boolean isChangeEnabled(long id) {
      if (id == CompatTable.CALL_ACTIVITY_RESULT_BEFORE_RESUME) {
        CompatTable.answer(id);  // counted; Robolectric's activity controller delivers results itself, so it stays off
        return false;
      }
      int a = CompatTable.answer(id);
      if (a != CompatTable.UNKNOWN) return a == CompatTable.ON;
      return ShadowCompatibility.isChangeEnabled(id);
    }
  }

  @Implements(value = CompatChanges.class, minSdk = 33)
  public static class ShadowSimCompatChanges {
    @Implementation
    protected static boolean isChangeEnabled(long id, String packageName, UserHandle user) {
      return other(id, packageName.equals(CompatTable.packageName()));
    }

    @Implementation
    protected static boolean isChangeEnabled(long id, int uid) {
      return other(id, uid == android.os.Process.myUid());
    }

    private static boolean other(long id, boolean running) {
      if (!CompatTable.active()) return true;  // as before: the service answers true for what it has no state for
      if (id == CompatTable.CALL_ACTIVITY_RESULT_BEFORE_RESUME && running) return false;
      int a = CompatTable.answer(id, running ? CompatTable.targetSdk() : CompatTable.PLATFORM_SDK, running);
      return a != CompatTable.OFF;
    }
  }
}
