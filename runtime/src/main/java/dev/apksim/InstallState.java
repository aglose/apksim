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
    SlotDevice.applySeeds(app);  // --setting, at each test's setup: Robolectric resets the settings between tests
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
      // With --network-profile offline the test environment took the networks away before this; keep them away (and
      // keep these as the ones `network online` brings back), as Host does.
      NetConnectivity.networksReplaced();
    } catch (Exception e) {
      System.err.println("[apksim] host networks: " + e);
    }
  }

  /**
   * What installing the APK grants: every requested permission of protection level normal (INTERNET, VIBRATE,
   * BLUETOOTH...), and with --permissions grant the runtime ones too, as `adb install -g` does. Robolectric grants
   * none, so a framework check of a normal permission (BluetoothAdapter.getProfileProxy's, in one app's startup) threw
   * in every test. Runtime permissions still wait for a grant, as on a device. Signature, privileged and internal
   * permissions (WRITE_SECURE_SETTINGS) stay denied, as for an ordinary APK on a device, except a signature
   * permission the app defines itself, which its own signature satisfies. A permission the package manager does not
   * know: a framework one (android.permission.*) is denied unless it is a known runtime one; one another app defines
   * (Play services' c2dm RECEIVE, Play's install referrer) is granted as install-time, since other apps are not
   * modelled here and those are normal on a device. Robolectric's package manager has no PermissionInfo for the
   * framework's permissions, so their levels come from the framework's own manifest (frameworkProtection).
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
      if (eligible(pm, app.getPackageName(), p, all)) grant.add(p);
    }
    org.robolectric.Shadows.shadowOf(app).grantPermissions(grant.toArray(new String[0]));
  }

  /** Whether installing grants [permission] (with [runtime]: --permissions grant, runtime ones as well). */
  private static boolean eligible(android.content.pm.PackageManager pm, String pkg, String permission, boolean runtime) {
    android.content.pm.PermissionInfo info;
    try {
      info = pm.getPermissionInfo(permission, 0);
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      Integer level = frameworkProtection().get(permission);
      if (level != null) return grantedAt(level & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE, runtime, false);
      if (RUNTIME_PERMISSIONS.contains(permission)) return runtime;
      return !permission.startsWith("android.permission.");
    }
    return grantedAt(info.getProtection(), runtime, pkg.equals(info.packageName));
  }

  /** Granted at install at base protection level [protection]: normal always, dangerous with [runtime], signature if [own]. */
  private static boolean grantedAt(int protection, boolean runtime, boolean own) {
    if (protection == android.content.pm.PermissionInfo.PROTECTION_NORMAL) return true;
    if (protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) return runtime;
    if (protection == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE) return own;
    return false;  // signatureOrSystem, internal, or a level this does not know
  }

  private static java.util.Map<String, Integer> frameworkProtection;

  /**
   * The framework's permissions and their android:protectionLevel, from the framework's own AndroidManifest.xml (in
   * the jar that holds android.view.View), read once per process (~1,000 permissions).
   */
  static synchronized java.util.Map<String, Integer> frameworkProtection() {
    if (frameworkProtection != null) return frameworkProtection;
    java.util.Map<String, Integer> out = new java.util.HashMap<>();
    try {
      java.net.URL jar = android.view.View.class.getProtectionDomain().getCodeSource().getLocation();
      String xml;
      try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(new java.io.File(jar.toURI()));
           java.io.InputStream in = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
        xml = Axml.decode(in.readAllBytes());
      }
      java.util.regex.Matcher element = java.util.regex.Pattern.compile("<permission\\s([^>]*)>").matcher(xml);
      java.util.regex.Pattern name = java.util.regex.Pattern.compile("android:name=\"([^\"]+)\"");
      java.util.regex.Pattern level = java.util.regex.Pattern.compile("android:protectionLevel=\"0x([0-9a-fA-F]+)\"");
      while (element.find()) {
        java.util.regex.Matcher n = name.matcher(element.group(1));
        java.util.regex.Matcher l = level.matcher(element.group(1));
        if (n.find()) out.put(n.group(1), l.find() ? (int) Long.parseLong(l.group(1), 16) : 0);
      }
    } catch (Exception e) {
      System.err.println("[apksim] framework permissions: " + e);
    }
    frameworkProtection = out;
    return out;
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
   * and canceled its requests. A provider that cannot be made or whose onCreate throws fails the test's setup, as it
   * crashes the app's start on a device; `-Dapksim.providerFailures=log` only logs it and goes on (the earlier
   * behaviour, for a suite that must run past a provider apksim cannot start).
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
        if ("log".equals(System.getProperty("apksim.providerFailures"))) {
          System.err.println("[apksim] content provider " + info.name + ": " + e);
        } else {
          Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
          throw new IllegalStateException("content provider " + info.name + " failed at the app's start, as it would crash it on a "
              + "device (-Dapksim.providerFailures=log to go on)", cause);
        }
      }
    }
  }
}
