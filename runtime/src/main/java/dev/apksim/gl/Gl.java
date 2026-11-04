package dev.apksim.gl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real GLES 2/3 and EGL 1.4 implementation behind android.opengl's Java API (ShadowSimGLES20/30, ShadowSimEGL14),
 * for code that renders offscreen through it: a scene drawn into a pbuffer and read back, as a 3D viewer's device
 * test does. The library is ANGLE (GLES on Metal) on a Mac, from work/runtime/gles or the Android SDK's emulator
 * (emulator/lib64/gles_angle), and the system's libEGL/libGLESv2 (Mesa) on Linux. Calls go through the JDK's
 * foreign function API.
 *
 * <p>Used in test runs (Instrument), or with {@code -Dapksim.gles=true}; otherwise, and where no library is found, the
 * shadows fall back to Robolectric's stand-ins. A Mac window has no GLES surface to present to, so
 * eglCreateWindowSurface is not supported.
 */
public final class Gl {
  private Gl() {}

  private static final Linker LINKER = Linker.nativeLinker();
  private static volatile Boolean on;
  private static SymbolLookup egl;
  private static SymbolLookup gles;
  private static final ConcurrentHashMap<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

  /** Whether calls go to a real GLES/EGL. */
  public static boolean on() {
    Boolean o = on;
    if (o == null) {
      synchronized (Gl.class) {
        if (on == null) on = load();
        o = on;
      }
    }
    return o;
  }

  private static boolean load() {
    boolean wanted = System.getProperty("apksim.instrument") != null || Boolean.getBoolean("apksim.gles");
    if (!wanted || "false".equals(System.getProperty("apksim.gles"))) return false;
    boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
    List<Path> dirs = new ArrayList<>();
    String explicit = System.getProperty("apksim.gles.dir", System.getenv("APKSIM_GLES_DIR"));
    if (explicit != null) dirs.add(Path.of(explicit));
    if (System.getProperty("apksim.runtime") != null) dirs.add(Path.of(System.getProperty("apksim.runtime"), "gles"));
    for (String sdk : new String[] {System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + (mac ? "/Library/Android/sdk" : "/Android/Sdk")}) {
      if (sdk != null) dirs.add(Path.of(sdk, "emulator", "lib64", "gles_angle"));
    }
    String eglName = mac ? "libEGL.dylib" : "libEGL.so";
    String glesName = mac ? "libGLESv2.dylib" : "libGLESv2.so";
    Arena global = Arena.global();
    for (Path d : dirs) {
      if (Files.isRegularFile(d.resolve(eglName)) && Files.isRegularFile(d.resolve(glesName))) {
        try {
          egl = SymbolLookup.libraryLookup(d.resolve(eglName), global);
          gles = SymbolLookup.libraryLookup(d.resolve(glesName), global);
          System.err.println("[apksim] GLES: " + d);
          return true;
        } catch (RuntimeException e) {
          System.err.println("[apksim] GLES: could not load " + d + ": " + e);
        }
      }
    }
    if (!mac) {
      try {
        egl = SymbolLookup.libraryLookup("libEGL.so.1", global);
        gles = SymbolLookup.libraryLookup("libGLESv2.so.2", global);
        System.err.println("[apksim] GLES: the system's libEGL/libGLESv2");
        return true;
      } catch (RuntimeException e) {
        // not installed
      }
    }
    System.err.println("[apksim] GLES: no GLES library found (set -Dapksim.gles.dir); GL calls do nothing");
    return false;
  }

  // ---------------------------------------------------------------- calls

