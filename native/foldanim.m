// The Mac window's fold animation (simwindow.m "fold animation"): a folding device closing or opening, rendered in 3D
// with SceneKit (a depth buffer, physically based light) from two pictures of it (open and closed, each lit and with its
// screen off) and the skin's look (its back picture, metal colour, body outline). Included by simwindow.m, and by
// scripts/fold-frames.m, which renders the frames from a skin with no app running.
//
// Three ways of folding (Fold.Hinge): BOOK, one hinge down the middle, the left half swinging over the right with the
// cover display on its back; CLAMSHELL, one hinge across the middle, the bottom half folding up onto the top one and the
// closed device then turned over, as a hand does, to show the cover display on the top half's back; TRIFOLD, two
// hinges, the left panel folding onto the middle one and then the right over both, the cover display on its back.
// Every property follows one hinge angle per hinge, as a hand moves a real device: the swing starts from rest, is
// carried at a near-even pace, and in its last 25 degrees the magnets take it shut (a snap, then a settle of under a
// degree); opening breaks the magnets with a small pop, swings, and the flat detent takes the last 12 degrees.
// Each panel is a slab extruded from the body's outline (its corners rounded as the device's, its edges chamfered), its
// thickness the panel's: the screen's picture on its front, the cover display or the skin's back picture on its back,
// machined metal in the skin's colour around it; the hinge is a barrel that fills in as the panels turn about it. A
// studio environment and a key light light it, so glass reflects, metal shines and the swinging panel shadows the one
// below; the screens glow by themselves, going dark and lighting up when a device's do.

#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#import <SceneKit/SceneKit.h>
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

// The inner screen is lit while the hinge is open past ~12 degrees, and goes dark under ~5.
static double foldInnerLit(double open) { return foldSmoother(foldRamp(open, 0.03, 0.07)); }

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
      p.innerLit = foldSmoother(foldRamp(open, 0.05, 0.10));
    }
  } else if (hinge == FOLD_TRIFOLD) {
    if (closing) {
      p.f1 = foldCloseCurve(u / 0.46);
      p.f2 = u < 0.44 ? 0 : foldCloseCurve((u - 0.44) / 0.56);
      p.move = foldSmoother(foldRamp(u, 0.30, 0.95));
      p.innerLit = foldInnerLit(1 - p.f2);
      p.coverLit = foldSmoother(foldRamp(u, 0.86, 0.95));  // the cover wakes as the magnets take it shut
    } else {
      double openR = foldOpenCurve(u / 0.52);
      p.f2 = 1 - openR;
      p.f1 = u < 0.50 ? 1 : 1 - foldOpenCurve((u - 0.50) / 0.50);
      p.move = 1 - foldSmoother(foldRamp(u, 0, 0.70));
      p.coverLit = 1 - foldRamp(u, 0, 0.05);
      p.innerLit = foldSmoother(foldRamp(openR, 0.05, 0.10));
    }
  } else {
    if (closing) {
      p.f1 = foldCloseCurve(u);
      p.move = foldSmoother(foldRamp(u, 0.05, 0.90));
      p.innerLit = foldInnerLit(1 - p.f1);
      p.coverLit = foldSmoother(foldRamp(u, 0.80, 0.90));  // the cover wakes as the magnets take it shut
    } else {
      double open = foldOpenCurve(u);
      p.f1 = 1 - open;
      p.move = 1 - foldSmoother(foldRamp(u, 0, 0.85));
      p.coverLit = 1 - foldRamp(u, 0, 0.06);
      p.innerLit = foldSmoother(foldRamp(open, 0.05, 0.10));
    }
  }
  return p;
}

// ---------------------------------------------------------------- pictures

static CGContextRef foldContext(size_t w, size_t h) {
  CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  CGContextRef ctx = CGBitmapContextCreate(NULL, w, h, 8, 0, cs, kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
  CGColorSpaceRelease(cs);
  return ctx;
}

// The screen's own light: the lit picture less the dark one (they differ only where the screen shows), so a screen is
// its dark glass lit by the room plus this glow.
static CGImageRef foldGlow(CGImageRef lit, CGImageRef dark) {
  size_t w = CGImageGetWidth(lit), h = CGImageGetHeight(lit);
  CGContextRef ctx = foldContext(w, h);
  if (!ctx) return CGImageRetain(lit);
  CGContextSetRGBFillColor(ctx, 0, 0, 0, 1);
  CGContextFillRect(ctx, CGRectMake(0, 0, w, h));
  CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), lit);
  CGContextSetBlendMode(ctx, kCGBlendModeDifference);
  CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), dark);
  CGImageRef out = CGBitmapContextCreateImage(ctx);
  CGContextRelease(ctx);
  return out;
}

