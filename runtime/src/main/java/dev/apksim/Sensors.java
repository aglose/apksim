package dev.apksim;

import android.app.Application;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSensorManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The device's sensors, as SensorManager shows them. Robolectric's ShadowSensorManager has none and sends no events; an
 * app that awaits a first accelerometer or light value (a callbackFlow, a suspendCoroutine) then waits for ever, with no
 * error (issue 96's class of bug). Here a device has the sensors an emulator of its kind lists (names, types, ranges
 * and the android.hardware.sensor.* features from a Pixel emulator's sensor service; the AOSP software sensors on top
 * of them: gravity, linear acceleration, rotation vectors, orientation) and delivers events as a device does.
 *
 * <p>State is one physical pose: the gravity vector in the device's frame, which at rest follows the display rotation
 * (portrait upright: 0, 9.81, 0; `rotate left` turns it onto x, as turning a phone does), a rotation rate (gyroscope),
 * a shake in progress, and the ambient light, proximity, pressure, humidity, temperature, step count and heart rate.
 * Every sensor's values are computed from that, so the accelerometer, gravity, magnetometer, orientation and rotation
 * vectors agree with each other.
 *
 * <p>Delivery: each registration (listener, sensor) is a delayed message on its Handler's looper (the main looper
 * without a Handler, as SystemSensorManager does), re-posted after each event at the rate the app asked for
 * (SENSOR_DELAY_* or microseconds, clamped to the sensor's minimum and maximum delay). A continuous sensor delivers
 * once at registration and then every period; an on-change one (light, proximity, pressure, humidity, temperature,
 * step counter, heart rate) delivers its value at registration if it has one and again on each change. Timestamps are
 * SystemClock.elapsedRealtimeNanos at delivery. Nothing is posted while nothing is registered, so there is no thread and
 * no idle cost, and nothing here moves the clock. In a running instance the looper's clock follows real time, so
 * events come at real rates; in a test run it is the simulated clock, which the test (idleFor, Espresso's idle waits,
 * the main looper being run) moves, so events come per unit of simulated time and a test that does not advance the
 * clock sees the first event of each registration (and each on-change event) only. As with any self-repeating message,
 * a test that runs the main queue to its end (ShadowLooper.runToEndOfTasks) while a continuous listener is registered
 * never finishes, so unregister first.
 *
 * <p>Control command: `sensor` (see {@link #command}). A device with a sensor removed (`sensor NAME off`) has no
 * default sensor of that type (getDefaultSensor returns null) and drops the feature. Heart rate needs BODY_SENSORS: a
 * registration without it returns false, as the sensor service refuses it. Not done: SensorDirectChannel, trigger
 * sensors (requestTriggerSensor returns false), dynamic sensors, batching (the latency argument is ignored), data
 * injection, the debug sensors an emulator also lists ("Gyroscope Bias", "Corrected Gyroscope").
 */
public final class Sensors {
  private Sensors() {}

  private static final float G = 9.80665f;
  private static final int CONTINUOUS = 0, ON_CHANGE = 1;
  private static final String AOSP = "The Android Open Source Project";

  /** One sensor a device kind has. */
  private record Spec(String key, String name, String vendor, int version, int type, String stringType, float range,
      float resolution, float power, int minDelayUs, int maxDelayUs, int mode, boolean wakeUp, String permission,
      String feature, int hw) {
    boolean continuous() {
      return mode == CONTINUOUS;
    }
  }

  private static Spec hw(String key, String name, int type, String st, float range, float res, float power, int mode,
      boolean wake, String feature) {
    boolean c = mode == CONTINUOUS;
    return new Spec(key, name, AOSP, 1, type, st, range, res, power, c ? 10_000 : 0, c ? 500_000 : 0, mode, wake, null, feature, 0x10);
  }

  private static Spec soft(String key, String name, int version, int type, String st, float range, float res, float power) {
    return new Spec(key, name, "AOSP", version, type, st, range, res, power, 10_000, 0, CONTINUOUS, false, null, null, 0);
  }

  private static final Spec ACCEL = hw("accelerometer", "Goldfish 3-axis Accelerometer", Sensor.TYPE_ACCELEROMETER, Sensor.STRING_TYPE_ACCELEROMETER, 39.300102f, 2.480159E-4f, 0.001f, CONTINUOUS, false, "android.hardware.sensor.accelerometer");
  private static final Spec GYRO = hw("gyroscope", "Goldfish 3-axis Gyroscope", Sensor.TYPE_GYROSCOPE, Sensor.STRING_TYPE_GYROSCOPE, 16.46f, 0.001f, 0.001f, CONTINUOUS, false, "android.hardware.sensor.gyroscope");
  private static final Spec MAG = hw("magnetometer", "Goldfish 3-axis Magnetic field sensor", Sensor.TYPE_MAGNETIC_FIELD, Sensor.STRING_TYPE_MAGNETIC_FIELD, 2000f, 0.5f, 0.001f, CONTINUOUS, false, "android.hardware.sensor.compass");
  private static final Spec ORIENT_HW = hw("orientation", "Goldfish Orientation sensor", Sensor.TYPE_ORIENTATION, Sensor.STRING_TYPE_ORIENTATION, 360f, 1f, 0.001f, CONTINUOUS, false, null);
  private static final Spec TEMP = hw("temperature", "Goldfish Ambient Temperature sensor", Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.STRING_TYPE_AMBIENT_TEMPERATURE, 80f, 1f, 0.001f, ON_CHANGE, false, "android.hardware.sensor.ambient_temperature");
  private static final Spec PROX = hw("proximity", "Goldfish Proximity sensor", Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_PROXIMITY, 1f, 1f, 0.001f, ON_CHANGE, true, "android.hardware.sensor.proximity");
  private static final Spec LIGHT = hw("light", "Goldfish Light sensor", Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT, 40000f, 1f, 0.001f, ON_CHANGE, false, "android.hardware.sensor.light");
  private static final Spec PRESSURE = hw("pressure", "Goldfish Pressure sensor", Sensor.TYPE_PRESSURE, Sensor.STRING_TYPE_PRESSURE, 800f, 1f, 0.001f, CONTINUOUS, false, "android.hardware.sensor.barometer");
  private static final Spec HUMIDITY = hw("humidity", "Goldfish Humidity sensor", Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.STRING_TYPE_RELATIVE_HUMIDITY, 100f, 1f, 0.001f, ON_CHANGE, false, "android.hardware.sensor.relative_humidity");
  private static final Spec MAG_U = hw("magnetometer-uncalibrated", "Goldfish 3-axis Magnetic field sensor (uncalibrated)", Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.STRING_TYPE_MAGNETIC_FIELD_UNCALIBRATED, 2000f, 0.5f, 0.001f, CONTINUOUS, false, null);
  private static final Spec GYRO_U = hw("gyroscope-uncalibrated", "Goldfish 3-axis Gyroscope (uncalibrated)", Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.STRING_TYPE_GYROSCOPE_UNCALIBRATED, 16.46f, 0.001f, 0.001f, CONTINUOUS, false, null);
  private static final Spec ACCEL_U = hw("accelerometer-uncalibrated", "Goldfish 3-axis Accelerometer Uncalibrated", Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.STRING_TYPE_ACCELEROMETER_UNCALIBRATED, 39.300102f, 2.480159E-4f, 0.001f, CONTINUOUS, false, null);
  private static final Spec GAME_ROT = soft("game-rotation", "Game Rotation Vector Sensor", 3, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.STRING_TYPE_GAME_ROTATION_VECTOR, 1f, 5.9604645E-8f, 0.001f);
  private static final Spec GEO_ROT = soft("geomagnetic-rotation", "GeoMag Rotation Vector Sensor", 3, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, Sensor.STRING_TYPE_GEOMAGNETIC_ROTATION_VECTOR, 1f, 5.9604645E-8f, 0.001f);
  private static final Spec GRAVITY = soft("gravity", "Gravity Sensor", 3, Sensor.TYPE_GRAVITY, Sensor.STRING_TYPE_GRAVITY, 19.6133f, 2.480159E-4f, 0.001f);
  private static final Spec LINEAR = soft("linear-acceleration", "Linear Acceleration Sensor", 3, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.STRING_TYPE_LINEAR_ACCELERATION, 19.6133f, 2.480159E-4f, 0.001f);
  private static final Spec ROT = soft("rotation", "Rotation Vector Sensor", 3, Sensor.TYPE_ROTATION_VECTOR, Sensor.STRING_TYPE_ROTATION_VECTOR, 1f, 5.9604645E-8f, 0.001f);
  private static final Spec ORIENT = soft("orientation-software", "Orientation Sensor", 1, Sensor.TYPE_ORIENTATION, Sensor.STRING_TYPE_ORIENTATION, 360f, 0.00390625f, 0.001f);
  // Not on an emulator (a phone has them): the step sensors and, on a watch, the heart rate.
  private static final Spec STEPS = new Spec("steps", "Step Counter", "apksim", 1, Sensor.TYPE_STEP_COUNTER, Sensor.STRING_TYPE_STEP_COUNTER, 2.14748365E9f, 1f, 0.001f, 0, 0, ON_CHANGE, false, "android.permission.ACTIVITY_RECOGNITION", "android.hardware.sensor.stepcounter", 0);
  private static final Spec STEP_DET = new Spec("step-detector", "Step Detector", "apksim", 1, Sensor.TYPE_STEP_DETECTOR, Sensor.STRING_TYPE_STEP_DETECTOR, 1f, 1f, 0.001f, 0, 0, 3 /* special */, false, "android.permission.ACTIVITY_RECOGNITION", "android.hardware.sensor.stepdetector", 0);
  private static final Spec HEART = new Spec("heart-rate", "Heart Rate", "apksim", 1, Sensor.TYPE_HEART_RATE, Sensor.STRING_TYPE_HEART_RATE, 300f, 1f, 0.001f, 0, 0, ON_CHANGE, false, "android.permission.BODY_SENSORS", "android.hardware.sensor.heartrate", 0);

  private static final Spec[] ALL_SPECS = {ACCEL, GYRO, MAG, ORIENT_HW, TEMP, PROX, LIGHT, PRESSURE, HUMIDITY, MAG_U, GYRO_U, ACCEL_U,
      GAME_ROT, GEO_ROT, GRAVITY, LINEAR, ROT, ORIENT, STEPS, STEP_DET, HEART};

  /** Every sensor feature a device might list: a switch of device kind turns the ones it lacks off. */
  static final List<String> ALL_FEATURES = List.of("android.hardware.sensor.accelerometer", "android.hardware.sensor.gyroscope",
      "android.hardware.sensor.compass", "android.hardware.sensor.ambient_temperature", "android.hardware.sensor.proximity",
      "android.hardware.sensor.light", "android.hardware.sensor.barometer", "android.hardware.sensor.relative_humidity",
      "android.hardware.sensor.stepcounter", "android.hardware.sensor.stepdetector", "android.hardware.sensor.heartrate");

  /** The sensors a device of this kind has (before any `sensor NAME off`). */
  private static List<Spec> kindSpecs(Device d) {
    List<Spec> l = new ArrayList<>();
    if (d.tv()) return l;  // an Android TV emulator definition has no sensors
    boolean watch = d.watch();
    boolean desktop = d.kind().equals("desktop");
    l.add(ACCEL);
    l.add(GYRO);
    l.add(MAG);
    l.add(ORIENT_HW);
    if (!watch && !desktop) {
      l.add(TEMP);
      l.add(PROX);
    }
    l.add(LIGHT);
    if (!desktop) l.add(PRESSURE);
    if (!watch && !desktop) l.add(HUMIDITY);
    l.add(MAG_U);
    l.add(GYRO_U);
    l.add(ACCEL_U);
    l.add(GAME_ROT);
    l.add(GEO_ROT);
    l.add(GRAVITY);
    l.add(LINEAR);
    l.add(ROT);
    l.add(ORIENT);
    if (!desktop) {
      l.add(STEPS);
      l.add(STEP_DET);
    }
    if (watch) l.add(HEART);
    return l;
  }

  // ---- state ----------------------------------------------------------------------------------------------------

  private static final Object LOCK = new Object();
  private static final CopyOnWriteArrayList<Reg> REGS = new CopyOnWriteArrayList<>();
  /** The sensors the device has now, with their Sensor objects. Guarded by LOCK. */
  private static final Map<Spec, Sensor> SENSORS = new LinkedHashMap<>();
  private static final Set<String> REMOVED = new LinkedHashSet<>();  // keys switched off with `sensor NAME off`
  private static final Map<String, java.util.concurrent.atomic.AtomicLong> SENT = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<String, float[]> LAST = new java.util.concurrent.ConcurrentHashMap<>();
  private static volatile Device device;

  private static volatile float[] gravity;  // null: at rest, from the display rotation
  private static volatile float[] gyro = new float[3];
  private static volatile long shakeStartNs = -1, shakeNs;
  private static volatile float light, proximity, pressure, humidity, temperature, steps;
  private static volatile float heartRate = Float.NaN;  // NaN: no reading (`heart-rate off`)

  private static void defaults() {
    gravity = null;
    gyro = new float[3];
    shakeStartNs = -1;
    // What the emulator's sensors report until told otherwise (its temperature sensor reports -1e28: 20 C here).
    light = 0f;
    proximity = 1f;
    pressure = 1013.25f;
    humidity = 0f;
    temperature = 20f;
    steps = 0f;
    heartRate = Float.NaN;
  }

  static {
    defaults();
  }

  private static Application app() {
    return org.robolectric.RuntimeEnvironment.getApplication();
  }

  // ---- device set up ---------------------------------------------------------------------------------------------

  /** Before Application.onCreate (DeviceIdentity): the launch device's sensors and the --sensor values. */
  static void beforeApplicationOnCreate(Application app) {
    reset();
    device = Device.peek();
    rebuild();
    launchOptions();
  }

  /** The `device` command switched kinds. */
  static void deviceChanged(Application app, Device d) {
    device = d;
    rebuild();
    for (Reg r : REGS) if (specOf(r.sensor) == null) r.stop();  // a sensor the new kind lacks stops delivering
  }

  /** Between tests (SimTestEnvironment.resetState): nothing registered, the launch's state follows at the next setup. */
  static void reset() {
    for (Reg r : REGS) r.stop();
    REGS.clear();
    synchronized (LOCK) {
      SENSORS.clear();
      REMOVED.clear();
    }
    SENT.clear();
    LAST.clear();
    defaults();
  }

  /** The features of the sensors the device has now (SystemFeatures). */
  static Set<String> features() {
    Set<String> out = new LinkedHashSet<>();
    synchronized (LOCK) {
      for (Spec s : specs()) if (s.feature() != null) out.add(s.feature());
    }
    return out;
  }

  private static List<Spec> specs() {
    synchronized (LOCK) {
      if (SENSORS.isEmpty() && device == null) {
        device = Device.peek();
        rebuild();
      }
      return new ArrayList<>(SENSORS.keySet());
    }
  }

  private static void rebuild() {
    synchronized (LOCK) {
      Map<Spec, Sensor> old = new LinkedHashMap<>(SENSORS);
      SENSORS.clear();
      int handle = 1;
      for (Spec s : kindSpecs(device == null ? Device.peek() : device)) {
        int h = handle++;
        if (REMOVED.contains(s.key())) continue;
        Sensor prev = old.get(s);
        SENSORS.put(s, prev != null ? prev : create(s, h));
      }
    }
  }

  private static Sensor create(Spec s, int handle) {
    Sensor o = ReflectionHelpers.callConstructor(Sensor.class);
    ReflectionHelpers.setField(o, "mName", s.name());
    ReflectionHelpers.setField(o, "mVendor", s.vendor());
    ReflectionHelpers.setField(o, "mVersion", s.version());
    ReflectionHelpers.setField(o, "mHandle", handle);
    ReflectionHelpers.setField(o, "mType", s.type());
    ReflectionHelpers.setField(o, "mMaxRange", s.range());
    ReflectionHelpers.setField(o, "mResolution", s.resolution());
    ReflectionHelpers.setField(o, "mPower", s.power());
    ReflectionHelpers.setField(o, "mMinDelay", s.minDelayUs());
    ReflectionHelpers.setField(o, "mMaxDelay", s.maxDelayUs());
    ReflectionHelpers.setField(o, "mStringType", s.stringType());
    ReflectionHelpers.setField(o, "mRequiredPermission", s.permission() == null ? "" : s.permission());
    ReflectionHelpers.setField(o, "mFlags", (s.mode() << 1) | (s.wakeUp() ? 1 : 0) | s.hw());
    ReflectionHelpers.setField(o, "mId", handle);
    ReflectionHelpers.setField(o, "mFifoReservedEventCount", 0);
    ReflectionHelpers.setField(o, "mFifoMaxEventCount", 0);
    return o;
  }

  private static Spec specOf(Sensor sensor) {
    synchronized (LOCK) {
      for (Map.Entry<Spec, Sensor> e : SENSORS.entrySet()) if (e.getValue() == sensor || e.getValue().equals(sensor)) return e.getKey();
    }
    return null;
  }

  // ---- values ----------------------------------------------------------------------------------------------------

  /** At rest: gravity on the axis the display rotation gives (a phone upright in portrait: +y). */
  private static float[] restGravity() {
    int rot = 0;
    try {
      rot = app().getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation();
    } catch (RuntimeException e) {
      // no display yet: portrait
    }
    return switch (rot) {
      case 1 -> new float[] {G, 0, 0};
      case 2 -> new float[] {0, -G, 0};
      case 3 -> new float[] {-G, 0, 0};
      default -> new float[] {0, G, 0};
    };
  }

  private static float[] gravityNow() {
    float[] g = gravity;
    return g != null ? g : restGravity();
  }

  /** What a shake adds to the acceleration at time ns: a decaying 8 Hz oscillation, mostly in x and y. */
  private static float[] shakeAt(long ns) {
    long start = shakeStartNs;
    if (start < 0 || ns < start || ns - start > shakeNs) return new float[3];
    double t = (ns - start) / 1e9;
    double env = 1.0 - (ns - start) / (double) shakeNs;
    float s = (float) (20.0 * env * Math.sin(2 * Math.PI * 8 * t));
    return new float[] {s, 0.6f * s, 0.3f * s};
  }

  /** The earth's field in the device's frame: 47.75 uT horizontal and 9.88 vertical, as the emulator's, from gravity. */
  private static float[] field(float[] g) {
    double gn = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
    if (gn < 1e-3) return new float[] {0, 0, 0};
    double ux = g[0] / gn, uy = g[1] / gn, uz = g[2] / gn;  // up
    // north: -z projected onto the horizontal plane (+y when the device lies flat)
    double nx = 0 - (-uz) * ux, ny = 0 - (-uz) * uy, nz = -1 - (-uz) * uz;
    double nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (nn < 1e-3) {
      nx = 0;
      ny = 1;
      nz = 0;
    } else {
      nx /= nn;
      ny /= nn;
      nz /= nn;
    }
    return new float[] {(float) (47.75 * nx + 9.88 * ux), (float) (47.75 * ny + 9.88 * uy), (float) (47.75 * nz + 9.88 * uz)};
  }

  private static float[] rotationMatrix(float[] g, float[] m) {
    float[] r = new float[9];
    if (!SensorManager.getRotationMatrix(r, null, g, m)) return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    return r;
  }

  private static float[] quaternion(float[] r) {
    double t = r[0] + r[4] + r[8];
    double x, y, z, w;
    if (t > 0) {
      double s = Math.sqrt(t + 1) * 2;
      w = 0.25 * s;
      x = (r[7] - r[5]) / s;
      y = (r[2] - r[6]) / s;
      z = (r[3] - r[1]) / s;
    } else if (r[0] > r[4] && r[0] > r[8]) {
      double s = Math.sqrt(1 + r[0] - r[4] - r[8]) * 2;
      w = (r[7] - r[5]) / s;
      x = 0.25 * s;
      y = (r[1] + r[3]) / s;
      z = (r[2] + r[6]) / s;
    } else if (r[4] > r[8]) {
      double s = Math.sqrt(1 + r[4] - r[0] - r[8]) * 2;
      w = (r[2] - r[6]) / s;
      x = (r[1] + r[3]) / s;
      y = 0.25 * s;
      z = (r[5] + r[7]) / s;
    } else {
      double s = Math.sqrt(1 + r[8] - r[0] - r[4]) * 2;
      w = (r[3] - r[1]) / s;
      x = (r[2] + r[6]) / s;
      y = (r[5] + r[7]) / s;
      z = 0.25 * s;
    }
    if (w < 0) {  // the vector part carries the sign
      x = -x;
      y = -y;
      z = -z;
      w = -w;
    }
    return new float[] {(float) x, (float) y, (float) z, (float) w};
  }

  /** The values a sensor reports at time ns, or null when it has none (a heart rate with no reading). */
  private static float[] read(Spec s, long ns) {
    float[] g = gravityNow();
    float[] sh = shakeAt(ns);
    float[] a = {g[0] + sh[0], g[1] + sh[1], g[2] + sh[2]};
    switch (s.type()) {
      case 1: /* ACCELEROMETER */
        return a;
      case 35: /* ACCELEROMETER_UNCALIBRATED */
        return new float[] {a[0], a[1], a[2], 0, 0, 0};
      case 9: /* GRAVITY */
        return g.clone();
      case 10: /* LINEAR_ACCELERATION */
        return sh;
      case 4: /* GYROSCOPE */
        return gyro.clone();
      case 16: /* GYROSCOPE_UNCALIBRATED */ {
        float[] y = gyro;
        return new float[] {y[0], y[1], y[2], 0, 0, 0};
      }
      case 2: /* MAGNETIC_FIELD */
        return field(g);
      case 14: /* MAGNETIC_FIELD_UNCALIBRATED */ {
        float[] m = field(g);
        return new float[] {m[0], m[1], m[2], 0, 0, 0};
      }
      case 3: /* ORIENTATION */ {
        float[] o = new float[3];
        SensorManager.getOrientation(rotationMatrix(g, field(g)), o);
        // degrees; azimuth 0..360 as the deprecated sensor reports it
        float az = (float) Math.toDegrees(o[0]);
        return new float[] {az < 0 ? az + 360 : az, (float) Math.toDegrees(o[1]), (float) Math.toDegrees(o[2])};
      }
      case 11: /* ROTATION_VECTOR */
      case 20: /* GEOMAGNETIC_ROTATION_VECTOR */
      case 15: /* GAME_ROTATION_VECTOR */ {
        float[] q = quaternion(rotationMatrix(g, field(g)));
        return s.type() == Sensor.TYPE_GAME_ROTATION_VECTOR ? q : new float[] {q[0], q[1], q[2], q[3], 0};
      }
      case 5: /* LIGHT */
        return new float[] {light};
      case 8: /* PROXIMITY */
        return new float[] {proximity};
      case 6: /* PRESSURE */
        return new float[] {pressure};
      case 12: /* RELATIVE_HUMIDITY */
        return new float[] {humidity};
      case 13: /* AMBIENT_TEMPERATURE */
        return new float[] {temperature};
      case 19: /* STEP_COUNTER */
        return new float[] {steps};
      case 18: /* STEP_DETECTOR */
        return new float[] {1};
      case 21: /* HEART_RATE */
        return Float.isNaN(heartRate) ? null : new float[] {heartRate};
      default:
        return null;
    }
  }

  // ---- delivery --------------------------------------------------------------------------------------------------

  private static final class Reg implements Runnable {
    final SensorEventListener listener;
    final Sensor sensor;
    final Spec spec;
    final Handler handler;
    final long periodNs;
    volatile boolean active = true;
    long nextNs;

    Reg(SensorEventListener l, Sensor sensor, Spec spec, Handler h, long periodNs) {
      this.listener = l;
      this.sensor = sensor;
      this.spec = spec;
      this.handler = h;
      this.periodNs = periodNs;
    }

    void stop() {
      active = false;
      handler.removeCallbacksAndMessages(this);
    }

    void start() {
      nextNs = SystemClock.elapsedRealtimeNanos();
      handler.postDelayed(this, this, 0);
    }

    /** The periodic tick (a continuous sensor) or the registration's first delivery. */
    @Override
    public void run() {
      if (!active) return;
      long now = SystemClock.elapsedRealtimeNanos();
      if (spec.continuous()) {
        // Next first: a listener that throws must not end the stream, and one that unregisters removes it.
        nextNs += periodNs;
        if (nextNs < now) nextNs = now;  // late (a busy looper, a clock that jumped): drop events, as a device does
        long delayMs = Math.max(1, (nextNs - now) / 1_000_000L);
        handler.postDelayed(this, this, delayMs);
      }
      deliver(listener, sensor, spec, now);
    }
  }

  private static void deliver(SensorEventListener l, Sensor sensor, Spec spec, long ns) {
    float[] v = read(spec, ns);
    if (v == null) return;
    SensorEvent e = ReflectionHelpers.callConstructor(SensorEvent.class, ClassParameter.from(int.class, v.length));
    System.arraycopy(v, 0, e.values, 0, v.length);
    e.sensor = sensor;
    e.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    e.timestamp = ns;
    SENT.computeIfAbsent(spec.key(), k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    LAST.put(spec.key(), v);
    l.onSensorChanged(e);
  }

  /** The sensors with this type's value changed (on-change ones tell their listeners now). */
  private static void changed(Spec spec) {
    for (Reg r : REGS) {
      if (r.spec == spec && r.active && spec.mode() != CONTINUOUS) {
        r.handler.post(() -> {
          if (r.active) deliver(r.listener, r.sensor, r.spec, SystemClock.elapsedRealtimeNanos());
        });
      }
    }
  }

  private static Spec byType(int type) {
    for (Spec s : specs()) if (s.type() == type) return s;
    return null;
  }

  static boolean register(SensorEventListener l, Sensor sensor, int rate, Handler handler) {
    if (l == null || sensor == null) return false;
    Spec spec = specOf(sensor);
    if (spec == null) return false;  // not this device's (removed with `sensor NAME off`)
    if (spec.permission() != null && !permitted(spec.permission())) return false;  // the sensor service refuses it
    // SENSOR_DELAY_FASTEST 0, GAME 1 (20 ms), UI 2 (66.7 ms), NORMAL 3 (200 ms), else microseconds.
    long us = switch (rate) {
      case 0 -> 0;
      case 1 -> 20_000;
      case 2 -> 66_667;
      case 3 -> 200_000;
      default -> Math.max(0, rate);
    };
    int min = spec.minDelayUs() > 0 ? spec.minDelayUs() : 10_000;
    long period = Math.max(min, us);
    if (spec.maxDelayUs() > 0) period = Math.min(period, spec.maxDelayUs());
    if (handler == null) handler = new Handler(Looper.getMainLooper());
    // As SensorEventQueue.addSensor: a listener already registered for the sensor is refused, and keeps its first rate.
    for (Reg r : REGS) if (r.listener == l && r.spec == spec) return false;
    Reg r = new Reg(l, sensor, spec, handler, period * 1000L);
    REGS.add(r);
    if (spec.mode() == 3) return true;  // step detector: events only when a step happens
    r.start();
    return true;
  }

  /** A runtime permission the sensor needs; ACTIVITY_RECOGNITION only binds apps targeting 29 and up. */
  private static boolean permitted(String permission) {
    Application app = app();
    if (permission.equals("android.permission.ACTIVITY_RECOGNITION") && app.getApplicationInfo().targetSdkVersion < 29) return true;
    return app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  static void unregister(SensorEventListener l, Sensor sensor) {
    for (Reg r : REGS) {
      if (r.listener == l && (sensor == null || r.sensor == sensor || r.sensor.equals(sensor))) {
        r.stop();
        REGS.remove(r);
      }
    }
  }

  // ---- the shadow ------------------------------------------------------------------------------------------------

  @Implements(SensorManager.class)
  public static class ShadowSimSensorManager extends ShadowSensorManager {
    @Implementation
    @Override
    protected Sensor getDefaultSensor(int type) {
      for (Spec s : specs()) {
        if (s.type() == type) return sensorOf(s);
      }
      return null;
    }

    @Implementation
    @Override
    public List<Sensor> getSensorList(int type) {
      List<Sensor> out = new ArrayList<>();
      for (Spec s : specs()) if (type == Sensor.TYPE_ALL || s.type() == type) out.add(sensorOf(s));
      return out;
    }

    @Implementation
    @Override
    protected boolean registerListener(SensorEventListener l, Sensor sensor, int rate, Handler handler) {
      return register(l, sensor, rate, handler);
    }

    @Implementation
    @Override
    protected boolean registerListener(SensorEventListener l, Sensor sensor, int rate, int maxLatency) {
      return register(l, sensor, rate, null);
    }

    @Implementation
    @Override
    protected boolean registerListener(SensorEventListener l, Sensor sensor, int rate, int maxLatency, Handler handler) {
      return register(l, sensor, rate, handler);
    }

    @Implementation
    @Override
    protected boolean registerListener(SensorEventListener l, Sensor sensor, int rate) {
      return register(l, sensor, rate, null);
    }

    @Implementation
    @Override
    protected void unregisterListener(SensorEventListener l, Sensor sensor) {
      unregister(l, sensor);
    }

    @Implementation
    @Override
    protected void unregisterListener(SensorEventListener l) {
      unregister(l, null);
    }

    @Implementation
    @Override
    public boolean hasListener(SensorEventListener l) {
      for (Reg r : REGS) if (r.listener == l) return true;
      return false;
    }

    @Implementation
    @Override
    public boolean hasListener(SensorEventListener l, Sensor sensor) {
      for (Reg r : REGS) if (r.listener == l && r.sensor == sensor) return true;
      return false;
    }

    @Implementation
    @Override
    protected boolean flush(SensorEventListener l) {
      if (!(l instanceof SensorEventListener2 l2)) return false;
      boolean any = false;
      for (Reg r : REGS) {
        if (r.listener == l) {
          any = true;
          Sensor s = r.sensor;
          r.handler.post(() -> l2.onFlushCompleted(s));
        }
      }
      return any;
    }
  }

  private static Sensor sensorOf(Spec s) {
    synchronized (LOCK) {
      return SENSORS.get(s);
    }
  }

  // ---- commands --------------------------------------------------------------------------------------------------

  private static final Map<String, String> ALIASES = new LinkedHashMap<>();

  static {
    String[][] al = {
        {"accel", "accelerometer"}, {"accelerometer", "accelerometer"}, {"gyro", "gyroscope"}, {"gyroscope", "gyroscope"},
        {"mag", "magnetometer"}, {"magnetometer", "magnetometer"}, {"compass", "magnetometer"}, {"light", "light"},
        {"proximity", "proximity"}, {"pressure", "pressure"}, {"barometer", "pressure"}, {"humidity", "humidity"},
        {"temperature", "temperature"}, {"temp", "temperature"}, {"steps", "steps"}, {"step-counter", "steps"},
        {"step-detector", "step-detector"}, {"heart-rate", "heart-rate"}, {"heartrate", "heart-rate"}, {"gravity", "gravity"},
        {"linear-accel", "linear-acceleration"}, {"linear-acceleration", "linear-acceleration"}, {"rotation", "rotation"},
        {"rotation-vector", "rotation"}, {"game-rotation", "game-rotation"}, {"orientation", "orientation"},
    };
    for (String[] p : al) ALIASES.put(p[0], p[1]);
  }

  private static final String USAGE = "error: usage: sensor [list | accel X Y Z | gyro X Y Z | tilt PITCH ROLL | shake [MS] | light LUX | proximity near|far|CM"
      + " | pressure HPA | humidity PCT | temperature C | steps N|+N | heart-rate BPM|off | reset | NAME on|off]";

  /** `--sensor NAME=VALUE`: -Dapksim.sensors=name=v,name=v (accel and gyro take x:y:z, tilt pitch:roll). */
  private static void launchOptions() {
    String list = System.getProperty("apksim.sensors");
    if (list == null || list.isBlank()) return;
    for (String kv : list.split(",")) {
      int eq = kv.indexOf('=');
      if (eq <= 0) continue;
      String name = kv.substring(0, eq).trim();
      String[] v = kv.substring(eq + 1).trim().split("[:;]");
      String[] cmd = new String[v.length + 2];
      cmd[0] = "sensor";
      cmd[1] = name;
      System.arraycopy(v, 0, cmd, 2, v.length);
      String r = command(cmd);
      if (r.startsWith("error")) System.err.println("[apksim] --sensor " + kv + ": " + r);
    }
  }

  private static float num(String s) {
    return Float.parseFloat(s);
  }

  /**
   * sensor [list]: the sensors, their values and listeners. Setters: accel X Y Z (m/s2, gravity included), gyro X Y Z
   * (rad/s), tilt PITCH ROLL (degrees; the device flat is 0 0, upright portrait -90 0), shake [MS], light LUX, proximity
   * near|far|CM, pressure HPA, humidity PCT, temperature C, steps N|+N, heart-rate BPM|off, reset (at rest, from the
   * display rotation, the launch's values), NAME off|on (removes the sensor from the device).
   */
  public static String command(String[] a) {
    if (a.length < 2 || a[1].equals("list")) return describe();
    String cmd = a[1].toLowerCase(Locale.ROOT);
    String key = ALIASES.get(cmd);
    if (key != null && a.length == 3 && (a[2].equals("on") || a[2].equals("off"))) {
      synchronized (LOCK) {
        if (a[2].equals("off")) REMOVED.add(key); else REMOVED.remove(key);
      }
      // A sensor taken away stops its listeners' events (they stay registered, as on hot-unplug).
      rebuild();
      for (Reg r : REGS) if (specOf(r.sensor) == null) r.active = false;
      refreshFeatures();
      return key + " " + a[2];
    }
    try {
      switch (cmd) {
        case "accel", "accelerometer" -> {
          if (a.length == 5) {
            gravity = new float[] {num(a[2]), num(a[3]), num(a[4])};
            return "accel " + fmt(gravity);
          }
        }
        case "gyro", "gyroscope" -> {
          if (a.length == 5) {
            gyro = new float[] {num(a[2]), num(a[3]), num(a[4])};
            return "gyro " + fmt(gyro);
          }
        }
        case "tilt" -> {
          if (a.length == 4) {
            double p = Math.toRadians(num(a[2])), r = Math.toRadians(num(a[3]));
            gravity = new float[] {(float) (-G * Math.sin(r) * Math.cos(p)), (float) (-G * Math.sin(p)), (float) (G * Math.cos(r) * Math.cos(p))};
            return "tilt " + fmt(gravity);
          }
        }
        case "shake" -> {
          long ms = a.length > 2 ? (long) num(a[2]) : 700;
          shakeNs = ms * 1_000_000L;
          shakeStartNs = SystemClock.elapsedRealtimeNanos();
          return "shake " + ms + " ms";
        }
        case "light" -> {
          if (a.length == 3) return set(LIGHT, v -> light = v, num(a[2]));
        }
        case "pressure" -> {
          if (a.length == 3) return set(PRESSURE, v -> pressure = v, num(a[2]));
        }
        case "humidity" -> {
          if (a.length == 3) return set(HUMIDITY, v -> humidity = v, num(a[2]));
        }
        case "temperature", "temp" -> {
          if (a.length == 3) return set(TEMP, v -> temperature = v, num(a[2]));
        }
        case "proximity" -> {
          if (a.length == 3) {
            Spec s = byType(Sensor.TYPE_PROXIMITY);
            float max = s == null ? 1f : s.range();
            float v = a[2].equals("near") ? 0f : a[2].equals("far") ? max : num(a[2]);
            return set(PROX, x -> proximity = x, v);
          }
        }
        case "steps" -> {
          if (a.length == 3) {
            float old = steps;
            boolean rel = a[2].startsWith("+");
            float n = num(rel ? a[2].substring(1) : a[2]);
            float now = rel ? old + n : n;
            steps = now;
            changed(STEPS);
            Spec det = byType(Sensor.TYPE_STEP_DETECTOR);
            if (det != null && now > old) stepEvents(det, (int) Math.min(100, now - old));
            return "steps " + (long) now;
          }
        }
        case "heart-rate", "heartrate" -> {
          if (a.length == 3) {
            heartRate = a[2].equals("off") ? Float.NaN : num(a[2]);
            if (!Float.isNaN(heartRate)) changed(HEART);
            return "heart-rate " + (Float.isNaN(heartRate) ? "off" : fmt(new float[] {heartRate}));
          }
        }
        case "reset" -> {
          defaults();
          synchronized (LOCK) {
            REMOVED.clear();
          }
          rebuild();
          refreshFeatures();
          return "sensors reset";
        }
        default -> {
        }
      }
    } catch (NumberFormatException e) {
      return USAGE;
    }
    return USAGE;
  }

  private interface FloatSetter {
    void set(float v);
  }

  private static String set(Spec spec, FloatSetter setter, float v) {
    Spec present = null;
    for (Spec s : specs()) if (s.key().equals(spec.key())) present = s;
    float[] before = present == null ? null : read(present, 0);
    setter.set(v);
    if (present != null && (before == null || before[0] != v)) changed(present);
    return spec.key() + " " + fmt(new float[] {v}) + (present == null ? " (this device has no such sensor)" : "");
  }

  /** The step detector fires once per step: n events to its listeners. */
  private static void stepEvents(Spec det, int n) {
    for (Reg r : REGS) {
      if (r.spec == det && r.active) {
        for (int i = 0; i < n; i++) r.handler.post(() -> {
          if (r.active) deliver(r.listener, r.sensor, r.spec, SystemClock.elapsedRealtimeNanos());
        });
      }
    }
  }

  private static void refreshFeatures() {
    try {
      SystemFeatures.sensorsChanged(app());
    } catch (RuntimeException e) {
      // no application yet
    }
  }

  private static String fmt(float[] v) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < v.length; i++) b.append(i > 0 ? " " : "").append(String.format(Locale.ROOT, "%.2f", v[i] == 0 ? 0f : v[i]));
    return b.toString();
  }

  private static String describe() {
    StringBuilder b = new StringBuilder();
    List<Spec> specs = specs();
    long sentAll = 0;
    for (var c : SENT.values()) sentAll += c.get();
    b.append("sensors: ").append(specs.size()).append(", listeners: ").append(REGS.size()).append(", events: ").append(sentAll);
    long now = SystemClock.elapsedRealtimeNanos();
    for (Spec s : specs) {
      float[] v = read(s, now);
      int n = 0;
      long periodNs = 0;
      for (Reg r : REGS) {
        if (r.spec == s && r.active) {
          n++;
          periodNs = periodNs == 0 ? r.periodNs : Math.min(periodNs, r.periodNs);
        }
      }
      var sent = SENT.get(s.key());
      b.append('\n').append(s.key()).append(": ").append(v == null ? "no reading" : fmt(v)).append("  [").append(s.name()).append(", type ")
          .append(s.type()).append(", ").append(s.continuous() ? "continuous" : s.mode() == ON_CHANGE ? "on-change" : "special")
          .append(", ").append(n).append(" listener").append(n == 1 ? "" : "s");
      if (n > 0 && s.continuous()) b.append(" at ").append(String.format(Locale.ROOT, "%.1f", periodNs / 1e6)).append(" ms");
      b.append(", ").append(sent == null ? 0 : sent.get()).append(" events]");
    }
    if (!REMOVED.isEmpty()) b.append("\nremoved: ").append(String.join(", ", REMOVED));
    return b.toString();
  }
}
