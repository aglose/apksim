package dev.apksim;

import android.os.Environment;
import com.google.common.collect.Table;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;

/**
 * What a data slot keeps of the device beside the app's own data, in {@code <slot>.device/} next to it (so
 * {@code --fresh}, {@code ctl.py erase} and apksim serve's cleanup clear it with the slot): external storage
 * ({@code sdcard/}, what the app sees as /sdcard and adb pushes to) and the Settings providers' values
 * ({@code settings.properties}: Global, Secure and System as the app and {@code adb shell settings put} left them). On a
 * device both outlive the app's process, and apps keep things there on purpose (a device id in /sdcard, a setup-done flag
 * in Settings.Global that survives clearing the app's data); Robolectric keeps both per process, so every launch began
 * without them. Running instances only: a test run starts each test from a clean device, as before.
 *
 * <p>Settings live in Robolectric's shadows' static maps, which have no write hook, so the values that differ from the
 * defaults the slot was taken with are written when they change (checked from Host's loop every 250 ms, as a device's
 * settings provider writes its file a moment after a change) and on the way out. animator_duration_scale and font_scale
 * are not kept: they are apksim's device controls (animscale, fontscale, --font-scale), which set more than the value.
 */
final class SlotDevice {
  private static final String SETTINGS = "settings.properties";
  /** Keys that the device controls own. */
  private static final Set<String> NOT_KEPT = Set.of("global/animator_duration_scale", "system/font_scale");
  /** A key the defaults had and the app deleted is kept as this prefix plus the key. */
  private static final String DELETED = "!";
  private static final long CHECK_NANOS = 250_000_000L;

  private static Path dir;
  /** The settings when the slot was taken, before anything stored was applied: what "differs" is measured against. */
  private static Map<String, String> defaults;
  /** What the settings file holds. */
  private static Map<String, String> saved;
  private static long nextCheck;

  private SlotDevice() {}

  /** The device folder of a data slot. */
  static Path dirFor(Path slot) {
    return slot.resolveSibling(slot.getFileName() + ".device");
  }

  /** Before Application.onCreate (Host.usePersistentDataDir): external storage and settings from the slot. */
  static synchronized void take(Path slot, boolean fresh) throws IOException {
    Path d = dirFor(slot);
    if (fresh && Files.isDirectory(d)) Storage.clearDataDir(d);
    Path sd = d.resolve("sdcard");
    Files.createDirectories(sd);
    // /sdcard and its public folders (Download, Pictures...) are one tree, mounted, as on a device's emulated storage.
    // Robolectric's are two temp folders that start "removed".
    ShadowEnvironment.setExternalStorageDirectory(sd);
    ShadowEnvironment.setExternalStoragePublicDirectory(sd);
    ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
    ShadowEnvironment.setIsExternalStorageEmulated(true);

    defaults = snapshot();
    Map<String, String> stored = read(d.resolve(SETTINGS));
    stored.forEach(SlotDevice::apply);
    applySeeds();
    dir = d;
    saved = stored;
    flush();
  }

