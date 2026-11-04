package dev.apksim;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loads the APK's Android .so files (native/elfloader.c) and connects them to Java's native methods. */
final class ElfLoader {
  private static final String ABI_DIR = "lib/" + HostOs.apkAbi() + "/";
  private static Path libDir;

  private ElfLoader() {}

  private static synchronized Path extracted() throws IOException {
    if (libDir != null) return libDir;
    Path dir = Path.of(System.getProperty("apksim.cache"), "lib");
    Files.createDirectories(dir);
    try (ZipFile zip = new ZipFile(System.getProperty("apksim.apk"))) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        if (!e.getName().startsWith(ABI_DIR) || !e.getName().endsWith(".so")) continue;
        Path out = dir.resolve(e.getName().substring(ABI_DIR.length()));
        Path crc = dir.resolve(out.getFileName() + ".crc");
        String want = Long.toHexString(e.getCrc());
        if (Files.exists(out) && Files.exists(crc) && Files.readString(crc).equals(want)) continue;
        // Several processes can extract at once (instrument.py --isolate starts four after an APK changes): each writes its
        // own temporary file and renames it into place, so none loads a library another is still writing or replacing.
        // The CRC, not the size, says whether the file is this APK's (a rebuilt library can keep its size).
        String tag = Storage.tmpTag();
        Path tmp = dir.resolve(out.getFileName() + tag);
        try (InputStream in = zip.getInputStream(e)) {
          Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Path crcTmp = dir.resolve(crc.getFileName() + tag);
        Files.writeString(crcTmp, want);
        Files.move(crcTmp, crc, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
    }
    System.load(HostOs.runtimeLib("libelfloader").toString());
    libDir = dir;
    return dir;
  }

  static synchronized void load(String soName) {
    try {
      Path dir = extracted();
      Path so = dir.resolve(soName);
      if (!Files.exists(so)) throw new UnsatisfiedLinkError("no " + soName + " for " + HostOs.apkAbi() + " in the APK");
      long handle = nativeLoad(so.toString(), dir.toString());
      bindJavaSymbols(handle);
    } catch (IOException e) {
      throw new UnsatisfiedLinkError(e.toString());
    }
  }

  /** JNI's by-name lookup cannot see ELF symbols, so register each exported Java_* function explicitly. */
  private static void bindJavaSymbols(long handle) {
    String all = nativeJavaSymbols(handle);
    if (all.isEmpty()) return;
    for (String sym : all.split("\n")) {
      try {
        bindOne(handle, sym);
      } catch (ClassNotFoundException | LinkageError e) {
        // The class is not in this APK, or was stripped: nothing can call this symbol.
      }
    }
  }

  private static void bindOne(long handle, String sym) throws ClassNotFoundException {
    String body = sym.substring("Java_".length());
    String signaturePart = null;
    int dbl = body.indexOf("__");
    if (dbl >= 0) {
      signaturePart = body.substring(dbl + 2);
      body = body.substring(0, dbl);
    }
    int lastSep = lastSeparator(body);
    if (lastSep < 0) return;
    String className = demangle(body.substring(0, lastSep)).replace('/', '.');
    String methodName = demangle(body.substring(lastSep + 1));
    Class<?> cls = Class.forName(className, false, ElfLoader.class.getClassLoader());
    for (Method m : cls.getDeclaredMethods()) {
      if (!Modifier.isNative(m.getModifiers()) || !m.getName().equals(methodName)) continue;
      String desc = descriptor(m);
      if (signaturePart != null && !mangle(desc.substring(1, desc.indexOf(')'))).equals(signaturePart)) continue;
      nativeBind(handle, cls, methodName, desc, sym);
    }
  }

  /** The '_' between class and method: the last one not part of an escape (_0, _1, _2, _3). */
  private static int lastSeparator(String s) {
    for (int i = s.length() - 1; i >= 0; i--) {
      if (s.charAt(i) != '_') continue;
      char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
      if (next < '0' || next > '3') return i;
    }
    return -1;
  }

  private static String demangle(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '_') {
        b.append(c);
        continue;
      }
      char n = i + 1 < s.length() ? s.charAt(i + 1) : 0;
      switch (n) {
        case '1' -> {
          b.append('_');
          i++;
        }
        case '2' -> {
          b.append(';');
          i++;
        }
        case '3' -> {
          b.append('[');
          i++;
        }
        case '0' -> {
          b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
          i += 5;
        }
        default -> b.append('/');
      }
    }
    return b.toString();
  }

  private static String mangle(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) {
      switch (c) {
        case '/' -> b.append('_');
        case '_' -> b.append("_1");
        case ';' -> b.append("_2");
        case '[' -> b.append("_3");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  private static String descriptor(Method m) {
    StringBuilder b = new StringBuilder("(");
    for (Class<?> p : m.getParameterTypes()) b.append(p.descriptorString());
    return b.append(')').append(m.getReturnType().descriptorString()).toString();
  }

  private static native long nativeLoad(String path, String searchDir);

  private static native String nativeJavaSymbols(long handle);

  private static native void nativeBind(long handle, Class<?> target, String name, String signature, String symbol);
}
