package dev.apksim.composeapp

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
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
    tags.add(intent?.getStringExtra("tag"))
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

    /** The "tag" extra of each intent onBind was given. */
    val tags: MutableList<String?> = Collections.synchronizedList(mutableListOf())
  }
}

/** A service with a binder, the same one for every intent. */
class BinderService : Service() {
  private val binder = Binder()

  override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * A service whose class makes a Handler for the current thread when it is initialized: on a device that is the main
 * thread, which creates services, whichever thread called bindService.
 */
class StaticHandlerService : Service() {
  override fun onBind(intent: Intent?): IBinder = Binder()

  companion object {
    @Suppress("DEPRECATION")
    val handler = Handler()
  }
}
