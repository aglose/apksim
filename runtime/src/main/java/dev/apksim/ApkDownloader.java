package dev.apksim;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Fast zero-HEAD parallel ranged HTTP/HTTPS downloader for APKs.
 *
 * <p>Opens an initial {@code GET Range: bytes=0-<firstChunk-1>} request (following redirects manually once so every
 * worker hits the final CDN URL directly without extra redirect RTTs, and stripping sensitive headers on cross-host
 * redirects). When the server replies {@code 206 Partial Content} with a total size larger than the first chunk,
 * remaining byte ranges are fetched concurrently across {@code -Dapksim.downloadConnections} (default 6) connections
 * via a work-stealing chunk queue writing straight into one {@link FileChannel} with positional {@code pwrite}.
 * Servers that return {@code 200 OK} stream sequentially from the same connection with zero wasted requests.
 */
public final class ApkDownloader {
  private static final int DEFAULT_CONNECTIONS = Math.max(1, Math.min(12, Integer.getInteger("apksim.downloadConnections", 6)));
  private static final long FIRST_CHUNK_BYTES = 4L * 1024 * 1024;
  private static final long MIN_CHUNK_BYTES = 4L * 1024 * 1024;
  private static final long MAX_CHUNK_BYTES = 8L * 1024 * 1024;
  private static final int BUF_SIZE = 128 * 1024;
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 60_000;
  private static final int MAX_REDIRECTS = 8;
  private static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;
  /** Default LRU cap for CLI downloads in {@code STATE/cache/downloads/} (0 = unlimited). */
  private static final long DOWNLOAD_CACHE_MAX_BYTES = Long.getLong("apksim.downloadCacheMB", 512L) * 1024L * 1024L;
  private static final Path CURL_BIN = Path.of("/usr/bin/curl");
  private static final AtomicLong PART_SEQ = new AtomicLong();

  public record Result(Path path, long bytes, String sha256, int connectionsUsed, long elapsedMs) {}

  private record ResolvedConnection(HttpURLConnection conn, URI finalUri, Map<String, String> finalHeaders, int code) {}

  private record Range(long start, long end) {}

  private ApkDownloader() {}

  public static boolean isUrl(String arg) {
    return arg != null && (arg.startsWith("https://") || arg.startsWith("http://"));
  }

