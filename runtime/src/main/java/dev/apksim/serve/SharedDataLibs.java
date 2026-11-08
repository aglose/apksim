package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The APK's arm64 libraries that macOS cannot map as they are (issue 171): linked for 4 KB pages, with writable data
 * outside RELRO on a 16 KB page with code. The native loader refuses them unless the launch has `--move-shared-data`,
 * so `apksim beam` adds that flag itself for a build that has one: an app a Linux serve runs (4 KB pages there) then
 * starts on the Mac rather than exiting with an UnsatisfiedLinkError and falling back to the server. The test is
 * `code_page_conflict` in native/elfloader.c, on the program headers alone; keep the two the same.
 */
final class SharedDataLibs {
  private static final String DIR = "lib/arm64-v8a/";
  private static final int PAGE = 16384;
  private static final int PT_LOAD = 1, PT_GNU_RELRO = 0x6474e552, PF_X = 1, PF_W = 2;
  /** Program headers sit near the start of the file; more than this to reach them is not a library we can judge. */
  private static final int MAX_HEADERS = 64 * 1024;
  private static final Map<String, List<String>> BY_BUILD = new ConcurrentHashMap<>();

  private SharedDataLibs() {}

  /** The APK entries of such libraries, empty for none; remembered by build (its SHA-256) when one is given. */
  static List<String> in(Path apk, String build) throws IOException {
    if (build != null) {
      List<String> known = BY_BUILD.get(build);
      if (known != null) return known;
    }
    List<String> found = new ArrayList<>();
    try (ZipFile zip = new ZipFile(apk.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String n = e.getName();
        if (!n.startsWith(DIR) || !n.endsWith(".so") || n.indexOf('/', DIR.length()) >= 0) continue;
        try (InputStream in = zip.getInputStream(e)) {
          if (conflict(in.readNBytes(MAX_HEADERS))) found.add(n);
        }
      }
    }
    List<String> result = List.copyOf(found);
    if (build != null) BY_BUILD.put(build, result);
    return result;
  }

  /** Whether this ELF's start (header and program headers) shows writable non-RELRO data on a 16 KB page with code. */
  static boolean conflict(byte[] head) {
    if (head.length < 64 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F' || head[4] != 2 || head[5] != 1) {
      return false;
    }
    ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
    long phoff = b.getLong(32);
    int phentsize = Short.toUnsignedInt(b.getShort(54));
    int phnum = Short.toUnsignedInt(b.getShort(56));
    if (phentsize < 56 || phoff < 0 || phoff + (long) phentsize * phnum > head.length) return false;
    int[] type = new int[phnum], flags = new int[phnum];
    long[] va = new long[phnum], mem = new long[phnum];
    long rl = 0, rh = 0;
    for (int i = 0; i < phnum; i++) {
      int at = (int) phoff + i * phentsize;
      type[i] = b.getInt(at);
      flags[i] = b.getInt(at + 4);
      va[i] = b.getLong(at + 16);
      mem[i] = b.getLong(at + 40);
      if (type[i] == PT_GNU_RELRO) {
        rl = va[i];
        rh = va[i] + mem[i];
      }
    }
    for (int i = 0; i < phnum; i++) {
      if (type[i] != PT_LOAD || (flags[i] & PF_X) == 0) continue;
      long from = va[i] & -PAGE;
      long to = (va[i] + mem[i] + PAGE - 1) & -PAGE;
      for (int j = 0; j < phnum; j++) {
        if (type[j] != PT_LOAD || (flags[j] & PF_W) == 0) continue;
        long ws = Math.max(va[j], from);
        long we = Math.min(va[j] + mem[j], to);
        if (ws >= we) continue;
        long bad = we;
        if (rh <= rl || ws < rl) bad = ws;             // writable bytes before RELRO (or no RELRO at all)
        else if (we > rh) bad = Math.max(ws, rh);      // writable bytes after RELRO
        if (bad < we) return true;
      }
    }
    return false;
  }
}
