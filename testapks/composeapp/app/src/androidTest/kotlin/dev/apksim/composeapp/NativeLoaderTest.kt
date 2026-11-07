package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * The native loader fixture (testapks/loader-fixture/loaderfixture.c): the stack guard read through the thread
 * pointer, thread-local variables on several threads, barriers, mutexes and condition variables at 4 mod 8, thread attributes
 * and setjmp/longjmp, as Android code uses them. Each passes on a device; on the Mac and in both Linux images (arm64
 * and x86_64) they check the loader and the bionic shims. A broken stack guard aborts the process instead of failing.
 */
@RunWith(AndroidJUnit4::class)
class NativeLoaderTest {
  // A broken barrier, wait or join would hang: the watchdog then ends the process and instrument.py runs the rest.
  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  @Test fun stackGuardIsStableOnEveryThread() = assertEquals("ok", LoaderFixture.check("stackGuard"))

  @Test fun threadLocalsArePerThreadAndKeepRegisters() = assertEquals("ok", LoaderFixture.check("tls"))

  @Test fun mutexesWorkAtFourModEight() = assertEquals("ok", LoaderFixture.check("mutex"))

  @Test fun conditionVariablesWorkAtFourModEight() = assertEquals("ok", LoaderFixture.check("cond"))

  @Test fun threadAttributesReachTheThread() = assertEquals("ok", LoaderFixture.check("threadAttr"))

  @Test fun barriersCanBeReusedAndDestroyedAtOnce() = assertEquals("ok", LoaderFixture.check("barrier"))

  @Test fun setjmpStaysInsideItsBuffer() = assertEquals("ok", LoaderFixture.check("setjmp"))

  // A library linked for 4 KB pages (an NDK before r27) whose code shares a page with RELRO: on macOS (16 KB pages,
  // never writable and executable at once) the page is read+execute once relocated. Relocated pointers in RELRO,
  // .data and .bss on pages of their own, an exported variable and calls through the PLT on the shared page all work.
  @Test fun codeSharingAPageWithRelroLoadsAndRuns() {
    PageFixtures.load4k()
    assertEquals("ok", PageFixtures.check4k())
    assertEquals("ok", PageFixtures.check4k())
  }

  // The same with -z norelro: writable data (the table, .data, the GOT, .dynamic) on the page with code. On macOS the
  // loader moves it to pages of its own and repoints the code (adrp) and relocations at it.
  @Test fun writableDataSharingAPageWithCodeLoadsAndRuns() {
    PageFixtures.loadNoRelro()
    assertEquals("ok", PageFixtures.checkNoRelro())
    assertEquals("ok", PageFixtures.checkNoRelro())
  }

  // Moved data a C++ exception's unwinding needs (the personality slot .eh_frame reaches indirectly): thrown three
  // frames deep and caught in the same library.
  @Test fun aCppExceptionIsCaughtWithItsPersonalitySlotMoved() {
    PageFixtures.loadCxx()
    assertEquals("ok", PageFixtures.checkCxx())
    assertEquals("ok", PageFixtures.checkCxx())
  }

  // On the Mac, moving libpagefixturefar.so's data puts it out of an adr's reach: every try is an
  // UnsatisfiedLinkError naming the APK entry that the app can catch (the failed load gives its address space back).
  // Elsewhere it loads and runs.
  @Test fun dataMovedOutOfReachIsALinkErrorEveryTime() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) {
      PageFixtures.loadFar()
      assertEquals("ok", PageFixtures.checkFar())
      return
    }
    repeat(5) {
      val e = assertThrows(UnsatisfiedLinkError::class.java) { PageFixtures.loadFar() }
      val message = e.message.orEmpty()
      assertTrue(message, message.contains("lib/arm64-v8a/libpagefixturefar.so is 4 KB aligned"))
      assertTrue(message, message.contains("out of reach"))
    }
  }

  // --skip-library: the app's System.loadLibrary throws as for a library the APK lacks (run with
  // `--skip-library pagefixtureskipped -e skipLibrary true`; on a device the library does not exist at all).
  @Test fun aSkippedLibraryIsALinkErrorNamingItsEntry() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("skipLibrary") == "true")
    val e = assertThrows(UnsatisfiedLinkError::class.java) { System.loadLibrary("pagefixtureskipped") }
    assertTrue(e.message, e.message.orEmpty().contains("lib/arm64-v8a/libpagefixtureskipped.so is skipped") ||
      e.message.orEmpty().contains("lib/x86_64/libpagefixtureskipped.so is skipped"))
  }
}
