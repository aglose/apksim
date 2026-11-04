package dev.apksim.dex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Translates one class_def of a dex file to a JVM class file. */
public final class ClassTranslator implements Opcodes {
  /**
   * Emit line numbers, local variables and Kotlin's SMAP, for a JVM debugger (`apksim --debug PORT`). Off otherwise:
   * the bigger classes cost ~30 ms of a warm launch to load, and ~5% more translation time.
   */
  static final boolean DEBUG_INFO = Boolean.getBoolean("apksim.debugInfo");
  /** -Dapksim.peephole=false writes methods as translated, for A/B runs (it changes the class cache's key too). */
  public static final boolean PEEPHOLE = !DEBUG_INFO && Boolean.parseBoolean(System.getProperty("apksim.peephole", "true"));
  private static final int VIS_RUNTIME = 1;
  private static final int VIS_SYSTEM = 2;

  private final DexFile dex;
  private final int def;
  private final Predicate<String> isInterface;
  private ClassWriter cw;
  private Set<String> split = Set.of();
  private String name;

  private final Map<Integer, Integer> fieldAnnots = new HashMap<>();
  private final Map<Integer, Integer> methodAnnots = new HashMap<>();
  private final Map<Integer, Integer> paramAnnots = new HashMap<>();
  private int classAnnots;

  private String signature;
  private String sourceDebug;
  private String outerClass;
  private String[] outerMethod;
  private String innerName;
  private int innerAccess = -1;
  private String[] memberClasses;
  private Map<String, Object> annotationDefaults = Map.of();

  public ClassTranslator(DexFile dex, int classDefIdx, Predicate<String> isInterface) {
    this.dex = dex;
    this.def = dex.classDefOffset(classDefIdx);
    this.isInterface = isInterface;
  }

  public byte[] translate() {
    Set<String> tooLarge = new HashSet<>();
    while (true) {
      try {
        return translateOnce(tooLarge);
      } catch (MethodTooLargeException e) {
        // Dalvik methods have no 64 KB limit. Redo the class, splitting this one.
        if (!tooLarge.add(e.getMethodName() + e.getDescriptor())) throw e;
      }
    }
  }

