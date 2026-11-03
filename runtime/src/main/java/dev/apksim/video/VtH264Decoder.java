package dev.apksim.video;

import java.nio.ByteBuffer;
import org.webrtc.EncodedImage;
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoCodecStatus;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoFrame;

/** libwebrtc's H.264 decoder here: {@link VideoToolbox}, handing libwebrtc I420 frames as MediaCodec would. */
public final class VtH264Decoder implements VideoDecoder {
  private long handle;
  private Callback callback;
  private final int[] size = new int[2];
  private ByteBuffer input;

  @Override
  public VideoCodecStatus initDecode(Settings settings, Callback decodeCallback) {
    callback = decodeCallback;
    handle = VideoToolbox.create();
    return handle != 0 ? VideoCodecStatus.OK : VideoCodecStatus.ERROR;
  }

  @Override
  public VideoCodecStatus release() {
    VideoToolbox.destroy(handle);
    handle = 0;
    return VideoCodecStatus.OK;
  }

  @Override
  public VideoCodecStatus decode(EncodedImage frame, DecodeInfo info) {
    if (handle == 0) return VideoCodecStatus.UNINITIALIZED;
    ByteBuffer data = frame.buffer;
    int length = data.remaining();
    if (!data.isDirect()) {
      // The native side reads the bytes in place, which needs a direct buffer.
      if (input == null || input.capacity() < length) input = ByteBuffer.allocateDirect(Math.max(length, 1 << 16));
      input.clear();
      input.put(data.duplicate()).flip();
      data = input;
    }
    int r = VideoToolbox.nativeDecode(handle, data, data.position(), length, size);
    if (r < 0) {
      VideoToolbox.failed.incrementAndGet();
      return VideoCodecStatus.ERROR;  // libwebrtc then asks the sender for a key frame.
    }
    if (r == 0) return VideoCodecStatus.OK;
    JavaI420Buffer out = JavaI420Buffer.allocate(size[0], size[1]);
    VideoToolbox.nativeCopyI420(handle, out.getDataY(), out.getStrideY(), out.getDataU(), out.getStrideU(), out.getDataV(), out.getStrideV());
    VideoFrame decoded = new VideoFrame(out, frame.rotation, frame.captureTimeNs);
    VideoToolbox.decoded.incrementAndGet();
    callback.onDecodedFrame(decoded, null, null);
    decoded.release();
    return VideoCodecStatus.OK;
  }

  @Override
  public String getImplementationName() {
    return "apksim VideoToolbox";
  }
}
