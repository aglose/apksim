// The Mac window's fold animation (simwindow.m "fold animation"): the layers of a folding device closing or opening,
// built from two pictures of it (open and closed, each lit and with its screen off) and played by Core Animation.
// Included by simwindow.m, and by scripts/fold-frames.m, which renders the frames from PNGs with no app running.
//
// Three ways of folding (Fold.Hinge): BOOK, one hinge down the middle, the left half swinging over the right with the
// cover display on its back; CLAMSHELL, one hinge across the middle, the bottom half folding up onto the top one and the
// closed device then turned over, as a hand does, to show the cover display on the top half's back; TRIFOLD, two
// hinges, the left panel folding onto the middle one and then the right over both, the cover display on its back.
// Every property follows one hinge angle per hinge, sampled over the run, as a hand moves a real device: the swing
// starts from rest, is carried by the hand at a near-even pace, and in its last 25 degrees the magnets take it shut
// (a snap, then a settle of under a degree); opening breaks the magnets with a small pop, swings, and the flat detent
// takes the last 12 degrees. Faces are lit by their angle to a light above and to the left of the viewer (a darker face
// as it turns edge on, a glint as the glass passes the light's mirror angle), the faces near a closing hinge darken as
// they close in on each other, the screens go dark and light up as a device's do (the inner one dark just before it
// shuts and lit soon after it opens, the cover one lit once shut and dark as it opens), and the edges have the panels'
// real thickness.

#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#include <math.h>

enum { FOLD_BOOK = 0, FOLD_CLAMSHELL = 1, FOLD_TRIFOLD = 2 };

typedef struct {
  int hinge;             // FOLD_*
  CGFloat thickness;     // one panel's thickness against the open device's height (natural orientation)
  CGImageRef openLit, openDark, closedLit, closedDark;  // the device as drawn, its screen on and off
  CGRect openRect, closedRect;  // where the open and the closed device are, in the parent's coordinates (y up)
  CGRect openScreen;     // the open picture's screen, as fractions of the picture, top down
  BOOL closing;
  double seconds;
} FoldSpec;

// A pose: how far each hinge is folded (0 flat, 1 shut), the closed device's turn (0..pi), how far it has moved to
// where the closed device is (0..1), how far it is lifted towards the viewer (fraction of its height), and the screens.
typedef struct {
  double f1, f2, yaw, move, lift, innerLit, coverLit;
} FoldPose;

