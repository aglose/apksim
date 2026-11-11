package dev.apksim.video;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.robolectric.util.ReflectionHelpers;

/**
 * Where decoded pictures meet the views showing them. There is no GL here, so a TextureView's SurfaceTexture
 * is only a name ({@link ShadowSimTextureView} makes it, and {@link ShadowSimSurfaceView} one for a SurfaceView's
 * holder), and a renderer drawing "into" it publishes a Bitmap under that name instead; the view draws the latest one.
 */
public final class VideoSurfaces {
  private VideoSurfaces() {}

  private static final class Entry {
    final View view;
    volatile Bitmap picture;

    Entry(View view) {
      this.view = view;
    }
  }

  private static final Map<SurfaceTexture, Entry> ENTRIES = new ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong published = new java.util.concurrent.atomic.AtomicLong();
  /** libwebrtc frames dropped for --video blank (here: SimEglRenderer references org.webrtc, which not every app has). */
  static final java.util.concurrent.atomic.AtomicLong webrtcBlank = new java.util.concurrent.atomic.AtomicLong();

  /** For the `video` control command. */
  public static String report() {
    StringBuilder b = new StringBuilder("decoded=" + VideoToolbox.decoded.get() + " decodeErrors=" + VideoToolbox.failed.get()
        + " drawn=" + published.get() + " blank=" + webrtcBlank.get() + " surfaces=" + ENTRIES.size());
    b.append(" | mediacodec");
    for (int i = 0; i < ShadowSimMediaCodec.STAT_NAMES.length; i++) {
      b.append(' ').append(ShadowSimMediaCodec.STAT_NAMES[i]).append('=').append(ShadowSimMediaCodec.STATS.get(i));
    }
    b.append(" | audiotrack opened=").append(ShadowSimAudioTrack.STATS.get(0)).append(" frames=").append(ShadowSimAudioTrack.STATS.get(1))
        .append(" underrunsOfClosed=").append(ShadowSimAudioTrack.STATS.get(2)).append(SimAudio.available() ? "" : " (no sound here)");
    b.append(" | listed").append(ShadowSimMediaCodec.listedReport());
    b.append(" | codecs").append(ShadowSimMediaCodec.liveReport()).append(" | views");
    for (Entry e : ENTRIES.values()) {
      Bitmap p = e.picture;
      b.append(" [").append(e.view.getWidth()).append('x').append(e.view.getHeight())
          .append(p == null ? " empty" : " " + p.getWidth() + "x" + p.getHeight()).append(']');
    }
    return b.toString();
  }

  static void attach(SurfaceTexture surface, View view) {
    ENTRIES.put(surface, new Entry(view));
  }

  static void detach(SurfaceTexture surface) {
    ENTRIES.remove(surface);
  }

  static Bitmap picture(SurfaceTexture surface) {
    Entry e = surface == null ? null : ENTRIES.get(surface);
    return e == null ? null : e.picture;
  }

  /**
   * The SurfaceTexture a Surface was made from. Android's Surface(SurfaceTexture) names the surface after it, which is
   * all that survives here (the native half is a stand-in).
   */
  public static SurfaceTexture textureOf(android.view.Surface surface) {
    if (surface == null) return null;
    String name = ReflectionHelpers.getField(surface, "mName");
    if (name == null) return null;
    for (SurfaceTexture t : ENTRIES.keySet()) {
      if (name.equals(t.toString())) return t;
    }
    return null;
  }

  /** The view showing [surface] (a TextureView or a SurfaceView), or null. */
  public static View viewOf(SurfaceTexture surface) {
    Entry e = surface == null ? null : ENTRIES.get(surface);
    return e == null ? null : e.view;
  }

  /** The size the view showing [surface] draws at, or null. */
  static int[] viewSize(SurfaceTexture surface) {
    Entry e = surface == null ? null : ENTRIES.get(surface);
    return e == null || e.view.getWidth() <= 0 ? null : new int[] {e.view.getWidth(), e.view.getHeight()};
  }

  /** Shows [picture] (null clears) and, as SurfaceTexture would, tells the view's listener on the main thread. */
  public static void publish(SurfaceTexture surface, Bitmap picture) {
    Entry e = ENTRIES.get(surface);
    if (e == null) return;
    if (picture != null) published.incrementAndGet();
    e.picture = picture;
    e.view.post(() -> {
      if (ENTRIES.get(surface) != e) return;
      if (e.view instanceof TextureView) {
        TextureView.SurfaceTextureListener l = ReflectionHelpers.getField(e.view, "mListener");
        if (l != null) l.onSurfaceTextureUpdated(surface);
      }
      e.view.invalidate();
    });
  }
}