// The rectangle of a picture's opaque pixels (alpha at least half), as fractions of it, top down.
static CGRect foldOpaqueRect(CGImageRef img) {
  size_t w = CGImageGetWidth(img), h = CGImageGetHeight(img);
  CGContextRef ctx = foldContext(w, h);
  if (!ctx) return CGRectMake(0, 0, 1, 1);
  CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), img);
  uint8_t *px = CGBitmapContextGetData(ctx);
  size_t stride = CGBitmapContextGetBytesPerRow(ctx);
  size_t x0 = w, x1 = 0, y0 = h, y1 = 0;
  for (size_t y = 0; y < h; y += 2) {
    for (size_t x = 0; x < w; x += 2) {
      if (px[y * stride + x * 4 + 3] >= 128) {  // BGRA, alpha last; memory rows are top down
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
    }
  }
  CGContextRelease(ctx);
  if (x1 <= x0 || y1 <= y0) return CGRectMake(0, 0, 1, 1);
  return CGRectMake((CGFloat)x0 / w, (CGFloat)y0 / h, (CGFloat)(x1 + 2 - x0) / w, (CGFloat)(y1 + 2 - y0) / h);
}

// A picture with its transparent pixels filled with the metal's colour: a face's texture must be opaque.
static CGImageRef foldOpaque(CGImageRef img, int rgb) {
  size_t w = CGImageGetWidth(img), h = CGImageGetHeight(img);
  CGContextRef ctx = foldContext(w, h);
  if (!ctx) return CGImageRetain(img);
  CGContextSetRGBFillColor(ctx, ((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0, 1);
  CGContextFillRect(ctx, CGRectMake(0, 0, w, h));
  CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), img);
  CGImageRef out = CGBitmapContextCreateImage(ctx);
  CGContextRelease(ctx);
  return out;
}

// A photographer's studio as an environment (equirectangular): a bright ceiling and two soft boxes above and to the
// left, a grey horizon, a dark floor. Metal and glass show it as they turn.
static CGImageRef foldStudio(void) {
  size_t w = 1024, h = 512;
  CGContextRef ctx = foldContext(w, h);
  CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  CGFloat c[] = {0.10, 0.10, 0.11, 1, 0.30, 0.30, 0.32, 1, 0.62, 0.62, 0.64, 1, 0.92, 0.92, 0.93, 1};
  CGFloat at[] = {0, 0.42, 0.55, 1};
  CGGradientRef g = CGGradientCreateWithColorComponents(cs, c, at, 4);
  CGContextDrawLinearGradient(ctx, g, CGPointMake(0, 0), CGPointMake(0, h), 0);  // y up: the floor at the bottom
  CGGradientRelease(g);
  CGColorSpaceRelease(cs);
  // Soft boxes: the image's middle faces the scene's -z; the viewer is at +z, so the boxes lights face the device from
  // the image's left and right ends (behind the viewer), high up.
  CGContextSetRGBFillColor(ctx, 1, 1, 1, 1);
  CGContextFillRect(ctx, CGRectMake(w * 0.04, h * 0.66, w * 0.12, h * 0.18));
  CGContextFillRect(ctx, CGRectMake(w * 0.80, h * 0.70, w * 0.08, h * 0.12));
  CGContextSetRGBFillColor(ctx, 1, 1, 1, 0.6);
  CGContextFillRect(ctx, CGRectMake(w * 0.30, h * 0.80, w * 0.40, h * 0.10));
  CGImageRef out = CGBitmapContextCreateImage(ctx);
  CGContextRelease(ctx);
  return out;
}

// ---------------------------------------------------------------- geometry

typedef struct { double x, y, z; } FoldVec;