  /**
   * Calls a GLES (or, for names starting with "egl", EGL) function. [sig] is the C signature as letters, the return
   * first: V void, I int, F float, J long (64-bit integers, pointers passed as numbers), B byte (GLboolean), P
   * pointer (a MemorySegment).
   */
  public static Object call(String name, String sig, Object... args) {
    MethodHandle h = HANDLES.computeIfAbsent(name + sig, k -> handle(name, sig));
    try {
      return h.invokeWithArguments(args);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  private static MethodHandle handle(String name, String sig) {
    SymbolLookup lib = name.startsWith("egl") ? egl : gles;
    MemorySegment fn = lib.find(name).orElseThrow(() -> new UnsupportedOperationException("GLES has no " + name));
    MemoryLayout[] params = new MemoryLayout[sig.length() - 1];
    for (int i = 1; i < sig.length(); i++) params[i - 1] = layout(sig.charAt(i));
    FunctionDescriptor d = sig.charAt(0) == 'V' ? FunctionDescriptor.ofVoid(params) : FunctionDescriptor.of(layout(sig.charAt(0)), params);
    return LINKER.downcallHandle(fn, d);
  }

  private static MemoryLayout layout(char c) {
    return switch (c) {
      case 'I' -> ValueLayout.JAVA_INT;
      case 'F' -> ValueLayout.JAVA_FLOAT;
      case 'J' -> ValueLayout.JAVA_LONG;
      case 'B' -> ValueLayout.JAVA_BYTE;
      case 'P' -> ValueLayout.ADDRESS;
      default -> throw new IllegalArgumentException("signature letter " + c);
    };
  }

  // ---------------------------------------------------------------- arguments

  public static byte bool(boolean b) {
    return (byte) (b ? 1 : 0);
  }

  public static MemorySegment address(long a) {
    return MemorySegment.ofAddress(a);
  }

  public static MemorySegment cstr(Arena a, String s) {
    return s == null ? MemorySegment.NULL : a.allocateFrom(s);
  }

  public static MemorySegment cstrs(Arena a, String[] s) {
    if (s == null) return MemorySegment.NULL;
    MemorySegment arr = a.allocate(ValueLayout.ADDRESS, s.length);
    for (int i = 0; i < s.length; i++) arr.setAtIndex(ValueLayout.ADDRESS, i, a.allocateFrom(s[i]));
    return arr;
  }

  public static String string(Object ptr) {
    MemorySegment p = (MemorySegment) ptr;
    return p.equals(MemorySegment.NULL) ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
  }

  /** An array's elements from [offset] on, copied to native memory; [back] copies them back after the call. */
  public static final class Copy {
    final MemorySegment seg;
    private final Runnable back;

    Copy(MemorySegment seg, Runnable back) {
      this.seg = seg;
      this.back = back;
    }

    public MemorySegment seg() {
      return seg;
    }

    public void back() {
      if (back != null) back.run();
    }
  }

  public static Copy array(Arena a, Object arr, int offset) {
    if (arr == null) return new Copy(MemorySegment.NULL, null);
    if (arr instanceof int[] x) {
      MemorySegment s = a.allocate(ValueLayout.JAVA_INT, Math.max(1, x.length - offset));
      MemorySegment.copy(x, offset, s, ValueLayout.JAVA_INT, 0, x.length - offset);
      return new Copy(s, () -> MemorySegment.copy(s, ValueLayout.JAVA_INT, 0, x, offset, x.length - offset));
    }
    if (arr instanceof float[] x) {
      MemorySegment s = a.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, x.length - offset));
      MemorySegment.copy(x, offset, s, ValueLayout.JAVA_FLOAT, 0, x.length - offset);
      return new Copy(s, () -> MemorySegment.copy(s, ValueLayout.JAVA_FLOAT, 0, x, offset, x.length - offset));
    }
    if (arr instanceof long[] x) {
      MemorySegment s = a.allocate(ValueLayout.JAVA_LONG, Math.max(1, x.length - offset));
      MemorySegment.copy(x, offset, s, ValueLayout.JAVA_LONG, 0, x.length - offset);
      return new Copy(s, () -> MemorySegment.copy(s, ValueLayout.JAVA_LONG, 0, x, offset, x.length - offset));
    }
    if (arr instanceof byte[] x) {
      MemorySegment s = a.allocate(ValueLayout.JAVA_BYTE, Math.max(1, x.length - offset));
      MemorySegment.copy(x, offset, s, ValueLayout.JAVA_BYTE, 0, x.length - offset);
      return new Copy(s, () -> MemorySegment.copy(s, ValueLayout.JAVA_BYTE, 0, x, offset, x.length - offset));
    }
    if (arr instanceof boolean[] x) {
      // GLboolean is one byte.
      MemorySegment s = a.allocate(ValueLayout.JAVA_BYTE, Math.max(1, x.length - offset));
      for (int i = offset; i < x.length; i++) s.set(ValueLayout.JAVA_BYTE, i - offset, bool(x[i]));
      return new Copy(s, () -> {
        for (int i = offset; i < x.length; i++) x[i] = s.get(ValueLayout.JAVA_BYTE, i - offset) != 0;
      });
    }
    throw new IllegalArgumentException("array of " + arr.getClass());
  }

