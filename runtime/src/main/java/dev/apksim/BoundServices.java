package dev.apksim;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;

/**
 * bindService of the app's own services, as a device runs it (TestActivities.ShadowSimInstrumentation hands binds and
 * unbinds here). Robolectric never runs a service on bind: it calls onServiceConnected with whatever binder a test
 * registered, and with (null, null) when none was, so a Kotlin connection whose parameters are non-null threw, and a
 * service whose onBind returns null was reported as connected. Here, as in ActiveServices and LoadedApk, a binding
 * with BIND_AUTO_CREATE creates the service, onBind runs once per intent (Intent.filterEquals) and its binder is
 * kept, with the intent of the first binding for it (a device keeps that even if the binding ended before the
 * service ran); each binding then gets onServiceConnected with that binder, or onNullBinding (API 28) when it is
 * null, on the main thread (or the bind's Executor). As LoadedApk's ServiceDispatcher, a connection is not told
 * again of a binder it already has, but is told of a null binding once per binding (the API 36 emulator does both).
 * Unbinding never calls onServiceDisconnected on the connection unbound; the last connection of an intent gets the
 * service onUnbind (onRebind on the next bind if it returned true), and with no BIND_AUTO_CREATE connection left the
 * service is destroyed: connections still bound without the flag then get onServiceDisconnected and onBindingDied, as
 * for a service brought down on a device, and are not connected again. Service code and callbacks run from
 * the main looper, after bindService returns, as on a device.
 *
 * <p>Left to Robolectric: services outside the app's package or not in its manifest (Play Services, other apps), and
 * intents a test registered a binder for or declared unbindable through ShadowApplication (BackgroundWork.bindable
 * registers the FCM service that way). A bound service is not the instance startService runs (BackgroundWork), and
 * a service with android:process runs in this process with its own binder, not a proxy.
 */
final class BoundServices {
  private static BoundServices current;

  /** This application's bindings; a new test's Application starts with none, as a new process would. */
  static synchronized BoundServices of(Application app) {
    if (current == null || current.app != app) current = new BoundServices(app);
    return current;
  }

  private final Application app;
  private final Handler main = new Handler(Looper.getMainLooper());
  /** Connections bound here, by the app's ServiceConnection: guarded by itself, read from any thread. */
  private final Map<ServiceConnection, Dispatcher> connections = new HashMap<>();
  /** Everything below is used on the main thread only. */
  private final Map<ComponentName, Record> records = new HashMap<>();

  private BoundServices(Application app) {
    this.app = app;
  }

  /** One service: running while controller is set. */
  private static final class Record {
    final ComponentName name;
    final Class<? extends Service> cls;
    final Map<Intent.FilterComparison, Binding> bindings = new LinkedHashMap<>();
    ServiceController<? extends Service> controller;
    /** While its onCreate runs: Robolectric's create() runs the main looper, which may hold another binding's attach. */
    boolean creating;

    Record(ComponentName name, Class<? extends Service> cls) {
      this.name = name;
      this.cls = cls;
    }
  }

  /** One intent's binding (ActiveServices' IntentBindRecord). */
  private static final class Binding {
    final Intent intent;
    final List<Conn> conns = new ArrayList<>();
    boolean received;  // onBind ran and its binder is kept
    boolean bound;     // bound now (onUnbind not yet called)
    boolean rebind;    // onUnbind returned true: onRebind on the next bind
    IBinder binder;

    Binding(Intent intent) {
      this.intent = intent;
    }
  }

  /** One ServiceConnection's bindings until it is unbound (LoadedApk's ServiceDispatcher). */
  private static final class Dispatcher {
    final List<Conn> conns = new ArrayList<>();
    /** The binder each service last gave this connection: guarded by this. */
    final Map<ComponentName, IBinder> active = new HashMap<>();
    volatile boolean forgotten;
  }

  /** One bindService call (ActiveServices' ConnectionRecord). */
  private static final class Conn {
    final ServiceConnection connection;
    final Dispatcher dispatcher;
    final Intent intent;
    final boolean autoCreate;
    final Executor executor;
    boolean delivered;
    Binding binding;
    Record record;

    Conn(ServiceConnection connection, Dispatcher dispatcher, Intent intent, int flags, Executor executor) {
      this.connection = connection;
      this.dispatcher = dispatcher;
      this.intent = intent;
      this.autoCreate = (flags & Context.BIND_AUTO_CREATE) != 0;
      this.executor = executor;
    }
  }

