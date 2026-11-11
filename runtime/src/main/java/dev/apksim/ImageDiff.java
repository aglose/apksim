package dev.apksim;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * `diff GOLDEN ACTUAL [MAP] [DELTA]` for screenshot tests (scripts/snapshot.py): compares two PNGs pixel by pixel
 * with the app's own decoder, so the script needs no imaging library. A pixel differs when any channel (alpha
 * included) differs by more than DELTA. The map is the golden faded to a light grey, with differing pixels red and
 * pixels that differ by DELTA or less yellow.
 */
final class ImageDiff {
  private ImageDiff() {}

  /** The folders under $HOME the launcher's sandbox denies reads of (apksim's sandbox profile). */
  private static final String[] SANDBOXED_READS = {
    ".ssh", ".aws", ".gnupg", ".config", "Documents", "Desktop",
    "Library/Keychains", "Library/Cookies", "Library/Application Support/Google", "Library/Application Support/Firefox",
  };

  /** Why a PNG could not be read: outside the sandbox's reads when it runs sandboxed, else missing or not a PNG. */
  static String unreadableReason(String path) {
    Path p = Storage.absolute(path).normalize();
    String home = System.getenv("HOME");
    if (System.getenv("APKSIM_SANDBOXED") != null && home != null) {
      Path h = Path.of(home).toAbsolutePath().normalize();
      for (String d : SANDBOXED_READS) {
        if (p.startsWith(h.resolve(d))) {
          return " (outside what the sandbox lets apksim read: ~/" + d + " is blocked; copy the file to /tmp or the"
              + " state folder, or launch with --no-sandbox)";
        }
      }
    }
    return Files.exists(p) ? " (not a readable PNG)" : " (no such file)";
  }

  static String compare(String golden, String actual, String map, int delta) throws IOException {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
    o.inPremultiplied = false;
    Bitmap g = null;
    Bitmap c = null;
    Bitmap m = null;
    // Native pixels (tens of MB for a tablet) are freed here, whatever happens: HotSpot does not count them towards
    // collecting, so a run of comparisons would otherwise hold them until some later GC.
    try {
      g = BitmapFactory.decodeFile(Storage.absolute(golden).toString(), o);
      c = BitmapFactory.decodeFile(Storage.absolute(actual).toString(), o);
      if (g == null) return "error: cannot read " + golden + unreadableReason(golden);
      if (c == null) return "error: cannot read " + actual + unreadableReason(actual);
      int w = g.getWidth();
      int h = g.getHeight();
      if (w != c.getWidth() || h != c.getHeight()) {
        return "size " + w + "x" + h + " " + c.getWidth() + "x" + c.getHeight() + " pixels="
            + (long) Math.max(w, c.getWidth()) * Math.max(h, c.getHeight()) + " total=" + (long) w * h + " maxDelta=255";
      }
      int[] gp = new int[w * h];
      int[] cp = new int[w * h];
      g.getPixels(gp, 0, w, 0, 0, w, h);
      c.getPixels(cp, 0, w, 0, 0, w, h);
      int[] out = map == null ? null : new int[w * h];
      Counts k = diff(gp, cp, delta, out);
      long over = k.over();
      long any = k.changed();
      int max = k.maxDelta();
      if (out != null) {
        m = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        try (FileOutputStream f = new FileOutputStream(Storage.absolute(map).toFile())) {
          m.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
      }
      return "size " + w + "x" + h + " pixels=" + over + " total=" + (long) w * h + " maxDelta=" + max + " changed=" + any;
    } finally {
      if (g != null) g.recycle();
      if (c != null) c.recycle();
      if (m != null) m.recycle();
    }
  }

  /** How two images of the same size differ, as `diff` reports it. */
  record Counts(long over, long changed, int maxDelta) {}

  /**
   * Compares two ARGB pixel arrays of the same size: pixels whose largest channel difference is over `delta`, pixels
   * that differ at all, and the largest difference. With `map` (same size), paints the difference map: red over
   * `delta`, amber under it, the golden image's pale grey elsewhere. Also `apksim replay`'s frame comparison.
   */
  static Counts diff(int[] gp, int[] cp, int delta, int[] out) {
    long over = 0;
    long any = 0;
    int max = 0;
    for (int i = 0; i < gp.length; i++) {
      int a = gp[i];
      int b = cp[i];
      int d = 0;
      if (a != b) {
        for (int shift = 0; shift < 32; shift += 8) d = Math.max(d, Math.abs(((a >>> shift) & 0xff) - ((b >>> shift) & 0xff)));
        any++;
        if (d > delta) over++;
        if (d > max) max = d;
      }
      if (out != null) {
        if (d > delta) {
          out[i] = 0xffff0000;
        } else if (d > 0) {
          out[i] = 0xffffc000;
        } else {
          int luma = (((a >> 16) & 0xff) * 3 + ((a >> 8) & 0xff) * 6 + (a & 0xff)) / 10;
          int v = 200 + luma * 55 / 255;
          out[i] = 0xff000000 | v << 16 | v << 8 | v;
        }
      }
    }
    return new Counts(over, any, max);
  }
}
