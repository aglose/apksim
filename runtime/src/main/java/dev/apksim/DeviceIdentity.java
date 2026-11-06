package dev.apksim;

import android.app.Application;
import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.util.ReflectionHelpers;

/**
 * Who the device says it is, set just before Application.onCreate in running instances and test runs alike
 * (ShadowSimInstrumentation.callApplicationOnCreate), so an app reads it from its first line of startup:
 * <ul>
 *   <li>its serial number, Build.getSerial() and Build.SERIAL ({@code --serial VALUE}, -Dapksim.serial). Robolectric
 *       answers "unknown", which a backend that checks a serial's format rejects, so by default it is 16 letters and
 *       digits derived from the data slot: the same each launch of one slot (one device), another for another slot
 *       (instance-1, serve-0), and the default slot's in test runs.</li>
 *   <li>system features, PackageManager.hasSystemFeature ({@code --feature NAME}, repeatable, -Dapksim.features=a,b;
 *       {@code NAME=false} takes one away): Robolectric's package manager has none of a phone's hardware features,
 *       so an app that tells device variants apart by FEATURE_CAMERA_ANY always saw the camera-less one.</li>
 *   <li>any Build or Build.VERSION field ({@code --build FIELD=VALUE}, repeatable, -Dapksim.build.FIELD=VALUE):
 *       MODEL, MANUFACTURER, BRAND, DEVICE, PRODUCT, VERSION.RELEASE, VERSION.SECURITY_PATCH..., typed by the field
 *       (String, int, long, boolean, String[] from a comma list).</li>
 * </ul>
 * Robolectric's ShadowBuild and package manager reset all of it between tests (Build's static initializer runs again),
 * and this runs again before the next test's Application.onCreate.
 */
final class DeviceIdentity {
  private DeviceIdentity() {}

  static void beforeApplicationOnCreate(Application app) {
    try {
      buildFields();
      String serial = serial();
      ShadowBuild.setSerial(serial);
      ReflectionHelpers.setStaticField(Build.class, "SERIAL", serial);
      features(app);
    } catch (RuntimeException e) {
      System.err.println("[apksim] device identity: " + e);
    }
  }

  /** The serial: --serial, else one derived from the data slot this instance holds. */
  static String serial() {
    String s = System.getProperty("apksim.serial");
    if (s != null && !s.isEmpty()) return s;
    return defaultSerial(TestActivities.testRun() ? null : Host.dataSlot());
  }

  /**
   * 16 upper-case letters and digits from SHA-256 of the slot's name ("default" when there is none), a letter
   * first and a digit second so the string has both, as most devices' serials do.
   */
  static String defaultSerial(String slot) {
    byte[] h;
    try {
      h = MessageDigest.getInstance("SHA-256").digest(("apksim-serial:" + (slot == null ? "default" : slot))
          .getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    char[] out = new char[16];
    for (int i = 0; i < out.length; i++) out[i] = digits.charAt((h[i] & 0xff) % 36);
    out[0] = (char) ('A' + (h[16] & 0xff) % 26);
    out[1] = (char) ('0' + (h[17] & 0xff) % 10);
    return new String(out);
  }

  /** -Dapksim.features=a,b,c=false: each one made present (or, with =false, absent). */
  private static void features(Application app) {
    String list = System.getProperty("apksim.features");
    if (list == null || list.isBlank()) return;
    var pm = Shadows.shadowOf(app.getPackageManager());
    for (String f : list.split(",")) {
      f = f.trim();
      if (f.isEmpty()) continue;
      boolean on = true;
      int eq = f.indexOf('=');
      if (eq > 0) {
        on = !f.substring(eq + 1).trim().equalsIgnoreCase("false");
        f = f.substring(0, eq).trim();
      }
      pm.setSystemFeature(f, on);
    }
  }

  /** The -Dapksim.build.FIELD=VALUE properties, by field name (upper case). */
  static Map<String, String> buildOptions() {
    Map<String, String> out = new TreeMap<>();
    for (String k : System.getProperties().stringPropertyNames()) {
      if (k.startsWith("apksim.build.")) out.put(k.substring("apksim.build.".length()).toUpperCase(Locale.ROOT), System.getProperty(k));
    }
    return out;
  }

  private static void buildFields() {
    for (Map.Entry<String, String> e : buildOptions().entrySet()) {
      try {
        setBuildField(e.getKey(), e.getValue());
      } catch (RuntimeException | ReflectiveOperationException x) {
        System.err.println("[apksim] --build " + e.getKey() + "=" + e.getValue() + ": " + x.getMessage());
      }
    }
  }

  /** Sets Build.NAME, or Build.VERSION.NAME for "VERSION.NAME", from [value] in the field's type. */
  static void setBuildField(String name, String value) throws ReflectiveOperationException {
    Class<?> owner = Build.class;
    String field = name;
    if (name.startsWith("VERSION.")) {
      owner = Build.VERSION.class;
      field = name.substring("VERSION.".length());
    }
    if (owner == Build.VERSION.class && (field.equals("SDK_INT") || field.equals("SDK"))) {
      throw new IllegalArgumentException("the API level is the framework's (36): it cannot be changed");
    }
    if (owner == Build.class && field.equals("SERIAL")) {
      throw new IllegalArgumentException("use --serial");
    }
    Field f = owner.getDeclaredField(field);
    if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isPublic(f.getModifiers())) {
      throw new NoSuchFieldException(name + " is not a public static field of " + owner.getName());
    }
    Class<?> t = f.getType();
    Object v;
    if (t == String.class) v = value;
    else if (t == int.class) v = Integer.parseInt(value);
    else if (t == long.class) v = Long.parseLong(value);
    else if (t == boolean.class) v = Boolean.parseBoolean(value);
    else if (t == String[].class) {
      List<String> parts = new ArrayList<>();
      for (String p : value.split(",")) if (!p.isBlank()) parts.add(p.trim());
      v = parts.toArray(new String[0]);
    } else throw new IllegalArgumentException("field type " + t.getName() + " is not supported");
    ReflectionHelpers.setStaticField(owner, field, v);
  }
}