static double foldClamp(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
static double foldRamp(double x, double a, double b) { return foldClamp((x - a) / (b - a)); }
static double foldSmoother(double x) {
  x = foldClamp(x);
  return x * x * x * (x * (6 * x - 15) + 10);
}

// A hand closing a hinge, v in 0..1 -> folded 0..1: from rest, an even swing, the magnets' snap, a settle.
static double foldCloseCurve(double v) {
  v = foldClamp(v);
  const double k = 0.85 * M_PI, norm = 1 - cos(k);
  if (v < 0.80) return 0.86 * (1 - cos(k * v / 0.80)) / norm;
  if (v < 0.90) {
    double x = (v - 0.80) / 0.10;
    return 0.86 + 0.14 * (0.49 * x + 0.51 * x * x);  // speeding up into the stop, from the hand's pace
  }
  double x = (v - 0.90) / 0.10;
  return 1 - 0.012 * sin(M_PI * x) * (1 - x);  // it bounces off the stop by a fraction of a degree
}

// A hand opening a hinge, v in 0..1 -> open 0..1: the magnets let go with a pop, the swing, the flat detent, a settle.
static double foldOpenCurve(double v) {
  v = foldClamp(v);
  const double k = 0.85 * M_PI, norm = 1 - cos(k);
  if (v < 0.10) {
    double x = v / 0.10;
    return 0.06 * (1 - (1 - x) * (1 - x));
  }
  if (v < 0.84) return 0.06 + 0.86 * (1 - cos(k * (v - 0.10) / 0.74)) / norm;
  if (v < 0.93) {
    double x = (v - 0.84) / 0.09;
    return 0.92 + 0.08 * (0.5 * x + 0.5 * x * x);
  }
  double x = (v - 0.93) / 0.07;
  return 1 - 0.010 * sin(M_PI * x) * (1 - x);
}

// The inner screen is lit while the hinge is open past ~25 degrees, and goes dark under ~11.
static double foldInnerLit(double open) { return foldSmoother(foldRamp(open, 0.06, 0.14)); }

static FoldPose foldPoseAt(int hinge, BOOL closing, double u) {
  FoldPose p = {0};
  if (hinge == FOLD_CLAMSHELL) {
    if (closing) {
      p.f1 = foldCloseCurve(u / 0.55);
      double turn = foldSmoother(foldRamp(u, 0.58, 1));
      p.yaw = M_PI * turn;
      p.move = turn;
      p.lift = 0.10 * sin(M_PI * turn);
      p.innerLit = foldInnerLit(1 - p.f1);
      p.coverLit = foldSmoother(foldRamp(u, 0.56, 0.66));
    } else {
      double turn = 1 - foldSmoother(foldRamp(u, 0, 0.40));
      p.yaw = M_PI * turn;
      p.move = turn;
      p.lift = 0.10 * sin(M_PI * turn);
      double open = u < 0.43 ? 0 : foldOpenCurve((u - 0.43) / 0.57);
      p.f1 = 1 - open;
      p.coverLit = 1 - foldRamp(u, 0.43, 0.48);
      p.innerLit = foldSmoother(foldRamp(open, 0.12, 0.22));
    }
  } else if (hinge == FOLD_TRIFOLD) {
    if (closing) {
      p.f1 = foldCloseCurve(u / 0.46);
      p.f2 = u < 0.44 ? 0 : foldCloseCurve((u - 0.44) / 0.56);
      p.move = foldSmoother(foldRamp(u, 0.30, 0.95));
      p.innerLit = foldInnerLit(1 - p.f2);
      p.coverLit = foldSmoother(foldRamp(u, 0.93, 1));
    } else {
      double openR = foldOpenCurve(u / 0.52);
      p.f2 = 1 - openR;
      p.f1 = u < 0.50 ? 1 : 1 - foldOpenCurve((u - 0.50) / 0.50);
      p.move = 1 - foldSmoother(foldRamp(u, 0, 0.70));
      p.coverLit = 1 - foldRamp(u, 0, 0.05);
      p.innerLit = foldSmoother(foldRamp(openR, 0.12, 0.22));
    }
  } else {
    if (closing) {
      p.f1 = foldCloseCurve(u);
      p.move = foldSmoother(foldRamp(u, 0.05, 0.90));
      p.innerLit = foldInnerLit(1 - p.f1);
      p.coverLit = foldSmoother(foldRamp(u, 0.92, 1));
    } else {
      double open = foldOpenCurve(u);
      p.f1 = 1 - open;
      p.move = 1 - foldSmoother(foldRamp(u, 0, 0.85));
      p.coverLit = 1 - foldRamp(u, 0, 0.06);
      p.innerLit = foldSmoother(foldRamp(open, 0.12, 0.22));
    }
  }
  return p;
}

// ---------------------------------------------------------------- light

typedef struct { double x, y, z; } FoldVec;

static FoldVec foldYaw(FoldVec n, double a) {  // as CATransform3DMakeRotation(a, 0, 1, 0)
  return (FoldVec){n.x * cos(a) + n.z * sin(a), n.y, -n.x * sin(a) + n.z * cos(a)};
}

// A face's darkening (black, 0..1) and glint (white, 0..1) for its normal, against a face lying flat towards the viewer.
static void foldLight(FoldVec n, double *dark, double *glint) {
  const FoldVec l = {-0.30, 0.45, 0.84};  // above and to the left of the viewer (normalised below)
  double ll = sqrt(l.x * l.x + l.y * l.y + l.z * l.z);
  FoldVec L = {l.x / ll, l.y / ll, l.z / ll};
  double diffuse = fmax(0, n.x * L.x + n.y * L.y + n.z * L.z), flat = L.z;
  *dark = foldClamp((flat - diffuse) / flat) * 0.78;
  FoldVec h = {L.x, L.y, L.z + 1};
  double hl = sqrt(h.x * h.x + h.y * h.y + h.z * h.z);
  double nh = fmax(0, (n.x * h.x + n.y * h.y + n.z * h.z) / hl), flatNh = h.z / hl;
  double spec = pow(nh, 48) - pow(flatNh, 48);
  *glint = foldClamp(spec * 2.2) * 0.42 + foldClamp((diffuse - flat) / (1 - flat)) * 0.12;
}

// ---------------------------------------------------------------- pictures

// A picture mirrored left to right (horizontal) or top to bottom: the back of a panel has its front's outline mirrored.
static CGImageRef foldMirrored(CGImageRef img, BOOL horizontal) {
  size_t w = CGImageGetWidth(img), h = CGImageGetHeight(img);
  CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  CGContextRef ctx = CGBitmapContextCreate(NULL, w, h, 8, 0, cs, kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
  CGColorSpaceRelease(cs);
  if (!ctx) return CGImageRetain(img);
  if (horizontal) {
    CGContextTranslateCTM(ctx, w, 0);
    CGContextScaleCTM(ctx, -1, 1);
  } else {
    CGContextTranslateCTM(ctx, 0, h);
    CGContextScaleCTM(ctx, 1, -1);
  }
  CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), img);
  CGImageRef out = CGBitmapContextCreateImage(ctx);
  CGContextRelease(ctx);
  return out;
}

// How far in from an edge of the picture the device starts, along its middle, as a fraction of the picture (a skin's
// frame leaves a margin for its buttons). side: 0 left, 1 right, 2 top, 3 bottom. at: where along the edge (0..1, top
// down or left to right) to look.
static CGFloat foldInset(CGImageRef img, int side, CGFloat at) {
  size_t w = CGImageGetWidth(img), h = CGImageGetHeight(img);
  BOOL row = side < 2;
  size_t n = row ? w : h;
  if (n == 0 || n > 16384) return 0;
  CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  CGContextRef ctx = CGBitmapContextCreate(NULL, row ? w : 1, row ? 1 : h, 8, 0, cs, (CGBitmapInfo)kCGImageAlphaPremultipliedLast);
  CGColorSpaceRelease(cs);
  if (!ctx) return 0;
  // Draw the picture so that the wanted row (or column) lands on the context's one row (column); y is bottom up.
  if (row) CGContextDrawImage(ctx, CGRectMake(0, -(CGFloat)h * (1 - at) + 0.5, w, h), img);
  else CGContextDrawImage(ctx, CGRectMake(-(CGFloat)w * at + 0.5, 0, w, h), img);
  uint8_t *px = CGBitmapContextGetData(ctx);
  size_t stride = CGBitmapContextGetBytesPerRow(ctx);
  size_t i = 0;
  for (; i < n / 4; i++) {
    size_t k = side == 0 || side == 2 ? i : n - 1 - i;  // a column's first pixel in memory is its top
    uint8_t a = row ? px[k * 4 + 3] : px[k * stride + 3];
    if (a >= 128) break;
  }
  CGContextRelease(ctx);
  return (CGFloat)i / n;
}

