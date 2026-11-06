package dev.apksim.composeapp

import android.content.Context
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * apksim://slot: what outlives the app's process on a device besides its own data, external storage and the Settings
 * providers (issues 82 and 84). "Write" puts a Settings.Global value and a file in /sdcard; the next launch of the same
 * data slot must see both from Application.onCreate on ("at app start", recorded by ComposeApp), and a --fresh launch
 * neither. `seed` is a value only apksim's `--setting global:apksim_slot_seed=...` sets. scripts/slot-check.sh drives it.
 */
class SlotStateActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
  }

  @Composable
  private fun Screen() {
    var now by remember { mutableStateOf(describe(this)) }
    var typed by remember { mutableStateOf(typed(this)) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
      Text("Slot state", style = MaterialTheme.typography.titleLarge)
      Text("at app start: ${ComposeApp.slotAtCreate}")
      Text("now: $now")
      Text("typed: $typed")
      Text("storage: ${storage()}")
      Text("font scale: ${resources.configuration.fontScale}")
      Button(onClick = {
        // A device refuses an ordinary app WRITE_SECURE_SETTINGS (SecurityException); apksim allows it.
        try {
          Settings.Global.putString(contentResolver, SETTING, "written")
          // Typed values, which the provider keeps as text and its getters parse: they must read back after a relaunch.
          Settings.Global.putInt(contentResolver, INT, 42)
          Settings.Secure.putLong(contentResolver, LONG, 1234567890123L)
          Settings.System.putFloat(contentResolver, FLOAT, 1.25f)
        } catch (e: SecurityException) {
          // left as it was: "now" shows it
        }
        typed = typed(this@SlotStateActivity)
        try {
          File(Environment.getExternalStorageDirectory(), FILE).writeText("written")
        } catch (e: Exception) {
          // same
        }
        now = describe(this@SlotStateActivity)
      }, Modifier.padding(top = 24.dp).testTag("write")) { Text("Write") }
    }
  }

  /**
   * External storage as a device shows it: mounted, its public folders inside /sdcard, and the app's own external
   * folder mounted and emulated too (the File overloads, which an app checks before writing there).
   */
  private fun storage(): String {
    val root = Environment.getExternalStorageDirectory()
    val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val inside = download.path == File(root, Environment.DIRECTORY_DOWNLOADS).path
    val own = getExternalFilesDir(null)
    val ownState = own?.let {
      Environment.getExternalStorageState(it) + if (Environment.isExternalStorageEmulated(it)) " emulated" else ""
    }
    return "${Environment.getExternalStorageState()}, Download ${if (inside) "inside" else "outside"} /sdcard, " +
        "app folder $ownState"
  }

  companion object {
    const val SETTING = "apksim_slot_check"
    const val SEED = "apksim_slot_seed"
    const val FILE = "apksim-slot-check.txt"
    const val INT = "apksim_slot_int"
    const val LONG = "apksim_slot_long"
    const val FLOAT = "apksim_slot_float"

    /** The typed values "Write" sets, read with the typed getters (-1 for one not set), and the float as text. */
    fun typed(context: Context): String {
      val cr = context.contentResolver
      return "int=${Settings.Global.getInt(cr, INT, -1)} long=${Settings.Secure.getLong(cr, LONG, -1L)} " +
          "float=${Settings.System.getFloat(cr, FLOAT, -1f)} text=${Settings.System.getString(cr, FLOAT)}"
    }

    /** The slot's Settings.Global values and the /sdcard file, as "setting=... seed=... sdcard=...". */
    fun describe(context: Context): String {
      val cr = context.contentResolver
      val sdcard = try {
        File(Environment.getExternalStorageDirectory(), FILE).takeIf { it.exists() }?.readText() ?: "missing"
      } catch (e: Exception) {
        "unreadable"
      }
      return "setting=${Settings.Global.getString(cr, SETTING)} seed=${Settings.Global.getString(cr, SEED)} sdcard=$sdcard"
    }
  }
}
