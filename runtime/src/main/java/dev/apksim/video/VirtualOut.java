package dev.apksim.video;

/**
 * An AudioTrack's output with no sound device (issue 545): the Linux image has none, so a track's playback head is a
 * clock instead. From {@code play()} the head moves at the track's sample rate, as a device's output consumes what
 * was written; a write takes what fits in the track's buffer and, when blocking, waits for room, so a player that
 * paces itself by the track (ExoPlayer's audio sink drives its video timing) sees the timing of the Mac's CoreAudio
 * stream whether or not a tab listens. Nothing is stored: only counts of frames, never the samples (the tap copies
 * those as they are written). When the head catches up with what was written the track has underrun, as an output does,
 * and the clock starts again with the next write.
 */
final class VirtualOut {
  private final int rate;
  private final long capacity;  // frames the track's buffer holds
  private final Object lock = new Object();
  private long written;  // frames accepted since creation or the last flush
  private long base;  // frames played when the clock last started
  private long since;  // System.nanoTime() when it did
  private boolean playing;
  private boolean closed;
  private boolean starved;
  private long underruns;

  VirtualOut(int rate, int frameBytes, int bufferBytes) {
    this.rate = Math.max(1, rate);
    long frames = bufferBytes > 0 ? bufferBytes / Math.max(1, frameBytes) : 0;
    this.capacity = Math.max(frames, this.rate / 10);  // at least 100 ms, as CoreAudio's stream, so a write is never refused for good
  }

  private long raw(long now) {
    long dt = now - since;
    return playing ? base + dt / 1_000_000_000L * rate + dt % 1_000_000_000L * rate / 1_000_000_000L : base;
  }

  /** Brings the clock up to date: a head that reached what was written stops there (an underrun) until the next write. */
  private long settle() {
    long now = System.nanoTime();
    long p = raw(now);
    if (playing && p >= written) {
      if (p > written && !starved && written > 0) {
        underruns++;
        starved = true;
      }
      base = written;
      since = now;
      return written;
    }
    return p;
  }

  /** Takes up to {@code frames} frames; when blocking, waits for room while playing. Returns the frames taken. */
  int write(long frames, boolean blocking) {
    long taken = 0;
    boolean interrupted = false;  // kept and restored at the end: an interrupt does not cut a write short, as on CoreAudio's
    synchronized (lock) {
      while (!closed) {
        long p = settle();
        long room = capacity - (written - p);
        long n = Math.min(frames - taken, Math.max(0, room));
        if (n > 0) {
          if (written == p) {  // idle (or starved): playing resumes with this data
            base = p;
            since = System.nanoTime();
          }
          written += n;
          taken += n;
          starved = false;
        }
        if (taken >= frames || !blocking || !playing) break;
        long need = Math.min(frames - taken, capacity) - Math.max(0, capacity - (written - settle()));
        long ms = Math.max(1, Math.min(20, need * 1000 / rate + 1));
        try {
          lock.wait(ms);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    return (int) taken;
  }

  void play(boolean on) {
    synchronized (lock) {
      long p = settle();
      base = p;
      since = System.nanoTime();
      playing = on;
      lock.notifyAll();
    }
  }

  void flush() {
    synchronized (lock) {
      written = 0;
      base = 0;
      since = System.nanoTime();
      starved = false;
      lock.notifyAll();
    }
  }

  long played() {
    synchronized (lock) {
      return settle();
    }
  }

  long pending() {
    synchronized (lock) {
      return written - settle();
    }
  }

  long underruns() {
    synchronized (lock) {
      settle();
      return underruns;
    }
  }

  void close() {
    synchronized (lock) {
      closed = true;
      playing = false;
      lock.notifyAll();
    }
  }
}
