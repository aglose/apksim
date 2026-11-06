package dev.apksim;

import android.os.SystemClock;
import android.view.View;

/**
 * ViewRootImpl.draw sets AttachInfo.mDrawingTime to the frame's time before every draw, and legacy view Animations
 * (View.startAnimation: fades, scales, AnimationSet) are stepped to it. HwRenderer records windows itself
 * (updateDisplayListIfDirty), so the time stayed 0: every such animation sat on its first frame, often invisible at
 * alpha 0, and its end listener never ran (a profile picker whose avatars fade in showed none, and a tap whose action
 * waits for its press animation did nothing).
 */
final class DrawingTime {
  private static java.lang.reflect.Field attachInfo;
  private static java.lang.reflect.Field drawingTime;

  private DrawingTime() {}

  static void stamp(View root) {
    try {
      if (attachInfo == null) {
        java.lang.reflect.Field f = View.class.getDeclaredField("mAttachInfo");
        f.setAccessible(true);
        attachInfo = f;
      }
      Object info = attachInfo.get(root);
      if (info == null) return;
      if (drawingTime == null) {
        java.lang.reflect.Field f = info.getClass().getDeclaredField("mDrawingTime");
        f.setAccessible(true);
        drawingTime = f;
      }
      drawingTime.setLong(info, SystemClock.uptimeMillis());
    } catch (ReflectiveOperationException e) {
      System.err.println("[apksim] drawing time: " + e);
    }
  }
}
