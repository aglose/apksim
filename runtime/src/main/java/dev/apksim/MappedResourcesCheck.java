package dev.apksim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.robolectric.res.android.CppApkAssets;
import org.robolectric.res.android.ResStringPool;
import org.robolectric.res.android.ResourceString;

/**
 * Checks a mapped resource table against one read into an array (scripts/check-mapped-resources.sh, on a plain JVM).
 * First {@code ResourceString.decodeString} on a buffer with no array, for each encoding, at an offset, with strings
 * past the one-byte length forms. Then, for each file given (`system:PATH` for the framework jar, else an APK), the
 * table loaded as apksim loads it and one line printed: whether it was mapped, how many strings its value pool has,
 * how many are not ASCII, and a hash over all of them. The script runs this with the switch on (twice: the first
 * inflates a deflated table into the cache, the second finds it there) and off, and compares the lines.
 */
public final class MappedResourcesCheck {
  public static void main(String[] args) throws Exception {
    decoding();
    for (String arg : args) {
      boolean system = arg.startsWith("system:");
      String path = system ? arg.substring(7) : arg;
      int before = MappedResources.mapped();
      CppApkAssets assets = CppApkAssets.Load(path, system);
      if (assets == null) throw new AssertionError("did not load: " + path);
      boolean mapped = MappedResources.mapped() > before;
      ResStringPool pool = assets.GetLoadedArsc().GetStringPool();
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      int n = pool == null ? 0 : pool.size();
      int nonAscii = 0;
      for (int i = 0; i < n; i++) {
        String s = pool.stringAt(i);
        if (s == null) {
          sha.update((byte) 0xff);
          continue;
        }
        if (!s.chars().allMatch(c -> c < 0x80)) nonAscii++;
        sha.update(s.getBytes(StandardCharsets.UTF_16BE));
        sha.update((byte) 0);
        sha.update((byte) 0);
      }
      System.out.println(path.substring(path.lastIndexOf('/') + 1) + " mapped=" + mapped + " strings=" + n
          + " nonAscii=" + nonAscii + " sha256=" + HexFormat.of().formatHex(sha.digest()));
    }
  }

  /** decodeString gives the same string from a heap buffer, a direct one and a read-only direct one. */
  private static void decoding() {
    String longAscii = "x".repeat(300);  // two-byte length forms in UTF-8
    String longCjk = "日本語".repeat(60);
    String[] samples = {"", "plain", "café", "日本語のテキスト", "😀 emoji", longAscii, longCjk,
        "العربية", "mixed üöä and ascii"};
    int checked = 0;
    for (ResourceString.Type type : ResourceString.Type.values()) {
      for (String s : samples) {
        byte[] encoded = type == ResourceString.Type.CESU8 ? cesu8(s) : ResourceString.encodeString(s, type);
        int offset = 7;  // not word-aligned, and not the buffer's start
        ByteBuffer heap = ByteBuffer.allocate(offset + encoded.length + 3).order(ByteOrder.LITTLE_ENDIAN);
        heap.put(offset, encoded);
        ByteBuffer direct = ByteBuffer.allocateDirect(heap.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        direct.put(0, heap, 0, heap.capacity());
        // A UTF8 pool's string that is not valid UTF-8 (surrogates encoded one by one) is read as CESU-8.
        ResourceString.Type as = type == ResourceString.Type.CESU8 ? ResourceString.Type.UTF8 : type;
        String fromHeap = ResourceString.decodeString(heap, offset, as);
        String fromDirect = ResourceString.decodeString(direct, offset, as);
        String fromReadOnly = ResourceString.decodeString(direct.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN), offset, as);
        if (!s.equals(fromHeap)) throw new AssertionError(type + ": heap decoded \"" + fromHeap + "\" for \"" + s + "\"");
        if (!s.equals(fromDirect)) throw new AssertionError(type + ": direct decoded \"" + fromDirect + "\" for \"" + s + "\"");
        if (!s.equals(fromReadOnly)) throw new AssertionError(type + ": read-only decoded \"" + fromReadOnly + "\" for \"" + s + "\"");
        checked++;
      }
    }
    // A length past the buffer (a malformed table) fails the same way from each, before anything is allocated for it.
    byte[][] malformed = {{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 'a', 0}, {(byte) 0x00, (byte) 0x70, 'a', 0, 0, 0}};
    ResourceString.Type[] as = {ResourceString.Type.UTF8, ResourceString.Type.UTF16};
    for (int i = 0; i < malformed.length; i++) {
      ByteBuffer heap = ByteBuffer.wrap(malformed[i]).order(ByteOrder.LITTLE_ENDIAN);
      ByteBuffer direct = ByteBuffer.allocateDirect(malformed[i].length).order(ByteOrder.LITTLE_ENDIAN);
      direct.put(0, malformed[i]);
      for (ByteBuffer b : new ByteBuffer[] {heap, direct}) {
        try {
          String s = ResourceString.decodeString(b, 0, as[i]);
          throw new AssertionError(as[i] + ": a length past the buffer decoded \"" + s + "\" from a " + (b.isDirect() ? "direct" : "heap") + " buffer");
        } catch (IndexOutOfBoundsException expected) {
          checked++;
        }
      }
    }
    System.out.println("decodeString: " + checked + " cases the same from heap, direct and read-only buffers");
  }

  /** A UTF8 pool's entry (character count, byte count, bytes, NUL) with the string in CESU-8, as aapt wrote emoji. */
  private static byte[] cesu8(String s) {
    byte[] bytes = s.getBytes(ResourceString.Type.CESU8.charset());
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    for (int length : new int[] {s.length(), bytes.length}) {
      if (length > 0x7f) out.write((length >> 8) | 0x80);
      out.write(length & 0xff);
    }
    out.writeBytes(bytes);
    out.write(0);
    return out.toByteArray();
  }
}
