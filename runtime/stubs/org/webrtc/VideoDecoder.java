package org.webrtc;
public interface VideoDecoder {
  // Fields are assigned in constructors, never initialised inline: a constant would be compiled into callers.
  class Settings {
    public final int numberOfCores;
    public final int width;
    public final int height;
    private Settings() { throw new AssertionError(); }
  }
  class DecodeInfo {
    public final boolean isMissingFrames;
    public final long renderTimeMs;
    private DecodeInfo() { throw new AssertionError(); }
  }
  interface Callback { void onDecodedFrame(VideoFrame frame, Integer decodeTimeMs, Integer qp); }
  VideoCodecStatus initDecode(Settings settings, Callback decodeCallback);
  VideoCodecStatus release();
  VideoCodecStatus decode(EncodedImage frame, DecodeInfo info);
  String getImplementationName();
}
