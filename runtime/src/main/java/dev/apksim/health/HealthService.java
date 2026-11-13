package dev.apksim.health;

import android.app.Application;
import android.content.AttributionSource;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.AggregateResult;
import android.health.connect.HealthConnectException;
import android.health.connect.RecordIdFilter;
import android.health.connect.aidl.AggregateDataRequestParcel;
import android.health.connect.aidl.AggregateDataResponseParcel;
import android.health.connect.aidl.DeleteUsingFiltersRequestParcel;
import android.health.connect.aidl.HealthConnectExceptionParcel;
import android.health.connect.aidl.IAggregateRecordsResponseCallback;
import android.health.connect.aidl.IChangeLogsResponseCallback;
import android.health.connect.aidl.IEmptyResponseCallback;
import android.health.connect.aidl.IGetChangeLogTokenCallback;
import android.health.connect.aidl.IHealthConnectService;
import android.health.connect.aidl.IInsertRecordsResponseCallback;
import android.health.connect.aidl.IReadRecordsResponseCallback;
import android.health.connect.aidl.InsertRecordsResponseParcel;
import android.health.connect.aidl.ReadRecordsRequestParcel;
import android.health.connect.aidl.ReadRecordsResponseParcel;
import android.health.connect.aidl.RecordsParcel;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.internal.datatypes.InstantRecordInternal;
import android.health.connect.internal.datatypes.IntervalRecordInternal;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import android.os.Process;
import android.os.UserHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The system side of Health Connect: what the platform's HealthConnectServiceImpl answers to the framework's own
 * HealthConnectManager (android.health.connect, which runs as it is), over the store in {@link Health}. Registered with
 * Robolectric's ServiceManager as "healthconnect", so {@code getSystemService(HealthConnectManager.class)} is a real
 * manager and AndroidX's HealthConnectClient (which on API 34+ asks for exactly that) finds Health Connect available.
 *
 * <p>The binder is a {@link Proxy} of IHealthConnectService: a method this class has (same name and parameters) runs
 * here; any other one that takes a callback answers ERROR_UNSUPPORTED_OPERATION through it, so the app never waits
 * for an answer that does not come (medical records, backup and restore, export, migration, priorities).
 *
 * <p>Requests are decided on the calling thread, under Health's lock, and answered on {@code apksim-health-binder}
 * in order, as the platform's oneway calls are.
 */
public final class HealthService {
  static final long NO_PAGE = -1;
  private static final int DEFAULT_PAGE = 1000;
  private static final int MAX_GROUPS = 100_000;
  private static final String PERM = "android.permission.health.";

  private final Health health;

  HealthService(Health health) {
    this.health = health;
  }

  /** The binder's interface: this class's methods where it has them, ERROR_UNSUPPORTED_OPERATION for the rest. */
  IHealthConnectService binder() {
    Map<String, Method> mine = new HashMap<>();
    for (Method m : HealthService.class.getMethods()) mine.put(key(m), m);
    InvocationHandler h = (proxy, m, args) -> {
      if (m.getDeclaringClass() == Object.class) {
        return switch (m.getName()) {
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> "apksim HealthConnectService";
        };
      }
      if (m.getName().equals("asBinder")) return null;
      health.count(m.getName());
      Method here = mine.get(key(m));
      if (here != null) {
        try {
          return here.invoke(this, args);
        } catch (InvocationTargetException e) {
          // A failure of the service itself reaches the app as its callback's error, as the platform's
          // tryAndThrowException turns one into ERROR_INTERNAL, not as an exception on the app's calling thread.
          Throwable cause = e.getCause();
          if (args != null && cause instanceof RuntimeException) {
            for (Object a : args) {
              if (a != null && answerError(a, HealthConnectException.ERROR_INTERNAL, String.valueOf(cause))) return defaultValue(m.getReturnType());
            }
          }
          throw cause;
        }
      }
      if (args != null) {
        for (Object a : args) {
          if (a != null && answerUnsupported(a, m.getName())) break;
        }
      }
      return defaultValue(m.getReturnType());
    };
    return (IHealthConnectService) Proxy.newProxyInstance(IHealthConnectService.class.getClassLoader(),
        new Class<?>[] {IHealthConnectService.class}, h);
  }

