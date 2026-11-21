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
  private static final byte[] HASH2 = {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
  private static final byte[] CODE2 = {(byte) 0xca, (byte) 0xfe, 4, 5, 6};

  private ClassCacheSiblingsCheck() {}

  public static void main(String[] a) {
    String mode = a[0], key = a[1];
    System.setProperty("apksim.quiet", "true");
    ClassCache c = ClassCache.open(new String[] {key}, "p.pkg");
    if (c == null) fail("no cache (the runtime is not a jar?)");
    if (mode.equals("write")) {
      c.loaded("p/A");
      c.put(0, "p/A", CODE, Map.of(), () -> HASH);
      ClassCache.flush();
      System.out.println("wrote");
      return;
    }
    if (mode.equals("write-other")) {
      // A second launch of the same build (its cache as the seed) that translates a class of another DEX file only: the
      // index it writes, which the agent moves over the build's, must still know the first launch's class (issue 583).
      ClassCache c2 = ClassCache.open(new String[] {key, a[2]}, "p.pkg");
      if (c2.get(0, "p/A", n -> 0, () -> HASH) == null) fail("the build's own pack (the seed) was not read");
      c2.put(1, "p/B", CODE2, Map.of(), () -> HASH2);
      ClassCache.flush();
      System.out.println("wrote other");
      return;
    }
    boolean expect = mode.equals("read-found");
    // A new build has no load hints of its own: it takes the newest sibling's (issue 583), and none without one.
    if (expect != c.hints().contains("p/A")) fail("hints " + c.hints() + " with" + (expect ? "" : "out") + " a sibling");
    byte[] got = c.get(0, "p/A", n -> 0, () -> HASH);
    if (expect && !Arrays.equals(got, CODE)) fail("the class was not found by content in a sibling's pack: " + Arrays.toString(got));
    if (!expect && got != null) fail("a class was found with no sibling named");
    if (expect && c.contentHits != 1) fail("contentHits " + c.contentHits);
    if (expect && !Arrays.equals(c.get(0, "p/B", n -> 0, () -> HASH2), CODE2)) fail("a class of the build's second launch was not found by content");
    ClassCache.flush();
    System.out.println("ok " + mode);
  }

  private static void fail(String why) {
    System.err.println("FAIL: " + why);
    System.exit(1);
  }
}
