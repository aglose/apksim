package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.robolectric.shadows.ShadowLocationManager;

/**
 * The device's location, as the iOS Simulator's Features > Location sets it: a fixed point, a moving route (run,
 * bicycle ride, freeway drive, or a GPX track), or none. Fixes go to the GPS, network and fused providers once a
 * second while moving. Apps that use Play Services' FusedLocationProviderClient are not covered (no Play Services).
 */
final class LocationSim {
  /** Apple Park, as the Simulator's default custom location; routes loop around it. */
  private static final double HOME_LAT = 37.3349;
  private static final double HOME_LNG = -122.0090;
  private static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER};

  private final LocationManager manager;
  private final ShadowLocationManager shadow;
  private List<double[]> route;  // lat, lng, seconds from start
  private double[] fixed;  // a custom location, reported again every second like a stationary GPS
  private double routeSeconds;
  private long routeStart;
  private long lastFix;

  LocationSim(Application app) {
    manager = app.getSystemService(LocationManager.class);
    shadow = shadowOf(manager);
  }

  void tick() {
    if (route == null && fixed == null) return;
    long now = SystemClock.uptimeMillis();
    if (now - lastFix < 1000) return;
    lastFix = now;
    if (fixed != null) {
      send(fix(fixed[0], fixed[1]));
      return;
    }
    double t = ((now - routeStart) / 1000.0) % routeSeconds;
    for (int i = 1; i < route.size(); i++) {
      double[] a = route.get(i - 1);
      double[] b = route.get(i);
      if (t > b[2]) continue;
      double f = b[2] == a[2] ? 0 : (t - a[2]) / (b[2] - a[2]);
      Location l = fix(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f);
      float[] d = new float[2];
      Location.distanceBetween(a[0], a[1], b[0], b[1], d);
      l.setSpeed((float) (d[0] / Math.max(0.001, b[2] - a[2])));
      l.setBearing(d[1] < 0 ? d[1] + 360 : d[1]);
      send(l);
      return;
    }
  }

  /** location LAT LNG | location run|bike|drive | location gpx FILE | location none | location (current). */
  String run(String[] a) {
    String op = a.length > 1 ? a[1] : "";
    switch (op) {
      case "" -> {
        Location l = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        return l == null ? "none" : String.format(Locale.ROOT, "%.6f, %.6f%s", l.getLatitude(), l.getLongitude(), route == null ? "" : " (moving)");
      }
      case "none" -> {
        route = null;
        fixed = null;
        for (String p : PROVIDERS) shadow.setProviderEnabled(p, false);
        return "ok";
      }
      case "run", "bike", "drive" -> {
        // A loop around Apple Park at running (3 m/s), cycling (6 m/s) or freeway (30 m/s) speed.
        double speed = op.equals("run") ? 3 : op.equals("bike") ? 6 : 30;
        double radius = op.equals("drive") ? 4000 : 800;
        List<double[]> points = new ArrayList<>();
        double circumference = 2 * Math.PI * radius;
        for (int i = 0; i <= 72; i++) {
          double ang = 2 * Math.PI * i / 72;
          double lat = HOME_LAT + radius * Math.cos(ang) / 111_320.0;
          double lng = HOME_LNG + radius * Math.sin(ang) / (111_320.0 * Math.cos(Math.toRadians(HOME_LAT)));
          points.add(new double[] {lat, lng, circumference * i / 72 / speed});
        }
        return start(points);
      }
      case "gpx" -> {
        if (a.length < 3) return "usage: location gpx FILE";
        try {
          return start(readGpx(Path.of(a[2])));
        } catch (java.io.IOException | RuntimeException e) {
          return "cannot read " + a[2] + ": " + e.getMessage();
        }
      }
      default -> {
        // location 51.5007 -0.1246, or "51.5007, -0.1246" as the menu's text field gives it
        String[] parts = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).split("[,\\s]+");
        try {
          double[] point = {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
          route = null;
          fixed = point;
          lastFix = 0;
          tick();
          return "ok";
        } catch (RuntimeException e) {
          return "usage: location LAT LNG | run | bike | drive | gpx FILE | none";
        }
      }
    }
  }

  private String start(List<double[]> points) {
    if (points.size() < 2) return "a route needs at least two points";
    route = points;
    fixed = null;
    routeSeconds = Math.max(1, points.get(points.size() - 1)[2]);
    routeStart = SystemClock.uptimeMillis();
    lastFix = 0;
    tick();
    return String.format(Locale.ROOT, "moving: %d points, %.0f s a loop", points.size(), routeSeconds);
  }

  private void send(Location l) {
    shadow.setLocationEnabled(true);
    for (String p : PROVIDERS) {
      shadow.setProviderEnabled(p, true);
      Location copy = new Location(l);
      copy.setProvider(p);
      shadow.simulateLocation(copy);
    }
  }

  private static Location fix(double lat, double lng) {
    Location l = new Location(LocationManager.GPS_PROVIDER);
    l.setLatitude(lat);
    l.setLongitude(lng);
    l.setAccuracy(5);
    l.setTime(System.currentTimeMillis());
    l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    return l;
  }

  /** Track points with their times (or 1 s apart when the file has none). */
  private static List<double[]> readGpx(Path file) throws java.io.IOException {
    String xml = Files.readString(file);
    Matcher m = Pattern.compile("<trkpt[^>]*lat=\"([-0-9.]+)\"[^>]*lon=\"([-0-9.]+)\"[^>]*>(.*?)</trkpt>|<trkpt[^>]*lon=\"([-0-9.]+)\"[^>]*lat=\"([-0-9.]+)\"[^>]*>(.*?)</trkpt>", Pattern.DOTALL).matcher(xml);
    Pattern time = Pattern.compile("<time>([^<]+)</time>");
    List<double[]> out = new ArrayList<>();
    Long t0 = null;
    while (m.find()) {
      boolean latFirst = m.group(1) != null;
      double lat = Double.parseDouble(latFirst ? m.group(1) : m.group(5));
      double lng = Double.parseDouble(latFirst ? m.group(2) : m.group(4));
      Matcher tm = time.matcher(latFirst ? m.group(3) : m.group(6));
      double seconds = out.size();
      if (tm.find()) {
        long ms = java.time.Instant.parse(tm.group(1)).toEpochMilli();
        if (t0 == null) t0 = ms;
        seconds = (ms - t0) / 1000.0;
      }
      out.add(new double[] {lat, lng, seconds});
    }
    return out;
  }
}
