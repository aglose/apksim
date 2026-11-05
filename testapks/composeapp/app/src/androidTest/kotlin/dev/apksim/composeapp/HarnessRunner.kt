package dev.apksim.composeapp

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * The suite's instrumentation: an AndroidJUnitRunner that swaps in a test Application, as large suites do for a test
 * dependency graph. On a device the runner makes the Application; apksim asks this one's newApplication which class
 * it wants (RunnerApplication) and gives every test that.
 */
class HarnessRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
    super.newApplication(cl, HarnessTestApplication::class.java.name, context)
}

/** The test Application HarnessRunner asks for. */
class HarnessTestApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    created = true
  }

  companion object {
    @Volatile var created = false
  }
}
