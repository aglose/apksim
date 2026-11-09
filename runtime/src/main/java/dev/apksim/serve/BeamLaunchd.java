package dev.apksim.serve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * `apksim beam install|uninstall` (docs/beam.md, "The launchd agent"): two LaunchAgents in ~/Library/LaunchAgents.
 * The agent's: launchd listens on 127.0.0.1:PORT and starts `apksim beam --launchd` on a page's first connection,
 * handing it the listening socket on stdin (inetd's way, so Java takes it with System.inheritedChannel); the agent goes
 * after its idle time and launchd starts it again for the next page. Nothing runs, no memory is used, until a page
 * asks. The sweep's: `apksim beam sweep` once a day, so builds past their hold go even while the agent never runs.
 * Opt-in: an agent anyone's page can reach (behind its prompt) is the visitor's choice, so nothing installs it.
 */
final class BeamLaunchd {
  static final String LABEL = "dev.apksim.beam";

  private BeamLaunchd() {}

  /**
   * `install [--port N] [--label L] [--agent-idle S] [--trust ORIGIN]...` or `uninstall [--label L]`. Returns the exit
   * code. `--trust` allows a site as `apksim beam trust allow` does (issue 178: a team's setup instructions pre-allow
   * their own dashboard, so its first start shows no dialog).
   */
  static int command(Path launcher, Path state, String cmd, List<String> args) throws IOException, InterruptedException {
    String label = LABEL;
    int port = Beam.DEFAULT_PORT;
    int idle = 600;
    Path dryRun = null;  // --dry-run DIR: the jobs written there, nothing loaded (to look at them, or for checks)
    List<String> trust = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i), v = i + 1 < args.size() ? args.get(++i) : null;
      if (v == null) return usage();
      switch (a) {
        case "--label" -> {
          if (!v.matches("[A-Za-z0-9.-]{1,100}")) return usage();
          label = v;
        }
        case "--port" -> port = Integer.parseInt(v);
        case "--agent-idle" -> idle = Integer.parseInt(v);
        case "--dry-run" -> dryRun = Path.of(v);
        case "--trust" -> {
          String o = Serve.origin(v);
          if (o == null || !cmd.equals("install")) {
            System.err.println("--trust takes a site's origin such as https://dashboard.example.com (install only)");
            return usage();
          }
          trust.add(o);
        }
        default -> {
          return usage();
        }
      }
    }
    Path dir = dryRun != null ? dryRun : Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
    Path agent = dir.resolve(label + ".plist");
    Path sweep = dir.resolve(label + ".sweep.plist");
    String uid = run("id", "-u").strip();
    if (cmd.equals("uninstall")) {
      boolean any = Files.exists(agent) || Files.exists(sweep);
      run("launchctl", "bootout", "gui/" + uid + "/" + label);
      run("launchctl", "bootout", "gui/" + uid + "/" + label + ".sweep");
      Files.deleteIfExists(agent);
      Files.deleteIfExists(sweep);
      System.out.println(any ? "removed " + label + " (pages can no longer start the agent; apksim beam still runs it by hand)"
          : "not installed");
      return 0;
    }
    // The path the launcher was run as (Homebrew's bin/apksim survives an upgrade; the install folder it leads to does not).
    String self = System.getenv("APKSIM_LAUNCHER_PATH");
    String exe = (self != null && Files.isExecutable(Path.of(self)) ? Path.of(self) : launcher).normalize().toString();
    Path logs = state.resolve("beam/logs");
    Files.createDirectories(logs);
    Files.createDirectories(dir);
    String env = System.getenv("APKSIM_HOME") == null ? ""
        // The resolved folder, not the variable as given: launchd starts the jobs from another working directory.
        : "  <key>EnvironmentVariables</key><dict><key>APKSIM_HOME</key>" + str(state.toAbsolutePath().normalize().toString()) + "</dict>\n";
    write(agent, plist(label, guarded(exe, agent, sweep, label, label + ".sweep",
            List.of("beam", "--launchd", "--port", Integer.toString(port), "--agent-idle", Integer.toString(idle))),
        "  <key>Sockets</key><dict><key>Listeners</key><dict>"
            + "<key>SockNodeName</key><string>127.0.0.1</string><key>SockServiceName</key>" + str(Integer.toString(port))
            + "<key>SockType</key><string>stream</string><key>SockFamily</key><string>IPv4</string></dict></dict>\n"
            + "  <key>inetdCompatibility</key><dict><key>Wait</key><true/></dict>\n"
            + "  <key>ProcessType</key><string>Interactive</string>\n" + env, logs.resolve("agent.log")));
    write(sweep, plist(label + ".sweep", guarded(exe, agent, sweep, label + ".sweep", label, List.of("beam", "sweep")),
        "  <key>StartInterval</key><integer>86400</integer>\n" + env, logs.resolve("sweep.log")));
    for (String o : trust) {
      new Beam(state).setTrust(o, "always");
      System.out.println("allowed " + o + " (it runs apps here without asking; apksim beam trust revoke " + o + " undoes it)");
    }
    if (dryRun != null) {
      System.out.println("wrote " + agent + " and " + sweep + " (not loaded)");
      return 0;
    }
    run("launchctl", "bootout", "gui/" + uid + "/" + label);
    run("launchctl", "bootout", "gui/" + uid + "/" + label + ".sweep");
    String a = run("launchctl", "bootstrap", "gui/" + uid, agent.toString());
    String b = run("launchctl", "bootstrap", "gui/" + uid, sweep.toString());
    if (!loaded(uid, label) || !loaded(uid, label + ".sweep")) {
      System.err.println("apksim beam install: launchctl did not load it: " + (a + " " + b).strip());
      return 1;
    }
    System.out.printf("installed %s: pages reach the agent on 127.0.0.1:%d (started when one asks, gone after %d s idle);"
        + " builds are swept daily. apksim beam uninstall removes it.%n", label, port, idle);
    return 0;
  }

  /**
   * A job's command: the launcher with these arguments, while it is there. Once it is gone (apksim uninstalled), the job
   * removes both jobs' files and boots both out, so nothing is left for macOS to start at the next login. The Homebrew
   * cask cannot do that on uninstall: `brew upgrade` runs a cask's uninstall `launchctl` and `delete` too, which took
   * the jobs away at every upgrade (Homebrew skips only `signal` there), so the cask leaves them to this and to `zap`.
   * Looked at twice a minute apart (APKSIM_BEAM_GONE_WAIT seconds, for checks), so an upgrade relinking bin/apksim
   * meanwhile does not count as gone. Paths and labels are arguments, never part of the script.
   */
  static List<String> guarded(String exe, Path agentPlist, Path sweepPlist, String self, String other, List<String> args) {
    String script = """
        a=$1 s=$2 self=$3 other=$4 l=$5; shift 5
        [ -x "$l" ] || sleep "${APKSIM_BEAM_GONE_WAIT:-60}"
        [ -x "$l" ] && exec "$l" "$@"
        echo "apksim beam: $l is gone (apksim was uninstalled): removing its launchd jobs" >&2
        rm -f "$a" "$s"
        launchctl bootout "gui/$(id -u)/$other" 2>/dev/null
        exec launchctl bootout "gui/$(id -u)/$self"
        """;
    List<String> cmd = new ArrayList<>(List.of("/bin/sh", "-c", script, "apksim-beam-job", agentPlist.toString(),
        sweepPlist.toString(), self, other, exe));
    cmd.addAll(args);
    return cmd;
  }

  /** Whether launchd has this job loaded in the user's session. */
  static boolean loaded(String uid, String label) throws IOException, InterruptedException {
    Process p = new ProcessBuilder("launchctl", "print", "gui/" + uid + "/" + label).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    return p.waitFor() == 0;
  }

  /** For `apksim beam status`: whether the agent's job is installed, and loaded. */
  static String state() {
    try {
      Path agent = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", LABEL + ".plist");
      if (!Files.exists(agent)) return "launchd: not installed (apksim beam install lets pages start the agent)";
      return "launchd: installed (" + agent + ")" + (loaded(run("id", "-u").strip(), LABEL) ? "" : ", but not loaded");
    } catch (IOException e) {
      return "launchd: unknown (" + e.getMessage() + ")";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "launchd: unknown";
    }
  }

  private static String plist(String label, List<String> args, String more, Path log) {
    StringBuilder b = new StringBuilder("""
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
        """);
    b.append("  <key>Label</key>").append(str(label)).append('\n');
    b.append("  <key>ProgramArguments</key><array>");
    for (String a : args) b.append(str(a));
    b.append("</array>\n").append(more);
    b.append("  <key>StandardOutPath</key>").append(str(log.toString())).append('\n');
    b.append("  <key>StandardErrorPath</key>").append(str(log.toString())).append('\n');
    return b.append("</dict>\n</plist>\n").toString();
  }

  private static String str(String s) {
    return "<string>" + s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</string>";
  }

  private static void write(Path f, String text) throws IOException {
    Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
    Files.writeString(tmp, text, StandardCharsets.UTF_8);
    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  private static String run(String... cmd) throws IOException {
    try {
      Process p = new ProcessBuilder(new ArrayList<>(List.of(cmd))).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p.waitFor();
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static int usage() {
    System.err.println("usage: apksim beam install [--port N] [--agent-idle SECONDS] [--trust ORIGIN]... [--dry-run DIR] | apksim beam uninstall");
    return 2;
  }
}
