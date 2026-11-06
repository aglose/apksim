package dev.apksim;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowConnectivityManager;

/**
 * Network callbacks as ConnectivityService runs them. Robolectric only recorded a callback, so an app that awaits the
 * first one (a callbackFlow and first(), as an "am I online" check does) waited for ever (issue 96).
 * Here each callback is told of the networks it is entitled to as it registers, and of every change after:
 * - a default callback: the active network;
 * - requestNetwork and registerBestMatchingNetworkCallback (one request, one network): the active network if it
 *   satisfies the request, else the first that does;
 * - registerNetworkCallback (a listen): every network whose capabilities satisfy the request.
 * A network it gains gets onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged and onBlockedStatusChanged, one
 * it keeps gets onCapabilitiesChanged/onLinkPropertiesChanged when those change, one it loses onLost (a default
 * callback or a request only when no network replaces it, or it disconnected: see decide).
 * What a callback is owed is decided when it registers and at every change (`rematch`: HostNetworks installing the
 * host's networks, `network offline|online`), as the system server decides it, and goes into that callback's own queue,
 * delivered in order on its handler (a background ConnectivityThread without one, as on Android). A change made on that thread delivers the queue at
 * once (NetConnectivity's callers expect onLost when `network offline` returns), so a registration's announcement
 * still pending then arrives first: registered, then offline and online before the main thread ran, a callback hears
 * available, lost, available, as on a device. A callback registered before the host's networks exist (in
 * Application.onCreate, before Host installs them) is told of them once they do.
 */
@Implements(ConnectivityManager.class)
public class ShadowSimConnectivityManager extends ShadowConnectivityManager {
  private enum Kind { DEFAULT, REQUEST, LISTEN }

  /** Where callbacks registered without a Handler run: a background looper, as Android's ConnectivityThread. */
  private static Handler connectivityThread;

  private static synchronized Handler connectivityHandler() {
    if (connectivityThread == null || !connectivityThread.getLooper().getThread().isAlive()) {
      android.os.HandlerThread t = new android.os.HandlerThread("ConnectivityThread");
      t.start();
      connectivityThread = new Handler(t.getLooper());
    }
    return connectivityThread;
  }

  private static final class Tracked {
    final ConnectivityManager.NetworkCallback cb;
    final NetworkRequest request;
    final Kind kind;
    final Handler handler;
    final ConnectivityManager cm;
    /** What the callback has been told (or has queued): each network with its capabilities and link properties. */
    final Map<Network, Object[]> told = new LinkedHashMap<>();
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    /** The queue is being delivered (on the handler's thread): a nested deliver leaves it to that loop. */
    boolean delivering;
    /**
     * A requestNetwork timeout still pending (no network satisfied the request yet), else null. Cleared for good when
     * decide() first matches a network, under TRACKED's lock, not when onAvailable is delivered: that is the mark
     * expire() checks, so a request satisfied once never expires (its onAvailable still queued on a busy handler, or
     * its network lost and back after the deadline), as Android cancels the timeout once satisfied.
     */
    Runnable timeout;
    /** When that timeout expires (SystemClock uptime): past it, the request is matched to nothing. */
    long expiresAt;
    /** Matched a network at least once (under TRACKED): a request that was ever satisfied never times out. */
    boolean satisfied;

    Tracked(ConnectivityManager cm, ConnectivityManager.NetworkCallback cb, NetworkRequest request, Kind kind, Handler handler) {
      this.cm = cm;
      this.cb = cb;
      this.request = request;
      this.kind = kind;
      this.handler = handler != null ? handler : connectivityHandler();
    }
  }

  private static final Map<ConnectivityManager.NetworkCallback, Tracked> TRACKED = new IdentityHashMap<>();

  @RealObject private ConnectivityManager cm;

  @Implementation
  @Override
  protected void registerNetworkCallback(NetworkRequest request, ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerNetworkCallback(request, cb, handler);
    track(new Tracked(cm, cb, request, Kind.LISTEN, handler));
  }

  // Robolectric sends requestNetwork to the two-argument registerNetworkCallback; a request gets one network.
  @Implementation
  @Override
  protected void requestNetwork(NetworkRequest request, ConnectivityManager.NetworkCallback cb) {
    requestNetwork(request, cb, (Handler) null);
  }

  @Implementation
  @Override
  protected void requestNetwork(NetworkRequest request, ConnectivityManager.NetworkCallback cb, int timeoutMs) {
    requestNetwork(request, cb, null, timeoutMs);
  }

