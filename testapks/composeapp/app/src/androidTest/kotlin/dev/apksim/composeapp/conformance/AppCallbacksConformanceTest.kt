package dev.apksim.composeapp.conformance

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.apksim.composeapp.HarnessTestApplication
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * A process-wide singleton's Application callbacks, across a suite's tests as on a device's one Application: it
 * registers once and keeps the Application it registered on; later tests' activities still reach it, and it can
 * unregister through that Application (docs/conformance.md).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppCallbacksConformanceTest {
  /** Counts resumes; a class of its own per use, so the carry-over's class check cannot mix them up. */
  open class Resumes : Application.ActivityLifecycleCallbacks {
    val resumes = AtomicInteger()
    override fun onActivityResumed(activity: Activity) { resumes.incrementAndGet() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
  }

  object Leaving : Resumes()
  object Arriving : Resumes() {
    @Volatile var handedOver = false
  }

  /** A process singleton's ComponentCallbacks2, registered once on the Application it keeps. */
  object Memory : ComponentCallbacks2 {
    var app: Application? = null
    @Volatile var trims = 0
    @Volatile var configurations = 0
    override fun onTrimMemory(level: Int) { trims++ }
    override fun onConfigurationChanged(newConfig: Configuration) { configurations++ }
    @Deprecated("ComponentCallbacks") override fun onLowMemory() {}
  }

  object Singleton : Application.ActivityLifecycleCallbacks {
    var app: Application? = null
    val resumes = AtomicInteger()
    fun ensureRegistered() {
      if (app != null) return
      app = ApplicationProvider.getApplicationContext()
      app!!.registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) { resumes.incrementAndGet() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
  }

  private fun launchAndCountResumes(): Int {
    Singleton.resumes.set(0)
    ActivityScenario.launch(ComponentActivity::class.java).use {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    return Singleton.resumes.get()
  }

  @Test
  fun a_aSingletonRegistersOnce() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { Singleton.ensureRegistered() }
    assertTrue(launchAndCountResumes() >= 1)
  }

  @Test
  fun b_aLaterTestsActivitiesReachItUntilItUnregisters() {
    // Each test in a process of its own (--isolate, the Orchestrator) has no singleton from before.
    assumeTrue(Singleton.app != null)
    assertTrue("the singleton hears a later test's activity", launchAndCountResumes() >= 1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      Singleton.app!!.unregisterActivityLifecycleCallbacks(Singleton)  // through the Application it kept
    }
    assertEquals("unregistered, it hears nothing more", 0, launchAndCountResumes())
    Singleton.app = null
  }

  /**
   * apksim only (a device has one Application, whose onCreate does not run again): what a singleton registers and
   * unregisters through the Application it kept while the next test's Application starts is what the next test has.
   */
  @Test
  fun c_aSingletonChangesItsRegistrationsWhileTheNextApplicationStarts() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val kept: Application = ApplicationProvider.getApplicationContext()
      kept.registerActivityLifecycleCallbacks(Leaving)
      HarnessTestApplication.onNextCreate = {
        kept.unregisterActivityLifecycleCallbacks(Leaving)
        kept.registerActivityLifecycleCallbacks(Arriving)
        Arriving.handedOver = true
      }
    }
  }

  @Test
  fun d_theNextTestHasThoseChanges() {
    assumeTrue("a new Application started since the last test", Arriving.handedOver)
    HarnessTestApplication.onNextCreate = null
    Leaving.resumes.set(0)
    Arriving.resumes.set(0)
    ActivityScenario.launch(ComponentActivity::class.java).use {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    assertEquals("unregistered while the next Application started", 0, Leaving.resumes.get())
    assertTrue("registered while the next Application started", Arriving.resumes.get() >= 1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      ApplicationProvider.getApplicationContext<Application>().unregisterActivityLifecycleCallbacks(Arriving)
    }
  }

  @Test
  fun e_aSingletonRegistersComponentCallbacksOnce() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      if (Memory.app == null) {
        Memory.app = ApplicationProvider.getApplicationContext()
        Memory.app!!.registerComponentCallbacks(Memory)
      }
    }
  }

  /**
   * A later test's Application delivers trim-memory and configuration calls to the singleton's ComponentCallbacks2 and
   * to the per-Application one its own onCreate registered, not to the ones earlier Applications registered; the
   * singleton unregisters through the Application it kept.
   */
  @Test
  fun f_aLaterTestsApplicationCallsThemUntilTheSingletonUnregisters() {
    assumeTrue(Memory.app != null)
    val perApplication = HarnessTestApplication.perApplication
    val current = perApplication.last()
    perApplication.forEach { it.trims = 0 }
    Memory.trims = 0
    Memory.configurations = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val app = ApplicationProvider.getApplicationContext<Application>()
      app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
      app.onConfigurationChanged(Configuration(app.resources.configuration))
    }
    assertEquals("the singleton's trim-memory call", 1, Memory.trims)
    assertEquals("the singleton's configuration call", 1, Memory.configurations)
    assertEquals("this Application's own callback", 1, current.trims)
    assertEquals("earlier Applications' callbacks are gone", 0, perApplication.dropLast(1).sumOf { it.trims })
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      Memory.app!!.unregisterComponentCallbacks(Memory)  // through the Application it kept
      ApplicationProvider.getApplicationContext<Application>().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }
    assertEquals("unregistered, it hears nothing more", 1, Memory.trims)
    Memory.app = null
  }
}
