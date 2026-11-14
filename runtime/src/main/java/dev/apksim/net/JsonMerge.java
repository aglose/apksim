package dev.apksim.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * JSON merge patch (RFC 7386) for the inspector's patch rules (docs/pr-preview.md). Not {@link Json}: a server's answer may carry
 * numbers a double cannot hold (64-bit ids), so numbers are kept as the text they came in ({@link Num}) and written back as
 * they were; objects keep their member order, a repeated name keeps its last value, and the output is compact.
 */
final class JsonMerge {
  /** A number as written. */
  record Num(String text) {}

  private static final int MAX_DEPTH = 200;
  private final String s;
  private int i;

  private JsonMerge(String s) {
    this.s = s;
  }

  /** The value of one JSON text: Map, List, String, Num, Boolean or null; an IllegalArgumentException says where it is wrong. */
  static Object parse(String text) {
    JsonMerge p = new JsonMerge(text);
    p.ws();
    Object v = p.value(0);
    p.ws();
    if (p.i != p.s.length()) throw p.error("trailing text");
    return v;
  }

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException("not JSON: " + what + " at offset " + i);
  }

  private void ws() {
    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
  }

  private Object value(int depth) {
    if (depth > MAX_DEPTH) throw error("nested too deep");
    if (i >= s.length()) throw error("ends early");
    char c = s.charAt(i);
    switch (c) {
      case '{' -> {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek('}')) return m;
        while (true) {
          ws();
          if (i >= s.length() || s.charAt(i) != '"') throw error("a member name is a string");
          String k = string();
          ws();
          if (!peek(':')) throw error("expected ':'");
          ws();
          m.put(k, value(depth + 1));
          ws();
          if (peek(',')) continue;
          if (peek('}')) return m;
          throw error("expected ',' or '}'");
        }
      }
      case '[' -> {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (peek(']')) return l;
        while (true) {
          ws();
          l.add(value(depth + 1));
          ws();
          if (peek(',')) continue;
          if (peek(']')) return l;
          throw error("expected ',' or ']'");
        }
      }
      case '"' -> {
        return string();
      }
      case 't' -> {
        return word("true", Boolean.TRUE);
      }
      case 'f' -> {
        return word("false", Boolean.FALSE);
      }
      case 'n' -> {
        return word("null", null);
      }
      default -> {
        return number();
      }
    }
  }

  private boolean peek(char c) {
    if (i < s.length() && s.charAt(i) == c) {
      i++;
      return true;
    }
    return false;
  }

  private Object word(String w, Object v) {
    if (!s.startsWith(w, i)) throw error("unexpected character");
    i += w.length();
    return v;
  }

  private Num number() {
    int start = i;
    if (i < s.length() && s.charAt(i) == '-') i++;
    int digits = i;
    if (i < s.length() && s.charAt(i) == '0') i++;
    else while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) i++;
    if (i == digits) throw error("unexpected character");
    if (i < s.length() && s.charAt(i) == '.') {
      i++;
      int f = i;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      if (i == f) throw error("a digit must follow '.'");
    }
    if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      i++;
      if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
      int f = i;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      if (i == f) throw error("a digit must follow the exponent");
    }
    return new Num(s.substring(start, i));
  }

  private String string() {
    i++;  // the opening quote
    StringBuilder b = new StringBuilder();
    while (true) {
      if (i >= s.length()) throw error("a string is not closed");
      char c = s.charAt(i++);
      if (c == '"') return b.toString();
      if (c < 0x20) throw error("a control character in a string");
      if (c != '\\') {
        b.append(c);
        continue;
      }
      if (i >= s.length()) throw error("a string is not closed");
      char e = s.charAt(i++);
      switch (e) {
        case '"', '\\', '/' -> b.append(e);
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'n' -> b.append('\n');
        case 'r' -> b.append('\r');
        case 't' -> b.append('\t');
        case 'u' -> {
          if (i + 4 > s.length()) throw error("a short \\u escape");
          try {
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          } catch (NumberFormatException x) {
            throw error("a bad \\u escape");
          }
          i += 4;
        }
        default -> throw error("a bad escape");
      }
    }
  }

  /** RFC 7386's MergePatch(target, patch): an object patch merges into an object target (null removes a member), anything else replaces. */
  @SuppressWarnings("unchecked")
  static Object merge(Object target, Object patch) {
    if (!(patch instanceof Map<?, ?> p)) return patch;
    Map<String, Object> t = target instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : ((Map<String, Object>) p).entrySet()) {
      if (e.getValue() == null) t.remove(e.getKey());
      else t.put(e.getKey(), merge(t.get(e.getKey()), e.getValue()));
    }
    return t;
  }

  /** Compact JSON text of a value from {@link #parse} or {@link #merge}. */
  static String write(Object v) {
    StringBuilder b = new StringBuilder();
    write(b, v);
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static void write(StringBuilder b, Object v) {
    if (v == null) {
      b.append("null");
    } else if (v instanceof String str) {
      b.append('"');
      for (int k = 0; k < str.length(); k++) {
        char c = str.charAt(k);
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
    } else if (v instanceof Num n) {
      b.append(n.text());
    } else if (v instanceof Map<?, ?> m) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
        if (!first) b.append(',');
        first = false;
        write(b, e.getKey());
        b.append(':');
        write(b, e.getValue());
      }
      b.append('}');
    } else if (v instanceof List<?> l) {
      b.append('[');
      for (int k = 0; k < l.size(); k++) {
        if (k > 0) b.append(',');
        write(b, l.get(k));
      }
      b.append(']');
    } else {
      b.append(v);
    }
  }
}
