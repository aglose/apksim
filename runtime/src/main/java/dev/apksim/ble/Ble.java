package dev.apksim.ble;

import android.Manifest;
import android.app.Application;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothScan;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.robolectric.RuntimeEnvironment;

/**
 * Scripted Bluetooth LE peripherals (docs/ble.md). apksim plays the device's Bluetooth stack: the system side of
 * IBluetoothScan and IBluetoothGatt, the binders the framework's own BluetoothLeScanner and BluetoothGatt talk to
 * (Robolectric's shadows of those two are replaced by empty ones, so AOSP's client code runs: its scan callback
 * wrapper posting results to the main thread, BluetoothGatt's busy flag, handle lookup, handler choice and the API 33
 * and older callback signatures). What a device's stack does is answered here: scans and their filters, settings,
 * batching and PendingIntent delivery; connections, discovery, reads, writes, notifications through the CCC
 * descriptor, MTU, RSSI, PHY and bonding. Answers go out on one thread (`apksim-ble-binder`, a binder thread's
 * stand-in: no Looper), in order, as oneway binder calls to one callback object arrive. Anything timed (advertising
 * intervals, timelines, batch delays, latency, a direct connection's 30 s timeout) is posted to the main looper by
 * uptime, so in a test run it moves only with the simulated clock.
 */
public final class Ble {
  private Ble() {}

  static final Object LOCK = new Object();

  // ---- state, guarded by LOCK ----
  private static final Map<String, Peripheral> peripherals = new LinkedHashMap<>();
  private static final Map<Integer, Client> clients = new LinkedHashMap<>();
  private static final Map<Integer, Scanner> scanners = new LinkedHashMap<>();
  /** CCC values per peripheral link (address → descriptor handle → value), as a peripheral keeps them per link. */
  private static final Map<String, Map<Integer, byte[]>> linkCcc = new HashMap<>();
  private static final Set<String> bonded = new HashSet<>();
  /** Pairing attempts under way, by address: the adapter going off ends them, so a later completion is ignored. */
  private static final Map<String, Object> pairing = new HashMap<>();
  private static final ArrayDeque<Long> scanStarts = new ArrayDeque<>();
  private static int nextId = 1;
  private static int generation;
  private static Object token = new Object();
  private static Handler clock;
  private static boolean adapterOn = true;

