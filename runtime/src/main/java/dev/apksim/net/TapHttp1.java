package dev.apksim.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tap's HTTP/1.1 decoder: the copies of what one connection sent and received, read into exchanges. Requests and
 * answers are parsed apart, each from its own direction, and paired in order (a request is queued before it is sent, so
 * it is always here before its answer). After a 101 to a WebSocket upgrade the bytes go to {@link TapWebSocket}; after a
 * CONNECT through the app's own proxy, or any other upgrade, nothing more is read.
 */
final class TapHttp1 implements Tap.Decoder {
  private final Tap.Conn c;
  private final Tap.Held outHeld = new Tap.Held(), inHeld = new Tap.Held();
  /** Requests whose answer is not whole yet, oldest first. */
  private final ArrayDeque<Tap.Ex> pending = new ArrayDeque<>();
  private final BodyReader outBody = new BodyReader(), inBody = new BodyReader();
  /** The request whose body is going up, and the exchange whose answer's body is coming down. */
  private Tap.Ex sending, answering;
  /** When the head being read began (nanoTime; 0: none begun). */
  private long outHeadAt, inHeadAt;
  /** Not read any further: a tunnel, an upgrade that is not WebSocket, or an answer nobody asked for. */
  private boolean opaque;
  private TapWebSocket ws;

  TapHttp1(Tap.Conn c) {
    this.c = c;
  }

  @Override
  public String protocol() {
    return ws != null ? "http/1.1, websocket" : "http/1.1";
  }

  @Override
  public void bytes(boolean out, byte[] d, long now) throws IOException {
    if (opaque) return;
    if (ws != null) {
      ws.bytes(out, d, 0, d.length, now);
      return;
    }
    if (out) sent(d, now);
    else received(d, now);
  }

