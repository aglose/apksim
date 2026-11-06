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
  override fun onCreate() {
    super.onCreate()
    created = true
  }

  companion object {
    @Volatile var created = false
  }
}
