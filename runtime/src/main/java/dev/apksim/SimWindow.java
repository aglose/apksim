package dev.apksim;

import java.nio.ByteBuffer;

/**
 * The host window (native/simwindow.m). Knows nothing about Android: it shows RGBA frames and queues
 * raw input. It opens at process start, so it never waits for the framework.
 */
final class SimWindow {
  static final int DOWN = 0;
  static final int UP = 1;
  static final int MOVE = 2;
  static final int WHEEL = 3;
  static final int BACK = 4;
  static final int CLOSE = 5;
  static final int CHAR = 6;
  static final int KEY = 7;
  /** A menu item: x is its tag (DeviceControls.menuCommand). */
  static final int COMMAND = 8;
  /** Two fingers: CENTRE sets the point the second finger mirrors the first around; value 1 = move both together. */
  static final int TOUCH2_CENTRE = 9;
  static final int TOUCH2_DOWN = 10;
  static final int TOUCH2_MOVE = 11;
  static final int TOUCH2_UP = 12;
  /** The user resized the window: x, y are its new size in device pixels; value 1 once the drag has ended. */
  static final int RESIZE = 13;
  /** A one-finger gesture cancelled (UiAutomation's ACTION_CANCEL): the target gets ACTION_CANCEL, not a release. */
  static final int CANCEL = 14;
  /** The crown turned (`rotary N`, or the wheel on a watch): value is AXIS_SCROLL, negative clockwise; x, y the window point. */
  static final int ROTARY = 15;

  /**
   * A CHAR event's y when the character comes from the clipboard (Cmd+V, `paste`), not the keyboard: the flight
   * recorder leaves pasted text out. Dispatch reads only a CHAR's value.
   */
  static final float PASTED = 1;

  /** Raw input in device pixels. */
  record Event(int type, float x, float y, float value) {}

  private static final float[] SLOT = new float[4];
  /** Until {@link #open}, as headless: a standby renders its warm-up with no window (Standby). */
  private static boolean headless = true;

  private SimWindow() {}

  /** Loads the window's library ahead of {@link #open} (a standby, before it waits). */
  static void preload() {
    if (HostOs.MAC) System.load(HostOs.runtimeLib("libsimwindow").toString());
  }

  static void open(int w, int h, float scale, String title) {
    headless = Boolean.getBoolean("apksim.headless") || !HostOs.MAC;
    if (headless) return;
    System.load(HostOs.runtimeLib("libsimwindow").toString());
    String mode = System.getProperty("apksim.window", "front");
    int m = mode(mode);
    if (m < 0) {
      System.err.println("apksim: unknown --window " + mode + " (" + String.join("|", MODES) + "), using front");
      m = 0;
    }
    nativeOpen(w, h, scale, title, Integer.getInteger("apksim.windowX", 0), m);
    synchronized (SimWindow.class) {
      windowOpen = true;
      nativeSetCloseHides(closeHidesWanted);  // a stream viewer may have come before the window (a cold launch)
    }
    // Host's loop sleeps while nothing happens (Host.idleWait); window input wakes it from here.
    Thread waiter = new Thread(() -> {
      long seen = 0;
      while (true) {
        long now = nativeWaitInput(seen, 1000);
        if (now != seen) Host.wake();
        seen = now;
      }
    }, "apksim-input-wait");
    waiter.setDaemon(true);
    waiter.start();
    Timing.mark("window-open");
  }

  private static native long nativeWaitInput(long seen, int timeoutMs);

  /** Whether the window (and its library) is up: natives other than the open's may be called from then. */
  private static boolean windowOpen;
  private static boolean closeHidesWanted;

  static void present(ByteBuffer rgba) {
    if (!headless) nativePresent(rgba);
    if (MjpegRecorder.recording()) MjpegRecorder.frame(rgba);
  }

  static Event poll() {
    if (headless || nativePoll(SLOT) == 0) return null;
    return new Event((int) SLOT[0], SLOT[1], SLOT[2], SLOT[3]);
  }

  static void windowSnapshot(String path) {
    if (!headless) nativeWindowSnapshot(path);
  }

  private static native void nativeWindowSnapshot(String path);

