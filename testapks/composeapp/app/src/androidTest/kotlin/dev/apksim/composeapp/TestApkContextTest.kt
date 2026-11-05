package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The instrumentation's own context is the test APK's, as on a device: its package and its assets (JSON fixtures). */
@RunWith(AndroidJUnit4::class)
class TestApkContextTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun theTestApksAssetsAreItsContexts() {
    val text = instrumentation.context.assets.open("test_fixture.json").bufferedReader().readText()
    assertTrue(text, text.contains("from the test APK"))
  }

  @Test
  fun theTestApksResourcesAreItsContexts() {
    val res = instrumentation.context.resources
    val id = res.getIdentifier("test_apk_greeting", "string", instrumentation.context.packageName)
    assertTrue("the test APK's string resource", id != 0)
    assertEquals("from the test APK's resources", res.getString(id))
  }

  @Test
  fun itsPackageIsTheTestApks() {
    assertEquals(instrumentation.targetContext.packageName + ".test", instrumentation.context.packageName)
  }
}
