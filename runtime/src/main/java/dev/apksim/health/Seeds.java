package dev.apksim.health;

import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.BloodPressureRecord;
import android.health.connect.datatypes.BodyFatRecord;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.FloorsClimbedRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.HeightRecord;
import android.health.connect.datatypes.HydrationRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.OxygenSaturationRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.TotalCaloriesBurnedRecord;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.units.Energy;
import android.health.connect.datatypes.units.Length;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.units.Percentage;
import android.health.connect.datatypes.units.Pressure;
import android.health.connect.datatypes.units.Volume;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Records to start with: {@code --health FILE|PRESET} and {@code health add FILE|PRESET|JSON}. A file holds a record,
 * a list, or {@code {"origin": PKG, "records": [...]}}; each record is {@code {"type": ..., ...}} with the fields of
 * its type (docs/health-connect.md lists them). Times are ISO instants, local date-times, or relative to the app's
 * clock ({@code now}, {@code -2h}, {@code -1d-30m}, {@code today 07:30}, {@code yesterday 23:10}, {@code -3d 18:00}).
 * The preset {@code week} is a plausible week up to now: steps, distance and calories by the hour, heart rate every
 * five minutes, a resting heart rate and weight each morning, a night's sleep with stages, and two runs.
 */
final class Seeds {
  static final String PRESETS = "week";

  private Seeds() {}

  static List<RecordInternal<?>> load(String spec, long now) throws Exception {
    String s = spec.trim();
    if (s.startsWith("{") || s.startsWith("[")) return parse(s, now);
    if (s.equals("week")) return internal(week(now), now);
    Path file = Path.of(s);
    if (!file.isAbsolute()) file = Path.of(System.getProperty("user.dir")).resolve(s);
    if (Files.isRegularFile(file)) return parse(Files.readString(file), now);
    throw new java.io.FileNotFoundException(s + " (neither a file nor a preset: " + PRESETS + ")");
  }

  // ---------------------------------------------------------------- record type names

  /** "steps", "heart-rate", "sleep-session": the record class's name, without Record, in kebab case. */
  static String name(Class<? extends Record> c) {
    String n = c.getSimpleName().replaceAll("Record$", "");
    return n.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
  }

  private static final Map<String, String> ALIASES = Map.of("sleep", "sleep-session", "exercise", "exercise-session",
      "active-calories", "active-calories-burned", "total-calories", "total-calories-burned", "calories", "total-calories-burned");

