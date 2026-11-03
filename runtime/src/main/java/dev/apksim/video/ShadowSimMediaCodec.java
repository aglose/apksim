package dev.apksim.video;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.HashSet;
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

  /** Lists the decoders with MediaCodecList and Robolectric's codec table. Run once at startup. */
  public static void install() {
    if (VideoToolbox.available()) {
      video(AVC_DECODER, MediaFormat.MIMETYPE_VIDEO_AVC, AVC, MediaCodecInfo.CodecProfileLevel.AVCLevel52,
          MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
          MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
          MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh);
      video(HEVC_DECODER, MediaFormat.MIMETYPE_VIDEO_HEVC, HEVC, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52,
          MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
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
    // Input big enough for a 4K key frame; output holds only a picture reference. Robolectric looks a codec up by
    // the string it was created with: the type for createDecoderByType, the name for createByCodecName, which is
    // what media players use.
    CodecConfig config = new CodecConfig(8 << 20, 8, placeholder);
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
  static final java.util.concurrent.atomic.AtomicLongArray STATS = new java.util.concurrent.atomic.AtomicLongArray(9);

  static final String[] STAT_NAMES = {"inputs", "pictures", "decodeErrors", "rendered", "dropped", "noSurface", "audioInputs",
      "audioErrors", "pcmBytes"};

  private static MediaCodecInfo.CodecProfileLevel level(int profile, int level) {
    MediaCodecInfo.CodecProfileLevel l = new MediaCodecInfo.CodecProfileLevel();
    l.profile = profile;
    l.level = level;
    return l;
  }

  private Decoder decoder;
  private AudioDecoder audio;

  @Implementation
  @Override
  protected void __constructor__(String name, boolean nameIsType, boolean encoder) {
    super.__constructor__(name, nameIsType, encoder);
    Object codec = ReflectionHelpers.getField(this, "fakeCodec");
    if (codec == AVC || codec == HEVC) {
      decoder = new Decoder(codec == HEVC);
      ReflectionHelpers.setField(this, "fakeCodec", decoder);
    } else if (codec == AAC || codec == MP3) {
      audio = new AudioDecoder(codec == MP3);
      ReflectionHelpers.setField(this, "fakeCodec", audio);
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

  @Implementation
  @Override
  protected void native_flush() {
    if (decoder != null) decoder.dropPictures();
    if (audio != null) audio.reset();
    super.native_flush();
  }

  @Implementation
  protected void native_release() {
    if (decoder != null) decoder.close();
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
    private int next;
    private final Set<Long> pictures = new HashSet<>();

    private boolean drewOne;

    Decoder(boolean hevc) {
      this.hevc = hevc;
      name = hevc ? HEVC_DECODER : AVC_DECODER;
    }

    @Override
    public void onConfigured(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      System.err.println("[apksim] MediaCodec " + name + " configured: " + format + (surface == null ? ", no surface" : ""));
      this.surface = surface;
      close();
      handle = VideoToolbox.create(hevc);
      // Parameter sets come in the format (Annex B: csd-0 and csd-1 for H.264, all three in csd-0 for HEVC) rather
      // than in the stream.
      for (String key : new String[] {"csd-0", "csd-1"}) {
        ByteBuffer csd = format.getByteBuffer(key);
        if (csd != null) decode(csd.duplicate());
      }
    }

    @Override
    public void process(ByteBuffer in, ByteBuffer out) {
      out.clear();
      long picture = 0;
      int r = 0;
      if (handle != 0 && in.remaining() > 0) {
        STATS.incrementAndGet(0);
        r = decode(in);
        if (r < 0) STATS.incrementAndGet(2);
      }
      if (r > 0) {
        STATS.incrementAndGet(1);
        picture = VideoToolbox.nativeTakePicture(handle);
        synchronized (pictures) {
          pictures.add(picture);
        }
      }
      out.putLong(picture);
    }

    private int decode(ByteBuffer data) {
      int n = data.remaining();
      if (input == null || input.capacity() < n) input = ByteBuffer.allocateDirect(Math.max(n, 1 << 20));
      input.clear();
      input.put(data).flip();
      return VideoToolbox.nativeDecode(handle, input, 0, n, size);
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
        if (render) draw(picture);
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
      if (view != null) {
        // Drawn at the view's size: scaling a 4K picture down on the raster thread every frame is far slower.
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
