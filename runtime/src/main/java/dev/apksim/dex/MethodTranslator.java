package dev.apksim.dex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Translates one Dalvik method body to JVM bytecode.
 *
 * <p>Each Dalvik register becomes one JVM local (wide values use the same register pair), and each
 * instruction becomes a load/operate/store sequence, so the JVM operand stack never survives an
 * instruction. The only real work is typing: Dalvik's `const` and `move` do not say whether a value
 * is an int, a float or null, while the JVM needs it for every load and store. A union-find over
 * values that flow together (ignoring registers dead at a merge) gives each value one JVM type;
 * where one constant really is used two ways, the use site converts the bits.
 */
final class MethodTranslator implements Opcodes {
  private static final int K_INT = 1;
  private static final int K_FLOAT = 2;
  private static final int K_LONG = 4;
  private static final int K_DOUBLE = 8;
  private static final int K_OBJ = 16;
  private static final int WIDE_HI = -2; // State marker: upper half of a wide register pair.
  private static final int UNSET = -1;

  // Locals of a split method's chunks: the register arrays, the block to run, then scratch.
  private static final int L_INTS = 0;
  private static final int L_LONGS = 1;
  private static final int L_FLOATS = 2;
  private static final int L_DOUBLES = 3;
  private static final int L_OBJECTS = 4;
  private static final int L_BLOCK = 5;
  private static final int L_TMP = 6;
  private static final int CHUNK_UNITS = 2500; // Dalvik code units per chunk; well under 64 KB of JVM code.
  private static final String CHUNK_DESC = "([I[J[F[D[Ljava/lang/Object;I)I";

  private final DexFile dex;
  private MethodVisitor mv;
  private final ClassVisitor splitInto; // Non-null if this method exceeds the JVM's size limit.
  private final String methodName;
  private boolean inChunk;
  private final Predicate<String> isInterface;
  private final int code;
  private final int regs;
  private final int insWords;
  private final int base; // JVM local of Dalvik register 0.
  private final boolean isStatic;
  private final String owner;
  private final String[] params;
  private final String ret;

  // Decoded instructions, indexed by instruction number.
  private int count;
  private int[] addr;
  private int[] op;
  private int[] a;
  private int[] b;
  private int[] c;
  private long[] lit;
  private int[][] args;
  private int[] addrToInsn;
  private final int unitCount;
  private final int insnsStart;

  private int[] blockOf;
  private int[] blockStart;
  private int blockCount;
  private int[][] normalSucc; // successors by flow (exception handlers aside)
  // Registers live into each block: block b's words at [b * liveWords, (b + 1) * liveWords).
  private long[] liveIn;
  private int liveWords;
  // The try covering each instruction (index into tries), or -1; per try, its handlers' blocks. DEX try ranges do
  // not overlap; if a method's did, tryAt is null and handlers are looked up range by range as before.
  private int[] tryAt;
  private int[][] tryHandlerBlocks;
  private int[][] blockState;
  private List<Try> tries = List.of();

  // Value nodes (union-find).
  private int[] parent = new int[64];
  private int[] kinds = new int[64];
  private boolean[] wide = new boolean[64];
  private String[] desc = new String[64];
  private int nodes;
  private int[] defNode;
  private boolean descChanged;

  private record Handler(String type, int addr) {}

  private record Try(int start, int end, List<Handler> handlers) {}

  /** A named Dalvik local from the debug info: register, name, JVM descriptor and generic signature, insn range. */
  private record Local(int reg, String name, String desc, String sig, int start, int end) {}

  private int[] lines; // Source line that starts at each instruction (0: none), or null without debug info.
  private List<Local> locals = List.of();
  private LocalRanges tracking; // While emitting a method with locals: told about every register def.

  MethodTranslator(DexFile dex, MethodVisitor mv, ClassVisitor splitInto, String methodName, Predicate<String> isInterface,
      int codeOff, String owner, boolean isStatic, String[] params, String ret) {
    this.dex = dex;
    this.mv = mv;
    this.splitInto = splitInto;
    this.methodName = methodName;
    this.isInterface = isInterface;
    this.code = codeOff;
    this.owner = owner;
    this.isStatic = isStatic;
    this.params = params;
    this.ret = ret;
    regs = dex.u16(codeOff);
    insWords = dex.u16(codeOff + 2);
    base = insWords;
    unitCount = dex.i32(codeOff + 12);
    insnsStart = codeOff + 16;
    debugTypes = DEBUG_TYPES != null && DEBUG_TYPES.equals(owner + "." + methodName);
  }

  void translate() {
    decode();
    readDebugInfo();
    readTries();
    buildBlocks();
    computeLiveness();
    analyze();
    if (splitInto != null) {
      emitSplit();
    } else {
      emit();
    }
  }

  // ---------------------------------------------------------------- decoding

  private int unit(int i) {
    return dex.u16(insnsStart + i * 2);
  }

  private int sunit(int i) {
    return (short) unit(i);
  }

  private void decode() {
    int cap = unitCount + 1;
    addr = new int[cap];
    op = new int[cap];
    a = new int[cap];
    b = new int[cap];
    c = new int[cap];
    lit = new long[cap];
    args = new int[cap][];
    addrToInsn = new int[unitCount + 1];
    Arrays.fill(addrToInsn, -1);
    int pc = 0;
    int n = 0;
    while (pc < unitCount) {
      int u = unit(pc);
      int o = u & 0xff;
      if (o == 0 && u != 0) { // Payload pseudo-instruction: skip.
        pc += payloadSize(pc, u);
        continue;
      }
      addr[n] = pc;
      op[n] = o;
      addrToInsn[pc] = n;
      int hi = u >>> 8;
      int len = decodeOne(n, o, hi, pc);
      pc += len;
      n++;
    }
    addr[n] = unitCount;
    addrToInsn[unitCount] = n;
    count = n;
  }

  private int payloadSize(int pc, int u) {
    switch (u) {
      case 0x0100:
        return unit(pc + 1) * 2 + 4;
      case 0x0200:
        return unit(pc + 1) * 4 + 2;
      case 0x0300: {
        int width = unit(pc + 1);
        int size = unit(pc + 2) | unit(pc + 3) << 16;
        return (size * width + 1) / 2 + 4;
      }
      default:
        throw new IllegalStateException("bad payload " + u);
    }
  }

  /** Fills a/b/c/lit for one instruction, returns its size in code units. */
  private int decodeOne(int n, int o, int hi, int pc) {
    int nibA = hi & 0xf;
    int nibB = hi >>> 4;
    if (o == 0x00 || o == 0x0e) return 1; // nop, return-void
    if (o == 0x01 || o == 0x04 || o == 0x07 || o == 0x21 || (o >= 0x7b && o <= 0x8f) || (o >= 0xb0 && o <= 0xcf)) {
      a[n] = nibA;
      b[n] = nibB;
      return 1; // 12x
    }
    if (o == 0x02 || o == 0x05 || o == 0x08) {
      a[n] = hi;
      b[n] = unit(pc + 1);
      return 2; // 22x
    }
    if (o == 0x03 || o == 0x06 || o == 0x09) {
      a[n] = unit(pc + 1);
      b[n] = unit(pc + 2);
      return 3; // 32x
    }
    if ((o >= 0x0a && o <= 0x0d) || (o >= 0x0f && o <= 0x11) || o == 0x1d || o == 0x1e || o == 0x27) {
      a[n] = hi;
      return 1; // 11x
    }
    switch (o) {
      case 0x12:
        a[n] = nibA;
        lit[n] = (hi << 24) >> 28;
        return 1;
      case 0x13:
      case 0x16:
        a[n] = hi;
        lit[n] = sunit(pc + 1);
        return 2;
      case 0x14:
      case 0x17:
        a[n] = hi;
        lit[n] = unit(pc + 1) | sunit(pc + 2) << 16;
        return 3;
      case 0x15:
        a[n] = hi;
        lit[n] = unit(pc + 1) << 16;
        return 2;
      case 0x18:
        a[n] = hi;
        lit[n] = (long) unit(pc + 1) | (long) unit(pc + 2) << 16 | (long) unit(pc + 3) << 32 | (long) unit(pc + 4) << 48;
        return 5;
      case 0x19:
        a[n] = hi;
        lit[n] = (long) sunit(pc + 1) << 48;
        return 2;
      case 0x1a:
      case 0x1c:
      case 0x1f:
      case 0x22:
      case 0xfe:
      case 0xff:
        a[n] = hi;
        b[n] = unit(pc + 1);
        return 2;
      case 0x1b:
        a[n] = hi;
        b[n] = unit(pc + 1) | unit(pc + 2) << 16;
        return 3;
      case 0x20:
      case 0x23:
        a[n] = nibA;
        b[n] = nibB;
        c[n] = unit(pc + 1);
        return 2;
      case 0x24:
      case 0x6e:
      case 0x6f:
      case 0x70:
      case 0x71:
      case 0x72:
      case 0xfc:
        return decode35c(n, hi, pc);
      case 0x25:
      case 0x74:
      case 0x75:
      case 0x76:
      case 0x77:
      case 0x78:
      case 0xfd:
        return decode3rc(n, hi, pc);
      case 0xfa: {
        int len = decode35c(n, hi, pc);
        lit[n] = unit(pc + 3);
        return len + 1;
      }
      case 0xfb: {
        int len = decode3rc(n, hi, pc);
        lit[n] = unit(pc + 3);
        return len + 1;
      }
      case 0x26:
      case 0x2b:
      case 0x2c:
        a[n] = hi;
        b[n] = pc + (unit(pc + 1) | sunit(pc + 2) << 16); // Payload address.
        return 3;
      case 0x28:
        a[n] = pc + (byte) hi; // Target.
        return 1;
      case 0x29:
        a[n] = pc + sunit(pc + 1);
        return 2;
      case 0x2a:
        a[n] = pc + (unit(pc + 1) | sunit(pc + 2) << 16);
        return 3;
      default:
        break;
    }
    if ((o >= 0x2d && o <= 0x31) || (o >= 0x44 && o <= 0x51) || (o >= 0x90 && o <= 0xaf)) {
      int u1 = unit(pc + 1);
      a[n] = hi;
      b[n] = u1 & 0xff;
      c[n] = u1 >>> 8;
      return 2; // 23x
    }
    if (o >= 0x32 && o <= 0x37) {
      a[n] = nibA;
      b[n] = nibB;
      c[n] = pc + sunit(pc + 1);
      return 2; // 22t
    }
    if (o >= 0x38 && o <= 0x3d) {
      a[n] = hi;
      c[n] = pc + sunit(pc + 1);
      return 2; // 21t
    }
    if (o >= 0x52 && o <= 0x5f) {
      a[n] = nibA;
      b[n] = nibB;
      c[n] = unit(pc + 1);
      return 2; // 22c
    }
    if (o >= 0x60 && o <= 0x6d) {
      a[n] = hi;
      b[n] = unit(pc + 1);
      return 2; // 21c
    }
    if (o >= 0xd0 && o <= 0xd7) {
      a[n] = nibA;
      b[n] = nibB;
      lit[n] = sunit(pc + 1);
      return 2; // 22s
    }
    if (o >= 0xd8 && o <= 0xe2) {
      int u1 = unit(pc + 1);
      a[n] = hi;
      b[n] = u1 & 0xff;
      lit[n] = (byte) (u1 >>> 8);
      return 2; // 22b
    }
    throw new IllegalStateException("unknown opcode 0x" + Integer.toHexString(o));
  }

  private int decode35c(int n, int hi, int pc) {
    int argc = hi >>> 4;
    int g = hi & 0xf;
    int idx = unit(pc + 1);
    int regsUnit = unit(pc + 2);
    int[] r = new int[argc];
    int[] all = {regsUnit & 0xf, regsUnit >>> 4 & 0xf, regsUnit >>> 8 & 0xf, regsUnit >>> 12 & 0xf, g};
    System.arraycopy(all, 0, r, 0, argc);
    args[n] = r;
    b[n] = idx;
    return 3;
  }

  private int decode3rc(int n, int hi, int pc) {
    int idx = unit(pc + 1);
    int first = unit(pc + 2);
    int[] r = new int[hi];
    for (int i = 0; i < hi; i++) r[i] = first + i;
    args[n] = r;
    b[n] = idx;
    return 3;
  }

  // ---------------------------------------------------------------- debug info

  /**
   * Runs the method's debug_info_item state machine: source lines per instruction, and the instruction ranges
   * where each named local is in its register. Parameters start at address 0 in the last registers. SET_FILE
   * (code inlined from another file) is ignored: a JVM method has one source file, and Kotlin's inline functions
   * are mapped through the class's SourceDebugExtension instead.
   */
  private void readDebugInfo() {
    int off = dex.i32(code + 8);
    if (off == 0 || !ClassTranslator.DEBUG_INFO) return;
    lines = new int[count + 1];
    String[] name = new String[regs];
    String[] desc = new String[regs];
    String[] sig = new String[regs];
    int[] from = new int[regs];
    boolean[] live = new boolean[regs];
    List<Local> out = new ArrayList<>();
    DexFile.Cursor cur = dex.new Cursor(off);
    int line = cur.uleb();
    int nParams = cur.uleb();
    int r = regs - insWords + (isStatic ? 0 : 1);
    for (int j = 0; j < nParams; j++) {
      int n = cur.ulebP1();
      if (j >= params.length || r >= regs) continue;
      if (n >= 0) {
        name[r] = dex.string(n);
        desc[r] = params[j];
        live[r] = true;
      }
      r += wideParam(params[j]) ? 2 : 1;
    }
    int address = 0;
    loop:
    while (true) {
      int o = cur.u1();
      switch (o) {
        case 0x00 -> {
          break loop;
        }
        case 0x01 -> address += cur.uleb();
        case 0x02 -> line += cur.sleb();
        case 0x03, 0x04 -> {
          int reg = cur.uleb();
          int n = cur.ulebP1();
          int t = cur.ulebP1();
          int g = o == 0x04 ? cur.ulebP1() : -1;
          if (reg >= regs) continue;
          endLocal(out, reg, address, name, desc, sig, from, live);
          name[reg] = n >= 0 ? dex.string(n) : null;
          desc[reg] = t >= 0 ? dex.type(t) : null;
          sig[reg] = g >= 0 ? dex.string(g) : null;
          from[reg] = address;
          live[reg] = name[reg] != null && desc[reg] != null;
        }
        case 0x05 -> {
          int reg = cur.uleb();
          if (reg < regs) endLocal(out, reg, address, name, desc, sig, from, live);
        }
        case 0x06 -> {
          int reg = cur.uleb();
          if (reg >= regs || live[reg] || name[reg] == null || desc[reg] == null) continue;
          from[reg] = address;
          live[reg] = true;
        }
        case 0x07, 0x08 -> {
        }
        case 0x09 -> cur.ulebP1();
        default -> {
          int adj = o - 0x0a;
          line += adj % 15 - 4;
          address += adj / 15;
          int i = insnFrom(address);
          if (i < count && line > 0 && line <= 0xffff) lines[i] = line;
        }
      }
    }
    for (int reg = 0; reg < regs; reg++) endLocal(out, reg, unitCount, name, desc, sig, from, live);
    out.sort((x, y) -> Integer.compare(x.start, y.start));
    locals = out;
  }

