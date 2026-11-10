package dev.apksim.camera;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNativeImageReader;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * An ImageReader the fake camera can fill ({@link FakeCamera}). Robolectric's native ImageReader takes pictures only
 * from a Canvas or HWUI, in RGBA; a camera fills YUV_420_888 and JPEG readers. A reader the camera has written to hands
 * out the camera's Images from acquireNextImage/acquireLatestImage, under the reader's maxImages as a device's buffer
 * queue: while every buffer is queued or acquired, the camera's frame for it is dropped. Every other reader (HWUI's
 * output, an app's own) is Robolectric's, unchanged.
 */
@Implements(value = ImageReader.class, minSdk = 28, isInAndroidSdk = false, callNativeMethodsByDefault = true)
public class ShadowSimImageReader extends ShadowNativeImageReader {
  @RealObject private ImageReader reader;

  private final Object lock = new Object();
  private boolean fed;
  private boolean closed;
  private final ArrayDeque<FakeImage> queued = new ArrayDeque<>();
  private int acquired;
  /** Images handed out and not closed: closing the reader invalidates them, as on a device. */
  private final java.util.Set<FakeImage> held = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private final ArrayDeque<ByteBuffer> freeBuffers = new ArrayDeque<>();

  // ImageFormat's and PixelFormat's values (the instrumented framework's constants are not compile-time constants).
  static final int YUV_420_888 = 0x23;
  static final int JPEG = 0x100;
  static final int PRIVATE = 0x22;
  static final int RGBA_8888 = 1;

  private static ShadowSimImageReader of(ImageReader r) {
    Object s = Shadow.extract(r);
    return s instanceof ShadowSimImageReader sr ? sr : null;
  }

  static String formatName(int format) {
    return switch (format) {
      case YUV_420_888 -> "YUV_420_888";
      case JPEG -> "JPEG";
      case PRIVATE -> "PRIVATE";
      case RGBA_8888 -> "RGBA_8888";
      default -> "format 0x" + Integer.toHexString(format);
    };
  }

  /** Whether the camera's next frame has a free buffer in this reader (and a format the camera writes). */
  static boolean wants(ImageReader r) {
    ShadowSimImageReader s = of(r);
    if (s == null) return false;
    int f = r.getImageFormat();
    if (f != YUV_420_888 && f != JPEG && f != PRIVATE && f != RGBA_8888) {
      return false;
    }
    synchronized (s.lock) {
      return !s.closed && s.queued.size() + s.acquired < r.getMaxImages();
    }
  }

