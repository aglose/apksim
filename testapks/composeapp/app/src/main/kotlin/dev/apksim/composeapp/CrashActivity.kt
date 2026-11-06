package dev.apksim.composeapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * apksim://crash?kind=KIND: an uncaught exception 300 ms after the screen opens, as an app's bug would throw one
 * (issue 104). On a device each of these logs `FATAL EXCEPTION: <thread>` and ends the process, unless the app has its
 * own handler:
 * - `coroutine`: thrown in a ViewModel's viewModelScope.launch (Dispatchers.Main), which kotlinx.coroutines hands to the
 *   thread's uncaught-exception handler.
 * - `post`: thrown from a Runnable posted to the main thread's Handler (it leaves Looper.loop).
 * - `worker`: thrown on a thread of the app's own ("crash-worker").
 * - `bare`: thrown on a thread of the app's own ("crash-bare"), an exception made without a stack trace, so nothing in
 *   it says the app's code ran.
 * - `handled`: the app installs a default handler of its own that does not pass the exception on, then crashes as
 *   `coroutine`: the app keeps running and the screen says what its handler got.
 * - `chained`: the app installs a handler that records the crash and passes it on to the one before it, as crash
 *   reporters do, then crashes as `coroutine`: the process ends as without it.
 */
class CrashActivity : androidx.activity.ComponentActivity() {
  class CrashModel : ViewModel() {
    var armed = false

    fun crashSoon(divisor: Int) {
      if (armed) return
      armed = true
      viewModelScope.launch {
        delay(300)
        Log.i(TAG, "crash: throwing in viewModelScope")
        Log.i(TAG, "never: ${10 / divisor}")
      }
    }
  }

  private val model: CrashModel by viewModels()
  private var status by mutableStateOf("")

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    val kind = intent.data?.getQueryParameter("kind") ?: "coroutine"
    status = "crash: $kind armed"
    val handler = Handler(Looper.getMainLooper())
    when (kind) {
      "coroutine" -> model.crashSoon(intent.data?.getQueryParameter("divisor")?.toInt() ?: 0)
      "post" -> handler.postDelayed({ throw IllegalStateException("thrown from a posted Runnable") }, 300)
      "worker" -> handler.postDelayed({
        Thread({ throw IllegalStateException("thrown on a worker thread") }, "crash-worker").start()
      }, 300)
      "bare" -> handler.postDelayed({ Thread({ throw Bare() }, "crash-bare").start() }, 300)
      "handled" -> {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
          Log.e(TAG, "crash: app handler got ${e.javaClass.name} on ${t.name}")
          handler.post { status = "crash: handled by the app (${e.javaClass.simpleName} on ${t.name})" }
        }
        model.crashSoon(0)
      }
      "chained" -> {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
          Log.e(TAG, "crash: reporter recorded ${e.javaClass.name} on ${t.name}, passing it on")
          before?.uncaughtException(t, e)
        }
        model.crashSoon(0)
      }
      else -> status = "crash: unknown kind $kind"
    }
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Crash", style = MaterialTheme.typography.titleLarge)
            Text(status, Modifier.padding(top = 12.dp))
          }
        }
      }
    }
  }

  /** An exception with no stack trace (writableStackTrace false). */
  class Bare : RuntimeException("thrown without a stack trace", null, false, false)

  private companion object {
    const val TAG = "CrashActivity"
  }
}
