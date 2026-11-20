package dev.apksim.composeapp

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `setprop log.tag.TAG LEVEL` changes what `Log.isLoggable(TAG, level)` answers, as on a device (issue 554): some apps and
 * SDKs log below INFO only when it says so.
 */
@RunWith(AndroidJUnit4::class)
class LogTagTest {
  private val tag = "ApksimLogTag"

  private fun shell(cmd: String) {
    val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
  }

  @After
  fun reset() {
    shell("setprop log.tag.$tag ''")
    shell("setprop persist.log.tag ''")
  }

  @Test
  fun aTagLevelChangesIsLoggable() {
    shell("setprop log.tag.$tag I")
    assertFalse(Log.isLoggable(tag, Log.DEBUG))
    assertTrue(Log.isLoggable(tag, Log.INFO))
    shell("setprop log.tag.$tag D")
    assertTrue(Log.isLoggable(tag, Log.DEBUG))
    assertFalse(Log.isLoggable(tag, Log.VERBOSE))
    shell("setprop log.tag.$tag VERBOSE")
    assertTrue(Log.isLoggable(tag, Log.VERBOSE))
    shell("setprop log.tag.$tag E")
    assertFalse(Log.isLoggable(tag, Log.WARN))
    assertTrue(Log.isLoggable(tag, Log.ERROR))
    shell("setprop log.tag.$tag S")
    assertFalse(Log.isLoggable(tag, Log.ASSERT))
  }

  @Test
  fun persistLogTagIsEveryTagsDefaultAndAnUnknownValueIsPassedOver() {
    shell("setprop persist.log.tag V")
    assertTrue(Log.isLoggable(tag, Log.VERBOSE))
    shell("setprop log.tag.$tag W")
    assertFalse(Log.isLoggable(tag, Log.INFO))  // the tag's own wins
    shell("setprop log.tag.$tag x")
    assertTrue(Log.isLoggable(tag, Log.VERBOSE))  // not a level: the next property decides, as liblog does
    shell("setprop persist.log.tag ''")
    shell("setprop log.tag.$tag ''")
    assertFalse(Log.isLoggable(tag, Log.DEBUG))
    assertTrue(Log.isLoggable(tag, Log.INFO))
  }

  @Test
  fun aLineBelowTheLevelIsStillWritten() {
    // A device's logd keeps every line: log.tag changes only what isLoggable answers.
    shell("logcat -c")  // the shell's log is kept from a test's first logcat on
    shell("setprop log.tag.$tag E")
    Log.d(tag, "apksim log tag line")
    val out = run {
      val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -s $tag:V")
      ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }
    assertTrue(out, out.contains("apksim log tag line"))
  }
}
