package dev.apksim.serve;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One browser tab and the apksim instance it drives. The instance is an ordinary launch (`apksim --headless --fresh
 * --control auto ... APK`, taken by a warm standby on the Mac when one is ready); this reads its control port from its
 * stderr, opens two control connections to it on loopback (commands, pipelined, and a frame stream), and relays: frames
 * to the tab, a window in flight (two to eight, sized from the ping round trip and the frame interval, see
 * {@link #window}; the tab acknowledges each one it draws, and newer frames replace the one waiting; with H.264,
 * a tab that falls behind drops to the next keyframe instead), and the tab's input to the instance, only as the
 * commands {@link #check} allows.
 */
final class Session {
  static final Pattern CONTROL_PORT = Pattern.compile("\\[apksim\\] control port (\\d+)");
  private static final Pattern DATA_SLOT = Pattern.compile("\\[apksim\\] data slot (.+) pid (\\d+)$");
  private static final Pattern PERMISSION = Pattern.compile("\\[apksim\\] permission request waiting: (.*?) \\(answer");
  private static final Pattern TRANSLATED = Pattern.compile("\\[apksim\\] translated (\\d+) classes");
  private static final String NUM = "-?\\d{1,5}(\\.\\d{1,3})?";
  private static final int MIN_IN_FLIGHT = 2;
  /** Bytes sent and not yet acknowledged, past the first {@link #MIN_IN_FLIGHT} frames: a slow link sees current frames. */
  private static final long IN_FLIGHT_BYTES = 1 << 20;
  static final List<String> DEVICES = List.of("phone", "foldable", "tablet", "desktop", "tv", "watch", "watch-small", "watch-large");

  final String id;
  final Catalog.App app;
  final Map<String, String> options;
  final long started = System.currentTimeMillis();
  private final Serve serve;
  private final WebSocket ws;
  private Process process;
  /** Reads the instance's output, where the data slot is announced: cleanup waits for it to reach the end. */
  private volatile Thread logReader;
  private Writer log;
  /** The last lines of the log, for the tab when the session ends badly. Guarded by this. */
  private final ArrayDeque<String> tail = new ArrayDeque<>();
  private final Crash.Reason crash = new Crash.Reason();
  /** The session ended because the instance died or could not start, not because the tab left or it idled. */
  private volatile boolean abnormal;
  private long lastProgress;
  private Socket control;
  private PrintWriter controlOut;
  private Socket stream;
  /** What to do with each reply the instance owes, in order (control replies come back in the order sent). */
  private final ArrayDeque<Consumer<String>> replies = new ArrayDeque<>();
  volatile long lastInput = System.currentTimeMillis();
  volatile long firstFrameMillis = -1;
  volatile long pid = -1;
  volatile int heapMb = -1;
  volatile int rssMb = -1;
  volatile long framesSent;
  volatile long bytesSent;
  volatile String device;
  private volatile Path dataDir;
  /** `apksim beam`: the slot name (`bm<hex>`) the agent unpacked this session's moved data into, else null. */
  volatile String seedSlot;
  /** `apksim beam`: the page's origin, and whether the data slot is kept when the session ends ("Keep on this Mac"). */
  volatile String site;
  volatile boolean keep;
  /**
   * Issue 181: the saved session this one reopened (its data copied into {@link #seedSlot}), else null; its copy goes
   * back over the saved slot when the session ends. Its `saved/<id>.lock` is held shared meanwhile, so `forget` and the
   * expiry sweep leave it.
   */
  volatile String savedId;
  volatile BeamStore.SavedHold savedLock;
  /** The saved record's `changed` when its copy was made: a write-back over data changed since is refused. */
  volatile long savedChanged;
  private volatile boolean ended;
  private final CompletableFuture<Integer> port = new CompletableFuture<>();
  // Frame relay: the newest frame not sent yet, and how many the tab has not acknowledged. Guarded by this.
  // JPEG: at most one frame waits, the newest. H.264: every frame from a keyframe on waits (each needs the one before),
  // and a tab that falls behind drops to the next keyframe, which is then asked for.
  private final ArrayDeque<byte[]> waiting = new ArrayDeque<>();
  private int inFlight;
  private long inFlightBytes;
  /** Send time (nanoTime) and size of each frame in flight, oldest first (acks come back in order). */
  private final java.util.ArrayDeque<long[]> flying = new java.util.ArrayDeque<>();
  /** Recent ack round trips in ms (a ring of 16), and the smoothed interval between the instance's frames in ms. */
  private final double[] ackRtts = new double[16];
  private int ackCount;
  /** Recent WebSocket ping round trips in ms (a ring of 8): the link alone, with no queue of frames to decode behind. */
  private final double[] pingRtts = new double[8];
  private int pingCount;
  private long lastProbe;
  private double frameIntervalMs = 100;
  private long lastFrameNanos;
  private boolean dropToKey;
  /** The stream is H.264 (the tab asked, and the instance has an encoder). */
  private volatile boolean h264;
  /** H.264 frames that may wait for the tab before it drops to the next keyframe. */
  private static final int MAX_WAITING = 3;

  Session(Serve serve, String id, Catalog.App app, Map<String, String> options, WebSocket ws) {
    this.serve = serve;
    this.fileKey = serve.beam ? HexFormat.of().formatHex(secureBytes()) : null;
    this.id = id;
    this.app = app;
    // A copy the session updates as the tab changes its settings (trackSetting): a kept session records what it ended with.
    this.options = new java.util.concurrent.ConcurrentHashMap<>(options);
    // The animation scale the app is launched with (the launcher takes the last --anim-scale), so a first toggle starts from it.
    List<String> launchArgs = app.launch();
    int animScaleIndex = launchArgs.lastIndexOf("--anim-scale");
    if (animScaleIndex >= 0 && animScaleIndex + 1 < launchArgs.size()) {
      String launchScale = launchArgs.get(animScaleIndex + 1);
      if (launchScale.matches("[0-9]+(\\.[0-9]+)?")) this.options.putIfAbsent("animscale", launchScale);
    }
    this.ws = ws;
    ws.onPong = this::ponged;
    // The tab's device (checked by Serve), else the app's own default, else the phone.
    String own = appDevice(app);
    this.device = options.getOrDefault("device", own != null ? own : "phone");
  }

  /** The device size the app's launch options give (`--device`, checked by Catalog.launchOptions), or null. */
  static String appDevice(Catalog.App app) {
    List<String> l = app.launch();
    int i = l.lastIndexOf("--device");  // the last, as the launcher takes it
    return i >= 0 && i + 1 < l.size() ? l.get(i + 1) : null;
  }

  /** The app's own launch options a reopened saved session's recorded setting replaces, and that setting's name. */
  private static final Map<String, String> SAVED_OVER_OWN = Map.of("--appearance", "appearance", "--locale", "locale",
      "--font-scale", "fontscale", "--permissions", "permissions");

  /** Whether a tab may ask for this device: a built-in one, a size serve was given a name for, or the app's own default. */
  static boolean deviceAllowed(Serve serve, Catalog.App app, String name) {
    return DEVICES.contains(name) || serve.deviceSizes.containsKey(name) || name.equals(appDevice(app));
  }

  /** What the instance's `--device` and `device` command take for a device name of this session. */
  private String deviceSpec(String name) {
    return serve.deviceSizes.getOrDefault(name, name);
  }

  /** Runs the session until the tab closes, the app exits or it times out. */
  void run() {
    // The tab is watched from the start: closing it while the app downloads or launches ends the session too.
    Thread tab = Thread.ofVirtual().name("serve-tab-" + id).start(() -> {
      ws.readLoop(this::onMessage);
      stop(null);
    });
    Thread reader = null;
    Thread sender = null;
    try {
      start();
      if (!ended) {
        reader = Thread.ofVirtual().name("serve-frames-" + id).start(this::readFrames);
        sender = Thread.ofVirtual().name("serve-send-" + id).start(this::sendFrames);
      }
    } catch (Exception e) {
      logLine("[serve] " + e);
      abnormal = true;
      Process dead;
      synchronized (this) {
        dead = process;
      }
      try {
        // An instance that died before its control port (a crash while starting): say how, as for one that ran.
        if (dead != null && !ended && dead.waitFor(3, TimeUnit.SECONDS)) exited(dead);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      if (!ended) stop("could not start: " + e.getMessage());
    }
    try {
      tab.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (reader != null) reader.interrupt();
    if (sender != null) sender.interrupt();
    stop(null);
  }

  private void start() throws Exception {
    // Waits for an added app's download; a closed tab or the idle limit stops the wait (not the shared download).
    Path apk = serve.catalog.file(app, what -> ws.sendText("p " + what), () -> ended);
    if (ended) return;  // the tab left while the APK downloaded
    // An APK whose cache is being warmed in the background: wait for it (a launch beside it would translate twice).
    serve.warmer.await(app, apk, what -> ws.sendText("p " + what), () -> ended);
    if (ended) return;
    Files.createDirectories(serve.logs);
    log = Files.newBufferedWriter(serve.logs.resolve(id + ".log"), StandardCharsets.UTF_8);
    // Never the owner's saved sign-ins (work/autofill): other people use this instance.
    // Its own data slots (serve-0, serve-1...): --fresh then never empties data an ordinary launch keeps.
    // A beamed instance has a window, hidden, which "Show in Simulator" brings up (docs/beam.md, phase 3): the same
    // display, a second view of it.
    List<String> cmd = new ArrayList<>(List.of(serve.launcher.toString()));
    cmd.addAll(serve.beam ? List.of("--window", "hidden") : List.of("--headless"));
    // A session whose data came from the server (phase 5) runs in the slot the agent unpacked it into, not a fresh one.
    if (seedSlot != null) cmd.addAll(List.of("--no-autofill", "--data-slots", seedSlot, "--control", "auto"));
    else cmd.addAll(List.of("--fresh", "--no-autofill", "--data-slots", serve.home, "--control", "auto"));
    cmd.addAll(List.of("--device", deviceSpec(device)));
    String appearance = options.get("appearance");
    if ("dark".equals(appearance) || "light".equals(appearance)) cmd.addAll(List.of("--appearance", appearance));
    String locale = options.get("locale");
    if (locale != null && locale.matches("[a-z]{2,3}(-[A-Z]{2})?")) cmd.addAll(List.of("--locale", locale));
    String font = options.get("fontscale");
    if (font != null && font.matches("[0-2](\\.\\d{1,2})?")) cmd.addAll(List.of("--font-scale", font));
    String perms = options.getOrDefault("permissions", "ask");
    if (perms.equals("grant") || perms.equals("deny")) cmd.addAll(List.of("--permissions", perms));
    if (serve.noNetwork) cmd.add("--no-network");
    if (serve.moveSharedData) cmd.add("--move-shared-data");
    // A beamed instance starts from its build's caches the agent kept (translated classes, its APK cache entry), which
    // it cannot write; what it makes goes to its own out folder, moved into that cache once it has exited (beamCaches).
    String build = serve.beam ? serve.catalog.buildHash(app.path()) : null;
    if (build != null) cmd.addAll(List.of("--jvm", "-Dapksim.beamBuildCache=" + serve.beamCache(build)));
    // A beam session records by default (`apksim beam config rec`; input alone costs little), so the person who finds a
    // bug has something to save (`rec save`) and replay (scripts/replay-adb.py). The app's own options may choose.
    List<String> own = app.launch();
    if (serve.beam && serve.store != null && !own.contains("--rec") && !"off".equals(serve.store.config.rec())) {
      cmd.addAll(List.of("--rec", serve.store.config.rec()));
    }
    // The app's own (catalog line or URL add), checked by Catalog.launchOptions; its --device is the default above.
    for (int i = 0; i < own.size(); i++) {
      if (own.get(i).equals("--device")) i++;
      // A reopened saved session (issue 181) starts as it was saved: what it recorded (in options) wins over the
      // app's own defaults, as `apksim beam run` puts them.
      else if (savedId != null && i + 1 < own.size() && SAVED_OVER_OWN.containsKey(own.get(i)) && options.containsKey(SAVED_OVER_OWN.get(own.get(i)))) i++;
      else if (own.get(i).equals("--rec") && i + 1 < own.size() && own.get(i + 1).equals("off")) i++;  // off: no recorder
      else cmd.add(own.get(i));
    }
    // A build with a 4 KB-aligned library the loader would refuse here (issue 171): a Linux serve runs it (4 KB pages),
    // its catalog need not say --move-shared-data, and the beamed app would exit at its first loadLibrary. The agent
    // adds the flag itself; it changes only how such libraries are mapped, and the instance is in the beam sandbox.
    if (serve.beam) {
      SharedDataLibs.addFlag(cmd, apk, build, app.name(), line -> {
        System.err.println(line);
        logLine(line);
      });
    }
    cmd.add(apk.toString());
    ws.sendText("p launching " + app.name());
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
        .redirectErrorStream(true);
    // Where the JVM writes a crash report (hs_err_pid<N>.log), found and removed by exited(). A beamed instance may not
    // read or write the agent's folders: the state folder's tmp (its report goes to its scratch, -XX:ErrorFile).
    if (serve.beam) Files.createDirectories(serve.state.resolve("tmp"));  // a fresh state folder has none yet
    pb.directory(serve.beam ? serve.state.resolve("tmp").toFile() : serve.logs.toFile());
    serve.instanceEnvironment(pb);
    // Under the lifecycle lock: a stop() that came first (idle while a configured URL downloaded) must not leave a
    // process it never saw.
    synchronized (this) {
      if (ended) throw new IOException("the session ended before the app started");
      process = pb.start();
    }
    logReader = Thread.ofVirtual().name("serve-log-" + id).start(this::readLog);
    Process started = process;
    started.onExit().thenRun(() -> {
      if (!ended) Thread.ofVirtual().start(() -> exited(started));
    });
    int p = port.get(serve.launchTimeoutSeconds, TimeUnit.SECONDS);
    control = connect(p);
    // The instance's identity for what serve or the agent does to its process and files (ending it, a beamed one's
    // caches): the process the kernel says listens on its control port, which Main opens before any app code runs.
    // Never what it prints: the app's own output could name another process.
    instancePid = listenerPid(p);
    if (instancePid <= 0 && !System.getProperty("os.name", "").startsWith("Mac")) {
      // No lsof (the Linux image), and no standby off the Mac: the launcher execs the JVM, so the process started
      // here is the instance itself.
      Process launched;
      synchronized (this) {
        launched = process;
      }
      if (launched != null) instancePid = launched.pid();
    }
    Thread.ofVirtual().name("serve-replies-" + id).start(this::readReplies);
    // First what the link asked for (its deep link, its orientation), then what the tab sent meanwhile, in order: a
    // rotation or deep link chosen during startup comes after the link's and stays.
    List<Map.Entry<String, Consumer<String>>> first = new ArrayList<>();
    first.add(Map.entry("stats", this::onStats));
    String open = options.get("open");
    if (open != null && check("open " + open) != null) first.add(Map.entry("open " + open, r -> ws.sendText("r open: " + r)));
    String orientation = options.get("orientation");
    if ("landscape".equals(orientation) || "portrait".equals(orientation)) first.add(Map.entry("rotate " + orientation, r -> {}));
    synchronized (replies) {
      controlOut = new PrintWriter(new java.io.OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8), true);
      first.addAll(early);
      early.clear();
      for (Map.Entry<String, Consumer<String>> e : first) {
        replies.add(e.getValue());
        controlOut.println(e.getKey());
      }
    }
    if (fileKey != null) ws.sendText("f " + fileKey);  // the key its file requests carry (beam)
    stream = connect(p);
    // H.264 when the tab can decode it (it says so with codec=h264) and serve allows it; the instance answers which.
    String ask = serve.allowH264 && "h264".equals(options.get("codec")) ? " h264 " + serve.bitrateKbps : "";
    stream.getOutputStream().write(("stream " + serve.quality + ask + "\n").getBytes(StandardCharsets.US_ASCII));
  }

  /** The instance ended by itself: why, from its log (the whole of it is read first) and the JVM's crash report. */
  private void exited(Process p) {
    // One handler at a time: the instance's onExit callback and a start that failed because it died both come here, and
    // the second must wait for the first (which takes the crash report and ends the session) rather than race it.
    synchronized (exitLock) {
      if (exitHandled) return;
      exitHandled = true;
      explainExit(p);
    }
  }

  private final Object exitLock = new Object();
  private boolean exitHandled;

  /**
   * `handoff` (docs/beam.md, phase 5): the app quits (so what it wrote is flushed), its data slot and .device folder
   * are packed (SlotPack) into `STATE/serve/handoff/<id>.zip`, and the tab gets `h {"ticket", "size"}`, a ticket for
   * `GET /api/beam/data` the visitor's agent downloads it with; then the session ends without a word (the tab is moving
   * it), its slot erased as always. The pack is kept until the ticket expires. A tab gets `h {"error"}` when it cannot.
   */
  private void handoff() {
    Path slot = dataDir;
    if (slot == null || ended) {
      ws.sendText("h {\"error\":\"the app has no data yet\"}");
      return;
    }
    // The slot comes from the instance's output, which app code (attachBaseContext) can write first: it must be one
    // of this server's slots, and one the session's own JVM (known by the kernel, not by what it prints) holds open,
    // checked while it still runs. Else the app could name any folder (the server user's, or another visitor's slot)
    // for the server to pack and hand to this tab.
    String why = handoffSlotProblem(slot);
    if (why != null) {
      logLine("[serve] no handoff: " + why);
      ws.sendText("h {\"error\":" + Http.q(why) + "}");
      return;
    }
    synchronized (exitLock) {
      if (exitHandled) return;
      exitHandled = true;  // its quit below is not an exit to report: this ends the session itself
    }
    try {
      synchronized (replies) {
        if (controlOut != null) controlOut.println("quit");
      }
      Process p;
      synchronized (this) {
        p = process;
      }
      if (p != null) p.waitFor(20, TimeUnit.SECONDS);
      long jvm = instancePid;
      if (jvm > 0) {
        ProcessHandle.of(jvm).ifPresent(h -> {
          try {
            h.onExit().get(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            // packed as far as it was written
          }
        });
      }
      Path dir = serve.state.resolve("serve/handoff");
      Files.createDirectories(dir);
      byte[] rnd = new byte[12];
      new java.security.SecureRandom().nextBytes(rnd);
      String hid = "d" + java.util.HexFormat.of().formatHex(rnd);
      Path zip = dir.resolve(hid + ".zip");
      long skipped;
      try {
        skipped = packWithDeadline(slot, zip);
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(zip);
        throw e;
      }
      String sha = serve.catalog.sha256(zip);
      logLine("[serve] handed off its data (" + (Files.size(zip) >> 10) + " KB) to the tab's Mac");
      if (skipped > 0) logLine("[serve] left out of the handoff: " + skipped + " links or special files");
      ws.sendText("h {\"ticket\":" + Http.q(serve.tickets.mint(hid, sha)) + ",\"size\":" + Files.size(zip)
          + (skipped > 0 ? ",\"skipped\":" + skipped : "") + "}");
    } catch (IOException | RuntimeException e) {
      logLine("[serve] could not hand off its data: " + e);
      ws.sendText("h {\"error\":" + Http.q(String.valueOf(e.getMessage())) + "}");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    stop(null);
  }

  /**
   * SlotPack.pack on a thread of its own, watched: a process the app left running can swap a file for a named pipe
   * between the check and the open, and that open blocks (uninterruptibly) until something writes to the pipe, as a
   * read from a pipe that never ends does. With no progress for 15 s the pack is interrupted (which ends a read) and its
   * pending open released (Progress.unstick: an open of the same name for writing). That fails when the app has
   * swapped the name again meanwhile, leaving the open blocked on a pipe nothing can name; so, still stuck 15 s later,
   * the pack is given up and the handoff fails, and the session still ends and frees its place. The thread is then
   * left blocked for good, holding only its folder handles (its zip is closed and deleted here).
   */
  private long packWithDeadline(Path slot, Path zip) throws IOException, InterruptedException {
    SlotPack.Progress progress = new SlotPack.Progress();
    Throwable[] failed = {null};
    java.io.OutputStream out = Files.newOutputStream(zip);
    Thread t = Thread.ofPlatform().daemon().name("serve-pack-" + id).start(() -> {
      try (out) {
        SlotPack.pack(serve.state, slot, slot.getParent().getFileName().toString(), out, progress);
      } catch (Throwable e) {
        failed[0] = e;
      }
    });
    long seenFiles = -1, seenBytes = -1, since = System.currentTimeMillis();
    boolean nudged = false;
    while (true) {
      t.join(1000);
      if (!t.isAlive()) break;
      long now = System.currentTimeMillis();
      if (progress.files != seenFiles || progress.bytes != seenBytes) {
        seenFiles = progress.files;
        seenBytes = progress.bytes;
        since = now;
        nudged = false;
      } else if (now - since > STALL_MS && !nudged) {
        logLine("[serve] packing the app's data has not moved for " + STALL_MS / 1000 + " s: unblocking it");
        t.interrupt();
        Thread.ofPlatform().daemon().name("serve-pack-unstick-" + id).start(progress::unstick);
        nudged = true;
        since = now;
      } else if (now - since > STALL_MS) {
        try {
          out.close();
        } catch (IOException e) {
          // closed either way
        }
        throw new IOException("the app's data could not be read (a file that never ends)");
      }
    }
    if (failed[0] instanceof IOException e) throw e;
    if (failed[0] instanceof RuntimeException e) throw e;
    if (failed[0] != null) throw new IOException(failed[0]);
    return progress.skipped;
  }

  /** How long a pack may go without reading a byte before it is unblocked, then given up (packWithDeadline). */
  static long STALL_MS = Long.getLong("apksim.packStallMs", 15_000);

  /** Why this session's data slot may not be handed off, or null. */
  private String handoffSlotProblem(Path slot) {
    Path dir = slot.toAbsolutePath().normalize();
    Path data = serve.state.resolve("cache/data").toAbsolutePath().normalize();
    if (dir.getParent() == null || !data.equals(dir.getParent().getParent())
        || !dir.getFileName().toString().matches("(" + java.util.regex.Pattern.quote(serve.home) + "|bm[0-9a-f]{12})-[0-9]+")) {
      return "the app's data slot is not one of this server's";
    }
    long pid = instancePid;
    if (pid <= 0) return "the app's process is not known";
    if (!holdsOpen(pid, dir.resolve(".lock"))) return "the app's data slot is not its own";
    // Over the limits the agent unpacks within: said now, while the app still runs here, not after it quit. (The
    // pack itself checks again: this count may change before then.)
    long[] used = {1, 0};  // entries (the package one), bytes
    for (Path root : List.of(dir, dir.resolveSibling(dir.getFileName() + ".device"))) {
      if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
      // Files and folders alike (each is an entry), and no further than the limit: a slot of millions of empty folders
      // must not keep this walking.
      try (Stream<Path> all = Files.walk(root)) {
        var it = all.iterator();
        long listed = 0;
        while (it.hasNext() && used[0] <= SlotPack.MAX_FILES && used[1] <= SlotPack.MAX_BYTES) {
          Path f = it.next();
          if (f.equals(root)) continue;
          if (++listed > SlotPack.MAX_LISTED) return "the app's data has more than " + SlotPack.MAX_LISTED + " entries to look at";
          // As pack and unpack limit them, said now: a pack that fails after the app quit would lose its data.
          Path rel = root.relativize(f);
          boolean isDir = Files.isDirectory(f, java.nio.file.LinkOption.NOFOLLOW_LINKS);
          if (rel.getNameCount() - (isDir ? 0 : 1) > SlotPack.MAX_DEPTH) return "the app's data has folders over " + SlotPack.MAX_DEPTH + " deep";
          if (rel.toString().length() + 8 > SlotPack.MAX_NAME) return "a name in the app's data is over " + SlotPack.MAX_NAME + " characters";
          if (Files.isDirectory(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            used[0]++;
          } else if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            used[0]++;
            try {
              used[1] += Files.size(f);
            } catch (IOException e) {
              // gone meanwhile
            }
          }
        }
      } catch (IOException | RuntimeException e) {
        return "the app's data could not be read: " + e.getMessage();
      }
    }
    if (used[0] > SlotPack.MAX_FILES) return "the app's data has more than " + (SlotPack.MAX_FILES - 1) + " files and folders to move";
    if (used[1] > SlotPack.MAX_BYTES) return "the app's data is over " + (SlotPack.MAX_BYTES >> 20) + " MB to move";
    return null;
  }

  /** Whether process `pid` has `file` open: /proc on Linux, lsof elsewhere. */
  static boolean holdsOpen(long pid, Path file) {
    try {
      Path real = file.toRealPath();
      Path fds = Path.of("/proc", Long.toString(pid), "fd");
      if (Files.isDirectory(fds)) {
        try (Stream<Path> all = Files.list(fds)) {
          return all.anyMatch(f -> {
            try {
              return real.equals(Files.readSymbolicLink(f));
            } catch (IOException | RuntimeException e) {
              return false;
            }
          });
        }
      }
      Process l = new ProcessBuilder("lsof", "-t", "--", real.toString()).redirectErrorStream(true).start();
      String out = new String(l.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      l.waitFor(5, TimeUnit.SECONDS);
      return out.lines().map(String::strip).anyMatch(Long.toString(pid)::equals);
    } catch (IOException | RuntimeException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void explainExit(Process p) {
    Thread reading = logReader;
    if (reading != null) {
      try {
        reading.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    int status = p.exitValue();
    String why = "the app exited (status " + status + ")";
    if (status != 0) {
      abnormal = true;
      // The JVM's crash report is summarised into the log, then deleted (it holds the environment and a memory map).
      List<String> hs = serve.beam ? beamHsErr() : Crash.takeHsErr(serve.logs, p.pid(), pid, crash.hsErrPid());
      for (String l : hs) logLine(l);
      String reason = Crash.hsErrReason(hs);
      if (reason != null) reason = "crashed: " + reason;
      else if (crash.text() != null) reason = crash.text();
      if (reason != null) why += ": " + reason;
    }
    stop(why);
  }

  /**
   * Whether this PID is an apksim JVM that launched this APK: its scratch's launch.info, which Main writes before any app
   * code runs, names it. A PID read from an instance's output is the app's to forge.
   */
  static boolean launched(Serve serve, long pid, Path apk) {
    try {
      Path info = serve.state.resolve("tmp").resolve(Long.toString(pid)).resolve("launch.info");
      if (!Files.isRegularFile(info, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
      List<String> lines = Files.readAllLines(info);
      return !lines.isEmpty() && lines.get(0).equals("apk=" + apk.toAbsolutePath())
          && ProcessHandle.of(pid).flatMap(h -> h.info().command()).map(c -> c.contains("java")).orElse(false);
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * After its client has gone: waits up to 5 s for a (standby) JVM, then kills it, as endInstance does; only one that
   * {@link #launched} this APK.
   */
  static void endJvm(Serve serve, long pid, Path apk) {
    if (pid <= 0 || !launched(serve, pid, apk)) return;
    ProcessHandle.of(pid).ifPresent(h -> {
      try {
        h.onExit().get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        h.destroyForcibly();
        try {
          h.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception e2) {
          // nothing more to do
        }
      }
    });
  }

  private static Socket connect(int port) throws IOException {
    Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
    s.setTcpNoDelay(true);
    return s;
  }

  private void readLog() {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line; (line = r.readLine()) != null; ) {
        logLine(line);
        crash.line(line);
        Matcher m = TRANSLATED.matcher(line);
        if (m.find() && firstFrameMillis < 0 && System.currentTimeMillis() - lastProgress > 1000) {
          lastProgress = System.currentTimeMillis();
          String what = "translating classes: " + m.group(1);
          Thread.ofVirtual().start(() -> ws.sendText("p " + what));
        }
        m = CONTROL_PORT.matcher(line);
        if (m.find()) port.complete(Integer.parseInt(m.group(1)));
        m = DATA_SLOT.matcher(line);
        if (m.find() && dataDir == null) {  // the first only: later lines may be the app's own output
          dataDir = Path.of(m.group(1));
          pid = Long.parseLong(m.group(2));
        }
        m = PERMISSION.matcher(line);
        if (m.find()) {
          String perms = m.group(1);
          // Not on this thread: a tab that stopped reading must not stop the log (and the data slot) being read.
          Thread.ofVirtual().start(() -> ws.sendText("q " + perms));
        }
      }
    } catch (IOException e) {
      // the process ended
    }
    port.completeExceptionally(new IOException("the instance ended before it opened its control port (see the log)"));
  }

  private synchronized void logLine(String line) {
    if (log == null) return;
    try {
      log.write(line);
      log.write('\n');
      log.flush();
      tail.add(Crash.clip(line));
      if (tail.size() > Crash.TAB_LINES) tail.poll();
    } catch (IOException e) {
      // nothing to do
    }
  }

  // ---------------------------------------------------------------- frames

  private void readFrames() {
    try {
      DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(stream.getInputStream(), 1 << 16));
      // "ok stream", or "ok stream h264|jpeg" when a codec was asked for: the tab is told which before any frame.
      StringBuilder line = new StringBuilder();
      for (int c; (c = in.read()) != '\n'; ) {
        if (c < 0) throw new IOException("the stream ended before its answer");
        line.append((char) c);
      }
      h264 = line.toString().trim().equals("ok stream h264");
      if (options.containsKey("codec")) ws.sendText("c " + (h264 ? "h264" : "jpeg"));
      while (!ended) {
        int len = in.readInt();
        if (len < 20 || len > 64 << 20) throw new IOException("a frame of " + len + " bytes");
        byte[] frame = new byte[len];
        in.readFully(frame);
        if (firstFrameMillis < 0) {
          firstFrameMillis = System.currentTimeMillis() - started;
          ws.sendText("p first frame after " + firstFrameMillis + " ms");
          // A beamed instance starts no standby itself (it would inherit this one's sandbox): the agent does.
          if (serve.beam) Thread.ofVirtual().name("beam-standby").start(() -> {
            try {
              Thread.sleep(1000);  // as an instance does: not while the app is starting
            } catch (InterruptedException e) {
              return;
            }
            serve.warmStandby();
          });
          System.err.printf("[serve] session %s: %s first frame after %d ms%n", id, app.name(), firstFrameMillis);
        }
        boolean askKey = false;
        synchronized (this) {
          long n = System.nanoTime();
          // Gaps over 250 ms are a still screen (unchanged images are not sent), not the app's frame rate.
          double gap = (n - lastFrameNanos) / 1e6;
          if (lastFrameNanos != 0 && gap <= 250) frameIntervalMs += (gap - frameIntervalMs) * 0.1;
          lastFrameNanos = n;
          if (!h264) {
            waiting.clear();
            waiting.add(frame);
          } else if (len > 20 && frame[20] == 1) {
            waiting.clear();
            waiting.add(frame);
            dropToKey = false;
          } else if (!dropToKey && waiting.size() >= MAX_WAITING) {
            // The tab is behind: what waits goes, and so does every frame until a keyframe.
            waiting.clear();
            dropToKey = askKey = true;
          } else if (!dropToKey) {
            waiting.add(frame);
          }
          notifyAll();
        }
        if (askKey) askKey();
      }
    } catch (IOException e) {
      // the instance went away, or ended the stream (H.264 that kept failing)
    }
    streamEnded();
  }

  /**
   * The frame stream ended while the session runs: the tab would wait on its last frame (or "Starting...") for good,
   * so the session stops and says so. An instance that exited says why itself (onExit), so it is given a moment.
   */
  private void streamEnded() {
    if (ended) return;
    Process p = process;
    try {
      if (p != null && p.waitFor(2, TimeUnit.SECONDS)) return;  // onExit's stop has the reason
    } catch (InterruptedException e) {
      return;  // the session is being torn down
    }
    if (!ended) stop("the screen stream ended (see the server log)");
  }

  private void sendFrames() {
    try {
      while (!ended) {
        byte[] f;
        synchronized (this) {
          while (waiting.isEmpty() || !mayFly(waiting.peek().length)) wait();
          f = waiting.poll();
          inFlight++;
          inFlightBytes += f.length;
          flying.add(new long[] {System.nanoTime(), f.length});
        }
        long now = System.nanoTime();
        if (now - lastProbe > 500_000_000L) {
          lastProbe = now;
          ws.ping(java.nio.ByteBuffer.allocate(8).putLong(now).array());
        }
        ws.sendBinary(new byte[0], f, 0, f.length);
        framesSent++;
        bytesSent += f.length;
      }
    } catch (IOException | InterruptedException e) {
      // the tab or the session ended
    }
  }

  private synchronized void acked() {
    long[] f = flying.poll();
    if (f != null) {
      inFlight--;
      inFlightBytes -= f[1];
      ackRtts[ackCount++ % ackRtts.length] = (System.nanoTime() - f[0]) / 1e6;
    }
    notifyAll();
  }

  /** The quickest of the recent ping round trips in ms, or -1 before the first. */
  private synchronized double pingRttMs() {
    int n = Math.min(pingCount, pingRtts.length);
    if (n == 0) return -1;
    double m = Double.MAX_VALUE;
    for (int i = 0; i < n; i++) m = Math.min(m, pingRtts[i]);
    return m;
  }

  private void ponged(long sentNanos) {
    double ms = (System.nanoTime() - sentNanos) / 1e6;
    synchronized (this) {
      pingRtts[pingCount++ % pingRtts.length] = ms;
      notifyAll();
    }
  }

  /** The quickest of the recent ack round trips in ms (queueing and a slow tab only add), or -1 before the first. */
  private synchronized double ackRttMs() {
    int n = Math.min(ackCount, ackRtts.length);
    if (n == 0) return -1;
    double m = Double.MAX_VALUE;
    for (int i = 0; i < n; i++) m = Math.min(m, ackRtts[i]);
    return m;
  }

  /**
   * Frames in flight so the link stays full: clamp(ceil(rtt / frame interval) + 1, 2, cap), where cap is `--in-flight`
   * (default 8; below 2 the window is that). Before the first ack, 2.
   */
  private synchronized int window() {
    int cap = serve.inFlightMax, lo = Math.min(MIN_IN_FLIGHT, cap);
    // The ping's round trip, not the acks': an ack waits behind the tab's decoding of the frames before it, so a tab
    // limited by its decoder would grow its own window (and latency) without gaining frames.
    double rtt = pingRttMs();
    if (rtt < 0) return lo;
    return (int) Math.max(lo, Math.min(cap, Math.ceil(rtt / Math.max(frameIntervalMs, 4)) + 1));
  }

  private boolean mayFly(int len) {
    int w = window();
    return inFlight < Math.min(w, MIN_IN_FLIGHT) || (inFlight < w && inFlightBytes + len <= IN_FLIGHT_BYTES);
  }

  /** H.264: a keyframe next, from the instance (one is sent even if the screen is still). */
  private void askKey() {
    Socket s = stream;
    if (!h264 || s == null) return;
    try {
      synchronized (s) {
        s.getOutputStream().write("key\n".getBytes(StandardCharsets.US_ASCII));
      }
    } catch (IOException e) {
      // the instance went away
    }
  }

  // ---------------------------------------------------------------- input

  private void onMessage(String text) {
    if (text.equals("ack")) {
      acked();
      return;
    }
    if (text.equals("key")) {
      // The tab's decoder lost its place (an error, or it started mid-stream): it waits for a keyframe.
      askKey();
      return;
    }
    if (text.startsWith("t ") && text.length() < 40) {
      // Clock: the tab's time back with this machine's (the instance's clock), so it can compare frame times with
      // its own across machines (NTP's way: the sample with the shortest round trip wins).
      java.time.Instant now = java.time.Instant.now();
      ws.sendText(text + " " + (now.getEpochSecond() * 1000 + now.getNano() / 1_000_000.0));
      return;
    }
    if (text.equals("stats")) {
      send("stats", this::onStats);
      return;
    }
    if (text.equals("handoff") && serve.allowBeam && !serve.beam) {
      // Phase 5: the tab moves this session to its Mac with the app's data. Only this session's own socket asks.
      Thread.ofVirtual().name("serve-handoff-" + id).start(this::handoff);
      return;
    }
    if (serve.beam && (text.equals("keep on") || text.equals("keep off"))) {
      // The agent's own, never passed to the instance; only this session's tab reaches this.
      if (savedId != null) {
        // A reopened saved session stays saved: only its owner erases it (apksim beam forget), never a page.
        logLine("[beam] saved session " + savedId + " stays saved (apksim beam forget " + savedId + " erases it)");
        ws.sendText("k on");
        return;
      }
      keep = text.equals("keep on");
      logLine("[beam] keep on this Mac: " + (keep ? "on" : "off"));
      ws.sendText("k " + (keep ? "on" : "off"));
      return;
    }
    if (text.startsWith("rec ")) {
      // Issue 187: the recorder from a tab, where the server allows it (--allow-rec; always on a visitor's own Mac).
      recCommand(text.strip());
      return;
    }
    String cmd = check(text);
    if (cmd == null && serve.beam) cmd = checkBeam(text);
    if (cmd == null && text.strip().startsWith("device ")) {
      // A size serve names (--device-size) or the app's own default: the name is checked, never passed on as given.
      String name = text.strip().substring(7);
      if (deviceAllowed(serve, app, name)) cmd = "device " + name;
    }
    if (cmd == null) {
      ws.sendText("r refused: " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
      return;
    }
    if (cmd.equals("animscale toggle")) {
      // As DeviceControls does: slow (10) unless already slower than normal; sent as a number so the recorded choice matches.
      float cur = 1f;
      try { cur = Float.parseFloat(options.getOrDefault("animscale", "1")); } catch (NumberFormatException e) { /* normal */ }
      cmd = cur > 1f ? "animscale 1" : "animscale 10";
    }
    // The tab's pace and its visibility are not someone using it.
    if (!cmd.startsWith("vsync ") && !cmd.startsWith("maxfps ")) lastInput = System.currentTimeMillis();
    trackSetting(cmd);
    String wire = cmd;
    if (cmd.startsWith("device ")) {
      device = cmd.substring(7);
      wire = "device " + deviceSpec(device);
    }
    String shown = cmd;
    boolean quiet = cmd.startsWith("touch ") || cmd.startsWith("wheel ") || cmd.startsWith("type ") || cmd.startsWith("key ")
        || cmd.startsWith("vsync ") || cmd.startsWith("maxfps ") || cmd.matches("device \\d+x\\d+@.*");  // a Fill tab's sizes, many while it resizes
    send(wire, quiet ? null : r -> ws.sendText("r " + shown.split(" ")[0] + ": " + r));
  }


  // ---------------------------------------------------------------- files and recordings (issue 187)

  /** `apksim beam`: the secret the tab's file requests carry (sent to it on its socket once the instance is up). */
  final String fileKey;
  private long pushedBytes;
  /** Recording ids this session asked for (a file exists once the instance has moved it into place, complete). */
  private final java.util.Set<String> recordings = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static byte[] secureBytes() {
    byte[] b = new byte[16];
    new java.security.SecureRandom().nextBytes(b);
    return b;
  }

  /** A device path a tab may name: a plain one (no white space or control characters, so it is one word on the control line). */
  static boolean plainDevicePath(String p) {
    return p != null && p.length() <= 300 && p.matches("[A-Za-z0-9._@+=,/-]+");
  }

  /** Waits for the instance's answer to one command (the control connection answers in order). */
  private String ask(String cmd, long timeoutMs) throws IOException {
    CompletableFuture<String> f = new CompletableFuture<>();
    send(cmd, f::complete);
    try {
      return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted");
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IOException("the app did not answer");
    }
  }

  /**
   * A tab's upload (`POST .../files?path=`): the bytes are read here, within the limits, and handed to the instance as
   * `devpush`, which writes them where `adb push` could (TabFiles). Returns the JSON answer; throws why not (message
   * shown to the tab, status in {@link PushRefused}).
   */
  String pushFile(String path, java.io.InputStream body, long length) throws IOException {
    if (!plainDevicePath(path)) throw new PushRefused(400, "path: letters, digits and . _ @ + = , / - only");
    for (String seg : path.split("/")) if (seg.equals("..")) throw new PushRefused(400, "path: no .. segments");
    if (!path.startsWith("/") && !path.matches("(files|cache|shared_prefs|databases)/.+")) {
      throw new PushRefused(400, "path: relative paths start with files/, cache/, shared_prefs/ or databases/");
    }
    long max = serve.maxPushMb << 20;
    if (length > max) throw new PushRefused(413, "too large: " + (length >> 20) + " MB (limit " + serve.maxPushMb + " MB a file)");
    if (ended || controlOut == null) throw new PushRefused(409, "the app is not running yet");
    synchronized (this) {
      if (pushedBytes + Math.max(length, 0) > serve.maxPushTotalMb << 20) {
        throw new PushRefused(413, "this session has taken its " + serve.maxPushTotalMb + " MB of files");
      }
    }
    // The body, its copy and its Base64 text are alive together: ~3 times its size, taken from one budget for every
    // session (the launcher's heap is small) before it is allocated or read, and given back when this upload is done.
    // A known length is reserved at once; a chunked body as it arrives.
    java.util.concurrent.atomic.AtomicLong held = new java.util.concurrent.atomic.AtomicLong();
    if (length >= 0 && !reserve(held, 3 * length)) throw new PushRefused(503, "busy: other uploads are using the server's memory, try again");
    try {
      return pushReserved(path, body, length, max, held);
    } finally {
      INFLIGHT.addAndGet(-held.get());
    }
  }

  private static boolean reserve(java.util.concurrent.atomic.AtomicLong held, long n) {
    if (INFLIGHT.addAndGet(n) > INFLIGHT_MAX) {
      INFLIGHT.addAndGet(-n);
      return false;
    }
    held.addAndGet(n);
    return true;
  }

  private static final java.util.concurrent.atomic.AtomicLong INFLIGHT = new java.util.concurrent.atomic.AtomicLong();
  private static final long INFLIGHT_MAX = 96L << 20;

  private String pushReserved(String path, java.io.InputStream body, long length, long max, java.util.concurrent.atomic.AtomicLong held)
      throws IOException {
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream((int) Math.max(0, Math.min(length, 1 << 20)));
    byte[] chunk = new byte[1 << 16];
    long left = length >= 0 ? length : max + 1;
    while (left > 0) {
      int n = body.read(chunk, 0, (int) Math.min(chunk.length, left));
      if (n < 0) break;
      if (length < 0 && !reserve(held, 3L * n)) throw new PushRefused(503, "busy: other uploads are using the server's memory, try again");
      buf.write(chunk, 0, n);
      left -= n;
      if (buf.size() > max) throw new PushRefused(413, "too large (limit " + serve.maxPushMb + " MB a file)");
    }
    if (length >= 0 && buf.size() != length) throw new PushRefused(400, "the body ended early");
    synchronized (this) {
      if (pushedBytes + buf.size() > serve.maxPushTotalMb << 20) {
        throw new PushRefused(413, "this session has taken its " + serve.maxPushTotalMb + " MB of files");
      }
      pushedBytes += buf.size();
    }
    String reply = ask("devpush " + path + " " + java.util.Base64.getEncoder().encodeToString(buf.toByteArray()), 30_000);
    if (!reply.startsWith("ok ")) {
      synchronized (this) {
        pushedBytes -= buf.size();
      }
      throw new PushRefused(422, reply.startsWith("error: ") ? reply.substring(7) : reply);
    }
    String[] v = reply.split(" ");
    lastInput = System.currentTimeMillis();
    logLine("[serve] file from the tab: " + v[1] + " (" + v[2] + " bytes)");
    return "{\"path\":" + Http.q(v[1]) + ",\"size\":" + v[2] + "}";
  }

  /** An upload or download the server refuses, with the HTTP status to answer. */
  static final class PushRefused extends IOException {
    final int status;

    PushRefused(int status, String why) {
      super(why);
      this.status = status;
    }
  }

  /** Where this session's recordings are written: the instance's own out folder in a beam sandbox, else the server's. */
  private Path recDir() {
    if (serve.beam) return instancePid > 0 ? serve.state.resolve("beam/out/" + instancePid + "/rec") : null;  // the kernel-confirmed pid, not the app-reported one
    return serve.logs.resolve("rec-" + id);
  }

  /** The folder a finished beam session's recordings are moved to (logs age out with it). */
  private Path keptRecDir() {
    return serve.logs.resolve("rec-" + id);
  }

  /**
   * The recording file, if this session made it and it is written (the instance moves it into place when complete).
   * A beam app can write its own out folder, so a file there is never opened by the agent as it stands: it is copied
   * (without following links) into the agent's folder and that copy is served.
   */
  Path recording(String rid) {
    if (!rid.matches("[0-9a-f]{12}") || !recordings.contains(rid)) return null;
    Path kept = keptRecDir().resolve(rid + ".apkrec");
    if (Files.isRegularFile(kept, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return kept;
    Path d = recDir();
    if (d == null) return null;
    if (!serve.beam) {
      Path f = d.resolve(rid + ".apkrec");
      return Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? f : null;
    }
    return copyOut(d, rid + ".apkrec") ? kept : null;
  }

  private final java.util.Set<String> failedCopies = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Set<String> copying = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final java.util.concurrent.atomic.AtomicInteger COPIERS = new java.util.concurrent.atomic.AtomicInteger();

  /** Copies one recording out of a beam instance's folder into the agent's own, never following a link. */
  private boolean copyOut(Path dir, String name) {
    java.nio.file.LinkOption nf = java.nio.file.LinkOption.NOFOLLOW_LINKS;
    Path f = dir.resolve(name);
    if (failedCopies.contains(name) || Files.isSymbolicLink(dir) || !Files.isRegularFile(f, nf)) return false;
    // Java cannot open without blocking, so a worker stuck on a pipe cannot be stopped. Each worker holds one of a few
    // slots until it has really finished (not until its caller gave up), one recording is copied by one worker at a
    // time, and a recording whose copy timed out is not tried again.
    if (!copying.add(name)) return false;
    boolean slot = false;
    for (int n = COPIERS.get(); n < 4 && !slot; n = COPIERS.get()) slot = COPIERS.compareAndSet(n, n + 1);
    if (!slot) {
      copying.remove(name);
      return false;
    }
    Path tmp = null;
    CompletableFuture<Void> job = null;
    try {
      Files.createDirectories(keptRecDir());
      Path t = Files.createTempFile(keptRecDir(), ".copy", ".tmp");
      tmp = t;
      java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      job = CompletableFuture.runAsync(() -> {
        try (java.io.InputStream in = Files.newInputStream(f, nf)) {
          if (!cancelled.get()) Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }, r -> Thread.ofVirtual().start(r));
      job.whenComplete((v, e) -> {
        COPIERS.decrementAndGet();
        copying.remove(name);
        if (cancelled.get()) {
          try {
            Files.deleteIfExists(t);
          } catch (IOException ex) {
            // left for the sweep
          }
        }
      });
      try {
        job.get(30, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        failedCopies.add(name);
        cancelled.set(true);
        return false;
      } catch (Exception e) {
        Files.deleteIfExists(t);
        return false;
      }
      Files.move(t, keptRecDir().resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return true;
    } catch (IOException e) {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ex) {
          // left for the sweep
        }
      }
      return false;
    } finally {
      if (job == null) {
        COPIERS.decrementAndGet();
        copying.remove(name);
      }
    }
  }

  /** After a beam instance exits: its recordings from the sandbox's out folder to the logs folder (copied, links refused). */
  private void keepRecordings() {
    Path d = serve.beam ? recDir() : null;
    if (d == null || Files.isSymbolicLink(d) || !Files.isDirectory(d, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
    for (String rid : recordings) copyOut(d, rid + ".apkrec");
  }

  /** What a download needs after the session ends: the key and origin (beam), the ids, and where they are. */
  record Kept(String key, String site, java.util.Set<String> rids, Path dir) {}

  Kept kept() {
    return recordings.isEmpty() ? null : new Kept(fileKey, site, new java.util.HashSet<>(recordings), keptRecDir());
  }

  /** `rec on|off CHANNEL...` by tokens (a repeated regex group would recurse once per word and overflow the stack). */
  private static boolean recChannels(String t) {
    if (t.length() > 200) return false;
    String[] w = t.split(" ");
    if (w.length < 2 || w.length > 10 || !w[0].equals("rec") || !(w[1].equals("on") || w[1].equals("off"))) return false;
    for (int i = 2; i < w.length; i++) {
      if (!List.of("input", "logcat", "net", "db", "db+values", "screen", "all").contains(w[i])) return false;
    }
    return true;
  }

  /** `rec on|off [CHANNEL...]`, `rec checkpoint now`, `rec save [SECONDS]` from the tab; never a file name of its own. */
  private void recCommand(String t) {
    if (!serve.beam && !serve.allowRec) {
      ws.sendText("r refused: rec (the server does not allow recordings: --allow-rec)");
      return;
    }
    lastInput = System.currentTimeMillis();  // a tab using the recorder is someone using the session
    String cmd = null;
    String id2 = null;
    if (recChannels(t) || t.equals("rec checkpoint now")) {
      cmd = t;
    } else if (t.matches("rec save( [1-9][0-9]{0,3})?")) {
      Path dir = recDir();
      if (dir == null) {
        ws.sendText("r rec: error: the app is not up yet");
        return;
      }
      if (!dir.toString().matches("\\S+")) {
        ws.sendText("r rec: error: the state folder's path has white space, which a control command cannot carry");
        return;
      }
      id2 = Long.toHexString(new java.security.SecureRandom().nextLong() & 0xffffffffffffL | 0x100000000000L).substring(0, 12);
      try {
        Files.createDirectories(dir);
      } catch (IOException e) {
        ws.sendText("r rec: error: " + e.getMessage());
        return;
      }
      String secs = t.length() > 8 ? t.substring(9) : "";
      cmd = "rec save " + (secs.isEmpty() ? "" : secs + " ") + dir.resolve(id2 + ".apkrec");
    }
    if (cmd == null) {
      ws.sendText("r refused: " + t);
      return;
    }
    final String rid = id2;
    if (rid != null) recordings.add(rid);
    send(cmd, r -> {
      if (rid != null && r.startsWith("error")) recordings.remove(rid);
      ws.sendText(rid != null && !r.startsWith("error") ? "r rec: saved " + rid : "r rec: " + r.replace(serve.state.toString(), "~"));
    });
  }

  /**
   * The control command a tab may send, normalised, or null. Only input and device settings: never commands that read
   * or write files on this machine (shot, record, pick, ...) or end the instance.
   */
  static String check(String text) {
    if (text.length() > 4000) return null;
    String t = text.strip();
    if (t.matches("touch (down|move|up|cancel) " + NUM + " " + NUM)) return t;
    if (t.matches("wheel " + NUM + " " + NUM + " " + NUM)) return t;
    if (t.matches("rotary " + NUM)) return t;
    if (t.matches("ambient (on|off)")) return t;
    if (t.matches("key [A-Z0-9_]{1,24}( [1-9])?( (ctrl|shift|alt|meta)){0,4}")) return t;
    if (t.matches("back|home|resume")) return t;
    // The tab's display pace (SimWindow.remoteVsync): only how this tab's instance times its frames.
    if (t.matches("vsync [0-9]{4,5} -?[0-9]{1,5}")) {
      // The instance's own limits (SimWindow.remoteVsync): it is not answered, so what it would refuse is refused here.
      String[] v = t.split(" ");
      int period = Integer.parseInt(v[1]), shift = Integer.parseInt(v[2]);
      return period >= 4000 && period <= 50000 && Math.abs(shift) <= period ? t : null;
    }
    // A frame rate cap: the page asks for 1 while it is hidden (it shows nothing) and 120 when it is back.
    if (t.matches("maxfps ([1-9]|[1-9][0-9]|1[01][0-9]|120)")) return t;
    if (t.matches("rotate (left|right|portrait|landscape)")) return t;
    if (t.matches("appearance (dark|light|toggle)")) return t;
    if (t.matches("device (" + String.join("|", DEVICES) + ")")) return t;
    if (t.matches("permission (allow|deny)")) return t;
    if (t.matches("locale [a-z]{2,3}(-[A-Z]{2})?")) return t;
    if (t.matches("fontscale (0\\.85|1|1\\.0|1\\.15|1\\.3|1\\.5|1\\.8|2|2\\.0)")) return t;
    // Not `audio off` / `video off`: those change the decoder list, so they are launch-only.
    if (t.matches("(?:audio (?:on|mute)|video (?:on|blank))")) return t;
    if (t.matches("animscale (0|0\\.5|1|1\\.5|2|5|10|toggle)")) return t;
    // What the fake camera sees (--camera fake), never `camera image PATH`: a tab names no host file.
    if (t.matches("camera (pattern|off)")) return t;
    if (t.matches("open [a-zA-Z][a-zA-Z0-9+.-]*:[^\\s\\p{Cntrl}]{1,2000}")) return t;
    if (t.startsWith("type ")) {
      // Control commands are one line, split on spaces: runs of white space become one space.
      String s = t.substring(5).replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
      return s.isEmpty() ? null : "type " + s;
    }
    return null;
  }

  /** The settings a kept session records (BeamStore.Leaving): as the tab last set them, not as it started. */
  private void trackSetting(String cmd) {
    if (cmd.startsWith("appearance ")) {
      String v = cmd.substring(11);
      if (v.equals("toggle")) v = "dark".equals(options.get("appearance")) ? "light" : "dark";
      options.put("appearance", v);
    } else if (cmd.startsWith("locale ")) {
      options.put("locale", cmd.substring(7));
    } else if (cmd.startsWith("fontscale ")) {
      options.put("fontscale", cmd.substring(10));
    } else if (cmd.startsWith("audio ") || cmd.startsWith("video ")) {
      int sep = cmd.indexOf(' ');
      options.put(cmd.substring(0, sep), cmd.substring(sep + 1));
    } else if (cmd.startsWith("animscale ")) {
      options.put("animscale", cmd.substring(10));
    }
  }

  /**
   * What only a tab of `apksim beam` may send besides {@link #check} (docs/beam.md, phase 3): the display sized to its
   * stage (`device WxH@D`, dp and density, 160-4096 dp a side and at most 4096 px, as --device-size takes), the Mac
   * window shown or hidden ("Show in Simulator"). The instance is the visitor's own: a size costs their memory, not a
   * server's.
   */
  static String checkBeam(String text) {
    String t = text.strip();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("device (\\d{3,4})x(\\d{3,4})@(\\d(?:\\.\\d{1,3})?)").matcher(t);
    if (m.matches()) {
      int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
      double d = Double.parseDouble(m.group(3));
      if (w < 160 || h < 160 || w > 4096 || h > 4096 || d < 0.75 || d > 4 || w * d > 4096 || h * d > 4096) return null;
      return t;
    }
    if (t.matches("window (front|hide)")) return t;
    return null;
  }

  private void send(String cmd, Consumer<String> onReply) {
    synchronized (replies) {
      if (ended) return;
      Consumer<String> c = onReply == null ? r -> {} : onReply;
      if (controlOut == null) {
        // Before the instance is up (the APK downloading, the app launching): kept, in order, and sent once it is, so
        // a setting changed meanwhile (dark mode, locale, device) is not lost. Bounded: a tab cannot queue without end.
        if (early.size() < 200) early.add(Map.entry(cmd, c));
        return;
      }
      replies.add(c);
      controlOut.println(cmd);
    }
  }

  /** Commands the tab sent before the control connection was open. Guarded by replies. */
  private final List<Map.Entry<String, Consumer<String>>> early = new ArrayList<>();

  private void readReplies() {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line; (line = r.readLine()) != null; ) {
        Consumer<String> c;
        synchronized (replies) {
          c = replies.poll();
        }
        if (c != null) c.accept(line);
      }
    } catch (IOException e) {
      // the instance went away
    }
  }

  private void onStats(String reply) {
    for (String kv : reply.split(" ")) {
      int eq = kv.indexOf('=');
      if (eq < 0) continue;
      String k = kv.substring(0, eq);
      String v = kv.substring(eq + 1);
      try {
        switch (k) {
          case "heapUsedMb" -> heapMb = Integer.parseInt(v);
          case "pid" -> pid = Long.parseLong(v);
          default -> {
          }
        }
      } catch (NumberFormatException e) {
        // an older instance
      }
    }
    rssMb = Serve.rssMb(pid);
    ws.sendText("s " + json());
  }

  private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Called every few seconds from serve's one timer: stats, and the idle timeout. The work runs on a thread of this
   * session's own, as anything written to a tab may block (a tab that stopped reading) and the timer serves them all.
   */
  /** WebSocket pings at this interval (WebSocket#ping), under the 60-100 s idle limits of common proxies. */
  static final long PING_MS = 20_000;
  private volatile long lastPing = System.currentTimeMillis();
  private final java.util.concurrent.atomic.AtomicBoolean pinging = new java.util.concurrent.atomic.AtomicBoolean();

  void tick() {
    if (ended) return;
    // The idle deadline first, apart from stats: a stats request stuck behind an app that stopped reading commands
    // must not keep the session alive.
    if (System.currentTimeMillis() - lastInput > serve.idleSeconds * 1000L) {
      if (idleStop.compareAndSet(false, true)) {
        Thread.ofVirtual().name("serve-stop-" + id).start(() -> stop("stopped after " + serve.idleSeconds + " s without input"));
      }
      return;
    }
    // Pings on a thread of their own, one at a time: a tab that stopped reading blocks its write, and this runs on the
    // timer every session shares.
    long now = System.currentTimeMillis();
    if (now - lastPing >= PING_MS && pinging.compareAndSet(false, true)) {
      lastPing = now;
      Thread.ofVirtual().name("serve-ping-" + id).start(() -> {
        try {
          ws.ping();
        } finally {
          pinging.set(false);
        }
      });
    }
    if (!ticking.compareAndSet(false, true)) return;
    Thread.ofVirtual().name("serve-tick-" + id).start(() -> {
      try {
        send("stats", this::onStats);
      } finally {
        ticking.set(false);
      }
    });
  }

  private final java.util.concurrent.atomic.AtomicBoolean idleStop = new java.util.concurrent.atomic.AtomicBoolean();

  String json() {
    long idle = (System.currentTimeMillis() - lastInput) / 1000;
    return "{\"id\":" + Http.q(id) + ",\"app\":" + Http.q(app.name()) + ",\"device\":" + Http.q(device)
        + ",\"pid\":" + pid + ",\"rssMb\":" + rssMb + ",\"heapMb\":" + heapMb + ",\"firstFrameMs\":" + firstFrameMillis
        + ",\"ageS\":" + (System.currentTimeMillis() - started) / 1000 + ",\"idleS\":" + idle
        + ",\"idleLimitS\":" + serve.idleSeconds + ",\"stopping\":" + ended + ",\"framesSent\":" + framesSent + ",\"mbSent\":" + (bytesSent >> 20)
        + ",\"window\":" + window() + ",\"pingRttMs\":" + Math.round(pingRttMs()) + ",\"ackRttMs\":" + Math.round(ackRttMs()) + ",\"frameMs\":" + Math.round(frameIntervalMs) + "}";
  }

  // ---------------------------------------------------------------- the end

  /** Ends the instance (quit, then kill after 5 s), closes the tab's connection and erases the app's data slot. */
  void stop(String why) {
    synchronized (this) {
      if (ended) return;
      ended = true;
      notifyAll();
    }
    try {
      endInstance();
    } finally {
      // Only now, with the instance gone: a stopping session still counts against --max.
      serve.ended(this);
    }
    // Last: tell the tab. That can block on a tab that stopped reading (a frame write holding the socket): give it two
    // seconds, then drop the connection, which frees the write, the session's thread and its connection permit.
    if (why != null) {
      Thread notice = Thread.ofVirtual().start(() -> {
        // An instance that died: its last log lines first, under the screen (bounded; the log is not a file the tab gets).
        if (abnormal) {
          List<String> lines;
          synchronized (this) {
            lines = new ArrayList<>(tail);
          }
          for (String l : lines) ws.sendText("r log: " + l);
        }
        ws.sendText("x " + why);
        ws.close(why.length() > 60 ? why.substring(0, 60) : why);
      });
      try {
        if (!notice.join(java.time.Duration.ofSeconds(2))) ws.abort();
      } catch (InterruptedException e) {
        ws.abort();
        Thread.currentThread().interrupt();
      }
    }
    logLine("[serve] session ended" + (why == null ? "" : ": " + why));
    synchronized (this) {
      try {
        if (log != null) log.close();
      } catch (IOException e) {
        // nothing to do
      }
      log = null;
    }
    System.err.printf("[serve] session %s ended%s%n", id, why == null ? "" : " (" + why + ")");
  }

  /** Quit, then kill after 5 s; then close the connections and erase the data slot. */
  private void endInstance() {
    // Best effort, on its own thread: an app whose main thread hangs stops reading commands, and a write to its full
    // socket would block. Ending the process below does not wait for it, and closing the socket frees that write.
    Thread.ofVirtual().name("serve-quit-" + id).start(() -> {
      synchronized (replies) {
        if (controlOut != null) controlOut.println("quit");
      }
    });
    Process p;
    synchronized (this) {
      p = process;
    }
    try {
      if (p != null && !p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroy();
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
      }
      // A warm launch runs in the standby's JVM, outside our child's process tree: make sure it has gone too. That
      // JVM as the kernel knows it (the control port's listener), else the one the instance's output named if its own
      // launch.info says it launched this APK: the output alone is the app's to write, and could name any process.
      long jvm = instancePid > 0 ? instancePid : pid;
      if (jvm > 0 && (jvm == instancePid || launched(serve, jvm, app.path()))) {
        ProcessHandle.of(jvm).ifPresent(h -> {
          try {
            h.onExit().get(5, TimeUnit.SECONDS);
          } catch (Exception e) {
            h.destroyForcibly();
          }
        });
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    closeQuietly(control);
    closeQuietly(stream);
    keepRecordings();
    // The data slot is announced on the instance's output: read it to the end (the process has gone, so the end comes)
    // before erasing, or an app that died just after taking its slot would leave it behind.
    Thread reading = logReader;
    if (reading != null) {
      try {
        reading.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (serve.beam) beamCaches();
    eraseData();
  }

  /**
   * A beamed instance's caches (`STATE/beam/out/<pid>`, BeamSandbox) into its build's cache: only that build's, so what
   * an app wrote there can reach no other app. Translated classes as regular files named as the cache names them, never
   * a link; the APK cache entry of this build's file as a whole (a link in it stays a link: the next instance's sandbox
   * reads nothing through it, and the agent never follows one). The out folder goes either way.
   */
  /** The PID of the process listening on this loopback port (lsof, the kernel's answer), or -1. */
  static long listenerPid(int port) {
    try {
      Process l = new ProcessBuilder("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t").redirectErrorStream(true).start();
      String out = new String(l.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      l.waitFor(5, TimeUnit.SECONDS);
      return out.matches("\\d{1,10}") ? Long.parseLong(out) : -1;  // one listener, or none known
    } catch (IOException | RuntimeException e) {
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  /**
   * A beamed JVM's crash report (`-XX:ErrorFile` in its scratch, the only place it may write): first renamed into the
   * agent's own folder (a link moves as a link), then read only if it is a regular file, so the app cannot have the
   * agent read another file through a link and show its comment lines to the page.
   */
  private List<String> beamHsErr() {
    long bp = instancePid;
    if (bp <= 0) return List.of();
    Path src = serve.state.resolve("tmp").resolve(Long.toString(bp)).resolve("hs_err_pid" + bp + ".log");
    Path dir = serve.state.resolve("beam/import").resolve("crash-" + bp + "-" + Long.toHexString(System.nanoTime()));
    try {
      if (!Files.exists(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
      Files.createDirectories(dir);
      Path f = dir.resolve(src.getFileName().toString());
      Files.move(src, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      List<String> hs = Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? Crash.takeHsErr(dir, bp) : List.of();
      BeamStore.deleteTree(dir);
      return hs;
    } catch (IOException | RuntimeException e) {
      BeamStore.deleteTree(dir);
      return List.of("[hs_err] unreadable: " + e.getMessage());
    }
  }

  /** {@link #listenerPid} of the instance's control port, else -1 (then a beamed one's caches are not kept). */
  private volatile long instancePid = -1;

  private static final Object BEAM_CACHE_SWAP = new Object();

  private void beamCaches() {
    keepBeamCaches(serve, instancePid, app.path(), this::logLine);
  }

  /**
   * As {@link #beamCaches} for any beamed JVM that has exited (a session's, a cache warm-up's): its out folder's caches
   * into the build's cache, for the next launch of that build.
   */
  static void keepBeamCaches(Serve serve, long pid, Path apk, java.util.function.Consumer<String> log) {
    if (pid <= 0) {
      log.accept("[beam] the instance's process was not known: its caches are not kept");
      return;
    }
    Path given = serve.state.resolve("beam/out").resolve(Long.toString(pid));
    String build = serve.catalog.buildHash(apk);
    // Out of the instance's reach first: renamed into the agent's own folder, which no beamed instance may write (a
    // child process the app left running could otherwise swap a folder for a link while this reads it), then used only
    // if what arrived is a real folder. Renaming a link moves the link, never what it names.
    Path out = serve.state.resolve("beam/import").resolve(Long.toString(pid) + "-" + Long.toHexString(System.nanoTime()));
    try {
      if (!Files.exists(given, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
      Files.createDirectories(out.getParent());
      Files.move(given, out, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      // A move keeps its time, which the sweep reads (it removes what an ended agent left an hour ago): now, so a
      // session that ran for hours is not swept while this reads it. A link stays untouched (NOFOLLOW below).
      if (Files.isDirectory(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        Files.setLastModifiedTime(out, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
      }
    } catch (IOException | RuntimeException e) {
      log.accept("[beam] could not take the instance's caches: " + e);
      return;
    }
    if (!Files.isDirectory(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.deleteIfExists(out);
      } catch (IOException e) {
        // the sweep's
      }
      return;
    }
    try {
      Path apkCache = out.resolve("apk-cache");
      if (build != null && Files.isDirectory(apkCache, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        try (var entries = Files.newDirectoryStream(apkCache)) {
          for (Path e : entries) {
            if (!e.getFileName().toString().matches(build + "\\.apk-[0-9a-f]{16}") || !Files.isDirectory(e, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            // Swapped in by renames, not emptied and refilled: another session of this build starting meanwhile
            // clones `apk` (BeamSandbox.seedApkCache) and would have found it half deleted or empty.
            Path into = serve.beamCache(build).resolve("apk");
            String tag = Long.toHexString(System.nanoTime());
            Path staged = into.resolveSibling("apk.new-" + tag);
            Path old = into.resolveSibling("apk.old-" + tag);
            Files.createDirectories(staged);
            Files.move(e, staged.resolve(e.getFileName().toString()));
            boolean had;
            synchronized (BEAM_CACHE_SWAP) {  // two sessions of one build ending together
              had = Files.exists(into, java.nio.file.LinkOption.NOFOLLOW_LINKS);
              if (had) Files.move(into, old, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
              Files.move(staged, into, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            if (had) BeamStore.deleteTree(old);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      log.accept("[beam] could not keep the APK cache entry: " + e);
    }
    try {
      Path classes = out.resolve("classes");
      if (build != null && Files.isDirectory(classes, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        Path into = serve.beamCache(build).resolve("classes");
        try (var runtimes = Files.newDirectoryStream(classes)) {
          for (Path rt : runtimes) {
            if (!Files.isDirectory(rt, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !rt.getFileName().toString().matches("[0-9a-zA-Z-]{1,80}")) continue;
            Path dest = into.resolve(rt.getFileName().toString());
            Files.createDirectories(dest);
            int moved = 0;
            try (var files = Files.newDirectoryStream(rt)) {
              for (Path f : files) {
                String n = f.getFileName().toString();
                if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !n.matches("[0-9a-zA-Z_.-]{1,200}\\.(pack|classes)")) continue;
                Files.move(f, dest.resolve(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moved++;
              }
            }
            if (moved > 0) log.accept("[beam] kept " + moved + " translated class files for the next launch of this build");
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      log.accept("[beam] could not keep translated classes: " + e);
    }
    SlotPack.deleteTree(out);  // never through a link
  }

  private void eraseData() {
    try {
      eraseOrKeep();
    } finally {
      closeSavedLock();
      if (seedSlot != null) {  // its saved session may be opened again now
        serve.reopened.remove(seedSlot);
        serve.reopenedChanged.remove(seedSlot);
      }
    }
  }

  private void closeSavedLock() {
    BeamStore.SavedHold l = savedLock;
    savedLock = null;
    if (l != null) l.close();
  }

  private void eraseOrKeep() {
    eraseUnusedSeed();
    if (dataDir == null) return;
    if (serve.beam && savedId != null) {
      // Its shared hold given up first: the write-back takes the session's lock exclusively (BeamStore.writeBack).
      closeSavedLock();
      Map<String, Object> rec = serve.store.saved(savedId);
      if (rec != null && seedSlot != null && dataDir.getFileName().toString().equals(seedSlot + "-0") && serve.store.writeBack(dataDir, rec, savedChanged)) {
        logLine("[beam] what the app wrote is kept in saved session " + savedId);
        System.err.printf("[beam] session %s: saved session %s updated (%s from %s)%n", this.id, savedId, app.name(), site);
        return;
      }
      logLine("[beam] could not put the data back in saved session " + savedId + " (opened by apksim beam run meanwhile, or forgotten): kept as a new one");
    }
    if (serve.beam && keep) {
      List<String> signers;
      try {
        signers = ApkSigners.of(app.path()).certs();
      } catch (IOException | RuntimeException e) {
        signers = null;  // a newer build then needs this one still here to start on its data
      }
      String id = serve.store.save(new BeamStore.Leaving(dataDir, app.name(), serve.catalog.buildHash(app.path()), app.launch(),
          deviceSpec(device), options.get("appearance"), options.get("locale"), options.get("fontscale"),
          options.get("permissions"), site, signers));
      if (id != null) {
        logLine("[beam] kept on this Mac as " + id + " (apksim beam run " + id + ")");
        System.err.printf("[beam] session %s kept as %s: %s from %s%n", this.id, id, app.name(), site);
        return;
      }
      logLine("[beam] could not keep the data: erased");
    }
    eraseSlot(serve, dataDir, this::logLine);
  }

  /**
   * A moved session's slot (phase 5) the instance did not take: the agent unpacks into the package the pack names,
   * which it cannot check against the build's (no manifest reader here), so a pack naming another package (or an
   * instance that never started) leaves its slot unused. It goes with the session, as the slot it ran in would.
   */
  private void eraseUnusedSeed() {
    SlotPack.dropSeed(serve.state, seedSlot, dataDir == null ? null : dataDir.toAbsolutePath().normalize(), this::logLine);
  }

  /**
   * Empties a data slot an instance held (a session's, or a cache warm-up's), once nothing holds its lock
   * (Storage.dataDir), within the state folder.
   */
  static void eraseSlot(Serve serve, Path dataDir, Consumer<String> logLine) {
    Path dir = dataDir.toAbsolutePath().normalize();
    // Only a slot of this server's own (serve-N, or beam-N for the agent, under the state folder's app data).
    String slotName = dir.getFileName().toString();
    if (!dir.startsWith(serve.state.resolve("cache/data").toAbsolutePath().normalize())
        || !(slotName.startsWith(serve.home + "-") || slotName.matches("bm[0-9a-f]{12}-[0-9]+"))) return;  // bm: moved or restored data
    Path lockFile = dir.resolve(".lock");
    try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      FileLock l;
      try {
        l = ch.tryLock();
      } catch (java.nio.channels.OverlappingFileLockException e) {
        l = null;  // another session's cleanup in this process holds it
      }
      if (l == null) return;  // another instance took the slot already (with --fresh), or is erasing it
      // Through folder handles, never a link at any level (SlotPack.emptyTree): the instance may have left a process
      // writing there, which could swap a folder for a link between a walk and a delete by path.
      SlotPack.emptyTree(dir, java.util.Set.of(".lock"));
      // And what the slot kept of the device beside it (SlotDevice: its /sdcard and Settings values).
      SlotPack.deleteTree(dir.resolveSibling(dir.getFileName() + ".device"));
      l.release();
    } catch (IOException | RuntimeException e) {
      logLine.accept("[serve] could not erase " + dir + ": " + e);
    }
  }

  private static void closeQuietly(Socket s) {
    try {
      if (s != null) s.close();
    } catch (IOException e) {
      // nothing to do
    }
  }
}
