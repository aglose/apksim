// Spike S1 (docs/spike-s1-shared-surfaces.md): a stand-in host app that SHOWS the instance's screen in a window and
// measures present-to-glass: the instance's present time (epoch us, from the `f` line or the stream header) to the
// time the display showed the frame (the CAMetalLayer drawable's presentedTime).
//   surfaceshow PORT mach SECONDS   IOSurface Mach ports (`surfacewatch mach NAME`), drawn with no CPU copy
//   surfaceshow PORT h264 SECONDS   today's `stream 80 h264 4000`, decoded by VideoToolbox into IOSurface buffers
// Both paths end the same way: the reader thread hands the newest frame to the window's display link (asking for
// 120 Hz), which turns its IOSurface into a Metal texture, blits it into a drawable and presents it.
// So the difference between them is only how the pixels arrive. A drawable that was never shown (replaced by a newer
// one before its vsync) counts as dropped.
// Build: clang -O2 -fobjc-arc -framework Cocoa -framework Metal -framework QuartzCore -framework IOSurface
//        -framework VideoToolbox -framework CoreMedia -framework CoreVideo -o work/surfaceshow
//        scripts/spike-s1/surfaceshow.m
#import <Cocoa/Cocoa.h>
#import <CoreMedia/CoreMedia.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <VideoToolbox/VideoToolbox.h>
#include <arpa/inet.h>
#include <mach/mach.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <servers/bootstrap.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>

static int64_t nowUs(void) {
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return (int64_t)tv.tv_sec * 1000000 + tv.tv_usec;
}
static double cpuSec(void) {
  struct rusage r;
  getrusage(RUSAGE_SELF, &r);
  return r.ru_utime.tv_sec + r.ru_stime.tv_sec + (r.ru_utime.tv_usec + r.ru_stime.tv_usec) / 1e6;
}

static int fd;
static FILE *in;
static id<MTLDevice> dev;
static id<MTLCommandQueue> queue;
static CAMetalLayer *layer;
static double epochMinusMedia;  // epoch seconds - CACurrentMediaTime(), to put presentedTime on the instance's clock
static NSMutableArray<NSNumber *> *toGlass, *toHost;
static long received, shown, dropped, copyUsSum;
static NSLock *lock;
static CGSize layerSize;

// The reader thread hands over the newest frame; the display link draws it at the next refresh. A frame replaced
// before a refresh took it is "superseded" (the host's display is slower than the instance or a refresh was missed).
static CFTypeRef pendingOwner;  // retains the surface (the IOSurface itself, or the decoder's CVPixelBuffer)
static IOSurfaceRef pendingSurface;
static int64_t pendingPresentUs, pendingHostUs;
static long superseded, ticks;
static NSMutableArray<NSNumber *> *commitToGlass;  // the host's own share: its commit to the display's scan-out

static void offer(CFTypeRef owner, IOSurfaceRef s, int64_t presentUs) {
  int64_t hostUs = nowUs();
  CFRetain(owner);
  [lock lock];
  received++;
  if (pendingOwner) CFRelease(pendingOwner), superseded++;
  pendingOwner = owner, pendingSurface = s, pendingPresentUs = presentUs, pendingHostUs = hostUs;
  [lock unlock];
}