// A rectangle with the corners named in `round` (1 top left, 2 top right, 4 bottom right, 8 bottom left) rounded.
static NSBezierPath *foldOutline(CGRect r, CGFloat radius, int round) {
  radius = MIN(radius, MIN(r.size.width, r.size.height) / 2);
  CGFloat x0 = CGRectGetMinX(r), x1 = CGRectGetMaxX(r), y0 = CGRectGetMinY(r), y1 = CGRectGetMaxY(r);
  CGFloat tl = round & 1 ? radius : 0, tr = round & 2 ? radius : 0, br = round & 4 ? radius : 0, bl = round & 8 ? radius : 0;
  NSBezierPath *p = [NSBezierPath bezierPath];
  [p moveToPoint:NSMakePoint(x0 + bl, y0)];
  [p lineToPoint:NSMakePoint(x1 - br, y0)];
  if (br > 0) [p appendBezierPathWithArcWithCenter:NSMakePoint(x1 - br, y0 + br) radius:br startAngle:270 endAngle:360];
  [p lineToPoint:NSMakePoint(x1, y1 - tr)];
  if (tr > 0) [p appendBezierPathWithArcWithCenter:NSMakePoint(x1 - tr, y1 - tr) radius:tr startAngle:0 endAngle:90];
  [p lineToPoint:NSMakePoint(x0 + tl, y1)];
  if (tl > 0) [p appendBezierPathWithArcWithCenter:NSMakePoint(x0 + tl, y1 - tl) radius:tl startAngle:90 endAngle:180];
  [p lineToPoint:NSMakePoint(x0, y0 + bl)];
  if (bl > 0) [p appendBezierPathWithArcWithCenter:NSMakePoint(x0 + bl, y0 + bl) radius:bl startAngle:180 endAngle:270];
  [p closePath];
  p.flatness = 0.25;
  return p;
}

// A texture's placement for a face over the rectangle r of the picture (fractions, top down). SceneKit maps a cap's
// outline's bounding box onto texture coordinates 0..1, the picture's top at the box's top; a back cap is mapped as
// seen from behind.
static SCNMatrix4 foldPlace(CGRect r) {
  return SCNMatrix4Mult(SCNMatrix4MakeScale(r.size.width, r.size.height, 1), SCNMatrix4MakeTranslation(r.origin.x, r.origin.y, 0));
}

static SCNMaterial *foldMetalMaterial(int rgb) {
  SCNMaterial *m = [SCNMaterial material];
  m.lightingModelName = SCNLightingModelPhysicallyBased;
  m.diffuse.contents = [NSColor colorWithSRGBRed:((rgb >> 16) & 255) / 255.0 green:((rgb >> 8) & 255) / 255.0 blue:(rgb & 255) / 255.0 alpha:1];
  m.metalness.contents = @0.85;
  m.roughness.contents = @0.32;
  return m;
}

// A face from a picture: its colour lit by the room, and its own glow (a screen's light: NULL for none).
static SCNMaterial *foldPictureMaterial(CGImageRef picture, CGImageRef glow, SCNMatrix4 place, double roughness) {
  SCNMaterial *m = [SCNMaterial material];
  m.lightingModelName = SCNLightingModelPhysicallyBased;
  m.diffuse.contents = (__bridge id)picture;
  m.diffuse.contentsTransform = place;
  m.diffuse.wrapS = m.diffuse.wrapT = SCNWrapModeClamp;
  m.metalness.contents = @0;
  m.roughness.contents = @(roughness);
  if (glow) {
    m.emission.contents = (__bridge id)glow;
    m.emission.contentsTransform = place;
    m.emission.wrapS = m.emission.wrapT = SCNWrapModeClamp;
  }
  return m;
}

// A panel: the outline extruded by its thickness, front at z 0 and back at -t, chamfered with a rounded profile; its
// front is a flat face of the same outline laid flush on it, so two panels' screens meet at a hinge with no seam.
static SCNNode *foldSlab(NSBezierPath *outline, CGFloat t, SCNMaterial *front, SCNMaterial *back, SCNMaterial *metal) {
  SCNShape *s = [SCNShape shapeWithPath:outline extrusionDepth:t];
  s.chamferMode = SCNChamferModeBoth;
  s.chamferRadius = t * 0.16;
  NSBezierPath *profile = [NSBezierPath bezierPath];
  [profile moveToPoint:NSMakePoint(0, 1)];
  [profile appendBezierPathWithArcWithCenter:NSMakePoint(0, 0) radius:1 startAngle:90 endAngle:0 clockwise:YES];
  s.chamferProfile = profile;
  SCNMaterial *glass = [SCNMaterial material];  // under the face: never seen but at a grazing edge
  glass.lightingModelName = SCNLightingModelPhysicallyBased;
  glass.diffuse.contents = [NSColor colorWithWhite:0.02 alpha:1];
  glass.roughness.contents = @0.2;
  s.materials = @[glass, back, metal, metal, metal];
  SCNNode *n = [SCNNode nodeWithGeometry:s];
  n.position = SCNVector3Make(0, 0, -t / 2);
  SCNShape *flat = [SCNShape shapeWithPath:outline extrusionDepth:0];
  flat.materials = @[front];
  SCNNode *face = [SCNNode nodeWithGeometry:flat];
  face.position = SCNVector3Make(0, 0, t / 2 + MAX(0.05, t * 0.01));
  [n addChildNode:face];
  return n;
}

