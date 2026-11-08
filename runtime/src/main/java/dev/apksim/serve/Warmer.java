package dev.apksim.serve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warms apksim's per-APK cache (translated classes, extracted libraries) in the background, so a session opened on an
 * APK just uploaded does not spend minutes on it (Linux has no warm standby, and a large app's first launch there
 * took 4-5 minutes). One APK at a time, at low priority (`nice`), by an ordinary headless launch that exits at its
 * first frame, which leaves the caches exactly as a session needs them. A session that opens an APK while it is
 * warming waits for it, with its progress; one that opens an APK still queued takes it off the queue and warms it
 * itself. A failed warm-up only means no head start.
 */
final class Warmer {
  private static final Pattern PROGRESS = Pattern.compile("\\[apksim\\] translated (\\d+) classes");
  private static final Pattern DATA_SLOT = Pattern.compile("\\[apksim\\] data slot (.+) pid (\\d+)$");
  enum State { QUEUED, WARMING, READY, FAILED, SKIPPED }

  private static final class Job {
    final Catalog.App app;
    final Path apk;
    volatile State state = State.QUEUED;
    volatile String progress = "";
    volatile String error = "";
    volatile long jvmPid = -1;  // the JVM a warm launch runs in (a standby's), not the client we started
    volatile long finishedAt;
    final CompletableFuture<Void> done = new CompletableFuture<>();

    Job(Catalog.App app, Path apk) {
      this.app = app;
      this.apk = apk;
    }
  }

  private final Serve serve;
  private final Map<String, Job> jobs = new LinkedHashMap<>();  // by APK path (apps sharing a file share its cache)
  /** App id to the APK path its job is under: a catalog URL app has no path of its own until it is downloaded. */
  private final Map<String, String> byId = new java.util.HashMap<>();
  /** APKs a session launched without a warm-up (it translates for itself): never warmed beside it afterwards. */
  private final Map<String, Long> taken = new java.util.HashMap<>();
  private boolean closed;
  private Thread loopThread;
  private volatile Process active;
  private final ArrayDeque<Job> queue = new ArrayDeque<>();
  private boolean running;

  Warmer(Serve serve) {
    this.serve = serve;
  }

  /** Queues an app whose APK is a file now (an upload, a finished download); anything else is ignored. */
  void enqueue(Catalog.App app) {
    if (app.path() == null) return;
    String key = app.path().toString();
    synchronized (this) {
      byId.put(app.id(), key);
      if (closed || taken.containsKey(key) || jobs.containsKey(key)) return;
      Job j = new Job(app, app.path());
      jobs.put(key, j);
      queue.add(j);
      if (!running) {
        running = true;
        loopThread = Thread.ofPlatform().name("serve-warm").daemon().start(this::loop);
      }
    }
  }

  /** Queues every listed app: a file at once, an URL once downloaded (its first session would download it anyway). */
  void enqueueAll(List<Catalog.App> apps) {
    Thread.ofVirtual().name("serve-warm-catalog").start(() -> {
      for (Catalog.App a : apps) {
        try {
          if (a.path() != null) {
            if (Files.isRegularFile(a.path())) enqueue(a);
          } else if (a.url() != null) {
            Path p = serve.catalog.file(a, w -> {}, () -> false);
            enqueue(new Catalog.App(a.id(), a.name(), p, a.url(), false, a.launch()));  // remembered under a.id()
          }
        } catch (IOException | RuntimeException e) {
          System.err.println("[serve] not warming " + a.name() + ": " + e.getMessage());
        }
      }
    });
  }

  private void loop() {
    while (true) {
      Job j;
      synchronized (this) {
        j = closed ? null : queue.poll();
        if (j == null) {
          running = false;
          return;
        }
        if (j.state != State.QUEUED) continue;  // a session took it
        j.state = State.WARMING;
      }
      try {
        warm(j);
        j.state = State.READY;
        j.finishedAt = System.currentTimeMillis();
      } catch (Exception e) {
        j.error = String.valueOf(e.getMessage());
        j.state = State.FAILED;
        j.finishedAt = System.currentTimeMillis();
        System.err.printf("[serve] warming %s failed: %s%n", j.app.name(), j.error);
      } finally {
        j.done.complete(null);
      }
    }
  }

