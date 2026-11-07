package dev.apksim.composeapp

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * apksim://exit?kind=KIND: the app ends its own process, or its last activity, 300 ms after the screen opens, each way
 * an app's code can (issue 116: a process ended with status 0 and nothing in the log). apksim says why in one
 * `[apksim] exit N: ...` line:
 * - `finish`: finish() on the only activity: exit 0, the activity and the call named.
 * - `affinity`: finishAffinity(), as an app does to clear its task after a sign-in.
 * - `handoff`: finish(), then 200 ms later starts another ExitActivity (kind `exit`) from a posted message: a device's
 *   process lingers after its last activity, so the start lands, and that one ends the process with status 3.
 * - `exit`: System.exit(3): exit 3.
 * - `halt`: Runtime.getRuntime().halt(4): exit 4.
 * - `kill`: Process.killProcess(Process.myPid()), as restart-after-login libraries do: the process dies (137).
 */
class ExitActivity : androidx.activity.ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    // `--activity .ExitActivity --jvm -Ddev.apksim.testapp.exit_kind=KIND` for a launch of its own (ci-smoke.sh).
    val kind = intent.data?.getQueryParameter("kind") ?: System.getProperty("dev.apksim.testapp.exit_kind") ?: "finish"
    val handler = Handler(Looper.getMainLooper())
    val app = applicationContext
    handler.postDelayed({
      Log.i(TAG, "exit: $kind")
      when (kind) {
        "finish" -> finish()
        "affinity" -> finishAffinity()
        "handoff" -> {
          finish()
          handler.postDelayed({
            app.startActivity(
              Intent(Intent.ACTION_VIEW, android.net.Uri.parse("apksim://exit?kind=exit"), app, ExitActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }, 200)
        }
        "exit" -> System.exit(3)
        "halt" -> Runtime.getRuntime().halt(4)
        "kill" -> android.os.Process.killProcess(android.os.Process.myPid())
        else -> Log.e(TAG, "exit: unknown kind $kind")
      }
    }, 300)
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Exit", style = MaterialTheme.typography.titleLarge)
            Text("exit: $kind armed", Modifier.padding(top = 12.dp))
          }
        }
      }
    }
  }

  private companion object {
    const val TAG = "ExitActivity"
  }
}
