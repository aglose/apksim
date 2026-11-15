package dev.apksim.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tap's HTTP/2 decoder: frames from each direction of one connection, header blocks through that direction's
 * {@link Hpack} table, DATA into the stream's body as it passes (never held whole as a frame). One exchange per stream:
 * finished when the server ends the stream, cut short by a RST_STREAM, a GOAWAY or the connection's end. gRPC is this:
 * its messages are the bodies, its status the trailers.
 */
final class TapHttp2 implements Tap.Decoder {
  static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
  private static final int DATA = 0, HEADERS = 1, RST_STREAM = 3, PUSH_PROMISE = 5, GOAWAY = 7, CONTINUATION = 9;
  private static final int END_STREAM = 0x1, END_HEADERS = 0x4, PADDED = 0x8, PRIORITY = 0x20;

  private final Tap.Conn c;
  private final Side client = new Side(true), server = new Side(false);
  /** Streams whose exchange is not over, by id, in the order they began. */
  private final Map<Integer, Stream> streams = new LinkedHashMap<>();
  private String goaway;

  private static final class Stream {
    final Tap.Ex x = new Tap.Ex();
    boolean answered;
  }

  private static final class Side {
    final boolean out;
    final Tap.Held held = new Tap.Held();
    final Hpack hpack = new Hpack();
    int preface;
    /** A DATA frame in progress: what is left of its data and of its padding, its stream, whether it ends the stream. */
    int dataLeft, padLeft, dataStream;
    boolean dataEnds;
    /** A header block in progress (HEADERS or PUSH_PROMISE, then CONTINUATIONs). */
    ByteArrayOutputStream block;
    int blockStream, blockPromised;
    boolean blockEnds;

    Side(boolean out) {
      this.out = out;
      preface = out ? PREFACE.length : 0;
    }
  }

  TapHttp2(Tap.Conn c) {
    this.c = c;
  }

  @Override
  public String protocol() {
    return "h2";
  }

  @Override
  public void bytes(boolean out, byte[] d, long now) throws IOException {
    Side s = out ? client : server;
    byte[] a;
    int off = 0, end;
    if (s.held.n == 0) {
      a = d;
      end = d.length;
    } else {
      s.held.add(d);
      a = s.held.a;
      end = s.held.n;
    }
    while (off < end) {
      if (s.preface > 0) {
        int n = Math.min(s.preface, end - off);
        off += n;
        s.preface -= n;
        continue;
      }
      if (s.dataLeft > 0) {
        int n = Math.min(s.dataLeft, end - off);
        data(s, a, off, n, now);
        off += n;
        s.dataLeft -= n;
        if (s.dataLeft == 0 && s.padLeft == 0) dataDone(s, now);
        continue;
      }
      if (s.padLeft > 0) {
        int n = Math.min(s.padLeft, end - off);
        off += n;
        s.padLeft -= n;
        if (s.padLeft == 0) dataDone(s, now);
        continue;
      }
      if (end - off < 9) break;
      int len = (a[off] & 0xff) << 16 | (a[off + 1] & 0xff) << 8 | (a[off + 2] & 0xff);
      int type = a[off + 3] & 0xff, flags = a[off + 4] & 0xff;
      int stream = int31(a, off + 5);
      if (type == DATA) {
        boolean padded = (flags & PADDED) != 0;
        if (padded && end - off < 10) break;
        int pad = padded ? a[off + 9] & 0xff : 0;
        off += padded ? 10 : 9;
        int n = len - (padded ? 1 + pad : 0);
        if (n < 0) throw new IOException("a DATA frame's padding is longer than the frame");
        s.dataLeft = n;
        s.padLeft = pad;
        s.dataStream = stream;
        s.dataEnds = (flags & END_STREAM) != 0;
        if (n == 0 && pad == 0) dataDone(s, now);
        continue;
      }
      if (len > Tap.MAX_HEAD) throw new IOException("an HTTP/2 frame of type " + type + " with " + len + " bytes");
      if (end - off < 9 + len) break;
      frame(s, type, flags, stream, a, off + 9, len, now);
      off += 9 + len;
    }
    s.held.keep(a, off, end);
  }

