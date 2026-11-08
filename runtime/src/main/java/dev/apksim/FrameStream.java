package dev.apksim;

import android.graphics.Bitmap;
import dev.apksim.video.H264Encoder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The screen as a stream of frames, for `apksim serve` (a control connection that sent `stream`): JPEG, or H.264 when
 * asked for and an encoder exists here ({@link H264Encoder}). The raster thread hands over each presented frame (BGRA,
 * as Skia lays it out here); one encoder thread encodes the latest one, skipping frames that match the last one
 * encoded, and each subscriber's own thread writes what it has not sent yet. A JPEG subscriber gets the newest frame,
 * so a slow reader gets fewer frames, never older ones. An H.264 subscriber gets every frame from its first keyframe on
 * (each depends on the one before); one that falls behind drops to the next keyframe, which it then asks for. It may
 * also ask for one itself (a `key` line: its viewer's decoder lost its place). Nothing is copied or encoded while no
 * one subscribes.
 *
 * <p>Wire format, per frame: int32 length of what follows, then int32 width, int32 height, int64 the present time in
 * epoch microseconds, int32 encode time in microseconds, and the JPEG; for H.264, a byte (1 for a keyframe, else 0)
 * and the access unit in Annex B. Big-endian.
 */
final class FrameStream {
  private static final CopyOnWriteArrayList<Subscriber> SUBSCRIBERS = new CopyOnWriteArrayList<>();
  private static final Object LOCK = new Object();
  /** H.264 frames a subscriber may have waiting before it drops them and waits for a keyframe. */
  private static final int MAX_QUEUED = 30;
  /** After motion stops, the last frame is encoded again this often (up to {@link #REFINES} times), to sharpen it. */
  private static final long REFINE_MS = 120;
  private static final int REFINES = 2;
  private static byte[] latest;  // raw pixels of the newest frame, guarded by LOCK
  private static int latestW;
  private static int latestH;
  private static long latestMicros;
  private static boolean fresh;
  private static boolean keyWanted;  // guarded by LOCK
  private static Thread encoder;

  private FrameStream() {}

  static boolean active() {
    return !SUBSCRIBERS.isEmpty();
  }

  /** Whether `stream ... h264` gets H.264 here. */
  static boolean h264Available() {
    return H264Encoder.available();
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

  /** A keyframe next, for every H.264 subscriber (one sent even if the screen is still). */
  private static void wantKey() {
    synchronized (LOCK) {
      keyWanted = true;
      LOCK.notifyAll();
    }
  }

  /**
   * Streams frames to this connection until it closes. Runs on the connection's own thread; asks the Host to draw a
   * frame at once (`redraw`), so a still screen is sent too. With h264 (only when {@link #h264Available}), the stream
   * is H.264 at about kbps kilobits a second.
   */
  static void serve(java.io.InputStream in, OutputStream out, int quality, boolean h264, int kbps)
      throws InterruptedException {
    Subscriber s = new Subscriber(new DataOutputStream(new java.io.BufferedOutputStream(out, 1 << 16)), h264);
    // The reader sends nothing more but `key` lines (H.264: a keyframe, please): its end of the stream is how a still
    // screen learns it went away.
    Thread eof = new Thread(() -> {
      try {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
          if (c != '\n') {
            if (line.length() < 64) line.append((char) c);
            continue;
          }
          if (h264 && line.toString().trim().equals("key")) s.askKey();
          line.setLength(0);
        }
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
      if (h264) FrameStream.kbps = Math.max(100, Math.min(100_000, kbps));
      lastEncoded = null;  // the new subscriber needs the current screen even if it has not changed
      if (h264) keyWanted = true;
    }
    Control.QUEUE.add(new Control.Pending(new String[] {"redraw"}));
    try {
      s.run();
    } finally {
      SUBSCRIBERS.remove(s);
    }
  }

  private static int quality = 80;
  private static int kbps = 4000;
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
    H264Encoder video = null;
    int videoKbps = 0;
    byte[] raw = null;
    int refines = REFINES;  // H.264 re-encodes of a still screen left
    while (true) {
      int w;
      int h;
      long micros;
      int q;
      int rate;
      boolean key;
      boolean refine = false;
      boolean repeat;
      synchronized (LOCK) {
        long until = System.nanoTime() + REFINE_MS * 1_000_000;
        while (!fresh && !keyWanted) {
          try {
            // H.264 only: once frames stop, the last one encoded again, for the detail the bit rate left out.
            long left = (until - System.nanoTime()) / 1_000_000;
            boolean canRefine = video != null && refines < REFINES && lastEncoded != null && hasCodec(true);
            if (canRefine && left <= 0) {
              refine = true;
              break;
            }
            LOCK.wait(canRefine ? Math.max(1, left) : 0);
          } catch (InterruptedException e) {
            return;
          }
        }
        key = keyWanted;
        boolean source = fresh && latest != null;
        fresh = false;
        keyWanted = false;
        repeat = refine || (key && !source);
        if (repeat) {
          // The last frame encoded again (a refinement, or a keyframe of a still screen).
          if (lastEncoded == null) continue;
          if (raw == null || raw.length != lastEncoded.length) raw = new byte[lastEncoded.length];
          System.arraycopy(lastEncoded, 0, raw, 0, lastEncoded.length);
          w = lastW;
          h = lastH;
          micros = epochMicros();
        } else {
          // Presented again, unchanged (same size too: a rotated solid screen has the same bytes).
          if (!key && lastEncoded != null && latestW == lastW && latestH == lastH && Arrays.equals(lastEncoded, latest)) continue;
          if (latest == null) continue;
          if (raw == null || raw.length != latest.length) raw = new byte[latest.length];
          System.arraycopy(latest, 0, raw, 0, latest.length);
          w = latestW;
          h = latestH;
          micros = latestMicros;
        }
        q = quality;
        rate = kbps;
      }
      if (SUBSCRIBERS.isEmpty()) continue;
      refines = refine ? refines + 1 : 0;
      if (hasCodec(false) && !repeat) {
        long t0 = System.nanoTime();
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
          if (bitmap != null) bitmap.recycle();
          bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(raw));
        jpeg.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, q, jpeg);
        int encodeMicros = (int) ((System.nanoTime() - t0) / 1000);
        Frame f = new Frame(jpeg.toByteArray(), w, h, micros, encodeMicros, true);
        for (Subscriber s : SUBSCRIBERS) if (!s.h264) s.offer(f);
      }
      if (hasCodec(true)) {
        long t0 = System.nanoTime();
        if (video != null && (video.width != (w & ~1) || video.height != (h & ~1) || videoKbps != rate)) {
          video.close();
          video = null;
        }
        if (video == null) {
          video = H264Encoder.open(w, h, rate);
          videoKbps = rate;
          key = true;
        }
        byte[] unit = video == null ? null : video.encode(raw, w * 4, micros, key);
        if (unit == null) {
          // Nothing came out (or no encoder for this size): a fresh encoder and a keyframe next time.
          if (video != null) video.close();
          video = null;
        } else {
          int encodeMicros = (int) ((System.nanoTime() - t0) / 1000);
          Frame f = new Frame(unit, video.width, video.height, micros, encodeMicros, unit[0] == 1);
          for (Subscriber s : SUBSCRIBERS) if (s.h264) s.offer(f);
        }
      } else if (video != null) {
        video.close();
        video = null;
      }
      synchronized (LOCK) {
        // Kept as the reference for "unchanged": swap rather than copy.
        byte[] previous = lastEncoded;
        lastEncoded = raw;
        raw = previous;
        lastW = w;
        lastH = h;
      }
    }
  }

  private static boolean hasCodec(boolean h264) {
    for (Subscriber s : SUBSCRIBERS) if (s.h264 == h264) return true;
    return false;
  }

  /** One encoded frame: a JPEG, or [keyframe flag] + an H.264 access unit. */
  private record Frame(byte[] data, int w, int h, long presentMicros, int encodeMicros, boolean key) {}

  private static final class Subscriber {
    private final DataOutputStream out;
    final boolean h264;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();  // guarded by this
    private boolean waitKey = true;  // H.264: dropping frames until a keyframe; guarded by this
    private boolean closed;  // guarded by this

    Subscriber(DataOutputStream out, boolean h264) {
      this.out = out;
      this.h264 = h264;
    }

    synchronized void close() {
      closed = true;
      notifyAll();
    }

    void askKey() {
      wantKey();
    }

    synchronized void offer(Frame f) {
      if (!h264) {
        queue.clear();  // the newest only
      } else if (f.key) {
        queue.clear();  // nothing before a keyframe is needed
        waitKey = false;
      } else if (waitKey) {
        return;
      } else if (queue.size() >= MAX_QUEUED) {
        // Too far behind: drop to the next keyframe.
        queue.clear();
        waitKey = true;
        wantKey();
        return;
      }
      queue.add(f);
      notifyAll();
    }

    void run() throws InterruptedException {
      while (true) {
        Frame f;
        synchronized (this) {
          while (queue.isEmpty() && !closed) wait();
          if (closed) return;
          f = queue.poll();
        }
        try {
          out.writeInt(4 + 4 + 8 + 4 + f.data.length);
          out.writeInt(f.w);
          out.writeInt(f.h);
          out.writeLong(f.presentMicros);
          out.writeInt(f.encodeMicros);
          out.write(f.data);
          out.flush();
        } catch (IOException e) {
          return;  // the reader went away
        }
      }
    }
  }
}
