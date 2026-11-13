package dev.apksim.health;

import android.health.connect.AggregateResult;
import android.health.connect.datatypes.AggregationType;
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
 * and maximums take the instant records and samples inside the window. Durations (sleep, exercise, mindfulness) are the
 * sessions' time inside the window. What has no getter to read (basal calories from a metabolic rate, activity
 * intensity's minutes) answers no value, as an aggregation over no data does.
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

  /** Aggregation [id] over [records] (the right type, already filtered by origin), or null when there is no value. */
  static AggregateResult<?> compute(int id, List<RecordInternal<?>> records, Window w) {
    Spec s = specs().get(id);
    if (s == null) return null;
    List<Double> values = new ArrayList<>();
    double sum = 0;
    long count = 0;
    Set<String> origins = new LinkedHashSet<>();
    ZoneOffset zone = null;
    boolean any = false;
    boolean duration = s.name().endsWith("DURATION_TOTAL");
    boolean countSamples = s.name().endsWith("MEASUREMENTS_COUNT");
    if (!duration && !countSamples && getter(s) == null && sampleGetter(s) == null) return null;
    for (RecordInternal<?> ri : records) {
      Record r = ri.toExternalRecord();
      boolean used = false;
      if (r instanceof IntervalRecord iv) {
        long a = w.at(iv.getStartTime(), iv.getStartZoneOffset());
        long b = w.at(iv.getEndTime(), iv.getEndZoneOffset());
        long lo = Math.max(a, w.start()), hi = Math.min(b, w.end());
        List<?> samples = samples(r);
        if (duration) {
          if (hi > lo) {
            sum += hi - lo;
            used = true;
          }
        } else if (samples != null) {
          for (Object smp : samples) {
            Instant t = sampleTime(smp);
            long at = t == null ? a : w.at(t, iv.getStartZoneOffset());
            if (at < w.start() || at >= w.end()) continue;
            used = true;
            count++;
            if (!countSamples) values.add(number(invoke(sampleGetter(s), smp)));
          }
        } else if (hi > lo || (a == b && a >= w.start() && a < w.end())) {
          double v = number(invoke(getter(s), r));
          double share = b > a ? (double) (hi - lo) / (b - a) : 1;
          sum += v * share;
          values.add(v);
          count++;
          used = true;
        }
        if (used && zone == null) zone = iv.getStartZoneOffset();
      } else if (r instanceof InstantRecord in) {
        long t = w.at(in.getTime(), in.getZoneOffset());
        if (t >= w.start() && t < w.end() && getter(s) != null) {
          double v = number(invoke(getter(s), r));
          sum += v;
          values.add(v);
          count++;
          used = true;
          if (zone == null) zone = in.getZoneOffset();
        }
      }
      if (used) {
        any = true;
        origins.add(ri.getPackageName());
      }
    }
    if (!any) return null;
    Object value;
    if (duration) {
      value = s.result() == Duration.class ? Duration.ofMillis((long) sum) : (Object) (long) sum;
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
