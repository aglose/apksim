package dev.apksim.composeapp

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri

/** A provider that does startup work in onCreate, as AndroidX Startup's does: a device creates it before the app's onCreate. */
class StartupProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    created = true
    // A startup initializer reading the battery: the system's sticky broadcast is there before any provider runs.
    batteryAtCreate = context?.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
      ?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    return true
  }

  override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
  override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

  companion object {
    @Volatile var created = false
    @Volatile var batteryAtCreate: Int? = null
  }
}
