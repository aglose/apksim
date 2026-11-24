package dev.apksim.serve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A real Android device attached over adb as a beam session's screen and input (issue 626, docs/beam.md "Device
 * sessions"): the agent's side of finding the device, installing the build and finding scrcpy's server, which
 * {@link DeviceBridge} runs on the device for its screen and input. Plain JDK and the `adb` program: no Android here.
 *
 * <p>`adb` is the one on PATH, else the Android SDK's (ANDROID_HOME, ANDROID_SDK_ROOT, ~/Library/Android/sdk) or
 * Homebrew's: an agent launchd starts has a short PATH. scrcpy's server is `APKSIM_SCRCPY_SERVER` (with
 * `APKSIM_SCRCPY_VERSION`, the version it was built as: the server refuses any other), else the one a Homebrew
 * `scrcpy` installed beside its program, whose `--version` names it.
 */
final class DeviceTarget {
  private DeviceTarget() {}

  /** One line of `adb devices -l` and what the device says of itself (only for a device in state "device"). */
  record Device(String serial, String state, String model, int sdk, int width, int height, String transport) {
    String json() {
      return "{\"serial\":" + Http.q(serial) + ",\"state\":" + Http.q(state) + ",\"model\":" + Http.q(model) + ",\"sdk\":" + sdk
          + ",\"width\":" + width + ",\"height\":" + height + ",\"transport\":" + Http.q(transport) + "}";
    }
  }

  record Result(int code, String out) {}

  /** The scrcpy server to push and the version string it must be started with. */
  record Server(Path jar, String version) {}

  // ---------------------------------------------------------------- adb

  private static volatile String adb;

  /** The `adb` program, or null when none is found. */
  static String adb() {
    String a = adb;
    if (a != null) return a;
    List<Path> tries = new ArrayList<>();
    String path = System.getenv("PATH");
    if (path != null) for (String d : path.split(":")) if (!d.isEmpty()) tries.add(Path.of(d, "adb"));
    for (String env : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
      String v = System.getenv(env);
      if (v != null && !v.isEmpty()) tries.add(Path.of(v, "platform-tools", "adb"));
    }
    tries.add(Path.of(System.getProperty("user.home"), "Library", "Android", "sdk", "platform-tools", "adb"));
    tries.add(Path.of("/opt/homebrew/bin/adb"));
    tries.add(Path.of("/usr/local/bin/adb"));
    for (Path p : tries) {
      if (Files.isRegularFile(p) && Files.isExecutable(p)) return adb = p.toString();
    }
    return null;
  }

  /** Runs `adb [-s serial] args...` with a deadline; stdout and stderr together. */
  static Result run(String serial, List<String> args, long seconds) throws IOException {
    return run(serial, args, seconds, 16 << 20);
  }

