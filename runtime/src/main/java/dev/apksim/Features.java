package dev.apksim;

/**
 * Per-instance switches for what an instance spends on things a journey may not need: the frame rate (--max-fps),
 * video (--video on|blank|off) and sound (--audio on|mute|off). Read from the launch's properties on first use, and
 * again by {@link #load} when a standby takes a launch (its warm-up read the standby's own), before the app's
 * Application exists. The {@code maxfps} and {@code video} commands change the first two
 * while the instance runs.
 *
 * <ul>
 *   <li>max fps: at most this many frames a second (1-120) instead of 120 Hz or the display's vsync, the clock still
 *       following real time (Host.applyFrameDelay). Animations run at the same speed, in fewer frames; an animating screen costs about a core at 120 fps headless.
 *   <li>video blank: MediaCodec's H.264 and HEVC decoders stay listed and a player plays at its pace, but nothing is
 *       decoded or drawn (each input gives an empty output with its timestamp), and libwebrtc's frames are dropped
 *       before conversion and drawing. A codec takes the mode it is configured in. video off: the decoders are not
 *       listed at all, as on a device without them; launch only, since players cache the codec list.
 *   <li>audio mute: decoded, timed as played, silent. audio off: no AAC/MP3 decoders, Robolectric's AudioTrack
 *       (launch only, as video off).
 * </ul>
 */
public final class Features {
  private Features() {}

  public enum Video { ON, BLANK, OFF }

  public enum Audio { ON, MUTE, OFF }

  private static volatile boolean loaded;
  private static volatile int maxFps = 120;
  private static volatile Video video = Video.ON;
  private static volatile Audio audio = Audio.ON;

  /** Reads the launch options; throws on a bad value, so a mistyped launch fails rather than runs with defaults. */
  static synchronized void load() {
    maxFps = parseFps(System.getProperty("apksim.maxFps", "120"));
    video = parse(Video.class, System.getProperty("apksim.video", "on"));
    String a = System.getProperty("apksim.audio", "on");
    // -Dapksim.audio=false and -Dapksim.mute=true, from before these options.
    if (a.equals("false")) a = "off";
    if (a.equals("true")) a = "on";
    audio = parse(Audio.class, a);
    if (audio == Audio.ON && Boolean.getBoolean("apksim.mute")) audio = Audio.MUTE;
    loaded = true;
  }

  private static void ensureLoaded() {
    if (!loaded) load();
  }

  public static int maxFps() {
    ensureLoaded();
    return maxFps;
  }

  public static Video video() {
    ensureLoaded();
    return video;
  }

  public static Audio audio() {
    ensureLoaded();
    return audio;
  }

  static int parseFps(String v) {
    int fps;
    try {
      fps = Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("max fps: " + v + " (a whole number, 1-120)");
    }
    if (fps < 1 || fps > 120) throw new IllegalArgumentException("max fps: " + v + " (1-120)");
    return fps;
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String v) {
    try {
      return Enum.valueOf(type, v.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      StringBuilder names = new StringBuilder();
      for (E c : type.getEnumConstants()) names.append(names.length() == 0 ? "" : "|").append(c.name().toLowerCase(java.util.Locale.ROOT));
      throw new IllegalArgumentException(type.getSimpleName().toLowerCase(java.util.Locale.ROOT) + ": " + v + " (" + names + ")");
    }
  }

  /** {@code maxfps [N]}: reports or sets the frame rate cap. */
  static String maxFpsCommand(String[] a) {
    ensureLoaded();
    if (a.length > 1) maxFps = parseFps(a[1]);
    return "maxfps " + maxFps;
  }

  /** {@code video on|blank}: the mode codecs configured from now on (and libwebrtc's next frames) take. */
  static String videoCommand(String mode) {
    ensureLoaded();
    Video v = parse(Video.class, mode);
    if (v == Video.OFF || video == Video.OFF) {
      return "error: video off is a launch option (--video off): players keep the codec list they read";
    }
    video = v;
    return "video " + v.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** One line for `stats`-style reports. */
  static String describe() {
    ensureLoaded();
    return "maxfps=" + maxFps + " video=" + video.name().toLowerCase(java.util.Locale.ROOT) + " audio="
        + audio.name().toLowerCase(java.util.Locale.ROOT);
  }
}
