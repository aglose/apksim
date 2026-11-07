package dev.apksim.serve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Just enough HTTP/1.1: one request per connection, then `Connection: close`. */
final class Http {
  private static final int MAX_HEAD = 16 * 1024;

  private Http() {}

  record Request(String method, String path, Map<String, String> query, Map<String, String> headers, InputStream body) {
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    String param(String name) {
      return query.get(name);
    }

    long contentLength() {
      String v = header("content-length");
      try {
        return v == null ? -1 : Long.parseLong(v.trim());
      } catch (NumberFormatException e) {
        return -1;
      }
    }

    boolean chunked() {
      String v = header("transfer-encoding");
      return v != null && v.toLowerCase(Locale.ROOT).contains("chunked");
    }

    /** The body as sent: chunked bodies decoded (their length is then unknown, -1 from contentLength). */
    InputStream content() {
      return chunked() ? new Chunked(body) : body;
    }

    String cookie(String name) {
      String c = header("cookie");
      if (c == null) return null;
      for (String part : c.split(";")) {
        int eq = part.indexOf('=');
        if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
      }
      return null;
    }
  }

  /**
   * The request's head, or null if the client closed, sent something that is not HTTP, or had not finished by the
   * deadline (System.nanoTime()).
   */
  static Request read(InputStream in, long deadlineNanos) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream(512);
    int last4 = 0;
    while (last4 != 0x0d0a0d0a) {
      int b = in.read();
      if (b < 0 || head.size() >= MAX_HEAD || System.nanoTime() > deadlineNanos) return null;
      head.write(b);
      last4 = (last4 << 8) | b;
    }
    String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
    String[] first = lines[0].split(" ");
    if (first.length < 3) return null;
    Map<String, String> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
    }
    String target = first[1];
    int q = target.indexOf('?');
    String path = q < 0 ? target : target.substring(0, q);
    Map<String, String> query = new LinkedHashMap<>();
    if (q >= 0) {
      for (String kv : target.substring(q + 1).split("&")) {
        if (kv.isEmpty()) continue;
        int eq = kv.indexOf('=');
        try {
          String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
          String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
          query.put(k, v);
        } catch (IllegalArgumentException e) {
          return null;
        }
      }
    }
    return new Request(first[0], path, query, headers, in);
  }

  /** A `Transfer-Encoding: chunked` body, decoded; ends at the last chunk (trailers are read and dropped). */
  static final class Chunked extends InputStream {
    private final InputStream in;
    private long left;
    private boolean done;

    Chunked(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      java.util.Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) return 0;  // never read (or wait for) the next chunk's header for nothing
      if (done) return -1;
      if (left == 0) {
        String size = line();
        int semi = size.indexOf(';');  // chunk extensions
        try {
          left = Long.parseLong((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
        } catch (NumberFormatException e) {
          throw new IOException("bad chunk size");
        }
        if (left < 0) throw new IOException("bad chunk size");
        if (left == 0) {
          while (!line().isEmpty()) {
            // trailers
          }
          done = true;
          return -1;
        }
      }
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n < 0) throw new IOException("the body ended inside a chunk");
      left -= n;
      if (left == 0 && !line().isEmpty()) throw new IOException("no line break after a chunk");
      return n;
    }

    private String line() throws IOException {
      StringBuilder b = new StringBuilder();
      for (int c; (c = in.read()) != '\n'; ) {
        if (c < 0) throw new IOException("the body ended inside a chunk header");
        if (b.length() > 1024) throw new IOException("chunk header too long");
        if (c != '\r') b.append((char) c);
      }
      return b.toString();
    }
  }

  /**
   * The `--allow-origin` origin the current request came from, or null: its responses carry Access-Control-Allow-Origin
   * so the page can read them. Each connection is handled on its own thread (Serve.route sets and clears it).
   */
  static final ThreadLocal<String> CORS_ORIGIN = new ThreadLocal<>();

  static void respond(OutputStream out, int status, String type, byte[] body, String... extraHeaders) throws IOException {
    StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
    h.append("Content-Type: ").append(type).append("\r\n");
    h.append("Content-Length: ").append(body.length).append("\r\n");
    h.append("Cache-Control: no-store\r\n");
    h.append("X-Content-Type-Options: nosniff\r\n");
    h.append("Referrer-Policy: no-referrer\r\n");
    for (String e : extraHeaders) h.append(e).append("\r\n");
    String cors = CORS_ORIGIN.get();
    if (cors != null) h.append("Access-Control-Allow-Origin: ").append(cors).append("\r\nVary: Origin\r\n");
    h.append("Connection: close\r\n\r\n");
    out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }

  static void text(OutputStream out, int status, String body) throws IOException {
    respond(out, status, "text/plain; charset=utf-8", (body + "\n").getBytes(StandardCharsets.UTF_8));
  }

  static void json(OutputStream out, int status, String body) throws IOException {
    respond(out, status, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  private static String reason(int status) {
    return switch (status) {
      case 101 -> "Switching Protocols";
      case 200 -> "OK";
      case 204 -> "No Content";
      case 302 -> "Found";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 202 -> "Accepted";
      case 405 -> "Method Not Allowed";
      case 409 -> "Conflict";
      case 411 -> "Length Required";
      case 413 -> "Content Too Large";
      case 503 -> "Service Unavailable";
      default -> "Error";
    };
  }

  /** A JSON string literal. */
  static String q(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20 || c == '<' || c == '>' || c == '&') b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }
}
