// Native host window: a plain Cocoa window whose layer shows IOSurface frames, so a frame reaches the
// compositor with one memcpy and no toolkit in between. Input goes into a queue the Java side polls.
#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <IOSurface/IOSurface.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreVideo/CoreVideo.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <dlfcn.h>
#include <pthread/qos.h>
#include <jni.h>
#include <pthread.h>

enum { EV_DOWN = 0, EV_UP = 1, EV_MOVE = 2, EV_WHEEL = 3, EV_BACK = 4, EV_CLOSE = 5, EV_CHAR = 6, EV_KEY = 7, EV_COMMAND = 8,
       EV_TOUCH2_CENTRE = 9, EV_TOUCH2_DOWN = 10, EV_TOUCH2_MOVE = 11, EV_TOUCH2_UP = 12, EV_RESIZE = 13 };
typedef struct { int type; float x, y, v; } Event;

#define QUEUE_SIZE 4096
static Event queue[QUEUE_SIZE];
static int qHead, qTail;
static pthread_mutex_t qLock = PTHREAD_MUTEX_INITIALIZER;
// Counts events posted, so a Java thread can sleep until the next one (nativeWaitInput) and wake Host's idle loop.
static pthread_cond_t qPosted = PTHREAD_COND_INITIALIZER;
static long qSeq;

static void post(int type, float x, float y, float v) {
  pthread_mutex_lock(&qLock);
  int next = (qTail + 1) % QUEUE_SIZE;
  if (next != qHead) {
    queue[qTail] = (Event){type, x, y, v};
    qTail = next;
  }
  qSeq++;
  pthread_cond_broadcast(&qPosted);
  pthread_mutex_unlock(&qLock);
}

// Waits up to timeoutMs for an event posted after `seen` (a value it returned before); returns the current count.
JNIEXPORT jlong JNICALL Java_dev_apksim_SimWindow_nativeWaitInput(JNIEnv *env, jclass c, jlong seen, jint timeoutMs) {
  pthread_mutex_lock(&qLock);
  if (qSeq == seen) {
    struct timespec rel = {timeoutMs / 1000, (long)(timeoutMs % 1000) * 1000000L};
    pthread_cond_timedwait_relative_np(&qPosted, &qLock, &rel);
  }
  long now = qSeq;
  pthread_mutex_unlock(&qLock);
  return now;
}

#define SURFACES 3
static IOSurfaceRef surfaces[SURFACES];
static int nextSurface;
static int devW, devH;
static float deviceScale;
static CALayer *frameLayer;
static long commits;
// A skin (Skinning.java): the frame drawn around the screen. Sizes in device pixels; the screen is devW x devH at
// skinScreen in a frame of skinOuterW x skinOuterH. The window's content is then the frame, the screen view inside it.
static BOOL skinOn;
static int skinOuterW, skinOuterH, skinScreenX, skinScreenY, skinRadius, skinHole[4];
static CGImageRef skinFrame, skinMask;
static NSView *skinView;
static CALayer *skinFrameLayer, *skinMaskLayer, *skinHoleLayer, *skinHoleMaskLayer;
static NSView *simView;
static int outerW(void) { return skinOn ? skinOuterW : devW; }
static int outerH(void) { return skinOn ? skinOuterH : devH; }
// The display's vsync, from a CADisplayLink on the window's view. Host starts each frame just after one, as Android's
// Choreographer does, and it counts what reached the screen: a vsync without a new frame while frames are flowing is
// a frame the screen showed twice, and two frames before one vsync means the first was never seen.
static CADisplayLink *vsyncLink;
static double vsyncTarget, vsyncPeriod, vsyncSeen, lastFrameVsync;  // seconds, CACurrentMediaTime's clock
static long vsyncCommits;
static long vsShown, vsRepeated, vsReplaced, vsPendingRepeats;
static volatile int closeHides;  // SimWindow.closeHides: the close button hides the window (a viewer is attached)
static AVAssetWriter *recWriter;  // screen recording, below
static void recordFrame(IOSurfaceRef s);

static int pasteClipboard(void) {
  NSString *text = [[NSPasteboard generalPasteboard] stringForType:NSPasteboardTypeString];
  int n = 0;
  for (NSUInteger i = 0; i < text.length; i++) {
    unichar ch = [text characterAtIndex:i];
    if (ch == '\n' || ch == '\r') continue;  // A copied password often carries a trailing newline.
    post(EV_CHAR, 0, 1, (float)ch);  // y 1: pasted (SimWindow.PASTED), which the flight recorder leaves out
    n++;
  }
  return n;
}

// ---------------------------------------------------------------- menu bar

// Menu items post EV_COMMAND with their tag; the Java side (DeviceControls.menuCommand) maps tags to control
// commands. Tags must match that table. Text an item asked for (Open URL) waits in pendingText.
enum {
  TAG_SCREENSHOT = 1, TAG_RECORD, TAG_SCREENSHOTS_FOLDER, TAG_HOME, TAG_LOCK, TAG_ROTATE_LEFT, TAG_ROTATE_RIGHT,
  TAG_APPEARANCE, TAG_TEXT_LARGER, TAG_TEXT_SMALLER, TAG_TEXT_DEFAULT, TAG_SLOW_ANIMATIONS, TAG_MEMORY, TAG_OPEN_URL,
  TAG_VOLUME_UP, TAG_VOLUME_DOWN, TAG_BACK, TAG_LOCALE, TAG_NOTIFICATION, TAG_LOCATION_CUSTOM, TAG_LOCATION_RUN,
  TAG_LOCATION_BIKE, TAG_LOCATION_DRIVE, TAG_LOCATION_NONE, TAG_BIOMETRICS_ENROLLED, TAG_BIOMETRICS_MATCH,
  TAG_BIOMETRICS_NOMATCH, TAG_LAYOUT_BOUNDS, TAG_A11Y_AUDIT, TAG_SOFT_KEYBOARD, TAG_DEVICE_PHONE, TAG_DEVICE_FOLDABLE,
  TAG_DEVICE_TABLET, TAG_DEVICE_DESKTOP, TAG_DEVICE_TV, TAG_NETWORK_ONLINE, TAG_NETWORK_OFFLINE, TAG_NETWORK_3G,
  TAG_NETWORK_LOSSY, TAG_STATUS_BAR, TAG_NAV_BAR, TAG_SAVE_REPLAY, TAG_CAMERA_OFF, TAG_CAMERA_PATTERN, TAG_CAMERA_IMAGE,
  TAG_SCREENSHOT_SKIN,
  // Device > Skin: None, then one item per stored skin (SimWindow.TAG_SKIN_FIRST).
  TAG_SKIN_FIRST = 200,
};
static NSMenu *skinMenu;
static BOOL keyboardHit(NSPoint p);  // the software keyboard, below
static void buildKeyboard(void);
static CALayer *bannerLayer;  // heads-up notification, below
static NSString *pendingText;
static NSMutableDictionary<NSNumber *, NSMenuItem *> *menuItems;
static BOOL selfResizing;  // a resize this file asked for (zoom, nativeResize), not the user's
static float openScale;  // device pixels per point at actual size: the display's density
static NSWindow *simWindow;
static int launchMode;  // --window, applied once NSApp has finished launching
static NSRunningApplication *launchedOver;  // the app that was frontmost when this one started

// How the window shows itself (--window, `window` command). FRONT is a normal app: shown, focused. The others never
// take focus from the app the user is in: NOFOCUS shows the window on top, BACKGROUND behind every other window,
// MINIMIZED in the Dock, HIDDEN not at all. The app runs the same in all of them (frames go to the window's layer;
// with no vsync from a hidden window Host paces on its 120 Hz grid). A click on the Dock icon shows it, focused.
enum { WIN_FRONT = 0, WIN_NOFOCUS, WIN_BACKGROUND, WIN_MINIMIZED, WIN_HIDDEN };
static BOOL hideWhenDeminiaturized, minimizeWhenUnhidden;
static void applyWindowMode(int mode) {
  hideWhenDeminiaturized = minimizeWhenUnhidden = NO;
  NSWindow *w = simWindow;
  if (!w) return;
  // After Hide (Cmd+H) the whole app is hidden, its minimized windows' Dock tiles too. Unhiding finishes
  // asynchronously, and a window minimized meanwhile comes back visible: minimize it once the app is back
  // (applicationDidUnhide).
  if (mode != WIN_HIDDEN && NSApp.hidden) {
    [NSApp unhideWithoutActivation];
    if (mode == WIN_MINIMIZED) {
      minimizeWhenUnhidden = YES;
      return;
    }
  }
  if (mode != WIN_HIDDEN && mode != WIN_MINIMIZED && w.miniaturized) [w deminiaturize:nil];
  switch (mode) {
    case WIN_FRONT:
      [w makeKeyAndOrderFront:nil];
      [NSApp activateIgnoringOtherApps:YES];
      break;
    case WIN_NOFOCUS: [w orderFrontRegardless]; break;
    case WIN_BACKGROUND: [w orderBack:nil]; break;
    case WIN_MINIMIZED:
      if (!w.visible && !w.miniaturized) [w orderBack:nil];  // a window must be on screen to go to the Dock
      [w miniaturize:nil];
      break;
    case WIN_HIDDEN:
      // orderOut leaves a minimized window's Dock tile: bring it back first, and hide it once it is back
      // (windowDidDeminiaturize; deminiaturizing finishes asynchronously).
      if (w.miniaturized) {
        hideWhenDeminiaturized = YES;
        [w deminiaturize:nil];
      } else {
        [w orderOut:nil];
      }
      break;
  }
}

