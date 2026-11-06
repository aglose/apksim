package dev.apksim.composeapp

import androidx.test.services.storage.TestStorage
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * The listener HarnessRunner adds to the run, as a report library's runner adds its own. It records the tests it
 * hears, and when one ends (after the test's teardown, as a report library writes its result) it writes a file named
 * after the test through the Test Storage service: test_outputfiles/listener/<class>#<method>.txt.
 */
class RecordingListener : RunListener() {
  override fun testStarted(description: Description) {
    started += description.displayName
    // The Application the instrumentation points at when the test's start is heard: this test's, as on a device.
    applicationAtStart[description.displayName] = System.identityHashCode(
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
  }

  override fun testFinished(description: Description) {
    TestStorage().openOutputFile("listener/${description.className}#${description.methodName}.txt").use {
      it.write("finished\n".toByteArray())
    }
  }

  companion object {
    val started: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val applicationAtStart: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
  }
}
