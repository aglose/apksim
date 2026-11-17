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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * One {@code PendingResult} per delivery. Robolectric's ordered broadcasts set a receiver's pending result when the
 * delivery is queued, not when it runs, and keep it on the receiver object: two broadcasts queued to one receiver
 * before either ran both finished the second's result, and the second delivery's {@code finish()} threw "Broadcast
 * already finished", which ended the app (issue 452; the tab shell's own case was issue 412). Ordered results are
 * kept in the order they were set, and each ordered delivery puts its own back before it runs. Other results are not
 * kept: a plain broadcast's is set in the delivery itself, just before the receiver runs, and a sticky one's at
 * registration, around a direct call of the receiver; a delivery that finds the result it was just given takes none
 * from the queue. Sends from several threads at once may still pair results with the wrong delivery (set and post
 * are two steps in Robolectric); each is finished once all the same.
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

  /** The result of the plain or sticky delivery that is about to run, if one was just set. */
  private volatile BroadcastReceiver.PendingResult given;

  @Implementation
  protected void setPendingResult(BroadcastReceiver.PendingResult result) {
    if (result != null && ordered(result)) {
      synchronized (queued) {
        queued.addLast(result);
      }
    } else {
      given = result;
    }
    reflector(Real.class, realReceiver).setPendingResult(result);
  }

  private static boolean ordered(BroadcastReceiver.PendingResult result) {
    try {
      return ReflectionHelpers.getField(result, "mOrderedHint");
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public void onReceive(Context context, Intent intent, AtomicBoolean abort) {
    BroadcastReceiver.PendingResult now = given;
    if (now != null && realReceiver.getPendingResult() == now) {
      given = null; // a plain delivery: its result was set just before this call
    } else {
      BroadcastReceiver.PendingResult mine;
      synchronized (queued) {
        mine = queued.pollFirst();
      }
      if (mine != null) reflector(Real.class, realReceiver).setPendingResult(mine);
    }
    super.onReceive(context, intent, abort);
  }
}