@interface MenuTarget : NSObject <NSApplicationDelegate>
@end
static void setZoom(int how);
@implementation MenuTarget
- (void)fire:(NSMenuItem *)item { post(EV_COMMAND, (float)item.tag, 0, 0); }
- (void)zoom:(NSMenuItem *)item { setZoom((int)item.tag); }
// Quit as closing the window does, so the app's main thread exits on its own path (it saves caches first).
- (void)quit:(id)sender { post(EV_CLOSE, 0, 0, 0); }
// The window server activates a newly launched regular app (one with a Dock icon) just after it finishes launching,
// whether or not it asks. So in a mode that must not take focus the app starts as an accessory app, which is not
// activated, and becomes a regular one here; should it be active anyway, it hands activation back.
- (void)applicationDidFinishLaunching:(NSNotification *)n {
  if (launchMode != WIN_FRONT) [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
  applyWindowMode(launchMode);
  if (launchMode != WIN_FRONT && NSApp.active && launchedOver && !launchedOver.terminated) {
    if (@available(macOS 14.0, *)) [NSApp yieldActivationToApplication:launchedOver];
    [launchedOver activateWithOptions:0];
  }
}
- (void)applicationDidUnhide:(NSNotification *)n {
  if (minimizeWhenUnhidden) applyWindowMode(WIN_MINIMIZED);
}
// A click on the Dock icon brings back a window that was hidden or minimized (--window hidden|minimized).
- (BOOL)applicationShouldHandleReopen:(NSApplication *)app hasVisibleWindows:(BOOL)visible {
  if (!simWindow.visible || simWindow.miniaturized) applyWindowMode(WIN_FRONT);
  return NO;
}
- (void)ask:(NSMenuItem *)item {
  NSAlert *a = [NSAlert new];
  BOOL url = item.tag == TAG_OPEN_URL, loc = item.tag == TAG_LOCATION_CUSTOM;
  a.messageText = url ? @"Open URL" : loc ? @"Custom Location" : @"Language and Region";
  a.informativeText = url ? @"Delivered to the app as a link tapped elsewhere would be."
                    : loc ? @"Latitude and longitude, in degrees." : @"A BCP 47 tag, such as fr-FR, ja or ar-EG.";
  NSTextField *f = [[NSTextField alloc] initWithFrame:NSMakeRect(0, 0, 320, 24)];
  f.placeholderString = url ? @"https://example.com/path  or  myapp://screen" : loc ? @"37.3349, -122.0090" : @"en-US";
  a.accessoryView = f;
  [a addButtonWithTitle:url ? @"Open" : loc ? @"Set" : @"Change"];
  [a addButtonWithTitle:@"Cancel"];
  a.window.initialFirstResponder = f;
  if ([a runModal] == NSAlertFirstButtonReturn && f.stringValue.length) {
    @synchronized([MenuTarget class]) { pendingText = [f.stringValue copy]; }
    post(EV_COMMAND, (float)item.tag, 0, 0);
  }
}
@end
static MenuTarget *menuTarget;

static NSMenuItem *addItem(NSMenu *m, NSString *title, int tag, NSString *key, NSEventModifierFlags mods) {
  BOOL asks = tag == TAG_OPEN_URL || tag == TAG_LOCALE || tag == TAG_LOCATION_CUSTOM;
  NSMenuItem *i = [[NSMenuItem alloc] initWithTitle:title action:asks ? @selector(ask:) : @selector(fire:) keyEquivalent:key ?: @""];
  i.keyEquivalentModifierMask = mods;
  i.target = menuTarget;
  i.tag = tag;
  [m addItem:i];
  menuItems[@(tag)] = i;
  return i;
}

static NSMenu *submenu(NSMenu *bar, NSString *title) {
  NSMenuItem *top = [[NSMenuItem alloc] init];
  NSMenu *m = [[NSMenu alloc] initWithTitle:title];
  m.autoenablesItems = NO;
  top.submenu = m;
  [bar addItem:top];
  return m;
}

// The iOS Simulator's menus and shortcuts, where Android has the same thing.
static void buildMenus(NSMenu *bar) {
  menuTarget = [MenuTarget new];
  menuItems = [NSMutableDictionary dictionary];
  NSEventModifierFlags cmd = NSEventModifierFlagCommand, shift = NSEventModifierFlagShift, opt = NSEventModifierFlagOption;
  NSString *left = [NSString stringWithFormat:@"%C", (unichar)NSLeftArrowFunctionKey];
  NSString *right = [NSString stringWithFormat:@"%C", (unichar)NSRightArrowFunctionKey];
  NSMenu *file = submenu(bar, @"File");
  addItem(file, @"Save Screen", TAG_SCREENSHOT, @"s", cmd);
  addItem(file, @"Save Screen with Skin", TAG_SCREENSHOT_SKIN, @"s", cmd | opt);
  addItem(file, @"Record Screen", TAG_RECORD, @"r", cmd);
  addItem(file, @"Save Replay", TAG_SAVE_REPLAY, @"s", cmd | shift);
  addItem(file, @"Show Screenshots and Recordings", TAG_SCREENSHOTS_FOLDER, nil, 0);
  NSMenu *device = submenu(bar, @"Device");
  // The display's shape, as Android Studio's device picker; dragging the window's edges resizes it freely.
  NSMenuItem *typeItem = [[NSMenuItem alloc] initWithTitle:@"Device Type" action:nil keyEquivalent:@""];
  NSMenu *type = [[NSMenu alloc] initWithTitle:@"Device Type"];
  type.autoenablesItems = NO;
  addItem(type, @"Phone", TAG_DEVICE_PHONE, @"1", cmd | shift);
  addItem(type, @"Foldable", TAG_DEVICE_FOLDABLE, @"2", cmd | shift);
  addItem(type, @"Tablet", TAG_DEVICE_TABLET, @"3", cmd | shift);
  addItem(type, @"Desktop", TAG_DEVICE_DESKTOP, @"4", cmd | shift);
  addItem(type, @"TV", TAG_DEVICE_TV, @"5", cmd | shift);
  typeItem.submenu = type;
  [device addItem:typeItem];
  // Stored skins (apksim skin list), filled in by the Java side (nativeSetSkinMenu).
  NSMenuItem *skinItem = [[NSMenuItem alloc] initWithTitle:@"Skin" action:nil keyEquivalent:@""];
  skinMenu = [[NSMenu alloc] initWithTitle:@"Skin"];
  skinMenu.autoenablesItems = NO;
  addItem(skinMenu, @"None", TAG_SKIN_FIRST, nil, 0);
  skinItem.submenu = skinMenu;
  [device addItem:skinItem];
  [device addItem:[NSMenuItem separatorItem]];
  addItem(device, @"Rotate Left", TAG_ROTATE_LEFT, left, cmd);
  addItem(device, @"Rotate Right", TAG_ROTATE_RIGHT, right, cmd);
  [device addItem:[NSMenuItem separatorItem]];
  addItem(device, @"Back", TAG_BACK, nil, 0);
  addItem(device, @"Home", TAG_HOME, @"h", cmd | shift);
  addItem(device, @"Lock", TAG_LOCK, @"l", cmd);
  addItem(device, @"Volume Up", TAG_VOLUME_UP, @"=", cmd | opt);
  addItem(device, @"Volume Down", TAG_VOLUME_DOWN, @"-", cmd | opt);
  [device addItem:[NSMenuItem separatorItem]];
  addItem(device, @"Software Keyboard", TAG_SOFT_KEYBOARD, @"k", cmd);
  addItem(device, @"Status Bar", TAG_STATUS_BAR, nil, 0);
  addItem(device, @"Navigation Bar", TAG_NAV_BAR, nil, 0);
  NSMenu *features = submenu(bar, @"Features");
  addItem(features, @"Dark Appearance", TAG_APPEARANCE, @"a", cmd | shift);
  addItem(features, @"Larger Text", TAG_TEXT_LARGER, @"=", cmd | shift);
  addItem(features, @"Smaller Text", TAG_TEXT_SMALLER, @"-", cmd | shift);
  addItem(features, @"Default Text Size", TAG_TEXT_DEFAULT, @"0", cmd | shift);
  addItem(features, @"Language and Region…", TAG_LOCALE, nil, 0);
  NSMenuItem *locationItem = [[NSMenuItem alloc] initWithTitle:@"Location" action:nil keyEquivalent:@""];
  NSMenu *location = [[NSMenu alloc] initWithTitle:@"Location"];
  location.autoenablesItems = NO;
  addItem(location, @"None", TAG_LOCATION_NONE, nil, 0);
  addItem(location, @"Custom Location…", TAG_LOCATION_CUSTOM, nil, 0);
  addItem(location, @"City Run", TAG_LOCATION_RUN, nil, 0);
  addItem(location, @"City Bicycle Ride", TAG_LOCATION_BIKE, nil, 0);
  addItem(location, @"Freeway Drive", TAG_LOCATION_DRIVE, nil, 0);
  locationItem.submenu = location;
  [features addItem:locationItem];
  NSMenuItem *bioItem = [[NSMenuItem alloc] initWithTitle:@"Fingerprint" action:nil keyEquivalent:@""];
  NSMenu *bio = [[NSMenu alloc] initWithTitle:@"Fingerprint"];
  bio.autoenablesItems = NO;
  addItem(bio, @"Enrolled", TAG_BIOMETRICS_ENROLLED, nil, 0);
  addItem(bio, @"Matching Finger", TAG_BIOMETRICS_MATCH, @"m", cmd | opt);
  addItem(bio, @"Non-matching Finger", TAG_BIOMETRICS_NOMATCH, @"n", cmd | opt);
  bioItem.submenu = bio;
  [features addItem:bioItem];
  NSMenuItem *netItem = [[NSMenuItem alloc] initWithTitle:@"Network" action:nil keyEquivalent:@""];
  NSMenu *net = [[NSMenu alloc] initWithTitle:@"Network"];
  net.autoenablesItems = NO;
  addItem(net, @"Online", TAG_NETWORK_ONLINE, nil, 0);
  addItem(net, @"Offline", TAG_NETWORK_OFFLINE, nil, 0);
  addItem(net, @"3G", TAG_NETWORK_3G, nil, 0);
  addItem(net, @"Lossy", TAG_NETWORK_LOSSY, nil, 0);
  netItem.submenu = net;
  [features addItem:netItem];
  // The fake camera's source (--camera fake; Cameras.java). Image File... opens the file panel from the Java side.
  NSMenuItem *camItem = [[NSMenuItem alloc] initWithTitle:@"Camera" action:nil keyEquivalent:@""];
  NSMenu *cam = [[NSMenu alloc] initWithTitle:@"Camera"];
  cam.autoenablesItems = NO;
  addItem(cam, @"Off", TAG_CAMERA_OFF, nil, 0);
  addItem(cam, @"Test Pattern", TAG_CAMERA_PATTERN, nil, 0);
  addItem(cam, @"Image File…", TAG_CAMERA_IMAGE, nil, 0);
  camItem.submenu = cam;
  [features addItem:camItem];
  [features addItem:[NSMenuItem separatorItem]];
  addItem(features, @"Open URL…", TAG_OPEN_URL, @"o", cmd | shift);
  NSMenu *window = submenu(bar, @"Window");
  NSMenuItem *mini = [[NSMenuItem alloc] initWithTitle:@"Minimize" action:@selector(performMiniaturize:) keyEquivalent:@"m"];
  mini.keyEquivalentModifierMask = cmd;
  [window addItem:mini];
  [window addItem:[NSMenuItem separatorItem]];
  NSString *zooms[] = {@"Actual Size", @"Fit Screen", @"Zoom In", @"Zoom Out"};
  NSString *keys[] = {@"1", @"3", @"=", @"-"};
  for (int i = 0; i < 4; i++) {
    NSMenuItem *z = [[NSMenuItem alloc] initWithTitle:zooms[i] action:@selector(zoom:) keyEquivalent:keys[i]];
    z.keyEquivalentModifierMask = cmd;
    z.target = menuTarget;
    z.tag = i;
    [window addItem:z];
  }
  NSMenu *debug = submenu(bar, @"Debug");
  addItem(debug, @"Slow Animations", TAG_SLOW_ANIMATIONS, @"t", cmd);
  addItem(debug, @"Simulate Memory Warning", TAG_MEMORY, @"m", cmd | shift);
  [debug addItem:[NSMenuItem separatorItem]];
  addItem(debug, @"Show Layout Bounds", TAG_LAYOUT_BOUNDS, nil, 0);
  addItem(debug, @"Accessibility Audit", TAG_A11Y_AUDIT, nil, 0);
}

@interface SimView : NSView <NSWindowDelegate>
@end

@implementation SimView
- (void)vsync:(CADisplayLink *)link {
  long n = commits - vsyncCommits;
  vsyncCommits = commits;
  @synchronized([SimView class]) {
    vsyncSeen = link.timestamp;
    vsyncTarget = link.targetTimestamp;
    vsyncPeriod = link.targetTimestamp - link.timestamp;
    if (n > 0) {
      vsShown++;
      vsReplaced += n - 1;
      vsRepeated += vsPendingRepeats;  // the gap was inside an animation, not the end of one
      vsPendingRepeats = 0;
      lastFrameVsync = link.timestamp;
    } else if (link.timestamp - lastFrameVsync < 0.05) {
      vsPendingRepeats++;
    } else {
      vsPendingRepeats = 0;
    }
  }
}
- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)acceptsFirstMouse:(NSEvent *)e { return YES; }
- (BOOL)isFlipped { return YES; }
- (void)touch:(int)type event:(NSEvent *)e {
  NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
  post(type, p.x * deviceScale, p.y * deviceScale, 0);
}
// Two fingers, as in the iOS Simulator: Option-drag pinches and rotates around the screen's centre (the second finger
// mirrors the pointer), Option-Shift-drag moves both fingers together. Grey dots show where the fingers are.
static BOOL twoFingers;
static CALayer *fingerLayers[2];
static NSPoint fingerCentre;
static NSPoint fingerOffset;
static BOOL fingersParallel;

