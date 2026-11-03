package dev.apksim.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.TextureView;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks for the `selftest` control command: the video paths, without a server or a signed-in app. */
public final class SelfTest {
  private SelfTest() {}

  /** libwebrtc's JavaI420Buffer.cropAndScale, a native method with 19 arguments. */
  public static String scale() throws Exception {
    ClassLoader cl = SelfTest.class.getClassLoader();
    Class<?> buf = Class.forName("org.webrtc.JavaI420Buffer", true, cl);
    Object src = buf.getMethod("allocate", int.class, int.class).invoke(null, 64, 48);
    ByteBuffer y = (ByteBuffer) buf.getMethod("getDataY").invoke(src);
    for (int i = 0; i < 64 * 48; i++) y.put(i, (byte) (i % 64 * 4));
    Object out = buf.getMethod("cropAndScale", int.class, int.class, int.class, int.class, int.class, int.class)
        .invoke(src, 0, 0, 64, 48, 32, 24);
    Class<?> i420 = Class.forName("org.webrtc.VideoFrame$I420Buffer", true, cl);
    ByteBuffer oy = (ByteBuffer) i420.getMethod("getDataY").invoke(out);
    int w = (int) i420.getMethod("getWidth").invoke(out);
    int first = oy.get(0) & 255;
    int last = oy.get(31) & 255;
    return "scale " + w + "x" + i420.getMethod("getHeight").invoke(out) + " row0 " + first + ".." + last
        + (w == 32 && first < 10 && last > 240 ? " ok" : " WRONG");
  }

  /** Decodes an Annex B H.264 file (access units split at AUDs) through MediaCodec onto a Surface. */
  public static String codec(Context context, String file) throws Exception {
    byte[] all = Files.readAllBytes(Path.of(file));
    List<int[]> units = new ArrayList<>();
    int start = -1;
    for (int i = 0; i + 4 < all.length; i++) {
      if (all[i] == 0 && all[i + 1] == 0 && all[i + 2] == 0 && all[i + 3] == 1 && (all[i + 4] & 0x1f) == 9) {
        if (start >= 0) units.add(new int[] {start, i});
        start = i;
      }
    }
    if (start >= 0) units.add(new int[] {start, all.length});
    SurfaceTexture texture = new SurfaceTexture(false);
    VideoSurfaces.attach(texture, new TextureView(context));
    Surface surface = new Surface(texture);
    // By name, as media players create codecs (and the lookup that once fell back to Robolectric's 512-byte default).
    MediaCodec codec = MediaCodec.createByCodecName(ShadowSimMediaCodec.AVC_DECODER);
    codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 360), surface, null, 0);
    codec.start();
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    int rendered = 0;
    long pts = 0;
    for (int[] u : units) {
      int in = codec.dequeueInputBuffer(10_000);
      if (in < 0) continue;
      ByteBuffer b = codec.getInputBuffer(in);
      b.clear();
      b.put(all, u[0], u[1] - u[0]);
      codec.queueInputBuffer(in, 0, u[1] - u[0], pts, 0);
      pts += 66_666;
      int out = codec.dequeueOutputBuffer(info, 10_000);
      if (out >= 0) {
        codec.releaseOutputBuffer(out, true);
        if (VideoSurfaces.picture(texture) != null) rendered++;
      }
    }
    String name = codec.getName();
    codec.stop();
    codec.release();
    Bitmap p = VideoSurfaces.picture(texture);
    VideoSurfaces.detach(texture);
    if (p == null) return "codec " + name + ": nothing drawn from " + units.size() + " units";
    int c = p.getPixel(p.getWidth() / 16, p.getHeight() / 2);  // testsrc2's leftmost bar, red.
    boolean red = (c >> 16 & 255) > 200 && (c & 255) < 60;
    return String.format("codec %s: %d of %d units drawn, %dx%d, left bar #%06x%s", name, rendered, units.size(),
        p.getWidth(), p.getHeight(), c & 0xffffff, units.size() > 0 && rendered >= units.size() - 1 && red ? " ok" : " WRONG");
  }

  /** The live-video path's colour conversion: a solid red I420 picture must be red in a Bitmap. */
  public static String colour() {
    int w = 16;
    ByteBuffer y = ByteBuffer.allocateDirect(w * w), u = ByteBuffer.allocateDirect(w * w / 4), v = ByteBuffer.allocateDirect(w * w / 4);
    for (int i = 0; i < w * w; i++) y.put(i, (byte) 63);
    for (int i = 0; i < w * w / 4; i++) {
      u.put(i, (byte) 102);
      v.put(i, (byte) 240);
    }
    ByteBuffer out = ByteBuffer.allocateDirect(w * w * 4);
    VideoToolbox.i420ToRgba(y, w, u, w / 2, v, w / 2, w, w, out, VideoToolbox.BITMAP_BGRA);
    Bitmap b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
    out.limit(w * w * 4);
    b.copyPixelsFromBuffer(out);
    int c = b.getPixel(8, 8);
    boolean red = (c >> 16 & 255) > 200 && (c & 255) < 60;
    return String.format("colour #%06x (bitmap %s)%s", c & 0xffffff, VideoToolbox.BITMAP_BGRA ? "BGRA" : "RGBA", red ? " ok" : " WRONG");
  }
}
