// Renders the Mac window's fold animation (native/foldanim.m) for a folding skin without running an app: a test
// picture in each screen, the skin's frames around them, frames of a closing and an opening written as PNGs.
//   clang -fobjc-arc -framework Cocoa -framework QuartzCore -framework Metal -framework SceneKit -o work/fold-frames scripts/fold-frames.m
//   work/fold-frames SKIN_DIR OUT_DIR book|clamshell|trifold THICKNESS COVER_ZOOM [FRAMES] [SECONDS]
// THICKNESS: one panel's thickness against the open device's height (Fold.java: panelMm / openHeightMm). COVER_ZOOM:
// the cover panel's pixels per inch against the inner one's (Fold.Spec.ppi), as the window shows each display. Each
// frame's time is its share of SECONDS (1 by default): FRAMES 13 shows the run, 60 is a smooth contact sheet's worth.

#include "../native/foldanim.m"

static NSDictionary *readJson(NSString *path) {
  NSData *d = [NSData dataWithContentsOfFile:path];
  return d ? [NSJSONSerialization JSONObjectWithData:d options:0 error:nil] : nil;
}

static CGImageRef loadPng(NSString *path) {
  CGDataProviderRef p = CGDataProviderCreateWithFilename(path.fileSystemRepresentation);
  if (!p) return NULL;
  CGImageRef img = CGImageCreateWithPNGDataProvider(p, NULL, false, kCGRenderingIntentDefault);
  CGDataProviderRelease(p);
  return img;
}

