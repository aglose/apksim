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
 * grants a page gets from `/beam/start`: to send an APK (`upload`) and to open its session (`session`).
 *
 * The decision rests on `Origin`, which a browser sets and a page cannot: a page on another site can ask, never pass
 * as a trusted one. A process on this Mac can send any Origin, but it could run apksim itself.
 */
final class Beam {
  /** The page's and the agent's protocol: `/beam/hello` says it, and a page that speaks another stays on the server. */
  static final int PROTOCOL = 1;
  static final int DEFAULT_PORT = 47474;
  /** How long a grant waits to be used: the page uses it at once (an upload of a large APK takes longer: per read). */
  private static final long GRANT_MS = 120_000;
  /** "Don't Allow" is not asked again for this long (a page cannot keep a prompt up). */
  private static final long DENIED_MS = 10 * 60_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  enum Kind { UPLOAD, SESSION }

  /** What a grant lets one request do, for one origin. */
  record Grant(Kind kind, String origin, String app, String sha256, String name, List<String> launch, long expires) {}

  private final Path trustFile;
  /**
   * `ask` (the prompt), or `allow` / `deny` for checks only (scripts/beam-check.py): from `-Dapksim.beam.consent` or
   * `APKSIM_BEAM_CONSENT`, which only whoever starts the agent sets. A page can never change it.
   */
  private final String consentMode = System.getProperty("apksim.beam.consent",
      System.getenv().getOrDefault("APKSIM_BEAM_CONSENT", "ask"));
  private final Map<String, Grant> grants = new ConcurrentHashMap<>();
  private final Map<String, Long> deniedUntil = new ConcurrentHashMap<>();
  private final Object prompting = new Object();

  Beam(Path state) {
    this.trustFile = state.resolve("beam/trust.json");
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
      if (trusted(origin)) return true;  // answered "Always Allow" while this request waited
      String answer = switch (consentMode) {
        case "allow" -> "Always Allow";
        case "deny" -> "Don't Allow";
        default -> prompt(origin, name, size);
      };
      System.err.printf("[beam] %s asked to run %s: %s%n", origin, name, answer);
      switch (answer) {
        case "Always Allow" -> {
          setTrust(origin, "always");
          return true;
        }
        case "Allow Once" -> {
          return true;
        }
        default -> {
          deniedUntil.put(origin, System.currentTimeMillis() + DENIED_MS);
          return false;
        }
      }
    }
  }

  /** The native prompt (AppleScript's dialog, in its own process: the agent's JVM has no window server connection). */
  private static String prompt(String origin, String name, long size) {
    String what = name == null || name.isBlank() ? "an app" : "“" + name + "”";
    String sz = size > 0 ? " (" + Math.max(1, size >> 20) + " MB)" : "";
    String text = origin + " wants to run " + what + sz + " on this Mac.\n\n"
        + "The page sends the APK here and shows the app in its tab. The app runs in apksim's sandbox and can reach the "
        + "network this Mac reaches.";
    String script = "display dialog " + appleScriptString(text) + " with title \"apksim\" buttons {\"Don't Allow\", \"Allow Once\", "
        + "\"Always Allow\"} default button \"Allow Once\" cancel button \"Don't Allow\" with icon caution giving up after 120";
    try {
      Process p = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(150, TimeUnit.SECONDS)) p.destroyForcibly();
      if (out.contains("gave up:true")) return "Don't Allow";
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

  // ---------------------------------------------------------------- grants

  String grant(Kind kind, String origin, String app, String sha256, String name, List<String> launch) {
    long now = System.currentTimeMillis();
    grants.values().removeIf(g -> g.expires < now);
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    String token = HexFormat.of().formatHex(b);
    grants.put(token, new Grant(kind, origin, app, sha256, name, launch, now + GRANT_MS));
    return token;
  }

  /** The grant for this token if it is of this kind, for this origin and unexpired; used up either way. */
  Grant take(String token, Kind kind, String origin) {
    if (token == null || origin == null) return null;
    Grant g = grants.remove(token);
    if (g == null || g.kind != kind || !g.origin.equals(origin) || g.expires < System.currentTimeMillis()) return null;
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
