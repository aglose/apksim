package dev.apksim.composeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binding a service whose onBind returns null: a device (API 28+) calls the connection's onNullBinding with the
 * service's name and never onServiceConnected, and unbinding ends the service without onServiceDisconnected.
 */
@RunWith(AndroidJUnit4::class)
class NullBindingTest {
  @Test
  fun aServiceWithNoBinderGetsOnNullBindingAndUnbindsCleanly() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val component = ComponentName(context, NullBindingService::class.java)
    NullBindingService.events.clear()
    val calls = Collections.synchronizedList(mutableListOf<String>())
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        calls.add("onServiceConnected($name, $service)")
      }
      override fun onServiceDisconnected(name: ComponentName?) {
        calls.add("onServiceDisconnected($name)")
      }
      override fun onBindingDied(name: ComponentName?) {
        calls.add("onBindingDied($name)")
      }
      override fun onNullBinding(name: ComponentName?) {
        calls.add("onNullBinding($name)")
      }
    }

    assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
    waitFor { calls.isNotEmpty() }
    assertEquals(listOf("onNullBinding($component)"), calls.toList())

    context.unbindService(connection)
    waitFor { "destroy" in NullBindingService.events }
    Thread.sleep(200) // time for a callback that should not come
    assertEquals(listOf("create", "bind", "unbind", "destroy"), NullBindingService.events.toList())
    assertEquals(listOf("onNullBinding($component)"), calls.toList())
  }

  /** Polls with Thread.sleep: on apksim the test body is on the main thread, and the sleep lets its queue run. */
  private fun waitFor(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
  }
}
