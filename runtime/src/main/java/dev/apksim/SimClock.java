package dev.apksim;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The wall clock the app sees. Real time unless set (`clock ISO|epoch-ms|real`, `--clock ISO` at launch): then it
 * starts at that instant and runs on from it at real speed, so screenshots of a screen showing the date or the time
 * of day come out the same on every run. The translator routes app and library calls here (System.currentTimeMillis,
 * java.time's no-argument now(), Clock.systemUTC/systemDefaultZone/system, new Date(), Calendar.getInstance), and the
 * framework reads it through SimRunner.WallClock. JDK code that reads the clock itself (a SimpleDateFormat's default
 * date, a file's time) still sees real time. The uptime clock (SystemClock, loopers) is not touched.
 */
public final class SimClock {
  private SimClock() {}

  /** Added to the real wall clock; 0 when real. */
  private static volatile long offsetMs;

  /** Applies -Dapksim.clock at launch. */
  static void applyLaunchOption() {
    String c = System.getProperty("apksim.clock");
    if (c != null && !c.isEmpty()) set(c);
  }

  /** `clock [ISO|epoch-ms|real]`: sets the app's wall clock, or reports it. */
  static String command(String[] a) {
    if (a.length > 1) set(a[1]);
    return Instant.ofEpochMilli(currentTimeMillis()) + (offsetMs == 0 ? " (real)" : " (set)");
  }

  static void set(String value) {
    if (value.equals("real")) {
      offsetMs = 0;
      return;
    }
    long at = value.chars().allMatch(Character::isDigit) ? Long.parseLong(value) : Instant.parse(value).toEpochMilli();
    // Never exactly 0: that means "real" (an offset of 0 ms is real time anyway).
    offsetMs = at - System.currentTimeMillis();
  }

  public static long currentTimeMillis() {
    return System.currentTimeMillis() + offsetMs;
  }

  /**
   * The system clock in [zone], shifted by whatever offset is current at each read: a Clock an app keeps (a static,
   * an injected singleton) follows later `clock` commands, as System.currentTimeMillis does.
   */
  private static final class Shifted extends Clock {
    private final ZoneId zone;

    Shifted(ZoneId zone) {
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
      return z.equals(zone) ? this : new Shifted(z);
    }

    @Override
    public long millis() {
      return currentTimeMillis();
    }

    @Override
    public Instant instant() {
      long o = offsetMs;
      Instant now = Instant.now();
      return o == 0 ? now : now.plusMillis(o);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Shifted s && s.zone.equals(zone);
    }

    @Override
    public int hashCode() {
      return zone.hashCode() + 1;
    }

    @Override
    public String toString() {
      return "SimClock[" + zone + "]";
    }
  }

  /** What java.time's X.now() reads (Clock.systemDefaultZone), shifted. */
  public static Clock clock() {
    return new Shifted(ZoneId.systemDefault());
  }

  /** What X.now(zone) reads. */
  public static Clock clock(ZoneId zone) {
    return new Shifted(java.util.Objects.requireNonNull(zone, "zone"));
  }

  public static Clock systemUTC() {
    return new Shifted(java.time.ZoneOffset.UTC);
  }

  public static Clock systemDefaultZone() {
    return new Shifted(ZoneId.systemDefault());
  }

  public static Clock system(ZoneId zone) {
    return clock(zone);
  }

  private static Calendar at(Calendar c) {
    if (offsetMs != 0) c.setTimeInMillis(currentTimeMillis());
    return c;
  }

  public static Calendar calendar() {
    return at(Calendar.getInstance());
  }

  public static Calendar calendar(TimeZone zone) {
    return at(Calendar.getInstance(zone));
  }

  public static Calendar calendar(Locale locale) {
    return at(Calendar.getInstance(locale));
  }

  public static Calendar calendar(TimeZone zone, Locale locale) {
    return at(Calendar.getInstance(zone, locale));
  }
}
