package dev.apksim.video;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.util.ReflectionHelpers;

/**
 * H.264 and HEVC MediaCodec decoders on VideoToolbox, rendering to the Surface they were configured with, and AAC and
 * MP3 decoders on AudioToolbox, as media players (Media3/ExoPlayer) use them. Robolectric's MediaCodec does the buffer
 * bookkeeping (both modes) and calls a process function per buffer; each codec here gets its own decoder. A video
 * output buffer carries a reference to its decoded picture, drawn into the Surface's TextureView when the app
 * releases the buffer with render=true; an audio one carries 16-bit PCM, for AudioTrack ({@link ShadowSimAudioTrack}).
 */
@Implements(MediaCodec.class)
public class ShadowSimMediaCodec extends ShadowMediaCodec {
  static final String AVC_DECODER = "apksim.avc.decoder";
  static final String HEVC_DECODER = "apksim.hevc.decoder";
  static final String AAC_DECODER = "apksim.aac.decoder";
  static final String MP3_DECODER = "apksim.mp3.decoder";

  /**
   * Stand for each decoder in Robolectric's table until a codec is made and gets its own. Told apart by identity, so
   * each is its own object (one lambda would be a single shared instance).
   */
  private static final CodecConfig.Codec AVC = new Placeholder(), HEVC = new Placeholder(), AAC = new Placeholder(), MP3 = new Placeholder();

  private static final class Placeholder implements CodecConfig.Codec {
    @Override
    public void process(ByteBuffer in, ByteBuffer out) {
      throw new IllegalStateException("not configured");
    }
  }