  private static String key(Method m) {
    StringBuilder b = new StringBuilder(m.getName());
    for (Class<?> p : m.getParameterTypes()) b.append(',').append(p.getName());
    return b.toString();
  }

  private boolean answerUnsupported(Object callback, String method) {
    return answerError(callback, HealthConnectException.ERROR_UNSUPPORTED_OPERATION, method + " is not supported by apksim's Health Connect");
  }

  /** Answers [callback]'s onError(code, message), if it is a callback. */
  private boolean answerError(Object callback, int code, String message) {
    try {
      Method onError = callback.getClass().getMethod("onError", HealthConnectExceptionParcel.class);
      health.answer(() -> onError.invoke(callback, exception(code, message)));
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static Object defaultValue(Class<?> c) {
    if (c == boolean.class) return false;
    if (c == int.class) return 0;
    if (c == long.class) return 0L;
    if (c.isPrimitive()) return null;
    return null;
  }

  static HealthConnectExceptionParcel exception(int code, String message) {
    return new HealthConnectExceptionParcel(new HealthConnectException(code, message));
  }

  // ---------------------------------------------------------------- permissions

  public void grantHealthPermission(String pkg, String permission, UserHandle user) {
    health.setGranted(permission, true);
  }

  public void revokeHealthPermission(String pkg, String permission, String reason, UserHandle user) {
    health.setGranted(permission, false);
  }

  public void revokeAllHealthPermissions(String pkg, String reason, UserHandle user) {
    for (String p : requestedHealthPermissions()) health.setGranted(p, false);
  }

  public List<String> getGrantedHealthPermissions(String pkg, UserHandle user) {
    List<String> out = new ArrayList<>();
    for (String p : requestedHealthPermissions()) if (granted(p)) out.add(p);
    return out;
  }

  public Map<String, Integer> getHealthPermissionsFlags(String pkg, UserHandle user, List<String> permissions) {
    Map<String, Integer> out = new HashMap<>();
    for (String p : permissions) out.put(p, 0);
    return out;
  }

  public void setHealthPermissionsUserFixedFlagValue(String pkg, UserHandle user, List<String> permissions, boolean value) {}

  public long getHistoricalAccessStartDateInMilliseconds(String pkg, UserHandle user) {
    return -1;
  }

  public int getRecordRetentionPeriodInDays(UserHandle user) {
    return 0;  // keep forever, the platform's default
  }

  static boolean granted(String permission) {
    Application app = org.robolectric.RuntimeEnvironment.getApplication();
    return app != null && app.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
  }

  static List<String> requestedHealthPermissions() {
    List<String> out = new ArrayList<>();
    Application app = org.robolectric.RuntimeEnvironment.getApplication();
    try {
      String[] req = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
      if (req != null) for (String p : req) if (p.startsWith(PERM)) out.add(p);
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      // no package info: nothing requested
    }
    return out;
  }

  /** The permission that reads records of [type]: the one of its permission category (heart rate's for HeartRateRecord). */
  private static String readPermission(int type) {
    HealthConnectMappings m = HealthConnectMappings.getInstance();
    return m.getHealthReadPermission(m.getHealthPermissionCategoryForRecordType(type));
  }

  private static String writePermission(int type) {
    HealthConnectMappings m = HealthConnectMappings.getInstance();
    return m.getHealthWritePermission(m.getHealthPermissionCategoryForRecordType(type));
  }

  /** The platform's message for a missing permission, as an ERROR_SECURITY (HealthConnectClient throws SecurityException). */
  private static HealthConnectExceptionParcel denied(String permission, String what) {
    return exception(HealthConnectException.ERROR_SECURITY, "Caller doesn't have " + permission + " to " + what);
  }

  private static String caller(AttributionSource source) {
    String pkg = source == null ? null : source.getPackageName();
    return pkg != null ? pkg : org.robolectric.RuntimeEnvironment.getApplication().getPackageName();
  }

  // ---------------------------------------------------------------- insert, update

  public void insertRecords(AttributionSource source, RecordsParcel parcel, IInsertRecordsResponseCallback cb) {
    String pkg = caller(source);
    List<String> ids = new ArrayList<>();
    synchronized (health.lock) {
      for (RecordInternal<?> r : parcel.getRecords()) {
        if (!granted(writePermission(r.getRecordType()))) {
          health.answer(() -> cb.onError(denied(writePermission(r.getRecordType()), "write records of type " + r.getRecordType())));
          return;
        }
      }
      long now = health.now();
      for (RecordInternal<?> in : parcel.getRecords()) {
        RecordInternal<?> r = Store.copy(in);
        stamp(r, pkg, now);
        String id = r.getClientRecordId() != null ? clientUuid(pkg, r.getRecordType(), r.getClientRecordId()).toString() : UUID.randomUUID().toString();
        Store.Entry old = health.store.records.get(id);
        r.setUuid(id);
        // An upsert by client id with an older version than what is stored leaves the stored one, as the platform does.
        if (old == null || old.record.getClientRecordVersion() <= r.getClientRecordVersion()) health.store.put(r, false, now);
        ids.add(id);
      }
      health.changed();
    }
    health.answer(() -> cb.onResult(new InsertRecordsResponseParcel(ids)));
  }

  public void updateRecords(AttributionSource source, RecordsParcel parcel, IEmptyResponseCallback cb) {
    String pkg = caller(source);
    synchronized (health.lock) {
      List<RecordInternal<?>> todo = new ArrayList<>();
      for (RecordInternal<?> in : parcel.getRecords()) {
        if (!granted(writePermission(in.getRecordType()))) {
          health.answer(() -> cb.onError(denied(writePermission(in.getRecordType()), "write records of type " + in.getRecordType())));
          return;
        }
        String id = in.getClientRecordId() != null ? clientUuid(pkg, in.getRecordType(), in.getClientRecordId()).toString()
            : in.getUuid() == null ? null : in.getUuid().toString();
        Store.Entry old = id == null ? null : health.store.records.get(id);
        if (old == null || !pkg.equals(old.record.getPackageName())) {
          health.answer(() -> cb.onError(exception(HealthConnectException.ERROR_INVALID_ARGUMENT,
              "No record with id " + in.getUuid() + " of this app to update")));
          return;
        }
        RecordInternal<?> r = Store.copy(in);
        r.setUuid(id);
        todo.add(r);
      }
      long now = health.now();
      for (RecordInternal<?> r : todo) {
        stamp(r, pkg, now);
        health.store.put(r, false, now);
      }
      health.changed();
    }
    health.answer(cb::onResult);
  }

  void stamp(RecordInternal<?> r, String pkg, long now) {
    r.setPackageName(pkg);
    r.setAppName(health.appName(pkg));
    r.setLastModifiedTime(now);
  }

  /** A record's id from its client id, the same for one app, type and client id (the platform's upsert key). */
  static UUID clientUuid(String pkg, int type, String clientId) {
    return UUID.nameUUIDFromBytes((pkg + "\0" + type + "\0" + clientId).getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- read

  public void readRecords(AttributionSource source, ReadRecordsRequestParcel req, IReadRecordsResponseCallback cb) {
    String pkg = caller(source);
    int type = req.getRecordType();
    ReadRecordsResponseParcel out;
    synchronized (health.lock) {
      boolean all = granted(readPermission(type));
      if (!all && !granted(writePermission(type))) {
        health.answer(() -> cb.onError(denied(readPermission(type), "read records of type " + type)));
        return;
      }
      // Without the read permission an app reads only what it wrote itself (the write permission's own data).
      List<RecordInternal<?>> found = new ArrayList<>();
      if (req.getRecordIdFiltersParcel() != null) {
        for (RecordIdFilter f : req.getRecordIdFiltersParcel().getRecordIdFilters()) {
          String id = f.getId() != null ? f.getId() : clientUuid(pkg, type, f.getClientRecordId()).toString();
          Store.Entry e = health.store.records.get(id);
          if (e != null && e.record.getRecordType() == type && (all || pkg.equals(e.record.getPackageName()))) found.add(e.record);
        }
        out = new ReadRecordsResponseParcel(new RecordsParcel(copies(found)), NO_PAGE);
      } else {
        List<String> origins = req.getPackageFilters();
        for (Store.Entry e : health.store.records.values()) {
          RecordInternal<?> r = e.record;
          if (r.getRecordType() != type) continue;
          if (!all && !pkg.equals(r.getPackageName())) continue;
          if (origins != null && !origins.isEmpty() && !origins.contains(r.getPackageName())) continue;
          if (!inRange(r, req.getStartTime(), req.getEndTime(), req.usesLocalTimeFilter())) continue;
          found.add(r);
        }
        // A page token carries the order it was made for (a request with one cannot set its own, as on the platform).
        long token = req.getPageToken();
        boolean paged = token != NO_PAGE && token != 0;
        boolean ascending = paged ? (token & 1) == 1 : req.isAscending();
        Comparator<RecordInternal<?>> order = Comparator.comparingLong(HealthService::startMillis);
        found.sort(ascending ? order : order.reversed());
        int size = req.getPageSize() > 0 ? req.getPageSize() : DEFAULT_PAGE;
        int from = paged ? (int) (token >> 1) : 0;
        int to = Math.min(found.size(), from + size);
        long next = to < found.size() ? ((long) to << 1) | (ascending ? 1 : 0) : NO_PAGE;
        out = new ReadRecordsResponseParcel(new RecordsParcel(copies(from < to ? found.subList(from, to) : List.of())), next);
      }
    }
    ReadRecordsResponseParcel answer = out;
    health.answer(() -> cb.onResult(answer));
  }

  private static List<RecordInternal<?>> copies(List<RecordInternal<?>> rs) {
    List<RecordInternal<?>> out = new ArrayList<>();
    for (RecordInternal<?> r : rs) out.add(Store.copy(r));
    return out;
  }

  static long startMillis(RecordInternal<?> r) {
    if (r instanceof IntervalRecordInternal<?> iv) return iv.getStartTimeInMillis();
    if (r instanceof InstantRecordInternal<?> in) return in.getTimeInMillis();
    return r.getRecordTime();
  }

  static long endMillis(RecordInternal<?> r) {
    if (r instanceof IntervalRecordInternal<?> iv) return iv.getEndTimeInMillis();
    return startMillis(r);
  }

  private static long startOffsetMs(RecordInternal<?> r) {
    if (r instanceof IntervalRecordInternal<?> iv) return iv.getStartZoneOffsetInSeconds() * 1000L;
    if (r instanceof InstantRecordInternal<?> in) return in.getZoneOffsetInSeconds() * 1000L;
    return 0;
  }

  /**
   * Whether [r] falls in a read's or a delete's time filter ([start, end) in epoch ms, or local ms with [local]; -1 for
   * none): the record's time, or an interval's start, inside it. An interval that began before the filter is not in it
   * even when it runs on into it (checked against an API 36 emulator: HealthConnectTest).
   */
  static boolean inRange(RecordInternal<?> r, long start, long end, boolean local) {
    long a = startMillis(r) + (local ? startOffsetMs(r) : 0);
    long lo = start == -1 ? Long.MIN_VALUE : start;
    long hi = end == -1 ? Long.MAX_VALUE : end;
    return a >= lo && a < hi;
  }

  // ---------------------------------------------------------------- aggregate

  public void aggregateRecords(AttributionSource source, AggregateDataRequestParcel req, IAggregateRecordsResponseCallback cb) {
    AggregateDataResponseParcel out;
    synchronized (health.lock) {
      Map<Integer, List<RecordInternal<?>>> byId = new LinkedHashMap<>();
      List<String> origins = req.getPackageFilters();
      for (int id : req.getAggregateIds()) {
        int type = Aggregates.recordType(id);
        if (type < 0) {
          health.answer(() -> cb.onError(exception(HealthConnectException.ERROR_INVALID_ARGUMENT, "Unknown aggregation " + id)));
          return;
        }
        if (!granted(readPermission(type))) {
          health.answer(() -> cb.onError(denied(readPermission(type), "aggregate records of type " + type)));
          return;
        }
        List<RecordInternal<?>> rs = new ArrayList<>();
        for (Store.Entry e : health.store.records.values()) {
          if (e.record.getRecordType() != type) continue;
          if (origins != null && !origins.isEmpty() && !origins.contains(e.record.getPackageName())) continue;
          rs.add(e.record);
        }
        byId.put(id, rs);
      }
      boolean local = req.useLocalTimeFilter();
      long start = req.getStartTime(), end = req.getEndTime();
      Duration duration = req.getDuration();
      Period period = req.getPeriod();
      // A group size that never moves on, or more groups than anyone reads: refused, rather than a loop under the lock.
      boolean badPeriod = period != null && (period.isZero() || period.isNegative());
      boolean badDuration = duration != null && (duration.isZero() || duration.isNegative());
      if (badPeriod || badDuration || (duration != null && end != -1 && start != -1 && (end - start) / duration.toMillis() > MAX_GROUPS)) {
        health.answer(() -> cb.onError(exception(HealthConnectException.ERROR_INVALID_ARGUMENT, "Invalid group duration or period")));
        return;
      }
      if (period != null) {
        List<AggregateRecordsResponse<?>> groups = new ArrayList<>();
        LocalDateTime from = LocalDateTime.ofEpochSecond(Math.floorDiv(start, 1000), (int) Math.floorMod(start, 1000) * 1_000_000, ZoneOffset.UTC);
        long lo = start;
        while (lo < end) {
          if (groups.size() >= MAX_GROUPS) {
            health.answer(() -> cb.onError(exception(HealthConnectException.ERROR_INVALID_ARGUMENT, "Too many groups")));
            return;
          }
          from = from.plus(period);
          long hi = Math.min(end, from.toInstant(ZoneOffset.UTC).toEpochMilli());
          groups.add(window(byId, lo, hi, true));
          lo = hi;
        }
        out = new AggregateDataResponseParcel(groups).setPeriod(period, req.getTimeRangeFilter());
      } else if (duration != null) {
        List<AggregateRecordsResponse<?>> groups = new ArrayList<>();
        long step = duration.toMillis();
        for (long lo = start; lo < end; lo += step) groups.add(window(byId, lo, Math.min(end, lo + step), local));
        out = new AggregateDataResponseParcel(groups).setDuration(duration, req.getTimeRangeFilter());
      } else {
        out = new AggregateDataResponseParcel(List.of(window(byId, start, end, local)));
      }
    }
    AggregateDataResponseParcel answer = out;
    health.answer(() -> cb.onResult(answer));
  }

  private static AggregateRecordsResponse<?> window(Map<Integer, List<RecordInternal<?>>> byId, long start, long end, boolean local) {
    Map<Integer, AggregateResult<?>> results = new HashMap<>();
    Aggregates.Window w = new Aggregates.Window(start == -1 ? Long.MIN_VALUE : start, end == -1 ? Long.MAX_VALUE : end, local);
    for (Map.Entry<Integer, List<RecordInternal<?>>> e : byId.entrySet()) {
      AggregateResult<?> r = Aggregates.compute(e.getKey(), e.getValue(), w);
      if (r != null) results.put(e.getKey(), r);
    }
    return new AggregateRecordsResponse<>(results);
  }

  // ---------------------------------------------------------------- delete

  public void deleteUsingFilters(AttributionSource source, DeleteUsingFiltersRequestParcel req, IEmptyResponseCallback cb) {
    String pkg = caller(source);
    synchronized (health.lock) {
      long now = health.now();
      List<String> gone = new ArrayList<>();
      if (req.usesIdFilters()) {
        for (RecordIdFilter f : req.getRecordIdFiltersParcel().getRecordIdFilters()) {
          int type = HealthConnectMappings.getInstance().getRecordType(f.getRecordType());
          if (!granted(writePermission(type))) {
            health.answer(() -> cb.onError(denied(writePermission(type), "delete records of type " + type)));
            return;
          }
          String id = f.getId() != null ? f.getId() : clientUuid(pkg, type, f.getClientRecordId()).toString();
          Store.Entry e = health.store.records.get(id);
          if (e != null && e.record.getRecordType() == type && pkg.equals(e.record.getPackageName())) gone.add(id);
        }
      } else {
        List<Integer> types = req.getRecordTypeFilters();
        if (types == null || types.isEmpty()) types = new ArrayList<>(HealthConnectMappings.getInstance().getAllRecordTypeIdentifiers());
        boolean explicit = req.getRecordTypeFilters() != null && !req.getRecordTypeFilters().isEmpty();
        for (int type : types) {
          if (explicit && !granted(writePermission(type))) {
            health.answer(() -> cb.onError(denied(writePermission(type), "delete records of type " + type)));
            return;
          }
        }
        // An app deletes only its own records, whatever package filter it asked for.
        for (Store.Entry e : health.store.records.values()) {
          RecordInternal<?> r = e.record;
          if (!types.contains(r.getRecordType()) || !pkg.equals(r.getPackageName())) continue;
          if (!granted(writePermission(r.getRecordType()))) continue;
          if (!inRange(r, req.getStartTime(), req.getEndTime(), req.isLocalTimeFilter())) continue;
          gone.add(r.getUuid().toString());
        }
      }
      for (String id : gone) health.store.delete(id, now);
      if (!gone.isEmpty()) health.changed();
    }
    health.answer(cb::onResult);
  }

  // ---------------------------------------------------------------- changes

  public void getChangeLogToken(AttributionSource source, ChangeLogTokenRequest req, IGetChangeLogTokenCallback cb) {
    List<Integer> types = req.getRecordTypesList();
    if (types == null || types.isEmpty()) {
      health.answer(() -> cb.onError(exception(HealthConnectException.ERROR_INVALID_ARGUMENT, "Requested record types must not be empty")));
      return;
    }
    for (int type : types) {
      if (!granted(readPermission(type))) {
        health.answer(() -> cb.onError(denied(readPermission(type), "read changes of records of type " + type)));
        return;
      }
    }
    String token;
    synchronized (health.lock) {
      token = Token.encode(health.store.seq, types, req.getPackageNamesToFilter());
    }
    health.answer(() -> cb.onResult(new ChangeLogTokenResponse(token)));
  }

  public void getChangeLogs(AttributionSource source, ChangeLogsRequest req, IChangeLogsResponseCallback cb) {
    Token t = Token.decode(req.getToken());
    if (t == null) {
      health.answer(() -> cb.onError(exception(HealthConnectException.ERROR_INVALID_ARGUMENT, "Invalid token")));
      return;
    }
    for (int type : t.types()) {
      if (!granted(readPermission(type))) {
        health.answer(() -> cb.onError(denied(readPermission(type), "read changes of records of type " + type)));
        return;
      }
    }
    ChangeLogsResponse out;
    synchronized (health.lock) {
      int size = req.getPageSize() > 0 ? req.getPageSize() : DEFAULT_PAGE;
      Map<String, Store.Change> latest = new LinkedHashMap<>();
      long last = t.seq();
      boolean more = false;
      for (Store.Change c : health.store.changes) {
        if (c.seq() <= t.seq()) continue;
        if (!t.types().contains(c.type())) continue;
        if (!t.origins().isEmpty() && !t.origins().contains(c.pkg())) continue;
        if (latest.size() >= size && !latest.containsKey(c.uuid())) {
          more = true;
          break;
        }
        latest.remove(c.uuid());
        latest.put(c.uuid(), c);
        last = c.seq();
      }
      List<RecordInternal<?>> upserted = new ArrayList<>();
      List<ChangeLogsResponse.DeletedLog> deleted = new ArrayList<>();
      for (Store.Change c : latest.values()) {
        Store.Entry e = health.store.records.get(c.uuid());
        if (!c.deleted() && e != null) {
          upserted.add(Store.copy(e.record));
        } else if (c.deleted()) {
          deleted.add(new ChangeLogsResponse.DeletedLog(c.uuid(), c.time()));
        }
      }
      out = new ChangeLogsResponse(new RecordsParcel(upserted), deleted, Token.encode(last, t.types(), t.origins()), more);
    }
    ChangeLogsResponse answer = out;
    health.answer(() -> cb.onResult(answer));
  }

  /** A change token: where in the change log it stands and what it watches, as text the app may keep. */
  record Token(long seq, List<Integer> types, List<String> origins) {
    static String encode(long seq, List<Integer> types, List<String> origins) {
      StringBuilder b = new StringBuilder("apksim1|").append(seq).append('|');
      for (int i = 0; i < types.size(); i++) b.append(i == 0 ? "" : ",").append(types.get(i));
      b.append('|');
      if (origins != null) b.append(String.join(",", origins));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Token decode(String token) {
      try {
        String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String[] p = s.split("\\|", -1);
        if (p.length != 4 || !p[0].equals("apksim1")) return null;
        List<Integer> types = new ArrayList<>();
        for (String x : p[2].split(",")) if (!x.isEmpty()) types.add(Integer.parseInt(x));
        List<String> origins = new ArrayList<>();
        for (String x : p[3].split(",")) if (!x.isEmpty()) origins.add(x);
        return new Token(Long.parseLong(p[1]), types, origins);
      } catch (RuntimeException e) {
        return null;
      }
    }
  }

}
