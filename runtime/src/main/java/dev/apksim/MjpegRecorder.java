package dev.apksim;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen recording without AVFoundation (Linux, headless): Motion-JPEG in an AVI, which every player and browser-less
 * CI artifact viewer opens. Frames are taken as they are presented; a writer thread stores the latest one 30 times a
 * second (repeating it while nothing changes), so the movie keeps real timing. JPEG comes from Android's own encoder.
 */
final class MjpegRecorder {
  private static final int DEFAULT_FPS = 30;
  private static volatile MjpegRecorder active;

  private final RandomAccessFile out;
  private final int w;
  private final int h;
  private final int fps;
  private final List<int[]> index = new ArrayList<>();  // offset, size
  private final Thread writer;
  private final long startNanos = System.nanoTime();
  private volatile boolean running = true;
  private byte[] latest;  // raw pixels, guarded by this
  private boolean fresh;
  private byte[] lastJpeg;
  private long moviStart;
  private int frames;

  private MjpegRecorder(String path, int w, int h, int fps) throws IOException {
    this.fps = fps;
    this.w = w;
    this.h = h;
    out = new RandomAccessFile(path, "rw");
    out.setLength(0);
    writeHeaders();
    writer = new Thread(this::loop, "apksim-recorder");
    writer.setDaemon(true);
    writer.start();
  }

  static String start(String path, int w, int h) {
    return start(path, w, h, DEFAULT_FPS);
  }

  /** Test runs (TestRecorder) pass the frame rate they were asked for. */
  static synchronized String start(String path, int w, int h, int fps) {
    if (active != null) return "already recording";
    try {
      active = new MjpegRecorder(path, w, h, fps);
      return null;
    } catch (IOException e) {
      return e.toString();
    }
  }

  /** Finishes the movie; its length in ms, or -1 if nothing was recording. */
  static synchronized long stop() {
    MjpegRecorder r = active;
    if (r == null) return -1;
    active = null;
    return r.finish();
  }

  static boolean recording() {
    return active != null;
  }

  /** From the raster thread: the frame just presented (BGRA, as Skia lays it out here). */
  static void frame(ByteBuffer pixels) {
    MjpegRecorder r = active;
    if (r == null) return;
    synchronized (r) {
      if (r.latest == null || r.latest.length != pixels.remaining()) r.latest = new byte[pixels.remaining()];
      pixels.duplicate().get(r.latest);
      r.fresh = true;
    }
  }

  private void loop() {
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1 << 20);
    try {
      long next = System.nanoTime();
      while (running) {
        next += 1_000_000_000L / fps;
        if (!step(bitmap, jpeg)) return;
        long wait = next - System.nanoTime();
        if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
      }
      // A frame that arrived since the last step (a short test's only one) still goes in.
      boolean pending;
      synchronized (this) {
        pending = fresh;
      }
      if (pending) step(bitmap, jpeg);
    } finally {
      bitmap.recycle();  // native pixels: HotSpot does not collect for them
    }
  }

  /** Encodes the newest frame if there is one and writes the latest picture; false when the file cannot be written. */
  private boolean step(Bitmap bitmap, ByteArrayOutputStream jpeg) {
    byte[] raw = null;
    synchronized (this) {
      if (fresh) {
        raw = latest.clone();
        fresh = false;
      }
    }
    try {
      if (raw != null && raw.length == w * h * 4) {
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(raw));
        jpeg.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
        lastJpeg = jpeg.toByteArray();
      }
      if (lastJpeg != null) writeFrame(lastJpeg);
      return true;
    } catch (IOException e) {
      System.err.println("[apksim] recording stopped: " + e);
      return false;
    }
  }

  private long finish() {
    running = false;
    java.util.concurrent.locks.LockSupport.unpark(writer);
    try {
      writer.join(2000);
      writeIndexAndPatch();
      out.close();
    } catch (IOException | InterruptedException e) {
      System.err.println("[apksim] could not finish the recording: " + e);
    }
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  // ---------------------------------------------------------------- AVI (RIFF) layout

  private void writeHeaders() throws IOException {
    ByteBuffer b = le(4096);
    b.put(fourcc("RIFF")).putInt(0).put(fourcc("AVI "));                     // size patched at the end
    b.put(fourcc("LIST")).putInt(4 + 64 + 8 + 116).put(fourcc("hdrl"));  // avih chunk, strl LIST
    b.put(fourcc("avih")).putInt(56);
    b.putInt(1_000_000 / fps).putInt(0).putInt(0).putInt(0x10);              // us per frame, max bytes/s, padding, AVIF_HASINDEX
    b.putInt(0).putInt(0).putInt(1).putInt(0).putInt(w).putInt(h);           // frames (patched), initial, streams, buffer, size
    b.putInt(0).putInt(0).putInt(0).putInt(0);
    b.put(fourcc("LIST")).putInt(4 + 64 + 48).put(fourcc("strl"));
    b.put(fourcc("strh")).putInt(56).put(fourcc("vids")).put(fourcc("MJPG"));
    b.putInt(0).putShort((short) 0).putShort((short) 0).putInt(0);           // flags, priority, language, initial frames
    b.putInt(1).putInt(fps).putInt(0).putInt(0);                             // scale, rate, start, length (patched)
    b.putInt(0).putInt(-1).putInt(0);                                        // buffer, quality, sample size
    b.putShort((short) 0).putShort((short) 0).putShort((short) w).putShort((short) h);
    b.put(fourcc("strf")).putInt(40);                                        // BITMAPINFOHEADER
    b.putInt(40).putInt(w).putInt(h).putShort((short) 1).putShort((short) 24).put(fourcc("MJPG"));
    b.putInt(w * h * 3).putInt(0).putInt(0).putInt(0).putInt(0);
    b.put(fourcc("LIST")).putInt(0).put(fourcc("movi"));                     // size patched at the end
    out.write(b.array(), 0, b.position());
    moviStart = out.getFilePointer() - 4;  // the "movi" fourcc, which index offsets count from
  }

  private void writeFrame(byte[] jpeg) throws IOException {
    int pad = jpeg.length & 1;
    ByteBuffer b = le(8);
    b.put(fourcc("00dc")).putInt(jpeg.length);
    long at = out.getFilePointer();
    out.write(b.array());
    out.write(jpeg);
    if (pad == 1) out.write(0);
    index.add(new int[] {(int) (at - moviStart), jpeg.length});
    frames++;
  }

  private void writeIndexAndPatch() throws IOException {
    long moviEnd = out.getFilePointer();
    ByteBuffer idx = le(8 + 16 * index.size());
    idx.put(fourcc("idx1")).putInt(16 * index.size());
    for (int[] e : index) idx.put(fourcc("00dc")).putInt(0x10).putInt(e[0]).putInt(e[1]);
    out.write(idx.array());
    long end = out.getFilePointer();
    patch(4, (int) (end - 8));                       // RIFF size
    patch(12 + 12 + 8 + 16, frames);                  // avih total frames
    patch(12 + 12 + 64 + 12 + 8 + 32, frames);        // strh length
    patch(moviStart - 4, (int) (moviEnd - moviStart)); // movi LIST size
  }

  private void patch(long at, int value) throws IOException {
    out.seek(at);
    out.write(le(4).putInt(value).array());
    out.seek(out.length());
  }

  private static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static byte[] fourcc(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }
}
