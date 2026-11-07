package dev.apksim;

import android.content.Context;
import android.content.Intent;
import java.time.ZoneId;
import java.util.TimeZone;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlarmManager;

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

  /**
   * The zone and `user.timezone` the JVM had before any change, for a test after one whose `timezone` changed it. The
   * zone itself, not its ID: the JVM takes legacy IDs (`-Duser.timezone=PST`) that ZoneId.of rejects.
   */
  private static TimeZone hostZone;
  private static String hostProperty;
  private static boolean changed;

  /** At each setup: the launch's zone; with no --timezone, the host's again only if a test's command changed it. */
  static synchronized void applyLaunchZone() {
    String zone = System.getProperty("apksim.timezone");
    if (zone != null) {
      set(parse(zone));
    } else if (changed) {
      set(hostZone);
      if (hostProperty == null) System.clearProperty("user.timezone");
      else System.setProperty("user.timezone", hostProperty);
    }
    changed = false;
  }

  /**
   * A zone named by the user (`--timezone`, `timezone ZONE`): any ID the JDK knows, and an unknown one is an error,
   * where TimeZone.getTimeZone would quietly give GMT. A device's zone is always a tz database ID, so the JDK's own
   * short aliases (PST, IST, JST: not in that database) become the region they stand for (America/Los_Angeles), with
   * the same rules: apps look the broadcast's ID, getprop's and user.timezone up in their own tz data (Joda's forID
   * threw for PST). EST, MST and HST are tz database zones and stay as they are.
   */
  static TimeZone parse(String id) {
    ZoneId zone = ZoneId.of(id, ZoneId.SHORT_IDS);  // throws for an unknown ID
    if (!(zone instanceof java.time.ZoneOffset) && !zone.getId().equals(id) && ZoneId.SHORT_IDS.containsKey(id)) {
      return TimeZone.getTimeZone(zone);
    }
    TimeZone tz = TimeZone.getTimeZone(id);
    return tz.getID().equals(id) ? tz : TimeZone.getTimeZone(zone);
  }

  /** `timezone ZONE` while the app runs (or in a test): the new zone, and the broadcast a device sends for it. */
  static synchronized String change(Context app, String zone) {
    TimeZone tz = parse(zone);
    set(tz);
    changed = true;
    if (app != null) {
      app.sendBroadcast(new Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, tz.getID())
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
  private static void set(TimeZone tz) {
    if (hostZone == null) {
      hostZone = TimeZone.getDefault();  // a copy
      hostProperty = System.getProperty("user.timezone");
    }
    TimeZone.setDefault(tz);
    System.setProperty("user.timezone", tz.getID());
    android.icu.util.TimeZone.setICUDefault(null);
    SimWindow.setTimeZone(tz.getID());
  }

  /**
   * AlarmManager.setTimeZone as the system server runs it: any ID the JDK knows becomes the device's zone, as the
   * `timezone` command does (ACTION_TIMEZONE_CHANGED included). The framework's own check asks libcore's zone database,
   * which is not here, so every ID was "not an Olson ID" and a setup wizard's zone picker crashed the app. An unknown
   * ID throws IllegalArgumentException, as on a device.
   */
  @Implements(android.app.AlarmManager.class)
  public static class ShadowSimAlarmManager extends ShadowAlarmManager {
    @Implementation
    @Override
    protected void setTimeZone(String timeZone) {
      if (timeZone == null || timeZone.isEmpty()) return;  // AlarmManager ignores an empty ID
      try {
        parse(timeZone);
      } catch (java.time.DateTimeException e) {
        throw new IllegalArgumentException("Timezone: " + timeZone + " is not an Olson ID");
      }
      change(RuntimeEnvironment.getApplication(), timeZone);
    }
  }
}