static void drawPending(void) {
  [lock lock];
  CFTypeRef owner = pendingOwner;
  IOSurfaceRef s = pendingSurface;
  int64_t presentUs = pendingPresentUs, hostUs = pendingHostUs;
  pendingOwner = NULL;
  [lock unlock];
  if (!owner) return;
  size_t w = IOSurfaceGetWidth(s), h = IOSurfaceGetHeight(s);
  if (!CGSizeEqualToSize(layerSize, CGSizeMake(w, h))) layer.drawableSize = layerSize = CGSizeMake(w, h);
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                               width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageShaderRead;
  d.storageMode = MTLStorageModeShared;
  id<MTLTexture> src = [dev newTextureWithDescriptor:d iosurface:s plane:0];
  id<CAMetalDrawable> drawable = [layer nextDrawable];
  if (!src || !drawable) {
    CFRelease(owner);
    return;
  }
  id<MTLCommandBuffer> cb = [queue commandBuffer];
  id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
  [blit copyFromTexture:src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
             sourceSize:MTLSizeMake(MIN(w, drawable.texture.width), MIN(h, drawable.texture.height), 1)
              toTexture:drawable.texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
  [blit endEncoding];
  CFTimeInterval commitT = CACurrentMediaTime();
  [drawable addPresentedHandler:^(id<MTLDrawable> dr) {
    CFTimeInterval t = dr.presentedTime;
    [lock lock];
    if (t == 0) {
      dropped++;
    } else {
      shown++;
      [toGlass addObject:@((int64_t)((t + epochMinusMedia) * 1e6) - presentUs)];
      [toHost addObject:@(hostUs - presentUs)];
      [commitToGlass addObject:@((int64_t)((t - commitT) * 1e6))];
    }
    [lock unlock];
  }];
  [cb addCompletedHandler:^(id<MTLCommandBuffer> c) {
    CFRelease(owner);  // the blit has read the surface
  }];
  [cb presentDrawable:drawable];
  [cb commit];
}

@interface Ticker : NSObject
@end
@implementation Ticker
- (void)tick:(CADisplayLink *)link {
  ticks++;
  drawPending();
}
@end

// --- shared surfaces over Mach ---
static int machPath(double seconds) {
  mach_port_t recv = MACH_PORT_NULL;
  char name[128];
  snprintf(name, sizeof name, "dev.apksim.spike.surfaces.%d", getpid());
  kern_return_t kr = bootstrap_check_in(bootstrap_port, name, &recv);
  if (kr != KERN_SUCCESS) return fprintf(stderr, "bootstrap_check_in: %s\n", bootstrap_strerror(kr)), 1;
  dprintf(fd, "surfacewatch mach %s\n", name);
  IOSurfaceRef ring[3] = {0};
  char line[512];
  if (!fgets(line, sizeof line, in) || strncmp(line, "ok", 2)) return fprintf(stderr, "instance: %s", line), 1;
  int64_t end = nowUs() + (int64_t)(seconds * 1e6);
  while (nowUs() < end && fgets(line, sizeof line, in)) {
    if (line[0] == 's') {
      for (int i = 0; i < 3; i++)
        if (ring[i]) CFRelease(ring[i]), ring[i] = NULL;
      struct {
        mach_msg_header_t header;
        mach_msg_body_t body;
        mach_msg_port_descriptor_t ports[3];
        int32_t w, h;
        mach_msg_trailer_t trailer;
      } m = {0};
      kr = mach_msg(&m.header, MACH_RCV_MSG | MACH_RCV_TIMEOUT, 0, sizeof m, recv, 1000, MACH_PORT_NULL);
      if (kr != KERN_SUCCESS) return fprintf(stderr, "mach_msg: %s\n", mach_error_string(kr)), 1;
      for (int i = 0; i < 3; i++) {
        ring[i] = IOSurfaceLookupFromMachPort(m.ports[i].name);
        mach_port_deallocate(mach_task_self(), m.ports[i].name);
      }
      if (!ring[0] || !ring[1] || !ring[2]) return fprintf(stderr, "surfaces not found\n"), 1;
    } else if (line[0] == 'f') {
      long seq;
      int idx, copyUs;
      long long presentUs;
      sscanf(line, "f %ld %d %lld %d", &seq, &idx, &presentUs, &copyUs);
      copyUsSum += copyUs;
      if (ring[idx]) offer(ring[idx], ring[idx], presentUs);
    }
  }
  return 0;
}

// --- H.264 stream ---
static VTDecompressionSessionRef session;
static CMVideoFormatDescriptionRef format;
static int64_t pendingPresent;

static void decoded(void *ref, void *frameRef, OSStatus st, VTDecodeInfoFlags fl, CVImageBufferRef img, CMTime pts,
                    CMTime dur) {
  if (st != noErr || !img) return;
  IOSurfaceRef s = CVPixelBufferGetIOSurface(img);
  if (s) offer(img, s, pendingPresent);
}

static int readFull(void *b, size_t n) { return fread(b, 1, n, in) == n; }

static int h264Path(double seconds) {
  dprintf(fd, "stream 80 h264 4000\n");
  char line[256];
  if (!fgets(line, sizeof line, in) || !strstr(line, "h264")) return fprintf(stderr, "instance: %s", line), 1;
  NSMutableData *buf = [NSMutableData data];
  int64_t end = nowUs() + (int64_t)(seconds * 1e6);
  while (nowUs() < end) {
    uint32_t len;
    if (!readFull(&len, 4)) break;
    len = ntohl(len);
    [buf setLength:len];
    uint8_t *p = buf.mutableBytes;
    if (!readFull(p, len)) break;
    int64_t present = 0;
    for (int i = 0; i < 8; i++) present = present << 8 | p[8 + i];
    copyUsSum += (int32_t)ntohl(*(uint32_t *)(p + 16));
    const uint8_t *au = p + 21;
    size_t n = len - 21;
    const uint8_t *sps = NULL, *pps = NULL;
    size_t spsN = 0, ppsN = 0;
    NSMutableData *avcc = [NSMutableData data];
    for (size_t i = 0; i + 3 <= n;) {
      if (!(au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1)) {
        i++;
        continue;
      }
      size_t s = i + 3, e = s;
      while (e + 3 <= n && !(au[e] == 0 && au[e + 1] == 0 && (au[e + 2] == 1 || (au[e + 2] == 0 && e + 3 < n && au[e + 3] == 1)))) e++;
      if (e + 3 > n) e = n;
      int type = au[s] & 0x1f;
      if (type == 7) sps = au + s, spsN = e - s;
      else if (type == 8) pps = au + s, ppsN = e - s;
      else {
        uint32_t l = htonl((uint32_t)(e - s));
        [avcc appendBytes:&l length:4];
        [avcc appendBytes:au + s length:e - s];
      }
      i = e;
    }
    if (sps && pps) {
      const uint8_t *sets[2] = {sps, pps};
      size_t sizes[2] = {spsN, ppsN};
      CMVideoFormatDescriptionRef f = NULL;
      CMVideoFormatDescriptionCreateFromH264ParameterSets(NULL, 2, sets, sizes, 4, &f);
      if (!format || !CMFormatDescriptionEqual(format, f)) {
        if (session) VTDecompressionSessionInvalidate(session), CFRelease(session), session = NULL;
        if (format) CFRelease(format);
        format = f;
        VTDecompressionOutputCallbackRecord cb = {decoded, NULL};
        NSDictionary *attrs = @{(id)kCVPixelBufferPixelFormatTypeKey : @(kCVPixelFormatType_32BGRA),
                                (id)kCVPixelBufferIOSurfacePropertiesKey : @{},
                                (id)kCVPixelBufferMetalCompatibilityKey : @YES};
        VTDecompressionSessionCreate(NULL, format, NULL, (__bridge CFDictionaryRef)attrs, &cb, &session);
      } else if (f) {
        CFRelease(f);
      }
    }
    if (!session || !avcc.length) continue;
    CMBlockBufferRef block = NULL;
    CMBlockBufferCreateWithMemoryBlock(NULL, (void *)avcc.bytes, avcc.length, kCFAllocatorNull, NULL, 0, avcc.length,
                                       0, &block);
    CMSampleBufferRef sample = NULL;
    size_t sz = avcc.length;
    CMSampleBufferCreateReady(NULL, block, format, 1, 0, NULL, 1, &sz, &sample);
    pendingPresent = present;
    VTDecompressionSessionDecodeFrame(session, sample, 0, NULL, NULL);  // synchronous
    CFRelease(sample);
    CFRelease(block);
  }
  return 0;
}

static void report(const char *mode, double seconds, double cpu) {
  [lock lock];
  NSArray *g = [toGlass sortedArrayUsingSelector:@selector(compare:)];
  NSArray *h = [toHost sortedArrayUsingSelector:@selector(compare:)];
  NSArray *c = [commitToGlass sortedArrayUsingSelector:@selector(compare:)];
  [lock unlock];
  if (!g.count) {
    printf("%s: received %ld, none shown (is the display awake?)\n", mode, received);
    return;
  }
#define Q(a, q) ([a[(long)(((long)a.count - 1) * (q))] longLongValue] / 1000.0)
  printf("%s: received %ld (%.1f/s), shown %ld (%.1f/s), superseded %ld, dropped %ld; present->host ms p50 %.2f p90 %.2f; "
         "present->glass ms p50 %.2f p90 %.2f p99 %.2f max %.2f (host commit->glass p50 %.2f p90 %.2f); instance copy/encode %.2f ms a frame; host CPU %.1f%%; "
         "display %ld Hz, display link %.1f ticks/s\n",
         mode, received, received / seconds, shown, shown / seconds, superseded, dropped, Q(h, 0.5), Q(h, 0.9), Q(g, 0.5),
         Q(g, 0.9), Q(g, 0.99), Q(g, 1.0), Q(c, 0.5), Q(c, 0.9), copyUsSum / 1000.0 / MAX(received, 1), 100 * cpu / seconds,
         (long)NSScreen.mainScreen.maximumFramesPerSecond, ticks / seconds);
}

int main(int argc, char **argv) {
  @autoreleasepool {
    if (argc < 4) return fprintf(stderr, "usage: surfaceshow PORT mach|h264 SECONDS\n"), 2;
    toGlass = [NSMutableArray array];
    toHost = [NSMutableArray array];
    commitToGlass = [NSMutableArray array];
    lock = [NSLock new];
    fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(atoi(argv[1])), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (connect(fd, (struct sockaddr *)&a, sizeof a)) return perror("connect"), 1;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    in = fdopen(fd, "r");

    NSApplication *app = NSApplication.sharedApplication;
    app.activationPolicy = NSApplicationActivationPolicyRegular;
    dev = MTLCreateSystemDefaultDevice();
    queue = [dev newCommandQueue];
    NSWindow *win = [[NSWindow alloc] initWithContentRect:NSMakeRect(80, 80, 411, 891)
                                                styleMask:NSWindowStyleMaskTitled backing:NSBackingStoreBuffered
                                                    defer:NO];
    win.title = @"surfaceshow";
    layer = [CAMetalLayer layer];
    layer.device = dev;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = NO;  // blit destination
    layer.maximumDrawableCount = getenv("DRAWABLES") ? atoi(getenv("DRAWABLES")) : 3;
    layer.displaySyncEnabled = YES;
    layer.contentsScale = win.backingScaleFactor;
    win.contentView.wantsLayer = YES;
    win.contentView.layer = layer;
    [win orderFrontRegardless];
    Ticker *ticker = [Ticker new];
    CADisplayLink *link = [win.contentView displayLinkWithTarget:ticker selector:@selector(tick:)];
    link.preferredFrameRateRange = CAFrameRateRangeMake(120, 120, 120);
    [link addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    epochMinusMedia = nowUs() / 1e6 - CACurrentMediaTime();

    NSString *mode = @(argv[2]);
    double seconds = atof(argv[3]);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
      usleep(300000);  // let the window reach the screen
      double cpu0 = cpuSec();
      int rc = [mode isEqual:@"h264"] ? h264Path(seconds) : machPath(seconds);
      double cpu = cpuSec() - cpu0;
      usleep(100000);  // the last presented handlers
      if (!rc) report(mode.UTF8String, seconds, cpu);
      fflush(stdout);
      exit(rc);
    });
    [app run];
  }
  return 0;
}