  private void endLocal(List<Local> out, int reg, int address, String[] name, String[] desc, String[] sig, int[] from,
      boolean[] live) {
    if (!live[reg]) return;
    live[reg] = false;
    int s = insnFrom(from[reg]);
    int e = insnFrom(address);
    if (s < e) out.add(new Local(reg, name[reg], desc[reg], sig[reg], s, e));
  }

  /** The instruction at this address, or the next one (debug addresses may point at a payload), or `count`. */
  private int insnFrom(int address) {
    if (address >= unitCount) return count;
    while (addrToInsn[address] < 0) address++;
    return addrToInsn[address];
  }

  /**
   * Turns the Dalvik locals into LocalVariableTable entries. A register's JVM local holds whatever kind its
   * current value resolved to, so an entry covers only the instructions where that kind matches the declared
   * type: a debugger must never read an int slot as a reference. A local is re-checked only where its register
   * may have changed (a def nearby, or a block entry): checking every live local at every instruction made locals
   * cost 1.5x as much to translate in Compose code, which keeps dozens live.
   */
  private final class LocalRanges {
    private final Local[] ls = locals.toArray(new Local[0]);
    private final Label[] labels;
    private final int[] kind = new int[ls.length];
    private final int[] open = new int[ls.length];
    private final int[] onReg = new int[regs]; // The local whose range covers each register now, or -1.
    private final int[] startHead = new int[count + 1]; // Locals starting / ending at each insn: linked lists.
    private final int[] endHead = new int[count + 1];
    private final int[] nextStart = new int[ls.length];
    private final int[] nextEnd = new int[ls.length];
    private int[] dirty = new int[8];
    private int nDirty;

    LocalRanges(Label[] labels) {
      this.labels = labels;
      Arrays.fill(open, -1);
      Arrays.fill(onReg, -1);
      Arrays.fill(startHead, -1);
      Arrays.fill(endHead, -1);
      for (int l = ls.length - 1; l >= 0; l--) {
        kind[l] = kindOf(ls[l].desc);
        nextStart[l] = startHead[ls[l].start];
        startHead[ls[l].start] = l;
        nextEnd[l] = endHead[ls[l].end];
        endHead[ls[l].end] = l;
      }
    }

    /** A def of register r: it may also have cut a wide pair around it. */
    void dirty(int r) {
      if (nDirty == dirty.length) dirty = Arrays.copyOf(dirty, nDirty * 2);
      dirty[nDirty++] = r;
    }

    /**
     * Called after labels[i] is placed, with the register state s before instruction i (null if unreachable:
     * dead code emits nothing, so it neither starts nor ends a range).
     */
    void at(int i, int[] s, boolean blockStart) {
      for (int l = endHead[i]; l >= 0; l = nextEnd[l]) {
        close(l, i);
        if (onReg[ls[l].reg] == l) onReg[ls[l].reg] = -1;
      }
      for (int l = startHead[i]; l >= 0; l = nextStart[l]) {
        onReg[ls[l].reg] = l;
        if (s != null && !blockStart) check(l, i, s);
      }
      if (s != null && blockStart) {
        for (int r = 0; r < regs; r++) {
          if (onReg[r] >= 0) check(onReg[r], i, s);
        }
      } else if (s != null) {
        for (int d = 0; d < nDirty; d++) {
          for (int r = Math.max(0, dirty[d] - 1); r <= dirty[d] + 2 && r < regs; r++) {
            if (onReg[r] >= 0) check(onReg[r], i, s);
          }
        }
      }
      nDirty = 0;
    }

    private void check(int l, int i, int[] s) {
      int v = s[ls[l].reg];
      if (v >= 0 && resolved(v) == kind[l]) {
        if (open[l] < 0) open[l] = i;
      } else {
        close(l, i);
      }
    }

    /** Writes the open range of local l up to instruction i (ASM reads both labels' offsets at once). */
    private void close(int l, int i) {
      int from = open[l];
      if (from < 0) return;
      open[l] = -1;
      Local x = ls[l];
      if (from < i) mv.visitLocalVariable(x.name, x.desc, x.sig, labels[from], labels[i], slot(x.reg));
    }

    /** Called once labels[count] is placed. */
    void finish() {
      at(count, null, false);
    }
  }

  private void readTries() {
    int triesSize = dex.u16(code + 6);
    if (triesSize == 0) return;
    int p = insnsStart + unitCount * 2;
    if ((unitCount & 1) != 0) p += 2;
    int handlersBase = p + triesSize * 8;
    tries = new ArrayList<>(triesSize);
    for (int i = 0; i < triesSize; i++) {
      int e = p + i * 8;
      int start = dex.i32(e);
      int end = start + dex.u16(e + 4);
      DexFile.Cursor cur = dex.new Cursor(handlersBase + dex.u16(e + 6));
      int size = cur.sleb();
      List<Handler> hs = new ArrayList<>();
      for (int h = 0; h < Math.abs(size); h++) {
        String type = dex.type(cur.uleb());
        hs.add(new Handler(type, cur.uleb()));
      }
      if (size <= 0) hs.add(new Handler(null, cur.uleb()));
      tries.add(new Try(start, end, hs));
    }
  }

  // ---------------------------------------------------------------- control flow

  private boolean isGoto(int o) {
    return o >= 0x28 && o <= 0x2a;
  }

  private boolean isReturnOrThrow(int o) {
    return (o >= 0x0e && o <= 0x11) || o == 0x27;
  }

  private boolean isIf(int o) {
    return o >= 0x32 && o <= 0x3d;
  }

  private boolean isSwitch(int o) {
    return o == 0x2b || o == 0x2c;
  }

  private int[] switchTargets(int i) {
    int pc = addr[i];
    int p = b[i];
    int size = unit(p + 1);
    int[] out = new int[size];
    int tableAt = op[i] == 0x2b ? p + 4 : p + 2 + size * 2;
    for (int j = 0; j < size; j++) {
      out[j] = pc + (unit(tableAt + j * 2) | sunit(tableAt + j * 2 + 1) << 16);
    }
    return out;
  }

  private int insnAt(int address) {
    int i = addrToInsn[address];
    if (i < 0) throw new IllegalStateException("branch into middle of instruction @" + address);
    return i;
  }

  private void buildBlocks() {
    BitSet leaders = new BitSet(count + 1);
    leaders.set(0);
    for (int i = 0; i < count; i++) {
      int o = op[i];
      if (isGoto(o)) {
        leaders.set(insnAt(a[i]));
        leaders.set(i + 1);
      } else if (isIf(o)) {
        leaders.set(insnAt(c[i]));
        leaders.set(i + 1);
      } else if (isSwitch(o)) {
        for (int t : switchTargets(i)) leaders.set(insnAt(t));
        leaders.set(i + 1);
      } else if (isReturnOrThrow(o)) {
        leaders.set(i + 1);
      }
    }
    for (Try t : tries) {
      if (addrToInsn[t.start] >= 0) leaders.set(addrToInsn[t.start]);
      if (t.end <= unitCount && addrToInsn[t.end] >= 0) leaders.set(addrToInsn[t.end]);
      for (Handler h : t.handlers) {
        int at = insnAt(h.addr);
        leaders.set(at);
        // A split method resumes after a handler's move-exception, possibly from another chunk.
        if (op[at] == 0x0d) leaders.set(at + 1);
      }
    }
    blockOf = new int[count + 1];
    int[] starts = new int[leaders.cardinality() + 1];
    int bc = 0;
    for (int i = 0; i < count; i++) {
      if (leaders.get(i)) starts[bc++] = i;
      blockOf[i] = bc - 1;
    }
    starts[bc] = count;
    blockStart = starts;
    blockCount = bc;
    normalSucc = new int[bc][];
    for (int bl = 0; bl < bc; bl++) {
      int last = blockStart[bl + 1] - 1;
      int o = op[last];
      if (isGoto(o)) {
        normalSucc[bl] = new int[] {blockOf[insnAt(a[last])]};
      } else if (isIf(o)) {
        normalSucc[bl] = new int[] {blockOf[insnAt(c[last])], blockOf[last + 1]};
      } else if (isSwitch(o)) {
        int[] targets = switchTargets(last);
        int[] s = new int[targets.length + 1];
        for (int k = 0; k < targets.length; k++) s[k] = blockOf[insnAt(targets[k])];
        s[targets.length] = blockOf[last + 1];
        normalSucc[bl] = s;
      } else if (!isReturnOrThrow(o) && last + 1 < count) {
        normalSucc[bl] = new int[] {blockOf[last + 1]};
      } else {
        normalSucc[bl] = NONE;
      }
    }
    indexTries();
  }

  private static final int[] NONE = {};

