package dev.apksim.serve;

import dev.apksim.ApkCache;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * What `apksim beam` keeps on this Mac, and for how long (docs/beam.md, "What the Mac keeps"). Builds
 * (`STATE/beam/uploads/<sha256>.apk`) are held while a session, a grant or a saved session names them, then
 * `keep-days` after their last session ended, and deleted with their apksim cache entry. A session's data slot is
 * erased when it ends unless it is saved ("Keep on this Mac", or `save-sessions`): then the slot is renamed to
 * `k<id>-0` (a name no beam session takes) and described in `STATE/beam/saved/<id>.json`, and `apksim beam run ID`
 * opens it later as an ordinary local launch.
 *
 * Settings are in `STATE/beam/config.json` (`apksim beam config`), read at the agent's start; `apksim beam`'s
 * `--keep-days`, `--save-sessions` and `--keep-saved-days` override them for one run.
 */
final class BeamStore {
  static final long DAY_MS = 24 * 3_600_000L;
  /** A build last started or stored this recently is kept whatever `keep-days` says: its grant may still be used. */
  static final long GRACE_MS = 3 * 60_000L;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** `keep-days`, `save-sessions`, `keep-saved-days` (negative: never). */
  record Config(double keepDays, boolean saveSessions, double keepSavedDays) {
    static final Config DEFAULT = new Config(2, false, -1);

    String describe() {
      return "keep-days " + num(keepDays) + ", save-sessions " + (saveSessions ? "on" : "off") + ", keep-saved-days "
          + (keepSavedDays < 0 ? "never" : num(keepSavedDays));
    }
  }

  private final Path state;
  private final Path beamDir;
  private final Path savedDir;
  private final Path configFile;
  private final Path cacheRoot;
  private final Path dataRoot;
  volatile Config config;

  BeamStore(Path state) {
    this.state = state;
    this.beamDir = state.resolve("beam");
    this.savedDir = beamDir.resolve("saved");
    this.configFile = beamDir.resolve("config.json");
    this.cacheRoot = state.resolve("cache");
    this.dataRoot = state.resolve("cache/data");
    this.config = readConfig();
  }

  /** A JSON number as a whole number (the parser reads every number as a double). */
  static long whole(Object o) {
    return o instanceof Number n ? n.longValue() : 0;
  }

  static String num(double d) {
    return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
  }

  // ---------------------------------------------------------------- config