  private void sent(byte[] d, long now) throws IOException {
    byte[] a;
    int off = 0, end;
    if (outHeld.n == 0) {
      a = d;
      end = d.length;
    } else {
      outHeld.add(d);
      a = outHeld.a;
      end = outHeld.n;
    }
    while (off < end) {
      if (sending != null) {
        off = outBody.feed(a, off, end);
        if (!outBody.done) break;
        sending.reqDone = true;
        sending.tSent = now;
        sending = null;
        continue;
      }
      if (outHeadAt == 0) outHeadAt = now;
      int e = headEnd(a, off, end);
      if (e < 0) {
        if (end - off > Tap.MAX_HEAD) throw new IOException("a request head over " + (Tap.MAX_HEAD >> 10) + " KB");
        break;
      }
      Tap.Ex x = requestHead(a, off, e);
      x.t0 = outHeadAt;
      outHeadAt = 0;
      off = e;
      pending.add(x);
      Tap.flight(1);
      String te = Tap.header(x.reqHeaders, "Transfer-Encoding");
      long length = length(Tap.header(x.reqHeaders, "Content-Length"));
      if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
        x.reqBody = new Tap.Body(true);
        outBody.chunked(x.reqBody);
        sending = x;
      } else if (length > 0) {
        x.reqBody = new Tap.Body(true);
        outBody.fixed(length, x.reqBody);
        sending = x;
      } else {
        x.reqDone = true;
        x.tSent = now;
      }
    }
    outHeld.keep(a, off, end);
  }

  private void received(byte[] d, long now) throws IOException {
    byte[] a;
    int off = 0, end;
    if (inHeld.n == 0) {
      a = d;
      end = d.length;
    } else {
      inHeld.add(d);
      a = inHeld.a;
      end = inHeld.n;
    }
    while (off < end) {
      if (ws != null) {  // frames right behind the 101
        ws.bytes(false, a, off, end - off, now);
        off = end;
        break;
      }
      if (opaque) {
        off = end;
        break;
      }
      if (answering != null) {
        off = inBody.feed(a, off, end);
        if (!inBody.done) break;
        Tap.Ex x = answering;
        answering = null;
        finish(x, now);
        continue;
      }
      if (pending.isEmpty()) {
        // An answer nobody asked for (a server's 408 on an idle connection, before it closes it): not an exchange.
        opaque = true;
        continue;
      }
      if (inHeadAt == 0) inHeadAt = now;
      int e = headEnd(a, off, end);
      if (e < 0) {
        if (end - off > Tap.MAX_HEAD) throw new IOException("a response head over " + (Tap.MAX_HEAD >> 10) + " KB");
        break;
      }
      String[] lines = new String(a, off, e - off, StandardCharsets.ISO_8859_1).split("\r?\n");
      String[] p = lines[0].split(" ", 3);
      if (p.length < 2 || !p[0].startsWith("HTTP/1.") || !p[1].matches("[0-9]{3}")) throw new IOException("not an HTTP/1 status line");
      int status = Integer.parseInt(p[1]);
      long at = inHeadAt;
      inHeadAt = 0;
      off = e;
      if (status >= 100 && status < 200 && status != 101) continue;  // 100 Continue, 103 Early Hints: the answer follows
      Tap.Ex x = pending.peek();
      x.status = status;
      x.statusText = p.length > 2 ? p[2] : "";
      x.respVersion = p[0];
      x.respHeaders = headers(lines);
      x.tFirst = at;
      if (status == 101) {
        upgraded(x, now);
        continue;
      }
      if (x.method.equals("CONNECT") && status / 100 == 2) {
        // A tunnel through the app's own proxy: what follows is another connection's bytes (TLS, layered by the app).
        x.reqDone = true;
        finish(x, now);
        opaque = true;
        continue;
      }
      String te = Tap.header(x.respHeaders, "Transfer-Encoding");
      String cl = Tap.header(x.respHeaders, "Content-Length");
      boolean chunked = te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
      if (x.method.equals("HEAD") || status == 204 || status == 304 || (!chunked && cl != null && length(cl) == 0)) {
        finish(x, now);
        continue;
      }
      x.respBody = new Tap.Body(false);
      if (chunked) inBody.chunked(x.respBody);
      else if (cl != null) inBody.fixed(length(cl), x.respBody);
      else inBody.toClose(x.respBody);
      answering = x;
    }
    inHeld.keep(a, off, end);
  }

  private void upgraded(Tap.Ex x, long now) {
    pending.poll();
    Tap.flight(-1);
    x.reqDone = true;
    Recording.Entry e = Tap.entry(x, now, null, false);
    String to = Tap.header(x.respHeaders, "Upgrade");
    if (to != null && to.equalsIgnoreCase("websocket")) {
      ws = new TapWebSocket(Tap.header(x.respHeaders, "Sec-WebSocket-Extensions"));
      e.ws = ws.messages;
    } else {
      opaque = true;
    }
    Tap.emit(c, e);
  }

  private void finish(Tap.Ex x, long now) {
    pending.remove(x);
    Tap.flight(-1);
    // Answered before its body went up (Expect: 100-continue, and the server said no): the body is not part of it.
    if (!x.reqDone && x.reqBody != null && x.reqBody.total == 0 && "100-continue".equalsIgnoreCase(Tap.header(x.reqHeaders, "Expect"))) {
      x.expectAnswered = true;
    }
    Tap.emit(c, Tap.entry(x, now, null, false));
  }

  @Override
  public void end(boolean eof, String error, long now) {
    if (eof && answering != null && inBody.state == BodyReader.TO_CLOSE) {  // a body that ends where the connection does
      Tap.Ex x = answering;
      answering = null;
      finish(x, now);
    }
    for (Tap.Ex x : pending) {
      Tap.flight(-1);
      Tap.emit(c, Tap.entry(x, now, error != null ? error : "the server closed the connection before its answer was whole", false));
    }
    pending.clear();
  }

  @Override
  public void unfinished(List<Recording.Entry> into) {
    long now = System.nanoTime();
    for (Tap.Ex x : pending) into.add(Tap.entry(x, now, null, true));
  }

  private Tap.Ex requestHead(byte[] a, int off, int end) throws IOException {
    String[] lines = new String(a, off, end - off, StandardCharsets.ISO_8859_1).split("\r?\n");
    String[] p = lines[0].split(" ");
    if (p.length != 3 || !p[2].startsWith("HTTP/1.")) throw new IOException("not an HTTP/1 request line");
    Tap.Ex x = new Tap.Ex();
    x.method = p[0];
    x.version = p[2];
    x.reqHeaders = headers(lines);
    String target = p[1];
    if (target.startsWith("http://") || target.startsWith("https://") || x.method.equals("CONNECT")) {
      x.url = target;  // through the app's own proxy: the URL itself, or the host a tunnel is for
    } else {
      String authority = Tap.header(x.reqHeaders, "Host");
      if (authority == null) authority = c.host + (c.port == (c.tls ? 443 : 80) ? "" : ":" + c.port);
      x.url = (c.tls ? "https://" : "http://") + authority + target;
    }
    return x;
  }

  private static List<String[]> headers(String[] lines) {
    List<String[]> out = new ArrayList<>(lines.length);
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0) out.add(new String[] {lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim()});
    }
    return out;
  }

  private static long length(String s) {
    if (s == null) return -1;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** The index just past the blank line that ends a head in a[off, end), or -1 if it is not all there. */
  static int headEnd(byte[] a, int off, int end) {
    for (int i = off; i < end; i++) {
      if (a[i] != '\n') continue;
      if (i + 1 < end && a[i + 1] == '\n') return i + 2;
      if (i + 2 < end && a[i + 1] == '\r' && a[i + 2] == '\n') return i + 3;
    }
    return -1;
  }

  /** A message body in one of HTTP/1.1's framings, fed as it arrives. */
  static final class BodyReader {
    static final int FIXED = 1, CHUNK_SIZE = 2, CHUNK_DATA = 3, CHUNK_END = 4, TRAILERS = 5, TO_CLOSE = 6;
    int state;
    boolean done;
    private long left;
    private Tap.Body sink;
    private final StringBuilder line = new StringBuilder();

    void fixed(long n, Tap.Body to) {
      start(FIXED, to);
      left = n;
    }

    void chunked(Tap.Body to) {
      start(CHUNK_SIZE, to);
    }

    void toClose(Tap.Body to) {
      start(TO_CLOSE, to);
    }

    private void start(int s, Tap.Body to) {
      state = s;
      sink = to;
      done = false;
      line.setLength(0);
    }

    /** Takes what belongs to the body from a[off, end); the index after it. `done` once the body is whole. */
    int feed(byte[] a, int off, int end) throws IOException {
      while (off < end && !done) {
        switch (state) {
          case FIXED, CHUNK_DATA -> {
            int n = (int) Math.min(left, end - off);
            sink.add(a, off, n);
            off += n;
            left -= n;
            if (left == 0) {
              if (state == FIXED) done = true;
              else state = CHUNK_END;
            }
          }
          case TO_CLOSE -> {
            sink.add(a, off, end - off);
            off = end;
          }
          case CHUNK_SIZE -> {
            byte ch = a[off++];
            if (ch != '\n') {
              line.append((char) ch);
              if (line.length() > 1024) throw new IOException("a chunk size line over 1 KB");
              continue;
            }
            String s = line.toString().trim();
            line.setLength(0);
            int semi = s.indexOf(';');
            try {
              left = Long.parseLong((semi < 0 ? s : s.substring(0, semi)).trim(), 16);
            } catch (NumberFormatException e) {
              throw new IOException("not a chunk size: " + s);
            }
            if (left < 0) throw new IOException("not a chunk size: " + s);
            state = left == 0 ? TRAILERS : CHUNK_DATA;
          }
          case CHUNK_END -> {
            if (a[off++] == '\n') state = CHUNK_SIZE;
          }
          case TRAILERS -> {
            byte ch = a[off++];
            if (ch == '\n') {
              if (line.length() == 0) done = true;  // the blank line after the last chunk (and any trailers)
              line.setLength(0);
            } else if (ch != '\r' && line.length() < 8) {
              line.append('x');
            }
          }
          default -> throw new IOException("no body is expected");
        }
      }
      return off;
    }
  }
}
