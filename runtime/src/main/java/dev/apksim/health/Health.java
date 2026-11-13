package dev.apksim.health;

import android.app.Application;
import android.health.connect.aidl.IHealthConnectService;
import android.health.connect.datatypes.Record;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.robolectric.shadows.ShadowServiceManager;

/**
 * Health Connect (docs/health-connect.md): the platform service the framework's HealthConnectManager talks to
 * ({@link HealthService}), its records ({@link Store}), the launch's seeds ({@code --health FILE|PRESET}, {@link Seeds})
 * and the {@code health} command. On API 34 and later AndroidX's HealthConnectClient uses that manager, which it finds
 * with {@code getSystemService("healthconnect")}: without a service published under that name the answer was null,
 * {@code getSdkStatus} said SDK_UNAVAILABLE and apps hid what they read from it.
 *
 * <p>A running instance keeps the records with its data slot ({@code <slot>.device/health.store}), as a device keeps
 * them outside the app's data; a test run starts every test with an empty store and the launch's seeds. Seeds are the
 * records of their own data origin ({@code dev.apksim.health} unless the file names one), replaced at every launch that
 * passes {@code --health}, so a week preset follows the clock; records apps or {@code health add} wrote stay.
 *
 * <p>References framework classes only (android.health.connect), never AndroidX's, so an app without the client
 * library pays nothing but the service's registration.
 */
public final class Health {
  /** The name the framework's HealthServicesInitializer looks the service up by. */
  static final String SERVICE = "healthconnect";
  static final String SEED_ORIGIN = "dev.apksim.health";
  private static final String FILE = "health.store";

  private static Health instance;

  final Object lock = new Object();
  final Store store = new Store();
  private final HealthService service = new HealthService(this);
  private final IHealthConnectService binder = service.binder();
  private final Map<String, Integer> calls = new TreeMap<>();
  private ExecutorService answers;
  private Path file;

  private Health() {}

  private static synchronized Health get() {
    if (instance == null) instance = new Health();
    return instance;
  }

  /**
   * Before every Application.onCreate (DeviceIdentity: running instances, a standby's warm-up and each test's setup):
   * publishes the service (Robolectric's ServiceManager forgets it between tests), then the store: the slot's file in a
   * running instance ([deviceDir], else none), with this launch's --health seeds in place of the last ones; in a test
   * run, the seeds alone.
   */
  public static void reset(Application app, Path deviceDir) {
    Health h = get();
    ShadowServiceManager.addBinderService(SERVICE, IHealthConnectService.class, h.binder);
    synchronized (h.lock) {
      h.calls.clear();
      h.store.clear();
      h.store.seq = 0;
      h.file = deviceDir == null ? null : deviceDir.resolve(FILE);
      if (h.file != null && Files.isRegularFile(h.file)) {
        try (InputStream in = Files.newInputStream(h.file)) {
          h.store.read(in);
        } catch (IOException | RuntimeException e) {
          System.err.println("[apksim] health: could not read " + h.file.getFileName() + ": " + e);
          h.store.clear();
        }
      }
      List<String> seeds = new ArrayList<>();
      for (int i = 0; System.getProperty("apksim.health." + i) != null; i++) seeds.add(System.getProperty("apksim.health." + i));
      if (!seeds.isEmpty()) {
        long now = h.now();
        // Seeds have ids derived from what they hold (Seeds.toInternal), so a launch that seeds the same records again
        // changes nothing: only seeds that are new or gone show up as changes (a week preset moves on a day at a time).
        java.util.Map<String, RecordInternal<?>> fresh = new java.util.LinkedHashMap<>();
        for (String s : seeds) {
          try {
            for (RecordInternal<?> r : Seeds.load(s, now)) fresh.put(r.getUuid().toString(), r);
          } catch (Exception e) {
            System.err.println("[apksim] --health " + s + ": " + e.getMessage());
          }
        }
        List<String> gone = new ArrayList<>();
        for (Store.Entry e : h.store.records.values()) {
          if (e.seed && !fresh.containsKey(e.record.getUuid().toString())) gone.add(e.record.getUuid().toString());
        }
        boolean changed = !gone.isEmpty();
        for (String id : gone) h.store.delete(id, now);
        for (RecordInternal<?> r : fresh.values()) {
          Store.Entry old = h.store.records.get(r.getUuid().toString());
          if (old != null && old.seed) {
            r.setLastModifiedTime(old.record.getLastModifiedTime());
            if (java.util.Arrays.equals(Store.toBytes(old.record), Store.toBytes(r))) continue;
          }
          h.store.put(r, true, now);
          changed = true;
        }
        if (changed) h.changed();
      }
    }
  }

  /** The wall clock the app sees (SimClock: --clock and the clock command move it). */
  long now() {
    return dev.apksim.SimClock.currentTimeMillis();
  }

  void count(String method) {
    synchronized (lock) {
      calls.merge(method, 1, Integer::sum);
    }
  }

  interface Call {
    void run() throws Exception;
  }

