package dev.apksim;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowBroadcastReceiver;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * One {@code PendingResult} per delivery. Robolectric's ordered broadcasts set a receiver's pending result when the
 * delivery is queued, not when it runs, and keep it on the receiver object: two broadcasts queued to one receiver
 * before either ran both finished the second's result, and the second delivery's {@code finish()} threw "Broadcast
 * already finished", which ended the app (issue 452; the tab shell's own case was issue 412). Results are kept in
 * the order they were set, and each delivery puts its own back before it runs.
 */
@Implements(value = BroadcastReceiver.class, isInAndroidSdk = false)
public class ShadowSimBroadcastReceiver extends ShadowBroadcastReceiver {
  @RealObject private BroadcastReceiver realReceiver;
  private final ArrayDeque<BroadcastReceiver.PendingResult> queued = new ArrayDeque<>();

  @ForType(BroadcastReceiver.class)
  interface Real {
    @Direct
    void setPendingResult(BroadcastReceiver.PendingResult result);
  }

  @Implementation
  protected void setPendingResult(BroadcastReceiver.PendingResult result) {
    if (result != null) {
      synchronized (queued) {
        queued.addLast(result);
      }
    }
    reflector(Real.class, realReceiver).setPendingResult(result);
  }

  @Override
  public void onReceive(Context context, Intent intent, AtomicBoolean abort) {
    BroadcastReceiver.PendingResult mine;
    synchronized (queued) {
      mine = queued.pollFirst();
    }
    if (mine != null) reflector(Real.class, realReceiver).setPendingResult(mine);
    super.onReceive(context, intent, abort);
  }
}
