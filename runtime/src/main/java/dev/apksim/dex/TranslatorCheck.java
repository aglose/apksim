package dev.apksim.dex;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.Opcodes;

/**
 * Translates .dex fixtures with apksim's translator, loads each into a plain JVM and runs its
 * {@code fixture.Checks.run()} (scripts/check-translator.sh, CI). The fixtures (testapks/translator-fixture) are the
 * same Java built with R8 and with D8 in debug mode, in shapes the translator once miscompiled: they need the
 * compilers' register reuse across ints, floats and objects, which ordinary test code rarely gets. Needs no Android
 * framework: the fixture uses only java.*.
 */
public final class TranslatorCheck {
  private TranslatorCheck() {}

  public static void main(String[] args) throws Exception {
    boolean failed = false;
    for (String path : args) failed |= !check(Path.of(path));
    if (failed) System.exit(1);
  }

  private static boolean check(Path path) throws Exception {
    DexFile dex = new DexFile(ByteBuffer.wrap(Files.readAllBytes(path)));
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < dex.classDefsSize; i++) index.put(internal(dex.className(i)), i);
    ClassLoader parent = TranslatorCheck.class.getClassLoader();
    ClassLoader loader = new ClassLoader(parent) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        Integer at = index.get(name.replace('.', '/'));
        if (at == null) throw new ClassNotFoundException(name);
        byte[] b = new ClassTranslator(dex, at, owner -> isInterface(dex, index, parent, owner)).translate();
        return defineClass(name, b, 0, b.length);
      }
    };
    String failures = (String) loader.loadClass("fixture.Checks").getMethod("run").invoke(null);
    if (!failures.isEmpty()) {
      System.err.print("translator check failed: " + path.getFileName() + "\n" + failures);
      return false;
    }
    System.out.println("translator check passed: " + path.getFileName() + " (" + index.size() + " classes)");
    return true;
  }

  private static boolean isInterface(DexFile dex, Map<String, Integer> index, ClassLoader parent, String owner) {
    Integer at = index.get(owner);
    if (at != null) return (dex.classAccess(at) & Opcodes.ACC_INTERFACE) != 0;
    try {
      return Class.forName(owner.replace('/', '.'), false, parent).isInterface();
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** "Lfixture/Checks;" -> "fixture/Checks". */
  private static String internal(String descriptor) {
    return descriptor.substring(1, descriptor.length() - 1);
  }
}
