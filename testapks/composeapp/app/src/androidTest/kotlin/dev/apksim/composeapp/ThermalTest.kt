package dev.apksim.composeapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.Executor

/**
 * PowerManager's thermal status (apksim's Thermal, issue 149): a listener registered with an Executor is called at once
 * with THERMAL_STATUS_NONE instead of addThermalStatusListener throwing, hears each change in order, stops hearing once
 * removed, and agrees with getCurrentThermalStatus. The `thermal` shell command, which sets the status, is apksim's
 * alone; each test starts again from NONE with no listeners, as Robolectric resets a device.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ThermalTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
  private val onApksim = Build.FINGERPRINT == "robolectric"
  private val inline = Executor { it.run() }

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  @Test
  fun a_aListenerIsCalledAtOnceWithNone() {
    assumeTrue("on a device the first call comes on a binder thread, with whatever status it has", onApksim)
    val heard = mutableListOf<Int>()
    val l = PowerManager.OnThermalStatusChangedListener { heard += it }
    pm.addThermalStatusListener(inline, l)
    try {
      assertEquals(listOf(PowerManager.THERMAL_STATUS_NONE), heard)
      assertEquals(PowerManager.THERMAL_STATUS_NONE, pm.currentThermalStatus)
    } finally {
      pm.removeThermalStatusListener(l)
    }
  }

  @Test
  fun b_changesReachListenersInOrderAndTheGetterAgrees() {
    assumeTrue("the thermal shell command is apksim's", onApksim)
    val heard = mutableListOf<Int>()
    val delivered = mutableListOf<String>()
    val l = PowerManager.OnThermalStatusChangedListener { heard += it }
    pm.addThermalStatusListener({ r -> delivered += Thread.currentThread().name; r.run() }, l)
    shell("apksim thermal moderate")
    assertEquals(PowerManager.THERMAL_STATUS_MODERATE, pm.currentThermalStatus)
    shell("apksim thermal severe")
    assertEquals(PowerManager.THERMAL_STATUS_SEVERE, pm.currentThermalStatus)
    shell("apksim thermal none")
    assertEquals(PowerManager.THERMAL_STATUS_NONE, pm.currentThermalStatus)
    assertEquals(
      listOf(
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_MODERATE,
        PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_NONE,
      ),
      heard,
    )
    assertEquals("every call went through the listener's own executor", 4, delivered.size)
    // Left at SEVERE on purpose: the next test must start from NONE again.
    shell("apksim thermal severe")
    pm.removeThermalStatusListener(l)
  }

  @Test
  fun c_eachTestStartsFromNoneWithNoListeners() {
    assumeTrue("Robolectric's per-test reset", onApksim)
    assertEquals(PowerManager.THERMAL_STATUS_NONE, pm.currentThermalStatus)
    // The listener of the last test was removed, but a reset would drop a forgotten one too: none hears this.
    val heard = mutableListOf<Int>()
    val l = PowerManager.OnThermalStatusChangedListener { heard += it }
    pm.addThermalStatusListener(inline, l)
    assertEquals(listOf(PowerManager.THERMAL_STATUS_NONE), heard)
    pm.removeThermalStatusListener(l)
  }

  @Test
  fun d_aRemovedListenerHearsNothingMoreAndADuplicateIsOnce() {
    assumeTrue("the thermal shell command is apksim's", onApksim)
    val heard = mutableListOf<Int>()
    val l = PowerManager.OnThermalStatusChangedListener { heard += it }
    pm.addThermalStatusListener(inline, l)
    pm.addThermalStatusListener(inline, l)
    shell("apksim thermal light")
    assertEquals(listOf(PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT), heard)
    pm.removeThermalStatusListener(l)
    shell("apksim thermal critical")
    assertEquals(2, heard.size)
    assertEquals(PowerManager.THERMAL_STATUS_CRITICAL, pm.currentThermalStatus)
  }
}
