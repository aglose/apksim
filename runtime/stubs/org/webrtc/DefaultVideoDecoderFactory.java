package org.webrtc;
public class DefaultVideoDecoderFactory implements VideoDecoderFactory {
  public DefaultVideoDecoderFactory(EglBase.Context eglContext) {}
  public DefaultVideoDecoderFactory(VideoDecoderFactory hardwareVideoDecoderFactory) {}
  public VideoDecoder createDecoder(VideoCodecInfo info) { throw new AssertionError(); }
  public VideoCodecInfo[] getSupportedCodecs() { throw new AssertionError(); }
}
