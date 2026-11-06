package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
}