// The hinge's barrel: the part of a cylinder about the hinge's axis between the fixed panel's end (pointing back, -z)
// and the turning one's, from radius r0 to r1, over [lo, hi] along the axis. alongY: a book's or tri-fold's hinge (the
// axis runs up the device), else a clamshell's (across it). sign: the way the turning panel goes (+1 a left panel, -1 a
// right one; a clamshell's bottom panel turns as -1 about x). It is rebuilt for each pose: its angle is the hinge's.
static SCNGeometry *foldBarrel(BOOL alongY, CGFloat ax, CGFloat az, CGFloat lo, CGFloat hi, CGFloat r0, CGFloat r1, int sign, double angle,
                               SCNMaterial *metal) {
  enum { K = 20 };
  int nv = 0;
  SCNVector3 v[(K + 1) * 8], nm[(K + 1) * 8];
  int32_t idx[K * 24];
  int ni = 0;
  for (int k = 0; k <= K; k++) {
    double a = angle * k / K;
    // The direction from the axis: back (-z) turned by the hinge's angle as the panel turns.
    double dp = alongY ? -sign * sin(a) : -sin(a), dz = -cos(a);
    for (int end = 0; end < 2; end++) {
      CGFloat at = end ? hi : lo;
      for (int ring = 0; ring < 2; ring++) {
        CGFloat r = ring ? r1 : r0;
        SCNVector3 p = alongY ? SCNVector3Make(ax + r * dp, at, az + r * dz) : SCNVector3Make(at, ax + r * dp, az + r * dz);
        // Two copies: one with the surface's normal (radial), one with the end cap's (along the axis).
        SCNVector3 radial = alongY ? SCNVector3Make(dp * (ring ? 1 : -1), 0, dz * (ring ? 1 : -1)) : SCNVector3Make(0, dp * (ring ? 1 : -1), dz * (ring ? 1 : -1));
        SCNVector3 axial = alongY ? SCNVector3Make(0, end ? 1 : -1, 0) : SCNVector3Make(end ? 1 : -1, 0, 0);
        v[nv] = p; nm[nv++] = radial;
        v[nv] = p; nm[nv++] = axial;
      }
    }
  }
  // Vertex layout per k: end*4 + ring*2 + (0 radial, 1 axial).
  for (int k = 0; k < K; k++) {
    int a = k * 8, b = (k + 1) * 8;
    for (int ring = 0; ring < 2; ring++) {  // outer and inner surfaces
      int p0 = a + ring * 2, p1 = a + 4 + ring * 2, q0 = b + ring * 2, q1 = b + 4 + ring * 2;
      int32_t t[6] = {p0, q0, q1, p0, q1, p1};
      for (int i = 0; i < 6; i++) idx[ni++] = t[i];
    }
    for (int end = 0; end < 2; end++) {  // the end caps
      int p0 = a + end * 4 + 1, p1 = a + end * 4 + 3, q0 = b + end * 4 + 1, q1 = b + end * 4 + 3;
      int32_t t[6] = {p0, q0, q1, p0, q1, p1};
      for (int i = 0; i < 6; i++) idx[ni++] = t[i];
    }
  }
  SCNGeometrySource *vs = [SCNGeometrySource geometrySourceWithVertices:v count:nv];
  SCNGeometrySource *ns = [SCNGeometrySource geometrySourceWithNormals:nm count:nv];
  NSData *d = [NSData dataWithBytes:idx length:ni * sizeof(int32_t)];
  SCNGeometryElement *e = [SCNGeometryElement geometryElementWithData:d primitiveType:SCNGeometryPrimitiveTypeTriangles
                                                       primitiveCount:ni / 3 bytesPerIndex:sizeof(int32_t)];
  SCNGeometry *g = [SCNGeometry geometryWithSources:@[vs, ns] elements:@[e]];
  SCNMaterial *m = [metal copy];
  m.doubleSided = YES;  // its winding goes either way with the hinge's direction
  g.materials = @[m];
  return g;
}

static CATransform3D foldAbout(CATransform3D r, CGFloat x, CGFloat y, CGFloat z) {
  return CATransform3DConcat(CATransform3DConcat(CATransform3DMakeTranslation(-x, -y, -z), r), CATransform3DMakeTranslation(x, y, z));
}

// ---------------------------------------------------------------- the stage