  private byte[] translateOnce(Set<String> split) {
    this.split = split;
    annotationDefaults = Map.of();
    memberClasses = null;
    signature = null;
    sourceDebug = null;
    outerClass = null;
    outerMethod = null;
    innerName = null;
    innerAccess = -1;
    fieldAnnots.clear();
    methodAnnots.clear();
    paramAnnots.clear();
    classAnnots = 0;
    cw = new ClassWriter(0);
    String desc = dex.type(dex.i32(def));
    name = desc.substring(1, desc.length() - 1);
    int access = dex.i32(def + 4) & 0xffff;
    int superIdx = dex.i32(def + 8);
    int ifaces = dex.i32(def + 12);
    int annotsDir = dex.i32(def + 20);
    int classData = dex.i32(def + 24);
    int staticValues = dex.i32(def + 28);

    readAnnotationDirectory(annotsDir);
    readSystemAnnotations();

    String sup = superIdx == -1 ? "java/lang/Object" : internal(dex.type(superIdx));
    sup = MethodTranslator.instantiated(sup);
    String[] interfaces = null;
    if (ifaces != 0) {
      String[] ts = dex.typeList(ifaces);
      interfaces = new String[ts.length];
      for (int i = 0; i < ts.length; i++) interfaces[i] = internal(ts[i]);
    }
    int classAccess = access & ~(ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC);
    if ((access & ACC_PROTECTED) != 0) classAccess |= ACC_PUBLIC;
    if ((access & ACC_INTERFACE) == 0) classAccess |= ACC_SUPER;
    cw.visit(V1_8, classAccess, name, signature, sup, interfaces);

    int src = dex.i32(def + 16);
    if (src != -1 || sourceDebug != null) cw.visitSource(src == -1 ? null : dex.string(src), sourceDebug);
    if (outerMethod != null) {
      cw.visitOuterClass(outerMethod[0], outerMethod[1], outerMethod[2]);
    } else if (outerClass != null && innerName == null && innerAccess != -1) {
      cw.visitOuterClass(outerClass, null, null);
    }
    if (innerAccess != -1) {
      boolean member = outerClass != null && innerName != null && outerMethod == null;
      cw.visitInnerClass(name, member ? outerClass : null, innerName, innerAccess & 0xffff);
    }
    if (memberClasses != null) {
      for (String m : memberClasses) {
        String im = internal(m);
        if (!im.startsWith(name + "$")) continue;
        cw.visitInnerClass(im, name, im.substring(name.length() + 1), ACC_PUBLIC | ACC_STATIC);
      }
    }
    if (classAnnots != 0) writeAnnotations(classAnnots, (d, v) -> cw.visitAnnotation(d, v));

    if (classData != 0) {
      DexFile.Cursor cur = dex.new Cursor(classData);
      int sf = cur.uleb();
      int inf = cur.uleb();
      int dm = cur.uleb();
      int vm = cur.uleb();
      Object[] statics = readStaticValues(staticValues);
      readFields(cur, sf, statics);
      readFields(cur, inf, null);
      readMethods(cur, dm);
      readMethods(cur, vm);
    }
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static String internal(String d) {
    return d.charAt(0) == 'L' ? d.substring(1, d.length() - 1) : d;
  }

  private void readFields(DexFile.Cursor cur, int n, Object[] statics) {
    int idx = 0;
    for (int i = 0; i < n; i++) {
      idx += cur.uleb();
      int access = cur.uleb() & 0xffff;
      DexFile.FieldRef f = dex.field(idx);
      Object value = statics != null && i < statics.length ? toConstant(statics[i], f.type()) : null;
      FieldVisitor fv = cw.visitField(access, f.name(), f.type(), fieldSignature(idx), value);
      Integer a = fieldAnnots.get(idx);
      if (a != null) writeAnnotations(a, fv::visitAnnotation);
      fv.visitEnd();
    }
  }

  private String fieldSignature(int fieldIdx) {
    Integer a = fieldAnnots.get(fieldIdx);
    return a == null ? null : signatureFrom(a);
  }

  private static Object toConstant(Object v, String type) {
    if (v == null) return null;
    char t = type.charAt(0);
    if (t == 'L' || t == '[') return v instanceof String ? v : null;
    if (v instanceof Boolean b) return b ? 1 : 0;
    if (v instanceof Byte || v instanceof Short) return ((Number) v).intValue();
    if (v instanceof Character c) return (int) c;
    if (isZero(v)) return null;
    return v;
  }

  private static boolean isZero(Object v) {
    if (v instanceof Integer i) return i == 0;
    if (v instanceof Long l) return l == 0;
    if (v instanceof Float f) return Float.floatToRawIntBits(f) == 0;
    if (v instanceof Double d) return Double.doubleToRawLongBits(d) == 0;
    return false;
  }

  private Object[] readStaticValues(int off) {
    if (off == 0) return new Object[0];
    DexFile.Cursor cur = dex.new Cursor(off);
    int size = cur.uleb();
    Object[] out = new Object[size];
    for (int i = 0; i < size; i++) out[i] = readValue(cur);
    return out;
  }

  private void readMethods(DexFile.Cursor cur, int n) {
    int idx = 0;
    for (int i = 0; i < n; i++) {
      idx += cur.uleb();
      int rawAccess = cur.uleb();
      int code = cur.uleb();
      int access = rawAccess & 0xffff;
      DexFile.MethodRef m = dex.method(idx);
      String sig = null;
      String[] exceptions = null;
      Integer annots = methodAnnots.get(idx);
      if (annots != null) {
        sig = signatureFrom(annots);
        exceptions = throwsFrom(annots);
      }
      // Buffered, so Peephole can shrink the code before it is written (not with debug info, which names the slots).
      org.objectweb.asm.tree.MethodNode node = PEEPHOLE
          ? new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ASM9, access, m.name(), m.descriptor(), sig, exceptions)
          : null;
      MethodVisitor mv = node != null ? node : cw.visitMethod(access, m.name(), m.descriptor(), sig, exceptions);
      if (annots != null) {
        writeAnnotations(annots, mv::visitAnnotation);
      }
      Object dflt = annotationDefaults.get(m.name());
      if (dflt != null) {
        AnnotationVisitor av = mv.visitAnnotationDefault();
        writeValue(av, null, dflt);
        av.visitEnd();
      }
      Integer pa = paramAnnots.get(idx);
      if (pa != null) writeParamAnnotations(pa, mv);
      if (code != 0) {
        try {
          boolean big = split.contains(m.name() + m.descriptor());
          new MethodTranslator(dex, mv, big ? cw : null, m.name(), isInterface, code, name, (access & ACC_STATIC) != 0, m.params(), m.ret()).translate();
        } catch (RuntimeException e) {
          throw new IllegalStateException("translating " + name + "." + m.name() + m.descriptor(), e);
        }
      } else {
        mv.visitEnd();
      }
      if (node != null) {
        Peephole.optimize(node);
        node.accept(cw);
      }
    }
  }

