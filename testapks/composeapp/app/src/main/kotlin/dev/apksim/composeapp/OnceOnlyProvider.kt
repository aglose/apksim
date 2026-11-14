package dev.apksim.composeapp

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri

/**
 * A provider that installs a library once per process and refuses a second onCreate, as LeakCanary's
 * MainProcessAppWatcherInstaller does (`check(!isInstalled)` on a static). A device's process creates it once; a
 * shared test process here makes the app again for every test (issue 353).
 */
class OnceOnlyProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    check(!installed) { "OnceOnlyProvider is already installed" }
    installed = true
    return true
  }

  override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
  override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

  companion object {
    @Volatile var installed = false
  }
}