  /**
   * {@code --setting NS:KEY=VALUE} (repeatable, -Dapksim.setting.N): set before the app starts, as {@code adb shell
   * settings put} before its first launch would. In a running instance they are kept with the slot's other settings;
   * in a test run each test's setup applies them again (InstallState).
   */
  static void applySeeds() {
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.setting." + i);
      if (s == null) return;
      int colon = s.indexOf(':');
      int eq = s.indexOf('=', colon + 1);
      String ns = colon > 0 ? s.substring(0, colon) : "";
      if (!ns.matches("global|secure|system") || eq <= colon + 1) {
        System.err.println("[apksim] --setting " + s + ": expected global|secure|system:KEY=VALUE");
        continue;
      }
      apply(ns + "/" + s.substring(colon + 1, eq), s.substring(eq + 1));
    }
  }

  /** From Host's loop: writes the settings when they changed since the last check (at most every 250 ms). */
  static void tick() {
    if (dir == null) return;
    long now = System.nanoTime();
    if (now - nextCheck < 0) return;
    nextCheck = now + CHECK_NANOS;
    flush();
  }

  /**
   * Writes the settings if they changed. On the way out too (Host.exit, the launch's client hanging up), but never once
   * Host.run has returned: Robolectric's reset after the "test" puts the defaults back, which would erase the file.
   */
  static synchronized void flush() {
    if (dir == null) return;
    Map<String, String> now;
    try {
      now = changed(snapshot());
    } catch (RuntimeException e) {
      return;  // a write on another thread meanwhile: the next check reads it again
    }
    if (now.equals(saved)) return;
    try {
      write(dir.resolve(SETTINGS), now);
      saved = now;
    } catch (IOException e) {
      System.err.println("[apksim] could not save settings: " + e);
    }
  }

  // ---------------------------------------------------------------- the shadows' maps

  @SuppressWarnings("unchecked")
  private static Map<String, Optional<Object>> global() {
    return (Map<String, Optional<Object>>) ReflectionHelpers.getStaticField(ShadowSettings.ShadowGlobal.class, "settings");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Optional<String>> secure() {
    return (Map<String, Optional<String>>) ReflectionHelpers.getStaticField(ShadowSettings.ShadowSecure.class, "dataMap");
  }

  @SuppressWarnings("unchecked")
  private static Table<Integer, String, Optional<Object>> system() {
    return (Table<Integer, String, Optional<Object>>) ReflectionHelpers.getStaticField(ShadowSettings.ShadowSystem.class, "settingsByUser");
  }

  /** Every setting as "global/KEY", "secure/KEY", "system/KEY" (user 0) or "system@USER/KEY"; null for a deleted one. */
  private static Map<String, String> snapshot() {
    Map<String, String> m = new TreeMap<>();
    global().forEach((k, v) -> m.put("global/" + k, v.map(String::valueOf).orElse(null)));
    secure().forEach((k, v) -> m.put("secure/" + k, v.orElse(null)));
    Table<Integer, String, Optional<Object>> sys = system();
    synchronized (sys) {  // Tables.synchronizedTable: iteration holds its lock
      for (Table.Cell<Integer, String, Optional<Object>> c : sys.cellSet()) {
        String ns = c.getRowKey() == 0 ? "system" : "system@" + c.getRowKey();
        m.put(ns + "/" + c.getColumnKey(), c.getValue().map(String::valueOf).orElse(null));
      }
    }
    return m;
  }

  /** The settings that differ from the defaults, as the file keeps them. */
  private static Map<String, String> changed(Map<String, String> now) {
    Map<String, String> out = new TreeMap<>();
    Set<String> keys = new HashSet<>(now.keySet());
    keys.addAll(defaults.keySet());
    for (String k : keys) {
      if (NOT_KEPT.contains(k)) continue;
      String v = now.get(k);
      if (Objects.equals(v, defaults.get(k))) continue;
      if (v != null) out.put(k, v);
      else out.put(DELETED + k, "");
    }
    return out;
  }

  private static void apply(String key, String value) {
    boolean deleted = key.startsWith(DELETED);
    String k = deleted ? key.substring(DELETED.length()) : key;
    if (NOT_KEPT.contains(k)) return;
    int slash = k.indexOf('/');
    if (slash <= 0) return;
    String ns = k.substring(0, slash);
    String name = k.substring(slash + 1);
    Optional<String> v = deleted ? Optional.empty() : Optional.of(value);
    if (ns.equals("global")) {
      global().put(name, v.map(s -> (Object) s));
    } else if (ns.equals("secure")) {
      secure().put(name, v);
    } else if (ns.equals("system") || ns.startsWith("system@")) {
      int user;
      try {
        user = ns.equals("system") ? 0 : Integer.parseInt(ns.substring("system@".length()));
      } catch (NumberFormatException e) {
        return;
      }
      system().put(user, name, v.map(s -> (Object) s));
    }
  }

  // ---------------------------------------------------------------- the file

  private static Map<String, String> read(Path file) throws IOException {
    Map<String, String> m = new TreeMap<>();
    if (!Files.exists(file)) return m;
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(r);
    }
    for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
    return m;
  }

  private static void write(Path file, Map<String, String> m) throws IOException {
    Properties p = new Properties();
    p.putAll(m);
    Path tmp = file.resolveSibling(file.getFileName() + Storage.tmpTag());
    try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
      p.store(w, "Settings.Global/Secure/System that differ from apksim's defaults (\"!\" + key: deleted)");
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