  private void warm(Job j) throws Exception {
    long t0 = System.currentTimeMillis();
    System.err.printf("[serve] warming the cache of %s%n", j.app.name());
    List<String> cmd = new ArrayList<>();
    if (Files.isExecutable(Path.of("/usr/bin/nice"))) cmd.addAll(List.of("/usr/bin/nice", "-n", "10"));
    cmd.addAll(List.of(serve.launcher.toString(), "--headless", "--fresh", "--no-autofill", "--data-slots", "serve",
        "--exit-after-first-frame"));
    if (serve.noNetwork) cmd.add("--no-network");
    if (serve.moveSharedData) cmd.add("--move-shared-data");
    cmd.addAll(j.app.launch());
    cmd.add(j.apk.toString());
    Files.createDirectories(serve.logs);
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
        .redirectErrorStream(true).directory(serve.logs.toFile());
    serve.instanceEnvironment(pb);
    Process p;
    synchronized (this) {
      if (closed) throw new IOException("the server is stopping");
      p = pb.start();
      active = p;
    }
    java.util.concurrent.atomic.AtomicReference<Path> slot = new java.util.concurrent.atomic.AtomicReference<>();
    try {
      run(j, p, slot, t0);
    } finally {
      active = null;
      if (p.isAlive()) {
        p.destroyForcibly();
        p.waitFor(5, TimeUnit.SECONDS);
      }
      Session.endJvm(j.jvmPid);  // a warm launch: the standby's JVM may still be flushing after its client went
      // Its data slot (serve-N and its .device folder), as a session's is erased: nothing may be left behind.
      if (slot.get() != null) Session.eraseSlot(serve, slot.get(), l -> System.err.println(l));
    }
  }