  /**
   * A request with a timeout: no network satisfying it within timeoutMs, onUnavailable on the callback's handler and
   * the request is released, as ConnectivityService's timeout does; one that is satisfied first never times out. The
   * deadline is the handler's (SystemClock uptime, the clock app timers run on), and unregistering cancels it.
   */
  @Implementation
  @Override
  protected void requestNetwork(NetworkRequest request, ConnectivityManager.NetworkCallback cb, Handler handler, int timeoutMs) {
    if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be strictly positive.");
    requestNetwork(request, cb, handler);
    synchronized (TRACKED) {
      Tracked t = TRACKED.get(cb);
      if (t == null || t.satisfied) return;  // satisfied already (even if lost since): nothing to time out
      t.expiresAt = android.os.SystemClock.uptimeMillis() + timeoutMs;
      t.timeout = () -> expire(t);
      t.handler.postDelayed(t.timeout, timeoutMs);
    }
  }

  /**
   * The request's deadline passed with no network: it is released and hears onUnavailable. Run by the posted timeout,
   * or posted by decide() when a network comes after the deadline but before a busy handler ran the timeout; the
   * first to run wins, once.
   */
  private static void expire(Tracked t) {
    synchronized (TRACKED) {
      if (TRACKED.get(t.cb) != t || t.timeout == null) return;
      t.timeout = null;
    }
    t.cm.unregisterNetworkCallback(t.cb);
    t.cb.onUnavailable();
  }

  @Implementation
  @Override
  protected void requestNetwork(NetworkRequest request, ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerNetworkCallback(request, cb, handler);  // Robolectric's record of it, without our listen
    track(new Tracked(cm, cb, request, Kind.REQUEST, handler));
  }

  @Implementation
  @Override
  protected void registerDefaultNetworkCallback(ConnectivityManager.NetworkCallback cb) {
    registerDefaultNetworkCallback(cb, null);
  }

  @Implementation
  @Override
  protected void registerDefaultNetworkCallback(ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerDefaultNetworkCallback(cb, handler);
    track(new Tracked(cm, cb, null, Kind.DEFAULT, handler));
  }

  @Implementation
  @Override
  protected void registerBestMatchingNetworkCallback(NetworkRequest request, ConnectivityManager.NetworkCallback cb, Handler handler) {
    super.registerBestMatchingNetworkCallback(request, cb, handler);
    track(new Tracked(cm, cb, request, Kind.REQUEST, handler));
  }

  @Implementation
  @Override
  protected void unregisterNetworkCallback(ConnectivityManager.NetworkCallback cb) {
    super.unregisterNetworkCallback(cb);
    synchronized (TRACKED) {
      Tracked t = TRACKED.remove(cb);
      if (t != null) {
        t.queue.clear();
        if (t.timeout != null) t.handler.removeCallbacks(t.timeout);
        t.timeout = null;
      }
    }
  }

  private void track(Tracked t) {
    synchronized (TRACKED) {
      TRACKED.put(t.cb, t);
      decide(t);
    }
    t.handler.post(() -> deliver(t));
  }

  /**
   * After the networks changed (HostNetworks, NetConnectivity): every callback is owed what changed for it, delivered
   * at once to callbacks whose handler runs on this thread, posted to the others.
   */
  static void rematch() {
    List<Tracked> all;
    synchronized (TRACKED) {
      // Robolectric forgets its callbacks between tests (its reset): so do we.
      java.util.Set<ConnectivityManager.NetworkCallback> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      live.addAll(new ShadowConnectivityManager().getNetworkCallbacks());
      TRACKED.keySet().removeIf(cb -> !live.contains(cb));
      all = new ArrayList<>(TRACKED.values());
      for (Tracked t : all) decide(t);
    }
    for (Tracked t : all) {
      if (t.handler.getLooper().isCurrentThread()) deliver(t);
      else t.handler.post(() -> deliver(t));
    }
  }

  /**
   * A network the device would offer a callback: connected, with capabilities. Robolectric's own networks (before
   * HostNetworks replaces them in a running instance) include a disconnected Wi-Fi, which a broad request
   * (clearCapabilities) would otherwise have been told was available.
   */
  @SuppressWarnings("deprecation")
  private static boolean offered(ConnectivityManager cm, Network n) {
    if (n == null || cm.getNetworkCapabilities(n) == null) return false;
    android.net.NetworkInfo info = cm.getNetworkInfo(n);
    return info != null && info.isConnected();
  }