  /**
   * A buffer from its position on, as Android's GLES bindings pass it: a direct buffer's own memory, a heap buffer
   * copied out and back. [keep] allocates the copy for good, for pointers GL holds on to (client vertex arrays).
   */
  public static Copy buffer(Arena a, Buffer b, boolean keep) {
    if (b == null) return new Copy(MemorySegment.NULL, null);
    if (b.isDirect()) return new Copy(MemorySegment.ofBuffer(b), null);
    MemorySegment heap = MemorySegment.ofBuffer(b);
    MemorySegment s = (keep ? Arena.global() : a).allocate(Math.max(1, heap.byteSize()), 8);
    MemorySegment.copy(heap, 0, s, 0, heap.byteSize());
    return new Copy(s, b.isReadOnly() ? null : () -> MemorySegment.copy(s, 0, heap, 0, heap.byteSize()));
  }

  static int elementSize(Buffer b) {
    if (b instanceof ByteBuffer) return 1;
    if (b instanceof ShortBuffer || b instanceof CharBuffer) return 2;
    if (b instanceof IntBuffer || b instanceof FloatBuffer) return 4;
    if (b instanceof LongBuffer || b instanceof DoubleBuffer) return 8;
    return 1;
  }

  // ---------------------------------------------------------------- the calls the generated code does not cover

  public static void glShaderSource(int shader, String source) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment strings = a.allocate(ValueLayout.ADDRESS);
      strings.set(ValueLayout.ADDRESS, 0, a.allocateFrom(source));
      call("glShaderSource", "VIIPP", shader, 1, strings, MemorySegment.NULL);
    }
  }

  public static String infoLog(boolean program, int id) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment len = a.allocate(ValueLayout.JAVA_INT);
      call(program ? "glGetProgramiv" : "glGetShaderiv", "VIIP", id, 0x8B84 /* GL_INFO_LOG_LENGTH */, len);
      int n = len.get(ValueLayout.JAVA_INT, 0);
      if (n <= 0) return "";
      MemorySegment buf = a.allocate(n + 1);
      call(program ? "glGetProgramInfoLog" : "glGetShaderInfoLog", "VIIPP", id, n + 1, MemorySegment.NULL, buf);
      return buf.getString(0);
    }
  }

  public static String shaderSource(int shader) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment len = a.allocate(ValueLayout.JAVA_INT);
      call("glGetShaderiv", "VIIP", shader, 0x8B88 /* GL_SHADER_SOURCE_LENGTH */, len);
      int n = len.get(ValueLayout.JAVA_INT, 0);
      if (n <= 0) return "";
      MemorySegment buf = a.allocate(n + 1);
      call("glGetShaderSource", "VIIPP", shader, n + 1, MemorySegment.NULL, buf);
      return buf.getString(0);
    }
  }

  /** glGetActiveAttrib/glGetActiveUniform's String-returning form: the name, with size and type written out. */
  public static String active(boolean uniform, int program, int index, Object size, int sizeOffset, Object type, int typeOffset) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment max = a.allocate(ValueLayout.JAVA_INT);
      call("glGetProgramiv", "VIIP", program, uniform ? 0x8B87 : 0x8B8A /* ACTIVE_UNIFORM/ATTRIBUTE_MAX_LENGTH */, max);
      int n = Math.max(1, max.get(ValueLayout.JAVA_INT, 0));
      MemorySegment name = a.allocate(n + 1);
      Copy s = size instanceof Buffer b ? buffer(a, b, false) : array(a, size, sizeOffset);
      Copy t = type instanceof Buffer b ? buffer(a, b, false) : array(a, type, typeOffset);
      call(uniform ? "glGetActiveUniform" : "glGetActiveAttrib", "VIIIPPPP", program, index, n + 1, MemorySegment.NULL, s.seg(), t.seg(), name);
      s.back();
      t.back();
      return name.getString(0);
    }
  }
}
