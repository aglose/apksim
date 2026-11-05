package dev.apksim;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * `diff GOLDEN ACTUAL [MAP] [DELTA]` for screenshot tests (scripts/snapshot.py): compares two PNGs pixel by pixel
 * with the app's own decoder, so the script needs no imaging library. A pixel differs when any channel (alpha
 * included) differs by more than DELTA. The map is the golden faded to a light grey, with differing pixels red and
 * pixels that differ by DELTA or less yellow.
 */
final class ImageDiff {
  private ImageDiff() {}

  static String compare(String golden, String actual, String map, int delta) throws IOException {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
    o.inPremultiplied = false;
    Bitmap g = BitmapFactory.decodeFile(Storage.absolute(golden).toString(), o);
    Bitmap c = BitmapFactory.decodeFile(Storage.absolute(actual).toString(), o);
    if (g == null || c == null) return "error: cannot read " + (g == null ? golden : actual);
    int w = g.getWidth();
    int h = g.getHeight();
    if (w != c.getWidth() || h != c.getHeight()) {
      return "size " + w + "x" + h + " " + c.getWidth() + "x" + c.getHeight() + " pixels=" + (long) Math.max(w, c.getWidth()) * Math.max(h, c.getHeight())
          + " total=" + (long) w * h + " maxDelta=255";
    }
    int[] gp = new int[w * h];
    int[] cp = new int[w * h];
    g.getPixels(gp, 0, w, 0, 0, w, h);
    c.getPixels(cp, 0, w, 0, 0, w, h);
    long over = 0;
    long any = 0;
    int max = 0;
    int[] out = map == null ? null : new int[w * h];
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
    if (out != null) {
      Bitmap m = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
      try (FileOutputStream f = new FileOutputStream(Storage.absolute(map).toFile())) {
        m.compress(Bitmap.CompressFormat.PNG, 100, f);
      }
      m.recycle();
    }
    g.recycle();
    c.recycle();
    return "size " + w + "x" + h + " pixels=" + over + " total=" + (long) w * h + " maxDelta=" + max + " changed=" + any;
  }
}
