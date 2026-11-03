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

  /** Runs the MediaCodec check that fits the file's type. */
  public static String file(Context context, String file) throws Exception {
    String f = file.toLowerCase(java.util.Locale.ROOT);
    if (f.endsWith(".aac")) return audio(file, false);
    if (f.endsWith(".mp3")) return audio(file, true);
    return codec(context, file, f.endsWith(".h265") || f.endsWith(".hevc"));
  }

  /** Decodes an Annex B H.264 or HEVC file (access units split at AUDs) through MediaCodec onto a Surface. */
  public static String codec(Context context, String file, boolean hevc) throws Exception {
    byte[] all = Files.readAllBytes(Path.of(file));
    List<int[]> units = new ArrayList<>();
    int start = -1;
    for (int i = 0; i + 4 < all.length; i++) {
      boolean aud = hevc ? (all[i + 4] >> 1 & 0x3f) == 35 : (all[i + 4] & 0x1f) == 9;
      if (all[i] == 0 && all[i + 1] == 0 && all[i + 2] == 0 && all[i + 3] == 1 && aud) {
        if (start >= 0) units.add(new int[] {start, i});
        start = i;
      }
    }
    if (start >= 0) units.add(new int[] {start, all.length});
    SurfaceTexture texture = new SurfaceTexture(false);
    VideoSurfaces.attach(texture, new TextureView(context));
    Surface surface = new Surface(texture);
    // By name, as media players create codecs (and the lookup that once fell back to Robolectric's 512-byte default).
    MediaCodec codec = MediaCodec.createByCodecName(hevc ? ShadowSimMediaCodec.HEVC_DECODER : ShadowSimMediaCodec.AVC_DECODER);
    codec.configure(MediaFormat.createVideoFormat(hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC, 640, 360), surface, null, 0);
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

  /**
   * Decodes an ADTS AAC or MP3 file through MediaCodec and plays the PCM through AudioTrack, as a media player does,
   * then checks the sound is not silence and that the playback head took real time to get through it.
   */
  public static String audio(String file, boolean mp3) throws Exception {
    byte[] all = Files.readAllBytes(Path.of(file));
    List<int[]> frames = new ArrayList<>();
    int rate = 0, channels = 0;
    byte[] asc = null;
    for (int i = 0; i + 7 <= all.length;) {
      int b1 = all[i + 1] & 255, b2 = all[i + 2] & 255, b3 = all[i + 3] & 255;
      if ((all[i] & 255) != 0xff || (b1 & 0xe0) != 0xe0) {
        i++;
        continue;
      }
      int len;
      if (mp3) {
        // MPEG-1 Layer III only: 144 * bitrate / rate + padding.
        int[] kbps = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
        int[] rates = {44100, 48000, 32000, 0};
        int bitrate = kbps[b2 >> 4] * 1000, r = rates[b2 >> 2 & 3];
        if (bitrate == 0 || r == 0) {
          i++;
          continue;
        }
        rate = r;
        channels = (b3 >> 6) == 3 ? 1 : 2;
        len = 144 * bitrate / r + (b2 >> 1 & 1);
        frames.add(new int[] {i, len});
      } else {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        int object = (b2 >> 6) + 1, index = b2 >> 2 & 15, config = (b2 & 1) << 2 | b3 >> 6;
        len = (b3 & 3) << 11 | (all[i + 4] & 255) << 3 | (all[i + 5] & 255) >> 5;
        int header = (b1 & 1) == 0 ? 9 : 7;  // with or without CRC
        rate = rates[Math.min(index, 12)];
        channels = config;
        asc = new byte[] {(byte) (object << 3 | index >> 1), (byte) ((index & 1) << 7 | config << 3)};
        frames.add(new int[] {i + header, len - header});
      }
      i += Math.max(len, 1);
    }
    if (frames.isEmpty()) return "audio: no frames in " + file;
    MediaFormat format = MediaFormat.createAudioFormat(mp3 ? MediaFormat.MIMETYPE_AUDIO_MPEG : MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
    if (asc != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
    MediaCodec codec = MediaCodec.createByCodecName(mp3 ? ShadowSimMediaCodec.MP3_DECODER : ShadowSimMediaCodec.AAC_DECODER);
    codec.configure(format, null, null, 0);
    codec.start();
    java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    for (int[] f : frames) {
      int in = codec.dequeueInputBuffer(10_000);
      if (in < 0) continue;
      ByteBuffer b = codec.getInputBuffer(in);
      b.clear();
      b.put(all, f[0], Math.min(f[1], all.length - f[0]));
      codec.queueInputBuffer(in, 0, b.position(), 0, 0);
      int out = codec.dequeueOutputBuffer(info, 10_000);
      if (out >= 0) {
        ByteBuffer o = codec.getOutputBuffer(out);
        byte[] chunk = new byte[info.size];
        o.position(info.offset);
        o.get(chunk);
        pcm.write(chunk);
        codec.releaseOutputBuffer(out, false);
      }
    }
    String name = codec.getName();
    codec.stop();
    codec.release();
    byte[] samples = pcm.toByteArray();
    double sum = 0;
    ByteBuffer le = ByteBuffer.wrap(samples).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i + 1 < samples.length; i += 2) sum += Math.pow(le.getShort(i) / 32768.0, 2);
    double rms = samples.length > 1 ? Math.sqrt(sum / (samples.length / 2)) : 0;
    int frameBytes = 2 * channels;
    int total = samples.length / frameBytes;
    android.media.AudioTrack track = new android.media.AudioTrack.Builder()
        .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(rate)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channels == 1 ? android.media.AudioFormat.CHANNEL_OUT_MONO : android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(rate / 4 * frameBytes)
        .build();
    long t0 = System.nanoTime();
    track.play();
    // As ExoPlayer does: non-blocking writes, topping the buffer up as the head moves.
    ByteBuffer src = ByteBuffer.wrap(samples);
    while (src.hasRemaining() && System.nanoTime() - t0 < 30_000_000_000L) {
      if (track.write(src, src.remaining(), android.media.AudioTrack.WRITE_NON_BLOCKING) == 0) Thread.sleep(5);
    }
    while (track.getPlaybackHeadPosition() < total && System.nanoTime() - t0 < 30_000_000_000L) Thread.sleep(5);
    double took = (System.nanoTime() - t0) / 1e9, expected = total / (double) rate;
    int head = track.getPlaybackHeadPosition();
    track.stop();
    track.release();
    boolean ok = rms > 0.05 && head >= total && Math.abs(took - expected) < 0.15 * expected + 0.1;
    return String.format("audio %s: %d frames in, %.2f s of PCM (%d Hz, %d ch, rms %.2f), played in %.2f s%s", name, frames.size(),
        expected, rate, channels, rms, took, ok ? " ok" : " WRONG");
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
