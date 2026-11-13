package dev.apksim.health;

import android.health.connect.AggregateResult;
import android.health.connect.datatypes.AggregationType;
import android.health.connect.datatypes.ExerciseSegment;
import android.health.connect.datatypes.ExerciseSegmentType;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.InstantRecord;
import android.health.connect.datatypes.IntervalRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.AggregationTypeIdMapper;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Aggregations as the platform's service computes them, over the store's records, for every aggregation the framework
 * defines, found by reflection rather than listed: an AggregationType names its record type, its operation (SUM, AVG,
 * MIN, MAX, COUNT) and its result class (Long, Double, a unit such as Energy or Mass); the value it reads is the record's
 * getter returning that class (by the aggregation's name when there are several, as NutritionRecord's), or its
 * samples' (HeartRateRecord, SpeedRecord...). A sum over interval records counts the part of each record inside the
 * window (the platform's proportional split: 1,000 steps over an hour, half of it inside, count 500); averages, minimums
 * and maximums take the instant records and samples inside the window. Session durations (sleep, exercise,
 * mindfulness) are the sessions' time inside the window less the stages and segments the framework's
 * DURATION_EXCLUDE_TYPES name (awake, pauses, rest). What has no getter to read (basal calories from a metabolic rate)
 * and activity intensity's totals answer no value, as an aggregation over no data does; so does a nutrient no record
 * set. Records are converted once per request ({@link #prepare}), not once per group.
 */
final class Aggregates {
  private Aggregates() {}

  /** One aggregation: what it reads and how it combines. */
  private record Spec(int id, String name, int recordType, int op, Class<?> result) {}

  private static Map<Integer, Spec> specs;

  private static synchronized Map<Integer, Spec> specs() {
    if (specs != null) return specs;
    Map<Integer, Spec> out = new HashMap<>();
    AggregationTypeIdMapper mapper = AggregationTypeIdMapper.getInstance();
    for (Class<? extends Record> c : HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap().values()) {
      for (Field f : c.getFields()) {
        if (!Modifier.isStatic(f.getModifiers()) || f.getType() != AggregationType.class) continue;
        try {
          AggregationType<?> t = (AggregationType<?>) f.get(null);
          int id = mapper.getIdFor(t);
          out.put(id, new Spec(id, f.getName(), t.getApplicableRecordTypeId(), t.getAggregateOperationType(), t.getAggregateResultClass()));
        } catch (ReflectiveOperationException | RuntimeException e) {
          // a field the mapper does not know: not an aggregation an app can ask for
        }
      }
    }
    specs = out;
    return out;
  }

  /** The record type aggregation [id] reads, or -1. */
  static int recordType(int id) {
    Spec s = specs().get(id);
    return s == null ? -1 : s.recordType();
  }

  /** The window: [start, end) in epoch ms, or in local ms (an instant plus its zone offset) when [local]. */
  record Window(long start, long end, boolean local) {
    long at(Instant t, ZoneOffset z) {
      long ms = t.toEpochMilli();
      return local && z != null ? ms + z.getTotalSeconds() * 1000L : ms;
    }
  }

  /**
   * A record as aggregations read it, converted once per request: its external form, times and offsets, and its
   * samples (a series record) or the parts its duration leaves out (a sleep session's awake stages, an exercise
   * session's pauses and rests: the types the framework lists in DURATION_EXCLUDE_TYPES).
   */
  static final class Item {
    final RecordInternal<?> internal;
    final Record record;
    final long start, end;
    final ZoneOffset startZone, endZone;
    final List<?> samples;
    final long[][] excluded;

    Item(RecordInternal<?> ri) {
      internal = ri;
      record = ri.toExternalRecord();
      if (record instanceof IntervalRecord iv) {
        start = iv.getStartTime().toEpochMilli();
        end = iv.getEndTime().toEpochMilli();
        startZone = iv.getStartZoneOffset();
        endZone = iv.getEndZoneOffset();
      } else {
        InstantRecord in = (InstantRecord) record;
        start = end = in.getTime().toEpochMilli();
        startZone = endZone = in.getZoneOffset();
      }
      samples = samples(record);
      excluded = excludedParts(record);
    }

    long start(Window w) {
      return w.local() && startZone != null ? start + startZone.getTotalSeconds() * 1000L : start;
    }

    long end(Window w) {
      return w.local() && endZone != null ? end + endZone.getTotalSeconds() * 1000L : end;
    }
  }

  static List<Item> prepare(List<RecordInternal<?>> records) {
    List<Item> out = new ArrayList<>(records.size());
    for (RecordInternal<?> r : records) out.add(new Item(r));
    return out;
  }

  /** Parts of a session its duration leaves out, as [start, end] pairs in epoch ms. */
  private static long[][] excludedParts(Record r) {
    List<long[]> out = new ArrayList<>();
    if (r instanceof SleepSessionRecord s) {
      for (SleepSessionRecord.Stage st : s.getStages()) {
        if (SleepSessionRecord.StageType.DURATION_EXCLUDE_TYPES.contains(st.getType())) {
          out.add(new long[] {st.getStartTime().toEpochMilli(), st.getEndTime().toEpochMilli()});
        }
      }
    } else if (r instanceof ExerciseSessionRecord e) {
      for (ExerciseSegment sg : e.getSegments()) {
        if (ExerciseSegmentType.DURATION_EXCLUDE_TYPES.contains(sg.getSegmentType())) {
          out.add(new long[] {sg.getStartTime().toEpochMilli(), sg.getEndTime().toEpochMilli()});
        }
      }
    }
    return out.toArray(new long[0][]);
  }

  /** Aggregations that sum sessions' time: by name, the ones the framework defines for sessions. */
  private static final Set<String> SESSION_DURATIONS = Set.of("SLEEP_DURATION_TOTAL", "EXERCISE_DURATION_TOTAL", "MINDFULNESS_DURATION_TOTAL");

  /** The value nothing was set to: a nutrient a NutritionRecord does not have reads back as Double.MIN_VALUE grams. */
  private static boolean unset(double v) {
    return v == Double.MIN_VALUE;
  }

  /** Aggregation [id] over [items] (the right type, already filtered by origin), or null when there is no value. */
  static AggregateResult<?> compute(int id, List<Item> items, Window w) {
    Spec s = specs().get(id);
    if (s == null) return null;
    boolean duration = SESSION_DURATIONS.contains(s.name()) && s.recordType() == recordTypeOf(s.name());
    boolean countSamples = s.name().endsWith("MEASUREMENTS_COUNT");
    // Activity intensity's totals (minutes by intensity) are not done: no value rather than a wrong one.
    if (!duration && (s.result() == Duration.class || s.name().equals("INTENSITY_MINUTES_TOTAL"))) return null;
    if (!duration && !countSamples && getter(s) == null && sampleGetter(s) == null) return null;
    List<Double> values = new ArrayList<>();
    double sum = 0;
    long count = 0;
    Set<String> origins = new LinkedHashSet<>();
    ZoneOffset zone = null;
    for (Item it : items) {
      long a = it.start(w), b = it.end(w);
      boolean interval = it.record instanceof IntervalRecord;
      // Cheap first: nothing of this record is in the window.
      if (interval ? (b <= w.start() && !(a == b && a == w.start())) || a >= w.end() : a < w.start() || a >= w.end()) continue;
      boolean used = false;
      if (duration) {
        long lo = Math.max(a, w.start()), hi = Math.min(b, w.end());
        if (hi > lo) {
          long ms = hi - lo;
          long shift = a - it.start;  // local windows: the parts move with the session
          for (long[] x : it.excluded) ms -= Math.max(0, Math.min(x[1] + shift, hi) - Math.max(x[0] + shift, lo));
          sum += Math.max(0, ms);
          used = true;
        }
      } else if (it.samples != null) {
        for (Object smp : it.samples) {
          Instant t = sampleTime(smp);
          long at = t == null ? a : t.toEpochMilli() + (a - it.start);
          if (at < w.start() || at >= w.end()) continue;
          used = true;
          count++;
          if (!countSamples) values.add(number(invoke(sampleGetter(s), smp)));
        }
      } else {
        Object raw = invoke(getter(s), it.record);
        if (raw == null) continue;
        double v = number(raw);
        if (unset(v)) continue;
        if (interval) {
          long lo = Math.max(a, w.start()), hi = Math.min(b, w.end());
          sum += b > a ? v * (hi - lo) / (b - a) : v;
        } else {
          sum += v;
        }
        values.add(v);
        count++;
        used = true;
      }
      if (used) {
        origins.add(it.internal.getPackageName());
        if (zone == null) zone = it.startZone;
      }
    }
    if (origins.isEmpty()) return null;
    Object value;
    if (duration) {
      value = (long) sum;
    } else if (countSamples) {
      value = count;
    } else {
      double v;
      if (s.op() == AggregationType.SUM) {
        v = sum;
      } else if (s.op() == AggregationType.COUNT) {
        v = count;
      } else if (values.isEmpty()) {
        return null;
      } else if (s.op() == AggregationType.MIN) {
        v = values.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
      } else if (s.op() == AggregationType.MAX) {
        v = values.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
      } else {
        v = values.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
      }
      value = of(s.result(), v);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    AggregateResult<?> out = new AggregateResult(value);
    out.setDataOrigins(new ArrayList<>(origins));
    if (zone != null) out.setZoneOffset(zone);
    return out;
  }

  /** The record type a session duration's name belongs to. */
  private static int recordTypeOf(String name) {
    Class<? extends Record> c = switch (name) {
      case "SLEEP_DURATION_TOTAL" -> SleepSessionRecord.class;
      case "EXERCISE_DURATION_TOTAL" -> ExerciseSessionRecord.class;
      default -> android.health.connect.datatypes.MindfulnessSessionRecord.class;
    };
    return HealthConnectMappings.getInstance().getRecordType(c);
  }

  // ---- reading values

  private static final Map<Integer, Method> getters = new HashMap<>();
  private static final Map<Integer, Method> sampleGetters = new HashMap<>();

  /** The record's getter aggregation [s] reads, or null (a series record's values are its samples'). */
  private static synchronized Method getter(Spec s) {
    if (getters.containsKey(s.id())) return getters.get(s.id());
    Class<?> c = HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap().get(s.recordType());
    Method m = c == null || sampleListGetter(c) != null ? null : pick(c, s);
    getters.put(s.id(), m);
    return m;
  }

  /** The sample class's getter aggregation [s] reads, for a record of samples. */
  private static synchronized Method sampleGetter(Spec s) {
    if (sampleGetters.containsKey(s.id())) return sampleGetters.get(s.id());
    Class<?> c = HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap().get(s.recordType());
    Method list = c == null ? null : sampleListGetter(c);
    Method m = null;
    if (list != null) {
      java.lang.reflect.Type t = list.getGenericReturnType();
      if (t instanceof java.lang.reflect.ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> sc) m = pick(sc, s);
    }
    sampleGetters.put(s.id(), m);
    return m;
  }

  private static Method sampleListGetter(Class<?> c) {
    for (String n : new String[] {"getSamples", "getDeltas"}) {
      try {
        Method m = c.getMethod(n);
        if (List.class.isAssignableFrom(m.getReturnType())) return m;
      } catch (NoSuchMethodException e) {
        // not a series record
      }
    }
    return null;
  }

  /** The getter of [c] returning [s]'s value: the only one of its type, else the one named after the aggregation. */
  private static Method pick(Class<?> c, Spec s) {
    List<Method> found = new ArrayList<>();
    for (Method m : c.getMethods()) {
      if (m.getParameterCount() != 0 || !m.getName().startsWith("get") || Modifier.isStatic(m.getModifiers())) continue;
      Class<?> d = m.getDeclaringClass();
      if (d == Object.class || d == Record.class || d == IntervalRecord.class || d == InstantRecord.class) continue;
      if (fits(m.getReturnType(), s.result())) found.add(m);
    }
    if (found.size() == 1) return found.get(0);
    String base = s.name().replaceAll("_(TOTAL|AVG|MIN|MAX|COUNT)$", "");
    StringBuilder name = new StringBuilder("get");
    for (String part : base.split("_")) {
      name.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
    }
    for (Method m : found) if (m.getName().equals(name.toString())) return m;
    return null;
  }

  private static boolean fits(Class<?> returned, Class<?> result) {
    if (result == Long.class) return returned == long.class || returned == Long.class;
    if (result == Double.class) return returned == double.class || returned == Double.class;
    return returned == result;
  }

  private static List<?> samples(Record r) {
    Method m = sampleListGetter(r.getClass());
    return m == null ? null : (List<?>) invoke(m, r);
  }

  private static Instant sampleTime(Object sample) {
    try {
      return (Instant) sample.getClass().getMethod("getTime").invoke(sample);
    } catch (ReflectiveOperationException | ClassCastException e) {
      return null;
    }
  }

  private static Object invoke(Method m, Object o) {
    try {
      return m.invoke(o);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A number, or a unit's value in its base unit (calories, meters, grams, liters...). */
  static double number(Object v) {
    if (v instanceof Number n) return n.doubleValue();
    for (Method m : v.getClass().getMethods()) {
      if (m.getParameterCount() == 0 && m.getReturnType() == double.class
          && (m.getName().startsWith("getIn") || m.getName().equals("getValue"))) {
        return (double) invoke(m, v);
      }
    }
    throw new IllegalArgumentException("not a number: " + v);
  }

  /** [v] as an aggregation's result class: Long rounded, Double, or the unit made from its base unit. */
  static Object of(Class<?> c, double v) {
    if (c == Long.class) return Math.round(v);
    if (c == Double.class) return v;
    for (Method m : c.getMethods()) {
      if (Modifier.isStatic(m.getModifiers()) && m.getName().startsWith("from") && m.getParameterCount() == 1
          && m.getParameterTypes()[0] == double.class && m.getReturnType() == c) {
        try {
          return m.invoke(null, v);
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    throw new IllegalArgumentException("no unit for " + c);
  }
}
