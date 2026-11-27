package dev.apksim;

import android.content.ComponentName;
import android.content.Intent;

/**
 * An app restarting its own process (issue 658): restart helpers (ProcessPhoenix, a sign-out or language switch that
 * wants a clean process) start the launcher activity, usually with {@code Intent.makeRestartActivityTask}, then call
 * {@code System.exit}, {@code Runtime.exit} or {@code Process.killProcess(myPid())}. On a device the activity manager
 * starts a new process for the pending start; apksim's process is the device, so the instance ended there.
 *
 * <p>This first step only recognises it: the last start of one of the app's activities that has not landed yet (no
 * activity resumed since), and when an exit follows within {@link #WINDOW_MS} of real time, Exits prints {@code [apksim] restart: ...} before its exit line. Relaunching the app
 * (serve and beam sessions, then plain launches) builds on it. Running instances only: in test runs the exit keeps
 * ending the run, as a killed process fails the instrumentation on a device.
 */
final class Restarts {
  private Restarts() {}

  /** How long before an exit a start still counts as the restart's. */
  static final long WINDOW_MS = 1000;

  private static volatile long lastStartNanos;
  private static volatile String lastStart;

  /** From Instrumentation.execStartActivity (every startActivity of the app, from an activity or another context). */
  static void started(Intent intent) {
    try {
      if (intent == null || TestActivities.testRun()) return;
      ComponentName c = intent.getComponent();
      String pkg = System.getProperty("apksim.package");
      if (c == null) {
        android.content.pm.ResolveInfo r =
            org.robolectric.RuntimeEnvironment.getApplication().getPackageManager().resolveActivity(intent, 0);
        if (r == null || r.activityInfo == null) return;
        c = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
      }
      if (pkg == null || !pkg.equals(c.getPackageName())) return;
      lastStart = c.getShortClassName() + flags(intent.getFlags());
      lastStartNanos = System.nanoTime();
    } catch (Throwable ignored) {
      // Only a record for the exit line.
    }
  }

  /** From Instrumentation.callActivityOnResume: the pending start, if any, has landed. */
  static void resumed() {
    lastStart = null;
  }

  /**
   * The restart line for an exit made by {@code call}, or null when no start of the app's came in the window before
   * it. Called once, by the exit that ends the process.
   */
  static String line(String call) {
    String start = lastStart;
    if (start == null) return null;
    long ago = (System.nanoTime() - lastStartNanos) / 1_000_000;
    if (ago > WINDOW_MS) return null;
    return "[apksim] restart: the app started " + start + " " + ago + " ms before " + call
        + ", restarting its process; apksim does not relaunch it yet (issue 658)";
  }

  private static String flags(int f) {
    StringBuilder s = new StringBuilder();
    if ((f & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) s.append("|NEW_TASK");
    if ((f & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0) s.append("|CLEAR_TASK");
    if ((f & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) s.append("|CLEAR_TOP");
    return s.length() == 0 ? "" : " (" + s.substring(1) + ")";
  }
}
