package dev.apksim;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * Fetches an APK from an HTTP(S) URL by reusing matching ZIP entries from builds already stored on disk (issue 674),
 * fetching only the remaining byte ranges with HTTP {@code Range} requests, and verifying the rebuilt file's full
 * SHA-256 before returning.
 *
 * <p>Runs only when an expected SHA-256 is known ahead of time: either named by the caller (e.g. beam's
 * {@code /beam/start} or serve's {@code POST /api/apps {"sha256"}}), or returned by the server in a digest header
 * ({@code X-Checksum-Sha256}, {@code Repr-Digest: sha-256=:...:}, or {@code Digest: sha-256=...}). When the file
 * cannot be verified, the server does not answer {@code 206 Partial Content}, the central directory cannot be read,
 * overlap with stored builds is too small, or the final SHA-256 mismatches, {@link #fetch} cleans up and returns
 * {@code null} so the caller falls back to a normal whole download.
 *
 * <p>When a stored build shares a large same-offset subset of entries with the new build, the staging file is first
 * cloned copy-on-write ({@code cp -c} on APFS, {@code cp --reflink=always} on Linux) and same-offset runs are kept
 * untouched so those blocks stay shared on disk.
 */
public final class ZipRangeFetch {
  private static final int DEFAULT_CONNECTIONS = Math.max(1, Math.min(12, Integer.getInteger("apksim.downloadConnections", 6)));
  private static final int MAX_CANDIDATES = 16;
  private static final long MAX_CD_BYTES = 64L * 1024 * 1024;
  private static final long MAX_CHUNK_BYTES = 8L * 1024 * 1024;
  private static final long MAX_GAP_MERGE_BYTES = 64L * 1024;
  private static final long MAX_GREEDY_GAP_BYTES = 512L * 1024;
  private static final int TARGET_MAX_RANGES = 64;
  private static final int BUF_SIZE = 128 * 1024;
  private static final int CONNECT_TIMEOUT_S = 15;
  private static final int REQUEST_TIMEOUT_S = 120;
  private static final int EOCD_MAX_SEARCH = 65535 + 22;
  private static final byte[] APK_SIG_MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
  private static final AtomicLong PART_SEQ = new AtomicLong();

  private ZipRangeFetch() {}

  /**
   * Definite policy or validation failure (disallowed redirect, non-APK content type, size limit exceeded) that the
   * caller should report directly rather than hiding behind a fallback download.
   */
  public static final class PolicyException extends IOException {
    PolicyException(String message) {
      super(message);
    }
  }

  public record Options(
      Map<String, String> headers,
      boolean netrc,
      String expectedSha256,
      Path reuseDir,
      Path stageDir,
      long maxBytes,
      int maxRedirects,
      int maxConnections,
      Function<String, URI> hopAllow,
      boolean followRedirects,
      boolean checkContentType,
      boolean anyBinary,
      boolean allowExactCacheHit,
      LongConsumer onTotal,
      LongConsumer onProgress
  ) {
    public static Options forServe(Map<String, String> headers, String expectedSha256, Path uploadsDir, long maxBytes,
        LongConsumer onTotal, LongConsumer onProgress) {
      return new Options(headers == null ? Map.of() : headers, false, normalizeSha256(expectedSha256), uploadsDir,
          uploadsDir, maxBytes, 8, DEFAULT_CONNECTIONS, null, true, false, true, true, onTotal, onProgress);
    }

    public static Options forBeam(Function<String, URI> hopAllow, boolean anyBinary, String expectedSha256,
        Path uploadsDir, long maxBytes) {
      Map<String, String> h = Map.of("Accept", "application/vnd.android.package-archive" + (anyBinary ? ", application/octet-stream" : ""));
      return new Options(h, false, normalizeSha256(expectedSha256), uploadsDir, uploadsDir, maxBytes,
          hopAllow != null ? 5 : 0, DEFAULT_CONNECTIONS, hopAllow, hopAllow != null, true, anyBinary, false, null, null);
    }
  }

  public record Result(
      Path path,
      long bytes,
      long fetchedBytes,
      long reusedBytes,
      int rangesFetched,
      String sha256,
      boolean reusedWholeFile,
      long elapsedMs
  ) {}

  private record EntryKey(
      byte[] name,
      int versionNeeded,
      int flags,
      int method,
      int modTime,
      int modDate,
      long crc32,
      long compSize,
      long uncompSize
  ) {
    @Override
    public boolean equals(Object o) {
      return o instanceof EntryKey k
          && versionNeeded == k.versionNeeded && flags == k.flags && method == k.method
          && modTime == k.modTime && modDate == k.modDate && crc32 == k.crc32
          && compSize == k.compSize && uncompSize == k.uncompSize && Arrays.equals(name, k.name);
    }

    @Override
    public int hashCode() {
      int h = Arrays.hashCode(name);
      h = 31 * h + Long.hashCode(crc32);
      h = 31 * h + Long.hashCode(compSize);
      h = 31 * h + Long.hashCode(uncompSize);
      h = 31 * h + (method ^ (flags << 8) ^ (modTime << 16));
      return h;
    }
  }

  private static final class ZipEntryInfo {
    final EntryKey key;
    final byte[] cdExtra;
    final long localHeaderOffset;
    long spanEnd;

    ZipEntryInfo(EntryKey key, byte[] cdExtra, long localHeaderOffset) {
      this.key = key;
      this.cdExtra = cdExtra;
      this.localHeaderOffset = localHeaderOffset;
    }

    long spanLen() {
      return spanEnd - localHeaderOffset;
    }
  }

  private enum MatchKind {
    EXACT_RUN,            // entire [R.off, R.spanEnd) copied from [L.off, L.off + localExactLen)
    SYNTH_LFH,            // synthesized LFH (zero-filled extra) + local payload copied to [R.spanEnd - compSize, R.spanEnd)
    EXACT_PREFIX_GAP,     // [R.off, R.off + localExactLen) copied; trailing gap fetched
    PAYLOAD_ONLY          // local payload copied to [R.spanEnd - compSize, R.spanEnd); LFH fetched
  }

  private record Match(
      Path apkPath,
      int apkRank,
      long localOff,
      long localExactLen,
      long localPayloadOff,
      int lfhNameLen,
      long destStart,
      long destEnd,
      int synthExtraLen,
      MatchKind kind,
      boolean sameOffset
  ) {
    int priority() {
      return switch (kind) {
        case EXACT_RUN -> sameOffset ? 5 : 4;
        case SYNTH_LFH -> 3;
        case EXACT_PREFIX_GAP -> 2;
        case PAYLOAD_ONLY -> 1;
      };
    }

    long reusedSpan() {
      return destEnd - destStart;
    }
  }

  private record Range(long start, long end) {
    long length() {
      return end - start + 1;
    }
  }

  private record ResolvedHead(
      URI finalUri,
      Map<String, String> finalHeaders,
      long remoteSize,
      String etag,
      String digestSha256,
      byte[] prefetchedTail,
      long prefetchedStart
  ) {}

  private record ResolvedResponse(
      HttpResponse<InputStream> resp,
      URI finalUri,
      Map<String, String> finalHeaders,
      String digestFromHops
  ) {}

  /**
   * Attempts a ranged fetch reusing matching entries from {@code opts.reuseDir()}.
   *
   * @return a verified {@link Result}, or {@code null} to fall back to a whole download.
   * @throws PolicyException on a definitive policy violation (disallowed redirect, bad content type, over maxBytes).
   */
  public static Result fetch(String url, Options opts) throws IOException {
    if ("false".equalsIgnoreCase(System.getProperty("apksim.rangeReuse"))) return null;
    if (opts == null || opts.reuseDir() == null || !Files.isDirectory(opts.reuseDir())) return null;
    long t0 = System.nanoTime();

    URI startUri;
    try {
      startUri = new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
    if ((!"https".equalsIgnoreCase(startUri.getScheme()) && !"http".equalsIgnoreCase(startUri.getScheme()))
        || startUri.getHost() == null) {
      return null;
    }
    for (Map.Entry<String, String> e : opts.headers().entrySet()) {
      String k = e.getKey(), v = e.getValue();
      if (k.indexOf('\r') >= 0 || k.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
        return null;
      }
    }

    List<Path> candidates = findCandidateApks(opts.reuseDir());
    if (candidates.isEmpty()) return null;

    Map<String, String> initialHeaders = opts.headers();
    if (opts.netrc() && !ApkDownloader.hasHeaderIgnoreCase(initialHeaders, "authorization")) {
      String basic = ApkDownloader.readNetrcBasicAuth(startUri.getHost());
      if (basic != null) {
        Map<String, String> copy = new LinkedHashMap<>(initialHeaders);
        copy.put("Authorization", basic);
        initialHeaders = copy;
      }
    }

    try (HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
        .build()) {
      ResolvedHead head = probeHead(client, startUri, initialHeaders, opts);
      if (head == null) return null;

      String targetSha256 = opts.expectedSha256() != null ? opts.expectedSha256() : head.digestSha256();
      if (targetSha256 == null) return null;

      long remoteSize = head.remoteSize();
      // Exact cache hit: caller didn't supply sha256, HEAD supplied one that is already stored on disk.
      if (opts.allowExactCacheHit() && opts.expectedSha256() == null) {
        Path existing = opts.reuseDir().resolve(targetSha256 + ".apk");
        if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
          try {
            if (Files.size(existing) == remoteSize) {
              if (opts.onTotal() != null) opts.onTotal().accept(remoteSize);
              if (opts.onProgress() != null) opts.onProgress().accept(remoteSize);
              return new Result(existing, remoteSize, 0, remoteSize, 0, targetSha256, true,
                  (System.nanoTime() - t0) / 1_000_000);
            }
          } catch (IOException ignored) {}
        }
      }

      if (remoteSize <= 1024) return null;

      // Step 1: Fetch remote tail (EOCD + Central Directory + 24 bytes before CD for APK Signing Block detection).
      AtomicLong fetchedCounter = new AtomicLong();
      AtomicInteger rangesCounter = new AtomicInteger();
      byte[] tailBuf;
      long bufStart;
      if (head.prefetchedTail() != null) {
        tailBuf = head.prefetchedTail();
        bufStart = head.prefetchedStart();
        fetchedCounter.addAndGet(tailBuf.length);
        rangesCounter.incrementAndGet();
      } else {
        long tailSize = Math.min(remoteSize, Math.max(1024L, Math.min(512L * 1024L, remoteSize / 16L)));
        bufStart = remoteSize - tailSize;
        tailBuf = fetchBytes(client, head.finalUri(), head.finalHeaders(), head.etag(), bufStart, remoteSize - 1,
            remoteSize, opts);
        if (tailBuf == null) return null;
        fetchedCounter.addAndGet(tailBuf.length);
        rangesCounter.incrementAndGet();
      }

      int eocdRel = findEocd(tailBuf);
      if (eocdRel < 0) {
        long maxTail = Math.min(remoteSize, EOCD_MAX_SEARCH);
        if (tailBuf.length < maxTail) {
          long newStart = remoteSize - maxTail;
          byte[] prefix = fetchBytes(client, head.finalUri(), head.finalHeaders(), head.etag(), newStart, bufStart - 1,
              remoteSize, opts);
          if (prefix == null) return null;
          fetchedCounter.addAndGet(prefix.length);
          rangesCounter.incrementAndGet();
          tailBuf = concat(prefix, tailBuf);
          bufStart = newStart;
          eocdRel = findEocd(tailBuf);
        }
        if (eocdRel < 0) return null;
      }

      long eocdAbs = bufStart + eocdRel;
      EocdInfo eocd = parseEocd(tailBuf, eocdRel, eocdAbs);
      if (eocd == null) return null;
      if (eocd.zip64LocatorOffset >= 0) {
        if (eocd.zip64LocatorOffset < bufStart) {
          byte[] prefix = fetchBytes(client, head.finalUri(), head.finalHeaders(), head.etag(),
              eocd.zip64LocatorOffset, bufStart - 1, remoteSize, opts);
          if (prefix == null) return null;
          fetchedCounter.addAndGet(prefix.length);
          rangesCounter.incrementAndGet();
          tailBuf = concat(prefix, tailBuf);
          bufStart = eocd.zip64LocatorOffset;
        }
        eocd = parseZip64Eocd(tailBuf, (int) (eocd.zip64LocatorOffset - bufStart), eocdAbs);
        if (eocd == null) return null;
      }

      if (eocd.cdOffset < 0 || eocd.cdSize <= 0 || eocd.cdSize > MAX_CD_BYTES
          || eocd.cdOffset + eocd.cdSize > eocdAbs) {
        return null;
      }

      long wantStart = Math.max(0L, eocd.cdOffset - 24L);
      if (remoteSize - wantStart > MAX_CD_BYTES + EOCD_MAX_SEARCH) return null;
      if (wantStart < bufStart) {
        byte[] prefix = fetchBytes(client, head.finalUri(), head.finalHeaders(), head.etag(),
            wantStart, bufStart - 1, remoteSize, opts);
        if (prefix == null) return null;
        fetchedCounter.addAndGet(prefix.length);
        rangesCounter.incrementAndGet();
        tailBuf = concat(prefix, tailBuf);
        bufStart = wantStart;
      }

      long entriesEnd = detectEntriesEnd(tailBuf, bufStart, eocd.cdOffset);
      List<ZipEntryInfo> remoteEntries = parseCentralDirectory(tailBuf, (int) (eocd.cdOffset - bufStart),
          (int) eocd.cdSize, eocd.entryCount);
      if (remoteEntries == null || remoteEntries.isEmpty()) return null;

      remoteEntries.sort(Comparator.comparingLong(e -> e.localHeaderOffset));
      if (entriesEnd < remoteEntries.get(remoteEntries.size() - 1).localHeaderOffset) {
        entriesEnd = eocd.cdOffset;
      }
      for (int i = 0; i < remoteEntries.size(); i++) {
        ZipEntryInfo e = remoteEntries.get(i);
        long nxt = i + 1 < remoteEntries.size() ? remoteEntries.get(i + 1).localHeaderOffset : entriesEnd;
        if (e.localHeaderOffset < 0 || nxt < e.localHeaderOffset || nxt > entriesEnd) return null;
        if (i + 1 < remoteEntries.size() && nxt == e.localHeaderOffset) return null; // duplicate offset
        e.spanEnd = nxt;
      }

      // Step 2: Match remote entries against local candidate APKs (one CD in memory at a time).
      Match[] matches = matchAgainstCandidates(remoteEntries, candidates, bufStart);

      // Step 3: Compute missing ranges in [0, bufStart) and coalesce nearby ranges.
      List<Range> missing = computeMissingRanges(matches, bufStart);
      List<Range> coalesced = coalesceRanges(missing, remoteSize);

      long missingBytes = 0;
      for (Range r : coalesced) missingBytes += r.length();
      long totalFetchBytes = tailBuf.length + missingBytes;
      long reusedBytes = remoteSize - totalFetchBytes;

      // Worth-it gate: require at least 10% reuse (or at least 4 MB on large APKs) and < 90% fetched.
      if (reusedBytes <= 0 || (reusedBytes < 4L * 1024 * 1024 && reusedBytes * 10L < remoteSize)
          || totalFetchBytes >= remoteSize * 90L / 100L) {
        return null;
      }

      // Split huge missing ranges into parallel chunks.
      int maxConn = Math.max(1, opts.maxConnections());
      List<Range> chunks = splitChunks(coalesced, maxConn);

      // Drop local matches completely shadowed by a coalesced fetch range.
      List<Match> activeMatches = filterShadowedMatches(matches, coalesced);

      // Pick best base APK for copy-on-write cloning (Part 3: same-offset exact runs).
      Map<Path, Long> sameOffByApk = new HashMap<>();
      for (Match m : activeMatches) {
        if (m.kind == MatchKind.EXACT_RUN && m.sameOffset) {
          sameOffByApk.merge(m.apkPath, m.reusedSpan(), Long::sum);
        }
      }
      Path bestCloneBase = null;
      long bestCloneBytes = 0;
      for (Map.Entry<Path, Long> e : sameOffByApk.entrySet()) {
        if (e.getValue() > bestCloneBytes) {
          bestCloneBase = e.getKey();
          bestCloneBytes = e.getValue();
        }
      }

      // Step 4: Assemble staged .part file (CoW clone if worthwhile + local copies + parallel Range GETs + tailBuf).
      Files.createDirectories(opts.stageDir());
      Path tmp = opts.stageDir().resolve("dl-reuse-" + ProcessHandle.current().pid() + "-" + PART_SEQ.incrementAndGet() + ".part");
      boolean ok = false;
      try {
        boolean cloned = false;
        if (bestCloneBase != null && bestCloneBytes >= Math.max(64L * 1024, remoteSize / 16L)) {
          cloned = tryCowClone(bestCloneBase, tmp);
        }

        if (opts.onTotal() != null) opts.onTotal().accept(remoteSize);
        AtomicLong progress = new AtomicLong(tailBuf.length);
        if (opts.onProgress() != null) opts.onProgress().accept(progress.get());

        try (FileChannel outCh = cloned
            ? FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE)
            : FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
          if (cloned && outCh.size() > remoteSize) {
            outCh.truncate(remoteSize);
          }

          // Local entry copies grouped by source APK (opening each source FileChannel once).
          if (!copyLocalMatches(activeMatches, cloned ? bestCloneBase : null, outCh, progress, opts.onProgress())) {
            return null;
          }

          // Parallel Range GETs for all missing chunks.
          if (!chunks.isEmpty()) {
            if (!fetchAllChunks(client, head.finalUri(), head.finalHeaders(), head.etag(), chunks, maxConn,
                remoteSize, opts, outCh, progress, fetchedCounter, rangesCounter)) {
              return null;
            }
          }

          // Write the already-fetched tailBuf ([bufStart, remoteSize)) last so it is authoritative for the tail.
          writeFully(outCh, tailBuf, 0, tailBuf.length, bufStart);

          if (outCh.size() != remoteSize) return null;

          // Verify ZIP magic.
          ByteBuffer magic = ByteBuffer.allocate(4);
          while (magic.hasRemaining() && outCh.read(magic, magic.position()) > 0) {}
          byte[] mb = magic.array();
          if (mb[0] != 'P' || mb[1] != 'K' || mb[2] != 3 || mb[3] != 4) return null;

          // Verify full-file SHA-256 against targetSha256.
          String actualSha = sha256Channel(outCh, remoteSize);
          if (!targetSha256.equals(actualSha)) {
            System.err.printf("[apksim] ranged reuse SHA-256 mismatch (got %s, expected %s): falling back to full download%n",
                actualSha.substring(0, 12), targetSha256.substring(0, 12));
            return null;
          }

          ok = true;
          long actualFetched = fetchedCounter.get();
          long actualReused = Math.max(0L, remoteSize - actualFetched);
          return new Result(tmp, remoteSize, actualFetched, actualReused, rangesCounter.get(),
              actualSha, false, (System.nanoTime() - t0) / 1_000_000);
        }
      } finally {
        if (!ok) {
          try {
            Files.deleteIfExists(tmp);
          } catch (IOException ignored) {}
        }
      }
    } catch (PolicyException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      // Any non-policy failure cleanly falls back to the caller's whole download.
      return null;
    }
  }

  // ---------------------------------------------------------------- HTTP probing & ranged requests

  private static ResolvedHead probeHead(HttpClient client, URI startUri, Map<String, String> headers, Options opts)
      throws IOException {
    ResolvedResponse r = sendFollowingRedirects(client, startUri, headers, "HEAD", null, null, opts);
    if (r == null) return null;
    try (InputStream body = r.resp.body()) {
      int code = r.resp.statusCode();
      if (code == 200) {
        if (opts.checkContentType()) {
          checkContentType(r.resp.headers(), opts.anyBinary(), false);
        }
        String ar = r.resp.headers().firstValue("accept-ranges").orElse("");
        if ("none".equalsIgnoreCase(ar.strip())) return null;
        long len = r.resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (len > opts.maxBytes()) {
          throw new PolicyException("too large: " + (len >> 20) + " MB (limit " + (opts.maxBytes() >> 20) + " MB)");
        }
        if (len < 22) return null;
        String digest = parseSha256Digest(r.resp.headers());
        if (digest == null) digest = r.digestFromHops;
        String etag = strongEtag(r.resp.headers().firstValue("etag").orElse(null));
        return new ResolvedHead(r.finalUri, r.finalHeaders, len, etag, digest, null, -1);
      }
      // Some servers (e.g. S3/GCS presigned GET URLs) reject HEAD with 403/405/501 while supporting ranged GET.
      if ((code == 403 || code == 405 || code == 501) && opts.expectedSha256() != null) {
        return probeSuffixGet(client, startUri, headers, opts);
      }
      return null;
    }
  }

  private static ResolvedHead probeSuffixGet(HttpClient client, URI startUri, Map<String, String> headers, Options opts)
      throws IOException {
    ResolvedResponse r = sendFollowingRedirects(client, startUri, headers, "GET", "bytes=-65536", null, opts);
    if (r == null) return null;
    try (InputStream body = r.resp.body()) {
      int code = r.resp.statusCode();
      if (code != 206) {
        if (code == 200 && opts.checkContentType()) {
          checkContentType(r.resp.headers(), opts.anyBinary(), true);
        }
        return null;
      }
      if (opts.checkContentType()) {
        checkContentType(r.resp.headers(), opts.anyBinary(), true);
      }
      long[] cr = ApkDownloader.parseContentRange(r.resp.headers().firstValue("content-range").orElse(null));
      if (cr == null) return null;
      long start = cr[0], end = cr[1], total = cr[2];
      if (total > opts.maxBytes()) {
        throw new PolicyException("too large: " + (total >> 20) + " MB (limit " + (opts.maxBytes() >> 20) + " MB)");
      }
      if (end != total - 1 || total < 22) return null;
      long wantLen = end - start + 1;
      if (wantLen > 256 * 1024) return null;
      byte[] tail = readExact(body, (int) wantLen);
      if (tail == null) return null;
      String digest = parseSha256Digest(r.resp.headers());
      if (digest == null) digest = r.digestFromHops;
      String etag = strongEtag(r.resp.headers().firstValue("etag").orElse(null));
      return new ResolvedHead(r.finalUri, r.finalHeaders, total, etag, digest, tail, start);
    }
  }

  private static ResolvedResponse sendFollowingRedirects(HttpClient client, URI startUri, Map<String, String> headers,
      String method, String rangeHeader, String ifRange, Options opts) throws IOException {
    URI cur = startUri;
    Map<String, String> curHeaders = headers;
    String digestSeen = null;
    int maxHops = opts.followRedirects() ? opts.maxRedirects() : 0;
    for (int hop = 0; ; hop++) {
      HttpRequest.Builder rb = HttpRequest.newBuilder(cur)
          .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
          .header("User-Agent", "apksim/1")
          .header("Accept-Encoding", "identity");
      for (Map.Entry<String, String> e : curHeaders.entrySet()) {
        try {
          rb.header(e.getKey(), e.getValue());
        } catch (IllegalArgumentException bad) {
          return null;
        }
      }
      if (rangeHeader != null) rb.header("Range", rangeHeader);
      if (ifRange != null) rb.header("If-Range", ifRange);
      if ("HEAD".equals(method)) rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
      else rb.GET();

      HttpResponse<InputStream> resp;
      try {
        resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("download interrupted", e);
      }
      int code = resp.statusCode();
      String hopDigest = parseSha256Digest(resp.headers());
      if (hopDigest != null) digestSeen = hopDigest;

      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        try (InputStream ignored = resp.body()) {}
        if (!opts.followRedirects()) {
          throw new PolicyException("the server answered " + code);
        }
        String loc = resp.headers().firstValue("location").orElse(null);
        if (loc == null || loc.isEmpty()) {
          throw new PolicyException("the server answered " + code + " with no address");
        }
        if (hop >= maxHops) {
          throw new PolicyException("too many redirects");
        }
        URI next = resolveRedirect(cur, loc, opts.hopAllow());
        if (next == null) {
          if (opts.hopAllow() != null) {
            throw new PolicyException("the server redirected to an address this Mac does not download from");
          }
          return null;
        }
        if (!curHeaders.isEmpty() && !ApkDownloader.sameOrigin(cur, next)) {
          // Strip all caller headers on cross-origin redirect, keeping only beam's non-secret Accept header if any.
          curHeaders = opts.checkContentType()
              ? Map.of("Accept", "application/vnd.android.package-archive" + (opts.anyBinary() ? ", application/octet-stream" : ""))
              : Map.of();
        }
        cur = next;
        continue;
      }
      return new ResolvedResponse(resp, cur, curHeaders, digestSeen);
    }
  }

  private static URI resolveRedirect(URI cur, String loc, Function<String, URI> hopAllow) {
    try {
      String curStr = cur.toString();
      String base = curStr.indexOf('?') < 0 ? curStr : curStr.substring(0, curStr.indexOf('?'));
      URI next = loc.startsWith("?") ? new URI(base + loc) : cur.resolve(new URI(loc));
      String scheme = next.getScheme();
      if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
      if (hopAllow != null) return hopAllow.apply(next.toString());
      return next;
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  private static void checkContentType(HttpHeaders headers, boolean anyBinary, boolean requirePresent) throws PolicyException {
    String type = headers.firstValue("content-type").orElse("");
    if (type.isEmpty()) {
      if (requirePresent) throw new PolicyException("the server sent no content type, not an APK");
      return; // Some servers omit Content-Type on HEAD; GET will still check it.
    }
    if (!(type.startsWith("application/vnd.android.package-archive")
        || (anyBinary && type.startsWith("application/octet-stream")))) {
      throw new PolicyException("the server sent " + type + ", not an APK");
    }
  }

  private static byte[] fetchBytes(HttpClient client, URI uri, Map<String, String> headers, String etag,
      long start, long end, long expectedTotal, Options opts) throws IOException {
    long expectedLen = end - start + 1;
    if (expectedLen <= 0 || expectedLen > MAX_CD_BYTES + EOCD_MAX_SEARCH) return null;
    String range = "bytes=" + start + "-" + end;
    for (int attempt = 0; attempt < 3; attempt++) {
      ResolvedResponse r;
      try {
        r = sendFollowingRedirects(client, uri, headers, "GET", range, etag, opts);
      } catch (PolicyException e) {
        throw e;
      } catch (IOException e) {
        if (attempt < 2) {
          sleepRetry(attempt);
          continue;
        }
        return null;
      }
      if (r == null) return null;
      try (InputStream in = r.resp.body()) {
        if (r.resp.statusCode() != 206) return null; // 200 means Range unsupported or If-Range changed
        if (opts.checkContentType()) {
          checkContentType(r.resp.headers(), opts.anyBinary(), true);
        }
        long[] cr = ApkDownloader.parseContentRange(r.resp.headers().firstValue("content-range").orElse(null));
        if (cr == null || cr[0] != start || cr[1] != end || cr[2] != expectedTotal) return null;
        byte[] data = readExact(in, (int) expectedLen);
        if (data != null) return data;
      } catch (PolicyException e) {
        throw e;
      } catch (IOException e) {
        if (attempt < 2) {
          sleepRetry(attempt);
          continue;
        }
        return null;
      }
    }
    return null;
  }

  private static boolean fetchAllChunks(HttpClient client, URI finalUri, Map<String, String> finalHeaders, String etag,
      List<Range> chunks, int maxConn, long expectedTotal, Options opts, FileChannel outCh, AtomicLong progress,
      AtomicLong fetchedCounter, AtomicInteger rangesCounter) throws IOException {
    int workers = Math.min(maxConn, chunks.size());
    AtomicInteger nextIdx = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    ExecutorService pool = workers > 1 ? Executors.newFixedThreadPool(workers - 1, r -> {
      Thread t = new Thread(r, "apksim-range-worker");
      t.setDaemon(true);
      return t;
    }) : null;
    List<Future<?>> futures = new ArrayList<>(Math.max(0, workers - 1));
    try {
      if (pool != null) {
        for (int w = 0; w < workers - 1; w++) {
          futures.add(pool.submit(() -> runChunkLoop(client, finalUri, finalHeaders, etag, chunks, nextIdx,
              expectedTotal, opts, outCh, progress, fetchedCounter, rangesCounter, failure)));
        }
      }
      runChunkLoop(client, finalUri, finalHeaders, etag, chunks, nextIdx, expectedTotal, opts, outCh,
          progress, fetchedCounter, rangesCounter, failure);
      for (Future<?> f : futures) {
        try {
          f.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        } catch (ExecutionException e) {
          failure.compareAndSet(null, e.getCause());
        }
      }
    } finally {
      if (pool != null) pool.shutdownNow();
    }
    Throwable err = failure.get();
    if (err instanceof PolicyException pe) throw pe;
    return err == null;
  }

  private static void runChunkLoop(HttpClient client, URI finalUri, Map<String, String> finalHeaders, String etag,
      List<Range> chunks, AtomicInteger nextIdx, long expectedTotal, Options opts, FileChannel outCh,
      AtomicLong progress, AtomicLong fetchedCounter, AtomicInteger rangesCounter, AtomicReference<Throwable> failure) {
    byte[] buf = new byte[BUF_SIZE];
    while (failure.get() == null) {
      int idx = nextIdx.getAndIncrement();
      if (idx >= chunks.size()) return;
      Range r = chunks.get(idx);
      try {
        fetchChunkWithRetry(client, finalUri, finalHeaders, etag, r, expectedTotal, opts, outCh, buf,
            progress, fetchedCounter, rangesCounter, failure);
      } catch (Throwable t) {
        failure.compareAndSet(null, t);
        return;
      }
    }
  }

  private static void fetchChunkWithRetry(HttpClient client, URI finalUri, Map<String, String> finalHeaders, String etag,
      Range r, long expectedTotal, Options opts, FileChannel outCh, byte[] buf, AtomicLong progress,
      AtomicLong fetchedCounter, AtomicInteger rangesCounter, AtomicReference<Throwable> failure) throws IOException {
    IOException lastErr = null;
    long expected = r.length();
    String rangeHeader = "bytes=" + r.start + "-" + r.end;
    for (int attempt = 0; attempt < 3; attempt++) {
      if (failure.get() != null) return;
      long written = 0;
      try {
        ResolvedResponse resp = sendFollowingRedirects(client, finalUri, finalHeaders, "GET", rangeHeader, etag, opts);
        if (resp == null) throw new IOException("range request failed");
        try (InputStream in = resp.resp.body()) {
          int code = resp.resp.statusCode();
          if (code != 206) {
            // Non-206 (e.g. 200 when If-Range mismatched or server ignored Range): abort immediately without retry.
            throw new NonRangeException("expected HTTP 206, got " + code);
          }
          if (opts.checkContentType()) {
            checkContentType(resp.resp.headers(), opts.anyBinary(), true);
          }
          long[] cr = ApkDownloader.parseContentRange(resp.resp.headers().firstValue("content-range").orElse(null));
          if (cr == null || cr[0] != r.start || cr[1] != r.end || cr[2] != expectedTotal) {
            throw new NonRangeException("unexpected Content-Range for " + rangeHeader);
          }
          long pos = r.start;
          while (written < expected) {
            if (failure.get() != null) return;
            int n = in.read(buf, 0, (int) Math.min(buf.length, expected - written));
            if (n < 0) throw new IOException("range " + rangeHeader + " ended early at " + written + "/" + expected);
            writeFully(outCh, buf, 0, n, pos);
            pos += n;
            written += n;
            long p = progress.addAndGet(n);
            if (opts.onProgress() != null) opts.onProgress().accept(p);
          }
          fetchedCounter.addAndGet(written);
          rangesCounter.incrementAndGet();
          return;
        }
      } catch (PolicyException | NonRangeException e) {
        if (written > 0) progress.addAndGet(-written);
        throw e;
      } catch (IOException e) {
        lastErr = e;
        if (written > 0) progress.addAndGet(-written);
        if (attempt < 2) sleepRetry(attempt);
      }
    }
    throw lastErr;
  }

  private static final class NonRangeException extends IOException {
    NonRangeException(String msg) {
      super(msg);
    }
  }

  private static void sleepRetry(int attempt) throws IOException {
    try {
      Thread.sleep(100L * (attempt + 1));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IOException("download interrupted", ie);
    }
  }

  // ---------------------------------------------------------------- Digest header parsing

  /**
   * Extracts a 64-character lowercase hex SHA-256 digest from standard HTTP response headers:
   * {@code X-Checksum-Sha256} (hex), {@code Repr-Digest} (RFC 9530 {@code sha-256=:base64:}), or
   * {@code Digest} (RFC 3230 {@code sha-256=base64}). Returns {@code null} if absent or malformed.
   */
  public static String parseSha256Digest(HttpHeaders headers) {
    if (headers == null) return null;
    for (String v : headers.allValues("x-checksum-sha256")) {
      String norm = normalizeSha256(v);
      if (norm != null) return norm;
    }
    for (String v : headers.allValues("repr-digest")) {
      String norm = parseReprDigest(v);
      if (norm != null) return norm;
    }
    for (String v : headers.allValues("digest")) {
      String norm = parseRfc3230Digest(v);
      if (norm != null) return norm;
    }
    return null;
  }

  static String parseReprDigest(String header) {
    if (header == null || header.isEmpty()) return null;
    for (String item : header.split(",")) {
      String s = item.strip();
      int eq = s.indexOf('=');
      if (eq <= 0) continue;
      String alg = s.substring(0, eq).strip().toLowerCase(Locale.ROOT);
      if (!"sha-256".equals(alg)) continue;
      String val = s.substring(eq + 1).strip();
      if (!val.startsWith(":")) continue;
      int close = val.indexOf(':', 1);
      if (close <= 1) continue;
      String hex = decodeBase64Sha256(val.substring(1, close));
      if (hex != null) return hex;
    }
    return null;
  }

  static String parseRfc3230Digest(String header) {
    if (header == null || header.isEmpty()) return null;
    for (String item : header.split(",")) {
      String s = item.strip();
      int eq = s.indexOf('=');
      if (eq <= 0) continue;
      String alg = s.substring(0, eq).strip().toLowerCase(Locale.ROOT);
      if (!"sha-256".equals(alg)) continue;
      String val = s.substring(eq + 1).strip();
      String hex = decodeBase64Sha256(val);
      if (hex != null) return hex;
    }
    return null;
  }

  private static String decodeBase64Sha256(String b64) {
    try {
      byte[] raw = Base64.getDecoder().decode(b64);
      if (raw.length != 32) return null;
      return HexFormat.of().formatHex(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static String normalizeSha256(String s) {
    if (s == null) return null;
    String n = s.strip().toLowerCase(Locale.ROOT);
    return n.matches("[0-9a-f]{64}") ? n : null;
  }

  private static String strongEtag(String etag) {
    if (etag == null) return null;
    String s = etag.strip();
    if (s.length() < 2 || s.startsWith("W/") || s.startsWith("w/") || !s.startsWith("\"") || !s.endsWith("\"")) {
      return null;
    }
    return s;
  }

  // ---------------------------------------------------------------- ZIP parsing

  private record EocdInfo(long entryCount, long cdSize, long cdOffset, long zip64LocatorOffset) {}

  private static int findEocd(byte[] buf) {
    int minPos = Math.max(0, buf.length - EOCD_MAX_SEARCH);
    for (int i = buf.length - 22; i >= minPos; i--) {
      if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
        int commentLen = u16(buf, i + 20);
        if (i + 22 + commentLen == buf.length) return i;
      }
    }
    return -1;
  }

  private static EocdInfo parseEocd(byte[] buf, int pos, long eocdAbs) {
    if (pos < 0 || pos + 22 > buf.length) return null;
    int disk = u16(buf, pos + 4);
    int cdDisk = u16(buf, pos + 6);
    if (disk != 0 || cdDisk != 0) return null;
    int entries = u16(buf, pos + 10);
    long cdSize = u32(buf, pos + 12);
    long cdOffset = u32(buf, pos + 16);
    if (entries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
      if (pos < 20) return null;
      int loc = pos - 20;
      if (buf[loc] != 0x50 || buf[loc + 1] != 0x4b || buf[loc + 2] != 0x06 || buf[loc + 3] != 0x07) return null;
      if (u32(buf, loc + 4) != 0 || u32(buf, loc + 16) != 1) return null;
      long z64Off = u64(buf, loc + 8);
      if (z64Off < 0 || z64Off + 56 > eocdAbs - 20) return null;
      return new EocdInfo(entries, cdSize, cdOffset, z64Off);
    }
    return new EocdInfo(entries, cdSize, cdOffset, -1);
  }

  private static EocdInfo parseZip64Eocd(byte[] buf, int pos, long eocdAbs) {
    if (pos < 0 || pos + 56 > buf.length) return null;
    if (buf[pos] != 0x50 || buf[pos + 1] != 0x4b || buf[pos + 2] != 0x06 || buf[pos + 3] != 0x06) return null;
    if (u32(buf, pos + 16) != 0 || u32(buf, pos + 20) != 0) return null;
    long entries = u64(buf, pos + 32);
    long cdSize = u64(buf, pos + 40);
    long cdOffset = u64(buf, pos + 48);
    if (entries < 0 || entries > 2_000_000 || cdSize < 0 || cdOffset < 0 || cdOffset + cdSize > eocdAbs) return null;
    return new EocdInfo(entries, cdSize, cdOffset, -1);
  }

  private static long detectEntriesEnd(byte[] tailBuf, long bufStart, long cdOffset) {
    if (cdOffset < 24 || cdOffset - 24 < bufStart) return cdOffset;
    int magicPos = (int) (cdOffset - 16 - bufStart);
    if (magicPos < 0 || magicPos + 16 > tailBuf.length) return cdOffset;
    for (int i = 0; i < 16; i++) {
      if (tailBuf[magicPos + i] != APK_SIG_MAGIC[i]) return cdOffset;
    }
    long blockSize = u64(tailBuf, (int) (cdOffset - 24 - bufStart));
    long totalSigSize = blockSize + 8L;
    if (blockSize < 24 || totalSigSize <= 0 || totalSigSize > cdOffset) return cdOffset;
    return cdOffset - totalSigSize;
  }

  private static List<ZipEntryInfo> parseCentralDirectory(byte[] buf, int off, int len, long expectedCount) {
    if (off < 0 || len < 0 || off + len > buf.length || expectedCount < 0 || expectedCount > 500_000) return null;
    List<ZipEntryInfo> out = new ArrayList<>((int) Math.min(expectedCount, 16384));
    int pos = off;
    int end = off + len;
    while (pos < end) {
      if (pos + 46 > end) return null;
      if (buf[pos] != 0x50 || buf[pos + 1] != 0x4b || buf[pos + 2] != 0x01 || buf[pos + 3] != 0x02) return null;
      int verNeed = u16(buf, pos + 6);
      int flags = u16(buf, pos + 8);
      int method = u16(buf, pos + 10);
      int modTime = u16(buf, pos + 12);
      int modDate = u16(buf, pos + 14);
      long crc = u32(buf, pos + 16);
      long compSize = u32(buf, pos + 20);
      long uncompSize = u32(buf, pos + 24);
      int nameLen = u16(buf, pos + 28);
      int extraLen = u16(buf, pos + 30);
      int commentLen = u16(buf, pos + 32);
      long localOff = u32(buf, pos + 42);
      if (pos + 46 + nameLen + extraLen + commentLen > end) return null;
      byte[] name = Arrays.copyOfRange(buf, pos + 46, pos + 46 + nameLen);
      byte[] extra = extraLen == 0 ? new byte[0] : Arrays.copyOfRange(buf, pos + 46 + nameLen, pos + 46 + nameLen + extraLen);
      if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOff == 0xFFFFFFFFL) {
        long[] z64 = parseZip64Extra(extra, uncompSize, compSize, localOff);
        if (z64 == null) return null;
        uncompSize = z64[0];
        compSize = z64[1];
        localOff = z64[2];
      }
      EntryKey key = new EntryKey(name, verNeed, flags, method, modTime, modDate, crc, compSize, uncompSize);
      out.add(new ZipEntryInfo(key, extra, localOff));
      pos += 46 + nameLen + extraLen + commentLen;
    }
    if (pos != end || out.size() != expectedCount) return null;
    return out;
  }

  private static long[] parseZip64Extra(byte[] extra, long uncomp, long comp, long off) {
    int p = 0;
    while (p + 4 <= extra.length) {
      int id = u16(extra, p);
      int sz = u16(extra, p + 2);
      p += 4;
      if (p + sz > extra.length) return null;
      if (id == 0x0001) {
        int q = p;
        if (uncomp == 0xFFFFFFFFL) {
          if (q + 8 > p + sz) return null;
          uncomp = u64(extra, q);
          q += 8;
        }
        if (comp == 0xFFFFFFFFL) {
          if (q + 8 > p + sz) return null;
          comp = u64(extra, q);
          q += 8;
        }
        if (off == 0xFFFFFFFFL) {
          if (q + 8 > p + sz) return null;
          off = u64(extra, q);
        }
        return new long[] {uncomp, comp, off};
      }
      p += sz;
    }
    return null;
  }

  // ---------------------------------------------------------------- Local candidate scanning & matching

  private static List<Path> findCandidateApks(Path dir) {
    record Candidate(Path path, long mtime) {}
    List<Candidate> found = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.apk")) {
      for (Path p : ds) {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
        try {
          long sz = Files.size(p);
          if (sz < 22) continue;
          found.add(new Candidate(p, Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis()));
        } catch (IOException ignored) {}
      }
    } catch (IOException e) {
      return List.of();
    }
    found.sort(Comparator.comparingLong(Candidate::mtime).reversed());
    List<Path> out = new ArrayList<>(Math.min(found.size(), MAX_CANDIDATES));
    for (int i = 0; i < found.size() && i < MAX_CANDIDATES; i++) out.add(found.get(i).path);
    return out;
  }

  private static Match[] matchAgainstCandidates(List<ZipEntryInfo> remoteEntries, List<Path> candidates, long bufStart) {
    Map<EntryKey, List<Integer>> remoteByKey = new HashMap<>(remoteEntries.size() * 2);
    for (int i = 0; i < remoteEntries.size(); i++) {
      remoteByKey.computeIfAbsent(remoteEntries.get(i).key, k -> new ArrayList<>(1)).add(i);
    }
    Match[] best = new Match[remoteEntries.size()];
    ByteBuffer lfhBuf = ByteBuffer.allocate(30);
    ByteBuffer ddBuf = ByteBuffer.allocate(24);
    byte[] extraScratch = new byte[16384];

    for (int rank = 0; rank < candidates.size(); rank++) {
      Path apk = candidates.get(rank);
      try (FileChannel ch = FileChannel.open(apk, StandardOpenOption.READ)) {
        long fileSize = ch.size();
        List<ZipEntryInfo> localEntries = readLocalCentralDirectory(ch, fileSize);
        if (localEntries == null) continue;

        for (ZipEntryInfo l : localEntries) {
          List<Integer> rIndices = remoteByKey.get(l.key);
          if (rIndices == null) continue;

          // Read and verify the 30-byte Local File Header on disk.
          lfhBuf.clear();
          if (!readFully(ch, lfhBuf, l.localHeaderOffset)) continue;
          byte[] lb = lfhBuf.array();
          if (lb[0] != 0x50 || lb[1] != 0x4b || lb[2] != 0x03 || lb[3] != 0x04) continue;
          if (u16(lb, 4) != l.key.versionNeeded || u16(lb, 6) != l.key.flags || u16(lb, 8) != l.key.method
              || u16(lb, 10) != l.key.modTime || u16(lb, 12) != l.key.modDate) {
            continue;
          }
          int lfhNameLen = u16(lb, 26);
          int lfhExtraLen = u16(lb, 28);
          if (lfhNameLen != l.key.name.length) continue;
          if ((l.key.flags & 8) == 0) {
            if (u32(lb, 14) != l.key.crc32) continue;
            long lfhComp = u32(lb, 18);
            long lfhUncomp = u32(lb, 22);
            if (lfhComp != 0xFFFFFFFFL && lfhComp != l.key.compSize) continue;
            if (lfhUncomp != 0xFFFFFFFFL && lfhUncomp != l.key.uncompSize) continue;
          }

          long localPayloadOff = l.localHeaderOffset + 30L + lfhNameLen + lfhExtraLen;
          long dataEnd = localPayloadOff + l.key.compSize;
          if (dataEnd > fileSize) continue;

          int ddLen = 0;
          if ((l.key.flags & 8) != 0) {
            ddLen = detectDataDescriptorLen(ch, dataEnd, fileSize, l.key.crc32, l.key.compSize, l.key.uncompSize, ddBuf);
            if (ddLen < 0) continue;
          }
          long localExactLen = 30L + lfhNameLen + lfhExtraLen + l.key.compSize + ddLen;
          if (l.localHeaderOffset + localExactLen > fileSize) continue;

          boolean extraAllZero = lfhExtraLen == 0 || isRangeAllZero(ch, l.localHeaderOffset + 30L + lfhNameLen,
              lfhExtraLen, extraScratch);

          for (int ri : rIndices) {
            ZipEntryInfo r = remoteEntries.get(ri);
            if (r.localHeaderOffset >= bufStart) continue; // Already in tailBuf
            Match cand = classifyMatch(apk, rank, l, r, localExactLen, localPayloadOff, lfhNameLen, lfhExtraLen,
                extraAllZero, bufStart);
            if (cand == null) continue;
            Match prev = best[ri];
            if (prev == null || cand.priority() > prev.priority()
                || (cand.priority() == prev.priority() && cand.reusedSpan() > prev.reusedSpan())) {
              best[ri] = cand;
            }
          }
        }
      } catch (IOException ignored) {
        // Candidate unreadable or concurrently removed: skip it.
      }
    }
    return best;
  }

  private static Match classifyMatch(Path apk, int rank, ZipEntryInfo l, ZipEntryInfo r,
      long localExactLen, long localPayloadOff, int lfhNameLen, int lfhExtraLen, boolean extraAllZero, long bufStart) {
    long rSpan = r.spanLen();
    long rStart = r.localHeaderOffset;
    long rEnd = r.spanEnd;

    // 1. Exact run match: LFH + payload (+ data descriptor) has the exact same byte length.
    if (rSpan == localExactLen && (lfhExtraLen == 0 || extraAllZero || Arrays.equals(r.cdExtra, l.cdExtra))) {
      long destEnd = Math.min(rEnd, bufStart);
      if (destEnd <= rStart) return null;
      return new Match(apk, rank, l.localHeaderOffset, localExactLen, localPayloadOff, lfhNameLen,
          rStart, destEnd, -1, MatchKind.EXACT_RUN, l.localHeaderOffset == rStart && destEnd == rEnd);
    }

    // 2. Stored entry whose zipalign LFH padding shifted (diffExtra in valid alignment range, payload aligned).
    if ((r.key.flags & 8) == 0 && r.key.method == 0) {
      long diffExtra = rSpan - (30L + r.key.name.length + r.key.compSize);
      long payOff = rEnd - r.key.compSize;
      boolean isSo = endsWithAscii(r.key.name, ".so");
      int maxAlign = isSo ? 16384 : 4;
      int reqAlign = isSo ? 4096 : 4;
      if (diffExtra >= 0 && diffExtra < maxAlign && (payOff % reqAlign) == 0) {
        if (r.cdExtra.length == 0 && extraAllZero) {
          long destEnd = Math.min(rEnd, bufStart);
          if (destEnd <= rStart) return null;
          return new Match(apk, rank, l.localHeaderOffset, localExactLen, localPayloadOff, lfhNameLen,
              rStart, destEnd, (int) diffExtra, MatchKind.SYNTH_LFH, false);
        }
        if (r.key.compSize >= 65536 && payOff < bufStart) {
          long destEnd = Math.min(rEnd, bufStart);
          if (destEnd > payOff) {
            return new Match(apk, rank, l.localHeaderOffset, localExactLen, localPayloadOff, lfhNameLen,
                payOff, destEnd, -1, MatchKind.PAYLOAD_ONLY, false);
          }
        }
      }
    }

    // 3. Entry followed by a zipflinger virtual-entry gap (same LFH size, trailing gap fetched from server).
    if (rSpan > localExactLen && ((r.key.method == 8 && lfhExtraLen == 0) || l.localHeaderOffset == rStart)
        && (lfhExtraLen == 0 || extraAllZero)) {
      long destEnd = Math.min(rStart + localExactLen, bufStart);
      if (destEnd <= rStart) return null;
      return new Match(apk, rank, l.localHeaderOffset, localExactLen, localPayloadOff, lfhNameLen,
          rStart, destEnd, -1, MatchKind.EXACT_PREFIX_GAP, l.localHeaderOffset == rStart);
    }

    return null;
  }

  private static List<ZipEntryInfo> readLocalCentralDirectory(FileChannel ch, long fileSize) throws IOException {
    if (fileSize < 22) return null;
    int tailLen = (int) Math.min(fileSize, EOCD_MAX_SEARCH);
    long tailStart = fileSize - tailLen;
    ByteBuffer tb = ByteBuffer.allocate(tailLen);
    if (!readFully(ch, tb, tailStart)) return null;
    byte[] tail = tb.array();
    int eocdRel = findEocd(tail);
    if (eocdRel < 0) return null;
    long eocdAbs = tailStart + eocdRel;
    EocdInfo eocd = parseEocd(tail, eocdRel, eocdAbs);
    if (eocd == null) return null;
    if (eocd.zip64LocatorOffset >= 0) {
      ByteBuffer z64 = ByteBuffer.allocate(56);
      if (!readFully(ch, z64, eocd.zip64LocatorOffset)) return null;
      eocd = parseZip64Eocd(z64.array(), 0, eocdAbs);
      if (eocd == null) return null;
    }
    if (eocd.cdOffset < 0 || eocd.cdSize <= 0 || eocd.cdSize > MAX_CD_BYTES || eocd.cdOffset + eocd.cdSize > eocdAbs) {
      return null;
    }
    byte[] cdBytes;
    int cdOffInBuf;
    if (eocd.cdOffset >= tailStart) {
      cdBytes = tail;
      cdOffInBuf = (int) (eocd.cdOffset - tailStart);
    } else {
      ByteBuffer cb = ByteBuffer.allocate((int) eocd.cdSize);
      if (!readFully(ch, cb, eocd.cdOffset)) return null;
      cdBytes = cb.array();
      cdOffInBuf = 0;
    }
    return parseCentralDirectory(cdBytes, cdOffInBuf, (int) eocd.cdSize, eocd.entryCount);
  }

  private static int detectDataDescriptorLen(FileChannel ch, long pos, long fileSize,
      long crc, long comp, long uncomp, ByteBuffer buf) throws IOException {
    int avail = (int) Math.min(24, fileSize - pos);
    if (avail < 12) return -1;
    buf.clear();
    buf.limit(avail);
    if (!readFully(ch, buf, pos)) return -1;
    byte[] b = buf.array();
    boolean hasSig = avail >= 16 && b[0] == 0x50 && b[1] == 0x4b && b[2] == 0x07 && b[3] == 0x08;
    if (hasSig) {
      if (u32(b, 4) == crc && u32(b, 8) == (comp & 0xFFFFFFFFL) && u32(b, 12) == (uncomp & 0xFFFFFFFFL)) return 16;
      if (avail >= 24 && u32(b, 4) == crc && u64(b, 8) == comp && u64(b, 16) == uncomp) return 24;
    }
    if (u32(b, 0) == crc && u32(b, 4) == (comp & 0xFFFFFFFFL) && u32(b, 8) == (uncomp & 0xFFFFFFFFL)) return 12;
    if (avail >= 20 && u32(b, 0) == crc && u64(b, 4) == comp && u64(b, 12) == uncomp) return 20;
    return -1;
  }

  private static boolean isRangeAllZero(FileChannel ch, long pos, int len, byte[] scratch) throws IOException {
    int rem = len;
    long p = pos;
    while (rem > 0) {
      int take = Math.min(rem, scratch.length);
      ByteBuffer bb = ByteBuffer.wrap(scratch, 0, take);
      if (!readFully(ch, bb, p)) return false;
      for (int i = 0; i < take; i++) {
        if (scratch[i] != 0) return false;
      }
      p += take;
      rem -= take;
    }
    return true;
  }

  // ---------------------------------------------------------------- Range planning & coalescing

  private static List<Range> computeMissingRanges(Match[] matches, long bufStart) {
    List<Range> missing = new ArrayList<>();
    long cursor = 0;
    for (Match m : matches) {
      if (m == null) continue;
      if (m.destStart > cursor) {
        missing.add(new Range(cursor, m.destStart - 1));
      }
      if (m.destEnd > cursor) cursor = m.destEnd;
    }
    if (cursor < bufStart) {
      missing.add(new Range(cursor, bufStart - 1));
    }
    return missing;
  }

  private static List<Range> coalesceRanges(List<Range> raw, long remoteSize) {
    if (raw.size() <= 1) return new ArrayList<>(raw);
    long gapMerge = Math.min(MAX_GAP_MERGE_BYTES, remoteSize / 32L);
    List<Range> merged = new ArrayList<>();
    Range cur = raw.get(0);
    for (int i = 1; i < raw.size(); i++) {
      Range nxt = raw.get(i);
      long gap = nxt.start - cur.end - 1;
      if (gap <= gapMerge) {
        cur = new Range(cur.start, Math.max(cur.end, nxt.end));
      } else {
        merged.add(cur);
        cur = nxt;
      }
    }
    merged.add(cur);

    // If still many small scattered ranges, greedily merge the smallest gaps (up to MAX_GREEDY_GAP_BYTES).
    long greedyMax = Math.min(MAX_GREEDY_GAP_BYTES, remoteSize / 16L);
    while (merged.size() > TARGET_MAX_RANGES) {
      int bestIdx = -1;
      long bestGap = Long.MAX_VALUE;
      for (int i = 0; i + 1 < merged.size(); i++) {
        long g = merged.get(i + 1).start - merged.get(i).end - 1;
        if (g < bestGap) {
          bestGap = g;
          bestIdx = i;
        }
      }
      if (bestIdx < 0 || bestGap > greedyMax) break;
      Range combined = new Range(merged.get(bestIdx).start, merged.get(bestIdx + 1).end);
      merged.set(bestIdx, combined);
      merged.remove(bestIdx + 1);
    }
    return merged;
  }

  private static List<Range> splitChunks(List<Range> ranges, int maxConn) {
    List<Range> out = new ArrayList<>(ranges.size());
    for (Range r : ranges) {
      if (maxConn > 1 && r.length() > MAX_CHUNK_BYTES) {
        for (long p = r.start; p <= r.end; p += MAX_CHUNK_BYTES) {
          out.add(new Range(p, Math.min(r.end, p + MAX_CHUNK_BYTES - 1)));
        }
      } else {
        out.add(r);
      }
    }
    return out;
  }

  private static List<Match> filterShadowedMatches(Match[] matches, List<Range> coalesced) {
    List<Match> out = new ArrayList<>();
    int ri = 0;
    for (Match m : matches) {
      if (m == null) continue;
      while (ri < coalesced.size() && coalesced.get(ri).end < m.destStart) ri++;
      if (ri < coalesced.size()) {
        Range r = coalesced.get(ri);
        if (r.start <= m.destStart && r.end >= m.destEnd - 1) continue; // Completely overwritten by fetched range
      }
      out.add(m);
    }
    return out;
  }

  // ---------------------------------------------------------------- Local copy & CoW clone

  private static boolean tryCowClone(Path src, Path dst) {
    try {
      List<String> cmd = HostOs.MAC
          ? List.of("cp", "-c", src.toString(), dst.toString())
          : List.of("cp", "--reflink=always", src.toString(), dst.toString());
      Process p = new ProcessBuilder(cmd)
          .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        Files.deleteIfExists(dst);
        return false;
      }
      if (p.exitValue() == 0 && Files.isRegularFile(dst, LinkOption.NOFOLLOW_LINKS)) return true;
      Files.deleteIfExists(dst);
      return false;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      try {
        Files.deleteIfExists(dst);
      } catch (IOException ignored) {}
      return false;
    }
  }

  private static boolean copyLocalMatches(List<Match> matches, Path clonedBase, FileChannel outCh,
      AtomicLong progress, LongConsumer onProgress) {
    // Group matches by source APK so each source file is opened once.
    Map<Path, List<Match>> byApk = new LinkedHashMap<>();
    for (Match m : matches) {
      byApk.computeIfAbsent(m.apkPath, k -> new ArrayList<>()).add(m);
    }
    byte[] buf = new byte[BUF_SIZE];
    byte[] zeros = new byte[16384];
    for (Map.Entry<Path, List<Match>> e : byApk.entrySet()) {
      Path srcApk = e.getKey();
      boolean isClonedBase = srcApk.equals(clonedBase);
      List<Match> list = e.getValue();
      try (FileChannel srcCh = FileChannel.open(srcApk, StandardOpenOption.READ)) {
        for (Match m : list) {
          long span = m.reusedSpan();
          if (isClonedBase && m.kind == MatchKind.EXACT_RUN && m.sameOffset) {
            // Bytes are already at exact same offset in the CoW-cloned file; leave blocks shared on disk!
            long p = progress.addAndGet(span);
            if (onProgress != null) onProgress.accept(p);
            continue;
          }
          switch (m.kind) {
            case EXACT_RUN, EXACT_PREFIX_GAP -> copyChannelRange(srcCh, m.localOff, outCh, m.destStart, span, buf);
            case PAYLOAD_ONLY -> copyChannelRange(srcCh, m.localPayloadOff, outCh, m.destStart, span, buf);
            case SYNTH_LFH -> {
              int hdrLen = 30 + m.lfhNameLen;
              byte[] hdr = new byte[hdrLen];
              if (!readFully(srcCh, ByteBuffer.wrap(hdr), m.localOff)) return false;
              hdr[28] = (byte) (m.synthExtraLen & 0xFF);
              hdr[29] = (byte) ((m.synthExtraLen >>> 8) & 0xFF);
              long maxWrite = m.destEnd - m.destStart;
              int hdrWrite = (int) Math.min(hdrLen, maxWrite);
              writeFully(outCh, hdr, 0, hdrWrite, m.destStart);
              long pos = m.destStart + hdrWrite;
              long remExtra = Math.min(m.synthExtraLen, m.destEnd - pos);
              while (remExtra > 0) {
                int z = (int) Math.min(zeros.length, remExtra);
                writeFully(outCh, zeros, 0, z, pos);
                pos += z;
                remExtra -= z;
              }
              long remPayload = m.destEnd - pos;
              if (remPayload > 0) {
                copyChannelRange(srcCh, m.localPayloadOff, outCh, pos, remPayload, buf);
              }
            }
          }
          long p = progress.addAndGet(span);
          if (onProgress != null) onProgress.accept(p);
        }
      } catch (IOException ex) {
        return false;
      }
    }
    return true;
  }

  private static void copyChannelRange(FileChannel src, long srcPos, FileChannel dst, long dstPos, long len, byte[] buf)
      throws IOException {
    long rem = len;
    long sp = srcPos, dp = dstPos;
    while (rem > 0) {
      int take = (int) Math.min(buf.length, rem);
      ByteBuffer bb = ByteBuffer.wrap(buf, 0, take);
      if (!readFully(src, bb, sp)) throw new IOException("unexpected EOF copying local APK");
      writeFully(dst, buf, 0, take, dp);
      sp += take;
      dp += take;
      rem -= take;
    }
  }

  // ---------------------------------------------------------------- Low-level helpers

  private static byte[] readExact(InputStream in, int len) throws IOException {
    byte[] out = new byte[len];
    int pos = 0;
    while (pos < len) {
      int n = in.read(out, pos, len - pos);
      if (n < 0) return null;
      pos += n;
    }
    return out;
  }

  private static boolean readFully(FileChannel ch, ByteBuffer dst, long pos) throws IOException {
    long p = pos;
    while (dst.hasRemaining()) {
      int n = ch.read(dst, p);
      if (n <= 0) return false;
      p += n;
    }
    return true;
  }

  private static void writeFully(FileChannel ch, byte[] buf, int off, int len, long pos) throws IOException {
    ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
    long p = pos;
    while (bb.hasRemaining()) {
      int n = ch.write(bb, p);
      if (n <= 0) throw new IOException("short write");
      p += n;
    }
  }

  private static String sha256Channel(FileChannel ch, long size) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    ByteBuffer bb = ByteBuffer.allocate(256 * 1024);
    long pos = 0;
    while (pos < size) {
      bb.clear();
      bb.limit((int) Math.min(bb.capacity(), size - pos));
      int n = ch.read(bb, pos);
      if (n <= 0) throw new IOException("unexpected EOF hashing staged APK");
      md.update(bb.array(), 0, n);
      pos += n;
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static boolean endsWithAscii(byte[] bytes, String suffix) {
    int n = suffix.length();
    if (bytes.length < n) return false;
    int off = bytes.length - n;
    for (int i = 0; i < n; i++) {
      if ((bytes[off + i] & 0xFF) != suffix.charAt(i)) return false;
    }
    return true;
  }

  private static int u16(byte[] b, int off) {
    return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
  }

  private static long u32(byte[] b, int off) {
    return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
        | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
  }

  private static long u64(byte[] b, int off) {
    return ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
  }
}
