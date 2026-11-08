package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * The APKs a session can run: the configured ones (`--apps`: folders of APKs, or files listing a path or https URL per
 * line, optionally followed by a name and ` -- ` launch options), uploads, and apps added by URL at run time
 * (`POST /api/apps`). An upload or added app is stored once by content hash (identical ones share apksim's per-APK
 * caches) and reached through an id of its own, random and listed nowhere.
 */
final class Catalog {
  /** `launch`: launch options of this app's own (checked by {@link #launchOptions}), put before the APK. */
  record App(String id, String name, Path path, String url, boolean uploaded, List<String> launch) {}

  /**
   * The launch options an app may carry (catalog lines, URL adds): what an app needs to start (an activity, Build
   * fields, Settings seeds, features...). Never options that read or write files, open ports or change the JVM
   * (`--jvm`, `--network record`, `--adb`, `--no-sandbox`, ...). Each takes one value, except the flags below.
   */
  static final Set<String> LAUNCH_OPTIONS = Set.of("--activity", "--build", "--setting", "--feature", "--timezone",
      "--clock", "--anim-scale", "--serial", "--bluetooth", "--status-bar", "--nav-bar", "--max-fps", "--video",
      "--audio", "--biometrics", "--network-profile", "--device");
  static final Set<String> LAUNCH_FLAGS = Set.of("--soft-keyboard");

  /** An app being downloaded from a URL added at run time: its id is answered at once, the APK comes later. */
  static final class Download {
    final App app;
    volatile long received;
    volatile long total = -1;
    final CompletableFuture<App> done = new CompletableFuture<>();
    final long started = System.currentTimeMillis();

    Download(App app) {
      this.app = app;
    }
  }

  private static final SecureRandom RANDOM = new SecureRandom();
  /** Told of each upload or added app once its APK is stored (the cache warm-up's queue), or null. */
  volatile java.util.function.Consumer<App> onReady;
  /** Publishing an upload (its APK and its id), renewing an id, and sweeping take this, so a sweep never sees half. */
  private final Object uploadsLock = new Object();
  private final Map<String, App> configured = new LinkedHashMap<>();
  private final Path uploads;
  private final Path downloads;
  private final long maxBytes;
  private final Map<String, Download> downloading = new ConcurrentHashMap<>();
  /** Downloads running at once (`POST /api/apps`): each holds a connection out and a temporary file. */
  static final int MAX_DOWNLOADS = 4;
  private final java.util.concurrent.Semaphore downloadSlots = new java.util.concurrent.Semaphore(MAX_DOWNLOADS);

  /** Too many downloads are running: try again later (503). */
  static final class Busy extends IOException {
    Busy(String message) {
      super(message);
    }
  }

  Catalog(List<String> sources, Path state, long maxBytes) throws IOException {
    this.uploads = state.resolve("serve/uploads");
    this.downloads = state.resolve("serve/downloads");
    this.maxBytes = maxBytes;
    Files.createDirectories(uploads);
    Files.createDirectories(downloads);
    for (String s : sources) {
      Path p = Path.of(s).toAbsolutePath().normalize();
      if (Files.isDirectory(p)) {
        try (DirectoryStream<Path> d = Files.newDirectoryStream(p, "*.apk")) {
          List<Path> apks = new ArrayList<>();
          d.forEach(apks::add);
          apks.sort(null);
          for (Path apk : apks) add(apk.getFileName().toString(), apk, null, List.of());
        }
      } else if (s.endsWith(".apk")) {
        add(p.getFileName().toString(), p, null, List.of());
      } else {
        for (String line : Files.readAllLines(p)) {
          line = line.strip();
          if (line.isEmpty() || line.startsWith("#")) continue;
          // `WHERE [NAME] [-- OPTION VALUE...]`
          List<String> launch = List.of();
          int dashes = (" " + line + " ").indexOf(" -- ");
          if (dashes >= 0) {
            String opts = line.substring(Math.min(line.length(), dashes + 3)).strip();
            line = line.substring(0, Math.max(0, dashes - 1)).strip();
            try {
              launch = launchOptions(opts.isEmpty() ? List.of() : List.of(opts.split("\\s+")));
            } catch (IOException e) {
              throw new IOException(p + ": " + e.getMessage());
            }
          }
          String[] parts = line.split("\\s+", 2);
          String where = parts[0];
          boolean url = where.startsWith("https://") || where.startsWith("http://");
          String name = parts.length > 1 ? parts[1] : where.substring(where.lastIndexOf('/') + 1);
          if (url) add(name, null, where, launch);
          else add(name, p.getParent().resolve(where).normalize(), null, launch);
        }
      }
    }
  }

