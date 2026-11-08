package dev.apksim.video;

import java.nio.ByteBuffer;

/**
 * Sound (native/audio.m): AAC and MP3 decoding on AudioToolbox for MediaCodec, and AudioTrack's PCM played on the
 * Mac's default output. Mac only; elsewhere {@link #available} is false and Robolectric's silent AudioTrack stays.
 * {@code --audio off} turns it off, {@code --audio mute} keeps the timing but plays silence ({@link dev.apksim.Features}:
 * read at each launch, so a standby that loaded the library still serves a launch without sound).
 */
public final class SimAudio {
  private SimAudio() {}

  static final int AAC = 0, MP3 = 1;
  private static final boolean AVAILABLE = load();

  static boolean available() {
    return AVAILABLE && dev.apksim.Features.audio() != dev.apksim.Features.Audio.OFF;
  }

  /** Whether streams play silence (--audio mute), read when a stream opens or its volume changes. */
  static boolean muted() {
    return dev.apksim.Features.audio() == dev.apksim.Features.Audio.MUTE;
  }

  private static final java.util.Set<ShadowSimAudioTrack> TRACKS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());  // weak: an app may drop a track without release

  static void track(ShadowSimAudioTrack t, boolean open) {
    synchronized (TRACKS) {
      if (open) TRACKS.add(t); else TRACKS.remove(t);
    }
  }

  /** Applies the current mute state to every open track (the `audio` command). */
  public static void remuteAll() {
    java.util.List<ShadowSimAudioTrack> open;
    synchronized (TRACKS) {
      open = new java.util.ArrayList<>(TRACKS);
    }
    for (ShadowSimAudioTrack t : open) t.remute();
  }

  private static boolean load() {
    try {
      // A launch without sound never loads the library (a standby loads it, and Features then turns it off).
      String option = System.getProperty("apksim.audio", "on");
      if (!dev.apksim.HostOs.MAC || option.equals("off") || option.equals("false")) return false;
      System.load(dev.apksim.HostOs.runtimeLib("libsimaudio").toString());
      return true;
    } catch (Throwable t) {
      System.err.println("[apksim] no sound: " + t);
      return false;
    }
  }

  /** A decoder from AAC (config: the AudioSpecificConfig, MediaCodec's csd-0) or MP3 to 16-bit PCM, or 0. */
  static native long nativeDecoderCreate(int codec, int sampleRate, int channels, byte[] config);

  /** Decodes one access unit; returns the PCM bytes written to out, or -1. Both buffers are direct. */
  static native int nativeDecode(long decoder, ByteBuffer in, int offset, int length, ByteBuffer out, int capacity);

  static native void nativeDecoderReset(long decoder);

  static native void nativeDecoderRelease(long decoder);

  /** An output stream; encoding is AudioFormat's ENCODING_PCM_16BIT, _8BIT or _FLOAT. 0 if CoreAudio refused. */
  static native long nativeOutCreate(int sampleRate, int channels, int encoding, int bufferBytes);

  /** Queues PCM from a direct buffer; returns the bytes taken (all of them when blocking, unless paused). */
  static native int nativeOutWrite(long out, ByteBuffer pcm, int offset, int length, boolean blocking);

  static native void nativeOutPlay(long out, boolean play);

  static native void nativeOutFlush(long out);

  /** Frames played since creation or the last flush. */
  static native long nativeOutPlayed(long out);

  static native long nativeOutPending(long out);

  static native long nativeOutUnderruns(long out);

  static native void nativeOutVolume(long out, float volume, boolean muted);

  static native void nativeOutRelease(long out);

  /** Streams released while a writer was still inside nativeOutWrite (a bug: that writer then reads freed memory). */
  static native long nativeReleasedUnderWrite();
}
