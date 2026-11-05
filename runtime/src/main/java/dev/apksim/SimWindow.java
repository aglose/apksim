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
    Timing.mark("window-open");
  }

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

  private static native String nativeDisplayInfo();

  private static native void nativeBoostThread();

  private static native int nativePaste();

  private static native String nativeClipboardText();

  /** The Mac clipboard's text, or null. */
  static String clipboardText() {
    return headless ? null : nativeClipboardText();
  }

  /** Types the Mac clipboard's text into the app, like Cmd+V in the window. */
  static int paste() {
    return headless ? 0 : nativePaste();
  }

  /** Call on threads that produce frames. */
  static void boostThread() {
    if (!headless && !Boolean.getBoolean("apksim.noBoost")) nativeBoostThread();
  }

  /** Nanoseconds until the display's next vsync, or -1 without a window or while macOS is not showing it. */
  static long untilVsync() {
    return headless ? -1 : nativeUntilVsync();
  }

  /** What reached the screen since the last call, counted per display vsync. */
  static String vsyncStats() {
    return headless ? "screen: headless" : nativeVsyncStats();
  }

  private static native long nativeUntilVsync();

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
    return headless ? -1 : nativeClipboardChangeCount();
  }

  /** Puts text on the Mac clipboard and returns the new change count. */
  static long setClipboardText(String text) {
    return headless ? -1 : nativeSetClipboardText(text);
  }

  static boolean isHeadless() {
    return headless;
  }

  /** Draws the status bar (clock, Wi-Fi, battery) and gesture handle over the app; dark icons for light bars. */
  static void setSystemBars(int statusPx, int navPx, boolean darkStatusIcons, boolean darkHandle) {
    if (!headless) nativeSetSystemBars(statusPx, navPx, darkStatusIcons, darkHandle);
  }

  private static native void nativeSetSystemBars(int statusPx, int navPx, boolean darkStatusIcons, boolean darkHandle);

  /** The display's new size in device pixels (rotation, resizing); call with no frame being presented. */
  /** actualSize: the window goes back to actual size (zoomed out only as far as the Mac's screen needs), for a new device. */
  static void resize(int w, int h, boolean actualSize) {
    if (!headless) nativeResize(w, h, actualSize);
  }

  private static native void nativeResize(int w, int h, boolean actualSize);

  static void setTitle(String title) {
    if (!headless) nativeSetTitle(title);
  }

  private static native void nativeSetTitle(String title);

  /** Resizes the window's content to this many points as the user dragging its corner would; false headless. */
  static boolean dragResize(int wPts, int hPts) {
    if (headless) return false;
    nativeDragResize(wPts, hPts);
    return true;
  }

  private static native void nativeDragResize(int wPts, int hPts);

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
