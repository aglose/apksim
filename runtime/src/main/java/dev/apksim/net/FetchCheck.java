package dev.apksim.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Checks `--network replay IN --network-unmatched fetch:OUT` (docs/network.md "Fetching what a recording lacks") the way
 * translated app code meets it: sockets connected and layered with TLS through Net's hooks, against servers on this machine.
 * A request IN has is answered from IN and never reaches the server; one IN lacks is fetched from the server (plain and
 * TLS, a POST body, a gzip answer), answered and appended to OUT with recordings' redaction; a repeat, on the same
 * connection, a new one or two at once, reaches the server once; a server that is down and a connection that is not HTTP
 * give the 599 and are listed as unmatched; IN is not changed; OUT holds the fetched exchanges alone and is written ~300 ms
 * after an exchange. `no-replay` checks that the policy without --network replay stops the launch.
 * Run by scripts/check-net-fetch.sh; exits 1 on any failure.
 */
public final class FetchCheck {
  private static int failures;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  /** Requests the server got, by path. */
  private static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

  private static int hits(String path) {
    AtomicInteger n = hits.get(path);
    return n == null ? 0 : n.get();
  }

  /** A small HTTP/1.1 server: each connection one request (the engine fetches with Connection: close). */
  private static int serve(ServerSocket ss) {
    Thread t = new Thread(() -> {
      while (true) {
        try {
          Socket c = ss.accept();
          Thread.ofVirtual().start(() -> {
            try (c) {
              InputStream in = c.getInputStream();
              String head = head(in);
              String[] line = head.split("\r\n")[0].split(" ");
              String path = line[1];
              hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
              int len = 0;
              boolean gzipOk = false;
              String auth = "";
              for (String h : head.split("\r\n")) {
                String l = h.toLowerCase();
                if (l.startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                if (l.startsWith("accept-encoding:") && l.contains("gzip")) gzipOk = true;
                if (l.startsWith("authorization:")) auth = h.substring(14).trim();
              }
              byte[] reqBody = in.readNBytes(len);
              byte[] body;
              String extra = "";
              switch (path) {
                case "/new" -> {
                  body = ("fresh " + hits("/new") + " auth=" + (auth.equals("Bearer tok-123") ? "sent" : "none")).getBytes(StandardCharsets.UTF_8);
                  extra = "Set-Cookie: session=secret-cookie\r\nX-Server: fixture\r\n";
                }
                case "/slow" -> {
                  Thread.sleep(400);
                  body = ("slow " + hits("/slow")).getBytes(StandardCharsets.UTF_8);
                }
                case "/echo" -> body = ("echo " + new String(reqBody, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                case "/gz" -> {
                  ByteArrayOutputStream b = new ByteArrayOutputStream();
                  try (GZIPOutputStream gz = new GZIPOutputStream(b)) {
                    gz.write("zipped text".getBytes(StandardCharsets.UTF_8));
                  }
                  body = gzipOk ? b.toByteArray() : "zipped text".getBytes(StandardCharsets.UTF_8);
                  if (gzipOk) extra = "Content-Encoding: gzip\r\n";
                }
                case "/chunked" -> {
                  OutputStream o = c.getOutputStream();
                  o.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
                      .getBytes(StandardCharsets.ISO_8859_1));
                  o.flush();
                  return;
                }
                default -> body = ("path " + path).getBytes(StandardCharsets.UTF_8);
              }
              int status = path.equals("/missing") ? 404 : 200;
              OutputStream o = c.getOutputStream();
              o.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/plain\r\n" + extra
                  + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
              o.write(body);
              o.flush();
            } catch (Exception ignored) {
              // the client went away
            }
          });
        } catch (IOException e) {
          return;
        }
      }
    }, "check-server");
    t.setDaemon(true);
    t.start();
    return ss.getLocalPort();
  }

  private static String head(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) >= 0) {
      b.write(c);
      byte[] a = b.toByteArray();
      int n = a.length;
      if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
    }
    return b.toString(StandardCharsets.ISO_8859_1);
  }

  private static SSLContext clientTls;