  /**
   * The display as a device's screencap shows it, the app's frame with the drawn system UI over it (bars, software
   * keyboard, banner), into a w x h direct buffer of BGRA pixels. False when there is no window or it is another
   * size (the caller then has only the app's pixels).
   */
  static boolean displayImage(ByteBuffer bgra, int w, int h) {
    return displayAvailable() && nativeDisplayImage(bgra, w, h) != 0;
  }

  /** Whether there is a window to draw the display from (none headless: a caller need not allocate a buffer). */
  static boolean displayAvailable() {
    return !headless;
  }

  private static native int nativeDisplayImage(ByteBuffer bgra, int w, int h);

  private static native String nativeDisplayInfo();

  private static native void nativeBoostThread();

  private static native int nativePaste();

  private static native String nativeClipboardText();

  /**
   * Whether the app's clipboard follows the Mac's, both ways. Not for a beamed instance (BeamSandbox): a web site's app
   * would read whatever the visitor copies (a password) and could write to it. Cmd+V in its shown window, which only the
   * visitor can press there, still types the clipboard in.
   */
  private static boolean macClipboard() {
    return !headless && !BeamSandbox.ON;
  }

  /** The Mac clipboard's text, or null. */
  static String clipboardText() {
    return macClipboard() ? nativeClipboardText() : null;
  }

  /** Types the Mac clipboard's text into the app, like Cmd+V in the window. */
  static int paste() {
    return headless ? 0 : nativePaste();
  }

  /** Call on threads that produce frames. */
  static void boostThread() {
    if (!headless && !Boolean.getBoolean("apksim.noBoost")) nativeBoostThread();
  }

  /**
   * Nanoseconds until the display's next vsync, or -1 without a window or while macOS is not showing it. Then, if a
   * viewer elsewhere has told its own display's pace (`vsync`, from apksim serve's page: {@link #remoteVsync}), the
   * time until that one's next.
   */
  static long untilVsync() {
    long n = headless ? -1 : nativeUntilVsync();
    return n >= 0 ? n : untilRemoteVsync();
  }

  /** The period of the vsyncs {@link #untilVsync} follows (the window's display, else a viewer's), or -1. */
  static long vsyncPeriodNanos() {
    if (!headless && nativeUntilVsync() >= 0) return nativeVsyncPeriod();
    long period = remotePeriodNanos;
    return period > 0 && System.nanoTime() - remoteSeenNanos < REMOTE_FRESH_NANOS ? period : -1;
  }

  /** What reached the screen since the last call, counted per display vsync. */
  static String vsyncStats() {
    String remote = remotePeriodNanos > 0 && System.nanoTime() - remoteSeenNanos < REMOTE_FRESH_NANOS
        ? String.format(" (pacing to a viewer's %.1f Hz display)", 1e9 / remotePeriodNanos) : "";
    return (headless ? "screen: headless" : nativeVsyncStats()) + remote;
  }

  // A viewer's display, for an instance whose frames are shown elsewhere (apksim serve and beam stream them into a
  // browser tab, docs/beam.md "Phase 1b"). The tab measures where in its own refresh interval each frame is drawn and
  // sends its period and how far to move these vsyncs so frames land mid-interval: with Host's own 120 Hz grid, two
  // clocks drifted, and on bad runs two frames reached one browser refresh and none the next (91% new frames against
  // the window's 99%). Without an update for a second and a half (the tab hidden, closed) Host's grid paces again.
  private static final long REMOTE_FRESH_NANOS = 1_500_000_000L;
  private static volatile long remotePeriodNanos;
  private static volatile long remoteAnchorNanos;
  private static volatile long remoteSeenNanos;

