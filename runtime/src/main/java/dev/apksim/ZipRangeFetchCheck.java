package dev.apksim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Verifies {@link ZipRangeFetch} (issue 674, scripts/check-range-reuse.sh, CI):
 * entry reuse across builds (same-offset CoW clone, shifted-offset, zipalign-shifted LFH padding, non-zero LFH extra,
 * zipflinger virtual-entry gaps, data descriptors, APK Signing Block), digest header parsing (X-Checksum-Sha256,
 * Repr-Digest, Digest, and digest on redirect hop), exact cache hit, no-digest / non-206 / mismatch fallbacks,
 * HEAD-403 presigned-URL suffix-range fallback, cross-origin credential stripping, beam allow-download & content-type
 * policy checks, and range coalescing. Exits 1 if any check fails.
 */
public final class ZipRangeFetchCheck {
  private static int failures;

  private ZipRangeFetchCheck() {}

  // Synthetic ZIP builder that produces real APK layouts: stored 4/4096/16384-aligned entries with zero or non-zero
  // LFH extra padding, deflated entries, optional zipflinger virtual-entry gaps, and optional APK Signing Block.
  record Spec(String name, byte[] data, boolean stored, int align, byte[] customExtra, int trailingGapBytes) {
    static Spec stored(String name, byte[] data, int align) {
      return new Spec(name, data, true, align, null, 0);
    }

    static Spec deflated(String name, byte[] data) {
      return new Spec(name, data, false, 0, null, 0);
    }
  }

