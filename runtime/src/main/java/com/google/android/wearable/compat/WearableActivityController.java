package com.google.android.wearable.compat;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * apksim's stand-in for the Wear OS shared library {@code com.google.android.wearable} (a watch's system provides the
 * real one): enough of {@code WearableActivityController} that androidx.wear.ambient finds it, reports a current
 * version, and gets its ambient callbacks. Ambient mode is never entered on its own; the {@code ambient on|off}
 * control command ({@link #setAmbient}) enters and leaves it for every activity that enabled it.
 */
public class WearableActivityController {
  public static final String EXTRA_BURN_IN_PROTECTION = "com.google.android.wearable.compat.extra.BURN_IN_PROTECTION";
  public static final String EXTRA_LOWBIT_AMBIENT = "com.google.android.wearable.compat.extra.LOWBIT_AMBIENT";

  /** The shared library's version (androidx.wear's SharedLibraryVersion reads it): the current one. */
  private static final int SHARED_LIBRARY_VERSION = com.google.android.wearable.WearableSharedLib.version();

  private static final List<WearableActivityController> LIVE = new ArrayList<>();
  private static boolean ambientNow;

  private final Activity activity;
  private final AmbientCallback callback;
  private boolean enabled;
  private boolean ambient;
  private boolean resumed;

  public WearableActivityController(String tag, Activity activity, AmbientCallback callback) {
    this.activity = activity;
    this.callback = callback;
  }

  public static int getSharedLibraryVersion() {
    return SHARED_LIBRARY_VERSION;
  }

  public final void setAmbientEnabled() {
    enabled = true;
  }

  public final void setAutoResumeEnabled(boolean enabled) {}

  public final void setAmbientGestureEnabled(boolean enabled) {}

  public final void setAmbientOffloadEnabled(boolean enabled) {}

  public final boolean isAmbient() {
    return ambient;
  }

  public final boolean isAutoResumeEnabled() {
    return false;
  }

  public void onCreate() {
    synchronized (LIVE) {
      if (!LIVE.contains(this)) LIVE.add(this);
    }
  }

  public void onResume() {
    // A recreated activity's new controller joins the ambient state the device is in.
    resumed = true;
    boolean now = ambient();
    if (enabled && ambient != now) {
      synchronized (LIVE) {
        if (!LIVE.contains(this)) LIVE.add(this);
      }
      change(now);
    } else if (ambient) {
      handler.removeCallbacks(update);
      handler.postDelayed(update, UPDATE_MS);
    }
  }

  public void onPause() {
    resumed = false;
    handler.removeCallbacks(update);
  }

  public void onStop() {
    resumed = false;
    handler.removeCallbacks(update);
  }

  public void onDestroy() {
    handler.removeCallbacks(update);
    synchronized (LIVE) {
      LIVE.remove(this);
    }
  }

  /** Enters or leaves ambient mode for every live controller whose activity enabled it; true if any was told. */
  public static boolean setAmbient(boolean on) {
    List<WearableActivityController> now;
    synchronized (LIVE) {
      ambientNow = on;
      now = new ArrayList<>(LIVE);
    }
    boolean told = false;
    for (WearableActivityController c : now) {
      // Callbacks come between onResume and onPause; a paused activity is told when it resumes.
      if (!c.enabled || !c.resumed || c.ambient == on) continue;
      told = true;
      Runnable r = () -> c.change(on);
      if (Looper.myLooper() == Looper.getMainLooper()) r.run();
      else new Handler(Looper.getMainLooper()).post(r);
    }
    return told;
  }

  public static boolean ambient() {
    synchronized (LIVE) {
      return ambientNow;
    }
  }

  /** Wear OS updates an ambient screen about once a minute (onUpdateAmbient), while the activity is resumed. */
  private static final long UPDATE_MS = 60_000;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable update = new Runnable() {
    @Override public void run() {
      if (!ambient || !resumed) return;
      if (callback != null) callback.onUpdateAmbient();
      handler.postDelayed(this, UPDATE_MS);
    }
  };

  /** AmbientDelegate.dump forwards here; the state is all there is to print. */
  public final void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
    writer.println(prefix + "WearableActivityController: enabled=" + enabled + " ambient=" + ambient + " resumed=" + resumed);
  }

  private void change(boolean on) {
    ambient = on;
    handler.removeCallbacks(update);
    if (on) handler.postDelayed(update, UPDATE_MS);
    if (callback == null) return;
    if (on) {
      Bundle b = new Bundle();
      b.putBoolean(EXTRA_BURN_IN_PROTECTION, false);
      b.putBoolean(EXTRA_LOWBIT_AMBIENT, false);
      callback.onEnterAmbient(b);
    } else {
      callback.onExitAmbient();
    }
  }

  /** The callbacks the controller sends an activity. */
  public static class AmbientCallback {
    public void onEnterAmbient(Bundle ambientDetails) {}

    public void onUpdateAmbient() {}

    public void onExitAmbient() {}

    public void onInvalidateAmbientOffload() {}
  }
}