- (void)showFingers:(NSPoint)p {
  if (!fingerLayers[0]) {
    for (int i = 0; i < 2; i++) {
      fingerLayers[i] = [CALayer layer];
      fingerLayers[i].bounds = CGRectMake(0, 0, 22, 22);
      fingerLayers[i].cornerRadius = 11;
      fingerLayers[i].backgroundColor = [NSColor colorWithWhite:0.5 alpha:0.55].CGColor;
      fingerLayers[i].borderColor = [NSColor colorWithWhite:1 alpha:0.8].CGColor;
      fingerLayers[i].borderWidth = 1;
      fingerLayers[i].zPosition = 20;
      [frameLayer addSublayer:fingerLayers[i]];
    }
  }
  NSPoint q = fingersParallel ? NSMakePoint(p.x + fingerOffset.x, p.y + fingerOffset.y)
                              : NSMakePoint(2 * fingerCentre.x - p.x, 2 * fingerCentre.y - p.y);
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  fingerLayers[0].position = p;
  fingerLayers[1].position = q;
  fingerLayers[0].hidden = fingerLayers[1].hidden = NO;
  [CATransaction commit];
}
- (void)hideFingers {
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  fingerLayers[0].hidden = fingerLayers[1].hidden = YES;
  [CATransaction commit];
}
- (void)mouseDown:(NSEvent *)e {
  NSPoint at = [self convertPoint:e.locationInWindow fromView:nil];
  if (keyboardHit(at)) return;  // a key on the software keyboard: typed, not touched
  if (bannerLayer && !bannerLayer.hidden && CGRectContainsPoint(bannerLayer.frame, at)) {
    bannerLayer.hidden = YES;
    post(EV_COMMAND, TAG_NOTIFICATION, 0, 0);  // opens it, as tapping a notification does
    return;
  }
  twoFingers = (e.modifierFlags & NSEventModifierFlagOption) != 0;
  if (!twoFingers) {
    [self touch:EV_DOWN event:e];
    return;
  }
  NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
  fingersParallel = (e.modifierFlags & NSEventModifierFlagShift) != 0;
  fingerCentre = NSMakePoint(NSMidX(self.bounds), NSMidY(self.bounds));
  fingerOffset = NSMakePoint(2 * (fingerCentre.x - p.x), 2 * (fingerCentre.y - p.y));
  post(EV_TOUCH2_CENTRE, fingerCentre.x * deviceScale, fingerCentre.y * deviceScale, 0);
  post(EV_TOUCH2_DOWN, p.x * deviceScale, p.y * deviceScale, fingersParallel ? 1 : 0);
  [self showFingers:p];
}
- (void)mouseDragged:(NSEvent *)e {
  if (!twoFingers) {
    [self touch:EV_MOVE event:e];
    return;
  }
  NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
  post(EV_TOUCH2_MOVE, p.x * deviceScale, p.y * deviceScale, 0);
  [self showFingers:p];
}
- (void)mouseUp:(NSEvent *)e {
  if (!twoFingers) {
    [self touch:EV_UP event:e];
    return;
  }
  NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
  post(EV_TOUCH2_UP, p.x * deviceScale, p.y * deviceScale, 0);
  [self hideFingers];
  twoFingers = NO;
}

// Trackpad pinch and rotate become two fingers around the pointer: 160 pt apart to start, then apart by the
// gesture's magnification and turned by its rotation.
static CGFloat gestureDistance, gestureAngle;
static BOOL gestureActive;
- (void)trackpadGesture:(NSEvent *)e magnify:(CGFloat)m rotate:(CGFloat)degrees {
  NSPoint c = gestureActive ? fingerCentre : [self convertPoint:e.locationInWindow fromView:nil];
  if (e.phase == NSEventPhaseBegan || !gestureActive) {
    gestureActive = YES;
    fingersParallel = NO;
    fingerCentre = c;
    gestureDistance = 160;
    gestureAngle = 0;
    post(EV_TOUCH2_CENTRE, c.x * deviceScale, c.y * deviceScale, 0);
    post(EV_TOUCH2_DOWN, (c.x - gestureDistance / 2) * deviceScale, c.y * deviceScale, 0);
  }
  gestureDistance = MAX(20, gestureDistance * (1 + m));
  gestureAngle -= degrees * M_PI / 180;  // the view is flipped: clockwise on the trackpad is clockwise on screen
  NSPoint p = NSMakePoint(c.x - cos(gestureAngle) * gestureDistance / 2, c.y - sin(gestureAngle) * gestureDistance / 2);
  BOOL end = e.phase == NSEventPhaseEnded || e.phase == NSEventPhaseCancelled;
  post(end ? EV_TOUCH2_UP : EV_TOUCH2_MOVE, p.x * deviceScale, p.y * deviceScale, 0);
  if (end) {
    gestureActive = NO;
    [self hideFingers];
  } else {
    [self showFingers:p];
  }
}
- (void)magnifyWithEvent:(NSEvent *)e { [self trackpadGesture:e magnify:e.magnification rotate:0]; }
- (void)rotateWithEvent:(NSEvent *)e { [self trackpadGesture:e magnify:0 rotate:e.rotation]; }
- (void)scrollWheel:(NSEvent *)e {
  NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
  float dy = e.hasPreciseScrollingDeltas ? e.scrollingDeltaY / 24.0f : e.scrollingDeltaY;
  if (dy != 0) post(EV_WHEEL, p.x * deviceScale, p.y * deviceScale, dy);
}
// Cmd+V types the clipboard's text into the app, as if typed: Android apps here have no clipboard of
// their own to paste from. Other Cmd shortcuts belong to the Mac, not the app.
- (BOOL)performKeyEquivalent:(NSEvent *)e {
  if (!(e.modifierFlags & NSEventModifierFlagCommand)) return [super performKeyEquivalent:e];
  if ([e.charactersIgnoringModifiers isEqualToString:@"v"]) {
    pasteClipboard();
    return YES;
  }
  return [super performKeyEquivalent:e];
}
// A Mac keyboard as an Android hardware keyboard. Keys go as key events with their modifiers (EV_KEY's x is the
// Android meta state): Control shortcuts (Ctrl+A, Ctrl+Z), Shift-selection, and Option+arrow, which moves by word on
// a Mac and is Ctrl+arrow on Android. Typing goes as characters. Command belongs to the Mac's menus.
static int androidMeta(NSEventModifierFlags f) {
  int m = 0;
  if (f & NSEventModifierFlagShift) m |= 0x1;      // META_SHIFT_ON
  if (f & NSEventModifierFlagOption) m |= 0x2;     // META_ALT_ON
  if (f & NSEventModifierFlagControl) m |= 0x1000; // META_CTRL_ON
  return m;
}
- (void)keyDown:(NSEvent *)e {
  if (e.modifierFlags & NSEventModifierFlagCommand) return;
  int meta = androidMeta(e.modifierFlags);
  int code = 0;
  switch (e.keyCode) {
    case 53: post(EV_BACK, 0, 0, 0); return;  // Escape acts as Back.
    case 51: code = 67; break;                // Delete -> KEYCODE_DEL
    case 117: code = 112; break;              // Forward delete -> KEYCODE_FORWARD_DEL
    case 36: case 76: code = 66; break;       // Return, keypad Enter -> KEYCODE_ENTER
    case 48: code = 61; break;                // Tab
    case 115: code = 122; break;              // Home -> KEYCODE_MOVE_HOME
    case 119: code = 123; break;              // End -> KEYCODE_MOVE_END
    case 116: code = 92; break;               // Page Up
    case 121: code = 93; break;               // Page Down
    case 123: code = 21; break;               // Arrows -> DPAD
    case 124: code = 22; break;
    case 125: code = 20; break;
    case 126: code = 19; break;
  }
  if (code >= 19 && code <= 22 && (meta & 0x2)) meta = (meta & ~0x2) | 0x1000;  // Option+arrow: by word
  if (!code && (meta & 0x1000)) {
    // Control with a letter or digit: a shortcut, not a control character.
    NSString *k = e.charactersIgnoringModifiers.lowercaseString;
    unichar ch = k.length ? [k characterAtIndex:0] : 0;
    if (ch >= 'a' && ch <= 'z') code = 29 + (ch - 'a');  // KEYCODE_A..Z
    if (ch >= '0' && ch <= '9') code = 7 + (ch - '0');   // KEYCODE_0..9
  }
  if (code) {
    post(EV_KEY, (float)meta, 0, (float)code);
    return;
  }
  NSString *s = e.characters;
  for (NSUInteger i = 0; i < s.length; i++) post(EV_CHAR, 0, 0, (float)[s characterAtIndex:i]);
}
// While a viewer elsewhere is attached (a browser tab streaming this instance: apksim beam's "Show in Simulator"), the
// close button hides the window and the app runs on in the tab; Quit (Cmd+Q) still ends it.
- (BOOL)windowShouldClose:(NSWindow *)w {
  if (closeHides) {
    [w orderOut:nil];
    return NO;
  }
  return YES;
}
- (void)windowWillClose:(NSNotification *)n { post(EV_CLOSE, 0, 0, 0); }
- (void)windowDidDeminiaturize:(NSNotification *)n {
  if (hideWhenDeminiaturized) [self.window orderOut:nil];
  hideWhenDeminiaturized = NO;
}
// Dragging the window's edges (or full screen) resizes the device's display, as a resizable emulator does: the new
// size goes to the Java side in device pixels, which reconfigures the app and calls nativeResize. While dragging,
// the last frame stays pinned top left at its own scale rather than stretching.
- (void)postResize:(BOOL)done {
  if (selfResizing || skinOn) return;  // a skinned window is not resizable: its size is the frame's
  NSSize s = self.window.contentView.bounds.size;
  post(EV_RESIZE, (float)(s.width * deviceScale), (float)(s.height * deviceScale), done ? 1 : 0);
}
- (void)windowWillStartLiveResize:(NSNotification *)n {
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  frameLayer.contentsScale = deviceScale;
  // Gravity is in the layer's own coordinates, and this one is top-down (the view is flipped): visually top left.
  frameLayer.contentsGravity = frameLayer.contentsAreFlipped ? kCAGravityBottomLeft : kCAGravityTopLeft;
  [CATransaction commit];
}
- (void)windowDidResize:(NSNotification *)n { [self postResize:!self.window.inLiveResize]; }
- (void)windowDidEndLiveResize:(NSNotification *)n { [self postResize:YES]; }
@end

// Not the GCD main queue: [NSApp run] never returns, and that queue is serial, so anything queued
// behind it would wait forever. Blocks on the run loop run from inside NSApp's own event loop.
static void onMain(dispatch_block_t block) {
  CFRunLoopRef main = CFRunLoopGetMain();
  CFRunLoopPerformBlock(main, kCFRunLoopCommonModes, block);
  CFRunLoopWakeUp(main);
}

