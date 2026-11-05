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
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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
 * to the tab, at most two in flight (the tab acknowledges each one it draws, and newer frames replace the one waiting),
 * and the tab's input to the instance, only as the commands {@link #check} allows.
 */
final class Session {
  private static final Pattern CONTROL_PORT = Pattern.compile("\\[apksim\\] control port (\\d+)");
  private static final Pattern PERMISSION = Pattern.compile("\\[apksim\\] permission request waiting: (.*?) \\(answer");
  private static final String NUM = "-?\\d{1,5}(\\.\\d{1,3})?";
  private static final int MAX_IN_FLIGHT = 2;
  static final List<String> DEVICES = List.of("phone", "foldable", "tablet", "desktop", "tv");

  final String id;
  final Catalog.App app;
  final Map<String, String> options;
  final long started = System.currentTimeMillis();
  private final Serve serve;
  private final WebSocket ws;
  private Process process;
  private Writer log;
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
  private volatile boolean ended;
  private final CompletableFuture<Integer> port = new CompletableFuture<>();
  // Frame relay: the newest frame not sent yet, and how many the tab has not acknowledged. Guarded by this.
  private byte[] waiting;
  private int inFlight;

  Session(Serve serve, String id, Catalog.App app, Map<String, String> options, WebSocket ws) {
    this.serve = serve;
    this.id = id;
    this.app = app;
    this.options = options;
    this.ws = ws;
    this.device = options.getOrDefault("device", "phone");
  }

  /** Runs the session until the tab closes, the app exits or it times out. */
  void run() {
    try {
      start();
      Thread reader = Thread.ofVirtual().name("serve-frames-" + id).start(this::readFrames);
      Thread sender = Thread.ofVirtual().name("serve-send-" + id).start(this::sendFrames);
      ws.readLoop(this::onMessage);
      reader.interrupt();
      sender.interrupt();
    } catch (Exception e) {
      if (!ended) ws.sendText("x could not start: " + e.getMessage());
      logLine("[serve] " + e);
    } finally {
      stop(null);
    }
  }

