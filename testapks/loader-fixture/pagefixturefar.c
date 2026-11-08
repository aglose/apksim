// libpagefixturefar.so (build.sh: 4 KB pages, -z norelro), for NativeLoaderTest. On macOS its writable data shares
// a 16 KB page with its code and, with --move-shared-data, must move past the image, which a 2 MB .bss makes more than 1 MB away: the adr below
// (arm64 assembly) can then no longer reach it, so the load must fail with an UnsatisfiedLinkError naming the APK
// entry, every time it is tried. Elsewhere it loads and the check passes. x86_64 has no adr: a plain C stand-in.
#include <jni.h>

__attribute__((used, visibility("hidden"))) unsigned long far_target = 42;  // .data, right after the code
__attribute__((used)) static volatile char far_bss[2 << 20];              // pushes the move past adr's 1 MB

unsigned long *far_adr(void);
#if defined(__aarch64__)
__asm__(".text\n.p2align 2\n.hidden far_adr\n.globl far_adr\n.type far_adr, %function\n"
        "far_adr:\n  adr x0, far_target\n  ret\n");
#else
__attribute__((noinline, visibility("hidden"))) unsigned long *far_adr(void) { return &far_target; }
#endif

JNIEXPORT jstring JNICALL Java_dev_apksim_composeapp_PageFixtures_checkFar(JNIEnv *env, jobject self) {
  (void)self;
  far_bss[sizeof far_bss - 1] = 1;
  int ok = far_adr() == &far_target && *far_adr() == 42 && far_bss[sizeof far_bss - 1] == 1;
  far_bss[sizeof far_bss - 1] = 0;
  return (*env)->NewStringUTF(env, ok ? "ok" : "adr does not reach far_target");
}
