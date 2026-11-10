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
import org.robolectric.RuntimeEnvironment;
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
 * are not kept: they are apksim's device controls (animscale, fontscale, --font-scale), which set more than the value;
 * a --setting of one goes through that control's code instead. bluetooth_on is not kept either: the adapter's state
 * follows --bluetooth (default on) at every launch, and a --setting of it is refused with a pointer there.
 */
final class SlotDevice {
  private static final String SETTINGS = "settings.properties";
  /** Keys that the device controls own. */
  private static final Set<String> NOT_KEPT =
      Set.of("global/animator_duration_scale", "system/font_scale", "global/bluetooth_on");
  /** A key the defaults had and the app deleted is kept as this prefix plus the key. */
  private static final String DELETED = "!";
  private static final long CHECK_NANOS = 250_000_000L;

  private static volatile Path dir;
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
  static synchronized void take(Path slot, boolean fresh, android.app.Application app) throws IOException {
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
    // The File overloads (getExternalStorageState(File) and the rest) read their own tables: a state covers a folder's
    // children, emulated and removable only the folder named, so the app's own external folder is named too.
    ShadowEnvironment.setExternalStorageState(sd.toFile(), Environment.MEDIA_MOUNTED);
    String pkg = System.getProperty("apksim.package");
    for (Path p : pkg == null ? java.util.List.of(sd) : java.util.List.of(sd, sd.resolve("Android/data/" + pkg))) {
      ShadowEnvironment.setExternalStorageEmulated(p.toFile(), true);
      ShadowEnvironment.setExternalStorageRemovable(p.toFile(), false);
    }

    seedFiles(sd);
    defaults = snapshot();
    Map<String, String> stored = read(d.resolve(SETTINGS));
    stored.forEach(SlotDevice::apply);
    applySeeds(app);
    dir = d;
    saved = stored;
    flush();
    // Ctrl-C or SIGTERM on a cold launch, or the app's System.exit, end the JVM past every other flush: a value set
    // within the last 250 ms would be lost. A no-op once close() has run, so never the reset's defaults.
    Runtime.getRuntime().addShutdownHook(new Thread(SlotDevice::close, "apksim-slot-settings"));
  }

  /**
   * Writes the launch's {@code --sdcard-file} seeds into the slot's /sdcard, over what the slot had (the option always
   * wins), before the app's process starts any code. Never through a link the app left in the slot: a seed whose path
   * runs through one is refused.
   */
  static void seedFiles(Path sd) throws IOException {
    long total = 0;
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.sdcard." + i);
      if (s == null) return;
      SdcardSeeds.File f = SdcardSeeds.parse(s);
      total += f.bytes().length;
      if (total > SdcardSeeds.TOTAL_MAX) {
        throw new IllegalArgumentException("--sdcard-file: over " + SdcardSeeds.TOTAL_MAX / 1024 + " KB in all");
      }
      Path target = sd.resolve(f.path()).normalize();
      Path real = sd.toRealPath();
      Path parent = target.getParent();
      Files.createDirectories(parent);
      if (!parent.toRealPath().startsWith(real) || Files.isSymbolicLink(target) || Files.isDirectory(target)) {
        throw new IllegalArgumentException("--sdcard-file " + f.path() + ": leads out of /sdcard or is a folder");
      }
      Path tmp = target.resolveSibling("." + target.getFileName() + Storage.tmpTag());
      Files.write(tmp, f.bytes());
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
  }