  private static int int31(byte[] a, int at) {
    return (a[at] & 0x7f) << 24 | (a[at + 1] & 0xff) << 16 | (a[at + 2] & 0xff) << 8 | (a[at + 3] & 0xff);
  }

  private static long int32(byte[] a, int at) {
    return (a[at] & 0xffL) << 24 | (a[at + 1] & 0xff) << 16 | (a[at + 2] & 0xff) << 8 | (a[at + 3] & 0xff);
  }

  private void data(Side s, byte[] a, int off, int n, long now) {
    Stream st = streams.get(s.dataStream);
    if (st == null) return;
    if (s.out) {
      if (st.x.reqBody == null) st.x.reqBody = new Tap.Body(true);
      st.x.reqBody.add(a, off, n);
    } else {
      if (st.x.respBody == null) st.x.respBody = new Tap.Body(false);
      st.x.respBody.add(a, off, n);
    }
  }

  private void dataDone(Side s, long now) {
    if (s.dataEnds) ended(s, s.dataStream, now);
    s.dataEnds = false;
  }

  private void frame(Side s, int type, int flags, int stream, byte[] a, int off, int len, long now) throws IOException {
    switch (type) {
      case HEADERS, PUSH_PROMISE -> {
        int p = off, e = off + len;
        if ((flags & PADDED) != 0) {
          if (len < 1) throw new IOException("a padded frame with no padding length");
          e -= a[p++] & 0xff;
        }
        int promised = 0;
        if (type == PUSH_PROMISE) {
          promised = int31(a, p);
          p += 4;
        } else if ((flags & PRIORITY) != 0) {
          p += 5;
        }
        if (e < p) throw new IOException("a header frame shorter than its own fields");
        s.blockStream = stream;
        s.blockPromised = promised;
        s.blockEnds = type == HEADERS && (flags & END_STREAM) != 0;
        if ((flags & END_HEADERS) != 0) {
          headers(s, s.hpack.decode(a, p, e - p), now);
        } else {
          s.block = new ByteArrayOutputStream(Math.max(64, 2 * (e - p)));
          s.block.write(a, p, e - p);
        }
      }
      case CONTINUATION -> {
        if (s.block == null) throw new IOException("a CONTINUATION with no header block before it");
        s.block.write(a, off, len);
        if (s.block.size() > Tap.MAX_HEAD) throw new IOException("a header block over " + (Tap.MAX_HEAD >> 10) + " KB");
        if ((flags & END_HEADERS) != 0) {
          byte[] b = s.block.toByteArray();
          s.block = null;
          headers(s, s.hpack.decode(b, 0, b.length), now);
        }
      }
      case RST_STREAM -> {
        if (len >= 4) reset(stream, (s.out ? "the app" : "the server") + " reset the stream: " + error(int32(a, off)), now);
      }
      case GOAWAY -> {
        if (len < 8) return;
        int last = int31(a, off);
        String debug = new String(a, off + 8, Math.min(len - 8, 200), StandardCharsets.ISO_8859_1).trim();
        goaway = (s.out ? "the app" : "the server") + " sent GOAWAY: " + error(int32(a, off + 4)) + (debug.isEmpty() ? "" : " (" + debug + ")");
        if (!s.out) {  // streams past the last one the server took will get no answer
          for (Integer id : new ArrayList<>(streams.keySet())) {
            if (id > last && id % 2 == 1) reset(id, goaway, now);
          }
        }
      }
      default -> {
        // SETTINGS, PING, WINDOW_UPDATE, PRIORITY, extensions: nothing an exchange is made of
      }
    }
  }