  public static String fileNameFromUrl(String url) {
    try {
      String path = URI.create(url).getPath();
      if (path != null) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.isEmpty()) {
          if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) name = name + ".apk";
          return name.length() > 80 ? name.substring(name.length() - 80) : name;
        }
      }
    } catch (IllegalArgumentException ignored) {}
    return "download.apk";
  }

  public static Path cachePath(Path cacheRoot, String url) {
    String hash = sha256Hex(url.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    return cacheRoot.resolve("downloads").resolve(hash + "-" + fileNameFromUrl(url));
  }

  /**
   * Downloads {@code url} into {@code STATE/cache/downloads/} asynchronously so the caller can boot the simulator
   * concurrently while bytes stream from the network. Reuses a cached file unless {@code fresh} is true.
   */
  public static CompletableFuture<Result> downloadToCacheAsync(String url, Path cacheRoot, boolean fresh) {
    return downloadToCacheAsync(url, cacheRoot, fresh, Map.of(), false);
  }

  public static CompletableFuture<Result> downloadToCacheAsync(String url, Path cacheRoot, boolean fresh,
      Map<String, String> headers, boolean netrc) {
    Path dest = cachePath(cacheRoot, url);
    if (!fresh && Files.isRegularFile(dest)) {
      try {
        long size = Files.size(dest);
        if (size >= 4) {
          try {
            Files.setLastModifiedTime(dest, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
          } catch (IOException ignored) {}
          return CompletableFuture.completedFuture(new Result(dest, size, null, 0, 0));
        }
      } catch (IOException ignored) {}
    }
    Map<String, String> copy = headers == null || headers.isEmpty() ? Map.of() : new LinkedHashMap<>(headers);
    return CompletableFuture.supplyAsync(() -> {
      try {
        Result res = download(url, dest, dest.getParent(), copy, netrc, DEFAULT_MAX_BYTES, false, null, null);
        sweepDownloadCache(dest.getParent(), dest);
        return res;
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }, r -> {
      Thread t = new Thread(r, "apksim-download");
      t.setDaemon(true);
      t.start();
    });
  }

  /** Evicts oldest cached CLI downloads in {@code dir} until total size is within {@link #DOWNLOAD_CACHE_MAX_BYTES}. */
  private static void sweepDownloadCache(Path dir, Path keep) {
    if (DOWNLOAD_CACHE_MAX_BYTES <= 0 || dir == null || !Files.isDirectory(dir)) return;
    record Entry(Path path, long size, long mtime) {}
    try (var stream = Files.list(dir)) {
      List<Entry> entries = new ArrayList<>();
      long total = 0;
      for (Path p : stream.toList()) {
        String name = p.getFileName().toString();
        if (!name.endsWith(".apk") || !Files.isRegularFile(p)) continue;
        long sz = Files.size(p);
        long mt = Files.getLastModifiedTime(p).toMillis();
        entries.add(new Entry(p, sz, mt));
        total += sz;
      }
      if (total <= DOWNLOAD_CACHE_MAX_BYTES) return;
      entries.sort(java.util.Comparator.comparingLong(Entry::mtime));
      for (Entry e : entries) {
        if (total <= DOWNLOAD_CACHE_MAX_BYTES) break;
        if (e.path.equals(keep)) continue;
        if (Files.deleteIfExists(e.path)) total -= e.size;
      }
    } catch (IOException ignored) {}
  }

  /**
   * Downloads an APK from {@code url}. If {@code dest} is non-null, atomically moves the completed file into
   * {@code dest}; otherwise leaves it in a staged temp file under {@code stageDir} (and caller owns moving/deleting it).
   */
  public static Result download(String url, Path dest, Map<String, String> headers, long maxBytes,
      boolean computeSha256, LongConsumer onTotal, LongConsumer onProgress) throws IOException {
    return download(url, dest, dest.getParent(), headers, false, maxBytes, computeSha256, onTotal, onProgress);
  }

  public static Result download(String url, Path dest, Map<String, String> headers, boolean netrc, long maxBytes,
      boolean computeSha256, LongConsumer onTotal, LongConsumer onProgress) throws IOException {
    return download(url, dest, dest.getParent(), headers, netrc, maxBytes, computeSha256, onTotal, onProgress);
  }

  public static Result downloadStaged(String url, Path stageDir, Map<String, String> headers, long maxBytes,
      boolean computeSha256, LongConsumer onTotal, LongConsumer onProgress) throws IOException {
    return download(url, null, stageDir, headers, false, maxBytes, computeSha256, onTotal, onProgress);
  }

  private static Result download(String url, Path dest, Path stageDir, Map<String, String> headers, boolean netrc,
      long maxBytes, boolean computeSha256, LongConsumer onTotal, LongConsumer onProgress) throws IOException {
    long t0 = System.nanoTime();
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IOException("invalid URL: " + url, e);
    }
    if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
      throw new IOException("only http(s) URLs are supported: " + url);
    }
    Map<String, String> reqHeaders = headers == null ? Map.of() : headers;
    // Fast path: native /usr/bin/curl uses native TLS + HTTP/2 with zero JVM startup/JSSE CPU overhead,
    // keeping CPU 100% free for overlapped simulator pre-boot. Falls back to parallel ranged HttpURLConnection
    // if curl is unavailable or disabled (-Dapksim.noCurl=true).
    if (!Boolean.getBoolean("apksim.noCurl") && Files.isExecutable(CURL_BIN)) {
      return downloadWithCurl(uri, dest, stageDir, reqHeaders, netrc, maxBytes, computeSha256, onTotal, onProgress, t0);
    }
    if (netrc && !hasHeaderIgnoreCase(reqHeaders, "authorization")) {
      String basic = readNetrcBasicAuth(uri.getHost());
      if (basic != null) {
        Map<String, String> withAuth = new LinkedHashMap<>(reqHeaders);
        withAuth.put("Authorization", basic);
        reqHeaders = withAuth;
      }
    }
    int maxConn = DEFAULT_CONNECTIONS;
    String rangeHeader = maxConn > 1 ? "bytes=0-" + (FIRST_CHUNK_BYTES - 1) : null;
    ResolvedConnection first = openFollowingRedirects(uri, reqHeaders, rangeHeader);
    if (first.code == 416 && rangeHeader != null) {
      first.conn.disconnect();
      first = openFollowingRedirects(uri, reqHeaders, null);
    }
    if (first.code != 200 && first.code != 206) {
      first.conn.disconnect();
      throw new IOException("HTTP " + first.code + " from " + first.finalUri.getHost());
    }

    Files.createDirectories(stageDir);
    Path tmp = stageDir.resolve("dl-" + ProcessHandle.current().pid() + "-" + PART_SEQ.incrementAndGet() + ".part");
    boolean ok = false;
    try {
      Result res;
      if (first.code == 206) {
        long[] parsed = parseContentRange(first.conn.getHeaderField("Content-Range"));
        if (parsed != null && parsed[0] == 0 && parsed[2] > 0) {
          long firstEnd = parsed[1];
          long total = parsed[2];
          if (total > maxBytes) {
            first.conn.disconnect();
            throw new IOException("too large: " + (total >> 20) + " MB (limit " + (maxBytes >> 20) + " MB)");
          }
          if (onTotal != null) onTotal.accept(total);
          res = downloadRanged(first, tmp, firstEnd, total, maxConn, computeSha256, onProgress, t0);
        } else {
          // Malformed Content-Range: fall back to streaming what came back.
          long len = first.conn.getContentLengthLong();
          if (len > maxBytes) {
            first.conn.disconnect();
            throw new IOException("too large: " + (len >> 20) + " MB (limit " + (maxBytes >> 20) + " MB)");
          }
          if (onTotal != null) onTotal.accept(len);
          res = downloadSingle(first.conn, tmp, len, maxBytes, computeSha256, onProgress, t0);
        }
      } else {
        long len = first.conn.getContentLengthLong();
        if (len > maxBytes) {
          first.conn.disconnect();
          throw new IOException("too large: " + (len >> 20) + " MB (limit " + (maxBytes >> 20) + " MB)");
        }
        if (onTotal != null) onTotal.accept(len);
        res = downloadSingle(first.conn, tmp, len, maxBytes, computeSha256, onProgress, t0);
      }

      Path outPath = tmp;
      if (dest != null) {
        Files.createDirectories(dest.getParent());
        try {
          Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
          Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        outPath = dest;
      }
      ok = true;
      return new Result(outPath, res.bytes(), res.sha256(), res.connectionsUsed(), (System.nanoTime() - t0) / 1_000_000);
    } finally {
      if (!ok) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {}
      }
    }
  }

  private record CurlTarget(URI uri, Map<String, String> headers, boolean netrc) {}

  private static Result downloadWithCurl(URI startUri, Path dest, Path stageDir, Map<String, String> headers,
      boolean netrc, long maxBytes, boolean computeSha256, LongConsumer onTotal, LongConsumer onProgress, long t0) throws IOException {
    for (Map.Entry<String, String> e : headers.entrySet()) {
      String k = e.getKey(), v = e.getValue();
      if (k.indexOf('\r') >= 0 || k.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
        throw new IOException("invalid HTTP header");
      }
    }
    // curl automatically strips Authorization and Cookie on cross-host redirects, but NOT custom API-key headers
    // like Artifactory's X-JFrog-Art-Api. Resolve redirects hop-by-hop when such headers are present so tokens never
    // leak to S3/GCS presigned redirect targets or break signed-header verification.
    CurlTarget target = hasCustomSensitiveHeader(headers)
        ? resolveRedirectsWithCurl(startUri, headers, netrc)
        : new CurlTarget(startUri, headers, netrc);
    Files.createDirectories(stageDir);
    Path tmp = stageDir.resolve("dl-" + ProcessHandle.current().pid() + "-" + PART_SEQ.incrementAndGet() + ".part");
    List<String> cmd = new ArrayList<>();
    cmd.add(CURL_BIN.toString());
    cmd.add("-fsSL");
    cmd.add("--http2");
    cmd.add("--proto");
    cmd.add("=http,https");
    cmd.add("--proto-redir");
    cmd.add("=http,https");
    cmd.add("--max-redirs");
    cmd.add(String.valueOf(MAX_REDIRECTS));
    cmd.add("--connect-timeout");
    cmd.add(String.valueOf(CONNECT_TIMEOUT_MS / 1000));
    cmd.add("--max-time");
    cmd.add("600");
    if (maxBytes > 0 && maxBytes < Long.MAX_VALUE) {
      cmd.add("--max-filesize");
      cmd.add(String.valueOf(maxBytes));
    }
    if (target.netrc()) cmd.add("--netrc");
    cmd.add("-A");
    cmd.add("apksim/1");
    for (Map.Entry<String, String> e : target.headers().entrySet()) {
      cmd.add("-H");
      cmd.add(e.getKey() + ": " + e.getValue());
    }
    cmd.add("-o");
    cmd.add(tmp.toString());
    cmd.add("--");
    cmd.add(target.uri().toString());

    boolean ok = false;
    Process proc = null;
    Thread monitor = null;
    java.util.concurrent.atomic.AtomicBoolean tooLarge = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      Process pRef = proc;
      if (onProgress != null || maxBytes < DEFAULT_MAX_BYTES) {
        monitor = new Thread(() -> {
          while (pRef.isAlive()) {
            try {
              if (Files.isRegularFile(tmp)) {
                long sz = Files.size(tmp);
                if (sz > maxBytes) {
                  tooLarge.set(true);
                  pRef.destroyForcibly();
                  return;
                }
                if (onProgress != null) onProgress.accept(sz);
              }
              Thread.sleep(75);
            } catch (InterruptedException ie) {
              return;
            } catch (IOException ignored) {}
          }
        }, "apksim-dl-monitor");
        monitor.setDaemon(true);
        monitor.start();
      }
      String errOut;
      try (InputStream err = proc.getInputStream()) {
        errOut = new String(err.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
      int exit;
      try {
        exit = proc.waitFor();
      } catch (InterruptedException e) {
        proc.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IOException("download interrupted", e);
      }
      if (monitor != null) monitor.interrupt();
      if (tooLarge.get()) {
        throw new IOException("too large (limit " + (maxBytes >> 20) + " MB)");
      }
      if (exit != 0) {
        if (exit == 63) throw new IOException("too large (limit " + (maxBytes >> 20) + " MB)");
        throw new IOException(errOut.isEmpty() ? "curl exited with status " + exit : errOut);
      }
      long size = Files.size(tmp);
      if (size > maxBytes) throw new IOException("too large: " + (size >> 20) + " MB (limit " + (maxBytes >> 20) + " MB)");
      if (onTotal != null) onTotal.accept(size);
      if (onProgress != null) onProgress.accept(size);
      String sha = null;
      try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) {
        ByteBuffer hdr = ByteBuffer.allocate(4);
        while (hdr.hasRemaining() && ch.read(hdr) > 0) {}
        verifyZipMagic(hdr.array());
        if (computeSha256) sha = sha256Channel(ch);
      }
      Path outPath = tmp;
      if (dest != null) {
        Files.createDirectories(dest.getParent());
        try {
          Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
          Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        outPath = dest;
      }
      ok = true;
      return new Result(outPath, size, sha, 1, (System.nanoTime() - t0) / 1_000_000);
    } finally {
      if (proc != null && proc.isAlive()) proc.destroyForcibly();
      if (monitor != null) monitor.interrupt();
      if (!ok) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {}
      }
    }
  }

  private static Result downloadSingle(HttpURLConnection conn, Path tmp, long expectedLen, long maxBytes,
      boolean computeSha256, LongConsumer onProgress, long t0) throws IOException {
    MessageDigest md = computeSha256 ? newSha256() : null;
    long total = 0;
    try (InputStream in = conn.getInputStream();
         FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      byte[] buf = new byte[BUF_SIZE];
      byte[] magic = new byte[4];
      int magicPos = 0;
      long want = expectedLen < 0 ? Long.MAX_VALUE : expectedLen;
      while (total < want) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, want - total));
        if (n < 0) break;
        if (magicPos < 4) {
          int take = Math.min(4 - magicPos, n);
          System.arraycopy(buf, 0, magic, magicPos, take);
          magicPos += take;
          if (magicPos == 4) verifyZipMagic(magic);
        }
        total += n;
        if (total > maxBytes) throw new IOException("too large (limit " + (maxBytes >> 20) + " MB)");
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
        while (bb.hasRemaining()) ch.write(bb);
        if (md != null) md.update(buf, 0, n);
        if (onProgress != null) onProgress.accept(total);
      }
      if (magicPos < 4) verifyZipMagic(magic);
      if (expectedLen >= 0 && total < expectedLen) throw new IOException("download ended early (" + total + " / " + expectedLen + ")");
    } finally {
      conn.disconnect();
    }
    String sha = md != null ? HexFormat.of().formatHex(md.digest()) : null;
    return new Result(tmp, total, sha, 1, (System.nanoTime() - t0) / 1_000_000);
  }

  private static Result downloadRanged(ResolvedConnection first, Path tmp, long firstEnd, long total, int maxConn,
      boolean computeSha256, LongConsumer onProgress, long t0) throws IOException {
    long firstLen = firstEnd + 1;
    List<Range> remaining = new ArrayList<>();
    if (firstLen < total) {
      long rem = total - firstLen;
      long targetChunks = Math.min(32, Math.max(maxConn, maxConn * 2L));
      long chunkSize = Math.max(MIN_CHUNK_BYTES, Math.min(MAX_CHUNK_BYTES, (rem + targetChunks - 1) / targetChunks));
      for (long pos = firstLen; pos < total; pos += chunkSize) {
        remaining.add(new Range(pos, Math.min(total - 1, pos + chunkSize - 1)));
      }
    }

    int workerCount = remaining.isEmpty() ? 0 : Math.min(maxConn - 1, remaining.size());
    AtomicLong received = new AtomicLong();
    AtomicInteger nextIdx = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ)) {
      // Read the first 4 bytes synchronously to fail fast on HTML error pages before spawning parallel connections.
      byte[] firstBuf = new byte[BUF_SIZE];
      int firstRead = 0;
      try (InputStream firstIn = first.conn.getInputStream()) {
        while (firstRead < 4 && firstRead < firstLen) {
          int n = firstIn.read(firstBuf, firstRead, (int) Math.min(firstBuf.length - firstRead, firstLen - firstRead));
          if (n < 0) break;
          firstRead += n;
        }
        if (firstRead < 4) throw new IOException("not an APK (file shorter than 4 bytes)");
        verifyZipMagic(firstBuf);

        ExecutorService pool = workerCount > 0 ? Executors.newFixedThreadPool(workerCount, r -> {
          Thread t = new Thread(r, "apksim-dl-worker");
          t.setDaemon(true);
          return t;
        }) : null;
        List<Future<?>> futures = new ArrayList<>(workerCount);
        try {
          if (pool != null) {
            for (int w = 0; w < workerCount; w++) {
              futures.add(pool.submit(() -> runChunkLoop(first.finalUri, first.finalHeaders, remaining, nextIdx, ch, received, onProgress, failure)));
            }
          }

          // Write what we already buffered from chunk 0, then stream the rest of chunk 0.
          long pos = 0;
          writeFully(ch, firstBuf, 0, firstRead, pos);
          pos += firstRead;
          long rec = received.addAndGet(firstRead);
          if (onProgress != null) onProgress.accept(rec);
          while (pos < firstLen) {
            if (failure.get() != null) break;
            int n = firstIn.read(firstBuf, 0, (int) Math.min(firstBuf.length, firstLen - pos));
            if (n < 0) throw new IOException("first range ended early (" + pos + " / " + firstLen + ")");
            writeFully(ch, firstBuf, 0, n, pos);
            pos += n;
            rec = received.addAndGet(n);
            if (onProgress != null) onProgress.accept(rec);
          }
          // Current thread joins the work-stealing queue for any remaining chunks!
          if (!remaining.isEmpty() && failure.get() == null) {
            runChunkLoop(first.finalUri, first.finalHeaders, remaining, nextIdx, ch, received, onProgress, failure);
          }
          for (Future<?> f : futures) {
            try {
              f.get();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException("download interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
              failure.compareAndSet(null, e.getCause());
            }
          }
        } finally {
          if (pool != null) pool.shutdownNow();
        }
      } finally {
        first.conn.disconnect();
      }

      Throwable err = failure.get();
      if (err != null) {
        if (err instanceof IOException ioe) throw ioe;
        throw new IOException("parallel download failed: " + err.getMessage(), err);
      }
      if (ch.size() != total) {
        throw new IOException("download size mismatch: got " + ch.size() + ", expected " + total);
      }
      String sha = computeSha256 ? sha256Channel(ch) : null;
      return new Result(tmp, total, sha, 1 + workerCount, (System.nanoTime() - t0) / 1_000_000);
    }
  }

  private static void runChunkLoop(URI finalUri, Map<String, String> headers, List<Range> chunks, AtomicInteger nextIdx,
      FileChannel ch, AtomicLong received, LongConsumer onProgress, AtomicReference<Throwable> failure) {
    byte[] buf = new byte[BUF_SIZE];
    while (failure.get() == null) {
      int idx = nextIdx.getAndIncrement();
      if (idx >= chunks.size()) return;
      Range r = chunks.get(idx);
      try {
        fetchRangeWithRetry(finalUri, headers, r, ch, buf, received, onProgress, failure);
      } catch (Throwable t) {
        failure.compareAndSet(null, t);
        return;
      }
    }
  }

  private static void fetchRangeWithRetry(URI finalUri, Map<String, String> headers, Range r, FileChannel ch,
      byte[] buf, AtomicLong received, LongConsumer onProgress, AtomicReference<Throwable> failure) throws IOException {
    IOException lastErr = null;
    long expected = r.end - r.start + 1;
    for (int attempt = 0; attempt < 3; attempt++) {
      if (failure.get() != null) return;
      long written = 0;
      HttpURLConnection conn = null;
      try {
        ResolvedConnection rc = openFollowingRedirects(finalUri, headers, "bytes=" + r.start + "-" + r.end);
        conn = rc.conn;
        if (rc.code != 206) {
          throw new IOException("expected HTTP 206 for range " + r.start + "-" + r.end + ", got " + rc.code);
        }
        try (InputStream in = conn.getInputStream()) {
          long pos = r.start;
          while (written < expected) {
            if (failure.get() != null) return;
            int n = in.read(buf, 0, (int) Math.min(buf.length, expected - written));
            if (n < 0) throw new IOException("range " + r.start + "-" + r.end + " ended early at " + written + "/" + expected);
            writeFully(ch, buf, 0, n, pos);
            pos += n;
            written += n;
            long rec = received.addAndGet(n);
            if (onProgress != null) onProgress.accept(rec);
          }
        }
        return;
      } catch (IOException e) {
        lastErr = e;
        if (written > 0) received.addAndGet(-written);
        if (attempt < 2) {
          try {
            Thread.sleep(100L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", ie);
          }
        }
      } finally {
        if (conn != null) conn.disconnect();
      }
    }
    throw lastErr;
  }

  private static ResolvedConnection openFollowingRedirects(URI startUri, Map<String, String> headers, String range) throws IOException {
    URI cur = startUri;
    Map<String, String> curHeaders = headers;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      HttpURLConnection c = (HttpURLConnection) cur.toURL().openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(CONNECT_TIMEOUT_MS);
      c.setReadTimeout(READ_TIMEOUT_MS);
      c.setRequestProperty("User-Agent", "apksim/1");
      c.setRequestProperty("Accept-Encoding", "identity");
      for (Map.Entry<String, String> e : curHeaders.entrySet()) {
        c.setRequestProperty(e.getKey(), e.getValue());
      }
      if (range != null) c.setRequestProperty("Range", range);
      int code;
      try {
        code = c.getResponseCode();
      } catch (IOException e) {
        c.disconnect();
        throw e;
      }
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null || loc.isEmpty()) throw new IOException("HTTP " + code + " without Location header");
        URI next = cur.resolve(loc);
        String scheme = next.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
          throw new IOException("refusing redirect to non-http(s) scheme: " + scheme);
        }
        if (!curHeaders.isEmpty() && !sameOrigin(cur, next)) {
          curHeaders = stripSensitiveHeaders(curHeaders);
        }
        cur = next;
        continue;
      }
      return new ResolvedConnection(c, cur, curHeaders, code);
    }
    throw new IOException("too many redirects (" + MAX_REDIRECTS + ") for " + startUri.getHost());
  }

  private static CurlTarget resolveRedirectsWithCurl(URI startUri, Map<String, String> headers, boolean netrc) throws IOException {
    URI cur = startUri;
    Map<String, String> curHeaders = headers;
    boolean curNetrc = netrc;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      List<String> cmd = new ArrayList<>();
      cmd.add(CURL_BIN.toString());
      cmd.add("-sS");
      cmd.add("--http2");
      cmd.add("--proto");
      cmd.add("=http,https");
      cmd.add("--max-redirs");
      cmd.add("0");
      cmd.add("--connect-timeout");
      cmd.add(String.valueOf(CONNECT_TIMEOUT_MS / 1000));
      cmd.add("--max-time");
      cmd.add("30");
      cmd.add("-r");
      cmd.add("0-0");
      if (curNetrc) cmd.add("--netrc");
      cmd.add("-A");
      cmd.add("apksim/1");
      for (Map.Entry<String, String> e : curHeaders.entrySet()) {
        cmd.add("-H");
        cmd.add(e.getKey() + ": " + e.getValue());
      }
      cmd.add("-o");
      cmd.add("/dev/null");
      cmd.add("-w");
      cmd.add("%{http_code}\n%{redirect_url}");
      cmd.add("--");
      cmd.add(cur.toString());

      Process p = new ProcessBuilder(cmd).start();
      String out;
      String err;
      try (InputStream stdout = p.getInputStream(); InputStream stderr = p.getErrorStream()) {
        out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
        err = new String(stderr.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
      int exit;
      try {
        exit = p.waitFor();
      } catch (InterruptedException ie) {
        p.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IOException("download interrupted", ie);
      }
      if (exit != 0) throw new IOException(err.isEmpty() ? "curl exited with status " + exit : err);
      String[] lines = out.split("\n", 2);
      int code;
      try {
        code = Integer.parseInt(lines[0].trim());
      } catch (NumberFormatException e) {
        return new CurlTarget(cur, curHeaders, curNetrc);
      }
      String loc = lines.length > 1 ? lines[1].trim() : "";
      if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && !loc.isEmpty()) {
        URI next = cur.resolve(loc);
        String scheme = next.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
          throw new IOException("refusing redirect to non-http(s) scheme: " + scheme);
        }
        if (!sameOrigin(cur, next)) {
          curHeaders = stripSensitiveHeaders(curHeaders);
          curNetrc = false;
        }
        cur = next;
        if (!hasCustomSensitiveHeader(curHeaders)) {
          // Sensitive custom headers have been stripped; curl -L can follow any remaining redirects directly.
          return new CurlTarget(cur, curHeaders, curNetrc);
        }
        continue;
      }
      return new CurlTarget(cur, curHeaders, curNetrc);
    }
    throw new IOException("too many redirects (" + MAX_REDIRECTS + ") for " + startUri.getHost());
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && effectivePort(a) == effectivePort(b);
  }

  private static int effectivePort(URI u) {
    int p = u.getPort();
    if (p != -1) return p;
    return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
  }

  private static boolean isCustomSensitiveHeader(String lowerName) {
    return "x-jfrog-art-api".equals(lowerName) || "x-api-key".equals(lowerName) || "private-token".equals(lowerName);
  }

  private static boolean hasCustomSensitiveHeader(Map<String, String> headers) {
    for (String k : headers.keySet()) {
      if (isCustomSensitiveHeader(k.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String targetLower) {
    for (String k : headers.keySet()) {
      if (targetLower.equals(k.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static Map<String, String> stripSensitiveHeaders(Map<String, String> headers) {
    Map<String, String> clean = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : headers.entrySet()) {
      String k = e.getKey().toLowerCase(Locale.ROOT);
      if ("authorization".equals(k) || "cookie".equals(k) || "proxy-authorization".equals(k) || isCustomSensitiveHeader(k)) continue;
      clean.put(e.getKey(), e.getValue());
    }
    return clean;
  }

  /** Minimal ~/.netrc reader (machine/default + login + password) for the pure-Java fallback when {@code --netrc} is used. */
  private static String readNetrcBasicAuth(String host) {
    if (host == null || host.isEmpty()) return null;
    String home = System.getProperty("user.home");
    if (home == null || home.isEmpty()) return null;
    Path netrc = Path.of(home, ".netrc");
    if (!Files.isRegularFile(netrc)) return null;
    try {
      String text = Files.readString(netrc, StandardCharsets.UTF_8);
      List<String> tokens = new ArrayList<>();
      for (String line : text.split("\n")) {
        int hash = line.indexOf('#');
        String s = (hash >= 0 ? line.substring(0, hash) : line).trim();
        if (s.isEmpty()) continue;
        for (String tok : s.split("\\s+")) if (!tok.isEmpty()) tokens.add(tok);
      }
      String matchedLogin = null, matchedPass = null;
      String defaultLogin = null, defaultPass = null;
      String curMachine = null;
      boolean curDefault = false;
      String curLogin = null, curPass = null;
      for (int i = 0; i < tokens.size(); i++) {
        String t = tokens.get(i);
        if ("machine".equals(t) || "default".equals(t)) {
          if (curLogin != null && curPass != null) {
            if (curMachine != null && curMachine.equalsIgnoreCase(host)) {
              matchedLogin = curLogin; matchedPass = curPass;
            } else if (curDefault && defaultLogin == null) {
              defaultLogin = curLogin; defaultPass = curPass;
            }
          }
          curLogin = null; curPass = null;
          if ("machine".equals(t) && i + 1 < tokens.size()) {
            curMachine = tokens.get(++i);
            curDefault = false;
          } else {
            curMachine = null;
            curDefault = true;
          }
        } else if ("login".equals(t) && i + 1 < tokens.size()) {
          curLogin = tokens.get(++i);
        } else if ("password".equals(t) && i + 1 < tokens.size()) {
          curPass = tokens.get(++i);
        } else if ("macdef".equals(t)) {
          break;
        }
      }
      if (curLogin != null && curPass != null) {
        if (curMachine != null && curMachine.equalsIgnoreCase(host)) {
          matchedLogin = curLogin; matchedPass = curPass;
        } else if (curDefault && defaultLogin == null) {
          defaultLogin = curLogin; defaultPass = curPass;
        }
      }
      String user = matchedLogin != null ? matchedLogin : defaultLogin;
      String pass = matchedPass != null ? matchedPass : defaultPass;
      if (user == null || pass == null) return null;
      String encoded = java.util.Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
      return "Basic " + encoded;
    } catch (IOException ignored) {
      return null;
    }
  }

  /** Parses {@code Content-Range: bytes START-END/TOTAL} into {@code [start, end, total]}, or null. */
  private static long[] parseContentRange(String header) {
    if (header == null) return null;
    String s = header.trim();
    if (!s.regionMatches(true, 0, "bytes ", 0, 6)) return null;
    int dash = s.indexOf('-', 6);
    int slash = s.indexOf('/', dash + 1);
    if (dash < 0 || slash < 0) return null;
    try {
      long start = Long.parseLong(s.substring(6, dash).trim());
      long end = Long.parseLong(s.substring(dash + 1, slash).trim());
      String totStr = s.substring(slash + 1).trim();
      if ("*".equals(totStr)) return null;
      long total = Long.parseLong(totStr);
      if (start < 0 || end < start || total <= end) return null;
      return new long[] {start, end, total};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static void verifyZipMagic(byte[] b) throws IOException {
    if (b.length < 4 || b[0] != 'P' || b[1] != 'K' || b[2] != 3 || b[3] != 4) {
      throw new IOException("not an APK (not a ZIP file)");
    }
  }

  private static void writeFully(FileChannel ch, byte[] buf, int off, int len, long pos) throws IOException {
    ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
    long p = pos;
    while (bb.hasRemaining()) {
      int n = ch.write(bb, p);
      if (n <= 0) throw new IOException("short write to file channel");
      p += n;
    }
  }

  private static String sha256Channel(FileChannel ch) throws IOException {
    MessageDigest md = newSha256();
    ByteBuffer bb = ByteBuffer.allocate(256 * 1024);
    long pos = 0;
    long size = ch.size();
    while (pos < size) {
      bb.clear();
      int n = ch.read(bb, pos);
      if (n <= 0) break;
      md.update(bb.array(), 0, n);
      pos += n;
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256Hex(byte[] data) {
    return HexFormat.of().formatHex(newSha256().digest(data));
  }
}