  /**
   * `vsync PERIOD_US SHIFT_US`: the viewer's display refreshes every PERIOD_US, and these vsyncs should move by SHIFT_US
   * (negative: earlier). `vsync off` forgets it; `vsync` says what is in use.
   */
  static synchronized String remoteVsync(String[] a) {
    if (a.length == 1) {
      boolean fresh = remotePeriodNanos > 0 && System.nanoTime() - remoteSeenNanos < REMOTE_FRESH_NANOS;
      return fresh ? String.format("vsync: a viewer's %.1f Hz display", 1e9 / remotePeriodNanos) : "vsync: this instance's own";
    }
    if (a.length == 2 && a[1].equals("off")) {
      remotePeriodNanos = 0;
      return "ok";
    }
    if (a.length != 3) throw new IllegalArgumentException("usage: vsync [PERIOD_US SHIFT_US | off]");
    long period = Long.parseLong(a[1]) * 1000;
    long shift = Long.parseLong(a[2]) * 1000;
    if (period < 4_000_000L || period > 50_000_000L || Math.abs(shift) > period) {
      throw new IllegalArgumentException("vsync: a period of 4000-50000 us and a shift of at most one period");
    }
    long now = System.nanoTime();
    long old = remotePeriodNanos;
    boolean fresh = old > 0 && now - remoteSeenNanos < REMOTE_FRESH_NANOS;
    // The latest vsync under the old period becomes the anchor, so a new period only spaces the vsyncs to come: kept
    // as it was, every interval since the anchor would be read again at the new period, a phase jump that grew with the
    // session's age on each small change of the page's estimate.
    long anchor = fresh ? now - Math.floorMod(now - remoteAnchorNanos, old) : now;
    remoteAnchorNanos = anchor + shift;
    remotePeriodNanos = period;
    remoteSeenNanos = now;
    return "ok";
  }

  private static long untilRemoteVsync() {
    long period = remotePeriodNanos;
    long now = System.nanoTime();
    if (period <= 0 || now - remoteSeenNanos >= REMOTE_FRESH_NANOS) return -1;
    return Math.floorMod(remoteAnchorNanos - now, period);
  }

  private static native long nativeUntilVsync();

  private static native long nativeVsyncPeriod();

  private static native void nativeSetCloseHides(boolean on);

  private static native void nativeClose();

  /** `window close`: as the window's close button (hides it while a viewer is attached, else the app ends). */
  static boolean close() {
    if (headless) return false;
    nativeClose();
    return true;
  }

  /**
   * While a stream viewer is attached (FrameStream: apksim beam's tab), the window's close button hides the window and
   * the app runs on there; Quit still ends it.
   */
  static synchronized void closeHides(boolean on) {
    closeHidesWanted = on;
    if (windowOpen) nativeSetCloseHides(on);
  }

  private static native String nativeVsyncStats();

  static String displayInfo() {
    return headless ? "headless" : nativeDisplayInfo();
  }

  private static native void nativeOpen(int w, int h, float scale, String title, int xOffset, int mode);

  /**
   * How the window shows itself, as native/simwindow.m's WIN_*: front takes focus as a normal app does; nofocus shows
   * it on top without taking focus; background behind every other window; minimized in the Dock; hidden not at all
   * (the Dock icon brings it back). The app runs the same in each.
   */
  static final String[] MODES = {"front", "nofocus", "background", "minimized", "hidden"};

  /** A mode's index in {@link #MODES} (with a few synonyms), or -1. */
  static int mode(String name) {
    String n = switch (name) {
      case "show" -> "nofocus";
      case "back" -> "background";
      case "minimize", "dock", "docked" -> "minimized";
      case "hide" -> "hidden";
      default -> name;
    };
    return java.util.Arrays.asList(MODES).indexOf(n);
  }

  /** Changes how the window shows itself while running; false without a window. */
  static boolean setMode(int mode) {
    if (headless) return false;
    nativeWindowMode(mode);
    return true;
  }

  static String windowState() {
    return headless ? "headless" : nativeWindowState();
  }

  private static native void nativeWindowMode(int mode);

  private static native String nativeWindowState();

  private static native void nativePresent(ByteBuffer rgba);

  private static native int nativePoll(float[] out);

  /** Starts recording the screen to a QuickTime movie; returns null, or why it could not. */
  static String recordStart(String path) {
    return headless ? "recording needs the Mac window" : nativeRecordStart(path);
  }

  /** Finishes the movie; its length in ms, or -1 if nothing was recording. */
  static long recordStop() {
    return headless ? -1 : nativeRecordStop();
  }

  /** Text a menu item asked for (Open URL), or null. */
  static String takeText() {
    return headless ? null : nativeTakeText();
  }

  /** Shows a menu item as on or off (a checkmark). */
  static void setMenuState(int tag, boolean on) {
    if (!headless) nativeSetMenuState(tag, on);
  }

