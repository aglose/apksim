package dev.apksim.composeapp

/**
 * Two libraries testapks/loader-fixture builds for 4 KB pages (pagefixture.c), loaded only when asked: on macOS, which
 * maps 16 KB pages, libpagefixture4k.so has code and RELRO on one page, libpagefixturenorelro.so code and writable
 * data. Each check returns "ok" or what went wrong. NativeLoaderTest runs them.
 */
object PageFixtures {
  fun load4k() = System.loadLibrary("pagefixture4k")

  fun loadNoRelro() = System.loadLibrary("pagefixturenorelro")

  external fun check4k(): String

  external fun checkNoRelro(): String
}