  /** Queues the camera's frame (sensor-oriented ARGB, the reader's size) as an Image and tells the reader's listener. */
  static boolean offer(ImageReader r, Bitmap raw, long timestamp, int jpegOrientation) {
    ShadowSimImageReader s = of(r);
    if (s == null) return false;
    int w = r.getWidth(), h = r.getHeight(), format = r.getImageFormat();
    ByteBuffer buffer;
    synchronized (s.lock) {
      if (s.closed || s.queued.size() + s.acquired >= r.getMaxImages()) return false;
      buffer = s.freeBuffers.poll();
    }
    FakeImage image = new FakeImage(s, format, w, h, timestamp);
    switch (format) {
      case YUV_420_888 -> {
        buffer = sized(buffer, w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2));
        yuv(raw, w, h, buffer);
        image.yuvPlanes(buffer);
      }
      case RGBA_8888 -> {
        buffer = sized(buffer, w * h * 4);
        rgba(raw, w, h, buffer);
        image.planes = new Image.Plane[] {new FakePlane(buffer, w * 4, 4)};
        image.buffer = buffer;
      }
      case JPEG -> {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        raw.compress(Bitmap.CompressFormat.JPEG, 90, out);
        byte[] bytes = withOrientation(out.toByteArray(), jpegOrientation);
        ByteBuffer b = ByteBuffer.allocateDirect(bytes.length);
        b.put(bytes).flip();
        image.planes = new Image.Plane[] {new FakePlane(b, 0, 0)};
      }
      default -> image.planes = new Image.Plane[0];  // PRIVATE: opaque buffers, no planes, as on a device
    }
    ImageReader.OnImageAvailableListener listener;
    Executor executor;
    Object listenerLock = ReflectionHelpers.getField(r, "mListenerLock");
    synchronized (listenerLock) {
      listener = ReflectionHelpers.getField(r, "mListener");
      executor = ReflectionHelpers.getField(r, "mListenerExecutor");
    }
    synchronized (s.lock) {
      if (s.closed) return false;
      s.fed = true;
      s.queued.add(image);
    }
    if (listener != null && executor != null) {
      executor.execute(() -> {
        synchronized (s.lock) {
          if (s.closed) return;
        }
        listener.onImageAvailable(r);
      });
    }
    return true;
  }

  /**
   * The JPEG with an EXIF Orientation for the request's JPEG_ORIENTATION, as a camera HAL that does not turn the pixels
   * writes it: the sensor-oriented picture then shows upright in a viewer. Bitmap.compress writes no EXIF.
   */
  static byte[] withOrientation(byte[] jpeg, int degrees) {
    int tag = switch (((degrees % 360) + 360) % 360) {
      case 90 -> 6;
      case 180 -> 3;
      case 270 -> 8;
      default -> 1;
    };
    byte[] app1 = {
      (byte) 0xFF, (byte) 0xE1, 0, 34,  // APP1, its length (with these two bytes)
      'E', 'x', 'i', 'f', 0, 0,
      'M', 'M', 0, 42, 0, 0, 0, 8,  // big-endian TIFF header, IFD0 at 8
      0, 1,  // one entry: Orientation, SHORT, count 1
      0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) tag, 0, 0,
      0, 0, 0, 0,  // no next IFD
    };
    byte[] out = new byte[jpeg.length + app1.length];
    System.arraycopy(jpeg, 0, out, 0, 2);  // SOI
    System.arraycopy(app1, 0, out, 2, app1.length);
    System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
    return out;
  }

  private static ByteBuffer sized(ByteBuffer b, int size) {
    // Direct, as a device's planes are: native code (CameraX's YUV conversion, ML Kit) reads their address.
    if (b == null || b.capacity() != size) b = ByteBuffer.allocateDirect(size);
    b.clear();
    return b;
  }

  private static final ThreadLocal<int[]> PIXELS = new ThreadLocal<>();

  private static int[] pixels(Bitmap raw, int w, int h) {
    int[] p = PIXELS.get();
    if (p == null || p.length != w * h) {
      p = new int[w * h];
      PIXELS.set(p);
    }
    raw.getPixels(p, 0, w, 0, 0, w, h);
    return p;
  }

  /** BT.601 limited range, as camera YUV is: Y, then V and U interleaved (NV21), which the U and V planes view. */
  private static void yuv(Bitmap raw, int w, int h, ByteBuffer out) {
    int[] p = pixels(raw, w, h);
    for (int y = 0; y < h; y++) {
      int row = y * w;
      for (int x = 0; x < w; x++) {
        int c = p[row + x];
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        out.put(row + x, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
      }
    }
    int cw = (w + 1) / 2, base = w * h;
    for (int y = 0; y < h; y += 2) {
      for (int x = 0; x < w; x += 2) {
        int c = p[y * w + x];
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
        int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
        int i = base + (y / 2) * cw * 2 + (x / 2) * 2;
        out.put(i, (byte) v);
        out.put(i + 1, (byte) u);
      }
    }
  }

  private static void rgba(Bitmap raw, int w, int h, ByteBuffer out) {
    int[] p = pixels(raw, w, h);
    out.order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < p.length; i++) out.putInt(i * 4, p[i] << 8 | p[i] >>> 24);
  }

  // ---------------------------------------------------------------- the reader's side

  @Implementation
  protected Image acquireNextImage() {
    synchronized (lock) {
      if (!fed) return reflector(ImageReaderReflector.class, reader).acquireNextImage();
      return next();
    }
  }

  @Implementation
  protected Image acquireLatestImage() {
    synchronized (lock) {
      if (!fed) return reflector(ImageReaderReflector.class, reader).acquireLatestImage();
      FakeImage image = next();
      if (image == null) return null;
      // As ImageReader does: keep acquiring, closing the one before, until the queue is empty.
      while (!queued.isEmpty()) {
        image.close();
        image = next();
      }
      return image;
    }
  }

  private FakeImage next() {
    if (closed) throw new IllegalStateException("ImageReader is closed");
    if (acquired >= reader.getMaxImages()) {
      throw new IllegalStateException(String.format("maxImages (%d) has already been acquired, call #close before"
          + " acquiring more.", reader.getMaxImages()));
    }
    if (queued.isEmpty()) return null;
    acquired++;
    FakeImage image = queued.poll();
    image.acquired = true;
    held.add(image);
    return image;
  }

  @Implementation
  protected void close() {
    synchronized (lock) {
      closed = true;
      for (FakeImage i : queued) i.invalidate();
      queued.clear();
      for (FakeImage i : held) i.invalidate();
      held.clear();
      freeBuffers.clear();
    }
    reflector(ImageReaderReflector.class, reader).close();
  }

  void released(FakeImage image) {
    synchronized (lock) {
      if (image.acquired && held.remove(image)) acquired--;
      if (image.buffer != null && !closed && freeBuffers.size() < 2) freeBuffers.add(image.buffer);
    }
  }

  @ForType(ImageReader.class)
  interface ImageReaderReflector {
    @Direct
    Image acquireNextImage();

    @Direct
    Image acquireLatestImage();

    @Direct
    void close();
  }

  // ---------------------------------------------------------------- images

  // Robolectric's per-object data in the instrumented Image and Plane, which their synthetic $$robo$getData returns.
  private static final java.lang.reflect.Field IMAGE_DATA = roboField(Image.class);
  private static final java.lang.reflect.Field PLANE_DATA = roboField(Image.Plane.class);

  private static java.lang.reflect.Field roboField(Class<?> c) {
    try {
      return c.getDeclaredField("__robo_data__");
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  private static Object roboData(java.lang.reflect.Field f, Object o) {
    try {
      return f == null ? null : f.get(o);
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  static final class FakeImage extends Image {
    private final ShadowSimImageReader owner;
    private final int format;
    private final int width;
    private final int height;
    private long timestamp;
    Image.Plane[] planes;
    ByteBuffer buffer;  // returned to the reader's pool on close
    boolean acquired;
    private boolean done;

    FakeImage(ShadowSimImageReader owner, int format, int width, int height, long timestamp) {
      this.owner = owner;
      this.format = format;
      this.width = width;
      this.height = height;
      this.timestamp = timestamp;
      mIsImageValid = true;
    }

    /** A device's YUV_420_888 from an NV21 buffer: Y at full size, V and U interleaved, each plane's pixel stride 2. */
    void yuvPlanes(ByteBuffer b) {
      buffer = b;
      int ySize = width * height, cw = (width + 1) / 2, ch = (height + 1) / 2, vuSize = cw * ch * 2;
      planes = new Image.Plane[] {
        new FakePlane(slice(b, 0, ySize), width, 1),
        new FakePlane(slice(b, ySize + 1, vuSize - 1), cw * 2, 2),
        new FakePlane(slice(b, ySize, vuSize - 1), cw * 2, 2),
      };
    }

    private static ByteBuffer slice(ByteBuffer b, int at, int length) {
      return b.duplicate().position(at).limit(at + length).slice();
    }

    void invalidate() {
      mIsImageValid = false;
      done = true;
    }

    /** Image is instrumented, and javac does not see its (synthetic) implementation of this. */
    @Override public Object $$robo$getData() {
      return roboData(IMAGE_DATA, this);
    }

    @Override public int getFormat() {
      throwISEIfImageIsInvalid();
      return format;
    }

    @Override public int getWidth() {
      throwISEIfImageIsInvalid();
      return width;
    }

    @Override public int getHeight() {
      throwISEIfImageIsInvalid();
      return height;
    }

    @Override public long getTimestamp() {
      throwISEIfImageIsInvalid();
      return timestamp;
    }

    @Override public void setTimestamp(long timestamp) {
      throwISEIfImageIsInvalid();
      this.timestamp = timestamp;
    }

    @Override public int getTransform() {
      throwISEIfImageIsInvalid();
      return 0;
    }

    @Override public int getScalingMode() {
      throwISEIfImageIsInvalid();
      return 0;
    }

    @Override public Image.Plane[] getPlanes() {
      throwISEIfImageIsInvalid();
      return planes.clone();
    }

    @Override public void close() {
      synchronized (this) {
        if (done) return;
        done = true;
        mIsImageValid = false;
      }
      owner.released(this);
    }
  }

  static final class FakePlane extends Image.Plane {
    private final ByteBuffer buffer;
    private final int rowStride;
    private final int pixelStride;

    FakePlane(ByteBuffer buffer, int rowStride, int pixelStride) {
      this.buffer = buffer;
      this.rowStride = rowStride;
      this.pixelStride = pixelStride;
    }

    @Override public Object $$robo$getData() {
      return roboData(PLANE_DATA, this);
    }

    @Override public int getRowStride() {
      return rowStride;
    }

    @Override public int getPixelStride() {
      return pixelStride;
    }

    @Override public ByteBuffer getBuffer() {
      return buffer;
    }
  }
}
