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
 * without {@link SimAudio} keep Robolectric's behaviour, except running instances on a host with no sound device,
 * whose tracks run on a {@link VirtualOut} clock (issue 545).
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
  private float volume = 1f;  // guarded by lock
  private int encoding;
  private final int trackId = NEXT_ID.incrementAndGet();
  private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger();
  private ByteBuffer tapScratch;
  /** Set for a track with no sound device (the Linux image): its head is a clock, {@link VirtualOut}. [out] is then 1. */
  private VirtualOut virt;

  /**
   * Whether tracks here get a virtual output (issue 545): hosts with no sound device, in running instances (test runs
   * keep Robolectric's, which plays all at once), unless --audio off. {@code -Dapksim.virtualAudio=true} forces it
   * on any host, to check it where a sound device exists.
   */
  static boolean virtualOutput() {
    if (dev.apksim.Features.audio() == dev.apksim.Features.Audio.OFF || System.getProperty("apksim.instrument") != null) return false;
    return Boolean.getBoolean("apksim.virtualAudio") || !dev.apksim.HostOs.MAC;
  }

  /** The CoreAudio stream, made on first use, or 0 when this track stays with Robolectric. */
  private long out() {
    if (tried) return out;
    tried = true;
    int encoding = real.getAudioFormat();
    boolean virtual = virtualOutput();
    if (!virtual && !SimAudio.available() || (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_8BIT
        && encoding != AudioFormat.ENCODING_PCM_FLOAT)) {
      return 0;
    }
    this.encoding = encoding;
    int channels = real.getChannelCount();
    int bufferBytes = ReflectionHelpers.getField(real, "mNativeBufferSizeInBytes");
    int bytesPerFrame = channels * (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2);
    if (virtual) {
      virt = new VirtualOut(real.getSampleRate(), bytesPerFrame, bufferBytes);
      out = 1;
    } else {
      out = SimAudio.nativeOutCreate(real.getSampleRate(), channels, encoding, bufferBytes);
    }
    if (out == 0) {
      System.err.println("[apksim] CoreAudio refused an AudioTrack (" + real.getSampleRate() + " Hz, " + channels + " ch); it plays silently");
      return 0;
    }
    frameBytes = bytesPerFrame;
    // Registered before the mute state is read, all under [lock]: a concurrent `audio mute` either finds this track
    // (and waits for the lock) or ran before the read below, which then sees it.
    SimAudio.track(this, true);
    oVolume(out, volume);
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

  // The stream's calls, to CoreAudio or to the virtual clock.
  private int oWrite(long o, ByteBuffer direct, int offset, int bytes, boolean blocking) {
    return virt != null ? virt.write(bytes / frameBytes, blocking) * frameBytes : SimAudio.nativeOutWrite(o, direct, offset, bytes, blocking);
  }

  private void oPlay(long o, boolean on) {
    if (virt != null) virt.play(on); else SimAudio.nativeOutPlay(o, on);
  }

  private void oFlush(long o) {
    if (virt != null) virt.flush(); else SimAudio.nativeOutFlush(o);
  }

  private long oPlayed(long o) {
    return virt != null ? virt.played() : SimAudio.nativeOutPlayed(o);
  }

  private long oPending(long o) {
    return virt != null ? virt.pending() : SimAudio.nativeOutPending(o);
  }

  private void oVolume(long o, float v) {
    if (virt == null) SimAudio.nativeOutVolume(o, v, SimAudio.muted());
  }

  private ByteBuffer scratch(int bytes) {
    if (scratch == null || scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 16)).order(ByteOrder.nativeOrder());
    scratch.clear();
    return scratch;
  }

  /** Writes bytes already in a direct buffer to stream [o] (taken with use()); returns the bytes taken. */
  private int write(long o, ByteBuffer direct, int offset, int bytes, boolean blocking) {
    boolean tap = AudioTap.wanted();  // muted or not: a tab hears what the Mac would play
    long queued = tap ? oPending(o) : 0;  // before the write: what plays ahead of it
    long at = tap ? System.currentTimeMillis() * 1000L : 0;  // before a write that may block for room
    int n = oWrite(o, direct, offset, bytes - bytes % frameBytes, blocking);
    STATS.addAndGet(1, n / frameBytes);
    if (tap && n > 0) tap(direct, offset, n, queued, at);
    return n;
  }

  /** Copies what was just written to the tab's audio stream, as 16-bit PCM at the track's volume (issue 532). */
  private void tap(ByteBuffer direct, int offset, int n, long queued, long atMicros) {
    ByteBuffer src = direct.duplicate().order(ByteOrder.nativeOrder());
    src.position(offset).limit(offset + n);
    float gain = Math.max(0f, Math.min(1f, volume));  // the output's own volume, which the copy is taken ahead of
    ByteBuffer pcm;
    int bytes;
    if (encoding == AudioFormat.ENCODING_PCM_16BIT && gain == 1f) {
      pcm = src;
      bytes = n;
    } else {
      int samples = n / (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1);
      if (tapScratch == null || tapScratch.capacity() < samples * 2) tapScratch = ByteBuffer.allocateDirect(Math.max(samples * 2, 1 << 15)).order(ByteOrder.nativeOrder());
      tapScratch.clear();
      for (int i = 0; i < samples; i++) {
        float v;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) v = src.getFloat() * 32767f;
        else if (encoding == AudioFormat.ENCODING_PCM_16BIT) v = src.getShort();
        else v = ((src.get() & 0xff) - 128) << 8;  // 8-bit PCM is unsigned
        tapScratch.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * gain))));
      }
      tapScratch.flip();
      pcm = tapScratch;
      bytes = samples * 2;
    }
    AudioTap.offer(trackId, real.getSampleRate(), real.getChannelCount(), pcm, bytes, queued, atMicros);
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
      if (out() != 0) oPlay(out, true);
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
      if (out() != 0) oPlay(out, false);
    }
  }

  /** Streaming tracks keep playing what was written, as on a device: the head stops once it is all played. */
  @Implementation
  protected void native_stop() {}

  @Implementation
  protected void native_flush() {
    synchronized (lock) {
      if (out() != 0) oFlush(out);
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
      return (int) oPlayed(o);
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
      played = oPlayed(o);
    } finally {
      done();
    }
    if (played == 0) return AudioTrack.ERROR;
    longArray[0] = played;
    longArray[1] = System.nanoTime();
    return AudioTrack.SUCCESS;
  }

  /** Applies the mute state in force to this track's stream, if it has one. */
  void remute() {
    synchronized (lock) {
      if (out != 0) oVolume(out, volume);
    }
  }

  @Implementation
  protected void native_setVolume(float left, float right) {
    // The volume, the mute state and the native call under one lock, so a stale mute state cannot land after
    // `audio mute` has updated the stream (release clears [out] under the same lock).
    synchronized (lock) {
      volume = Math.max(left, right);
      if (out() != 0) oVolume(out, volume);
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
      SimAudio.track(this, false);
      oPlay(o, false);
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
    if (virt != null) {
      STATS.addAndGet(2, virt.underruns());
      virt.close();
    } else {
      STATS.addAndGet(2, SimAudio.nativeOutUnderruns(o));
      SimAudio.nativeOutRelease(o);
    }
  }

  @Implementation
  protected void native_finalize() {
    native_release();
  }
}