// The scene for one fold, for a view showing `view` (a rectangle in the same coordinates as the spec's rectangles).
@interface FoldStage : NSObject <SCNSceneRendererDelegate>
@property(nonatomic, strong) SCNScene *scene;
@property(nonatomic, strong) SCNNode *cameraNode;
@property(nonatomic, copy) void (^finished)(void);
@property(nonatomic, copy) void (^firstFrame)(void);
- (instancetype)initWithSpec:(const FoldSpec *)spec view:(CGRect)view;
- (void)poseAt:(double)u;
@end

@implementation FoldStage {
  FoldSpec _f;
  CGRect _view;
  SCNNode *_device, *_moving1, *_moving2, *_barrel1, *_barrel2;
  NSMutableArray<SCNMaterial *> *_inner, *_cover;
  CGFloat _W, _H, _t, _hx1, _hx2, _hy, _by0, _by1, _bx0, _bx1, _stackTop;
  CGRect _stack, _closedBody;
  CGPoint _devicePos;
  SCNMaterial *_metal;
  CFTimeInterval _t0;
  BOOL _done, _drew;
}

- (instancetype)initWithSpec:(const FoldSpec *)spec view:(CGRect)view {
  if (!(self = [super init])) return nil;
  _f = *spec;
  _view = view;
  const FoldSpec *f = &_f;
  CGFloat W = _W = f->openRect.size.width, H = _H = f->openRect.size.height;
  CGFloat t = _t = f->thickness * H;
  int hinge = f->hinge;
  int metal = f->edge >= 0 ? f->edge : 0x6e7075;
  _scene = [SCNScene scene];
  SCNNode *root = _scene.rootNode;
  CGImageRef studio = foldStudio();
  _scene.lightingEnvironment.contents = (__bridge id)studio;
  _scene.lightingEnvironment.intensity = 1.25;
  CGImageRelease(studio);

  // The camera: at the open device's centre, 2.4 of its sizes away, showing exactly `view` in the device's plane (an
  // off-centre frustum), so the plane z 0 is drawn 1:1 where the window shows the device.
  CGFloat d = 2.4 * MAX(W, H);
  CGPoint eye = CGPointMake(CGRectGetMidX(f->openRect), CGRectGetMidY(f->openRect));
  _cameraNode = [SCNNode node];
  _cameraNode.camera = [SCNCamera camera];
  _cameraNode.position = SCNVector3Make(eye.x, eye.y, d);
  CGFloat n = d * 0.05, far = d * 4;
  CGFloat l = (CGRectGetMinX(view) - eye.x) * n / d, r = (CGRectGetMaxX(view) - eye.x) * n / d;
  CGFloat b = (CGRectGetMinY(view) - eye.y) * n / d, tp = (CGRectGetMaxY(view) - eye.y) * n / d;
  SCNMatrix4 p = SCNMatrix4Identity;
  p.m11 = 2 * n / (r - l);
  p.m22 = 2 * n / (tp - b);
  p.m31 = (r + l) / (r - l);
  p.m32 = (tp + b) / (tp - b);
  p.m33 = -(far + n) / (far - n);
  p.m34 = -1;
  p.m43 = -2 * far * n / (far - n);
  p.m44 = 0;
  _cameraNode.camera.zNear = n;
  _cameraNode.camera.zFar = far;
  _cameraNode.camera.projectionTransform = p;
  [root addChildNode:_cameraNode];

  // The key light above and to the left of the viewer, casting soft shadows; a little fill.
  SCNNode *key = [SCNNode node];
  key.light = [SCNLight light];
  key.light.type = SCNLightTypeDirectional;
  key.light.intensity = 650;
  key.light.castsShadow = YES;
  key.light.shadowMode = SCNShadowModeForward;
  key.light.shadowRadius = 6;
  key.light.shadowSampleCount = 16;
  key.light.shadowMapSize = CGSizeMake(2048, 2048);
  key.light.shadowColor = [NSColor colorWithWhite:0 alpha:0.55];
  key.light.orthographicScale = MAX(W, H) * 0.9;
  key.light.zNear = 1;
  key.light.zFar = d * 4;
  key.position = SCNVector3Make(eye.x - 0.6 * d, eye.y + 0.9 * d, d * 1.6);
  [key lookAt:SCNVector3Make(eye.x, eye.y, 0)];
  [root addChildNode:key];
  SCNNode *fill = [SCNNode node];
  fill.light = [SCNLight light];
  fill.light.type = SCNLightTypeAmbient;
  fill.light.intensity = 120;
  [root addChildNode:fill];

  // The device: its origin the open device's centre; panels in its coordinates (y up).
  _device = [SCNNode node];
  _devicePos = eye;
  [root addChildNode:_device];
  CGRect sc = f->openScreen;
  CGFloat hx1, hx2 = 0, hy = 0;
  if (hinge == FOLD_TRIFOLD) {
    hx1 = -W / 2 + W * (sc.origin.x + sc.size.width / 3);
    hx2 = -W / 2 + W * (sc.origin.x + sc.size.width * 2 / 3);
  } else {
    hx1 = -W / 2 + W * (sc.origin.x + sc.size.width / 2);
    hy = H / 2 - H * (sc.origin.y + sc.size.height / 2);
  }
  _hx1 = hx1;
  _hx2 = hx2;
  _hy = hy;
  CGRect body = f->body.size.width > 0 ? f->body : CGRectMake(0, 0, 1, 1);
  CGFloat bx0 = _bx0 = -W / 2 + W * body.origin.x, bx1 = _bx1 = bx0 + W * body.size.width;
  CGFloat by1 = _by1 = H / 2 - H * body.origin.y, by0 = _by0 = by1 - H * body.size.height;
  CGFloat radius = fmin(f->bodyRadius * H, fmin(bx1 - bx0, by1 - by0) / 2);
  _metal = foldMetalMaterial(metal);

  // Textures: the front is the open picture (its glow the screen); a back is the cover display or the skin's back.
  CGImageRef openGlow = foldGlow(f->openLit, f->openDark);
  CGRect coverBox = foldOpaqueRect(f->closedLit);
  CGImageRef coverDark = foldOpaque(f->closedDark, metal), coverGlow = foldGlow(f->closedLit, f->closedDark);
  CGImageRef backPic = f->back ? foldOpaque(f->back, metal) : NULL;
  _closedBody = CGRectMake(CGRectGetMinX(f->closedRect) + coverBox.origin.x * f->closedRect.size.width,
                           CGRectGetMaxY(f->closedRect) - (coverBox.origin.y + coverBox.size.height) * f->closedRect.size.height,
                           coverBox.size.width * f->closedRect.size.width, coverBox.size.height * f->closedRect.size.height);
  _inner = [NSMutableArray array];
  _cover = [NSMutableArray array];
  // The picture's rectangle for a panel's region in device coordinates (fractions, top down).
  CGRect (^frac)(CGRect) = ^CGRect(CGRect rr) {
    return CGRectMake((rr.origin.x + W / 2) / W, (H / 2 - CGRectGetMaxY(rr)) / H, rr.size.width / W, rr.size.height / H);
  };
  SCNMaterial * (^front)(CGRect) = ^SCNMaterial *(CGRect rr) {
    SCNMaterial *m = foldPictureMaterial(f->openDark, openGlow, foldPlace(frac(rr)), 0.16);
    [self->_inner addObject:m];
    return m;
  };
  SCNMaterial * (^coverBack)(void) = ^SCNMaterial *(void) {
    SCNMaterial *m = foldPictureMaterial(coverDark, coverGlow, foldPlace(coverBox), 0.16);
    [self->_cover addObject:m];
    return m;
  };
  SCNMaterial * (^plainBack)(CGRect) = ^SCNMaterial *(CGRect rr) {
    if (!backPic) {
      SCNMaterial *m = [SCNMaterial material];
      m.lightingModelName = SCNLightingModelPhysicallyBased;
      m.diffuse.contents = [NSColor colorWithSRGBRed:0.13 green:0.14 blue:0.17 alpha:1];
      m.roughness.contents = @0.45;
      return m;
    }
    CGRect fr = frac(rr);
    fr.origin.x = 1 - fr.origin.x - fr.size.width;  // the back picture is seen from behind
    return foldPictureMaterial(backPic, NULL, foldPlace(fr), 0.5);  // frosted glass
  };

  SCNNode *fixed = [SCNNode node];
  _moving1 = [SCNNode node];
  [_device addChildNode:fixed];
  [_device addChildNode:_moving1];
  if (hinge == FOLD_BOOK) {
    CGRect left = CGRectMake(bx0, by0, hx1 - bx0, by1 - by0), right = CGRectMake(hx1, by0, bx1 - hx1, by1 - by0);
    [fixed addChildNode:foldSlab(foldOutline(right, radius, 2 | 4), t, front(right), plainBack(right), _metal)];
    [_moving1 addChildNode:foldSlab(foldOutline(left, radius, 1 | 8), t, front(left), coverBack(), _metal)];
    _stack = CGRectMake(hx1 - t, by0, hx1 - bx0 + t, by1 - by0);  // the barrel rounds off the hinge's side
    _stackTop = t;
  } else if (hinge == FOLD_CLAMSHELL) {
    CGRect top = CGRectMake(bx0, hy, bx1 - bx0, by1 - hy), bottom = CGRectMake(bx0, by0, bx1 - bx0, hy - by0);
    [fixed addChildNode:foldSlab(foldOutline(top, radius, 1 | 2), t, front(top), coverBack(), _metal)];
    [_moving1 addChildNode:foldSlab(foldOutline(bottom, radius, 4 | 8), t, front(bottom), plainBack(bottom), _metal)];
    _stack = CGRectMake(bx0, hy - t, bx1 - bx0, by1 - hy + t);
    _stackTop = t;
  } else {
    _moving2 = [SCNNode node];
    [_device addChildNode:_moving2];
    CGRect left = CGRectMake(bx0, by0, hx1 - bx0, by1 - by0), mid = CGRectMake(hx1, by0, hx2 - hx1, by1 - by0);
    CGRect right = CGRectMake(hx2, by0, bx1 - hx2, by1 - by0);
    [fixed addChildNode:foldSlab(foldOutline(mid, radius, 0), t, front(mid), plainBack(mid), _metal)];
    [_moving1 addChildNode:foldSlab(foldOutline(left, radius, 1 | 8), t, front(left), plainBack(left), _metal)];
    [_moving2 addChildNode:foldSlab(foldOutline(right, radius, 2 | 4), t, front(right), coverBack(), _metal)];
    // Closed: the right panel over the left, between the two barrels.
    CGFloat x0 = fmin(2 * hx2 - bx1, hx1 - t), x1 = hx2 + 1.5 * t;
    _stack = CGRectMake(x0, by0, x1 - x0, by1 - by0);
    _stackTop = 2 * t;
  }
  // Keys: small metal bars standing out of the body's right side.
  for (int k = 0; k < f->keys && k < 4; k++) {
    CGFloat ky1 = H / 2 - H * f->key[2 * k], ky0 = H / 2 - H * f->key[2 * k + 1];
    if (ky1 - ky0 < 1) continue;
    CGFloat proud = MAX(1, t * 0.12);
    SCNBox *box = [SCNBox boxWithWidth:proud * 2 height:ky1 - ky0 length:t * 0.55 chamferRadius:proud * 0.8];
    box.materials = @[_metal];
    SCNNode *kn = [SCNNode nodeWithGeometry:box];
    kn.position = SCNVector3Make(bx1, (ky0 + ky1) / 2, -t / 2);
    CGFloat my = (ky0 + ky1) / 2;
    SCNNode *owner = hinge == FOLD_CLAMSHELL ? (my < hy ? _moving1 : fixed) : hinge == FOLD_TRIFOLD ? _moving2 : fixed;
    [owner addChildNode:kn];
  }
  _barrel1 = [SCNNode node];
  [_device addChildNode:_barrel1];
  if (_moving2) {
    _barrel2 = [SCNNode node];
    [_device addChildNode:_barrel2];
  }
  CGImageRelease(openGlow);
  CGImageRelease(coverDark);
  CGImageRelease(coverGlow);
  if (backPic) CGImageRelease(backPic);
  [self poseAt:0];
  return self;
}

