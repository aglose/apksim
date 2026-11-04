package dev.apksim.video;

import android.media.AudioFormat;
import android.media.AudioTrack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.util.ReflectionHelpers;

/**
 * AudioTrack that plays: PCM goes to the Mac's default output (native/audio.m) and the playback head moves only as
 * CoreAudio plays it. Robolectric's own AudioTrack counts everything written as played at once, so a media player
 * that follows the audio clock (ExoPlayer does) would race through its content. Non-PCM encodings, and hosts
 * without {@link SimAudio}, keep Robolectric's behaviour.
 */
@Implements(AudioTrack.class)
public class ShadowSimAudioTrack extends ShadowAudioTrack {
  /** Totals for the `audio` control command: tracks opened, frames written, underruns of tracks since closed. */
  static final java.util.concurrent.atomic.AtomicLongArray STATS = new java.util.concurrent.atomic.AtomicLongArray(3);

  @RealObject private AudioTrack real;
  private long out;
  private boolean tried;
  // Native calls on [out] in progress, so release frees the stream only once none is: an app may release a track
  // from one thread while another is blocked in write() waiting for room (allowed on a device).
  private final Object lock = new Object();
  private int using;
  private ByteBuffer scratch;
  private int frameBytes;

  /** The CoreAudio stream, made on first use, or 0 when this track stays with Robolectric. */
  private long out() {
    if (tried) return out;
    tried = true;
    int encoding = real.getAudioFormat();
    if (!SimAudio.available() || (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_8BIT
        && encoding != AudioFormat.ENCODING_PCM_FLOAT)) {
      return 0;
    }
    int channels = real.getChannelCount();
    int bufferBytes = ReflectionHelpers.getField(real, "mNativeBufferSizeInBytes");
    out = SimAudio.nativeOutCreate(real.getSampleRate(), channels, encoding, bufferBytes);
    if (out == 0) {
      System.err.println("[apksim] CoreAudio refused an AudioTrack (" + real.getSampleRate() + " Hz, " + channels + " ch); it plays silently");
      return 0;
    }
    frameBytes = channels * (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2);
    SimAudio.nativeOutVolume(out, 1f, SimAudio.MUTED);
    STATS.incrementAndGet(0);
    return out;
  }

  /** The stream for one native call, counted until {@link #done}; 0 when there is none (not PCM, or released). */
  private long use() {
    synchronized (lock) {
      long o = out();
      if (o != 0) using++;
      return o;
    }
  }

  private void done() {
    synchronized (lock) {
      if (--using == 0) lock.notifyAll();
    }
  }

  private ByteBuffer scratch(int bytes) {
    if (scratch == null || scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 16)).order(ByteOrder.nativeOrder());
    scratch.clear();
    return scratch;
  }

  /** Writes bytes already in a direct buffer to stream [o] (taken with use()); returns the bytes taken. */
  private int write(long o, ByteBuffer direct, int offset, int bytes, boolean blocking) {
    int n = SimAudio.nativeOutWrite(o, direct, offset, bytes - bytes % frameBytes, blocking);
    STATS.addAndGet(1, n / frameBytes);
    return n;
  }

  @Implementation
  @Override
  protected int native_write_byte(byte[] data, int offset, int size, int format, boolean blocking) {
    long o = use();
    if (o == 0) return super.native_write_byte(data, offset, size, format, blocking);
    try {
      ByteBuffer b = scratch(size);
      b.put(data, offset, size);
      return write(o, b, 0, size, blocking);
    } finally {
      done();
    }
  }

  @Implementation
  @Override
  protected int native_write_native_bytes(ByteBuffer data, int position, int size, int format, boolean blocking) {
    long o = use();
    if (o == 0) return super.native_write_native_bytes(data, position, size, format, blocking);
    try {
      if (data.isDirect()) return write(o, data, position, size, blocking);
      ByteBuffer b = scratch(size);
      b.put(data.duplicate().position(position).limit(position + size));
      return write(o, b, 0, size, blocking);
    } finally {
      done();
    }
  }

  @Implementation
  protected int native_write_short(short[] data, int offset, int size, int format, boolean blocking) {
    long o = use();
    if (o == 0) return size;
    try {
      ByteBuffer b = scratch(size * 2);
      b.asShortBuffer().put(data, offset, size);
      return write(o, b, 0, size * 2, blocking) / 2;
    } finally {
      done();
    }
  }

  @Implementation
  protected int native_write_float(float[] data, int offset, int size, int format, boolean blocking) {
    long o = use();
    if (o == 0) return size;
    try {
      ByteBuffer b = scratch(size * 4);
      b.asFloatBuffer().put(data, offset, size);
      return write(o, b, 0, size * 4, blocking) / 4;
    } finally {
      done();
    }
  }

  // Start, pause and flush switch the output on and off, so they run under the lock: release stops it under the same
  // lock, and a start already admitted can then not switch a released stream back on (a writer blocked for room
  // would wait for render callbacks that never come, and release for that writer).
  @Implementation
  protected void native_start() {
    synchronized (lock) {
      if (out() != 0) SimAudio.nativeOutPlay(out, true);
    }
  }

  @Implementation
  @Override
  protected void play() {
    super.play();
    native_start();
  }

  @Implementation
  protected void native_pause() {
    synchronized (lock) {
      if (out() != 0) SimAudio.nativeOutPlay(out, false);
    }
  }

  /** Streaming tracks keep playing what was written, as on a device: the head stops once it is all played. */
  @Implementation
  protected void native_stop() {}

  @Implementation
  protected void native_flush() {
    synchronized (lock) {
      if (out() != 0) SimAudio.nativeOutFlush(out);
    }
  }

  @Implementation
  @Override
  protected void flush() {
    super.flush();
    native_flush();
  }

  @Implementation
  @Override
  protected int getPlaybackHeadPosition() {
    long o = use();
    if (o == 0) return super.getPlaybackHeadPosition();
    try {
      return (int) SimAudio.nativeOutPlayed(o);
    } finally {
      done();
    }
  }

  /** The head position now, which is what a device's timestamp says once its output latency is taken out. */
  @Implementation
  protected int native_get_timestamp(long[] longArray) {
    long o = use();
    if (o == 0) return AudioTrack.ERROR;
    long played;
    try {
      played = SimAudio.nativeOutPlayed(o);
    } finally {
      done();
    }
    if (played == 0) return AudioTrack.ERROR;
    longArray[0] = played;
    longArray[1] = System.nanoTime();
    return AudioTrack.SUCCESS;
  }

  @Implementation
  protected void native_setVolume(float left, float right) {
    long o = use();
    if (o == 0) return;
    try {
      SimAudio.nativeOutVolume(o, Math.max(left, right), SimAudio.MUTED);
    } finally {
      done();
    }
  }

  /**
   * Frees the stream once no other thread is in a native call on it. Clearing [out] keeps new calls away, and
   * stopping it (under the lock, so no start can follow) wakes a writer blocked for room, which then returns what it
   * took, as on a device.
   */
  @Implementation
  protected void native_release() {
    long o;
    synchronized (lock) {
      o = out;
      if (o == 0) return;
      out = 0;
      SimAudio.nativeOutPlay(o, false);
    }
    synchronized (lock) {
      boolean interrupted = false;
      while (using > 0) {
        try {
          lock.wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    STATS.addAndGet(2, SimAudio.nativeOutUnderruns(o));
    SimAudio.nativeOutRelease(o);
  }

  @Implementation
  protected void native_finalize() {
    native_release();
  }
}
