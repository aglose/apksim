// libpagefixturecxx.so (build.sh: 4 KB pages, -z norelro, libc++ linked in), for NativeLoaderTest. Its writable data,
// DW.ref.__gxx_personality_v0 among it, shares a 16 KB page with its code, so on macOS the loader moves it; .eh_frame
// reaches that personality slot PC-relative and indirect, and must be pointed at the moved copy or a C++ exception
// thrown here is never caught (std::terminate). Elsewhere it loads as it is.
#include <jni.h>

#include <stdexcept>
#include <string>

static int depth = 3;  // .data

// build.sh sets TEXT_PAD when the code would otherwise end too close to a 16 KB boundary for the data to share its
// last page: padding (never run) that moves the end of the code on.
#if TEXT_PAD
#define PAD_STR2(s) #s
#define PAD_STR(s) PAD_STR2(s)
__asm__(".text\n.space " PAD_STR(TEXT_PAD) "\n");
#endif

__attribute__((noinline)) static int thrower(int n) {
  if (n <= 0) throw std::runtime_error("thrown in libpagefixturecxx");
  return thrower(n - 1) + 1;
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_apksim_composeapp_PageFixtures_checkCxx(JNIEnv *env, jobject) {
  std::string result = "not thrown";
  try {
    thrower(depth);
  } catch (const std::runtime_error &e) {
    result = std::string(e.what()) == "thrown in libpagefixturecxx" ? "ok" : std::string("caught ") + e.what();
  }
  return env->NewStringUTF(result.c_str());
}
