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
 * and the access unit in Annex B. Big-endian. A `viewport W H` line (the size the tab shows the stream at) makes the
 * frames smaller than the device's (see {@link #setViewport}); the header's width and height are the frame's own.
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
  /**
   * `pace MS` from the serve relay (a tab that decodes slower than the app draws): at most one new frame is encoded per
   * this many ns, the newest at that time, so the H.264 stream stays a valid chain at a lower rate instead of dropping to
   * keyframes. 0: every frame. Each H.264 subscriber has its own request; this is the slowest of them, worked out again
   * when one asks, joins or leaves (recomputePace), and 0 while a JPEG subscriber is attached (one capture feeds both
   * codecs, and a JPEG viewer needs no pacing). Guarded by LOCK.
   */
  private static long paceNanos;
  private static long lastNewNanos;  // when the last new frame (not a refinement or repeat) was encoded
  private static Thread encoder;

  private FrameStream() {}

  static boolean active() {
    return !SUBSCRIBERS.isEmpty();
  }

  /** Whether `stream ... h264` gets H.264 here: an encoder that opened and encoded a test frame. */
  static boolean h264Available() {
    return H264Encoder.works();
  }

  /** From the raster thread: the frame just presented. */
  static void frame(ByteBuffer pixels, int w, int h) {
    if (SUBSCRIBERS.isEmpty() || pixels.remaining() != w * h * 4) return;
    long micros = epochMicros();
    synchronized (LOCK) {
      if (latest == null || latest.length != pixels.remaining()) latest = new byte[pixels.remaining()];
      pixels.duplicate().get(latest);
      if (Device.peek().watch()) roundMask(latest, w, h);
      latestW = w;
      latestH = h;
      latestMicros = micros;
      fresh = true;
      LOCK.notifyAll();
    }
  }

  /** A watch's screen is round: everything outside the inscribed circle is black (alpha 0), for the client to show as a circle. */
  static void roundMask(byte[] bgra, int w, int h) {
    double r = Math.min(w, h) / 2.0, cx = w / 2.0, cy = h / 2.0;
    for (int y = 0; y < h; y++) {
      double dy = y + 0.5 - cy;
      double half = r * r - dy * dy;
      int x0 = half <= 0 ? w : (int) Math.ceil(cx - Math.sqrt(half) - 0.5);
      int x1 = half <= 0 ? w : (int) Math.floor(cx + Math.sqrt(half) - 0.5);
      int row = y * w * 4;
      if (x0 > w) x0 = w;
      if (x0 > 0) java.util.Arrays.fill(bgra, row, row + Math.min(x0, w) * 4, (byte) 0);
      if (x1 < w - 1) java.util.Arrays.fill(bgra, row + Math.max(x1 + 1, 0) * 4, row + w * 4, (byte) 0);
    }
  }

  private static void setPace(Subscriber s, String ms) {
    try {
      long v = Math.max(0, Math.min(1000, Long.parseLong(ms.trim())));
      synchronized (LOCK) {
        s.paceNanos = v * 1_000_000;
        recomputePace();
        LOCK.notifyAll();
      }
    } catch (NumberFormatException e) {
      // ignored
    }
  }

  /**
   * `viewport W H` from the serve relay (issue 499): the size, in device pixels, at which the tab shows the stream.
   * Frames are scaled down to it before encoding when it is clearly smaller than the device's (see {@link #target});
   * `viewport 0 0` or a size that is not clearly smaller goes back to full resolution. Frames carry their own size in
   * the header, and the device's pixels stay the touch coordinates.
   */
  private static void setViewport(Subscriber s, String args) {
    try {
      String[] v = args.trim().split(" +");
      if (v.length != 2) return;
      int w = Math.max(0, Math.min(16384, Integer.parseInt(v[0])));
      int h = Math.max(0, Math.min(16384, Integer.parseInt(v[1])));
      synchronized (LOCK) {
        if (s.viewW == w && s.viewH == h) return;
        s.viewW = w;
        s.viewH = h;
        s.needsFrame = true;  // a new size needs a frame (H.264: a keyframe, as the encoder is reopened)
        wake = true;
        LOCK.notifyAll();
      }
    } catch (NumberFormatException e) {
      // ignored
    }
  }

  /** Called with LOCK held: the size to encode a w x h frame at, as {w, h}: the largest viewport asked for, else w x h. */
  private static int[] target(int w, int h) {
    int vw = 0, vh = 0;
    for (Subscriber s : SUBSCRIBERS) {
      if (s.viewW <= 0 || s.viewH <= 0) return new int[] {w, h};  // one wants everything
      vw = Math.max(vw, s.viewW);
      vh = Math.max(vh, s.viewH);
    }
    // Only when clearly smaller in both dimensions (under 75%), never up, the aspect ratio kept.
    if (vw <= 0 || vw * 4 >= w * 3 || vh * 4 >= h * 3) return new int[] {w, h};
    double f = Math.min((double) vw / w, (double) vh / h);
    int tw = Math.max(2, (int) Math.round(w * f) & ~1);
    int th = Math.max(2, (int) Math.round(h * f) & ~1);
    return new int[] {tw, th};
  }

  /** Box-filter downscale of BGRA pixels (every source pixel counts towards the output pixel that covers it). */
  static void scaleBgra(byte[] src, int w, int h, byte[] dst, int tw, int th) {
    int[] x0 = new int[tw + 1];
    for (int x = 0; x <= tw; x++) x0[x] = (int) ((long) x * w / tw);
    for (int y = 0; y < th; y++) {
      int ya = (int) ((long) y * h / th), yb = Math.max(ya + 1, (int) ((long) (y + 1) * h / th));
      for (int x = 0; x < tw; x++) {
        int xa = x0[x], xb = Math.max(xa + 1, x0[x + 1]);
        int b = 0, g = 0, r = 0, a = 0;
        for (int yy = ya; yy < yb; yy++) {
          int i = (yy * w + xa) * 4;
          for (int xx = xa; xx < xb; xx++, i += 4) {
            b += src[i] & 255;
            g += src[i + 1] & 255;
            r += src[i + 2] & 255;
            a += src[i + 3] & 255;
          }
        }
        int n = (yb - ya) * (xb - xa);
        int o = (y * tw + x) * 4;
        dst[o] = (byte) ((b + n / 2) / n);
        dst[o + 1] = (byte) ((g + n / 2) / n);
        dst[o + 2] = (byte) ((r + n / 2) / n);
        dst[o + 3] = (byte) ((a + n / 2) / n);
      }
    }
  }

  /** Called with LOCK held. */
  private static void recomputePace() {
    long pace = 0;
    for (Subscriber s : SUBSCRIBERS) {
      if (!s.h264) {
        pace = 0;
        break;
      }
      pace = Math.max(pace, s.paceNanos);
    }
    paceNanos = pace;
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
          String cmd = line.toString().trim();
          if (h264 && cmd.equals("key")) s.askKey();
          else if (h264 && cmd.startsWith("pace ")) setPace(s, cmd.substring(5));
          else if (cmd.startsWith("viewport ")) setViewport(s, cmd.substring(9));
          line.setLength(0);
        }
      } catch (IOException e) {
        // closed
      }
      s.close();
    }, "apksim-frame-stream-eof");
    eof.setDaemon(true);
    eof.start();
    startEncoder();
    synchronized (LOCK) {
      FrameStream.quality = Math.max(10, Math.min(100, quality));
      if (h264) FrameStream.kbps = Math.max(100, Math.min(100_000, kbps));
      SUBSCRIBERS.add(s);
      recomputePace();
      SimWindow.closeHides(true);
      // The new subscriber needs the current screen even if it does not change (s.needsFrame): the last frame is
      // encoded again for it, and the redraw below covers a first subscriber, before any frame was kept.
      wake = true;
      LOCK.notifyAll();
    }
    Control.submit(new Control.Pending(new String[] {"redraw"}));
    try {
      s.run();
    } finally {
      synchronized (LOCK) {
        SUBSCRIBERS.remove(s);
        recomputePace();  // its pace goes with it
        SimWindow.closeHides(!SUBSCRIBERS.isEmpty());
        wake = true;  // the encoder lets go of an H.264 encoder no one needs any more
        LOCK.notifyAll();
      }
    }
  }

  private static boolean wake;  // guarded by LOCK: a subscriber came or went
  /** H.264 encodes that failed in a row; past {@link #MAX_FAILURES} the H.264 subscribers are ended. */
  private static int failures;
  private static final int MAX_FAILURES = 5;
  private static final long RETRY_MS = 200;

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
    byte[] scaled = null;  // raw at the viewport's size, when it is smaller
    int refines = REFINES;  // H.264 re-encodes of a still screen done since the last new frame
    // When the next refinement and the next retry are due (System.nanoTime): set by the encode that calls for them,
    // and kept across frames that bring nothing new, so redraws of an unchanged screen do not put them off.
    long refineAt = 0;
    long retryAt = 0;
    while (true) {
      int w;
      int h;
      long micros;
      int q;
      int rate;
      boolean key;
      boolean refine = false;
      boolean retry = false;
      boolean repeat;
      boolean needy;
      int ew;
      int eh;
      synchronized (LOCK) {
        while (true) {
          // A frame presented again with the same pixels counts as none (unless someone still needs a frame): it
          // must not hold off a refinement or retry that is due.
          if (fresh && !keyWanted && !wake && latest != null && lastEncoded != null && latestW == lastW
              && latestH == lastH && !anyNeedsFrame() && Arrays.equals(lastEncoded, latest)) {
            fresh = false;
          }
          long nowNs = System.nanoTime();
          long paceDue = fresh && !keyWanted && !wake && paceNanos > 0 ? lastNewNanos + paceNanos : 0;
          boolean paced = paceDue > nowNs;
          if ((fresh || keyWanted || wake) && !paced) break;
          // No H.264 subscriber left: its encoder (native buffers, a VideoToolbox session) goes now, not at the
          // next frame, which a still screen may never present.
          if (video != null && !hasCodec(true)) {
            video.close();
            video = null;
          }
          try {
            // H.264 only: after a failed encode, the last frame again as a keyframe, a few times at most.
            boolean canRetry = failures > 0 && lastEncoded != null && hasCodec(true);
            // H.264 only: once frames stop, the last one encoded again, for the detail the bit rate left out.
            boolean canRefine = !fresh && video != null && refines < REFINES && lastEncoded != null && hasCodec(true);
            long now = System.nanoTime();
            if (canRetry && now >= retryAt) {
              retry = true;
              break;
            }
            if (!canRetry && canRefine && now >= refineAt) {
              refine = true;
              break;
            }
            long until = canRetry ? retryAt : canRefine ? refineAt : 0;
            if (paced && (until == 0 || paceDue < until)) until = paceDue;
            LOCK.wait(until == 0 ? 0 : Math.max(1, (until - now) / 1_000_000));
          } catch (InterruptedException e) {
            return;
          }
        }
        wake = false;
        needy = anyNeedsFrame();
        key = keyWanted || retry;
        boolean source = fresh && latest != null;
        fresh = false;
        keyWanted = false;
        // The last frame encoded again: a refinement, a retry, or, on a still screen, a keyframe or a new subscriber's
        // first frame.
        repeat = refine || (!source && (key || needy));
        if (!repeat) lastNewNanos = System.nanoTime();
        if (repeat) {
          if (lastEncoded == null) continue;
          if (raw == null || raw.length != lastEncoded.length) raw = new byte[lastEncoded.length];
          System.arraycopy(lastEncoded, 0, raw, 0, lastEncoded.length);
          w = lastW;
          h = lastH;
          micros = epochMicros();
        } else {
          // Presented again, unchanged (same size too: a rotated solid screen has the same bytes).
          // Kept for a subscriber that has had no frame yet.
          if (!key && !needy && lastEncoded != null && latestW == lastW && latestH == lastH
              && Arrays.equals(lastEncoded, latest)) {
            continue;
          }
          if (latest == null) continue;
          if (raw == null || raw.length != latest.length) raw = new byte[latest.length];
          System.arraycopy(latest, 0, raw, 0, latest.length);
          w = latestW;
          h = latestH;
          micros = latestMicros;
        }
        int[] t = target(w, h);
        ew = t[0];
        eh = t[1];
        q = quality;
        rate = kbps;
      }
      if (SUBSCRIBERS.isEmpty()) continue;
      byte[] pix = raw;  // what is encoded: raw itself, or raw scaled down to the viewport
      if (ew != w || eh != h) {
        if (scaled == null || scaled.length != ew * eh * 4) scaled = new byte[ew * eh * 4];
        scaleBgra(raw, w, h, scaled, ew, eh);
        pix = scaled;
      }
      refines = refine ? refines + 1 : 0;
      // JPEG for a new frame, or for a JPEG subscriber that has had none yet (it may have joined while the frame
      // before was being encoded, which then was not offered to it).
      if (hasCodec(false) && (!repeat || (!refine && anyNeedsFrame(false)))) {
        long t0 = System.nanoTime();
        if (bitmap == null || bitmap.getWidth() != ew || bitmap.getHeight() != eh) {
          if (bitmap != null) bitmap.recycle();
          bitmap = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888);
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pix, 0, ew * eh * 4));
        jpeg.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, q, jpeg);
        int encodeMicros = (int) ((System.nanoTime() - t0) / 1000);
        Frame f = new Frame(jpeg.toByteArray(), ew, eh, micros, encodeMicros, true);
        for (Subscriber s : SUBSCRIBERS) if (!s.h264) s.offer(f);
      }
      if (hasCodec(true)) {
        long t0 = System.nanoTime();
        if (video != null && (video.width != (ew & ~1) || video.height != (eh & ~1) || videoKbps != rate)) {
          video.close();
          video = null;
        }
        if (video == null) {
          video = H264Encoder.open(ew, eh, rate);
          videoKbps = rate;
          key = true;
        }
        if (anyNeedsFrame(true)) key = true;  // a new H.264 subscriber starts at a keyframe
        byte[] unit = video == null ? null : video.encode(pix, ew * 4, micros, key);
        if (unit == null) {
          // Nothing came out (or no encoder for this size): a fresh encoder and the last frame again as a keyframe,
          // RETRY_MS later even on a still screen. Past MAX_FAILURES in a row the H.264 streams end (their tabs see
          // the stream close) rather than wait on a screen that never comes.
          if (video != null) video.close();
          video = null;
          retryAt = System.nanoTime() + RETRY_MS * 1_000_000;
          if (++failures > MAX_FAILURES) {
            System.err.println("[apksim] H.264 encoding failed " + failures + " times at " + w + "x" + h
                + ": ending the H.264 streams");
            failures = 0;
            for (Subscriber s : SUBSCRIBERS) if (s.h264) s.close();
          }
        } else {
          failures = 0;
          refineAt = System.nanoTime() + REFINE_MS * 1_000_000;  // the next refinement, if nothing new comes
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

  /** Whether a subscriber has had no frame yet (an H.264 one: no keyframe). */
  private static boolean anyNeedsFrame() {
    for (Subscriber s : SUBSCRIBERS) if (s.needsFrame) return true;
    return false;
  }

  private static boolean anyNeedsFrame(boolean h264) {
    for (Subscriber s : SUBSCRIBERS) if (s.h264 == h264 && s.needsFrame) return true;
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
    /** This viewer's `pace` request in ns (0: none). Guarded by LOCK. */
    long paceNanos;
    /** No frame offered yet (H.264: no keyframe): the encoder sends it the current screen even if unchanged. */
    volatile boolean needsFrame = true;
    /** This viewer's `viewport` in device pixels (0: full resolution). Guarded by LOCK. */
    int viewW;
    int viewH;

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
      needsFrame = false;
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
