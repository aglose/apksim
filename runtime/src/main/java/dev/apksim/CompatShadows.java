package dev.apksim;

import android.app.compat.CompatChanges;
import android.compat.Compatibility;
import android.os.UserHandle;
import java.util.function.LongPredicate;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCompatibility;

/**
 * Compat changes as a device answers them for the running app (CompatTable): Robolectric's shadow said `true` for every
 * id it did not know, so an app targeting 28 behaved as one targeting the latest SDK wherever the framework asks
 * Compatibility.isChangeEnabled (CompatChanges.isChangeEnabled(long) goes there too). The uid and package overloads of
 * CompatChanges ask the platform_compat service, whose Robolectric stand-in answered false for every id; they answer
 * from the table for the running app, for another installed package by that package's own targetSdk, and for one that
 * is not installed by the change's default, as a device does. An id the table lacks, or no target known (a standby
 * before its launch, -Dapksim.compatTable=false), gets what it got before: Robolectric's answer, false for the overloads.
 * The decisions are plain static methods ({@link #decide}, {@link #decideFor}) so CompatTableCheck runs them on a plain JVM.
 */
public final class CompatShadows {
  private CompatShadows() {}

  /** Compatibility.isChangeEnabled(id): the table's answer, else {@code fallback} (Robolectric's shadow). */
  static boolean decide(long id, LongPredicate fallback) {
    int a = CompatTable.answer(id);
    if (a != CompatTable.UNKNOWN) return a == CompatTable.ON;
    return fallback.test(id);
  }

  /**
   * The CompatChanges overloads that name a package or uid. {@code running}: it is the app; otherwise {@code target}
   * is the installed package's targetSdkVersion, or -1 when no installed package has it.
   */
  static boolean decideFor(long id, boolean running, int target) {
    if (!CompatTable.active()) return false;  // as before: the stand-in service said false to everything
    if (running) {
      int a = CompatTable.answer(id);
      return a == CompatTable.ON || a == CompatTable.UNKNOWN;  // an id the table lacks is on, as for a package with no state
    }
    if (target > 0) {
      int a = CompatTable.answer(id, target, false);
      return a != CompatTable.OFF;
    }
    return CompatTable.answerNotInstalled(id) != CompatTable.OFF;
  }

  private static int targetOf(String pkg) {
    try {
      return org.robolectric.RuntimeEnvironment.getApplication().getPackageManager().getApplicationInfo(pkg, 0).targetSdkVersion;
    } catch (Exception e) {  // not installed
      return -1;
    }
  }

  private static int targetOfUid(int uid) {
    try {
      String[] pkgs = org.robolectric.RuntimeEnvironment.getApplication().getPackageManager().getPackagesForUid(uid);
      return pkgs == null || pkgs.length == 0 ? -1 : targetOf(pkgs[0]);
    } catch (Exception e) {
      return -1;
    }
  }

  @Implements(value = Compatibility.class, isInAndroidSdk = false, minSdk = 30)
  public static class ShadowSimCompatibility extends ShadowCompatibility {
    @Implementation
    protected static boolean isChangeEnabled(long id) {
      return decide(id, ShadowCompatibility::isChangeEnabled);
    }
  }

  @Implements(value = CompatChanges.class, minSdk = 33)
  public static class ShadowSimCompatChanges {
    @Implementation
    protected static boolean isChangeEnabled(long id, String packageName, UserHandle user) {
      boolean running = CompatTable.packageName().equals(packageName);  // null-safe: a device answers the default
      return decideFor(id, running, running || packageName == null ? -1 : targetOf(packageName));
    }

    @Implementation
    protected static boolean isChangeEnabled(long id, int uid) {
      boolean running = uid == android.os.Process.myUid();
      return decideFor(id, running, running ? -1 : targetOfUid(uid));
    }
  }
}