  private static final ExecutorService binder = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "apksim-ble-binder");
    t.setDaemon(true);
    return t;
  });

  /**
   * Another binder thread, for registerScanner's answer alone: BluetoothLeScanner waits for it (up to 2 s) on the
   * calling thread, which may be apksim-ble-binder itself (a scan started from a GATT callback without a Handler), as a
   * device's binder pool has more than one thread.
   */
  private static final ExecutorService registrar = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "apksim-ble-binder-2");
    t.setDaemon(true);
    return t;
  });

  static final int GATT_SUCCESS = 0;
  static final int GATT_INVALID_HANDLE = 0x01;
  static final int GATT_READ_NOT_PERMITTED = 0x02;
  static final int GATT_WRITE_NOT_PERMITTED = 0x03;
  static final int GATT_INVALID_ATTRIBUTE_LENGTH = 0x0d;
  static final int GATT_CONN_TIMEOUT = 0x08;
  static final int GATT_CONN_TERMINATE_PEER_USER = 0x13;
  static final int GATT_CONN_TERMINATE_LOCAL_HOST = 0x16;
  static final int GATT_ERROR = 0x85;  // 133
  static final long DIRECT_CONNECT_TIMEOUT_MS = 30_000;

  static final class Client {
    int id;
    IBluetoothGattCallback cb;
    String address;
    boolean connected;
    boolean pending;  // connecting: direct (with a timeout) or in the background (autoConnect)
    boolean direct;
    int mtu = 23;
    int txPhy = BluetoothDevice.PHY_LE_1M;
    int rxPhy = BluetoothDevice.PHY_LE_1M;
    /** Bumped whenever the link comes or goes: a delayed answer for an older link is dropped (answer). */
    int epoch;
    /** Bumped by every clientConnect: a direct attempt's 30 s timeout ends only that attempt. */
    int attempt;
    final Set<Integer> notify = new HashSet<>();
  }

  static final class Scanner {
    int id;
    IScannerCallback cb;
    PendingIntent intent;
    ScanSettings settings;
    List<ScanFilter> filters;
    boolean started;
    boolean location;  // results may be delivered (location rules), decided at start as the stack does
    boolean disavowed;  // BLUETOOTH_SCAN is neverForLocation (target 31+): location never matters
    final Set<String> found = new HashSet<>();
    final List<ScanResult> batch = new ArrayList<>();

    boolean opportunistic() {
      return settings.getScanMode() == ScanSettings.SCAN_MODE_OPPORTUNISTIC;
    }
  }

  // ---- reset, launch, adapter ----

  /** At the application's (a test's) start: the launch's peripherals (`--ble`), nothing connected or scanning. */
  public static void reset(Application app) {
    synchronized (LOCK) {
      generation++;
      if (clock != null) clock.removeCallbacksAndMessages(token);
      token = new Object();
      clock = new Handler(Looper.getMainLooper());
      peripherals.clear();
      clients.clear();
      scanners.clear();
      linkCcc.clear();
      bonded.clear();
      pairing.clear();
      scanStarts.clear();
      BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
      adapterOn = a != null && a.isEnabled();
      for (int i = 0; System.getProperty("apksim.ble." + i) != null; i++) {
        String s = System.getProperty("apksim.ble." + i);
        try {
          for (Peripheral p : Peripheral.load(s)) addLocked(p);
        } catch (Exception e) {
          System.err.println("[apksim] --ble " + s + ": " + e.getMessage());
        }
      }
    }
  }

  /** The adapter switched (Bluetooth): off stops every scan and drops every connection, as the stack shutting down. */
  public static void adapterSwitched(boolean on) {
    synchronized (LOCK) {
      if (adapterOn == on) return;
      adapterOn = on;
      if (on) {
        for (Client c : clients.values()) if (c.pending && !c.direct) tryConnect(c);
        return;
      }
      scanners.clear();
      for (Client c : clients.values()) {
        if (c.connected || (c.pending && c.direct)) {
          boolean wasConnected = c.connected;
          c.connected = false;
          c.epoch++;
          c.pending = !c.direct;
          if (wasConnected || c.direct) answer(null, c, cb -> cb.onClientConnectionState(GATT_CONN_TERMINATE_LOCAL_HOST, c.id, false, c.address));
        }
      }
      linkCcc.keySet().removeIf(a -> !bonded.contains(a));
      // Pairing in progress fails as the stack goes down, whatever happens to the adapter after.
      for (String address : new ArrayList<>(pairing.keySet())) {
        pairing.remove(address);
        Devices.bondState(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address), BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING);
      }
    }
  }

  static boolean adapterOn() {
    synchronized (LOCK) {
      return adapterOn;
    }
  }

  // ---- delivery ----

  interface GattCall {
    void call(IBluetoothGattCallback cb) throws RemoteException;
  }

  interface ScanCall {
    void call(IScannerCallback cb) throws RemoteException;
  }

  /** Runs [r] on the binder thread, unless a reset came first. Under LOCK. */
  private static void deliver(Runnable r) {
    deliverOn(binder, r);
  }

  private static void deliverOn(ExecutorService thread, Runnable r) {
    int gen = generation;
    thread.execute(() -> {
      synchronized (LOCK) {
        if (gen != generation) return;
      }
      try {
        r.run();
      } catch (RuntimeException e) {
        System.err.println("[apksim] ble: " + e);
      }
    });
  }

  /** A GATT answer to [c], after the peripheral's latency. Under LOCK. */
  private static void answer(Peripheral p, Client c, GattCall call) {
    Runnable send = () -> deliver(() -> {
      try {
        call.call(c.cb);
      } catch (RemoteException e) {
        throw e.rethrowAsRuntimeException();
      }
    });
    if (p == null || p.latencyMs == 0) {
      send.run();
    } else {
      int epoch = c.epoch;
      // An answer from a link that has since ended (or a client unregistered) is never delivered.
      later(p.latencyMs, () -> {
        if (c.epoch == epoch && clients.get(c.id) == c) send.run();
      });
    }
  }

  private static void toScanner(Scanner s, ScanCall call) {
    if (s.cb == null) return;
    deliver(() -> {
      try {
        call.call(s.cb);
      } catch (RemoteException e) {
        throw e.rethrowAsRuntimeException();
      }
    });
  }

  /** [r] under LOCK on the main looper after [ms] of uptime, unless a reset came first. Under LOCK. */
  private static void later(long ms, Runnable r) {
    int gen = generation;
    clock.postDelayed(() -> {
      synchronized (LOCK) {
        if (gen == generation) r.run();
      }
    }, token, Math.max(0, ms));
  }

  // ---- permissions, as a device's Bluetooth service enforces them ----

  private static Application app() {
    return RuntimeEnvironment.getApplication();
  }

  private static int target() {
    return app().getApplicationInfo().targetSdkVersion;
  }

  private static boolean granted(String permission) {
    return app().checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
  }

  private static void need(String permission, String op, AttributionSource source) {
    if (granted(permission)) return;
    String name = permission.startsWith("android.permission.") && target() < 31 ? permission.substring(19) : permission;
    throw new SecurityException("Need " + name + " permission for " + source + ": " + op);
  }

  /** BLUETOOTH_SCAN from API 31, else the legacy BLUETOOTH_ADMIN. */
  static void enforceScan(String op, AttributionSource source) {
    need(target() >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.BLUETOOTH_ADMIN, op, source);
  }

  /** BLUETOOTH_CONNECT from API 31, else the legacy BLUETOOTH. */
  static void enforceConnect(String op, AttributionSource source) {
    need(target() >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH, op, source);
  }

  /**
   * Whether a scan's results may reach the app: from API 31 with BLUETOOTH_SCAN marked neverForLocation, always;
   * otherwise only with location on and ACCESS_FINE_LOCATION (API 29+), or either location permission below 29.
   * A scan without it runs and finds nothing, with no error, as on a device.
   */
  static boolean locationAllowsResults() {
    if (target() >= 31 && neverForLocation()) return true;
    if (!locationEnabled()) return false;
    if (target() >= 29) return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    return granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
  }

  static boolean neverForLocation() {
    try {
      PackageInfo pi = app().getPackageManager().getPackageInfo(app().getPackageName(), PackageManager.GET_PERMISSIONS);
      if (pi.requestedPermissions == null || pi.requestedPermissionsFlags == null) return false;
      for (int i = 0; i < pi.requestedPermissions.length; i++) {
        if (Manifest.permission.BLUETOOTH_SCAN.equals(pi.requestedPermissions[i])) {
          return (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0;
        }
      }
    } catch (PackageManager.NameNotFoundException e) {
      // not installed: no flags
    }
    return false;
  }

  static boolean locationEnabled() {
    LocationManager lm = app().getSystemService(LocationManager.class);
    return lm == null || lm.isLocationEnabled();
  }

  // ---- peripherals ----

  private static void addLocked(Peripheral p) {
    Peripheral old = peripherals.remove(p.address);
    if (old != null) goneLocked(old, GATT_CONN_TIMEOUT);
    peripherals.put(p.address, p);
    for (Peripheral.Service s : p.services) {
      for (Peripheral.Characteristic c : s.characteristics) {
        if (c.timeline != null) {
          c.timelineStart = c.valueAt = SystemClock.uptimeMillis();
          c.ticking = false;
        }
      }
    }
    for (Scanner s : new ArrayList<>(scanners.values())) if (s.started) sightings(s, p);
    for (Client c : clients.values()) if (c.pending && p.address.equals(c.address)) tryConnect(c);
  }

  /** [p] stopped advertising and dropped its links: connections end with [status], scans lose it. */
  private static void goneLocked(Peripheral p, int status) {
    for (Client c : clients.values()) {
      if (!p.address.equals(c.address) || !c.connected) continue;
      c.connected = false;
      c.epoch++;
      c.pending = !c.direct;  // a background (autoConnect) connection waits for it to come back
      answer(null, c, cb -> cb.onClientConnectionState(status, c.id, false, c.address));
    }
    if (!bonded.contains(p.address)) linkCcc.remove(p.address);
    for (Scanner s : scanners.values()) {
      if (s.found.remove(p.address) && deliverable(s) && (s.settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
        ScanResult r = result(p);
        if (s.intent != null) {
          sendIntent(s, ScanSettings.CALLBACK_TYPE_MATCH_LOST, List.of(r));
        } else {
          toScanner(s, cb -> cb.onFoundOrLost(false, r));
        }
      }
    }
  }

  /**
   * A characteristic's value now. A timeline is a function of uptime since the peripheral arrived (step k at
   * start + k * everyMs), computed when read, so a peripheral nobody listens to posts nothing and an idle screen can
   * settle; an explicit value (a write, `ble set|notify`) holds until the next step after it.
   */
  static byte[] current(Peripheral.Characteristic c) {
    if (c.timeline == null) return c.value;
    long ticks = (SystemClock.uptimeMillis() - c.timelineStart) / c.timelineEveryMs;
    if (ticks <= 0) return c.value;
    int n = c.timeline.size();
    long k = c.timelineLoop ? ticks - 1 : Math.min(ticks - 1, n - 1);
    long stepAt = c.timelineStart + (k + 1) * c.timelineEveryMs;
    return stepAt > c.valueAt ? c.timeline.get((int) (k % n)) : c.value;
  }

  static void setValue(Peripheral.Characteristic c, byte[] value) {
    c.value = value.clone();
    c.valueAt = SystemClock.uptimeMillis();
  }

  /** Whether some connected client gets [c]'s notifications: registered for them, and the link's CCC asking. */
  private static boolean subscribed(Peripheral p, Peripheral.Characteristic c) {
    Peripheral.Descriptor ccc = null;
    for (Peripheral.Descriptor d : c.descriptors) if (d.uuid.equals(Peripheral.CCC)) ccc = d;
    if (ccc == null) return false;
    byte[] v = linkCcc.getOrDefault(p.address, Map.of()).get(ccc.handle);
    if (v == null || v.length == 0 || (v[0] & 0x03) == 0) return false;
    for (Client cl : clients.values()) if (cl.connected && p.address.equals(cl.address) && cl.notify.contains(c.handle)) return true;
    return false;
  }

  /** Schedules the next timeline step of each of [p]'s characteristics someone is subscribed to, in phase. */
  private static void timelines(Peripheral p) {
    for (Peripheral.Service s : p.services) {
      for (Peripheral.Characteristic c : s.characteristics) {
        if (c.timeline == null || c.ticking || !subscribed(p, c)) continue;
        long now = SystemClock.uptimeMillis();
        long ticks = (now - c.timelineStart) / c.timelineEveryMs;
        if (!c.timelineLoop && ticks >= c.timeline.size()) continue;  // the timeline has ended
        c.ticking = true;
        later(c.timelineStart + (ticks + 1) * c.timelineEveryMs - now, () -> {
          c.ticking = false;
          if (peripherals.get(p.address) != p || !subscribed(p, c)) return;
          notifyLocked(p, c);
          timelines(p);
        });
      }
    }
  }

  /** The characteristic's value to every client that registered for it on a link whose CCC asks for it. */
  private static void notifyLocked(Peripheral p, Peripheral.Characteristic c) {
    Peripheral.Descriptor ccc = null;
    for (Peripheral.Descriptor d : c.descriptors) if (d.uuid.equals(Peripheral.CCC)) ccc = d;
    if (ccc == null) return;
    byte[] v = linkCcc.getOrDefault(p.address, Map.of()).get(ccc.handle);
    if (v == null || v.length == 0 || (v[0] & 0x03) == 0) return;
    byte[] value = current(c).clone();
    for (Client cl : clients.values()) {
      if (cl.connected && p.address.equals(cl.address) && cl.notify.contains(c.handle)) {
        byte[] own = value.clone();  // each client its own copy, as each gets its own parcel
        answer(null, cl, cb -> cb.onNotify(p.address, c.handle, own));
      }
    }
  }

  private static boolean linked(String address) {
    for (Client c : clients.values()) if (c.connected && address.equals(c.address)) return true;
    return false;
  }

  // ---- scanning ----

  private static ScanResult result(Peripheral p) {
    BluetoothDevice device = Devices.seen(p);
    int eventType = 0x10 | (p.connectable ? 0x03 : 0);  // legacy ADV_IND (connectable, scannable) or ADV_NONCONN_IND
    return new ScanResult(device, eventType, BluetoothDevice.PHY_LE_1M, ScanResult.PHY_UNUSED, ScanResult.SID_NOT_PRESENT,
        p.txPower == null ? ScanResult.TX_POWER_NOT_PRESENT : p.txPower, p.rssi, 0,
        ScanRecord.parseFromBytes(p.advertisingBytes()), SystemClock.elapsedRealtimeNanos());
  }

  private static boolean matches(Scanner s, ScanResult r) {
    if (s.filters == null || s.filters.isEmpty()) return true;
    for (ScanFilter f : s.filters) if (f.matches(r)) return true;
    return false;
  }

  /** Whether a non-opportunistic scan runs, which an opportunistic one rides on. */
  private static boolean radioScanning() {
    for (Scanner s : scanners.values()) if (s.started && !s.opportunistic()) return true;
    return false;
  }

  /** Reports [p] to [s] now and at each advertising interval while both last. */
  private static void sightings(Scanner s, Peripheral p) {
    if (scanners.get(s.id) != s || peripherals.get(p.address) != p || !adapterOn) return;
    sight(s, p);
    later(p.intervalMs, () -> sightings(s, p));
  }

  /**
   * Whether results may reach the app now. The location permission is judged as the scan starts (the stack decides it
   * then, and a permission granted later does not wake a running scan); at each delivery the scan permission and the
   * location permission must still be held and location still be on (the stack's blockedByLocationOff), unless the
   * scan is neverForLocation. A device kills an app whose runtime permission is revoked; apksim does not
   * (`pm revoke` in a test run), so a revoked permission stops the results instead.
   */
  private static boolean deliverable(Scanner s) {
    if (!s.location) return false;
    if (!granted(target() >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.BLUETOOTH_ADMIN)) return false;
    return s.disavowed || locationAllowsResults();
  }

  private static void sight(Scanner s, Peripheral p) {
    if (!deliverable(s)) return;
    if (s.opportunistic() && !radioScanning()) return;
    ScanResult r = result(p);
    if (!matches(s, r)) return;
    int type = s.settings.getCallbackType();
    if ((type & (ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST)) != 0) {
      if (s.found.add(p.address) && (type & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0) {
        if (s.intent != null) {
          sendIntent(s, ScanSettings.CALLBACK_TYPE_FIRST_MATCH, List.of(r));
        } else {
          toScanner(s, cb -> cb.onFoundOrLost(true, r));
        }
      }
      return;
    }
    if (s.settings.getReportDelayMillis() > 0) {
      s.batch.add(r);
    } else if (s.intent != null) {
      sendIntent(s, ScanSettings.CALLBACK_TYPE_ALL_MATCHES, List.of(r));
    } else {
      toScanner(s, cb -> cb.onScanResult(r));
    }
  }

  private static void flushBatch(Scanner s) {
    if (s.batch.isEmpty()) return;
    if (!deliverable(s)) {
      s.batch.clear();
      return;
    }
    List<ScanResult> list = new ArrayList<>(s.batch);
    s.batch.clear();
    if (s.intent != null) {
      sendIntent(s, ScanSettings.CALLBACK_TYPE_ALL_MATCHES, list);
    } else {
      toScanner(s, cb -> cb.onBatchScanResults(list));
    }
  }

  private static void batches(Scanner s) {
    if (scanners.get(s.id) != s) return;
    flushBatch(s);
    later(s.settings.getReportDelayMillis(), () -> batches(s));
  }

  private static void sendIntent(Scanner s, int callbackType, List<ScanResult> results) {
    Intent fill = new Intent()
        .putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, callbackType)
        .putParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, new ArrayList<>(results));
    PendingIntent pi = s.intent;
    deliver(() -> {
      try {
        pi.send(app(), 0, fill);
      } catch (PendingIntent.CanceledException e) {
        synchronized (LOCK) {
          scanners.remove(s.id);
        }
      }
    });
  }

  private static void startScanner(Scanner s) {
    s.started = true;
    s.location = locationAllowsResults();
    s.disavowed = target() >= 31 && neverForLocation();
    scanStarts.add(SystemClock.elapsedRealtime());
    for (Peripheral p : new ArrayList<>(peripherals.values())) sightings(s, p);
    if (s.settings.getReportDelayMillis() > 0) later(s.settings.getReportDelayMillis(), () -> batches(s));
  }

  /** Android 7+ refuses a sixth scan start within 30 s (AppScanStats): the app hears nothing at all. */
  private static boolean scanningTooFrequently() {
    long now = SystemClock.elapsedRealtime();
    while (!scanStarts.isEmpty() && now - scanStarts.peekFirst() > 30_000) scanStarts.pollFirst();
    return scanStarts.size() >= 5;
  }

  /** The system side of BluetoothLeScanner (IBluetoothScan). */
  static final class ScanService extends IBluetoothScan.Default {
    @Override
    public void registerScanner(IScannerCallback callback, WorkSource ws, AttributionSource source) {
      enforceScan("ScanController registerScanner", source);
      synchronized (LOCK) {
        int id = nextId++;
        boolean tooOften = scanningTooFrequently();
        if (!tooOften) {
          Scanner s = new Scanner();
          s.id = id;
          s.cb = callback;
          scanners.put(id, s);
        }
        // From another thread: the caller waits for it (BleScanCallbackWrapper.startRegistration).
        deliverOn(registrar, () -> {
          try {
            callback.onScannerRegistered(tooOften ? ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY : GATT_SUCCESS, tooOften ? -1 : id);
          } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
          }
        });
      }
    }

    @Override
    public void unregisterScanner(int scannerId, AttributionSource source) {
      synchronized (LOCK) {
        scanners.remove(scannerId);
      }
    }

    @Override
    public void startScan(int scannerId, ScanSettings settings, List<ScanFilter> filters, AttributionSource source) {
      enforceScan("ScanController startScan", source);
      synchronized (LOCK) {
        Scanner s = scanners.get(scannerId);
        if (s == null || s.started) return;
        s.settings = settings;
        s.filters = filters;
        startScanner(s);
      }
    }

    @Override
    public void startScanForIntent(PendingIntent intent, ScanSettings settings, List<ScanFilter> filters, AttributionSource source) {
      enforceScan("ScanController startScanForIntent", source);
      synchronized (LOCK) {
        stopScanForIntent(intent, source);
        if (scanningTooFrequently()) return;  // as a callback scan: nothing starts and nothing is sent
        Scanner s = new Scanner();
        s.id = nextId++;
        s.intent = intent;
        s.settings = settings;
        s.filters = filters;
        scanners.put(s.id, s);
        startScanner(s);
      }
    }

    @Override
    public void stopScan(int scannerId, AttributionSource source) {
      synchronized (LOCK) {
        Scanner s = scanners.remove(scannerId);
        if (s != null && s.settings != null && s.settings.getReportDelayMillis() > 0) flushBatch(s);
      }
    }

    @Override
    public void stopScanForIntent(PendingIntent intent, AttributionSource source) {
      synchronized (LOCK) {
        scanners.values().removeIf(s -> s.intent != null && s.intent.equals(intent));
      }
    }

    @Override
    public void flushPendingBatchResults(int scannerId, AttributionSource source) {
      synchronized (LOCK) {
        Scanner s = scanners.get(scannerId);
        if (s != null) flushBatch(s);
      }
    }

    @Override
    public int numHwTrackFiltersAvailable(AttributionSource source) {
      return 32;
    }
  }

  // ---- GATT ----

  private static void tryConnect(Client c) {
    Peripheral p = peripherals.get(c.address);
    if (p == null || !p.connectable || !adapterOn) return;
    c.pending = false;
    c.connected = true;
    c.epoch++;
    // MTU and PHY belong to the link: a client joining one already up gets what was negotiated on it.
    Client joined = null;
    for (Client o : clients.values()) if (o != c && o.connected && c.address.equals(o.address)) joined = o;
    c.mtu = joined != null ? joined.mtu : 23;
    c.txPhy = joined != null ? joined.txPhy : BluetoothDevice.PHY_LE_1M;
    c.rxPhy = joined != null ? joined.rxPhy : BluetoothDevice.PHY_LE_1M;
    timelines(p);  // a bonded link's CCC survives a reconnect
    answer(p, c, cb -> cb.onClientConnectionState(GATT_SUCCESS, c.id, true, c.address));
  }

  private static Client client(int clientIf, String address) {
    Client c = clients.get(clientIf);
    return c != null && c.connected && c.address != null && c.address.equals(address) ? c : null;
  }

  /** The system side of BluetoothGatt (IBluetoothGatt); the GATT server and advertising parts stay no-ops. */
  static final class GattService extends IBluetoothGatt.Default {
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states, AttributionSource source) {
      enforceConnect("getDevicesMatchingConnectionStates", source);
      List<BluetoothDevice> out = new ArrayList<>();
      synchronized (LOCK) {
        Set<String> seen = new HashSet<>();
        for (Peripheral p : peripherals.values()) {
          int state = linked(p.address) ? BluetoothProfile.STATE_CONNECTED : BluetoothProfile.STATE_DISCONNECTED;
          if (Arrays.stream(states).anyMatch(x -> x == state) && seen.add(p.address)) out.add(Devices.device(p.address));
        }
      }
      return out;
    }

    @Override
    public void registerClient(ParcelUuid uuid, IBluetoothGattCallback callback, boolean eatt, AttributionSource source) {
      enforceConnect("GattService registerClient", source);
      synchronized (LOCK) {
        Client c = new Client();
        c.id = nextId++;
        c.cb = callback;
        clients.put(c.id, c);
        answer(null, c, cb -> cb.onClientRegistered(GATT_SUCCESS, c.id));
      }
    }

    @Override
    public void unregisterClient(int clientIf, AttributionSource source) {
      synchronized (LOCK) {
        Client c = clients.remove(clientIf);
        if (c != null) c.epoch++;
        // close() without disconnect(): the link goes with the last client on it, and nobody is told.
        if (c != null && c.connected && !linked(c.address) && !bonded.contains(c.address)) linkCcc.remove(c.address);
      }
    }

    @Override
    public void clientConnect(int clientIf, String address, int addressType, boolean isDirect, int transport,
        boolean opportunistic, int phy, AttributionSource source) {
      enforceConnect("GattService clientConnect", source);
      synchronized (LOCK) {
        Client c = clients.get(clientIf);
        if (c == null) return;
        c.address = address;
        c.direct = isDirect;
        c.pending = true;
        int attempt = ++c.attempt;
        tryConnect(c);
        if (c.pending && isDirect) {
          // A direct connection to nothing in range fails after ~30 s with GATT_ERROR (133).
          later(DIRECT_CONNECT_TIMEOUT_MS, () -> {
            if (clients.get(clientIf) == c && c.attempt == attempt && c.direct && c.pending) {
              c.pending = false;
              answer(null, c, cb -> cb.onClientConnectionState(GATT_ERROR, clientIf, false, address));
            }
          });
        }
      }
    }

    @Override
    public void clientDisconnect(int clientIf, String address, AttributionSource source) {
      enforceConnect("GattService clientDisconnect", source);
      synchronized (LOCK) {
        Client c = clients.get(clientIf);
        if (c == null || !(c.connected || c.pending)) return;
        c.connected = false;
        c.pending = false;
        c.epoch++;
        if (!linked(address) && !bonded.contains(address)) linkCcc.remove(address);
        answer(peripherals.get(address), c, cb -> cb.onClientConnectionState(GATT_SUCCESS, clientIf, false, address));
      }
    }

    @Override
    public void clientSetPreferredPhy(int clientIf, String address, int txPhy, int rxPhy, int phyOptions, AttributionSource source) {
      enforceConnect("GattService clientSetPreferredPhy", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        if (c == null) return;
        int tx = lowestPhy(txPhy), rx = lowestPhy(rxPhy);
        for (Client o : clients.values()) {
          if (o.connected && address.equals(o.address)) {  // one link
            o.txPhy = tx;
            o.rxPhy = rx;
          }
        }
        answer(peripherals.get(address), c, cb -> cb.onPhyUpdate(address, tx, rx, GATT_SUCCESS));
      }
    }

    private static int lowestPhy(int mask) {
      if ((mask & BluetoothDevice.PHY_LE_2M_MASK) != 0) return BluetoothDevice.PHY_LE_2M;
      if ((mask & BluetoothDevice.PHY_LE_CODED_MASK) != 0 && (mask & BluetoothDevice.PHY_LE_1M_MASK) == 0) return BluetoothDevice.PHY_LE_CODED;
      return BluetoothDevice.PHY_LE_1M;
    }

    @Override
    public void clientReadPhy(int clientIf, String address, AttributionSource source) {
      enforceConnect("GattService clientReadPhy", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        if (c == null) return;
        int tx = c.txPhy, rx = c.rxPhy;
        answer(peripherals.get(address), c, cb -> cb.onPhyRead(address, tx, rx, GATT_SUCCESS));
      }
    }

    @Override
    public void discoverServices(int clientIf, String address, AttributionSource source) {
      discover(clientIf, address, null, source);
    }

    @Override
    public void discoverServiceByUuid(int clientIf, String address, ParcelUuid uuid, AttributionSource source) {
      discover(clientIf, address, uuid.getUuid(), source);
    }

    private void discover(int clientIf, String address, UUID only, AttributionSource source) {
      enforceConnect("GattService discoverServices", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return;  // no connection: the stack logs it and answers nothing
        var services = p.gattServices();
        if (only != null) services.removeIf(s -> !s.getUuid().equals(only));
        answer(p, c, cb -> cb.onSearchComplete(address, services, GATT_SUCCESS));
      }
    }

    @Override
    public void readCharacteristic(int clientIf, String address, int handle, int authReq, AttributionSource source) {
      enforceConnect("GattService readCharacteristic", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return;
        Peripheral.Characteristic ch = p.characteristic(handle);
        if (ch == null) {
          answer(p, c, cb -> cb.onCharacteristicRead(address, GATT_INVALID_HANDLE, handle, new byte[0]));
        } else if ((ch.properties & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
          answer(p, c, cb -> cb.onCharacteristicRead(address, GATT_READ_NOT_PERMITTED, handle, new byte[0]));
        } else {
          byte[] v = current(ch).clone();
          answer(p, c, cb -> cb.onCharacteristicRead(address, GATT_SUCCESS, handle, v));
        }
      }
    }

    @Override
    public void readUsingCharacteristicUuid(int clientIf, String address, ParcelUuid uuid, int startHandle, int endHandle,
        int authReq, AttributionSource source) {
      enforceConnect("GattService readUsingCharacteristicUuid", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return;
        Peripheral.Characteristic ch = p.characteristic(uuid.getUuid());
        if (ch == null || ch.handle < startHandle || ch.handle > endHandle) {
          answer(p, c, cb -> cb.onCharacteristicRead(address, GATT_INVALID_HANDLE, 0, new byte[0]));
        } else {
          byte[] v = current(ch).clone();
          answer(p, c, cb -> cb.onCharacteristicRead(address, GATT_SUCCESS, ch.handle, v));
        }
      }
    }

    @Override
    public int writeCharacteristic(int clientIf, String address, int handle, int writeType, int authReq, byte[] value,
        AttributionSource source) {
      enforceConnect("GattService writeCharacteristic", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        Peripheral.Characteristic ch = p.characteristic(handle);
        int status;
        if (ch == null) {
          status = GATT_INVALID_HANDLE;
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ? (ch.properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0
            : (ch.properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE)) == 0) {
          status = GATT_WRITE_NOT_PERMITTED;
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && value.length > c.mtu - 3) {
          status = GATT_INVALID_ATTRIBUTE_LENGTH;  // one packet: no long write without a response
        } else {
          status = GATT_SUCCESS;
          setValue(ch, value);
          ch.writes++;
          ch.lastWritten = value.clone();
        }
        byte[] v = value.clone();
        // Write without response is answered too, once the packet is sent (Android's flow control).
        answer(p, c, cb -> cb.onCharacteristicWrite(address, status, handle, v));
        return BluetoothStatusCodes.SUCCESS;
      }
    }

    @Override
    public void readDescriptor(int clientIf, String address, int handle, int authReq, AttributionSource source) {
      enforceConnect("GattService readDescriptor", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return;
        Object[] cd = p.descriptor(handle);
        if (cd == null) {
          answer(p, c, cb -> cb.onDescriptorRead(address, GATT_INVALID_HANDLE, handle, new byte[0]));
          return;
        }
        Peripheral.Descriptor d = (Peripheral.Descriptor) cd[1];
        byte[] v = d.uuid.equals(Peripheral.CCC)
            ? linkCcc.getOrDefault(address, Map.of()).getOrDefault(handle, new byte[] {0, 0}).clone()
            : d.value.clone();
        answer(p, c, cb -> cb.onDescriptorRead(address, GATT_SUCCESS, handle, v));
      }
    }

    @Override
    public int writeDescriptor(int clientIf, String address, int handle, int authReq, byte[] value, AttributionSource source) {
      enforceConnect("GattService writeDescriptor", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        Object[] cd = p.descriptor(handle);
        int status = GATT_SUCCESS;
        if (cd == null) {
          status = GATT_INVALID_HANDLE;
        } else {
          Peripheral.Descriptor d = (Peripheral.Descriptor) cd[1];
          if (d.uuid.equals(Peripheral.CCC)) {
            if (value.length != 2) {
              status = GATT_INVALID_ATTRIBUTE_LENGTH;
            } else {
              linkCcc.computeIfAbsent(address, k -> new HashMap<>()).put(handle, value.clone());
              timelines(p);
            }
          } else {
            d.value = value.clone();
          }
        }
        int st = status;
        byte[] v = value.clone();
        answer(p, c, cb -> cb.onDescriptorWrite(address, st, handle, v));
        return BluetoothStatusCodes.SUCCESS;
      }
    }

    @Override
    public void registerForNotification(int clientIf, String address, int handle, boolean enable, AttributionSource source) {
      enforceConnect("GattService registerForNotification", source);
      synchronized (LOCK) {
        Client c = clients.get(clientIf);
        if (c == null) return;
        if (enable) {
          c.notify.add(handle);
        } else {
          c.notify.remove(handle);
        }
        Peripheral p = c.address == null ? null : peripherals.get(c.address);
        if (p != null) timelines(p);
      }
    }

    @Override
    public void beginReliableWrite(int clientIf, String address, AttributionSource source) {
      enforceConnect("GattService beginReliableWrite", source);
    }

    @Override
    public void endReliableWrite(int clientIf, String address, boolean execute, AttributionSource source) {
      enforceConnect("GattService endReliableWrite", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        if (c != null && execute) answer(peripherals.get(address), c, cb -> cb.onExecuteWrite(address, GATT_SUCCESS));
      }
    }

    @Override
    public void readRemoteRssi(int clientIf, String address, AttributionSource source) {
      enforceConnect("GattService readRemoteRssi", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return;
        int rssi = p.rssi;
        answer(p, c, cb -> cb.onReadRemoteRssi(address, rssi, GATT_SUCCESS));
      }
    }

    @Override
    public void configureMTU(int clientIf, String address, int mtu, AttributionSource source) {
      enforceConnect("GattService configureMTU", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        Peripheral p = peripherals.get(address);
        if (c == null || p == null) return;
        int agreed = Math.max(23, Math.min(Math.min(mtu, 517), p.mtu));
        for (Client o : clients.values()) if (o.connected && address.equals(o.address)) o.mtu = agreed;  // one link
        answer(p, c, cb -> cb.onConfigureMTU(address, agreed, GATT_SUCCESS));
      }
    }

    @Override
    public void connectionParameterUpdate(int clientIf, String address, int priority, AttributionSource source) {
      enforceConnect("GattService connectionParameterUpdate", source);
      synchronized (LOCK) {
        Client c = client(clientIf, address);
        if (c == null) return;
        // AOSP's defaults per priority (interval in 1.25 ms units): high 6-8, balanced 24-40, low power 80-100.
        int interval = priority == BluetoothGatt.CONNECTION_PRIORITY_HIGH ? 6 : priority == BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER ? 80 : 36;
        int latency = priority == BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER ? 2 : 0;
        answer(peripherals.get(address), c, cb -> cb.onConnectionUpdated(address, interval, latency, 500, GATT_SUCCESS));
      }
    }
  }

  static final ScanService SCAN = new ScanService();
  static final GattService GATT = new GattService();

  /** The binders BluetoothAdapter hands out: none while the adapter is off, as getBluetoothGatt() then returns null. */
  public static IBluetoothGatt gattService() {
    return adapterOn() ? GATT : null;
  }

  public static IBluetoothScan scanService() {
    return adapterOn() ? SCAN : null;
  }

  // ---- bonding ----

  /** createBond(): BONDING then BONDED (or back to NONE for a peripheral that is not there or refuses), broadcast. */
  static boolean createBond(BluetoothDevice device) {
    enforceConnect("AdapterService createBond", app().getAttributionSource());
    String address = device.getAddress();
    synchronized (LOCK) {
      if (!adapterOn) return false;
      if (bonded.contains(address) || pairing.containsKey(address)) return false;
      Peripheral p = peripherals.get(address);
      boolean ok = p != null && p.bondable && p.connectable;
      Object attempt = new Object();
      pairing.put(address, attempt);
      Devices.bondState(device, BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE);
      later(p != null ? Math.max(p.latencyMs, 100) : 100, () -> {
        if (pairing.get(address) != attempt) return;  // ended already (the adapter went off)
        pairing.remove(address);
        // Pairing interrupted (the peripheral gone or replaced) ends unbonded.
        boolean done = ok && adapterOn && peripherals.get(address) == p;
        if (done) bonded.add(address);
        Devices.bondState(device, done ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING);
      });
      return true;
    }
  }

  static boolean removeBond(BluetoothDevice device) {
    enforceConnect("AdapterService removeBond", app().getAttributionSource());
    synchronized (LOCK) {
      if (!bonded.remove(device.getAddress())) return false;
      if (!linked(device.getAddress())) linkCcc.remove(device.getAddress());
      Devices.bondState(device, BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDED);
      return true;
    }
  }

  static Set<String> bondedAddresses() {
    synchronized (LOCK) {
      return new HashSet<>(bonded);
    }
  }

  // ---- the `ble` command ----

  /** ble | ble add FILE|PRESET[:ADDR] | remove ADDR|all | rssi ADDR N | notify ADDR CHAR HEX | set ADDR CHAR HEX | disconnect ADDR */
  public static String command(String[] a) {
    String op = a.length > 1 ? a[1] : "";
    try {
      synchronized (LOCK) {
        if (clock == null) return "error: the app has not started yet";
        switch (op) {
          case "" -> {
            return status();
          }
          case "add" -> {
            if (a.length < 3) return "usage: ble add FILE|PRESET[:ADDRESS] (presets: " + Peripheral.PRESETS + ")";
            List<String> added = new ArrayList<>();
            for (Peripheral p : Peripheral.load(String.join(" ", Arrays.copyOfRange(a, 2, a.length)))) {
              addLocked(p);
              added.add(p.address);
            }
            return "ok (" + String.join(", ", added) + ")";
          }
          case "remove" -> {
            if (a.length < 3) return "usage: ble remove ADDRESS|all";
            List<Peripheral> gone = new ArrayList<>();
            if (a[2].equals("all")) {
              gone.addAll(peripherals.values());
            } else {
              Peripheral p = peripherals.get(Peripheral.normalizeAddress(a[2]));
              if (p == null) return "error: no peripheral " + a[2];
              gone.add(p);
            }
            for (Peripheral p : gone) {
              peripherals.remove(p.address);
              goneLocked(p, GATT_CONN_TIMEOUT);  // out of range: the link times out
            }
            return "ok (" + gone.size() + " removed)";
          }
          case "rssi" -> {
            if (a.length < 4) return "usage: ble rssi ADDRESS DBM";
            Peripheral p = need(a[2]);
            p.rssi = Integer.parseInt(a[3]);
            return "ok";
          }
          case "notify", "set" -> {
            if (a.length < 5) return "usage: ble " + op + " ADDRESS CHARACTERISTIC HEX";
            Peripheral p = need(a[2]);
            Peripheral.Characteristic c = p.characteristic(Peripheral.uuid(a[3]));
            if (c == null) return "error: " + a[2] + " has no characteristic " + a[3];
            setValue(c, Peripheral.hex(a[4]));
            if (op.equals("notify")) notifyLocked(p, c);
            return "ok";
          }
          case "disconnect" -> {
            if (a.length < 3) return "usage: ble disconnect ADDRESS";
            Peripheral p = need(a[2]);
            int n = 0;
            for (Client c : clients.values()) {
              if (!c.connected || !p.address.equals(c.address)) continue;
              n++;
              c.connected = false;
              c.epoch++;
              c.pending = !c.direct;
              answer(null, c, cb -> cb.onClientConnectionState(GATT_CONN_TERMINATE_PEER_USER, c.id, false, c.address));
            }
            if (!bonded.contains(p.address)) linkCcc.remove(p.address);
            // A background connection comes back once the peripheral advertises again.
            later(p.intervalMs, () -> {
              if (peripherals.get(p.address) != p) return;
              for (Client c : clients.values()) if (c.pending && !c.direct && p.address.equals(c.address)) tryConnect(c);
            });
            return "ok (" + n + " disconnected)";
          }
          default -> {
            return "usage: ble [add FILE|PRESET[:ADDRESS] | remove ADDRESS|all | rssi ADDRESS DBM | notify|set ADDRESS CHAR HEX | disconnect ADDRESS]";
          }
        }
      }
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  /** A name from a scene file inside a one-line reply: no line breaks or bare quotes. */
  private static String oneLine(String s) {
    return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\"", "\\\"");
  }

  private static Peripheral need(String address) {
    Peripheral p = peripherals.get(Peripheral.normalizeAddress(address));
    if (p == null) throw new IllegalArgumentException("no peripheral " + address);
    return p;
  }

  private static String status() {
    List<String> lines = new ArrayList<>();
    for (Peripheral p : peripherals.values()) {
      StringBuilder sb = new StringBuilder(p.address);
      if (p.name != null) sb.append(" \"").append(oneLine(p.name)).append('"');
      sb.append(" rssi ").append(p.rssi).append(p.connectable ? "" : " non-connectable");
      int links = 0, mtu = 23;
      for (Client c : clients.values()) {
        if (c.connected && p.address.equals(c.address)) {
          links++;
          mtu = c.mtu;
        }
      }
      sb.append(", ").append(links).append(links == 1 ? " connection" : " connections");
      if (links > 0) sb.append(" (mtu ").append(mtu).append(')');
      if (bonded.contains(p.address)) sb.append(", bonded");
      List<String> subscribed = new ArrayList<>();
      List<String> writes = new ArrayList<>();
      Map<Integer, byte[]> ccc = linkCcc.getOrDefault(p.address, Map.of());
      for (Peripheral.Service s : p.services) {
        for (Peripheral.Characteristic c : s.characteristics) {
          for (Peripheral.Descriptor d : c.descriptors) {
            byte[] v = ccc.get(d.handle);
            if (d.uuid.equals(Peripheral.CCC) && v != null && v.length > 0 && (v[0] & 3) != 0) subscribed.add(Peripheral.shortName(c.uuid));
          }
          if (c.writes > 0) writes.add(Peripheral.shortName(c.uuid) + "=" + Peripheral.hex(c.lastWritten) + (c.writes > 1 ? " (" + c.writes + " writes)" : ""));
        }
      }
      if (!subscribed.isEmpty()) sb.append(", notifying ").append(String.join(" ", subscribed));
      if (!writes.isEmpty()) sb.append(", written ").append(String.join(" ", writes));
      lines.add(sb.toString());
    }
    if (lines.isEmpty()) lines.add("no peripherals (ble add FILE|" + Peripheral.PRESETS.replace(", ", "|") + ")");
    long scans = scanners.values().stream().filter(s -> s.started).count();
    lines.add("scans " + scans + (adapterOn ? "" : ", adapter off"));
    return String.join("; ", lines);
  }
}