static void openWindow(int w, int h, float scale, NSString *title, int xOffset, int mode) {
  [NSApplication sharedApplication];
  launchMode = mode;
  launchedOver = NSWorkspace.sharedWorkspace.frontmostApplication;
  [NSApp setActivationPolicy:mode == WIN_FRONT ? NSApplicationActivationPolicyRegular : NSApplicationActivationPolicyAccessory];
  // No App Nap: macOS throttles the timers of an app whose windows are hidden or covered, and the app's frame loop
  // and background work run on timers. Idle system sleep is still allowed.
  static id activity;
  activity = [NSProcessInfo.processInfo beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep
                                                          reason:@"Android app running"];
  NSMenu *menu = [[NSMenu alloc] init];
  NSMenuItem *appItem = [[NSMenuItem alloc] init];
  [menu addItem:appItem];
  NSMenu *appMenu = [[NSMenu alloc] init];
  [appMenu addItemWithTitle:@"Hide apksim" action:@selector(hide:) keyEquivalent:@"h"];
  NSMenuItem *others = [appMenu addItemWithTitle:@"Hide Others" action:@selector(hideOtherApplications:) keyEquivalent:@"h"];
  others.keyEquivalentModifierMask = NSEventModifierFlagCommand | NSEventModifierFlagOption;
  [appMenu addItemWithTitle:@"Show All" action:@selector(unhideAllApplications:) keyEquivalent:@""];
  [appMenu addItem:[NSMenuItem separatorItem]];
  NSMenuItem *quit = [appMenu addItemWithTitle:@"Quit" action:@selector(quit:) keyEquivalent:@"q"];
  appItem.submenu = appMenu;
  buildMenus(menu);
  quit.target = menuTarget;
  NSApp.mainMenu = menu;
  NSApp.delegate = menuTarget;

  NSRect frame = NSMakeRect(80 + xOffset, 80, w / scale, h / scale);
  NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                 styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable |
                                                           NSWindowStyleMaskResizable
                                                   backing:NSBackingStoreBuffered
                                                     defer:NO];
  window.title = title;
  window.releasedWhenClosed = NO;
  window.contentMinSize = NSMakeSize(160, 160);
  // A display bigger than the Mac's screen (desktop, TV) opens zoomed out to fit, as Fit Screen would.
  NSRect visible = (window.screen ?: NSScreen.mainScreen).visibleFrame;
  CGFloat titleBar = window.frame.size.height - frame.size.height;
  float fit = MAX(h / (visible.size.height - titleBar), w / visible.size.width);
  if (visible.size.width > 0 && fit > scale) {
    deviceScale = scale = fit;
    selfResizing = YES;
    [window setContentSize:NSMakeSize(w / scale, h / scale)];
    selfResizing = NO;
    frame.size = NSMakeSize(w / scale, h / scale);
  }
  SimView *view = [[SimView alloc] initWithFrame:NSMakeRect(0, 0, w / scale, h / scale)];
  view.wantsLayer = YES;
  frameLayer = [CALayer layer];
  frameLayer.frame = view.bounds;
  frameLayer.contentsGravity = kCAGravityResize;
  frameLayer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
  frameLayer.opaque = YES;
  frameLayer.geometryFlipped = NO;
  view.layer = frameLayer;
  window.contentView = view;
  window.delegate = view;
  simView = view;
  vsyncLink = [view displayLinkWithTarget:view selector:@selector(vsync:)];
  [vsyncLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
  [window makeFirstResponder:view];
  simWindow = window;
  // Shown now in front mode, as before; the others wait for applicationDidFinishLaunching (above).
  if (mode == WIN_FRONT) applyWindowMode(mode);
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeOpen(JNIEnv *env, jclass c, jint w, jint h, jfloat scale, jstring title, jint xOffset, jint mode) {
  devW = w;
  devH = h;
  deviceScale = openScale = scale;
  for (int i = 0; i < SURFACES; i++) {
    NSDictionary *props = @{
      (id)kIOSurfaceWidth : @(w),
      (id)kIOSurfaceHeight : @(h),
      (id)kIOSurfaceBytesPerElement : @4,
      (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
    };
    surfaces[i] = IOSurfaceCreate((__bridge CFDictionaryRef)props);
  }
  const char *t = (*env)->GetStringUTFChars(env, title, NULL);
  NSString *ns = [NSString stringWithUTF8String:t];
  (*env)->ReleaseStringUTFChars(env, title, t);
  onMain(^{
    openWindow(w, h, scale, ns, xOffset, mode);
    [NSApp run];
  });
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativePresent(JNIEnv *env, jclass c, jobject buffer) {
  uint8_t *src = (*env)->GetDirectBufferAddress(env, buffer);
  IOSurfaceRef s = surfaces[nextSurface];
  nextSurface = (nextSurface + 1) % SURFACES;
  IOSurfaceLock(s, 0, NULL);
  // Android bitmaps here are already BGRA: Skia's native 32-bit format on this platform.
  uint8_t *dst = IOSurfaceGetBaseAddress(s);
  size_t stride = IOSurfaceGetBytesPerRow(s);
  size_t row = devW * 4;
  if (stride == row) {
    memcpy(dst, src, row * devH);
  } else {
    for (int y = 0; y < devH; y++) memcpy(dst + y * stride, src + y * row, row);
  }
  IOSurfaceUnlock(s, 0, NULL);
  if (recWriter) recordFrame(s);
  onMain(^{
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    frameLayer.contents = (__bridge id)s;
    [CATransaction commit];
    [CATransaction flush];
    commits++;
  });
}

// ---------------------------------------------------------------- screen recording

// Frames are copied out of the IOSurface (it is reused three frames later) into the writer's pool and stamped with
// the time they were shown, so the movie keeps real timing even though frames arrive only when something changes.
static AVAssetWriterInputPixelBufferAdaptor *recAdaptor;
static CMTime recLast;
static uint64_t recStartNanos, recLastNanos;
static pthread_mutex_t recLock = PTHREAD_MUTEX_INITIALIZER;

static void recordFrame(IOSurfaceRef s) {
  pthread_mutex_lock(&recLock);
  if (recWriter && recAdaptor.assetWriterInput.readyForMoreMediaData) {
    uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    if (!recStartNanos) recStartNanos = now;
    if (now - recLastNanos >= 16000000ULL) {  // at most 60 fps: enough to watch, and cheap
      CVPixelBufferRef pb = NULL;
      if (CVPixelBufferPoolCreatePixelBuffer(NULL, recAdaptor.pixelBufferPool, &pb) == kCVReturnSuccess) {
        CVPixelBufferLockBaseAddress(pb, 0);
        IOSurfaceLock(s, kIOSurfaceLockReadOnly, NULL);
        uint8_t *dst = CVPixelBufferGetBaseAddress(pb);
        const uint8_t *src = IOSurfaceGetBaseAddress(s);
        size_t ds = CVPixelBufferGetBytesPerRow(pb), ss = IOSurfaceGetBytesPerRow(s);
        for (int y = 0; y < devH; y++) memcpy(dst + y * ds, src + y * ss, (size_t)devW * 4);
        IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, NULL);
        CVPixelBufferUnlockBaseAddress(pb, 0);
        recLast = CMTimeMake((int64_t)(now - recStartNanos), 1000000000);
        [recAdaptor appendPixelBuffer:pb withPresentationTime:recLast];
        CVPixelBufferRelease(pb);
        recLastNanos = now;
      }
    }
  }
  pthread_mutex_unlock(&recLock);
}

// Returns NULL when recording started, or why it did not.
JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativeRecordStart(JNIEnv *env, jclass c, jstring jpath) {
  const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
  NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:p]];
  (*env)->ReleaseStringUTFChars(env, jpath, p);
  pthread_mutex_lock(&recLock);
  NSString *err = nil;
  if (recWriter) {
    err = @"already recording";
  } else {
    NSError *e = nil;
    [[NSFileManager defaultManager] removeItemAtURL:url error:nil];
    AVAssetWriter *w = [AVAssetWriter assetWriterWithURL:url fileType:AVFileTypeQuickTimeMovie error:&e];
    NSDictionary *settings = @{AVVideoCodecKey : AVVideoCodecTypeH264, AVVideoWidthKey : @(devW), AVVideoHeightKey : @(devH)};
    AVAssetWriterInput *in = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo outputSettings:settings];
    in.expectsMediaDataInRealTime = YES;
    NSDictionary *attrs = @{(id)kCVPixelBufferPixelFormatTypeKey : @(kCVPixelFormatType_32BGRA), (id)kCVPixelBufferWidthKey : @(devW),
                            (id)kCVPixelBufferHeightKey : @(devH)};
    AVAssetWriterInputPixelBufferAdaptor *ad = [AVAssetWriterInputPixelBufferAdaptor assetWriterInputPixelBufferAdaptorWithAssetWriterInput:in
                                                                                                         sourcePixelBufferAttributes:attrs];
    if (!w || ![w canAddInput:in]) {
      err = e.localizedDescription ?: @"cannot create the movie";
    } else {
      [w addInput:in];
      [w startWriting];
      [w startSessionAtSourceTime:kCMTimeZero];
      recWriter = w;
      recAdaptor = ad;
      recStartNanos = recLastNanos = 0;
      recLast = kCMTimeZero;
    }
  }
  pthread_mutex_unlock(&recLock);
  // The first frame: what is on screen now, so a still screen still records.
  IOSurfaceRef shown = surfaces[(nextSurface + SURFACES - 1) % SURFACES];
  if (!err && shown) recordFrame(shown);
  return err ? (*env)->NewStringUTF(env, err.UTF8String) : NULL;
}

// Finishes the movie; returns its duration in milliseconds, or -1 if nothing was recording.
JNIEXPORT jlong JNICALL Java_dev_apksim_SimWindow_nativeRecordStop(JNIEnv *env, jclass c) {
  pthread_mutex_lock(&recLock);
  AVAssetWriter *w = recWriter;
  recWriter = nil;
  uint64_t start = recStartNanos;
  pthread_mutex_unlock(&recLock);
  if (!w) return -1;
  uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
  CMTime end = CMTimeMake((int64_t)(now - (start ? start : now)), 1000000000);
  [w.inputs.firstObject markAsFinished];
  [w endSessionAtSourceTime:end];
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  [w finishWritingWithCompletionHandler:^{ dispatch_semaphore_signal(done); }];
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  return (jlong)(CMTimeGetSeconds(end) * 1000);
}

// ---------------------------------------------------------------- system bars

// Drawn over the app as on a phone: clock, Wi-Fi and battery in the status bar, and the gesture handle. The app
// already gets their insets; these layers sit above its pixels (window captures show them, `shot` does not). Each bar
// is drawn only while the device has it and the app has not hidden it (SystemBars.java).
static CALayer *barsLayer, *statusLayer, *wifiLayer, *batteryLayer, *handleLayer;
static CATextLayer *clockLayer;
static NSTimer *clockTimer;
static int darkStatusIcons = -1, darkHandle = -1;
static CGFloat barsW, barsH, barsStatus = -1, barsNav = -1;

static CGImageRef symbol(NSString *name, NSColor *color, CGFloat points) {
  NSImage *img = [NSImage imageWithSystemSymbolName:name accessibilityDescription:nil];
  NSImageSymbolConfiguration *cfg = [[NSImageSymbolConfiguration configurationWithPointSize:points weight:NSFontWeightSemibold]
      configurationByApplyingConfiguration:[NSImageSymbolConfiguration configurationWithPaletteColors:@[ color ]]];
  img = [img imageWithSymbolConfiguration:cfg];
  NSRect r = NSMakeRect(0, 0, img.size.width, img.size.height);
  return [img CGImageForProposedRect:&r context:nil hints:@{NSImageHintCTM : [NSAffineTransform transform]}];
}

static NSTimeZone *clockZone;  // the device's (--timezone, `timezone`); nil: the Mac's

static void updateClock(void) {
  NSDateFormatter *f = [NSDateFormatter new];
  f.dateFormat = @"H:mm";
  if (clockZone) f.timeZone = clockZone;
  clockLayer.string = [f stringFromDate:[NSDate date]];
}

