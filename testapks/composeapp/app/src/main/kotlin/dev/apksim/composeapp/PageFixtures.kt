package dev.apksim.composeapp

/**
 * Two libraries testapks/loader-fixture builds for 4 KB pages (pagefixture.c), loaded only when asked: on macOS, which
 * maps 16 KB pages, libpagefixture4k.so has code and RELRO on one page, libpagefixturenorelro.so code and writable
 * data. Each check returns "ok" or what went wrong. NativeLoaderTest runs them.
 */
object PageFixtures {
  fun load4k() = System.loadLibrary("pagefixture4k")

  fun loadNoRelro() = System.loadLibrary("pagefixturenorelro")

  /** libpagefixturefar.so: on macOS its moved data is beyond an adr's reach, so it must not load. */
  fun loadFar() = System.loadLibrary("pagefixturefar")

  /** libpagefixturecxx.so: a C++ exception thrown and caught with its personality slot moved. */
  fun loadCxx() = System.loadLibrary("pagefixturecxx")

  external fun check4k(): String

  external fun checkNoRelro(): String

  external fun checkFar(): String

  external fun checkCxx(): String
}
