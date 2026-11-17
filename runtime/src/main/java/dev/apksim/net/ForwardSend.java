package dev.apksim.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One HTTP request of a forward rule (issue 363) sent to its target and the answer read whole: HTTP/1.1, no redirects
 * followed (they are the app's to follow), 10 s to connect, 30 s for the headers, 60 s in all for the body, at most
 * `maxBody` bytes each way. Used by the network engine in an instance and, for a beam session (issue 426), by the agent
 * on the instance's behalf (`ForwardLink`), whose sandbox cannot reach the Mac's loopback.
 */
public final class ForwardSend {
  private ForwardSend() {}

  /** The answer as the target sent it: every header as one name/value pair, nothing filtered. */
  public record Result(int status, List<String[]> headers, byte[] body) {}

  private static java.net.http.HttpClient client;

  /** The client forward rules send with: no redirects followed, its threads apksim's (daemons, named so). */
  private static synchronized java.net.http.HttpClient client() {
    if (client == null) {
      AtomicInteger n = new AtomicInteger();
      client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER)
          .connectTimeout(java.time.Duration.ofSeconds(10))
          .executor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "apksim-net-forward-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
          })).build();
    }
    return client;
  }

  /** `body` null: no body. A header the client will not send (Host, Content-Length...) is left out. */
  public static Result send(String method, String url, List<String[]> headers, byte[] body, int maxBody) throws IOException, InterruptedException {
    java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
        .version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(java.time.Duration.ofSeconds(30))
        .method(method, body != null ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body) : java.net.http.HttpRequest.BodyPublishers.noBody());
    for (String[] h : headers) {
      try {
        b.header(h[0], h[1]);
      } catch (IllegalArgumentException e) {
        // a name or value the client will not send: left out
      }
    }
    java.net.http.HttpResponse<InputStream> r = client().send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    // The answer is held in memory: at most maxBody of it, read within 60 s in all, else an error.
    ByteArrayOutputStream got = new ByteArrayOutputStream();
    long deadline = System.nanoTime() + 60_000_000_000L;
    // The client's own timeout ends with the headers: a body that stalls would block the read below for good, so a
    // watchdog closes it at the deadline (the read then throws).
    InputStream rbody = r.body();
    java.util.Timer watchdog = new java.util.Timer("apksim-net-forward-watchdog", true);
    watchdog.schedule(new java.util.TimerTask() {
      @Override
      public void run() {
        try {
          rbody.close();
        } catch (IOException ignored) {
          // closing is the point
        }
      }
    }, 60_000L);
    try (InputStream in = rbody) {
      byte[] buf = new byte[64 * 1024];
      for (int n; (n = in.read(buf)) > 0; ) {
        if (got.size() + n > maxBody) throw new IOException("the upstream's answer is over " + maxBody + " bytes");
        if (System.nanoTime() > deadline) throw new IOException("the upstream's answer took over 60 s");
        got.write(buf, 0, n);
      }
    } finally {
      watchdog.cancel();
    }
    List<String[]> out = new ArrayList<>();
    r.headers().map().forEach((name, values) -> {
      for (String v : values) out.add(new String[] {name, v});
    });
    return new Result(r.statusCode(), out, got.toByteArray());
  }

  /** A failure's message as one line of printable ASCII, as the exchange's row and the 502 show it. */
  static String reason(Exception e) {
    return (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).replaceAll("[^\\x20-\\x7e]", " ");
  }
}
