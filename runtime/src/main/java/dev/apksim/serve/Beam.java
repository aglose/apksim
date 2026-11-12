package dev.apksim.serve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * `apksim beam` (docs/beam.md): the agent's own state, beside {@link Serve} running in its loopback-only mode. Which
 * page origins may run apps on this Mac (`STATE/beam/trust.json`, asked once with a native prompt), and the one-use
 * grants a page gets from `/beam/start`: to send an APK (`upload`), to send a moved session's data (`data`, when the
 * agent could not download it itself) and to open its session (`session`).
 *
 * The decision rests on `Origin`, which a browser sets and a page cannot: a page on another site can ask, never pass
 * as a trusted one. A process on this Mac can send any Origin, but it could run apksim itself.
 */
final class Beam {
  /**
   * The protocols between a page and an agent (docs/beam.md, "Versions"): the page that runs is the server's, the agent
   * the visitor's installed release, so each says the range it speaks (`/api/beam` the page's, `/beam/hello` the
   * agent's) and the page uses the newest both do, naming it in `/beam/start` and `/ws`. PROTOCOL is the newest this
   * build speaks; MIN_PROTOCOL the oldest, raised only once no release that far back still serves pages (keep at
   * least the last three releases'). With no protocol in common the page stays on the server and says which side to
   * update. `APKSIM_BEAM_PROTOCOLS=MIN-MAX` overrides the range, for checks only.
   *
   * 2 (issue 173): a start with data the agent cannot download itself may answer `need: "data"`, and the page sends the
   * pack to `/beam/upload-data`. A protocol 1 page would read that answer as a session grant, so it still gets the 502.
   *
   * 3 (issue 276): the start's `"relay": true` and the `y` text messages of the tab relay; `/beam/hello` says `"relay": true`.
   */
  static final int PROTOCOL = 3;
  static final int MIN_PROTOCOL = 1;

  /** {min, max} this build speaks (or the checks' override). */
  static int[] protocols() {
    String o = System.getenv("APKSIM_BEAM_PROTOCOLS");
    if (o != null && o.matches("\\d{1,4}-\\d{1,4}")) {
      String[] v = o.split("-");
      return new int[] {Integer.parseInt(v[0]), Integer.parseInt(v[1])};
    }
    return new int[] {MIN_PROTOCOL, PROTOCOL};
  }

  /**
   * Why a page's request in protocol `asked` (null: none named, which is the first release's page, protocol 1) cannot
   * be served, or null.
   */
  static String protocolProblem(String asked) {
    int p;
    try {
      p = asked == null ? 1 : Integer.parseInt(asked);
    } catch (NumberFormatException e) {
      return "bad protocol " + asked;
    }
    int[] r = protocols();
    if (p > r[1]) return "this Mac's apksim speaks protocols " + r[0] + "-" + r[1] + ", the page " + p + ": update apksim on this Mac (brew upgrade apksim)";
    if (p < r[0]) return "this Mac's apksim speaks protocols " + r[0] + "-" + r[1] + ", the page " + p + ": this site's apksim is older than this Mac's";
    return null;
  }
  static final int DEFAULT_PORT = 47474;
  /** How long a grant waits to be used: the page uses it at once (an upload of a large APK takes longer: per read). */
  private static final long GRANT_MS = 120_000;
  /** "Don't Allow" is not asked again for this long (a page cannot keep a prompt up). */
  private static final long DENIED_MS = 10 * 60_000;
  /** The dialog gives up after 120 s; past this the prompt's process is ended and the start has no answer. */
  private static final long PROMPT_MS = Long.getLong("apksim.beam.promptMs", 150_000);
  private static final SecureRandom RANDOM = new SecureRandom();

  enum Kind { UPLOAD, DATA, SESSION }

  /**
   * What a grant lets one request do, for one origin. `extra`: for a session grant, the slot the agent unpacked a moved
   * session's data into (phase 5); for an upload grant, the data's `ticket url` to fetch once the APK arrives. Null
   * otherwise. `protocol`: the one the start that made it named (an upload's answer depends on it).
   */
  record Grant(Kind kind, String origin, String app, String sha256, String name, List<String> launch, long expires, String extra,
               int protocol) {}