static void styleBars(void) {
  NSColor *fg = darkStatusIcons ? [NSColor colorWithWhite:0.1 alpha:1] : [NSColor whiteColor];
  clockLayer.foregroundColor = fg.CGColor;
  wifiLayer.contents = (__bridge id)symbol(@"wifi", fg, 10);
  batteryLayer.contents = (__bridge id)symbol(@"battery.100percent", fg, 11);
  handleLayer.backgroundColor = (darkHandle ? [NSColor colorWithWhite:0 alpha:0.75] : [NSColor colorWithWhite:1 alpha:0.85]).CGColor;
}

// statusPx/navPx: the bars' heights in device pixels (0 = not drawn). Icons dark when the app asked for light bars.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetSystemBars(JNIEnv *env, jclass c, jint statusPx, jint navPx, jboolean darkStatus, jboolean darkNav) {
  onMain(^{
    if (!frameLayer) return;
    CGFloat sh = statusPx / deviceScale, nh = navPx / deviceScale, W = devW / deviceScale, H = devH / deviceScale;
    if (!barsLayer && sh == 0 && nh == 0) return;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (!barsLayer) {
      barsLayer = [CALayer layer];
      barsLayer.geometryFlipped = NO;  // the parent layer is already top-down
      barsLayer.zPosition = 10;
      [frameLayer addSublayer:barsLayer];
      barsStatus = barsNav = -1;  // new layers (first call, or after a zoom rebuilt them): lay them out below
      CGFloat scale = NSApp.windows.firstObject.backingScaleFactor ?: 2;
      statusLayer = [CALayer layer];
      [barsLayer addSublayer:statusLayer];
      clockLayer = [CATextLayer layer];
      clockLayer.font = (__bridge CFTypeRef)[NSFont monospacedDigitSystemFontOfSize:12 weight:NSFontWeightMedium];
      clockLayer.fontSize = 12;
      clockLayer.contentsScale = scale;
      [statusLayer addSublayer:clockLayer];
      batteryLayer = [CALayer layer];
      batteryLayer.contentsGravity = kCAGravityResizeAspect;
      batteryLayer.contentsScale = scale;
      [statusLayer addSublayer:batteryLayer];
      wifiLayer = [CALayer layer];
      wifiLayer.contentsGravity = kCAGravityResizeAspect;
      wifiLayer.contentsScale = scale;
      [statusLayer addSublayer:wifiLayer];
      updateClock();
      clockTimer = [NSTimer scheduledTimerWithTimeInterval:5 repeats:YES block:^(NSTimer *t) { updateClock(); }];
      handleLayer = [CALayer layer];
      handleLayer.cornerRadius = 2;
      [barsLayer addSublayer:handleLayer];
    }
    // Laid out again when a bar comes or goes or the display changes size (rotation, another device, resizing).
    if (sh != barsStatus || nh != barsNav || W != barsW || H != barsH) {
      barsStatus = sh;
      barsNav = nh;
      barsW = W;
      barsH = H;
      barsLayer.frame = frameLayer.bounds;
      statusLayer.hidden = sh == 0;
      statusLayer.frame = CGRectMake(0, 0, W, sh);
      clockLayer.frame = CGRectMake(16, (sh - 15) / 2, 60, 16);
      batteryLayer.frame = CGRectMake(W - 16 - 24, (sh - 12) / 2, 24, 12);
      wifiLayer.frame = CGRectMake(W - 16 - 24 - 20, (sh - 11) / 2, 15, 11);
      handleLayer.hidden = nh == 0;
      handleLayer.frame = CGRectMake((W - 108) / 2, H - nh / 2 - 2, 108, 4);
    }
    if (darkStatus != darkStatusIcons || darkNav != darkHandle) {
      darkStatusIcons = darkStatus;
      darkHandle = darkNav;
      styleBars();
    }
    [CATransaction commit];
  });
}

// ---------------------------------------------------------------- zoom

// Window size only: the app keeps its pixels, the layer scales them, and input divides by the same factor.
// 0 = actual size (as opened), 1 = fit the screen's height, 2 = zoom in, 3 = zoom out.
static void layoutAfterZoom(void);
static void setZoom(int how) {
  NSWindow *win = NSApp.windows.firstObject;
  if (!win) return;
  float s = deviceScale;
  NSRect visible = win.screen.visibleFrame;
  CGFloat titleBar = win.frame.size.height - [win contentRectForFrameRect:win.frame].size.height;
  switch (how) {
    case 0: s = openScale; break;
    case 1: s = MAX(outerH() / (visible.size.height - titleBar), outerW() / visible.size.width); break;
    case 2: s = deviceScale / 1.25f; break;
    case 3: s = deviceScale * 1.25f; break;
  }
  s = MIN(MAX(s, openScale / 2.5f), openScale * 4);
  deviceScale = s;
  NSRect content = [win contentRectForFrameRect:win.frame];
  NSSize size = NSMakeSize(outerW() / s, outerH() / s);
  content.origin.y += content.size.height - size.height;
  content.size = size;
  NSRect frame = [win frameRectForContentRect:content];
  // Keep it on screen.
  if (NSMinY(frame) < NSMinY(visible)) frame.origin.y = NSMinY(visible);
  if (NSMaxY(frame) > NSMaxY(visible)) frame.origin.y = NSMaxY(visible) - frame.size.height;
  selfResizing = YES;
  [win setFrame:frame display:YES animate:NO];
  selfResizing = NO;
  layoutAfterZoom();
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeZoom(JNIEnv *env, jclass c, jint how) {
  onMain(^{ setZoom(how); });
}

// The window's content to the display's size (or the skin's frame) at the current zoom, its top left corner kept and
// on the Mac's screen; actualSize goes back to actual size first, zoomed out only as far as the screen needs.
static void sizeWindow(BOOL actualSize) {
  NSWindow *win = NSApp.windows.firstObject;
  // While the user drags an edge the window is already the size asked for (to a pixel): leave it to them.
  if (win.inLiveResize || (win.styleMask & NSWindowStyleMaskFullScreen)) return;
  int w = outerW(), h = outerH();
  NSRect visible = win.screen.visibleFrame;
  NSRect frame = win.frame;
  CGFloat titleBar = frame.size.height - [win contentRectForFrameRect:frame].size.height;
  // Another device opens at actual size, and a display too big for the Mac's screen (a TV, say) zoomed out to fit.
  if (actualSize) deviceScale = openScale;
  float fit = MAX(h / (visible.size.height - titleBar), w / visible.size.width);
  if (visible.size.width > 0 && fit > deviceScale) deviceScale = fit;
  NSSize size = NSMakeSize(w / deviceScale, h / deviceScale);
  NSRect content = [win contentRectForFrameRect:frame];
  // Keep the window's top left corner where it was, and on screen.
  content.origin.y += content.size.height - size.height;
  content.size = size;
  frame = [win frameRectForContentRect:content];
  if (NSMinY(frame) < NSMinY(visible)) frame.origin.y = NSMinY(visible);
  if (NSMaxX(frame) > NSMaxX(visible)) frame.origin.x = MAX(NSMinX(visible), NSMaxX(visible) - frame.size.width);
  selfResizing = YES;
  [win setFrame:frame display:YES animate:NO];
  selfResizing = NO;
  frameLayer.contentsGravity = kCAGravityResize;
}

// ---------------------------------------------------------------- rotation

// New display size: new IOSurfaces (the old ones are released once Core Animation lets go of them), a window of
// the new shape, and overlays laid out again (the Java side re-sends the system bars).
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeResize(JNIEnv *env, jclass c, jint w, jint h, jboolean actualSize) {
  IOSurfaceRef old[SURFACES];
  for (int i = 0; i < SURFACES; i++) {
    old[i] = surfaces[i];
    NSDictionary *props = @{
      (id)kIOSurfaceWidth : @(w),
      (id)kIOSurfaceHeight : @(h),
      (id)kIOSurfaceBytesPerElement : @4,
      (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
    };
    surfaces[i] = IOSurfaceCreate((__bridge CFDictionaryRef)props);
  }
  nextSurface = 0;
  devW = w;
  devH = h;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    sizeWindow(actualSize);
    frameLayer.contentsScale = deviceScale;
    frameLayer.contents = nil;
    layoutAfterZoom();
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  // The layer no longer shows them; Core Animation keeps its own reference to any it still holds.
  for (int i = 0; i < SURFACES; i++) {
    if (old[i]) CFRelease(old[i]);
  }
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetTitle(JNIEnv *env, jclass c, jstring title) {
  const char *t = (*env)->GetStringUTFChars(env, title, NULL);
  NSString *ns = [NSString stringWithUTF8String:t];
  (*env)->ReleaseStringUTFChars(env, title, t);
  onMain(^{ NSApp.windows.firstObject.title = ns; });
}

// The status bar clock shows this zone (an IANA name), as the device's own clock would.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetTimeZone(JNIEnv *env, jclass c, jstring zone) {
  const char *z = (*env)->GetStringUTFChars(env, zone, NULL);
  NSTimeZone *tz = [NSTimeZone timeZoneWithName:[NSString stringWithUTF8String:z]];
  (*env)->ReleaseStringUTFChars(env, zone, z);
  onMain(^{
    clockZone = tz;
    if (clockLayer) updateClock();
  });
}

// Sets the window's content size in points as a drag of its corner would end (the `window` control command), so
// scripts can check the path a user's resize takes.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeDragResize(JNIEnv *env, jclass c, jint wPts, jint hPts) {
  onMain(^{
    NSWindow *win = NSApp.windows.firstObject;
    NSRect content = [win contentRectForFrameRect:win.frame];
    content.origin.y += content.size.height - hPts;
    content.size = NSMakeSize(wPts, hPts);
    [win setFrame:[win frameRectForContentRect:content] display:YES animate:NO];
  });
}

// The overlays are laid out in points: rebuild them for the new window size (the Java side re-sends the bars).
static void layoutSkin(void);
static void layoutAfterZoom(void) {
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  layoutSkin();  // the screen view into the frame, or back as the window's content
  if (!skinOn) frameLayer.frame = NSMakeRect(0, 0, devW / deviceScale, devH / deviceScale);
  [barsLayer removeFromSuperlayer];
  barsLayer = nil;
  darkStatusIcons = darkHandle = -1;
  [clockTimer invalidate];
  [bannerLayer removeFromSuperlayer];
  bannerLayer = nil;
  [CATransaction commit];
  buildKeyboard();  // laid out in points too
}

// ---------------------------------------------------------------- skins

// The frame view: the window's content while a skin is on, with the screen view (SimView) inside it at the skin's
// screen rectangle. Clicks on the frame land here and go no further; the screen view takes its own as before, so the
// app's coordinates are unchanged. The frame image is a layer above the screen (its hole shows the screen through,
// and its anti-aliased edge falls over the screen's), clipped by nothing.
@interface SkinView : NSView
@end
@implementation SkinView
- (BOOL)isFlipped { return YES; }
- (void)mouseDown:(NSEvent *)e {}
- (void)mouseDragged:(NSEvent *)e {}
- (void)mouseUp:(NSEvent *)e {}
- (void)rightMouseDown:(NSEvent *)e {}
- (void)scrollWheel:(NSEvent *)e {}
@end

// An image turned by quarter turns counterclockwise (1: its top edge on the left), as Skinning.turnRect turns rects.
static CGImageRef turnImage(CGImageRef img, int q) {
  q &= 3;
  if (!img) return NULL;
  if (q == 0) return CGImageRetain(img);
  size_t w = CGImageGetWidth(img), h = CGImageGetHeight(img);
  size_t W = q % 2 ? h : w, H = q % 2 ? w : h;
  CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  CGContextRef ctx = CGBitmapContextCreate(NULL, W, H, 8, 0, cs, (CGBitmapInfo)kCGImageAlphaPremultipliedLast);
  CGColorSpaceRelease(cs);
  // The context is y-up: a positive angle turns counterclockwise as seen.
  switch (q) {
    case 1: CGContextTranslateCTM(ctx, W, 0); CGContextRotateCTM(ctx, M_PI_2); break;
    case 2: CGContextTranslateCTM(ctx, W, H); CGContextRotateCTM(ctx, M_PI); break;
    case 3: CGContextTranslateCTM(ctx, 0, H); CGContextRotateCTM(ctx, -M_PI_2); break;
  }
  CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), img);
  CGImageRef out = CGBitmapContextCreateImage(ctx);
  CGContextRelease(ctx);
  return out;
}

