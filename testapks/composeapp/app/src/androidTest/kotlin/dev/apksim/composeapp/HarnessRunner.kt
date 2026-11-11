package dev.apksim.composeapp

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The suite's instrumentation: an AndroidJUnitRunner that swaps in a test Application, as large suites do for a test
 * dependency graph, and only for the app's own Application class (the class name a device passes is the manifest's).
 * On a device the runner makes the Application; apksim asks this one's newApplication which class it wants
 * (RunnerApplication) and gives every test that. Its onCreate also adds a JUnit listener to the run's arguments before
 * AndroidJUnitRunner's, as report libraries' runners do (RunnerListenersTest).
 */
class HarnessRunner : AndroidJUnitRunner() {
  override fun onCreate(arguments: Bundle) {
    onCreateTargetPackage = targetContext?.packageName  // set up before onCreate, as on a device
    val listeners = arguments.getString("listener")
    arguments.putString(
      "listener", listOfNotNull(listeners, RecordingListener::class.java.name).joinToString(","))
    super.onCreate(arguments)
  }

  override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
    super.newApplication(
      cl, if (className == ComposeApp::class.java.name) HarnessTestApplication::class.java.name else className, context)
}

/** What HarnessRunner.onCreate saw (RunnerListenersTest). */
@Volatile var onCreateTargetPackage: String? = null

/** The test Application HarnessRunner asks for: the app's own, with a test's additions. */
class HarnessTestApplication : ComposeApp() {
  // Shapes AppCallbacksConformanceTest checks: a register method that forwards to super, a listener registered in
  // attachBaseContext, and one object every onCreate registers (on a device each runs once, on one Application).
  override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) =
    super.registerActivityLifecycleCallbacks(callback)

  override fun attachBaseContext(base: android.content.Context) {
    super.attachBaseContext(base)
    early += EarlyListener().also { registerActivityLifecycleCallbacks(it) }
  }

  /** Counts resumes. */
  open class EarlyListener : ActivityLifecycleCallbacks {
    val resumes = java.util.concurrent.atomic.AtomicInteger()
    override fun onActivityResumed(activity: android.app.Activity) { resumes.incrementAndGet() }
    override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
  }

  object EveryCreate : EarlyListener()

  override fun onCreate() {
    super.onCreate()
    created = true
    registerActivityLifecycleCallbacks(EveryCreate)
    // A per-Application ComponentCallbacks, new with each Application (AppCallbacksConformanceTest).
    perApplication += PerApplicationMemory().also { registerComponentCallbacks(it) }
    onNextCreate?.also { onNextCreate = null }?.invoke(this)
  }

  /** Counts trim-memory and configuration calls. */
  class PerApplicationMemory : android.content.ComponentCallbacks2 {
    @Volatile var trims = 0
    override fun onTrimMemory(level: Int) { trims++ }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    @Deprecated("ComponentCallbacks") override fun onLowMemory() {}
  }

  companion object {
    @Volatile var created = false

    /** Run once at the next test's Application.onCreate (a test run's new Application per test; AppCallbacksConformanceTest). */
    @Volatile var onNextCreate: ((HarnessTestApplication) -> Unit)? = null

    /** Every EarlyListener attachBaseContext registered, oldest first. */
    val early = java.util.concurrent.CopyOnWriteArrayList<EarlyListener>()

    /** Every PerApplicationMemory, oldest first: one per Application this process made. */
    val perApplication = java.util.concurrent.CopyOnWriteArrayList<PerApplicationMemory>()
  }
}
