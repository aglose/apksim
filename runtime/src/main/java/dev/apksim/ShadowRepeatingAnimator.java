package dev.apksim;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.animation.ValueAnimator;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowValueAnimator;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * Running instances and {@code --realtime-clock} test runs: a ValueAnimator set to repeat {@link ValueAnimator#INFINITE}
 * times repeats for ever, as on a device. Robolectric's shadow turns INFINITE into 1, so an app's endless View
 * animation (a spinner, a pulsing dot, the test app's rotating square on {@code apksim://bitmaps}) stopped after its
 * second cycle, and {@code getRepeatCount()} said 1. Robolectric caps it because its unpaused Choreographer runs every
 * frame asked for at once: an endless animator would never let the main looper go idle. Host's loop and
 * RealTimeClock pause the Choreographer and run frames at their vsyncs, so the cap is not needed there; default test
 * runs keep it.
 */
@Implements(ValueAnimator.class)
public class ShadowRepeatingAnimator extends ShadowValueAnimator {
  @RealObject private ValueAnimator realObject;

  @Implementation
  @Override
  protected void setRepeatCount(int count) {
    super.setRepeatCount(count);  // keeps Robolectric's getActualRepeatCount()
    reflector(Reflector.class, realObject).setRepeatCount(count);
  }

  @ForType(ValueAnimator.class)
  interface Reflector {
    @Direct
    void setRepeatCount(int count);
  }
}