  private final Path trustFile;
  /** URL prefixes the agent may download a build from when a page names one (`source`, issue 177). */
  private final Path downloadsFile;
  /**
   * `ask` (the prompt), or `allow` / `deny` for checks only (scripts/beam-check.py): from `-Dapksim.beam.consent` or
   * `APKSIM_BEAM_CONSENT`, which only whoever starts the agent sets. A page can never change it.
   */
  private final String consentMode = System.getProperty("apksim.beam.consent",
      System.getenv().getOrDefault("APKSIM_BEAM_CONSENT", "ask"));
  private final Map<String, Grant> grants = new ConcurrentHashMap<>();
  private final Map<String, Long> deniedUntil = new ConcurrentHashMap<>();
  /** Builds whose grant was just used (sha256 -> when): its session is being set up, and the sweep must not miss it. */
  private final Map<String, Long> justUsed = new ConcurrentHashMap<>();
  /** Moved-data slots being unpacked now, before their grant exists (the sweep must not take them meanwhile). */
  final java.util.Set<String> importing = ConcurrentHashMap.newKeySet();
  /** Moved-data slots whose grant was just used (name -> when): its session is being set up. */
  private final Map<String, Long> seedsTaken = new ConcurrentHashMap<>();
  private final Object prompting = new Object();
  /** The origin whose consent dialog is up now, else null (`/beam/hello`'s `asking`, issue 178). */
  private volatile String askingFor;

  private final Path state;

  Beam(Path state) {
    this.state = state;
    this.trustFile = state.resolve("beam/trust.json");
    this.downloadsFile = state.resolve("beam/downloads.json");
  }

  /** The version line `/beam/hello` reports: an installed release's number, else "dev". */
  static String version(Path root) {
    try {
      return Files.readString(root.resolve("work/release")).strip();
    } catch (IOException e) {
      return "dev";
    }
  }

  // ---------------------------------------------------------------- trust

