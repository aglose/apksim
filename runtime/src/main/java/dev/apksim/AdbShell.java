package dev.apksim;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.robolectric.RuntimeEnvironment;

/**
 * `adb shell`: a small shell whose commands are the device's usual tools, mapped onto apksim. Input, `am start` of a
 * URL, `settings put global animator_duration_scale`, `pm grant`, screencap and the rest go through the control
 * commands (Host.runCommand and DeviceControls.run), so adb, ctl.py, drive.py and the Mac menus share one
 * implementation; `apksim CMD...` sends any control command as is. Files live in the app's folders (see Paths).
 * Supports `;`, `&&`, `||`, quoting and `>`/`>>` into a file; no pipes (pipe on the Mac: `adb shell ... | grep`).
 */
final class AdbShell {
  private static final Map<String, String> SET_PROPS = new ConcurrentHashMap<>();
  private static final AtomicInteger SHOTS = new AtomicInteger();

  private OutputStream out;
  private String cwd = "/";
  private boolean interactive;
  /** Run for UiAutomation.executeShellCommand: output is read once the command ends, so nothing may stream. */
  private boolean inProcess;
  /** The command's standard input (the adb stream's), for `pm install-write ... -`; empty when there is none. */
  private final InputStream in;
  /** Whether the client closed the stream, for commands that wait (`am instrument -w`). */
  private final java.util.function.BooleanSupplier gone;

  AdbShell(OutputStream out) {
    this(out, InputStream.nullInputStream(), () -> false);
  }

  AdbShell(OutputStream out, InputStream in, java.util.function.BooleanSupplier gone) {
    this.out = out;
    this.in = in;
    this.gone = gone;
  }

  // ---------------------------------------------------------------- entry points

  /** Runs one command line, as `adb shell CMD` does. */
  void run(String line) throws Exception {
    int status = 0;
    for (Command c : parse(line)) {
      if (c.after.equals("&&") && status != 0 || c.after.equals("||") && status == 0) continue;
      if (c.args.isEmpty()) continue;
      if (c.args.contains("|")) {
        print("apksim shell: pipes are not supported; pipe on the Mac instead (adb shell CMD | grep ...)\n");
        status = 1;
        continue;
      }
      OutputStream saved = out;
      if (c.redirect != null) {
        Path p = Paths.host(c.redirect, cwd);
        if (c.redirect.equals("/dev/null")) {
          out = OutputStream.nullOutputStream();
        } else if (p == null) {
          print("sh: " + c.redirect + ": Read-only file system\n");
          status = 1;
          continue;
        } else {
          out = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
              c.append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        }
      }
      try {
        status = exec(c.args);
      } finally {
        if (out != saved) {
          out.close();
          out = saved;
        }
      }
      out.flush();
      if (status == EXIT) return;
    }
  }

