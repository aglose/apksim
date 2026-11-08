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
  enum State { QUEUED, WARMING, READY, FAILED, SKIPPED }

  private static final class Job {
    final Catalog.App app;
    final Path apk;
    volatile State state = State.QUEUED;
    volatile String progress = "";
    volatile String error = "";
    final CompletableFuture<Void> done = new CompletableFuture<>();

    Job(Catalog.App app, Path apk) {
      this.app = app;
      this.apk = apk;
    }
  }

  private final Serve serve;
  private final Map<String, Job> jobs = new LinkedHashMap<>();  // by APK path (apps sharing a file share its cache)
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
      if (jobs.containsKey(key)) return;
      Job j = new Job(app, app.path());
      jobs.put(key, j);
      queue.add(j);
      if (!running) {
        running = true;
        Thread.ofPlatform().name("serve-warm").daemon().start(this::loop);
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
            enqueue(new Catalog.App(a.id(), a.name(), p, a.url(), false, a.launch()));
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
        j = queue.poll();
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
      } catch (Exception e) {
        j.error = String.valueOf(e.getMessage());
        j.state = State.FAILED;
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
    cmd.addAll(j.app.launch());
    cmd.add(j.apk.toString());
    Files.createDirectories(serve.logs);
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
        .redirectErrorStream(true).directory(serve.logs.toFile());
    serve.instanceEnvironment(pb);
    Process p = pb.start();
    List<String> last = new ArrayList<>();
    Crash.Reason reason = new Crash.Reason();
    Thread reader = Thread.ofVirtual().name("serve-warm-log").start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line; (line = r.readLine()) != null; ) {
          reason.line(line);
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
      throw new IOException("no first frame after " + limit + " s");
    }
    reader.join(2000);
    List<String> hs = Crash.takeHsErr(serve.logs, p.pid());
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
   * second, for at most `maxSeconds`); takes it off the queue when it has not started, so the session warms it itself.
   */
  void await(Catalog.App app, Consumer<String> progress, BooleanSupplier stopped, int maxSeconds) {
    if (app.path() == null) return;
    Job j;
    synchronized (this) {
      j = jobs.get(app.path().toString());
      if (j == null) return;
      if (j.state == State.QUEUED) {
        j.state = State.SKIPPED;
        return;
      }
    }
    if (j.state != State.WARMING) return;
    String last = "";
    for (int i = 0; i < maxSeconds && !stopped.getAsBoolean(); i++) {
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
  synchronized String state(Catalog.App app) {
    if (app == null || app.path() == null) return "none";
    Job j = jobs.get(app.path().toString());
    if (j == null || j.state == State.SKIPPED) return "none";
    return j.state.name().toLowerCase(java.util.Locale.ROOT);
  }

  synchronized String detail(Catalog.App app) {
    Job j = app == null || app.path() == null ? null : jobs.get(app.path().toString());
    if (j == null) return "";
    return j.state == State.FAILED ? j.error : j.progress;
  }

  /** One JSON object per APK warmed or being warmed, for `/api/status`. */
  synchronized String json() {
    StringBuilder b = new StringBuilder("[");
    for (Job j : jobs.values()) {
      if (j.state == State.SKIPPED) continue;
      if (b.length() > 1) b.append(',');
      b.append("{\"id\":").append(Http.q(j.app.id())).append(",\"name\":").append(Http.q(j.app.name()))
          .append(",\"cache\":").append(Http.q(j.state.name().toLowerCase(java.util.Locale.ROOT)));
      String d = j.state == State.FAILED ? j.error : j.progress;
      if (!d.isEmpty()) b.append(",\"detail\":").append(Http.q(d));
      b.append('}');
    }
    return b.append(']').toString();
  }
}
