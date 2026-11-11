package dev.apksim.serve;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * {@code apksim rec view FILE}: serves trace-viewer.html and one recording on a loopback port and opens the page in
 * the default browser (docs/trace-viewer.md). The page is a static file that reads a zip in the browser; this only
 * hands it the file without a file picker. Plain JVM, apksim.jar alone. Exits after the page has fetched the file
 * and been quiet for {@code --linger} seconds (default 120), or at once on Ctrl-C.
 */
public final class TraceView {
  private TraceView() {}

  public static void main(String[] args) throws Exception {
    Path file = null;
    boolean open = true;
    int linger = 120, port = 0;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--no-open" -> open = false;
        case "--linger" -> linger = Integer.parseInt(args[++i]);
        case "--port" -> port = Integer.parseInt(args[++i]);
        case "-h", "--help" -> { usage(); return; }
        default -> {
          if (args[i].startsWith("-") || file != null) { usage(); System.exit(2); }
          file = Path.of(args[i]);
        }
      }
    }
    byte[] page;
    try (InputStream in = TraceView.class.getResourceAsStream("trace-viewer.html")) {
      if (in == null) throw new IOException("trace-viewer.html is missing from apksim.jar");
      page = in.readAllBytes();
    }
    if (file != null && !Files.isRegularFile(file)) {
      System.err.println("apksim rec view: no such file: " + file);
      System.exit(2);
    }
    byte[] rnd = new byte[12];
    new SecureRandom().nextBytes(rnd);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
    try (ServerSocket ss = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
      int p = ss.getLocalPort();
      String url = "http://127.0.0.1:" + p + "/" + (file == null ? "" : "?src=/rec/" + token + "&name="
          + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20"));
      System.out.println(url);
      System.out.flush();
      if (open) openBrowser(url);
      long[] last = { System.currentTimeMillis() };
      java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
      ss.setSoTimeout(1000);
      final Path f = file;
      while (true) {
        long idle = System.currentTimeMillis() - last[0];
        if (idle > (delivered.get() ? linger : 600) * 1000L) break;
        Socket s;
        try { s = ss.accept(); } catch (SocketTimeoutException e) { continue; }
        last[0] = System.currentTimeMillis();
        Thread t = new Thread(() -> {
          try (s) {
            if (handle(s, p, page, f, token)) delivered.set(true);
          } catch (IOException ignored) {
          }
        }, "apksim-traceview");
        t.setDaemon(true);
        t.start();
      }
    }
  }

  private static void usage() {
    System.err.println("usage: apksim rec view [--no-open] [--linger SECONDS] [FILE.apkrec|bundle.zip]\n"
        + "  serves the trace viewer on a loopback port and opens it; FILE is passed to the page (without it, drop one in)");
  }

  private static void openBrowser(String url) {
    String os = System.getProperty("os.name", "").toLowerCase();
    try {
      new ProcessBuilder(os.contains("mac") ? "open" : "xdg-open", url).inheritIO().start();
    } catch (IOException e) {
      System.err.println("apksim rec view: could not start a browser; open " + url);
    }
  }

  /** Answers one request; true when it delivered the recording. */
  private static boolean handle(Socket s, int port, byte[] page, Path file, String token) throws IOException {
    s.setSoTimeout(10000);
    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
    String req = r.readLine();
    if (req == null) return false;
    String host = null, line;
    while ((line = r.readLine()) != null && !line.isEmpty()) {
      if (line.regionMatches(true, 0, "Host:", 0, 5)) host = line.substring(5).trim();
    }
    OutputStream out = s.getOutputStream();
    String[] parts = req.split(" ");
    // DNS rebinding: only our own address is a Host we answer to
    if (parts.length < 2 || host == null || !(host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port))) {
      send(out, 403, "text/plain", "forbidden\n".getBytes(StandardCharsets.UTF_8));
      return false;
    }
    String path = parts[1];
    int q = path.indexOf('?');
    if (q >= 0) path = path.substring(0, q);
    if (!parts[0].equals("GET")) {
      send(out, 405, "text/plain", "GET only\n".getBytes(StandardCharsets.UTF_8));
    } else if (path.equals("/")) {
      send(out, 200, "text/html; charset=utf-8", page);
    } else if (file != null && path.equals("/rec/" + token)) {
      long size = Files.size(file);
      out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + size
          + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
      try (InputStream in = Files.newInputStream(file)) { in.transferTo(out); }
      out.flush();
      return true;
    } else {
      send(out, 404, "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8));
    }
    out.flush();
    return false;
  }

  private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
    out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
        + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n")
        .getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }
}