  /** NSPasteboard's change count: it moves whenever anything on the Mac copies. -1 without a window. */
  static long clipboardChangeCount() {
    return macClipboard() ? nativeClipboardChangeCount() : -1;
  }

  /** Puts text on the Mac clipboard and returns the new change count. */
  static long setClipboardText(String text) {
    return macClipboard() ? nativeSetClipboardText(text) : -1;
  }

  static boolean isHeadless() {
    return headless;
  }

  /** Draws the status bar (clock, Wi-Fi, battery) and gesture handle over the app; dark icons for light bars. */
  static void setSystemBars(int statusPx, int navPx, boolean darkStatusIcons, boolean darkHandle) {
    if (!headless) nativeSetSystemBars(statusPx, navPx, darkStatusIcons, darkHandle);
  }

  private static native void nativeSetSystemBars(int statusPx, int navPx, boolean darkStatusIcons, boolean darkHandle);

  /** The status bar's clock follows the device's zone (TimeZones). */
  static void setTimeZone(String zone) {
    if (!headless) nativeSetTimeZone(zone);
  }

  private static native void nativeSetTimeZone(String zone);

  /** The display's new size in device pixels (rotation, resizing); call with no frame being presented. */
  /** actualSize: the window goes back to actual size (zoomed out only as far as the Mac's screen needs), for a new device. */
  static void resize(int w, int h, boolean actualSize) {
    if (!headless) nativeResize(w, h, actualSize);
  }

  private static native void nativeResize(int w, int h, boolean actualSize);

  /**
   * A folding device is about to switch displays: the device's picture (in its skin's frame) goes on an overlay and the
   * window is hidden until the fold has played. True when
   * a fold animation will play ({@link #foldRun} after the switch); false without a visible window.
   */
  static boolean foldBegin() {
    return !headless && Boolean.parseBoolean(System.getProperty("apksim.foldAnimation", "true")) && nativeFoldBegin();
  }

  /** The displays switched: once the new one has drawn, the overlay folds the device shut or open, as a book, over ms. */
  static void foldRun(boolean closing, int ms) {
    if (!headless) nativeFoldRun(closing, ms);
  }

  private static native boolean nativeFoldBegin();

  private static native void nativeFoldRun(boolean closing, int ms);

  static void setTitle(String title) {
    if (!headless) nativeSetTitle(title);
  }

  private static native void nativeSetTitle(String title);

  /**
   * Resizes the window's content to this many points as the user dragging its corner would: at once as a drag's end,
   * or with ms over that much real time as a live drag (120 steps a second, then its end). Answers "ok", or why not.
   */
  static String dragResize(int wPts, int hPts, int ms) {
    if (headless) return "no window";
    return switch (nativeDragResize(wPts, hPts, ms)) {
      case 1 -> "error: a drag of the window is under way";
      case 2 -> "error: a skinned window is its frame's size (skin off first)";
      default -> "ok";
    };
  }

  private static native int nativeDragResize(int wPts, int hPts, int ms);

  /**
   * Draws a skin around the screen (Skinning), or none with a null layout: the window becomes the frame's size, the
   * screen sits at its place in it, clipped to its corners or mask, and only the screen takes clicks.
   */
  static void setSkin(Skinning.Layout l) {
    if (headless) return;
    if (l == null) {
      nativeSetSkin(null, 0, null, 0, 0, 0, null, 0, 0, 0);
      return;
    }
    int[] turns = new int[1];
    byte[] frame = Skinning.frameImage(l, turns);
    nativeSetSkin(frame, turns[0], Skinning.maskImage(), l.quarterTurns(), l.outerW(), l.outerH(),
        new int[] {l.holeX(), l.holeY(), l.holeW(), l.holeH()}, l.screenX(), l.screenY(), l.cornerRadius());
  }

  /** frame null: no skin. Sizes in device pixels; the screen is the display's own size; hole: x, y, w, h. */
  private static native void nativeSetSkin(byte[] frame, int frameTurns, byte[] mask, int maskTurns, int outerW, int outerH,
      int[] hole, int screenX, int screenY, int cornerRadius);

  /** Device > Skin: None, then these skins (titles), tags {@link #TAG_SKIN_FIRST} + 1 + index. */
  static void setSkinMenu(java.util.List<String> titles) {
    if (!headless) nativeSetSkinMenu(titles.toArray(new String[0]));
  }

