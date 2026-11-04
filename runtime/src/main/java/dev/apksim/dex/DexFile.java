package dev.apksim.dex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only view of one .dex file. All offsets are as in the DEX format specification. The bytes are usually
 * mapped straight from the APK (DEX files are stored uncompressed), so they live in the page cache, not the heap.
 */
public final class DexFile {
  private final ByteBuffer d;
  private final int stringIds;
  private final int typeIds;
  private final int protoIds;
  private final int fieldIds;
  private final int methodIds;
  private final int methodHandles;
  public final int classDefs;
  public final int classDefsSize;
  private final String[] strings;

  public DexFile(ByteBuffer data) {
    d = data.order(ByteOrder.LITTLE_ENDIAN);
    if (d.get(0) != 'd' || d.get(1) != 'e' || d.get(2) != 'x') throw new IllegalArgumentException("not a dex file");
    stringIds = i32(0x3c);
    typeIds = i32(0x44);
    protoIds = i32(0x4c);
    fieldIds = i32(0x54);
    methodIds = i32(0x5c);
    classDefsSize = i32(0x60);
    classDefs = i32(0x64);
    strings = new String[i32(0x38)];
    methodHandles = findMethodHandles();
  }

  /** Another reader of the same bytes, with its own caches, for use on another thread. */
  public DexFile view() {
    return new DexFile(d.duplicate());
  }

  private int findMethodHandles() {
    int map = i32(0x34);
    int n = i32(map);
    for (int i = 0; i < n; i++) {
      int item = map + 4 + i * 12;
      if (u16(item) == 0x0008) return i32(item + 8);
    }
    return 0;
  }

  public int u8(int p) {
    return d.get(p) & 0xff;
  }

  public int u16(int p) {
    return d.getShort(p) & 0xffff;
  }

  public int i32(int p) {
    return d.getInt(p);
  }

  public String string(int idx) {
    String s = strings[idx];
    if (s != null) return s;
    int p = i32(stringIds + idx * 4);
    int chars = 0;
    int shift = 0;
    int b;
    do {
      b = d.get(p++);
      chars |= (b & 0x7f) << shift;
      shift += 7;
    } while (b < 0);
    s = decodeMutf8(p, chars);
    strings[idx] = s;
    return s;
  }

  private String decodeMutf8(int p, int chars) {
    boolean ascii = true;
    for (int i = 0; i < chars; i++) {
      if (d.get(p + i) <= 0) {
        ascii = false;
        break;
      }
    }
    if (ascii) {
      byte[] b = new byte[chars];
      d.get(p, b);
      return new String(b, 0, 0, chars);
    }
    char[] out = new char[chars];
    for (int i = 0; i < chars; i++) {
      int a = d.get(p++) & 0xff;
      if (a < 0x80) {
        out[i] = (char) a;
      } else if ((a & 0xe0) == 0xc0) {
        out[i] = (char) ((a & 0x1f) << 6 | (d.get(p++) & 0x3f));
      } else {
        int b = d.get(p++) & 0x3f;
        out[i] = (char) ((a & 0x0f) << 12 | b << 6 | (d.get(p++) & 0x3f));
      }
    }
    return new String(out);
  }

  /** Type descriptor, e.g. "Ljava/lang/String;" or "[I". */
  public String type(int idx) {
    return string(i32(typeIds + idx * 4));
  }

  public int classDefOffset(int idx) {
    return classDefs + idx * 32;
  }

  /**
   * For each type, its class definition here, or -1. Building it reads one int per class, where indexing every class
   * by name read and decoded each name (~80 ms for 20k classes).
   */
  public int[] classDefsByType() {
    int[] out = new int[i32(0x40)];
    java.util.Arrays.fill(out, -1);
    for (int i = 0; i < classDefsSize; i++) {
      int t = i32(classDefOffset(i));
      if (out[t] < 0) out[t] = i;
    }
    return out;
  }

