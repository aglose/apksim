package dev.apksim.serve;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a request body: objects (Map), arrays (List), strings, numbers (Double), true/false/null.
 * Serve runs on a plain JVM with only apksim.jar, so no library.
 */
final class Json {
  private static final int MAX_DEPTH = 16;
  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  static Object parse(String s) throws IOException {
    Json j = new Json(s);
    Object v = j.value(0);
    j.space();
    if (j.i != s.length()) throw j.error("text after the value");
    return v;
  }

  private Object value(int depth) throws IOException {
    if (depth > MAX_DEPTH) throw error("nested too deeply");
    space();
    if (i >= s.length()) throw error("unexpected end");
    char c = s.charAt(i);
    switch (c) {
      case '{' -> {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        space();
        if (peek('}')) return m;
        do {
          space();
          if (i >= s.length() || s.charAt(i) != '"') throw error("expected a key");
          String k = string();
          space();
          expect(':');
          m.put(k, value(depth + 1));
          space();
        } while (peek(','));
        expect('}');
        return m;
      }
      case '[' -> {
        i++;
        List<Object> l = new ArrayList<>();
        space();
        if (peek(']')) return l;
        do {
          l.add(value(depth + 1));
          space();
        } while (peek(','));
        expect(']');
        return l;
      }
      case '"' -> {
        return string();
      }
      default -> {
        if (s.startsWith("true", i)) {
          i += 4;
          return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
          i += 5;
          return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
          i += 4;
          return null;
        }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        try {
          return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
          throw error("not a value");
        }
      }
    }
  }

  private String string() throws IOException {
    i++;  // the opening quote
    StringBuilder b = new StringBuilder();
    while (true) {
      if (i >= s.length()) throw error("unterminated string");
      char c = s.charAt(i++);
      if (c == '"') return b.toString();
      if (c != '\\') {
        b.append(c);
        continue;
      }
      if (i >= s.length()) throw error("unterminated string");
      char e = s.charAt(i++);
      switch (e) {
        case '"', '\\', '/' -> b.append(e);
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'n' -> b.append('\n');
        case 'r' -> b.append('\r');
        case 't' -> b.append('\t');
        case 'u' -> {
          if (i + 4 > s.length()) throw error("bad escape");
          try {
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          } catch (NumberFormatException x) {
            throw error("bad escape");
          }
          i += 4;
        }
        default -> throw error("bad escape");
      }
    }
  }

  private void space() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
  }

  private boolean peek(char c) {
    if (i < s.length() && s.charAt(i) == c) {
      i++;
      return true;
    }
    return false;
  }

  private void expect(char c) throws IOException {
    if (!peek(c)) throw error("expected '" + c + "'");
  }

  private IOException error(String what) {
    return new IOException("bad JSON at " + i + ": " + what);
  }
}
