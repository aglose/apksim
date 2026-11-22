package dev.apksim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that URL launches' credentials (`--header`, `--netrc`) reach the first origin only (scripts/check-download-auth.sh,
 * CI): two loopback servers on different ports (two origins), the first requiring the credential and redirecting the
 * download to the second, which must never see it. Every case runs on curl's path and on the pure-Java one
 * (-Dapksim.noCurl=true). Exits 1 if any check fails.
 */
public final class DownloadAuthCheck {
  private static final byte[] BODY = body();
  private static int failures;

  private DownloadAuthCheck() {}

  private static byte[] body() {
    byte[] b = new byte[64 * 1024];
    b[0] = 'P'; b[1] = 'K'; b[2] = 3; b[3] = 4;
    return b;
  }

  /** What one server saw: each request's path and its headers, names lower-cased. */
  static final class Seen {
    final List<String> lines = new ArrayList<>();

    synchronized void add(HttpExchange ex) {
      StringBuilder sb = new StringBuilder(ex.getRequestURI().getPath());
      ex.getRequestHeaders().forEach((k, v) -> sb.append(' ').append(k.toLowerCase()).append('=').append(String.join(",", v)));
      lines.add(sb.toString());
    }

    synchronized boolean any(String needle) {
      return lines.stream().anyMatch(l -> l.contains(needle));
    }

    synchronized void clear() {
      lines.clear();
    }
  }

  public static void main(String[] args) throws Exception {
    Seen seenA = new Seen(), seenB = new Seen();
    HttpServer b = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    b.createContext("/", ex -> {
      seenB.add(ex);
      serve(ex);
    });
    b.start();
    String bUrl = "http://127.0.0.1:" + b.getAddress().getPort();
    HttpServer a = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String basic = "Basic " + Base64.getEncoder().encodeToString("user1:secret1".getBytes(StandardCharsets.UTF_8));
    a.createContext("/", ex -> {
      seenA.add(ex);
      String path = ex.getRequestURI().getPath();
      String key = ex.getRequestHeaders().getFirst("X-JFrog-Art-Api");
      String custom = ex.getRequestHeaders().getFirst("X-Custom-Token");
      String auth = ex.getRequestHeaders().getFirst("Authorization");
      boolean authorized = "key-123".equals(key) || "tok-456".equals(custom) || basic.equals(auth);
      if (!authorized) {
        ex.sendResponseHeaders(401, -1);
        ex.close();
        return;
      }
      // Anything else is sent to cloud storage (another origin), as an Artifactory set to redirect downloads does.
      String to = switch (path) {
        case "/same.apk" -> "/app-final.apk";  // same origin: credentials kept
        case "/app-final.apk" -> null;
        default -> bUrl + "/storage/app.apk?sig=presigned";
      };
      if (to == null) {
        serve(ex);
        return;
      }
      ex.getResponseHeaders().add("Location", to);
      ex.sendResponseHeaders(302, -1);
      ex.close();
    });
    a.start();
    String aUrl = "http://127.0.0.1:" + a.getAddress().getPort();
    Path dir = Files.createTempDirectory("apksim-download-auth");
    Path netrc = dir.resolve("netrc");
    Files.writeString(netrc, "machine 127.0.0.1 login user1 password secret1\ndefault login user2 password secret2\n");
    try {
      for (boolean noCurl : new boolean[] {false, true}) {
        if (noCurl) System.setProperty("apksim.noCurl", "true");
        else System.clearProperty("apksim.noCurl");
        String path = noCurl ? "java" : "curl";
        // An Artifactory key, a header no list knows, and netrc credentials: each to the first origin only.
        for (Map.Entry<String, String> h : Map.of("X-JFrog-Art-Api", "key-123", "X-Custom-Token", "tok-456").entrySet()) {
          seenA.clear();
          seenB.clear();
          Map<String, String> headers = new LinkedHashMap<>();
          headers.put(h.getKey(), h.getValue());
          String what = path + ": " + h.getKey() + " across origins";
          if (download(aUrl + "/app.apk", dir, headers, false, what)) {
            check(seenA.any(h.getKey().toLowerCase() + "=" + h.getValue()), what + ": the first origin got it");
            check(!seenB.any(h.getValue()), what + ": the redirect target never sees it", seenB.lines.toString());
          }
        }
        System.setProperty("apksim.netrcFile", netrc.toString());
        seenA.clear();
        seenB.clear();
        String what = path + ": --netrc across origins";
        if (download(aUrl + "/app.apk", dir, Map.of(), true, what)) {
          check(!seenB.any("authorization="), what + ": the redirect target gets no Authorization (not even netrc's default)",
              seenB.lines.toString());
        }
        System.clearProperty("apksim.netrcFile");
        seenA.clear();
        what = path + ": a same-origin redirect";
        if (download(aUrl + "/same.apk", dir, Map.of("X-JFrog-Art-Api", "key-123"), false, what)) {
          check(seenA.lines.stream().anyMatch(l -> l.startsWith("/app-final.apk") && l.contains("x-jfrog-art-api=key-123")),
              what + " keeps the key", seenA.lines.toString());
        }
        seenA.clear();
        what = path + ": a header value with a quote and a backslash";
        Map<String, String> odd = new LinkedHashMap<>();
        odd.put("X-JFrog-Art-Api", "key-123");
        odd.put("X-Odd", "a\"b\\c = d");
        if (download(aUrl + "/same.apk", dir, odd, false, what)) {
          check(seenA.any("x-odd=a\"b\\c = d"), what + " arrives as given", seenA.lines.toString());
        }
        what = path + ": no credential";
        try {
          ApkDownloader.download(aUrl + "/app.apk", dir.resolve("x.apk"), Map.of(), false, 1 << 20, false, null, null);
          check(false, what + ": a 401 fails the download");
        } catch (IOException e) {
          check(e.getMessage().contains("401"), what + ": a 401 fails the download", e.getMessage());
        }
      }
    } finally {
      a.stop(0);
      b.stop(0);
    }
    System.out.println(failures == 0 ? "download-auth: all passed" : "download-auth: " + failures + " failed");
    System.exit(failures == 0 ? 0 : 1);
  }