  private static byte[] buildApk(List<Spec> specs, byte[] sigBlockPayload) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream cd = new ByteArrayOutputStream();
    for (Spec s : specs) {
      long localOff = out.size();
      byte[] nameBytes = s.name.getBytes(StandardCharsets.UTF_8);
      CRC32 crc32 = new CRC32();
      crc32.update(s.data);
      long crc = crc32.getValue();
      byte[] payload;
      int method;
      if (s.stored) {
        method = 0;
        payload = s.data;
      } else {
        method = 8;
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        def.setInput(s.data);
        def.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!def.finished()) {
          int n = def.deflate(tmp);
          compressed.write(tmp, 0, n);
        }
        def.end();
        payload = compressed.toByteArray();
      }
      byte[] extra;
      if (s.customExtra != null) {
        extra = s.customExtra;
      } else if (s.stored && s.align > 1) {
        long afterHdr = localOff + 30 + nameBytes.length;
        int pad = (int) ((s.align - (afterHdr % s.align)) % s.align);
        extra = new byte[pad];
      } else {
        extra = new byte[0];
      }
      int modTime = 0x0821;
      int modDate = 0x0221;
      // Local File Header
      ByteBuffer lfh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
      lfh.putInt(0x04034b50);
      lfh.putShort((short) 20);
      lfh.putShort((short) 0);
      lfh.putShort((short) method);
      lfh.putShort((short) modTime);
      lfh.putShort((short) modDate);
      lfh.putInt((int) crc);
      lfh.putInt(payload.length);
      lfh.putInt(s.data.length);
      lfh.putShort((short) nameBytes.length);
      lfh.putShort((short) extra.length);
      out.write(lfh.array());
      out.write(nameBytes);
      out.write(extra);
      out.write(payload);
      if (s.trailingGapBytes >= 30) {
        // Zipflinger virtual (free-list) entry: PK\x03\x04 with nameLen=0, extraLen=gap-30, compSize=0.
        ByteBuffer vf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        vf.putInt(0x04034b50);
        vf.putShort((short) 0);
        vf.putShort((short) 0);
        vf.putShort((short) 0);
        vf.putShort((short) modTime);
        vf.putShort((short) modDate);
        vf.putInt(0);
        vf.putInt(0);
        vf.putInt(0);
        vf.putShort((short) 0);
        vf.putShort((short) (s.trailingGapBytes - 30));
        out.write(vf.array());
        out.write(new byte[s.trailingGapBytes - 30]);
      }
      // Central Directory Header (cdExtra is empty, matching zipalign/zipflinger)
      ByteBuffer cdh = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
      cdh.putInt(0x02014b50);
      cdh.putShort((short) 20);
      cdh.putShort((short) 20);
      cdh.putShort((short) 0);
      cdh.putShort((short) method);
      cdh.putShort((short) modTime);
      cdh.putShort((short) modDate);
      cdh.putInt((int) crc);
      cdh.putInt(payload.length);
      cdh.putInt(s.data.length);
      cdh.putShort((short) nameBytes.length);
      cdh.putShort((short) 0);
      cdh.putShort((short) 0);
      cdh.putShort((short) 0);
      cdh.putShort((short) 0);
      cdh.putInt(0);
      cdh.putInt((int) localOff);
      cd.write(cdh.array());
      cd.write(nameBytes);
    }
    if (sigBlockPayload != null) {
      // Android APK Signing Block: [u64 blockSize][payload][u64 blockSize]["APK Sig Block 42"]
      long blockSize = sigBlockPayload.length + 8L + 16L;
      ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      b.putLong(blockSize);
      out.write(b.array());
      out.write(sigBlockPayload);
      out.write(b.array());
      out.write("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));
    }
    int cdOff = out.size();
    byte[] cdBytes = cd.toByteArray();
    out.write(cdBytes);
    ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
    eocd.putInt(0x06054b50);
    eocd.putShort((short) 0);
    eocd.putShort((short) 0);
    eocd.putShort((short) specs.size());
    eocd.putShort((short) specs.size());
    eocd.putInt(cdBytes.length);
    eocd.putInt(cdOff);
    eocd.putShort((short) 0);
    out.write(eocd.array());
    return out.toByteArray();
  }

  private static byte[] buildZipStreamApk(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        ZipEntry ze = new ZipEntry(e.getKey());
        ze.setTime(1_700_000_000_000L);
        zos.putNextEntry(ze);
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static byte[] randomBytes(long seed, int len) {
    byte[] b = new byte[len];
    new Random(seed).nextBytes(b);
    return b;
  }

  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  static final class MockServer implements AutoCloseable {
    final HttpServer server;
    final String baseUrl;
    final AtomicLong bodyBytesSent = new AtomicLong();
    final AtomicInteger headCount = new AtomicInteger();
    final AtomicInteger getCount = new AtomicInteger();
    final List<String> seenHeaders = new ArrayList<>();
    volatile byte[] body = new byte[0];
    volatile String contentType = "application/vnd.android.package-archive";
    volatile String digestHeaderName;
    volatile String digestHeaderValue;
    volatile boolean supportRange = true;
    volatile boolean acceptRangesNone = false;
    volatile int headStatusOverride = 0; // e.g. 403 or 405
    volatile String etag = "\"v1\"";

    MockServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
      baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void resetStats() {
      bodyBytesSent.set(0);
      headCount.set(0);
      getCount.set(0);
      synchronized (seenHeaders) {
        seenHeaders.clear();
      }
    }

    private void handle(HttpExchange ex) throws IOException {
      StringBuilder sb = new StringBuilder(ex.getRequestMethod()).append(' ').append(ex.getRequestURI().toString());
      ex.getRequestHeaders().forEach((k, v) -> sb.append(' ').append(k.toLowerCase()).append('=').append(String.join(",", v)));
      synchronized (seenHeaders) {
        seenHeaders.add(sb.toString());
      }
      byte[] data = body;
      if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
      if (etag != null) ex.getResponseHeaders().add("ETag", etag);
      if (digestHeaderName != null && digestHeaderValue != null) {
        ex.getResponseHeaders().add(digestHeaderName, digestHeaderValue);
      }
      if (acceptRangesNone) ex.getResponseHeaders().add("Accept-Ranges", "none");
      else if (supportRange) ex.getResponseHeaders().add("Accept-Ranges", "bytes");

      if ("HEAD".equals(ex.getRequestMethod())) {
        headCount.incrementAndGet();
        if (headStatusOverride != 0) {
          ex.sendResponseHeaders(headStatusOverride, -1);
          ex.close();
          return;
        }
        ex.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
        ex.sendResponseHeaders(200, -1);
        ex.close();
        return;
      }

      getCount.incrementAndGet();
      String range = ex.getRequestHeaders().getFirst("Range");
      if (supportRange && !acceptRangesNone && range != null && range.startsWith("bytes=")) {
        String spec = range.substring(6).trim();
        int dash = spec.indexOf('-');
        String a = spec.substring(0, dash);
        String b = spec.substring(dash + 1);
        int start, end;
        if (a.isEmpty()) {
          int suffix = Integer.parseInt(b);
          start = Math.max(0, data.length - suffix);
          end = data.length - 1;
        } else {
          start = Integer.parseInt(a);
          end = b.isEmpty() ? data.length - 1 : Math.min(data.length - 1, Integer.parseInt(b));
        }
        int len = end - start + 1;
        ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
        ex.sendResponseHeaders(206, len);
        ex.getResponseBody().write(data, start, len);
        bodyBytesSent.addAndGet(len);
        ex.close();
        return;
      }

      ex.sendResponseHeaders(200, data.length);
      ex.getResponseBody().write(data);
      bodyBytesSent.addAndGet(data.length);
      ex.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  public static void main(String[] args) throws Exception {
    testDigestHeaderParsing();
    Path dir = Files.createTempDirectory("apksim-range-reuse-check");
    try (MockServer srv = new MockServer()) {
      // Common payloads shared between build 1 and build 2 (~1 MB total, mostly unchanged assets/libs/res).
      byte[] sharedPrefix = randomBytes(1, 200_000);       // stays at EXACT same offset (tests CoW clone!)
      byte[] sharedSo1 = randomBytes(2, 250_000);          // 4 KB aligned .so (shifted after DEX change!)
      byte[] sharedSo2 = randomBytes(3, 180_000);          // 16 KB aligned .so with non-zero custom extra!
      byte[] sharedRes = randomBytes(4, 150_000);          // 4-byte aligned stored resource
      byte[] sharedDeflated = ("hello compose world ".repeat(4000)).getBytes(StandardCharsets.UTF_8);
      byte[] dexV1 = randomBytes(10, 60_000);
      byte[] dexV2 = randomBytes(11, 61_337);              // +1337 bytes -> shifts everything after DEX by non-aligned amount!
      byte[] sigV1 = randomBytes(20, 4096);
      byte[] sigV2 = randomBytes(21, 4096);
      byte[] customExtraV1 = new byte[] {0x35, (byte) 0xd9, 0x04, 0x00, 0x00, 0x10, 0x00, 0x00};
      // Build 2 changes customExtra length so non-zero extra triggers PAYLOAD_ONLY (fetch LFH, copy 180 KB payload!)
      byte[] customExtraV2 = new byte[4096];
      Arrays.fill(customExtraV2, 0, 4, (byte) 0x55);

      byte[] apkV1 = buildApk(List.of(
          Spec.stored("res/raw/prefix.bin", sharedPrefix, 4),
          Spec.deflated("classes.dex", dexV1),
          Spec.stored("lib/arm64-v8a/libfoo.so", sharedSo1, 4096),
          new Spec("lib/arm64-v8a/libcustom.so", sharedSo2, true, 0, customExtraV1, 0),
          Spec.stored("resources.arsc", sharedRes, 4),
          new Spec("assets/doc.txt", sharedDeflated, false, 0, null, 0)
      ), sigV1);

      // Compute exact non-zero custom extra length for libcustom.so in V2 so its payload is 4096-aligned:
      byte[] apkV2Pre = buildApk(List.of(
          Spec.stored("res/raw/prefix.bin", sharedPrefix, 4),
          Spec.deflated("classes.dex", dexV2),
          Spec.stored("lib/arm64-v8a/libfoo.so", sharedSo1, 4096)
      ), null);
      // Use standard 4096-aligned padding with non-zero first 4 bytes to simulate bundletool 0xd935 extra:
      long customLfhStart = findEndOfEntries(apkV2Pre);
      byte[] customName = "lib/arm64-v8a/libcustom.so".getBytes(StandardCharsets.UTF_8);
      int customPad = (int) ((4096 - ((customLfhStart + 30 + customName.length) % 4096)) % 4096);
      if (customPad < 8) customPad += 4096;
      byte[] customExtraV2Aligned = new byte[customPad];
      customExtraV2Aligned[0] = 0x35; customExtraV2Aligned[1] = (byte) 0xd9;

      byte[] apkV2 = buildApk(List.of(
          Spec.stored("res/raw/prefix.bin", sharedPrefix, 4),
          Spec.deflated("classes.dex", dexV2),
          Spec.stored("lib/arm64-v8a/libfoo.so", sharedSo1, 4096),
          new Spec("lib/arm64-v8a/libcustom.so", sharedSo2, true, 0, customExtraV2Aligned, 0),
          Spec.stored("resources.arsc", sharedRes, 4),
          new Spec("assets/doc.txt", sharedDeflated, false, 0, null, 512) // zipflinger virtual-entry gap!
      ), sigV2);

      String shaV1 = sha256(apkV1);
      String shaV2 = sha256(apkV2);
      Path uploads = dir.resolve("uploads");
      Files.createDirectories(uploads);
      Files.write(uploads.resolve(shaV1 + ".apk"), apkV1);

      // 1. Reuse with caller-supplied sha256 (beam / serve with sha256)
      srv.body = apkV2;
      srv.digestHeaderName = null;
      srv.digestHeaderValue = null;
      srv.resetStats();
      ZipRangeFetch.Result r1 = ZipRangeFetch.fetch(srv.baseUrl + "/app-v2.apk",
          ZipRangeFetch.Options.forServe(Map.of(), shaV2, uploads, 64 << 20, null, null));
      check(r1 != null, "caller sha256: ranged fetch succeeded");
      if (r1 != null) {
        byte[] got = Files.readAllBytes(r1.path());
        check(Arrays.equals(got, apkV2), "caller sha256: rebuilt APK is byte-for-byte identical");
        check(shaV2.equals(r1.sha256()), "caller sha256: SHA-256 matches");
        check(srv.bodyBytesSent.get() < apkV2.length / 4,
            "caller sha256: fetched <25% of APK (" + srv.bodyBytesSent.get() + " / " + apkV2.length + " B)");
        Files.deleteIfExists(r1.path());
      }

      // 2. Reuse via server digest headers (X-Checksum-Sha256, Repr-Digest, Digest) with NO caller sha256
      String b64V2 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(shaV2));
      for (String[] hdr : new String[][] {
          {"X-Checksum-Sha256", shaV2.toUpperCase()},
          {"Repr-Digest", "sha-512=:AAAA:, sha-256=:" + b64V2 + ":"},
          {"Digest", "md5=AAAA, SHA-256=" + b64V2}
      }) {
        srv.digestHeaderName = hdr[0];
        srv.digestHeaderValue = hdr[1];
        srv.resetStats();
        ZipRangeFetch.Result r = ZipRangeFetch.fetch(srv.baseUrl + "/app-v2.apk",
            ZipRangeFetch.Options.forServe(Map.of(), null, uploads, 64 << 20, null, null));
        check(r != null && shaV2.equals(r.sha256()) && srv.bodyBytesSent.get() < apkV2.length / 4,
            "digest header " + hdr[0] + ": reused entries (" + srv.bodyBytesSent.get() + " / " + apkV2.length + " B)");
        if (r != null) Files.deleteIfExists(r.path());
      }

      // 3. Exact cache hit when HEAD X-Checksum-Sha256 matches already-stored V1 (0 body bytes!)
      srv.body = apkV1;
      srv.digestHeaderName = "X-Checksum-Sha256";
      srv.digestHeaderValue = shaV1;
      srv.resetStats();
      ZipRangeFetch.Result hit = ZipRangeFetch.fetch(srv.baseUrl + "/app-v1.apk",
          ZipRangeFetch.Options.forServe(Map.of(), null, uploads, 64 << 20, null, null));
      check(hit != null && hit.reusedWholeFile() && srv.bodyBytesSent.get() == 0 && srv.getCount.get() == 0,
          "exact cache hit from HEAD digest: 0 GETs, 0 bytes fetched");

      // 4. No caller sha256 and no server digest header -> returns null (no reuse)
      srv.body = apkV2;
      srv.digestHeaderName = null;
      srv.digestHeaderValue = null;
      srv.resetStats();
      ZipRangeFetch.Result noHash = ZipRangeFetch.fetch(srv.baseUrl + "/app-v2.apk",
          ZipRangeFetch.Options.forServe(Map.of(), null, uploads, 64 << 20, null, null));
      check(noHash == null && srv.getCount.get() == 0, "no expected hash: returns null after HEAD (no range GETs)");

      // 5. Server does not support Range (Accept-Ranges: none or 200 OK) -> returns null cleanly
      srv.digestHeaderName = "X-Checksum-Sha256";
      srv.digestHeaderValue = shaV2;
      srv.acceptRangesNone = true;
      srv.resetStats();
      check(ZipRangeFetch.fetch(srv.baseUrl + "/app-v2.apk",
          ZipRangeFetch.Options.forServe(Map.of(), shaV2, uploads, 64 << 20, null, null)) == null && srv.getCount.get() == 0,
          "Accept-Ranges: none -> returns null without GET");
      srv.acceptRangesNone = false;
      srv.supportRange = false;
      srv.resetStats();
      check(ZipRangeFetch.fetch(srv.baseUrl + "/app-v2.apk",
          ZipRangeFetch.Options.forServe(Map.of(), shaV2, uploads, 64 << 20, null, null)) == null,
          "server ignores Range (200 OK) -> returns null cleanly");
      srv.supportRange = true;

      // 6. HEAD returns 403 (S3/GCS presigned GET URL) with caller sha256 -> suffix-range GET works!
      srv.headStatusOverride = 403;
      srv.digestHeaderName = null;
      srv.resetStats();
      ZipRangeFetch.Result presigned = ZipRangeFetch.fetch(srv.baseUrl + "/presigned.apk",
          ZipRangeFetch.Options.forBeam(null, true, shaV2, uploads, 64 << 20));
      check(presigned != null && shaV2.equals(presigned.sha256()) && srv.bodyBytesSent.get() < apkV2.length / 3,
          "HEAD 403 presigned URL: falls back to suffix-range GET and reuses (" + srv.bodyBytesSent.get() + " B)");
      if (presigned != null) Files.deleteIfExists(presigned.path());
      srv.headStatusOverride = 0;

      // 7. Wrong expected SHA-256 -> returns null and cleans up .part file
      srv.resetStats();
      ZipRangeFetch.Result badSha = ZipRangeFetch.fetch(srv.baseUrl + "/app-v2.apk",
          ZipRangeFetch.Options.forServe(Map.of(), "0".repeat(64), uploads, 64 << 20, null, null));
      check(badSha == null, "SHA-256 mismatch -> returns null (fallback)");
      long leftoverParts;
      try (var s = Files.list(uploads)) {
        leftoverParts = s.filter(p -> p.getFileName().toString().endsWith(".part")).count();
      }
      check(leftoverParts == 0, "SHA-256 mismatch -> leaves no .part files behind");

      // 8. Data Descriptors (ZipOutputStream deflated entries with flags & 8)
      Map<String, byte[]> zs1 = new LinkedHashMap<>();
      zs1.put("res/a.bin", randomBytes(30, 120_000));
      zs1.put("res/b.bin", randomBytes(31, 120_000));
      zs1.put("classes.dex", randomBytes(32, 30_000));
      Map<String, byte[]> zs2 = new LinkedHashMap<>();
      zs2.put("res/a.bin", zs1.get("res/a.bin"));
      zs2.put("res/b.bin", zs1.get("res/b.bin"));
      zs2.put("classes.dex", randomBytes(33, 31_000));
      byte[] zApk1 = buildZipStreamApk(zs1);
      byte[] zApk2 = buildZipStreamApk(zs2);
      Path ddUploads = dir.resolve("dd-uploads");
      Files.createDirectories(ddUploads);
      Files.write(ddUploads.resolve(sha256(zApk1) + ".apk"), zApk1);
      srv.body = zApk2;
      srv.digestHeaderName = "X-Checksum-Sha256";
      srv.digestHeaderValue = sha256(zApk2);
      srv.resetStats();
      ZipRangeFetch.Result ddRes = ZipRangeFetch.fetch(srv.baseUrl + "/dd.apk",
          ZipRangeFetch.Options.forServe(Map.of(), null, ddUploads, 64 << 20, null, null));
      check(ddRes != null && Arrays.equals(Files.readAllBytes(ddRes.path()), zApk2)
          && srv.bodyBytesSent.get() < zApk2.length / 3,
          "ZipOutputStream data descriptors (flags & 8): reused cleanly (" + srv.bodyBytesSent.get() + " / " + zApk2.length + " B)");
      if (ddRes != null) Files.deleteIfExists(ddRes.path());

      // 9. Coalescing: 80 small changed entries separated by large untouched regions -> coalesced into <= 64 range GETs
      //    while still reusing most of the APK!
      List<Spec> many1 = new ArrayList<>();
      List<Spec> many2 = new ArrayList<>();
      for (int i = 0; i < 80; i++) {
        byte[] keep = randomBytes(1000 + i, i % 10 == 0 ? 100_000 : 8_000);
        many1.add(Spec.stored("res/keep_" + i + ".bin", keep, 4));
        many2.add(Spec.stored("res/keep_" + i + ".bin", keep, 4));
        many1.add(Spec.deflated("res/chg_" + i + ".txt", ("v1-" + i).getBytes(StandardCharsets.UTF_8)));
        many2.add(Spec.deflated("res/chg_" + i + ".txt", ("v2-" + i + "-changed").getBytes(StandardCharsets.UTF_8)));
      }
      byte[] mApk1 = buildApk(many1, sigV1);
      byte[] mApk2 = buildApk(many2, sigV2);
      Path manyUploads = dir.resolve("many-uploads");
      Files.createDirectories(manyUploads);
      Files.write(manyUploads.resolve(sha256(mApk1) + ".apk"), mApk1);
      srv.body = mApk2;
      srv.digestHeaderName = "X-Checksum-Sha256";
      srv.digestHeaderValue = sha256(mApk2);
      srv.resetStats();
      ZipRangeFetch.Result mRes = ZipRangeFetch.fetch(srv.baseUrl + "/many.apk",
          ZipRangeFetch.Options.forServe(Map.of(), null, manyUploads, 64 << 20, null, null));
      check(mRes != null && Arrays.equals(Files.readAllBytes(mRes.path()), mApk2)
          && srv.getCount.get() <= 20 && srv.bodyBytesSent.get() < mApk2.length * 6 / 10,
          "coalescing 80 scattered changes across 1.4 MB: gets=" + srv.getCount.get()
              + ", fetched=" + srv.bodyBytesSent.get() + " / " + mApk2.length + " B");
      if (mRes != null) Files.deleteIfExists(mRes.path());

      // 10. Cross-origin redirect credential isolation + digest on 302 hop
      testCrossOriginRedirectAndPolicies(srv, apkV2, shaV2, uploads);

      // 11. Beam policy checks: non-APK content type and maxBytes throw PolicyException
      srv.body = apkV2;
      srv.contentType = "text/html";
      srv.resetStats();
      boolean threwType = false;
      try {
        ZipRangeFetch.fetch(srv.baseUrl + "/login",
            ZipRangeFetch.Options.forBeam(null, true, shaV2, uploads, 64 << 20));
      } catch (ZipRangeFetch.PolicyException e) {
        threwType = e.getMessage().contains("not an APK");
      }
      check(threwType, "beam content-type policy: text/html throws PolicyException");
      srv.contentType = "application/vnd.android.package-archive";

      boolean threwSize = false;
      try {
        ZipRangeFetch.fetch(srv.baseUrl + "/big.apk",
            ZipRangeFetch.Options.forBeam(null, true, shaV2, uploads, 1024));
      } catch (ZipRangeFetch.PolicyException e) {
        threwSize = e.getMessage().contains("too large");
      }
      check(threwSize, "maxBytes policy: oversized Content-Length throws PolicyException");
    } finally {
      deleteTree(dir);
    }

    System.out.println(failures == 0 ? "range-reuse: all passed" : "range-reuse: " + failures + " failed");
    System.exit(failures == 0 ? 0 : 1);
  }

  private static void testCrossOriginRedirectAndPolicies(MockServer target, byte[] apkV2, String shaV2, Path uploads)
      throws Exception {
    // Origin A requires X-JFrog-Art-Api, sends X-Checksum-Sha256 on the 302 redirect, redirects to Origin B (target)
    // which has NO digest header and must NEVER see X-JFrog-Art-Api!
    target.body = apkV2;
    target.digestHeaderName = null;
    target.digestHeaderValue = null;
    target.contentType = "application/octet-stream";
    target.resetStats();

    HttpServer originA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    originA.createContext("/", ex -> {
      String path = ex.getRequestURI().toString();
      if ("/qredirect?sig=old".equals(path)) {
        ex.getResponseHeaders().add("Location", "?sig=new");
        ex.sendResponseHeaders(302, -1);
        ex.close();
        return;
      }
      if ("/qredirect?sig=new".equals(path)) {
        ex.getResponseHeaders().add("Location", target.baseUrl + "/storage/v2.apk");
        ex.sendResponseHeaders(302, -1);
        ex.close();
        return;
      }
      String key = ex.getRequestHeaders().getFirst("X-JFrog-Art-Api");
      if (!"secret-key-99".equals(key)) {
        ex.sendResponseHeaders(401, -1);
        ex.close();
        return;
      }
      ex.getResponseHeaders().add("X-Checksum-Sha256", shaV2);
      ex.getResponseHeaders().add("Location", target.baseUrl + "/storage/v2.apk?sig=presigned");
      ex.sendResponseHeaders(302, -1);
      ex.close();
    });
    originA.start();
    String aUrl = "http://127.0.0.1:" + originA.getAddress().getPort();
    try {
      ZipRangeFetch.Result r = ZipRangeFetch.fetch(aUrl + "/builds/v2.apk",
          ZipRangeFetch.Options.forServe(Map.of("X-JFrog-Art-Api", "secret-key-99"), null, uploads, 64 << 20, null, null));
      check(r != null && shaV2.equals(r.sha256()) && target.bodyBytesSent.get() < apkV2.length / 4,
          "cross-origin redirect: digest on 302 hop enables reuse (" + target.bodyBytesSent.get() + " B)");
      if (r != null) Files.deleteIfExists(r.path());
      boolean leaked;
      synchronized (target.seenHeaders) {
        leaked = target.seenHeaders.stream().anyMatch(l -> l.contains("secret-key-99") || l.contains("x-jfrog-art-api"));
      }
      check(!leaked, "cross-origin redirect: redirect target NEVER saw X-JFrog-Art-Api on HEAD or Range GETs");

      // Beam hopAllow policy: disallow redirect to target
      boolean refused = false;
      try {
        ZipRangeFetch.fetch(aUrl + "/qredirect?sig=old",
            ZipRangeFetch.Options.forBeam(addr -> addr.startsWith(aUrl) ? URI.create(addr) : null,
                true, shaV2, uploads, 64 << 20));
      } catch (ZipRangeFetch.PolicyException e) {
        refused = e.getMessage().contains("redirected");
      }
      check(refused, "beam hopAllow: query-only redirect followed, out-of-prefix redirect refused with PolicyException");

      // Beam hopAllow allowing both origins: query-only redirect preserves path and succeeds with reuse!
      target.resetStats();
      ZipRangeFetch.Result qr = ZipRangeFetch.fetch(aUrl + "/qredirect?sig=old",
          ZipRangeFetch.Options.forBeam(URI::create, true, shaV2, uploads, 64 << 20));
      check(qr != null && shaV2.equals(qr.sha256()) && target.bodyBytesSent.get() < apkV2.length / 4,
          "beam query-only redirect (?sig=new) preserves path and reuses entries");
      if (qr != null) Files.deleteIfExists(qr.path());
    } finally {
      originA.stop(0);
    }
  }

  private static void testDigestHeaderParsing() {
    byte[] raw32 = new byte[32];
    for (int i = 0; i < 32; i++) raw32[i] = (byte) (i * 7 + 3);
    String hex = HexFormat.of().formatHex(raw32);
    String b64 = Base64.getEncoder().encodeToString(raw32);

    check(hex.equals(ZipRangeFetch.parseSha256Digest(HttpHeaders.of(
        Map.of("X-Checksum-Sha256", List.of(" " + hex.toUpperCase() + " ")), (a, b) -> true))),
        "parseSha256Digest: X-Checksum-Sha256");
    check(hex.equals(ZipRangeFetch.parseSha256Digest(HttpHeaders.of(
        Map.of("Repr-Digest", List.of("sha-512=:AAAA:, sha-256=:" + b64 + ":")), (a, b) -> true))),
        "parseSha256Digest: Repr-Digest multi-member");
    check(hex.equals(ZipRangeFetch.parseSha256Digest(HttpHeaders.of(
        Map.of("Digest", List.of("md5=1234, SHA-256=" + b64)), (a, b) -> true))),
        "parseSha256Digest: Digest RFC 3230");
    check(ZipRangeFetch.parseSha256Digest(HttpHeaders.of(
        Map.of("X-Checksum-Sha256", List.of("not-hex"), "Repr-Digest", List.of("sha-256=:AAAA:")), (a, b) -> true)) == null,
        "parseSha256Digest: malformed values return null");
  }

  private static long findEndOfEntries(byte[] zip) {
    for (int i = zip.length - 22; i >= 0; i--) {
      if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 0x05 && zip[i + 3] == 0x06) {
        return ByteBuffer.wrap(zip, i + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
      }
    }
    return 0;
  }

  private static void deleteTree(Path root) {
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {}
      });
    } catch (IOException ignored) {}
  }

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }
}
