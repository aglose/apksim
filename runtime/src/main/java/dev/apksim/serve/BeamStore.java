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

  /** The running agent's port, or -1 when none runs. */
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
      return s.matches("\\d{1,5}") ? Integer.parseInt(s) : 0;
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
   * described in `saved/<id>.json`. The new id, or null when the slot cannot be kept (then the caller erases it).
   */
  String save(Leaving s) {
    Path dir = s.slot().toAbsolutePath().normalize();
    if (!dir.startsWith(dataRoot.toAbsolutePath().normalize()) || !dir.getFileName().toString().startsWith("beam-")) return null;
    byte[] b = new byte[6];
    RANDOM.nextBytes(b);
    String id = HexFormat.of().formatHex(b);
    Path target = dir.resolveSibling("k" + id + "-0");
    try (FileChannel ch = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileLock l;
      try {
        l = ch.tryLock();
      } catch (OverlappingFileLockException e) {
        l = null;
      }
      if (l == null) return null;  // another instance holds it (it took the slot with --fresh): nothing of ours is left
      try {
        Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE);
        Path device = dir.resolveSibling(dir.getFileName() + ".device");
        if (Files.isDirectory(device)) Files.move(device, target.resolveSibling(target.getFileName() + ".device"), StandardCopyOption.ATOMIC_MOVE);
      } finally {
        l.release();
      }
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
      return null;
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

  /**
   * Erases a saved session: its slots (none may be open: `run` holds its slot's lock), then its record. Null when it
   * is gone, else why it was not.
   */
  String forget(Map<String, Object> rec) {
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
        for (Path p : all) {
          try (Stream<Path> files = Files.walk(p)) {
            for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
          }
        }
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
        if (r.get("device") instanceof String d) cmdLine.addAll(List.of("--device", d));
        if (r.get("appearance") instanceof String a && (a.equals("dark") || a.equals("light"))) cmdLine.addAll(List.of("--appearance", a));
        if (r.get("locale") instanceof String l && l.matches("[a-z]{2,3}(-[A-Z]{2})?")) cmdLine.addAll(List.of("--locale", l));
        if (r.get("fontscale") instanceof String f && f.matches("[0-2](\\.\\d{1,2})?")) cmdLine.addAll(List.of("--font-scale", f));
        if (r.get("permissions") instanceof String p && (p.equals("grant") || p.equals("deny"))) cmdLine.addAll(List.of("--permissions", p));
        List<String> launch = new ArrayList<>();
        if (r.get("launch") instanceof List<?> l) for (Object o : l) launch.add(String.valueOf(o));
        cmdLine.addAll(Catalog.launchOptions(launch));  // checked again: the record is a file anyone here could edit
        cmdLine.addAll(args.subList(1, args.size()));
        cmdLine.add(catalog.buildFile(sha).toString());
        store.used(String.valueOf(r.get("id")));
        int code = new ProcessBuilder(cmdLine).inheritIO().start().waitFor();
        store.used(String.valueOf(r.get("id")));
        return code;
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
        for (int i = 0; i < args.size(); i += 2) {
          String k = args.get(i), v = i + 1 < args.size() ? args.get(i + 1) : null;
          switch (k) {
            case "keep-days" -> {
              Double d = days(v, false);
              if (d == null) return usage();
              c = new Config(d, c.saveSessions(), c.keepSavedDays());
            }
            case "save-sessions" -> {
              Boolean b = onOff(v);
              if (b == null) return usage();
              c = new Config(c.keepDays(), b, c.keepSavedDays());
            }
            case "keep-saved-days" -> {
              Double d = days(v, true);
              if (d == null) return usage();
              c = new Config(c.keepDays(), c.saveSessions(), d);
            }
            default -> {
              return usage();
            }
          }
        }
        if (!args.isEmpty()) store.writeConfig(c);
        System.out.println(c.describe() + (args.isEmpty() ? "" : store.agentPort() >= 0 ? "  (the running agent reads it at its next start)" : ""));
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
             apksim beam sweep                  deletes what is past its time now (the agent does it by itself)""";

  private static int usage() {
    System.err.println(USAGE);
    return 2;
  }
}