  /** The networks this callback is entitled to now. */
  private static List<Network> entitled(Tracked t, ConnectivityManager cm) {
    Network active = cm.getActiveNetwork();
    if (!offered(cm, active)) active = null;
    if (t.kind == Kind.DEFAULT) return active != null ? List.of(active) : List.of();
    List<Network> out = new ArrayList<>();
    for (Network n : cm.getAllNetworks()) {
      if (!offered(cm, n)) continue;
      NetworkCapabilities caps = cm.getNetworkCapabilities(n);
      if (t.request == null || !t.request.canBeSatisfiedBy(caps)) continue;
      if (t.kind == Kind.REQUEST && n.equals(active)) return List.of(n);  // the default network serves a request first
      out.add(n);
    }
    return t.kind == Kind.REQUEST && !out.isEmpty() ? List.of(out.get(0)) : out;
  }

  /**
   * Queues what the callback is owed against what it was told (under TRACKED's lock), as ConnectivityService's
   * rematch does. A network the callback no longer gets: a listen hears onLost; a default callback or a request (one
   * network) hears onLost only when that network disconnected or nothing replaces it. When another network takes over
   * while the old one stays up (it lingers, then goes), the switch is just onAvailable of the new one: Android sends
   * those registrations no onLost for the network they moved off (applyNetworkReassignment notifies the new satisfier,
   * CALLBACK_LOST only with none; the old network's teardown later notifies the requests it still serves, not these).
   */
  private static void decide(Tracked t) {
    ConnectivityManager cm = t.cm;  // Robolectric keeps the networks in static state: any instance has them
    if (t.timeout != null && android.os.SystemClock.uptimeMillis() >= t.expiresAt) {
      // Expired, though its handler has not run the timeout yet (busy): as on a device, it can no longer be
      // satisfied; it is released with onUnavailable, whichever of this post and the timeout runs first.
      t.handler.post(() -> expire(t));
      return;
    }
    List<Network> now = entitled(t, cm);
    ConnectivityManager.NetworkCallback cb = t.cb;
    if (t.timeout != null && !now.isEmpty()) {  // satisfied before its deadline: the timeout is off
      t.handler.removeCallbacks(t.timeout);
      t.timeout = null;
    }
    // Losses first: a network that disconnected is gone before its replacement is announced.
    List<Network> all = java.util.Arrays.asList(cm.getAllNetworks());
    for (Network n : new ArrayList<>(t.told.keySet())) {
      if (now.contains(n)) continue;
      t.told.remove(n);
      boolean up = all.contains(n) && offered(cm, n);
      if (t.kind == Kind.LISTEN || now.isEmpty() || !up) t.queue.add(() -> cb.onLost(n));
    }
    for (Network n : now) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(n);
      LinkProperties link = cm.getLinkProperties(n);
      Object[] was = t.told.get(n);
      if (was == null) {
        t.queue.add(() -> {
          cb.onAvailable(n);
          cb.onCapabilitiesChanged(n, caps);
          if (link != null) cb.onLinkPropertiesChanged(n, link);
          cb.onBlockedStatusChanged(n, false);
        });
      } else {
        if (!Objects.equals(was[0], caps)) t.queue.add(() -> cb.onCapabilitiesChanged(n, caps));
        if (link != null && !Objects.equals(was[1], link)) t.queue.add(() -> cb.onLinkPropertiesChanged(n, link));
      }
      t.told.put(n, new Object[] {caps, link});
      t.satisfied = true;
    }
  }

  /**
   * Runs the callback's queue in order, unless it was unregistered meanwhile. A change a callback makes from inside
   * one of its own calls (onAvailable going offline) only queues: the outer drain finishes the announcement it is in
   * (onCapabilitiesChanged, onLinkPropertiesChanged), then delivers the new entries (onLost).
   */
  private static void deliver(Tracked t) {
    synchronized (TRACKED) {
      if (t.delivering) return;
      t.delivering = true;
    }
    try {
      while (true) {
        Runnable r;
        synchronized (TRACKED) {
          if (TRACKED.get(t.cb) != t) return;
          r = t.queue.poll();
        }
        if (r == null) return;
        r.run();
      }
    } finally {
      synchronized (TRACKED) {
        t.delivering = false;
      }
    }
  }
}