  /** origin -> "always" | "never", as saved. */
  synchronized Map<String, String> trust() {
    Map<String, String> t = new TreeMap<>();
    try {
      if (Files.isRegularFile(trustFile) && Json.parse(Files.readString(trustFile)) instanceof Map<?, ?> m) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
          String o = Serve.origin(String.valueOf(e.getKey()));
          if (o != null && (e.getValue().equals("always") || e.getValue().equals("never"))) t.put(o, (String) e.getValue());
        }
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[beam] cannot read " + trustFile + ": " + e.getMessage());
    }
    return t;
  }

  synchronized void setTrust(String origin, String value) throws IOException {
    // Read, change and write under a lock the agent and `apksim beam trust` share, so neither loses the other's change.
    Files.createDirectories(trustFile.getParent());
    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(trustFile.resolveSibling("trust.lock"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
         java.nio.channels.FileLock l = ch.lock()) {
      writeTrust(origin, value);
    }
  }

  private void writeTrust(String origin, String value) throws IOException {
    Map<String, String> t = trust();
    if (value == null) t.remove(origin);
    else t.put(origin, value);
    StringBuilder b = new StringBuilder("{");
    for (Map.Entry<String, String> e : t.entrySet()) {
      if (b.length() > 1) b.append(",");
      b.append("\n  ").append(Http.q(e.getKey())).append(": ").append(Http.q(e.getValue()));
    }
    b.append(t.isEmpty() ? "}\n" : "\n}\n");
    Files.createDirectories(trustFile.getParent());
    Path tmp = trustFile.resolveSibling("trust.json." + ProcessHandle.current().pid() + ".tmp");
    Files.writeString(tmp, b, StandardCharsets.UTF_8);
    Files.move(tmp, trustFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  boolean trusted(String origin) {
    return "always".equals(trust().get(origin));
  }

  /**
   * Whether this origin may run this app now: saved "always", else the prompt's answer ("Always Allow" is saved,
   * "Allow Once" is this start only). "never" and a recent "Don't Allow" refuse without asking. One prompt at a time.
   */
  boolean allowed(String origin, String name, long size) throws IOException {
    String saved = trust().get(origin);
    if ("always".equals(saved)) return true;
    if ("never".equals(saved)) return false;
    synchronized (prompting) {
      Long until = deniedUntil.get(origin);
      if (until != null && until > System.currentTimeMillis()) return false;
      // Read again: while this request waited for another prompt, its answer or `apksim beam trust` may have saved one.
      String now = trust().get(origin);
      if ("never".equals(now)) return false;
      if ("always".equals(now)) return true;
      String answer;
      askingFor = origin;
      try {
        answer = switch (consentMode) {
          case "allow" -> "Always Allow";
          case "deny" -> "Don't Allow";
          // For checks: the dialog's wait without a dialog (scripts/beam-check.py polls hello's `asking` meanwhile).
          case "allow-after-2s" -> {
            sleep(2000);
            yield "Allow Once";
          }
          default -> prompt(origin, name, size);
        };
      } finally {
        askingFor = null;
      }
      System.err.printf("[beam] %s asked to run %s: %s%n", origin, name, answer);
      switch (answer) {
        case "Always Allow" -> {
          setTrust(origin, "always");
          return true;
        }
        case "Allow Once" -> {
          return true;
        }
        case "No Answer" -> {
          return false;  // this start only: nobody saw it, so the page may ask again
        }
        default -> {
          deniedUntil.put(origin, System.currentTimeMillis() + DENIED_MS);
          return false;
        }
      }
    }
  }

  /**
   * Whether the consent dialog is up for this origin now (`/beam/hello`'s `asking`): the page's start is waiting for
   * the Mac's owner, not for the agent, and it can say so (issue 178). Only the asking origin's own: another page
   * learns nothing about which sites ask.
   */
  boolean asking(String origin) {
    return origin != null && origin.equals(askingFor);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * A notification beside the dialog (issue 178): a dialog behind other windows or on another Space is easy to miss,
   * the more so right after the browser's own prompt for apps on this device. Not waited for, and nothing if it fails.
   */
  private static void notifyOwner(String origin) {
    String script = "display notification " + appleScriptString(origin + " asks to run an app on this Mac: choose Allow in apksim's dialog")
        + " with title \"apksim\" sound name \"default\"";
    try {
      Process p = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      Thread.ofVirtual().name("apksim-beam-notify").start(() -> {
        try {
          if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
          p.destroyForcibly();
        }
      });
    } catch (IOException e) {
      System.err.println("[beam] no notification: " + e.getMessage());
    }
  }

  /** The native prompt (AppleScript's dialog, in its own process: the agent's JVM has no window server connection). */
  private static String prompt(String origin, String name, long size) {
    notifyOwner(origin);
    String what = name == null || name.isBlank() ? "an app" : "“" + name + "”";
    String sz = size > 0 ? " (" + Math.max(1, size >> 20) + " MB)" : "";
    String text = origin + " wants to run " + what + sz + " on this Mac.\n\n"
        + "The page sends the APK here and shows the app in its tab. The app runs in apksim's sandbox and can reach the "
        + "network this Mac reaches.";
    // Brought to the front first: a dialog from a process in the background opens behind the window in use.
    String script = "tell current application to activate\ndisplay dialog " + appleScriptString(text) + " with title \"apksim\" buttons {\"Don't Allow\", \"Allow Once\", "
        + "\"Always Allow\"} default button \"Allow Once\" cancel button \"Don't Allow\" with icon caution giving up after 120";
    try {
      Process p = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start();
      // Read on a thread of its own: reading here waited for osascript to end before the timeout was looked at, so
      // one that never ended (a dialog that never came up) held `prompting`, and every later start, for good.
      var output = new java.util.concurrent.CompletableFuture<String>();
      Thread.ofVirtual().name("apksim-beam-prompt").start(() -> {
        try {
          output.complete(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
          output.complete("");
        }
      });
      if (!p.waitFor(PROMPT_MS, TimeUnit.MILLISECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        System.err.println("[beam] the prompt did not end in " + PROMPT_MS / 1000 + " s: no answer");
        return "No Answer";  // nobody answered: this start only, the page may ask again
      }
      String out;
      try {
        out = output.get(5, TimeUnit.SECONDS);  // ended: its output is closed, unless a child it left holds the pipe
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
        out = "";
      }
      if (out.contains("gave up:true")) return "No Answer";
      if (out.contains("button returned:Always Allow")) return "Always Allow";
      if (out.contains("button returned:Allow Once")) return "Allow Once";
      return "Don't Allow";
    } catch (IOException | InterruptedException e) {
      System.err.println("[beam] no prompt: " + e);
      return "Don't Allow";
    }
  }

  private static String appleScriptString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  // ---------------------------------------------------------------- downloads (issue 177)

  /**
   * A download prefix as it is kept, or null: https (http only for this Mac's own loopback addresses), a host, no user,
   * query or fragment, and a path without `..` (none means `/`). Host lower-cased and a default port dropped, so it
   * compares with {@link #downloadAllowed}'s form of a page's `source`.
   */
  static String downloadPrefix(String s) {
    java.net.URI u = parsed(s);
    if (u == null || u.getRawQuery() != null) return null;
    String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
    return site(u) + path;
  }

  /** `scheme://host[:port]` of an address the agent may fetch (see {@link #downloadPrefix}), or null. */
  private static String site(java.net.URI u) {
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
    String host = u.getHost() == null ? null : u.getHost().toLowerCase(java.util.Locale.ROOT);
    if (host == null) return null;
    boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    if (!(scheme.equals("https") || scheme.equals("http") && loopback)) return null;
    int port = u.getPort();
    boolean defaultPort = port == -1 || scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443;
    return scheme + "://" + host + (defaultPort ? "" : ":" + port);
  }

  /**
   * A URL the agent may take apart safely, or null: absolute, http(s), a host, no user or fragment, and no path
   * segment that a server could read as another folder (`..`, `.`, or a slash or dot written as %2F / %2E).
   */
  private static java.net.URI parsed(String s) {
    if (s == null || s.length() > 4000) return null;
    java.net.URI u;
    try {
      u = new java.net.URI(s);
    } catch (java.net.URISyntaxException e) {
      return null;
    }
    if (!u.isAbsolute() || u.getRawUserInfo() != null || u.getRawFragment() != null || site(u) == null) return null;
    String path = u.getRawPath() == null ? "" : u.getRawPath();
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("%2f") || lower.contains("%2e") || lower.contains("%5c") || path.contains("\\")) return null;
    for (String seg : path.split("/", -1)) if (seg.equals("..") || seg.equals(".")) return null;
    return u;
  }

  /** The prefixes allowed, as kept (`apksim beam config allow-download`). */
  synchronized List<String> downloads() {
    List<String> d = new java.util.ArrayList<>();
    try {
      if (Files.isRegularFile(downloadsFile) && Json.parse(Files.readString(downloadsFile)) instanceof List<?> l) {
        for (Object x : l) {
          String p = x instanceof String str ? downloadPrefix(str) : null;
          if (p != null && !d.contains(p)) d.add(p);
        }
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[beam] cannot read " + downloadsFile + ": " + e.getMessage());
    }
    return d;
  }

  /** Adds or removes a prefix (already {@link #downloadPrefix}'s form), under trust's lock. */
  synchronized void setDownload(String prefix, boolean allow) throws IOException {
    Files.createDirectories(downloadsFile.getParent());
    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(trustFile.resolveSibling("trust.lock"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
         java.nio.channels.FileLock l = ch.lock()) {
      List<String> d = downloads();
      d.remove(prefix);
      if (allow) d.add(prefix);
      StringBuilder b = new StringBuilder("[");
      for (String p : d) b.append(b.length() > 1 ? "," : "").append("\n  ").append(Http.q(p));
      b.append(d.isEmpty() ? "]\n" : "\n]\n");
      Path tmp = downloadsFile.resolveSibling("downloads.json." + ProcessHandle.current().pid() + ".tmp");
      Files.writeString(tmp, b, StandardCharsets.UTF_8);
      Files.move(tmp, downloadsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
  }

  /**
   * Whether this Mac's owner lets the agent download from this address (a page's `source`, or a redirect on the way):
   * on a prefix's site exactly, and at its path or below it (`/builds` covers `/builds` and `/builds/x`, never
   * `/builds-other`). The parsed address, or null.
   */
  java.net.URI downloadAllowed(String address) {
    java.net.URI u = parsed(address);
    if (u == null) return null;
    String at = site(u) + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath());
    for (String p : downloads()) {
      if (at.equals(p) || at.startsWith(p.endsWith("/") ? p : p + "/")) return u;
    }
    return null;
  }

  // ---------------------------------------------------------------- grants

  String grant(Kind kind, String origin, String app, String sha256, String name, List<String> launch) {
    return grant(kind, origin, app, sha256, name, launch, null);
  }

  String grant(Kind kind, String origin, String app, String sha256, String name, List<String> launch, String extra) {
    return grant(kind, origin, app, sha256, name, launch, extra, 1);
  }

  String grant(Kind kind, String origin, String app, String sha256, String name, List<String> launch, String extra, int protocol) {
    expire();
    long now = System.currentTimeMillis();
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    String token = HexFormat.of().formatHex(b);
    grants.put(token, new Grant(kind, origin, app, sha256, name, launch, now + GRANT_MS, extra, protocol));
    if (kind == Kind.SESSION && extra != null) importing.remove(extra);  // the grant owns it now
    return token;
  }

  /** Drops expired grants, and with a session grant the moved data unpacked for it (also every minute, `Serve`). */
  void expire() {
    long now = System.currentTimeMillis();
    for (var e : List.copyOf(grants.entrySet())) {
      if (e.getValue().expires < now && grants.remove(e.getKey(), e.getValue())) dropSeed(e.getValue());
    }
  }

  /** Whether a live session grant owns this moved-data slot (the hourly sweep leaves it). */
  boolean ownsSeed(String name) {
    seedsTaken.values().removeIf(t -> t < System.currentTimeMillis() - 30_000);
    return importing.contains(name) || seedsTaken.containsKey(name) || grants.values().stream().anyMatch(g -> g.kind == Kind.SESSION && name.equals(g.extra));
  }

  private void dropSeed(Grant g) {
    if (g.kind == Kind.SESSION) SlotPack.dropSeed(state, g.extra, null, System.err::println);
  }

  /** Whether an unexpired grant names this build (the sweep keeps it: the page is about to use it). */
  boolean holds(String sha256) {
    long now = System.currentTimeMillis();
    justUsed.values().removeIf(t -> t < now - 30_000);
    return justUsed.containsKey(sha256) || grants.values().stream().anyMatch(g -> g.expires >= now && sha256.equals(g.sha256));
  }

  /** The session a grant was used for is registered now: it holds its build itself. */
  void settled(String sha256) {
    if (sha256 != null) justUsed.remove(sha256);
  }

  /** The grant for this token if it is of this kind, for this origin and unexpired; used up either way. */
  Grant take(String token, Kind kind, String origin) {
    if (token == null || origin == null) return null;
    Grant g = grants.get(token);
    if (g == null) return null;
    // Marked taken before the grant goes, so a sweep in between still finds an owner for its build and moved data
    // (removed first, a sweep there saw neither and erased the data the session was about to start with).
    Long now = System.currentTimeMillis();
    String seed = g.kind == Kind.SESSION ? g.extra : null;
    if (g.sha256 != null) justUsed.put(g.sha256, now);
    if (seed != null) seedsTaken.put(seed, now);
    if (!grants.remove(token, g)) return null;  // another request took it: its marks are its own
    if (g.kind != kind || !g.origin.equals(origin) || g.expires < now) {
      if (g.sha256 != null) justUsed.remove(g.sha256, now);  // unless another take of the build marked it since
      if (seed != null) seedsTaken.remove(seed, now);
      dropSeed(g);  // used up, so its moved data has no session to go to
      return null;
    }
    return g;
  }

  // ---------------------------------------------------------------- apksim beam trust

  /** `apksim beam trust [list|allow ORIGIN|revoke ORIGIN|deny ORIGIN]`. */
  static void trustCommand(Path state, List<String> args) throws IOException {
    Beam b = new Beam(state);
    String cmd = args.isEmpty() ? "list" : args.get(0);
    if (cmd.equals("list")) {
      Map<String, String> t = b.trust();
      if (t.isEmpty()) System.out.println("no sites (a site asks the first time it runs an app here)");
      t.forEach((o, v) -> System.out.println(v + "\t" + o));
      return;
    }
    String o = args.size() == 2 ? Serve.origin(args.get(1)) : null;
    if (o == null || !List.of("allow", "revoke", "deny").contains(cmd)) {
      System.err.println("usage: apksim beam trust [list | allow ORIGIN | deny ORIGIN | revoke ORIGIN]  (ORIGIN: https://app.example.com)");
      System.exit(2);
    }
    b.setTrust(o, cmd.equals("allow") ? "always" : cmd.equals("deny") ? "never" : null);
    System.out.println((cmd.equals("revoke") ? "forgot " : cmd.equals("allow") ? "allowed " : "refused ") + o);
  }
}