  static final int TAG_SKIN_FIRST = 200;

  private static native void nativeSetSkinMenu(String[] titles);

  /**
   * A mouse click at a point of the window's content (points, top left), sent through the window as the user's would
   * be (`window click X Y`): checks which view takes it, the screen or the frame around it.
   */
  static boolean click(float x, float y) {
    if (headless) return false;
    nativeClick(x, y);
    return true;
  }

  private static native void nativeClick(float x, float y);

  /** Draws the software keyboard over the bottom of the screen, heightPx tall; clicks on it type into the app. */
  static void showKeyboard(int heightPx) {
    if (!headless) nativeShowKeyboard(heightPx);
  }

  static void hideKeyboard() {
    if (!headless) nativeShowKeyboard(0);
  }

  private static native void nativeShowKeyboard(int heightPx);

  /** The window's zoom: 0 actual size, 1 fit the screen, 2 in, 3 out. The app's pixels do not change. */
  static void zoom(int how) {
    if (!headless) nativeZoom(how);
  }

  private static native void nativeZoom(int how);

  private static int askTicket;

  /**
   * Shows a question as a sheet on the window, replacing any other; returns a ticket for {@link #askAnswer}, or -1
   * without a window.
   */
  static synchronized int ask(String title, String message, String[] buttons) {
    if (headless) return -1;
    nativeAsk(title, message, buttons);
    return ++askTicket;
  }

  /** The index of the button pressed, -1 while the question is open, -2 if another question replaced it. */
  static synchronized int askAnswer(int ticket) {
    if (headless || ticket != askTicket) return -2;
    return nativeAskAnswer();
  }

  static void dismissAsk() {
    if (!headless) nativeDismissAsk();
  }

  /** A heads-up notification at the top of the window for five seconds; a click on it opens it. */
  static void showBanner(String app, String title, String text) {
    if (!headless) nativeShowBanner(app, title, text);
  }

  static void hideBanner() {
    if (!headless) nativeHideBanner();
  }

  private static native void nativeShowBanner(String app, String title, String text);

  private static native void nativeHideBanner();

  private static int pickTicket;

  /** Opens the Mac file panel as a sheet (MIME types, comma-separated); returns a ticket for {@link #pickResult}. */
  static synchronized int pickFiles(String mimeTypes, boolean multiple) {
    if (headless) return -1;
    nativePickFiles(mimeTypes, multiple);
    return ++pickTicket;
  }

  /** The chosen paths (newline-separated), "" if cancelled, or null while the panel is open. */
  static synchronized String pickResult(int ticket) {
    return headless || ticket != pickTicket ? "" : nativePickResult();
  }

  static void dismissPick() {
    if (!headless) nativeDismissPick();
  }

  /** The Mac's share menu, with text and/or a file. */
  static void share(String text, String path) {
    if (!headless) nativeShare(text, path);
  }

  /**
   * Opens a sign-in page in the system browser's authentication session, which ends at a redirect to `scheme://...`
   * (host null) or to https://host/path. False when this Mac cannot wait for that redirect (https needs macOS 14.4).
   */
  static boolean webAuth(String url, String scheme, String host, String path) {
    return !headless && nativeWebAuth(url, scheme, host, path);
  }

  /** The URL a finished sign-in session was redirected to, "" if it was cancelled or failed, null while none is done. */
  static String webAuthResult() {
    return headless ? null : nativeWebAuthResult();
  }

  private static native boolean nativeWebAuth(String url, String scheme, String host, String path);

  private static native String nativeWebAuthResult();

  private static native void nativePickFiles(String mimeTypes, boolean multiple);

  private static native String nativePickResult();

  private static native void nativeDismissPick();

  private static native void nativeShare(String text, String path);

  private static native void nativeAsk(String title, String message, String[] buttons);

  private static native int nativeAskAnswer();

  private static native void nativeDismissAsk();

  private static native String nativeRecordStart(String path);

  private static native long nativeRecordStop();

  private static native String nativeTakeText();

  private static native void nativeSetMenuState(int tag, boolean on);

  private static native long nativeClipboardChangeCount();

  private static native long nativeSetClipboardText(String text);
}
