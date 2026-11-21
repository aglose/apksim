// Spike S1 (docs/spike-s1-shared-surfaces.md): a stand-in for the host app. It attaches to a running instance's
// control port and receives the screen one of three ways, then prints present-to-host latency:
//   surfacehost PORT global SECONDS [DUMP.ppm]  IOSurfaces by global ID (`surfacewatch global`, IOSurfaceLookup)
//   surfacehost PORT mach SECONDS [DUMP.ppm]    IOSurface Mach ports sent to a bootstrap service this process checks in
//   surfacehost PORT h264 SECONDS               today's `stream 80 h264 4000`, decoded with VideoToolbox
// Latency is the host's wall clock when the frame is readable here (surface: its pixels sampled; H.264: the decoder's
// output callback) less the instance's present time (epoch microseconds in the `f` line or the stream's header).
// Build: clang -O2 -fobjc-arc -framework Foundation -framework IOSurface -framework VideoToolbox -framework CoreMedia
//        -framework CoreVideo -o work/surfacehost scripts/spike-s1/surfacehost.m
#import <CoreMedia/CoreMedia.h>
#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#import <VideoToolbox/VideoToolbox.h>
#include <arpa/inet.h>
#include <mach/mach.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <servers/bootstrap.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

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
static NSMutableArray<NSNumber *> *lat;
static NSMutableSet<NSNumber *> *sums;
static int64_t firstUs, lastUs;
static long frames, gaps, lastSeq, copyUsSum;  // copyUsSum: the instance's copy into the ring (surfaces) or encode (H.264)

static void record(int64_t presentUs, uint32_t sum) {
  int64_t t = nowUs();
  if (!frames) firstUs = t;
  lastUs = t;
  frames++;
  [lat addObject:@(t - presentUs)];
  [sums addObject:@(sum)];
}

static uint32_t sample(IOSurfaceRef s) {  // reads 256 pixels along the diagonal: proof the pixels are there and change
  IOSurfaceLock(s, kIOSurfaceLockReadOnly, NULL);
  const uint8_t *p = IOSurfaceGetBaseAddress(s);
  size_t w = IOSurfaceGetWidth(s), h = IOSurfaceGetHeight(s), st = IOSurfaceGetBytesPerRow(s);
  uint32_t sum = 2166136261u;
  for (int i = 0; i < 256; i++) sum = (sum ^ *(const uint32_t *)(p + (h * i / 256) * st + (w * i / 256) * 4)) * 16777619u;
  IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, NULL);
  return sum;
}

static void dump(IOSurfaceRef s, const char *path) {
  IOSurfaceLock(s, kIOSurfaceLockReadOnly, NULL);
  const uint8_t *p = IOSurfaceGetBaseAddress(s);
  size_t w = IOSurfaceGetWidth(s), h = IOSurfaceGetHeight(s), st = IOSurfaceGetBytesPerRow(s);
  FILE *f = fopen(path, "wb");
  fprintf(f, "P6 %zu %zu 255\n", w, h);
  for (size_t y = 0; y < h; y++)
    for (size_t x = 0; x < w; x++) {
      const uint8_t *q = p + y * st + x * 4;
      uint8_t rgb[3] = {q[2], q[1], q[0]};  // BGRA in memory
      fwrite(rgb, 1, 3, f);
    }
  fclose(f);
  IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, NULL);
}

