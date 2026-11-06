package dev.apksim.composeapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://install: what a device has before the app starts, as ComposeApp.onCreate saw it (a normal permission
 * granted at install, an ANDROID_ID, the app's content providers created), and a bind to a service in a companion
 * app that isn't installed, which a device answers with false and no callback, then an unbind that calls nothing back
 * either (issues 76 and 81, in running instances; test runs check the same with InstallPermissionsTest, AndroidIdTest
 * and BindServiceTest). The connection is Kotlin's, with non-null parameters: a callback with nulls ends the app.
 */
class InstallStateActivity : Activity() {
  private var callbacks = 0

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) { callbacks++ }
    override fun onServiceDisconnected(name: ComponentName) { callbacks++ }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val permission = when (ComposeApp.vibrateAtCreate) {
      PackageManager.PERMISSION_GRANTED -> "granted"
      null -> "unknown"
      else -> "denied"
    }
    val status = TextView(this).apply { textSize = 18f }
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 160, 48, 48)
      addView(TextView(this@InstallStateActivity).apply { textSize = 18f; text = "install: vibrate $permission at create" })
      addView(TextView(this@InstallStateActivity).apply {
        textSize = 18f
        text = "android id: " + (if (ComposeApp.androidIdAtCreate != null) "set" else "missing") + " at create"
      })
      addView(TextView(this@InstallStateActivity).apply {
        textSize = 18f
        text = "provider: " + (if (ComposeApp.providerBeforeCreate) "before" else "after") + " create"
      })
      addView(status)
    }
    setContentView(layout)
    val intent = Intent("com.example.companion.ACTION_BIND").setPackage("com.example.companion.notinstalled")
    val bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    status.text = "bind missing: $bound"
    // A device keeps the connection's dispatcher even when bindService returned false, so unbinding it is allowed and
    // quiet. The result shows once both have had their chance to call back.
    layout.postDelayed({
      unbindService(connection)
      layout.postDelayed({ status.text = "bind missing: $bound, unbound, $callbacks callbacks" }, 300)
    }, 300)
  }
}
