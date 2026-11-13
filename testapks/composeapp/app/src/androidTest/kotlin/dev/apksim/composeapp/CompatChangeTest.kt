package dev.apksim.composeapp

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Platform compat changes follow the app's targetSdk (apksim's CompatTable): the test app targets 36, so a change
 * enabled since 36 is on, one enabled since 37 is off, and a disabled one is off, as `dumpsys platform_compat` lists
 * them on an API 36 device (the ids are from it). Robolectric answered true for all three. Asked through
 * CompatChanges.isChangeEnabled(long), by reflection as it is a system API; the uid and package overloads and the
 * `compat` shell command are apksim's alone (a device wants a permission for them).
 */
@RunWith(AndroidJUnit4::class)
class CompatChangeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val onApksim = Build.FINGERPRINT == "robolectric"

  private val sinceTarget36 = 364980165L  // ENABLE_FILE_SYSTEM_ACCESS
  private val sinceTarget37 = 297019750L  // RENDERSCRIPT_SCRIPTC_DEPRECATION_CHANGE_ID
  private val disabled = 189969744L  // DOWNSCALE_65

  private val compatChanges = Class.forName("android.app.compat.CompatChanges")

  private fun enabled(id: Long): Boolean =
    compatChanges.getMethod("isChangeEnabled", Long::class.javaPrimitiveType).invoke(null, id) as Boolean

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  @Test
  fun changeEnabledSinceTheAppsTargetIsOn() {
    assertEquals(36, instrumentation.targetContext.applicationInfo.targetSdkVersion)
    assertTrue(enabled(sinceTarget36))
  }

  @Test
  fun changeEnabledSinceALaterTargetIsOff() {
    assertEquals(false, enabled(sinceTarget37))
  }

  @Test
  fun disabledChangeIsOff() {
    assertEquals(false, enabled(disabled))
  }

  @Test
  fun uidAndPackageOverloadsAgree() {
    if (!onApksim) return
    val pkg = instrumentation.targetContext.packageName
    val user = Process.myUserHandle()
    val m = compatChanges.getMethod("isChangeEnabled", Long::class.javaPrimitiveType, String::class.java, android.os.UserHandle::class.java)
    val u = compatChanges.getMethod("isChangeEnabled", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    for ((id, want) in listOf(sinceTarget36 to true, sinceTarget37 to false, disabled to false)) {
      assertEquals("package $id", want, m.invoke(null, id, pkg, user) as Boolean)
      assertEquals("uid $id", want, u.invoke(null, id, Process.myUid()) as Boolean)
    }
  }

  @Test
  fun compatCommandListsWhatWasAsked() {
    if (!onApksim) return
    enabled(sinceTarget37)
    val out = shell("apksim compat")
    assertTrue(out, out.contains("RENDERSCRIPT_SCRIPTC_DEPRECATION_CHANGE_ID (297019750): off"))
    assertTrue(out, shell("apksim compat all").contains("target 36"))
  }
}