  // ---------------------------------------------------------------- annotations

  private void readAnnotationDirectory(int off) {
    if (off == 0) return;
    classAnnots = dex.i32(off);
    int fields = dex.i32(off + 4);
    int methods = dex.i32(off + 8);
    int params = dex.i32(off + 12);
    int p = off + 16;
    for (int i = 0; i < fields; i++, p += 8) fieldAnnots.put(dex.i32(p), dex.i32(p + 4));
    for (int i = 0; i < methods; i++, p += 8) methodAnnots.put(dex.i32(p), dex.i32(p + 4));
    for (int i = 0; i < params; i++, p += 8) paramAnnots.put(dex.i32(p), dex.i32(p + 4));
  }

  /** Item in an annotation_set at index i; returns {visibility, offset of encoded_annotation}. */
  private int[] setItem(int set, int i) {
    int off = dex.i32(set + 4 + i * 4);
    return new int[] {dex.u8(off), off + 1};
  }

  private void readSystemAnnotations() {
    if (classAnnots == 0) return;
    int n = dex.i32(classAnnots);
    for (int i = 0; i < n; i++) {
      int[] item = setItem(classAnnots, i);
      if (item[0] != VIS_SYSTEM) continue;
      DexFile.Cursor cur = dex.new Cursor(item[1]);
      String type = dex.type(cur.uleb());
      Map<String, Object> vals = readElements(cur);
      switch (type) {
        case "Ldalvik/annotation/Signature;" -> signature = joinSignature(vals.get("value"));
        // Kotlin's SMAP: lets a debugger map inlined functions' lines back to their own files.
        case "Ldalvik/annotation/SourceDebugExtension;" -> sourceDebug = DEBUG_INFO ? smap((String) vals.get("value")) : null;
        case "Ldalvik/annotation/EnclosingClass;" -> outerClass = internal(((Type) vals.get("value")).getDescriptor());
        case "Ldalvik/annotation/EnclosingMethod;" -> {
          if (vals.get("value") instanceof DexFile.MethodRef m) {
            outerClass = internal(m.owner());
            outerMethod = new String[] {outerClass, m.name(), m.descriptor()};
          }
        }
        case "Ldalvik/annotation/InnerClass;" -> {
          innerName = (String) vals.get("name");
          innerAccess = ((Number) vals.get("accessFlags")).intValue();
        }
        case "Ldalvik/annotation/MemberClasses;" -> {
          Object[] arr = (Object[]) vals.get("value");
          memberClasses = new String[arr.length];
          for (int j = 0; j < arr.length; j++) memberClasses[j] = ((Type) arr[j]).getDescriptor();
        }
        case "Ldalvik/annotation/AnnotationDefault;" -> {
          if (vals.get("value") instanceof Nested dflts) annotationDefaults = dflts.values();
        }
        default -> {
        }
      }
    }
  }

  /**
   * Kotlin sometimes maps lines from input line -1 (androidx.collection has 22 such classes), and JDI's SMAP
   * parser then throws for the whole class, so a debugger could not set breakpoints in it. Those lines map nothing.
   */
  private static String smap(String s) {
    return s != null && s.contains("\n-") ? s.replaceAll("(?m)^-\\d+[^\n]*\n", "") : s;
  }

  private String signatureFrom(int set) {
    int n = dex.i32(set);
    for (int i = 0; i < n; i++) {
      int[] item = setItem(set, i);
      if (item[0] != VIS_SYSTEM) continue;
      DexFile.Cursor cur = dex.new Cursor(item[1]);
      if ("Ldalvik/annotation/Signature;".equals(dex.type(cur.uleb()))) {
        return joinSignature(readElements(cur).get("value"));
      }
    }
    return null;
  }

  private String[] throwsFrom(int set) {
    int n = dex.i32(set);
    for (int i = 0; i < n; i++) {
      int[] item = setItem(set, i);
      if (item[0] != VIS_SYSTEM) continue;
      DexFile.Cursor cur = dex.new Cursor(item[1]);
      if ("Ldalvik/annotation/Throws;".equals(dex.type(cur.uleb()))) {
        Object[] arr = (Object[]) readElements(cur).get("value");
        String[] out = new String[arr.length];
        for (int j = 0; j < arr.length; j++) out[j] = internal(((Type) arr[j]).getDescriptor());
        return out;
      }
    }
    return null;
  }

  private static String joinSignature(Object value) {
    StringBuilder b = new StringBuilder();
    for (Object o : (Object[]) value) b.append(o);
    return b.toString();
  }

