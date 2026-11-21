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
  CGImageRef back;       // the open device seen from behind, upright, the open picture's size (a skin's), or NULL
  int edge;              // the frame's metal as 0xRRGGBB, or -1
  CGRect body;           // the body in the open picture, as fractions, top down (empty: the whole picture)
  CGFloat bodyRadius;    // its corner radius, as a fraction of the picture's height
  int keys;              // side keys on the body's right edge (at most 4): top and bottom, fractions of the picture's height
  CGFloat key[8];
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

// A colour of the frame's metal, times f (lighter above 1).
static CGColorRef foldMetal(int rgb, double f) {
  double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
  return [NSColor colorWithSRGBRed:fmin(1, r * f) green:fmin(1, g * f) blue:fmin(1, b * f) alpha:1].CGColor;
}

// A normal of a panel's (0: fixed, 1: the first to fold, 2: the tri-fold's right one) turned as its hinge is.
static FoldVec foldTurn(FoldVec v, int hinge, int panel, double a1, double a2) {
  if (panel == 0) return v;
  if (hinge == FOLD_CLAMSHELL) {
    double b = -a1;
    return (FoldVec){v.x, v.y * cos(b) - v.z * sin(b), v.y * sin(b) + v.z * cos(b)};
  }
  double a = panel == 1 ? a1 : -a2;
  return (FoldVec){v.x * cos(a) + v.z * sin(a), v.y, -v.x * sin(a) + v.z * cos(a)};
}

