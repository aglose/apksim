package dev.apksim;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Canvas;
import android.graphics.drawable.RippleDrawable;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowDrawable;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * Uses Android's plain solid ripple, animated in Java, rather than the patterned one. The patterned
 * ripple interprets a shader per pixel on the CPU here (15 ms a frame even on a small button), and animates
 * on the render thread, which reports completion through a native looper this process does not have,
 * so it would also never finish. The visible difference is the absence of the sparkle texture.
 */
@Implements(RippleDrawable.class)
public class ShadowFastRipple extends ShadowDrawable {
  @RealObject private RippleDrawable real;
  private boolean forced;

  private void force() {
    if (forced) return;
    forced = true;
    Object state = ReflectionHelpers.getField(real, "mState");
    if (Api.LEVEL >= 31) ReflectionHelpers.setField(state, "mRippleStyle", 0 /* STYLE_SOLID */);  // SPIKE: no patterned ripple before 31
    real.setForceSoftware(true);
  }

  @Implementation
  protected boolean onStateChange(int[] states) {
    force();
    return reflector(Direct_.class, real).onStateChange(states);
  }

  @Implementation
  protected void draw(Canvas canvas) {
    force();
    reflector(Direct_.class, real).draw(canvas);
  }

  @ForType(RippleDrawable.class)
  interface Direct_ {
    @Direct
    boolean onStateChange(int[] states);

    @Direct
    void draw(Canvas canvas);
  }
}
