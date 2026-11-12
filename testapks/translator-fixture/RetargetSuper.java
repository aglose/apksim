import java.nio.file.*;
import org.objectweb.asm.*;

/** build.sh: rewrites fixture/SuperCall$Impl's invokespecial of SuperCall$Mid.who to SuperCall$Base.who. */
public class RetargetSuper {
  public static void main(String[] a) throws Exception {
    for (String c : new String[] {"SuperCall$Impl.class"}) {
    Path p = Path.of(a[0], "fixture", c);
    ClassReader cr = new ClassReader(Files.readAllBytes(p));
    ClassWriter cw = new ClassWriter(0);
    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
      @Override
      public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, n, d, s, e)) {
          @Override
          public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
            if (op == Opcodes.INVOKESPECIAL && o.equals("fixture/SuperCall$Mid")) o = "fixture/SuperCall$Base";
            super.visitMethodInsn(op, o, mn, md, itf);
          }
        };
      }
    }, 0);
    Files.write(p, cw.toByteArray());
    }
  }
}