// --- shared surfaces ---
static int surfaces(int mach, double seconds, const char *dumpPath) {
  mach_port_t recv = MACH_PORT_NULL;
  char name[128];
  snprintf(name, sizeof name, "dev.apksim.spike.surfaces.%d", getpid());
  if (mach) {
    kern_return_t kr = bootstrap_check_in(bootstrap_port, name, &recv);
    if (kr != KERN_SUCCESS) {
      fprintf(stderr, "bootstrap_check_in(%s): %s (%d)\n", name, bootstrap_strerror(kr), kr);
      return 1;
    }
    dprintf(fd, "surfacewatch mach %s\n", name);
  } else {
    dprintf(fd, "surfacewatch global\n");
  }
  IOSurfaceRef ring[3] = {0};
  char line[512];
  if (!fgets(line, sizeof line, in) || strncmp(line, "ok", 2)) {
    fprintf(stderr, "instance: %s", line);
    return 1;
  }
  int64_t end = nowUs() + (int64_t)(seconds * 1e6);
  while (nowUs() < end && fgets(line, sizeof line, in)) {
    if (line[0] == 's') {
      int gen, w, h;
      unsigned ids[3] = {0};
      sscanf(line, "s %d %d %d %u %u %u", &gen, &w, &h, &ids[0], &ids[1], &ids[2]);
      for (int i = 0; i < 3; i++)
        if (ring[i]) CFRelease(ring[i]);
      if (mach) {
        struct {
          mach_msg_header_t header;
          mach_msg_body_t body;
          mach_msg_port_descriptor_t ports[3];
          int32_t w, h;
          mach_msg_trailer_t trailer;
        } m = {0};
        kern_return_t kr = mach_msg(&m.header, MACH_RCV_MSG | MACH_RCV_TIMEOUT, 0, sizeof m, recv, 1000, MACH_PORT_NULL);
        if (kr != KERN_SUCCESS) {
          fprintf(stderr, "mach_msg receive: %s\n", mach_error_string(kr));
          return 1;
        }
        for (int i = 0; i < 3; i++) {
          ring[i] = IOSurfaceLookupFromMachPort(m.ports[i].name);
          mach_port_deallocate(mach_task_self(), m.ports[i].name);
        }
      } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        for (int i = 0; i < 3; i++) ring[i] = IOSurfaceLookup(ids[i]);
#pragma clang diagnostic pop
      }
      fprintf(stderr, "ring %d: %dx%d, surfaces %s\n", gen, w, h,
              ring[0] && ring[1] && ring[2] ? "found" : "NOT found");
      if (!ring[0] || !ring[1] || !ring[2]) return 1;
    } else if (line[0] == 'f') {
      long seq;
      int idx, copyUs;
      long long presentUs;
      sscanf(line, "f %ld %d %lld %d", &seq, &idx, &presentUs, &copyUs);
      copyUsSum += copyUs;
      if (lastSeq && seq != lastSeq + 1) gaps++;
      lastSeq = seq;
      if (ring[idx]) record(presentUs, sample(ring[idx]));
      if (dumpPath && ring[idx] && nowUs() >= end - 50000) {
        dump(ring[idx], dumpPath);
        dumpPath = NULL;
      }
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
  record(pendingPresent, s ? sample(s) : 0);
}

static int readFull(void *b, size_t n) {
  return fread(b, 1, n, in) == n;
}

static int h264(double seconds) {
  dprintf(fd, "stream 80 h264 4000\n");
  char line[256];
  if (!fgets(line, sizeof line, in) || !strstr(line, "h264")) {
    fprintf(stderr, "instance: %s", line);
    return 1;
  }
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
    const uint8_t *au = p + 21;  // w, h, present, encode time, keyframe byte
    size_t n = len - 21;
    // Annex B NAL units: SPS/PPS make the format; the rest go out with 4-byte lengths (AVCC).
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
                                (id)kCVPixelBufferIOSurfacePropertiesKey : @{}};
        OSStatus st = VTDecompressionSessionCreate(NULL, format, NULL, (__bridge CFDictionaryRef)attrs, &cb, &session);
        if (st) fprintf(stderr, "VTDecompressionSessionCreate: %d\n", (int)st);
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
    VTDecompressionSessionDecodeFrame(session, sample, 0, NULL, NULL);  // synchronous: the callback runs before this returns
    CFRelease(sample);
    CFRelease(block);
  }
  return 0;
}

int main(int argc, char **argv) {
  @autoreleasepool {
    if (argc < 4) {
      fprintf(stderr, "usage: surfacehost PORT global|mach|h264 SECONDS [DUMP.ppm]\n");
      return 2;
    }
    lat = [NSMutableArray array];
    sums = [NSMutableSet set];
    fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(atoi(argv[1])), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (connect(fd, (struct sockaddr *)&a, sizeof a)) return perror("connect"), 1;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    in = fdopen(fd, "r");
    double cpu0 = cpuSec(), seconds = atof(argv[3]);
    int rc = !strcmp(argv[2], "h264") ? h264(seconds) : surfaces(!strcmp(argv[2], "mach"), seconds, argc > 4 ? argv[4] : NULL);
    double cpu = cpuSec() - cpu0;
    if (rc || !frames) return rc ? rc : (fprintf(stderr, "no frames\n"), 1);
    NSArray *s = [lat sortedArrayUsingSelector:@selector(compare:)];
    long n = s.count;
#define P(q) ([s[(long)((n - 1) * (q))] longLongValue] / 1000.0)
    double span = (lastUs - firstUs) / 1e6;
    printf("%s: %ld frames in %.1f s (%.1f fps), %ld seq gaps, %lu distinct samples; present->host ms p50 %.2f p90 %.2f "
           "p99 %.2f max %.2f; instance copy/encode %.2f ms a frame; host CPU %.1f%%\n",
           argv[2], frames, span, (frames - 1) / span, gaps, (unsigned long)sums.count, P(0.5), P(0.9), P(0.99), P(1.0), copyUsSum / 1000.0 / frames,
           100 * cpu / seconds);
  }
  return 0;
}