  /** Sends an answer to the app's callback from the binder thread, in the order answers were decided. */
  void answer(Call c) {
    ExecutorService a;
    synchronized (this) {
      if (answers == null) {
        answers = Executors.newSingleThreadExecutor(r -> {
          Thread t = new Thread(r, "apksim-health-binder");
          t.setDaemon(true);
          return t;
        });
      }
      a = answers;
    }
    a.execute(() -> {
      try {
        c.run();
      } catch (Throwable t) {
        // A oneway call's callback that throws on a device is the app's problem in its own process: there, the
        // manager's callback hands results to the app's executor, so what throws here is the framework's (logged).
        System.err.println("[apksim] health: answering a call failed: " + t);
      }
    });
  }

  /** The store changed: written to the slot's file when there is one (beside it, then moved over it). */
  void changed() {
    if (file == null) return;
    try {
      Path tmp = file.resolveSibling(FILE + ".tmp" + ProcessHandle.current().pid());
      try (OutputStream out = Files.newOutputStream(tmp)) {
        store.write(out);
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      System.err.println("[apksim] health: could not save: " + e);
    }
  }

  String appName(String pkg) {
    Application app = org.robolectric.RuntimeEnvironment.getApplication();
    if (app != null && pkg.equals(app.getPackageName())) {
      try {
        return String.valueOf(app.getApplicationInfo().loadLabel(app.getPackageManager()));
      } catch (RuntimeException e) {
        return pkg;
      }
    }
    return SEED_ORIGIN.equals(pkg) ? "apksim" : pkg;
  }

  void setGranted(String permission, boolean on) {
    Application app = org.robolectric.RuntimeEnvironment.getApplication();
    if (on) {
      org.robolectric.Shadows.shadowOf(app).grantPermissions(permission);
    } else {
      org.robolectric.Shadows.shadowOf(app).denyPermissions(permission);
    }
  }

  // ---------------------------------------------------------------- the health command

  /**
   * {@code health}: records per type, the app's health permissions, calls made; {@code health add FILE|PRESET|JSON};
   * {@code health clear [TYPE]}; {@code health types}.
   */
  public static String command(String[] a) {
    Health h = get();
    String op = a.length > 1 ? a[1] : "";
    try {
      switch (op) {
        case "" -> {
          return h.status();
        }
        case "add" -> {
          if (a.length < 3) return "usage: health add FILE|PRESET|JSON (presets: " + Seeds.PRESETS + ")";
          String spec = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
          synchronized (h.lock) {
            long now = h.now();
            List<RecordInternal<?>> rs = Seeds.load(spec, now);
            for (RecordInternal<?> r : rs) h.store.put(r, false, now);
            h.changed();
            return "ok (" + rs.size() + " records)";
          }
        }
        case "clear" -> {
          synchronized (h.lock) {
            Integer type = a.length > 2 ? Seeds.typeId(a[2]) : null;
            if (a.length > 2 && type == null) return "error: unknown record type " + a[2] + " (health types lists them)";
            long now = h.now();
            List<String> ids = new ArrayList<>();
            for (Store.Entry e : h.store.records.values()) {
              if (type == null || e.record.getRecordType() == type) ids.add(e.record.getUuid().toString());
            }
            for (String id : ids) h.store.delete(id, now);
            h.changed();
            return "ok (" + ids.size() + " removed)";
          }
        }
        case "types" -> {
          List<String> names = new ArrayList<>();
          for (Class<? extends Record> c : HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap().values()) {
            names.add(Seeds.name(c));
          }
          names.sort(null);
          return String.join(" ", names);
        }
        default -> {
          return "usage: health [add FILE|PRESET|JSON | clear [TYPE] | types]";
        }
      }
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  private String status() {
    synchronized (lock) {
      Map<String, Integer> perType = new TreeMap<>();
      Map<Integer, Class<? extends Record>> classes = HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap();
      for (Store.Entry e : store.records.values()) {
        Class<? extends Record> c = classes.get(e.record.getRecordType());
        perType.merge(c == null ? "type" + e.record.getRecordType() : Seeds.name(c), 1, Integer::sum);
      }
      StringBuilder b = new StringBuilder("records: ");
      if (perType.isEmpty()) b.append("none");
      for (Iterator<Map.Entry<String, Integer>> it = perType.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<String, Integer> e = it.next();
        b.append(e.getKey()).append(' ').append(e.getValue()).append(it.hasNext() ? ", " : "");
      }
      List<String> granted = new ArrayList<>(), denied = new ArrayList<>();
      for (String p : HealthService.requestedHealthPermissions()) {
        (HealthService.granted(p) ? granted : denied).add(p.substring("android.permission.health.".length()));
      }
      b.append("; granted: ").append(granted.isEmpty() ? "none" : String.join(" ", granted));
      b.append("; not granted: ").append(denied.isEmpty() ? "none" : String.join(" ", denied));
      b.append("; calls: ");
      if (calls.isEmpty()) b.append("none");
      List<String> cs = new ArrayList<>();
      calls.forEach((k, v) -> cs.add(k + " " + v));
      b.append(String.join(", ", cs));
      return b.toString();
    }
  }
}
