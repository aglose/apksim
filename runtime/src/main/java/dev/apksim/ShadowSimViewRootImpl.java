package dev.apksim;

import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.view.View;
import android.view.ViewRootImpl;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * In a test run each window draw draws the window's views, as a device's does after every traversal that asks for
 * one. Robolectric's draw has no surface and draws nothing, so between screenshots views were never drawn: their
 * View.computeScroll never ran, so a Scroller-driven move (a DrawerLayout opening, ScrollView.smoothScrollTo) stood
 * still until a screenshot drew the window, and a view not drawn since its last invalidate drops the next one as
 * already pending. The views draw into a display list that is then thrown away: recording only, nothing rasterized.
 *
 * <p>In a running instance a window's frame starts where Host shows the window ({@link #setFrame}).
 */
@Implements(value = ViewRootImpl.class, isInAndroidSdk = false)
public class ShadowSimViewRootImpl extends ShadowViewRootImpl {
  private RenderNode node;

  @Implementation(minSdk = 34)
  @Override
  protected boolean draw(boolean fullRedrawNeeded, Object activeSyncGroup, boolean syncBuffer) {
    boolean drew = super.draw(fullRedrawNeeded, activeSyncGroup, syncBuffer);
    if (TestActivities.testRun()) {
      record();
      TestRecorder.onDraw();  // --record: a frame of the test's movie, when one is due
    }
    return drew;
  }

  /**
   * In a running instance, the frame a relayout (or a resize) gives the window is moved to where Host shows it, so
   * that AttachInfo's window position, which ViewRootImpl copies from it, and with it getLocationOnScreen and
   * accessibility's boundsInScreen, are the screen's from the traversal on: Robolectric's window session puts a window
   * that is not full-screen (a centred dialog) at lp.x/lp.y with the display's size. A Compose dialog relayouts on every
   * traversal and Compose caches the window's position it reads then, so moving it only afterwards
   * ({@link Host#syncWindowPositions}) left its nodes at the top left. Insets are worked out from the frame as
   * Robolectric gave it, as before.
   */
  @Implementation(minSdk = 36)
  protected void setFrame(Rect frame, boolean withinRelayout) {
    org.robolectric.shadow.api.Shadow.directlyOn(realObject, ViewRootImpl.class, "setFrame",
        ClassParameter.from(Rect.class, frame), ClassParameter.from(boolean.class, withinRelayout));
    View view = realObject.getView();
    if (view == null || TestActivities.testRun()) return;
    // Measured already: a relayout comes after the traversal's measure, before its layout.
    int[] at = Host.screenPositionIfRunning(view, view.getMeasuredWidth(), view.getMeasuredHeight());
    if (at == null) return;
    Rect winFrame = ReflectionHelpers.getField(realObject, "mWinFrame");
    winFrame.offsetTo(at[0], at[1]);
  }

  private void record() {
    View view = realObject.getView();
    if (view == null || view.getWidth() == 0 || view.getHeight() == 0) return;
    if (node == null) node = RenderNode.create("apksim-window", null);
    RecordingCanvas c = node.beginRecording(view.getWidth(), view.getHeight());
    try {
      view.draw(c);
    } finally {
      node.endRecording();
      node.discardDisplayList();
    }
  }
}
