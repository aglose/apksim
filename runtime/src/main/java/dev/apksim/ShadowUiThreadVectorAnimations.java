package dev.apksim;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.drawable.AnimatedVectorDrawable;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.reflector.Accessor;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * Running instances: an AnimatedVectorDrawable animates on the UI thread (AOSP's {@code VectorDrawableAnimatorUI},
 * what the framework itself falls back to for a software canvas), not on the render thread. A framework
 * indeterminate {@code ProgressBar} is one (Material's spinner), and so are checkbox and switch transitions.
 *
 * <p>On a device the render-thread animator runs inside HWUI: each frame's prepareTree steps it and, while it runs,
 * the render thread asks its own vsync for the next frame, so nothing on the main thread draws. Here HWUI has no
 * render-thread vsync (no native looper, as {@link ShadowFastRipple} notes for the ripple): the animator advanced
 * only in frames the main thread drew anyway, so a spinner turned for the ~250 ms an activity's opening drew and then
 * stood still, and {@code settle} called a screen with a spinner settled. The UI animator is ValueAnimators on the
 * Choreographer that invalidate the drawable each frame, so Host's loop draws while it runs (an infinite repeat is
 * kept by {@link ShadowRepeatingAnimator}). A ProgressBar stops its animation when it is no longer visible
 * ({@code onVisibilityAggregated}), as Host's hiding of a stopped activity's window makes it, so hidden screens do not
 * draw.
 *
 * <p>The render-thread animator is shadowed, not AnimatedVectorDrawable: here AnimatedVectorDrawable's natives are
 * libandroid_runtime's, bound to the class directly, and a shadow on that class answers its natives with nothing
 * (the first spinner crashed in VirtualRefBasePtr). When the render-thread animator is asked to start or reverse
 * before it has started, the drawable is switched to its UI animator ({@code forceAnimationOnUI}, which carries the
 * listener over) and asked again. Test runs keep Robolectric's behaviour (their unpaused Choreographer would never
 * idle). Off with {@code -Dapksim.uiVectorAnimations=false}.
 */
@Implements(
    className = "android.graphics.drawable.AnimatedVectorDrawable$VectorDrawableAnimatorRT",
    isInAndroidSdk = false)
public class ShadowUiThreadVectorAnimations {
  static final boolean ON = !"false".equals(System.getProperty("apksim.uiVectorAnimations"));

  @RealObject private Object real;

  /** The drawable, now on its UI animator; null when this animator is not the drawable's, or has started. */
  private AnimatedVectorDrawable toUi() {
    Rt rt = reflector(Rt.class, real);
    AnimatedVectorDrawable d = rt.getDrawable();
    if (d == null || !rt.getInitialized() || rt.getStarted() || reflector(Avd.class, d).getAnimatorSet() != real) {
      return null;
    }
    d.forceAnimationOnUI();
    return reflector(Avd.class, d).getAnimatorSet() == real ? null : d;
  }

  @Implementation
  protected void start() {
    AnimatedVectorDrawable d = toUi();
    if (d != null) d.start();
    else reflector(Rt.class, real).start();
  }

  @Implementation
  protected void reverse() {
    AnimatedVectorDrawable d = toUi();
    if (d != null) d.reverse();
    else reflector(Rt.class, real).reverse();
  }

  @ForType(className = "android.graphics.drawable.AnimatedVectorDrawable$VectorDrawableAnimatorRT")
  interface Rt {
    @Accessor("mDrawable")
    AnimatedVectorDrawable getDrawable();

    @Accessor("mInitialized")
    boolean getInitialized();

    @Accessor("mStarted")
    boolean getStarted();

    @Direct
    void start();

    @Direct
    void reverse();
  }

  @ForType(AnimatedVectorDrawable.class)
  interface Avd {
    @Accessor("mAnimatorSet")
    Object getAnimatorSet();
  }
}
