package dev.apksim;

import java.nio.file.Path;

/**
 * The machine apksim runs on. On a Mac it has a window, Metal and VideoToolbox; elsewhere (Linux containers,
 * CI) it runs headless, with shaders on the CPU and no hardware video decoding.
 */
public final class HostOs {
  public static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");
  private static final boolean X86_64 = System.getProperty("os.arch", "").matches("amd64|x86_64");

  private HostOs() {}

  /** One of apksim's own native libraries, by base name ("libelfloader"). */
  public static Path runtimeLib(String baseName) {
    return Path.of(System.getProperty("apksim.runtime"), baseName + (MAC ? ".dylib" : ".so"));
  }

  /** The APK's native code directory that matches this CPU. */
  static String apkAbi() {
    return X86_64 ? "x86_64" : "arm64-v8a";
  }
}
