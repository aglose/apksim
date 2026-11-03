package dev.apksim;

import java.nio.file.Path;

/** Target of rewritten System/Runtime library-loading calls from app code. */
public final class Natives {
  private Natives() {}

  public static void loadLibrary(String name) {
    ElfLoader.load("lib" + name + ".so");
    Timing.mark("loaded lib" + name + ".so");
  }

  public static void load(String path) {
    ElfLoader.load(Path.of(path).getFileName().toString());
  }

  public static void runtimeLoadLibrary(Runtime rt, String name) {
    loadLibrary(name);
  }

  public static void runtimeLoad(Runtime rt, String path) {
    load(path);
  }
}
