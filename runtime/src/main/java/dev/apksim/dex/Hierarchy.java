package dev.apksim.dex;

/**
 * What the translator may ask about the classes of the whole app, which one DEX file does not hold: R8 puts a class
 * and its superclass or interfaces in different files (classes4.dex and classes9.dex). {@code null} means the class is
 * not the app's (the framework, a library on the class path).
 */
@FunctionalInterface
public interface Hierarchy {
  /**
   * A class's superclass (null for none), its listed interfaces, and whether it declares the method
   * {@code name + descriptor} itself.
   */
  record Shape(String superclass, String[] interfaces, boolean declares) {}

  Shape of(String internalName, String methodName, String descriptor);

  /** One DEX file's view: what the translator knew before the app's other files were consulted. */
  static Hierarchy of(DexFile dex) {
    return (cls, name, desc) -> {
      int def = dex.classDefIndex(cls);
      if (def < 0) return null;
      return new Shape(dex.superclassOf(def), dex.interfacesOf(def), dex.declaresMethod(def, name, desc));
    };
  }
}
