package dev.apksim;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.Map;
import org.robolectric.Shadows;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowInstrumentation;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * The broadcasts a device's system server has sent before an app starts, and the ones it sends when the app changes
 * system state. Robolectric has no sticky broadcast but those a test sends itself, so a receiver registered for
 * Wi-Fi's state, the network's or the battery's never heard a thing: an app that awaits the first one (a callbackFlow
 * and first(), as code showing Wi-Fi's state does) waited for ever (issue 96), and `registerReceiver(null,
 * ACTION_BATTERY_CHANGED)` returned null. On a device the system keeps the last of each and delivers it to every
 * receiver as it registers (Robolectric does that for the ones it has, synchronously).
 * Set before Application.onCreate (each test's too: Robolectric keeps stickies per Instrumentation), and again
 * whenever the networks change (HostNetworks, `network offline|online`).
 */
final class SystemBroadcasts {
  private SystemBroadcasts() {}

  static void beforeApplicationOnCreate(Context app) {
    battery(app);
    network(app);
  }

  /**
   * A full battery, not charging: what the iOS Simulator shows, and what Robolectric's BatteryManager says
   * (isCharging false).
   */
  private static void battery(Context app) {
    Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED)
        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_REPLACE_PENDING)
        .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
        .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
        .putExtra(BatteryManager.EXTRA_PRESENT, true)
        .putExtra(BatteryManager.EXTRA_LEVEL, 100)
        .putExtra(BatteryManager.EXTRA_SCALE, 100)
        .putExtra(BatteryManager.EXTRA_PLUGGED, 0)
        .putExtra(BatteryManager.EXTRA_VOLTAGE, 4300)
        .putExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
        .putExtra(BatteryManager.EXTRA_TECHNOLOGY, "Li-ion")
        .putExtra(BatteryManager.EXTRA_BATTERY_LOW, false);
    sticky(app, i);
  }

  /**
   * Wi-Fi's state (WIFI_STATE_CHANGED: WifiManager's), its connection (NETWORK_STATE_CHANGED) and CONNECTIVITY_ACTION,
   * from ConnectivityManager as it is now. HostNetworks shows the host's default interface as Wi-Fi; offline, Wi-Fi
   * stays on with no connection and CONNECTIVITY_ACTION says there is no connectivity, as ConnectivityService sends it.
   */
  @SuppressWarnings("deprecation")
  static void network(Context context) {
    Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    WifiManager wm = app.getSystemService(WifiManager.class);
    ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
    NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
    boolean connected = active != null && active.isConnected();
    if (wm != null) {
      sticky(app, new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
          .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
          .putExtra(WifiManager.EXTRA_WIFI_STATE, wm.getWifiState())
          .putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN));
    }
    NetworkInfo wifi = connected && active.getType() == ConnectivityManager.TYPE_WIFI ? active
        : ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.DISCONNECTED, ConnectivityManager.TYPE_WIFI, 0,
            false, NetworkInfo.State.DISCONNECTED);
    sticky(app, new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_REPLACE_PENDING)
        .putExtra(WifiManager.EXTRA_NETWORK_INFO, wifi));
    Intent conn = new Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
    if (connected) {
      conn.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, active)
          .putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, active.getType());
    } else {
      conn.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, wifi)
          .putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_WIFI)
          .putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
    }
    sticky(app, conn);
  }

  /**
   * Kept as the action's sticky broadcast, and sent to the receivers registered for it now. With none (before
   * onCreate) it is only kept: Robolectric prints every broadcast it sends to stderr, and each test would print four.
   */
  @SuppressWarnings({"deprecation", "unchecked"})
  private static void sticky(Context app, Intent intent) {
    boolean listened = false;
    if (app instanceof Application a) {
      for (ShadowApplication.Wrapper w : Shadows.shadowOf(a).getRegisteredReceivers()) {
        if (w.intentFilter != null && w.intentFilter.matchAction(intent.getAction())) listened = true;
      }
    }
    if (!listened) {
      try {
        Object shadow = Shadow.extract(ShadowInstrumentation.getInstrumentation());
        Field f = ShadowInstrumentation.class.getDeclaredField("stickyIntents");
        f.setAccessible(true);
        ((Map<String, Intent>) f.get(shadow)).put(intent.getAction(), intent);
        return;
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Robolectric's field moved: send it, as below
      }
    }
    app.sendStickyBroadcast(intent);  // Robolectric keeps it, then delivers it to the receivers registered now
  }

  /**
   * AudioService's VOLUME_CHANGED_ACTION (and STREAM_MUTE_CHANGED_ACTION) when a stream's volume (or mute) changes, as
   * a volume control waits for after setStreamVolume: Robolectric only stored the new value. Sent after the
   * call returns, as the system server's broadcast arrives.
   */
  @Implements(AudioManager.class)
  public static class ShadowSimAudioManager extends ShadowAudioManager {
    static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";

    @Implementation
    @Override
    protected void setStreamVolume(int stream, int index, int flags) {
      int before = getStreamVolume(stream);
      super.setStreamVolume(stream, index, flags);
      changed(stream, before);
    }

    @Implementation
    @Override
    protected void adjustStreamVolume(int stream, int direction, int flags) {
      int before = getStreamVolume(stream);
      boolean muted = isStreamMute(stream);
      super.adjustStreamVolume(stream, direction, flags);
      changed(stream, before);
      if (muted != isStreamMute(stream)) {
        send(new Intent(MUTE_CHANGED)
            .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", stream)
            .putExtra("android.media.EXTRA_STREAM_VOLUME_MUTED", isStreamMute(stream)));
      }
    }

    private void changed(int stream, int before) {
      int after = getStreamVolume(stream);
      if (after == before) return;  // as AudioService: only a change is broadcast
      send(new Intent(VOLUME_CHANGED)
          .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", stream)
          .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS", stream)
          .putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", after)
          .putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", before));
    }

    private static void send(Intent intent) {
      intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
      Context app = org.robolectric.RuntimeEnvironment.getApplication();
      if (app != null) new Handler(Looper.getMainLooper()).post(() -> app.sendBroadcast(intent));
    }
  }
}
