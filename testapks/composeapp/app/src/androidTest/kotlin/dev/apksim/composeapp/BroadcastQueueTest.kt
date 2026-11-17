package dev.apksim.composeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two broadcasts sent back to back to one receiver registered at run time are both delivered, plain or ordered, as on
 * a device (issue 452: Robolectric kept one PendingResult on the receiver, so the second delivery threw "Broadcast
 * already finished" and ended the app). Passes on an emulator too.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastQueueTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext

  private fun twice(action: String, send: (Context, Intent) -> Unit) {
    val got = ArrayList<String>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) { got += i.getStringExtra("n") ?: "?" }
    }
    context.registerReceiver(receiver, IntentFilter(action), null, Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
    try {
      instrumentation.runOnMainSync {
        send(context, Intent(action).setPackage(context.packageName).putExtra("n", "1"))
        send(context, Intent(action).setPackage(context.packageName).putExtra("n", "2"))
      }
      val deadline = System.currentTimeMillis() + 10_000
      while (got.size < 2 && System.currentTimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        Thread.sleep(20)
      }
      assertEquals(listOf("1", "2"), got.toList())
    } finally {
      context.unregisterReceiver(receiver)
    }
  }

  @Test fun twoPlainBroadcastsAreBothDelivered() = twice("dev.apksim.testapp.QUEUE_PLAIN") { c, i -> c.sendBroadcast(i) }

  @Test fun twoOrderedBroadcastsAreBothDelivered() = twice("dev.apksim.testapp.QUEUE_ORDERED") { c, i -> c.sendOrderedBroadcast(i, null) }
}
