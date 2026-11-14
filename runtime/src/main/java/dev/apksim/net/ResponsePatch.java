package dev.apksim.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * A patch rule's look at a real server's answer (issue 373, docs/pr-preview.md): a 2xx answer with a JSON content type, whole
 * or gzip-encoded, is parsed, merged with the rule's patch (RFC 7386) and written back compact, unencoded, with its own
 * Content-Length. Anything else, or a body over {@link #LIMIT}, is relayed as it came and says why in {@link Outcome#note}.
 */
final class ResponsePatch {
  /** The most of a body that is held to be patched (and, gzip-decoded, the most it may grow to). */
  static final int LIMIT = 8 * 1024 * 1024;

  private ResponsePatch() {}

  /**
   * What to relay: `resp` (its head, rewritten when the framing or the body changed) and `src`, where its body is read from
   * (the connection itself, or what was held); `patched`, or else `note`, why it was not.
   */
  static final class Outcome {
    NetEngine.Response resp;
    InputStream src;
    boolean patched;
    String note;
  }

  /** Why an answer's head rules patching out, or null. */
  static String headProblem(String method, NetEngine.Response r) {
    if (r.status < 200 || r.status > 299) return "status " + r.status + " is not 2xx";
    if (method.equals("HEAD") || r.status == 204 || r.status == 205) return "the answer has no body";
    String type = r.header("Content-Type");
    String media = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!(media.equals("application/json") || media.endsWith("+json"))) return "content type " + (type == null ? "none" : media) + " is not JSON";
    String enc = r.header("Content-Encoding");
    if (enc != null && !enc.equalsIgnoreCase("identity") && !enc.equalsIgnoreCase("gzip") && !enc.equalsIgnoreCase("x-gzip")) {
      return "content encoding " + enc.trim() + " is not decoded";
    }
    return null;
  }

  /** Reads the answer's body from `uin` (after the head) and patches it; never throws for a body that is wrong, only for a broken connection. */
  static Outcome apply(String method, NetEngine.Response resp, InputStream uin, Object patch, int ruleId) throws IOException {
    Outcome o = new Outcome();
    o.resp = resp;
    o.src = uin;
    String why = headProblem(method, resp);
    if (why != null) {
      o.note = why;
      return o;
    }
    String te = resp.header("Transfer-Encoding");
    String cl = resp.header("Content-Length");
    boolean chunked = te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
    byte[] raw;
    if (chunked) {
      ByteArrayOutputStream held = new ByteArrayOutputStream();
      while (true) {
        byte[] sizeLine = NetEngine.readLineBytes(uin);
        if (sizeLine == null) throw new IOException("chunked body ended early");
        String sz = NetEngine.trimEol(sizeLine);
        int semi = sz.indexOf(';');
        long size = Long.parseLong((semi < 0 ? sz : sz.substring(0, semi)).trim(), 16);
        if (size == 0) {
          while (true) {  // trailers are dropped, up to the blank line
            byte[] t = NetEngine.readLineBytes(uin);
            if (t == null) throw new IOException("chunked body ended early");
            if (NetEngine.trimEol(t).isEmpty()) break;
          }
          break;
        }
        if (held.size() + size > LIMIT) {
          // Over the limit: what was held goes on as one chunk, then this chunk's size line, and the connection after it.
          ByteArrayOutputStream prefix = new ByteArrayOutputStream();
          if (held.size() > 0) {
            prefix.write((Integer.toHexString(held.size()) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            held.writeTo(prefix);
            prefix.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
          }
          prefix.write(sizeLine);
          o.src = new SequenceInputStream(new ByteArrayInputStream(prefix.toByteArray()), uin);
          o.note = "the body is over " + LIMIT / (1024 * 1024) + " MB";
          return o;
        }
        held.write(uin.readNBytes((int) size));
        if (NetEngine.readLineBytes(uin) == null) throw new IOException("chunked body ended early");
      }
      raw = held.toByteArray();
    } else if (cl != null) {
      long n;
      try {
        n = Long.parseLong(cl.trim());
      } catch (NumberFormatException e) {
        o.note = "the Content-Length is not a number";
        return o;
      }
      if (n > LIMIT) {
        o.note = "the body is over " + LIMIT / (1024 * 1024) + " MB";
        return o;
      }
      raw = uin.readNBytes((int) n);
      if (raw.length != n) throw new IOException("the answer ended early");
    } else {
      raw = uin.readNBytes(LIMIT + 1);
      if (raw.length > LIMIT) {
        o.src = new SequenceInputStream(new ByteArrayInputStream(raw), uin);
        o.note = "the body is over " + LIMIT / (1024 * 1024) + " MB";
        return o;
      }
    }
    // The body is held whole: from here the answer is relayed from memory, with a Content-Length of its own.
    byte[] data = raw;
    String enc = resp.header("Content-Encoding");
    boolean gzip = enc != null && !enc.equalsIgnoreCase("identity");
    String problem = null;
    Object merged = null;
    if (gzip) {
      try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
        data = gz.readNBytes(LIMIT + 1);
        if (data.length > LIMIT) problem = "the decoded body is over " + LIMIT / (1024 * 1024) + " MB";
      } catch (IOException e) {
        problem = "the body is not valid gzip";
      }
    }
    if (problem == null) {
      try {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString();
        if (text.startsWith("﻿")) text = text.substring(1);
        merged = JsonMerge.merge(JsonMerge.parse(text), patch);
      } catch (CharacterCodingException e) {
        problem = "the body is not UTF-8";
      } catch (IllegalArgumentException e) {
        problem = "the body is not JSON";
      }
    }
    if (problem != null) {
      o.note = problem;
      o.src = new ByteArrayInputStream(raw);
      if (chunked) o.resp = rewritten(resp, raw.length, false, 0);
      return o;
    }
    byte[] out = JsonMerge.write(merged).getBytes(StandardCharsets.UTF_8);
    o.src = new ByteArrayInputStream(out);
    o.resp = rewritten(resp, out.length, true, ruleId);
    o.patched = true;
    return o;
  }

  /** The answer's head with a Content-Length for `length` bytes held in memory (and, patched, no Content-Encoding). */
  private static NetEngine.Response rewritten(NetEngine.Response r, int length, boolean patched, int ruleId) {
    NetEngine.Response n = new NetEngine.Response();
    n.status = r.status;
    n.text = r.text;
    StringBuilder h = new StringBuilder("HTTP/1.1 ").append(r.status).append(' ').append(r.text == null || r.text.isEmpty() ? Net.statusText(r.status) : r.text).append("\r\n");
    for (String[] x : r.headers) {
      String name = x[0];
      boolean drop = name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")
          || patched && (name.equalsIgnoreCase("Content-Encoding") || name.equalsIgnoreCase("Content-MD5") || name.equalsIgnoreCase("Digest"));
      if (drop) continue;
      n.headers.add(x);
      h.append(name).append(": ").append(x[1]).append("\r\n");
    }
    String[] len = {"Content-Length", String.valueOf(length)};
    n.headers.add(len);
    h.append(len[0]).append(": ").append(len[1]).append("\r\n");
    if (patched) {
      String[] mark = {"X-Apksim-Rule", String.valueOf(ruleId)};
      n.headers.add(mark);
      h.append(mark[0]).append(": ").append(mark[1]).append("\r\n");
    }
    n.head = h.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    return n;
  }
}
