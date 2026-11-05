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

/**
 * The APKs a session can run: the configured ones (`--apps`: folders of APKs, or files listing a path or https URL per
 * line, optionally followed by a name), and uploads. An upload is stored once by content hash (identical uploads share
 * apksim's per-APK caches) and reached through an id of its own, random and listed nowhere.
 */
final class Catalog {
  record App(String id, String name, Path path, String url, boolean uploaded) {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private final Map<String, App> configured = new LinkedHashMap<>();
  private final Path uploads;
  private final Path downloads;
  private final long maxBytes;

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
          for (Path apk : apks) add(apk.getFileName().toString(), apk, null);
        }
      } else if (s.endsWith(".apk")) {
        add(p.getFileName().toString(), p, null);
      } else {
        for (String line : Files.readAllLines(p)) {
          line = line.strip();
          if (line.isEmpty() || line.startsWith("#")) continue;
          String[] parts = line.split("\\s+", 2);
          String where = parts[0];
          boolean url = where.startsWith("https://") || where.startsWith("http://");
          String name = parts.length > 1 ? parts[1] : where.substring(where.lastIndexOf('/') + 1);
          if (url) add(name, null, where);
          else add(name, p.getParent().resolve(where).normalize(), null);
        }
      }
    }
  }

  private void add(String name, Path path, String url) {
    String id = "c" + hash((path != null ? path.toString() : url).getBytes(StandardCharsets.UTF_8)).substring(0, 10);
    configured.put(id, new App(id, name, path, url, false));
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
    Path ref = uploads.resolve(id + ".ref");
    if (!Files.isRegularFile(ref)) return null;
    List<String> lines = Files.readAllLines(ref);
    if (lines.size() < 2 || !lines.get(0).matches("[0-9a-f]{64}\\.apk")) return null;
    Files.setLastModifiedTime(ref, FileTime.fromMillis(System.currentTimeMillis()));  // in use: keep it
    return new App(id, lines.get(1), uploads.resolve(lines.get(0)), null, true);
  }

  /** The APK file to launch, downloading a configured URL the first time. */
  Path file(App a) throws IOException {
    if (a.path() != null) {
      if (!Files.isRegularFile(a.path())) throw new IOException("no such APK: " + a.name());
      return a.path();
    }
    Path to = downloads.resolve(hash(a.url().getBytes(StandardCharsets.UTF_8)).substring(0, 24) + ".apk");
    synchronized (this) {
      if (Files.isRegularFile(to)) return to;
      HttpURLConnection c = (HttpURLConnection) URI.create(a.url()).toURL().openConnection();
      c.setConnectTimeout(15_000);
      c.setReadTimeout(60_000);
      if (c.getResponseCode() != 200) throw new IOException("download failed: HTTP " + c.getResponseCode());
      try (InputStream in = c.getInputStream()) {
        Path tmp = store(in, c.getContentLengthLong());
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
      }
      return to;
    }
  }

  /** Stores an upload of `length` bytes; its new id, or throws with the reason it was refused. */
  App upload(InputStream in, long length, String name) throws IOException {
    Path tmp = store(in, length);
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
    if (Files.exists(apk)) Files.delete(tmp);
    else Files.move(tmp, apk);
    byte[] idBytes = new byte[16];
    RANDOM.nextBytes(idBytes);
    String id = "u" + HexFormat.of().formatHex(idBytes);
    String clean = name == null || name.isBlank() ? "upload.apk" : name.replaceAll("[^A-Za-z0-9._ -]", "_");
    if (clean.length() > 80) clean = clean.substring(0, 80);
    Files.writeString(uploads.resolve(id + ".ref"), sha + ".apk\n" + clean + "\n");
    return new App(id, clean, apk, null, true);
  }

  /** Copies at most maxBytes into a temporary file, which must be a ZIP. */
  private Path store(InputStream in, long length) throws IOException {
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
        // (Not one stored in the last hour: its id may be being written right now.)
        boolean old = Files.getLastModifiedTime(p).toMillis() < System.currentTimeMillis() - 3_600_000L;
        boolean staleApk = n.endsWith(".apk") && !used.contains(n) && old;
        boolean stalePart = n.endsWith(".part") && old;
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
