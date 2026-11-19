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
    if (Files.isDirectory(path)) return checkMulti(path);
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

  /**
   * A folder of classes.dex, classes2.dex...: one app over several DEX files, as R8 writes it, each class translated
   * with the whole app's hierarchy (as AppClasses does) and fixture.MultiImpl.run() the check.
   */
  private static boolean checkMulti(Path dir) throws Exception {
    java.util.List<Path> files;
    try (var st = Files.list(dir)) {
      files = st.filter(f -> f.toString().endsWith(".dex")).sorted().toList();
    }
    java.util.List<DexFile> dexes = new java.util.ArrayList<>();
    Map<String, int[]> index = new HashMap<>();
    for (Path f : files) {
      DexFile d = new DexFile(ByteBuffer.wrap(Files.readAllBytes(f)));
      for (int i = 0; i < d.classDefsSize; i++) index.putIfAbsent(internal(d.className(i)), new int[] {dexes.size(), i});
      dexes.add(d);
    }
    ClassLoader parent = TranslatorCheck.class.getClassLoader();
    Hierarchy all = (cls, name, desc) -> {
      int[] at = index.get(cls);
      if (at == null) return null;
      DexFile d = dexes.get(at[0]);
      return new Hierarchy.Shape(d.superclassOf(at[1]), d.interfacesOf(at[1]), d.declaresMethod(at[1], name, desc));
    };
    ClassLoader loader = new ClassLoader(parent) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        int[] at = index.get(name.replace('.', '/'));
        if (at == null) throw new ClassNotFoundException(name);
        DexFile d = dexes.get(at[0]);
        byte[] b = new ClassTranslator(d, at[1], owner -> {
          int[] o = index.get(owner);
          if (o != null) return (dexes.get(o[0]).classAccess(o[1]) & Opcodes.ACC_INTERFACE) != 0;
          try {
            return Class.forName(owner.replace('/', '.'), false, parent).isInterface();
          } catch (ClassNotFoundException | LinkageError e) {
            return false;
          }
        }, all).translate();
        return defineClass(name, b, 0, b.length);
      }
    };
    String failures = (String) loader.loadClass("fixture.MultiImpl").getMethod("run").invoke(null);
    if (!failures.isEmpty()) {
      System.err.print("translator check failed: " + dir.getFileName() + "\n" + failures);
      return false;
    }
    System.out.println("translator check passed: " + dir.getFileName() + " (" + index.size() + " classes in " + dexes.size() + " DEX files)");
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