  /**
   * The type index of class {@code internalName} ("a/b/C"), or -1. DEX keeps type_ids sorted by their descriptor
   * (string_ids are sorted by UTF-16 value), so this is a binary search that decodes no strings. Only absolute reads:
   * safe on any thread.
   */
  public int findClassType(String internalName) {
    int lo = 0;
    int hi = i32(0x40) - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int c = compareClassDescriptor(i32(typeIds + mid * 4), internalName);
      if (c < 0) lo = mid + 1; else if (c > 0) hi = mid - 1; else return mid;
    }
    return -1;
  }

  /** Compares string {@code idx} with "L" + name + ";" by UTF-16 values, as DEX sorts strings. */
  private int compareClassDescriptor(int idx, String name) {
    int p = i32(stringIds + idx * 4);
    while (d.get(p++) < 0) {
      // Skip the ULEB128 length.
    }
    int n = name.length() + 2;
    for (int i = 0; ; i++) {
      int a = d.get(p) & 0xff;
      if (a == 0) return i < n ? -1 : 0;
      if (i >= n) return 1;
      char c;
      if (a < 0x80) {
        c = (char) a;
        p++;
      } else if ((a & 0xe0) == 0xc0) {
        c = (char) ((a & 0x1f) << 6 | (d.get(p + 1) & 0x3f));
        p += 2;
      } else {
        c = (char) ((a & 0x0f) << 12 | (d.get(p + 1) & 0x3f) << 6 | (d.get(p + 2) & 0x3f));
        p += 3;
      }
      char q = i == 0 ? 'L' : i == n - 1 ? ';' : name.charAt(i - 1);
      if (c != q) return c < q ? -1 : 1;
    }
  }

  public String className(int classDefIdx) {
    return type(i32(classDefOffset(classDefIdx)));
  }

  public int classAccess(int classDefIdx) {
    return i32(classDefOffset(classDefIdx) + 4);
  }

  /** A field reference. */
  public record FieldRef(String owner, String type, String name) {}

  public FieldRef field(int idx) {
    int p = fieldIds + idx * 8;
    return new FieldRef(type(u16(p)), type(u16(p + 2)), string(i32(p + 4)));
  }

  /** A method reference. */
  public record MethodRef(String owner, String name, String[] params, String ret) {
    public String descriptor() {
      return DexFile.descriptor(params, ret);
    }
  }

  private final Map<Integer, MethodRef> methodCache = new HashMap<>();

  public MethodRef method(int idx) {
    MethodRef m = methodCache.get(idx);
    if (m != null) return m;
    int p = methodIds + idx * 8;
    int proto = protoIds + u16(p + 2) * 12;
    m = new MethodRef(type(u16(p)), string(i32(p + 4)), protoParams(proto), type(i32(proto + 4)));
    methodCache.put(idx, m);
    return m;
  }

  private String[] protoParams(int proto) {
    int off = i32(proto + 8);
    return off == 0 ? new String[0] : typeList(off);
  }

  public String[] protoParamsById(int protoIdx) {
    return protoParams(protoIds + protoIdx * 12);
  }

  public String protoReturnById(int protoIdx) {
    return type(i32(protoIds + protoIdx * 12 + 4));
  }

  public String[] typeList(int off) {
    int n = i32(off);
    String[] out = new String[n];
    for (int i = 0; i < n; i++) out[i] = type(u16(off + 4 + i * 2));
    return out;
  }

  public int methodHandleOffset(int idx) {
    return methodHandles + idx * 8;
  }

  public int callSiteOffset(int idx) {
    int map = i32(0x34);
    int n = i32(map);
    for (int i = 0; i < n; i++) {
      int item = map + 4 + i * 12;
      if (u16(item) == 0x0007) return i32(i32(item + 8) + idx * 4);
    }
    throw new IllegalStateException("no call sites");
  }

  public static String descriptor(String[] params, String ret) {
    StringBuilder b = new StringBuilder("(");
    for (String s : params) b.append(s);
    return b.append(')').append(ret).toString();
  }

  /** Sequential reader for ULEB128-based structures. */
  public final class Cursor {
    public int p;

    public Cursor(int at) {
      p = at;
    }

    public int uleb() {
      int r = 0;
      int s = 0;
      int b;
      do {
        b = d.get(p++);
        r |= (b & 0x7f) << s;
        s += 7;
      } while (b < 0);
      return r;
    }

    public int sleb() {
      int r = 0;
      int s = 0;
      int b;
      do {
        b = d.get(p++);
        r |= (b & 0x7f) << s;
        s += 7;
      } while (b < 0);
      if (s < 32 && (b & 0x40) != 0) r |= -1 << s;
      return r;
    }

    public int ulebP1() {
      return uleb() - 1;
    }

    public int u1() {
      return d.get(p++) & 0xff;
    }
  }
}