static CGImageRef decodeImage(JNIEnv *env, jbyteArray bytes) {
  if (!bytes) return NULL;
  jsize n = (*env)->GetArrayLength(env, bytes);
  NSMutableData *d = [NSMutableData dataWithLength:n];
  (*env)->GetByteArrayRegion(env, bytes, 0, n, d.mutableBytes);
  NSBitmapImageRep *rep = [NSBitmapImageRep imageRepWithData:d];
  return rep.CGImage ? CGImageRetain(rep.CGImage) : NULL;
}

// Lays the screen view out in the frame view (skin on), or makes it the window's content again (off).
static void layoutSkin(void) {
  NSWindow *win = simView.window ?: NSApp.windows.firstObject;
  if (!win || !simView) return;
  CGFloat s = deviceScale;
  if (!skinOn) {
    if (win.contentView != simView) {
      [simView removeFromSuperview];
      win.contentView = simView;
      [win makeFirstResponder:simView];
      simView.frame = NSMakeRect(0, 0, devW / s, devH / s);
    }
    frameLayer.cornerRadius = 0;
    frameLayer.masksToBounds = NO;
    frameLayer.mask = nil;
    return;
  }
  if (!skinView) {
    skinView = [[SkinView alloc] initWithFrame:NSMakeRect(0, 0, skinOuterW / s, skinOuterH / s)];
    skinView.wantsLayer = YES;
    skinFrameLayer = [CALayer layer];
    skinFrameLayer.zPosition = 100;
    skinFrameLayer.contentsGravity = kCAGravityResize;
    skinMaskLayer = [CALayer layer];
    skinMaskLayer.contentsGravity = kCAGravityResize;
    // Black under the screen, in the hole's own shape: the frame's hole and the display agree only within 1%.
    skinHoleLayer = [CALayer layer];
    skinHoleLayer.zPosition = -10;
    skinHoleLayer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
    skinHoleMaskLayer = [CALayer layer];  // the mask again: a layer masks only one other
    skinHoleMaskLayer.contentsGravity = kCAGravityResize;
  }
  if (win.contentView != skinView) {
    [simView removeFromSuperview];
    win.contentView = skinView;
    [skinView addSubview:simView];
    [win makeFirstResponder:simView];
  }
  skinView.frame = NSMakeRect(0, 0, skinOuterW / s, skinOuterH / s);
  simView.frame = NSMakeRect(skinScreenX / s, skinScreenY / s, devW / s, devH / s);
  if (skinFrameLayer.superlayer != skinView.layer) [skinView.layer addSublayer:skinFrameLayer];
  if (skinHoleLayer.superlayer != skinView.layer) [skinView.layer addSublayer:skinHoleLayer];
  // The layer's coordinates are its view's (flipped, top down) as AppKit sets them up for a flipped layer-backed view.
  skinHoleLayer.frame = CGRectMake(skinHole[0] / s, skinView.layer.geometryFlipped ? skinHole[1] / s
                                   : (skinOuterH - skinHole[1] - skinHole[3]) / s, skinHole[2] / s, skinHole[3] / s);
  skinHoleLayer.cornerRadius = skinMask ? 0 : skinRadius / s;
  // With a mask the backing takes the screen's shape too: its corners outside a round watch face stay transparent.
  if (skinMask) {
    skinHoleMaskLayer.frame = skinHoleLayer.bounds;
    skinHoleMaskLayer.contents = (__bridge id)skinMask;
    skinHoleLayer.mask = skinHoleMaskLayer;
  } else {
    skinHoleLayer.mask = nil;
  }
  skinFrameLayer.frame = skinView.layer.bounds;
  skinFrameLayer.contents = (__bridge id)skinFrame;
  frameLayer.cornerRadius = skinMask ? 0 : skinRadius / s;
  frameLayer.masksToBounds = skinRadius > 0 && !skinMask;
  if (skinMask) {
    skinMaskLayer.frame = frameLayer.bounds;
    skinMaskLayer.contents = (__bridge id)skinMask;
    frameLayer.mask = skinMaskLayer;
  } else {
    frameLayer.mask = nil;
  }
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetSkin(JNIEnv *env, jclass c, jbyteArray jframe, jint frameTurns,
    jbyteArray jmask, jint maskTurns, jint ow, jint oh, jintArray jhole, jint sx, jint sy, jint radius) {
  jint hole[4] = {0, 0, 0, 0};
  if (jhole) (*env)->GetIntArrayRegion(env, jhole, 0, 4, hole);
  int h0 = hole[0], h1 = hole[1], h2 = hole[2], h3 = hole[3];
  CGImageRef frame = NULL, mask = NULL;
  if (jframe) {
    CGImageRef f = decodeImage(env, jframe);
    frame = turnImage(f, frameTurns);
    if (f) CGImageRelease(f);
    CGImageRef m = decodeImage(env, jmask);
    mask = turnImage(m, maskTurns);
    if (m) CGImageRelease(m);
  }
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    NSWindow *win = NSApp.windows.firstObject;
    if (!frame && !skinOn) {  // no skin before or after (a rotation without one): the window keeps its size and zoom
      dispatch_semaphore_signal(done);
      return;
    }
    if (skinFrame) CGImageRelease(skinFrame);
    if (skinMask) CGImageRelease(skinMask);
    skinFrame = frame;
    skinMask = mask;
    skinOn = frame != NULL;
    skinOuterW = ow;
    skinOuterH = oh;
    skinScreenX = sx;
    skinScreenY = sy;
    skinRadius = radius;
    skinHole[0] = h0;
    skinHole[1] = h1;
    skinHole[2] = h2;
    skinHole[3] = h3;
    // The frame has its own shape: the window is not resized by dragging while it is on (zoom still works).
    // None of this is the user resizing the display: a style change or a new content view resizes the window too.
    selfResizing = YES;
    if (skinOn) win.styleMask &= ~NSWindowStyleMaskResizable;
    else win.styleMask |= NSWindowStyleMaskResizable;
    selfResizing = NO;
    sizeWindow(!skinOn);  // back to actual size without one (a frame may have needed the window zoomed out)
    selfResizing = YES;
    layoutAfterZoom();
    selfResizing = NO;
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetSkinMenu(JNIEnv *env, jclass c, jobjectArray jtitles) {
  NSMutableArray<NSString *> *titles = [NSMutableArray array];
  jsize n = (*env)->GetArrayLength(env, jtitles);
  for (jsize i = 0; i < n; i++) {
    jstring t = (*env)->GetObjectArrayElement(env, jtitles, i);
    const char *u = (*env)->GetStringUTFChars(env, t, NULL);
    [titles addObject:[NSString stringWithUTF8String:u]];
    (*env)->ReleaseStringUTFChars(env, t, u);
  }
  onMain(^{
    while (skinMenu.numberOfItems > 1) {
      NSMenuItem *old = [skinMenu itemAtIndex:skinMenu.numberOfItems - 1];
      [menuItems removeObjectForKey:@(old.tag)];
      [skinMenu removeItem:old];
    }
    if (titles.count) [skinMenu addItem:[NSMenuItem separatorItem]];
    for (NSUInteger i = 0; i < titles.count; i++) addItem(skinMenu, titles[i], TAG_SKIN_FIRST + 1 + (int)i, nil, 0);
  });
}

// A left click at a point of the window's content (points from its top left), sent through the window: the view
// under it takes it, as a real click (`window click X Y`).
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeClick(JNIEnv *env, jclass c, jfloat x, jfloat y) {
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    NSWindow *win = NSApp.windows.firstObject;
    NSView *content = win.contentView;
    NSPoint p = [content convertPoint:NSMakePoint(x, content.isFlipped ? y : content.bounds.size.height - y) toView:nil];
    NSTimeInterval now = [NSProcessInfo processInfo].systemUptime;
    for (int i = 0; i < 2; i++) {
      NSEvent *e = [NSEvent mouseEventWithType:i == 0 ? NSEventTypeLeftMouseDown : NSEventTypeLeftMouseUp location:p
                                 modifierFlags:0 timestamp:now + i * 0.05 windowNumber:win.windowNumber context:nil
                                   eventNumber:0 clickCount:1 pressure:i == 0 ? 1 : 0];
      [win sendEvent:e];
    }
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}

// ---------------------------------------------------------------- software keyboard

// Drawn over the app like an IME window; the app already has the IME insets (SoftKeyboard.java). A click on a key
// posts the character (or Delete/Enter) as the hardware keyboard would.
static CALayer *keyboardLayer;
static int keyboardPx;
static BOOL keyboardShift, keyboardSymbols;
typedef struct { CGRect r; unichar ch; int code; } Key;  // code: 0 char, 1 shift, 2 delete, 3 enter, 4 symbols
static Key keys[64];
static int keyCount;

static void buildKeyboard(void) {
  [keyboardLayer removeFromSuperlayer];
  keyboardLayer = nil;
  keyCount = 0;
  if (!keyboardPx || !frameLayer) return;
  CGFloat W = devW / deviceScale, H = devH / deviceScale, kh = keyboardPx / deviceScale;
  CGFloat scale = NSApp.windows.firstObject.backingScaleFactor ?: 2;
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  keyboardLayer = [CALayer layer];
  keyboardLayer.frame = CGRectMake(0, H - kh, W, kh);
  keyboardLayer.backgroundColor = [NSColor colorWithWhite:0.17 alpha:1].CGColor;
  keyboardLayer.zPosition = 25;
  [frameLayer addSublayer:keyboardLayer];
  NSArray<NSString *> *rows = keyboardSymbols ? @[ @"1234567890", @"@#$%&-+()/", @"*\"':;!?", @"" ]
                                              : @[ @"qwertyuiop", @"asdfghjkl", @"zxcvbnm", @"" ];
  CGFloat bottomInset = 48 / deviceScale;  // the gesture bar stays under the keyboard
  CGFloat rowH = (kh - bottomInset - 8) / 4, gap = 5, kw = (W - gap * 11) / 10;
  for (int r = 0; r < 4; r++) {
    NSString *row = rows[r];
    CGFloat y = 6 + r * rowH;
    NSMutableArray *rowKeys = [NSMutableArray array];
    if (r == 2) [rowKeys addObject:@[ keyboardSymbols ? @"" : (keyboardShift ? @"⇧" : @"⇧"), @1, @1.4 ]];
    for (NSUInteger i = 0; i < row.length; i++) {
      NSString *c = [row substringWithRange:NSMakeRange(i, 1)];
      [rowKeys addObject:@[ keyboardShift ? c.uppercaseString : c, @0, @1 ]];
    }
    if (r == 2) [rowKeys addObject:@[ @"⌫", @2, @1.4 ]];
    if (r == 3) {
      [rowKeys addObject:@[ keyboardSymbols ? @"ABC" : @"?123", @4, @1.6 ]];
      [rowKeys addObject:@[ @",", @0, @1 ]];
      [rowKeys addObject:@[ @" ", @0, @4.6 ]];
      [rowKeys addObject:@[ @".", @0, @1 ]];
      [rowKeys addObject:@[ @"⏎", @3, @1.6 ]];
    }
    CGFloat units = 0;
    for (NSArray *k in rowKeys) units += [k[2] doubleValue];
    CGFloat x = (W - (units * kw + (rowKeys.count - 1) * gap)) / 2;
    for (NSArray *k in rowKeys) {
      NSString *label = k[0];
      CGFloat width = [k[2] doubleValue] * kw;
      if (label.length && keyCount < 64) {
        CGRect kr = CGRectMake(x, y, width, rowH - gap);
        CALayer *key = [CALayer layer];
        key.frame = kr;
        key.cornerRadius = 6;
        key.backgroundColor = ([k[1] intValue] ? [NSColor colorWithWhite:0.32 alpha:1] : [NSColor colorWithWhite:0.42 alpha:1]).CGColor;
        CATextLayer *t = [CATextLayer layer];
        t.string = [label isEqualToString:@" "] ? @"space" : label;
        t.font = (__bridge CFTypeRef)[NSFont systemFontOfSize:15];
        t.fontSize = [label isEqualToString:@" "] ? 12 : 15;
        t.alignmentMode = kCAAlignmentCenter;
        t.foregroundColor = NSColor.whiteColor.CGColor;
        t.contentsScale = scale;
        t.frame = CGRectMake(0, (kr.size.height - 19) / 2, kr.size.width, 19);
        [key addSublayer:t];
        [keyboardLayer addSublayer:key];
        keys[keyCount++] = (Key){CGRectOffset(kr, 0, H - kh), [label characterAtIndex:0], [k[1] intValue]};
      }
      x += width + gap;
    }
  }
  [CATransaction commit];
}

// From mouseDown: true if the click was on the keyboard (a key typed, or the gap between keys).
static BOOL keyboardHit(NSPoint p) {
  if (!keyboardLayer || p.y < CGRectGetMinY(keyboardLayer.frame)) return NO;
  for (int i = 0; i < keyCount; i++) {
    if (!CGRectContainsPoint(keys[i].r, p)) continue;
    switch (keys[i].code) {
      case 0:
        post(EV_CHAR, 0, 0, (float)keys[i].ch);
        if (keyboardShift) {
          keyboardShift = NO;
          buildKeyboard();
        }
        break;
      case 1: keyboardShift = !keyboardShift; buildKeyboard(); break;
      case 2: post(EV_KEY, 0, 0, 67); break;
      case 3: post(EV_KEY, 0, 0, 66); break;
      case 4: keyboardSymbols = !keyboardSymbols; buildKeyboard(); break;
    }
    break;
  }
  return YES;
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeShowKeyboard(JNIEnv *env, jclass c, jint px) {
  onMain(^{
    keyboardPx = px;
    keyboardShift = keyboardSymbols = NO;
    buildKeyboard();
  });
}

// ---------------------------------------------------------------- heads-up notifications

static CATextLayer *bannerApp, *bannerTitle, *bannerText;
static NSTimer *bannerTimer;

static CATextLayer *bannerLine(CGFloat size, NSFontWeight weight, CGFloat y, CGFloat width, CGFloat scale) {
  CATextLayer *t = [CATextLayer layer];
  t.font = (__bridge CFTypeRef)[NSFont systemFontOfSize:size weight:weight];
  t.fontSize = size;
  t.contentsScale = scale;
  t.truncationMode = kCATruncationEnd;
  t.frame = CGRectMake(14, y, width - 28, size + 4);
  [bannerLayer addSublayer:t];
  return t;
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeShowBanner(JNIEnv *env, jclass c, jstring japp, jstring jtitle, jstring jtext) {
  NSString *strs[3];
  jstring js[3] = {japp, jtitle, jtext};
  for (int i = 0; i < 3; i++) {
    const char *u = (*env)->GetStringUTFChars(env, js[i], NULL);
    strs[i] = [NSString stringWithUTF8String:u];
    (*env)->ReleaseStringUTFChars(env, js[i], u);
  }
  NSString *app = strs[0], *title = strs[1], *text = strs[2];
  onMain(^{
    if (!frameLayer) return;
    CGFloat W = devW / deviceScale, top = 48 / deviceScale + 6;
    CGFloat scale = NSApp.windows.firstObject.backingScaleFactor ?: 2;
    if (!bannerLayer) {
      bannerLayer = [CALayer layer];
      bannerLayer.frame = CGRectMake(8, top, W - 16, 70);
      bannerLayer.cornerRadius = 18;
      bannerLayer.backgroundColor = [NSColor colorWithWhite:0.16 alpha:0.96].CGColor;
      bannerLayer.shadowOpacity = 0.35;
      bannerLayer.shadowRadius = 8;
      bannerLayer.shadowOffset = CGSizeMake(0, -2);
      bannerLayer.zPosition = 30;
      [frameLayer addSublayer:bannerLayer];
      bannerApp = bannerLine(11, NSFontWeightRegular, 10, W - 16, scale);
      bannerApp.foregroundColor = [NSColor colorWithWhite:0.75 alpha:1].CGColor;
      bannerTitle = bannerLine(13, NSFontWeightSemibold, 26, W - 16, scale);
      bannerTitle.foregroundColor = NSColor.whiteColor.CGColor;
      bannerText = bannerLine(13, NSFontWeightRegular, 44, W - 16, scale);
      bannerText.foregroundColor = [NSColor colorWithWhite:0.9 alpha:1].CGColor;
    }
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    bannerApp.string = app;
    bannerTitle.string = title;
    bannerText.string = text;
    bannerLayer.hidden = NO;
    [CATransaction commit];
    [bannerTimer invalidate];
    bannerTimer = [NSTimer scheduledTimerWithTimeInterval:5 repeats:NO block:^(NSTimer *t) { bannerLayer.hidden = YES; }];
  });
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeHideBanner(JNIEnv *env, jclass c) {
  onMain(^{ bannerLayer.hidden = YES; });
}

// ---------------------------------------------------------------- questions (permission prompts)

// A sheet on the window, so neither thread blocks: the Java side polls for the answer.
static volatile int askAnswer = -1;
static NSAlert *askAlert;

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeAsk(JNIEnv *env, jclass c, jstring jtitle, jstring jmessage, jobjectArray jbuttons) {
  const char *t = (*env)->GetStringUTFChars(env, jtitle, NULL);
  const char *m = (*env)->GetStringUTFChars(env, jmessage, NULL);
  NSString *title = [NSString stringWithUTF8String:t], *message = [NSString stringWithUTF8String:m];
  (*env)->ReleaseStringUTFChars(env, jtitle, t);
  (*env)->ReleaseStringUTFChars(env, jmessage, m);
  NSMutableArray *buttons = [NSMutableArray array];
  for (jsize i = 0; i < (*env)->GetArrayLength(env, jbuttons); i++) {
    jstring b = (*env)->GetObjectArrayElement(env, jbuttons, i);
    const char *bc = (*env)->GetStringUTFChars(env, b, NULL);
    [buttons addObject:[NSString stringWithUTF8String:bc]];
    (*env)->ReleaseStringUTFChars(env, b, bc);
  }
  askAnswer = -1;
  onMain(^{
    NSAlert *a = [NSAlert new];
    a.messageText = title;
    a.informativeText = message;
    for (NSString *b in buttons) [a addButtonWithTitle:b];
    askAlert = a;
    if (!simWindow.visible) [NSApp requestUserAttention:NSInformationalRequest];  // hidden or minimized: bounce the Dock icon
    [a beginSheetModalForWindow:NSApp.windows.firstObject completionHandler:^(NSModalResponse r) {
      askAnswer = (int)(r - NSAlertFirstButtonReturn);
      askAlert = nil;
    }];
  });
}

JNIEXPORT jint JNICALL Java_dev_apksim_SimWindow_nativeAskAnswer(JNIEnv *env, jclass c) { return askAnswer; }

// Answered elsewhere (the control channel): take the sheet down without an answer.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeDismissAsk(JNIEnv *env, jclass c) {
  onMain(^{
    if (askAlert) [NSApp.windows.firstObject endSheet:askAlert.window returnCode:NSModalResponseAbort];
  });
}

// ---------------------------------------------------------------- file panel (document and photo pickers)

// A sheet, like the questions: the Java side polls. The result is the chosen paths, newline-separated, or "" when
// cancelled; nil while the panel is open.
static NSString *pickResult;
static NSOpenPanel *pickPanel;

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativePickFiles(JNIEnv *env, jclass c, jstring jtypes, jboolean multiple) {
  const char *t = (*env)->GetStringUTFChars(env, jtypes, NULL);
  NSArray<NSString *> *mimes = [[NSString stringWithUTF8String:t] componentsSeparatedByString:@","];
  (*env)->ReleaseStringUTFChars(env, jtypes, t);
  @synchronized([MenuTarget class]) { pickResult = nil; }
  onMain(^{
    NSOpenPanel *panel = [NSOpenPanel openPanel];
    panel.allowsMultipleSelection = multiple;
    panel.canChooseDirectories = NO;
    panel.message = @"Choose a file for the app (the sandbox lets it read Downloads, not Documents or Desktop)";
    panel.directoryURL = [NSURL fileURLWithPath:[NSHomeDirectory() stringByAppendingPathComponent:@"Downloads"]];
    NSMutableArray<UTType *> *types = [NSMutableArray array];
    for (NSString *m in mimes) {
      if ([m isEqualToString:@"*/*"] || m.length == 0) { [types removeAllObjects]; break; }
      UTType *u = [m hasPrefix:@"image/"] && [m hasSuffix:@"*"] ? UTTypeImage
                : [m hasPrefix:@"video/"] && [m hasSuffix:@"*"] ? UTTypeMovie
                : [m hasPrefix:@"audio/"] && [m hasSuffix:@"*"] ? UTTypeAudio
                : [m hasPrefix:@"text/"] && [m hasSuffix:@"*"] ? UTTypeText
                : [UTType typeWithMIMEType:m];
      if (u) [types addObject:u];
    }
    if (types.count) panel.allowedContentTypes = types;
    pickPanel = panel;
    [panel beginSheetModalForWindow:NSApp.windows.firstObject completionHandler:^(NSModalResponse r) {
      NSMutableArray *paths = [NSMutableArray array];
      if (r == NSModalResponseOK) for (NSURL *u in panel.URLs) [paths addObject:u.path];
      @synchronized([MenuTarget class]) { pickResult = [paths componentsJoinedByString:@"\n"]; }
      pickPanel = nil;
    }];
  });
}

JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativePickResult(JNIEnv *env, jclass c) {
  NSString *r;
  @synchronized([MenuTarget class]) { r = pickResult; }
  return r ? (*env)->NewStringUTF(env, r.UTF8String) : NULL;
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeDismissPick(JNIEnv *env, jclass c) {
  onMain(^{
    if (pickPanel) [NSApp.windows.firstObject endSheet:pickPanel returnCode:NSModalResponseCancel];
  });
}

// ---------------------------------------------------------------- share sheet

// Text the app shares (ACTION_SEND) goes to the Mac's share menu: Mail, Messages, Notes, AirDrop and the rest.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeShare(JNIEnv *env, jclass c, jstring jtext, jstring jpath) {
  NSMutableArray *items = [NSMutableArray array];
  if (jtext) {
    const char *t = (*env)->GetStringUTFChars(env, jtext, NULL);
    [items addObject:[NSString stringWithUTF8String:t]];
    (*env)->ReleaseStringUTFChars(env, jtext, t);
  }
  if (jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    [items addObject:[NSURL fileURLWithPath:[NSString stringWithUTF8String:p]]];
    (*env)->ReleaseStringUTFChars(env, jpath, p);
  }
  onMain(^{
    NSView *view = NSApp.windows.firstObject.contentView;
    NSSharingServicePicker *picker = [[NSSharingServicePicker alloc] initWithItems:items];
    NSRect at = NSMakeRect(NSMidX(view.bounds) - 1, NSMidY(view.bounds) - 1, 2, 2);
    [picker showRelativeToRect:at ofView:view preferredEdge:NSRectEdgeMinY];
  });
}

// ---------------------------------------------------------------- menu and clipboard plumbing

JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativeTakeText(JNIEnv *env, jclass c) {
  NSString *t;
  @synchronized([MenuTarget class]) {
    t = pendingText;
    pendingText = nil;
  }
  return t ? (*env)->NewStringUTF(env, t.UTF8String) : NULL;
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetMenuState(JNIEnv *env, jclass c, jint tag, jboolean on) {
  onMain(^{
    menuItems[@(tag)].state = on ? NSControlStateValueOn : NSControlStateValueOff;
  });
}

JNIEXPORT jlong JNICALL Java_dev_apksim_SimWindow_nativeClipboardChangeCount(JNIEnv *env, jclass c) {
  return (jlong)[NSPasteboard generalPasteboard].changeCount;
}

// Puts the app's copied text on the Mac clipboard; returns the new change count, so the copy is not read back.
JNIEXPORT jlong JNICALL Java_dev_apksim_SimWindow_nativeSetClipboardText(JNIEnv *env, jclass c, jstring jtext) {
  const jchar *chars = (*env)->GetStringChars(env, jtext, NULL);
  NSString *text = [NSString stringWithCharacters:chars length:(NSUInteger)(*env)->GetStringLength(env, jtext)];
  (*env)->ReleaseStringChars(env, jtext, chars);
  __block long count = 0;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    NSPasteboard *pb = [NSPasteboard generalPasteboard];
    [pb clearContents];
    [pb setString:text forType:NSPasteboardTypeString];
    count = pb.changeCount;
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  return (jlong)count;
}

// Fills out[] with {type, x, y, v} and returns 1, or returns 0 if the queue is empty.
JNIEXPORT jint JNICALL Java_dev_apksim_SimWindow_nativePoll(JNIEnv *env, jclass c, jfloatArray out) {
  Event e;
  pthread_mutex_lock(&qLock);
  if (qHead == qTail) {
    pthread_mutex_unlock(&qLock);
    return 0;
  }
  e = queue[qHead];
  qHead = (qHead + 1) % QUEUE_SIZE;
  pthread_mutex_unlock(&qLock);
  jfloat vals[4] = {(float)e.type, e.x, e.y, e.v};
  (*env)->SetFloatArrayRegion(env, out, 0, 4, vals);
  return 1;
}

// Debug aid: captures this process's own window and writes it as a binary PPM, so the pixels the user
// actually sees can be checked without screen-recording permission (own windows are always allowed).
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeWindowSnapshot(JNIEnv *env, jclass c, jstring jpath) {
  const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
  NSString *path = [NSString stringWithUTF8String:p];
  (*env)->ReleaseStringUTFChars(env, jpath, p);
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    typedef CGImageRef (*CaptureFn)(CGRect, uint32_t, uint32_t, uint32_t);
    CaptureFn capture = (CaptureFn)dlsym(RTLD_DEFAULT, "CGWindowListCreateImage");
    NSWindow *win = NSApp.windows.firstObject;
    CGImageRef img = capture ? capture(CGRectNull, 1 << 3 /* IncludingWindow */, (uint32_t)win.windowNumber, 1 << 0 /* IgnoreFraming */) : NULL;
    if (!img) {
      NSLog(@"capture failed: fn=%p window=%ld", capture, (long)win.windowNumber);
    } else {
      size_t w = CGImageGetWidth(img), h = CGImageGetHeight(img);
      NSMutableData *px = [NSMutableData dataWithLength:w * h * 4];
      CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
      CGContextRef ctx = CGBitmapContextCreate(px.mutableBytes, w, h, 8, w * 4, cs, kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
      CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), img);
      FILE *f = fopen(path.UTF8String, "wb");
      fprintf(f, "P6\n%zu %zu\n255\n", w, h);
      const uint8_t *b = px.bytes;
      for (size_t i = 0; i < w * h; i++) fwrite(b + i * 4, 1, 3, f);
      fclose(f);
      CGContextRelease(ctx);
      CGColorSpaceRelease(cs);
      CGImageRelease(img);
    }
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}

// The display as a device's screencap shows it: the app's frame with the system UI drawn over it (status and
// navigation bars, the software keyboard, a heads-up notification), without the window's chrome or the touch dots.
// Fills buffer (devW x devH, BGRA, premultiplied: Skia's order here) and returns 1, or 0 when the window is not that size.
JNIEXPORT jint JNICALL Java_dev_apksim_SimWindow_nativeDisplayImage(JNIEnv *env, jclass c, jobject buffer, jint w, jint h) {
  uint8_t *dst = (*env)->GetDirectBufferAddress(env, buffer);
  if (!dst || !frameLayer || w != devW || h != devH) return 0;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  __block int rendered = 0;  // 0 when no context could be made: the caller keeps its own image
  onMain(^{
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGContextRef ctx = CGBitmapContextCreate(dst, w, h, 8, (size_t)w * 4, cs,
                                             kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
    if (ctx) {
      rendered = 1;
      BOOL fingerHidden[2];  // a finger dot still down (or hidden after its gesture) keeps its own visibility
      for (int i = 0; i < 2; i++) {
        fingerHidden[i] = fingerLayers[i].hidden;
        fingerLayers[i].hidden = YES;
      }
      // A bitmap context's origin is bottom-left, while the window's layers are laid out top-down: flip it, or the shot
      // comes out upside down (adb screencap, issue 237).
      CGContextTranslateCTM(ctx, 0, h);
      CGContextScaleCTM(ctx, 1, -1);
      CGContextScaleCTM(ctx, deviceScale, deviceScale);
      [frameLayer renderInContext:ctx];
      for (int i = 0; i < 2; i++) fingerLayers[i].hidden = fingerHidden[i];
      CGContextRelease(ctx);
    }
    CGColorSpaceRelease(cs);
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  return rendered;
}

// What the screen can do, and how many frames reached Core Animation, on the thread that shows them.
JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativeDisplayInfo(JNIEnv *env, jclass c) {
  __block long maxFps = 0;
  __block long committed = 0;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    maxFps = NSApp.windows.firstObject.screen.maximumFramesPerSecond;
    committed = commits;
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  char out[128];
  snprintf(out, sizeof out, "screen maximumFramesPerSecond=%ld, frames committed to Core Animation=%ld", maxFps, committed);
  return (*env)->NewStringUTF(env, out);
}

// Nanoseconds until the display's next vsync, or -1 when the display link is not running (window hidden, minimised
// or fully covered: macOS then stops it), so the caller falls back to its own clock.
JNIEXPORT jlong JNICALL Java_dev_apksim_SimWindow_nativeUntilVsync(JNIEnv *env, jclass c) {
  double target, period, seen, now = CACurrentMediaTime();
  @synchronized([SimView class]) {
    target = vsyncTarget;
    period = vsyncPeriod;
    seen = vsyncSeen;
  }
  if (period <= 0 || now - seen > 4 * period + 0.004) return -1;
  while (target < now) target += period;
  return (jlong)((target - now) * 1e9);
}

// The display's refresh period in nanoseconds while its vsyncs arrive (as nativeUntilVsync), else -1.
JNIEXPORT jlong JNICALL Java_dev_apksim_SimWindow_nativeVsyncPeriod(JNIEnv *env, jclass c) {
  double period, seen, now = CACurrentMediaTime();
  @synchronized([SimView class]) {
    period = vsyncPeriod;
    seen = vsyncSeen;
  }
  if (period <= 0 || now - seen > 4 * period + 0.004) return -1;
  return (jlong)(period * 1e9);
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetCloseHides(JNIEnv *env, jclass c, jboolean on) { closeHides = on; }

// `window close`: what the window's close button does (performClose: asks windowShouldClose, as a click would).
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeClose(JNIEnv *env, jclass c) {
  dispatch_async(dispatch_get_main_queue(), ^{
    if (simWindow) [simWindow performClose:nil];
  });
}

// What reached the screen since the last call, counted per display vsync.
JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativeVsyncStats(JNIEnv *env, jclass c) {
  char out[200];
  @synchronized([SimView class]) {
    long all = vsShown + vsRepeated;
    snprintf(out, sizeof out, "screen %.0f Hz: new frame on %ld%% of vsyncs while animating (shown=%ld repeated=%ld), replaced before shown=%ld",
        vsyncPeriod > 0 ? 1 / vsyncPeriod : 0, all ? vsShown * 100 / all : 0, vsShown, vsRepeated, vsReplaced);
    vsShown = vsRepeated = vsReplaced = 0;
  }
  return (*env)->NewStringUTF(env, out);
}

// Marks the calling thread as driving the UI, which a real app's UI threads get automatically. Otherwise
// macOS may run it on an efficiency core and delay its timers to save power.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeBoostThread(JNIEnv *env, jclass c) {
  pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
}

// For the control channel's `paste`: same path as Cmd+V. Returns how many characters were queued.
JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativeClipboardText(JNIEnv *env, jclass c) {
  // On the main thread, like Cmd+V: read from another thread, NSPasteboard can hand back what it held before.
  __block NSString *text = nil;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    text = [[[NSPasteboard generalPasteboard] stringForType:NSPasteboardTypeString] copy];
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  if (!text) return NULL;
  NSUInteger n = text.length;
  unichar *buf = malloc(n * sizeof(unichar) + 1);
  [text getCharacters:buf range:NSMakeRange(0, n)];
  jstring r = (*env)->NewString(env, buf, (jsize)n);
  free(buf);
  return r;
}

JNIEXPORT jint JNICALL Java_dev_apksim_SimWindow_nativePaste(JNIEnv *env, jclass c) {
  __block int n = 0;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    n = pasteClipboard();
    dispatch_semaphore_signal(done);
  });
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
  return n;
}

// ---------------------------------------------------------------- window mode (`window show|hide|...`)

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeWindowMode(JNIEnv *env, jclass c, jint mode) {
  onMain(^{ applyWindowMode(mode); });
}

// What the window is doing: visible/minimized/hidden, and whether apksim is the active app.
JNIEXPORT jstring JNICALL Java_dev_apksim_SimWindow_nativeWindowState(JNIEnv *env, jclass c) {
  __block NSString *state = @"no window";
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  onMain(^{
    NSWindow *w = simWindow;
    if (w) {
      NSString *shown = NSApp.hidden ? @"hidden (app)" : w.miniaturized ? @"minimized" : w.visible ? @"visible" : @"hidden";
      // The content's size in points and the device pixels per point (the zoom), for `window click`.
      NSSize content = w.contentView.bounds.size;
      state = [NSString stringWithFormat:@"%@, %@%@, content %.0fx%.0f pt, %.3f px/pt", shown, NSApp.active ? @"active" : @"not active",
                                         w.visible && w.isKeyWindow && NSApp.active ? @", key" : @"", content.width, content.height,
                                         deviceScale];
    }
    dispatch_semaphore_signal(done);
  });
  if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC))) return (*env)->NewStringUTF(env, "main thread busy");
  return (*env)->NewStringUTF(env, state.UTF8String);
}