  Config readConfig() {
    Config c = Config.DEFAULT;
    try {
      if (Files.isRegularFile(configFile) && Json.parse(Files.readString(configFile)) instanceof Map<?, ?> m) {
        double keep = m.get("keep-days") instanceof Number n && n.doubleValue() >= 0 ? n.doubleValue() : c.keepDays();
        boolean save = m.get("save-sessions") instanceof Boolean b ? b : c.saveSessions();
        double saved = m.get("keep-saved-days") instanceof Number n && n.doubleValue() >= 0 ? n.doubleValue() : -1;
        c = new Config(keep, save, saved);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[beam] cannot read " + configFile + " (using the defaults): " + e.getMessage());
    }
    return c;
  }

  void writeConfig(Config c) throws IOException {
    String json = "{\n  \"keep-days\": " + num(c.keepDays()) + ",\n  \"save-sessions\": " + c.saveSessions()
        + ",\n  \"keep-saved-days\": " + (c.keepSavedDays() < 0 ? "\"never\"" : num(c.keepSavedDays())) + "\n}\n";
    writeAtomically(configFile, json);
    config = c;
  }

  /** A `keep-days` or `keep-saved-days` value (`never` only where allowed: -1), or null. */
  static Double days(String v, boolean neverAllowed) {
    if (v == null) return null;
    if (neverAllowed && v.equals("never")) return -1.0;
    try {
      double d = Double.parseDouble(v);
      return d >= 0 && d <= 36500 && !Double.isNaN(d) ? d : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** `on|off` (true/false accepted), or null. */
  static Boolean onOff(String v) {
    if (v == null) return null;
    return switch (v) {
      case "on", "true" -> Boolean.TRUE;
      case "off", "false" -> Boolean.FALSE;
      default -> null;
    };
  }

  private static void writeAtomically(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
    Files.writeString(tmp, text, StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  // ---------------------------------------------------------------- the agent's lock

  private FileChannel agentChannel;

  /**
   * Taken by the agent for as long as it runs (`STATE/beam/agent.lock`, with its port in it), so two agents never
   * sweep each other's builds and `apksim beam sweep` leaves a running agent's alone. False when another holds it.
   */
  boolean lockAgent(int port) throws IOException {
    Files.createDirectories(beamDir);
    FileChannel ch = FileChannel.open(beamDir.resolve("agent.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE);
    FileLock l;
    try {
      l = ch.tryLock();
    } catch (OverlappingFileLockException e) {
      l = null;
    }
    if (l == null) {
      ch.close();
      return false;
    }
    ch.truncate(0);
    ch.write(java.nio.ByteBuffer.wrap((port + "\n").getBytes(StandardCharsets.UTF_8)), 0);
    agentChannel = ch;  // held until the process ends
    return true;
  }

  /**
   * What the lock file says while the agent holds it: its port, or "exiting" once a launchd agent has gone idle and is
   * about to end ({@link #agentPort} then answers -2, so `apksim beam status` does not connect: a connection queued as
   * it ends makes launchd start it again at once).
   */
  void agentState(String s) {
    FileChannel ch = agentChannel;
    if (ch == null) return;
    try {
      ch.truncate(0);
      ch.write(java.nio.ByteBuffer.wrap((s + "\n").getBytes(StandardCharsets.UTF_8)), 0);
      ch.force(false);
    } catch (IOException e) {
      // status may then connect once more: harmless
    }
  }

  /** The running agent's port, -2 while it is exiting (see {@link #agentState}), or -1 when none runs. */
  int agentPort() {
    Path f = beamDir.resolve("agent.lock");
    if (!Files.exists(f)) return -1;
    try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock l = ch.tryLock();
      if (l != null) {
        l.release();
        return -1;
      }
      String s = Files.readString(f).strip();
      return s.equals("exiting") ? -2 : s.matches("\\d{1,5}") ? Integer.parseInt(s) : 0;
    } catch (OverlappingFileLockException e) {
      return 0;
    } catch (IOException e) {
      return -1;
    }
  }

  // ---------------------------------------------------------------- saved sessions

  /** Every saved session's record (sorted by when it was saved), each with its "id". */
  List<Map<String, Object>> saved() {
    List<Map<String, Object>> out = new ArrayList<>();
    if (!Files.isDirectory(savedDir)) return out;
    try (DirectoryStream<Path> d = Files.newDirectoryStream(savedDir, "*.json")) {
      for (Path p : d) {
        try {
          if (Json.parse(Files.readString(p)) instanceof Map<?, ?> m && m.get("id") instanceof String id
              && id.matches("[0-9a-f]{12}") && p.getFileName().toString().equals(id + ".json")) {
            Map<String, Object> rec = new LinkedHashMap<>();
            m.forEach((k, v) -> rec.put(String.valueOf(k), v));
            out.add(rec);
          }
        } catch (IOException | RuntimeException e) {
          System.err.println("[beam] cannot read " + p + ": " + e.getMessage());
        }
      }
    } catch (IOException e) {
      System.err.println("[beam] cannot list " + savedDir + ": " + e.getMessage());
    }
    out.sort(Comparator.comparingLong(r -> r.get("saved") instanceof Number n ? n.longValue() : 0));
    return out;
  }

  Map<String, Object> saved(String id) {
    if (id == null || !id.matches("[0-9a-f]{12}")) return null;
    for (Map<String, Object> r : saved()) if (r.get("id").equals(id)) return r;
    return null;
  }

  /** The SHA-256 of every build a saved session names: the sweep keeps them. */
  Set<String> savedBuilds() {
    Set<String> s = new java.util.HashSet<>();
    for (Map<String, Object> r : saved()) if (r.get("sha256") instanceof String h) s.add(h);
    return s;
  }

  /** What a session leaves for its saved record: the slot, what it ran, and where it came from. */
  record Leaving(Path slot, String app, String sha256, List<String> launch, String device, String appearance,
      String locale, String fontScale, String permissions, String site) {}

  /**
   * Keeps a session's data slot (its instance has exited): renamed to `k<id>-0`, its `.device` beside it too, and
   * described in `saved/<id>.json`. The new id, or null when the slot cannot be kept (then the caller erases it, and
   * nothing of it is left under another name). Synchronized with {@link #sweep}: a sweep sees either the session
   * running or its record, never neither, so it cannot delete the build a session is being saved with.
   */
  synchronized String save(Leaving s) {
    Path dir = s.slot().toAbsolutePath().normalize();
    String slotName = dir.getFileName().toString();
    if (!dir.startsWith(dataRoot.toAbsolutePath().normalize()) || !(slotName.startsWith("beam-") || slotName.matches("bm[0-9a-f]{12}-[0-9]+"))) return null;
    byte[] b = new byte[6];
    RANDOM.nextBytes(b);
    String id = HexFormat.of().formatHex(b);
    Path target = dir.resolveSibling("k" + id + "-0");
    Path device = dir.resolveSibling(dir.getFileName() + ".device");
    Path deviceTarget = target.resolveSibling(target.getFileName() + ".device");
    try (FileChannel ch = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileLock l;
      try {
        l = ch.tryLock();
      } catch (OverlappingFileLockException e) {
        l = null;
      }
      if (l == null) return null;  // another instance holds it (it took the slot with --fresh): nothing of ours is left
      boolean deviceMoved = false;
      boolean slotMoved = false;
      try {
        // The .device folder first, while the slot still has its name and its lock: once the slot is renamed, another
        // session may take beam-N and, with --fresh, empty beam-N.device.
        if (Files.isDirectory(device)) {
          Files.move(device, deviceTarget, StandardCopyOption.ATOMIC_MOVE);
          deviceMoved = true;
        }
        Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE);
        slotMoved = true;
        long now = System.currentTimeMillis();
        StringBuilder j = new StringBuilder("{\n");
        field(j, "id", id);
        field(j, "app", s.app());
        field(j, "sha256", s.sha256());
        field(j, "package", dir.getParent().getFileName().toString());
        field(j, "device", s.device());
        field(j, "appearance", s.appearance());
        field(j, "locale", s.locale());
        field(j, "fontscale", s.fontScale());
        field(j, "permissions", s.permissions());
        field(j, "site", s.site());
        j.append("  \"launch\": [");
        for (int i = 0; i < s.launch().size(); i++) j.append(i > 0 ? ", " : "").append(Http.q(s.launch().get(i)));
        j.append("],\n  \"saved\": ").append(now).append(",\n  \"lastUsed\": ").append(now).append("\n}\n");
        writeAtomically(savedDir.resolve(id + ".json"), j.toString());
        return id;
      } catch (IOException | RuntimeException e) {
        System.err.println("[beam] could not keep " + dir + ": " + e);
        // Back where they were, for the caller to erase; what cannot go back (its name taken meanwhile) goes now, so
        // no data is left under a name nothing lists.
        if (slotMoved) putBack(target, dir);
        if (deviceMoved) putBack(deviceTarget, device);
        return null;
      } finally {
        l.release();
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[beam] could not keep " + dir + ": " + e);
      return null;
    }
  }

  private static void putBack(Path moved, Path original) {
    try {
      Files.move(moved, original, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      deleteTree(moved);
    }
  }

  private static void field(StringBuilder j, String k, String v) {
    if (v != null) j.append("  ").append(Http.q(k)).append(": ").append(Http.q(v)).append(",\n");
  }

  /** Notes a saved session's use (`run`): `keep-saved-days` counts from it. */
  void used(String id) {
    Path f = savedDir.resolve(id + ".json");
    try {
      String text = Files.readString(f);
      writeAtomically(f, text.replaceFirst("\"lastUsed\": \\d+", "\"lastUsed\": " + System.currentTimeMillis()));
    } catch (IOException e) {
      System.err.println("[beam] cannot note the use of " + id + ": " + e.getMessage());
    }
  }

  /** The saved session's slots (`k<id>-N`, from `run`s side by side) and their `.device` folders. */
  private List<Path> slots(Map<String, Object> rec) throws IOException {
    List<Path> out = new ArrayList<>();
    if (!(rec.get("package") instanceof String pkg) || !pkg.matches("[A-Za-z0-9._]{1,255}")) return out;
    Path dir = dataRoot.resolve(pkg);
    if (!Files.isDirectory(dir)) return out;
    String prefix = "k" + rec.get("id") + "-";
    try (DirectoryStream<Path> d = Files.newDirectoryStream(dir)) {
      for (Path p : d) {
        String n = p.getFileName().toString();
        if (n.startsWith(prefix) && n.substring(prefix.length()).matches("\\d+(\\.device)?")) out.add(p);
      }
    }
    out.sort(null);
    return out;
  }

  /** Bytes the saved session's data takes. */
  long size(Map<String, Object> rec) {
    long total = 0;
    try {
      for (Path p : slots(rec)) {
        try (Stream<Path> all = Files.walk(p)) {
          total += all.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
      }
    } catch (IOException | java.io.UncheckedIOException e) {
      // as far as it got
    }
    return total;
  }

  /** `saved/<id>.lock`: shared by each `run` of the session while it runs, exclusive to `forget`. */
  FileChannel sessionLock(String id) throws IOException {
    Files.createDirectories(savedDir);
    return FileChannel.open(savedDir.resolve(id + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
  }

  /**
   * Erases a saved session: its slots (none may be open: `run` holds the session's lock and its slot's), then its
   * record. Null when it is gone, else why it was not.
   */
  String forget(Map<String, Object> rec) {
    String sid = String.valueOf(rec.get("id"));
    if (!sid.matches("[0-9a-f]{12}")) return "not a saved session";
    try (FileChannel lockCh = sessionLock(sid)) {
      FileLock own;
      try {
        own = lockCh.tryLock();
      } catch (OverlappingFileLockException e) {
        own = null;
      }
      if (own == null) return "it is open (apksim beam run): quit it first";
      String why = forgetLocked(rec);
      if (why == null) Files.deleteIfExists(savedDir.resolve(sid + ".lock"));
      return why;
    } catch (IOException e) {
      return e.toString();
    }
  }

  private String forgetLocked(Map<String, Object> rec) {
    try {
      List<Path> all = slots(rec);
      List<FileChannel> open = new ArrayList<>();
      try {
        for (Path p : all) {
          if (p.getFileName().toString().endsWith(".device")) continue;
          FileChannel ch = FileChannel.open(p.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          open.add(ch);
          FileLock l;
          try {
            l = ch.tryLock();
          } catch (OverlappingFileLockException e) {
            l = null;
          }
          if (l == null) return "it is open (apksim beam run): quit it first";
        }
        for (Path p : all) SlotPack.deleteTree(p);  // never through a link
      } finally {
        for (FileChannel ch : open) ch.close();
      }
      Files.deleteIfExists(savedDir.resolve(rec.get("id") + ".json"));
      return null;
    } catch (IOException | RuntimeException e) {
      return e.toString();
    }
  }

  // ---------------------------------------------------------------- the sweep

  /**
   * Forgets saved sessions unused for `keep-saved-days`, then deletes builds nothing keeps once `keep-days` have
   * passed since their last session ended, each with its apksim cache entry. `inUse` names the builds a running
   * session or a live grant holds. Synchronized: the timer, session ends and starts all call it.
   */
  synchronized void sweep(Catalog catalog, Predicate<String> inUse) {
    Config c = config;
    long now = System.currentTimeMillis();
    if (c.keepSavedDays() >= 0) {
      for (Map<String, Object> r : saved()) {
        long last = r.get("lastUsed") instanceof Number n ? n.longValue() : 0;
        if (now - last < c.keepSavedDays() * DAY_MS) continue;
        String why = forget(r);
        if (why == null) {
          System.err.printf("[beam] forgot saved session %s (%s): unused for %s days%n", r.get("id"), r.get("app"), num(c.keepSavedDays()));
          if (r.get("sha256") instanceof String h) catalog.released(h);
        }
      }
    }
    Set<String> saved = savedBuilds();
    List<String> gone = catalog.sweepBuilds(sha -> saved.contains(sha) || inUse.test(sha), (long) (c.keepDays() * DAY_MS), GRACE_MS);
    for (String name : gone) {
      try {
        boolean all = ApkCache.remove(cacheRoot, name);
        System.err.printf("[beam] deleted build %s%s%n", name.substring(0, 12), all ? " and its cache entry" : " (its cache entry is in use: later)");
      } catch (IOException e) {
        System.err.println("[beam] could not remove the cache entry of " + name + ": " + e);
      }
    }
    // The agent's own per-build caches (translated classes, BeamStore's sibling Serve.beamCache) of builds now gone, and
    // out folders of beamed instances that have exited (a session's end moves them; one that ended the agent leaves them).
    Path cacheDir = beamDir.resolve("cache");
    if (Files.isDirectory(cacheDir)) {
      try (DirectoryStream<Path> d = Files.newDirectoryStream(cacheDir)) {
        for (Path p : d) {
          String n = p.getFileName().toString();
          if (n.matches("[0-9a-f]{64}") && !catalog.hasBuild(n)) deleteTree(p);
        }
      } catch (IOException e) {
        // the next sweep
      }
    }
    // What a session's end took from an instance and left (the agent ended meanwhile): not one being read now.
    Path importDir = beamDir.resolve("import");
    if (Files.isDirectory(importDir)) {
      try (DirectoryStream<Path> d = Files.newDirectoryStream(importDir)) {
        for (Path p : d) {
          if (Files.getLastModifiedTime(p, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() < now - 3_600_000L) deleteTree(p);
        }
      } catch (IOException e) {
        // the next sweep
      }
    }
    Path outDir = beamDir.resolve("out");
    if (Files.isDirectory(outDir)) {
      try (DirectoryStream<Path> d = Files.newDirectoryStream(outDir)) {
        for (Path p : d) {
          String n = p.getFileName().toString();
          if (!n.matches("\\d{1,10}")) continue;
          boolean alive = ProcessHandle.of(Long.parseLong(n)).map(ProcessHandle::isAlive).orElse(false);
          if (!alive) deleteTree(p);
        }
      } catch (IOException e) {
        // the next sweep
      }
    }
    // A cache entry an earlier sweep left (in use then) whose build is gone: named by a build's hash, which no other
    // launch's file is but a serve upload of the same build (`STATE/serve/uploads`), whose entry stays.
    try (DirectoryStream<Path> d = Files.newDirectoryStream(cacheRoot, "*.apk-*")) {
      for (Path p : d) {
        String n = p.getFileName().toString();
        if (!n.matches("[0-9a-f]{64}\\.apk-[0-9a-f]{16}")) continue;
        String sha = n.substring(0, 64);
        if (catalog.hasBuild(sha) || Files.exists(state.resolve("serve/uploads").resolve(sha + ".apk"))) continue;
        ApkCache.remove(cacheRoot, n.substring(0, 68));
      }
    } catch (IOException e) {
      // the next sweep
    }
  }

  /** Deletes a folder and what is in it, never through a link at any level (SlotPack.deleteTree). Best effort. */
  static void deleteTree(Path dir) {
    if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;  // nothing there yet (a build's first cache entry)
    SlotPack.deleteTree(dir);
  }

  // ---------------------------------------------------------------- apksim beam saved|run|forget|config|sweep

  private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

  /** The command line's subcommands (`trust` is {@link Beam#trustCommand}). Returns the exit code. */
  static int command(Path root, Path state, Path launcher, String cmd, List<String> args) throws IOException, InterruptedException {
    BeamStore store = new BeamStore(state);
    Catalog catalog = new Catalog(List.of(), state, "beam", Long.MAX_VALUE);
    switch (cmd) {
      case "saved" -> {
        List<Map<String, Object>> all = store.saved();
        if (all.isEmpty()) {
          System.out.println("no saved sessions (\"Keep on this Mac\" in the page saves one; apksim beam config save-sessions on saves every one)");
          return 0;
        }
        System.out.printf("%-12s  %-24s  %-32s  %-16s  %8s  %s%n", "ID", "APP", "SITE", "SAVED", "DATA", "BUILD");
        for (Map<String, Object> r : all) {
          long when = r.get("saved") instanceof Number n ? n.longValue() : 0;
          String sha = r.get("sha256") instanceof String h ? h : "";
          System.out.printf("%-12s  %-24s  %-32s  %-16s  %6.1f MB  %s%n", r.get("id"), r.getOrDefault("app", "?"),
              r.getOrDefault("site", "?"), WHEN.format(Instant.ofEpochMilli(when)), store.size(r) / 1048576.0,
              catalog.hasBuild(sha) ? "here" : "gone (beam it again to bring it back)");
        }
        return 0;
      }
      case "run" -> {
        if (args.isEmpty()) return usage();
        Map<String, Object> r = store.saved(args.get(0));
        if (r == null) {
          System.err.println("apksim beam run: no saved session " + args.get(0) + " (apksim beam saved lists them)");
          return 1;
        }
        String sha = r.get("sha256") instanceof String h ? h : "";
        if (!catalog.hasBuild(sha)) {
          System.err.println("apksim beam run: the build of " + r.get("id") + " (" + r.get("app") + ") is no longer on this Mac;"
              + " running it again from " + r.getOrDefault("site", "its site") + " in the browser brings it back, data kept");
          return 1;
        }
        List<String> cmdLine = new ArrayList<>(List.of(launcher.toString(), "--data-slots", "k" + r.get("id"), "--no-autofill"));
        List<String> launch = new ArrayList<>();
        if (r.get("launch") instanceof List<?> l) for (Object o : l) launch.add(String.valueOf(o));
        // Checked again (the record is a file anyone here could edit). First the app's own options (its --device was its
        // default: the session's device is recorded), then what the session ended with (device, appearance, locale,
        // font scale, as its tab last set them), then options given here, which win.
        List<String> own = Catalog.launchOptions(launch);
        for (int i = 0; i < own.size(); i++) {
          if (own.get(i).equals("--device") && r.get("device") instanceof String) i++;
          else cmdLine.add(own.get(i));
        }
        if (r.get("device") instanceof String d) cmdLine.addAll(List.of("--device", d));
        if (r.get("appearance") instanceof String a && (a.equals("dark") || a.equals("light"))) cmdLine.addAll(List.of("--appearance", a));
        if (r.get("locale") instanceof String l && l.matches("[a-z]{2,3}(-[A-Z]{2})?")) cmdLine.addAll(List.of("--locale", l));
        if (r.get("fontscale") instanceof String f && f.matches("[0-2](\\.\\d{1,2})?")) cmdLine.addAll(List.of("--font-scale", f));
        if (r.get("permissions") instanceof String p && (p.equals("grant") || p.equals("deny"))) cmdLine.addAll(List.of("--permissions", p));
        cmdLine.addAll(args.subList(1, args.size()));
        // As a tab's session got it (issue 171): the record keeps the app's own options, not what the agent added.
        SharedDataLibs.addFlag(cmdLine, catalog.buildFile(sha), sha, String.valueOf(r.get("app")), System.err::println);
        cmdLine.add(catalog.buildFile(sha).toString());
        // Held (shared: runs of one session side by side) until the launch ends: forget and the expiry sweep take it
        // exclusively, so neither erases a session that is open or opening.
        try (FileChannel ch = store.sessionLock(String.valueOf(r.get("id")))) {
          FileLock held;
          try {
            held = ch.tryLock(0, Long.MAX_VALUE, true);
          } catch (OverlappingFileLockException e) {
            held = null;  // this JVM holds it already: as taken (released with the channel, closed below either way)
          }
          if (held == null) {
            System.err.println("apksim beam run: " + r.get("id") + " is being forgotten");
            return 1;
          }
          store.used(String.valueOf(r.get("id")));
          int code = new ProcessBuilder(cmdLine).inheritIO().start().waitFor();
          store.used(String.valueOf(r.get("id")));
          return code;
        }
      }
      case "forget" -> {
        if (args.size() != 1) return usage();
        Map<String, Object> r = store.saved(args.get(0));
        if (r == null) {
          System.err.println("apksim beam forget: no saved session " + args.get(0));
          return 1;
        }
        String why = store.forget(r);
        if (why != null) {
          System.err.println("apksim beam forget: " + why);
          return 1;
        }
        if (r.get("sha256") instanceof String h) catalog.released(h);
        System.out.println("forgot " + r.get("id") + " (" + r.get("app") + "); its build is kept "
            + num(store.config.keepDays()) + " days from now unless another session uses it");
        return 0;
      }
      case "config" -> {
        Config c = store.config;
        Beam beam = new Beam(state);
        boolean changed = false;
        for (int i = 0; i < args.size(); i += 2) {
          String k = args.get(i), v = i + 1 < args.size() ? args.get(i + 1) : null;
          switch (k) {
            // Issue 177: where the agent may download a build a page names (`source`), read at every start (no restart).
            case "allow-download", "disallow-download" -> {
              String p = Beam.downloadPrefix(v);
              if (p == null) {
                System.err.println("apksim beam config " + k + ": takes a URL prefix such as https://artifacts.example.com/builds/"
                    + " (https; http only on 127.0.0.1 or localhost; no user, query or fragment)");
                return 2;
              }
              beam.setDownload(p, k.equals("allow-download"));
              System.out.println((k.equals("allow-download") ? "downloads allowed from " : "downloads no longer allowed from ") + p);
            }
            case "keep-days" -> {
              Double d = days(v, false);
              if (d == null) return usage();
              c = new Config(d, c.saveSessions(), c.keepSavedDays());
              changed = true;
            }
            case "save-sessions" -> {
              Boolean b = onOff(v);
              if (b == null) return usage();
              c = new Config(c.keepDays(), b, c.keepSavedDays());
              changed = true;
            }
            case "keep-saved-days" -> {
              Double d = days(v, true);
              if (d == null) return usage();
              c = new Config(c.keepDays(), c.saveSessions(), d);
              changed = true;
            }
            default -> {
              return usage();
            }
          }
        }
        if (changed) store.writeConfig(c);
        System.out.println(c.describe() + (changed && store.agentPort() >= 0 ? "  (the running agent reads it at its next start)" : ""));
        List<String> dl = beam.downloads();
        System.out.println("downloads from: " + (dl.isEmpty() ? "none (the agent fetches builds only from the page's own server)" : String.join(", ", dl)));
        return 0;
      }
      case "status" -> {
        // What the agent is doing, what it lets in, and what it keeps.
        int port = store.agentPort();
        if (port > 0) {
          try {
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/beam/status"))
                    .timeout(java.time.Duration.ofSeconds(5)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200 && Json.parse(r.body()) instanceof Map<?, ?> st) {
              List<?> ss = st.get("sessions") instanceof List<?> l ? l : List.of();
              System.out.printf("agent: running on 127.0.0.1:%d, %d of %d sessions%n", port, ss.size(), whole(st.get("max")));
              for (Object o : ss) {
                if (o instanceof Map<?, ?> x) System.out.printf("  %s  %-24s from %-32s %s%s, pid %d, idle %d s%n", x.get("id"), x.get("app"),
                    x.get("site"), x.get("device"), Boolean.TRUE.equals(x.get("keep")) ? ", kept when it ends" : "", whole(x.get("pid")), whole(x.get("idleS")));
              }
            } else {
              System.out.println("agent: running (port " + port + "), status unavailable (" + r.statusCode() + ")");
            }
          } catch (IOException e) {
            System.out.println("agent: holds its lock (port " + port + ") but does not answer: " + e.getMessage());
          }
        } else {
          System.out.println(port == 0 ? "agent: a sweep is running" : port == -2 ? "agent: exiting, idle (launchd starts it again for the next page)"
              : "agent: not running (apksim beam starts it)");
        }
        Map<String, String> trust = new Beam(state).trust();
        List<String> always = trust.entrySet().stream().filter(e -> e.getValue().equals("always")).map(Map.Entry::getKey).toList();
        List<String> never = trust.entrySet().stream().filter(e -> e.getValue().equals("never")).map(Map.Entry::getKey).toList();
        System.out.println("sites allowed: " + (always.isEmpty() ? "none" : String.join(", ", always))
            + (never.isEmpty() ? "" : "; refused: " + String.join(", ", never)));
        List<String> dl = new Beam(state).downloads();
        if (!dl.isEmpty()) System.out.println("downloads from: " + String.join(", ", dl));
        long bytes = 0;
        int builds = 0;
        Path uploads = state.resolve("beam/uploads");
        if (Files.isDirectory(uploads)) {
          try (DirectoryStream<Path> d = Files.newDirectoryStream(uploads, "*.apk")) {
            for (Path p : d) {
              builds++;
              bytes += Files.size(p);
            }
          }
        }
        System.out.printf("builds: %d (%.1f MB), held %s days after their last session%n", builds, bytes / 1048576.0, num(store.config.keepDays()));
        System.out.println("saved sessions: " + store.saved().size() + (store.saved().isEmpty() ? "" : " (apksim beam saved)"));
        System.out.println("keeps: " + store.config.describe());
        System.out.println(BeamLaunchd.state());
        return 0;
      }
      case "sweep" -> {
        int port = store.agentPort();
        if (port >= 0) {
          System.out.println("the agent" + (port > 0 ? " on port " + port : "") + " is running and sweeps by itself");
          return 0;
        }
        if (!store.lockAgent(0)) {
          System.out.println("an agent just started: it sweeps by itself");
          return 0;
        }
        store.sweep(catalog, sha -> false);
        return 0;
      }
      default -> {
        return usage();
      }
    }
  }

  static final String USAGE = """
      usage: apksim beam saved                  the sessions kept on this Mac
             apksim beam run ID [OPTIONS]       opens one in apksim's window, with the data it left
             apksim beam forget ID              erases one (its build then gets the keep-days hold)
             apksim beam config [keep-days N] [save-sessions on|off] [keep-saved-days N|never]
                                [allow-download URL] [disallow-download URL]   (where it may fetch a build a page names)
             apksim beam sweep                  deletes what is past its time now (the agent does it by itself)
             apksim beam status                 what the agent runs, the sites it lets in, what it keeps
             apksim beam install [--trust ORIGIN]... | uninstall    let pages start the agent through launchd (or not)""";

  private static int usage() {
    System.err.println(USAGE);
    return 2;
  }
}
