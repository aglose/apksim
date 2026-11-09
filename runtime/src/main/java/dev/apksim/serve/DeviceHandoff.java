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

  static final String SERIAL = "[A-Za-z0-9._:\\-]{1,100}";

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

  /** Runs `adb` (with `-s serial`); stdout and stderr together. Stdin is `stdin` or the file `stdinFile`. */
  static Result adb(String serial, List<String> args, byte[] stdin) throws IOException, InterruptedException {
    return adb(serial, args, stdin, null);
  }

  static Result adb(String serial, List<String> args, byte[] stdin, Path stdinFile) throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(List.of("adb"));
    if (serial != null) cmd.addAll(List.of("-s", serial));
    cmd.addAll(args);
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    Thread feed = Thread.ofVirtual().start(() -> {
      try (OutputStream o = p.getOutputStream()) {
        if (stdin != null) o.write(stdin);
        else if (stdinFile != null) Files.copy(stdinFile, o);
      } catch (IOException e) {
        // the process ended first: its exit code says so
      }
    });
    // Drained on a thread of its own, so the deadline holds when adb hangs without closing its output.
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    Thread drain = Thread.ofVirtual().start(() -> {
      try (InputStream in = p.getInputStream()) {
        in.transferTo(out);
      } catch (IOException e) {
        // closed on a timeout
      }
    });
    if (!p.waitFor(10, TimeUnit.MINUTES)) {
      p.destroyForcibly();
      p.getInputStream().close();
      p.getOutputStream().close();
      drain.join(5_000);
      feed.join(5_000);
      throw new IOException("adb " + String.join(" ", args) + " did not finish");
    }
    drain.join();  // the process has ended: its output ends and the stdin writer returns
    feed.join();
    synchronized (out) {
      return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8));
    }
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
    if (id == null || serial != null && !serial.matches(SERIAL)) return null;
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
      if (f.length == 2 && f[1].equals("device") && f[0].matches(SERIAL)) ready.add(f[0]);  // a serial adb reports is data too
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
    Set<String> names = null;
    if (!want.equals("all")) {
      names = new java.util.LinkedHashSet<>();
      for (String k : want.split(",")) {
        if (!k.isBlank()) names.add(k.strip());
      }
      if (names.isEmpty()) notes.add("--settings was given but names no keys (use --settings all or --settings ns/key,ns/key)");
    }
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

  /**
   * The component to `am start -n` for a saved `--activity` (`PKG/.Act`, `PKG/full.Name`, `.Act`, `full.Name`), or null
   * for none or a simple name (which only the manifest resolves: the launcher activity is started then).
   */
  static String component(String pkg, String a) {
    if (a == null) return null;
    String cls = a;
    int slash = a.indexOf('/');
    if (slash >= 0) {
      if (!a.substring(0, slash).equals(pkg)) return null;
      cls = a.substring(slash + 1);
    }
    if (!cls.matches("\\.?[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)*") || !cls.startsWith(".") && !cls.contains(".")) return null;
    return pkg + "/" + cls;
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
    String activity = component(pkg, launchValue(launch, "--activity"));
    if (activity == null && launchValue(launch, "--activity") != null) {
      System.out.println("note: the session's activity " + launchValue(launch, "--activity")
          + " is a simple name; the device's launcher activity is started instead");
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
        if (!devicePath.matches("/[A-Za-z0-9_./=~+\\-]{1,300}") || adb(serial, List.of("pull", devicePath, pulled.toString()), null).code() != 0) {
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
    byte[] rnd = new byte[3];
    new java.security.SecureRandom().nextBytes(rnd);
    // Unique per handoff (a second in the same second must not overwrite the first's recovery files).
    String backupName = serial.replaceAll("[^A-Za-z0-9._\\-]", "_") + "-" + LocalDateTime.now().format(STAMP) + "-"
        + java.util.HexFormat.of().formatHex(rnd);
    Path backups = store.stateDir().resolve("beam/device-backup");
    List<String> launchChanges = new ArrayList<>();
    if (rec.get("appearance") instanceof String a && (a.equals("dark") || a.equals("light"))) launchChanges.add("appearance " + a);
    if (rec.get("fontscale") instanceof String f && f.matches("[0-2](\\.\\d{1,2})?")) launchChanges.add("font scale " + f);
    if (rec.get("locale") instanceof String l && l.matches("[a-z]{2,3}(-[A-Z]{2})?")) launchChanges.add("app locale " + l);
    System.out.println("to " + serial + ": " + pkg + " (" + rec.getOrDefault("app", "?") + ", session " + o.id() + ")");
    System.out.println("  build:    " + buildNote + (install ? " -> installing the session's build" : ""));
    System.out.println("  data:     " + (o.data() ? "replace the device's app data with the session's (the device's backed up to "
        + backups.resolve(backupName + ".tar") + ")" : "left as it is on the device (--data replaces it)"));
    System.out.println("  settings: " + (chosen.isEmpty() ? "none" : chosen.size() + " value(s): " + String.join(", ", chosen.keySet())));
    for (String n : notes) System.out.println("            " + n);
    System.out.println("  options:  " + (launchChanges.isEmpty() ? "none" : String.join(", ", launchChanges) + " (set on the device, old ones saved)"));
    System.out.println("  start:    " + (activity != null ? activity : pkg + "'s launcher activity"));
    if (o.dry()) {
      System.out.println("(dry run: nothing was changed)");
      return 0;
    }
    if ((o.data() || install || !chosen.isEmpty() || !launchChanges.isEmpty()) && !o.yes()) {
      if (System.console() == null || !System.console().isTerminal()) {
        System.err.println("apksim beam to-device: this changes the device's state; run it in a terminal to be asked, or pass --yes");
        return 1;
      }
      System.out.print("Change that on " + serial + "? [y/N] ");
      String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
      if (line == null || !line.strip().toLowerCase().startsWith("y")) {
        System.out.println("stopped; nothing changed");
        return 1;
      }
    }

    Path tarOut = null;
    java.nio.channels.FileChannel deviceLock = null;
    java.nio.channels.FileLock deviceFileLock = null;
    try (BeamStore.SavedHold held = store.hold(o.id(), false)) {
      if (held == null) {
        System.err.println("apksim beam to-device: " + o.id() + " is busy (being forgotten, or put back into): try again");
        return 1;
      }
      // Checked again now that it is held: `forget` may have erased it since it was read.
      if (store.saved(o.id()) == null || !Files.isDirectory(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS) && (o.data() || o.settings() != null)) {
        System.err.println("apksim beam to-device: the session is gone; nothing changed");
        return 1;
      }
      java.nio.channels.FileLock own = null;
      java.nio.channels.FileChannel lockCh = null;
      try {
        if (Files.isDirectory(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          // The slot's own lock, as a tab's copy takes it: no `beam run` writes the data while it is read.
          lockCh = java.nio.channels.FileChannel.open(slot.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.WRITE);
          try {
            own = lockCh.tryLock();
          } catch (java.nio.channels.OverlappingFileLockException e) {
            own = null;
          }
          if (own == null) {
            System.err.println("apksim beam to-device: the session is open on this Mac (apksim beam run): quit it first; nothing changed");
            return 1;
          }
        }
        // Everything read from the session is read now, under its lock and before the device is touched.
        chosen = chooseSettings(sessionSettings(slot, launch), o.settings(), new ArrayList<>());
        if (o.data()) {
          tarOut = Files.createTempFile("apksim-slot-", ".tar");
          ProcessBuilder tb = new ProcessBuilder("/usr/bin/tar", "-cf", tarOut.toString(), "--exclude", "./.lock", "-C", slot.toString(), ".");
          tb.environment().put("COPYFILE_DISABLE", "1");  // no AppleDouble ._ files
          if (tb.redirectErrorStream(true).start().waitFor() != 0) {
            System.err.println("apksim beam to-device: could not pack the session's data; nothing changed");
            return 1;
          }
        }
      } finally {
        if (own != null) own.release();
        if (lockCh != null) lockCh.close();
      }
      // What the device has now, before anything is written, kept in a script that puts it back.
      try {
        Files.createDirectories(backups, PRIVATE_DIR);
      } catch (UnsupportedOperationException e) {
        Files.createDirectories(backups);
      }
      restrict(backups, "rwx------");  // also if it existed
      // One handoff at a time per device (any session, any process): two would mix their data, and the second's "old"
      // values and backup would be the first's half-written state.
      Path lockFile = backups.resolve(serial.replace(':', '_') + ".lock");
      if (!Files.exists(lockFile)) {
        try {
          Files.createFile(lockFile, PRIVATE_FILE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
          // made meanwhile
        }
      }
      restrict(lockFile, "rw-------");
      deviceLock = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE);
      java.nio.channels.FileLock dl;
      try {
        dl = deviceLock.tryLock();
        deviceFileLock = dl;
      } catch (java.nio.channels.OverlappingFileLockException e) {
        dl = null;
      }
      if (dl == null) {
        System.err.println("apksim beam to-device: another handoff to " + serial + " is running; nothing changed");
        return 1;
      }
      Path undo = backups.resolve(backupName + "-undo.sh");
      StringBuilder script = new StringBuilder("#!/bin/sh\n# puts back what beam to-device changed on " + serial + " (an installed build is not undone)\nset -e\n");
      if (o.data()) {
        // Nothing is cleared unless the archive is there and reads.
        String t = q(backups.resolve(backupName + ".tar").toString());
        script.append("test -s ").append(t).append(" && tar -tf ").append(t).append(" >/dev/null || { echo 'the backup archive is missing or unreadable' >&2; exit 1; }\n");
        script.append("adb -s ").append(q(serial)).append(" shell ").append(q("am force-stop " + pkg)).append("\n");
        script.append("adb -s ").append(q(serial)).append(" shell ").append(q("run-as " + pkg + " sh -c " + q(CLEAR))).append("\n");
        script.append("adb -s ").append(q(serial)).append(" shell ").append(q("run-as " + pkg + " tar -xf - -C .")).append(" < ")
            .append(q(backups.resolve(backupName + ".tar").toString())).append("\n");
      }
      List<String> lines = new ArrayList<>();
      for (Map.Entry<String, String> e : chosen.entrySet()) {
        String ns = e.getKey().substring(0, e.getKey().indexOf('/')), key = e.getKey().substring(e.getKey().indexOf('/') + 1);
        String was = settingsGet(serial, ns, key);
        if (was == null) {
          script.append("adb -s ").append(q(serial)).append(" shell ").append(q("settings delete " + ns + " " + key)).append("\n");
        } else {
          script.append("adb -s ").append(q(serial)).append(" shell ").append(q("settings put " + ns + " " + key + " " + q(was))).append("\n");
        }
        lines.add(e.getKey() + ": " + (was == null ? "(unset)" : was) + " -> " + (e.getValue() == null ? "(deleted)" : e.getValue()));
      }
      optionsUndo(serial, pkg, rec, script);
      Files.createFile(undo, PRIVATE_FILE);
      Files.writeString(undo, script.toString(), StandardCharsets.UTF_8);
      System.out.println("what the device has now is saved in " + undo + " (sh it to put it back)");

      if (install) {
        Result r = adb(serial, List.of("install", "-r", "-d", catalog.buildFile(sha).toString()), null);
        if (r.code() != 0 || !r.out().contains("Success")) {
          System.err.println("apksim beam to-device: install failed: " + r.out().strip());
          return 1;
        }
        System.out.println("installed the session's build");
      }
      if (o.data()) {
        int code = putData(serial, pkg, tarOut, backups.resolve(backupName + ".tar"));
        if (code != 0) return code;
      }
      for (Map.Entry<String, String> e : chosen.entrySet()) {
        String ns = e.getKey().substring(0, e.getKey().indexOf('/')), key = e.getKey().substring(e.getKey().indexOf('/') + 1);
        Result r = sh(serial, e.getValue() == null ? "settings delete " + ns + " " + key
            : "settings put " + ns + " " + key + " " + q(e.getValue()));
        if (r.code() != 0 || r.out().contains("Exception")) {
          System.err.println("apksim beam to-device: could not set " + e.getKey() + ": " + r.out().strip() + " (sh " + undo + " puts back)");
          return 1;
        }
      }
      if (!lines.isEmpty()) {
        System.out.println("settings changed (old -> new):");
        for (String l : lines) System.out.println("  " + l);
      }
      List<String> missed = new ArrayList<>();
      options(serial, pkg, rec, missed);
      sh(serial, "am force-stop " + pkg);
      Result start = activity != null ? sh(serial, "am start -n " + q(activity))
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
    } finally {
      if (tarOut != null) Files.deleteIfExists(tarOut);
      if (deviceFileLock != null && deviceFileLock.isValid()) deviceFileLock.release();
      if (deviceLock != null) deviceLock.close();  // backstop
    }
    return 0;
  }

  /** The recovery files hold the device's private data: this user only (the folder's default mode may be wider). */
  private static final java.nio.file.attribute.FileAttribute<Set<java.nio.file.attribute.PosixFilePermission>> PRIVATE_DIR =
      java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  private static final java.nio.file.attribute.FileAttribute<Set<java.nio.file.attribute.PosixFilePermission>> PRIVATE_FILE =
      java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));

  /** Sets a private mode on a path that may already exist; a file system without POSIX modes only gets a warning. */
  private static void restrict(Path f, String mode) throws IOException {
    try {
      Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString(mode));
    } catch (UnsupportedOperationException e) {
      System.err.println("apksim beam to-device: warning: " + f + " cannot be made private on this file system");
    }
  }

  /** Empties the app's data folder but for the system's own `lib` link. */
  static final String CLEAR = "find . -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} +";

  /** A setting's value as the device has it (one trailing newline dropped, nothing else), or null when unset. */
  private static String settingsGet(String serial, String ns, String key) throws IOException, InterruptedException {
    String v = sh(serial, "settings get " + ns + " " + key).out();
    if (v.endsWith("\n")) v = v.substring(0, v.length() - 1);
    if (v.endsWith("\r")) v = v.substring(0, v.length() - 1);
    return v.equals("null") ? null : v;
  }

  /** The inverse of {@link #options}, appended to the restore script. */
  private static void optionsUndo(String serial, String pkg, Map<String, Object> rec, StringBuilder script)
      throws IOException, InterruptedException {
    String pre = "adb -s " + q(serial) + " shell ";
    if (rec.get("appearance") instanceof String a && (a.equals("dark") || a.equals("light"))) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("(yes|no|auto)").matcher(sh(serial, "cmd uimode night").out());
      if (m.find()) script.append(pre).append(q("cmd uimode night " + m.group(1))).append("\n");
    }
    if (rec.get("fontscale") instanceof String f && f.matches("[0-2](\\.\\d{1,2})?")) {
      String was = settingsGet(serial, "system", "font_scale");
      script.append(pre).append(q(was == null ? "settings delete system font_scale" : "settings put system font_scale " + q(was))).append("\n");
    }
    if (rec.get("locale") instanceof String l && l.matches("[a-z]{2,3}(-[A-Z]{2})?")) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([A-Za-z0-9,_\\-]*)\\]").matcher(sh(serial, "cmd locale get-app-locales " + pkg).out());
      if (m.find()) script.append(pre).append(q("cmd locale set-app-locales " + pkg + " --locales " + q(m.group(1)))).append("\n");
    }
  }

  /** Backs the device's app data up (the app stopped first), then replaces it with the packed slot. */
  private static int putData(String serial, String pkg, Path packed, Path tar) throws IOException, InterruptedException {
    if (sh(serial, "run-as " + pkg + " true").code() != 0) {
      System.err.println("apksim beam to-device: run-as " + pkg + " is refused (the build is not debuggable); app data can only be"
          + " written to a debuggable build here (`adb root` on a userdebug device is not supported yet). Nothing changed.");
      return 1;
    }
    // Stopped before it is read: a running app could checkpoint a database halfway through the copy.
    Result stop = sh(serial, "am force-stop " + pkg);
    if (stop.code() != 0) {
      System.err.println("apksim beam to-device: could not stop " + pkg + " on the device: " + stop.out().strip());
      return 1;
    }
    // `shell` (shell protocol v2) is binary-safe and returns the remote tar's exit status, which `exec-out` does not.
    Files.createFile(tar, PRIVATE_FILE);
    Process p = new ProcessBuilder("adb", "-s", serial, "shell", "run-as " + pkg + " tar -cf - .")
        .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(tar.toFile()).start();
    boolean good = p.waitFor(10, TimeUnit.MINUTES) && p.exitValue() == 0 && Files.size(tar) >= 512;
    // And read back whole here: a half-written or damaged archive is never taken for a backup.
    if (good) {
      Process t = new ProcessBuilder("/usr/bin/tar", "-tf", tar.toString()).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      good = t.waitFor() == 0;
    }
    if (!good) {
      p.destroyForcibly();
      Files.deleteIfExists(tar);
      System.err.println("apksim beam to-device: could not back up the device's app data; nothing changed");
      return 1;
    }
    System.out.println("backed up the device's app data to " + tar);
    Result clear = sh(serial, "run-as " + pkg + " sh -c " + q(CLEAR));
    if (clear.code() != 0) {
      System.err.println("apksim beam to-device: could not clear the device's app data: " + clear.out().strip());
      return 1;
    }
    // Streamed from the file made under the slot's lock, not held in memory.
    Result put = adb(serial, List.of("shell", "run-as " + pkg + " tar -xf - -C ."), null, packed);
    if (put.code() != 0) {
      System.err.println("apksim beam to-device: writing the data failed: " + put.out().strip());
      return 1;
    }
    // Files made through run-as belong to the app's uid and take the folder's SELinux label; restorecon where allowed.
    sh(serial, "run-as " + pkg + " restorecon -R . 2>/dev/null; true");
    System.out.println("wrote the session's app data (" + Files.size(packed) / 1024 + " KB as tar)");
    return 0;
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