static BOOL foldRoundCorner(CGImageRef img) {
  CGFloat top = foldInset(img, 0, 0.004), mid = foldInset(img, 0, 0.5);
  return top > mid + 0.004 || top >= 0.24;
}

// ---------------------------------------------------------------- layers

static CAKeyframeAnimation *foldKeys(NSString *key, NSArray *values, CFTimeInterval t0, double seconds) {
  CAKeyframeAnimation *a = [CAKeyframeAnimation animationWithKeyPath:key];
  a.values = values;
  a.calculationMode = kCAAnimationLinear;
  a.beginTime = t0;
  a.duration = seconds;
  a.fillMode = kCAFillModeBoth;
  a.removedOnCompletion = NO;
  return a;
}

// A picture's slice in device coordinates (y up, origin at the open device's centre) as a layer's contentsRect, which
// on the Mac is bottom up in the picture as the layer's own coordinates are.
static CGRect foldSlice(CGRect r, CGFloat W, CGFloat H) {
  return CGRectMake((r.origin.x + W / 2) / W, (r.origin.y + H / 2) / H, r.size.width / W, r.size.height / H);
}

static CALayer *foldImageLayer(CGRect frame, CGImageRef img, CGRect contentsRect, unsigned int edges) {
  CALayer *l = [CALayer layer];
  l.frame = frame;
  l.contents = (__bridge id)img;
  l.contentsRect = contentsRect;
  l.contentsGravity = kCAGravityResize;
  l.edgeAntialiasingMask = edges;  // not along a hinge: no seam between two panels
  return l;
}

// An overlay in a face's own outline (the picture's alpha): a darkening, a glint or a shadow.
static void foldMaskWith(CALayer *overlay, CGImageRef img, CGRect contentsRect) {
  CALayer *m = [CALayer layer];
  m.frame = overlay.bounds;
  m.contents = (__bridge id)img;
  m.contentsRect = contentsRect;
  m.contentsGravity = kCAGravityResize;
  overlay.edgeAntialiasingMask = 0;  // its face's edges are antialiased already
  overlay.mask = m;
}

typedef struct {
  CALayer *dark, *glint;
} FoldLit;

// A face: its picture with the screen off, the lit picture over it (litKey: which pose's screen), and the light's
// darkening and glint, all in its outline.
static FoldLit foldFace(CALayer *parent, CGRect frame, CGImageRef dark, CGImageRef lit, CGRect contentsRect, unsigned int edges,
                        NSMutableArray *litLayers) {
  CALayer *base = foldImageLayer(frame, dark ?: lit, contentsRect, edges);
  base.doubleSided = NO;
  [parent addSublayer:base];
  if (dark && lit) {
    CALayer *on = foldImageLayer(base.bounds, lit, contentsRect, edges);
    [base addSublayer:on];
    [litLayers addObject:on];
  } else {
    [litLayers addObject:[NSNull null]];
  }
  FoldLit f;
  f.dark = [CALayer layer];
  f.dark.frame = base.bounds;
  f.dark.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
  f.dark.opacity = 0;
  foldMaskWith(f.dark, lit ?: dark, contentsRect);
  [base addSublayer:f.dark];
  CAGradientLayer *g = [CAGradientLayer layer];
  g.frame = base.bounds;
  g.startPoint = CGPointMake(0, 1);
  g.endPoint = CGPointMake(1, 0);
  g.colors = @[(id)[NSColor colorWithWhite:1 alpha:0.2].CGColor, (id)[NSColor colorWithWhite:1 alpha:1].CGColor,
               (id)[NSColor colorWithWhite:1 alpha:0.15].CGColor];
  g.locations = @[@0.15, @0.45, @0.85];
  g.opacity = 0;
  foldMaskWith(g, lit ?: dark, contentsRect);
  [base addSublayer:g];
  f.glint = g;
  return f;
}

// The darkening of a face near a hinge as the panels close in on each other: dark at the hinge, fading over a width
// that grows as the hinge closes. from: the hinge's side of the face, in the gradient's unit square.
static CAGradientLayer *foldNearShade(CALayer *face, CGImageRef img, CGRect contentsRect, CGPoint from, CGPoint to) {
  CAGradientLayer *g = [CAGradientLayer layer];
  g.frame = face.bounds;
  g.startPoint = from;
  g.endPoint = to;
  g.colors = @[(id)[NSColor colorWithWhite:0 alpha:1].CGColor, (id)[NSColor colorWithWhite:0 alpha:0].CGColor];
  g.locations = @[@0, @0.01];
  g.opacity = 0;
  foldMaskWith(g, img, contentsRect);
  [face addSublayer:g];
  return g;
}

// The metal edge of a panel, between its front and its back, seen while it is on its way over.
static CAGradientLayer *foldRail(CGSize size, BOOL alongY) {
  CAGradientLayer *r = [CAGradientLayer layer];
  r.bounds = CGRectMake(0, 0, size.width, size.height);
  r.startPoint = alongY ? CGPointMake(0, 0.5) : CGPointMake(0.5, 0);
  r.endPoint = alongY ? CGPointMake(1, 0.5) : CGPointMake(0.5, 1);
  r.colors = @[(id)[NSColor colorWithSRGBRed:0.30 green:0.31 blue:0.33 alpha:1].CGColor,
               (id)[NSColor colorWithSRGBRed:0.74 green:0.75 blue:0.78 alpha:1].CGColor,
               (id)[NSColor colorWithSRGBRed:0.52 green:0.53 blue:0.56 alpha:1].CGColor,
               (id)[NSColor colorWithSRGBRed:0.24 green:0.25 blue:0.27 alpha:1].CGColor];
  r.locations = @[@0, @0.35, @0.65, @1];
  r.cornerRadius = MIN(size.width, size.height) / 2;
  r.doubleSided = NO;
  return r;
}

