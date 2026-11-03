package org.webrtc;
import java.nio.ByteBuffer;
public class EncodedImage implements RefCounted {
  public enum FrameType { EmptyFrame, VideoFrameKey, VideoFrameDelta }
  // Assigned in a constructor, never initialised inline: a constant here would be compiled into callers.
  public final ByteBuffer buffer;
  public final int encodedWidth;
  public final int encodedHeight;
  public final long captureTimeNs;
  public final FrameType frameType;
  public final int rotation;
  private EncodedImage() { throw new AssertionError(); }
  public void retain() {}
  public void release() {}
}