  /**
   * A command line run for the app's own process (UiAutomation.executeShellCommand), returning all it printed. It
   * runs on the calling thread, which in a test run is the main thread: a device's shell is another process and
   * answers while the test waits, so here nothing may wait for the main thread. Commands that would stream (`logcat`
   * without -d) print what there is and end.
   */
  static byte[] runInProcess(String line) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    AdbShell shell = new AdbShell(out);
    shell.inProcess = true;
    try {
      shell.run(line);
    } catch (Exception e) {
      try {
        shell.print("error: " + e + "\n");
      } catch (IOException ignored) {
        // a ByteArrayOutputStream does not throw
      }
    }
    return out.toByteArray();
  }

  /** `adb shell` with no command: a prompt that echoes, as a terminal would, since the client sends raw keys. */
  void interactive(InputStream in) throws Exception {
    interactive = true;
    OutputStream terminal = out;
    out = new FilterOutputStream(terminal) {
      @Override
      public void write(int b) throws IOException {
        if (b == '\n') super.write('\r');
        super.write(b);
      }
    };
    StringBuilder line = new StringBuilder();
    prompt();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\r' || b == '\n') {
        print("\n");
        String cmd = line.toString();
        line.setLength(0);
        if (cmd.trim().equals("exit")) return;
        try {
          run(cmd);
        } catch (IOException e) {
          throw e;
        } catch (Exception e) {
          print("error: " + e + "\n");
        }
        prompt();
      } else if (b == 0x7f || b == 0x08) {
        if (line.length() > 0) {
          line.setLength(line.length() - 1);
          print("\b \b");
          out.flush();
        }
      } else if (b == 0x03) {
        line.setLength(0);
        print("^C\n");
        prompt();
      } else if (b == 0x04) {
        if (line.length() == 0) return;
      } else if (b >= 0x20 || b == '\t') {
        line.append((char) b);
        out.write(b);
        out.flush();
      }
    }
  }

  private void prompt() throws IOException {
    print("apksim:" + cwd + " # ");
    out.flush();
  }

  // ---------------------------------------------------------------- parsing

  private static final class Command {
    final List<String> args = new ArrayList<>();
    String after = ";";  // the operator before this command
    String redirect;
    boolean append;
  }

  /** Splits a line into commands at unquoted ; & && ||, with '' "" and \ quoting, and > / >> redirections. */
  static List<Command> parse(String line) {
    List<Command> cmds = new ArrayList<>();
    Command c = new Command();
    StringBuilder word = null;
    boolean redirectNext = false;
    boolean discardNext = false;  // the target of 2>
    for (int i = 0; i <= line.length(); i++) {
      char ch = i < line.length() ? line.charAt(i) : '\n';
      // An unquoted lone `&` (`am instrument -w ... &`) ends a command as `;` does: it runs here, and its output
      // still reaches this stream while the stream is open.
      boolean end = ch == '\n' || ch == ';' || ch == '&'
          || ch == '|' && i + 1 < line.length() && line.charAt(i + 1) == '|';
      if (Character.isWhitespace(ch) || end || ch == '>' || ch == '<') {
        String fd = null;
        if (ch == '>' && word != null && word.toString().matches("[0-9]")) {
          fd = word.toString();
          word = null;
        }
        if (word != null) {
          String w = word.toString();
          if (discardNext) discardNext = false;
          else if (redirectNext) c.redirect = w;
          else c.args.add(w);
          redirectNext = false;
          word = null;
        }
        // `< FILE`: standard input from a file; adb's own commands only ever give </dev/null.
        if (ch == '<') discardNext = true;
        if (ch == '>') {
          boolean twice = i + 1 < line.length() && line.charAt(i + 1) == '>';
          if (twice) i++;
          if (fd != null && !fd.equals("1")) {
            // 2>/dev/null and 2>&1: there is only one output stream here.
            if (i + 1 < line.length() && line.charAt(i + 1) == '&') i += 2;
            else discardNext = true;
          } else {
            c.append = twice;
            redirectNext = true;
          }
        }
        if (end) {
          cmds.add(c);
          c = new Command();
          boolean doubled = i + 1 < line.length() && line.charAt(i + 1) == ch;
          if ((ch == '&' || ch == '|') && doubled) {
            c.after = ch == '&' ? "&&" : "||";
            i++;
          }
        }
        continue;
      }
      if (word == null) word = new StringBuilder();
      if (ch == '\'') {
        int close = line.indexOf('\'', i + 1);
        if (close < 0) close = line.length();
        word.append(line, i + 1, close);
        i = close;
      } else if (ch == '"') {
        int j = i + 1;
        while (j < line.length() && line.charAt(j) != '"') {
          if (line.charAt(j) == '\\' && j + 1 < line.length()) j++;
          word.append(line.charAt(j++));
        }
        i = j;
      } else if (ch == '\\' && i + 1 < line.length()) {
        word.append(line.charAt(++i));
      } else {
        word.append(ch);
      }
    }
    return cmds;
  }

  // ---------------------------------------------------------------- commands

  private static final int EXIT = Integer.MIN_VALUE;

  private int exec(List<String> a) throws Exception {
    // Leading NAME=VALUE assignments and `exec` change nothing here.
    while (!a.isEmpty() && (a.get(0).matches("[A-Za-z_][A-Za-z0-9_]*=.*") || a.get(0).equals("exec"))) a = a.subList(1, a.size());
    if (a.isEmpty()) return 0;
    String cmd = a.get(0);
    List<String> args = a.subList(1, a.size());
    switch (cmd) {
      case "export", "true", ":", "set", "stty", "umask", "unset" -> {
        return 0;
      }
      case "false" -> {
        return 1;
      }
      case "exit" -> {
        return EXIT;
      }
      case "echo" -> {
        boolean newline = !(args.size() > 0 && args.get(0).equals("-n"));
        print(String.join(" ", newline ? args : args.subList(1, args.size())) + (newline ? "\n" : ""));
        return 0;
      }
      case "sleep" -> {
        Thread.sleep((long) (Double.parseDouble(args.isEmpty() ? "0" : args.get(0)) * 1000));
        return 0;
      }
      case "id" -> {
        print("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0\n");
        return 0;
      }
      case "whoami" -> {
        print("root\n");
        return 0;
      }
      case "uname" -> {
        print(args.contains("-a") ? "Linux localhost 6.6.0-apksim #1 SMP PREEMPT " + System.getProperty("os.arch") + " Toybox\n"
            : args.contains("-m") ? (HostOs.apkAbi().equals("x86_64") ? "x86_64" : "aarch64") + "\n" : "Linux\n");
        return 0;
      }
      case "pwd" -> {
        print(cwd + "\n");
        return 0;
      }
      case "cd" -> {
        String to = Paths.normalize(args.isEmpty() ? "/" : args.get(0), cwd);
        if (!Paths.isDir(to)) {
          print("cd: " + to + ": No such file or directory\n");
          return 1;
        }
        cwd = to;
        return 0;
      }
      case "getprop" -> {
        return getprop(args);
      }
      case "setprop" -> {
        if (args.size() < 2) return usage("setprop NAME VALUE");
        SET_PROPS.put(args.get(0), args.get(1));
        return 0;
      }
      case "input" -> {
        return input(args);
      }
      case "am" -> {
        return am(args);
      }
      case "pm" -> {
        return pm(args);
      }
      case "wm" -> {
        return wm(args);
      }
      case "settings" -> {
        return settings(args);
      }
      case "cmd" -> {
        return cmd(args);
      }
      case "monkey" -> {
        return monkey(args);
      }
      case "logcat" -> {
        return logcat(args);
      }
      case "screencap" -> {
        return screencap(args);
      }
      case "run-as" -> {
        if (args.isEmpty()) return usage("run-as PACKAGE [COMMAND...]");
        if (!args.get(0).equals(pkg())) {
          print("run-as: unknown package: " + args.get(0) + "\n");
          return 1;
        }
        int i = 1;
        if (i + 1 < args.size() && args.get(i).equals("--user")) i += 2;
        String saved = cwd;
        cwd = "/data/data/" + pkg();
        try {
          return i < args.size() ? exec(new ArrayList<>(args.subList(i, args.size()))) : 0;
        } finally {
          cwd = saved;
        }
      }
      case "pidof" -> {
        if (args.contains(pkg())) {
          print(ProcessHandle.current().pid() + "\n");
          return 0;
        }
        return 1;
      }
      case "ps" -> {
        print("USER            PID   PPID     VSZ    RSS WCHAN            ADDR S NAME\n");
        long rss = Runtime.getRuntime().totalMemory() >> 10;
        print(String.format("u0_a100      %6d      1       0 %6d 0                   0 S %s%n", ProcessHandle.current().pid(), rss, pkg()));
        return 0;
      }
      case "ls" -> {
        return ls(args);
      }
      case "cat" -> {
        int status = 0;
        for (String f : args) {
          Path p = Paths.host(f, cwd);
          if (p == null || !Files.isRegularFile(p)) {
            print("cat: " + f + ": No such file or directory\n");
            status = 1;
          } else {
            Files.copy(p, out);
          }
        }
        return status;
      }
      case "rm" -> {
        return rm(args);
      }
      case "mkdir" -> {
        int status = 0;
        for (String f : args) {
          if (f.startsWith("-")) continue;
          Path p = Paths.host(f, cwd);
          if (p == null) {
            if (!Paths.isDir(Paths.normalize(f, cwd))) {
              print("mkdir: '" + f + "': Read-only file system\n");
              status = 1;
            }
            continue;
          }
          Files.createDirectories(p);
        }
        return status;
      }
      case "touch" -> {
        for (String f : args) {
          Path p = Paths.host(f, cwd);
          if (p == null) continue;
          if (Files.exists(p)) Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
          else Files.createFile(p);
        }
        return 0;
      }
      case "dumpsys" -> {
        return dumpsys(args);
      }
      case "apksim" -> {
        // Any control command, as `ctl.py` or `drive.py` would send it: `adb shell apksim rotate left`.
        if (args.isEmpty()) return usage("apksim CONTROL-COMMAND [ARGS...] (see AGENTS.md)");
        return reply(control(args.toArray(new String[0])), true);
      }
      default -> {
        print("/system/bin/sh: " + cmd + ": inaccessible or not found\n");
        return 127;
      }
    }
  }

  private int getprop(List<String> args) throws IOException {
    Map<String, String> props = props();
    if (args.isEmpty()) {
      for (Map.Entry<String, String> e : props.entrySet()) print("[" + e.getKey() + "]: [" + e.getValue() + "]\n");
    } else {
      print(props.getOrDefault(args.get(0), args.size() > 1 ? args.get(1) : "") + "\n");
    }
    return 0;
  }

  /** What `getprop` reports: enough for adb, Android Studio and scripts to recognise a booted, debuggable device. */
  static Map<String, String> props() {
    Map<String, String> p = new TreeMap<>();
    Device d = Device.current();
    boolean booted = app() != null;
    if (booted) {
      int api = RuntimeEnvironment.getApiLevel();
      p.put("ro.build.version.sdk", String.valueOf(api));
      p.put("ro.build.version.release", android.os.Build.VERSION.RELEASE);
      p.put("ro.build.version.release_or_codename", android.os.Build.VERSION.RELEASE);
      p.put("sys.boot_completed", "1");
      p.put("dev.bootcomplete", "1");
      p.put("persist.sys.locale", app().getResources().getConfiguration().getLocales().get(0).toLanguageTag());
      p.put("persist.sys.timezone", java.util.TimeZone.getDefault().getID());
    }
    p.put("ro.build.version.codename", "REL");
    p.put("init.svc.bootanim", booted ? "stopped" : "running");
    p.put("ro.product.manufacturer", "apksim");
    p.put("ro.product.brand", "apksim");
    p.put("ro.product.name", "apksim");
    p.put("ro.product.model", "apksim_" + d.name());
    p.put("ro.product.device", "apksim_" + d.name());
    p.put("ro.hardware", "apksim");
    p.put("ro.product.cpu.abi", HostOs.apkAbi());
    p.put("ro.product.cpu.abilist", HostOs.apkAbi());
    p.put("ro.product.cpu.abilist64", HostOs.apkAbi());
    p.put("ro.build.characteristics", d.characteristics());
    p.put("ro.sf.lcd_density", String.valueOf(Math.round(d.density() * 160)));
    p.put("ro.serialno", "apksim-" + AdbServer.port);
    p.put("ro.boot.serialno", "apksim-" + AdbServer.port);
    p.put("ro.debuggable", "1");
    p.put("ro.secure", "0");
    p.put("ro.adb.secure", "0");
    p.put("service.adb.root", "1");
    p.put("ro.build.type", "userdebug");
    p.put("ro.build.tags", "test-keys");
    p.put("ro.build.display.id", "apksim");
    p.put("ro.build.fingerprint", "apksim/apksim/apksim_" + d.name() + ":REL/apksim/1:userdebug/test-keys");
    p.put("apksim.package", String.valueOf(System.getProperty("apksim.package")));
    p.putAll(SET_PROPS);
    return p;
  }

  // input tap|swipe|text|keyevent: through the control commands, so they queue with the rest of the scripted input.
  private int input(List<String> a) throws Exception {
    int i = 0;
    if (i < a.size() && a.get(i).matches("touchscreen|touchpad|touchnavigation|mouse|keyboard|stylus|trackball|dpad|gamepad|joystick|rotaryencoder")) i++;
    if (i + 1 < a.size() && a.get(i).equals("-d")) i += 2;
    if (i >= a.size()) return usage("input [SOURCE] tap X Y | swipe X1 Y1 X2 Y2 [MS] | text TEXT | keyevent [--longpress] KEY...");
    String op = a.get(i);
    List<String> r = a.subList(i + 1, a.size());
    switch (op) {
      case "tap" -> {
        if (r.size() < 2) return usage("input tap X Y");
        return reply(control("tap", r.get(0), r.get(1)), false);
      }
      case "swipe", "draganddrop" -> {
        if (r.size() < 4) return usage("input swipe X1 Y1 X2 Y2 [DURATION_MS]");
        List<String> c = new ArrayList<>(List.of("swipe"));
        c.addAll(r.subList(0, Math.min(5, r.size())));
        return reply(control(c.toArray(new String[0])), false);
      }
      case "text" -> {
        if (r.isEmpty()) return usage("input text TEXT");
        // As on a device: %s is a space (adb strips the quoting a real space would need).
        return reply(control("type", String.join(" ", r).replace("%s", " ")), false);
      }
      case "keyevent", "keycombination" -> {
        int status = 0;
        for (String k : r) {
          if (k.startsWith("--")) continue;
          if (k.equals("-t")) continue;
          status |= key(k);
        }
        return status;
      }
      default -> {
        print("Error: Unknown command: " + op + "\n");
        return 1;
      }
    }
  }

  private int key(String k) throws Exception {
    String name = k.toUpperCase(Locale.ROOT);
    if (name.startsWith("KEYCODE_")) name = name.substring(8);
    switch (name) {
      case "4", "BACK" -> {
        return reply(control("back"), false);
      }
      case "3", "HOME" -> {
        return reply(control("home"), false);
      }
      case "223", "SLEEP" -> {
        return reply(quiet(control("lock")), false);
      }
      case "224", "WAKEUP" -> {
        return reply(quiet(control("unlock")), false);
      }
      case "26", "POWER" -> {
        String r = control("lock");
        if (r.startsWith("already")) r = control("unlock");
        return reply(quiet(r), false);
      }
      case "82", "MENU" -> {
        // The classic "unlock the screen" key; with the screen on, it is the menu key.
        String r = control("unlock");
        return r.equals("not hidden") ? reply(control("key", "MENU"), false) : reply(quiet(r), false);
      }
      default -> {
        return reply(control("key", name), false);
      }
    }
  }

  private static String quiet(String reply) {
    return reply.startsWith("already") || reply.equals("not hidden") ? "ok" : reply;
  }

  /** The last receiver of an ordered broadcast: what `am broadcast` prints once the receivers are done. */
  private static final class ResultReceiver extends android.content.BroadcastReceiver {
    private final CompletableFuture<String> result;

    ResultReceiver(CompletableFuture<String> result) {
      this.result = result;
    }

    @Override
    public void onReceive(android.content.Context context, Intent intent) {
      String data = getResultData();
      android.os.Bundle extras = getResultExtras(false);
      result.complete("result=" + getResultCode() + (data != null ? ", data=\"" + data + "\"" : "")
          + (extras != null && !extras.isEmpty() ? ", extras: " + extras : ""));
    }

    // Robolectric's instrumented BroadcastReceiver has this, but javac does not see it (synthetic) and asks an abstract
    // class's subclass for it; what it returns is the field Robolectric added.
    public Object $$robo$getData() {
      try {
        return android.content.BroadcastReceiver.class.getField("__robo_data__").get(this);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** pm install sessions: id -> the folder their APK is written into. */
  private static final Map<Integer, Path> SESSIONS = new ConcurrentHashMap<>();
  private static final AtomicInteger SESSION_IDS = new AtomicInteger(1000);

  /** Copies SIZE bytes of standard input (all of it when SIZE is -1) into FILE: an APK streamed by `adb install`. */
  private void receive(Path file, long size) throws IOException {
    try (OutputStream o = Files.newOutputStream(file)) {
      byte[] buf = new byte[64 * 1024];
      long left = size < 0 ? Long.MAX_VALUE : size;
      while (left > 0) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
        if (n < 0) break;
        o.write(buf, 0, n);
        left -= n;
      }
    }
  }

  /** am instrument [-w] [-r] [-e KEY VALUE]... PKG/CLASS: see LiveInstrumentation. */
  private int instrument(List<String> a) throws Exception {
    boolean await = false;
    boolean raw = false;
    android.os.Bundle args = new android.os.Bundle();
    String target = null;
    for (int i = 0; i < a.size(); i++) {
      String o = a.get(i);
      switch (o) {
        case "-w" -> await = true;
        case "-r" -> raw = true;
        case "-e" -> {
          if (i + 2 >= a.size()) return usage("am instrument [-w] [-r] [-e KEY VALUE] PKG/CLASS");
          args.putString(a.get(i + 1), a.get(i + 2));
          i += 2;
        }
        case "-p", "--user", "--abi" -> i++;
        default -> {
          if (o.startsWith("-")) continue;  // --no-window-animation, --no-hidden-api-checks and the like
          target = o;
        }
      }
    }
    if (target == null) return usage("am instrument [-w] [-r] [-e KEY VALUE] PKG/CLASS");
    return LiveInstrumentation.start(target, args, raw, await, out, gone);
  }

  // am start|broadcast|startservice|force-stop
  private int am(List<String> a) throws Exception {
    if (a.isEmpty()) return usage("am start|broadcast|startservice|force-stop ...");
    String op = a.get(0);
    switch (op) {
      case "start", "start-activity" -> {
        Parsed p = intent(a.subList(1, a.size()));
        if (p == null) return 1;
        Intent in = p.intent;
        print("Starting: " + describe(in) + "\n");
        long t0 = System.nanoTime();
        String r;
        if (p.onlyView()) {
          // A plain deep link is the `open URL` control command.
          r = control("open", in.getDataString());
          if (r.startsWith("no activity")) r = "Error: Activity not started, unable to resolve " + describe(in);
        } else {
          r = start(in);
        }
        if (r.startsWith("Error")) {
          print(r + "\n");
          return 1;
        }
        if (p.wait) {
          long ms = (System.nanoTime() - t0) / 1_000_000;
          String target = r.startsWith("opening in ") ? r.substring(11) : r;
          print("Status: ok\nLaunchState: WARM\nActivity: " + target + "\nTotalTime: " + ms + "\nWaitTime: " + ms + "\nComplete\n");
        }
        return 0;
      }
      case "startservice", "start-service", "start-foreground-service", "startforegroundservice" -> {
        Parsed p = intent(a.subList(1, a.size()));
        if (p == null) return 1;
        Intent in = p.intent;
        print("Starting service: " + describe(in) + "\n");
        ComponentName c = onMain(() -> app().startService(in));
        if (c == null) {
          print("Error: Not found; no service started.\n");
          return 1;
        }
        return 0;
      }
      case "broadcast" -> {
        Parsed p = intent(a.subList(1, a.size()));
        if (p == null) return 1;
        Intent in = p.intent;
        // From the main thread (UiAutomation.executeShellCommand in a test), the receivers and the result come on this
        // thread's own queue, so it is run while waiting; inside a message's dispatch it cannot be, and nothing is sent.
        boolean main = Looper.getMainLooper().isCurrentThread();
        if (main && !TestActivities.canRunMainQueue()) {
          print("Error: am broadcast from the main thread inside a message cannot wait for its result: send it from"
              + " the test body or another thread\n");
          return 1;
        }
        print("Broadcasting: " + describe(in) + "\n");
        // Ordered, with a result receiver, as `am broadcast` sends it: a receiver's setResultCode/setResultData come back
        // (the Android CLI asks its instrumentation server for its version and socket this way).
        CompletableFuture<String> result = new CompletableFuture<>();
        onMain(() -> {
          app().sendOrderedBroadcast(in, null, new ResultReceiver(result), null, 0, null, null);
          return null;
        });
        if (main) TestActivities.runMainQueueUntil(result::isDone, TimeUnit.SECONDS.toNanos(10));
        String r;
        try {
          r = result.get(main ? 0 : 10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
          r = "result=0";
        }
        print("Broadcast completed: " + r + "\n");
        return 0;
      }
      case "instrument" -> {
        return instrument(a.subList(1, a.size()));
      }
      case "force-stop", "kill", "kill-all", "stop-app" -> {
        // The app is this process, so stopping it would end the device too.
        print("apksim: " + op + " is not supported: the app is this process (quit it with `adb shell apksim quit`)\n");
        return 0;
      }
      case "get-current-user" -> {
        print("0\n");
        return 0;
      }
      default -> {
        print("Error: Unknown command: " + op + "\n");
        return 1;
      }
    }
  }

  /** Starts an activity of the app (bringing it back from home or lock first), as `am start` does. */
  private String start(Intent in) throws Exception {
    String pkg = pkg();
    if (in.getComponent() != null && !in.getComponent().getPackageName().equals(pkg)) {
      return "Error: Activity class {" + in.getComponent().flattenToShortString() + "} does not exist.";
    }
    if (in.getComponent() == null && in.getPackage() == null) in.setPackage(pkg);
    ResolveInfo ri = onMain(() -> app().getPackageManager().resolveActivity(in, 0));
    if (ri == null || ri.activityInfo == null) return "Error: Activity not started, unable to resolve " + describe(in);
    control("resume");
    String target = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
    if (ri.activityInfo.name.equals(control("activity")) && in.getData() == null && in.getExtras() == null
        && (in.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
      // As on a device, launching what is already running brings it to the front instead of stacking a copy.
      print("Warning: Activity not started, its current task has been brought to the front\n");
      return target;
    }
    in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    onMain(() -> {
      app().startActivity(in);
      return null;
    });
    return target;
  }

  private static final class Parsed {
    Intent intent = new Intent();
    boolean wait;
    boolean other;  // anything beyond -a VIEW -d URL

    boolean onlyView() {
      return !other && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null && intent.getComponent() == null
          && intent.getType() == null;
    }
  }

  /** am's intent arguments: -a -d -t -c -n -p -f, --es/--ez/--ei/--el/--ef/--eu/--esn extras, a few flags, a trailing URI or package. */
  private Parsed intent(List<String> a) throws IOException {
    Parsed p = new Parsed();
    Intent in = p.intent;
    String data = null;
    String type = null;
    for (int i = 0; i < a.size(); i++) {
      String o = a.get(i);
      String v = i + 1 < a.size() ? a.get(i + 1) : null;
      try {
        switch (o) {
          case "-a" -> in.setAction(a.get(++i));
          case "-d" -> data = a.get(++i);
          case "-t" -> type = a.get(++i);
          case "-c" -> {
            in.addCategory(a.get(++i));
            p.other = true;
          }
          case "-n" -> {
            ComponentName c = ComponentName.unflattenFromString(a.get(++i));
            if (c == null) throw new IllegalArgumentException("Bad component name: " + v);
            in.setComponent(c);
          }
          case "-p" -> {
            in.setPackage(a.get(++i));
            p.other = true;
          }
          case "-f" -> {
            in.setFlags(Integer.decode(a.get(++i)));
            p.other = true;
          }
          case "-e", "--es" -> {
            in.putExtra(a.get(i + 1), a.get(i + 2));
            i += 2;
            p.other = true;
          }
          case "--esn" -> {
            in.putExtra(a.get(++i), (String) null);
            p.other = true;
          }
          case "--ez" -> {
            in.putExtra(a.get(i + 1), Boolean.parseBoolean(a.get(i + 2)) || a.get(i + 2).equals("1"));
            i += 2;
            p.other = true;
          }
          case "--ei" -> {
            in.putExtra(a.get(i + 1), Integer.decode(a.get(i + 2)));
            i += 2;
            p.other = true;
          }
          case "--el" -> {
            in.putExtra(a.get(i + 1), Long.decode(a.get(i + 2)));
            i += 2;
            p.other = true;
          }
          case "--ef" -> {
            in.putExtra(a.get(i + 1), Float.parseFloat(a.get(i + 2)));
            i += 2;
            p.other = true;
          }
          case "--ed" -> {
            in.putExtra(a.get(i + 1), Double.parseDouble(a.get(i + 2)));
            i += 2;
            p.other = true;
          }
          case "--eu" -> {
            in.putExtra(a.get(i + 1), Uri.parse(a.get(i + 2)));
            i += 2;
            p.other = true;
          }
          case "--ecn" -> {
            in.putExtra(a.get(i + 1), ComponentName.unflattenFromString(a.get(i + 2)));
            i += 2;
            p.other = true;
          }
          case "--activity-clear-top" -> in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
          case "--activity-single-top" -> in.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
          case "--activity-new-task" -> in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          case "--activity-clear-task" -> in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
          case "--activity-no-history" -> in.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
          case "--activity-reorder-to-front" -> in.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
          case "-W" -> p.wait = true;
          case "-S", "-D", "-N", "--receiver-foreground", "--receiver-registered-only", "--track-allocation", "--streaming" -> {
            // Restart, debugger and profiler options: nothing to do here.
          }
          case "--user", "-R", "-P", "--start-profiler", "--sampling", "--attach-agent", "--display", "--windowingMode",
              "--activityType", "--task", "--receiver-permission" -> i++;
          default -> {
            if (o.startsWith("-")) {
              print("Error: Unknown option: " + o + "\n");
              return null;
            }
            if (o.startsWith("intent:") || o.startsWith("#Intent")) {
              Intent parsed = Intent.parseUri(o, Intent.URI_INTENT_SCHEME);
              if (in.getAction() != null) parsed.setAction(in.getAction());
              p.intent = in = parsed;
              p.other = true;
            } else if (o.contains(":")) {
              data = o;
            } else {
              in.setPackage(o);
              p.other = true;
            }
          }
        }
      } catch (IndexOutOfBoundsException | NumberFormatException | java.net.URISyntaxException e) {
        print("Error: bad argument for " + o + ": " + e.getMessage() + "\n");
        return null;
      }
    }
    if (data != null && type != null) in.setDataAndType(Uri.parse(data), type);
    else if (data != null) in.setData(Uri.parse(data));
    else if (type != null) in.setType(type);
    return p;
  }

  private static String describe(Intent in) {
    StringBuilder b = new StringBuilder("Intent { ");
    if (in.getAction() != null) b.append("act=").append(in.getAction()).append(' ');
    if (in.getCategories() != null) b.append("cat=").append(in.getCategories()).append(' ');
    if (in.getDataString() != null) b.append("dat=").append(in.getDataString()).append(' ');
    if (in.getType() != null) b.append("typ=").append(in.getType()).append(' ');
    if (in.getFlags() != 0) b.append("flg=0x").append(Integer.toHexString(in.getFlags())).append(' ');
    if (in.getPackage() != null) b.append("pkg=").append(in.getPackage()).append(' ');
    if (in.getComponent() != null) b.append("cmp=").append(in.getComponent().flattenToShortString()).append(' ');
    if (in.getExtras() != null) b.append("(has extras) ");
    return b.append('}').toString();
  }

  private int pm(List<String> a) throws Exception {
    if (a.isEmpty()) return usage("pm list packages | path PKG | grant PKG PERM | revoke PKG PERM | clear PKG");
    String pkg = pkg();
    String apk = System.getProperty("apksim.apk");
    switch (a.get(0)) {
      case "list" -> {
        String what = a.size() > 1 ? a.get(1) : "";
        if (what.equals("packages")) {
          boolean files = false;
          boolean systemOnly = false;
          String filter = null;
          for (int i = 2; i < a.size(); i++) {
            String o = a.get(i);
            if (o.equals("-f")) files = true;
            else if (o.equals("-s")) systemOnly = true;
            else if (o.equals("--user")) i++;
            else if (!o.startsWith("-")) filter = o;
          }
          if (!systemOnly && (filter == null || pkg.contains(filter))) print("package:" + (files ? apk + "=" : "") + pkg + "\n");
          for (Packages.Installed p : Packages.all()) {
            if (!systemOnly && (filter == null || p.pkg().contains(filter))) print("package:" + (files ? p.apk() + "=" : "") + p.pkg() + "\n");
          }
          return 0;
        }
        if (what.equals("instrumentation")) {
          for (Packages.Installed p : Packages.all()) {
            print("instrumentation:" + p.pkg() + "/" + p.instrumentation() + " (target=" + p.target() + ")\n");
          }
          return 0;
        }
        if (what.equals("users")) {
          print("Users:\n\tUserInfo{0:Owner:c13} running\n");
          return 0;
        }
        if (what.equals("features") || what.equals("libraries") || what.equals("permissions") || what.equals("instrumentation")) return 0;
        print("Error: unknown list type '" + what + "'\n");
        return 1;
      }
      case "path" -> {
        if (a.size() > 1 && a.get(a.size() - 1).equals(pkg)) {
          print("package:" + apk + "\n");
          return 0;
        }
        Packages.Installed p = a.size() > 1 ? Packages.get(a.get(a.size() - 1)) : null;
        if (p != null) {
          print("package:" + p.apk() + "\n");
          return 0;
        }
        return 1;
      }
      case "install" -> {
        // pm install [-r -t -g -d ...] PATH, or pm install -S SIZE (the APK on standard input).
        long size = -1;
        String path = null;
        for (int i = 1; i < a.size(); i++) {
          String o = a.get(i);
          if (o.equals("-S")) size = Long.parseLong(a.get(++i));
          else if (o.equals("-i") || o.equals("--user") || o.equals("--install-location") || o.equals("--abi") || o.equals("-p")) i++;
          else if (!o.startsWith("-")) path = o;
        }
        Path file;
        boolean temp = false;
        if (size >= 0) {
          file = Files.createTempFile("adb-install", ".apk");
          temp = true;
          receive(file, size);
        } else {
          file = path == null ? null : Paths.host(path, cwd);
          if (file == null || !Files.isRegularFile(file)) {
            print("Failure [INSTALL_FAILED_INVALID_URI: " + path + "]\n");
            return 1;
          }
        }
        try {
          String r = Packages.install(file);
          print(r + "\n");
          return r.equals("Success") ? 0 : 1;
        } finally {
          if (temp) Files.deleteIfExists(file);
        }
      }
      case "install-create" -> {
        int id = SESSION_IDS.incrementAndGet();
        Path dir = Files.createTempDirectory("adb-session");
        SESSIONS.put(id, dir);
        print("Success: created install session [" + id + "]\n");
        return 0;
      }
      case "install-write" -> {
        // pm install-write [-S SIZE] SESSION NAME PATH|-
        long size = -1;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < a.size(); i++) {
          if (a.get(i).equals("-S")) size = Long.parseLong(a.get(++i));
          else rest.add(a.get(i));
        }
        Path dir = rest.isEmpty() ? null : SESSIONS.get(Integer.parseInt(rest.get(0)));
        if (dir == null || rest.size() < 2) {
          print("Error: invalid session\n");
          return 1;
        }
        String name = rest.get(1).replace('/', '_');
        if (!name.endsWith(".apk")) name += ".apk";
        Path to = dir.resolve(name);
        if (rest.size() < 3 || rest.get(2).equals("-")) {
          receive(to, size);
        } else {
          Path from = Paths.host(rest.get(2), cwd);
          if (from == null || !Files.isRegularFile(from)) {
            print("Error: Unable to open file: " + rest.get(2) + "\n");
            return 1;
          }
          Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        print("Success: streamed " + Files.size(to) + " bytes\n");
        return 0;
      }
      case "install-commit" -> {
        Path dir = a.size() > 1 ? SESSIONS.remove(Integer.parseInt(a.get(1))) : null;
        if (dir == null) {
          print("Error: invalid session\n");
          return 1;
        }
        try (Stream<Path> files = Files.list(dir)) {
          List<Path> apks = files.toList();
          String r = apks.size() == 1 ? Packages.install(apks.get(0))
              : "Failure [INSTALL_FAILED_INTERNAL_ERROR: apksim installs one APK per session, not " + apks.size() + " (no split APKs)]";
          print(r + "\n");
          return r.equals("Success") ? 0 : 1;
        } finally {
          Storage.deleteTree(dir);
        }
      }
      case "install-abandon", "install-destroy" -> {
        Path dir = a.size() > 1 ? SESSIONS.remove(Integer.parseInt(a.get(1))) : null;
        if (dir != null) Storage.deleteTree(dir);
        print("Success\n");
        return 0;
      }
      case "uninstall" -> {
        String which = a.get(a.size() - 1);
        if (which.equals(pkg)) {
          print("Failure [DELETE_FAILED_INTERNAL_ERROR: " + pkg + " is the app this instance runs]\n");
          return 1;
        }
        if (LiveInstrumentation.running(which) != null) {
          print("Failure [DELETE_FAILED_INTERNAL_ERROR: " + which + " is running its instrumentation]\n");
          return 1;
        }
        boolean removed = Packages.uninstall(which);
        print(removed ? "Success\n" : "Failure [DELETE_FAILED_INTERNAL_ERROR]\n");
        return removed ? 0 : 1;
      }
      case "grant", "revoke" -> {
        if (a.size() < 3) return usage("pm " + a.get(0) + " PACKAGE PERMISSION");
        int i = 1;
        if (a.get(i).equals("--user")) i += 2;
        if (!a.get(i).equals(pkg)) {
          print("Exception occurred while executing '" + a.get(0) + "': java.lang.IllegalArgumentException: Unknown package: " + a.get(i) + "\n");
          return 1;
        }
        List<String> c = new ArrayList<>(List.of("permission", a.get(0)));
        c.addAll(a.subList(i + 1, a.size()));
        return reply(control(c.toArray(new String[0])), false);
      }
      case "resolve-activity" -> {
        boolean brief = a.contains("--brief");
        if (!a.get(a.size() - 1).equals(pkg)) {
          print("No activity found\n");
          return 1;
        }
        String launcher = System.getProperty("apksim.launcher");
        if (brief) print("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" + pkg + "/" + launcher + "\n");
        else print("Activity Resolver Table:\n  " + pkg + "/" + launcher + "\n");
        return 0;
      }
      case "clear" -> {
        // A device kills the app's process and deletes its data, and the device stays up. Here the instance is both
        // the process and the device, and the app's open files (databases) would outlive a delete underneath it.
        print("Failed: APKSIM_RUNNING: the app's data is in use by this instance; relaunch it with --fresh, or quit it and"
            + " run scripts/ctl.py erase " + pkg + "\n");
        return 1;
      }
      case "install-multiple" -> {
        print("Failure [APKSIM_ONE_APP: an apksim instance runs the one APK it was launched with; beside it, it installs"
            + " only a single APK that brings an instrumentation]\n");
        return 1;
      }
      default -> {
        print("Unknown command: " + a.get(0) + "\n");
        return 1;
      }
    }
  }

  private int wm(List<String> a) throws IOException, InterruptedException {
    Device d = Device.current();
    String op = a.isEmpty() ? "" : a.get(0);
    switch (op) {
      case "size" -> {
        // wm size WxH (pixels) resizes the display as dragging the window does; reset goes back to the preset.
        if (a.size() > 1) {
          String[] wh = a.get(1).split("x");
          if (!a.get(1).equals("reset") && wh.length != 2) return usage("wm size [WxH|reset]");
          // A device's name is also its preset size (a custom one is "WxH@density"), so reset is `device NAME`.
          String r = a.get(1).equals("reset") ? control("device", d.name())
              : control("size", Math.round(Integer.parseInt(wh[0]) / d.density()) + "x" + Math.round(Integer.parseInt(wh[1]) / d.density()));
          return reply(r, false);
        }
        Device preset = Device.named(d.name());
        print("Physical size: " + preset.widthPx() + "x" + preset.heightPx() + "\n");
        if (!preset.equals(d)) print("Override size: " + d.widthPx() + "x" + d.heightPx() + "\n");
        return 0;
      }
      case "density" -> {
        if (a.size() > 1 && !a.get(1).equals("reset")) return unsupported("wm density N");
        print("Physical density: " + Math.round(d.density() * 160) + "\n");
        return 0;
      }
      default -> {
        return usage("wm size | wm density");
      }
    }
  }

  // settings get|put|delete NAMESPACE KEY [VALUE]: animator_duration_scale and font_scale are control commands;
  // anything else is written to the app's Settings provider.
  private int settings(List<String> a) throws Exception {
    int i = 0;
    if (i + 1 < a.size() && a.get(i).equals("--user")) i += 2;
    if (a.size() - i < 3) return usage("settings get|put|delete global|system|secure KEY [VALUE]");
    String op = a.get(i);
    String ns = a.get(i + 1);
    String key = a.get(i + 2);
    String value = a.size() > i + 3 ? a.get(i + 3) : null;
    if (!ns.matches("global|system|secure")) {
      print("Invalid namespace '" + ns + "'\n");
      return 1;
    }
    switch (op) {
      case "get" -> {
        String v = onMain(() -> get(ns, key));
        print((v == null ? "null" : v) + "\n");
        return 0;
      }
      case "put" -> {
        if (value == null) return usage("settings put NAMESPACE KEY VALUE");
        if (key.equals(Settings.Global.ANIMATOR_DURATION_SCALE)) return reply(control("animscale", value), false);
        if (key.equals(Settings.System.FONT_SCALE)) return reply(control("fontscale", value), false);
        onMain(() -> {
          put(ns, key, value);
          return null;
        });
        return 0;
      }
      case "delete" -> {
        onMain(() -> {
          put(ns, key, null);
          return null;
        });
        print("Deleted 1 rows\n");
        return 0;
      }
      default -> {
        return usage("settings get|put|delete NAMESPACE KEY [VALUE]");
      }
    }
  }

  private static String get(String ns, String key) {
    android.content.ContentResolver cr = app().getContentResolver();
    return switch (ns) {
      case "global" -> Settings.Global.getString(cr, key);
      case "system" -> Settings.System.getString(cr, key);
      default -> Settings.Secure.getString(cr, key);
    };
  }

  private static void put(String ns, String key, String value) {
    android.content.ContentResolver cr = app().getContentResolver();
    Uri uri;
    switch (ns) {
      case "global" -> {
        Settings.Global.putString(cr, key, value);
        uri = Settings.Global.getUriFor(key);
      }
      case "system" -> {
        Settings.System.putString(cr, key, value);
        uri = Settings.System.getUriFor(key);
      }
      default -> {
        Settings.Secure.putString(cr, key, value);
        uri = Settings.Secure.getUriFor(key);
      }
    }
    cr.notifyChange(uri, null);
  }

  /** `cmd SERVICE ...`: the services that have a command line above. */
  private int cmd(List<String> a) throws Exception {
    if (a.isEmpty()) return usage("cmd SERVICE ...");
    List<String> r = a.subList(1, a.size());
    switch (a.get(0)) {
      case "package" -> {
        return pm(r);
      }
      case "activity" -> {
        return am(r);
      }
      case "window" -> {
        return wm(r);
      }
      case "settings" -> {
        return settings(r);
      }
      case "input" -> {
        return input(r);
      }
      case "uimode" -> {
        // cmd uimode night yes|no
        if (r.size() >= 2 && r.get(0).equals("night")) {
          String mode = r.get(1).equals("yes") ? "dark" : r.get(1).equals("no") ? "light" : null;
          if (mode == null) return unsupported("cmd uimode night " + r.get(1));
          return reply(control("appearance", mode), false);
        }
        return usage("cmd uimode night yes|no");
      }
      default -> {
        print("Can't find service: " + a.get(0) + "\n");
        return 1;
      }
    }
  }

  private int monkey(List<String> a) throws Exception {
    // monkey -p PKG [-c android.intent.category.LAUNCHER] 1: the usual "launch the app" incantation.
    int i = a.indexOf("-p");
    if (i < 0 || i + 1 >= a.size() || !a.get(i + 1).equals(pkg())) {
      print("** No activities found to run, monkey aborted.\n");
      return 1;
    }
    Intent in = onMain(() -> app().getPackageManager().getLaunchIntentForPackage(pkg()));
    if (in == null) {
      print("** No activities found to run, monkey aborted.\n");
      return 1;
    }
    String r = start(in);
    if (r.startsWith("Error")) {
      print(r + "\n");
      return 1;
    }
    print("Events injected: 1\n");
    return 0;
  }

  private int dumpsys(List<String> a) throws Exception {
    String service = a.isEmpty() ? "" : a.get(0);
    switch (service) {
      case "activity" -> {
        if (a.size() > 1 && (a.get(1).equals("activities") || a.get(1).equals("top") || a.get(1).equals("a"))) {
          String top = control("activity");
          print("  mResumedActivity: ActivityRecord{0 u0 " + pkg() + "/" + top + " t1}\n");
          print("  ResumedActivity: ActivityRecord{0 u0 " + pkg() + "/" + top + " t1}\n");
          return 0;
        }
        print("apksim: dumpsys activity supports `activities` and `top`\n");
        return 0;
      }
      case "window" -> {
        String top = control("activity");
        print("  mCurrentFocus=Window{0 u0 " + pkg() + "/" + top + "}\n");
        print("  mFocusedApp=ActivityRecord{0 u0 " + pkg() + "/" + top + " t1}\n");
        return 0;
      }
      default -> {
        print("Can't find service: " + service + "\n");
        return 0;
      }
    }
  }

  // logcat [-c] [-d] [-t N] [-T N] [-s] [-e REGEX] [TAG:LEVEL...]: the app's android.util.Log lines (AdbLog).
  private int logcat(List<String> a) throws Exception {
    AdbLog.install();
    boolean dump = false;
    boolean clear = false;
    int tail = -1;
    char fallback = 'V';
    Map<String, Character> levels = new HashMap<>();
    Pattern regex = null;
    for (int i = 0; i < a.size(); i++) {
      String o = a.get(i);
      switch (o) {
        case "-c", "--clear" -> clear = true;
        case "-d" -> dump = true;
        case "-s" -> fallback = 'S';
        case "-t", "-T" -> {
          String v = a.get(++i);
          if (v.matches("[0-9]+")) tail = Integer.parseInt(v);
          if (o.equals("-t")) dump = true;
        }
        case "-e", "--regex" -> regex = Pattern.compile(a.get(++i));
        case "-v", "-b", "-m", "--max-count", "-f", "-r", "-n", "-G", "--pid", "--format", "--buffer" -> i++;
        case "-g" -> {
          print("main: ring buffer is " + 20_000 + " lines, " + AdbLog.since(0, 0).lines().size() + " used\n");
          return 0;
        }
        default -> {
          if (o.startsWith("--regex=")) regex = Pattern.compile(o.substring(8));
          else if (o.startsWith("-")) continue;  // -B, -D, --pid=..., formats: printed the same here
          else {
            int colon = o.lastIndexOf(':');
            String tag = colon < 0 ? o : o.substring(0, colon);
            char level = colon < 0 ? 'V' : Character.toUpperCase(o.charAt(colon + 1));
            if (tag.equals("*")) fallback = level;
            else levels.put(tag, level);
          }
        }
      }
    }
    if (clear) {
      AdbLog.clear();
      return 0;
    }
    if (inProcess) dump = true;
    long from = tail >= 0 ? Math.max(0, AdbLog.next() - tail) : 0;
    while (true) {
      AdbLog.Batch b = AdbLog.since(from, dump ? 0 : 500);
      for (AdbLog.Line l : b.lines()) {
        if (rank(l.level()) < rank(levels.getOrDefault(l.tag(), fallback))) continue;
        if (regex != null && !regex.matcher(l.text()).find()) continue;
        print(l.text() + "\n");
      }
      from = b.next();
      out.flush();  // also how a closed stream is noticed while nothing is logged
      if (dump) return 0;
    }
  }

  private static int rank(char level) {
    int r = "VDIWEFS".indexOf(level == 'A' ? 'F' : level);
    return r < 0 ? 0 : r;
  }

  // screencap [-p] [FILE]: the app's pixels as Host's `shot` saves them; raw RGBA unless -p (or a .png file).
  private int screencap(List<String> a) throws Exception {
    boolean png = false;
    String file = null;
    for (int i = 0; i < a.size(); i++) {
      String o = a.get(i);
      if (o.equals("-p")) png = true;
      else if (o.equals("-d")) i++;
      else if (!o.startsWith("-")) file = o;
    }
    Path shot = Path.of(System.getProperty("java.io.tmpdir"), "screencap-" + SHOTS.incrementAndGet() + ".png");
    try {
      String r = control("shot", shot.toString());
      if (!r.equals("ok")) {
        print(r + "\n");
        return 1;
      }
      byte[] bytes = Files.readAllBytes(shot);
      if (file != null) {
        png |= file.endsWith(".png");
        Path p = Paths.host(file, cwd);
        if (p == null) {
          print("Error opening file: " + file + " (Read-only file system)\n");
          return 1;
        }
        Files.write(p, png ? bytes : raw(bytes));
      } else {
        out.write(png ? bytes : raw(bytes));
      }
      return 0;
    } finally {
      Files.deleteIfExists(shot);
    }
  }

  /** screencap's raw format: width, height, format (1 = RGBA_8888) and dataspace, then the pixels. */
  private static byte[] raw(byte[] png) throws IOException {
    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    int w = img.getWidth();
    int h = img.getHeight();
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16 + w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.putInt(w).putInt(h).putInt(1).putInt(0);
    int[] row = new int[w];
    for (int y = 0; y < h; y++) {
      img.getRGB(0, y, w, 1, row, 0, w);
      for (int argb : row) b.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }
    return b.array();
  }

  private int ls(List<String> a) throws IOException {
    boolean longForm = false;
    boolean all = false;
    boolean dirOnly = false;
    List<String> paths = new ArrayList<>();
    for (String o : a) {
      if (o.startsWith("-") && o.length() > 1) {
        longForm |= o.contains("l");
        all |= o.contains("a");
        dirOnly |= o.contains("d");
      } else {
        paths.add(o);
      }
    }
    if (paths.isEmpty()) paths.add(cwd);
    int status = 0;
    for (String path : paths) {
      String dev = Paths.normalize(path, cwd);
      Path host = Paths.host(dev, "/");
      int[] st = Paths.stat(dev, host);
      if (st[0] == 0) {
        print("ls: " + path + ": No such file or directory\n");
        status = 1;
        continue;
      }
      if (paths.size() > 1 && (st[0] & 0040000) != 0 && !dirOnly) print(path + ":\n");
      List<Paths.Entry> entries = (st[0] & 0040000) != 0 && !dirOnly ? Paths.list(dev)
          : List.of(new Paths.Entry(path, st[0], st[1], st[2]));
      for (Paths.Entry e : entries) {
        if (!all && e.name().startsWith(".")) continue;
        if (longForm) {
          String perms = ((e.mode() & 0040000) != 0 ? "d" : "-") + rwx(e.mode() >> 6) + rwx(e.mode() >> 3) + rwx(e.mode());
          String when = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(e.mtime() * 1000L));
          print(String.format("%s 1 root root %8d %s %s%n", perms, e.size(), when, e.name()));
        } else {
          print(e.name() + "\n");
        }
      }
    }
    return status;
  }

  private static String rwx(int bits) {
    return ((bits & 4) != 0 ? "r" : "-") + ((bits & 2) != 0 ? "w" : "-") + ((bits & 1) != 0 ? "x" : "-");
  }

  private int rm(List<String> a) throws IOException {
    boolean recursive = false;
    boolean force = false;
    int status = 0;
    for (String o : a) {
      if (o.startsWith("-") && o.length() > 1) {
        recursive |= o.contains("r") || o.contains("R");
        force |= o.contains("f");
        continue;
      }
      String dev = Paths.normalize(o, cwd);
      Path p = Paths.host(dev, "/");
      if (p == null || Paths.isMountRoot(dev)) {
        if (!force || Paths.isDir(dev)) {
          print("rm: " + o + ": " + (Paths.isDir(dev) ? "Is a directory" : "No such file or directory") + "\n");
          status = 1;
        }
        continue;
      }
      if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        if (!force) {
          print("rm: " + o + ": No such file or directory\n");
          status = 1;
        }
        continue;
      }
      if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !recursive) {
        print("rm: " + o + ": Is a directory\n");
        status = 1;
        continue;
      }
      Storage.deleteTree(p);
    }
    return status;
  }

  // ---------------------------------------------------------------- helpers

  private void print(String s) throws IOException {
    out.write(s.getBytes(StandardCharsets.UTF_8));
  }

  private int usage(String s) throws IOException {
    print("usage: " + s + "\n");
    return 1;
  }

  private int unsupported(String what) throws IOException {
    print("apksim: " + what + " is not supported\n");
    return 1;
  }

  /** Prints a control command's answer unless it is a plain "ok" (device tools print nothing on success). */
  private int reply(String r, boolean always) throws IOException {
    boolean failed = r.startsWith("error") || r.startsWith("unknown") || r.startsWith("usage") || r.startsWith("no ");
    if (always || failed || !r.equals("ok")) {
      if (!always && !failed && (r.startsWith("ok") || r.startsWith("opening"))) return 0;
      print(r + "\n");
    }
    return failed ? 1 : 0;
  }

  /**
   * Runs a control command on the Android main thread, exactly as `--control` would, and waits for its answer. From
   * the main thread itself it runs at once (the Host loop that takes the queue is that thread). A test run has no Host
   * loop: the few commands a test's shell calls need are done here (see {@link #testRunControl}).
   */
  static String control(String... args) throws InterruptedException {
    try {
      if (TestActivities.testRun()) return testRunControl(args);
      if (Looper.getMainLooper().isCurrentThread()) {
        String r = Host.runOnMain(args);
        if (r != null) return r;
      }
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      return "error: " + e;
    }
    Control.Pending p = new Control.Pending(args);
    Control.QUEUE.add(p);
    return p.await();
  }

  /** Control commands in a test run, where there is no Host or device controls: what a test's shell calls reach. */
  private static String testRunControl(String[] a) {
    switch (a[0]) {
      case "animscale" -> {
        DeviceControls.setAnimatorScale(app(), a.length < 2 ? 1f : Float.parseFloat(a[1]));
        return "ok";
      }
      case "fontscale" -> {
        RuntimeEnvironment.setFontScale(Float.parseFloat(a[1]));
        return "ok";
      }
      case "network" -> {
        return dev.apksim.net.Net.command(a);
      }
      case "bars" -> {
        return SystemBars.command(a);
      }
      default -> {
        return "error: " + a[0] + " is not available in a test run";
      }
    }
  }

  /** Runs code on the Android main thread (the Host loop runs the looper's messages) and waits for it. */
  static <T> T onMain(Callable<T> c) throws Exception {
    if (app() == null) throw new IllegalStateException("the app has not started yet");
    if (Looper.getMainLooper().isCurrentThread()) return c.call();
    CompletableFuture<T> f = new CompletableFuture<>();
    new Handler(Looper.getMainLooper()).post(() -> {
      try {
        f.complete(c.call());
      } catch (Throwable t) {
        f.completeExceptionally(t);
      }
    });
    try {
      return f.get(30, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      throw e.getCause() instanceof Exception x ? x : e;
    }
  }

  static Application app() {
    return RuntimeEnvironment.getApplication();
  }

  static String pkg() {
    return System.getProperty("apksim.package");
  }

  // ---------------------------------------------------------------- files

  /**
   * The device's file system as adb sees it: /data/data/PKG (and /data/user/0/PKG) is the app's data folder,
   * /sdcard (and /storage/emulated/0) its external storage, /data/local/tmp a scratch folder of this instance. The
   * folders above them exist only as names to list; nothing else is visible.
   */
  static final class Paths {
    record Entry(String name, int mode, int size, int mtime) {}

    private static final int DIR = 0040755;
    private static final int FILE = 0100644;

    private Paths() {}

    static String normalize(String path, String cwd) {
      String p = path.startsWith("/") ? path : cwd + "/" + path;
      ArrayDeque<String> parts = new ArrayDeque<>();
      for (String s : p.split("/")) {
        if (s.isEmpty() || s.equals(".")) continue;
        if (s.equals("..")) parts.pollLast();
        else parts.addLast(s);
      }
      return "/" + String.join("/", parts);
    }

    private static Map<String, Path> mounts() throws IOException {
      Map<String, Path> m = new HashMap<>();
      Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "data-local-tmp");
      Files.createDirectories(tmp);
      m.put("/data/local/tmp", tmp);
      Application app = app();
      if (app != null) {
        Path data = app.getDataDir().toPath();
        m.put("/data/data/" + pkg(), data);
        m.put("/data/user/0/" + pkg(), data);
        Path sd = android.os.Environment.getExternalStorageDirectory().toPath();
        for (String s : List.of("/sdcard", "/storage/emulated/0", "/storage/self/primary", "/mnt/sdcard")) m.put(s, sd);
      }
      return m;
    }

    /** Names that only hold other names: listing / shows data, sdcard, storage. */
    private static Map<String, List<String>> virtual() {
      String pkg = pkg();
      Map<String, List<String>> v = new HashMap<>();
      v.put("/", List.of("data", "mnt", "sdcard", "storage"));
      v.put("/data", List.of("data", "local", "user"));
      v.put("/data/data", List.of(pkg));
      v.put("/data/user", List.of("0"));
      v.put("/data/user/0", List.of(pkg));
      v.put("/data/local", List.of("tmp"));
      v.put("/mnt", List.of("sdcard"));
      v.put("/storage", List.of("emulated", "self"));
      v.put("/storage/emulated", List.of("0"));
      v.put("/storage/self", List.of("primary"));
      return v;
    }

    /** The host file for a device path, or null if it is not inside one of the mapped folders. */
    static Path host(String path, String cwd) throws IOException {
      String dev = normalize(path, cwd);
      for (Map.Entry<String, Path> m : mounts().entrySet()) {
        String root = m.getKey();
        if (dev.equals(root)) return m.getValue();
        if (dev.startsWith(root + "/")) return m.getValue().resolve(dev.substring(root.length() + 1));
      }
      return null;
    }

    static boolean isMountRoot(String dev) throws IOException {
      return mounts().containsKey(dev);
    }

    static boolean isDir(String dev) throws IOException {
      if (virtual().containsKey(dev)) return true;
      Path p = host(dev, "/");
      return p != null && Files.isDirectory(p);
    }

    /** mode, size, mtime (seconds) as sync's STAT reports them; mode 0 if there is nothing there. */
    static int[] stat(String dev, Path host) throws IOException {
      String d = normalize(dev, "/");
      if (virtual().containsKey(d)) return new int[] {DIR, 4096, (int) (System.currentTimeMillis() / 1000)};
      if (host == null || !Files.exists(host)) return new int[] {0, 0, 0};
      BasicFileAttributes at = Files.readAttributes(host, BasicFileAttributes.class);
      return new int[] {at.isDirectory() ? DIR : FILE, (int) Math.min(at.size(), Integer.MAX_VALUE), (int) at.lastModifiedTime().to(TimeUnit.SECONDS)};
    }

    static List<Entry> list(String dev) throws IOException {
      String d = normalize(dev, "/");
      List<Entry> out = new ArrayList<>();
      List<String> names = virtual().get(d);
      if (names != null) {
        int now = (int) (System.currentTimeMillis() / 1000);
        for (String n : names) out.add(new Entry(n, DIR, 4096, now));
        return out;
      }
      Path p = host(d, "/");
      if (p == null || !Files.isDirectory(p)) return out;
      try (Stream<Path> s = Files.list(p)) {
        for (Path c : (Iterable<Path>) s.sorted()::iterator) {
          int[] st = stat(d + "/" + c.getFileName(), c);
          out.add(new Entry(c.getFileName().toString(), st[0], st[1], st[2]));
        }
      }
      return out;
    }
  }
}
