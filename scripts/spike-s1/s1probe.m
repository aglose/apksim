// Spike S1: the two hand-offs alone, to run the instance's half inside a sandbox profile (the launcher's
// APKSIM_SANDBOX_TEST runs a command in its profile; plain sandbox-exec for others) while the host's half runs outside.
//   s1probe serve            host: checks in a bootstrap name, prints it, waits 10 s for a surface's port
//   s1probe send NAME        instance: makes a surface, fills it with 0x5A, sends its Mach port to NAME
//   s1probe make             instance: makes a global surface filled with 0x5A, prints its ID, keeps it 5 s
//   s1probe read ID          host: IOSurfaceLookup(ID) and checks the fill
// Build: clang -O2 -fobjc-arc -framework Foundation -framework IOSurface -o work/s1probe scripts/spike-s1/s1probe.m
#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#include <mach/mach.h>
#include <servers/bootstrap.h>
#include <unistd.h>
#pragma clang diagnostic ignored "-Wdeprecated-declarations"

static IOSurfaceRef make(BOOL global) {
  NSMutableDictionary *p = [@{(id)kIOSurfaceWidth : @64, (id)kIOSurfaceHeight : @64, (id)kIOSurfaceBytesPerElement : @4,
                              (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA')} mutableCopy];
  if (global) p[(id)kIOSurfaceIsGlobal] = @YES;
  IOSurfaceRef s = IOSurfaceCreate((__bridge CFDictionaryRef)p);
  if (!s) return NULL;
  IOSurfaceLock(s, 0, NULL);
  memset(IOSurfaceGetBaseAddress(s), 0x5A, IOSurfaceGetAllocSize(s));
  IOSurfaceUnlock(s, 0, NULL);
  return s;
}

static int check(IOSurfaceRef s, const char *how) {
  if (!s) return printf("%s: no surface\n", how), 1;
  IOSurfaceLock(s, kIOSurfaceLockReadOnly, NULL);
  int ok = ((uint8_t *)IOSurfaceGetBaseAddress(s))[100] == 0x5A;
  IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, NULL);
  printf("%s: surface %u, pixels %s\n", how, IOSurfaceGetID(s), ok ? "ok" : "WRONG");
  return !ok;
}

typedef struct {
  mach_msg_header_t header;
  mach_msg_body_t body;
  mach_msg_port_descriptor_t port;
} Msg;

int main(int argc, char **argv) {
  if (argc < 2) return 2;
  if (!strcmp(argv[1], "serve")) {
    char name[96];
    snprintf(name, sizeof name, "dev.apksim.spike.probe.%d", getpid());
    mach_port_t recv;
    kern_return_t kr = bootstrap_check_in(bootstrap_port, name, &recv);
    if (kr) return printf("bootstrap_check_in: %s\n", bootstrap_strerror(kr)), 1;
    printf("%s\n", name);
    fflush(stdout);
    struct { Msg m; mach_msg_trailer_t t; } r = {0};
    kr = mach_msg(&r.m.header, MACH_RCV_MSG | MACH_RCV_TIMEOUT, 0, sizeof r, recv, 10000, MACH_PORT_NULL);
    if (kr) return printf("receive: %s\n", mach_error_string(kr)), 1;
    return check(IOSurfaceLookupFromMachPort(r.m.port.name), "host got the port");
  }
  if (!strcmp(argv[1], "send") && argc > 2) {
    IOSurfaceRef s = make(NO);
    if (!s) return printf("IOSurfaceCreate failed\n"), 1;
    mach_port_t host;
    kern_return_t kr = bootstrap_look_up(bootstrap_port, argv[2], &host);
    if (kr) return printf("bootstrap_look_up(%s): %s (%d)\n", argv[2], bootstrap_strerror(kr), kr), 1;
    Msg m = {0};
    m.header.msgh_bits = MACH_MSGH_BITS(MACH_MSG_TYPE_COPY_SEND, 0) | MACH_MSGH_BITS_COMPLEX;
    m.header.msgh_size = sizeof m;
    m.header.msgh_remote_port = host;
    m.body.msgh_descriptor_count = 1;
    m.port = (mach_msg_port_descriptor_t){.name = IOSurfaceCreateMachPort(s), .disposition = MACH_MSG_TYPE_MOVE_SEND,
                                          .type = MACH_MSG_PORT_DESCRIPTOR};
    kr = mach_msg(&m.header, MACH_SEND_MSG | MACH_SEND_TIMEOUT, sizeof m, 0, MACH_PORT_NULL, 1000, MACH_PORT_NULL);
    printf("send: %s\n", kr ? mach_error_string(kr) : "ok");
    return kr != 0;
  }
  if (!strcmp(argv[1], "make")) {
    IOSurfaceRef s = make(YES);
    if (!s) return printf("IOSurfaceCreate failed\n"), 1;
    printf("%u\n", IOSurfaceGetID(s));
    fflush(stdout);
    sleep(5);
    return 0;
  }
  if (!strcmp(argv[1], "read") && argc > 2) return check(IOSurfaceLookup((IOSurfaceID)strtoul(argv[2], NULL, 10)), "lookup");
  return 2;
}
