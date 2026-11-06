package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The suite's runner prepares the run as on a device: the listener its onCreate adds (HarnessRunner, RecordingListener)
 * hears the tests, and the Test Storage service takes the files a test or listener writes and answers the run's
 * arguments. CI checks that the file RecordingListener writes after each test's teardown is under --test-outputs.
 */
@RunWith(AndroidJUnit4::class)
class RunnerListenersTest {
  @Test
  fun theRunnersListenerHearsTheTest() {
    val me = "theRunnersListenerHearsTheTest(${RunnerListenersTest::class.java.name})"
    assertTrue("RecordingListener heard: ${RecordingListener.started}", me in RecordingListener.started)
  }

  @Test
  fun testStorageKeepsAnOutputFile() {
    val storage = TestStorage()
    storage.openOutputFile("storage-check/out.txt").use { it.write("written\n".toByteArray()) }
    storage.openOutputFile("storage-check/out.txt", true).use { it.write("appended\n".toByteArray()) }
    val uri = android.net.Uri.parse("content://androidx.test.services.storage.outputfiles/storage-check/out.txt")
    val text = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      .contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
    assertEquals("written\nappended\n", text)
  }

  @Test
  fun testStorageAnswersTheRunsArguments() {
    val run = androidx.test.platform.app.InstrumentationRegistry.getArguments()
    val expected = run.keySet().associateWith { run.get(it).toString() }
    assertEquals(expected, TestStorage().inputArgs)
  }
}
