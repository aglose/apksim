package dev.apksim.dex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A hash of one class's definition with every DEX index resolved to what it names, for {@code ClassCache}: the same
 * class in two builds of an app hashes the same even when a string was added somewhere in the file and every index
 * after it moved. It covers what {@link ClassTranslator} reads of the class itself: its header, the system and
 * runtime annotations of the class and its members, each field and method (names, types, access) with its code, the
 * static values and the source file name. Method code is walked instruction by instruction so that each index
 * operand (string, type, field, method, proto) is replaced by the name it stands for; branch offsets, registers and
 * literals are as they are, and the debug info is left out (the cache is not used for debug-info translations).
 *
 * <p>Anything this walk does not know how to read (an odex opcode, a call site or method handle, an instruction that
 * does not end where the code does) makes {@link #of} answer null, and the class is then keyed by its DEX file only.
 */
public final class ClassHash {
  private static final int LENGTHS_MAX = 0x100;
  /** Units per opcode, 0 for one this walk does not know. */
  private static final byte[] LEN = new byte[LENGTHS_MAX];

  static {
    int[][] ranges = {
      {0x00, 0x00, 1}, {0x01, 0x01, 1}, {0x02, 0x02, 2}, {0x03, 0x03, 3}, {0x04, 0x04, 1}, {0x05, 0x05, 2},
      {0x06, 0x06, 3}, {0x07, 0x07, 1}, {0x08, 0x08, 2}, {0x09, 0x09, 3}, {0x0a, 0x11, 1}, {0x12, 0x12, 1},
      {0x13, 0x13, 2}, {0x14, 0x14, 3}, {0x15, 0x16, 2}, {0x17, 0x17, 3}, {0x18, 0x18, 5}, {0x19, 0x1a, 2},
      {0x1b, 0x1b, 3}, {0x1c, 0x1c, 2}, {0x1d, 0x1e, 1}, {0x1f, 0x20, 2}, {0x21, 0x21, 1}, {0x22, 0x23, 2},
      {0x24, 0x26, 3}, {0x27, 0x28, 1}, {0x29, 0x29, 2}, {0x2a, 0x2c, 3}, {0x2d, 0x3d, 2}, {0x44, 0x6d, 2},
      {0x6e, 0x72, 3}, {0x74, 0x78, 3}, {0x7b, 0x8f, 1}, {0x90, 0xaf, 2}, {0xb0, 0xcf, 1}, {0xd0, 0xe2, 2},
      {0xfa, 0xfb, 4},
    };
    for (int[] r : ranges) for (int o = r[0]; o <= r[1]; o++) LEN[o] = (byte) r[2];
    // 0xfc/0xfd invoke-custom and 0xfe const-method-handle name call sites and handles: not walked (LEN 0).
    LEN[0xff] = 2; // const-method-type (proto index)
  }

  private ClassHash() {}

  /** Thrown inside the walk for anything it cannot read; {@link #of} turns it into null. */
  private static final class Unknown extends RuntimeException {
    Unknown(String why) {
      super(why, null, false, false);
    }
  }

  /** What {@link #of} hashes, for finding where two copies of a class differ (null as there). */
  public static byte[] preimage(DexFile dex, int def) {
    try {
      Walk w = new Walk(dex);
      w.classDef(dex.classDefOffset(def));
      return w.out.toByteArray();
    } catch (Unknown | IndexOutOfBoundsException | IllegalArgumentException e) {
      return null;
    }
  }

  /** The 16-byte hash of class definition {@code def} of {@code dex}, or null when it cannot be worked out. */
  public static byte[] of(DexFile dex, int def) {
    try {
      Walk w = new Walk(dex);
      w.classDef(dex.classDefOffset(def));
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(w.out.toByteArray());
      byte[] h = new byte[16];
      System.arraycopy(md.digest(), 0, h, 0, 16);
      return h;
    } catch (Unknown | IndexOutOfBoundsException | NoSuchAlgorithmException | IllegalArgumentException e) {
      return null;
    }
  }

  private static final class Walk {
    final DexFile dex;
    final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);

    Walk(DexFile dex) {
      this.dex = dex;
    }

    void i(int v) {
      out.write(v);
      out.write(v >>> 8);
      out.write(v >>> 16);
      out.write(v >>> 24);
    }

    void str(String s) {
      if (s == null) {
        i(-1);
        return;
      }
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      i(b.length);
      out.write(b, 0, b.length);
    }

    void tag(char c) {
      out.write(c);
    }

    void type(int idx) {
      str(dex.type(idx));
    }

    void proto(int idx) {
      String[] params = dex.protoParamsById(idx);
      i(params.length);
      for (String p : params) str(p);
      str(dex.protoReturnById(idx));
    }

    void field(int idx) {
      DexFile.FieldRef f = dex.field(idx);
      str(f.owner());
      str(f.name());
      str(f.type());
    }

    void method(int idx) {
      DexFile.MethodRef m = dex.method(idx);
      str(m.owner());
      str(m.name());
      str(m.descriptor());
    }

    void classDef(int def) {
      tag('C');
      type(dex.i32(def));
      i(dex.i32(def + 4));
      int sup = dex.i32(def + 8);
      if (sup == -1) i(-1);
      else type(sup);
      int ifaces = dex.i32(def + 12);
      if (ifaces == 0) {
        i(0);
      } else {
        String[] ts = dex.typeList(ifaces);
        i(ts.length);
        for (String t : ts) str(t);
      }
      int src = dex.i32(def + 16);
      str(src == -1 ? null : dex.string(src));
      annotationDirectory(dex.i32(def + 20));
      int data = dex.i32(def + 24);
      int statics = dex.i32(def + 28);
      tag('S');
      staticValues(statics);
      if (data != 0) classData(data);
    }

    void annotationDirectory(int off) {
      tag('A');
      if (off == 0) {
        i(0);
        return;
      }
      i(1);
      annotationSet(dex.i32(off));
      int fields = dex.i32(off + 4);
      int methods = dex.i32(off + 8);
      int params = dex.i32(off + 12);
      int p = off + 16;
      i(fields);
      for (int k = 0; k < fields; k++, p += 8) {
        field(dex.i32(p));
        annotationSet(dex.i32(p + 4));
      }
      i(methods);
      for (int k = 0; k < methods; k++, p += 8) {
        method(dex.i32(p));
        annotationSet(dex.i32(p + 4));
      }
      i(params);
      for (int k = 0; k < params; k++, p += 8) {
        method(dex.i32(p));
        int list = dex.i32(p + 4);
        int n = dex.i32(list);
        i(n);
        for (int j = 0; j < n; j++) annotationSet(dex.i32(list + 4 + j * 4));
      }
    }

    /** An annotation set, the translator's way: only system and runtime items matter (build ones are dropped). */
    void annotationSet(int set) {
      if (set == 0) {
        i(0);
        return;
      }
      int n = dex.i32(set);
      i(n);
      for (int k = 0; k < n; k++) {
        int off = dex.i32(set + 4 + k * 4);
        int vis = dex.u8(off);
        if (vis == 0) {
          i(-2); // VISIBILITY_BUILD: not read
          continue;
        }
        i(vis);
        DexFile.Cursor cur = dex.new Cursor(off + 1);
        annotation(cur);
      }
    }

    void annotation(DexFile.Cursor cur) {
      type(cur.uleb());
      int size = cur.uleb();
      i(size);
      for (int k = 0; k < size; k++) {
        str(dex.string(cur.uleb()));
        value(cur);
      }
    }

    void staticValues(int off) {
      if (off == 0) {
        i(0);
        return;
      }
      DexFile.Cursor cur = dex.new Cursor(off);
      int n = cur.uleb();
      i(n);
      for (int k = 0; k < n; k++) value(cur);
    }

    void value(DexFile.Cursor cur) {
      int header = cur.u1();
      int type = header & 0x1f;
      int arg = header >>> 5;
      out.write(type);
      switch (type) {
        case 0x00, 0x02, 0x03, 0x04, 0x06, 0x10, 0x11 -> {
          // numbers are the same bytes in every build
          out.write(arg);
          for (int k = 0; k <= arg; k++) out.write(cur.u1());
        }
        case 0x15, 0x16 -> throw new Unknown("method type/handle value");
        case 0x17 -> str(dex.string((int) index(cur, arg)));
        case 0x18 -> type((int) index(cur, arg));
        case 0x19, 0x1b -> field((int) index(cur, arg));
        case 0x1a -> method((int) index(cur, arg));
        case 0x1c -> {
          int n = cur.uleb();
          i(n);
          for (int k = 0; k < n; k++) value(cur);
        }
        case 0x1d -> annotation(cur);
        case 0x1e -> { }
        case 0x1f -> out.write(arg);
        default -> throw new Unknown("encoded value " + type);
      }
    }

    long index(DexFile.Cursor cur, int arg) {
      long v = 0;
      for (int k = 0; k <= arg; k++) v |= (long) cur.u1() << (8 * k);
      return v;
    }

    void classData(int off) {
      tag('D');
      DexFile.Cursor cur = dex.new Cursor(off);
      int sf = cur.uleb();
      int inf = cur.uleb();
      int dm = cur.uleb();
      int vm = cur.uleb();
      i(sf);
      i(inf);
      i(dm);
      i(vm);
      fields(cur, sf);
      fields(cur, inf);
      methods(cur, dm);
      methods(cur, vm);
    }

    void fields(DexFile.Cursor cur, int n) {
      int idx = 0;
      for (int k = 0; k < n; k++) {
        idx += cur.uleb();
        i(cur.uleb());
        field(idx);
      }
    }

    void methods(DexFile.Cursor cur, int n) {
      int idx = 0;
      for (int k = 0; k < n; k++) {
        idx += cur.uleb();
        int access = cur.uleb();
        int code = cur.uleb();
        i(access);
        method(idx);
        if (code == 0) {
          i(0);
        } else {
          i(1);
          code(code);
        }
      }
    }

    void code(int c) {
      i(dex.u16(c)); // registers
      i(dex.u16(c + 2)); // ins
      i(dex.u16(c + 4)); // outs
      int triesSize = dex.u16(c + 6);
      i(triesSize);
      int units = dex.i32(c + 12);
      i(units);
      int base = c + 16;
      int pc = 0;
      while (pc < units) {
        int u = dex.u16(base + pc * 2);
        int o = u & 0xff;
        if (o == 0 && u != 0) { // a payload: its units are as they are
          int size;
          switch (u) {
            case 0x0100 -> size = dex.u16(base + pc * 2 + 2) * 2 + 4;
            case 0x0200 -> size = dex.u16(base + pc * 2 + 2) * 4 + 2;
            case 0x0300 -> {
              int width = dex.u16(base + pc * 2 + 2);
              int n = dex.u16(base + pc * 2 + 4) | dex.u16(base + pc * 2 + 6) << 16;
              size = (n * width + 1) / 2 + 4;
            }
            default -> throw new Unknown("payload");
          }
          if (pc + size > units) throw new Unknown("payload size");
          tag('P');
          for (int k = 0; k < size; k++) i(dex.u16(base + (pc + k) * 2));
          pc += size;
          continue;
        }
        int len = LEN[o];
        if (len == 0 || pc + len > units) throw new Unknown("opcode " + o);
        int at = base + pc * 2;
        i(u);
        int tail = 1; // the first unit after the index operand's
        if (o == 0x1a) {
          str(dex.string(dex.u16(at + 2)));
          tail = 2;
        } else if (o == 0x1b) {
          str(dex.string(dex.i32(at + 2)));
          tail = 3;
        } else if (o == 0x1c || o == 0x1f || o == 0x20 || o == 0x22 || o == 0x23 || o == 0x24 || o == 0x25) {
          type(dex.u16(at + 2));
          tail = 2;
        } else if (o >= 0x52 && o <= 0x6d) {
          field(dex.u16(at + 2));
          tail = 2;
        } else if ((o >= 0x6e && o <= 0x72) || (o >= 0x74 && o <= 0x78)) {
          method(dex.u16(at + 2));
          tail = 2;
        } else if (o == 0xfa || o == 0xfb) {
          method(dex.u16(at + 2));
          i(dex.u16(at + 4));
          proto(dex.u16(at + 6));
          tail = 4;
        } else if (o == 0xff) {
          proto(dex.u16(at + 2));
          tail = 2;
        }
        for (int k = tail; k < len; k++) i(dex.u16(at + k * 2));
        pc += len;
      }
      if (pc != units) throw new Unknown("code does not end where it should");
      if (triesSize != 0) {
        int p = base + units * 2;
        if ((units & 1) != 0) p += 2;
        int handlers = p + triesSize * 8;
        for (int k = 0; k < triesSize; k++) {
          int e = p + k * 8;
          i(dex.i32(e));
          i(dex.u16(e + 4));
          DexFile.Cursor cur = dex.new Cursor(handlers + dex.u16(e + 6));
          int size = cur.sleb();
          i(size);
          for (int h = 0; h < Math.abs(size); h++) {
            type(cur.uleb());
            i(cur.uleb());
          }
          if (size <= 0) i(cur.uleb());
        }
      }
    }
  }
}
