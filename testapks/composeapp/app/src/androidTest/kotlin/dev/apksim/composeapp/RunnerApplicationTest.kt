package dev.apksim.composeapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Application is the one the suite's runner asks for in newApplication (HarnessRunner), and it was created. */
@RunWith(AndroidJUnit4::class)
class RunnerApplicationTest {
  @Test
  fun theApplicationIsTheRunnersChoice() {
    val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    assertEquals(HarnessTestApplication::class.java, app.javaClass)
    assertEquals(HarnessTestApplication::class.java, InstrumentationRegistry.getInstrumentation().targetContext.applicationContext.javaClass)
    assertTrue("HarnessTestApplication.onCreate did not run", HarnessTestApplication.created)
  }
}
