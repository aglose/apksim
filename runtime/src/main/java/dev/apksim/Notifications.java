package dev.apksim;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.service.notification.StatusBarNotification;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notifications the app posts. Robolectric keeps them in a list nobody looks at; here each new one shows as a
 * heads-up banner on the window (a click opens it, as tapping a notification does) and in the log, and the
 * `notifications` command lists and opens them, headless too. As on Android 13+, an app targeting 33+ needs
 * POST_NOTIFICATIONS for its notifications to be seen.
 */
final class Notifications {
  private final Application app;
  private final NotificationManager manager;
  private final Map<Notification, Boolean> seen = new IdentityHashMap<>();
  private StatusBarNotification lastShown;

  Notifications(Application app) {
    this.app = app;
    manager = app.getSystemService(NotificationManager.class);
  }

  void tick() {
    for (StatusBarNotification sbn : manager.getActiveNotifications()) {
      Notification n = sbn.getNotification();
      if (seen.put(n, Boolean.TRUE) != null) continue;
      if (!allowed()) {
        System.err.println("[apksim] notification not shown, POST_NOTIFICATIONS is not granted: " + title(n));
        continue;
      }
      System.err.println("[apksim] notification: " + title(n) + " — " + text(n));
      lastShown = sbn;
      SimWindow.showBanner(label(), title(n), text(n));
    }
  }

  private boolean allowed() {
    ApplicationInfo info = app.getApplicationInfo();
    return info.targetSdkVersion < 33
        || app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
  }

  /** notifications: those posted, numbered; notifications open N|last; notifications clear. */
  String run(String[] a) {
    List<StatusBarNotification> all = List.of(manager.getActiveNotifications());
    String op = a.length > 1 ? a[1] : "list";
    switch (op) {
      case "open" -> {
        StatusBarNotification n = a.length > 2 && !a[2].equals("last") ? at(all, a[2]) : lastShown;
        if (n == null) return "no such notification";
        return open(n);
      }
      case "clear" -> {
        manager.cancelAll();
        return "ok";
      }
      default -> {
        if (all.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
          if (i > 0) out.append(" ; ");
          Notification n = all.get(i).getNotification();
          out.append(i + 1).append(": ").append(title(n)).append(" — ").append(text(n));
        }
        return out.toString();
      }
    }
  }

  /** The banner was clicked. */
  String openLast() {
    return lastShown == null ? "no notification" : open(lastShown);
  }

  private String open(StatusBarNotification sbn) {
    SimWindow.hideBanner();
    Notification n = sbn.getNotification();
    if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) manager.cancel(sbn.getTag(), sbn.getId());
    PendingIntent intent = n.contentIntent;
    if (intent == null) return "the notification has no action: " + title(n);
    try {
      intent.send();
    } catch (PendingIntent.CanceledException e) {
      return "its intent was cancelled";
    }
    return "opened: " + title(n);
  }

  private static StatusBarNotification at(List<StatusBarNotification> all, String number) {
    int i = Integer.parseInt(number) - 1;
    return i >= 0 && i < all.size() ? all.get(i) : null;
  }

  private String label() {
    return String.valueOf(app.getApplicationInfo().loadLabel(app.getPackageManager()));
  }

  private static String title(Notification n) {
    CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
    return t == null ? "" : t.toString();
  }

  private static String text(Notification n) {
    CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TEXT);
    return t == null ? "" : t.toString();
  }
}
