package dev.apksim;

import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.view.View;
import android.view.ViewRootImpl;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowViewRootImpl;

/**
 * In a test run each window draw draws the window's views, as a device's does after every traversal that asks for
 * one. Robolectric's draw has no surface and draws nothing, so between screenshots views were never drawn: their
 * View.computeScroll never ran, so a Scroller-driven move (a DrawerLayout opening, ScrollView.smoothScrollTo) stood
 * still until a screenshot drew the window, and a view not drawn since its last invalidate drops the next one as
 * already pending. The views draw into a display list that is then thrown away: recording only, nothing rasterized.
 */
@Implements(value = ViewRootImpl.class, isInAndroidSdk = false)
public class ShadowSimViewRootImpl extends ShadowViewRootImpl {
  private RenderNode node;

  @Implementation(minSdk = 34)
  @Override
  protected boolean draw(boolean fullRedrawNeeded, Object activeSyncGroup, boolean syncBuffer) {
    boolean drew = super.draw(fullRedrawNeeded, activeSyncGroup, syncBuffer);
    if (TestActivities.testRun()) record();
    return drew;
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