- (void)poseAt:(double)u {
  const FoldSpec *f = &_f;
  int hinge = f->hinge;
  FoldPose p = foldPoseAt(hinge, f->closing, u);
  CGFloat W = _W, H = _H, t = _t;
  (void)W;
  CGPoint sc = CGPointMake(CGRectGetMidX(_stack), CGRectGetMidY(_stack));
  CGPoint target = CGPointMake(CGRectGetMidX(_closedBody) - _devicePos.x, CGRectGetMidY(_closedBody) - _devicePos.y);
  CGFloat sx = _closedBody.size.width / _stack.size.width, sy = _closedBody.size.height / _stack.size.height, sz = (sx + sy) / 2;
  double m = p.move;
  CATransform3D d = CATransform3DMakeTranslation(-sc.x, -sc.y, 0);
  d = CATransform3DConcat(d, CATransform3DMakeScale(1 + m * (sx - 1), 1 + m * (sy - 1), 1 + m * (sz - 1)));
  d = CATransform3DConcat(d, CATransform3DMakeRotation(p.yaw, 0, 1, 0));
  CGPoint at = CGPointMake(sc.x + m * (target.x - sc.x), sc.y + m * (target.y - sc.y));
  d = CATransform3DConcat(d, CATransform3DMakeTranslation(at.x, at.y, p.lift * H - m * _stackTop * sz));
  d = CATransform3DConcat(d, CATransform3DMakeTranslation(_devicePos.x, _devicePos.y, 0));
  _device.transform = d;
  double a1 = M_PI * p.f1, a2 = M_PI * p.f2;
  if (hinge == FOLD_CLAMSHELL) _moving1.transform = foldAbout(CATransform3DMakeRotation(-a1, 1, 0, 0), 0, _hy, 0);
  else _moving1.transform = foldAbout(CATransform3DMakeRotation(a1, 0, 1, 0), _hx1, 0, 0);
  if (_moving2) _moving2.transform = foldAbout(CATransform3DMakeRotation(-a2, 0, 1, 0), _hx2, 0, t / 2);
  for (SCNMaterial *mt in _inner) mt.emission.intensity = p.innerLit;
  for (SCNMaterial *mt in _cover) mt.emission.intensity = p.coverLit;
  // The barrels: hidden while flat (no angle), filled to the hinge's angle as it turns.
  if (hinge == FOLD_CLAMSHELL) {
    _barrel1.geometry = a1 > 0.01 ? foldBarrel(NO, _hy, 0, _bx0 + t * 0.2, _bx1 - t * 0.2, 0, t, -1, a1, _metal) : nil;
  } else {
    _barrel1.geometry = a1 > 0.01 ? foldBarrel(YES, _hx1, 0, _by0 + t * 0.2, _by1 - t * 0.2, 0, t, 1, a1, _metal) : nil;
  }
  if (_barrel2) _barrel2.geometry = a2 > 0.01 ? foldBarrel(YES, _hx2, t / 2, _by0 + t * 0.2, _by1 - t * 0.2, t * 0.5, t * 1.5, -1, a2, _metal) : nil;
}

