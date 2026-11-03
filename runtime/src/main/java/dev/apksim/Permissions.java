package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.robolectric.shadows.ShadowActivity;

/**
 * Runtime permission requests. Robolectric records them and never answers, so an app waiting on one waits forever.
 * Here a request is answered the way a device would: a prompt on the Mac window, the `permission allow|deny` command,
 * or a launch policy (--permissions grant|deny). The answer reaches the app through onRequestPermissionsResult, which
 * androidx's ActivityResult APIs (RequestPermission, RequestMultiplePermissions) receive too.
 */
final class Permissions {
  private final Application app;
  private final String policy = System.getProperty("apksim.permissions", "ask");
  private final Set<String> granted = new LinkedHashSet<>();
  private ShadowActivity.PermissionsRequest seen;
  private Activity pendingActivity;
  private ShadowActivity.PermissionsRequest pending;
  private int ticket = -1;

  Permissions(Application app) {
    this.app = app;
    if (policy.equals("grant")) {
      // Like `adb install -g`: everything the manifest asks for, granted up front.
      try {
        String[] all = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
        if (all != null) {
          shadowOf(app).grantPermissions(all);
          granted.addAll(Arrays.asList(all));
        }
      } catch (PackageManager.NameNotFoundException e) {
        // No package info: requests are still granted as they come.
      }
    }
  }

  /** Looks for a new request from the top activity, and for the answer to one on screen. */
  void tick(Activity top) {
    if (top == null) return;
    if (pending == null) {
      ShadowActivity.PermissionsRequest r = shadowOf(top).getLastRequestedPermission();
      if (r != null && r != seen) {
        seen = r;
        request(top, r);
      }
    } else if (ticket >= 0) {
      int answer = SimWindow.askAnswer(ticket);
      if (answer >= 0 || answer == -2) {
        ticket = -1;
        if (answer >= 0) answer(answer == 0);
      }
    }
  }

  private void request(Activity activity, ShadowActivity.PermissionsRequest r) {
    List<String> needed = new ArrayList<>();
    for (String p : r.requestedPermissions) {
      if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
    }
    pendingActivity = activity;
    pending = r;
    if (needed.isEmpty() || policy.equals("grant")) {
      answer(true);
    } else if (policy.equals("deny")) {
      answer(false);
    } else if (!SimWindow.isHeadless()) {
      ticket = SimWindow.ask("Allow " + label() + " to " + describe(needed) + "?", String.join(", ", shortNames(needed)),
          new String[] {"Allow", "Don't allow"});
    } else {
      System.err.println("[apksim] permission request waiting: " + String.join(", ", shortNames(needed))
          + " (answer with the control command `permission allow` or `permission deny`)");
    }
  }

  private void answer(boolean allow) {
    if (pending == null) return;
    String[] perms = pending.requestedPermissions;
    int[] results = new int[perms.length];
    for (int i = 0; i < perms.length; i++) {
      boolean already = pendingActivity.checkSelfPermission(perms[i]) == PackageManager.PERMISSION_GRANTED;
      boolean grant = already || allow;
      if (grant) {
        shadowOf(app).grantPermissions(perms[i]);
        granted.add(perms[i]);
      } else {
        shadowOf(app).denyPermissions(perms[i]);
      }
      results[i] = grant ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }
    System.err.println("[apksim] permission " + (allow ? "allowed" : "denied") + ": " + String.join(", ", shortNames(Arrays.asList(perms))));
    Activity a = pendingActivity;
    int code = pending.requestCode;
    pending = null;
    pendingActivity = null;
    a.onRequestPermissionsResult(code, perms, results);
  }

  /** permission [allow|deny | grant NAME... | revoke NAME... | reset]: answers or changes permissions. */
  String run(String[] a) {
    String op = a.length > 1 ? a[1] : "";
    switch (op) {
      case "allow", "deny" -> {
        if (pending == null) return "no permission request is waiting";
        if (ticket >= 0) {
          ticket = -1;
          SimWindow.dismissAsk();
        }
        answer(op.equals("allow"));
        return "ok";
      }
      case "grant", "revoke" -> {
        if (a.length < 3) return "usage: permission " + op + " NAME...";
        for (int i = 2; i < a.length; i++) {
          String p = fullName(a[i]);
          if (op.equals("grant")) {
            shadowOf(app).grantPermissions(p);
            granted.add(p);
          } else {
            shadowOf(app).denyPermissions(p);
            granted.remove(p);
          }
        }
        return "ok";
      }
      case "reset" -> {
        shadowOf(app).denyPermissions(granted.toArray(new String[0]));
        granted.clear();
        return "ok";
      }
      default -> {
        String waiting = pending == null ? "none" : String.join(", ", shortNames(Arrays.asList(pending.requestedPermissions)));
        return "waiting: " + waiting + "; granted: " + (granted.isEmpty() ? "none" : String.join(", ", shortNames(granted)));
      }
    }
  }

  private String label() {
    return String.valueOf(app.getApplicationInfo().loadLabel(app.getPackageManager()));
  }

  /** "use the camera", from the permission's own description where the framework has one. */
  private String describe(List<String> perms) {
    List<String> parts = new ArrayList<>();
    for (String p : perms) {
      String text = null;
      try {
        PermissionInfo info = app.getPackageManager().getPermissionInfo(p, 0);
        CharSequence label = info.loadLabel(app.getPackageManager());
        if (label != null && !label.toString().equals(p)) text = label.toString();
      } catch (PackageManager.NameNotFoundException | RuntimeException e) {
        // Not a framework permission: use its name.
      }
      String part = text != null ? text : "use " + shortName(p).toLowerCase(Locale.ROOT).replace('_', ' ');
      if (!parts.contains(part)) parts.add(part);
    }
    return String.join(" and ", parts);
  }

  private static String fullName(String name) {
    return name.contains(".") ? name : "android.permission." + name.toUpperCase(Locale.ROOT);
  }

  private static String shortName(String p) {
    return p.startsWith("android.permission.") ? p.substring("android.permission.".length()) : p;
  }

  private static List<String> shortNames(Iterable<String> perms) {
    List<String> out = new ArrayList<>();
    for (String p : perms) out.add(shortName(p));
    return out;
  }
}
