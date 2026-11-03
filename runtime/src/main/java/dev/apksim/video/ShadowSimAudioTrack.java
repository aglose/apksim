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

  private ByteBuffer scratch(int bytes) {
    if (scratch == null || scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 16)).order(ByteOrder.nativeOrder());
    scratch.clear();
    return scratch;
  }

  /** Writes bytes already in a direct buffer; returns the bytes taken. */
  private int write(ByteBuffer direct, int offset, int bytes, boolean blocking) {
    int n = SimAudio.nativeOutWrite(out, direct, offset, bytes - bytes % frameBytes, blocking);
    STATS.addAndGet(1, n / frameBytes);
    return n;
  }

  @Implementation
  @Override
  protected int native_write_byte(byte[] data, int offset, int size, int format, boolean blocking) {
    if (out() == 0) return super.native_write_byte(data, offset, size, format, blocking);
    ByteBuffer b = scratch(size);
    b.put(data, offset, size);
    return write(b, 0, size, blocking);
  }

  @Implementation
  @Override
  protected int native_write_native_bytes(ByteBuffer data, int position, int size, int format, boolean blocking) {
    if (out() == 0) return super.native_write_native_bytes(data, position, size, format, blocking);
    if (data.isDirect()) return write(data, position, size, blocking);
    ByteBuffer b = scratch(size);
    b.put(data.duplicate().position(position).limit(position + size));
    return write(b, 0, size, blocking);
  }

  @Implementation
  protected int native_write_short(short[] data, int offset, int size, int format, boolean blocking) {
    if (out() == 0) return size;
    ByteBuffer b = scratch(size * 2);
    b.asShortBuffer().put(data, offset, size);
    return write(b, 0, size * 2, blocking) / 2;
  }

  @Implementation
  protected int native_write_float(float[] data, int offset, int size, int format, boolean blocking) {
    if (out() == 0) return size;
    ByteBuffer b = scratch(size * 4);
    b.asFloatBuffer().put(data, offset, size);
    return write(b, 0, size * 4, blocking) / 4;
  }

  @Implementation
  protected void native_start() {
    if (out() != 0) SimAudio.nativeOutPlay(out, true);
  }

  @Implementation
  @Override
  protected void play() {
    super.play();
    native_start();
  }

  @Implementation
  protected void native_pause() {
    if (out() != 0) SimAudio.nativeOutPlay(out, false);
  }

  /** Streaming tracks keep playing what was written, as on a device: the head stops once it is all played. */
  @Implementation
  protected void native_stop() {}

  @Implementation
  protected void native_flush() {
    if (out() != 0) SimAudio.nativeOutFlush(out);
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
    if (out() == 0) return super.getPlaybackHeadPosition();
    return (int) SimAudio.nativeOutPlayed(out);
  }

  /** The head position now, which is what a device's timestamp says once its output latency is taken out. */
  @Implementation
  protected int native_get_timestamp(long[] longArray) {
    if (out() == 0) return AudioTrack.ERROR;
    long played = SimAudio.nativeOutPlayed(out);
    if (played == 0) return AudioTrack.ERROR;
    longArray[0] = played;
    longArray[1] = System.nanoTime();
    return AudioTrack.SUCCESS;
  }

  @Implementation
  protected void native_setVolume(float left, float right) {
    if (out() != 0) SimAudio.nativeOutVolume(out, Math.max(left, right), SimAudio.MUTED);
  }

  @Implementation
  protected void native_release() {
    if (out != 0) {
      STATS.addAndGet(2, SimAudio.nativeOutUnderruns(out));
      SimAudio.nativeOutRelease(out);
      out = 0;
    }
  }

  @Implementation
  protected void native_finalize() {
    native_release();
  }
}
