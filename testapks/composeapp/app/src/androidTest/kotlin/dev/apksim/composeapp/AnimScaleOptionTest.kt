package dev.apksim.composeapp

import android.animation.ValueAnimator
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * apksim's `--anim-scale N`: the animator duration scale every test starts with, as `adb shell settings put global
 * animator_duration_scale N` before a run gives on a device. CI runs this class with `-e animScale 0` and
 * `--anim-scale 0`; without the argument it is skipped. The app's onCreate already sees it. The first test changes the
 * scale, and the second must find it set again, since apksim applies the option at each test's setup.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AnimScaleOptionTest {
  private val expected = InstrumentationRegistry.getArguments().getString("animScale")

  @Before
  fun withTheOption() {
    assumeTrue("run with -e animScale N and --anim-scale N", expected != null)
  }

  private fun assertScale() {
    val scale = expected!!.toFloat()
    assertEquals("ValueAnimator (Views)", scale, ValueAnimator.getDurationScale())
    val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    assertEquals("the global setting (Compose)", scale,
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
  }

  @Test
  fun a_theScaleIsSetWhenTheTestStarts() {
    assertEquals("in Application.onCreate", expected!!.toFloat(), ComposeApp.animScaleAtCreate)
    assertScale()
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("settings put global animator_duration_scale 5")
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    assertEquals(5f, ValueAnimator.getDurationScale())
  }

  @Test
  fun b_andSetAgainForTheNextTest() {
    assertScale()
  }
}
