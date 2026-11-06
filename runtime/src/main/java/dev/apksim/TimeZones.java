package dev.apksim;

import android.content.Context;
import android.content.Intent;
import java.time.ZoneId;
import java.util.TimeZone;

/**
 * The device's time zone: `--timezone ZONE` (-Dapksim.timezone) at each setup, before the Application exists, and the
 * `timezone ZONE` command while the app runs. Every zone source an app reads gets it: the JDK's default (java.util,
 * java.time), android.icu's, and the `user.timezone` property, which Joda-Time's DateTimeZone.getDefault() reads before
 * the JDK's default (it was left at the host's zone, so Joda dates were the host's). A change while the app runs is
 * broadcast as ACTION_TIMEZONE_CHANGED, as a device does: libraries that keep a default of their own (joda-time-android)
 * listen for it.
 */
final class TimeZones {
  private TimeZones() {}

  /** The zone and `user.timezone` the JVM had before any change, for a test after one whose `timezone` changed it. */
  private static String hostZone, hostProperty;
  private static boolean changed;

  /** At each setup: the launch's zone; with no --timezone, the host's again only if a test's command changed it. */
  static synchronized void applyLaunchZone() {
    String zone = System.getProperty("apksim.timezone");
    if (zone != null) {
      set(zone);
    } else if (changed) {
      set(hostZone);
      if (hostProperty == null) System.clearProperty("user.timezone");
      else System.setProperty("user.timezone", hostProperty);
    }
    changed = false;
  }

  /** `timezone ZONE` while the app runs (or in a test): the new zone, and the broadcast a device sends for it. */
  static synchronized String change(Context app, String zone) {
    set(zone);
    changed = true;
    if (app != null) {
      app.sendBroadcast(new Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, zone)
          .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND));
    }
    return describe();
  }

  /** The zone the app sees, and android.icu's when it differs. */
  static String describe() {
    String icu = android.icu.util.TimeZone.getDefault().getID();
    String jdk = TimeZone.getDefault().getID();
    return jdk.equals(icu) ? jdk : jdk + " (android.icu: " + icu + ")";
  }

  /**
   * ICU keeps the zone it first worked out, and a standby may have formatted a date before the launch named one, so
   * its cached zone is dropped too and taken again from the JDK's. Called inside the sandbox: ICU's classes are
   * framework classes.
   */
  private static void set(String zone) {
    if (hostZone == null) {
      hostZone = TimeZone.getDefault().getID();
      hostProperty = System.getProperty("user.timezone");
    }
    // ZoneId.of rejects an unknown name, which TimeZone.getTimeZone would quietly turn into GMT.
    TimeZone tz = TimeZone.getTimeZone(ZoneId.of(zone));
    TimeZone.setDefault(tz);
    System.setProperty("user.timezone", tz.getID());
    android.icu.util.TimeZone.setICUDefault(null);
    SimWindow.setTimeZone(tz.getID());
  }
}