  /** Fills tryAt and tryHandlerBlocks: one pass over each try's instructions (addr is ascending). */
  private void indexTries() {
    if (tries.isEmpty()) return;
    tryAt = new int[count];
    Arrays.fill(tryAt, -1);
    tryHandlerBlocks = new int[tries.size()][];
    for (int t = 0; t < tries.size(); t++) {
      Try tr = tries.get(t);
      int[] hb = new int[tr.handlers.size()];
      for (int k = 0; k < hb.length; k++) hb[k] = blockOf[insnAt(tr.handlers.get(k).addr)];
      tryHandlerBlocks[t] = hb;
      int lo = 0, hi = count;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (addr[mid] < tr.start) lo = mid + 1;
        else hi = mid;
      }
      for (int i = lo; i < count && addr[i] < tr.end; i++) {
        if (tryAt[i] >= 0) {
          tryAt = null; // Overlapping ranges: not valid DEX, but keep the general lookup for it.
          return;
        }
        tryAt[i] = t;
      }
    }
  }

  /** Handler blocks instruction i may continue at if it throws (it must be able to: canThrow), or null. */
  private int[] handlersOf(int i) {
    if (tries.isEmpty()) return null;
    if (tryAt != null) return tryAt[i] < 0 ? null : tryHandlerBlocks[tryAt[i]];
    List<Integer> hs = new ArrayList<>(1);
    for (Try t : tries) {
      if (addr[i] >= t.start && addr[i] < t.end) {
        for (Handler h : t.handlers) hs.add(blockOf[insnAt(h.addr)]);
      }
    }
    if (hs.isEmpty()) return null;
    int[] out = new int[hs.size()];
    for (int k = 0; k < out.length; k++) out[k] = hs.get(k);
    return out;
  }

  // ---------------------------------------------------------------- liveness

  private static boolean wideParam(String t) {
    char ch = t.charAt(0);
    return ch == 'J' || ch == 'D';
  }

  private void addUses(int i, long[] use) {
    int o = op[i];
    int ra = a[i];
    int rb = b[i];
    int rc = c[i];
    if (o == 0x01 || o == 0x02 || o == 0x03 || o == 0x07 || o == 0x08 || o == 0x09 || o == 0x21) {
      setBit(use, rb);
    } else if (o == 0x04 || o == 0x05 || o == 0x06) {
      setBit(use, rb);
      setBit(use, rb + 1);
    } else if (o == 0x0f || o == 0x11 || o == 0x1d || o == 0x1e || o == 0x27 || o == 0x1f || o == 0x26 || o == 0x2b || o == 0x2c
        || (o >= 0x38 && o <= 0x3d)) {
      setBit(use, ra);
    } else if (o == 0x10) {
      setBit(use, ra);
      setBit(use, ra + 1);
    } else if (o == 0x20 || o == 0x23) {
      setBit(use, rb);
    } else if (o == 0x2d || o == 0x2e) {
      setBit(use, rb);
      setBit(use, rc);
    } else if (o >= 0x2f && o <= 0x31) {
      setBit(use, rb);
      setBit(use, rb + 1);
      setBit(use, rc);
      setBit(use, rc + 1);
    } else if (o >= 0x32 && o <= 0x37) {
      setBit(use, ra);
      setBit(use, rb);
    } else if (o >= 0x44 && o <= 0x4a) {
      setBit(use, rb);
      setBit(use, rc);
    } else if (o >= 0x4b && o <= 0x51) {
      setBit(use, ra);
      if (o == 0x4c) setBit(use, ra + 1);
      setBit(use, rb);
      setBit(use, rc);
    } else if (o >= 0x52 && o <= 0x58) {
      setBit(use, rb);
    } else if (o >= 0x59 && o <= 0x5f) {
      setBit(use, ra);
      if (o == 0x5a) setBit(use, ra + 1);
      setBit(use, rb);
    } else if (o >= 0x67 && o <= 0x6d) {
      setBit(use, ra);
      if (o == 0x68) setBit(use, ra + 1);
    } else if (args[i] != null) {
      for (int r : args[i]) setBit(use, r);
    } else if (o >= 0x7b && o <= 0x8f) {
      setBit(use, rb);
      if (o == 0x7d || o == 0x7e || o == 0x80 || o == 0x84 || o == 0x85 || o == 0x86 || o == 0x8a || o == 0x8b || o == 0x8c) setBit(use, rb + 1);
    } else if (o >= 0x90 && o <= 0xaf) {
      boolean w = binWide(o - 0x90);
      setBit(use, rb);
      setBit(use, rc);
      if (w) {
        setBit(use, rb + 1);
        if (!isShift(o - 0x90)) setBit(use, rc + 1);
      }
    } else if (o >= 0xb0 && o <= 0xcf) {
      boolean w = binWide(o - 0xb0);
      setBit(use, ra);
      setBit(use, rb);
      if (w) {
        setBit(use, ra + 1);
        if (!isShift(o - 0xb0)) setBit(use, rb + 1);
      }
    } else if (o >= 0xd0 && o <= 0xe2) {
      setBit(use, rb);
    }
  }

  /** Register written by instruction i, or -1. Wide results also write the next register. */
  private int defReg(int i) {
    int o = op[i];
    if ((o >= 0x01 && o <= 0x0d) || (o >= 0x12 && o <= 0x1c) || o == 0x1f || o == 0x20 || o == 0x21 || o == 0x22 || o == 0x23
        || (o >= 0x2d && o <= 0x31) || (o >= 0x44 && o <= 0x4a) || (o >= 0x52 && o <= 0x58) || (o >= 0x60 && o <= 0x66)
        || (o >= 0x7b && o <= 0x8f) || (o >= 0x90 && o <= 0xe2) || o == 0xfe || o == 0xff) {
      return a[i];
    }
    return -1;
  }

  private boolean defWide(int i) {
    int o = op[i];
    if (o == 0x04 || o == 0x05 || o == 0x06 || o == 0x0b || (o >= 0x16 && o <= 0x19) || o == 0x45 || o == 0x53 || o == 0x61) return true;
    if (o == 0x7d || o == 0x7e || o == 0x80 || o == 0x81 || o == 0x83 || o == 0x86 || o == 0x88 || o == 0x89 || o == 0x8b) return true;
    if (o >= 0x90 && o <= 0xaf) return binWide(o - 0x90);
    if (o >= 0xb0 && o <= 0xcf) return binWide(o - 0xb0);
    return false;
  }

  private static boolean binWide(int k) {
    return (k >= 0x0b && k <= 0x15) || (k >= 0x1b && k <= 0x1f);
  }

  private static boolean isShift(int k) {
    return k >= 0x13 && k <= 0x15;
  }

  /**
   * Registers live on entry to each block. A handler sees the registers as they were before an instruction that
   * can throw, so its live registers are live there, and only there: not throughout every block its try range
   * covers. (Adding them at the block's entry made a register look live in a handler that redefines it first, and
   * merged unrelated values: in Compose's SuspendAnimationKt.animate a float duration scale with an ObjectRef, which
   * typed the float as a reference and dropped it, so every animation in a v2 Compose test snapped to its end.)
   */
  private void computeLiveness() {
    int words = (regs + 64) >>> 6; // a wide use of the last register names one past it
    liveWords = words;
    liveIn = new long[blockCount * words];
    // Per block: reads before any write in it (gen), writes (kill), writes before its first instruction that can throw
    // to a handler (killPre), and those handlers. A handler's live registers join at a throwing instruction, after its
    // own write: so at block entry they are live less what earlier instructions write. Composed, in = gen + (out -
    // kill) + (handlers' in - killPre), exactly what walking the block instruction by instruction gives. A block whose
    // throwing instructions reach different handlers (only if try bounds were not block starts) is walked that way.
    long[] gen = new long[blockCount * words];
    long[] kill = new long[blockCount * words];
    long[] killPre = new long[blockCount * words];
    int[][] blockHandlers = new int[blockCount][];
    boolean[] walk = new boolean[blockCount];
    long[] use = new long[words];
    for (int bl = 0; bl < blockCount; bl++) {
      int o = bl * words;
      int first = blockStart[bl], end = blockStart[bl + 1];
      int firstThrow = -1;
      for (int i = first; i < end; i++) {
        if (!canThrow(op[i])) continue;
        int[] hs = handlersOf(i);
        if (hs == null) continue;
        if (firstThrow < 0) {
          firstThrow = i;
          blockHandlers[bl] = hs;
        } else if (hs != blockHandlers[bl]) {
          walk[bl] = true;
        }
      }
      for (int i = end - 1; i >= first; i--) {
        int r = defReg(i);
        if (r >= 0) {
          int n = defWide(i) ? 2 : 1;
          for (int k = r; k < r + n; k++) {
            gen[o + (k >>> 6)] &= ~(1L << k);
            kill[o + (k >>> 6)] |= 1L << k;
            if (firstThrow >= 0 && i < firstThrow) killPre[o + (k >>> 6)] |= 1L << k;
          }
        }
        Arrays.fill(use, 0);
        addUses(i, use);
        for (int w = 0; w < words; w++) gen[o + w] |= use[w];
      }
    }
    long[] live = new long[words];
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int bl = blockCount - 1; bl >= 0; bl--) {
        int o = bl * words;
        Arrays.fill(live, 0);
        for (int s : normalSucc[bl]) for (int w = 0; w < words; w++) live[w] |= liveIn[s * words + w];
        if (walk[bl]) {
          for (int i = blockStart[bl + 1] - 1; i >= blockStart[bl]; i--) {
            int r = defReg(i);
            if (r >= 0) {
              live[r >>> 6] &= ~(1L << r);
              if (defWide(i)) live[(r + 1) >>> 6] &= ~(1L << (r + 1));
            }
            Arrays.fill(use, 0);
            addUses(i, use);
            for (int w = 0; w < words; w++) live[w] |= use[w];
            int[] hs = canThrow(op[i]) ? handlersOf(i) : null;
            if (hs != null) for (int h : hs) for (int w = 0; w < words; w++) live[w] |= liveIn[h * words + w];
          }
        } else {
          for (int w = 0; w < words; w++) live[w] = gen[o + w] | (live[w] & ~kill[o + w]);
          if (blockHandlers[bl] != null) {
            for (int h : blockHandlers[bl]) {
              for (int w = 0; w < words; w++) live[w] |= liveIn[h * words + w] & ~killPre[o + w];
            }
          }
        }
        for (int w = 0; w < words; w++) {
          if ((live[w] & ~liveIn[o + w]) != 0) {
            liveIn[o + w] |= live[w];
            changed = true;
          }
        }
      }
    }
  }

  private static void setBit(long[] words, int r) {
    words[r >>> 6] |= 1L << r;
  }

  // ---------------------------------------------------------------- value typing

  private int newNode(int kind, boolean isWide, String d) {
    if (nodes == parent.length) {
      int cap = nodes * 2;
      parent = Arrays.copyOf(parent, cap);
      kinds = Arrays.copyOf(kinds, cap);
      wide = Arrays.copyOf(wide, cap);
      desc = Arrays.copyOf(desc, cap);
    }
    parent[nodes] = nodes;
    kinds[nodes] = kind;
    wide[nodes] = isWide;
    desc[nodes] = d;
    return nodes++;
  }

  private int find(int n) {
    while (parent[n] != n) {
      parent[n] = parent[parent[n]];
      n = parent[n];
    }
    return n;
  }

  // -Dapksim.debugTypes=owner/Class.method: report where a value of that method is typed both a reference and a
  // primitive (which ends with the primitive dropped), with the instruction and merge that did it.
  private static final String DEBUG_TYPES = System.getProperty("apksim.debugTypes");
  private boolean debugTypes;
  private int curInsn = -1;
  private String curWhat = "";

  private boolean debugging() {
    return debugTypes;
  }

  private void noteMix(int ka, int kb) {
    boolean oa = (ka & K_OBJ) != 0, ob = (kb & K_OBJ) != 0;
    boolean pa = (ka & ~K_OBJ) != 0, pb = (kb & ~K_OBJ) != 0;
    if ((oa && pb) || (ob && pa) || (oa && pa) || (ob && pb)) {
      System.err.printf("[apksim] DEBUG types %s: mix %d+%d at insn %d (addr 0x%x op 0x%x) %s%n", methodName, ka, kb, curInsn,
          curInsn >= 0 ? addr[curInsn] : -1, curInsn >= 0 ? op[curInsn] : -1, curWhat);
    }
  }

  private void union(int x, int y) {
    int rx = find(x);
    int ry = find(y);
    if (rx == ry) return;
    if (debugging()) noteMix(kinds[rx], kinds[ry]);
    parent[ry] = rx;
    kinds[rx] |= kinds[ry];
    wide[rx] |= wide[ry];
    if (desc[rx] == null && desc[ry] != null) {
      desc[rx] = desc[ry];
      descChanged = true;
    }
  }

  private static int kindOf(String type) {
    switch (type.charAt(0)) {
      case 'F':
        return K_FLOAT;
      case 'J':
        return K_LONG;
      case 'D':
        return K_DOUBLE;
      case 'L':
      case '[':
        return K_OBJ;
      default:
        return K_INT;
    }
  }

  private int resolved(int node) {
    int r = find(node);
    int k = kinds[r];
    if ((k & K_OBJ) != 0) return K_OBJ;
    if (wide[r]) return (k & K_DOUBLE) != 0 && (k & K_LONG) == 0 ? K_DOUBLE : K_LONG;
    return (k & K_FLOAT) != 0 && (k & K_INT) == 0 ? K_FLOAT : K_INT;
  }

  private int[] initialState() {
    int[] s = new int[regs];
    Arrays.fill(s, UNSET);
    int r = regs - insWords;
    if (!isStatic) s[r++] = newNode(K_OBJ, false, owner);
    for (String p : params) {
      int k = kindOf(p);
      boolean w = wideParam(p);
      s[r] = newNode(k, w, k == K_OBJ ? p : null);
      if (w) s[r + 1] = WIDE_HI;
      r += w ? 2 : 1;
    }
    return s;
  }

  private void setDef(int[] s, int r, int node, boolean isWide) {
    if (tracking != null) tracking.dirty(r);
    if (r > 0 && s[r] == WIDE_HI) s[r - 1] = UNSET;
    if (s[r] >= 0 && r + 1 < regs && s[r + 1] == WIDE_HI) s[r + 1] = UNSET;
    s[r] = node;
    if (isWide) {
      if (r + 2 < regs && s[r + 2] == WIDE_HI) s[r + 2] = UNSET;
      s[r + 1] = WIDE_HI;
    }
  }

  private void analyze() {
    defNode = new int[count];
    Arrays.fill(defNode, -1);
    blockState = new int[blockCount][];
    int[] entry = initialState();
    // Blocks to visit, first in first out (the order decides which type name a value gets first): a ring of block
    // indices, each queued at most once (a bit per block says which are).
    work = new int[blockCount];
    queued = new long[(blockCount + 63) >>> 6];
    int[] s = new int[regs];
    while (true) {
      descChanged = false;
      Arrays.fill(blockState, null);
      blockState[0] = entry.clone();
      workHead = 0;
      workSize = 0;
      enqueue(0);
      while (workSize > 0) {
        int bl = work[workHead];
        workHead = workHead + 1 == blockCount ? 0 : workHead + 1;
        workSize--;
        queued[bl >>> 6] &= ~(1L << bl);
        System.arraycopy(blockState[bl], 0, s, 0, regs);
        for (int i = blockStart[bl]; i < blockStart[bl + 1]; i++) {
          curInsn = i;
          curWhat = "to handler";
          mergeToHandlers(i, s);
          curWhat = "transfer";
          transfer(i, s);
        }
        curInsn = blockStart[bl + 1] - 1;
        if (debugTypes) curWhat = "flow out of block " + bl;
        int last = blockStart[bl + 1] - 1;
        int o = op[last];
        if (isGoto(o)) {
          flow(blockOf[insnAt(a[last])], s);
        } else if (isIf(o)) {
          flow(blockOf[insnAt(c[last])], s);
          flow(blockOf[last + 1], s);
        } else if (isSwitch(o)) {
          for (int t : switchTargets(last)) flow(blockOf[insnAt(t)], s);
          flow(blockOf[last + 1], s);
        } else if (!isReturnOrThrow(o) && last + 1 < count) {
          flow(blockOf[last + 1], s);
        }
      }
      if (!descChanged) break;
    }
  }

  private int[] work;
  private int workHead, workSize;
  private long[] queued;

  private void enqueue(int bl) {
    if ((queued[bl >>> 6] & (1L << bl)) != 0) return;
    queued[bl >>> 6] |= 1L << bl;
    int at = workHead + workSize;
    work[at < blockCount ? at : at - blockCount] = bl;
    workSize++;
  }

  private void mergeToHandlers(int i, int[] s) {
    if (tries.isEmpty() || !canThrow(op[i])) return;
    int[] hs = handlersOf(i);
    if (hs != null) for (int h : hs) flow(h, s);
  }

  private static boolean canThrow(int o) {
    // Returns (0x0e-0x11) cannot throw either, as ART's verifier has it. A return inside a try range must not hand its
    // registers to the handler: D8 ends a synchronized block's catch-all after a `return v0` reached from outside the
    // lock with an int in v0, while the handler's monitor-exit v0 reads the lock object. Merging the two made every
    // int that flowed into v0 a reference (play-services' GoogleApiAvailabilityCache then always called keyAt(0) on
    // an empty SparseIntArray, and its GoogleApiHandler thread died).
    if (o <= 0x11 || (o >= 0x12 && o <= 0x19) || (o >= 0x28 && o <= 0x3d)) return false;
    if (o >= 0x7b && o <= 0xe2) {
      // Only integer division can throw among arithmetic.
      return o == 0x93 || o == 0x94 || o == 0x9e || o == 0x9f || o == 0xb3 || o == 0xb4 || o == 0xbe || o == 0xbf
          || o == 0xd3 || o == 0xd4 || o == 0xdb || o == 0xdc;
    }
    return true;
  }

  private void flow(int target, int[] s) {
    int[] in = blockState[target];
    boolean changed = false;
    if (in == null) {
      in = new int[regs];
      Arrays.fill(in, UNSET);
      blockState[target] = in;
      changed = true;
    }
    for (int w = 0, o = target * liveWords; w < liveWords; w++) {
      for (long bits = liveIn[o + w]; bits != 0; bits &= bits - 1) {
        int r = (w << 6) + Long.numberOfTrailingZeros(bits);
        int v = s[r];
        if (v == UNSET) continue;
        if (in[r] == UNSET) {
          in[r] = v;
          changed = true;
        } else if (v >= 0 && in[r] >= 0) {
          if (find(v) != find(in[r])) {
            if (debugging()) curWhat = curWhat.replaceAll(" reg v\\d+.*", "") + " reg v" + r + " into block at 0x" + Integer.toHexString(addr[blockStart[target]]);
            union(v, in[r]);
            changed = true;
          }
        }
      }
    }
    if (changed) enqueue(target);
  }

  private int def(int i, int kind, boolean isWide, String d) {
    int n = defNode[i];
    if (n < 0) {
      n = newNode(kind, isWide, d);
      defNode[i] = n;
    } else {
      int r = find(n);
      kinds[r] |= kind;
      if (d != null && desc[r] == null) {
        desc[r] = d;
        descChanged = true;
      }
    }
    return n;
  }

  private void use(int[] s, int r, int kind) {
    int v = s[r];
    if (v >= 0 && debugging()) {
      curWhat = "use v" + r;
      noteMix(kinds[find(v)], kind);
    }
    if (v >= 0) kinds[find(v)] |= kind;
  }

  private String arrayDesc(int[] s, int r) {
    int v = s[r];
    return v >= 0 ? desc[find(v)] : null;
  }

  private static String elem(String arr) {
    return arr != null && arr.length() > 1 && arr.charAt(0) == '[' ? arr.substring(1) : null;
  }

  private String resultType(int i) {
    for (int j = i - 1; j >= 0 && j >= i - 1; j--) {
      int o = op[j];
      if ((o >= 0x6e && o <= 0x72) || (o >= 0x74 && o <= 0x78)) return dex.method(b[j]).ret();
      if (o == 0x24 || o == 0x25) return dex.type(b[j]);
      if (o == 0xfa || o == 0xfb) return dex.protoReturnById((int) lit[j]);
      if (o == 0xfc || o == 0xfd) return callSiteReturn(b[j]);
    }
    return "Ljava/lang/Object;";
  }

  private String callSiteReturn(int idx) {
    Object[] parts = callSite(idx);
    return Type.getReturnType((String) parts[1]).getDescriptor();
  }

  private void invokeUses(int[] s, int[] regsList, boolean hasReceiver, String[] ps) {
    int x = 0;
    if (hasReceiver) use(s, regsList[x++], K_OBJ);
    for (String p : ps) {
      use(s, regsList[x], kindOf(p));
      x += wideParam(p) ? 2 : 1;
    }
  }

  private void transfer(int i, int[] s) {
    int o = op[i];
    int ra = a[i];
    int rb = b[i];
    int rc = c[i];
    switch (o) {
      case 0x00:
      case 0x0e:
      case 0x28:
      case 0x29:
      case 0x2a:
        return;
      case 0x01:
      case 0x02:
      case 0x03:
      case 0x04:
      case 0x05:
      case 0x06:
        if (s[rb] >= 0) {
          setDef(s, ra, s[rb], o >= 0x04);
        } else {
          setDef(s, ra, def(i, o >= 0x04 ? K_LONG : K_INT, o >= 0x04, null), o >= 0x04);
        }
        return;
      case 0x07:
      case 0x08:
      case 0x09:
        if (s[rb] >= 0) {
          use(s, rb, K_OBJ);
          setDef(s, ra, s[rb], false);
        } else {
          setDef(s, ra, def(i, K_OBJ, false, null), false);
        }
        return;
      case 0x0a:
      case 0x0b:
      case 0x0c: {
        String t = resultType(i);
        setDef(s, ra, def(i, kindOf(t), o == 0x0b, kindOf(t) == K_OBJ ? t : null), o == 0x0b);
        return;
      }
      case 0x0d:
        setDef(s, ra, def(i, K_OBJ, false, null), false);
        return;
      case 0x0f:
      case 0x10:
      case 0x11:
        use(s, ra, kindOf(ret));
        return;
      case 0x12:
      case 0x13:
      case 0x14:
      case 0x15:
        setDef(s, ra, def(i, 0, false, null), false);
        return;
      case 0x16:
      case 0x17:
      case 0x18:
      case 0x19:
        setDef(s, ra, def(i, 0, true, null), true);
        return;
      case 0x1a:
      case 0x1b:
        setDef(s, ra, def(i, K_OBJ, false, "Ljava/lang/String;"), false);
        return;
      case 0x1c:
        setDef(s, ra, def(i, K_OBJ, false, "Ljava/lang/Class;"), false);
        return;
      case 0xfe:
        setDef(s, ra, def(i, K_OBJ, false, "Ljava/lang/invoke/MethodHandle;"), false);
        return;
      case 0xff:
        setDef(s, ra, def(i, K_OBJ, false, "Ljava/lang/invoke/MethodType;"), false);
        return;
      case 0x1d:
      case 0x1e:
      case 0x27:
        use(s, ra, K_OBJ);
        return;
      case 0x1f:
        use(s, ra, K_OBJ);
        setDef(s, ra, def(i, K_OBJ, false, dex.type(rb)), false);
        return;
      case 0x20:
        use(s, rb, K_OBJ);
        setDef(s, ra, def(i, K_INT, false, null), false);
        return;
      case 0x21:
        use(s, rb, K_OBJ);
        setDef(s, ra, def(i, K_INT, false, null), false);
        return;
      case 0x22:
        setDef(s, ra, def(i, K_OBJ, false, dex.type(rb)), false);
        return;
      case 0x23:
        use(s, rb, K_INT);
        setDef(s, ra, def(i, K_OBJ, false, dex.type(rc)), false);
        return;
      case 0x24:
      case 0x25: {
        String el = dex.type(b[i]).substring(1);
        for (int r : args[i]) use(s, r, kindOf(el));
        return;
      }
      case 0x26:
        use(s, ra, K_OBJ);
        return;
      case 0x2b:
      case 0x2c:
        use(s, ra, K_INT);
        return;
      case 0x2d:
      case 0x2e:
        use(s, rb, K_FLOAT);
        use(s, rc, K_FLOAT);
        setDef(s, ra, def(i, K_INT, false, null), false);
        return;
      case 0x2f:
      case 0x30:
        use(s, rb, K_DOUBLE);
        use(s, rc, K_DOUBLE);
        setDef(s, ra, def(i, K_INT, false, null), false);
        return;
      case 0x31:
        use(s, rb, K_LONG);
        use(s, rc, K_LONG);
        setDef(s, ra, def(i, K_INT, false, null), false);
        return;
      default:
        break;
    }
    if (o >= 0x32 && o <= 0x37) {
      if (s[ra] >= 0 && s[rb] >= 0) union(s[ra], s[rb]);
      return;
    }
    if (o >= 0x38 && o <= 0x3d) return;
    if (o >= 0x44 && o <= 0x4a) {
      use(s, rb, K_OBJ);
      use(s, rc, K_INT);
      String el = elem(arrayDesc(s, rb));
      if (o == 0x44) {
        int k = "F".equals(el) ? K_FLOAT : K_INT;
        setDef(s, ra, def(i, k, false, null), false);
      } else if (o == 0x45) {
        int k = "D".equals(el) ? K_DOUBLE : K_LONG;
        setDef(s, ra, def(i, k, true, null), true);
      } else if (o == 0x46) {
        setDef(s, ra, def(i, K_OBJ, false, el), false);
      } else {
        setDef(s, ra, def(i, K_INT, false, null), false);
      }
      return;
    }
    if (o >= 0x4b && o <= 0x51) {
      use(s, rb, K_OBJ);
      use(s, rc, K_INT);
      String el = elem(arrayDesc(s, rb));
      if (o == 0x4b) {
        use(s, ra, "F".equals(el) ? K_FLOAT : K_INT);
      } else if (o == 0x4c) {
        use(s, ra, "D".equals(el) ? K_DOUBLE : K_LONG);
      } else if (o == 0x4d) {
        use(s, ra, K_OBJ);
      } else {
        use(s, ra, K_INT);
      }
      return;
    }
    if (o >= 0x52 && o <= 0x58) {
      use(s, rb, K_OBJ);
      DexFile.FieldRef f = dex.field(rc);
      int k = kindOf(f.type());
      setDef(s, ra, def(i, k, o == 0x53, k == K_OBJ ? f.type() : null), o == 0x53);
      return;
    }
    if (o >= 0x59 && o <= 0x5f) {
      use(s, rb, K_OBJ);
      use(s, ra, kindOf(dex.field(rc).type()));
      return;
    }
    if (o >= 0x60 && o <= 0x66) {
      DexFile.FieldRef f = dex.field(rb);
      int k = kindOf(f.type());
      setDef(s, ra, def(i, k, o == 0x61, k == K_OBJ ? f.type() : null), o == 0x61);
      return;
    }
    if (o >= 0x67 && o <= 0x6d) {
      use(s, ra, kindOf(dex.field(rb).type()));
      return;
    }
    if ((o >= 0x6e && o <= 0x72) || (o >= 0x74 && o <= 0x78)) {
      DexFile.MethodRef m = dex.method(b[i]);
      boolean isStaticCall = o == 0x71 || o == 0x77;
      invokeUses(s, args[i], !isStaticCall, m.params());
      return;
    }
    if (o == 0xfa || o == 0xfb) {
      invokeUses(s, args[i], true, dex.protoParamsById((int) lit[i]));
      return;
    }
    if (o == 0xfc || o == 0xfd) {
      Type[] ts = Type.getArgumentTypes((String) callSite(b[i])[1]);
      String[] ps = new String[ts.length];
      for (int t = 0; t < ts.length; t++) ps[t] = ts[t].getDescriptor();
      invokeUses(s, args[i], false, ps);
      return;
    }
    if (o >= 0x7b && o <= 0x8f) {
      int[] io = unaryKinds(o);
      use(s, rb, io[0]);
      setDef(s, ra, def(i, io[1], io[1] == K_LONG || io[1] == K_DOUBLE, null), io[1] == K_LONG || io[1] == K_DOUBLE);
      return;
    }
    if (o >= 0x90 && o <= 0xaf) {
      int k = binKind(o - 0x90);
      use(s, rb, k);
      use(s, rc, isShift(o - 0x90) ? K_INT : k);
      boolean w = k == K_LONG || k == K_DOUBLE;
      setDef(s, ra, def(i, k, w, null), w);
      return;
    }
    if (o >= 0xb0 && o <= 0xcf) {
      int k = binKind(o - 0xb0);
      use(s, ra, k);
      use(s, rb, isShift(o - 0xb0) ? K_INT : k);
      boolean w = k == K_LONG || k == K_DOUBLE;
      setDef(s, ra, def(i, k, w, null), w);
      return;
    }
    if (o >= 0xd0 && o <= 0xe2) {
      use(s, rb, K_INT);
      setDef(s, ra, def(i, K_INT, false, null), false);
      return;
    }
    throw new IllegalStateException("unhandled opcode 0x" + Integer.toHexString(o));
  }

  private static int binKind(int k) {
    if (k <= 0x0a) return K_INT;
    if (k <= 0x15) return K_LONG;
    if (k <= 0x1a) return K_FLOAT;
    return K_DOUBLE;
  }

  private static int[] unaryKinds(int o) {
    switch (o) {
      case 0x7b:
      case 0x7c:
      case 0x8d:
      case 0x8e:
      case 0x8f:
        return new int[] {K_INT, K_INT};
      case 0x7d:
      case 0x7e:
        return new int[] {K_LONG, K_LONG};
      case 0x7f:
        return new int[] {K_FLOAT, K_FLOAT};
      case 0x80:
        return new int[] {K_DOUBLE, K_DOUBLE};
      case 0x81:
        return new int[] {K_INT, K_LONG};
      case 0x82:
        return new int[] {K_INT, K_FLOAT};
      case 0x83:
        return new int[] {K_INT, K_DOUBLE};
      case 0x84:
        return new int[] {K_LONG, K_INT};
      case 0x85:
        return new int[] {K_LONG, K_FLOAT};
      case 0x86:
        return new int[] {K_LONG, K_DOUBLE};
      case 0x87:
        return new int[] {K_FLOAT, K_INT};
      case 0x88:
        return new int[] {K_FLOAT, K_LONG};
      case 0x89:
        return new int[] {K_FLOAT, K_DOUBLE};
      case 0x8a:
        return new int[] {K_DOUBLE, K_INT};
      case 0x8b:
        return new int[] {K_DOUBLE, K_LONG};
      default:
        return new int[] {K_DOUBLE, K_FLOAT};
    }
  }

  // ---------------------------------------------------------------- emission

  private int slot(int r) {
    return base + r;
  }

  private void regLoad(int k, int r) {
    if (!inChunk) {
      mv.visitVarInsn(loadOp(k), slot(r));
      return;
    }
    mv.visitVarInsn(ALOAD, arrayLocal(k));
    pushInt(r);
    mv.visitInsn(switch (k) {
      case K_INT -> IALOAD;
      case K_FLOAT -> FALOAD;
      case K_LONG -> LALOAD;
      case K_DOUBLE -> DALOAD;
      default -> AALOAD;
    });
  }

  private void regStore(int k, int r) {
    if (!inChunk) {
      mv.visitVarInsn(storeOp(k), slot(r));
      return;
    }
    mv.visitVarInsn(storeOp(k), L_TMP);
    mv.visitVarInsn(ALOAD, arrayLocal(k));
    pushInt(r);
    mv.visitVarInsn(loadOp(k), L_TMP);
    mv.visitInsn(switch (k) {
      case K_INT -> IASTORE;
      case K_FLOAT -> FASTORE;
      case K_LONG -> LASTORE;
      case K_DOUBLE -> DASTORE;
      default -> AASTORE;
    });
  }

  private static int arrayLocal(int k) {
    return switch (k) {
      case K_INT -> L_INTS;
      case K_FLOAT -> L_FLOATS;
      case K_LONG -> L_LONGS;
      case K_DOUBLE -> L_DOUBLES;
      default -> L_OBJECTS;
    };
  }

  private void load(int[] s, int r, int want) {
    int v = s[r];
    if (v < 0) {
      // Reachable only on dead paths the Dalvik verifier cannot rule out.
      pushZero(want);
      return;
    }
    int k = resolved(v);
    if (k == want) {
      regLoad(k, r);
    } else if (k == K_OBJ || want == K_OBJ) {
      pushZero(want); // The value is the constant 0 used as both null and a primitive.
    } else {
      regLoad(k, r);
      convert(k, want);
    }
  }

  private void pushZero(int k) {
    switch (k) {
      case K_INT -> mv.visitInsn(ICONST_0);
      case K_FLOAT -> mv.visitInsn(FCONST_0);
      case K_LONG -> mv.visitInsn(LCONST_0);
      case K_DOUBLE -> mv.visitInsn(DCONST_0);
      default -> mv.visitInsn(ACONST_NULL);
    }
  }

  private void convert(int from, int to) {
    if (from == K_INT && to == K_FLOAT) {
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false);
    } else if (from == K_FLOAT && to == K_INT) {
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
    } else if (from == K_LONG && to == K_DOUBLE) {
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
    } else if (from == K_DOUBLE && to == K_LONG) {
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
    } else {
      throw new IllegalStateException("cannot convert " + from + " to " + to);
    }
  }

  private static int loadOp(int k) {
    return switch (k) {
      case K_INT -> ILOAD;
      case K_FLOAT -> FLOAD;
      case K_LONG -> LLOAD;
      case K_DOUBLE -> DLOAD;
      default -> ALOAD;
    };
  }

  private static int storeOp(int k) {
    return switch (k) {
      case K_INT -> ISTORE;
      case K_FLOAT -> FSTORE;
      case K_LONG -> LSTORE;
      case K_DOUBLE -> DSTORE;
      default -> ASTORE;
    };
  }

  /** Stores the stack top (of kind `have`) into r as a fresh definition by instruction i. */
  private void store(int[] s, int i, int r, int have, boolean isWide) {
    int n = defNode[i];
    int k = resolved(n);
    if (k != have) {
      if (k == K_OBJ || have == K_OBJ) {
        // A primitive-typed def merged with references cannot be read as either; drop it.
        mv.visitInsn(isWide ? POP2 : POP);
        pushZero(k);
      } else {
        convert(have, k);
      }
    }
    regStore(k, r);
    setDef(s, r, n, isWide);
  }

  private void emit() {
    // Branches, handlers and try bounds all lead to block starts (buildBlocks makes them so), so only those and the
    // end need a label; debug info (line numbers, local ranges) names every instruction.
    Label[] labels = new Label[count + 1];
    if (lines != null || !locals.isEmpty()) {
      for (int i = 0; i <= count; i++) labels[i] = new Label();
    } else {
      for (int bl = 0; bl < blockCount; bl++) labels[blockStart[bl]] = new Label();
      labels[count] = new Label();
    }
    Map<Integer, Label> stubs = new HashMap<>();
    mv.visitCode();
    Label start = new Label();
    mv.visitLabel(start);
    LocalRanges lr = locals.isEmpty() ? null : new LocalRanges(labels);
    tracking = lr;
    for (Try t : tries) {
      int si = addrToInsn[t.start];
      int ei = t.end <= unitCount ? addrToInsn[t.end] : count;
      if (si < 0 || ei < 0 || si >= ei) continue;
      for (Handler h : t.handlers) {
        Label stub = stubs.computeIfAbsent(h.addr, x -> new Label());
        mv.visitTryCatchBlock(labels[si], labels[ei], stub, h.type == null ? null : internal(h.type));
      }
    }
    copyParams();
    int[] s = new int[regs];
    for (int bl = 0; bl < blockCount; bl++) {
      boolean reachable = blockState[bl] != null;
      if (reachable) System.arraycopy(blockState[bl], 0, s, 0, regs);
      for (int i = blockStart[bl]; i < blockStart[bl + 1]; i++) {
        if (labels[i] != null) mv.visitLabel(labels[i]);
        if (lr != null) lr.at(i, reachable ? s : null, i == blockStart[bl]);
        // After the parameter copies for insn 0: a breakpoint there must already see the parameters.
        if (reachable && lines != null && lines[i] != 0) mv.visitLineNumber(lines[i], labels[i]);
        if (reachable) emitInsn(i, s, labels);
      }
    }
    mv.visitLabel(labels[count]);
    if (lr != null) lr.finish();
    tracking = null;
    for (var e : stubs.entrySet()) {
      int target = insnAt(e.getKey());
      mv.visitLabel(e.getValue());
      if (op[target] == 0x0d) {
        mv.visitVarInsn(ASTORE, slot(a[target]));
      } else {
        mv.visitInsn(POP);
      }
      mv.visitJumpInsn(GOTO, labels[op[target] == 0x0d ? target + 1 : target]);
    }
    if (!isStatic && (lines != null || lr != null)) {
      Label end = new Label();
      mv.visitLabel(end);
      mv.visitLocalVariable("this", "L" + owner + ";", null, start, end, 0); // Slot 0 is never reassigned.
    }
    mv.visitMaxs(maxStack(), base + regs + 2);
    mv.visitEnd();
  }

  /**
   * For a method too big for the JVM: registers live in arrays, shared by "chunk" methods that each hold
   * a run of basic blocks. A chunk returns the next block to run (or -1), and a small driver loops.
   */
  private void emitSplit() {
    List<int[]> chunks = new ArrayList<>(); // {first block, end block}
    int first = 0;
    int units = 0;
    for (int bl = 0; bl < blockCount; bl++) {
      int size = addr[blockStart[bl + 1]] - addr[blockStart[bl]];
      if (units > 0 && units + size > CHUNK_UNITS) {
        chunks.add(new int[] {first, bl});
        first = bl;
        units = 0;
      }
      units += size;
    }
    chunks.add(new int[] {first, blockCount});
    emitDriver(chunks);
    for (int c = 0; c < chunks.size(); c++) emitChunk(c, chunks.get(c));
  }

  private String chunkName(int c) {
    String base = methodName.replace('<', '_').replace('>', '_');
    return base + "$apksim$" + c;
  }

  private void emitDriver(List<int[]> chunks) {
    MethodVisitor main = mv;
    main.visitCode();
    int size = regs + 2;
    // Locals after the incoming arguments.
    int ints = insWords;
    int longs = ints + 1;
    int floats = ints + 2;
    int doubles = ints + 3;
    int objects = ints + 4;
    int block = ints + 5;
    int[][] arrays = {{T_INT, ints}, {T_LONG, longs}, {T_FLOAT, floats}, {T_DOUBLE, doubles}};
    for (int[] a : arrays) {
      pushInt(size);
      main.visitIntInsn(NEWARRAY, a[0]);
      main.visitVarInsn(ASTORE, a[1]);
    }
    pushInt(size);
    main.visitTypeInsn(ANEWARRAY, "java/lang/Object");
    main.visitVarInsn(ASTORE, objects);
    int in = 0;
    int r = regs - insWords;
    if (!isStatic) {
      main.visitVarInsn(ALOAD, objects);
      pushInt(r++);
      main.visitVarInsn(ALOAD, in++);
      main.visitInsn(AASTORE);
    }
    for (String p : params) {
      int k = kindOf(p);
      boolean w = wideParam(p);
      int held = blockState[0][r] >= 0 ? resolved(blockState[0][r]) : k;
      if (held == K_OBJ || k == K_OBJ) held = k;
      int array = switch (held) {
        case K_INT -> ints;
        case K_FLOAT -> floats;
        case K_LONG -> longs;
        case K_DOUBLE -> doubles;
        default -> objects;
      };
      main.visitVarInsn(ALOAD, array);
      pushInt(r);
      main.visitVarInsn(loadOp(k), in);
      if (held != k) convert(k, held);
      main.visitInsn(switch (held) {
        case K_INT -> IASTORE;
        case K_FLOAT -> FASTORE;
        case K_LONG -> LASTORE;
        case K_DOUBLE -> DASTORE;
        default -> AASTORE;
      });
      in += w ? 2 : 1;
      r += w ? 2 : 1;
    }
    main.visitInsn(ICONST_0);
    main.visitVarInsn(ISTORE, block);
    Label loop = new Label();
    Label done = new Label();
    main.visitLabel(loop);
    main.visitVarInsn(ILOAD, block);
    main.visitJumpInsn(IFLT, done);
    for (int c = 0; c < chunks.size(); c++) {
      Label next = new Label();
      main.visitVarInsn(ILOAD, block);
      pushInt(chunks.get(c)[1]);
      main.visitJumpInsn(IF_ICMPGE, next);
      main.visitVarInsn(ALOAD, ints);
      main.visitVarInsn(ALOAD, longs);
      main.visitVarInsn(ALOAD, floats);
      main.visitVarInsn(ALOAD, doubles);
      main.visitVarInsn(ALOAD, objects);
      main.visitVarInsn(ILOAD, block);
      main.visitMethodInsn(INVOKESTATIC, owner, chunkName(c), CHUNK_DESC, isInterface.test(owner));
      main.visitVarInsn(ISTORE, block);
      main.visitJumpInsn(GOTO, loop);
      main.visitLabel(next);
    }
    main.visitLabel(done);
    if (ret.charAt(0) == 'V') {
      main.visitInsn(RETURN);
    } else {
      int k = kindOf(ret);
      main.visitVarInsn(ALOAD, switch (k) {
        case K_INT -> ints;
        case K_FLOAT -> floats;
        case K_LONG -> longs;
        case K_DOUBLE -> doubles;
        default -> objects;
      });
      pushInt(regs);
      if (k == K_OBJ) {
        main.visitInsn(AALOAD);
        main.visitTypeInsn(CHECKCAST, internal(ret));
        main.visitInsn(ARETURN);
      } else {
        main.visitInsn(switch (k) {
          case K_INT -> IALOAD;
          case K_FLOAT -> FALOAD;
          case K_LONG -> LALOAD;
          default -> DALOAD;
        });
        main.visitInsn(switch (k) {
          case K_INT -> IRETURN;
          case K_FLOAT -> FRETURN;
          case K_LONG -> LRETURN;
          default -> DRETURN;
        });
      }
    }
    main.visitMaxs(8, objects + 3);
    main.visitEnd();
  }

  private void emitChunk(int index, int[] range) {
    inChunk = true;
    mv = splitInto.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, chunkName(index), CHUNK_DESC, null, null);
    mv.visitCode();
    int firstInsn = blockStart[range[0]];
    int endInsn = blockStart[range[1]];
    Label[] labels = new Label[count + 1];
    for (int i = 0; i <= count; i++) labels[i] = new Label();
    Label chunkEnd = new Label();
    Map<Integer, Label> stubs = new HashMap<>();
    for (Try t : tries) {
      int si = addrToInsn[t.start];
      int ei = t.end <= unitCount ? addrToInsn[t.end] : count;
      if (si < 0 || ei < 0) continue;
      si = Math.max(si, firstInsn);
      ei = Math.min(ei, endInsn);
      if (si >= ei) continue;
      for (Handler h : t.handlers) {
        Label stub = stubs.computeIfAbsent(h.addr, x -> new Label());
        mv.visitTryCatchBlock(labels[si], ei == endInsn ? chunkEnd : labels[ei], stub, h.type == null ? null : internal(h.type));
      }
    }
    // Enter at the requested block.
    int blocks = range[1] - range[0];
    Label[] entry = new Label[blocks];
    for (int b = 0; b < blocks; b++) entry[b] = labels[blockStart[range[0] + b]];
    mv.visitVarInsn(ILOAD, L_BLOCK);
    mv.visitTableSwitchInsn(range[0], range[1] - 1, entry[0], entry);
    int[] s = new int[regs];
    for (int bl = range[0]; bl < range[1]; bl++) {
      boolean reachable = blockState[bl] != null;
      if (reachable) System.arraycopy(blockState[bl], 0, s, 0, regs);
      for (int i = blockStart[bl]; i < blockStart[bl + 1]; i++) {
        mv.visitLabel(labels[i]);
        if (reachable && lines != null && lines[i] != 0) mv.visitLineNumber(lines[i], labels[i]);
        if (reachable) emitInsn(i, s, labels);
      }
      // Falling out of a dead block is impossible, but the code after it must still be well formed.
    }
    mv.visitLabel(chunkEnd);
    // Falling off the end continues with the next chunk's first block.
    pushInt(range[1] < blockCount ? range[1] : -1);
    mv.visitInsn(IRETURN);
    for (var e : stubs.entrySet()) {
      int target = insnAt(e.getKey());
      mv.visitLabel(e.getValue());
      if (op[target] == 0x0d) {
        regStore(K_OBJ, a[target]);
      } else {
        mv.visitInsn(POP);
      }
      int resume = op[target] == 0x0d ? target + 1 : target;
      if (resume >= firstInsn && resume < endInsn) {
        mv.visitJumpInsn(GOTO, labels[resume]);
      } else {
        pushInt(blockOf[resume]); // A block start: buildBlocks splits after a handler's move-exception.
        mv.visitInsn(IRETURN);
      }
    }
    // Branches to code outside this chunk leave through here.
    for (int i = firstInsn; i < endInsn; i++) {
      for (int t : branchTargets(i)) {
        if (t >= firstInsn && t < endInsn) continue;
        if (!farDone.add(t * 100000L + index)) continue;
        mv.visitLabel(labels[t]);
        pushInt(blockOf[t]);
        mv.visitInsn(IRETURN);
      }
    }
    mv.visitMaxs(maxStack() + 4, L_TMP + 2);
    mv.visitEnd();
    inChunk = false;
  }

  private final java.util.Set<Long> farDone = new java.util.HashSet<>();

  private int[] branchTargets(int i) {
    int o = op[i];
    if (isGoto(o)) return new int[] {insnAt(a[i])};
    if (isIf(o)) return new int[] {insnAt(c[i])};
    if (isSwitch(o)) {
      int[] t = switchTargets(i);
      int[] out = new int[t.length + 1];
      for (int j = 0; j < t.length; j++) out[j] = insnAt(t[j]);
      out[t.length] = i + 1;
      return out;
    }
    return new int[0];
  }

  private int maxStack() {
    int max = 8;
    for (int i = 0; i < count; i++) {
      if (args[i] != null) max = Math.max(max, args[i].length + 8);
    }
    return max;
  }

  private void copyParams() {
    int in = 0;
    int r = regs - insWords;
    if (!isStatic) {
      mv.visitVarInsn(ALOAD, in++);
      mv.visitVarInsn(ASTORE, slot(r++));
    }
    for (String p : params) {
      int k = kindOf(p);
      boolean w = wideParam(p);
      int held = blockState[0][r] >= 0 ? resolved(blockState[0][r]) : k;
      mv.visitVarInsn(loadOp(k), in);
      if (held != k && held != K_OBJ && k != K_OBJ) {
        convert(k, held);
      } else {
        held = k;
      }
      mv.visitVarInsn(storeOp(held), slot(r));
      in += w ? 2 : 1;
      r += w ? 2 : 1;
    }
  }

  private static String internal(String desc) {
    return desc.charAt(0) == 'L' ? desc.substring(1, desc.length() - 1) : desc;
  }

  private void pushInt(int v) {
    if (v >= -1 && v <= 5) {
      mv.visitInsn(ICONST_0 + v);
    } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
      mv.visitIntInsn(BIPUSH, v);
    } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
      mv.visitIntInsn(SIPUSH, v);
    } else {
      mv.visitLdcInsn(v);
    }
  }

  private void emitInsn(int i, int[] s, Label[] labels) {
    int o = op[i];
    int ra = a[i];
    int rb = b[i];
    int rc = c[i];
    switch (o) {
      case 0x00:
        return;
      case 0x01:
      case 0x02:
      case 0x03:
      case 0x04:
      case 0x05:
      case 0x06:
      case 0x07:
      case 0x08:
      case 0x09: {
        boolean w = o >= 0x04 && o <= 0x06;
        int v = s[rb];
        if (v < 0) return; // Moving an undefined register: nothing can read the result.
        int k = resolved(v);
        regLoad(k, rb);
        regStore(k, ra);
        setDef(s, ra, v, w);
        return;
      }
      case 0x0a:
        store(s, i, ra, kindOf(resultType(i)), false);
        return;
      case 0x0b:
        store(s, i, ra, kindOf(resultType(i)), true);
        return;
      case 0x0c:
        store(s, i, ra, K_OBJ, false);
        return;
      case 0x0d:
        // The exception was already stored by the handler stub.
        setDef(s, ra, defNode[i], false);
        return;
      case 0x0e:
        if (inChunk) {
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IRETURN);
          return;
        }
        mv.visitInsn(RETURN);
        return;
      case 0x0f:
      case 0x10:
      case 0x11: {
        int k = kindOf(ret);
        load(s, ra, k);
        if (inChunk) {
          // Leave the result in the extra slot past the registers, for the driver to return.
          regStore(k, regs);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IRETURN);
          return;
        }
        mv.visitInsn(switch (k) {
          case K_INT -> IRETURN;
          case K_FLOAT -> FRETURN;
          case K_LONG -> LRETURN;
          case K_DOUBLE -> DRETURN;
          default -> ARETURN;
        });
        return;
      }
      case 0x12:
      case 0x13:
      case 0x14:
      case 0x15:
        emitConst32(i, s, ra, (int) lit[i]);
        return;
      case 0x16:
      case 0x17:
      case 0x18:
      case 0x19:
        emitConst64(i, s, ra, lit[i]);
        return;
      case 0x1a:
      case 0x1b:
        ldcString(dex.string(rb));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0x1c:
        mv.visitLdcInsn(Type.getType(dex.type(rb)));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0xfe:
        mv.visitLdcInsn(methodHandle(rb));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0xff:
        mv.visitLdcInsn(Type.getMethodType(DexFile.descriptor(dex.protoParamsById(rb), dex.protoReturnById(rb))));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0x1d:
        load(s, ra, K_OBJ);
        mv.visitInsn(MONITORENTER);
        return;
      case 0x1e:
        load(s, ra, K_OBJ);
        mv.visitInsn(MONITOREXIT);
        return;
      case 0x1f:
        load(s, ra, K_OBJ);
        mv.visitTypeInsn(CHECKCAST, dex.internalType(rb));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0x20:
        load(s, rb, K_OBJ);
        mv.visitTypeInsn(INSTANCEOF, dex.internalType(rc));
        store(s, i, ra, K_INT, false);
        return;
      case 0x21:
        load(s, rb, K_OBJ);
        mv.visitInsn(ARRAYLENGTH);
        store(s, i, ra, K_INT, false);
        return;
      case 0x22:
        mv.visitTypeInsn(NEW, instantiated(dex.internalType(rb)));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0x23:
        load(s, rb, K_INT);
        newArray(dex.type(rc).substring(1));
        store(s, i, ra, K_OBJ, false);
        return;
      case 0x24:
      case 0x25:
        emitFilledNewArray(i, s);
        return;
      case 0x26:
        emitFillArrayData(i, s);
        return;
      case 0x27:
        load(s, ra, K_OBJ);
        mv.visitInsn(ATHROW);
        return;
      case 0x28:
      case 0x29:
      case 0x2a:
        mv.visitJumpInsn(GOTO, labels[insnAt(ra)]);
        return;
      case 0x2b:
      case 0x2c:
        emitSwitch(i, s, labels);
        return;
      case 0x2d:
      case 0x2e:
        load(s, rb, K_FLOAT);
        load(s, rc, K_FLOAT);
        mv.visitInsn(o == 0x2d ? FCMPL : FCMPG);
        store(s, i, ra, K_INT, false);
        return;
      case 0x2f:
      case 0x30:
        load(s, rb, K_DOUBLE);
        load(s, rc, K_DOUBLE);
        mv.visitInsn(o == 0x2f ? DCMPL : DCMPG);
        store(s, i, ra, K_INT, false);
        return;
      case 0x31:
        load(s, rb, K_LONG);
        load(s, rc, K_LONG);
        mv.visitInsn(LCMP);
        store(s, i, ra, K_INT, false);
        return;
      default:
        break;
    }
    if (o >= 0x32 && o <= 0x37) {
      emitIf(o, s, ra, rb, labels[insnAt(rc)]);
      return;
    }
    if (o >= 0x38 && o <= 0x3d) {
      emitIfz(o, s, ra, labels[insnAt(rc)]);
      return;
    }
    if (o >= 0x44 && o <= 0x4a) {
      emitAget(i, o, s, ra, rb, rc);
      return;
    }
    if (o >= 0x4b && o <= 0x51) {
      emitAput(o, s, ra, rb, rc);
      return;
    }
    if (o >= 0x52 && o <= 0x58) {
      DexFile.FieldRef f = dex.field(rc);
      load(s, rb, K_OBJ);
      mv.visitFieldInsn(GETFIELD, f.ownerInternal(), f.name(), f.type());
      store(s, i, ra, kindOf(f.type()), o == 0x53);
      return;
    }
    if (o >= 0x59 && o <= 0x5f) {
      DexFile.FieldRef f = dex.field(rc);
      load(s, rb, K_OBJ);
      load(s, ra, kindOf(f.type()));
      mv.visitFieldInsn(PUTFIELD, f.ownerInternal(), f.name(), f.type());
      return;
    }
    if (o >= 0x60 && o <= 0x66) {
      DexFile.FieldRef f = dex.field(rb);
      if ("androidx/compose/foundation/lazy/layout/PrefetchScheduler_androidKt".equals(owner)
          && "android/os/Build".equals(f.ownerInternal()) && "FINGERPRINT".equals(f.name())) {
        // Compose's lazy layouts never prefetch when this reads "robolectric" (TestActivities.prefetchFingerprint).
        mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "prefetchFingerprint", "()Ljava/lang/String;", false);
      } else {
        mv.visitFieldInsn(GETSTATIC, f.ownerInternal(), f.name(), f.type());
      }
      store(s, i, ra, kindOf(f.type()), o == 0x61);
      return;
    }
    if (o >= 0x67 && o <= 0x6d) {
      DexFile.FieldRef f = dex.field(rb);
      load(s, ra, kindOf(f.type()));
      mv.visitFieldInsn(PUTSTATIC, f.ownerInternal(), f.name(), f.type());
      return;
    }
    if ((o >= 0x6e && o <= 0x72) || (o >= 0x74 && o <= 0x78)) {
      emitInvoke(i, o, s);
      return;
    }
    if (o == 0xfa || o == 0xfb) {
      emitInvokePolymorphic(i, s);
      return;
    }
    if (o == 0xfc || o == 0xfd) {
      emitInvokeCustom(i, s);
      return;
    }
    if (o >= 0x7b && o <= 0x8f) {
      emitUnary(i, o, s, ra, rb);
      return;
    }
    if (o >= 0x90 && o <= 0xaf) {
      int k = binKind(o - 0x90);
      load(s, rb, k);
      load(s, rc, isShift(o - 0x90) ? K_INT : k);
      mv.visitInsn(binOp(o - 0x90));
      store(s, i, ra, k, k == K_LONG || k == K_DOUBLE);
      return;
    }
    if (o >= 0xb0 && o <= 0xcf) {
      int k = binKind(o - 0xb0);
      load(s, ra, k);
      load(s, rb, isShift(o - 0xb0) ? K_INT : k);
      mv.visitInsn(binOp(o - 0xb0));
      store(s, i, ra, k, k == K_LONG || k == K_DOUBLE);
      return;
    }
    if (o >= 0xd0 && o <= 0xd7) {
      emitLit(i, o - 0xd0, LIT16_OPS, s, ra, rb);
      return;
    }
    if (o >= 0xd8 && o <= 0xe2) {
      emitLit(i, o - 0xd8, LIT8_OPS, s, ra, rb);
      return;
    }
    throw new IllegalStateException("unhandled opcode 0x" + Integer.toHexString(o));
  }

  private void ldcString(String v) {
    int bytes = 0;
    for (int i = 0; i < v.length(); i++) {
      char ch = v.charAt(i);
      bytes += ch != 0 && ch < 0x80 ? 1 : ch < 0x800 ? 2 : 3;
    }
    if (bytes <= 65535) {
      mv.visitLdcInsn(v);
      return;
    }
    // Longer than a JVM constant: rebuild it at runtime.
    mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
    mv.visitInsn(DUP);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
    for (int at = 0; at < v.length(); at += 20000) {
      mv.visitLdcInsn(v.substring(at, Math.min(v.length(), at + 20000)));
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    }
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
  }

  private void emitConst32(int i, int[] s, int r, int v) {
    int k = resolved(defNode[i]);
    switch (k) {
      case K_OBJ -> mv.visitInsn(ACONST_NULL);
      case K_FLOAT -> mv.visitLdcInsn(Float.intBitsToFloat(v));
      default -> pushInt(v);
    }
    regStore(k, r);
    setDef(s, r, defNode[i], false);
  }

  private void emitConst64(int i, int[] s, int r, long v) {
    int k = resolved(defNode[i]);
    if (k == K_DOUBLE) {
      mv.visitLdcInsn(Double.longBitsToDouble(v));
    } else {
      mv.visitLdcInsn(v);
    }
    regStore(k, r);
    setDef(s, r, defNode[i], true);
  }

  private void newArray(String el) {
    switch (el.charAt(0)) {
      case 'Z' -> mv.visitIntInsn(NEWARRAY, T_BOOLEAN);
      case 'B' -> mv.visitIntInsn(NEWARRAY, T_BYTE);
      case 'S' -> mv.visitIntInsn(NEWARRAY, T_SHORT);
      case 'C' -> mv.visitIntInsn(NEWARRAY, T_CHAR);
      case 'I' -> mv.visitIntInsn(NEWARRAY, T_INT);
      case 'J' -> mv.visitIntInsn(NEWARRAY, T_LONG);
      case 'F' -> mv.visitIntInsn(NEWARRAY, T_FLOAT);
      case 'D' -> mv.visitIntInsn(NEWARRAY, T_DOUBLE);
      default -> mv.visitTypeInsn(ANEWARRAY, internal(el));
    }
  }

  private void emitFilledNewArray(int i, int[] s) {
    String el = dex.type(b[i]).substring(1);
    int[] rs = args[i];
    pushInt(rs.length);
    newArray(el);
    int k = kindOf(el);
    int store = switch (el.charAt(0)) {
      case 'Z', 'B' -> BASTORE;
      case 'S' -> SASTORE;
      case 'C' -> CASTORE;
      case 'I' -> IASTORE;
      case 'F' -> FASTORE;
      default -> AASTORE;
    };
    for (int x = 0; x < rs.length; x++) {
      mv.visitInsn(DUP);
      pushInt(x);
      load(s, rs[x], k);
      mv.visitInsn(store);
    }
    int next = i + 1;
    if (next < count && op[next] == 0x0c) return; // move-result-object stores it.
    mv.visitInsn(POP);
  }

  private void emitFillArrayData(int i, int[] s) {
    int p = b[i];
    int width = unit(p + 1);
    int size = unit(p + 2) | unit(p + 3) << 16;
    int bytes = size * width;
    int dataAt = insnsStart + (p + 4) * 2;
    final int chunkElems = Math.max(1, 16000 / width);
    for (int at = 0; at < size; at += chunkElems) {
      int n = Math.min(chunkElems, size - at);
      char[] cs = new char[n * width];
      for (int j = 0; j < cs.length; j++) cs[j] = (char) dex.u8(dataAt + (at * width) + j);
      load(s, a[i], K_OBJ);
      pushInt(at);
      pushInt(n);
      pushInt(width);
      mv.visitLdcInsn(new String(cs));
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/dex/DexRt", "fill", "(Ljava/lang/Object;IIILjava/lang/String;)V", false);
    }
  }

  private void emitSwitch(int i, int[] s, Label[] labels) {
    int p = b[i];
    int size = unit(p + 1);
    int[] targets = switchTargets(i);
    Label[] ls = new Label[size];
    for (int j = 0; j < size; j++) ls[j] = labels[insnAt(targets[j])];
    Label dflt = labels[i + 1];
    load(s, a[i], K_INT);
    if (op[i] == 0x2b) {
      int first = unit(p + 2) | unit(p + 3) << 16;
      mv.visitTableSwitchInsn(first, first + size - 1, dflt, ls);
    } else {
      int[] keys = new int[size];
      for (int j = 0; j < size; j++) keys[j] = unit(p + 2 + j * 2) | unit(p + 3 + j * 2) << 16;
      mv.visitLookupSwitchInsn(dflt, keys, ls);
    }
  }

  private void emitIf(int o, int[] s, int ra, int rb, Label target) {
    int k = s[ra] >= 0 ? resolved(s[ra]) : K_INT;
    if (k == K_OBJ) {
      load(s, ra, K_OBJ);
      load(s, rb, K_OBJ);
      mv.visitJumpInsn(o == 0x32 ? IF_ACMPEQ : IF_ACMPNE, target);
    } else {
      load(s, ra, K_INT);
      load(s, rb, K_INT);
      mv.visitJumpInsn(IF_ICMPEQ + (o - 0x32), target);
    }
  }

  private void emitIfz(int o, int[] s, int ra, Label target) {
    int k = s[ra] >= 0 ? resolved(s[ra]) : K_INT;
    if (k == K_OBJ) {
      load(s, ra, K_OBJ);
      mv.visitJumpInsn(o == 0x38 ? IFNULL : IFNONNULL, target);
    } else {
      load(s, ra, K_INT);
      mv.visitJumpInsn(IFEQ + (o - 0x38), target);
    }
  }

  private void emitAget(int i, int o, int[] s, int ra, int rb, int rc) {
    String el = elem(arrayDesc(s, rb));
    load(s, rb, K_OBJ);
    load(s, rc, K_INT);
    switch (o) {
      case 0x44 -> {
        boolean f = "F".equals(el);
        mv.visitInsn(f ? FALOAD : IALOAD);
        store(s, i, ra, f ? K_FLOAT : K_INT, false);
      }
      case 0x45 -> {
        boolean dbl = "D".equals(el);
        mv.visitInsn(dbl ? DALOAD : LALOAD);
        store(s, i, ra, dbl ? K_DOUBLE : K_LONG, true);
      }
      case 0x46 -> {
        mv.visitInsn(AALOAD);
        store(s, i, ra, K_OBJ, false);
      }
      case 0x47, 0x48 -> {
        mv.visitInsn(BALOAD);
        store(s, i, ra, K_INT, false);
      }
      case 0x49 -> {
        mv.visitInsn(CALOAD);
        store(s, i, ra, K_INT, false);
      }
      default -> {
        mv.visitInsn(SALOAD);
        store(s, i, ra, K_INT, false);
      }
    }
  }

  private void emitAput(int o, int[] s, int ra, int rb, int rc) {
    String el = elem(arrayDesc(s, rb));
    load(s, rb, K_OBJ);
    load(s, rc, K_INT);
    switch (o) {
      case 0x4b -> {
        boolean f = "F".equals(el);
        load(s, ra, f ? K_FLOAT : K_INT);
        mv.visitInsn(f ? FASTORE : IASTORE);
      }
      case 0x4c -> {
        boolean dbl = "D".equals(el);
        load(s, ra, dbl ? K_DOUBLE : K_LONG);
        mv.visitInsn(dbl ? DASTORE : LASTORE);
      }
      case 0x4d -> {
        load(s, ra, K_OBJ);
        mv.visitInsn(AASTORE);
      }
      case 0x4e, 0x4f -> {
        load(s, ra, K_INT);
        mv.visitInsn(BASTORE);
      }
      case 0x50 -> {
        load(s, ra, K_INT);
        mv.visitInsn(CASTORE);
      }
      default -> {
        load(s, ra, K_INT);
        mv.visitInsn(SASTORE);
      }
    }
  }

  private void loadArgs(int[] s, int[] rs, int start, String[] ps) {
    int x = start;
    for (String p : ps) {
      load(s, rs[x], kindOf(p));
      x += wideParam(p) ? 2 : 1;
    }
  }

  private void afterCall(int i, String retType) {
    if (retType.charAt(0) == 'V') return;
    int next = i + 1;
    if (next < count && op[next] >= 0x0a && op[next] <= 0x0c) return; // move-result stores it.
    mv.visitInsn(wideParam(retType) ? POP2 : POP);
  }

  private void emitInvoke(int i, int o, int[] s) {
    DexFile.MethodRef m = dex.method(b[i]);
    int kind = o <= 0x72 ? o - 0x6e : o - 0x74;
    int[] rs = args[i];
    String ownerName = m.ownerInternal();
    if (kind == 3) {
      loadArgs(s, rs, 0, m.params());
      call(INVOKESTATIC, ownerName, m, !"<init>".equals(m.name()) && isInterface.test(ownerName));
    } else {
      load(s, rs[0], K_OBJ);
      loadArgs(s, rs, 1, m.params());
      switch (kind) {
        case 0 -> call(INVOKEVIRTUAL, ownerName, m, false);
        case 4 -> call(INVOKEINTERFACE, ownerName, m, true);
        default -> call(INVOKESPECIAL, ownerName, m, !"<init>".equals(m.name()) && isInterface.test(ownerName));
      }
    }
    afterCall(i, m.ret());
  }

  private static final String RUNTIME_SHADER = "android/graphics/RuntimeShader";
  private static final String TRACKED_SHADER = "dev/apksim/gpu/TrackedRuntimeShader";
  private static final String P = "Landroid/graphics/Paint;)V";
  private static final Set<String> GPU_DRAWS = Set.of(
      "drawRect(FFFF" + P, "drawRect(Landroid/graphics/RectF;" + P, "drawRect(Landroid/graphics/Rect;" + P,
      "drawRoundRect(FFFFFF" + P, "drawRoundRect(Landroid/graphics/RectF;FF" + P,
      "drawOval(FFFF" + P, "drawOval(Landroid/graphics/RectF;" + P, "drawCircle(FFF" + P,
      "drawArc(FFFFFFZ" + P, "drawArc(Landroid/graphics/RectF;FFZ" + P,
      "drawPath(Landroid/graphics/Path;" + P, "drawPaint(" + P);

  /**
   * Framework and library classes that app code gets an apksim subclass of, wherever it creates one or extends
   * one: shaders and layer effects that can run on the GPU, and libwebrtc's video decoding and drawing without GLES.
   */
  private static final Map<String, String> SUBSTITUTES = Map.of(
      RUNTIME_SHADER, TRACKED_SHADER,
      // Compose's graphicsLayer: runtime-shader RenderEffects over layer content run on Metal.
      "android/graphics/RenderNode", "dev/apksim/gpu/GpuRenderNode",
      "org/webrtc/DefaultVideoDecoderFactory", "dev/apksim/video/SimVideoDecoderFactory",
      "org/webrtc/EglRenderer", "dev/apksim/video/SimEglRenderer");

  /**
   * App calls to the framework's SQLite classes that go through dev.apksim.rec.Db, the flight recorder's db channel
   * (docs/flight-recorder.md): each becomes a static call taking the receiver first, which is the call itself while the
   * channel is off. Framework names, which R8 never renames. Listed here rather than read from Db, so translating
   * never loads it.
   */
  private static final java.util.Set<String> DB_HOOKS = java.util.Set.of(
      "android/database/sqlite/SQLiteDatabase.execSQL(Ljava/lang/String;)V",
      "android/database/sqlite/SQLiteDatabase.execSQL(Ljava/lang/String;[Ljava/lang/Object;)V",
      "android/database/sqlite/SQLiteDatabase.rawQuery(Ljava/lang/String;[Ljava/lang/String;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.rawQuery(Ljava/lang/String;[Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.rawQueryWithFactory(Landroid/database/sqlite/SQLiteDatabase$CursorFactory;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.rawQueryWithFactory(Landroid/database/sqlite/SQLiteDatabase$CursorFactory;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.query(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.query(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.query(ZLjava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.query(ZLjava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.queryWithFactory(Landroid/database/sqlite/SQLiteDatabase$CursorFactory;ZLjava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.queryWithFactory(Landroid/database/sqlite/SQLiteDatabase$CursorFactory;ZLjava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
      "android/database/sqlite/SQLiteDatabase.insert(Ljava/lang/String;Ljava/lang/String;Landroid/content/ContentValues;)J",
      "android/database/sqlite/SQLiteDatabase.insertOrThrow(Ljava/lang/String;Ljava/lang/String;Landroid/content/ContentValues;)J",
      "android/database/sqlite/SQLiteDatabase.replace(Ljava/lang/String;Ljava/lang/String;Landroid/content/ContentValues;)J",
      "android/database/sqlite/SQLiteDatabase.replaceOrThrow(Ljava/lang/String;Ljava/lang/String;Landroid/content/ContentValues;)J",
      "android/database/sqlite/SQLiteDatabase.insertWithOnConflict(Ljava/lang/String;Ljava/lang/String;Landroid/content/ContentValues;I)J",
      "android/database/sqlite/SQLiteDatabase.delete(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)I",
      "android/database/sqlite/SQLiteDatabase.update(Ljava/lang/String;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
      "android/database/sqlite/SQLiteDatabase.updateWithOnConflict(Ljava/lang/String;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;I)I",
      "android/database/sqlite/SQLiteDatabase.beginTransaction()V",
      "android/database/sqlite/SQLiteDatabase.beginTransactionNonExclusive()V",
      "android/database/sqlite/SQLiteDatabase.setTransactionSuccessful()V",
      "android/database/sqlite/SQLiteDatabase.endTransaction()V",
      "android/database/sqlite/SQLiteStatement.execute()V",
      "android/database/sqlite/SQLiteStatement.executeUpdateDelete()I",
      "android/database/sqlite/SQLiteStatement.executeInsert()J",
      "android/database/sqlite/SQLiteStatement.simpleQueryForLong()J",
      "android/database/sqlite/SQLiteStatement.simpleQueryForString()Ljava/lang/String;");

  /**
   * JDK network calls in app code that go through dev.apksim.net.Net (docs/network.md "Where it hooks"): sockets and
   * TLS layered on them, URL connections, and the app setting an HTTPS connection's socket factory. JDK names, which R8 never renames, so minified OkHttp is covered too.
   * Each maps owner.name+descriptor to Net's static method, which takes the receiver as its first argument.
   */
  private static final Map<String, String[]> NET_HOOKS = Map.ofEntries(
      Map.entry("java/net/Socket.connect(Ljava/net/SocketAddress;)V",
          new String[] {"connect", "(Ljava/net/Socket;Ljava/net/SocketAddress;)V"}),
      Map.entry("java/net/Socket.connect(Ljava/net/SocketAddress;I)V",
          new String[] {"connect", "(Ljava/net/Socket;Ljava/net/SocketAddress;I)V"}),
      Map.entry("javax/net/SocketFactory.createSocket(Ljava/lang/String;I)Ljava/net/Socket;",
          new String[] {"createSocket", "(Ljavax/net/SocketFactory;Ljava/lang/String;I)Ljava/net/Socket;"}),
      Map.entry("javax/net/SocketFactory.createSocket(Ljava/net/InetAddress;I)Ljava/net/Socket;",
          new String[] {"createSocket", "(Ljavax/net/SocketFactory;Ljava/net/InetAddress;I)Ljava/net/Socket;"}),
      Map.entry("javax/net/ssl/SSLSocketFactory.createSocket(Ljava/lang/String;I)Ljava/net/Socket;",
          new String[] {"createSocket", "(Ljavax/net/SocketFactory;Ljava/lang/String;I)Ljava/net/Socket;"}),
      Map.entry("javax/net/ssl/SSLSocketFactory.createSocket(Ljava/net/InetAddress;I)Ljava/net/Socket;",
          new String[] {"createSocket", "(Ljavax/net/SocketFactory;Ljava/net/InetAddress;I)Ljava/net/Socket;"}),
      Map.entry("javax/net/ssl/SSLSocketFactory.createSocket(Ljava/net/Socket;Ljava/lang/String;IZ)Ljava/net/Socket;",
          new String[] {"createSocket", "(Ljavax/net/ssl/SSLSocketFactory;Ljava/net/Socket;Ljava/lang/String;IZ)Ljava/net/Socket;"}),
      Map.entry("java/net/URL.openConnection()Ljava/net/URLConnection;",
          new String[] {"openConnection", "(Ljava/net/URL;)Ljava/net/URLConnection;"}),
      Map.entry("java/net/URL.openConnection(Ljava/net/Proxy;)Ljava/net/URLConnection;",
          new String[] {"openConnection", "(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;"}),
      Map.entry("java/net/URL.openStream()Ljava/io/InputStream;",
          new String[] {"openStream", "(Ljava/net/URL;)Ljava/io/InputStream;"}),
      Map.entry("javax/net/ssl/HttpsURLConnection.setSSLSocketFactory(Ljavax/net/ssl/SSLSocketFactory;)V",
          new String[] {"setSSLSocketFactory", "(Ljavax/net/ssl/HttpsURLConnection;Ljavax/net/ssl/SSLSocketFactory;)V"}));

  /** The class app code should instantiate, or extend, in place of [type]. */
  static String instantiated(String type) {
    return SUBSTITUTES.getOrDefault(type, type);
  }

  private void call(int opcode, String ownerName, DexFile.MethodRef m, boolean itf) {
    if (opcode == INVOKEVIRTUAL && "android/graphics/Canvas".equals(ownerName) && GPU_DRAWS.contains(m.name() + m.descriptor())) {
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/gpu/GpuCanvas", m.name(), "(Landroid/graphics/Canvas;" + m.descriptor().substring(1), false);
      return;
    }
    if (opcode == INVOKESTATIC && "android/graphics/RenderEffect".equals(ownerName)
        && ("createRuntimeShaderEffect".equals(m.name()) || "createBlurEffect".equals(m.name()))) {
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/gpu/GpuEffects", m.name(), m.descriptor(), false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && ownerName.startsWith("android/database/sqlite/SQLite")
        && DB_HOOKS.contains(ownerName + "." + m.name() + m.descriptor())) {
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/rec/Db", m.name(), "(L" + ownerName + ";" + m.descriptor().substring(1), false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && (ownerName.startsWith("java/net/") || ownerName.startsWith("javax/net/"))) {
      String[] hook = NET_HOOKS.get(ownerName + "." + m.name() + m.descriptor());
      if (hook == null && "javax/net/ssl/SSLSocket".equals(ownerName) && "connect".equals(m.name())) {
        hook = NET_HOOKS.get("java/net/Socket." + m.name() + m.descriptor());
      }
      if (hook != null) {
        mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/net/Net", hook[0], hook[1], false);
        return;
      }
    }
    if (opcode == INVOKESPECIAL && "<init>".equals(m.name()) && SUBSTITUTES.containsKey(ownerName)) {
      mv.visitMethodInsn(opcode, instantiated(ownerName), m.name(), m.descriptor(), false);
      return;
    }
    if (opcode == INVOKESTATIC && "androidx/compose/ui/test/AndroidSynchronization_androidKt".equals(ownerName)
        && "runOnUiThread".equals(m.name())) {
      // On a device this posts behind what the main thread already has queued (Compose's snapshot apply among it);
      // a Robolectric test on the main thread would run it at once. Let the queue go first (dev.apksim.TestActivities).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "beforeUiThreadAction", "()V", false);
    }
    if (opcode == INVOKESPECIAL && "androidx/compose/ui/test/RobolectricIdlingStrategy".equals(ownerName) && "<init>".equals(m.name())
        && "(Landroidx/compose/ui/test/ComposeRootRegistry;Landroidx/compose/ui/test/ComposeIdlingResource;)V".equals(m.descriptor())) {
      // A Compose test rule in Robolectric mode: note its idling resource, which Espresso's waits then wait for, as on a
      // device (dev.apksim.TestActivities.composeIdling). The resource is on top of the stack, the strategy below it.
      mv.visitInsn(DUP);
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "composeIdling", "(Ljava/lang/Object;)V", false);
    }
    if ((opcode == INVOKEINTERFACE || opcode == INVOKEVIRTUAL) && "loopMainThreadUntilIdle".equals(m.name())
        && "()V".equals(m.descriptor()) && ownerName.startsWith("androidx/test/espresso/")) {
      // Espresso's wait for the main thread: on a device it also waits for Compose (dev.apksim.TestActivities).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "espressoLoopMainThreadUntilIdle", "(Ljava/lang/Object;)V", false);
      return;
    }
    if (opcode == INVOKESTATIC && "java/lang/Thread".equals(ownerName) && "sleep".equals(m.name())
        && ("(J)V".equals(m.descriptor()) || "(JI)V".equals(m.descriptor()))) {
      // A test sleeping on the main thread lets the main queue run meanwhile, as a device's own main thread would.
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "sleep", m.descriptor(), false);
      return;
    }
    if (opcode == INVOKESTATIC && "java/lang/System".equals(ownerName) && "exit".equals(m.name()) && "(I)V".equals(m.descriptor())) {
      // The app ending its process: said in the log with the caller, and the code reaches a warm client (dev.apksim.Exits).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/Exits", "systemExit", "(I)V", false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && "java/lang/Runtime".equals(ownerName) && ("exit".equals(m.name()) || "halt".equals(m.name()))
        && "(I)V".equals(m.descriptor())) {
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/Exits", "exit".equals(m.name()) ? "runtimeExit" : "runtimeHalt",
          "(Ljava/lang/Runtime;I)V", false);
      return;
    }
    if (clock(opcode, ownerName, m)) return;
    if (opcode == INVOKESPECIAL && "<init>".equals(m.name()) && "(Z)V".equals(m.descriptor())
        && "androidx/compose/foundation/text/input/internal/CursorAnimationState".equals(ownerName)) {
      // Compose's caret blinks unless made with animate=false; --snapshot holds it (dev.apksim.Snapshot).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/Snapshot", "cursorBlink", "(Z)Z", false);
    }
    if (opcode == INVOKESPECIAL && "androidx/test/runner/AndroidJUnitRunner".equals(ownerName) && "onCreate".equals(m.name())
        && "(Landroid/os/Bundle;)V".equals(m.descriptor())) {
      // A runner's super.onCreate(arguments): apksim runs the runner's own onCreate up to here (TestRunnerSetup).
      mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(owner));
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestRunnerSetup", "superOnCreate",
          "(Ljava/lang/Object;Landroid/os/Bundle;Ljava/lang/Class;)V", false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && "android/app/Instrumentation".equals(ownerName) && "getContext".equals(m.name())
        && "()Landroid/content/Context;".equals(m.descriptor())) {
      // The test APK's own context, as on a device (Robolectric's instrumentation returns the app's).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "instrumentationContext",
          "(Landroid/app/Instrumentation;)Landroid/content/Context;", false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && "java/util/concurrent/CountDownLatch".equals(ownerName) && "await".equals(m.name())
        && "(JLjava/util/concurrent/TimeUnit;)Z".equals(m.descriptor()) && "androidx/test/uiautomator/InteractionController".equals(owner)) {
      // UiAutomator's wait for a frame after each gesture step; on the main thread in a test run, the main queue
      // draws that frame meanwhile (TestActivities.awaitFrame).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "awaitFrame",
          "(Ljava/util/concurrent/CountDownLatch;JLjava/util/concurrent/TimeUnit;)Z", false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && "java/util/concurrent/CountDownLatch".equals(ownerName) && "await".equals(m.name())
        && ("()V".equals(m.descriptor()) || "(JLjava/util/concurrent/TimeUnit;)Z".equals(m.descriptor()))) {
      // A test waiting for main-thread work: on the main thread in a test run, the main queue runs meanwhile.
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "await",
          "(Ljava/util/concurrent/CountDownLatch;" + m.descriptor().substring(1), false);
      return;
    }
    if (opcode == INVOKESTATIC && "java/util/concurrent/locks/LockSupport".equals(ownerName) && "parkNanos".equals(m.name())
        && ("(Ljava/lang/Object;J)V".equals(m.descriptor()) || "(J)V".equals(m.descriptor()))) {
      // runBlocking's event loop parks here; on the main thread in a test run, the main queue runs meanwhile.
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/TestActivities", "parkNanos", m.descriptor(), false);
      return;
    }
    if (opcode == INVOKESTATIC && "java/security/KeyStore".equals(ownerName) && "getDefaultType".equals(m.name())) {
      // Android's default keystore type, BKS, with Bouncy Castle to read it (KeyStores).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/KeyStores", "getDefaultType", "()Ljava/lang/String;", false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && "javax/crypto/Cipher".equals(ownerName) && "init".equals(m.name())
        && ("(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V".equals(m.descriptor())
            || "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;Ljava/security/SecureRandom;)V".equals(m.descriptor()))) {
      // AES/GCM set up again with the same key and IV, which Android's provider allows and the JDK's refuses (Ciphers).
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/Ciphers", "init", "(Ljavax/crypto/Cipher;" + m.descriptor().substring(1), false);
      return;
    }
    if (opcode == INVOKESTATIC && "java/lang/System".equals(ownerName) && ("loadLibrary".equals(m.name()) || "load".equals(m.name()))) {
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/Natives", m.name(), m.descriptor(), false);
      return;
    }
    if (opcode == INVOKEVIRTUAL && "java/lang/Runtime".equals(ownerName) && ("loadLibrary".equals(m.name()) || "load".equals(m.name()))) {
      mv.visitMethodInsn(INVOKESTATIC, "dev/apksim/Natives", "loadLibrary".equals(m.name()) ? "runtimeLoadLibrary" : "runtimeLoad",
          "(Ljava/lang/Runtime;Ljava/lang/String;)V", false);
      return;
    }
    mv.visitMethodInsn(opcode, ownerName, m.name(), m.descriptor(), itf);
  }

  /** java.time classes whose now() and now(ZoneId) app code reads the wall clock through. */
  private static final Set<String> TIME_NOW = Set.of("java/time/Instant", "java/time/LocalDate", "java/time/LocalTime",
      "java/time/LocalDateTime", "java/time/ZonedDateTime", "java/time/OffsetDateTime", "java/time/OffsetTime",
      "java/time/Year", "java/time/YearMonth", "java/time/MonthDay");

  /**
   * Reads of the wall clock go to dev.apksim.SimClock, which `clock ISO` can set (screenshot tests): the same
   * methods, so with the clock real nothing changes but one static call. Emits the call and returns true if [m] is one.
   */
  private boolean clock(int opcode, String ownerName, DexFile.MethodRef m) {
    String name = m.name();
    String d = m.descriptor();
    if (opcode == INVOKESTATIC) {
      if ("java/lang/System".equals(ownerName) && "currentTimeMillis".equals(name)) {
        mv.visitMethodInsn(INVOKESTATIC, SIM_CLOCK, name, d, false);
        return true;
      }
      if (TIME_NOW.contains(ownerName) && "now".equals(name) && (d.startsWith("()") || d.startsWith("(Ljava/time/ZoneId;)"))) {
        // X.now() = X.now(Clock.systemDefaultZone()), X.now(zone) = X.now(Clock.system(zone)): the same, shifted.
        mv.visitMethodInsn(INVOKESTATIC, SIM_CLOCK, "clock", d.startsWith("()") ? "()Ljava/time/Clock;"
            : "(Ljava/time/ZoneId;)Ljava/time/Clock;", false);
        mv.visitMethodInsn(INVOKESTATIC, ownerName, name, "(Ljava/time/Clock;)" + d.substring(d.indexOf(')') + 1), false);
        return true;
      }
      if ("java/time/Clock".equals(ownerName) && ("systemUTC".equals(name) || "systemDefaultZone".equals(name) || "system".equals(name))) {
        mv.visitMethodInsn(INVOKESTATIC, SIM_CLOCK, name, d, false);
        return true;
      }
      if ("java/util/Calendar".equals(ownerName) && "getInstance".equals(name)) {
        mv.visitMethodInsn(INVOKESTATIC, SIM_CLOCK, "calendar", d, false);
        return true;
      }
    }
    if (opcode == INVOKESPECIAL && "java/util/Date".equals(ownerName) && "<init>".equals(name) && "()V".equals(d)) {
      mv.visitMethodInsn(INVOKESTATIC, SIM_CLOCK, "currentTimeMillis", "()J", false);
      mv.visitMethodInsn(INVOKESPECIAL, ownerName, name, "(J)V", false);
      return true;
    }
    return false;
  }

  private static final String SIM_CLOCK = "dev/apksim/SimClock";

  private void emitInvokePolymorphic(int i, int[] s) {
    DexFile.MethodRef m = dex.method(b[i]);
    int protoIdx = (int) lit[i];
    String[] ps = dex.protoParamsById(protoIdx);
    String r = dex.protoReturnById(protoIdx);
    int[] rs = args[i];
    load(s, rs[0], K_OBJ);
    loadArgs(s, rs, 1, ps);
    mv.visitMethodInsn(INVOKEVIRTUAL, m.ownerInternal(), m.name(), DexFile.descriptor(ps, r), false);
    afterCall(i, r);
  }

  private Handle methodHandle(int idx) {
    int p = dex.methodHandleOffset(idx);
    int kind = dex.u16(p);
    int member = dex.u16(p + 4);
    if (kind <= 3) {
      DexFile.FieldRef f = dex.field(member);
      int tag = switch (kind) {
        case 0 -> H_PUTSTATIC;
        case 1 -> H_GETSTATIC;
        case 2 -> H_PUTFIELD;
        default -> H_GETFIELD;
      };
      return new Handle(tag, f.ownerInternal(), f.name(), f.type(), false);
    }
    DexFile.MethodRef m = dex.method(member);
    int tag = switch (kind) {
      case 4 -> H_INVOKESTATIC;
      case 5 -> H_INVOKEVIRTUAL;
      case 6 -> H_NEWINVOKESPECIAL;
      case 7 -> H_INVOKESPECIAL;
      default -> H_INVOKEINTERFACE;
    };
    String o = m.ownerInternal();
    // The app's own exits, as for a call (dev.apksim.Exits): a handle to Runtime's takes the receiver first.
    if ("(I)V".equals(m.descriptor())) {
      if (kind == 4 && "java/lang/System".equals(o) && "exit".equals(m.name())) {
        return new Handle(H_INVOKESTATIC, "dev/apksim/Exits", "systemExit", "(I)V", false);
      }
      if (kind == 5 && "java/lang/Runtime".equals(o) && ("exit".equals(m.name()) || "halt".equals(m.name()))) {
        return new Handle(H_INVOKESTATIC, "dev/apksim/Exits", "exit".equals(m.name()) ? "runtimeExit" : "runtimeHalt",
            "(Ljava/lang/Runtime;I)V", false);
      }
    }
    return new Handle(tag, o, m.name(), m.descriptor(), kind == 8 || (kind != 6 && isInterface.test(o)));
  }

  /** Returns {name, descriptor, bootstrap handle, bootstrap args...}. */
  private Object[] callSite(int idx) {
    DexFile.Cursor cur = dex.new Cursor(dex.callSiteOffset(idx));
    int size = cur.uleb();
    Object[] parts = new Object[size];
    for (int j = 0; j < size; j++) {
      int header = cur.u1();
      int type = header & 0x1f;
      int argc = header >>> 5;
      long v = 0;
      for (int k = 0; k <= argc; k++) v |= (long) cur.u1() << (8 * k);
      parts[j] = switch (type) {
        case 0x17 -> dex.string((int) v);
        case 0x15 -> Type.getMethodType(DexFile.descriptor(dex.protoParamsById((int) v), dex.protoReturnById((int) v)));
        case 0x16 -> methodHandle((int) v);
        case 0x18 -> Type.getType(dex.type((int) v));
        case 0x04 -> (int) signExtend(v, argc);
        case 0x06 -> signExtend(v, argc);
        case 0x10 -> Float.intBitsToFloat((int) (v << (8 * (3 - argc))));
        case 0x11 -> Double.longBitsToDouble(v << (8 * (7 - argc)));
        default -> throw new IllegalStateException("call site value 0x" + Integer.toHexString(type));
      };
    }
    // Canonical order in dex: bootstrap handle, name, method type, extra args.
    Object[] out = new Object[size];
    out[0] = parts[1];
    out[1] = ((Type) parts[2]).getDescriptor();
    out[2] = parts[0];
    System.arraycopy(parts, 3, out, 3, size - 3);
    return out;
  }

  private static long signExtend(long v, int argc) {
    int shift = 64 - 8 * (argc + 1);
    return v << shift >> shift;
  }

  private void emitInvokeCustom(int i, int[] s) {
    Object[] site = callSite(b[i]);
    String d = (String) site[1];
    Type[] ts = Type.getArgumentTypes(d);
    String[] ps = new String[ts.length];
    for (int t = 0; t < ts.length; t++) ps[t] = ts[t].getDescriptor();
    loadArgs(s, args[i], 0, ps);
    mv.visitInvokeDynamicInsn((String) site[0], d, (Handle) site[2], Arrays.copyOfRange(site, 3, site.length));
    afterCall(i, Type.getReturnType(d).getDescriptor());
  }

  private void emitUnary(int i, int o, int[] s, int ra, int rb) {
    int[] io = unaryKinds(o);
    load(s, rb, io[0]);
    switch (o) {
      case 0x7b -> mv.visitInsn(INEG);
      case 0x7c -> {
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IXOR);
      }
      case 0x7d -> mv.visitInsn(LNEG);
      case 0x7e -> {
        mv.visitLdcInsn(-1L);
        mv.visitInsn(LXOR);
      }
      case 0x7f -> mv.visitInsn(FNEG);
      case 0x80 -> mv.visitInsn(DNEG);
      case 0x81 -> mv.visitInsn(I2L);
      case 0x82 -> mv.visitInsn(I2F);
      case 0x83 -> mv.visitInsn(I2D);
      case 0x84 -> mv.visitInsn(L2I);
      case 0x85 -> mv.visitInsn(L2F);
      case 0x86 -> mv.visitInsn(L2D);
      case 0x87 -> mv.visitInsn(F2I);
      case 0x88 -> mv.visitInsn(F2L);
      case 0x89 -> mv.visitInsn(F2D);
      case 0x8a -> mv.visitInsn(D2I);
      case 0x8b -> mv.visitInsn(D2L);
      case 0x8c -> mv.visitInsn(D2F);
      case 0x8d -> mv.visitInsn(I2B);
      case 0x8e -> mv.visitInsn(I2C);
      default -> mv.visitInsn(I2S);
    }
    store(s, i, ra, io[1], io[1] == K_LONG || io[1] == K_DOUBLE);
  }

  private static int binOp(int k) {
    return switch (k) {
      case 0x00 -> IADD;
      case 0x01 -> ISUB;
      case 0x02 -> IMUL;
      case 0x03 -> IDIV;
      case 0x04 -> IREM;
      case 0x05 -> IAND;
      case 0x06 -> IOR;
      case 0x07 -> IXOR;
      case 0x08 -> ISHL;
      case 0x09 -> ISHR;
      case 0x0a -> IUSHR;
      case 0x0b -> LADD;
      case 0x0c -> LSUB;
      case 0x0d -> LMUL;
      case 0x0e -> LDIV;
      case 0x0f -> LREM;
      case 0x10 -> LAND;
      case 0x11 -> LOR;
      case 0x12 -> LXOR;
      case 0x13 -> LSHL;
      case 0x14 -> LSHR;
      case 0x15 -> LUSHR;
      case 0x16 -> FADD;
      case 0x17 -> FSUB;
      case 0x18 -> FMUL;
      case 0x19 -> FDIV;
      case 0x1a -> FREM;
      case 0x1b -> DADD;
      case 0x1c -> DSUB;
      case 0x1d -> DMUL;
      case 0x1e -> DDIV;
      default -> DREM;
    };
  }

  // JVM operations of the lit16 (add-int/lit16..xor-int/lit16) and lit8 (..ushr-int/lit8) forms; -1 is rsub.
  private static final int[] LIT16_OPS = {IADD, -1, IMUL, IDIV, IREM, IAND, IOR, IXOR};
  private static final int[] LIT8_OPS = {IADD, -1, IMUL, IDIV, IREM, IAND, IOR, IXOR, ISHL, ISHR, IUSHR};

  private void emitLit(int i, int k, int[] ops, int[] s, int ra, int rb) {
    int v = (int) lit[i];
    if (k == 1) { // rsub: literal minus register.
      pushInt(v);
      load(s, rb, K_INT);
      mv.visitInsn(ISUB);
    } else {
      load(s, rb, K_INT);
      pushInt(v);
      mv.visitInsn(ops[k]);
    }
    store(s, i, ra, K_INT, false);
  }
}
