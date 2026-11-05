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
 * kept; each connection then gets onServiceConnected with that binder, or onNullBinding (API 28) when it is null, on
 * the main thread (or the bind's Executor). Unbinding never calls onServiceDisconnected (on a device only a dead
 * process does); the last connection of an intent gets the service onUnbind (onRebind on the next bind if it returned
 * true), and with no BIND_AUTO_CREATE connection left the service is destroyed. Service code and callbacks run from
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
  private final Map<ServiceConnection, List<Conn>> connections = new HashMap<>();
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

  private static final class Conn {
    final ServiceConnection connection;
    final Intent intent;
    final boolean autoCreate;
    final Executor executor;
    volatile boolean unbound;
    boolean delivered;
    Binding binding;
    Record record;

    Conn(ServiceConnection connection, Intent intent, int flags, Executor executor) {
      this.connection = connection;
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
      cls = Class.forName(name.getClassName(), true, app.getClassLoader()).asSubclass(Service.class);
    } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
      return false;
    }
    Conn c = new Conn(connection, new Intent(intent), flags, executor);
    synchronized (connections) {
      connections.computeIfAbsent(connection, k -> new ArrayList<>()).add(c);
    }
    main.post(() -> attach(c, name, cls));
    return true;
  }

  /** Unbinds a connection bound here (all its bindings, as a device does) and returns true; false if it is not one. */
  boolean unbind(ServiceConnection connection) {
    List<Conn> cs;
    synchronized (connections) {
      cs = connections.remove(connection);
    }
    if (cs == null) return false;
    for (Conn c : cs) c.unbound = true;
    main.post(() -> {
      for (Conn c : cs) {
        if (c.binding == null) continue;  // never attached
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
    if (c.unbound) return;
    Record r = records.computeIfAbsent(name, n -> new Record(n, cls));
    Binding b = r.bindings.computeIfAbsent(new Intent.FilterComparison(c.intent), k -> new Binding(c.intent));
    c.record = r;
    c.binding = b;
    // The same connection bound again to the same intent is told once (LoadedApk keeps one dispatcher for it).
    for (Conn other : b.conns) c.delivered |= other.connection.equals(c.connection) && other.delivered;
    b.conns.add(c);
    settle(r);
  }

  /** Brings a service in line with its connections: created, bound, unbound or destroyed. */
  private void settle(Record r) {
    boolean autoCreate = false;
    for (Binding b : r.bindings.values()) for (Conn c : b.conns) autoCreate |= c.autoCreate;
    if (r.controller == null) {
      // Without BIND_AUTO_CREATE a binding waits for the service to be created (by another binding here).
      if (!autoCreate) return;
      r.controller = Robolectric.buildService(r.cls, r.bindings.values().iterator().next().intent).create();
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
    // No BIND_AUTO_CREATE connection left (and nothing here starts services): the service goes. Connections bound
    // without the flag stay and are connected again if a new binding creates it.
    for (Binding b : r.bindings.values()) {
      if (b.bound) s.onUnbind(b.intent);
    }
    r.controller.destroy();
    r.controller = null;
    r.bindings.values().removeIf(b -> b.conns.isEmpty());
    for (Binding b : r.bindings.values()) {
      b.bound = b.received = b.rebind = false;
      b.binder = null;
      for (Conn c : b.conns) c.delivered = false;
    }
    if (r.bindings.isEmpty()) records.remove(r.name);
  }

  private void deliver(Conn c, ComponentName name, IBinder binder) {
    Runnable callback = () -> {
      if (c.unbound) return;  // unbound meanwhile: LoadedApk forgets the connection
      if (binder != null) c.connection.onServiceConnected(name, binder);
      else c.connection.onNullBinding(name);
    };
    if (c.executor != null) c.executor.execute(callback);
    else main.post(callback);
  }
}
