package dev.apksim.composeapp

/**
 * The native library testapks/loader-fixture builds (libloaderfixture.so): checks of what apksim's ELF loader and
 * bionic shims must get right for Android code. Each returns "ok" or what went wrong. NativeLoaderTest runs them.
 */
object LoaderFixture {
  init {
    System.loadLibrary("loaderfixture")
  }

  /** One of stackGuard, tls, mutex, cond, threadAttr, setjmp. */
  external fun check(name: String): String
}