// A panel's back with no screen, in the front's outline mirrored (img is the mirrored picture): its aluminium rim and
// the back glass inside it.
static CALayer *foldPlainBack(CGRect frame, CGImageRef mirroredOutline, CGRect contentsRect, CGFloat corner, CGFloat rim) {
  CAGradientLayer *b = [CAGradientLayer layer];
  b.frame = frame;
  b.startPoint = CGPointMake(0, 1);
  b.endPoint = CGPointMake(1, 0);
  b.colors = @[(id)[NSColor colorWithSRGBRed:0.62 green:0.63 blue:0.66 alpha:1].CGColor,
               (id)[NSColor colorWithSRGBRed:0.36 green:0.37 blue:0.40 alpha:1].CGColor];
  b.doubleSided = NO;
  foldMaskWith(b, mirroredOutline, contentsRect);
  b.edgeAntialiasingMask = kCALayerLeftEdge | kCALayerRightEdge | kCALayerBottomEdge | kCALayerTopEdge;
  CAGradientLayer *glass = [CAGradientLayer layer];
  glass.frame = CGRectInset(b.bounds, rim, rim);
  glass.cornerRadius = MAX(0, corner - rim);
  glass.startPoint = CGPointMake(0, 1);
  glass.endPoint = CGPointMake(1, 0);
  glass.colors = @[(id)[NSColor colorWithSRGBRed:0.25 green:0.27 blue:0.31 alpha:1].CGColor,
                   (id)[NSColor colorWithSRGBRed:0.12 green:0.13 blue:0.16 alpha:1].CGColor,
                   (id)[NSColor colorWithSRGBRed:0.16 green:0.17 blue:0.20 alpha:1].CGColor];
  glass.locations = @[@0, @0.55, @1];
  [b addSublayer:glass];
  return b;
}

static CATransform3D foldAbout(CATransform3D r, CGFloat x, CGFloat y, CGFloat z) {
  return CATransform3DConcat(CATransform3DConcat(CATransform3DMakeTranslation(-x, -y, -z), r), CATransform3DMakeTranslation(x, y, z));
}

