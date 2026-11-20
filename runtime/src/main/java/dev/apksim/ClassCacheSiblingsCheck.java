package dev.apksim;

import java.util.Arrays;
import java.util.Map;

/**
 * Check for a beamed instance's content lookup across builds (issue 578, scripts/check-class-cache-siblings.sh): one
 * process per step, as the agent runs instances. {@code write} saves a class under a DEX key, {@code read} opens a cache
 * for another key and must find it by content (when the sibling is named) or not at all (when it is not).
 */
public final class ClassCacheSiblingsCheck {
  private static final byte[] HASH = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
  private static final byte[] CODE = {(byte) 0xca, (byte) 0xfe, 1, 2, 3};

  private ClassCacheSiblingsCheck() {}

  public static void main(String[] a) {
    String mode = a[0], key = a[1];
    System.setProperty("apksim.quiet", "true");
    ClassCache c = ClassCache.open(new String[] {key}, "p.pkg");
    if (c == null) fail("no cache (the runtime is not a jar?)");
    if (mode.equals("write")) {
      c.put(0, "p/A", CODE, Map.of(), () -> HASH);
      ClassCache.flush();
      System.out.println("wrote");
      return;
    }
    byte[] got = c.get(0, "p/A", n -> 0, () -> HASH);
    boolean expect = mode.equals("read-found");
    if (expect && !Arrays.equals(got, CODE)) fail("the class was not found by content in a sibling's pack: " + Arrays.toString(got));
    if (!expect && got != null) fail("a class was found with no sibling named");
    if (expect && c.contentHits != 1) fail("contentHits " + c.contentHits);
    ClassCache.flush();
    System.out.println("ok " + mode);
  }

  private static void fail(String why) {
    System.err.println("FAIL: " + why);
    System.exit(1);
  }
}
