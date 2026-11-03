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
 * An H.264 MediaCodec decoder on VideoToolbox, rendering to the Surface it was configured with, as media players
 * (Media3/ExoPlayer) use one. Robolectric's MediaCodec does the buffer bookkeeping (both modes) and calls a
 * process function per buffer; each codec here gets its own decoder, and an output buffer carries a reference to
 * its decoded picture, drawn into the Surface's TextureView when the app releases the buffer with render=true.
 */
@Implements(MediaCodec.class)
public class ShadowSimMediaCodec extends ShadowMediaCodec {
  static final String AVC_DECODER = "apksim.avc.decoder";

  /** Stands for the decoder in Robolectric's table until a codec is made and gets its own. */
  private static final CodecConfig.Codec PLACEHOLDER = (in, out) -> {
    throw new IllegalStateException("not configured");
  };

  /** Lists the decoder with MediaCodecList and Robolectric's codec table. Run once at startup. */
  public static void install() {
    if (!VideoToolbox.available()) return;
    MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 8192, 4320);
    MediaCodecInfo.CodecProfileLevel[] levels = {
        level(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline),
        level(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline),
        level(MediaCodecInfo.CodecProfileLevel.AVCProfileMain),
        level(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh),
        level(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh)};
    ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
        .setName(AVC_DECODER)
        .setIsEncoder(false)
        .setIsHardwareAccelerated(true)
        .setIsVendor(true)
        .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(false)
            .setProfileLevels(levels)
            .setColorFormats(new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
            .build())
        .build());
    // Input big enough for a 4K key frame; output holds only a picture reference. Robolectric looks a codec up by
    // the string it was created with: the type for createDecoderByType, the name for createByCodecName, which is
    // what media players use.
    CodecConfig config = new CodecConfig(8 << 20, 8, PLACEHOLDER);
    ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, config);
    ShadowMediaCodec.addDecoder(AVC_DECODER, config);
  }

  /** Totals across codecs, for the `video` control command. */
  static final java.util.concurrent.atomic.AtomicLongArray STATS = new java.util.concurrent.atomic.AtomicLongArray(6);

  static final String[] STAT_NAMES = {"inputs", "pictures", "decodeErrors", "rendered", "dropped", "noSurface"};

  private static MediaCodecInfo.CodecProfileLevel level(int profile) {
    MediaCodecInfo.CodecProfileLevel l = new MediaCodecInfo.CodecProfileLevel();
    l.profile = profile;
    l.level = MediaCodecInfo.CodecProfileLevel.AVCLevel52;
    return l;
  }

  private Decoder decoder;

  @Implementation
  @Override
  protected void __constructor__(String name, boolean nameIsType, boolean encoder) {
    super.__constructor__(name, nameIsType, encoder);
    if (ReflectionHelpers.getField(this, "fakeCodec") == PLACEHOLDER) {
      decoder = new Decoder();
      ReflectionHelpers.setField(this, "fakeCodec", decoder);
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
    super.native_flush();
  }

  @Implementation
  protected void native_release() {
    if (decoder != null) decoder.close();
  }

  private ByteBuffer outputBuffer(int index) {
    ByteBuffer[] buffers = ReflectionHelpers.getField(this, "outputBuffers");
    return index >= 0 && index < buffers.length ? buffers[index] : null;
  }

  /** One codec's VideoToolbox session, the Surface it draws to, and the pictures its output buffers hold. */
  private static final class Decoder implements CodecConfig.Codec {
    private long handle;
    volatile Surface surface;
    private final int[] size = new int[2];
    private ByteBuffer input;
    private ByteBuffer rgba;
    private final Bitmap[] pool = new Bitmap[3];
    private int next;
    private final Set<Long> pictures = new HashSet<>();

    private boolean drewOne;

    @Override
    public void onConfigured(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      System.err.println("[apksim] MediaCodec " + AVC_DECODER + " configured: " + format + (surface == null ? ", no surface" : ""));
      this.surface = surface;
      close();
      handle = VideoToolbox.create();
      // Parameter sets come in the format (Annex B, as csd-0 and csd-1) rather than in the stream.
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
        System.err.println("[apksim] MediaCodec " + AVC_DECODER + " drew its first picture, " + w + "x" + h);
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
}
