package dev.apksim.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates AGSL to Metal Shading Language. The two are close: both are C-like with float2/half4 style
 * types. The trick that keeps this simple is putting the shader's functions inside a struct that holds
 * its uniforms, so functions see uniforms as members, exactly as they see globals in AGSL. The struct is
 * filled from a buffer with the same fields on entry to the fragment function.
 */
final class AgslTranslator {
  /** One uniform and where it lives in the Metal struct. */
  record Field(String name, String type, int offset, int size, boolean isInt) {}

  record Program(String msl, Map<String, Field> fields, int uniformBytes) {}

  /** The shader uses something this translator does not handle; it stays on the CPU. */
  static final class Unsupported extends Exception {
    Unsupported(String why) {
      super(why);
    }
  }

  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("half", "float"), Map.entry("half2", "float2"), Map.entry("half3", "float3"), Map.entry("half4", "float4"),
      Map.entry("vec2", "float2"), Map.entry("vec3", "float3"), Map.entry("vec4", "float4"),
      Map.entry("ivec2", "int2"), Map.entry("ivec3", "int3"), Map.entry("ivec4", "int4"),
      Map.entry("bvec2", "bool2"), Map.entry("bvec3", "bool3"), Map.entry("bvec4", "bool4"),
      Map.entry("mat2", "float2x2"), Map.entry("mat3", "float3x3"), Map.entry("mat4", "float4x4"),
      Map.entry("half2x2", "float2x2"), Map.entry("half3x3", "float3x3"), Map.entry("half4x4", "float4x4"),
      Map.entry("main", "agsl_main"), Map.entry("mod", "agsl_mod"));

  // Words that are ordinary identifiers in AGSL but reserved in Metal's C++.
  private static final Set<String> RESERVED = Set.of("this", "class", "template", "new", "delete", "operator", "public", "private",
      "protected", "namespace", "try", "catch", "throw", "auto", "friend", "register", "typename", "union", "using", "virtual",
      "explicit", "mutable", "vertex", "fragment", "kernel", "constant", "device", "thread", "threadgroup", "stage_in", "signed",
      "unsigned", "short", "long", "char", "double", "typedef", "extern", "goto", "sizeof", "static", "alignas", "alignof", "enum",
      "inline", "nullptr", "object", "mesh", "patch", "ray_data", "sampler", "texture");

  private static final String PRELUDE = """
      #include <metal_stdlib>
      using namespace metal;
      template<typename T, typename U> inline T agsl_mod(T x, U y) { return x - T(y) * floor(x / T(y)); }
      inline float atan(float y, float x) { return atan2(y, x); }
      inline float2 atan(float2 y, float2 x) { return atan2(y, x); }
      inline float3 atan(float3 y, float3 x) { return atan2(y, x); }
      inline float radians(float d) { return d * 0.017453292519943295; }
      inline float degrees(float r) { return r * 57.29577951308232; }
      inline float inversesqrt(float x) { return 1.0 / sqrt(x); }
      inline float length(float x) { return abs(x); }
      inline float distance(float a, float b) { return abs(a - b); }
      inline float dot(float a, float b) { return a * b; }
      inline float normalize(float a) { return sign(a); }
      inline float min(float a, int b) { return min(a, float(b)); }
      inline float min(int a, float b) { return min(float(a), b); }
      inline float max(float a, int b) { return max(a, float(b)); }
      inline float max(int a, float b) { return max(float(a), b); }
      inline float pow(float a, int b) { return pow(a, float(b)); }
      inline float clamp(float x, int a, int b) { return clamp(x, float(a), float(b)); }
      inline float clamp(float x, float a, int b) { return clamp(x, a, float(b)); }
      inline float clamp(float x, int a, float b) { return clamp(x, float(a), b); }
      inline float mix(float a, float b, int t) { return mix(a, b, float(t)); }
      inline float mix(int a, float b, float t) { return mix(float(a), b, t); }
      inline float mix(float a, int b, float t) { return mix(a, float(b), t); }
      inline float mix(int a, int b, float t) { return mix(float(a), float(b), t); }
      inline float step(int e, float x) { return step(float(e), x); }
      inline float step(float e, int x) { return step(e, float(x)); }
      inline float smoothstep(int a, int b, float x) { return smoothstep(float(a), float(b), x); }
      inline float smoothstep(float a, int b, float x) { return smoothstep(a, float(b), x); }
      inline float smoothstep(int a, float b, float x) { return smoothstep(float(a), b, x); }
      #define VEC_HELPERS(V) \\
        inline V min(V a, float b) { return min(a, V(b)); } \\
        inline V max(V a, float b) { return max(a, V(b)); } \\
        inline V min(V a, int b) { return min(a, V(float(b))); } \\
        inline V max(V a, int b) { return max(a, V(float(b))); } \\
        inline V clamp(V x, float a, float b) { return clamp(x, V(a), V(b)); } \\
        inline V clamp(V x, int a, int b) { return clamp(x, V(float(a)), V(float(b))); } \\
        inline V clamp(V x, float a, int b) { return clamp(x, V(a), V(float(b))); } \\
        inline V clamp(V x, int a, float b) { return clamp(x, V(float(a)), V(b)); } \\
        inline V mix(V a, V b, float t) { return mix(a, b, V(t)); } \\
        inline V mix(V a, V b, int t) { return mix(a, b, V(float(t))); } \\
        inline V pow(V a, float b) { return pow(a, V(b)); } \\
        inline V step(float e, V x) { return step(V(e), x); } \\
        inline V step(int e, V x) { return step(V(float(e)), x); } \\
        inline V smoothstep(float a, float b, V x) { return smoothstep(V(a), V(b), x); } \\
        inline V smoothstep(int a, int b, V x) { return smoothstep(V(float(a)), V(float(b)), x); } \\
        inline V smoothstep(float a, int b, V x) { return smoothstep(V(a), V(float(b)), x); } \\
        inline V smoothstep(int a, float b, V x) { return smoothstep(V(float(a)), V(b), x); }
      VEC_HELPERS(float2)
      VEC_HELPERS(float3)
      VEC_HELPERS(float4)
      template<typename T> inline auto lessThan(T a, T b) { return a < b; }
      template<typename T> inline auto lessThanEqual(T a, T b) { return a <= b; }
      template<typename T> inline auto greaterThan(T a, T b) { return a > b; }
      template<typename T> inline auto greaterThanEqual(T a, T b) { return a >= b; }
      template<typename T> inline auto equal(T a, T b) { return a == b; }
      template<typename T> inline auto notEqual(T a, T b) { return a != b; }
      inline float4 unpremul(float4 c) { return c.a > 0.0 ? float4(c.rgb / c.a, c.a) : c; }
      """;

  private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
  private static final Pattern PARAM_QUALIFIER = Pattern.compile("\\b(inout|out)\\s+(const\\s+)?([A-Za-z_][A-Za-z_0-9]*)\\s+([A-Za-z_][A-Za-z_0-9]*)");
  private static final Pattern PARAM_IN = Pattern.compile("([(,]\\s*)in\\s+");
  private static final Pattern PRECISION = Pattern.compile("\\b(lowp|mediump|highp|flat|noperspective)\\b");
  private static final Pattern UNIFORM = Pattern.compile(
      "^(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:layout\\s*\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z_0-9]*)\\s+(.*)$", Pattern.DOTALL);

  private AgslTranslator() {}

  static Program translate(String source) throws Unsupported {
    String src = stripComments(source);
    src = PRECISION.matcher(src).replaceAll("");
    List<String> statements = splitTopLevel(src);
    StringBuilder constants = new StringBuilder();
    StringBuilder structs = new StringBuilder();
    StringBuilder methods = new StringBuilder();
    StringBuilder uniforms = new StringBuilder();
    StringBuilder copies = new StringBuilder();
    Map<String, Field> fields = new HashMap<>();
    int offset = 0;
    boolean hasMain = false;
    for (String raw : statements) {
      String st = raw.trim();
      if (st.isEmpty()) continue;
      if (st.startsWith("#")) throw new Unsupported("preprocessor");
      Matcher u = UNIFORM.matcher(st);
      if (u.matches()) {
        String type = mapTokens(u.group(1));
        if (type.equals("shader") || type.equals("colorFilter") || type.equals("blender")) {
          throw new Unsupported("input " + type);
        }
        String names = u.group(2).replace(";", "");
        for (String n : names.split(",")) {
          String name = n.trim();
          int count = 1;
          int bracket = name.indexOf('[');
          if (bracket >= 0) {
            count = Integer.parseInt(name.substring(bracket + 1, name.indexOf(']')).trim());
            name = name.substring(0, bracket).trim();
          }
          int[] layout = layoutOf(type);
          offset = align(offset, layout[0]);
          fields.put(name, new Field(name, type, offset, layout[1] * count, type.startsWith("int") || type.startsWith("bool")));
          String member = rename(name);
          uniforms.append("  ").append(type).append(' ').append(member);
          if (count > 1) {
            uniforms.append('[').append(count).append(']');
            copies.append("  for (int i = 0; i < ").append(count).append("; i++) s.").append(member).append("[i] = u.").append(member).append("[i];\n");
          } else {
            copies.append("  s.").append(member).append(" = u.").append(member).append(";\n");
          }
          uniforms.append(";\n");
          offset += layout[1] * count;
        }
      } else if (st.startsWith("const ") && !st.contains("{")) {
        constants.append("constant ").append(mapTokens(st.substring(6))).append('\n');
      } else if (st.startsWith("struct ")) {
        structs.append(mapTokens(st)).append(st.endsWith(";") ? "\n" : ";\n");
      } else if (st.endsWith("}")) {
        String fn = translateParams(st);
        String mapped = mapTokens(fn);
        if (mapped.contains("agsl_main")) hasMain = true;
        methods.append(mapped).append('\n');
      } else if (!st.contains("(") || st.contains("=")) {
        // A non-const global. Metal has no mutable program scope, but struct members behave the same.
        methods.append(mapTokens(st)).append('\n');
      }
      // Anything else is a function prototype: not needed inside a struct.
    }
    if (!hasMain) throw new Unsupported("no main");
    String msl = PRELUDE + constants + structs
        + "struct Uniforms {\n" + uniforms + (fields.isEmpty() ? "  float unused;\n" : "") + "};\n"
        + "struct Shader {\n" + uniforms + methods + "};\n"
        + """
        struct ApksimV { float4 position [[position]]; };
        vertex ApksimV apksim_vertex(uint id [[vertex_id]]) {
          float2 p = float2(float((id << 1) & 2), float(id & 2));
          ApksimV o; o.position = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;
        }
        fragment float4 apksim_fragment(ApksimV in [[stage_in]], constant Uniforms& u [[buffer(0)]], constant float3* m [[buffer(1)]]) {
          Shader s;
        """ + copies + """
          float3 p = float3(in.position.xy, 1.0);
          return float4(s.agsl_main(float2(dot(m[0], p), dot(m[1], p))));
        }
        """;
    return new Program(msl, fields, offset);
  }

  private static String translateParams(String fn) {
    int open = fn.indexOf('(');
    int close = fn.indexOf(')');
    if (open < 0 || close < 0) return fn;
    String params = fn.substring(open, close + 1);
    params = PARAM_QUALIFIER.matcher(params).replaceAll("thread $3& $4");
    params = PARAM_IN.matcher(params).replaceAll("$1");
    return fn.substring(0, open) + params + fn.substring(close + 1);
  }

  private static String rename(String id) {
    return RESERVED.contains(id) ? id + "_" : id;
  }

  private static String mapTokens(String s) {
    Matcher m = TOKEN.matcher(s);
    StringBuilder b = new StringBuilder();
    int at = 0;
    while (m.find()) {
      // Skip suffixes of numbers such as 1e5 or 2.0f, and swizzles/members are harmless.
      if (m.start() > 0 && (Character.isDigit(s.charAt(m.start() - 1)) || s.charAt(m.start() - 1) == '.') && isNumberSuffix(s, m.start())) continue;
      b.append(s, at, m.start());
      String t = m.group();
      String mapped = TYPES.get(t);
      b.append(mapped != null ? mapped : (s.startsWith("thread", m.start()) || t.equals("constant") ? t : rename(t)));
      at = m.end();
    }
    return b.append(s.substring(at)).toString();
  }

  private static boolean isNumberSuffix(String s, int at) {
    char prev = s.charAt(at - 1);
    if (Character.isDigit(prev)) return true;
    // ".xy" on an identifier is a member; "1.f" is a number.
    return at >= 2 && Character.isDigit(s.charAt(at - 2));
  }

  /** {alignment, size} of a type in a Metal struct. */
  private static int[] layoutOf(String t) throws Unsupported {
    return switch (t) {
      case "float", "int" -> new int[] {4, 4};
      case "bool" -> new int[] {1, 1};
      case "float2", "int2" -> new int[] {8, 8};
      case "float3", "float4", "int3", "int4" -> new int[] {16, 16};
      case "float2x2" -> new int[] {8, 16};
      case "float3x3" -> new int[] {16, 48};
      case "float4x4" -> new int[] {16, 64};
      default -> throw new Unsupported("uniform type " + t);
    };
  }

  private static int align(int v, int a) {
    return (v + a - 1) / a * a;
  }

  private static String stripComments(String s) {
    return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
  }

  /** Splits into top-level declarations: up to a ';' at depth 0, or the close of a function body. */
  private static List<String> splitTopLevel(String s) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    boolean isStruct = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '{') {
        if (depth == 0) isStruct = s.substring(start, i).trim().startsWith("struct");
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0 && !isStruct) {
          out.add(s.substring(start, i + 1));
          start = i + 1;
        }
      } else if (c == ';' && depth == 0) {
        out.add(s.substring(start, i + 1));
        start = i + 1;
        isStruct = false;
      }
    }
    if (start < s.length()) out.add(s.substring(start));
    return out;
  }
}