// The fold's layers for a parent in which the device rectangles are offset by -origin, played from t0.
static CALayer *foldScene(const FoldSpec *f, CGPoint origin, CFTimeInterval t0) {
  const int N = 180;  // poses sampled over the run
  CGRect openRect = f->openRect, closedRect = f->closedRect;
  CGFloat W = openRect.size.width, H = openRect.size.height;
  CGFloat t = f->thickness * H;
  int hinge = f->hinge;
  BOOL closing = f->closing;
  double seconds = f->seconds;

  CALayer *stage = [CALayer layer];
  CGRect all = CGRectUnion(openRect, closedRect);
  stage.frame = CGRectOffset(all, -origin.x, -origin.y);
  CATransform3D perspective = CATransform3DIdentity;
  perspective.m34 = -1.0 / (2.4 * MAX(W, H));
  stage.sublayerTransform = perspective;
  CGPoint so = all.origin;

  CATransformLayer *device = [CATransformLayer layer];
  device.bounds = CGRectMake(-W / 2, -H / 2, W, H);
  device.position = CGPointMake(CGRectGetMidX(openRect) - so.x, CGRectGetMidY(openRect) - so.y);
  [stage addSublayer:device];

  // Where the hinges are, in device coordinates, from the open picture's screen.
  CGRect sc = f->openScreen;
  CGFloat hx1, hx2 = 0, hy = 0;
  if (hinge == FOLD_TRIFOLD) {
    hx1 = -W / 2 + W * (sc.origin.x + sc.size.width / 3);
    hx2 = -W / 2 + W * (sc.origin.x + sc.size.width * 2 / 3);
  } else {
    hx1 = -W / 2 + W * (sc.origin.x + sc.size.width / 2);
    hy = H / 2 - H * (sc.origin.y + sc.size.height / 2);
  }
  BOOL rounded = foldRoundCorner(f->openLit);
  CGFloat corner = rounded ? 0.045 * H : 0;  // a body's corner radius: about 7 mm of 158

  CGImageRef mirrorX = foldMirrored(f->openLit, YES), mirrorY = foldMirrored(f->openLit, NO);
  NSMutableArray *innerLit = [NSMutableArray array], *coverLit = [NSMutableArray array];
  const unsigned int ALL = kCALayerLeftEdge | kCALayerRightEdge | kCALayerBottomEdge | kCALayerTopEdge;

  // Panels: each a transform layer turning about its hinge, holding its faces in device coordinates.
  CATransformLayer *fixedPanel = [CATransformLayer layer], *moving1 = [CATransformLayer layer], *moving2 = nil;
  for (CATransformLayer *p in @[fixedPanel, moving1]) {
    p.bounds = device.bounds;
    p.position = CGPointZero;
    [device addSublayer:p];
  }
  // Stack: where the closed device ends up in device coordinates (the cover picture's face, once folded), and the
  // height of its top face above the screen's plane.
  CGRect stack;
  CGFloat stackTop;
  NSMutableArray *faces = [NSMutableArray array];  // light per face, matching foldFaceNormal's numbering
  NSMutableArray<CAGradientLayer *> *nearShades = [NSMutableArray array];  // [face shade, hinge index] pairs below
  NSMutableArray<NSNumber *> *nearHinge = [NSMutableArray array];
  NSMutableArray<CALayer *> *rails = [NSMutableArray array];  // the clamshell's stack edges, shown once it is folded

  if (hinge == FOLD_BOOK) {
    CGRect left = CGRectMake(-W / 2, -H / 2, hx1 + W / 2, H), right = CGRectMake(hx1, -H / 2, W / 2 - hx1, H);
    // 0: the right half's screen (fixed); 1: the left half's screen; 2: the left half's back (the cover display).
    FoldLit rf = foldFace(fixedPanel, right, f->openDark, f->openLit, foldSlice(right, W, H), ALL & ~kCALayerLeftEdge, innerLit);
    [faces addObject:rf.dark]; [faces addObject:rf.glint];
    FoldLit lf = foldFace(moving1, left, f->openDark, f->openLit, foldSlice(left, W, H), ALL & ~kCALayerRightEdge, innerLit);
    [faces addObject:lf.dark]; [faces addObject:lf.glint];
    CALayer *backHolder = [CALayer layer];
    backHolder.frame = left;
    backHolder.zPosition = -t;
    backHolder.transform = CATransform3DMakeRotation(M_PI, 0, 1, 0);
    backHolder.doubleSided = NO;
    [moving1 addSublayer:backHolder];
    FoldLit bf = foldFace(backHolder, backHolder.bounds, f->closedDark, f->closedLit, CGRectMake(0, 0, 1, 1), ALL, coverLit);
    [faces addObject:bf.dark]; [faces addObject:bf.glint];
    [nearShades addObject:foldNearShade(fixedPanel.sublayers[0], f->openLit, foldSlice(right, W, H), CGPointMake(0, 0.5), CGPointMake(1, 0.5))];
    [nearShades addObject:foldNearShade(moving1.sublayers[0], f->openLit, foldSlice(left, W, H), CGPointMake(1, 0.5), CGPointMake(0, 0.5))];
    [nearHinge addObject:@1]; [nearHinge addObject:@1];
    CGFloat inset = foldInset(f->openLit, 0, 0.5) * W;
    CAGradientLayer *rail = foldRail(CGSizeMake(t, H - 2 * corner), YES);
    rail.position = CGPointMake(-W / 2 + inset, 0);
    rail.zPosition = -t / 2;
    rail.transform = CATransform3DMakeRotation(-M_PI_2, 0, 1, 0);
    [moving1 addSublayer:rail];
    stack = CGRectMake(hx1, -H / 2, hx1 + W / 2, H);
    stackTop = t;
  } else if (hinge == FOLD_CLAMSHELL) {
    CGRect top = CGRectMake(-W / 2, hy, W, H / 2 - hy), bottom = CGRectMake(-W / 2, -H / 2, W, hy + H / 2);
    // 0: the top half's screen (fixed); 1: the bottom half's screen; 2: the bottom half's back; 3: the top half's back
    // (the cover display).
    FoldLit tf = foldFace(fixedPanel, top, f->openDark, f->openLit, foldSlice(top, W, H), ALL & ~kCALayerBottomEdge, innerLit);
    [faces addObject:tf.dark]; [faces addObject:tf.glint];
    FoldLit bf = foldFace(moving1, bottom, f->openDark, f->openLit, foldSlice(bottom, W, H), ALL & ~kCALayerTopEdge, innerLit);
    [faces addObject:bf.dark]; [faces addObject:bf.glint];
    // The bottom half's back: its glass, in its outline mirrored top to bottom, a thickness behind it.
    CGRect mb = foldSlice(bottom, W, H);
    mb.origin.y = 1 - mb.origin.y - mb.size.height;
    CALayer *bottomBack = foldPlainBack(bottom, mirrorY, mb, corner, 0.004 * H + 1);
    bottomBack.zPosition = -t;
    bottomBack.transform = CATransform3DMakeRotation(M_PI, 1, 0, 0);
    [moving1 addSublayer:bottomBack];
    [faces addObject:bottomBack]; [faces addObject:[NSNull null]];
    CALayer *coverHolder = [CALayer layer];
    coverHolder.frame = top;
    coverHolder.zPosition = -t;
    coverHolder.transform = CATransform3DMakeRotation(M_PI, 0, 1, 0);
    coverHolder.doubleSided = NO;
    [fixedPanel addSublayer:coverHolder];
    FoldLit cf = foldFace(coverHolder, coverHolder.bounds, f->closedDark, f->closedLit, CGRectMake(0, 0, 1, 1), ALL, coverLit);
    [faces addObject:cf.dark]; [faces addObject:cf.glint];
    [nearShades addObject:foldNearShade(fixedPanel.sublayers[0], f->openLit, foldSlice(top, W, H), CGPointMake(0.5, 0), CGPointMake(0.5, 1))];
    [nearShades addObject:foldNearShade(moving1.sublayers[0], f->openLit, foldSlice(bottom, W, H), CGPointMake(0.5, 1), CGPointMake(0.5, 0))];
    [nearHinge addObject:@1]; [nearHinge addObject:@1];
    // The bottom half's outer edge, and the closed device's sides for its turn (two panels thick).
    CGFloat insetB = foldInset(f->openLit, 3, 0.5) * H;
    CAGradientLayer *rail = foldRail(CGSizeMake(W - 2 * corner, t), NO);
    rail.position = CGPointMake(0, -H / 2 + insetB);
    rail.zPosition = -t / 2;
    rail.transform = CATransform3DMakeRotation(M_PI_2, 1, 0, 0);
    [moving1 addSublayer:rail];
    CGFloat at = (sc.origin.y + sc.size.height * 0.25);
    CGFloat insetL = foldInset(f->openLit, 0, at) * W, insetR = foldInset(f->openLit, 1, at) * W;
    CGFloat railH = top.size.height - 2 * corner;
    CAGradientLayer *l = foldRail(CGSizeMake(2 * t, railH), YES), *r = foldRail(CGSizeMake(2 * t, railH), YES);
    l.position = CGPointMake(-W / 2 + insetL, CGRectGetMidY(top));
    l.transform = CATransform3DMakeRotation(-M_PI_2, 0, 1, 0);
    r.position = CGPointMake(W / 2 - insetR, CGRectGetMidY(top));
    r.transform = CATransform3DMakeRotation(M_PI_2, 0, 1, 0);
    [fixedPanel addSublayer:l];
    [fixedPanel addSublayer:r];
    [rails addObject:l];
    [rails addObject:r];
    stack = top;
    stackTop = t;
  } else {
    moving2 = [CATransformLayer layer];
    moving2.bounds = device.bounds;
    moving2.position = CGPointZero;
    [device addSublayer:moving2];
    CGRect left = CGRectMake(-W / 2, -H / 2, hx1 + W / 2, H), mid = CGRectMake(hx1, -H / 2, hx2 - hx1, H);
    CGRect right = CGRectMake(hx2, -H / 2, W / 2 - hx2, H);
    // 0: the middle panel's screen (fixed); 1: the left's screen; 2: the left's back; 3: the right's screen; 4: the
    // right's back (the cover display).
    FoldLit mf = foldFace(fixedPanel, mid, f->openDark, f->openLit, foldSlice(mid, W, H), kCALayerTopEdge | kCALayerBottomEdge, innerLit);
    [faces addObject:mf.dark]; [faces addObject:mf.glint];
    FoldLit lf = foldFace(moving1, left, f->openDark, f->openLit, foldSlice(left, W, H), ALL & ~kCALayerRightEdge, innerLit);
    [faces addObject:lf.dark]; [faces addObject:lf.glint];
    CGRect ml = foldSlice(left, W, H);
    ml.origin.x = 1 - ml.origin.x - ml.size.width;
    CALayer *leftBack = foldPlainBack(left, mirrorX, ml, corner, 0.004 * H + 1);
    leftBack.zPosition = -t;
    leftBack.transform = CATransform3DMakeRotation(M_PI, 0, 1, 0);
    [moving1 addSublayer:leftBack];
    [faces addObject:leftBack]; [faces addObject:[NSNull null]];
    FoldLit rf = foldFace(moving2, right, f->openDark, f->openLit, foldSlice(right, W, H), ALL & ~kCALayerLeftEdge, innerLit);
    [faces addObject:rf.dark]; [faces addObject:rf.glint];
    CALayer *coverHolder = [CALayer layer];
    coverHolder.frame = right;
    coverHolder.zPosition = -t;
    coverHolder.transform = CATransform3DMakeRotation(M_PI, 0, 1, 0);
    coverHolder.doubleSided = NO;
    [moving2 addSublayer:coverHolder];
    FoldLit cf = foldFace(coverHolder, coverHolder.bounds, f->closedDark, f->closedLit, CGRectMake(0, 0, 1, 1), ALL, coverLit);
    [faces addObject:cf.dark]; [faces addObject:cf.glint];
    CALayer *midFace = fixedPanel.sublayers[0];
    [nearShades addObject:foldNearShade(midFace, f->openLit, foldSlice(mid, W, H), CGPointMake(0, 0.5), CGPointMake(1, 0.5))];
    [nearShades addObject:foldNearShade(moving1.sublayers[0], f->openLit, foldSlice(left, W, H), CGPointMake(1, 0.5), CGPointMake(0, 0.5))];
    [nearShades addObject:foldNearShade(midFace, f->openLit, foldSlice(mid, W, H), CGPointMake(1, 0.5), CGPointMake(0, 0.5))];
    [nearShades addObject:foldNearShade(moving2.sublayers[0], f->openLit, foldSlice(right, W, H), CGPointMake(0, 0.5), CGPointMake(1, 0.5))];
    [nearHinge addObject:@1]; [nearHinge addObject:@1]; [nearHinge addObject:@2]; [nearHinge addObject:@2];
    CGFloat insetL = foldInset(f->openLit, 0, 0.5) * W, insetR = foldInset(f->openLit, 1, 0.5) * W;
    CAGradientLayer *lr = foldRail(CGSizeMake(t, H - 2 * corner), YES), *rr = foldRail(CGSizeMake(t, H - 2 * corner), YES);
    lr.position = CGPointMake(-W / 2 + insetL, 0);
    lr.zPosition = -t / 2;
    lr.transform = CATransform3DMakeRotation(-M_PI_2, 0, 1, 0);
    [moving1 addSublayer:lr];
    rr.position = CGPointMake(W / 2 - insetR, 0);
    rr.zPosition = -t / 2;
    rr.transform = CATransform3DMakeRotation(M_PI_2, 0, 1, 0);
    [moving2 addSublayer:rr];
    stack = CGRectMake(2 * hx2 - W / 2, -H / 2, W / 2 - hx2, H);
    stackTop = 2 * t;
  }
  CGImageRelease(mirrorX);
  CGImageRelease(mirrorY);

  // The inner screens, which face each other once shut: hidden then, or they would show through the closed picture's
  // transparent margin along the hinge. Each with the hinge whose closing presses it (2: the tri-fold's right one).
  NSMutableArray<CALayer *> *inner = [NSMutableArray arrayWithObjects:fixedPanel.sublayers[0], moving1.sublayers[0], nil];
  NSMutableArray<NSNumber *> *innerHinge = [NSMutableArray arrayWithObjects:@(hinge == FOLD_TRIFOLD ? 2 : 1), @1, nil];
  if (moving2) {
    [inner addObject:moving2.sublayers[0]];
    [innerHinge addObject:@2];
  }
  NSMutableArray *shutA = [NSMutableArray array], *shut2A = [NSMutableArray array];

  // Sample the run.
  NSMutableArray *deviceT = [NSMutableArray array], *m1T = [NSMutableArray array], *m2T = [NSMutableArray array];
  NSMutableArray *innerA = [NSMutableArray array], *coverA = [NSMutableArray array], *railsA = [NSMutableArray array];
  NSUInteger nf = faces.count / 2;
  NSMutableArray *darkA[8], *glintA[8];
  for (NSUInteger i = 0; i < nf; i++) {
    darkA[i] = [NSMutableArray array];
    glintA[i] = [NSMutableArray array];
  }
  NSMutableArray *nearLoc[8], *nearOp[8];
  for (NSUInteger i = 0; i < nearShades.count; i++) {
    nearLoc[i] = [NSMutableArray array];
    nearOp[i] = [NSMutableArray array];
  }
  CGPoint scenter = CGPointMake(CGRectGetMidX(stack), CGRectGetMidY(stack));
  CGPoint target = CGPointMake(CGRectGetMidX(closedRect) - so.x - device.position.x, CGRectGetMidY(closedRect) - so.y - device.position.y);
  CGFloat sx = closedRect.size.width / stack.size.width, sy = closedRect.size.height / stack.size.height, sz = (sx + sy) / 2;
  for (int i = 0; i < N; i++) {
    double u = (double)i / (N - 1);
    FoldPose p = foldPoseAt(hinge, closing, u);
    double m = p.move;
    CATransform3D d = CATransform3DMakeTranslation(-scenter.x, -scenter.y, 0);
    d = CATransform3DConcat(d, CATransform3DMakeScale(1 + m * (sx - 1), 1 + m * (sy - 1), 1 + m * (sz - 1)));
    d = CATransform3DConcat(d, CATransform3DMakeRotation(p.yaw, 0, 1, 0));
    CGPoint at = CGPointMake(scenter.x + m * (target.x - scenter.x), scenter.y + m * (target.y - scenter.y));
    d = CATransform3DConcat(d, CATransform3DMakeTranslation(at.x, at.y, p.lift * H - m * stackTop * sz));
    [deviceT addObject:[NSValue valueWithCATransform3D:d]];
    double a1 = M_PI * p.f1, a2 = M_PI * p.f2;
    CATransform3D r1, r2 = CATransform3DIdentity;
    if (hinge == FOLD_CLAMSHELL) r1 = foldAbout(CATransform3DMakeRotation(-a1, 1, 0, 0), 0, hy, 0);
    else r1 = foldAbout(CATransform3DMakeRotation(a1, 0, 1, 0), hx1, 0, 0);
    if (hinge == FOLD_TRIFOLD) r2 = foldAbout(CATransform3DMakeRotation(-a2, 0, 1, 0), hx2, 0, t / 2);
    [m1T addObject:[NSValue valueWithCATransform3D:r1]];
    [m2T addObject:[NSValue valueWithCATransform3D:r2]];
    [innerA addObject:@(p.innerLit)];
    [coverA addObject:@(p.coverLit)];
    [railsA addObject:@(p.f1 > 0.985 ? 1.0 : 0.0)];
    [shutA addObject:@(p.f1 > 0.98 ? 0.0 : 1.0)];  // the settle after the snap stays above 0.98
    [shut2A addObject:@(p.f2 > 0.98 ? 0.0 : 1.0)];
    // Each face's normal, turned by the closed device's turn.
    FoldVec n[5];
    if (hinge == FOLD_BOOK) {
      n[0] = (FoldVec){0, 0, 1};
      n[1] = (FoldVec){sin(a1), 0, cos(a1)};
      n[2] = (FoldVec){-sin(a1), 0, -cos(a1)};
    } else if (hinge == FOLD_CLAMSHELL) {
      n[0] = (FoldVec){0, 0, 1};
      n[1] = (FoldVec){0, sin(a1), cos(a1)};
      n[2] = (FoldVec){0, -sin(a1), -cos(a1)};
      n[3] = (FoldVec){0, 0, -1};
    } else {
      n[0] = (FoldVec){0, 0, 1};
      n[1] = (FoldVec){sin(a1), 0, cos(a1)};
      n[2] = (FoldVec){-sin(a1), 0, -cos(a1)};
      n[3] = (FoldVec){-sin(a2), 0, cos(a2)};
      n[4] = (FoldVec){sin(a2), 0, -cos(a2)};
    }
    for (NSUInteger k = 0; k < nf; k++) {
      double dark, glint;
      foldLight(foldYaw(n[k], p.yaw), &dark, &glint);
      [darkA[k] addObject:@(dark)];
      [glintA[k] addObject:@(glint)];
    }
    for (NSUInteger k = 0; k < nearShades.count; k++) {
      double a = nearHinge[k].intValue == 2 ? a2 : a1;
      double reach = 0.04 + 0.96 * pow((1 - cos(a)) / 2, 1.6);  // how far from the hinge the panels are close
      [nearLoc[k] addObject:@[@0, @(fmax(0.01, reach))]];
      [nearOp[k] addObject:@(0.62 * pow(sin(a / 2), 1.4))];
    }
  }
  [device addAnimation:foldKeys(@"transform", deviceT, t0, seconds) forKey:@"move"];
  device.transform = [(NSValue *)deviceT.firstObject CATransform3DValue];
  [moving1 addAnimation:foldKeys(@"transform", m1T, t0, seconds) forKey:@"fold"];
  moving1.transform = [(NSValue *)m1T.firstObject CATransform3DValue];
  if (moving2) {
    [moving2 addAnimation:foldKeys(@"transform", m2T, t0, seconds) forKey:@"fold"];
    moving2.transform = [(NSValue *)m2T.firstObject CATransform3DValue];
  }
  for (id l in innerLit) {
    if (l == [NSNull null]) continue;
    [(CALayer *)l addAnimation:foldKeys(@"opacity", innerA, t0, seconds) forKey:@"screen"];
    ((CALayer *)l).opacity = [innerA.firstObject floatValue];
  }
  for (id l in coverLit) {
    if (l == [NSNull null]) continue;
    [(CALayer *)l addAnimation:foldKeys(@"opacity", coverA, t0, seconds) forKey:@"screen"];
    ((CALayer *)l).opacity = [coverA.firstObject floatValue];
  }
  for (NSUInteger k = 0; k < inner.count; k++) {
    NSArray *a = innerHinge[k].intValue == 2 ? shut2A : shutA;
    [inner[k] addAnimation:foldKeys(@"opacity", a, t0, seconds) forKey:@"shut"];
    inner[k].opacity = [a.firstObject floatValue];
  }
  for (CALayer *l in rails) {
    [l addAnimation:foldKeys(@"opacity", railsA, t0, seconds) forKey:@"shown"];
    l.opacity = [railsA.firstObject floatValue];
  }
  for (NSUInteger k = 0; k < nf; k++) {
    CALayer *dark = faces[2 * k], *glint = faces[2 * k + 1];
    if ([dark isKindOfClass:CAGradientLayer.class] && (id)glint == [NSNull null]) {
      // A plain back: darkened through a sublayer of its own.
      CALayer *shade = [CALayer layer];
      shade.frame = dark.bounds;
      shade.edgeAntialiasingMask = 0;
      shade.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
      [dark addSublayer:shade];
      [shade addAnimation:foldKeys(@"opacity", darkA[k], t0, seconds) forKey:@"light"];
      shade.opacity = [darkA[k].firstObject floatValue];
      continue;
    }
    [dark addAnimation:foldKeys(@"opacity", darkA[k], t0, seconds) forKey:@"light"];
    dark.opacity = [darkA[k].firstObject floatValue];
    if ((id)glint != [NSNull null]) {
      [glint addAnimation:foldKeys(@"opacity", glintA[k], t0, seconds) forKey:@"light"];
      glint.opacity = [glintA[k].firstObject floatValue];
    }
  }
  for (NSUInteger k = 0; k < nearShades.count; k++) {
    [nearShades[k] addAnimation:foldKeys(@"locations", nearLoc[k], t0, seconds) forKey:@"reach"];
    [nearShades[k] addAnimation:foldKeys(@"opacity", nearOp[k], t0, seconds) forKey:@"shade"];
    nearShades[k].opacity = [nearOp[k].firstObject floatValue];
  }
  return stage;
}