  private void start() throws Exception {
    Path apk = serve.catalog.file(app);
    Files.createDirectories(serve.logs);
    log = Files.newBufferedWriter(serve.logs.resolve(id + ".log"), StandardCharsets.UTF_8);
    // Never the owner's saved sign-ins (work/autofill): other people use this instance.
    List<String> cmd = new ArrayList<>(List.of(serve.launcher.toString(), "--headless", "--fresh", "--no-autofill", "--control", "auto"));
    cmd.addAll(List.of("--device", device));
    String appearance = options.get("appearance");
    if ("dark".equals(appearance) || "light".equals(appearance)) cmd.addAll(List.of("--appearance", appearance));
    String locale = options.get("locale");
    if (locale != null && locale.matches("[a-z]{2,3}(-[A-Z]{2})?")) cmd.addAll(List.of("--locale", locale));
    String font = options.get("fontscale");
    if (font != null && font.matches("[0-2](\\.\\d{1,2})?")) cmd.addAll(List.of("--font-scale", font));
    String perms = options.getOrDefault("permissions", "ask");
    if (perms.equals("grant") || perms.equals("deny")) cmd.addAll(List.of("--permissions", perms));
    if (serve.noNetwork) cmd.add("--no-network");
    cmd.add(apk.toString());
    ws.sendText("p launching " + app.name());
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
        .redirectErrorStream(true);
    pb.directory(serve.logs.toFile());
    // Under the lifecycle lock: a stop() that came first (idle while a configured URL downloaded) must not leave a
    // process it never saw.
    synchronized (this) {
      if (ended) throw new IOException("the session ended before the app started");
      process = pb.start();
    }
    Thread.ofVirtual().name("serve-log-" + id).start(this::readLog);
    Process started = process;
    started.onExit().thenRun(() -> {
      if (!ended) Thread.ofVirtual().start(() -> stop("the app exited (status " + started.exitValue() + ")"));
    });
    int p = port.get(serve.launchTimeoutSeconds, TimeUnit.SECONDS);
    control = connect(p);
    controlOut = new PrintWriter(new java.io.OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8), true);
    Thread.ofVirtual().name("serve-replies-" + id).start(this::readReplies);
    stream = connect(p);
    stream.getOutputStream().write(("stream " + serve.quality + "\n").getBytes(StandardCharsets.US_ASCII));
    send("stats", this::onStats);
    String open = options.get("open");
    if (open != null && check("open " + open) != null) send("open " + open, r -> ws.sendText("r open: " + r));
    String orientation = options.get("orientation");
    if ("landscape".equals(orientation)) send("rotate landscape", r -> {});
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
        Matcher m = CONTROL_PORT.matcher(line);
        if (m.find()) port.complete(Integer.parseInt(m.group(1)));
        m = PERMISSION.matcher(line);
        if (m.find()) ws.sendText("q " + m.group(1));
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
    } catch (IOException e) {
      // nothing to do
    }
  }

  // ---------------------------------------------------------------- frames

  private void readFrames() {
    try {
      DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(stream.getInputStream(), 1 << 16));
      // "ok stream"
      StringBuilder line = new StringBuilder();
      for (int c; (c = in.read()) != '\n'; ) {
        if (c < 0) return;
        line.append((char) c);
      }
      while (!ended) {
        int len = in.readInt();
        if (len < 20 || len > 64 << 20) return;
        byte[] frame = new byte[len];
        in.readFully(frame);
        if (firstFrameMillis < 0) {
          firstFrameMillis = System.currentTimeMillis() - started;
          ws.sendText("p first frame after " + firstFrameMillis + " ms");
          System.err.printf("[serve] session %s: %s first frame after %d ms%n", id, app.name(), firstFrameMillis);
        }
        synchronized (this) {
          waiting = frame;
          notifyAll();
        }
      }
    } catch (IOException e) {
      // the instance went away
    }
  }

  private void sendFrames() {
    try {
      while (!ended) {
        byte[] f;
        synchronized (this) {
          while (waiting == null || inFlight >= MAX_IN_FLIGHT) wait();
          f = waiting;
          waiting = null;
          inFlight++;
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
    if (inFlight > 0) inFlight--;
    notifyAll();
  }

  // ---------------------------------------------------------------- input

  private void onMessage(String text) {
    if (text.equals("ack")) {
      acked();
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
    String cmd = check(text);
    if (cmd == null) {
      ws.sendText("r refused: " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
      return;
    }
    lastInput = System.currentTimeMillis();
    if (cmd.startsWith("device ")) device = cmd.substring(7);
    boolean quiet = cmd.startsWith("touch ") || cmd.startsWith("wheel ") || cmd.startsWith("type ") || cmd.startsWith("key ");
    send(cmd, quiet ? null : r -> ws.sendText("r " + cmd.split(" ")[0] + ": " + r));
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
    if (t.matches("key [A-Z0-9_]{1,24}( [1-9])?( (ctrl|shift|alt|meta)){0,4}")) return t;
    if (t.matches("back|home|resume")) return t;
    if (t.matches("rotate (left|right|portrait|landscape)")) return t;
    if (t.matches("appearance (dark|light|toggle)")) return t;
    if (t.matches("device (" + String.join("|", DEVICES) + ")")) return t;
    if (t.matches("permission (allow|deny)")) return t;
    if (t.matches("locale [a-z]{2,3}(-[A-Z]{2})?")) return t;
    if (t.matches("fontscale (0\\.85|1|1\\.0|1\\.15|1\\.3|1\\.5|1\\.8|2|2\\.0)")) return t;
    if (t.matches("open [a-zA-Z][a-zA-Z0-9+.-]*:[^\\s\\p{Cntrl}]{1,2000}")) return t;
    if (t.startsWith("type ")) {
      // Control commands are one line, split on spaces: runs of white space become one space.
      String s = t.substring(5).replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
      return s.isEmpty() ? null : "type " + s;
    }
    return null;
  }

  private void send(String cmd, Consumer<String> onReply) {
    synchronized (replies) {
      if (controlOut == null || ended) return;
      replies.add(onReply == null ? r -> {} : onReply);
      controlOut.println(cmd);
    }
  }

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
          case "data" -> dataDir = v.equals("null") ? null : Path.of(v);
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
  void tick() {
    if (ended || !ticking.compareAndSet(false, true)) return;
    Thread.ofVirtual().name("serve-tick-" + id).start(() -> {
      try {
        long idle = System.currentTimeMillis() - lastInput;
        if (idle > serve.idleSeconds * 1000L) {
          stop("stopped after " + serve.idleSeconds + " s without input");
        } else {
          send("stats", this::onStats);
        }
      } finally {
        ticking.set(false);
      }
    });
  }

  String json() {
    long idle = (System.currentTimeMillis() - lastInput) / 1000;
    return "{\"id\":" + Http.q(id) + ",\"app\":" + Http.q(app.name()) + ",\"device\":" + Http.q(device)
        + ",\"pid\":" + pid + ",\"rssMb\":" + rssMb + ",\"heapMb\":" + heapMb + ",\"firstFrameMs\":" + firstFrameMillis
        + ",\"ageS\":" + (System.currentTimeMillis() - started) / 1000 + ",\"idleS\":" + idle
        + ",\"idleLimitS\":" + serve.idleSeconds + ",\"framesSent\":" + framesSent + ",\"mbSent\":" + (bytesSent >> 20) + "}";
  }

  // ---------------------------------------------------------------- the end

  /** Ends the instance (quit, then kill after 5 s), closes the tab's connection and erases the app's data slot. */
  void stop(String why) {
    synchronized (this) {
      if (ended) return;
      ended = true;
      notifyAll();
    }
    serve.ended(this);
    synchronized (replies) {
      if (controlOut != null) controlOut.println("quit");
    }
    Process p;
    synchronized (this) {
      p = process;
    }
    try {
      if (p != null && !p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroy();
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
      }
      // A warm launch runs in the standby's JVM, outside our child's process tree: make sure it has gone too.
      if (pid > 0) {
        ProcessHandle.of(pid).ifPresent(h -> {
          if (h.info().command().map(c -> c.contains("java")).orElse(false)) {
            try {
              h.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
              h.destroyForcibly();
            }
          }
        });
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    closeQuietly(control);
    closeQuietly(stream);
    eraseData();
    // Last: telling the tab can block on one that stopped reading (close gives up after a second).
    if (why != null) {
      Thread.ofVirtual().start(() -> {
        ws.sendText("x " + why);
        ws.close(why.length() > 60 ? why.substring(0, 60) : why);
      });
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

  /** Empties the data slot the instance held, once nothing holds its lock (Storage.dataDir), within the state folder. */
  private void eraseData() {
    if (dataDir == null) return;
    Path dir = dataDir.toAbsolutePath().normalize();
    if (!dir.startsWith(serve.state.resolve("cache/data").toAbsolutePath().normalize())) return;
    Path lockFile = dir.resolve(".lock");
    try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      FileLock l = ch.tryLock();
      if (l == null) return;  // another instance took the slot already, with --fresh
      try (Stream<Path> all = Files.walk(dir)) {
        for (Path p : all.filter(p -> !p.equals(dir) && !p.equals(lockFile)).sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
      l.release();
    } catch (IOException e) {
      logLine("[serve] could not erase " + dir + ": " + e);
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
