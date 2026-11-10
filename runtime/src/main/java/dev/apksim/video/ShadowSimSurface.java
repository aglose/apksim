package dev.apksim.video;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNativeSurface;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import org.robolectric.util.reflector.Static;

/**
 * A Surface made from a SurfaceTexture is valid, as on a device. Robolectric's native Surface gives it no native object
 * (0), so {@code isValid()} was false and a consumer that checks (CameraX's SurfaceRequest.provideSurface: "invalid
 * surface", so no preview and no camera session at all) refused it. Such a Surface is only a name here (pictures for it
 * go to {@link VideoSurfaces}), so it gets a handle of its own, negative, which no native code ever sees: every native
 * method taking one answers for it here (valid, no buffers, nothing to release). Every other Surface is Robolectric's,
 * except that its native runtime lacks some of SurfaceNatives' JNI methods (isValid, release: an ImageReader's Surface
 * released by CameraX threw UnsatisfiedLinkError), which answer as a live Surface with nothing to report would.
 */
@Implements(value = Surface.class, minSdk = 26, isInAndroidSdk = false, callNativeMethodsByDefault = true)
public class ShadowSimSurface extends ShadowNativeSurface {
  private static final AtomicLong NEXT = new AtomicLong();

  private static boolean ours(long handle) {
    return handle < 0;
  }

  /** Robolectric's native call, or {@code fallback} where its runtime does not have it. */
  private static <T> T or(java.util.function.Supplier<T> call, T fallback) {
    try {
      return call.get();
    } catch (UnsatisfiedLinkError e) {
      return fallback;
    }
  }

  private static void or(Runnable call) {
    try {
      call.run();
    } catch (UnsatisfiedLinkError e) {
      // nothing to do without it
    }
  }

  @Implementation
  protected static long nativeCreateFromSurfaceTexture(SurfaceTexture texture) {
    return -NEXT.incrementAndGet();
  }

  @Implementation
  protected static boolean nativeIsValid(long handle) {
    return ours(handle) || or(() -> ShadowNativeSurface.nativeIsValid(handle), handle != 0);
  }

  @Implementation
  protected static void nativeRelease(long handle) {
    if (!ours(handle)) or(() -> ShadowNativeSurface.nativeRelease(handle));
  }

  @Implementation
  protected static void nativeDestroy(long handle) {
    if (!ours(handle)) or(() -> reflector(SurfaceReflector.class).nativeDestroy(handle));
  }

  @Implementation
  protected static long nativeLockCanvas(long handle, Canvas canvas, Rect dirty) throws Surface.OutOfResourcesException {
    if (ours(handle)) throw new IllegalArgumentException("apksim: lockCanvas on a SurfaceTexture's Surface is not supported");
    return ShadowNativeSurface.nativeLockCanvas(handle, canvas, dirty);
  }

  @Implementation
  protected static void nativeUnlockCanvasAndPost(long handle, Canvas canvas) {
    if (!ours(handle)) ShadowNativeSurface.nativeUnlockCanvasAndPost(handle, canvas);
  }

  @Implementation
  protected static boolean nativeIsConsumerRunningBehind(long handle) {
    return !ours(handle) && or(() -> ShadowNativeSurface.nativeIsConsumerRunningBehind(handle), false);
  }

  @Implementation
  protected static long nativeReadFromParcel(long handle, Parcel parcel) {
    return ShadowNativeSurface.nativeReadFromParcel(ours(handle) ? 0 : handle, parcel);
  }

  @Implementation
  protected static void nativeWriteToParcel(long handle, Parcel parcel) {
    ShadowNativeSurface.nativeWriteToParcel(ours(handle) ? 0 : handle, parcel);
  }

  @Implementation
  protected static void nativeAllocateBuffers(long handle) {
    if (!ours(handle)) or(() -> ShadowNativeSurface.nativeAllocateBuffers(handle));
  }

  @Implementation
  protected static int nativeGetWidth(long handle) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeGetWidth(handle), 0);
  }

  @Implementation
  protected static int nativeGetHeight(long handle) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeGetHeight(handle), 0);
  }

  @Implementation
  protected static long nativeGetNextFrameNumber(long handle) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeGetNextFrameNumber(handle), 0L);
  }

  @Implementation
  protected static int nativeSetScalingMode(long handle, int mode) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeSetScalingMode(handle, mode), 0);
  }

  @Implementation
  protected static int nativeForceScopedDisconnect(long handle) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeForceScopedDisconnect(handle), 0);
  }

  @Implementation
  protected static int nativeAttachAndQueueBufferWithColorSpace(long handle, HardwareBuffer buffer, int colorSpaceId) {
    return ours(handle) ? 0 : ShadowNativeSurface.nativeAttachAndQueueBufferWithColorSpace(handle, buffer, colorSpaceId);
  }

  @Implementation
  protected static int nativeSetSharedBufferModeEnabled(long handle, boolean enabled) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeSetSharedBufferModeEnabled(handle, enabled), 0);
  }

  @Implementation
  protected static int nativeSetAutoRefreshEnabled(long handle, boolean enabled) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeSetAutoRefreshEnabled(handle, enabled), 0);
  }

  @Implementation
  protected static int nativeSetFrameRate(long handle, float frameRate, int compatibility, int strategy) {
    return ours(handle) ? 0 : or(() -> ShadowNativeSurface.nativeSetFrameRate(handle, frameRate, compatibility, strategy), 0);
  }

  @Implementation
  protected static long nHwuiCreate(long rootNode, long surface, boolean isWideColorGamut) {
    if (ours(surface)) throw new IllegalArgumentException("apksim: lockHardwareCanvas on a SurfaceTexture's Surface is not supported");
    return ShadowNativeSurface.nHwuiCreate(rootNode, surface, isWideColorGamut);
  }

  @Implementation
  protected static void nHwuiSetSurface(long renderer, long surface) {
    if (ours(surface)) throw new IllegalArgumentException("apksim: lockHardwareCanvas on a SurfaceTexture's Surface is not supported");
    ShadowNativeSurface.nHwuiSetSurface(renderer, surface);
  }

  @ForType(Surface.class)
  interface SurfaceReflector {
    @Static
    @Direct
    void nativeDestroy(long handle);
  }
}