// A strip of a panel's side: a quad standing between its front (z 0) and back (z -depth), along the outline from its
// middle (x, y) with outward normal (nx, ny), len long, out further out than the outline (a key stands proud of it).
static CAGradientLayer *foldWall(CGFloat x, CGFloat y, CGFloat nx, CGFloat ny, CGFloat len, CGFloat depth, CGFloat out, int rgb, double lift) {
  CAGradientLayer *w = [CAGradientLayer layer];
  w.bounds = CGRectMake(0, 0, len, depth);
  w.position = CGPointMake(x + nx * out, y + ny * out);
  w.zPosition = -depth / 2;
  // Stood up (its normal along -y), then turned to face (nx, ny).
  w.transform = CATransform3DConcat(CATransform3DMakeRotation(M_PI_2, 1, 0, 0), CATransform3DMakeRotation(atan2(nx, -ny), 0, 0, 1));
  // Machined aluminium: a bright chamfer along each edge, the flat band between.
  w.colors = @[(__bridge id)foldMetal(rgb, 1.75 * lift), (__bridge id)foldMetal(rgb, 1.08 * lift), (__bridge id)foldMetal(rgb, 0.92 * lift),
               (__bridge id)foldMetal(rgb, 1.0 * lift), (__bridge id)foldMetal(rgb, 1.6 * lift)];
  w.locations = @[@0, @0.1, @0.5, @0.9, @1];
  w.startPoint = CGPointMake(0.5, 0);
  w.endPoint = CGPointMake(0.5, 1);
  w.doubleSided = NO;
  return w;
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

// A panel's back, rect in device coordinates, in a holder a thickness behind the front and facing the other way: the
// skin's back picture's part for it (seen from behind, so its slice is the front's mirrored), else plain glass in the
// front's outline mirrored. Returns the holder; *light gets the face's darkening and glint (glint NSNull for plain).
static CALayer *foldBackFace(CALayer *panel, CGRect rect, const FoldSpec *f, CGImageRef mirrorX, CGFloat W, CGFloat H, CGFloat t,
                             CGFloat corner, NSMutableArray *faces) {
  CGRect slice = foldSlice(rect, W, H);
  slice.origin.x = 1 - slice.origin.x - slice.size.width;
  CALayer *holder = [CALayer layer];
  holder.frame = rect;
  holder.zPosition = -t;
  holder.transform = CATransform3DMakeRotation(M_PI, 0, 1, 0);
  holder.doubleSided = NO;
  [panel addSublayer:holder];
  if (f->back) {
    FoldLit l = foldFace(holder, holder.bounds, NULL, f->back, slice, kCALayerLeftEdge | kCALayerRightEdge | kCALayerBottomEdge | kCALayerTopEdge,
                         [NSMutableArray array]);
    [faces addObject:l.dark];
    [faces addObject:l.glint];
  } else {
    CALayer *b = foldPlainBack(holder.bounds, mirrorX, slice, corner, 0.004 * H + 1);
    [holder addSublayer:b];
    [faces addObject:b];
    [faces addObject:[NSNull null]];
  }
  return holder;
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
  // The body's outline in device coordinates (the skin's; a bare screen's is the picture, square).
  CGRect body = f->body.size.width > 0 ? f->body : CGRectMake(0, 0, 1, 1);
  CGFloat bx0 = -W / 2 + W * body.origin.x, bx1 = bx0 + W * body.size.width;
  CGFloat by1 = H / 2 - H * body.origin.y, by0 = by1 - H * body.size.height;
  CGFloat corner = fmin(f->bodyRadius * H, fmin(bx1 - bx0, by1 - by0) / 2);
  int metal = f->edge >= 0 ? f->edge : 0x6e7075;

  CGImageRef mirrorX = foldMirrored(f->openLit, YES);
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
    // The bottom half's back: the skin's back picture's glass, a thickness behind it.
    foldBackFace(moving1, bottom, f, mirrorX, W, H, t, corner, faces);
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
    foldBackFace(moving1, left, f, mirrorX, W, H, t, corner, faces);
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
    stack = CGRectMake(2 * hx2 - W / 2, -H / 2, W / 2 - hx2, H);
    stackTop = 2 * t;
  }
  CGImageRelease(mirrorX);

  // The sides: the body's outline, counterclockwise, as straight runs and its corners in short steps, cut where a hinge
  // crosses it; each piece a wall on the panel it belongs to, the panel's thickness deep. Keys stand out of the right side.
  CGPoint pts[160];
  int np = 0;
  const int ARC = 8;
  CGFloat cuts[2] = {hinge == FOLD_CLAMSHELL ? hy : hx1, hx2};
  int ncuts = hinge == FOLD_TRIFOLD ? 2 : 1;
  CGPoint arcCentre[4] = {{bx1 - corner, by0 + corner}, {bx1 - corner, by1 - corner}, {bx0 + corner, by1 - corner}, {bx0 + corner, by0 + corner}};
  for (int side = 0; side < 4; side++) {
    // side 0 bottom (left to right), 1 right (up), 2 top (right to left), 3 left (down); then the corner after it.
    CGPoint a = side == 0 ? CGPointMake(bx0 + corner, by0) : side == 1 ? CGPointMake(bx1, by0 + corner)
              : side == 2 ? CGPointMake(bx1 - corner, by1) : CGPointMake(bx0, by1 - corner);
    pts[np++] = a;
    BOOL horizontal = side % 2 == 0;
    if (horizontal == (hinge != FOLD_CLAMSHELL)) {
      for (int k = 0; k < ncuts; k++) {
        int c = side < 2 ? k : ncuts - 1 - k;  // in the order the side runs
        CGFloat v = cuts[c];
        if (horizontal && v > bx0 + corner && v < bx1 - corner) pts[np++] = CGPointMake(v, a.y);
        if (!horizontal && v > by0 + corner && v < by1 - corner) pts[np++] = CGPointMake(a.x, v);
      }
    }
    if (corner >= 1) {
      CGPoint c = arcCentre[side];
      double a0 = -M_PI_2 + side * M_PI_2;
      for (int k = 0; k < ARC; k++) {
        double ang = a0 + M_PI_2 * k / ARC;
        pts[np++] = CGPointMake(c.x + corner * cos(ang), c.y + corner * sin(ang));
      }
    }
  }
  NSMutableArray<CALayer *> *walls = [NSMutableArray array];
  NSMutableArray<NSValue *> *wallNormal = [NSMutableArray array];  // its outward normal, x and y
  NSMutableArray<NSNumber *> *wallPanel = [NSMutableArray array];
  NSArray<CALayer *> *panels = moving2 ? @[fixedPanel, moving1, moving2] : @[fixedPanel, moving1];
  for (int i = 0; i < np; i++) {
    CGPoint a = pts[i], b = pts[(i + 1) % np];
    CGFloat dx = b.x - a.x, dy = b.y - a.y, len = hypot(dx, dy);
    if (len < 0.5) continue;
    CGFloat nx = dy / len, ny = -dx / len;
    CGPoint m = CGPointMake((a.x + b.x) / 2, (a.y + b.y) / 2);
    int panel = hinge == FOLD_CLAMSHELL ? (m.y < hy ? 1 : 0) : m.x < hx1 ? 1 : (hinge == FOLD_TRIFOLD && m.x > hx2) ? 2 : 0;
    CAGradientLayer *w = foldWall(m.x, m.y, nx, ny, len + 0.8, t, 0, metal, 1);  // a little long: no cracks between pieces
    [panels[panel] addSublayer:w];
    [walls addObject:w];
    [wallNormal addObject:[NSValue valueWithPoint:NSMakePoint(nx, ny)]];
    [wallPanel addObject:@(panel)];
  }
  for (int k = 0; k < f->keys && k < 4; k++) {
    CGFloat ky1 = H / 2 - H * f->key[2 * k], ky0 = H / 2 - H * f->key[2 * k + 1];
    if (ky1 - ky0 < 1) continue;
    CGFloat my = (ky0 + ky1) / 2;
    int panel = hinge == FOLD_CLAMSHELL ? (my < hy ? 1 : 0) : hinge == FOLD_TRIFOLD ? 2 : 0;
    CGFloat proud = MAX(1, t * 0.09);
    CAGradientLayer *key = foldWall(bx1, my, 1, 0, ky1 - ky0, t * 0.55, proud, metal, 1.12);
    key.cornerRadius = t * 0.2;
    [panels[panel] addSublayer:key];
    [walls addObject:key];
    [wallNormal addObject:[NSValue valueWithPoint:NSMakePoint(1, 0)]];
    [wallPanel addObject:@(panel)];
  }
  NSMutableArray *wallDark[160], *wallGlint[160];
  NSUInteger nw = MIN(walls.count, 160);
  CALayer *wallShade[160], *wallShine[160];
  for (NSUInteger k = 0; k < nw; k++) {
    wallDark[k] = [NSMutableArray array];
    wallGlint[k] = [NSMutableArray array];
    wallShade[k] = [CALayer layer];
    wallShade[k].frame = walls[k].bounds;
    wallShade[k].backgroundColor = CGColorGetConstantColor(kCGColorBlack);
    wallShade[k].cornerRadius = walls[k].cornerRadius;
    wallShine[k] = [CALayer layer];
    wallShine[k].frame = walls[k].bounds;
    wallShine[k].backgroundColor = CGColorGetConstantColor(kCGColorWhite);
    wallShine[k].cornerRadius = walls[k].cornerRadius;
    [walls[k] addSublayer:wallShade[k]];
    [walls[k] addSublayer:wallShine[k]];
  }

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
  NSMutableArray *innerA = [NSMutableArray array], *coverA = [NSMutableArray array];
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
    for (NSUInteger k = 0; k < nw; k++) {
      NSPoint wn = wallNormal[k].pointValue;
      double dark, glint;
      foldLight(foldYaw(foldTurn((FoldVec){wn.x, wn.y, 0}, hinge, wallPanel[k].intValue, a1, a2), p.yaw), &dark, &glint);
      // Bare metal: it goes darker away from the light, and shines brighter towards it, than glass.
      [wallDark[k] addObject:@(fmin(0.9, dark * 1.05))];
      [wallGlint[k] addObject:@(fmin(0.7, glint * 1.5))];
    }
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
  for (NSUInteger k = 0; k < nw; k++) {
    [wallShade[k] addAnimation:foldKeys(@"opacity", wallDark[k], t0, seconds) forKey:@"light"];
    wallShade[k].opacity = [wallDark[k].firstObject floatValue];
    [wallShine[k] addAnimation:foldKeys(@"opacity", wallGlint[k], t0, seconds) forKey:@"light"];
    wallShine[k].opacity = [wallGlint[k].firstObject floatValue];
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
