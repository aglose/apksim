package dev.apksim.dex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
  // Decoded strings and references by DEX index, filled as they are asked for and shared by every view of the same
  // bytes (a view's thread may decode an entry another already has: the objects are immutable, so a race only
  // costs a second decode). Flat arrays: one load per lookup, no boxed keys.
  private final String[] strings;
  private final Shared shared;

  private static final class Shared {
    volatile MethodRef[] methods;
    volatile FieldRef[] fields;
    volatile String[] internalTypes;
    volatile int[] defsByType;
  }

  public DexFile(ByteBuffer data) {
    this(data, null, null);
  }

  private DexFile(ByteBuffer data, String[] strings, Shared shared) {
    d = data.order(ByteOrder.LITTLE_ENDIAN);
    if (d.get(0) != 'd' || d.get(1) != 'e' || d.get(2) != 'x') throw new IllegalArgumentException("not a dex file");
    stringIds = i32(0x3c);
    typeIds = i32(0x44);
    protoIds = i32(0x4c);
    fieldIds = i32(0x54);
    methodIds = i32(0x5c);
    classDefsSize = i32(0x60);
    classDefs = i32(0x64);
    this.strings = strings != null ? strings : new String[i32(0x38)];
    this.shared = shared != null ? shared : new Shared();
    methodHandles = findMethodHandles();
  }

  /** Another reader of the same bytes, for use on another thread; it shares this one's decoded strings and references. */
  public DexFile view() {
    return new DexFile(d.duplicate(), strings, shared);
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

  /** The class definition index of {@code internalName} in this DEX, or -1; the type index is cached once, shared. */
  public int classDefIndex(String internalName) {
    int t = findClassType(internalName);
    if (t < 0) return -1;
    int[] all = shared.defsByType;
    if (all == null) shared.defsByType = all = classDefsByType();
    return all[t];
  }

  /** The superclass of class definition {@code def} as an internal name, or null (java.lang.Object's own, an interface's none). */
  public String superclassOf(int def) {
    int t = i32(classDefOffset(def) + 8);
    if (t == -1 || t == 0xFFFFFFFF) return null;
    String d = type(t);
    return d.substring(1, d.length() - 1);
  }

  /** The interfaces class definition {@code def} lists (an interface's superinterfaces), as internal names. */
  public String[] interfacesOf(int def) {
    int ifaces = i32(classDefOffset(def) + 12);
    if (ifaces == 0) return new String[0];
    String[] ts = typeList(ifaces);
    for (int i = 0; i < ts.length; i++) ts[i] = ts[i].substring(1, ts[i].length() - 1);
    return ts;
  }

  /** Whether class definition {@code def} declares a method with this name and descriptor. */
  public boolean declaresMethod(int def, String name, String descriptor) {
    int data = i32(classDefOffset(def) + 24);
    if (data == 0) return false;
    Cursor cur = new Cursor(data);
    int sf = cur.uleb();
    int inf = cur.uleb();
    int dm = cur.uleb();
    int vm = cur.uleb();
    for (int i = 0; i < sf + inf; i++) {
      cur.uleb();
      cur.uleb();
    }
    for (int n : new int[] {dm, vm}) {
      int idx = 0;
      for (int i = 0; i < n; i++) {
        idx += cur.uleb();
        cur.uleb();
        cur.uleb();
        MethodRef m = method(idx);
        if (m.name().equals(name) && m.descriptor().equals(descriptor)) return true;
      }
    }
    return false;
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

  /** A field reference; ownerInternal is the owner as an internal name (com/example/Foo). */
  public static final class FieldRef {
    private final String owner, type, name, ownerInternal;

    FieldRef(String owner, String type, String name) {
      this.owner = owner;
      this.type = type;
      this.name = name;
      ownerInternal = internal(owner);
    }

    public String owner() { return owner; }
    public String type() { return type; }
    public String name() { return name; }
    public String ownerInternal() { return ownerInternal; }

    @Override
    public String toString() {
      return owner + "." + name + ":" + type;
    }
  }

  public FieldRef field(int idx) {
    FieldRef[] all = shared.fields;
    if (all == null) shared.fields = all = new FieldRef[i32(0x50)];
    FieldRef f = all[idx];
    if (f != null) return f;
    int p = fieldIds + idx * 8;
    return all[idx] = new FieldRef(type(u16(p)), type(u16(p + 2)), string(i32(p + 4)));
  }

  /** A method reference, with its descriptor and the owner's internal name worked out once. */
  public static final class MethodRef {
    private final String owner, name, ret, descriptor, ownerInternal;
    private final String[] params;

    MethodRef(String owner, String name, String[] params, String ret) {
      this.owner = owner;
      this.name = name;
      this.params = params;
      this.ret = ret;
      descriptor = DexFile.descriptor(params, ret);
      ownerInternal = internal(owner);
    }

    public String owner() { return owner; }
    public String name() { return name; }
    public String[] params() { return params; }
    public String ret() { return ret; }
    public String descriptor() { return descriptor; }
    public String ownerInternal() { return ownerInternal; }

    @Override
    public String toString() {
      return owner + "." + name + descriptor;
    }
  }

  public MethodRef method(int idx) {
    MethodRef[] all = shared.methods;
    if (all == null) shared.methods = all = new MethodRef[i32(0x58)];
    MethodRef m = all[idx];
    if (m != null) return m;
    int p = methodIds + idx * 8;
    int proto = protoIds + u16(p + 2) * 12;
    return all[idx] = new MethodRef(type(u16(p)), string(i32(p + 4)), protoParams(proto), type(i32(proto + 4)));
  }

  /** Type [idx] as an internal name: com/example/Foo for Lcom/example/Foo;, an array's descriptor as it is. */
  public String internalType(int idx) {
    String[] all = shared.internalTypes;
    if (all == null) shared.internalTypes = all = new String[i32(0x40)];
    String t = all[idx];
    return t != null ? t : (all[idx] = internal(type(idx)));
  }

  static String internal(String desc) {
    return desc.charAt(0) == 'L' ? desc.substring(1, desc.length() - 1) : desc;
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
