package dev.apksim.rec;

import java.util.List;
import java.util.Map;

/** One-line JSON (a .jsonl record): maps, lists, strings, numbers, booleans and null. */
public final class JsonLine {
  private JsonLine() {}

  public static String write(Object v) {
    StringBuilder b = new StringBuilder();
    write(b, v);
    return b.toString();
  }

  private static void write(StringBuilder b, Object v) {
    if (v == null) {
      b.append("null");
    } else if (v instanceof String s) {
      quote(b, s);
    } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short) {
      b.append(v);
    } else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
      else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
      else b.append(d);
    } else if (v instanceof Map<?, ?> m) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) b.append(',');
        first = false;
        quote(b, String.valueOf(e.getKey()));
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
    } else if (v instanceof Object[] a) {
      write(b, java.util.Arrays.asList(a));
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
