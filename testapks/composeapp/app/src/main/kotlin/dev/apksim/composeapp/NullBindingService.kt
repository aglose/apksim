package dev.apksim.composeapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.Collections

/**
 * A service that can be bound but gives no binder: on a device (API 28+) a client's connection then gets
 * onNullBinding, never onServiceConnected. It notes its lifecycle so a test can check the binding and its end.
 */
class NullBindingService : Service() {
  override fun onCreate() {
    events.add("create")
  }

  override fun onBind(intent: Intent?): IBinder? {
    events.add("bind")
    return null
  }

  override fun onUnbind(intent: Intent?): Boolean {
    events.add("unbind")
    return false
  }

  override fun onDestroy() {
    events.add("destroy")
  }

  companion object {
    /** What ran, in order: create, bind, unbind, destroy. */
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
  }
}