  /** Binds an app service and returns true, or returns false when it is not one to bind here (Robolectric then does). */
  boolean bind(Intent intent, int flags, Executor executor, ServiceConnection connection) {
    ComponentName name = resolve(intent);
    if (name == null) return false;
    Class<? extends Service> cls;
    try {
      // Not initialized here: on a device the class initializes when the main thread makes the service, whichever
      // thread binds (a static Handler() would fail on a worker without a Looper).
      cls = Class.forName(name.getClassName(), false, app.getClassLoader()).asSubclass(Service.class);
    } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
      return false;
    }
    Conn c;
    synchronized (connections) {
      Dispatcher d = connections.computeIfAbsent(connection, k -> new Dispatcher());
      c = new Conn(connection, d, new Intent(intent), flags, executor);
      d.conns.add(c);
    }
    main.post(() -> attach(c, name, cls));
    return true;
  }

  /** Unbinds a connection bound here (all its bindings, as a device does) and returns true; false if it is not one. */
  boolean unbind(ServiceConnection connection) {
    List<Conn> cs;
    synchronized (connections) {
      Dispatcher d = connections.remove(connection);
      if (d == null) return false;
      d.forgotten = true;
      cs = d.conns;
    }
    main.post(() -> {
      for (Conn c : cs) {
        if (c.binding == null) continue;  // never attached, or its service was brought down
        c.binding.conns.remove(c);
        settle(c.record);
      }
    });
    return true;
  }

  private ComponentName resolve(Intent intent) {
    PackageManager pm = app.getPackageManager();
    ComponentName name = intent.getComponent();
    try {
      if (name == null) {
        ResolveInfo ri = pm.resolveService(intent, 0);
        if (ri == null || ri.serviceInfo == null) return null;
        name = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
      } else {
        pm.getServiceInfo(name, 0);
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      return null;
    }
    return name.getPackageName().equals(app.getPackageName()) ? name : null;
  }

  private void attach(Conn c, ComponentName name, Class<? extends Service> cls) {
    if (c.dispatcher.forgotten) return;
    Record r = records.computeIfAbsent(name, n -> new Record(n, cls));
    Binding b = r.bindings.computeIfAbsent(new Intent.FilterComparison(c.intent), k -> new Binding(c.intent));
    c.record = r;
    c.binding = b;
    b.conns.add(c);
    settle(r);
  }

  /** Brings a service in line with its connections: created, bound, unbound or destroyed. */
  private void settle(Record r) {
    if (r.creating) return;  // the settle creating it goes on with every connection attached meanwhile
    boolean autoCreate = false;
    for (Binding b : r.bindings.values()) for (Conn c : b.conns) autoCreate |= c.autoCreate;
    if (r.controller == null) {
      // Without BIND_AUTO_CREATE a binding waits for the service to be created (by another binding here). Its
      // Binding stays when it is unbound, as the device's IntentBindRecord does: onBind gets its intent later.
      if (!autoCreate) return;
      r.creating = true;
      try {
        r.controller = Robolectric.buildService(r.cls, r.bindings.values().iterator().next().intent).create();
      } finally {
        r.creating = false;
      }
    }
    Service s = r.controller.get();
    for (Binding b : r.bindings.values()) {
      if (!b.conns.isEmpty() && !b.bound) {
        if (!b.received) {
          b.binder = s.onBind(b.intent);
          b.received = true;
        } else if (b.rebind) {
          s.onRebind(b.intent);
        }
        b.bound = true;
      }
      if (b.bound) {
        for (Conn c : b.conns) {
          if (c.delivered) continue;
          c.delivered = true;
          deliver(c, r.name, b.binder);
        }
      }
    }
    for (Binding b : r.bindings.values()) {
      if (b.bound && b.conns.isEmpty()) {
        b.bound = false;
        b.rebind = s.onUnbind(b.intent);
      }
    }
    if (autoCreate) return;
    // No BIND_AUTO_CREATE connection left (and nothing here starts services): the service goes, and with it its
    // record. As ActiveServices.bringDownServiceLocked, every connection still bound (those without the flag) is told
    // the service is dead, connected(name, null, dead): onServiceDisconnected if it had a binder, then onBindingDied.
    // It is not connected again if a new binding makes the service again (API 36 emulator).
    for (Binding b : r.bindings.values()) {
      if (b.bound) s.onUnbind(b.intent);
    }
    r.controller.destroy();
    r.controller = null;
    for (Binding b : r.bindings.values()) {
      for (Conn c : b.conns) {
        deliverDeath(c, r.name);
        c.binding = null;  // unbindService then has nothing left to do for it
        c.record = null;
      }
    }
    r.bindings.clear();
    records.remove(r.name);
  }

  private void deliver(Conn c, ComponentName name, IBinder binder) {
    post(c, name, binder, false);
  }

  private void deliverDeath(Conn c, ComponentName name) {
    post(c, name, null, true);
  }

  /** ServiceDispatcher.doConnected, on the connection's thread and in order with its other callbacks. */
  private void post(Conn c, ComponentName name, IBinder binder, boolean dead) {
    // Each binding is told (ActiveServices calls connected() once per ConnectionRecord); the dispatcher drops a binder
    // the connection already has, and keeps no null one to compare with. A death drops the binder it had.
    Dispatcher d = c.dispatcher;
    Runnable callback = () -> {
      IBinder old;
      synchronized (d) {
        if (d.forgotten) return;  // unbound meanwhile: LoadedApk forgets the connection
        old = d.active.get(name);
        if (binder != null && old == binder) return;
        if (binder != null) d.active.put(name, binder);
        else d.active.remove(name);
      }
      if (old != null) c.connection.onServiceDisconnected(name);
      if (dead) c.connection.onBindingDied(name);
      else if (binder != null) c.connection.onServiceConnected(name, binder);
      else c.connection.onNullBinding(name);
    };
    if (c.executor != null) c.executor.execute(callback);
    else main.post(callback);
  }
}
