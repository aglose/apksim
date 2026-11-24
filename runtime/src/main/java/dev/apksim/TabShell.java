package dev.apksim;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * `tabshell limited|full BASE64`: an `adb shell` command line a browser tab sent (`apksim serve` and `beam`, issue 397),
 * run by the adb shell (AdbShell) without an adb device. Only the server sends it: it is not a command a tab may type.
 * Answered `ok STATUS BASE64` (the exit status and what the command printed, at most {@link #MAX_OUT} bytes) or
 * `error: ...`.
 *
 * `limited` is what any page may run: one command, no `;`, `&&`, pipe or redirection, and only
 *   am start|start-activity [-W] [-a ACTION] [-d URI] [-t TYPE] [-c CATEGORY] [-n PKG/CLASS] [-p PKG] [-f FLAGS] [extras]
 *   am broadcast [-a ACTION] [-d URI] [-t TYPE] [-c CATEGORY] [-n PKG/CLASS] [-p PKG] [-f FLAGS] [extras]
 *   settings get|put global|secure|system KEY [VALUE]
 *   cat PATH   (one file inside the app's files, cache, external files or external cache folder: issue 408)
 * with the extras `--broadcast` takes (--es --ez --ei --el --ef K V) and the app's own package: a component or -p of
 * another package is refused, and a broadcast that names neither is sent to the app's package alone. Nothing here writes a
 * file or names another process. `full` (a beamed session that asked for adb while its Mac's owner allows it) is the whole shell, as
 * `adb shell LINE` on the instance's adb device, except that nothing streams: `logcat` prints what there is.
 *
 * Runs on the control connection's own thread, not Host's loop: the shell's commands wait for the main thread.
 */
public final class TabShell {
  static final int MAX_OUT = 256 * 1024;

  private TabShell() {}

  static String run(String[] args) {
    if (args.length != 3 || !(args[1].equals("limited") || args[1].equals("full"))) return "error: usage: tabshell limited|full BASE64";
    String line;
    try {
      line = new String(Base64.getDecoder().decode(args[2]), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "error: tabshell: bad base64";
    }
    if (TestActivities.testRun()) return "error: tabshell is for running instances";
    if (!AdbServer.awaitFramework()) return "error: the app is still starting, retry";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int status;
    try {
      status = AdbShell.runForTab(line, args[1].equals("full"), out);
    } catch (Crashes.AppCrash crash) {
      return "error: app crashed: " + crash.getMessage();
    } catch (Exception e) {
      return "error: " + e;
    }
    byte[] b = out.toByteArray();
    if (b.length > MAX_OUT) {
      byte[] cut = new byte[MAX_OUT];
      System.arraycopy(b, 0, cut, 0, MAX_OUT);
      b = cut;
    }
    return "ok " + status + " " + Base64.getEncoder().encodeToString(b);
  }

  private static boolean inRange(String v, double lo, double hi) {
    try {
      double d = Double.parseDouble(v);
      return Double.isFinite(d) && d >= lo && d <= hi;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Whether an allowed `am` line names a component or package of its own (`-n`, `-p` as options, not as values). */
  static boolean pinned(List<String> a) {
    for (int i = 2; i < a.size(); i++) {
      switch (a.get(i)) {
        case "-n", "-p" -> {
          return true;
        }
        case "-a", "-d", "-t", "-c", "-f" -> i++;
        case "--es", "--ez", "--ei", "--el", "--ef" -> i += 2;
        default -> { }
      }
    }
    return false;
  }

  /** {@link #pinned} for the agent's device sessions (issue 626). */
  public static boolean pinnedFor(List<String> a) {
    return pinned(a);
  }

  /** {@link #refusal} for the agent's device sessions (issue 626: DeviceBridge runs the line on a real device). */
  public static String refusalFor(List<String> a, String pkg) {
    return refusal(a, pkg);
  }

  /** Why a limited line is refused, or null when it may run. `pkg` is the app's package. */
  static String refusal(List<String> a, String pkg) {
    if (a.isEmpty()) return "empty command";
    switch (a.get(0)) {
      case "am" -> {
        if (a.size() < 2 || !List.of("start", "start-activity", "broadcast").contains(a.get(1))) {
          return "only am start and am broadcast";
        }
        boolean start = !a.get(1).equals("broadcast");
        for (int i = 2; i < a.size(); i++) {
          String o = a.get(i);
          switch (o) {
            case "-W" -> {
              if (!start) return "-W is am start's";
            }
            case "-a", "-d", "-t", "-c", "-f" -> {
              if (++i >= a.size()) return o + " needs a value";
            }
            case "-n" -> {
              if (++i >= a.size()) return "-n needs a component";
              int slash = a.get(i).indexOf('/');
              if (slash <= 0 || !a.get(i).substring(0, slash).equals(pkg)) return "a component of another package (this app is " + pkg + ")";
            }
            case "-p" -> {
              if (++i >= a.size()) return "-p needs a package";
              if (!a.get(i).equals(pkg)) return "another package (this app is " + pkg + ")";
            }
            case "--es", "--ez", "--ei", "--el", "--ef" -> {
              i += 2;
              if (i >= a.size()) return o + " needs a key and a value";
            }
            default -> {
              return "option " + (o.length() > 40 ? o.substring(0, 40) + "..." : o) + " (allowed: "
                  + (start ? "-W " : "") + "-a -d -t -c -n -p -f --es --ez --ei --el --ef)";
            }
          }
        }
        return null;
      }
      case "settings" -> {
        if (a.size() < 4 || !List.of("get", "put").contains(a.get(1))) return "only settings get|put NAMESPACE KEY [VALUE]";
        if (!a.get(2).matches("global|secure|system")) return "namespace " + a.get(2);
        if (!a.get(3).matches("[A-Za-z0-9_.:-]{1,200}")) return "that key";
        if (a.size() != (a.get(1).equals("get") ? 4 : 5)) return a.get(1).equals("get") ? "settings get NAMESPACE KEY" : "settings put NAMESPACE KEY VALUE";
        // The keys that are device controls take the values a tab's own controls may send (Session.check), no others.
        if (a.get(1).equals("put") && a.get(3).equals("font_scale") && !inRange(a.get(4), 0.85, 2)) return "font_scale takes 0.85 to 2";
        if (a.get(1).equals("put") && a.get(3).equals("animator_duration_scale") && !inRange(a.get(4), 0, 10)) {
          return "animator_duration_scale takes 0 to 10";
        }
        return null;
      }
      case "cat" -> {
        if (a.size() != 2 || a.get(1).startsWith("-")) return "cat PATH (one file)";
        return null; // which file is decided where the path resolves (AdbShell.tabCat)
      }
      default -> {
        return "only am start, am broadcast, settings get|put and cat of a file in the app's own folders";
      }
    }
  }
}
