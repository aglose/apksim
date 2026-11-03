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
       EV_TOUCH2_CENTRE = 9, EV_TOUCH2_DOWN = 10, EV_TOUCH2_MOVE = 11, EV_TOUCH2_UP = 12 };
typedef struct { int type; float x, y, v; } Event;

#define QUEUE_SIZE 4096
static Event queue[QUEUE_SIZE];
static int qHead, qTail;
static pthread_mutex_t qLock = PTHREAD_MUTEX_INITIALIZER;

static void post(int type, float x, float y, float v) {
  pthread_mutex_lock(&qLock);
  int next = (qTail + 1) % QUEUE_SIZE;
  if (next != qHead) {
    queue[qTail] = (Event){type, x, y, v};
    qTail = next;
  }
  pthread_mutex_unlock(&qLock);
}

#define SURFACES 3
static IOSurfaceRef surfaces[SURFACES];
static int nextSurface;
static int devW, devH;
static float deviceScale;
static CALayer *frameLayer;
static long commits;
static AVAssetWriter *recWriter;  // screen recording, below
static void recordFrame(IOSurfaceRef s);

static int pasteClipboard(void) {
  NSString *text = [[NSPasteboard generalPasteboard] stringForType:NSPasteboardTypeString];
  int n = 0;
  for (NSUInteger i = 0; i < text.length; i++) {
    unichar ch = [text characterAtIndex:i];
    if (ch == '\n' || ch == '\r') continue;  // A copied password often carries a trailing newline.
    post(EV_CHAR, 0, 0, (float)ch);
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
  TAG_BIOMETRICS_NOMATCH, TAG_LAYOUT_BOUNDS, TAG_A11Y_AUDIT, TAG_SOFT_KEYBOARD,
};
static BOOL keyboardHit(NSPoint p);  // the software keyboard, below
static void buildKeyboard(void);
static CALayer *bannerLayer;  // heads-up notification, below
static NSString *pendingText;
static NSMutableDictionary<NSNumber *, NSMenuItem *> *menuItems;

@interface MenuTarget : NSObject
@end
static void setZoom(int how);
@implementation MenuTarget
- (void)fire:(NSMenuItem *)item { post(EV_COMMAND, (float)item.tag, 0, 0); }
- (void)zoom:(NSMenuItem *)item { setZoom((int)item.tag); }
// Quit as closing the window does, so the app's main thread exits on its own path (it saves caches first).
- (void)quit:(id)sender { post(EV_CLOSE, 0, 0, 0); }
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
  addItem(file, @"Record Screen", TAG_RECORD, @"r", cmd);
  addItem(file, @"Show Screenshots and Recordings", TAG_SCREENSHOTS_FOLDER, nil, 0);
  NSMenu *device = submenu(bar, @"Device");
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
  [features addItem:[NSMenuItem separatorItem]];
  addItem(features, @"Open URL…", TAG_OPEN_URL, @"o", cmd | shift);
  NSMenu *window = submenu(bar, @"Window");
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
- (void)windowWillClose:(NSNotification *)n { post(EV_CLOSE, 0, 0, 0); }
@end

// Not the GCD main queue: [NSApp run] never returns, and that queue is serial, so anything queued
// behind it would wait forever. Blocks on the run loop run from inside NSApp's own event loop.
static void onMain(dispatch_block_t block) {
  CFRunLoopRef main = CFRunLoopGetMain();
  CFRunLoopPerformBlock(main, kCFRunLoopCommonModes, block);
  CFRunLoopWakeUp(main);
}

static void openWindow(int w, int h, float scale, NSString *title, int xOffset) {
  [NSApplication sharedApplication];
  [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
  NSMenu *menu = [[NSMenu alloc] init];
  NSMenuItem *appItem = [[NSMenuItem alloc] init];
  [menu addItem:appItem];
  NSMenu *appMenu = [[NSMenu alloc] init];
  NSMenuItem *quit = [appMenu addItemWithTitle:@"Quit" action:@selector(quit:) keyEquivalent:@"q"];
  appItem.submenu = appMenu;
  buildMenus(menu);
  quit.target = menuTarget;
  NSApp.mainMenu = menu;

  NSRect frame = NSMakeRect(80 + xOffset, 80, w / scale, h / scale);
  NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                 styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable
                                                   backing:NSBackingStoreBuffered
                                                     defer:NO];
  window.title = title;
  window.releasedWhenClosed = NO;
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
  [window makeFirstResponder:view];
  [window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeOpen(JNIEnv *env, jclass c, jint w, jint h, jfloat scale, jstring title, jint xOffset) {
  devW = w;
  devH = h;
  deviceScale = scale;
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
    openWindow(w, h, scale, ns, xOffset);
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
// already gets their insets; these layers sit above its pixels (window captures show them, `shot` does not).
static CALayer *barsLayer, *wifiLayer, *batteryLayer, *handleLayer;
static CATextLayer *clockLayer;
static NSTimer *clockTimer;
static int darkStatusIcons = -1, darkHandle = -1;

static CGImageRef symbol(NSString *name, NSColor *color, CGFloat points) {
  NSImage *img = [NSImage imageWithSystemSymbolName:name accessibilityDescription:nil];
  NSImageSymbolConfiguration *cfg = [[NSImageSymbolConfiguration configurationWithPointSize:points weight:NSFontWeightSemibold]
      configurationByApplyingConfiguration:[NSImageSymbolConfiguration configurationWithPaletteColors:@[ color ]]];
  img = [img imageWithSymbolConfiguration:cfg];
  NSRect r = NSMakeRect(0, 0, img.size.width, img.size.height);
  return [img CGImageForProposedRect:&r context:nil hints:@{NSImageHintCTM : [NSAffineTransform transform]}];
}

static void updateClock(void) {
  NSDateFormatter *f = [NSDateFormatter new];
  f.dateFormat = @"H:mm";
  clockLayer.string = [f stringFromDate:[NSDate date]];
}

static void styleBars(void) {
  NSColor *fg = darkStatusIcons ? [NSColor colorWithWhite:0.1 alpha:1] : [NSColor whiteColor];
  clockLayer.foregroundColor = fg.CGColor;
  wifiLayer.contents = (__bridge id)symbol(@"wifi", fg, 10);
  batteryLayer.contents = (__bridge id)symbol(@"battery.100percent", fg, 11);
  handleLayer.backgroundColor = (darkHandle ? [NSColor colorWithWhite:0 alpha:0.75] : [NSColor colorWithWhite:1 alpha:0.85]).CGColor;
}

// statusPx/navPx: the insets in device pixels (0 = no bar). Icons dark when the app asked for light bars.
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeSetSystemBars(JNIEnv *env, jclass c, jint statusPx, jint navPx, jboolean darkStatus, jboolean darkNav) {
  onMain(^{
    if (!frameLayer) return;
    CGFloat sh = statusPx / deviceScale, nh = navPx / deviceScale, W = devW / deviceScale, H = devH / deviceScale;
    if (!barsLayer) {
      barsLayer = [CALayer layer];
      barsLayer.frame = frameLayer.bounds;
      barsLayer.geometryFlipped = NO;  // the parent layer is already top-down
      barsLayer.zPosition = 10;
      [frameLayer addSublayer:barsLayer];
      CGFloat scale = NSApp.windows.firstObject.backingScaleFactor ?: 2;
      if (sh > 0) {
        clockLayer = [CATextLayer layer];
        clockLayer.font = (__bridge CFTypeRef)[NSFont monospacedDigitSystemFontOfSize:12 weight:NSFontWeightMedium];
        clockLayer.fontSize = 12;
        clockLayer.contentsScale = scale;
        clockLayer.frame = CGRectMake(16, (sh - 15) / 2, 60, 16);
        [barsLayer addSublayer:clockLayer];
        batteryLayer = [CALayer layer];
        batteryLayer.contentsGravity = kCAGravityResizeAspect;
        batteryLayer.contentsScale = scale;
        batteryLayer.frame = CGRectMake(W - 16 - 24, (sh - 12) / 2, 24, 12);
        [barsLayer addSublayer:batteryLayer];
        wifiLayer = [CALayer layer];
        wifiLayer.contentsGravity = kCAGravityResizeAspect;
        wifiLayer.contentsScale = scale;
        wifiLayer.frame = CGRectMake(W - 16 - 24 - 20, (sh - 11) / 2, 15, 11);
        [barsLayer addSublayer:wifiLayer];
        updateClock();
        clockTimer = [NSTimer scheduledTimerWithTimeInterval:5 repeats:YES block:^(NSTimer *t) { updateClock(); }];
      }
      if (nh > 0) {
        handleLayer = [CALayer layer];
        handleLayer.frame = CGRectMake((W - 108) / 2, H - nh / 2 - 2, 108, 4);
        handleLayer.cornerRadius = 2;
        [barsLayer addSublayer:handleLayer];
      }
    }
    if (darkStatus == darkStatusIcons && darkNav == darkHandle) return;
    darkStatusIcons = darkStatus;
    darkHandle = darkNav;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    styleBars();
    [CATransaction commit];
  });
}

// ---------------------------------------------------------------- zoom

// Window size only: the app keeps its pixels, the layer scales them, and input divides by the same factor.
// 0 = actual size (as opened), 1 = fit the screen's height, 2 = zoom in, 3 = zoom out.
static float openScale;
static void layoutAfterZoom(void);
static void setZoom(int how) {
  NSWindow *win = NSApp.windows.firstObject;
  if (!win) return;
  if (!openScale) openScale = deviceScale;
  float s = deviceScale;
  NSRect visible = win.screen.visibleFrame;
  CGFloat titleBar = win.frame.size.height - [win contentRectForFrameRect:win.frame].size.height;
  switch (how) {
    case 0: s = openScale; break;
    case 1: s = MAX(devH / (visible.size.height - titleBar), devW / visible.size.width); break;
    case 2: s = deviceScale / 1.25f; break;
    case 3: s = deviceScale * 1.25f; break;
  }
  s = MIN(MAX(s, openScale / 2.5f), openScale * 4);
  deviceScale = s;
  NSRect content = [win contentRectForFrameRect:win.frame];
  NSSize size = NSMakeSize(devW / s, devH / s);
  content.origin.y += content.size.height - size.height;
  content.size = size;
  NSRect frame = [win frameRectForContentRect:content];
  // Keep it on screen.
  if (NSMinY(frame) < NSMinY(visible)) frame.origin.y = NSMinY(visible);
  if (NSMaxY(frame) > NSMaxY(visible)) frame.origin.y = NSMaxY(visible) - frame.size.height;
  [win setFrame:frame display:YES animate:NO];
  layoutAfterZoom();
}

JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeZoom(JNIEnv *env, jclass c, jint how) {
  onMain(^{ setZoom(how); });
}

// ---------------------------------------------------------------- rotation

// New display size: new IOSurfaces (the old ones are released once Core Animation lets go of them), a window of
// the new shape, and overlays laid out again (the Java side re-sends the system bars).
JNIEXPORT void JNICALL Java_dev_apksim_SimWindow_nativeResize(JNIEnv *env, jclass c, jint w, jint h) {
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
    NSWindow *win = NSApp.windows.firstObject;
    NSRect frame = win.frame;
    NSSize size = NSMakeSize(w / deviceScale, h / deviceScale);
    NSRect content = [win contentRectForFrameRect:frame];
    // Keep the window's top left corner where it was.
    content.origin.y += content.size.height - size.height;
    content.size = size;
    [win setFrame:[win frameRectForContentRect:content] display:YES animate:NO];
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

// The overlays are laid out in points: rebuild them for the new window size (the Java side re-sends the bars).
static void layoutAfterZoom(void) {
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  frameLayer.frame = NSMakeRect(0, 0, devW / deviceScale, devH / deviceScale);
  [barsLayer removeFromSuperlayer];
  barsLayer = nil;
  darkStatusIcons = darkHandle = -1;
  [clockTimer invalidate];
  [bannerLayer removeFromSuperlayer];
  bannerLayer = nil;
  [CATransaction commit];
  buildKeyboard();  // laid out in points too
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
