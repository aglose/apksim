package org.webrtc;
import java.nio.ByteBuffer;
public class VideoFrame implements RefCounted {
  public interface Buffer extends RefCounted {
    int getWidth();
    int getHeight();
    I420Buffer toI420();
    Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight, int scaleWidth, int scaleHeight);
  }
  public interface I420Buffer extends Buffer {
    ByteBuffer getDataY();
    ByteBuffer getDataU();
    ByteBuffer getDataV();
    int getStrideY();
    int getStrideU();
    int getStrideV();
  }
  public VideoFrame(Buffer buffer, int rotation, long timestampNs) { throw new AssertionError(); }
  public Buffer getBuffer() { throw new AssertionError(); }
  public int getRotation() { throw new AssertionError(); }
  public long getTimestampNs() { throw new AssertionError(); }
  public void retain() {}
  public void release() {}
}
