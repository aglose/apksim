package dev.apksim.dex;

/** Runtime helpers called by translated code. */
public final class DexRt {
  private DexRt() {}

  /** fill-array-data: `s` holds little-endian bytes, one per char, for `count` elements from `start`. */
  public static void fill(Object array, int start, int count, int width, String s) {
    int p = 0;
    if (array instanceof int[] a) {
      for (int i = 0; i < count; i++, p += 4) a[start + i] = i32(s, p);
    } else if (array instanceof float[] a) {
      for (int i = 0; i < count; i++, p += 4) a[start + i] = Float.intBitsToFloat(i32(s, p));
    } else if (array instanceof long[] a) {
      for (int i = 0; i < count; i++, p += 8) a[start + i] = i64(s, p);
    } else if (array instanceof double[] a) {
      for (int i = 0; i < count; i++, p += 8) a[start + i] = Double.longBitsToDouble(i64(s, p));
    } else if (array instanceof byte[] a) {
      for (int i = 0; i < count; i++) a[start + i] = (byte) s.charAt(i);
    } else if (array instanceof boolean[] a) {
      for (int i = 0; i < count; i++) a[start + i] = s.charAt(i) != 0;
    } else if (array instanceof short[] a) {
      for (int i = 0; i < count; i++, p += 2) a[start + i] = (short) (s.charAt(p) | s.charAt(p + 1) << 8);
    } else if (array instanceof char[] a) {
      for (int i = 0; i < count; i++, p += 2) a[start + i] = (char) (s.charAt(p) | s.charAt(p + 1) << 8);
    } else {
      throw new NullPointerException("fill-array-data on " + array);
    }
  }

  private static int i32(String s, int p) {
    return s.charAt(p) | s.charAt(p + 1) << 8 | s.charAt(p + 2) << 16 | s.charAt(p + 3) << 24;
  }

  private static long i64(String s, int p) {
    return (i32(s, p) & 0xffffffffL) | (long) i32(s, p + 4) << 32;
  }
}
