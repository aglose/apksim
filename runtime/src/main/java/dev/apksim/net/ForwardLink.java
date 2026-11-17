package dev.apksim.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forward rules in a beam session (issue 426, docs/beam.md "Forward rules"): the app runs in the beam sandbox, which
 * denies the Mac's loopback, so a forward rule's request is sent by the agent. With `--forward-link` (the agent adds it
 * to a session that runs the network inspector) the engine hands each forward to the `fwdwatch` control connection as
 * one line `q ID {json}` (method, url, headers, body in base64) and takes the agent's `q ID {json}` answer (status,
 * headers, body, or `error`). The agent sends only to what its owner allowed (`apksim beam config forward-allow`),
 * read again at each request, whatever rule the page set. Without the flag a forward goes out from the instance as on
 * a server (`ForwardSend`).
 */
public final class ForwardLink {
  private ForwardLink() {}

  /** Set at launch by `--forward-link` (`-Dapksim.forwardLink=true`), and again when a standby takes the launch. */
  public static volatile boolean on;
  /** At most this much of a body each way: a line carries it in base64, and the agent holds it in memory. */
  public static final int MAX_BODY = 4 * 1024 * 1024;
  /** The agent's own limits add up to 10 + 30 + 60 s; past this the link is taken to be dead. */
  static final long ANSWER_MS = Long.getLong("apksim.forwardLink.answerMs", 110_000);
  private static final long FIRST_LINK_MS = Long.getLong("apksim.forwardLink.firstLinkMs", 10_000);

  public static void load() {
    on = Boolean.getBoolean("apksim.forwardLink");
    linkDeadline = System.currentTimeMillis() + FIRST_LINK_MS;
  }

  private static volatile PrintWriter sender;
  private static volatile boolean linked;
  private static volatile long linkDeadline;
  private static final Map<Long, CompletableFuture<ForwardSend.Result>> pending = new ConcurrentHashMap<>();
  private static final Map<Long, PrintWriter> owners = new ConcurrentHashMap<>();
  private static final AtomicLong ids = new AtomicLong();

  /** `fwdwatch` (Control): this connection is the link until it closes; lines out are requests, lines in the answers. */
  public static void serve(BufferedReader in, PrintWriter out) throws IOException {
    if (!on) {
      out.println("error: forward link is off (--forward-link)");
      return;
    }
    out.println("ok fwdwatch");
    PrintWriter old;
    synchronized (ForwardLink.class) {
      old = sender;
      sender = out;
      linked = true;
      ForwardLink.class.notifyAll();
    }
    if (old != null) failAll(old, "the agent's link was replaced by another");
    try {
      for (String line; (line = in.readLine()) != null; ) answer(out, line);
    } finally {
      synchronized (ForwardLink.class) {
        if (sender == out) sender = null;
      }
      failAll(out, "the agent went away");
    }
  }

  static void answer(PrintWriter link, String line) {
    if (!line.startsWith("q ")) return;
    int sp = line.indexOf(' ', 2);
    if (sp < 0) return;
    long id;
    try {
      id = Long.parseLong(line.substring(2, sp));
    } catch (NumberFormatException e) {
      return;
    }
    CompletableFuture<ForwardSend.Result> f = pending.get(id);
    if (f == null || owners.get(id) != link) return;
    try {
      if (!(Json.parse(line.substring(sp + 1)) instanceof Map<?, ?> m)) throw new IllegalArgumentException("not an object");
      if (m.get("error") instanceof String e) {
        f.completeExceptionally(new IOException(e));
        return;
      }
      int status = m.get("status") instanceof Number n ? n.intValue() : 0;
      if (status < 100 || status > 599) throw new IllegalArgumentException("status " + status);
      List<String[]> headers = new ArrayList<>();
      if (m.get("headers") instanceof List<?> l) {
        for (Object o : l) if (o instanceof List<?> p && p.size() == 2) headers.add(new String[] {String.valueOf(p.get(0)), String.valueOf(p.get(1))});
      }
      String b64 = m.get("body") instanceof String s ? s : "";
      if (b64.length() > (MAX_BODY / 3 + 1) * 4) throw new IllegalArgumentException("body over " + MAX_BODY + " bytes");
      byte[] body = Base64.getDecoder().decode(b64);
      if (body.length > MAX_BODY) throw new IllegalArgumentException("body over " + MAX_BODY + " bytes");
      f.complete(new ForwardSend.Result(status, headers, body));
    } catch (RuntimeException e) {
      f.completeExceptionally(new IOException("the agent's answer could not be used: " + e.getMessage()));
    }
  }

  private static void failAll(PrintWriter link, String why) {
    for (Map.Entry<Long, PrintWriter> e : owners.entrySet()) {
      CompletableFuture<ForwardSend.Result> f = e.getValue() == link ? pending.get(e.getKey()) : null;
      if (f != null) f.completeExceptionally(new IOException(why));
    }
  }

  /** The request sent by the agent: its answer as the target gave it, or an IOException saying why not (the engine's 502). */
  static ForwardSend.Result fetch(String method, String url, List<String[]> headers, byte[] body) throws IOException, InterruptedException {
    if (body != null && body.length > MAX_BODY) throw new IOException("a request body over " + MAX_BODY + " bytes is not forwarded in a beam session");
    PrintWriter out = sender;
    if (out == null && !linked) {  // a request from Application.onCreate can come before the agent has attached
      synchronized (ForwardLink.class) {
        while (sender == null && !linked && System.currentTimeMillis() < linkDeadline) ForwardLink.class.wait(100);
        out = sender;
      }
    }
    if (out == null) throw new IOException("no agent to send it (the beam agent's forward link is not up)");
    long id = ids.incrementAndGet();
    CompletableFuture<ForwardSend.Result> f = new CompletableFuture<>();
    owners.put(id, out);
    pending.put(id, f);
    try {
      StringBuilder b = new StringBuilder(256 + (body == null ? 0 : body.length * 4 / 3));
      b.append("q ").append(id).append(" {\"method\":");
      b.append(Inspector.q(method));
      b.append(",\"url\":");
      b.append(Inspector.q(url));
      b.append(",\"headers\":[");
      boolean first = true;
      for (String[] h : headers) {
        if (!first) b.append(',');
        first = false;
        b.append('[');
        b.append(Inspector.q(h[0]));
        b.append(',');
        b.append(Inspector.q(h[1]));
        b.append(']');
      }
      b.append("],\"body\":\"").append(body == null ? "" : Base64.getEncoder().encodeToString(body)).append("\"}");
      PrintWriter w = out;
      Thread.ofVirtual().name("apksim-forward-send").start(() -> {
        synchronized (w) {
          w.println(b);
          if (w.checkError()) f.completeExceptionally(new IOException("the agent went away"));
        }
      });
      try {
        return f.get(ANSWER_MS, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        throw new IOException("the agent did not answer in " + ANSWER_MS / 1000 + " s");
      } catch (java.util.concurrent.ExecutionException e) {
        throw e.getCause() instanceof IOException io ? io : new IOException(String.valueOf(e.getCause()));
      }
    } finally {
      pending.remove(id);
      owners.remove(id);
    }
  }
}