// SceneKit's render loop (a live SCNView): the pose for the time since the first frame, and the end.
- (void)renderer:(id<SCNSceneRenderer>)renderer updateAtTime:(NSTimeInterval)time {
  if (_t0 == 0) _t0 = time;
  double u = (time - _t0) / _f.seconds;
  [self poseAt:fmin(1, u)];
  if (u >= 1 && !_done) {
    _done = YES;
    void (^done)(void) = self.finished;
    if (done) dispatch_async(dispatch_get_main_queue(), done);
  }
}

- (void)renderer:(id<SCNSceneRenderer>)renderer didRenderScene:(SCNScene *)scene atTime:(NSTimeInterval)time {
  if (_drew) return;
  _drew = YES;
  void (^first)(void) = self.firstFrame;
  if (first) dispatch_async(dispatch_get_main_queue(), first);
}
@end

// The frames of a run rendered off screen into DIR as PNGs (fold-closing-00.png ...), on grey: scripts cannot see the
// overlay window. APKSIM_FOLD_FRAMES=DIR in an instance; scripts/fold-frames.m from a skin's pictures.
static void foldDump(const FoldSpec *f, const char *dir, int frames) {
  CGRect crop = CGRectIntegral(CGRectInset(CGRectUnion(f->openRect, f->closedRect), -f->openRect.size.width * 0.35, -f->openRect.size.height * 0.25));
  id<MTLDevice> gpu = MTLCreateSystemDefaultDevice();
  if (!gpu || crop.size.width < 1 || crop.size.height < 1) return;
  FoldStage *stage = [[FoldStage alloc] initWithSpec:f view:crop];
  stage.scene.background.contents = [NSColor colorWithWhite:0.88 alpha:1];
  SCNRenderer *r = [SCNRenderer rendererWithDevice:gpu options:nil];
  r.scene = stage.scene;
  r.pointOfView = stage.cameraNode;
  for (int i = 0; i < frames; i++) {
    [stage poseAt:(double)i / (frames - 1)];
    NSImage *img = [r snapshotAtTime:i withSize:crop.size antialiasingMode:SCNAntialiasingModeMultisampling4X];
    NSString *path = [NSString stringWithFormat:@"%s/fold-%s-%02d.png", dir, f->closing ? "closing" : "opening", i];
    CGImageRef cg = [img CGImageForProposedRect:NULL context:nil hints:nil];
    if (!cg) continue;
    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc] initWithCGImage:cg];
    [[rep representationUsingType:NSBitmapImageFileTypePNG properties:@{}] writeToFile:path atomically:YES];
  }
}
