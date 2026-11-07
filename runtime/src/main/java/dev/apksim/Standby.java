package dev.apksim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * A pre-booted instance, as Android's zygote is a pre-booted app process. Started in the background by the launcher
 * ({@code apksim --standby SOCKET}), it boots the JVM and the framework (Robolectric's sandbox, the native runtime, a
 * throwaway application: {@link StandbyWarmup}), then waits on a Unix socket. The next launch from the same folder
 * hands it the APK and its own stdin, stdout and stderr (native/apksim-client.c), and this process becomes that
 * instance, minus the half of a cold start that does not depend on the APK. No app code runs before the launch.
 *
 * <p>Each instance starts the next standby once its first frame is up ({@link #spawnNext}). A standby quits when
 * apksim.jar changes (it would run the old build) and after {@code -Dapksim.standbyIdle} seconds unused.
 */
final class Standby {
  private static boolean adopted;
  /** Set before the client is told the exit code: its hanging up is then expected, not an interrupted launch. */
  private static volatile boolean exiting;
  private static boolean spawned;

  private Standby() {}

  static boolean requested() {
    return System.getProperty("apksim.standby") != null;
  }

  /** Boots, waits for a launch, and returns its arguments for {@link Main} (the APK). */
  static String[] await() throws Exception {
    String sock = System.getProperty("apksim.standby");
    Path pidFile = Path.of(sock + ".pid");
    long jar = jarStamp();
    // The standby's own log counts from its process start.
    Timing.restart(ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis()));
    Timing.mark("standby-boot");
    Result r = new JUnitCore().run(Request.aClass(StandbyWarmup.class));
    for (Failure f : r.getFailures()) f.getException().printStackTrace();
    if (!r.wasSuccessful() || r.getRunCount() == 0) {
      System.err.println("[apksim] standby: warm-up failed, quitting");
      quit(pidFile, sock);
    }
    awaitCompilerIdle();
    // Launch work that needs no APK: sweeping dead instances' scratch, and the window's library (Cocoa).
    Storage.initScratch();
    SimWindow.preload();
    System.gc();
    System.load(HostOs.runtimeLib("libstandby").toString());
    int listen = nativeListen(sock);
    if (listen < 0) {
      System.err.println("[apksim] standby: cannot listen on " + sock + " (errno " + -listen + ")");
      quit(pidFile, null);
    }
    Timing.mark("standby-ready");
    long idleMs = Long.getLong("apksim.standbyIdle", 3600) * 1000;
    long until = System.currentTimeMillis() + idleMs;
    while (true) {
      byte[] msg = nativeAccept(listen, 2000);
      boolean stale = jarStamp() != jar || !ownsPidFile(pidFile);
      if (msg == null) {
        if (stale || System.currentTimeMillis() > until) quit(pidFile, sock);
        continue;
      }
      List<String> parts = split(msg);
      if (stale || parts.size() < 3) {
        // An old build (or a malformed message): the client launches cold.
        nativeDecline();
        if (stale) quit(pidFile, sock);
        continue;
      }
      // Nobody else may find this standby now: drop its name before telling the client it is taken.
      Files.deleteIfExists(Path.of(sock));
      Files.deleteIfExists(pidFile);
      nativeClose(listen);
      if (!nativeAdopt()) Runtime.getRuntime().halt(1);  // the client went away
      adopted = true;
      // Relative paths (in control commands, say) are the launch's, wherever this JVM started.
      if (nativeChdir(parts.get(1))) System.setProperty("user.dir", parts.get(1));
      System.clearProperty("apksim.standby");
      Timing.restart(Long.parseLong(parts.get(0)));
      List<String> args = new ArrayList<>();
      for (String a : parts.subList(2, parts.size())) {
        int eq = a.indexOf('=');
        if (a.startsWith("-D") && eq > 2) System.setProperty(a.substring(2, eq), a.substring(eq + 1)); else args.add(a);
      }
      // Options a class read while this standby booted (volatile, so they can be read again).
      SoftKeyboard.enabled = Boolean.getBoolean("apksim.softKeyboard");
      Biometrics.enrolled = Boolean.getBoolean("apksim.biometricsEnrolled");
      // --max-fps, --video, --audio: the warm-up read the standby's own; the app's onCreate must see the launch's.
      Features.load();
      Timing.mark("handoff");
      Thread hangup = new Thread(() -> {
        nativeAwaitHangup();
        // Told the exit code, the client hangs up: halt goes on (ApkCache.beforeExit's sweep) and ends the process.
        if (exiting) return;
        // The launch was interrupted (Ctrl-C, terminal closed, killed): the instance goes with it.
        SlotDevice.flush();
        ClassCache.flush();
        Storage.deleteScratch();
        Runtime.getRuntime().halt(130);
      }, "apksim-client");
      hangup.setDaemon(true);
      hangup.start();
      // An app's own System.exit: the code is not known here, so the client gets 0.
      Runtime.getRuntime().addShutdownHook(new Thread(() -> nativeSendExit(0)));
      return args.toArray(new String[0]);
    }
  }

  /**
   * Waits until the JIT has compiled what the warm-up made hot (no compilation for 300 ms, at most 10 s), so a launch
   * finds the compiler threads free for the app's own methods.
   */
  private static void awaitCompilerIdle() {
    if (!Boolean.parseBoolean(System.getProperty("apksim.standbyJitIdle", "true"))) return;
    var jit = java.lang.management.ManagementFactory.getCompilationMXBean();
    if (jit == null || !jit.isCompilationTimeMonitoringSupported()) return;
    long last = -1;
    long quietSince = System.nanoTime();
    long until = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < until) {
      long t = jit.getTotalCompilationTime();
      if (t != last) {
        last = t;
        quietSince = System.nanoTime();
      } else if (System.nanoTime() - quietSince > 300_000_000L) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        return;
      }
    }
    Timing.mark("standby-jit-idle");
  }

  /**
   * As {@link #halt}, for a crash: every step best-effort (an OutOfMemoryError may be why it ends), and whatever fails,
   * the client still gets the exit code and the process halts with it.
   */
  static void haltNow(int code) {
    try {
      dev.apksim.net.Net.flush();
    } catch (Throwable ignored) {
      // A recording in progress loses its end.
    }
    try {
      spawnNext();
    } catch (Throwable ignored) {
      // The next launch starts one.
    }
    try {
      System.err.flush();
      System.out.flush();
    } catch (Throwable ignored) {
      // Ending anyway.
    }
    try {
      exiting = true;
      if (adopted) nativeSendExit(code);
    } catch (Throwable ignored) {
      // The client then reports that the instance died without an exit code.
    } finally {
      Runtime.getRuntime().halt(code);
    }
  }

  /** Ends the process, passing the exit code to the launcher's client when this instance came from a standby. */
  static void halt(int code) {
    dev.apksim.net.Net.flush();  // a recording in progress
    spawnNext();
    System.err.flush();
    System.out.flush();
    // A short launch's cache sweep (at most once an hour), before the client is told: once told, the next launch may
    // start while this process still holds its data slot, and take instance-1 instead of default.
    ApkCache.beforeExit();
    exiting = true;
    if (adopted) nativeSendExit(code);
    Runtime.getRuntime().halt(code);
  }

  /**
   * Starts the next standby a second after the first frame, off the main thread: its boot (~2 s of CPU) then does not
   * compete with this launch or the app's first animations. An instance that exits sooner starts it on the way out.
   */
  static void spawnNextSoon() {
    if (System.getProperty("apksim.spawnStandby") == null) return;
    Thread t = new Thread(() -> {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return;
      }
      spawnNext();
    }, "apksim-spawn-standby");
    t.setDaemon(true);
    t.start();
  }

  /**
   * Starts the standby for the next launch, if the launcher allowed one ({@code -Dapksim.spawnStandby=FLAGS}) and
   * there is none yet (the launcher checks).
   */
  static synchronized void spawnNext() {
    String flags = System.getProperty("apksim.spawnStandby");
    if (spawned || flags == null) return;
    spawned = true;
    try {
      List<String> cmd = new ArrayList<>(List.of(System.getProperty("apksim.root") + "/apksim", "--spawn-standby"));
      if (!flags.isEmpty()) cmd.add(flags);
      new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (Exception e) {
      System.err.println("[apksim] could not start a standby: " + e);
    }
  }

  private static void quit(Path pidFile, String sock) {
    try {
      if (sock != null) Files.deleteIfExists(Path.of(sock));
      if (ownsPidFile(pidFile)) Files.deleteIfExists(pidFile);
    } catch (Exception e) {
      // Leftovers are cleaned by the next spawn.
    }
    Storage.deleteScratch();
    Runtime.getRuntime().halt(0);
  }

  private static boolean ownsPidFile(Path pidFile) {
    try {
      return Files.readString(pidFile).trim().equals(String.valueOf(ProcessHandle.current().pid()));
    } catch (Exception e) {
      return false;
    }
  }

  private static long jarStamp() {
    // A rebuilt runtime, or a retrained startup cache (a standby booted without it is slow).
    java.io.File jar = new java.io.File(System.getProperty("apksim.root"), "work/apksim.jar");
    // The cache lives in the state folder (work/ in a checkout, ~/.apksim for an installed release), cacheRoot's parent.
    java.io.File aot = new java.io.File(new java.io.File(System.getProperty("apksim.cacheRoot")).getParentFile(), "app.aot");
    return (jar.lastModified() * 31 + jar.length()) * 31 + aot.lastModified();
  }

  private static List<String> split(byte[] msg) {
    List<String> out = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < msg.length; i++) {
      if (msg[i] == 0) {
        out.add(new String(msg, start, i - start, StandardCharsets.UTF_8));
        start = i + 1;
      }
    }
    return out;
  }

  private static native int nativeListen(String path);

  private static native byte[] nativeAccept(int listenFd, int timeoutMs);

  private static native boolean nativeAdopt();

  private static native void nativeDecline();

  private static native void nativeClose(int fd);

  private static native void nativeAwaitHangup();

  private static native void nativeSendExit(int code);

  private static native boolean nativeChdir(String dir);
}