  /** As {@link #run(String, List, long)}, keeping at most `max` bytes of output: past that, adb is stopped. */
  static Result run(String serial, List<String> args, long seconds, int max) throws IOException {
    String a = adb();
    if (a == null) throw new IOException("no adb on this Mac (install the Android SDK's platform-tools)");
    List<String> cmd = new ArrayList<>(List.of(a));
    if (serial != null) cmd.addAll(List.of("-s", serial));
    cmd.addAll(args);
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    Thread drain = Thread.ofVirtual().start(() -> {
      try (var in = p.getInputStream()) {
        byte[] b = new byte[1 << 16];
        for (int n; (n = in.read(b)) > 0; ) {
          synchronized (out) {
            int keep = Math.min(n, max - out.size());
            if (keep > 0) out.write(b, 0, keep);
            if (out.size() >= max) {
              p.destroyForcibly();  // a command that never stops printing (cat /dev/zero) ends here, not in memory
              break;
            }
          }
        }
      } catch (IOException e) {
        // closed on a timeout
      }
    });
    try {
      if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        p.getInputStream().close();
        drain.join(2_000);
        throw new IOException("adb " + String.join(" ", args) + " did not finish in " + seconds + " s");
      }
      drain.join();
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted");
    }
    synchronized (out) {
      return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8));
    }
  }

  /** A device shell command's output, trimmed ("" when it failed). */
  static String shell(String serial, String command) {
    try {
      Result r = run(serial, List.of("shell", command), 15);
      return r.code() == 0 ? r.out().strip() : "";
    } catch (IOException e) {
      return "";
    }
  }

  // ---------------------------------------------------------------- devices

  /** Every device adb knows, with what a page shows of it. Throws when adb itself cannot run. */
  static List<Device> list() throws IOException {
    Result r = run(null, List.of("devices", "-l"), 15);
    if (r.code() != 0) throw new IOException("adb devices failed: " + r.out().strip());
    List<Device> out = new ArrayList<>();
    for (String line : r.out().split("\n")) {
      String[] f = line.strip().split("\\s+");
      if (f.length < 2 || line.startsWith("List of") || line.startsWith("*") || !f[0].matches(DeviceHandoff.SERIAL)) continue;
      String serial = f[0], state = f[1];
      if (!state.matches("[a-z]{1,30}")) continue;
      String model = "";
      for (String kv : f) if (kv.startsWith("model:")) model = kv.substring(6).replace('_', ' ');
      out.add(state.equals("device") ? describe(serial, model) : new Device(serial, state, model, 0, 0, 0, transport(serial)));
    }
    return out;
  }

  /** The state adb reports for this serial ("device", "unauthorized", "offline", ...), or "missing". */
  static String state(String serial) {
    try {
      for (Device d : list()) if (d.serial().equals(serial)) return d.state();
    } catch (IOException e) {
      return "missing";
    }
    return "missing";
  }

  private static Device describe(String serial, String model) {
    String props = shell(serial, "getprop ro.product.model; getprop ro.build.version.sdk; wm size");
    String[] l = props.split("\n");
    String m = l.length > 0 && !l[0].isBlank() ? l[0].strip() : model;
    int sdk = 0;
    try {
      if (l.length > 1) sdk = Integer.parseInt(l[1].strip());
    } catch (NumberFormatException e) {
      // unknown
    }
    int w = 0, h = 0;
    // "Physical size: 1080x2404", then "Override size: ..." when one is set: the override is what the screen shows.
    for (int i = 2; i < l.length; i++) {
      java.util.regex.Matcher s = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(l[i]);
      if (s.find()) {
        w = Integer.parseInt(s.group(1));
        h = Integer.parseInt(s.group(2));
      }
    }
    return new Device(serial, "device", m, sdk, w, h, transport(serial));
  }

  /** USB unless the serial is a network address or an mDNS service name (adb over Wi-Fi). */
  static String transport(String serial) {
    return serial.contains(":") || serial.contains("._adb-tls-") ? "tcp" : "usb";
  }

  /** Why a start cannot run on a device in this state (null when it can). */
  static String refusal(String serial, String state) {
    return switch (state) {
      case "device" -> null;
      case "unauthorized" -> "the device " + serial + " has not allowed this Mac yet: unlock it and accept the"
          + " \"Allow USB debugging?\" prompt on its screen, then start again";
      case "offline" -> "the device " + serial + " is offline to adb: reconnect it (or restart adb) and start again";
      case "missing" -> "no device " + serial + " is attached (GET /beam/devices lists them)";
      default -> "the device " + serial + " is " + state + " to adb, not ready";
    };
  }

  // ---------------------------------------------------------------- the build

  /**
   * Installs the build on the device unless it has it already. Answers null when the device has the build now, or a
   * reason beginning "need-uninstall: " when the installed copy is signed by another key and `uninstall` is false
   * (a replacement would erase the device's data of that app: its user decides). Throws when it could not install.
   */
  static String install(String serial, Path apk, String sha256, boolean uninstall) throws IOException {
    String pkg = packageOf(apk);
    if (pkg == null) throw new IOException("cannot read the build's package name");
    Result pm = run(serial, List.of("shell", "pm path " + DeviceHandoff.q(pkg)), 30);
    String devicePath = pm.code() == 0 && pm.out().startsWith("package:") ? pm.out().lines().findFirst().get().substring(8).strip() : null;
    if (devicePath != null) {
      Path pulled = Files.createTempFile("apksim-device-", ".apk");
      try {
        if (!devicePath.matches("/[A-Za-z0-9_./=~+\\-]{1,300}") || run(serial, List.of("pull", devicePath, pulled.toString()), 300).code() != 0) {
          throw new IOException("could not read the device's build of " + pkg + " to compare it");
        }
        if (sha256(pulled).equals(sha256)) return null;  // already this build: nothing to do
        List<String> have, want;
        try {
          have = ApkSigners.of(pulled).certs();
          want = ApkSigners.of(apk).certs();
        } catch (IOException e) {
          throw new IOException("cannot tell who signed the build or the device's copy of " + pkg + ": " + e.getMessage());
        }
        if (!have.equals(want)) {
          if (!uninstall) {
            return "need-uninstall: the device's " + pkg + " is signed by another key than this build; installing it"
                + " means uninstalling that copy first, which erases its data on the device";
          }
          Result u = run(serial, List.of("uninstall", pkg), 120);
          if (u.code() != 0 || !u.out().contains("Success")) throw new IOException("could not uninstall " + pkg + ": " + u.out().strip());
        }
      } finally {
        Files.deleteIfExists(pulled);
      }
    }
    // -r: replace keeping data (same signer); -d: allow a lower version code, as a build checked here may be older.
    Result r = run(serial, List.of("install", "-r", "-d", apk.toString()), 600);
    if (r.code() != 0 || !r.out().contains("Success")) {
      String why = r.out().strip();
      if (why.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") && !uninstall) {
        return "need-uninstall: the device refused to replace " + pkg + " (" + lastLine(why) + ")";
      }
      throw new IOException("install failed: " + lastLine(why));
    }
    return null;
  }

  private static String lastLine(String s) {
    String[] l = s.strip().split("\n");
    return l[l.length - 1].strip();
  }

  /** The APK's package name, from its binary manifest. */
  static String packageOf(Path apk) {
    try {
      return dev.apksim.ApkCache.manifestPackage(apk);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  static String sha256(Path f) throws IOException {
    try (var in = Files.newInputStream(f)) {
      java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
      byte[] b = new byte[1 << 16];
      for (int n; (n = in.read(b)) > 0; ) d.update(b, 0, n);
      return java.util.HexFormat.of().formatHex(d.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  // ---------------------------------------------------------------- scrcpy's server

  /** scrcpy's server and its version, or null with nothing found (the reason is {@link #serverMissing}). */
  static Server server() {
    String jar = System.getenv("APKSIM_SCRCPY_SERVER");
    if (jar != null && !jar.isEmpty()) {
      String v = System.getenv("APKSIM_SCRCPY_VERSION");
      return Files.isRegularFile(Path.of(jar)) && v != null && v.matches("[0-9][0-9A-Za-z.\\-]{0,30}") ? new Server(Path.of(jar), v) : null;
    }
    for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
      Path j = Path.of(prefix, "share", "scrcpy", "scrcpy-server"), bin = Path.of(prefix, "bin", "scrcpy");
      if (!Files.isRegularFile(j) || !Files.isExecutable(bin)) continue;
      try {
        Process p = new ProcessBuilder(bin.toString(), "--version").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(10, TimeUnit.SECONDS);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^scrcpy ([0-9][0-9A-Za-z.\\-]{0,30})").matcher(out);
        if (m.find()) return new Server(j, m.group(1));
      } catch (IOException e) {
        // the next prefix
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  static final String SERVER_MISSING = "scrcpy's server is not on this Mac: brew install scrcpy (or set APKSIM_SCRCPY_SERVER and"
      + " APKSIM_SCRCPY_VERSION to a scrcpy-server file and the version it was built as)";

  /** Lower-case for comparisons of adb's states and model names. */
  static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}
