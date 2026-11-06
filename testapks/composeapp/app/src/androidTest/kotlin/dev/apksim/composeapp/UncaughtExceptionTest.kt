package dev.apksim.composeapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In a running instance an uncaught exception ends the process with FATAL EXCEPTION, as on a device (issue 104,
 * scripts/ci-smoke.sh checks it). Test runs keep their behaviour: apksim installs no default handler there, the one
 * AndroidJUnitRunner's onCreate set (MonitoringInstrumentation's) gets a coroutine's uncaught exception, logs it
 * ("Handling an uncaught exception thrown on the thread ...") and passes it on to none, and the run goes on.
 */
@RunWith(AndroidJUnit4::class)
class UncaughtExceptionTest {
  @Test
  fun aCoroutineCrashInATestRunGoesToTheRunnersHandler() {
    // On a device MonitoringInstrumentation passes it on to RuntimeInit's handler, which ends the instrumentation run.
    assumeTrue("apksim only", Build.FINGERPRINT == "robolectric")
    val handler = Thread.getDefaultUncaughtExceptionHandler()
    assertFalse("apksim's crash handler in a test run: $handler", handler?.javaClass?.name.orEmpty().startsWith("dev.apksim.Crashes"))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("apksim://crash?kind=coroutine"))
      .setClassName(InstrumentationRegistry.getInstrumentation().targetContext, CrashActivity::class.java.name)
    ActivityScenario.launch<CrashActivity>(intent).use {
      SystemClock.sleep(1000)  // past the coroutine's 300 ms delay
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()  // it runs, and throws, here
    }
  }
}
