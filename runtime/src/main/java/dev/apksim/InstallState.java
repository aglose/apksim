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
    grantInstallPermissions(app);
    hostNetworks(app);
    installProviders(app);
  }

  /**
   * The host's network, as a device's connected one: HostNetworks shows its interfaces as Android networks with
   * INTERNET and VALIDATED, as Host does for a running instance. Robolectric's default network has neither, so an app
   * that checks NET_CAPABILITY_VALIDATED before a request decided it was offline and refused its own requests
   * (NoNetworkConnectionException from its OkHttp interceptor) while the host's network worked.
   */
  private static void hostNetworks(Application app) {
    try {
      HostNetworks.install(app);
    } catch (Exception e) {
      System.err.println("[apksim] host networks: " + e);
    }
  }

  /**
   * What installing the APK grants: every requested permission of protection level normal (INTERNET, VIBRATE,
   * BLUETOOTH...), and with --permissions grant everything requested, as `adb install -g` does. Robolectric grants
   * none, so a framework check of a normal permission (BluetoothAdapter.getProfileProxy's, in one app's startup) threw
   * in every test. Runtime permissions still wait for a grant, as on a device.
   */
  private static void grantInstallPermissions(Application app) {
    android.content.pm.PackageManager pm = app.getPackageManager();
    boolean all = "grant".equals(System.getProperty("apksim.permissions"));
    String[] requested;
    try {
      requested = pm.getPackageInfo(app.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return;
    }
    if (requested == null) return;
    java.util.List<String> grant = new java.util.ArrayList<>();
    for (String p : requested) {
      if (all || !isRuntimePermission(pm, p)) grant.add(p);
    }
    org.robolectric.Shadows.shadowOf(app).grantPermissions(grant.toArray(new String[0]));
  }

  /** Runtime ("dangerous") permissions need the user's grant; one the package manager does not know is install-time. */
  private static boolean isRuntimePermission(android.content.pm.PackageManager pm, String permission) {
    try {
      return pm.getPermissionInfo(permission, 0).getProtection() == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return RUNTIME_PERMISSIONS.contains(permission);
    }
  }

  /** API 36's runtime permissions, for when the package manager has no PermissionInfo for one. */
  private static final java.util.Set<String> RUNTIME_PERMISSIONS = java.util.Set.of(
      "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR", "android.permission.CAMERA",
      "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS",
      "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
      "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.RECORD_AUDIO",
      "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS", "android.permission.CALL_PHONE",
      "android.permission.ANSWER_PHONE_CALLS", "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
      "android.permission.ADD_VOICEMAIL", "android.permission.USE_SIP", "android.permission.BODY_SENSORS",
      "android.permission.BODY_SENSORS_BACKGROUND", "android.permission.ACTIVITY_RECOGNITION",
      "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
      "android.permission.RECEIVE_WAP_PUSH", "android.permission.RECEIVE_MMS",
      "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
      "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION",
      "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
      "android.permission.UWB_RANGING", "android.permission.NEARBY_WIFI_DEVICES", "android.permission.POST_NOTIFICATIONS");

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

  /**
   * The app's content providers, as ActivityThread installs them before Application.onCreate: each one in the app's
   * process, highest initOrder first, created, attached (its onCreate runs) and registered for its authorities.
   * Robolectric creates a provider only when it is first used, so a provider's startup work never ran: AndroidX
   * Startup's InitializationProvider runs the app's initializers (lifecycle, emoji, an HTTP client's that hands it the
   * application Context), and without it the HTTP client could not read the public suffix list from the app's assets
   * and canceled its requests.
   */
  private static void installProviders(Application app) {
    android.content.pm.ProviderInfo[] providers;
    try {
      providers = app.getPackageManager()
          .getPackageInfo(app.getPackageName(), android.content.pm.PackageManager.GET_PROVIDERS).providers;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return;
    }
    if (providers == null) return;
    String process = app.getApplicationInfo().processName;
    java.util.List<android.content.pm.ProviderInfo> ordered = new java.util.ArrayList<>(java.util.Arrays.asList(providers));
    ordered.sort((a, b) -> Integer.compare(b.initOrder, a.initOrder));
    for (android.content.pm.ProviderInfo info : ordered) {
      if (info.processName != null && process != null && !info.processName.equals(process)) continue;
      if (info.authority == null) continue;
      try {
        android.content.ContentProvider provider = (android.content.ContentProvider)
            Class.forName(info.name, true, app.getClassLoader()).getDeclaredConstructor().newInstance();
        provider.attachInfo(app, info);
        for (String authority : info.authority.split(";")) {
          org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider);
        }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        System.err.println("[apksim] content provider " + info.name + ": " + e);
      }
    }
  }
}
