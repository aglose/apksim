package dev.apksim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Decodes Android's binary XML (AndroidManifest.xml inside an APK) into plain XML text. */
final class Axml {
  private static final int CHUNK_STRING_POOL = 0x0001;
  private static final int CHUNK_START_NS = 0x0100;
  private static final int CHUNK_START_TAG = 0x0102;
  private static final int CHUNK_END_TAG = 0x0103;
  private static final int TYPE_STRING = 0x03;
  private static final int TYPE_BOOL = 0x12;

  private Axml() {}

  /**
   * Bounded by its input, which may be a site's (beam's agent reads a build's package with it, outside any sandbox):
   * every length and offset is checked against the bytes there before anything is allocated, and what the strings
   * and the output may add up to is capped relative to the input's size (a real manifest stores each string once; a
   * crafted one could point a million entries, or attributes, at one long string). Throws IllegalArgumentException
   * for anything past those bounds.
   */
  static String decode(byte[] data) {
    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.position(8);
    long[] budget = {2L * data.length + 65_536};  // chars all strings together may decode to
    long outLimit = 16L * data.length + (1 << 20);
    List<String> strings = new ArrayList<>();
    StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    boolean first = true;
    while (b.remaining() >= 8) {
      int start = b.position();
      int type = b.getShort(start) & 0xffff;
      int size = b.getInt(start + 4);
      if (size <= 0) break;
      if (out.length() > outLimit) throw new IllegalArgumentException("the decoded manifest is too large");
      if (type == CHUNK_STRING_POOL) {
        readStrings(b, start, strings, budget);
      } else if (type == CHUNK_START_TAG) {
        int attrStart = start + 16 + (b.getShort(start + 16 + 8) & 0xffff);
        String name = str(strings, b.getInt(start + 16 + 4));
        int count = b.getShort(start + 16 + 12) & 0xffff;
        int attrSize = b.getShort(start + 16 + 10) & 0xffff;
        out.append('<').append(name);
        if (first) {
          out.append(" xmlns:android=\"http://schemas.android.com/apk/res/android\"");
          first = false;
        }
        for (int i = 0; i < count; i++) {
          int a = attrStart + i * attrSize;
          int ns = b.getInt(a);
          String attr = str(strings, b.getInt(a + 4));
          int rawString = b.getInt(a + 8);
          int dataType = b.get(a + 15) & 0xff;
          int value = b.getInt(a + 16);
          out.append(' ');
          if (ns != -1) out.append("android:");
          out.append(attr).append("=\"");
          if (rawString != -1) {
            out.append(escape(str(strings, rawString)));
          } else if (dataType == TYPE_STRING) {
            out.append(escape(str(strings, value)));
          } else if (dataType == TYPE_BOOL) {
            out.append(value != 0);
          } else if (dataType == 0x01 || dataType == 0x02) {
            out.append(dataType == 0x01 ? "@0x" : "?0x").append(Integer.toHexString(value));
          } else if (dataType == 0x11) {
            out.append("0x").append(Integer.toHexString(value));
          } else {
            out.append(value);
          }
          out.append('"');
          // Per attribute too: one tag may declare 65,535 of them, all on the same long string (a stride of 0).
          if (out.length() > outLimit) throw new IllegalArgumentException("the decoded manifest is too large");
        }
        out.append(">\n");
      } else if (type == CHUNK_END_TAG) {
        out.append("</").append(str(strings, b.getInt(start + 16 + 4))).append(">\n");
      } else if (type != CHUNK_START_NS && type != 0x0101 && type != 0x0180 && type != 0x0104) {
        // Unknown chunks are skipped by size.
      }
      b.position(start + size);
    }
    return out.toString();
  }

  private static String str(List<String> s, int i) {
    return i >= 0 && i < s.size() ? s.get(i) : "";
  }

  private static String escape(String v) {
    return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  private static void readStrings(ByteBuffer b, int start, List<String> out, long[] budget) {
    int count = b.getInt(start + 8);
    int flags = b.getInt(start + 16);
    int stringsStart = b.getInt(start + 20);
    boolean utf8 = (flags & (1 << 8)) != 0;
    if (count < 0 || (long) start + 28 + 4L * count > b.limit()) throw new IllegalArgumentException("bad string count " + count);
    for (int i = 0; i < count; i++) {
      long at = (long) start + stringsStart + b.getInt(start + 28 + i * 4);
      if (at < 0 || at >= b.limit()) throw new IllegalArgumentException("bad string offset");
      int p = (int) at;
      if (utf8) {
        p += (b.get(p) & 0x80) != 0 ? 2 : 1; // UTF-16 length
        int len = b.get(p) & 0xff;
        if ((len & 0x80) != 0) {
          len = ((len & 0x7f) << 8) | (b.get(p + 1) & 0xff);
          p += 2;
        } else {
          p += 1;
        }
        if ((long) p + len > b.limit() || (budget[0] -= len) < 0) throw new IllegalArgumentException("bad string length " + len);
        byte[] bytes = new byte[len];
        b.get(p, bytes);
        out.add(new String(bytes, StandardCharsets.UTF_8));
      } else {
        int len = b.getShort(p) & 0xffff;
        p += 2;
        if ((len & 0x8000) != 0) {
          len = ((len & 0x7fff) << 16) | (b.getShort(p) & 0xffff);
          p += 2;
        }
        if ((long) p + 2L * len > b.limit() || (budget[0] -= len) < 0) throw new IllegalArgumentException("bad string length " + len);
        char[] chars = new char[len];
        for (int c = 0; c < len; c++) chars[c] = (char) b.getShort(p + c * 2);
        out.add(new String(chars));
      }
    }
  }
}
