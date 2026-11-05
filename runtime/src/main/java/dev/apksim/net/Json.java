package dev.apksim.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for HAR files: objects as LinkedHashMap, arrays as List, numbers as Double. */
final class Json {
  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  static Object parse(String text) {
    Json p = new Json(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != p.s.length()) throw p.error("trailing text");
    return v;
  }

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException("JSON: " + what + " at offset " + i);
  }

  private void ws() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
  }

  private Object value() {
    if (i >= s.length()) throw error("unexpected end");
    char c = s.charAt(i);
    switch (c) {
      case '{' -> {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (s.charAt(i) == '}') {
          i++;
          return m;
        }
        while (true) {
          ws();
          String k = string();
          ws();
          expect(':');
          ws();
          m.put(k, value());
          ws();
          if (s.charAt(i) == ',') {
            i++;
            continue;
          }
          expect('}');
          return m;
        }
      }
      case '[' -> {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (s.charAt(i) == ']') {
          i++;
          return l;
        }
        while (true) {
          ws();
          l.add(value());
          ws();
          if (s.charAt(i) == ',') {
            i++;
            continue;
          }
          expect(']');
          return l;
        }
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
        if (start == i) throw error("unexpected '" + c + "'");
        return Double.parseDouble(s.substring(start, i));
      }
    }
  }

  private void expect(char c) {
    if (i >= s.length() || s.charAt(i) != c) throw error("expected '" + c + "'");
    i++;
  }

  private String string() {
    expect('"');
    StringBuilder b = new StringBuilder();
    while (true) {
      if (i >= s.length()) throw error("unterminated string");
      char c = s.charAt(i++);
      if (c == '"') return b.toString();
      if (c != '\\') {
        b.append(c);
        continue;
      }
      char e = s.charAt(i++);
      switch (e) {
        case 'n' -> b.append('\n');
        case 't' -> b.append('\t');
        case 'r' -> b.append('\r');
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'u' -> {
          b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          i += 4;
        }
        default -> b.append(e);
      }
    }
  }

  static String write(Object v) {
    StringBuilder b = new StringBuilder();
    write(b, v, 0);
    return b.append('\n').toString();
  }

  private static void indent(StringBuilder b, int n) {
    b.append('\n');
    for (int k = 0; k < n; k++) b.append("  ");
  }

  @SuppressWarnings("unchecked")
  private static void write(StringBuilder b, Object v, int depth) {
    if (v == null) {
      b.append("null");
    } else if (v instanceof String str) {
      quote(b, str);
    } else if (v instanceof Boolean) {
      b.append(v);
    } else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
      else b.append(d);
    } else if (v instanceof Map<?, ?> m) {
      if (m.isEmpty()) {
        b.append("{}");
        return;
      }
      b.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
        if (!first) b.append(',');
        first = false;
        indent(b, depth + 1);
        quote(b, e.getKey());
        b.append(": ");
        write(b, e.getValue(), depth + 1);
      }
      indent(b, depth);
      b.append('}');
    } else if (v instanceof List<?> l) {
      if (l.isEmpty()) {
        b.append("[]");
        return;
      }
      b.append('[');
      for (int k = 0; k < l.size(); k++) {
        if (k > 0) b.append(',');
        indent(b, depth + 1);
        write(b, l.get(k), depth + 1);
      }
      indent(b, depth);
      b.append(']');
    } else {
      quote(b, v.toString());
    }
  }

  private static void quote(StringBuilder b, String s) {
    b.append('"');
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    b.append('"');
  }
}