  /**
   * Lists the decoders with MediaCodecList and Robolectric's codec table. Run once at startup. Not the video ones with
   * --video off, nor the audio ones with --audio off (Features): the app then sees a device without them.
   */
  public static void install() {
    if (VideoToolbox.available() && dev.apksim.Features.video() != dev.apksim.Features.Video.OFF) {
      video(AVC_DECODER, MediaFormat.MIMETYPE_VIDEO_AVC, AVC, MediaCodecInfo.CodecProfileLevel.AVCLevel52,
          MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
          MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
          MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh);
      video(HEVC_DECODER, MediaFormat.MIMETYPE_VIDEO_HEVC, HEVC, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52,
          MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
    }
    if (!VideoToolbox.available() && dev.apksim.Features.video() != dev.apksim.Features.Video.OFF) {
      // Players (Media3) then drop the video renderer and play the rest, logging nothing an app owner would see.
      System.err.println("[apksim] MediaCodec video/avc, video/hevc: no decoder on this host (no VideoToolbox or libvtdecoder);"
          + " an app's video will not draw, its audio still plays");
    }
    if (SimAudio.available()) {
      try {
        audio(AAC_DECODER, MediaFormat.MIMETYPE_AUDIO_AAC, AAC, MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            MediaCodecInfo.CodecProfileLevel.AACObjectHE, MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS);
        audio(MP3_DECODER, MediaFormat.MIMETYPE_AUDIO_MPEG, MP3);
      } catch (RuntimeException e) {
        // Sound is optional: an app must start without it.
        System.err.println("[apksim] audio decoders not listed: " + e);
      }
    }
  }

  private static void video(String name, String mime, CodecConfig.Codec placeholder, int level, int... profiles) {
    MediaCodecInfo.CodecProfileLevel[] levels = new MediaCodecInfo.CodecProfileLevel[profiles.length];
    for (int i = 0; i < profiles.length; i++) levels[i] = level(profiles[i], level);
    ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
        .setName(name)
        .setIsEncoder(false)
        .setIsHardwareAccelerated(true)
        .setIsVendor(true)
        .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(mime, 8192, 4320))
            .setIsEncoder(false)
            .setProfileLevels(levels)
            .setColorFormats(new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
            .build())
        .build());
    // Input sized once the format is known (Decoder.onConfigured, sizeInputs): Robolectric allocates its 10 input
    // buffers here, zero-filled. Output holds only a picture reference. Robolectric looks a codec up by the string it
    // was created with: the type for createDecoderByType, the name for createByCodecName, which is what media
    // players use.
    CodecConfig config = new CodecConfig(64 << 10, 8, placeholder);
    ShadowMediaCodec.addDecoder(mime, config);
    ShadowMediaCodec.addDecoder(name, config);
  }

  private static void audio(String name, String mime, CodecConfig.Codec placeholder, int... profiles) {
    MediaCodecInfo.CodecProfileLevel[] levels = new MediaCodecInfo.CodecProfileLevel[profiles.length];
    for (int i = 0; i < profiles.length; i++) levels[i] = level(profiles[i], 0);
    MediaFormat format = MediaFormat.createAudioFormat(mime, 48000, 8);
    // What AudioCapabilities reads, so players see every common rate rather than only 48 kHz.
    format.setString("sample-rate-ranges", "7350-96000");
    format.setString("max-channel-count", "8");
    ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
        .setName(name)
        .setIsEncoder(false)
        .setIsSoftwareOnly(true)
        .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(false)
            .setProfileLevels(levels)
            .build())
        .build());
    // An access unit in; up to 8 channels of 1152 16-bit frames out (an MP3 frame; AAC has 1024).
    CodecConfig config = new CodecConfig(1 << 16, 1 << 16, placeholder);
    ShadowMediaCodec.addDecoder(mime, config);
    ShadowMediaCodec.addDecoder(name, config);
  }

  /** Totals across codecs, for the `video` control command. */
  static final java.util.concurrent.atomic.AtomicLongArray STATS = new java.util.concurrent.atomic.AtomicLongArray(14);

  static final String[] STAT_NAMES = {"inputs", "pictures", "decodeErrors", "rendered", "dropped", "noSurface", "audioInputs",
      "audioErrors", "pcmBytes", "decodeMs", "slowDecodes", "drawMs", "maxDecodeMs", "blank"};

  private static MediaCodecInfo.CodecProfileLevel level(int profile, int level) {
    MediaCodecInfo.CodecProfileLevel l = new MediaCodecInfo.CodecProfileLevel();
    l.profile = profile;
    l.level = level;
    return l;
  }

  private Decoder decoder;
  private AudioDecoder audio;

  /** Video codecs not yet released, for the `video` control command. */
  private static final Set<Decoder> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Each live video codec's output: the view size it draws at, or the Surface name when no TextureView owns it. */
  static String liveReport() {
    StringBuilder b = new StringBuilder();
    for (Decoder d : LIVE) {
      Surface s = d.surface;
      SurfaceTexture t = VideoSurfaces.textureOf(s);
      int[] view = VideoSurfaces.viewSize(t);
      b.append(" [").append(d.hevc ? "hevc" : "avc").append(d.blank ? " blank" : "").append(" -> ")
          .append(s == null ? "no surface" : t == null ? "untracked " + ReflectionHelpers.getField(s, "mName")
              : view == null ? "view not laid out" : view[0] + "x" + view[1]).append(']');
    }
    return b.toString();
  }

  @Implementation
  @Override
  protected void __constructor__(String name, boolean nameIsType, boolean encoder) {
    super.__constructor__(name, nameIsType, encoder);
    Object codec = ReflectionHelpers.getField(this, "fakeCodec");
    if (codec == AVC || codec == HEVC) {
      decoder = new Decoder(codec == HEVC, this);
      LIVE.add(decoder);
      ReflectionHelpers.setField(this, "fakeCodec", decoder);
    } else if (codec == AAC || codec == MP3) {
      audio = new AudioDecoder(codec == MP3);
      ReflectionHelpers.setField(this, "fakeCodec", audio);
    }
  }

  /**
   * Gives the input buffers the format's max-input-size when it has one (media players set it), else 8 MB, enough for
   * a 4K key frame: a bound from the picture size alone can be too small (an H.264 picture of uncompressed I_PCM
   * macroblocks is bigger than its YUV samples). A fixed 8 MB made every video codec hold 80 MB of zero-filled native
   * memory, freed only when a GC happened to run.
   */
  private void sizeInputs(MediaFormat format) {
    int size = 8 << 20;
    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) && format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) > 0) {
      size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
    }
    ByteBuffer[] inputs = ReflectionHelpers.getField(this, "inputBuffers");
    for (int i = 0; i < inputs.length; i++) {
      if (inputs[i].capacity() != size) inputs[i] = ByteBuffer.allocateDirect(size);
    }
  }

  @Implementation
  protected void native_setSurface(Surface surface) {
    if (decoder != null) decoder.surface = surface;
  }

  @Implementation
  @Override
  protected void releaseOutputBuffer(int index, boolean render) {
    if (decoder != null) decoder.finish(outputBuffer(index), render);
    super.releaseOutputBuffer(index, render);
  }

  @Implementation
  @Override
  protected void releaseOutputBuffer(int index, long renderTimestampNs) {
    if (decoder != null) decoder.finish(outputBuffer(index), true);
    super.releaseOutputBuffer(index, renderTimestampNs);
  }

  /** MediaCodec's flag for an input that is decoded but gives no output (API 34). Robolectric then recycles it. */
  private static final int DECODE_ONLY = 32;

  /**
   * Decodes here rather than in Robolectric's per-buffer process call, so pictures can come out in presentation order.
   * Robolectric pairs each input with one output that keeps the input's timestamp, but with B-frames pictures decode
   * out of order (pts 0, 66, 33, ...) and players drop the ones that seem to go back in time as late.
   */
  @Implementation
  @Override
  protected void native_queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
    if (decoder == null) {
      super.native_queueInputBuffer(index, offset, size, presentationTimeUs, flags);
      return;
    }
    ByteBuffer[] inputs = ReflectionHelpers.getField(this, "inputBuffers");
    ByteBuffer in = index >= 0 && index < inputs.length ? inputs[index].duplicate() : null;
    if (in != null) in.limit(offset + size).position(offset);
    int heldBefore = decoder.held();
    long[] out = decoder.queue(in, presentationTimeUs, flags);
    if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
      endOfStream(index, offset, size, presentationTimeUs, flags);
    } else if (out != null) {
      super.native_queueInputBuffer(index, offset, size, out[0], flags & ~DECODE_ONLY);
    } else if (decoder.held() > heldBefore && reserved.size() < Decoder.MAX_DEPTH && reserve(index)) {
      // Its picture is held back: so is the buffer, to carry a picture out at the end of the stream.
    } else {
      super.native_queueInputBuffer(index, offset, size, presentationTimeUs, flags | DECODE_ONLY);
    }
  }

  /**
   * Input buffers kept by the codec while the pictures they brought wait in the decoder to be put in presentation
   * order. Robolectric gives each input one output in its own buffer, so without them the pictures still held when
   * the stream ends (1-4) had no buffer to leave in and were lost.
   */
  private final ArrayDeque<Integer> reserved = new ArrayDeque<>();

  /**
   * Keeps [index] for the codec, if the app can have another buffer instead. In async mode Robolectric hands out one
   * input buffer at a time (the next when an output is released), so a kept one is replaced from its free queue.
   */
  private boolean reserve(int index) {
    java.util.concurrent.BlockingQueue<Integer> free = ReflectionHelpers.getField(this, "inputBuffersPendingDequeue");
    boolean async = ReflectionHelpers.getField(this, "isAsync");
    Integer instead = null;
    if (async && (instead = free.poll()) == null) return false;
    List<Integer> queuing = ReflectionHelpers.getField(this, "inputBuffersPendingQueuing");
    queuing.remove(Integer.valueOf(index));
    reserved.add(index);
    if (instead != null) {
      ReflectionHelpers.callInstanceMethod(ShadowMediaCodec.class, this, "makeInputBufferAvailable",
          ReflectionHelpers.ClassParameter.from(int.class, instead));
    }
    return true;
  }

  /** The held pictures leave in the reserved buffers, earliest first, and the end-of-stream buffer goes last. */
  private void endOfStream(int index, int offset, int size, long presentationTimeUs, int flags) {
    List<long[]> left = decoder.drain();
    int i = 0;
    for (; i < left.size() - 1 && !reserved.isEmpty(); i++) {
      long[] picture = left.get(i);
      decoder.nextPicture(picture[1]);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      info.set(0, 0, picture[0], 0);
      ReflectionHelpers.callInstanceMethod(ShadowMediaCodec.class, this, "makeOutputBufferAvailable",
          ReflectionHelpers.ClassParameter.from(int.class, reserved.poll()),
          ReflectionHelpers.ClassParameter.from(MediaCodec.BufferInfo.class, info));
    }
    // Any the reserved buffers could not take are lost, as before; the latest rides on the end-of-stream buffer.
    for (; i < left.size() - 1; i++) decoder.dropPicture(left.get(i)[1]);
    long[] last = left.isEmpty() ? null : left.get(left.size() - 1);
    decoder.nextPicture(last == null ? 0 : last[1]);
    super.native_queueInputBuffer(index, offset, size, last == null ? presentationTimeUs : last[0], flags & ~DECODE_ONLY);
    // Any left over go back to Robolectric's free queue (sync: for dequeueInputBuffer; async: to be handed out).
    java.util.concurrent.BlockingQueue<Integer> free = ReflectionHelpers.getField(this, "inputBuffersPendingDequeue");
    while (!reserved.isEmpty()) free.add(reserved.poll());
  }

  @Implementation
  @Override
  protected void native_start() {
    reserved.clear();
    super.native_start();
    if (decoder != null || audio != null) handOutInputs();
  }

  /**
   * In async mode Robolectric's start hands the app one input buffer, and each buffer comes back only when the output
   * it became is released, so a player had one picture in flight: a video player holds each output until its frame
   * is due, then waits a turn of its work loop for the input callback, so ExoPlayer decoded ~40 pictures a second
   * and dropped most of a 60 fps clip as late. A device's codec offers several inputs at once; here every buffer but
   * the {@link Decoder#MAX_DEPTH} that {@link #reserve} may keep for held pictures.
   */
  private void handOutInputs() {
    boolean async = ReflectionHelpers.getField(this, "isAsync");
    if (!async) return;
    java.util.concurrent.BlockingQueue<Integer> free = ReflectionHelpers.getField(this, "inputBuffersPendingDequeue");
    int keep = decoder != null ? Decoder.MAX_DEPTH : 0;
    Integer next;
    while (free.size() > keep && (next = free.poll()) != null) {
      ReflectionHelpers.callInstanceMethod(ShadowMediaCodec.class, this, "makeInputBufferAvailable",
          ReflectionHelpers.ClassParameter.from(int.class, next));
    }
  }

  @Implementation
  @Override
  protected void native_flush() {
    // Robolectric hands every buffer back to the app on flush (sync) or the start that follows it (async).
    reserved.clear();
    if (decoder != null) decoder.dropPictures();
    if (audio != null) audio.reset();
    super.native_flush();
  }

  @Implementation
  protected void native_release() {
    if (decoder != null) {
      decoder.close();
      LIVE.remove(decoder);
    }
    if (audio != null) audio.close();
  }

  private ByteBuffer outputBuffer(int index) {
    ByteBuffer[] buffers = ReflectionHelpers.getField(this, "outputBuffers");
    return index >= 0 && index < buffers.length ? buffers[index] : null;
  }

  /** One codec's VideoToolbox session, the Surface it draws to, and the pictures its output buffers hold. */
  private static final class Decoder implements CodecConfig.Codec {
    private final boolean hevc;
    private final String name;
    private long handle;
    volatile Surface surface;
    private final int[] size = new int[2];
    private ByteBuffer input;
    private ByteBuffer rgba;
    private final Bitmap[] pool = new Bitmap[3];
    private static final boolean AT_VIEW_SIZE = !"false".equals(System.getProperty("apksim.videoAtViewSize"));
    private static final int STEADY_PICTURES = 10;
    private int outW;
    private int outH;
    private int seenW;
    private int seenH;
    private int steady;
    private int next;
    private final Set<Long> pictures = new HashSet<>();

    private boolean drewOne;
    /** --video blank when configured (Features): no session; each input gives an empty output with its timestamp. */
    volatile boolean blank;

    private final ShadowSimMediaCodec codec;

    Decoder(boolean hevc, ShadowSimMediaCodec codec) {
      this.hevc = hevc;
      this.codec = codec;
      name = hevc ? HEVC_DECODER : AVC_DECODER;
    }

    @Override
    public void onConfigured(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      System.err.println("[apksim] MediaCodec " + name + " configured: " + format + (surface == null ? ", no surface" : ""));
      codec.sizeInputs(format);
      this.surface = surface;
      close();
      blank = dev.apksim.Features.video() == dev.apksim.Features.Video.BLANK;
      // A native decoder that reorders leaves nothing to hold back; blank has none and orders as the Mac does.
      if (VideoToolbox.REORDERS) depth = blank ? 2 : 0;
      if (blank) {
        System.err.println("[apksim] MediaCodec " + name + ": video blank, nothing is decoded or drawn");
        // A picture an earlier codec (with video on) left on this surface comes off: the view stays empty.
        SurfaceTexture t = VideoSurfaces.textureOf(surface);
        if (t != null) VideoSurfaces.publish(t, null);
        return;
      }
      handle = VideoToolbox.create(hevc);
      // Parameter sets come in the format (Annex B: csd-0 and csd-1 for H.264, all three in csd-0 for HEVC) rather
      // than in the stream.
      for (String key : new String[] {"csd-0", "csd-1"}) {
        ByteBuffer csd = format.getByteBuffer(key);
        if (csd == null) continue;
        if (VideoToolbox.REORDERS) send(csd.duplicate(), NO_PTS);
        else decode(csd.duplicate());
      }
    }

    /** Decoded pictures not yet handed out, as {pts, picture}, earliest first. */
    private final java.util.PriorityQueue<long[]> held = new java.util.PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
    static final int MAX_DEPTH = 4;
    /** How many pictures are held back before the earliest goes out: grows when one arrives older than one already out. */
    private int depth = 2;
    private long lastOut = Long.MIN_VALUE;
    /** The picture the next process() call puts in its output buffer (0: an empty end-of-stream buffer). */
    private long nextPicture;

    /**
     * Decodes one input. Returns {pts} of the picture its output buffer should carry, or null when it gives none yet
     * (and at end of stream, where {@link #drain} hands out what is held).
     */
    long[] queue(ByteBuffer in, long pts, int flags) {
      if (blank && in != null && in.remaining() > 0) {
        // An empty picture (0) for every input the player will look for an output of, in presentation order as below.
        STATS.incrementAndGet(0);
        if ((flags & (DECODE_ONLY | MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) == 0) {
          STATS.incrementAndGet(13);
          if (pts < lastOut) depth = Math.min(depth + 1, MAX_DEPTH);
          held.add(new long[] {pts, 0});
        }
      } else if (VideoToolbox.REORDERS && handle != 0 && in != null && in.remaining() > 0) {
        STATS.incrementAndGet(0);
        long t0 = System.nanoTime();
        sendAndReceive(in, pts, flags);
        long took = System.nanoTime() - t0;
        STATS.addAndGet(9, took / 1_000_000);
        if (took > 20_000_000) STATS.incrementAndGet(10);
        STATS.accumulateAndGet(12, took / 1_000_000, Math::max);
      } else if (handle != 0 && in != null && in.remaining() > 0) {
        STATS.incrementAndGet(0);
        long t0 = System.nanoTime();
        int r = decode(in);
        long took = System.nanoTime() - t0;
        STATS.addAndGet(9, took / 1_000_000);
        if (took > 20_000_000) STATS.incrementAndGet(10);
        STATS.accumulateAndGet(12, took / 1_000_000, Math::max);
        if (r < 0) STATS.incrementAndGet(2);
        if (r > 0) {
          STATS.incrementAndGet(1);
          long picture = VideoToolbox.nativeTakePicture(handle);
          if ((flags & DECODE_ONLY) != 0 || (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            VideoToolbox.nativeReleasePicture(picture);  // decoded for reference only, as the player asked
          } else {
            if (pts < lastOut) depth = Math.min(depth + 1, MAX_DEPTH);
            held.add(new long[] {pts, picture});
          }
        }
      }
      boolean end = (flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      if (end || (flags & (DECODE_ONLY | MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) != 0 || held.size() <= depth) return null;
      long[] first = held.poll();
      nextPicture = first[1];
      lastOut = Math.max(lastOut, first[0]);
      if (first[1] != 0) {
        synchronized (pictures) {
          pictures.add(first[1]);
        }
      }
      return new long[] {first[0]};
    }

    /** Pictures waiting here, and (libavcodec only) those still inside the decoder, which will come out later. */
    int held() {
      return held.size() + inside;
    }

    /** The pts a parameter set is sent with: none. */
    private static final long NO_PTS = Long.MIN_VALUE;
    /** Linux: inputs sent and not DECODE_ONLY or CODEC_CONFIG, minus the pictures received for them. */
    private int inside;
    /**
     * Linux: each input sent whose picture has not come out yet, by the number it went to libavcodec with in place of
     * its pts (which comes back with its picture): {pts, 1 if DECODE_ONLY}. Two inputs may share a pts (a seek's
     * preroll), so the pts itself cannot say which input a picture came from.
     */
    private final java.util.Map<Long, long[]> sent = new java.util.HashMap<>();
    private long nextInput;
    private final long[] receivedPts = new long[1];

    /** Linux: one input to the decoder, then every picture it has finished, into {@link #held}. */
    private void sendAndReceive(ByteBuffer in, long pts, int flags) {
      boolean config = (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
      boolean only = (flags & DECODE_ONLY) != 0;
      long id = nextInput++;
      int r = send(in, config ? NO_PTS : id);
      if (r < 0) {
        STATS.incrementAndGet(2);
      } else if (!config) {
        sent.put(id, new long[] {pts, only ? 1 : 0});
        if (!only) inside++;
      }
      receiveAll();
    }

    private void receiveAll() {
      long picture;
      while ((picture = VideoToolbox.nativeReceive(handle, receivedPts)) != 0) {
        STATS.incrementAndGet(1);
        long[] input = sent.remove(receivedPts[0]);
        if (input == null || input[1] != 0) {
          // Decoded for reference only, as the player asked (or from no input of ours: nothing to show it for).
          VideoToolbox.nativeReleasePicture(picture);
        } else {
          if (inside > 0) inside--;
          held.add(new long[] {input[0], picture});
        }
      }
    }

    /** Everything held, as {pts, picture} earliest first, each now owned by the output buffer it is given to. */
    List<long[]> drain() {
      if (VideoToolbox.REORDERS && handle != 0) {
        // Everything the decoder still has comes out now, in presentation order.
        VideoToolbox.nativeSend(handle, null, 0, 0, NO_PTS);
        receiveAll();
        inside = 0;
        sent.clear();
      }
      List<long[]> all = new ArrayList<>();
      while (!held.isEmpty()) all.add(held.poll());
      synchronized (pictures) {
        for (long[] p : all) if (p[1] != 0) pictures.add(p[1]);
      }
      return all;
    }

    /** The picture the next process() call puts in its output buffer (0: an empty buffer). */
    void nextPicture(long picture) {
      nextPicture = picture;
    }

    void dropPicture(long picture) {
      synchronized (pictures) {
        pictures.remove(picture);
      }
      VideoToolbox.nativeReleasePicture(picture);
    }

    @Override
    public void process(ByteBuffer in, ByteBuffer out) {
      out.clear();
      if (nextPicture != 0) out.putLong(nextPicture);
      nextPicture = 0;
    }

    private int decode(ByteBuffer data) {
      int n = stage(data);
      return VideoToolbox.nativeDecode(handle, input, 0, n, size);
    }

    /** Linux: [data] to libavcodec with [pts]. Negative: error. */
    private int send(ByteBuffer data, long pts) {
      int n = stage(data);
      if (n == 0) return 0;
      return VideoToolbox.nativeSend(handle, input, 0, n, pts);
    }

    /** Copies [data] into the direct buffer the natives read; returns its length. */
    private int stage(ByteBuffer data) {
      int n = data.remaining();
      if (input == null || input.capacity() < n) input = ByteBuffer.allocateDirect(Math.max(n, 1 << 20));
      input.clear();
      input.put(data).flip();
      return n;
    }

    /** An output buffer goes back to the codec: draw its picture if asked, then let it go either way. */
    void finish(ByteBuffer buffer, boolean render) {
      if (buffer == null || buffer.limit() < 8) return;
      long picture = buffer.getLong(0);
      if (picture == 0) return;
      synchronized (pictures) {
        if (!pictures.remove(picture)) return;
      }
      STATS.incrementAndGet(render ? 3 : 4);
      try {
        long t0 = System.nanoTime();
        if (render) draw(picture);
        STATS.addAndGet(11, (System.nanoTime() - t0) / 1_000_000);
      } finally {
        VideoToolbox.nativeReleasePicture(picture);
      }
    }

    private void draw(long picture) {
      Surface s = surface;
      SurfaceTexture target = VideoSurfaces.textureOf(s);
      if (target == null) {
        STATS.incrementAndGet(5);
        if (!drewOne) System.err.println("[apksim] MediaCodec output surface " + s + " has no TextureView");
        drewOne = true;
        return;
      }
      int w = VideoToolbox.nativePictureWidth(picture);
      int h = VideoToolbox.nativePictureHeight(picture);
      int[] view = VideoSurfaces.viewSize(target);
      if (view != null && AT_VIEW_SIZE) {
        // Converted at the view's size, which the view draws 1:1: scaling on the raster thread every frame (a 4K
        // picture down, or a small one up into a large view) is far slower than in VideoToolbox's transfer here.
        // While the view resizes (a window drag) the size in use is kept, the view stretching it, until the new one
        // has held for STEADY_PICTURES: each new size would otherwise replace the pooled Bitmaps (and grow the
        // buffer) on every picture.
        if (view[0] != seenW || view[1] != seenH) {
          seenW = view[0];
          seenH = view[1];
          steady = 0;
        } else if (steady < STEADY_PICTURES) {
          steady++;
        }
        if (outW <= 0 || steady >= STEADY_PICTURES) {
          outW = seenW;
          outH = seenH;
        }
        w = outW;
        h = outH;
      } else if (view != null) {
        w = Math.min(w, view[0]);
        h = Math.min(h, view[1]);
      }
      if (w <= 0 || h <= 0) return;
      if (rgba == null || rgba.capacity() < w * h * 4) rgba = ByteBuffer.allocateDirect(w * h * 4);
      rgba.clear();
      if (!VideoToolbox.nativePictureToRgba(picture, w, h, rgba, VideoToolbox.BITMAP_BGRA)) return;
      rgba.limit(w * h * 4);
      Bitmap b = pool[next];
      if (b == null || b.getWidth() != w || b.getHeight() != h) pool[next] = b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      next = (next + 1) % pool.length;
      b.copyPixelsFromBuffer(rgba);
      VideoSurfaces.publish(target, b);
      if (!drewOne) {
        drewOne = true;
        System.err.println("[apksim] MediaCodec " + name + " drew its first picture, " + w + "x" + h);
      }
    }

    void dropPictures() {
      for (long[] h : held) VideoToolbox.nativeReleasePicture(h[1]);
      held.clear();
      nextPicture = 0;
      lastOut = Long.MIN_VALUE;
      if (VideoToolbox.REORDERS) {
        // A seek: what is inside the decoder goes, and so do the DECODE_ONLY inputs waiting for their pictures.
        if (handle != 0) VideoToolbox.nativeReset(handle);
        sent.clear();
        inside = 0;
      }
      synchronized (pictures) {
        for (long p : pictures) VideoToolbox.nativeReleasePicture(p);
        pictures.clear();
      }
    }

    void close() {
      dropPictures();
      VideoToolbox.destroy(handle);
      handle = 0;
    }
  }

  /** One codec's AudioToolbox converter: an access unit in, 16-bit interleaved PCM out. */
  private static final class AudioDecoder implements CodecConfig.Codec {
    private final boolean mp3;
    private long handle;
    private ByteBuffer input;
    private final ByteBuffer pcm = ByteBuffer.allocateDirect(1 << 16);

    AudioDecoder(boolean mp3) {
      this.mp3 = mp3;
    }

    @Override
    public void onConfigured(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      close();
      int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      ByteBuffer csd = format.getByteBuffer("csd-0");
      byte[] config = null;
      if (csd != null) {
        config = new byte[csd.remaining()];
        csd.duplicate().get(config);
      }
      handle = SimAudio.nativeDecoderCreate(mp3 ? SimAudio.MP3 : SimAudio.AAC, rate, channels, config);
      System.err.println("[apksim] MediaCodec " + (mp3 ? MP3_DECODER : AAC_DECODER) + " configured: " + rate + " Hz, "
          + channels + " ch" + (handle == 0 ? ", but AudioToolbox refused it" : ""));
    }

    @Override
    public void process(ByteBuffer in, ByteBuffer out) {
      out.clear();
      int n = in.remaining();
      if (handle == 0 || n == 0) return;
      STATS.incrementAndGet(6);
      if (input == null || input.capacity() < n) input = ByteBuffer.allocateDirect(Math.max(n, 1 << 14));
      input.clear();
      input.put(in).flip();
      int bytes = SimAudio.nativeDecode(handle, input, 0, n, pcm, Math.min(pcm.capacity(), out.capacity()));
      if (bytes < 0) {
        STATS.incrementAndGet(7);
        return;
      }
      STATS.addAndGet(8, bytes);
      out.put(pcm.duplicate().position(0).limit(bytes));
    }

    void reset() {
      if (handle != 0) SimAudio.nativeDecoderReset(handle);
    }

    void close() {
      if (handle != 0) SimAudio.nativeDecoderRelease(handle);
      handle = 0;
    }
  }
}
