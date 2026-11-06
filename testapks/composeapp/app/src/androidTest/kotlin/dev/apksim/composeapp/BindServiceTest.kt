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

  /**
   * A connection bound to one of the app's services, then refused a bind to a service nobody has: unbinding it still
   * ends the binding that succeeded (onUnbind, and onDestroy as no binding is left), and the refusal calls nothing.
   */
  @Test
  fun unbindingAfterARefusedBindStillEndsTheBindingThatSucceeded() {
    NullBindingService.events.clear()
    val connection = Recording()
    val missing = Intent("com.example.companion.ACTION_BIND").setPackage("com.example.companion.notinstalled")
    instrumentation.runOnMainSync {
      assertTrue(context.bindService(Intent(context, NullBindingService::class.java), connection, Context.BIND_AUTO_CREATE))
    }
    waitFor { connection.calls == 1 }  // onNullBinding
    instrumentation.runOnMainSync {
      assertFalse(context.bindService(missing, connection, Context.BIND_AUTO_CREATE))
      context.unbindService(connection)
    }
    waitFor { "destroy" in NullBindingService.events }
    assertEquals(listOf("create", "bind", "unbind", "destroy"), NullBindingService.events.toList())
    assertEquals(1, connection.calls)
  }

  /**
   * One connection bound twice to an app's service, unbound (which ends both bindings), then refused a bind to a
   * service nobody has and unbound again: nothing after the first unbind calls back.
   */
  @Test
  fun aConnectionBoundTwiceThenRefusedUnbindsQuietly() {
    NullBindingService.events.clear()
    val connection = Recording()
    val missing = Intent("com.example.companion.ACTION_BIND").setPackage("com.example.companion.notinstalled")
    instrumentation.runOnMainSync {
      assertTrue(context.bindService(Intent(context, NullBindingService::class.java), connection, Context.BIND_AUTO_CREATE))
      assertTrue(context.bindService(Intent(context, NullBindingService::class.java), connection, Context.BIND_AUTO_CREATE))
    }
    waitFor { connection.calls >= 2 }  // a null binding per binding (LoadedApk's ServiceDispatcher)
    instrumentation.runOnMainSync { context.unbindService(connection) }
    waitFor { "destroy" in NullBindingService.events }
    val beforeRefused = connection.calls
    instrumentation.runOnMainSync {
      assertFalse(context.bindService(missing, connection, Context.BIND_AUTO_CREATE))
      context.unbindService(connection)
    }
    instrumentation.waitForIdleSync()
    assertEquals(beforeRefused, connection.calls)
    assertEquals(listOf("create", "bind", "unbind", "destroy"), NullBindingService.events.toList())
  }

  private fun waitFor(condition: () -> Boolean) {
    for (i in 0 until 100) {
      instrumentation.waitForIdleSync()
      if (condition()) return
      Thread.sleep(10)
    }
  }

  /** A device keeps a refused connection's dispatcher, so unbinding it is allowed and calls nothing back either. */
  @Test
  fun unbindingARefusedConnectionCallsNothing() {
    val connection = Recording()
    val intent = Intent("com.example.companion.ACTION_BIND").setPackage("com.example.companion.notinstalled")
    instrumentation.runOnMainSync {
      assertFalse(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
      context.unbindService(connection)
    }
    instrumentation.waitForIdleSync()
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