// The frames of a run rendered off screen into DIR as PNGs (fold-closing-00.png ...), on grey: scripts cannot see the
// overlay window. APKSIM_FOLD_FRAMES=DIR in an instance; scripts/fold-frames.m from pictures.
static void foldDump(const FoldSpec *f, const char *dir, int frames) {
  CGRect crop = CGRectIntegral(CGRectInset(CGRectUnion(f->openRect, f->closedRect), -f->openRect.size.width * 0.35, -f->openRect.size.height * 0.25));
  size_t w = crop.size.width, h = crop.size.height;
  id<MTLDevice> gpu = MTLCreateSystemDefaultDevice();
  if (!gpu || w == 0 || h == 0) return;
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  d.storageMode = MTLStorageModeShared;
  id<MTLTexture> tex = [gpu newTextureWithDescriptor:d];
  id<MTLCommandQueue> queue = [gpu newCommandQueue];
  CARenderer *r = [CARenderer rendererWithMTLTexture:tex options:@{kCARendererMetalCommandQueue : queue}];
  CALayer *root = [CALayer layer];
  root.frame = CGRectMake(0, 0, w, h);
  root.backgroundColor = [NSColor colorWithWhite:0.88 alpha:1].CGColor;
  CFTimeInterval t0 = CACurrentMediaTime() + 1;
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  [root addSublayer:foldScene(f, crop.origin, t0)];
  [CATransaction commit];
  r.layer = root;
  r.bounds = root.bounds;
  [CATransaction flush];
  NSMutableData *pixels = [NSMutableData dataWithLength:w * h * 4];
  for (int i = 0; i < frames; i++) {
    [r beginFrameAtTime:t0 + f->seconds * i / (frames - 1) timeStamp:NULL];
    [r addUpdateRect:r.bounds];
    [r render];
    [r endFrame];
    id<MTLCommandBuffer> sync = [queue commandBuffer];
    [sync commit];
    [sync waitUntilCompleted];
    [tex getBytes:pixels.mutableBytes bytesPerRow:w * 4 fromRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0];
    uint8_t *px = pixels.mutableBytes;
    uint8_t *tmp = malloc(w * 4);  // the texture's rows are bottom up
    for (size_t y = 0; y < h / 2; y++) {
      memcpy(tmp, px + y * w * 4, w * 4);
      memcpy(px + y * w * 4, px + (h - 1 - y) * w * 4, w * 4);
      memcpy(px + (h - 1 - y) * w * 4, tmp, w * 4);
    }
    free(tmp);
    CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef ctx = CGBitmapContextCreate(pixels.mutableBytes, w, h, 8, w * 4, cs, kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little);
    CGColorSpaceRelease(cs);
    CGImageRef img = CGBitmapContextCreateImage(ctx);
    CGContextRelease(ctx);
    NSString *path = [NSString stringWithFormat:@"%s/fold-%s-%02d.png", dir, f->closing ? "closing" : "opening", i];
    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc] initWithCGImage:img];
    [[rep representationUsingType:NSBitmapImageFileTypePNG properties:@{}] writeToFile:path atomically:YES];
    CGImageRelease(img);
  }
  r.layer = nil;
}
