package fixture;

/**
 * Modelled on Compose's SuspendAnimationKt.animate, where a translator that counted a handler's live registers at the
 * entry of every block of its range merged the float duration scale with an ObjectRef and dropped it (every animation
 * in a v2 Compose test snapped to its end). Written in Java, R8 and D8 allocate it without that register sharing, so
 * this passes either way ([Shapes.unregister] is the shape that fails); it stays as a check of floats and refs around
 * handlers.
 */
final class Animate {
  static final class Ref {
    Object element;
  }

  interface Frame {
    /** Returns the next frame time, or throws to cancel. */
    long next(long now);
  }

  /** Steps a value from [from] to [to] over [durationMs] scaled by [scale]; returns where it stopped. */
  static float animate(float from, float to, long durationMs, float scale, Frame frames, Ref lastFrame) {
    float duration = durationMs * scale;
    Ref state = new Ref();
    state.element = from;
    long start = 0;
    long now = 0;
    try {
      while (true) {
        now = frames.next(now);
        lastFrame.element = now;
        float t = duration == 0f ? 1f : Math.min(1f, (now - start) / duration);
        state.element = from + (to - from) * t;
        if (t >= 1f) break;
      }
    } catch (IllegalStateException cancelled) {
      Object why = cancelled.getMessage();
      lastFrame.element = why;
    } finally {
      state.element = state.element == null ? from : state.element;
    }
    return (Float) state.element;
  }

  /** Like [animate], the scale read again in the handler: live there and after the range. */
  static float scaledOrCancelled(float scale, Frame frames) {
    float s = scale * 2f;
    Object last = null;
    int frame = 0;
    try {
      for (; frame < 4; frame++) last = frames.next(frame);
    } catch (IllegalStateException e) {
      last = e;
      return -s;
    }
    return last == null ? 0f : s + frame;
  }
}