// The device as the window draws it: a test screen (lit) or black glass (dark) in the frame's hole, then the frame.
static CGImageRef devicePicture(CGImageRef frame, NSDictionary *screen, BOOL lit, BOOL cover) {
  size_t W = CGImageGetWidth(frame), H = CGImageGetHeight(frame);
  CGFloat x = [screen[@"x"] doubleValue], y = [screen[@"y"] doubleValue], w = [screen[@"width"] doubleValue];
  CGFloat h = [screen[@"height"] doubleValue], r = [screen[@"cornerRadius"] doubleValue];
  CGColorSpaceRef cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  CGContextRef ctx = CGBitmapContextCreate(NULL, W, H, 8, 0, cs, kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
  CGRect sr = CGRectMake(x, H - y - h, w, h);
  CGPathRef rr = CGPathCreateWithRoundedRect(sr, r, r, NULL);
  CGContextAddPath(ctx, rr);
  CGContextClip(ctx);
  if (lit) {
    // A wallpaper-like gradient, a grid, a top bar and a mark in the top left, so turns and mirrors show.
    CGFloat c0[] = {cover ? 0.10 : 0.16, cover ? 0.55 : 0.30, cover ? 0.45 : 0.75, 1, cover ? 0.95 : 0.98, cover ? 0.80 : 0.55, 0.25, 1};
    CGGradientRef g = CGGradientCreateWithColorComponents(cs, c0, NULL, 2);
    CGContextDrawLinearGradient(ctx, g, CGPointMake(x, H - y), CGPointMake(x + w, H - y - h), 0);
    CGGradientRelease(g);
    CGContextSetRGBStrokeColor(ctx, 1, 1, 1, 0.25);
    CGContextSetLineWidth(ctx, 2);
    for (CGFloat gx = x; gx < x + w; gx += 120) { CGContextMoveToPoint(ctx, gx, H - y); CGContextAddLineToPoint(ctx, gx, H - y - h); }
    for (CGFloat gy = 0; gy < h; gy += 120) { CGContextMoveToPoint(ctx, x, H - y - gy); CGContextAddLineToPoint(ctx, x + w, H - y - gy); }
    CGContextStrokePath(ctx);
    CGContextSetRGBFillColor(ctx, 1, 1, 1, 0.85);
    CGContextFillRect(ctx, CGRectMake(x, H - y - 90, w, 90));
    CGContextSetRGBFillColor(ctx, 0.9, 0.1, 0.1, 1);
    CGContextFillRect(ctx, CGRectMake(x + 60, H - y - 330, 200, 200));
    CGContextSetRGBFillColor(ctx, 1, 1, 1, 0.9);
    for (int i = 0; i < 6; i++) CGContextFillRect(ctx, CGRectMake(x + 60, H - y - 460 - i * 150, w * 0.6, 70));
  } else {
    CGContextSetRGBFillColor(ctx, 0.01, 0.01, 0.015, 1);
    CGContextFillRect(ctx, sr);
  }
  CGContextResetClip(ctx);
  CGPathRelease(rr);
  CGContextDrawImage(ctx, CGRectMake(0, 0, W, H), frame);
  CGImageRef img = CGBitmapContextCreateImage(ctx);
  CGContextRelease(ctx);
  CGColorSpaceRelease(cs);
  return img;
}

int main(int argc, char **argv) {
  @autoreleasepool {
    if (argc < 6) {
      fprintf(stderr, "usage: fold-frames SKIN_DIR OUT_DIR book|clamshell|trifold THICKNESS COVER_ZOOM [FRAMES] [SECONDS]\n");
      return 2;
    }
    NSString *dir = @(argv[1]);
    NSDictionary *skin = readJson([dir stringByAppendingPathComponent:@"skin.json"]);
    if (!skin || !skin[@"cover"]) {
      fprintf(stderr, "no folding skin.json in %s\n", argv[1]);
      return 2;
    }
    CGImageRef openFrame = loadPng([dir stringByAppendingPathComponent:skin[@"frame"]]);
    CGImageRef coverFrame = loadPng([dir stringByAppendingPathComponent:skin[@"cover"][@"frame"]]);
    int hinge = !strcmp(argv[3], "clamshell") ? FOLD_CLAMSHELL : !strcmp(argv[3], "trifold") ? FOLD_TRIFOLD : FOLD_BOOK;
    double zoom = atof(argv[5]);
    int frames = argc > 6 ? atoi(argv[6]) : 13;
    double seconds = argc > 7 ? atof(argv[7]) : 1;
    // The window at half size, as on a Retina screen at actual size.
    CGFloat s = 2;
    CGFloat ow = CGImageGetWidth(openFrame) / s, oh = CGImageGetHeight(openFrame) / s;
    CGFloat cw = CGImageGetWidth(coverFrame) / (s * zoom), ch = CGImageGetHeight(coverFrame) / (s * zoom);
    NSDictionary *sc = skin[@"screen"];
    FoldSpec f = {0};
    f.edge = -1;
    // The skin's fold look: its back picture, metal, outline and keys (docs/skins.md).
    NSDictionary *look = skin[@"fold"];
    size_t PW = CGImageGetWidth(openFrame), PH = CGImageGetHeight(openFrame);
    if (look[@"back"]) f.back = loadPng([dir stringByAppendingPathComponent:look[@"back"]]);
    if ([look[@"edge"] isKindOfClass:NSString.class]) f.edge = (int)strtol([look[@"edge"] UTF8String] + 1, NULL, 16);
    NSDictionary *b = look[@"body"];
    if (b) {
      f.body = CGRectMake([b[@"x"] doubleValue] / PW, [b[@"y"] doubleValue] / PH, [b[@"width"] doubleValue] / PW, [b[@"height"] doubleValue] / PH);
      f.bodyRadius = [b[@"radius"] doubleValue] / PH;
    }
    for (NSArray *k in look[@"keys"]) {
      if (f.keys >= 4) break;
      f.key[2 * f.keys] = [k[0] doubleValue] / PH;
      f.key[2 * f.keys + 1] = [k[1] doubleValue] / PH;
      f.keys++;
    }
    f.hinge = hinge;
    f.thickness = atof(argv[4]);
    f.openLit = devicePicture(openFrame, sc, YES, NO);
    f.openDark = devicePicture(openFrame, sc, NO, NO);
    f.closedLit = devicePicture(coverFrame, skin[@"cover"][@"screen"], YES, YES);
    f.closedDark = devicePicture(coverFrame, skin[@"cover"][@"screen"], NO, YES);
    f.openRect = CGRectMake(0, 0, ow, oh);
    f.closedRect = CGRectMake(ow / 2 - cw / 2, oh / 2 - ch / 2, cw, ch);
    size_t FW = CGImageGetWidth(openFrame), FH = CGImageGetHeight(openFrame);
    f.openScreen = CGRectMake([sc[@"x"] doubleValue] / FW, [sc[@"y"] doubleValue] / FH, [sc[@"width"] doubleValue] / FW,
                              [sc[@"height"] doubleValue] / FH);
    f.seconds = seconds;
    for (int closing = 1; closing >= 0; closing--) {
      f.closing = closing;
      foldDump(&f, argv[2], frames);
    }
    printf("wrote %d frames of each to %s\n", frames, argv[2]);
  }
  return 0;
}