  private void run(Job j, Process p, java.util.concurrent.atomic.AtomicReference<Path> slot, long t0) throws Exception {
    List<String> last = new ArrayList<>();
    Crash.Reason reason = new Crash.Reason();
    Thread reader = Thread.ofVirtual().name("serve-warm-log").start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line; (line = r.readLine()) != null; ) {
          reason.line(line);
          Matcher ds = DATA_SLOT.matcher(line);
          if (ds.find()) {
            slot.set(Path.of(ds.group(1)));
            j.jvmPid = Long.parseLong(ds.group(2));  // a warm launch runs in the standby's JVM, not the client we started
          }
          Matcher m = PROGRESS.matcher(line);
          if (m.find()) j.progress = "translating classes: " + m.group(1);
          synchronized (last) {
            last.add(Crash.clip(line));
            if (last.size() > 10) last.remove(0);
          }
        }
      } catch (IOException e) {
        // the process ended
      }
    });
    long limit = Math.max(900, serve.launchTimeoutSeconds * 5L);
    if (!p.waitFor(limit, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      p.waitFor(5, TimeUnit.SECONDS);
      reader.join(2000);
      throw new IOException(closed ? "the server is stopping" : "no first frame after " + limit + " s");
    }
    reader.join(2000);
    List<String> hs = Crash.takeHsErr(serve.logs, p.pid(), j.jvmPid, reason.hsErrPid());
    if (p.exitValue() != 0) {
      String why = Crash.hsErrReason(hs);
      if (why == null) why = reason.text();
      if (why == null) synchronized (last) { why = last.isEmpty() ? "" : last.get(last.size() - 1); }
      throw new IOException("exit " + p.exitValue() + (why == null || why.isEmpty() ? "" : ": " + why));
    }
    System.err.printf("[serve] warmed %s in %d s%n", j.app.name(), (System.currentTimeMillis() - t0) / 1000);
  }

  /**
   * Before a session launches an app: waits while its APK is being warmed (progress to `progress`, at most once a
   * second, until it ends: it always does, in at most 15 minutes, and a session's own idle limit or closed tab stops the
   * wait too, so a launch never runs beside a warm-up still translating); takes it off the queue when it has not started, so the session warms it itself.
   */
  void await(Catalog.App app, Path apk, Consumer<String> progress, BooleanSupplier stopped) {
    String key = apk.toString();
    Job j;
    synchronized (this) {
      byId.putIfAbsent(app.id(), key);
      j = jobs.get(key);
      if (j == null) {
        taken.put(key, System.currentTimeMillis());  // not warmed (yet): this session translates for itself, and nothing warms it beside it
        return;
      }
      if (j.state == State.QUEUED) {
        skip(j);
        taken.put(key, System.currentTimeMillis());
        return;
      }
    }
    if (j.state != State.WARMING) return;
    String last = "";
    while (!stopped.getAsBoolean()) {
      try {
        j.done.get(1, TimeUnit.SECONDS);
        return;
      } catch (java.util.concurrent.TimeoutException e) {
        String now = "warming the app's cache" + (j.progress.isEmpty() ? "" : " (" + j.progress + ")");
        if (!now.equals(last)) progress.accept(now);
        last = now;
      } catch (Exception e) {
        return;
      }
    }
  }

  /** The cache state of an app for `GET /api/apps/ID` and `/api/status`: "none", "queued", "warming", "ready", "failed". */
  private Job job(Catalog.App app) {
    if (app == null) return null;
    String key = byId.get(app.id());
    if (key == null && app.path() != null) key = app.path().toString();
    return key == null ? null : jobs.get(key);
  }

  synchronized String state(Catalog.App app) {
    Job j = job(app);
    if (j == null || j.state == State.SKIPPED) return "none";
    return j.state.name().toLowerCase(java.util.Locale.ROOT);
  }

  synchronized String detail(Catalog.App app) {
    Job j = job(app);
    if (j == null) return "";
    return j.state == State.FAILED ? j.error : j.progress;
  }

  private static void skip(Job j) {
    j.state = State.SKIPPED;
    j.finishedAt = System.currentTimeMillis();
  }

  /**
   * Forgets what is a day old and over, alongside `Catalog.sweep`: finished jobs, the ids that pointed at them and the
   * taken marks, except the catalog's own apps (`keepIds`) and anything queued or warming.
   */
  synchronized void prune(java.util.Set<String> keepIds) {
    long cutoff = System.currentTimeMillis() - 24 * 3_600_000L;
    java.util.Set<String> keepKeys = new java.util.HashSet<>();
    for (String id : keepIds) if (byId.get(id) != null) keepKeys.add(byId.get(id));
    jobs.entrySet().removeIf(e -> {
      Job j = e.getValue();
      boolean over = j.state == State.READY || j.state == State.FAILED || j.state == State.SKIPPED;
      return over && j.finishedAt < cutoff && !keepKeys.contains(e.getKey());
    });
    taken.entrySet().removeIf(e -> e.getValue() < cutoff && !jobs.containsKey(e.getKey()));
    byId.entrySet().removeIf(e -> !keepIds.contains(e.getKey()) && !jobs.containsKey(e.getValue()) && !taken.containsKey(e.getValue()));
  }

  /**
   * The server is stopping: nothing queued will run, a running warm-up's instance is ended and its data slot erased
   * (by the warm-up's own thread), and this waits for that, up to 15 s.
   */
  void shutdown() {
    Thread t;
    synchronized (this) {
      closed = true;
      for (Job j : queue) {
        skip(j);
        j.done.complete(null);
      }
      queue.clear();
      t = loopThread;
    }
    Process p = active;
    if (p != null) {
      p.destroy();
      try {
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
      } catch (InterruptedException e) {
        p.destroyForcibly();
        Thread.currentThread().interrupt();
      }
    }
    try {
      if (t != null) t.join(15_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
