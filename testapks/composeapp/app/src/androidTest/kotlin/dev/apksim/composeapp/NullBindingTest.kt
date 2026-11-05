package dev.apksim.composeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binding the app's own services as a device does. A service whose onBind returns null: a device (API 28+) calls
 * the connection's onNullBinding with the service's name and never onServiceConnected, and unbinding ends the
 * service without onServiceDisconnected.
 */
@RunWith(AndroidJUnit4::class)
class NullBindingTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun clear() {
    NullBindingService.events.clear()
    NullBindingService.tags.clear()
  }

  @Test
  fun aServiceWithNoBinderGetsOnNullBindingAndUnbindsCleanly() {
    val component = ComponentName(context, NullBindingService::class.java)
    val connection = Recorder()

    assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
    waitFor { connection.calls.isNotEmpty() }
    assertEquals(listOf("onNullBinding($component)"), connection.calls.toList())

    context.unbindService(connection)
    waitFor { "destroy" in NullBindingService.events }
    Thread.sleep(200) // time for a callback that should not come
    assertEquals(listOf("create", "bind", "unbind", "destroy"), NullBindingService.events.toList())
    assertEquals(listOf("onNullBinding($component)"), connection.calls.toList())
  }

  /** The service class is initialized where the service is made, the main thread, not on the thread that binds. */
  @Test
  fun aServiceBoundFromAWorkerIsInitializedOnTheMainThread() {
    val component = ComponentName(context, StaticHandlerService::class.java)
    val connection = Recorder()
    var bound = false
    thread { bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE) }.join()
    assertTrue(bound)
    waitFor { connection.calls.isNotEmpty() }
    assertEquals(listOf("onServiceConnected($component, binder)"), connection.calls.toList())
    assertSame(Looper.getMainLooper(), StaticHandlerService.handler.looper)
    context.unbindService(connection)
  }

  /**
   * A binding without BIND_AUTO_CREATE that ended before the service ran: a device keeps its intent (ActiveServices'
   * IntentBindRecord stays in the ServiceRecord), so the next bind with an equal intent gets onBind with the first
   * one's extras, as the API 36 emulator does.
   */
  @Test
  fun onBindGetsTheIntentOfTheFirstBindingEvenIfItEnded() {
    val component = ComponentName(context, NullBindingService::class.java)
    val waiting = Recorder()
    assertTrue(context.bindService(Intent().setComponent(component).putExtra("tag", "first"), waiting, 0))
    Thread.sleep(200)
    context.unbindService(waiting)
    Thread.sleep(200)

    val connection = Recorder()
    assertTrue(context.bindService(Intent().setComponent(component).putExtra("tag", "second"), connection, Context.BIND_AUTO_CREATE))
    waitFor { connection.calls.isNotEmpty() }
    assertEquals(listOf("onNullBinding($component)"), connection.calls.toList())
    assertEquals(listOf("first"), NullBindingService.tags.toList())
    assertEquals(emptyList<String>(), waiting.calls.toList())
    context.unbindService(connection)
    waitFor { "destroy" in NullBindingService.events }
  }

  /**
   * One connection bound to one intent without BIND_AUTO_CREATE and then with it is connected once: the service
   * publishes its binder to both bindings, and LoadedApk's ServiceDispatcher drops a binder it already has.
   */
  @Test
  fun aConnectionBoundTwiceToTheSameIntentIsConnectedOnce() {
    val component = ComponentName(context, BinderService::class.java)
    val connection = Recorder()
    assertTrue(context.bindService(Intent().setComponent(component), connection, 0))
    assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
    waitFor { connection.calls.isNotEmpty() }
    Thread.sleep(300) // time for a second callback that should not come
    assertEquals(listOf("onServiceConnected($component, binder)"), connection.calls.toList())
    context.unbindService(connection)
  }

  /**
   * One connection bound to two intents of one service that are not filterEquals: onBind runs for each, both give
   * the same binder, and the dispatcher, which keeps the binder by component, tells the connection once.
   */
  @Test
  fun aConnectionBoundToTwoIntentsWithTheSameBinderIsConnectedOnce() {
    val component = ComponentName(context, BinderService::class.java)
    val connection = Recorder()
    assertTrue(context.bindService(Intent("one").setComponent(component), connection, Context.BIND_AUTO_CREATE))
    assertTrue(context.bindService(Intent("two").setComponent(component), connection, Context.BIND_AUTO_CREATE))
    waitFor { connection.calls.isNotEmpty() }
    Thread.sleep(300) // time for a second callback that should not come
    assertEquals(listOf("onServiceConnected($component, binder)"), connection.calls.toList())
    context.unbindService(connection)
  }

  /**
   * A connection bound without BIND_AUTO_CREATE when the last BIND_AUTO_CREATE binding goes: the service is brought
   * down, and the connection hears what the device says to every connection of a service brought down.
   */
  @Test
  fun aConnectionWithoutAutoCreateHearsTheServiceGoAndCome() {
    val component = ComponentName(context, SingletonBinderService::class.java)
    val waiting = Recorder()
    val creating = Recorder()
    assertTrue(context.bindService(Intent().setComponent(component), waiting, 0))
    assertTrue(context.bindService(Intent().setComponent(component), creating, Context.BIND_AUTO_CREATE))
    waitFor { waiting.calls.isNotEmpty() && creating.calls.isNotEmpty() }
    context.unbindService(creating) // the service is destroyed
    waitFor { waiting.calls.size >= 2 }
    Thread.sleep(300)
    val again = Recorder()
    assertTrue(context.bindService(Intent().setComponent(component), again, Context.BIND_AUTO_CREATE)) // made again
    waitFor { again.calls.isNotEmpty() }
    Thread.sleep(300)
    android.util.Log.i("NullBindingTest", "waiting connection: ${waiting.calls}")
    assertEquals(listOf("onServiceConnected($component, binder)"), creating.calls.toList())
    assertEquals(listOf("onServiceConnected($component, binder)"), again.calls.toList())
    assertEquals(
      listOf("onServiceConnected($component, binder)", "onServiceDisconnected($component)", "onBindingDied($component)"),
      waiting.calls.toList())
    context.unbindService(again)
    context.unbindService(waiting)
  }

  /** The same with a null binder: ServiceDispatcher keeps no null binder to compare, so each binding is told. */
  @Test
  fun aConnectionBoundTwiceToANullBindingIsToldForEachBinding() {
    val component = ComponentName(context, NullBindingService::class.java)
    val connection = Recorder()
    assertTrue(context.bindService(Intent().setComponent(component), connection, 0))
    assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
    waitFor { connection.calls.size >= 2 }
    Thread.sleep(300)
    assertEquals(listOf("onNullBinding($component)", "onNullBinding($component)"), connection.calls.toList())
    context.unbindService(connection)
    waitFor { "destroy" in NullBindingService.events }
  }

  private class Recorder : ServiceConnection {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      calls.add("onServiceConnected($name, ${if (service == null) "null" else "binder"})")
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

  /** Polls with Thread.sleep: on apksim the test body is on the main thread, and the sleep lets its queue run. */
  private fun waitFor(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
  }
}
