package org.webrtc;
import java.nio.ByteBuffer;
public class JavaI420Buffer implements VideoFrame.I420Buffer {
  public static JavaI420Buffer allocate(int width, int height) { throw new AssertionError(); }
  public static JavaI420Buffer wrap(int width, int height, ByteBuffer dataY, int strideY, ByteBuffer dataU, int strideU,
      ByteBuffer dataV, int strideV, Runnable releaseCallback) { throw new AssertionError(); }
  public int getWidth() { throw new AssertionError(); }
  public int getHeight() { throw new AssertionError(); }
  public VideoFrame.I420Buffer toI420() { throw new AssertionError(); }
  public VideoFrame.Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) { throw new AssertionError(); }
  public ByteBuffer getDataY() { throw new AssertionError(); }
  public ByteBuffer getDataU() { throw new AssertionError(); }
  public ByteBuffer getDataV() { throw new AssertionError(); }
  public int getStrideY() { throw new AssertionError(); }
  public int getStrideU() { throw new AssertionError(); }
  public int getStrideV() { throw new AssertionError(); }
  public void retain() {}
  public void release() {}
}