  private static boolean download(String url, Path dir, Map<String, String> headers, boolean netrc, String what) {
    try {
      Path dest = dir.resolve("got.apk");
      Files.deleteIfExists(dest);
      ApkDownloader.Result r = ApkDownloader.download(url, dest, headers, netrc, 1 << 20, false, null, null);
      check(r.bytes() == BODY.length && Files.size(dest) == BODY.length, what + ": downloads the file", r.bytes() + " bytes");
      return true;
    } catch (IOException e) {
      check(false, what + ": downloads the file", e.toString());
      return false;
    }
  }

  private static void serve(HttpExchange ex) throws IOException {
    String range = ex.getRequestHeaders().getFirst("Range");
    if (range != null && range.startsWith("bytes=")) {
      String[] p = range.substring(6).split("-", 2);
      int start = Integer.parseInt(p[0]);
      int end = p.length > 1 && !p[1].isEmpty() ? Math.min(Integer.parseInt(p[1]), BODY.length - 1) : BODY.length - 1;
      ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + BODY.length);
      ex.sendResponseHeaders(206, end - start + 1);
      ex.getResponseBody().write(BODY, start, end - start + 1);
    } else {
      ex.sendResponseHeaders(200, BODY.length);
      ex.getResponseBody().write(BODY);
    }
    ex.close();
  }

  private static void check(boolean ok, String what) {
    check(ok, what, "");
  }

  private static void check(boolean ok, String what, String detail) {
    System.out.println((ok ? "ok   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : ": " + detail));
    if (!ok) failures++;
  }
}