  private interface AnnotationSink {
    AnnotationVisitor visit(String descriptor, boolean visible);
  }

  private void writeAnnotations(int set, AnnotationSink sink) {
    int n = dex.i32(set);
    for (int i = 0; i < n; i++) {
      int[] item = setItem(set, i);
      if (item[0] != VIS_RUNTIME) continue;
      DexFile.Cursor cur = dex.new Cursor(item[1]);
      String type = dex.type(cur.uleb());
      AnnotationVisitor av = sink.visit(type, true);
      if (av != null) {
        writeValues(av, readElements(cur));
        av.visitEnd();
      }
    }
  }

  private void writeParamAnnotations(int listOff, MethodVisitor mv) {
    int n = dex.i32(listOff);
    for (int i = 0; i < n; i++) {
      int set = dex.i32(listOff + 4 + i * 4);
      if (set == 0) continue;
      final int param = i;
      writeAnnotations(set, (d, v) -> mv.visitParameterAnnotation(param, d, v));
    }
  }

  private Map<String, Object> readElements(DexFile.Cursor cur) {
    int size = cur.uleb();
    Map<String, Object> out = new HashMap<>();
    for (int i = 0; i < size; i++) {
      String key = dex.string(cur.uleb());
      out.put(key, readValue(cur));
    }
    return out;
  }

  /** A nested encoded annotation value. */
  private record Nested(String type, Map<String, Object> values) {}

  private record EnumConst(String type, String name) {}

  private Object readValue(DexFile.Cursor cur) {
    int header = cur.u1();
    int type = header & 0x1f;
    int arg = header >>> 5;
    switch (type) {
      case 0x00:
        return (byte) cur.u1();
      case 0x02:
        return (short) signed(cur, arg);
      case 0x03:
        return (char) unsigned(cur, arg);
      case 0x04:
        return (int) signed(cur, arg);
      case 0x06:
        return signed(cur, arg);
      case 0x10:
        return Float.intBitsToFloat((int) (unsigned(cur, arg) << (8 * (3 - arg))));
      case 0x11:
        return Double.longBitsToDouble(unsigned(cur, arg) << (8 * (7 - arg)));
      case 0x15:
      case 0x16:
        unsigned(cur, arg);
        return null;
      case 0x17:
        return dex.string((int) unsigned(cur, arg));
      case 0x18:
        return Type.getType(dex.type((int) unsigned(cur, arg)));
      case 0x19:
        return dex.field((int) unsigned(cur, arg));
      case 0x1a:
        return dex.method((int) unsigned(cur, arg));
      case 0x1b: {
        DexFile.FieldRef f = dex.field((int) unsigned(cur, arg));
        return new EnumConst(f.owner(), f.name());
      }
      case 0x1c: {
        int n = cur.uleb();
        Object[] arr = new Object[n];
        for (int i = 0; i < n; i++) arr[i] = readValue(cur);
        return arr;
      }
      case 0x1d: {
        String t = dex.type(cur.uleb());
        return new Nested(t, readElements(cur));
      }
      case 0x1e:
        return null;
      case 0x1f:
        return arg != 0;
      default:
        throw new IllegalStateException("bad encoded value 0x" + Integer.toHexString(type));
    }
  }

  private static long unsigned(DexFile.Cursor cur, int arg) {
    long v = 0;
    for (int i = 0; i <= arg; i++) v |= (long) cur.u1() << (8 * i);
    return v;
  }

  private static long signed(DexFile.Cursor cur, int arg) {
    long v = unsigned(cur, arg);
    int shift = 64 - 8 * (arg + 1);
    return v << shift >> shift;
  }

  private void writeValues(AnnotationVisitor av, Map<String, Object> vals) {
    for (Map.Entry<String, Object> e : vals.entrySet()) writeValue(av, e.getKey(), e.getValue());
  }

  private void writeValue(AnnotationVisitor av, String key, Object v) {
    if (v == null) return;
    if (v instanceof EnumConst c) {
      av.visitEnum(key, c.type(), c.name());
    } else if (v instanceof Nested n) {
      AnnotationVisitor sub = av.visitAnnotation(key, n.type());
      writeValues(sub, n.values());
      sub.visitEnd();
    } else if (v instanceof Object[] arr) {
      AnnotationVisitor sub = av.visitArray(key);
      for (Object o : arr) writeValue(sub, null, o);
      sub.visitEnd();
    } else if (v instanceof DexFile.FieldRef || v instanceof DexFile.MethodRef) {
      // Not representable in JVM annotations.
    } else {
      av.visit(key, v);
    }
  }
}
