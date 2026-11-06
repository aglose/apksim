package dev.apksim.composeapp

import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * A running instance keeps its data slot's /sdcard and Settings values across launches (SlotDevice,
 * scripts/slot-check.sh); a test run does not: every test starts from a clean device, but for the values given with
 * apksim's `--setting`, which each test's setup applies before Application.onCreate (CI runs this class with
 * `-e settingSeed V` and `--setting global:apksim_slot_seed=V`, and `-e animSeed A` with
 * `--setting global:animator_duration_scale=A`, and `-e fontSeed F` with two font_scale seeds, the last one F).
 * apksim only.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)  // the test that writes first, then one that must not see it
class SlotStateTest {
  private val args = InstrumentationRegistry.getArguments()
  private val seed = args.getString("settingSeed")

  @Before
  fun onApksimOnly() {
    assumeTrue("apksim's data slots", Build.FINGERPRINT == "robolectric")
  }

  @Test
  fun b_aTestStartsWithNothingInSdcardOrTheSettingsButTheSeed() {
    assertEquals("setting=null seed=$seed sdcard=missing", ComposeApp.slotAtCreate)
  }

  @Test
  fun c_aSeededSettingIsThereForEveryTest() {
    assumeTrue("run with -e settingSeed V and --setting global:apksim_slot_seed=V", seed != null)
    val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    assertEquals(seed, Settings.Global.getString(cr, SlotStateActivity.SEED))
  }

  @Test
  fun d_aSeedForADeviceControlsKeyGoesThroughThatControl() {
    // --setting global:animator_duration_scale=V (CI: -e animSeed V) does what `animscale V` does, not only the value.
    val anim = args.getString("animSeed")?.toFloat()
    assumeTrue("run with -e animSeed V and --setting global:animator_duration_scale=V", anim != null)
    val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    assertEquals(anim!!, android.animation.ValueAnimator.getDurationScale(), 0f)
    assertEquals(anim, Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
  }

  @Test
  fun e_aSeededFontScaleReachesTheTestsActivities() {
    // --setting system:font_scale=F (the last one given, CI: -e fontSeed F): what `fontscale F` does, and not undone
    // by the test's own configuration set up after Application.onCreate.
    val font = args.getString("fontSeed")?.toFloat()
    assumeTrue("run with -e fontSeed F and --setting system:font_scale=F", font != null)
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(font!!, app.resources.configuration.fontScale, 0f)
    // And the setting a device's font-size control writes beside the configuration.
    assertEquals(font, Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE), 0f)
    androidx.test.core.app.ActivityScenario.launch(SlotStateActivity::class.java).use { scenario ->
      scenario.onActivity { assertEquals(font, it.resources.configuration.fontScale, 0f) }
    }
  }

  @Test
  fun a_whatATestWritesIsGoneForTheNext() {
    // Written here; the next test (b_) must not see it.
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    Settings.Global.putString(context.contentResolver, SlotStateActivity.SETTING, "written")
    java.io.File(android.os.Environment.getExternalStorageDirectory(), SlotStateActivity.FILE).writeText("written")
    assertEquals("setting=written seed=$seed sdcard=written", SlotStateActivity.describe(context))
  }

  @Test
  fun f_aShellFontScaleUpdatesTheSettingAndObservers() {
    // `settings put system font_scale V` through the test run's shell does what `fontscale V` does: the configuration,
    // Settings.System.FONT_SCALE and that setting's observers (DeviceControls.setFontScale).
    assumeTrue("apksim's shell and font_scale control", Build.FINGERPRINT == "robolectric")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val resolver = context.contentResolver
    var notified = 0
    // No handler: the observer is called on the thread that notifies, as in BluetoothTest.
    val observer = object : ContentObserver(null) {
      override fun onChange(selfChange: Boolean) {
        notified++
      }
    }
    resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.FONT_SCALE), false, observer)
    try {
      val pfd = instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.5")
      android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
      assertEquals(1.5f, Settings.System.getFloat(resolver, Settings.System.FONT_SCALE), 0f)
      assertEquals(1.5f, context.resources.configuration.fontScale, 0f)
      assertTrue("the observer of font_scale was called", notified > 0)
    } finally {
      resolver.unregisterContentObserver(observer)
    }
  }
}
