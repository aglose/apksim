package dev.apksim.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.EglBase;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoDecoderFactory;

/**
 * libwebrtc's decoder factory, plus H.264 on VideoToolbox. On a device H.264 comes from MediaCodec, and
 * libwebrtc has no software decoder for it, so here an offer would list only VP8, VP9 and AV1, and a
 * server whose cameras send H.264 had nothing to answer with. Apps get this class wherever they create
 * a DefaultVideoDecoderFactory (see MethodTranslator).
 */
public class SimVideoDecoderFactory extends DefaultVideoDecoderFactory {
  public SimVideoDecoderFactory(EglBase.Context eglContext) {
    super(eglContext);
  }

  public SimVideoDecoderFactory(VideoDecoderFactory hardwareVideoDecoderFactory) {
    super(hardwareVideoDecoderFactory);
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    List<VideoCodecInfo> codecs = new ArrayList<>();
    if (VideoToolbox.available()) {
      // The two profiles Android's hardware decoders announce, first as they are there.
      codecs.add(h264("640c1f"));
      codecs.add(h264("42e01f"));
    }
    for (VideoCodecInfo c : super.getSupportedCodecs()) {
      if (!"H264".equalsIgnoreCase(c.name)) codecs.add(c);
    }
    return codecs.toArray(new VideoCodecInfo[0]);
  }

  @Override
  public VideoDecoder createDecoder(VideoCodecInfo info) {
    if ("H264".equalsIgnoreCase(info.name) && VideoToolbox.available()) return new VtH264Decoder();
    return super.createDecoder(info);
  }

  private static VideoCodecInfo h264(String profileLevelId) {
    return new VideoCodecInfo("H264", Map.of(
        "level-asymmetry-allowed", "1",
        "packetization-mode", "1",
        "profile-level-id", profileLevelId), List.of());
  }
}
