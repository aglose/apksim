package dev.apksim;

import android.os.PowerManager;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPowerManager;

/**
 * The device's thermal status, as PowerManager shows it: THERMAL_STATUS_NONE until `thermal STATUS` says otherwise.
 * Robolectric's shadow covers only the deprecated one-argument addThermalStatusListener, and the real
 * addThermalStatusListener(Executor, listener) asks a thermal service that is not here, then throws "Thermal status
 * listener failed to set". The shadow here registers the listener and calls it at once with the current status on its
 * executor, as the thermal service does, and again on each change; getCurrentThermalStatus returns the same.
 * Control command: `thermal [none|light|moderate|severe|critical|emergency|shutdown|0-6]`.
 */
public final class Thermal {
  private Thermal() {}

  private static final String[] NAMES = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
  private static volatile int status = PowerManager.THERMAL_STATUS_NONE;
  private static final CopyOnWriteArrayList<Entry> LISTENERS = new CopyOnWriteArrayList<>();

  private record Entry(Executor executor, PowerManager.OnThermalStatusChangedListener listener) {}

  // Registration and changes queue their notifications under one lock, in the order they happen, and one drainer runs the
  // queue outside it (an executor may run its task inline, or call back into add/remove): a listener hears statuses in
  // the order they were set and ends on the latest. A listener already registered is ignored, as PowerManager does.
  private static final Object LOCK = new Object();
  private static final java.util.ArrayDeque<Runnable> QUEUE = new java.util.ArrayDeque<>();
  private static boolean draining;
  private static int generation;  // each reset starts a new one: a drainer from an earlier one retires without touching the state

  private static void notify(Entry e, int s) {
    final int gen = generation;  // called under LOCK
    QUEUE.add(() -> {
      try {
        // A task an asynchronous executor still holds past a reset is dropped, not delivered to the last test's listener.
        e.executor().execute(() -> {
          synchronized (LOCK) {
            if (gen != generation) return;
          }
          e.listener().onThermalStatusChanged(s);
        });
      } catch (RuntimeException x) {  // a rejecting executor must not keep the others from hearing
        System.err.println("[apksim] thermal listener not notified: " + x);
      }
    });
  }

  private static void drain(int gen) {
    boolean clean = false;
    try {
      while (true) {
        Runnable r;
        synchronized (LOCK) {
          if (gen != generation) {  // reset while a callback ran: this drainer is retired
            clean = true;
            return;
          }
          r = QUEUE.poll();
          if (r == null) {
            draining = false;
            clean = true;
            return;
          }
        }
        r.run();
      }
    } finally {
      if (!clean) {  // an Error from a listener: give up ownership so later notifications still run, then propagate
        synchronized (LOCK) {
          if (gen == generation) draining = false;
        }
      }
    }
  }

  private static void startDrain() {
    int gen;
    synchronized (LOCK) {
      if (draining) return;  // the running drainer takes what was queued
      draining = true;
      gen = generation;
    }
    drain(gen);
  }

  static void add(Executor executor, PowerManager.OnThermalStatusChangedListener listener) {
    synchronized (LOCK) {
      for (Entry e : LISTENERS) if (e.listener() == listener) return;
      Entry e = new Entry(executor, listener);
      LISTENERS.add(e);
      notify(e, status);
    }
    startDrain();
  }

  static void remove(PowerManager.OnThermalStatusChangedListener listener) {
    synchronized (LOCK) {
      LISTENERS.removeIf(e -> e.listener() == listener);
    }
  }

  /** Between tests (SimTestEnvironment.resetState): no listeners, status NONE, as a new device. */
  static void reset() {
    synchronized (LOCK) {
      LISTENERS.clear();
      QUEUE.clear();
      draining = false;
      generation++;
      status = PowerManager.THERMAL_STATUS_NONE;
    }
  }

  /** The callback executor of the deprecated one-argument overload: the main looper, as PowerManager's main executor. */
  private static Executor mainExecutor() {
    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    return h::post;
  }

  /** thermal [STATUS]: reports the status, or sets it and tells the listeners. */
  public static String command(String[] a) {
    if (a.length < 2) return "thermal: " + NAMES[status];
    String v = a[1].toLowerCase(Locale.ROOT);
    int s = -1;
    for (int i = 0; i < NAMES.length; i++) {
      if (NAMES[i].equals(v) || Integer.toString(i).equals(v)) s = i;
    }
    if (s < 0) return "error: usage: thermal [none|light|moderate|severe|critical|emergency|shutdown]";
    synchronized (LOCK) {
      status = s;
      for (Entry e : LISTENERS) notify(e, s);
    }
    startDrain();
    return "thermal: " + NAMES[s];
  }

  @Implements(PowerManager.class)
  public static class ShadowSimPowerManager extends ShadowPowerManager {
    @Implementation
    protected void addThermalStatusListener(Executor executor, PowerManager.OnThermalStatusChangedListener listener) {
      add(executor, listener);
    }

    @Implementation
    @Override
    protected void addThermalStatusListener(@ClassName("android.os.PowerManager$OnThermalStatusChangedListener") Object listener) {
      add(mainExecutor(), (PowerManager.OnThermalStatusChangedListener) listener);
    }

    @Implementation
    @Override
    protected void removeThermalStatusListener(@ClassName("android.os.PowerManager$OnThermalStatusChangedListener") Object listener) {
      remove((PowerManager.OnThermalStatusChangedListener) listener);
    }

    @Implementation
    @Override
    protected int getCurrentThermalStatus() {
      return status;
    }
  }
}
