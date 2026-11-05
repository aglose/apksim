package dev.apksim.composeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * bindService to a service in a companion app that isn't installed returns false and never calls back, as on a
 * device. The connection is Kotlin's, with non-null parameters, so a callback with null arguments would throw.
 */
@RunWith(AndroidJUnit4::class)
class BindServiceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  private class Recording : ServiceConnection {
    var calls = 0
    override fun onServiceConnected(name: ComponentName, service: IBinder) { calls++ }
    override fun onServiceDisconnected(name: ComponentName) { calls++ }
    override fun onNullBinding(name: ComponentName) { calls++ }
  }

  @Test
  fun bindingAServiceThatIsNotInstalledReturnsFalse() {
    val connection = Recording()
    val intent = Intent("com.example.companion.ACTION_BIND").setPackage("com.example.companion.notinstalled")
    var bound = true
    instrumentation.runOnMainSync { bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
    instrumentation.waitForIdleSync()
    assertFalse(bound)
    assertEquals(0, connection.calls)
  }

  @Test
  fun bindingThisAppsOwnServiceStillWorks() {
    // Nullable parameters: whether a null binding reaches onNullBinding is not what this checks.
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
      override fun onServiceDisconnected(name: ComponentName?) {}
    }
    var bound = false
    instrumentation.runOnMainSync {
      bound = context.bindService(Intent(context, UploadService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    instrumentation.waitForIdleSync()
    assertTrue(bound)
    instrumentation.runOnMainSync { context.unbindService(connection) }
  }
}
