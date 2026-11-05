package dev.apksim;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The screen as a stream of JPEG frames, for `apksim serve` (a control connection that sent `stream`). The raster
 * thread hands over each presented frame (BGRA, as Skia lays it out here); one encoder thread JPEG-encodes the latest
 * one with Android's encoder, skipping frames that match the last one encoded, and each subscriber's own thread
 * writes the newest JPEG it has not sent yet, so a slow reader gets fewer frames, never older ones. Nothing is copied
 * or encoded while no one subscribes.
 *
 * <p>Wire format, per frame: int32 length of what follows, then int32 width, int32 height, int64 the present time in
 * epoch microseconds, int32 encode time in microseconds, and the JPEG. Big-endian.
 */
final class FrameStream {
  private static final CopyOnWriteArrayList<Subscriber> SUBSCRIBERS = new CopyOnWriteArrayList<>();
  private static final Object LOCK = new Object();
  private static byte[] latest;  // raw pixels of the newest frame, guarded by LOCK
  private static int latestW;
  private static int latestH;
  private static long latestMicros;
  private static boolean fresh;
  private static Thread encoder;

  private FrameStream() {}

  static boolean active() {
    return !SUBSCRIBERS.isEmpty();
  }

  /** From the raster thread: the frame just presented. */
  static void frame(ByteBuffer pixels, int w, int h) {
    if (SUBSCRIBERS.isEmpty() || pixels.remaining() != w * h * 4) return;
    long micros = epochMicros();
    synchronized (LOCK) {
      if (latest == null || latest.length != pixels.remaining()) latest = new byte[pixels.remaining()];
      pixels.duplicate().get(latest);
      latestW = w;
      latestH = h;
      latestMicros = micros;
      fresh = true;
      LOCK.notifyAll();
    }
  }

  private static long epochMicros() {
    java.time.Instant now = java.time.Instant.now();
    return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000;
  }

  /**
   * Streams frames to this connection until it closes. Runs on the connection's own thread; asks the Host to draw a
   * frame at once (`redraw`), so a still screen is sent too.
   */
  static void serve(java.io.InputStream in, OutputStream out, int quality) throws InterruptedException {
    Subscriber s = new Subscriber(new DataOutputStream(new java.io.BufferedOutputStream(out, 1 << 16)));
    // The reader never sends anything more: its end of the stream is how a still screen learns it went away.
    Thread eof = new Thread(() -> {
      try {
        while (in.read() >= 0) {}
      } catch (IOException e) {
        // closed
      }
      s.close();
    }, "apksim-frame-stream-eof");
    eof.setDaemon(true);
    eof.start();
    SUBSCRIBERS.add(s);
    startEncoder();
    synchronized (LOCK) {
      FrameStream.quality = Math.max(10, Math.min(100, quality));
      lastEncoded = null;  // the new subscriber needs the current screen even if it has not changed
    }
    Control.QUEUE.add(new Control.Pending(new String[] {"redraw"}));
    try {
      s.run();
    } finally {
      SUBSCRIBERS.remove(s);
    }
  }

  private static int quality = 80;
  private static byte[] lastEncoded;  // raw pixels of the last frame encoded, guarded by LOCK
  private static int lastW;
  private static int lastH;

  private static synchronized void startEncoder() {
    if (encoder != null) return;
    encoder = new Thread(FrameStream::encodeLoop, "apksim-frame-stream");
    encoder.setDaemon(true);
    encoder.start();
  }

  private static void encodeLoop() {
    Bitmap bitmap = null;
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1 << 20);
    byte[] raw = null;
    while (true) {
      int w;
      int h;
      long micros;
      int q;
      synchronized (LOCK) {
        while (!fresh) {
          try {
            LOCK.wait();
          } catch (InterruptedException e) {
            return;
          }
        }
        fresh = false;
        // Presented again, unchanged (same size too: a rotated solid screen has the same bytes).
        if (lastEncoded != null && latestW == lastW && latestH == lastH && Arrays.equals(lastEncoded, latest)) continue;
        if (raw == null || raw.length != latest.length) raw = new byte[latest.length];
        System.arraycopy(latest, 0, raw, 0, latest.length);
        w = latestW;
        h = latestH;
        micros = latestMicros;
        q = quality;
      }
      if (SUBSCRIBERS.isEmpty()) continue;
      long t0 = System.nanoTime();
      if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
        if (bitmap != null) bitmap.recycle();
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      }
      bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(raw));
      jpeg.reset();
      bitmap.compress(Bitmap.CompressFormat.JPEG, q, jpeg);
      int encodeMicros = (int) ((System.nanoTime() - t0) / 1000);
      Frame f = new Frame(jpeg.toByteArray(), w, h, micros, encodeMicros);
      synchronized (LOCK) {
        // Kept as the reference for "unchanged": swap rather than copy.
        byte[] previous = lastEncoded;
        lastEncoded = raw;
        raw = previous;
        lastW = w;
        lastH = h;
      }
      for (Subscriber s : SUBSCRIBERS) s.offer(f);
    }
  }

  private record Frame(byte[] jpeg, int w, int h, long presentMicros, int encodeMicros) {}

  private static final class Subscriber {
    private final DataOutputStream out;
    private Frame next;  // guarded by this
    private boolean closed;  // guarded by this

    Subscriber(DataOutputStream out) {
      this.out = out;
    }

    synchronized void close() {
      closed = true;
      notifyAll();
    }

    synchronized void offer(Frame f) {
      next = f;
      notifyAll();
    }

    void run() throws InterruptedException {
      while (true) {
        Frame f;
        synchronized (this) {
          while (next == null && !closed) wait();
          if (closed) return;
          f = next;
          next = null;
        }
        try {
          out.writeInt(4 + 4 + 8 + 4 + f.jpeg.length);
          out.writeInt(f.w);
          out.writeInt(f.h);
          out.writeLong(f.presentMicros);
          out.writeInt(f.encodeMicros);
          out.write(f.jpeg);
          out.flush();
        } catch (IOException e) {
          return;  // the reader went away
        }
      }
    }
  }
}