  /** The framework's record type for a name ("steps", "heart-rate", an alias such as "sleep"), or null. */
  static Integer typeId(String name) {
    String n = ALIASES.getOrDefault(name.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
    for (Map.Entry<Integer, Class<? extends Record>> e : HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap().entrySet()) {
      if (name(e.getValue()).equals(n)) return e.getKey();
    }
    return null;
  }

  // ---------------------------------------------------------------- JSON

  static List<RecordInternal<?>> parse(String json, long now) throws Exception {
    Object v = new org.json.JSONTokener(json).nextValue();
    String origin = Health.SEED_ORIGIN;
    JSONArray list;
    if (v instanceof JSONObject o && o.has("records")) {
      origin = o.optString("origin", origin);
      list = o.getJSONArray("records");
    } else if (v instanceof JSONObject o) {
      list = new JSONArray().put(o);
    } else if (v instanceof JSONArray a) {
      list = a;
    } else {
      throw new IllegalArgumentException("expected a JSON object or list");
    }
    List<RecordInternal<?>> out = new ArrayList<>();
    for (int i = 0; i < list.length(); i++) {
      JSONObject o = list.getJSONObject(i);
      try {
        Record r = record(o, o.optString("origin", origin), now);
        out.add(toInternal(r, o.optString("origin", origin), o.has("clientId") ? o.getString("clientId") : null, now));
      } catch (Exception e) {
        throw new IllegalArgumentException("record " + (i + 1) + " (" + o.optString("type") + "): " + e.getMessage(), e);
      }
    }
    return out;
  }

  private static Record record(JSONObject o, String origin, long now) throws Exception {
    String type = o.getString("type");
    Integer id = typeId(type);
    if (id == null) throw new IllegalArgumentException("unknown type " + type + " (health types lists them)");
    String n = name(HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap().get(id));
    Metadata m = metadata(origin, o.has("clientId") ? o.getString("clientId") : null);
    return switch (n) {
      case "steps" -> new StepsRecord.Builder(m, t(o, "start", now), t(o, "end", now), o.getLong("count")).buildWithoutValidation();
      case "distance" -> new DistanceRecord.Builder(m, t(o, "start", now), t(o, "end", now), Length.fromMeters(o.getDouble("meters"))).buildWithoutValidation();
      case "active-calories-burned" -> new ActiveCaloriesBurnedRecord.Builder(m, t(o, "start", now), t(o, "end", now),
          Energy.fromCalories(o.getDouble("kcal") * 1000)).buildWithoutValidation();
      case "total-calories-burned" -> new TotalCaloriesBurnedRecord.Builder(m, t(o, "start", now), t(o, "end", now),
          Energy.fromCalories(o.getDouble("kcal") * 1000)).buildWithoutValidation();
      case "heart-rate" -> {
        List<HeartRateRecord.HeartRateSample> samples = new ArrayList<>();
        Instant start = t(o, "start", now);
        if (o.has("samples")) {
          JSONArray a = o.getJSONArray("samples");
          for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            samples.add(new HeartRateRecord.HeartRateSample(x.getLong("bpm"), t(x, "time", now)));
          }
        } else {
          samples.add(new HeartRateRecord.HeartRateSample(o.getLong("bpm"), start));
        }
        Instant end = o.has("end") ? t(o, "end", now) : samples.get(samples.size() - 1).getTime().plusSeconds(1);
        yield new HeartRateRecord.Builder(m, start, end, samples).buildWithoutValidation();
      }
      case "resting-heart-rate" -> new RestingHeartRateRecord.Builder(m, t(o, "time", now), o.getLong("bpm")).buildWithoutValidation();
      case "weight" -> new WeightRecord.Builder(m, t(o, "time", now), Mass.fromGrams(o.getDouble("kg") * 1000)).buildWithoutValidation();
      case "height" -> new HeightRecord.Builder(m, t(o, "time", now), Length.fromMeters(o.getDouble("meters"))).buildWithoutValidation();
      case "sleep-session" -> {
        SleepSessionRecord.Builder b = new SleepSessionRecord.Builder(m, t(o, "start", now), t(o, "end", now));
        if (o.has("title")) b.setTitle(o.getString("title"));
        if (o.has("stages")) {
          List<SleepSessionRecord.Stage> stages = new ArrayList<>();
          JSONArray a = o.getJSONArray("stages");
          for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            stages.add(new SleepSessionRecord.Stage(t(x, "start", now), t(x, "end", now), constant(SleepSessionRecord.StageType.class, "STAGE_TYPE_", x.get("stage"))));
          }
          b.setStages(stages);
        }
        yield b.buildWithoutValidation();
      }
      case "exercise-session" -> {
        ExerciseSessionRecord.Builder b = new ExerciseSessionRecord.Builder(m, t(o, "start", now), t(o, "end", now),
            constant(ExerciseSessionType.class, "EXERCISE_SESSION_TYPE_", o.opt("exercise") == null ? "other_workout" : o.get("exercise")));
        if (o.has("title")) b.setTitle(o.getString("title"));
        yield b.buildWithoutValidation();
      }
      case "floors-climbed" -> new FloorsClimbedRecord.Builder(m, t(o, "start", now), t(o, "end", now), o.getDouble("floors")).buildWithoutValidation();
      case "hydration" -> new HydrationRecord.Builder(m, t(o, "start", now), t(o, "end", now), Volume.fromLiters(o.getDouble("liters"))).buildWithoutValidation();
      case "oxygen-saturation" -> new OxygenSaturationRecord.Builder(m, t(o, "time", now), Percentage.fromValue(o.getDouble("percent"))).buildWithoutValidation();
      case "blood-pressure" -> new BloodPressureRecord.Builder(m, t(o, "time", now), 0,
          Pressure.fromMillimetersOfMercury(o.getDouble("systolic")), Pressure.fromMillimetersOfMercury(o.getDouble("diastolic")), 0).buildWithoutValidation();
      case "body-fat" -> new BodyFatRecord.Builder(m, t(o, "time", now), Percentage.fromValue(o.getDouble("percent"))).buildWithoutValidation();
      default -> throw new IllegalArgumentException("type " + n + " has no seed format yet (an app can still write and read it)");
    };
  }

  /** A class's int constant by name ("deep" → STAGE_TYPE_SLEEPING_DEEP's value via the short names below) or a number. */
  private static int constant(Class<?> c, String prefix, Object v) throws Exception {
    if (v instanceof Number n) return n.intValue();
    String s = v.toString().toUpperCase(Locale.ROOT).replace('-', '_');
    s = switch (s) {
      case "DEEP", "LIGHT", "REM" -> "SLEEPING_" + s;
      case "OUT_OF_BED", "IN_BED" -> "AWAKE_" + s;
      default -> s;
    };
    try {
      Field f = c.getField(prefix + s);
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) return f.getInt(null);
    } catch (NoSuchFieldException e) {
      // below
    }
    throw new IllegalArgumentException("unknown " + prefix.toLowerCase(Locale.ROOT) + "value " + v);
  }

  // Record builders' build() checks a record is not in the future against the JDK's clock, in framework code that
  // --clock does not move: seeds made "up to now" on a clock ahead of the real one would be refused. Seeds are built
  // without that check (buildWithoutValidation); their own times come from SimClock.

  private static Metadata metadata(String origin, String clientId) {
    Metadata.Builder b = new Metadata.Builder().setDataOrigin(new DataOrigin.Builder().setPackageName(origin).build());
    if (clientId != null) b.setClientRecordId(clientId);
    return b.build();
  }

  private static RecordInternal<?> toInternal(Record r, String origin, String clientId, long now) {
    RecordInternal<?> ri = r.toRecordInternal();
    ri.setPackageName(origin);
    ri.setAppName(Health.SEED_ORIGIN.equals(origin) ? "apksim" : origin);
    // Without a client id, an id from the record's content (before its id and time written are set): the same seed
    // gets the same id at every launch, so seeding it again changes nothing.
    ri.setUuid(new UUID(0, 0));
    ri.setLastModifiedTime(0);
    String key = clientId != null ? clientId : "seed:" + Base64.getEncoder().encodeToString(Store.toBytes(ri));
    ri.setUuid(HealthService.clientUuid(origin, ri.getRecordType(), key));
    ri.setLastModifiedTime(now);
    return ri;
  }

  private static List<RecordInternal<?>> internal(List<Record> rs, long now) {
    List<RecordInternal<?>> out = new ArrayList<>();
    for (Record r : rs) out.add(toInternal(r, Health.SEED_ORIGIN, null, now));
    return out;
  }

  // ---------------------------------------------------------------- times

  private static final Pattern RELATIVE = Pattern.compile("([+-])(\\d+(?:\\.\\d+)?)([dhms])");
  private static final Pattern DAY_TIME = Pattern.compile("(today|yesterday|-\\d+d)\\s+(\\d{1,2}):(\\d{2})");

  private static Instant t(JSONObject o, String key, long now) throws org.json.JSONException {
    if (!o.has(key)) throw new IllegalArgumentException("missing \"" + key + "\"");
    return time(o.getString(key), now);
  }

  static Instant time(String s, long now) {
    String v = s.trim();
    if (v.equals("now")) return Instant.ofEpochMilli(now);
    ZoneId zone = ZoneId.systemDefault();
    Matcher d = DAY_TIME.matcher(v);
    if (d.matches()) {
      LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
      int back = d.group(1).equals("today") ? 0 : d.group(1).equals("yesterday") ? 1 : Integer.parseInt(d.group(1).substring(1, d.group(1).length() - 1));
      return today.minusDays(back).atTime(Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3))).atZone(zone).toInstant();
    }
    if (v.startsWith("-") || v.startsWith("+")) {
      Matcher m = RELATIVE.matcher(v);
      long ms = 0;
      int end = 0;
      while (m.find() && m.start() == end) {
        double n = Double.parseDouble(m.group(2)) * (m.group(1).equals("-") ? -1 : 1);
        ms += (long) (n * switch (m.group(3)) {
          case "d" -> 86_400_000L;
          case "h" -> 3_600_000L;
          case "m" -> 60_000L;
          default -> 1000L;
        });
        end = m.end();
      }
      if (end == v.length() && end > 0) return Instant.ofEpochMilli(now + ms);
    }
    try {
      return Instant.parse(v);
    } catch (RuntimeException e) {
      try {
        return LocalDateTime.parse(v).atZone(zone).toInstant();
      } catch (RuntimeException e2) {
        throw new IllegalArgumentException("not a time: " + s + " (ISO instant, local date-time, now, -2h, today 07:30...)");
      }
    }
  }

  // ---------------------------------------------------------------- the week preset

  /**
   * A week up to [now] in the app's time zone. Every value of a day comes from that day's date alone (a Random per day
   * and block, every value drawn whether or not it is past [now], then cut at it), so a day is the same at every launch
   * and records already seeded keep their ids (Seeds' content ids). Run days are every third day by date.
   */
  static List<Record> week(long now) {
    ZoneId zone = ZoneId.systemDefault();
    Instant end = Instant.ofEpochMilli(now);
    LocalDate today = end.atZone(zone).toLocalDate();
    List<Record> out = new ArrayList<>();
    Metadata m = metadata(Health.SEED_ORIGIN, null);
    for (int back = 6; back >= 0; back--) {
      LocalDate day = today.minusDays(back);
      boolean run = Math.floorMod(day.toEpochDay(), 3) == 0;
      // The night before: about 23:00 to 07:00 with 90-minute cycles of light, deep, light and REM sleep, awake once.
      Random rs = random(day, 1);
      ZonedDateTime bed = day.minusDays(1).atTime(LocalTime.of(22, 40)).plusMinutes(rs.nextInt(50)).atZone(zone);
      ZonedDateTime wake = day.atTime(LocalTime.of(6, 30)).plusMinutes(rs.nextInt(60)).atZone(zone);
      List<SleepSessionRecord.Stage> stages = new ArrayList<>();
      ZonedDateTime at = bed;
      int[] cycle = {SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT, SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP,
          SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT, SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM};
      int[] minutes = {25, 30, 20, 15};
      for (int i = 0; at.isBefore(wake); i++) {
        boolean awake = i == 9;
        ZonedDateTime next = at.plusMinutes(awake ? 5 + rs.nextInt(10) : minutes[i % 4] + rs.nextInt(10));
        stages.add(new SleepSessionRecord.Stage(at.toInstant(), min(next, wake).toInstant(),
            awake ? SleepSessionRecord.StageType.STAGE_TYPE_AWAKE : cycle[i % 4]));
        at = next;
      }
      if (!wake.toInstant().isAfter(end)) {
        out.add(new SleepSessionRecord.Builder(m, bed.toInstant(), wake.toInstant()).setTitle("Night").setStages(stages).buildWithoutValidation());
      }
      Random rm = random(day, 2);
      ZonedDateTime morning = day.atTime(LocalTime.of(7, 5)).atZone(zone);
      int resting = 56 + rm.nextInt(7);
      int grams = 72_000 + rm.nextInt(900);
      if (!morning.toInstant().isAfter(end)) {
        out.add(new RestingHeartRateRecord.Builder(m, morning.toInstant(), resting).buildWithoutValidation());
        out.add(new WeightRecord.Builder(m, morning.toInstant(), Mass.fromGrams(grams)).buildWithoutValidation());
      }
      ZonedDateTime runStart = day.atTime(LocalTime.of(18, 0)).atZone(zone), runEnd = runStart.plusMinutes(45);
      if (run && !runEnd.toInstant().isAfter(end)) {
        out.add(new ExerciseSessionRecord.Builder(m, runStart.toInstant(), runEnd.toInstant(),
            ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING).setTitle("Evening run").buildWithoutValidation());
      }
      Random steps = random(day, 3), heart = random(day, 4);
      int bpm = 58;
      for (int hour = 0; hour < 24; hour++) {
        ZonedDateTime from = day.atTime(hour, 0).atZone(zone), to = from.plusHours(1);
        boolean whole = !to.toInstant().isAfter(end);
        boolean awake = hour >= 7 && hour <= 22;
        boolean running = run && hour == 18;
        long count = running ? 6200 + steps.nextInt(800) : (long) (stepsAt(hour) * (0.6 + steps.nextDouble() * 0.8));
        if (!awake) count = 0;
        if (whole && awake) {
          out.add(new StepsRecord.Builder(m, from.toInstant(), to.toInstant(), count).buildWithoutValidation());
          out.add(new DistanceRecord.Builder(m, from.toInstant(), to.toInstant(), Length.fromMeters(count * (running ? 1.1 : 0.76))).buildWithoutValidation());
          out.add(new ActiveCaloriesBurnedRecord.Builder(m, from.toInstant(), to.toInstant(), Energy.fromCalories(count * 45.0)).buildWithoutValidation());
        }
        if (whole) {
          // A resting 68 kcal an hour plus the active calories of the same steps.
          out.add(new TotalCaloriesBurnedRecord.Builder(m, from.toInstant(), to.toInstant(), Energy.fromCalories(68_000 + count * 45.0)).buildWithoutValidation());
        }
        // Heart rate every five minutes (ten at night), up to now.
        List<HeartRateRecord.HeartRateSample> samples = new ArrayList<>();
        int step = awake ? 5 : 10;
        for (int min = 0; min < 60; min += step) {
          int target = running && min < 45 ? 150 : awake ? 74 : 54;
          bpm += (int) Math.signum(target - bpm) * Math.min(Math.abs(target - bpm), 12) + heart.nextInt(7) - 3;
          bpm = Math.max(45, Math.min(185, bpm));
          Instant t = from.plusMinutes(min).toInstant();
          if (!t.isAfter(end)) samples.add(new HeartRateRecord.HeartRateSample(bpm, t));
        }
        if (!samples.isEmpty()) {
          out.add(new HeartRateRecord.Builder(m, from.toInstant(), samples.get(samples.size() - 1).getTime().plusSeconds(1), samples).buildWithoutValidation());
        }
      }
    }
    return out;
  }

  /** The values of one block ([which]) of a day: the same for that date at every launch. */
  private static Random random(LocalDate day, int which) {
    return new Random(day.toEpochDay() * 31 + which);
  }

  private static ZonedDateTime min(ZonedDateTime a, ZonedDateTime b) {
    return a.isBefore(b) ? a : b;
  }

  /** Steps an ordinary hour has: a walk to work, lunch and an evening stroll. */
  private static int stepsAt(int hour) {
    return switch (hour) {
      case 7, 8 -> 1400;
      case 12, 13 -> 1100;
      case 17, 19 -> 1300;
      case 9, 10, 11, 14, 15, 16 -> 450;
      default -> 250;
    };
  }
}
