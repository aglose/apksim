package org.webrtc;
public interface VideoDecoderFactory {
  VideoDecoder createDecoder(VideoCodecInfo info);
  VideoCodecInfo[] getSupportedCodecs();
}
