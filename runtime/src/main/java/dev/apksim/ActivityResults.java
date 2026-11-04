package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;

/**
 * Results of activities started for one (startActivityForResult, ActivityResult APIs). The framework leaves
 * delivering them to the system, so without this a requester waits forever. In the app: the started activity's
 * setResult goes back when it finishes. Leaving the app: document and photo pickers open a Mac file panel and return
 * the file as a content:// URI (PickedFiles); anything else is cancelled, as if the user pressed Back.
 */
final class ActivityResults {
  private record Request(Activity requester, Intent intent) {}

  private final Map<Activity, Request> inApp = new IdentityHashMap<>();
  private Request picking;
  private int pickTicket = -1;
  private boolean pickingMultiple;

  /** Whether this just-started intent was started for a result; if so it is taken off Robolectric's queue. */
  boolean claim(Activity requester, Intent next) {
    if (requester == null) return false;
    ShadowActivity.IntentForResult r = shadowOf(requester).peekNextStartedActivityForResult();
    if (r == null || !r.intent.filterEquals(next)) return false;
    shadowOf(requester).getNextStartedActivityForResult();
    return r.requestCode >= 0;  // plain startActivity is recorded too, with -1
  }

  /** An in-app activity started for a result: its result goes to the requester when it finishes. */
  void started(ActivityController<? extends Activity> c, Activity requester, Intent intent) {
    inApp.put(c.get(), new Request(requester, intent));
  }

  void finished(Activity done) {
    Request r = inApp.remove(done);
    if (r == null) return;
    ShadowActivity s = shadowOf(done);
    deliver(r, s.getResultCode(), s.getResultIntent());
  }

  /** A for-result intent no activity here handles: a picker, or cancelled. */
  void outside(Activity requester, Intent intent) {
    Request r = new Request(requester, intent);
    String action = String.valueOf(intent.getAction());
    if (action.equals("android.content.pm.action.REQUEST_PERMISSIONS")) return;  // Permissions answers these
    boolean picker = action.equals(Intent.ACTION_OPEN_DOCUMENT) || action.equals(Intent.ACTION_GET_CONTENT)
        || action.equals(Intent.ACTION_PICK) || action.equals("android.provider.action.PICK_IMAGES");
    if (picker && !SimWindow.isHeadless() && picking == null) {
      picking = r;
      pickingMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) || intent.hasExtra("android.provider.extra.PICK_IMAGES_MAX");
      String mime = intent.getType() == null ? "*/*" : intent.getType();
      String[] extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
      pickTicket = SimWindow.pickFiles(mime + (extra == null ? "" : "," + String.join(",", extra)), pickingMultiple);
      System.err.println("[apksim] file picker for " + action + " (" + mime + "); or answer with `pick FILE...` / `pick cancel`");
      return;
    }
    if (picker && SimWindow.isHeadless() && picking == null) {
      picking = r;
      pickingMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
      System.err.println("[apksim] file picker waiting: answer with `pick FILE...` or `pick cancel`");
      return;
    }
    System.err.println("[apksim] no activity for " + intent + ": answered RESULT_CANCELED");
    deliver(r, Activity.RESULT_CANCELED, null);
  }

  /** Polled each loop turn: the Mac panel's answer. */
  void tick() {
    if (picking == null || pickTicket < 0) return;
    String paths = SimWindow.pickResult(pickTicket);
    if (paths == null) return;
    pickTicket = -1;
    answerPick(paths.isEmpty() ? List.of() : List.of(paths.split("\n")));
  }

  /** pick FILE... | pick cancel: answers a waiting picker from the control channel. */
  String pick(String[] a) {
    if (picking == null) return "no picker is waiting";
    List<String> files = new ArrayList<>();
    for (int i = 1; i < a.length; i++) if (!a[i].equals("cancel")) files.add(Storage.absolute(a[i]).toString());
    if (pickTicket >= 0) SimWindow.dismissPick();
    pickTicket = -1;
    return answerPick(files);
  }

  private String answerPick(List<String> paths) {
    Request r = picking;
    picking = null;
    if (paths.isEmpty()) {
      deliver(r, Activity.RESULT_CANCELED, null);
      return "cancelled";
    }
    List<Uri> uris = new ArrayList<>();
    for (String p : paths) {
      Path file = Path.of(p);
      if (!Files.isReadable(file)) {
        // The launcher's macOS sandbox keeps the app out of ~/Documents and ~/Desktop, file panel or not.
        System.err.println("[apksim] cannot read " + p + " (the sandbox blocks ~/Documents and ~/Desktop; use Downloads)");
        continue;
      }
      uris.add(PickedFiles.add(file));
      if (!pickingMultiple) break;
    }
    if (uris.isEmpty()) {
      deliver(r, Activity.RESULT_CANCELED, null);
      return "nothing readable picked: cancelled";
    }
    Intent data = new Intent().setData(uris.get(0)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (uris.size() > 1 || pickingMultiple) {
      ClipData clip = ClipData.newRawUri("picked", uris.get(0));
      for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
      data.setClipData(clip);
    }
    deliver(r, Activity.RESULT_OK, data);
    return "picked " + uris;
  }

  private static void deliver(Request r, int code, Intent data) {
    if (r.requester().isDestroyed()) return;
    shadowOf(r.requester()).receiveResult(r.intent(), code, data);
  }
}