  /**
   * {@code --setting NS:KEY=VALUE} (repeatable, -Dapksim.setting.N): set before the app starts, as {@code adb shell
   * settings put} before its first launch would. In a running instance they are kept with the slot's other settings;
   * in a test run each test's setup applies them again (InstallState).
   */
  static void applySeeds(android.app.Application app) {
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
      String key = ns + "/" + s.substring(colon + 1, eq);
      String value = s.substring(eq + 1);
      if (key.equals("global/bluetooth_on")) {
        // The adapter owns it (Bluetooth: --bluetooth, the bluetooth command, svc, settings put), and Bluetooth's
        // setup writes it from the launch state before onCreate, so a seed alone would disagree with the adapter.
        throw new IllegalArgumentException("--setting " + s + ": Bluetooth is set with --bluetooth on|off");
      }
      if (NOT_KEPT.contains(key)) deviceControl(app, s, key, value);
      else apply(key, value);
    }
  }

  /**
   * A seed for a key a device control owns goes through that control's code, as `settings put` of it does (AdbShell):
   * setting the value alone would leave ValueAnimator's scale or the configuration's font scale as they were. Not kept
   * in the file; given again at the next launch, it applies again.
   */
  private static void deviceControl(android.app.Application app, String seed, String key, String value) {
    float f = number(seed, key, value);
    if (key.equals("global/animator_duration_scale")) {
      // = `animscale F`; the last seed wins, and --anim-scale (applied after it in a launch, before it in a test's
      // setup) wins over any seed, as --font-scale does.
      if (System.getProperty("apksim.animScale", "").isEmpty()) DeviceControls.setAnimatorScale(app, f);
    } else {
      applyFontScale();  // = `fontscale F`, for the value the launch ends up with
    }
  }

  private static float number(String seed, String key, String value) {
    try {
      return Float.parseFloat(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--setting " + seed + ": not a number (or use "
          + (key.endsWith("font_scale") ? "--font-scale" : "the animscale command") + ")");
    }
  }

  private static final String FONT_SEED = "system:font_scale=";

  /**
   * The font scale this launch asks for: {@code --font-scale} if given (it wins over a seed), else the last
   * {@code --setting system:font_scale=F}; null for neither. Read from the properties each time, so a test run's
   * setup after a reset gets the same answer as onCreate did.
   */
  static Float launchFontScale() {
    String explicit = System.getProperty("apksim.fontScale");
    if (explicit != null) return Float.parseFloat(explicit);
    Float seeded = null;
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.setting." + i);
      if (s == null) return seeded;
      if (s.startsWith(FONT_SEED)) seeded = number(s, "system/font_scale", s.substring(FONT_SEED.length()));
    }
  }

  /**
   * Sets the launch's font scale (launchFontScale) as `fontscale` does, if it has one. Again after anything that resets
   * it: Host.run's and a test's setQualifiers (a full qualifier string resets the font scale).
   */
  static void applyFontScale() {
    Float f = launchFontScale();
    if (f == null) return;
    RuntimeEnvironment.setFontScale(f);
    putFontScaleSetting(f);
  }

  /**
   * Settings.System.FONT_SCALE as a device's font-size setting writes it beside the configuration (the seed, --font-scale
   * and `fontscale`), straight into the shadow's table: no ContentResolver may exist yet. Not kept in the slot's file.
   */
  static void putFontScaleSetting(float f) {
    system().put(0, android.provider.Settings.System.FONT_SCALE, Optional.of((Object) f));
  }

  /**
   * A test's setup, after SimRunner's setQualifiers: a seeded font scale again, for the test and its activities (only
   * with a seed: --font-scale alone has never applied in test runs).
   */
  static void afterTestQualifiers() {
    for (int i = 0; ; i++) {
      String s = System.getProperty("apksim.setting." + i);
      if (s == null) return;
      if (s.startsWith(FONT_SEED)) {
        applyFontScale();
        return;
      }
    }
  }

  /**
   * Persistence ends before Robolectric's reset after Host.run (returned or thrown), which puts the default settings
   * back: the live values are written once more, and no later flush (the launch's client hanging up) writes again.
   * Under the same lock as flush, so the hangup thread cannot write in between.
   */
  static synchronized void close() {
    flush();
    dir = null;
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
   * Writes the settings if they changed. On the way out too (Host.exit, the launch's client hanging up); nothing once
   * close() has run (before Robolectric's reset after Host.run, which puts the defaults back).
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