  private void add(String name, Path path, String url, List<String> launch) {
    String key = (path != null ? path.toString() : url) + (launch.isEmpty() ? "" : " " + String.join(" ", launch));
    String id = "c" + hash(key.getBytes(StandardCharsets.UTF_8)).substring(0, 10);
    configured.put(id, new App(id, name, path, url, false, launch));
  }

  /** The options as given, if every one is allowed ({@link #LAUNCH_OPTIONS}) with a plain value; else the reason. */
  static List<String> launchOptions(List<String> given) throws IOException {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < given.size(); i++) {
      String o = given.get(i);
      if (LAUNCH_FLAGS.contains(o)) {
        out.add(o);
        continue;
      }
      if (!LAUNCH_OPTIONS.contains(o)) {
        throw new IOException("launch option " + o + " is not allowed (allowed: "
            + String.join(" ", new java.util.TreeSet<>(LAUNCH_OPTIONS)) + " " + String.join(" ", LAUNCH_FLAGS) + ")");
      }
      String v = i + 1 < given.size() ? given.get(++i) : null;
      // One argument each (no shell is involved), but never something the launcher would read as an option.
      if (v == null || v.isEmpty() || v.startsWith("-") && !v.matches("-?\\d+(\\.\\d+)?") || v.length() > 300
          || v.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
        throw new IOException("launch option " + o + " needs a plain value");
      }
      if (o.equals("--device") && !Session.DEVICES.contains(v) && deviceSize(v) == null) {
        throw new IOException("--device takes " + String.join(", ", Session.DEVICES) + " or WxH[@density] such as 1280x720@1.5"
            + " (160-4096 dp a side, density 0.75-4, at most 4096 pixels a side)");
      }
      out.add(o);
      out.add(v);
    }
    if (out.size() > 64) throw new IOException("too many launch options");
    return List.copyOf(out);
  }

  /**
   * A custom device size `WxH[@density]` (dp; density a scale factor such as 1, 1.5, 2, 3, default 2) in a form
   * `--device` takes, or null when it is not one or is out of bounds (the limits of apksim's own parser, and at most
   * 4096 pixels a side, as a session's frames are held in memory).
   */
  static String deviceSize(String s) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([1-9]\\d{2,3})x([1-9]\\d{2,3})(?:@(\\d(?:\\.\\d{1,2})?))?").matcher(s);
    if (!m.matches()) return null;
    int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
    double d = m.group(3) == null ? 2 : Double.parseDouble(m.group(3));
    if (w < 160 || h < 160 || w > 4096 || h > 4096 || d < 0.75 || d > 4 || w * d > 4096 || h * d > 4096) return null;
    return s;
  }

  List<App> listed() {
    return new ArrayList<>(configured.values());
  }

  /** The app with this id (configured, or an upload), or null. */
  App find(String id) throws IOException {
    if (id == null) return null;
    App a = configured.get(id);
    if (a != null) return a;
    if (!id.matches("u[0-9a-f]{32}")) return null;
    Download d = downloading.get(id);
    if (d != null) {
      if (!d.done.isDone()) return d.app;
      if (d.done.isCompletedExceptionally()) return d.app;  // file() answers why
    }
    Path ref = uploads.resolve(id + ".ref");
    if (!Files.isRegularFile(ref)) return null;
    synchronized (uploadsLock) {
      if (!Files.isRegularFile(ref)) return null;
      List<String> lines = Files.readAllLines(ref);
      if (lines.size() < 2 || !lines.get(0).matches("[0-9a-f]{64}\\.apk")) return null;
      Files.setLastModifiedTime(ref, FileTime.fromMillis(System.currentTimeMillis()));  // in use: keep it
      return new App(id, lines.get(1), uploads.resolve(lines.get(0)), null, true, List.copyOf(lines.subList(2, lines.size())));
    }
  }

  /**
   * The APK file to launch: downloading a configured URL the first time, or waiting for an added URL still downloading.
   * `progress` hears what is happening ("downloading 40%"), at most once a second.
   */
  Path file(App a, Consumer<String> progress, BooleanSupplier stopped) throws IOException {
    Download d = a.path() == null ? downloading.get(a.id()) : null;
    if (d == null && a.path() == null && a.uploaded()) {
      // An added app whose download finished (and left the map) after find() answered: its published ref.
      App published = find(a.id());
      if (published == null || published.path() == null) throw new IOException("no such app: " + a.name());
      return file(published, progress, stopped);
    }
    if (d != null) {
      String last = "";
      while (true) {
        if (stopped.getAsBoolean()) throw new IOException("the session ended while the app downloaded");
        try {
          return d.done.get(1, TimeUnit.SECONDS).path();
        } catch (TimeoutException e) {
          long total = d.total;
          String now = "downloading " + (total > 0 ? d.received * 100 / total + "%" : (d.received >> 20) + " MB");
          if (!now.equals(last)) progress.accept(now);
          last = now;
        } catch (ExecutionException e) {
          throw new IOException("download failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted");
        }
      }
    }
    if (a.path() != null) {
      if (!Files.isRegularFile(a.path())) throw new IOException("no such APK: " + a.name());
      return a.path();
    }
    Path to = downloads.resolve(hash(a.url().getBytes(StandardCharsets.UTF_8)).substring(0, 24) + ".apk");
    synchronized (this) {
      if (Files.isRegularFile(to)) return to;
      progress.accept("downloading " + a.name());
      HttpURLConnection c = connect(a.url(), Map.of());
      try (InputStream in = c.getInputStream()) {
        Path tmp = store(in, c.getContentLengthLong(), n -> {});
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
      }
      return to;
    }
  }

  private static HttpURLConnection connect(String url, Map<String, String> headers) throws IOException {
    HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
    c.setConnectTimeout(15_000);
    c.setReadTimeout(60_000);
    headers.forEach(c::setRequestProperty);
    int code;
    try {
      code = c.getResponseCode();
    } catch (IOException e) {
      c.disconnect();
      throw e;
    }
    if (code != 200) {
      c.disconnect();  // not left open until a GC: a failed download releases its slot at once
      throw new IOException("HTTP " + code + " from " + URI.create(url).getHost());
    }
    return c;
  }

  /**
   * Adds an app by URL (`POST /api/apps`): answers its id at once and downloads it in the background, once, storing it
   * by content hash as uploads are (same expiry: a day after its last use). A session opened on the id meanwhile waits
   * for the download and shows its progress. `headers` go with the download only (an artifact store's
   * Authorization) and are not kept.
   */
  App addUrl(String url, String name, Map<String, String> headers, List<String> launch) throws IOException {
    URI u;
    try {
      u = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IOException("not a URL");
    }
    if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme()) || u.getHost() == null) {
      throw new IOException("only http(s) URLs");
    }
    pruneFailures();
    if (!downloadSlots.tryAcquire()) {
      throw new Busy(MAX_DOWNLOADS + " downloads are running: try again when one has finished");
    }
    String path = u.getPath() == null ? "" : u.getPath();
    String clean = clean(name == null || name.isBlank() ? path.substring(path.lastIndexOf('/') + 1) : name);
    App pending = new App(newId(), clean, null, url, true, launch);
    Download d = new Download(pending);
    downloading.put(pending.id(), d);
    Thread.ofVirtual().name("serve-download").start(() -> {
      try {
        HttpURLConnection c = connect(url, headers);
        d.total = c.getContentLengthLong();
        Path tmp;
        try (InputStream in = c.getInputStream()) {
          tmp = store(in, d.total, n -> d.received = n);
        }
        App done = publish(tmp, pending.id(), clean, launch);
        ready(done);  // the warm-up's job exists before a session waiting on the download looks for it
        d.done.complete(done);
        System.err.printf("[serve] added %s (%d MB) from %s%n", clean, d.received >> 20, u.getHost());
      } catch (Exception e) {
        d.done.completeExceptionally(e);
        System.err.printf("[serve] could not add %s from %s: %s%n", clean, u.getHost(), e.getMessage());
      } finally {
        downloadSlots.release();
      }
      // Sessions waiting on it have its file (or its failure) by now; later ones find the stored ref (or nothing).
      if (!d.done.isCompletedExceptionally()) downloading.remove(pending.id());
    });
    return pending;
  }

  /** Failed downloads kept for their ids to answer why (an hour, at most {@link #MAX_FAILURES}): the oldest go first. */
  static final int MAX_FAILURES = 100;

  private void pruneFailures() {
    long hourAgo = System.currentTimeMillis() - 3_600_000L;
    downloading.values().removeIf(d -> d.done.isCompletedExceptionally() && d.started < hourAgo);
    List<Download> failed = new ArrayList<>();
    for (Download d : downloading.values()) if (d.done.isCompletedExceptionally()) failed.add(d);
    if (failed.size() <= MAX_FAILURES) return;
    failed.sort(java.util.Comparator.comparingLong(d -> d.started));
    for (Download d : failed.subList(0, failed.size() - MAX_FAILURES)) downloading.remove(d.app.id(), d);
  }

  /** An added app's download: null when it is not one (or finished long ago). */
  Download download(String id) {
    return id == null ? null : downloading.get(id);
  }

  /**
   * Stores an upload of `length` bytes (-1: up to the end, e.g. a chunked body; `--max-upload` still holds); its new
   * id, or throws with the reason it was refused.
   */
  App upload(InputStream in, long length, String name, List<String> launch) throws IOException {
    App a = publish(store(in, length, n -> {}), newId(), clean(name), launch);
    ready(a);
    return a;
  }

  private void ready(App a) {
    java.util.function.Consumer<App> c = onReady;
    if (c != null) c.accept(a);
  }

  private static String newId() {
    byte[] idBytes = new byte[16];
    RANDOM.nextBytes(idBytes);
    return "u" + HexFormat.of().formatHex(idBytes);
  }

  private static String clean(String name) {
    String clean = name == null || name.isBlank() ? "upload.apk" : name.replaceAll("[^A-Za-z0-9._ -]", "_");
    return clean.length() > 80 ? clean.substring(0, 80) : clean;
  }

  /** Moves a stored file into place by its content hash and publishes `id` for it. */
  private App publish(Path tmp, String id, String clean, List<String> launch) throws IOException {
    String sha;
    try (InputStream f = Files.newInputStream(tmp)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      for (int n; (n = f.read(buf)) > 0; ) md.update(buf, 0, n);
      sha = HexFormat.of().formatHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    Path apk = uploads.resolve(sha + ".apk");
    StringBuilder ref = new StringBuilder(sha).append(".apk\n").append(clean).append('\n');
    for (String o : launch) ref.append(o).append('\n');
    synchronized (uploadsLock) {
      // The same APK stored already (or by an upload a moment ago): share it.
      if (Files.exists(apk)) Files.delete(tmp);
      else Files.move(tmp, apk);
      Files.writeString(uploads.resolve(id + ".ref"), ref);
    }
    return new App(id, clean, apk, null, true, launch);
  }

  /** Copies at most maxBytes into a temporary file, which must be a ZIP. */
  private Path store(InputStream in, long length, LongConsumer progress) throws IOException {
    if (length > maxBytes) throw new IOException("too large: " + (length >> 20) + " MB (limit " + (maxBytes >> 20) + " MB)");
    Path tmp = Files.createTempFile(uploads, "incoming-", ".part");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      byte[] buf = new byte[1 << 16];
      long total = 0;
      long want = length < 0 ? Long.MAX_VALUE : length;
      while (total < want) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, want - total));
        if (n < 0) break;
        total += n;
        if (total > maxBytes) throw new IOException("too large (limit " + (maxBytes >> 20) + " MB)");
        out.write(buf, 0, n);
        progress.accept(total);
      }
      if (length >= 0 && total < length) throw new IOException("upload ended early");
    } catch (IOException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
    byte[] magic = new byte[4];
    try (InputStream f = Files.newInputStream(tmp)) {
      f.readNBytes(magic, 0, 4);
    }
    if (magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
      Files.delete(tmp);
      throw new IOException("not an APK (not a ZIP file)");
    }
    return tmp;
  }

  /** Deletes uploads not used for this long, and stored APKs no upload refers to any more. */
  void sweep(long maxAgeMillis) {
    pruneFailures();
    synchronized (uploadsLock) {
      sweepLocked(maxAgeMillis);
    }
  }

  private void sweepLocked(long maxAgeMillis) {
    long cutoff = System.currentTimeMillis() - maxAgeMillis;
    try (DirectoryStream<Path> d = Files.newDirectoryStream(uploads)) {
      List<Path> all = new ArrayList<>();
      d.forEach(all::add);
      java.util.Set<String> used = new java.util.HashSet<>();
      for (Path p : all) {
        String n = p.getFileName().toString();
        if (n.endsWith(".ref")) {
          if (Files.getLastModifiedTime(p).toMillis() < cutoff) Files.delete(p);
          else used.add(Files.readAllLines(p).get(0));
        }
      }
      for (Path p : all) {
        String n = p.getFileName().toString();
        if (!n.endsWith(".apk") && !n.endsWith(".part")) continue;  // ids: handled above (and maybe deleted)
        // An APK no id names (ids and APKs are published together, under the lock this holds); an upload's
        // partial file only once it is an hour old (it may still be arriving: uploads are received without the lock).
        boolean staleApk = n.endsWith(".apk") && !used.contains(n);
        boolean stalePart = n.endsWith(".part")
            && Files.getLastModifiedTime(p).toMillis() < System.currentTimeMillis() - 3_600_000L;
        if (staleApk || stalePart) Files.deleteIfExists(p);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not sweep uploads: " + e);
    }
  }

  static String hash(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
