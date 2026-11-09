package dev.apksim;

import java.nio.file.Path;

/** Target of rewritten System/Runtime library-loading calls from app code. */
public final class Natives {
  private Natives() {}

  /**
   * The app loaded a SQLite library of its own (androidx.sqlite's bundled driver, SQLCipher...). The flight recorder's
   * checkpoints then do not open the app's databases with the framework's SQLite: POSIX locks are per process, and a
   * second library closing a file would drop the app's library's locks on it.
   */
  public static volatile boolean ownSqlite;

  public static void loadLibrary(String name) {
    if (name.toLowerCase(java.util.Locale.ROOT).contains("sqlite")) ownSqlite = true;
    ElfLoader.load("lib" + name + ".so");
    Timing.mark("loaded lib" + name + ".so");
  }

  public static void load(String path) {
    if (path.toLowerCase(java.util.Locale.ROOT).contains("sqlite")) ownSqlite = true;
    ElfLoader.load(Path.of(path).getFileName().toString());
  }

  public static void runtimeLoadLibrary(Runtime rt, String name) {
    loadLibrary(name);
  }

  public static void runtimeLoad(Runtime rt, String path) {
    load(path);
  }
}
