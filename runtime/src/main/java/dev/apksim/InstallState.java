package dev.apksim;

import android.app.Application;
import android.provider.Settings;

/**
 * What a device has before an app's process starts, set up just before Application.onCreate
 * (ShadowSimInstrumentation.callApplicationOnCreate) in test runs and running instances alike, warm or cold: an app
 * reads it from its first line of startup. Running instances got none of it before onCreate (the host's networks only
 * after it, from Host.run), and one app's onCreate died on a normal permission's check (BLUETOOTH).
 */
final class InstallState {
  private InstallState() {}

  static void beforeApplicationOnCreate(Application app) {
    // --setting, at each test's setup (Robolectric resets the settings between tests); a running instance applied them
    // with its slot's settings already (SlotDevice.take, just before this). Before the ANDROID_ID, so a seeded one wins
    // in both, as one the slot kept does.
    if (TestActivities.testRun()) SlotDevice.applySeeds(app);
    seedAndroidId(app);
    grantInstallPermissions(app);
    hostNetworks(app);
    standInBrowser(app);  // before the providers: AndroidX Startup initializers may look for a browser
    installProviders(app);
  }

  /**
   * A standby's warm-up (StandbyWarmup, an app with no APK): the same steps on its application, so a launch finds their
   * classes loaded and the framework's permission levels read (~15 ms of a warm launch). Robolectric resets what they
   * set before the launch.
   */
  static void warm(Application app) {
    seedAndroidId(app);
    android.content.pm.PackageManager pm = app.getPackageManager();
    try {
      pm.getPackageInfo(app.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
      pm.getPackageInfo(app.getPackageName(), android.content.pm.PackageManager.GET_PROVIDERS);
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      // nothing to warm
    }
    for (String p : new String[] {"android.permission.INTERNET", "android.permission.CAMERA", "dev.apksim.warm.PERMISSION"}) {
      eligible(pm, app.getPackageName(), p, false);
    }
    org.robolectric.Shadows.shadowOf(app).grantPermissions("android.permission.INTERNET");
    hostNetworks(app);
  }

  /** The stand-in browser's package: what resolves a web link, as a device with Chrome does. */
  static final String BROWSER = "com.android.chrome";

  /**
   * A browser, as an emulator image has one: a package whose activity answers the VIEW probes browser-detecting code sends (a
   * web link, with or without BROWSABLE), so `queryIntentActivities` and `resolveActivity` find it. OAuth SDKs (Auth0's
   * WebAuthProvider, AppAuth) ask for one before they start and stop with "no compatible browser" otherwise (issue
   * 236). A launch for it leaves the app, so Host hands it to the Mac's browser (DeviceControls.openOnHost). It has no
   * CustomTabsService, so those SDKs fall back to a plain VIEW launch. Not for an app that is itself a browser
   * (its own filters resolve first).
   */
  private static void standInBrowser(Application app) {
    try {
      android.content.pm.PackageManager pm = app.getPackageManager();
      try {
        pm.getPackageInfo(BROWSER, 0);
        return;
      } catch (android.content.pm.PackageManager.NameNotFoundException e) {
        // not there yet
      }
      android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
      pi.packageName = BROWSER;
      pi.versionName = "130.0.0.0";
      pi.signatures = new android.content.pm.Signature[0];  // AppAuth iterates them (GET_SIGNATURES)
      pi.applicationInfo = new android.content.pm.ApplicationInfo();
      pi.applicationInfo.packageName = BROWSER;
      pi.applicationInfo.name = "Chrome";
      pi.applicationInfo.enabled = true;
      pi.applicationInfo.flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM;
      android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
      ai.packageName = BROWSER;
      ai.name = "com.google.android.apps.chrome.Main";
      ai.exported = true;
      ai.enabled = true;
      ai.applicationInfo = pi.applicationInfo;
      pi.activities = new android.content.pm.ActivityInfo[] {ai};
      org.robolectric.shadows.ShadowPackageManager spm = org.robolectric.Shadows.shadowOf(pm);
      spm.addPackage(pi);
      android.content.ComponentName cn = new android.content.ComponentName(BROWSER, ai.name);
      android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_VIEW);
      f.addCategory(android.content.Intent.CATEGORY_DEFAULT);
      f.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
      f.addDataScheme("http");
      f.addDataScheme("https");
      spm.addActivityIfNotPresent(cn);
      // Robolectric cannot attach intent filters to an activity added this way, so the probes browser-detecting code
      // sends are answered by intent: AppAuth's BrowserSelector (VIEW + BROWSABLE on `http:` and example.com), Auth0's
      // (the same without the category), https for either.
      android.net.Uri[] urls = {android.net.Uri.parse("http://www.example.com"), android.net.Uri.parse("https://www.example.com"),
          android.net.Uri.parse("http://example.com"), android.net.Uri.parse("https://example.com"),
          android.net.Uri.fromParts("http", "", null), android.net.Uri.fromParts("https", "", null)};  // AppAuth's "http:"
      for (android.net.Uri url : urls) {
        for (boolean browsable : new boolean[] {false, true}) {
          android.content.Intent probe = new android.content.Intent(android.content.Intent.ACTION_VIEW, url);
          if (browsable) probe.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
          android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo();
          ri.activityInfo = ai;
          ri.priority = 0;
          ri.isDefault = true;
          ri.filter = f;  // AppAuth asks for GET_RESOLVED_FILTER and skips a result without one
          ri.match = 0x108000;
          spm.addResolveInfoForIntentNoDefaults(probe, ri);
        }
      }
    } catch (Exception | Error e) {
      System.err.println("[apksim] stand-in browser: " + e);
    }
  }

  /**
   * The host's network, as a device's connected one: HostNetworks shows its interfaces as Android networks with
   * INTERNET and VALIDATED. Robolectric's default network has neither, so an app
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
      if (Api.LEVEL != 36) e.printStackTrace();
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
   * leaves it null. An app that reads it as its device id, into a non-null Kotlin String, threw in every test. In test
   * runs a fixed value, as one device would have; a test that sets its own keeps it. A running instance's comes from
   * its data slot, as its serial does (DeviceIdentity): the same each launch of a slot, another for another slot, so
   * two instances side by side are two devices to a backend.
   */
  private static void seedAndroidId(Application app) {
    android.content.ContentResolver cr = app.getContentResolver();
    if (Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID) == null) {
      Settings.Secure.putString(cr, Settings.Secure.ANDROID_ID, TestActivities.testRun() ? "a75e5101d0c0ffee" : slotAndroidId());
    }
  }

  private static String slotAndroidId() {
    String slot = Storage.heldSlot();
    try {
      byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(
          ("apksim-android-id:" + (slot == null ? "default" : slot)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(h, 0, 8);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
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
   * behaviour, for a suite that must run past a provider apksim cannot start). A running instance logs by default
   * (`-Dapksim.providerFailures=fail` to stop there): before, its providers were made only when first used, and an
   * app that ran then keeps running, its failing provider left to Robolectric's on-first-use creation.
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
        String failures = System.getProperty("apksim.providerFailures", TestActivities.testRun() ? "fail" : "log");
        if ("log".equals(failures)) {
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
