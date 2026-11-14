package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A provider that refuses a second onCreate in one process (LeakCanary's installer) does not fail the tests after a
 * process's first: a device creates providers once per process (issue 353). Two tests, so one of them is never the
 * process's first (it checks nothing under --isolate, where each test is).
 */
@RunWith(AndroidJUnit4::class)
class OnceOnlyProviderTest {
  @Test
  fun theProviderIsInstalledForTheFirstTest() {
    assertTrue(OnceOnlyProvider.installed)
  }

  @Test
  fun theProviderIsStillInstalledForTheNextTest() {
    assertTrue(OnceOnlyProvider.installed)
  }
}
