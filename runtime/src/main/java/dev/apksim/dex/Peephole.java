package dev.apksim.dex;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 */
final class Peephole {
  private Peephole() {}

  static void optimize(MethodNode m) {
    if (m.instructions.size() == 0 || (m.localVariables != null && !m.localVariables.isEmpty())) return;
    propagateParams(m);
    dropStoreLoadPairs(m);
  }

  // ---------------------------------------------------------------- parameter copies

  private static void propagateParams(MethodNode m) {
    int paramSlots = (m.access & ACC_STATIC) != 0 ? 0 : 1;
    for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(m.desc)) paramSlots += t.getSize();
    // How often each slot is written anywhere in the method.
    Map<Integer, Integer> writes = new HashMap<>();
    for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
      if (n instanceof VarInsnNode v && isStore(v.getOpcode())) writes.merge(v.var, 1, Integer::sum);
      if (n instanceof IincInsnNode i) writes.merge(i.var, 1, Integer::sum);
    }
    Map<Integer, Integer> alias = new HashMap<>(); // register slot -> parameter slot
    Set<AbstractInsnNode> drop = new HashSet<>();
    // The prologue: xload p; xstore s pairs before anything else (labels and line numbers aside).
    AbstractInsnNode n = real(m.instructions.getFirst());
    while (n instanceof VarInsnNode load && isLoad(load.getOpcode()) && load.var < paramSlots) {
      AbstractInsnNode next = real(n.getNext());
      if (!(next instanceof VarInsnNode store) || store.getOpcode() != storeFor(load.getOpcode())) break;
      if (!writes.containsKey(load.var) && writes.getOrDefault(store.var, 0) == 1 && store.var >= paramSlots
          && !targeted(m, n, next)) {
        alias.put(store.var, load.var);
        drop.add(load);
        drop.add(store);
      }
      n = real(next.getNext());
    }
    if (alias.isEmpty()) return;
    for (AbstractInsnNode d : drop) m.instructions.remove(d);
    for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
      if (i instanceof VarInsnNode v && isLoad(v.getOpcode())) {
        Integer p = alias.get(v.var);
        if (p != null) v.var = p;
      }
    }
  }

  // ---------------------------------------------------------------- store/load pairs

  private static void dropStoreLoadPairs(MethodNode m) {
    InsnList list = m.instructions;
    AbstractInsnNode[] insns = list.toArray();
    int count = insns.length;
    Map<LabelNode, Integer> at = new HashMap<>();
    for (int i = 0; i < count; i++) if (insns[i] instanceof LabelNode l) at.put(l, i);
    Set<LabelNode> targets = new HashSet<>();
    int[][] succ = new int[count][];
    for (int i = 0; i < count; i++) {
      AbstractInsnNode n = insns[i];
      int op = n.getOpcode();
      if (n instanceof JumpInsnNode j) {
        targets.add(j.label);
        succ[i] = op == GOTO ? new int[] {at.get(j.label)} : new int[] {at.get(j.label), i + 1};
      } else if (n instanceof TableSwitchInsnNode t) {
        succ[i] = switchSucc(at, targets, t.dflt, t.labels);
      } else if (n instanceof LookupSwitchInsnNode t) {
        succ[i] = switchSucc(at, targets, t.dflt, t.labels);
      } else if ((op >= IRETURN && op <= RETURN) || op == ATHROW || op == RET || op == JSR) {
        succ[i] = op == JSR || op == RET ? null : new int[0];
      } else {
        succ[i] = i + 1 < count ? new int[] {i + 1} : new int[0];
      }
      if (succ[i] == null) return;  // Subroutines: never produced by the translator; leave such a method alone.
    }
    // Exception edges: anything in a protected range may continue at its handler, with the locals it has then.
    int[][] handlers = new int[count][];
    if (m.tryCatchBlocks != null) {
      for (TryCatchBlockNode t : m.tryCatchBlocks) {
        targets.add(t.handler);
        int h = at.get(t.handler);
        for (int i = at.get(t.start); i < at.get(t.end); i++) {
          int[] old = handlers[i];
          int[] grown = old == null ? new int[1] : java.util.Arrays.copyOf(old, old.length + 1);
          grown[grown.length - 1] = h;
          handlers[i] = grown;
        }
      }
    }
    // Backward liveness of local slots: in(i) = use(i) + (out(i) - def(i)) + in(handlers of i).
    BitSet[] in = new BitSet[count];
    BitSet[] out = new BitSet[count];
    for (int i = 0; i < count; i++) {
      in[i] = new BitSet();
      out[i] = new BitSet();
    }
    int[][] pred = predecessors(succ, handlers, count);
    ArrayDeque<Integer> work = new ArrayDeque<>();
    boolean[] queued = new boolean[count];
    for (int i = count - 1; i >= 0; i--) {
      work.add(i);
      queued[i] = true;
    }
    BitSet scratch = new BitSet();
    while (!work.isEmpty()) {
      int i = work.poll();
      queued[i] = false;
      BitSet o = out[i];
      o.clear();
      for (int s : succ[i]) if (s < count) o.or(in[s]);
      scratch.clear();
      scratch.or(o);
      AbstractInsnNode n = insns[i];
      if (n instanceof VarInsnNode v) {
        int size = v.getOpcode() == LLOAD || v.getOpcode() == DLOAD || v.getOpcode() == LSTORE || v.getOpcode() == DSTORE ? 2 : 1;
        if (isStore(v.getOpcode())) {
          scratch.clear(v.var, v.var + size);
        } else {
          scratch.set(v.var, v.var + size);
        }
      } else if (n instanceof IincInsnNode inc) {
        scratch.set(inc.var);
      }
      if (handlers[i] != null) for (int h : handlers[i]) scratch.or(in[h]);
      if (!scratch.equals(in[i])) {
        in[i].clear();
        in[i].or(scratch);
        for (int p : pred[i]) {
          if (!queued[p]) {
            queued[p] = true;
            work.add(p);
          }
        }
      }
    }
    for (int i = 0; i < count; i++) {
      if (!(insns[i] instanceof VarInsnNode store) || !isStore(store.getOpcode())) continue;
      int j = i + 1;
      boolean entered = false;
      while (j < count && insns[j].getOpcode() < 0) {
        if (insns[j] instanceof LabelNode l && targets.contains(l)) entered = true;
        j++;
      }
      if (entered || j >= count || !(insns[j] instanceof VarInsnNode load) || load.var != store.var
          || load.getOpcode() != loadFor(store.getOpcode())) {
        continue;
      }
      int size = store.getOpcode() == LSTORE || store.getOpcode() == DSTORE ? 2 : 1;
      if (out[j].get(store.var) || (size == 2 && out[j].get(store.var + 1))) continue;
      list.remove(store);
      list.remove(load);
      i = j;
    }
  }

  private static int[] switchSucc(Map<LabelNode, Integer> at, Set<LabelNode> targets, LabelNode dflt, java.util.List<LabelNode> labels) {
    int[] s = new int[labels.size() + 1];
    s[0] = at.get(dflt);
    targets.add(dflt);
    for (int k = 0; k < labels.size(); k++) {
      s[k + 1] = at.get(labels.get(k));
      targets.add(labels.get(k));
    }
    return s;
  }

  private static int[][] predecessors(int[][] succ, int[][] handlers, int count) {
    int[] n = new int[count];
    for (int i = 0; i < count; i++) {
      for (int s : succ[i]) if (s < count) n[s]++;
      if (handlers[i] != null) for (int h : handlers[i]) n[h]++;
    }
    int[][] pred = new int[count][];
    for (int i = 0; i < count; i++) pred[i] = new int[n[i]];
    int[] fill = new int[count];
    for (int i = 0; i < count; i++) {
      for (int s : succ[i]) if (s < count) pred[s][fill[s]++] = i;
      if (handlers[i] != null) for (int h : handlers[i]) pred[h][fill[h]++] = i;
    }
    return pred;
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

  /** The next instruction that is not a label, line number or frame. */
  private static AbstractInsnNode real(AbstractInsnNode n) {
    while (n != null && n.getOpcode() < 0) n = n.getNext();
    return n;
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
