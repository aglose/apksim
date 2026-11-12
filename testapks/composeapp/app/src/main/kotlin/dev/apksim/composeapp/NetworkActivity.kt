package dev.apksim.composeapp

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A server the app has compiled in, as a real app would: api.apksim.test never resolves (.test is reserved), so this
 * works only through apksim's network layer, recording against a fixture server (--network-host) or replaying
 * (docs/network.md). Each fetch sends an Authorization header, which a recording must redact.
 */
object GreetingClient {
  const val BASE = "https://api.apksim.test"
  private val client by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

  /** code, body, X-Apksim-Replay (null when not replayed), milliseconds. */
  data class Result(val code: Int, val body: String, val replay: String?, val ms: Long) {
    val greeting: String get() = Regex("\"greeting\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: body.trim().take(60)
  }

  /** POST /v1/greeting with a JSON body, through OkHttp. */
  fun fetch(path: String = "/v1/greeting"): Result {
    val t0 = System.nanoTime()
    val request = Request.Builder().url(BASE + path)
      .header("Authorization", "Bearer test-token-123")
      .post("""{"name":"apksim"}""".toRequestBody("application/json".toMediaType()))
      .build()
    client.newCall(request).execute().use { r ->
      return Result(r.code, r.body.string(), r.header("X-Apksim-Replay"), (System.nanoTime() - t0) / 1_000_000)
    }
  }

  /** GET /v1/plain through the platform's HttpsURLConnection. */
  fun fetchWithUrlConnection(): Result {
    val t0 = System.nanoTime()
    val c = URL("$BASE/v1/plain").openConnection() as HttpsURLConnection
    c.setRequestProperty("Authorization", "Bearer test-token-123")
    c.connectTimeout = 30_000
    c.readTimeout = 30_000
    try {
      val code = c.responseCode
      val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
      return Result(code, body, c.getHeaderField("X-Apksim-Replay"), (System.nanoTime() - t0) / 1_000_000)
    } finally {
      c.disconnect()
    }
  }

  /** The beam tab relay's host (docs/beam.md "Tab relay"): the page's tab answers what the app sends it. */
  const val RELAY = "http://page.apksim.invalid"

  /** POST /relay/echo?via=okhttp to the tab relay with a body, through OkHttp. */
  fun relay(): Result {
    val t0 = System.nanoTime()
    val request = Request.Builder().url("$RELAY/relay/echo?via=okhttp")
      .header("X-Relay-Check", "okhttp")
      .post("""{"name":"apksim"}""".toRequestBody("application/json".toMediaType()))
      .build()
    client.newCall(request).execute().use { r ->
      return Result(r.code, r.body.string(), r.header("X-Relay-Answer"), (System.nanoTime() - t0) / 1_000_000)
    }
  }

  /** GET /relay/echo?via=urlconnection to the tab relay through the platform's HttpURLConnection. */
  fun relayWithUrlConnection(): Result {
    val t0 = System.nanoTime()
    val c = URL("$RELAY/relay/echo?via=urlconnection").openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("X-Relay-Check", "urlconnection")
    c.connectTimeout = 30_000
    c.readTimeout = 30_000
    try {
      val code = c.responseCode
      val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
      return Result(code, body, c.getHeaderField("X-Relay-Answer"), (System.nanoTime() - t0) / 1_000_000)
    } finally {
      c.disconnect()
    }
  }

  /** `--feature dev.apksim.testapp.no_timings` leaves the time out: it is real time, which a replay cannot repeat. */
  fun describe(r: Result, timing: Boolean = true) =
    "${r.code} ${r.greeting}${r.replay?.let { " [$it]" } ?: ""}" + if (timing) " in ${r.ms} ms" else ""

  /** The default network as ConnectivityManager reports it. */
  fun connectivity(cm: ConnectivityManager): String {
    val n = cm.activeNetwork ?: return "none"
    val caps = cm.getNetworkCapabilities(n) ?: return "unknown"
    val kind = when {
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
      else -> "other"
    }
    return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "$kind internet" else kind
  }
}

class NetworkActivity : androidx.activity.ComponentActivity() {
  private var connectivity by mutableStateOf("?")
  private var events by mutableStateOf("")
  private lateinit var callback: ConnectivityManager.NetworkCallback

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    val cm = getSystemService(ConnectivityManager::class.java)
    connectivity = GreetingClient.connectivity(cm)
    callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) = runOnUiThread { events += "+"; connectivity = GreetingClient.connectivity(cm) }
      override fun onLost(network: Network) = runOnUiThread { events += "-"; connectivity = "none" }
    }
    cm.registerDefaultNetworkCallback(callback)
    setContent {
      MaterialTheme {
        val scope = rememberCoroutineScope()
        var okhttp by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var huc by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var relayOkhttp by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var relayHuc by androidx.compose.runtime.remember { mutableStateOf("idle") }
        fun run(set: (String) -> Unit, f: () -> GreetingClient.Result) {
          set("fetching")
          scope.launch {
            set(withContext(Dispatchers.IO) {
              runCatching { GreetingClient.describe(f(), !packageManager.hasSystemFeature("dev.apksim.testapp.no_timings")) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            })
          }
        }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
          Text("Network", style = MaterialTheme.typography.titleLarge)
          Row(Modifier.padding(top = 12.dp)) {
            Button({ run({ okhttp = it }) { GreetingClient.fetch() } }) { Text("Fetch") }
            Button({ run({ okhttp = it }) { GreetingClient.fetch("/v1/missing") } }, Modifier.padding(start = 8.dp)) { Text("Fetch other") }
            Button({ run({ huc = it }) { GreetingClient.fetchWithUrlConnection() } }, Modifier.padding(start = 8.dp)) { Text("URL") }
          }
          Row(Modifier.padding(top = 8.dp)) {
            Button({ run({ relayOkhttp = it }) { GreetingClient.relay() } }) { Text("Relay") }
            Button({ run({ relayHuc = it }) { GreetingClient.relayWithUrlConnection() } }, Modifier.padding(start = 8.dp)) { Text("Relay URL") }
          }
          Text("okhttp: $okhttp", Modifier.padding(top = 12.dp))
          Text("urlconnection: $huc", Modifier.padding(top = 8.dp))
          Text("relay okhttp: $relayOkhttp", Modifier.padding(top = 8.dp))
          Text("relay urlconnection: $relayHuc", Modifier.padding(top = 8.dp))
          Text("connectivity: $connectivity", Modifier.padding(top = 8.dp))
          Text("at app start: ${ComposeApp.networkAtStart}", Modifier.padding(top = 8.dp))
          Text("callbacks: $events", Modifier.padding(top = 8.dp))
        }
      }
    }
  }

  override fun onDestroy() {
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
    super.onDestroy()
  }
}
