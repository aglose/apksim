package dev.apksim.video;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The app's audio for a browser tab (issue 532): what AudioTracks play ({@link ShadowSimAudioTrack}) is copied, as 16-bit
 * little-endian PCM in chunks of at most 20 ms, to the control connections that sent `audiowatch` (`apksim serve`'s
 * session). Nothing is copied while no one listens, and a listener that cannot keep up loses chunks, never the app's
 * output. A line per chunk: {@code u PRESENTED_US TRACK RATE CHANNELS BASE64}; PRESENTED_US is epoch microseconds, the
 * clock of the screen frames' header, at which the chunk's first sample is expected to play (when written, plus what
 * the track still had queued). The tab sends {@code stream off|on} lines, which stop and start the copying for that
 * connection without touching the app. PCM, not Opus: no encoder is linked here, so the bit rate is 1.5 Mbit/s for
 * 48 kHz stereo before Base64.
 */
public final class AudioTap {
  private AudioTap() {}

  private static final class Listener {
    final PrintWriter out;
    volatile boolean on = true;
    // Chunks wait here for the sender thread, so a slow reader never blocks the app's audio thread; past the bound they are dropped.
    final java.util.concurrent.ArrayBlockingQueue<String> queue = new java.util.concurrent.ArrayBlockingQueue<>(1000);  // 20 s: one large write (a static track) is hundreds of chunks at once

    Listener(PrintWriter out) {
      this.out = out;
    }
  }

  private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
  private static volatile boolean active;

  /** Whether anyone is listening and wants audio now: the track's write path asks before it copies a byte. */
  static boolean wanted() {
    return active;
  }

  private static void recount() {
    boolean any = false;
    for (Listener l : LISTENERS) any |= l.on;
    active = any;
  }

  /** The control connection's `audiowatch`: chunks go out until the watcher hangs up. */
  public static void serve(BufferedReader in, PrintWriter out) {
    out.println("ok audiowatch");
    Listener l = new Listener(out);
    LISTENERS.add(l);
    recount();
    Thread sender = new Thread(() -> {
      try {
        while (true) {
          String line = l.queue.take();
          l.out.println(line);
          if (l.out.checkError()) return;
        }
      } catch (InterruptedException e) {
        // the watcher went away
      }
    }, "apksim-audio-tap");
    sender.setDaemon(true);
    sender.start();
    try {
      for (String line; (line = in.readLine()) != null; ) {
        String t = line.trim();
        if (t.equals("stream off")) l.on = false;
        else if (t.equals("stream on")) l.on = true;
        else continue;
        recount();
      }
    } catch (IOException e) {
      // the watcher went away
    } finally {
      LISTENERS.remove(l);
      sender.interrupt();
      recount();
    }
  }

  /**
   * One write of a track: [pcm16] holds [bytes] bytes of 16-bit PCM (little-endian, native order of a direct buffer),
   * [queuedFrames] frames were still waiting to play at [atMicros] (epoch), just before it was written.
   */
  static void offer(int track, int rate, int channels, ByteBuffer pcm16, int bytes, long queuedFrames, long atMicros) {
    if (!active || rate <= 0 || channels <= 0) return;
    int frameBytes = channels * 2;
    int chunk = Math.max(1, rate / 50) * frameBytes;  // 20 ms
    long presented = atMicros + queuedFrames * 1_000_000L / rate;
    byte[] raw = new byte[Math.min(chunk, bytes)];
    ByteBuffer src = pcm16.duplicate().order(ByteOrder.nativeOrder());
    for (int off = 0; off + frameBytes <= bytes; ) {
      int n = Math.min(chunk, bytes - off);
      n -= n % frameBytes;
      if (n != raw.length) raw = new byte[n];
      src.position(pcm16.position() + off).limit(pcm16.position() + off + n);
      ShortBufferCopy.littleEndian(src, raw);
      String line = "u " + presented + " " + track + " " + rate + " " + channels + " " + Base64.getEncoder().encodeToString(raw);
      for (Listener l : LISTENERS) {
        if (l.on) l.queue.offer(line);  // full: this chunk is dropped
      }
      presented += (long) (n / frameBytes) * 1_000_000L / rate;
      off += n;
    }
  }

  /** Native-order 16-bit samples to little-endian bytes. */
  private static final class ShortBufferCopy {
    static void littleEndian(ByteBuffer src, byte[] dst) {
      ByteBuffer d = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
      d.asShortBuffer().put(src.asShortBuffer());
    }
  }
}