  /** As app code connects: through Net.connect, with TLS layered by the app's factory through Net.createSocket. */
  private static Socket connect(String host, int port, boolean tls) throws IOException {
    Socket raw = new Socket();
    Net.connect(raw, new InetSocketAddress(host, port), 5000);
    if (!tls) return raw;
    javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) Net.createSocket(clientTls.getSocketFactory(), raw, host, port, true);
    s.startHandshake();
    return s;
  }

  record Answer(int status, String head, byte[] body) {
    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      for (String l : head.split("\r\n")) {
        int colon = l.indexOf(':');
        if (colon > 0 && l.substring(0, colon).trim().equalsIgnoreCase(name)) return l.substring(colon + 1).trim();
      }
      return null;
    }
  }

  /** One request on an open connection (kept alive), its answer read by Content-Length. */
  private static Answer ask(Socket s, String method, String host, String path, String headers, byte[] body) throws IOException {
    OutputStream o = s.getOutputStream();
    o.write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + headers
        + (body != null ? "Content-Length: " + body.length + "\r\n" : "") + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    if (body != null) o.write(body);
    o.flush();
    InputStream in = s.getInputStream();
    String h = head(in);
    int status = Integer.parseInt(h.split(" ")[1]);
    Answer a = new Answer(status, h, new byte[0]);
    String cl = a.header("Content-Length");
    return new Answer(status, h, cl == null ? new byte[0] : in.readNBytes(Integer.parseInt(cl)));
  }

  private static Answer get(String host, int port, boolean tls, String path) throws IOException {
    try (Socket s = connect(host, port, tls)) {
      return ask(s, "GET", host + (port == (tls ? 443 : 80) ? "" : ":" + port), path, "", null);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> entries(Path har) throws IOException {
    Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(har));
    lastLog = (Map<String, Object>) root.get("log");
    return (List<Map<String, Object>>) lastLog.get("entries");
  }

  private static Map<String, Object> lastLog;

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> with(List<Map<String, Object>> es, String urlEnd) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> e : es) if (String.valueOf(((Map<String, Object>) e.get("request")).get("url")).endsWith(urlEnd)) out.add(e);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static String header(Map<String, Object> e, String part, String name) {
    for (Object o : (List<Object>) ((Map<String, Object>) e.get(part)).get("headers")) {
      Map<String, Object> h = (Map<String, Object>) o;
      if (String.valueOf(h.get("name")).equalsIgnoreCase(name)) return String.valueOf(h.get("value"));
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> content(Map<String, Object> e) {
    return (Map<String, Object>) ((Map<String, Object>) e.get("response")).get("content");
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("fetchcheck");
    if (args.length == 1 && args[0].equals("no-replay")) {
      System.setProperty("apksim.networkUnmatched", "fetch:" + dir.resolve("out.har"));
      String msg = "";
      try {
        Net.configure();
      } catch (RuntimeException e) {
        msg = String.valueOf(e.getMessage());
      }
      check(msg.contains("--network-unmatched fetch:FILE needs --network replay FILE"), "fetch: without --network replay stops the launch: " + msg);
      check(!Files.exists(dir.resolve("out.har")), "and writes nothing");
      System.out.println(failures == 0 ? "all ok" : failures + " FAILED");
      System.exit(failures == 0 ? 0 : 1);
    }

    // A TLS server whose certificate names secure.fetch.test (the app's factory trusts anything; the name is still checked).
    Path ks = dir.resolve("ks.p12");
    new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-dname",
        "CN=secure.fetch.test", "-ext", "SAN=dns:secure.fetch.test", "-keystore", ks.toString(), "-storepass", "changeit",
        "-storetype", "PKCS12", "-validity", "2").redirectErrorStream(true).start().waitFor();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(ks)) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(store, "changeit".toCharArray());
    SSLContext serverTls = SSLContext.getInstance("TLS");
    serverTls.init(kmf.getKeyManagers(), null, null);
    clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, new TrustManager[] {new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] c, String a) {}
      public void checkServerTrusted(X509Certificate[] c, String a) {}
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }}, null);

    int port = serve(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    int tlsPort = serve((SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    int downPort;
    try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      downPort = gone.getLocalPort();  // closed again: nothing listens there
    }

    Path in = dir.resolve("in.har");
    String inText = "{\"log\":{\"version\":\"1.2\",\"entries\":[{\"startedDateTime\":\"2026-01-01T00:00:00Z\",\"time\":1,"
        + "\"request\":{\"method\":\"GET\",\"url\":\"http://api.fetch.test/recorded\",\"httpVersion\":\"HTTP/1.1\",\"headers\":[],\"queryString\":[]},"
        + "\"response\":{\"status\":200,\"statusText\":\"OK\",\"httpVersion\":\"HTTP/1.1\",\"headers\":[{\"name\":\"Content-Type\",\"value\":\"text/plain\"}],"
        + "\"content\":{\"size\":7,\"mimeType\":\"text/plain\",\"text\":\"from-IN\"}},\"timings\":{\"send\":0,\"wait\":0,\"receive\":0}}]}}";
    Files.writeString(in, inText);
    byte[] inBefore = Files.readAllBytes(in);
    Path out = dir.resolve("out.har");

    System.setProperty("apksim.netCheckLoopback", "true");
    System.setProperty("apksim.network", "replay:" + in);
    System.setProperty("apksim.networkUnmatched", "fetch:" + out);
    System.setProperty("apksim.networkHost", "api.fetch.test=127.0.0.1:" + port + ",secure.fetch.test=127.0.0.1:" + tlsPort
        + ",down.fetch.test=127.0.0.1:" + downPort);
    Net.configure();
    check(Net.mode == Net.Mode.REPLAY && Net.fetched != null, "replaying, fetching into " + out);
    check(Net.describe().contains("fetching what it lacks into " + out), "the launch line says so: " + Net.describe());

    // Matched: answered from IN, the server never asked.
    Answer a = get("api.fetch.test", 80, false, "/recorded");
    check(a.status == 200 && a.text().equals("from-IN") && "recorded".equals(a.header("X-Apksim-Replay")) && hits("/recorded") == 0,
        "a request IN has is answered from IN, not the server: " + a.status + " " + a.text());

    // Unmatched: fetched from the server, answered with its headers, kept in OUT.
    Answer f;
    try (Socket s = connect("api.fetch.test", 80, false)) {
      f = ask(s, "GET", "api.fetch.test", "/new", "Authorization: Bearer tok-123\r\n", null);
      check(f.status == 200 && f.text().equals("fresh 1 auth=sent") && "fetched".equals(f.header("X-Apksim-Replay"))
          && "session=secret-cookie".equals(f.header("Set-Cookie")) && hits("/new") == 1,
          "a request IN lacks is fetched from the server (its Authorization sent, its Set-Cookie to the app): " + f.status + " " + f.text());
      Answer again = ask(s, "GET", "api.fetch.test", "/new", "Authorization: Bearer tok-123\r\n", null);
      check(again.status == 200 && again.text().equals(f.text()) && hits("/new") == 1,
          "its repeat on the same connection is answered from what was fetched; the server saw one: " + again.text());
    }
    Answer third = get("api.fetch.test", 80, false, "/new");
    // Without the Authorization header: it is redacted, so not part of the match.
    check(third.status == 200 && third.text().equals(f.text()) && hits("/new") == 1, "and on a new connection: " + third.text());

    // Written about 300 ms after the exchange, without a save.
    Thread.sleep(1200);
    check(Files.exists(out) && with(entries(out), "/new").size() == 1, "OUT is written after the exchange, with it");

    // Two at once: one request reaches the server, both get its answer.
    Answer[] two = new Answer[2];
    Thread[] ts = new Thread[2];
    for (int i = 0; i < 2; i++) {
      int k = i;
      ts[i] = new Thread(() -> {
        try {
          two[k] = get("api.fetch.test", 80, false, "/slow");
        } catch (IOException e) {
          two[k] = new Answer(-1, e.toString(), new byte[0]);
        }
      });
      ts[i].start();
    }
    for (Thread t : ts) t.join();
    check(two[0].status == 200 && two[1].status == 200 && two[0].text().equals("slow 1") && two[1].text().equals("slow 1") && hits("/slow") == 1,
        "the same request on two connections at once reaches the server once: " + two[0].text() + ", " + two[1].text() + ", " + hits("/slow") + " hit");

    // A POST body goes to the server and into OUT; a different body is a different request.
    Answer p1, p2;
    try (Socket s = connect("api.fetch.test", 80, false)) {
      p1 = ask(s, "POST", "api.fetch.test", "/echo", "Content-Type: application/json\r\n", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
      p2 = ask(s, "POST", "api.fetch.test", "/echo", "Content-Type: application/json\r\n", "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
    }
    check(p1.text().equals("echo {\"a\":1}") && p2.text().equals("echo {\"a\":2}") && hits("/echo") == 2, "POST bodies are sent and matched by body: " + p1.text() + ", " + p2.text());

    // gzip: decoded in OUT, re-encoded for a client that accepts it.
    Answer gz;
    try (Socket s = connect("api.fetch.test", 80, false)) {
      gz = ask(s, "GET", "api.fetch.test", "/gz", "Accept-Encoding: gzip\r\n", null);
    }
    String unzipped = "";
    try (InputStream g = new GZIPInputStream(new ByteArrayInputStream(gz.body))) {
      unzipped = new String(g.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      unzipped = e.toString();
    }
    check("gzip".equals(gz.header("Content-Encoding")) && unzipped.equals("zipped text"), "a gzip answer reaches the app gzipped: " + unzipped);

    // Chunked, and an error status, are fetched answers like any other.
    Answer ch = get("api.fetch.test", 80, false, "/chunked");
    check(ch.status == 200 && ch.text().equals("hello world"), "a chunked answer: " + ch.text());
    Answer nf = get("api.fetch.test", 80, false, "/missing");
    check(nf.status == 404 && nf.text().equals("path /missing"), "a 404 is the server's answer, fetched: " + nf.status);

    // TLS: the app's TLS ends at the engine, which does real TLS to the server with the app's factory.
    Answer tls = get("secure.fetch.test", 443, true, "/tls");
    check(tls.status == 200 && tls.text().equals("path /tls") && "fetched".equals(tls.header("X-Apksim-Replay")), "an https request is fetched over TLS: " + tls.status + " " + tls.text());

    // A host by its real name (the system's DNS, not replay's placeholders).
    Answer local = get("localhost", port, false, "/by-name");
    check(local.status == 200 && local.text().equals("path /by-name"), "a host not mapped is looked up for real: " + local.status + " " + local.text());

    // A server that is down: 599, unmatched, as without the policy.
    check(Net.unmatched.isEmpty(), "nothing fetched counts as unmatched: " + Net.unmatched);
    Answer down = get("down.fetch.test", 80, false, "/x");
    check(down.status == 599 && "unmatched".equals(down.header("X-Apksim-Replay")) && down.text().contains("fetching it failed"),
        "a server that is down gives the 599: " + down.status + " " + down.text().trim());
    check(Net.unmatched.size() == 1 && Net.unmatched.get(0).contains("http://down.fetch.test/x"), "and is listed as unmatched: " + Net.unmatched);

    // Not HTTP: unmatched as before.
    try (Socket s = connect("api.fetch.test", 80, false)) {
      s.getOutputStream().write(new byte[] {0, 1, 2, 3});
      s.getOutputStream().flush();
      s.setSoTimeout(3000);
      s.getInputStream().read();
    } catch (IOException ignored) {
      // closed or timed out: either way no answer
    }
    Thread.sleep(200);
    check(Net.unmatched.size() == 2 && Net.unmatched.get(1).contains("non-HTTP"), "a connection that is not HTTP is unmatched: " + Net.unmatched);

    String status = Net.command(new String[] {"network"});
    check(status.contains("replayed, 9 fetched, 2 unmatched"), "the status line counts what was fetched: " + status);

    Net.flush();
    List<Map<String, Object>> es = entries(out);
    check(es.size() == 9, "OUT holds the 9 fetched exchanges, each once: " + es.size());
    check(with(es, "/recorded").isEmpty(), "and none of IN's");
    check(lastLog.get("_apksimConnections") == null, "and none of the tap's connections");
    Map<String, Object> n = with(es, "/new").get(0);
    check("REDACTED".equals(header(n, "request", "Authorization")) && "REDACTED".equals(header(n, "response", "Set-Cookie"))
        && !Files.readString(out).contains("tok-123") && !Files.readString(out).contains("secret-cookie"),
        "redacted as a recording is: Authorization and Set-Cookie, no secret in the file");
    check("fresh 1 auth=sent".equals(content(n).get("text")) && "fixture".equals(header(n, "response", "X-Server")), "the answer as the server gave it");
    check(with(es, "/slow").size() == 1 && with(es, "/echo").size() == 2, "the request asked twice at once is kept once; two bodies are two entries");
    Map<String, Object> g = with(es, "/gz").get(0);
    check(Boolean.TRUE.equals(content(g).get("_apksimGzip")) && "zipped text".equals(content(g).get("text")), "a gzip body is kept decoded");
    @SuppressWarnings("unchecked")
    Map<String, Object> certs = (Map<String, Object>) lastLog.get("_apksimCertificates");
    check(certs != null && certs.containsKey("secure.fetch.test"), "the TLS server's certificates are kept");
    check(java.util.Arrays.equals(inBefore, Files.readAllBytes(in)), "IN is not changed");
    check(!Files.exists(out.resolveSibling(out.getFileName() + ".apksim-tmp")), "the temporary file was renamed over OUT");

    // OUT replays: what a later launch would be answered from.
    Recording again = Recording.load(out, new Recording.Match(java.util.Set.of("method", "url", "body"), java.util.Set.of(), java.util.Set.of(), Net.redact));
    Recording.Entry e = again.find("GET", "http://api.fetch.test/new", List.of(), null);
    check(e != null && new String(e.respBody, StandardCharsets.UTF_8).equals("fresh 1 auth=sent"), "OUT loads as a recording a replay answers from");

    System.out.println(failures == 0 ? "all ok" : failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }
}
