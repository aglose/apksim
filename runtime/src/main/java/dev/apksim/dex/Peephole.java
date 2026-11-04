package dev.apksim.dex;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Shrinks translated methods. A Dalvik instruction reads registers and writes one, so the translation of each is
 * load, operation, store (one Dalvik register is one JVM local), about twice the bytecodes javac writes: a getter is
 * {@code aload_0 astore_2 aload_2 getfield istore_1 iload_1 ireturn}. Interpreted code runs every one of them, and
 * the JIT's inlining limits count bytes (C2 always inlines methods under 35). Two rewrites, both exact:
 *
 * <ul>
 *   <li>A parameter copied into its register slot (the translator's prologue) is read from the parameter itself when
 *       neither slot is written again, and the copy is dropped.
 *   <li>{@code xstore n; xload n} next to each other, where nothing jumps in between and n is not read again before
 *       being written (liveness, exception handlers included), become nothing: the value stays on the stack.
 * </ul>
 *
 * The getter above becomes {@code aload_0 getfield ireturn}. Methods with debug info are left alone (their local
 * variable tables name the slots). Classes are written without stack map frames and verification is off, and an
 * adjacent store/load pair leaves the stack at the depth it had, so max stack holds.
 *
 * <p>It runs on every method of every class translated, so its data is flat: per-node ints, basic blocks as index
 * ranges, edges in packed arrays, and live slots as bit words per block (one long for a method of up to 64 slots).
 * Liveness is solved per block (a block's nodes share their exception handlers, since try bounds start blocks) and
 * walked per node only inside blocks that hold a candidate pair. Per-node BitSets, boxed maps and work lists were
 * ~40% of translation time and half its allocation.
 */
final class Peephole {
  private Peephole() {}

  static void optimize(MethodNode m) {
    if (m.instructions.size() == 0 || (m.localVariables != null && !m.localVariables.isEmpty())) return;
    InsnList list = m.instructions;
    int count = list.size();
    list.get(0); // Builds the list's index: get(i) and indexOf are array and field reads from here on.
    // One walk of the nodes into flat arrays; everything after works on those.
    int[] op = new int[count];
    int[] kind = new int[count];
    int[] var = new int[count];
    int slots = m.maxLocals;
    boolean branches = false;
    for (int i = 0; i < count; i++) {
      AbstractInsnNode n = list.get(i);
      int o = n.getOpcode();
      op[i] = o;
      if (n instanceof VarInsnNode v) {
        var[i] = v.var;
        boolean wide = o == LLOAD || o == DLOAD || o == LSTORE || o == DSTORE;
        kind[i] = (isStore(o) ? DEF : USE) | (wide ? WIDE : 0);
        slots = Math.max(slots, v.var + (wide ? 2 : 1));
      } else if (n instanceof IincInsnNode inc) {
        var[i] = inc.var;
        kind[i] = USE | IINC;
        slots = Math.max(slots, inc.var + 1);
      } else if (o == JSR || o == RET) {
        return; // Subroutines: never produced by the translator; leave such a method alone.
      } else if (o >= 0 && (n instanceof JumpInsnNode || n instanceof TableSwitchInsnNode || n instanceof LookupSwitchInsnNode)) {
        branches = true;
      }
    }
    List<AbstractInsnNode> drop = new ArrayList<>();
    propagateParams(m, list, count, op, kind, var, slots, drop);
    dropStoreLoadPairs(m, list, count, op, kind, var, slots, branches, drop);
    for (AbstractInsnNode d : drop) list.remove(d);
  }

  // What a node does to local slots, packed in kind[]: whether it reads or writes var[] (and var + 1 when wide).
  // GONE marks a node a rewrite has already dropped: no longer there for the next one.
  private static final int USE = 1, DEF = 2, WIDE = 4, IINC = 8, GONE = 16;

  // ---------------------------------------------------------------- parameter copies

  private static void propagateParams(MethodNode m, InsnList list, int count, int[] op, int[] kind, int[] var, int slots,
                                      List<AbstractInsnNode> drop) {
    int paramSlots = (m.access & ACC_STATIC) != 0 ? 0 : 1;
    for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(m.desc)) paramSlots += t.getSize();
    // How often each slot is written anywhere in the method.
    int[] writes = new int[slots];
    for (int i = 0; i < count; i++) if ((kind[i] & (DEF | IINC)) != 0) writes[var[i]]++;
    int[] alias = null; // register slot -> parameter slot + 1
    // The prologue: xload p; xstore s pairs before anything else (labels and line numbers aside).
    int i = real(op, count, 0);
    while (i < count && isLoad(op[i]) && var[i] < paramSlots) {
      int next = real(op, count, i + 1);
      if (next >= count || op[next] != storeFor(op[i])) break;
      if (writes[var[i]] == 0 && writes[var[next]] == 1 && var[next] >= paramSlots && !targeted(m, list.get(i), list.get(next))) {
        if (alias == null) alias = new int[slots];
        alias[var[next]] = var[i] + 1;
        drop.add(list.get(i));
        drop.add(list.get(next));
        kind[i] = GONE;
        kind[next] = GONE;
        op[i] = -1;
        op[next] = -1;
      }
      i = real(op, count, next + 1);
    }
    if (alias == null) return;
    for (int k = 0; k < count; k++) {
      if ((kind[k] & (USE | IINC)) == USE && alias[var[k]] != 0) {
        var[k] = alias[var[k]] - 1;
        ((VarInsnNode) list.get(k)).var = var[k];
      }
    }
  }

  /** The first index from [i] on that is an instruction (not a label, line number, frame or dropped node). */
  private static int real(int[] op, int count, int i) {
    while (i < count && op[i] < 0) i++;
    return i;
  }

  // ---------------------------------------------------------------- store/load pairs

  private static void dropStoreLoadPairs(MethodNode m, InsnList list, int count, int[] op, int[] kind, int[] var, int slots,
                                         boolean branches, List<AbstractInsnNode> drop) {
    // Candidate pairs first: a store, then (past labels and line numbers) a load of the same slot. Most methods have
    // none worth the liveness below; whether a label between them is a jump target is checked once targets are known.
    long[] candidate = new long[(count + 63) >>> 6];
    int[] pairedStore = new int[count];
    boolean any = false;
    for (int i = 0; i < count; i++) {
      if ((kind[i] & DEF) == 0) continue;
      int j = real(op, count, i + 1);
      if (j >= count || (kind[j] & (USE | IINC)) != USE || var[j] != var[i] || op[j] != loadFor(op[i])) continue;
      set(candidate, j);
      pairedStore[j] = i;
      any = true;
      i = j;
    }
    if (!any) return;
    int words = (slots + 63) >>> 6;
    long[] target = new long[(count + 63) >>> 6]; // labels that jumps, switches or handlers lead to
    long[] leader = new long[(count + 64) >>> 6]; // nodes that start a basic block
    set(leader, 0);
    if (branches) {
      for (int i = 0; i < count; i++) {
        int o = op[i];
        if (o < 0 || kind[i] != 0) continue;
        AbstractInsnNode n = list.get(i);
        if (n instanceof JumpInsnNode j) {
          mark(list, target, leader, j.label);
          set(leader, i + 1);
        } else if (n instanceof TableSwitchInsnNode t) {
          mark(list, target, leader, t.dflt);
          for (LabelNode l : t.labels) mark(list, target, leader, l);
          set(leader, i + 1);
        } else if (n instanceof LookupSwitchInsnNode t) {
          mark(list, target, leader, t.dflt);
          for (LabelNode l : t.labels) mark(list, target, leader, l);
          set(leader, i + 1);
        }
      }
    }
    for (int i = 0; i < count; i++) {
      int o = op[i];
      if ((o >= IRETURN && o <= RETURN) || o == ATHROW) set(leader, i + 1);
    }
    List<TryCatchBlockNode> tries = m.tryCatchBlocks == null ? List.of() : m.tryCatchBlocks;
    for (TryCatchBlockNode t : tries) {
      mark(list, target, leader, t.handler);
      set(leader, list.indexOf(t.start));
      set(leader, list.indexOf(t.end));
    }
    // A label something jumps to between a store and its load: control can arrive there without the store.
    boolean anyLeft = false;
    for (int j = nextSet(candidate, 0, count); j < count; j = nextSet(candidate, j + 1, count)) {
      for (int k = pairedStore[j] + 1; k < j; k++) {
        if (get(target, k)) {
          clear(candidate, j);
          break;
        }
      }
      anyLeft |= get(candidate, j);
    }
    if (!anyLeft) return;

    // Basic blocks: block b is nodes [start[b], start[b + 1]).
    int blocks = 0;
    for (int w = 0; w < (count + 63) >>> 6; w++) blocks += Long.bitCount(w == count >>> 6 ? leader[w] & ((1L << count) - 1) : leader[w]);
    int[] start = new int[blocks + 1];
    int[] blockOf = new int[count];
    for (int i = 0, b = -1; i < count; i++) {
      if (get(leader, i)) start[++b] = i;
      blockOf[i] = b;
    }
    start[blocks] = count;

    // Edges, packed: succ[succAt[b] .. succAt[b + 1]), and the handler blocks of each block likewise.
    int[] succAt = new int[blocks + 1];
    int[] succ = new int[blocks * 2];
    int edges = 0;
    for (int b = 0; b < blocks; b++) {
      succAt[b] = edges;
      int last = start[b + 1] - 1;
      int o = op[last];
      AbstractInsnNode n = branches && o >= 0 && kind[last] == 0 ? list.get(last) : null;
      if (n instanceof JumpInsnNode j) {
        succ = push(succ, edges++, blockOf[list.indexOf(j.label)]);
        if (o != GOTO && last + 1 < count) succ = push(succ, edges++, b + 1);
      } else if (n instanceof TableSwitchInsnNode t) {
        succ = push(succ, edges++, blockOf[list.indexOf(t.dflt)]);
        for (LabelNode l : t.labels) succ = push(succ, edges++, blockOf[list.indexOf(l)]);
      } else if (n instanceof LookupSwitchInsnNode t) {
        succ = push(succ, edges++, blockOf[list.indexOf(t.dflt)]);
        for (LabelNode l : t.labels) succ = push(succ, edges++, blockOf[list.indexOf(l)]);
      } else if (!((o >= IRETURN && o <= RETURN) || o == ATHROW) && last + 1 < count) {
        succ = push(succ, edges++, b + 1);
      }
    }
    succAt[blocks] = edges;
    int[] handlerAt = new int[blocks + 1];
    int[] handler = new int[0];
    if (!tries.isEmpty()) {
      int[] n = new int[blocks];
      for (TryCatchBlockNode t : tries) {
        for (int b = blockOf[list.indexOf(t.start)], end = list.indexOf(t.end); b < blocks && start[b] < end; b++) n[b]++;
      }
      for (int b = 0; b < blocks; b++) handlerAt[b + 1] = handlerAt[b] + n[b];
      handler = new int[handlerAt[blocks]];
      int[] fill = new int[blocks];
      for (TryCatchBlockNode t : tries) {
        int h = blockOf[list.indexOf(t.handler)];
        for (int b = blockOf[list.indexOf(t.start)], end = list.indexOf(t.end); b < blocks && start[b] < end; b++) {
          handler[handlerAt[b] + fill[b]++] = h;
        }
      }
    }
    // Predecessors (by flow or exception) of each block, for the work list.
    int[] predAt = new int[blocks + 1];
    for (int e = 0; e < edges; e++) predAt[succ[e] + 1]++;
    for (int e = 0; e < handler.length; e++) predAt[handler[e] + 1]++;
    for (int b = 0; b < blocks; b++) predAt[b + 1] += predAt[b];
    int[] pred = new int[predAt[blocks]];
    int[] fill = new int[blocks];
    for (int b = 0; b < blocks; b++) {
      for (int e = succAt[b]; e < succAt[b + 1]; e++) pred[predAt[succ[e]] + fill[succ[e]]++] = b;
      for (int e = handlerAt[b]; e < handlerAt[b + 1]; e++) pred[predAt[handler[e]] + fill[handler[e]]++] = b;
    }

    // Each block's reads before any write (gen) and writes (kill); then in(b) = gen + (out - kill) + in(handlers),
    // out(b) = in(successors). Every node of a block shares its handlers, so this is the per-node result composed.
    long[] gen = new long[blocks * words];
    long[] kill = new long[blocks * words];
    long[] in = new long[blocks * words];
    for (int b = 0; b < blocks; b++) {
      int o = b * words;
      for (int i = start[b + 1] - 1; i >= start[b]; i--) {
        int k = kind[i];
        if ((k & (USE | DEF)) == 0) continue;
        for (int s = var[i], e = s + ((k & WIDE) != 0 ? 2 : 1); s < e; s++) {
          long bit = 1L << s;
          if ((k & DEF) != 0) {
            gen[o + (s >>> 6)] &= ~bit;
            kill[o + (s >>> 6)] |= bit;
          } else {
            gen[o + (s >>> 6)] |= bit;
          }
        }
      }
    }
    int[] queue = new int[blocks];
    long[] queued = new long[(blocks + 63) >>> 6];
    int head = 0, size = blocks;
    for (int b = 0; b < blocks; b++) {
      queue[b] = blocks - 1 - b;
      set(queued, b);
    }
    long[] out = new long[words];
    long[] next = new long[words];
    while (size > 0) {
      int b = queue[head];
      head = head + 1 == blocks ? 0 : head + 1;
      size--;
      clear(queued, b);
      outOf(b, succAt, succ, in, words, out);
      int o = b * words;
      boolean changed = false;
      for (int w = 0; w < words; w++) next[w] = gen[o + w] | (out[w] & ~kill[o + w]);
      for (int e = handlerAt[b]; e < handlerAt[b + 1]; e++) {
        for (int w = 0; w < words; w++) next[w] |= in[handler[e] * words + w];
      }
      for (int w = 0; w < words; w++) {
        if (next[w] != in[o + w]) {
          in[o + w] = next[w];
          changed = true;
        }
      }
      if (!changed) continue;
      for (int e = predAt[b]; e < predAt[b + 1]; e++) {
        int p = pred[e];
        if (get(queued, p)) continue;
        set(queued, p);
        queue[head + size < blocks ? head + size : head + size - blocks] = p;
        size++;
      }
    }

    // Walk back through each block holding a candidate load: what is live just after it decides.
    long[] live = out;
    for (int b = 0; b < blocks; b++) {
      int first = start[b], last = start[b + 1] - 1;
      if (nextSet(candidate, first, last + 1) > last) continue;
      outOf(b, succAt, succ, in, words, live);
      for (int i = last; i >= first; i--) {
        int k = kind[i];
        if (get(candidate, i)) {
          int s = var[i];
          if (!bit(live, s) && !((k & WIDE) != 0 && bit(live, s + 1))) {
            drop.add(list.get(pairedStore[i]));
            drop.add(list.get(i));
          }
        }
        if ((k & (USE | DEF)) != 0) {
          for (int s = var[i], e = s + ((k & WIDE) != 0 ? 2 : 1); s < e; s++) {
            if ((k & DEF) != 0) live[s >>> 6] &= ~(1L << s);
            else live[s >>> 6] |= 1L << s;
          }
        }
        for (int e = handlerAt[b]; e < handlerAt[b + 1]; e++) {
          for (int w = 0; w < words; w++) live[w] |= in[handler[e] * words + w];
        }
      }
    }
  }

  /** The first set bit at or after [from], or [limit] if none before it. */
  private static int nextSet(long[] bits, int from, int limit) {
    for (int i = from; i < limit; ) {
      long w = bits[i >>> 6] >>> i;
      if (w != 0) {
        int at = i + Long.numberOfTrailingZeros(w);
        return Math.min(at, limit);
      }
      i = (i | 63) + 1;
    }
    return limit;
  }

  /** out(b): the union of what is live into b's successors. */
  private static void outOf(int b, int[] succAt, int[] succ, long[] in, int words, long[] out) {
    java.util.Arrays.fill(out, 0);
    for (int e = succAt[b]; e < succAt[b + 1]; e++) {
      int o = succ[e] * words;
      for (int w = 0; w < words; w++) out[w] |= in[o + w];
    }
  }

  private static void mark(InsnList list, long[] target, long[] leader, LabelNode l) {
    int i = list.indexOf(l);
    set(target, i);
    set(leader, i);
  }

  private static int[] push(int[] a, int at, int v) {
    if (at == a.length) a = java.util.Arrays.copyOf(a, Math.max(4, a.length * 2));
    a[at] = v;
    return a;
  }

  private static boolean bit(long[] words, int i) {
    return (words[i >>> 6] & (1L << i)) != 0;
  }

  private static boolean get(long[] bits, int i) {
    return (bits[i >>> 6] & (1L << i)) != 0;
  }

  private static void set(long[] bits, int i) {
    bits[i >>> 6] |= 1L << i;
  }

  private static void clear(long[] bits, int i) {
    bits[i >>> 6] &= ~(1L << i);
  }

  /** Whether a label between two instructions is a jump or handler target (control can arrive in between). */
  private static boolean targeted(MethodNode m, AbstractInsnNode from, AbstractInsnNode to) {
    for (AbstractInsnNode n = from.getNext(); n != to; n = n.getNext()) {
      if (!(n instanceof LabelNode l)) continue;
      for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
        if (i instanceof JumpInsnNode j && j.label == l) return true;
        if (i instanceof TableSwitchInsnNode t && (t.dflt == l || t.labels.contains(l))) return true;
        if (i instanceof LookupSwitchInsnNode t && (t.dflt == l || t.labels.contains(l))) return true;
      }
      if (m.tryCatchBlocks != null) for (TryCatchBlockNode t : m.tryCatchBlocks) if (t.handler == l) return true;
    }
    return false;
  }

  private static boolean isStore(int op) {
    return op >= ISTORE && op <= ASTORE;
  }

  private static boolean isLoad(int op) {
    return op >= ILOAD && op <= ALOAD;
  }

  private static int storeFor(int load) {
    return load - ILOAD + ISTORE;
  }

  private static int loadFor(int store) {
    return store - ISTORE + ILOAD;
  }
}