  private void headers(Side s, List<String[]> fields, long now) {
    int id = s.blockStream;
    if (s.blockPromised != 0) {
      // A push: the request the server answers of its own accord, on the promised stream.
      Stream st = new Stream();
      request(st, fields, now);
      st.x.reqDone = true;
      st.x.tSent = now;
      streams.put(s.blockPromised, st);
      Tap.flight(1);
      return;
    }
    if (s.out) {
      Stream st = streams.get(id);
      if (st == null) {
        // Trailers of a request whose answer is already whole (the stream is over here) are not a new request.
        if (Tap.header(fields, ":method") == null) return;
        st = new Stream();
        request(st, fields, now);
        streams.put(id, st);
        Tap.flight(1);
      }
      if (s.blockEnds) ended(s, id, now);
      return;
    }
    Stream st = streams.get(id);
    if (st == null) return;
    String status = Tap.header(fields, ":status");
    if (status != null && !st.answered) {
      int code;
      try {
        code = Integer.parseInt(status.trim());
      } catch (NumberFormatException e) {
        code = 0;
      }
      if (code >= 100 && code < 200 && code != 101) return;  // an interim answer: the real one follows
      st.answered = true;
      st.x.status = code;
      st.x.respVersion = "HTTP/2";
      st.x.respHeaders = plain(fields);
      st.x.tFirst = now;
    } else {
      st.x.trailers = plain(fields);
    }
    if (s.blockEnds) ended(s, id, now);
  }

  private void request(Stream st, List<String[]> fields, long now) {
    Tap.Ex x = st.x;
    String method = Tap.header(fields, ":method"), scheme = Tap.header(fields, ":scheme"), path = Tap.header(fields, ":path");
    String authority = Tap.header(fields, ":authority");
    if (authority == null) authority = Tap.header(fields, "host");
    if (authority == null) authority = c.host + (c.port == 443 ? "" : ":" + c.port);
    x.method = method == null ? "" : method;
    x.version = "HTTP/2";
    x.url = "CONNECT".equals(method) && path == null ? authority : (scheme == null ? (c.tls ? "https" : "http") : scheme) + "://" + authority + (path == null ? "/" : path);
    x.reqHeaders = plain(fields);
    x.t0 = now;
  }

  /** The fields without the pseudo-headers, which the method, URL and status already say. */
  private static List<String[]> plain(List<String[]> fields) {
    List<String[]> out = new ArrayList<>(fields.size());
    for (String[] f : fields) if (!f[0].startsWith(":")) out.add(f);
    return out;
  }

  /** One side ended the stream: the app's request is whole, or the server's answer is. */
  private void ended(Side s, int id, long now) {
    Stream st = streams.get(id);
    if (st == null) return;
    if (s.out) {
      st.x.reqDone = true;
      st.x.tSent = now;
    } else {
      finish(id, st, now, st.answered ? null : "the server ended the stream without an answer");
    }
  }

  private void reset(int id, String why, long now) {
    Stream st = streams.get(id);
    if (st != null) finish(id, st, now, why);
  }

  private void finish(int id, Stream st, long now, String error) {
    streams.remove(id);
    Tap.flight(-1);
    Tap.emit(c, Tap.entry(st.x, now, error, false));
  }

  @Override
  public void end(boolean eof, String error, long now) {
    String why = error != null ? error : goaway != null ? goaway : "the connection closed before the answer was whole";
    for (Map.Entry<Integer, Stream> e : new ArrayList<>(streams.entrySet())) finish(e.getKey(), e.getValue(), now, why);
  }

  @Override
  public void unfinished(List<Recording.Entry> into) {
    long now = System.nanoTime();
    for (Stream st : streams.values()) into.add(Tap.entry(st.x, now, null, true));
  }

  private static String error(long code) {
    String[] names = {"NO_ERROR", "PROTOCOL_ERROR", "INTERNAL_ERROR", "FLOW_CONTROL_ERROR", "SETTINGS_TIMEOUT", "STREAM_CLOSED", "FRAME_SIZE_ERROR",
        "REFUSED_STREAM", "CANCEL", "COMPRESSION_ERROR", "CONNECT_ERROR", "ENHANCE_YOUR_CALM", "INADEQUATE_SECURITY", "HTTP_1_1_REQUIRED"};
    return code >= 0 && code < names.length ? names[(int) code] : "error " + code;
  }
}
