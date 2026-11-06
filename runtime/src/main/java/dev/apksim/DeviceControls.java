package dev.apksim;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * What a simulator's hardware menu does: appearance, locale, text size, animation speed, memory warnings, the Home
 * and Lock buttons, and opening URLs. Each is a control command (see {@link #run}) and, on a Mac, a menu item.
 */
final class DeviceControls {
  private final Application app;
  private final ArrayDeque<ActivityController<? extends Activity>> stack;
  private int w;
  private int h;
  private int rotation;  // quarter turns clockwise from the device's natural orientation
  private final Resizer resizer;

  /** Gives the renderer and the window a new display size; actualSize puts the window back at actual size. */
  interface Resizer {
    void resize(int w, int h, boolean actualSize);
  }
  private String hiddenBy;
  private final Permissions permissions;
  private final ActivityResults results = new ActivityResults();
  private final SoftKeyboard keyboard;
  private long keyboardCheckNanos;
  // Created on first use, or a second after launch: making the system services they need costs startup time.
  private Notifications notifications;
  private LocationSim location;
  private BackgroundWork background;
  private final long createdNanos = System.nanoTime();
  private boolean slowAnimations;
  private String recording;
  private ClipboardManager clipboard;
  private long clipboardSeen = -1;
  private String clipboardLast;
  private long slowTickNanos;
  // A window resize by the user waiting to be applied (device pixels), and when the last one was.
  private int resizeW;
  private int resizeH;
  private boolean resizeDone;
  private long resizedNanos;
  /** At most this often while the user drags the window's edge: each step is a configuration change. */
  private static final long RESIZE_INTERVAL_NANOS = 100_000_000L;

  // Tags of the Mac menu items (native/simwindow.m), as control commands. "{text}" is what the item asked for.
  private static final String[] MENU = {
    null, "screenshot", "record toggle", "reveal", "home", "lock", "rotate left", "rotate right", "appearance toggle",
    "fontscale +", "fontscale -", "fontscale 1", "animscale toggle", "memory", "open {text}", "key VOLUME_UP",
    "key VOLUME_DOWN", "back", "locale {text}", "notifications open last", "location {text}", "location run",
    "location bike", "location drive", "location none", "biometrics toggle", "biometrics match", "biometrics nomatch",
    "debug layout toggle", "a11y show", "keyboard soft", "device phone", "device foldable", "device tablet",
    "device desktop", "device tv", "network online", "network offline", "network 3g", "network lossy",
  };
  private static final int TAG_DEVICE_FIRST = 31;
  private static final String[] DEVICE_MENU = {"phone", "foldable", "tablet", "desktop", "tv"};
  private static final int TAG_SOFT_KEYBOARD = 30;
  private static final int TAG_LAYOUT_BOUNDS = 28;
  private boolean layoutBounds;
  private static final int TAG_BIOMETRICS_ENROLLED = 25;
  private static final int TAG_APPEARANCE = 8;
  private static final int TAG_SLOW_ANIMATIONS = 12;
  private static final int TAG_RECORD = 2;
  /** Android's text size steps (Settings > Display > Font size). */
  private static final float[] FONT_STEPS = {0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f};

  DeviceControls(Application app, ArrayDeque<ActivityController<? extends Activity>> stack, int w, int h,
      Resizer resizer) {
    this.app = app;
    this.resizer = resizer;
    this.stack = stack;
    this.w = w;
    this.h = h;
    this.permissions = new Permissions(app);
    this.keyboard = new SoftKeyboard(app);
  }

  /** Launch options (--appearance, --locale, --font-scale), applied before the first activity starts. */
  static void applyLaunchOptions() {
    BackgroundWork.enableAlarms();
    String appearance = System.getProperty("apksim.appearance");
    if (appearance != null) overlayQualifiers(nightQualifier(appearance));
    String locale = System.getProperty("apksim.locale");
    if (locale != null) applyLocale(locale);
    String fontScale = System.getProperty("apksim.fontScale");
    if (fontScale != null) RuntimeEnvironment.setFontScale(Float.parseFloat(fontScale));
  }

  /** True while the app is behind the home screen or the lock screen: nothing to draw, a tap brings it back. */
  boolean hidden() {
    return hiddenBy != null;
  }

  private String push(String spec) {
    // Play Services hands a message to the app's FCM receiver (FirebaseInstanceIdReceiver) as this broadcast; FCM
    // then queues it for the app's FirebaseMessagingService, which BackgroundWork starts.
    Intent intent = new Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(app.getPackageName());
    java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
    try {
      if (spec.trim().startsWith("{")) {
        org.json.JSONObject json = new org.json.JSONObject(spec);
        org.json.JSONObject d = json.optJSONObject("data");
        org.json.JSONObject n = json.optJSONObject("notification");
        if (d == null && n == null) d = json;
        if (d != null) for (java.util.Iterator<String> k = d.keys(); k.hasNext(); ) {
          String key = k.next();
          data.put(key, d.getString(key));
        }
        if (n != null) {
          // How FCM carries a notification message to the app.
          intent.putExtra("gcm.n.e", "1");
          for (java.util.Iterator<String> k = n.keys(); k.hasNext(); ) {
            String key = k.next();
            intent.putExtra("gcm.notification." + key, n.getString(key));
          }
        }
      } else {
        for (String pair : spec.trim().split("\\s+(?=[\\w.-]+=)")) {
          int eq = pair.indexOf('=');
          if (eq > 0) data.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
      }
    } catch (org.json.JSONException e) {
      return "bad JSON: " + e.getMessage();
    }
    if (data.isEmpty() && !intent.hasExtra("gcm.n.e")) return "usage: push key=value... | push {json}";
    data.forEach(intent::putExtra);
    intent.putExtra("google.message_id", "apksim-" + System.nanoTime());
    intent.putExtra("google.sent_time", System.currentTimeMillis());
    intent.putExtra("from", "apksim");
    List<android.content.pm.ResolveInfo> handlers = app.getPackageManager().queryBroadcastReceivers(intent, 0);
    if (handlers.isEmpty()) return "the app has no FCM receiver (com.google.android.c2dm.intent.RECEIVE)";
    intent.setClassName(app.getPackageName(), handlers.get(0).activityInfo.name);
    // FCM passes the message on by binding to the app's messaging service with this intent.
    Intent bind = new Intent("com.google.firebase.MESSAGING_EVENT").setPackage(app.getPackageName());
    List<android.content.pm.ResolveInfo> service = app.getPackageManager().queryIntentServices(bind, 0);
    if (!service.isEmpty()) background().bindable(bind, new android.content.ComponentName(app.getPackageName(), service.get(0).serviceInfo.name));
    app.sendBroadcast(intent);
    return "pushed through " + handlers.get(0).activityInfo.name + ": " + data;
  }

  private BackgroundWork background() {
    if (background == null) background = new BackgroundWork(app);
    return background;
  }

  /** Back closes the software keyboard first, as on a device; true if it did. */
  boolean backClosesKeyboard() {
    if (!keyboard.shown()) return false;
    keyboard.dismiss(rootViews());
    return true;
  }

  ActivityResults results() {
    return results;
  }

  private Notifications notifications() {
    if (notifications == null) notifications = new Notifications(app);
    return notifications;
  }

  private LocationSim location() {
    if (location == null) location = new LocationSim(app);
    return location;
  }

  /** The control command a menu item stands for, or null. */
  static String menuCommand(int tag) {
    if (tag <= 0 || tag >= MENU.length) return null;
    String c = MENU[tag];
    if (c.contains("{text}")) {
      String text = SimWindow.takeText();
      if (text == null || text.isBlank()) return null;
      c = c.replace("{text}", text.trim());
    }
    if (c.equals("screenshot")) {
      try {
        c = "shot " + newMediaFile("png");
      } catch (java.io.IOException e) {
        return null;
      }
    }
    return c;
  }

  /** Where Save Screen and Record Screen put their files: work/screenshots. */
  static Path mediaDir() throws java.io.IOException {
    Path dir = Path.of(System.getProperty("apksim.cacheRoot")).getParent().resolve("screenshots");
    Files.createDirectories(dir);
    return dir;
  }

  static Path newMediaFile(String extension) throws java.io.IOException {
    return mediaDir().resolve("apksim-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date()) + "." + extension);
  }

  /**
   * A link the app opens outside itself: web and mail links go to the Mac's browser and mail app, as the iOS
   * Simulator hands them to Safari. Other schemes stay ignored (they could launch arbitrary Mac apps).
   */
  static boolean openOnHost(Intent intent) {
    if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
      Intent inner = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
      return inner != null && openOnHost(inner);
    }
    if (Intent.ACTION_SEND.equals(intent.getAction())) return share(intent);
    Uri uri = intent.getData();
    if (!Intent.ACTION_VIEW.equals(intent.getAction()) || uri == null || !HostOs.MAC || SimWindow.isHeadless()) return false;
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) return false;
    try {
      new ProcessBuilder("/usr/bin/open", uri.toString()).start();
      System.err.println("[apksim] opened on the Mac: " + uri);
      return true;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /** ACTION_SEND: text (and a subject) or one file to the Mac's share menu; logged either way. */
  private static boolean share(Intent intent) {
    CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
    String body = text == null ? null : (subject != null ? subject + "\n" : "") + text;
    String path = null;
    Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
    if (stream != null) {
      try (java.io.InputStream in = RuntimeEnvironment.getApplication().getContentResolver().openInputStream(stream)) {
        String name = stream.getLastPathSegment() == null ? "shared" : stream.getLastPathSegment();
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "shared", name.replace('/', '_'));
        Files.createDirectories(out.getParent());
        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        path = out.toString();
      } catch (java.io.IOException | RuntimeException e) {
        System.err.println("[apksim] cannot read the shared file " + stream + ": " + e);
      }
    }
    System.err.println("[apksim] shared: " + (body != null ? body.replace('\n', ' ') : "") + (path != null ? " " + path : ""));
    if (body == null && path == null) return false;
    SimWindow.share(body, path);
    return true;
  }

  /** Called every loop turn: keeps the app's clipboard and the Mac's in step, and the system bars' colours. */
  void tick() {
    applyWindowResize();
    if (hiddenBy == null && !stack.isEmpty()) permissions.tick(stack.peek().get());
    Biometrics.tick();
    results.tick();
    long now = System.nanoTime();
    if (now - keyboardCheckNanos > 50_000_000L && hiddenBy == null) {
      keyboardCheckNanos = now;
      keyboard.tick(rootViews(), w, h);
    }
    if (now - slowTickNanos < 300_000_000L || now - createdNanos < 1_000_000_000L) return;
    slowTickNanos = now;
    location().tick();
    notifications().tick();
    background().tick();
    if (SimWindow.isHeadless()) return;
    updateSystemBars();
    if (clipboard == null) {
      clipboard = (ClipboardManager) app.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
      clipboard.addPrimaryClipChangedListener(this::appCopied);
    }
    long count = SimWindow.clipboardChangeCount();
    if (count == clipboardSeen) return;
    boolean first = clipboardSeen == -1;
    clipboardSeen = count;
    if (first) return;  // what was on the Mac clipboard before launch stays there
    String text = SimWindow.clipboardText();
    if (text == null || text.equals(clipboardLast)) return;
    clipboardLast = text;
    clipboard.setPrimaryClip(ClipData.newPlainText("Mac", text));
  }

  /** Phones and tablets have a status bar and gesture navigation (24 dp each); desktop and TV have neither. */
  private void updateSystemBars() {
    if (!Device.current().systemBars()) return;
    boolean darkStatus = false;
    boolean darkNav = false;
    ActivityController<? extends Activity> top = stack.peek();
    if (hiddenBy == null && top != null) {
      android.view.WindowInsetsController c = top.get().getWindow().getDecorView().getWindowInsetsController();
      int appearance = c == null ? 0 : c.getSystemBarsAppearance();
      darkStatus = (appearance & android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0;
      darkNav = (appearance & android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0;
    }
    int bar = Math.round(24 * Device.current().density());  // Robolectric's SystemUi: 24 dp each
    SimWindow.setSystemBars(bar, bar, darkStatus, darkNav);
  }

  private void appCopied() {
    ClipData clip = clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) return;
    CharSequence text = clip.getItemAt(0).coerceToText(app);
    if (text == null || text.toString().equals(clipboardLast)) return;
    clipboardLast = text.toString();
    clipboardSeen = SimWindow.setClipboardText(clipboardLast);
  }

  /** Handles a control command, or returns null if it is not one of these. */
  /** The animator duration scale, as the system sets it: ValueAnimator's for Views, the global setting for Compose. */
  static void setAnimatorScale(Application app, float scale) {
    ValueAnimator.setDurationScale(scale);
    Settings.Global.putFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    app.getContentResolver().notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null);
  }

  String run(String[] a) {
    Configuration before = new Configuration(app.getResources().getConfiguration());
    switch (a[0]) {
      case "appearance" -> {
        // appearance dark|light|toggle
        String mode = a.length > 1 ? a[1] : "toggle";
        if (mode.equals("toggle")) mode = isNight() ? "light" : "dark";
        overlayQualifiers(nightQualifier(mode));
        SimWindow.setMenuState(TAG_APPEARANCE, isNight());
        return configurationChanged(before) + " (" + mode + ")";
      }
      case "config" -> {
        // config: the configuration the app and its top activity see.
        Activity top = stack.isEmpty() ? null : stack.peek().get();
        return "app " + app.getResources().getConfiguration() + (top == null ? "" : " | activity " + top.getResources().getConfiguration());
      }
      case "clock" -> {
        // clock [ISO|epoch-ms|real]: the wall clock the app sees starts at that instant and runs on (SimClock)
        return SimClock.command(a);
      }
      case "timezone" -> {
        // timezone: the zone the app sees, the JDK's and android.icu's (set at launch with --timezone ZONE)
        String icu = android.icu.util.TimeZone.getDefault().getID();
        String jdk = java.util.TimeZone.getDefault().getID();
        return jdk.equals(icu) ? jdk : jdk + " (android.icu: " + icu + ")";
      }
      case "locale" -> {
        // locale fr-FR
        if (a.length < 2) return app.getResources().getConfiguration().getLocales().toLanguageTags();
        applyLocale(a[1]);
        return configurationChanged(before);
      }
      case "fontscale" -> {
        // fontscale 1.3 (1 is the default; Android offers 0.85 to 2), or + and - for the next step
        float current = app.getResources().getConfiguration().fontScale;
        if (a.length < 2) return String.valueOf(current);
        float scale = switch (a[1]) {
          case "+" -> step(current, 1);
          case "-" -> step(current, -1);
          default -> Float.parseFloat(a[1]);
        };
        RuntimeEnvironment.setFontScale(scale);
        return configurationChanged(before);
      }
      case "animscale" -> {
        // animscale 10 slows animations tenfold, 0 turns them off, 1 is normal. Views read ValueAnimator's scale;
        // Compose reads the global setting and watches it.
        float scale = a.length < 2 ? 1f : a[1].equals("toggle") ? (slowAnimations ? 1f : 10f) : Float.parseFloat(a[1]);
        slowAnimations = scale > 1f;
        SimWindow.setMenuState(TAG_SLOW_ANIMATIONS, slowAnimations);
        setAnimatorScale(app, scale);
        return "ok";
      }
      case "memory" -> {
        // memory [moderate|low|critical]: what the system sends when the device runs short.
        String level = a.length > 1 ? a[1] : "critical";
        int trim = switch (level) {
          case "moderate" -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
          case "low" -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
          default -> ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        };
        app.onTrimMemory(trim);
        for (ActivityController<? extends Activity> c : stack) c.get().onTrimMemory(trim);
        if (trim == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
          app.onLowMemory();
          for (ActivityController<? extends Activity> c : stack) c.get().onLowMemory();
        }
        return "ok";
      }
      case "home", "lock" -> {
        return hide(a[0]);
      }
      case "resume", "unlock" -> {
        return show();
      }
      case "record" -> {
        // record start [FILE.mov] | stop | toggle: the screen as a movie (Mac window only). Default file: work/screenshots.
        String op = a.length > 1 ? a[1] : "toggle";
        if (op.equals("toggle")) op = recording == null ? "start" : "stop";
        try {
          if (op.equals("start")) {
            // On a Mac window: H.264 QuickTime through AVFoundation. Headless or on Linux: Motion-JPEG AVI.
            boolean avi = SimWindow.isHeadless() || (a.length > 2 && a[2].endsWith(".avi"));
            String path = a.length > 2 ? Storage.absolute(a[2]).toString() : newMediaFile(avi ? "avi" : "mov").toString();
            String err = avi ? MjpegRecorder.start(path, w, h) : SimWindow.recordStart(path);
            // Frames reach the AVI only as they are drawn: draw one now, so a still screen is recorded too.
            if (avi && err == null) for (android.view.View root : rootViews()) root.invalidate();
            if (err != null) return err;
            recording = path;
            SimWindow.setMenuState(TAG_RECORD, true);
            return "recording to " + path;
          }
          long ms = MjpegRecorder.recording() ? MjpegRecorder.stop() : SimWindow.recordStop();
          String path = recording;
          recording = null;
          SimWindow.setMenuState(TAG_RECORD, false);
          return ms < 0 ? "not recording" : "saved " + path + " (" + ms / 1000.0 + " s)";
        } catch (java.io.IOException e) {
          return e.toString();
        }
      }
      case "clipboard" -> {
        // clipboard: the app's clipboard text; clipboard set TEXT: copies TEXT in the app (and so to the Mac).
        ClipboardManager cm = (ClipboardManager) app.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (a.length > 2 && a[1].equals("set")) {
          cm.setPrimaryClip(ClipData.newPlainText("apksim", String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length))));
          return "ok";
        }
        ClipData clip = cm.getPrimaryClip();
        return clip == null || clip.getItemCount() == 0 ? "(empty)" : String.valueOf(clip.getItemAt(0).coerceToText(app));
      }
      case "push" -> {
        // push key=value... | push {"data":{...},"notification":{"title":..,"body":..}}: an FCM message, delivered to
        // the app's FirebaseMessagingService as Play Services would (`simctl push` for Android).
        return push(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
      }
      case "jobs", "services", "alarms" -> {
        // jobs [run ID] | services | alarms: background work the app scheduled (BackgroundWork).
        return background().run(a);
      }
      case "keyboard" -> {
        // keyboard soft on|off: a software keyboard for text fields (off: a hardware keyboard is connected);
        // keyboard show|hide: raise or dismiss it now.
        String r = keyboard.run(a, rootViews());
        SimWindow.setMenuState(TAG_SOFT_KEYBOARD, SoftKeyboard.enabled);
        return r;
      }
      case "pick" -> {
        // pick FILE... | pick cancel: answers a document or photo picker the app opened.
        return results.pick(a);
      }
      case "biometrics" -> {
        if (a.length > 1 && a[1].equals("toggle")) a = new String[] {"biometrics", Biometrics.enrolled ? "unenroll" : "enroll"};
        String r = Biometrics.run(a);
        SimWindow.setMenuState(TAG_BIOMETRICS_ENROLLED, Biometrics.enrolled);
        return r;
      }
      case "location" -> {
        return location().run(a);
      }
      case "network" -> {
        // network [online|offline|3g|lossy|latency MS|bandwidth KBPS|unmatched [clear]|save] (docs/network.md).
        return dev.apksim.net.Net.command(a);
      }
      case "notifications" -> {
        return notifications().run(a);
      }
      case "permission" -> {
        return permissions.run(a);
      }
      case "reveal" -> {
        // reveal: shows the screenshots and recordings folder in the Finder.
        try {
          new ProcessBuilder("/usr/bin/open", mediaDir().toString()).start();
          return "ok";
        } catch (java.io.IOException e) {
          return e.toString();
        }
      }
      case "recreate" -> {
        // recreate: what a configuration change does (ViewModels survive, saved state is restored). Robolectric's
        // recreate() needs window focus before the new window exists, so go through its configuration change, with a
        // change no activity handles itself (assets paths).
        Configuration config = new Configuration(app.getResources().getConfiguration());
        for (ActivityController<? extends Activity> c : stack) c.configurationChange(config, app.getResources().getDisplayMetrics(), 0x80000000);
        ActivityController<? extends Activity> top = stack.peek();
        if (top != null && hiddenBy == null && top.get().getWindow().getDecorView().getViewRootImpl() == null) top.visible();
        return "ok";
      }
      case "process-death" -> {
        return processDeath();
      }
      case "debug" -> {
        // debug layout on|off: Developer options' "Show layout bounds" (views and Compose).
        if (a.length < 3 || !a[1].equals("layout")) return "usage: debug layout on|off|toggle";
        boolean on = a[2].equals("toggle") ? !layoutBounds : a[2].equals("on");
        layoutBounds = on;
        SimWindow.setMenuState(TAG_LAYOUT_BOUNDS, on);
        org.robolectric.shadows.ShadowSystemProperties.override("debug.layout", String.valueOf(on));
        int compose = 0;
        int missed = 0;
        for (android.view.View root : rootViews()) {
          android.view.ViewRootImpl vri = root.getViewRootImpl();
          if (vri != null) vri.loadSystemProperties();  // re-reads it and redraws every view
          // Compose reads it once, when its view is attached: tell each AndroidComposeView and redraw its nodes.
          java.util.ArrayDeque<android.view.View> todo = new java.util.ArrayDeque<>(List.of(root));
          while (!todo.isEmpty()) {
            android.view.View v = todo.pop();
            if (v instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) todo.push(g.getChildAt(i));
            if (!v.getClass().getName().endsWith("AndroidComposeView")) continue;
            try {
              v.getClass().getMethod("setShowLayoutBounds", boolean.class).invoke(v, on);
              v.getClass().getMethod("invalidateDescendants").invoke(v);
              compose++;
            } catch (ReflectiveOperationException e) {
              missed++;  // minified: names differ
            }
          }
        }
        return missed > 0 ? "ok for views; Compose in this (minified) app shows them after `recreate`"
            : "ok" + (compose > 0 ? " (" + compose + " Compose view" + (compose == 1 ? "" : "s") + ")" : "");
      }
      case "a11y" -> {
        // a11y: unlabeled controls, small touch targets, undescribed focusable images. "a11y show": in a sheet too.
        String r = A11yAudit.run(rootViews(), app.getResources().getDisplayMetrics().density);
        if (a.length > 1 && a[1].equals("show")) SimWindow.ask("Accessibility audit", r.replace(" ; ", "\n"), new String[] {"OK"});
        return r;
      }
      case "zoom" -> {
        // zoom actual|fit|in|out: the window's size (Window menu, Cmd+1/3/=/-); the app sees no change.
        int how = java.util.List.of("actual", "fit", "in", "out").indexOf(a.length > 1 ? a[1] : "");
        if (how < 0) return "usage: zoom actual|fit|in|out";
        if (SimWindow.isHeadless()) return "no window";
        SimWindow.zoom(how);
        return "ok";
      }
      case "device" -> {
        // device: what the app runs on; device phone|foldable|tablet|desktop|tv|WxH[@density]: switch to another.
        if (a.length < 2) return Device.current().describe() + " (" + w + "x" + h + " px); presets: " + Device.names();
        Device to = Device.named(a[1]);
        return reshape(to, to.widthPx(), to.heightPx(), true, before);
      }
      case "size" -> {
        // size WxH: the display's size in dp, keeping the device's kind (as dragging the window's edge does).
        if (a.length < 2) return Device.current().describe() + " (" + w + "x" + h + " px)";
        String[] wh = a[1].split("x");
        if (wh.length != 2) return "usage: size WxH (dp)";
        float d = Device.current().density();
        return reshape(Device.current(), Math.round(Integer.parseInt(wh[0]) * d), Math.round(Integer.parseInt(wh[1]) * d), false, before);
      }
      case "window" -> {
        // window WxH: sets the Mac window's content size in points as a drag of its corner would (checks that path).
        // window front|show|background|minimize|hide: how the window shows itself (--window); window: its state.
        if (a.length == 1) return SimWindow.windowState();
        int mode = SimWindow.mode(a[1]);
        if (mode >= 0) return SimWindow.setMode(mode) ? "ok" : "no window";
        String[] wh = a[1].split("x");
        if (wh.length != 2) return "usage: window [WxH (points)|front|show|background|minimize|hide]";
        return SimWindow.dragResize(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])) ? "ok" : "no window";
      }
      case "rotate" -> {
        // rotate left|right (a quarter turn), or rotate portrait|landscape
        String to = a.length > 1 ? a[1] : "right";
        boolean landscapeNow = w > h;
        int turn = switch (to) {
          case "left" -> 3;
          case "right" -> 1;
          case "portrait" -> landscapeNow ? (rotation == 1 ? 3 : 1) : 0;
          case "landscape" -> landscapeNow ? 0 : 1;
          default -> -1;
        };
        if (turn < 0) return "usage: rotate left|right|portrait|landscape";
        if (turn == 0) return "unchanged";
        return rotate(turn, before);
      }
      case "open" -> {
        // open URL: a deep link into the app, as a link tapped elsewhere would deliver it.
        if (a.length < 2) return "usage: open URL";
        return open(a[1]);
      }
      default -> {
        return null;
      }
    }
  }

  private String hide(String why) {
    if (hiddenBy != null) return "already " + hiddenBy;
    ActivityController<? extends Activity> top = stack.peek();
    if (top == null) return "no activity";
    if (why.equals("home")) top.userLeaving();
    top.pause().stop();
    // As ActivityThread on stop: the window is hidden, so its views see the visibility change (a SurfaceView gives up
    // its surface, a video pauses on it). Nothing is drawn while hidden either way.
    Host.setWindowVisible(top.get(), false);
    if (why.equals("home")) app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
    hiddenBy = why;
    SimWindow.present(placeholder(why));
    return "ok";
  }

  /** Brings the app back from the home or lock screen. */
  String show() {
    if (hiddenBy == null) return "not hidden";
    hiddenBy = null;
    ActivityController<? extends Activity> top = stack.peek();
    if (top != null) {
      Host.setWindowVisible(top.get(), true);
      top.restart().resume();
      Host.attachWindow(top);  // rebuilt by process-death while hidden: never shown yet
      top.get().getWindow().getDecorView().invalidate();
    }
    return "ok";
  }

  /** The home screen (the app's icon and name; tap to return) or a dark lock screen. */
  private ByteBuffer placeholder(String why) {
    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    c.drawColor(why.equals("lock") ? Color.BLACK : Color.rgb(28, 32, 38));
    float d = app.getResources().getDisplayMetrics().density;
    Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    text.setColor(Color.WHITE);
    text.setTextAlign(Paint.Align.CENTER);
    if (why.equals("home")) {
      int size = (int) (64 * d);
      try {
        Drawable icon = app.getPackageManager().getApplicationIcon(app.getApplicationInfo());
        icon.setBounds(w / 2 - size / 2, h / 3 - size / 2, w / 2 + size / 2, h / 3 + size / 2);
        icon.draw(c);
      } catch (RuntimeException e) {
        // No usable icon: the name is enough.
      }
      text.setTextSize(14 * d);
      c.drawText(String.valueOf(app.getApplicationInfo().loadLabel(app.getPackageManager())), w / 2f, h / 3f + size / 2f + 24 * d, text);
      text.setColor(Color.argb(150, 255, 255, 255));
      text.setTextSize(12 * d);
      c.drawText("Click to return to the app", w / 2f, h - 64 * d, text);
    } else {
      text.setTextSize(48 * d);
      c.drawText(String.format(Locale.ROOT, "%tR", System.currentTimeMillis()), w / 2f, h / 4f, text);
      text.setTextSize(12 * d);
      text.setColor(Color.argb(150, 255, 255, 255));
      c.drawText("Click to unlock", w / 2f, h - 64 * d, text);
    }
    ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
    b.copyPixelsToBuffer(out);
    out.rewind();
    b.recycle();
    return out;
  }

  private String open(String url) {
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(app.getPackageName())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    List<ResolveInfo> handlers = app.getPackageManager().queryIntentActivities(intent, 0);
    if (handlers.isEmpty()) return "no activity in " + app.getPackageName() + " handles " + url;
    ActivityController<? extends Activity> top = stack.peek();
    if (hiddenBy != null) show();
    if (top != null) top.get().startActivity(intent);
    else app.startActivity(intent);
    return "opening in " + handlers.get(0).activityInfo.name;
  }

  /**
   * A quarter turn changes the display's size, orientation and rotation: the qualifiers swap width and height, the
   * renderer and window resize, and activities get the configuration change (recreated unless they handle it).
   */
  private String rotate(int turn, Configuration before) {
    if (recording != null) run(new String[] {"record", "stop"});  // a movie keeps one frame size
    rotation = (rotation + turn) % 4;
    int nw = h;
    int nh = w;
    float d = app.getResources().getDisplayMetrics().density;
    RuntimeEnvironment.setQualifiers("+w" + Math.round(nw / d) + "dp-h" + Math.round(nh / d) + "dp-" + (nw > nh ? "land" : "port"));
    org.robolectric.shadows.ShadowDisplay display = org.robolectric.Shadows.shadowOf(
        app.getSystemService(android.view.WindowManager.class).getDefaultDisplay());
    display.setRotation(rotation);
    w = nw;
    h = nh;
    resizer.resize(nw, nh, false);
    Device.setCurrent(Device.current().withSize(Math.round(nw / d), Math.round(nh / d)));
    String r = configurationChanged(before);
    windowsResized();
    showTitle();
    return r + " (" + (nw > nh ? "landscape" : "portrait") + ", rotation " + rotation * 90 + ")";
  }

  /** Activities that handle a size change themselves keep their window: give it the new size. */
  private void windowsResized() {
    for (ActivityController<? extends Activity> c : stack) {
      android.view.ViewRootImpl root = c.get().getWindow().getDecorView().getViewRootImpl();
      if (root == null) continue;
      Object shadow = org.robolectric.shadow.api.Shadow.extract(root);
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(shadow, "callDispatchResized");
      c.get().getWindow().getDecorView().requestLayout();
    }
  }

  /** The user dragged the window's edge: the display follows, at most every 100 ms while dragging. */
  void windowResized(int pxW, int pxH, boolean done) {
    resizeW = pxW;
    resizeH = pxH;
    resizeDone |= done;
    applyWindowResize();
  }

  private void applyWindowResize() {
    if (resizeW == 0) return;
    long now = System.nanoTime();
    if (!resizeDone && now - resizedNanos < RESIZE_INTERVAL_NANOS) return;
    int pw = resizeW;
    int ph = resizeH;
    boolean done = resizeDone;
    resizeW = resizeH = 0;
    resizeDone = false;
    resizedNanos = now;
    Configuration before = new Configuration(app.getResources().getConfiguration());
    String r = reshape(Device.current(), pw, ph, false, before);
    if (done) System.err.println("[apksim] window resized: " + r);
  }

  /**
   * A new display: another device (kind, density, system bars), or the same one at another size. The qualifiers
   * are rewritten whole (Device.withQualifiers), the renderer and window take the new size, and activities get the
   * configuration change, as on a resizable emulator or a device in a freeform window.
   */
  private String reshape(Device to, int pxW, int pxH, boolean newDevice, Configuration before) {
    float d = to.density();
    int wDp = Math.max(160, Math.round(pxW / d));
    int hDp = Math.max(160, Math.round(pxH / d));
    Device was = Device.current();
    Device now = to.withSize(wDp, hDp);
    if (!newDevice && now.equals(was) && pxW == w && pxH == h) {
      // The window snaps back to the display's size in whole dp, and the frame is drawn again into the new surfaces.
      resizer.resize(w, h, false);
      for (android.view.View root : rootViews()) root.invalidate();
      return "unchanged";
    }
    if (recording != null) run(new String[] {"record", "stop"});  // a movie keeps one frame size
    float fontScale = app.getResources().getConfiguration().fontScale;
    RuntimeEnvironment.setQualifiers(now.withQualifiers(RuntimeEnvironment.getQualifiers()));
    if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale);  // not a qualifier: a full string resets it
    if (newDevice) {
      rotation = 0;
      org.robolectric.Shadows.shadowOf(app.getSystemService(android.view.WindowManager.class).getDefaultDisplay()).setRotation(0);
    }
    Device.setCurrent(now);
    if (was.systemBars() != now.systemBars()) applySystemUi(now);
    android.util.DisplayMetrics m = app.getResources().getDisplayMetrics();
    w = m.widthPixels;
    h = m.heightPixels;
    resizer.resize(w, h, newDevice);
    String r = configurationChanged(before);
    windowsResized();
    showTitle();
    if (!now.systemBars()) SimWindow.setSystemBars(0, 0, false, false);
    return r + " (" + now.describe() + ", " + w + "x" + h + " px)";
  }

  /** Status bar and gesture navigation for phones, foldables and tablets; none on desktop and TV. */
  static void applySystemUi(Device d) {
    try {
      // Robolectric keeps SystemUi package-private.
      Class<?> ui = Class.forName("org.robolectric.shadows.SystemUi");
      Object display = org.robolectric.util.ReflectionHelpers.callStaticMethod(ui, "systemUiForDefaultDisplay");
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(display, "setBehavior",
          org.robolectric.util.ReflectionHelpers.ClassParameter.from(Class.forName("org.robolectric.shadows.SystemUi$StatusBarBehavior"),
              org.robolectric.util.ReflectionHelpers.getStaticField(ui, d.systemBars() ? "STANDARD_STATUS_BAR" : "NO_STATUS_BAR")),
          org.robolectric.util.ReflectionHelpers.ClassParameter.from(Class.forName("org.robolectric.shadows.SystemUi$NavigationBarBehavior"),
              org.robolectric.util.ReflectionHelpers.getStaticField(ui, d.systemBars() ? "GESTURAL_NAVIGATION" : "NO_NAVIGATION_BAR")));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The window's title: the APK and the device it runs on, with the display's size while it is being resized. */
  void showTitle() {
    Device d = Device.current();
    SimWindow.setTitle(System.getProperty("apksim.windowTitle", "apksim") + " - " + d.name()
        + " " + d.widthDp() + "\u00d7" + d.heightDp() + " dp");
    for (int i = 0; i < DEVICE_MENU.length; i++) SimWindow.setMenuState(TAG_DEVICE_FIRST + i, DEVICE_MENU[i].equals(d.name()));
  }

  /**
   * The system killing the app in the background and the user returning: every activity saves its state, is destroyed
   * (ViewModels cleared) and is rebuilt from that state. The JVM lives on, so statics and the Application object
   * survive, which a real process death would reset; what this catches is state that is not saved and restored.
   */
  private String processDeath() {
    java.util.List<ActivityController<? extends Activity>> old = new java.util.ArrayList<>(stack);
    java.util.Collections.reverse(old);  // bottom first
    java.util.List<android.os.Bundle> states = new java.util.ArrayList<>();
    for (ActivityController<? extends Activity> c : old) {
      android.os.Bundle b = new android.os.Bundle();
      c.saveInstanceState(b);
      states.add(b);
    }
    ActivityController<? extends Activity> top = stack.peek();
    if (top != null && hiddenBy == null) top.pause().stop();
    for (ActivityController<? extends Activity> c : old) c.destroy();
    stack.clear();
    for (int i = 0; i < old.size(); i++) {
      Activity was = old.get(i).get();
      android.os.Bundle b = states.get(i);
      ActivityController<? extends Activity> c = org.robolectric.Robolectric.buildActivity(was.getClass(), was.getIntent());
      c.create(b).start().restoreInstanceState(b).postCreate(b);
      if (i == old.size() - 1 && hiddenBy == null) {
        c.resume().visible();
      } else {
        c.stop();
      }
      stack.push(c);
    }
    return "ok, " + old.size() + " activit" + (old.size() == 1 ? "y" : "ies") + " rebuilt from saved state";
  }

  private static List<android.view.View> rootViews() {
    return Host.rootViews();
  }

  private static float step(float current, int direction) {
    int i = 0;
    while (i < FONT_STEPS.length - 1 && FONT_STEPS[i] < current - 0.01f) i++;
    if (direction > 0 && FONT_STEPS[i] <= current + 0.01f) i++;
    if (direction < 0) i--;
    return FONT_STEPS[Math.max(0, Math.min(FONT_STEPS.length - 1, i))];
  }

  private boolean isNight() {
    return (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
  }

  private static String nightQualifier(String mode) {
    return switch (mode) {
      case "dark", "night" -> "+night";
      case "light", "day", "notnight" -> "+notnight";
      default -> throw new IllegalArgumentException("appearance is dark or light, not " + mode);
    };
  }

  /** "fr-FR" or "fr": sets the resource qualifier and the JVM default the app's formatting uses. */
  private static void applyLocale(String tag) {
    Locale locale = Locale.forLanguageTag(tag);
    String q = locale.getLanguage() + (locale.getCountry().isEmpty() ? "" : "-r" + locale.getCountry());
    overlayQualifiers("+" + q);
    Locale.setDefault(locale);
  }

  /**
   * RuntimeEnvironment.setQualifiers("+night") (or a locale), keeping the display as it is. Robolectric first rebuilds
   * the default display from the overlay alone (ShadowDisplayManager.changeDisplay), which turned a landscape display
   * portrait: on a tablet, dark mode left the configuration at 2560x1600 and gave new windows 1600x2560. So the
   * display is set again from the full qualifiers the overlay produced.
   */
  private static void overlayQualifiers(String q) {
    int rotation = app().getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation();
    RuntimeEnvironment.setQualifiers(q);
    org.robolectric.shadows.ShadowDisplayManager.changeDisplay(android.view.Display.DEFAULT_DISPLAY, RuntimeEnvironment.getQualifiers());
    org.robolectric.Shadows.shadowOf(app().getSystemService(android.view.WindowManager.class).getDefaultDisplay()).setRotation(rotation);
  }

  private static Application app() {
    return RuntimeEnvironment.getApplication();
  }

  /**
   * Delivers the new configuration to every activity: recreated, or onConfigurationChanged if it handles it.
   * setQualifiers has already updated the shared resources, so Robolectric would see no difference: say what changed.
   */
  private String configurationChanged(Configuration before) {
    Configuration config = new Configuration(app.getResources().getConfiguration());
    int changed = before.diff(config);
    if (changed == 0) return "unchanged";
    int n = 0;
    for (ActivityController<? extends Activity> c : stack) {
      c.configurationChange(config, app.getResources().getDisplayMetrics(), changed);
      n++;
    }
    // Robolectric's recreate() leaves the new activity's window detached (setup() would call visible()).
    ActivityController<? extends Activity> top = stack.peek();
    if (top != null && hiddenBy == null && top.get().getWindow().getDecorView().getViewRootImpl() == null) top.visible();
    return "ok, " + n + " activit" + (n == 1 ? "y" : "ies") + " updated";
  }
}
