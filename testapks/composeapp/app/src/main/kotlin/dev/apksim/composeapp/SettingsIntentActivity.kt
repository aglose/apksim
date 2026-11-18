package dev.apksim.composeapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * apksim://settings-intent: starts the system Settings screens an app's menu opens. Nothing here resolves them, so
 * startActivity throws ActivityNotFoundException, as on a device with no Settings app (issue 504): the app can catch
 * it. "settings: wifi=notfound, settings=notfound".
 */
class SettingsIntentActivity : androidx.activity.ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    fun attempt(action: String): String =
      try {
        startActivity(Intent(action))
        "started"
      } catch (e: ActivityNotFoundException) {
        "notfound"
      }
    val line = "settings: wifi=${attempt(Settings.ACTION_WIFI_SETTINGS)}, settings=${attempt(Settings.ACTION_SETTINGS)}"
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Settings intent", style = MaterialTheme.typography.titleLarge)
            Text(line, Modifier.padding(top = 12.dp))
          }
        }
      }
    }
  }
}
