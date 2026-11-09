package dev.apksim.serve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * `apksim beam to-device ID`: carries a saved session on to a real device attached over adb (issue 191): its build
 * (when the device's differs), its app data (the slot, written with `run-as`, so a debuggable build), the Settings
 * values that differ from apksim's defaults, and its launch options, then starts its activity. What it overwrites on
 * the device is backed up first (`STATE/beam/device-backup/`), with the old Settings values and a script that puts
 * them back.
 *
 * <p>Everything is a call of the `adb` on PATH, so it is checked against a stand-in (scripts/beam-device-check.py).
 * Values that reach the device's shell are quoted ({@link #q}); packages, keys and activities are validated first.
 */
final class DeviceHandoff {
  private DeviceHandoff() {}

  /** Settings that make the device what it is: never carried over, even when named. */
  static final Set<String> IDENTITY = Set.of("secure/android_id", "global/device_id", "global/device_name",
      "secure/bluetooth_address", "secure/bluetooth_name", "global/boot_count", "global/adb_enabled",
      "global/development_settings_enabled", "global/install_non_market_apps", "secure/install_non_market_apps");

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  static final String USAGE = """
      usage: apksim beam to-device ID [-s SERIAL] [--data] [--settings KEY,KEY...|all] [--install replace|skip] [--yes] [--dry-run]
        --data      replace the app's data on the device with the session's (the device's is backed up first)
        --settings  put the session's Settings values on the device (ns/key such as global/foo; all = all but the device's
                    identity ones). The old values are printed and saved with a script that puts them back
        --install   replace: install the session's build over the device's when they differ (same signer only; the
                    device's data is kept); skip (default): leave the device's build as it is
        --yes       do not ask before overwriting; --dry-run only says what would be done
      The session's activity is started on the device afterwards.""";

  record Options(String id, String serial, boolean data, String settings, boolean replace, boolean yes, boolean dry) {}

  record Result(int code, String out) {}

  /** Runs `adb` (with `-s serial`); stdout and stderr together. */
  static Result adb(String serial, List<String> args, byte[] stdin) throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(List.of("adb"));
    if (serial != null) cmd.addAll(List.of("-s", serial));
    cmd.addAll(args);
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    Thread feed = Thread.ofVirtual().start(() -> {
      try (OutputStream o = p.getOutputStream()) {
        if (stdin != null) o.write(stdin);
      } catch (IOException e) {
        // the process ended first: its exit code says so
      }
    });
    byte[] out = p.getInputStream().readAllBytes();
    if (!p.waitFor(10, TimeUnit.MINUTES)) {
      p.destroyForcibly();
      throw new IOException("adb " + String.join(" ", args) + " did not finish");
    }
    feed.join();
    return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
  }

  private static Result sh(String serial, String script) throws IOException, InterruptedException {
    return adb(serial, List.of("shell", script), null);
  }

  /** `s` as one word for the device's shell. */
  static String q(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  static Options parse(List<String> a) {
    String id = null, serial = null, settings = null;
    boolean data = false, replace = false, yes = false, dry = false;
    for (int i = 0; i < a.size(); i++) {
      String o = a.get(i);
      switch (o) {
        case "-s" -> {
          if (++i >= a.size()) return null;
          serial = a.get(i);
        }
        case "--settings" -> {
          if (++i >= a.size()) return null;
          settings = a.get(i);
        }
        case "--install" -> {
          if (++i >= a.size() || !List.of("replace", "skip").contains(a.get(i))) return null;
          replace = a.get(i).equals("replace");
        }
        case "--data" -> data = true;
        case "--yes" -> yes = true;
        case "--dry-run" -> dry = true;
        default -> {
          if (o.startsWith("-") || id != null) return null;
          id = o;
        }
      }
    }
    if (id == null || serial != null && !serial.matches("[A-Za-z0-9._:\\-]{1,100}")) return null;
    return new Options(id, serial, data, settings, replace, yes, dry);
  }

  /** The device to use: the one named, or the only one attached. Null with the reason printed. */
  static String device(String named) throws IOException, InterruptedException {
    Result r = adb(null, List.of("devices"), null);
    if (r.code() != 0) {
      System.err.println("apksim beam to-device: adb failed: " + r.out().strip());
      return null;
    }
    List<String> ready = new ArrayList<>();
    for (String l : r.out().split("\n")) {
      String[] f = l.strip().split("\\s+");
      if (f.length == 2 && f[1].equals("device")) ready.add(f[0]);
    }
    if (named != null) {
      if (ready.contains(named)) return named;
      System.err.println("apksim beam to-device: no device " + named + " is attached and authorized (adb devices: "
          + (ready.isEmpty() ? "none" : String.join(", ", ready)) + ")");
      return null;
    }
    if (ready.size() == 1) return ready.get(0);
    System.err.println(ready.isEmpty() ? "apksim beam to-device: no adb device is attached and authorized"
        : "apksim beam to-device: several adb devices are attached (" + String.join(", ", ready) + "): name one with -s SERIAL");
    return null;
  }

  /** The settings the session carries: the slot's file, then the launch options' `--setting`s over it. */
  static Map<String, String> sessionSettings(Path slot, List<String> launch) throws IOException {
    Map<String, String> m = new TreeMap<>();
    Path f = slot.resolveSibling(slot.getFileName() + ".device").resolve("settings.properties");
    if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      Properties p = new Properties();
      try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
        p.load(r);
      }
      for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
    }
    for (int i = 0; i + 1 < launch.size(); i++) {
      if (!launch.get(i).equals("--setting")) continue;
      String s = launch.get(++i);
      int c = s.indexOf(':'), e = s.indexOf('=', c + 1);
      if (c > 0 && e > c + 1) m.put(s.substring(0, c) + "/" + s.substring(c + 1, e), s.substring(e + 1));
    }
    return m;
  }

  /** The settings to put, chosen by `--settings` (null: none). A key is `ns/key`; a null value: delete it. */
  static Map<String, String> chooseSettings(Map<String, String> all, String want, List<String> notes) {
    Map<String, String> out = new TreeMap<>();
    if (want == null) return out;
    Set<String> names = want.equals("all") ? null : Set.of(want.split(","));
    for (Map.Entry<String, String> e : all.entrySet()) {
      boolean deleted = e.getKey().startsWith("!");  // SlotDevice: a default the app deleted
      String key = deleted ? e.getKey().substring(1) : e.getKey();
      if (names != null && !names.contains(key)) continue;
      if (!key.matches("(global|secure|system)/[A-Za-z0-9_.\\-]{1,100}")) {
        notes.add("left out " + key + ": not a settings name");
      } else if (IDENTITY.contains(key)) {
        notes.add("left out " + key + ": it identifies the device");
      } else if (key.equals("system/font_scale") || key.equals("global/animator_duration_scale") || key.equals("global/bluetooth_on")) {
        notes.add("left out " + key + ": a device control");
      } else {
        out.put(key, deleted ? null : e.getValue());
      }
    }
    if (names != null) {
      for (String n : names) {
        if (IDENTITY.contains(n)) notes.add("refused " + n + ": it identifies the device");
        else if (!all.containsKey(n) && !all.containsKey("!" + n)) notes.add(n + " is not among the session's settings");
      }
    }
    return out;
  }

  static String launchValue(List<String> launch, String option) {
    for (int i = 0; i + 1 < launch.size(); i++) if (launch.get(i).equals(option)) return launch.get(i + 1);
    return null;
  }

  static int command(BeamStore store, Catalog catalog, List<String> args) throws IOException, InterruptedException {
    Options o = parse(args);
    if (o == null) {
      System.err.println(USAGE);
      return 2;
    }
    Map<String, Object> rec = store.saved(o.id());
    if (rec == null) {
      System.err.println("apksim beam to-device: no saved session " + o.id() + " (apksim beam saved lists them)");
      return 1;
    }
    String pkg = rec.get("package") instanceof String s && s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") ? s : null;
    String sha = rec.get("sha256") instanceof String h ? h : "";
    Path slot = store.slotOf(rec);
    if (pkg == null || slot == null) {
      System.err.println("apksim beam to-device: the record of " + o.id() + " has no usable package");
      return 1;
    }
    List<String> launch = new ArrayList<>();
    if (rec.get("launch") instanceof List<?> l) for (Object x : l) launch.add(String.valueOf(x));
    launch = Catalog.launchOptions(launch);
    String activity = launchValue(launch, "--activity");
    if (activity != null && !activity.matches("\\.?[A-Za-z0-9_$.]{1,300}")) {
      System.err.println("apksim beam to-device: the session's activity name is not one adb can start safely: " + activity);
      return 1;
    }
    String serial = device(o.serial());
    if (serial == null) return 1;
    if (o.replace() && !catalog.hasBuild(sha)) {
      System.err.println("apksim beam to-device: the build of " + o.id() + " is no longer on this Mac (--install replace needs it);"
          + " beam it again from its site, or use --install skip");
      return 1;
    }
    if (o.data() && !Files.isDirectory(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      System.err.println("apksim beam to-device: the session has no data on this Mac (" + slot.getFileName() + ")");
      return 1;
    }

    // 1. The device's build against the session's.
    Result pm = sh(serial, "pm path " + pkg);
    String devicePath = pm.code() == 0 && pm.out().startsWith("package:") ? pm.out().lines().findFirst().get().substring(8).strip() : null;
    boolean install = false;
    String buildNote;
    if (devicePath == null) {
      if (!o.replace()) {
        System.err.println("apksim beam to-device: " + pkg + " is not installed on the device; --install replace installs the session's build");
        return 1;
      }
      buildNote = pkg + " is not installed on the device";
      install = true;
    } else {
      Path pulled = Files.createTempFile("apksim-device-", ".apk");
      try {
        if (!devicePath.matches("/[A-Za-z0-9_./=\\-]{1,300}") || adb(serial, List.of("pull", devicePath, pulled.toString()), null).code() != 0) {
          System.err.println("apksim beam to-device: could not read the device's build of " + pkg + " (" + devicePath + ")");
          return 1;
        }
        if (sha256(pulled).equals(sha)) {
          buildNote = "the device has the session's build";
        } else {
          List<String> want = rec.get("signers") instanceof List<?> l ? l.stream().map(String::valueOf).sorted().toList() : null;
          List<String> have = null;
          try {
            have = ApkSigners.of(pulled).certs();
          } catch (IOException e) {
            // unsigned or unreadable: stops below
          }
          if (want == null && catalog.hasBuild(sha)) {
            try {
              want = ApkSigners.of(catalog.buildFile(sha)).certs();
            } catch (IOException e) {
              // stops below
            }
          }
          if (want == null || have == null) {
            System.err.println("apksim beam to-device: cannot tell who signed the session's build or the device's: stopping"
                + " (a reinstall with another key would erase the device's data). Nothing changed.");
            return 1;
          }
          if (!want.equals(have)) {
            System.err.println("apksim beam to-device: the device's build of " + pkg + " is signed by another key than the session's;"
                + " replacing it would erase the device's data. Stopping; nothing changed.");
            return 1;
          }
          buildNote = "the device's build differs from the session's (same signer)" + (o.replace() ? "" : "; kept as it is (--install replace swaps it)");
          install = o.replace();
        }
      } finally {
        Files.deleteIfExists(pulled);
      }
    }

    // 2.-3. What is to be overwritten.
    List<String> notes = new ArrayList<>();
    Map<String, String> chosen = chooseSettings(sessionSettings(slot, launch), o.settings(), notes);
    String backupName = serial.replaceAll("[^A-Za-z0-9._\\-]", "_") + "-" + LocalDateTime.now().format(STAMP);
    Path backups = store.stateDir().resolve("beam/device-backup");
    System.out.println("to " + serial + ": " + pkg + " (" + rec.getOrDefault("app", "?") + ", session " + o.id() + ")");
    System.out.println("  build:    " + buildNote + (install ? " -> installing the session's build" : ""));
    System.out.println("  data:     " + (o.data() ? "replace the device's app data with the session's (the device's backed up to "
        + backups.resolve(backupName + ".tar") + ")" : "left as it is on the device (--data replaces it)"));
    System.out.println("  settings: " + (chosen.isEmpty() ? "none" : chosen.size() + " value(s): " + String.join(", ", chosen.keySet())));
    for (String n : notes) System.out.println("            " + n);
    System.out.println("  start:    " + (activity != null ? pkg + "/" + activity : pkg + "'s launcher activity"));
    if (o.dry()) {
      System.out.println("(dry run: nothing was changed)");
      return 0;
    }
    if ((o.data() || install || !chosen.isEmpty()) && !o.yes()) {
      if (System.console() == null || !System.console().isTerminal()) {
        System.err.println("apksim beam to-device: this overwrites the device's state; run it in a terminal to be asked, or pass --yes");
        return 1;
      }
      System.out.print("Overwrite that on " + serial + "? [y/N] ");
      String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
      if (line == null || !line.strip().toLowerCase().startsWith("y")) {
        System.out.println("stopped; nothing changed");
        return 1;
      }
    }

    try (BeamStore.SavedHold held = store.hold(o.id(), false)) {
      if (held == null) {
        System.err.println("apksim beam to-device: " + o.id() + " is busy (being forgotten, or put back into): try again");
        return 1;
      }
      if (install) {
        Result r = adb(serial, List.of("install", "-r", "-d", catalog.buildFile(sha).toString()), null);
        if (r.code() != 0 || !r.out().contains("Success")) {
          System.err.println("apksim beam to-device: install failed: " + r.out().strip());
          return 1;
        }
        System.out.println("installed the session's build");
      }
      if (o.data()) {
        int code = putData(serial, pkg, slot, backups, backupName);
        if (code != 0) return code;
      }
      if (!chosen.isEmpty()) {
        int code = putSettings(serial, chosen, backups, backupName);
        if (code != 0) return code;
      }
      List<String> missed = new ArrayList<>();
      options(serial, pkg, rec, missed);
      sh(serial, "am force-stop " + pkg);
      Result start = activity != null ? sh(serial, "am start -n " + q(pkg + "/" + activity))
          : sh(serial, "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
      if (start.code() != 0 || start.out().contains("Error")) {
        System.err.println("apksim beam to-device: could not start the app: " + start.out().strip());
        return 1;
      }
      System.out.println("started " + pkg);
      for (String m : missed) System.out.println("  not carried over: " + m);
      System.out.println("  not carried over: in-memory state, the back stack, network state"
          + (o.data() ? ", the session's /sdcard files" : "; app data (without --data)"));
      store.used(o.id());
    }
    return 0;
  }

  /** Backs the device's app data up, then replaces it with the slot's. */
  private static int putData(String serial, String pkg, Path slot, Path backups, String name)
      throws IOException, InterruptedException {
    if (sh(serial, "run-as " + pkg + " true").code() != 0) {
      System.err.println("apksim beam to-device: run-as " + pkg + " is refused (the build is not debuggable); app data can only be"
          + " written to a debuggable build here (`adb root` on a userdebug device is not supported yet). Nothing changed.");
      return 1;
    }
    Files.createDirectories(backups);
    Path tar = backups.resolve(name + ".tar");
    Process p = new ProcessBuilder("adb", "-s", serial, "exec-out", "run-as", pkg, "tar", "-cf", "-", ".")
        .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(tar.toFile()).start();
    if (p.waitFor() != 0 || Files.size(tar) < 512) {
      Files.deleteIfExists(tar);
      System.err.println("apksim beam to-device: could not back up the device's app data; nothing changed");
      return 1;
    }
    System.out.println("backed up the device's app data to " + tar
        + " (put it back: adb -s " + serial + " shell run-as " + pkg + " tar -xf - -C . < " + tar + ")");
    sh(serial, "am force-stop " + pkg);
    // Everything in the data folder goes but the system's own `lib` link; then the slot's tree (its .lock aside).
    Result clear = sh(serial, "run-as " + pkg + " sh -c " + q("find . -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} +"));
    if (clear.code() != 0) {
      System.err.println("apksim beam to-device: could not clear the device's app data: " + clear.out().strip()
          + " (the backup is at " + tar + ")");
      return 1;
    }
    ProcessBuilder tb = new ProcessBuilder("/usr/bin/tar", "-cf", "-", "--exclude", "./.lock", "-C", slot.toString(), ".");
    tb.environment().put("COPYFILE_DISABLE", "1");  // no AppleDouble ._ files
    Process t = tb.redirectError(ProcessBuilder.Redirect.INHERIT).start();
    byte[] packed;
    try (InputStream in = t.getInputStream()) {
      packed = in.readAllBytes();
    }
    if (t.waitFor() != 0) {
      System.err.println("apksim beam to-device: could not pack the session's data (the device's own is in " + tar + ")");
      return 1;
    }
    Result put = adb(serial, List.of("shell", "run-as " + pkg + " tar -xf - -C ."), packed);
    if (put.code() != 0) {
      System.err.println("apksim beam to-device: writing the data failed: " + put.out().strip() + " (the device's own is in " + tar + ")");
      return 1;
    }
    // Files made through run-as belong to the app's uid and take the folder's SELinux label; restorecon where allowed.
    sh(serial, "run-as " + pkg + " restorecon -R . 2>/dev/null; true");
    System.out.println("wrote the session's app data (" + packed.length / 1024 + " KB as tar)");
    return 0;
  }

  /** Puts the chosen settings, after saving what they were (and the commands that restore them). */
  private static int putSettings(String serial, Map<String, String> chosen, Path backups, String name)
      throws IOException, InterruptedException {
    StringBuilder undo = new StringBuilder("#!/bin/sh\n# puts back the Settings values beam to-device changed on " + serial + "\n");
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, String> e : chosen.entrySet()) {
      String ns = e.getKey().substring(0, e.getKey().indexOf('/')), key = e.getKey().substring(e.getKey().indexOf('/') + 1);
      String was = sh(serial, "settings get " + ns + " " + key).out().strip();
      boolean unset = was.isEmpty() || was.equals("null");
      lines.add(e.getKey() + ": " + (unset ? "(unset)" : was) + " -> " + (e.getValue() == null ? "(deleted)" : e.getValue()));
      undo.append("adb -s ").append(serial).append(" shell ")
          .append(q(unset ? "settings delete " + ns + " " + key : "settings put " + ns + " " + key + " " + q(was))).append("\n");
      Result r = sh(serial, e.getValue() == null ? "settings delete " + ns + " " + key
          : "settings put " + ns + " " + key + " " + q(e.getValue()));
      if (r.code() != 0 || r.out().contains("Exception")) {
        System.err.println("apksim beam to-device: could not set " + e.getKey() + ": " + r.out().strip());
        System.err.println("  earlier ones can be put back with: sh " + saveUndo(backups, name, undo));
        return 1;
      }
    }
    Path f = saveUndo(backups, name, undo);
    System.out.println("settings changed (old -> new):");
    for (String l : lines) System.out.println("  " + l);
    System.out.println("  put them back with: sh " + f);
    return 0;
  }

  private static Path saveUndo(Path backups, String name, StringBuilder undo) throws IOException {
    Files.createDirectories(backups);
    Path f = backups.resolve(name + "-settings.sh");
    Files.writeString(f, undo.toString());
    return f;
  }

  /** Appearance, font scale and locale; what has no device form is added to `missed`. */
  private static void options(String serial, String pkg, Map<String, Object> rec, List<String> missed)
      throws IOException, InterruptedException {
    if (rec.get("appearance") instanceof String a && (a.equals("dark") || a.equals("light"))) {
      if (sh(serial, "cmd uimode night " + (a.equals("dark") ? "yes" : "no")).code() != 0) missed.add("appearance " + a);
    }
    if (rec.get("fontscale") instanceof String f && f.matches("[0-2](\\.\\d{1,2})?")) {
      if (sh(serial, "settings put system font_scale " + f).code() != 0) missed.add("font scale " + f);
    }
    if (rec.get("locale") instanceof String l && l.matches("[a-z]{2,3}(-[A-Z]{2})?")) {
      Result r = sh(serial, "cmd locale set-app-locales " + pkg + " --locales " + l);
      if (r.code() != 0 || r.out().contains("Exception") || r.out().contains("Unknown")) missed.add("locale " + l + " (needs Android 13)");
    }
    if (rec.get("device") instanceof String d) missed.add("device size " + d + " (the device keeps its own)");
  }

  private static String sha256(Path f) throws IOException {
    try (InputStream in = Files.newInputStream(f)) {
      java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
      return java.util.HexFormat.of().formatHex(d.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }
}
