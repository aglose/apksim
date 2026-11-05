package dev.apksim;

import android.app.Application;
import android.provider.Settings;

/**
 * What a device has before an app's process starts, set up in a test run just before Application.onCreate
 * (ShadowSimInstrumentation.callApplicationOnCreate): an app reads it from its first line of startup.
 */
final class InstallState {
  private InstallState() {}

  static void beforeApplicationOnCreate(Application app) {
    seedAndroidId(app);
  }

  /**
   * A device always has Settings.Secure.ANDROID_ID (16 hex digits, one per app signing key since API 26); Robolectric
   * leaves it null. An app that reads it as its device id, into a non-null Kotlin String, threw in every test. A fixed
   * value, as one device would have; a test that sets its own keeps it.
   */
  private static void seedAndroidId(Application app) {
    android.content.ContentResolver cr = app.getContentResolver();
    if (Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID) == null) {
      Settings.Secure.putString(cr, Settings.Secure.ANDROID_ID, "a75e5101d0c0ffee");
    }
  }
}
