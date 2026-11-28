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

  /**
   * A sign-in as an app makes one (`apksim://network?signin=1`): the typed user and password in a POST's JSON body. The
   * flight recorder leaves a password field's characters out of its journal, so this is the request a replay cannot
   * type again as it was (docs/replay.md "A session with a sign-in").
   */
  fun signIn(user: String, password: String): Result {
    val t0 = System.nanoTime()
    val body = org.json.JSONObject().put("user", user).put("password", password).toString()
    val request = Request.Builder().url("$BASE/v1/signin")
      .header("Authorization", "Bearer test-token-123")
      .post(body.toRequestBody("application/json".toMediaType()))
      .build()
    client.newCall(request).execute().use { r ->
      return Result(r.code, r.body.string(), r.header("X-Apksim-Replay"), (System.nanoTime() - t0) / 1_000_000)
    }
  }

  /** An id made when first asked for, as an app makes an installation id at run time: another in every process. */
  val device: String by lazy { java.util.UUID.randomUUID().toString() }

  /** GET /v1/profile with that id in the query: a value no recording of another process can hold. */
  fun profile(): Result {
    val t0 = System.nanoTime()
    val request = Request.Builder().url("$BASE/v1/profile?device=$device&lang=en").header("Authorization", "Bearer test-token-123").build()
    client.newCall(request).execute().use { r ->
      return Result(r.code, r.body.string(), r.header("X-Apksim-Replay"), (System.nanoTime() - t0) / 1_000_000)
    }
  }

  /**
   * A server that is not there: down.apksim.test, which a check maps to a port nothing listens on
   * (`--network-host down.apksim.test=127.0.0.1:1`). The connect fails; what the app shows is the exception's class.
   */
  fun down(): String =
    runCatching {
      client.newCall(Request.Builder().url("https://down.apksim.test:9/").build()).execute().use { r -> "${r.code}" }
    }.getOrElse { it.javaClass.simpleName }

  /**
   * The page's own site through the tab relay (docs/beam.md "The tab relay"): plain HTTP to page.apksim.invalid, which
   * apksim carries to the browser tab that started a beam session with `relay`. A POST of a body through OkHttp, whose
   * default read timeout is 10 s.
   */
  const val RELAY = "http://page.apksim.invalid"

  fun relayFetch(path: String = "/relay/okhttp"): Result {
    val t0 = System.nanoTime()
    val request = Request.Builder().url(RELAY + path).post("""{"from":"okhttp"}""".toRequestBody("application/json".toMediaType())).build()
    OkHttpClient().newCall(request).execute().use { r ->
      return Result(r.code, r.body.string(), r.header("X-Relay"), (System.nanoTime() - t0) / 1_000_000)
    }
  }

  /** GET through HttpURLConnection (plain http: not the HttpsURLConnection above). */
  fun relayFetchWithUrlConnection(path: String = "/relay/url"): Result {
    val t0 = System.nanoTime()
    val c = URL(RELAY + path).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("X-From", "urlconnection")
    try {
      val code = c.responseCode
      val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
      return Result(code, body, c.getHeaderField("X-Relay"), (System.nanoTime() - t0) / 1_000_000)
    } finally {
      c.disconnect()
    }
  }

  /**
   * GET of any URL (`apksim://network?url=URL`), through OkHttp or the platform's HttpURLConnection: what a server the
   * app reaches by itself answers, and over which protocol (the tap's check: h2 stays h2 while it is recorded).
   */
  fun get(url: String, okhttp: Boolean): String {
    if (okhttp) {
      client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        return "${r.code} ${r.protocol} ${r.body.bytes().size} bytes"
      }
    }
    val c = URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 30_000
    c.readTimeout = 30_000
    try {
      val code = c.responseCode
      val n = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.size ?: 0
      return "$code $n bytes"
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
        var relayOk by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var relayHuc by androidx.compose.runtime.remember { mutableStateOf("idle") }
        val url = intent?.data?.getQueryParameter("url")
        var urlOk by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var urlHuc by androidx.compose.runtime.remember { mutableStateOf("idle") }
        fun get(set: (String) -> Unit, okhttp: Boolean) {
          set("fetching")
          scope.launch {
            set(withContext(Dispatchers.IO) { runCatching { GreetingClient.get(url!!, okhttp) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" } })
          }
        }
        fun run(set: (String) -> Unit, f: () -> GreetingClient.Result) {
          set("fetching")
          scope.launch {
            set(withContext(Dispatchers.IO) {
              runCatching { GreetingClient.describe(f(), !packageManager.hasSystemFeature("dev.apksim.testapp.no_timings")) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            })
          }
        }
        val signin = intent?.data?.getQueryParameter("signin") != null
        var user by androidx.compose.runtime.remember { mutableStateOf("") }
        var password by androidx.compose.runtime.remember { mutableStateOf("") }
        var signedIn by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var profile by androidx.compose.runtime.remember { mutableStateOf("idle") }
        var down by androidx.compose.runtime.remember { mutableStateOf("idle") }
        fun signIn() {
          signedIn = "fetching"
          val timing = !packageManager.hasSystemFeature("dev.apksim.testapp.no_timings")
          scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { GreetingClient.signIn(user, password) } }
            signedIn = r.fold({ GreetingClient.describe(it, timing) }, { "${it.javaClass.simpleName}: ${it.message}" })
            if (r.getOrNull()?.code == 200) {
              profile = "fetching"
              profile = withContext(Dispatchers.IO) {
                runCatching { GreetingClient.describe(GreetingClient.profile(), timing) }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
              }
            }
          }
        }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
          Text("Network", style = MaterialTheme.typography.titleLarge)
          if (signin) {
            androidx.compose.material3.OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true)
            androidx.compose.material3.OutlinedTextField(
              password, { password = it }, label = { Text("Password") }, singleLine = true,
              visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
              keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
            )
            Row(Modifier.padding(top = 8.dp)) {
              Button({ signIn() }) { Text("Sign in") }
              Button({
                down = "fetching"
                scope.launch { down = withContext(Dispatchers.IO) { GreetingClient.down() } }
              }, Modifier.padding(start = 8.dp)) { Text("Ping down") }
            }
            Text("signin: $signedIn", Modifier.padding(top = 8.dp))
            Text("profile: $profile", Modifier.padding(top = 8.dp))
            Text("down: $down", Modifier.padding(top = 8.dp))
          }
          Row(Modifier.padding(top = 12.dp)) {
            Button({ run({ okhttp = it }) { GreetingClient.fetch() } }) { Text("Fetch") }
            Button({ run({ okhttp = it }) { GreetingClient.fetch("/v1/missing") } }, Modifier.padding(start = 8.dp)) { Text("Fetch other") }
            Button({ run({ huc = it }) { GreetingClient.fetchWithUrlConnection() } }, Modifier.padding(start = 8.dp)) { Text("URL") }
          }
          Row(Modifier.padding(top = 8.dp)) {
            Button({ run({ relayOk = it }) { GreetingClient.relayFetch() } }) { Text("Relay") }
            Button({ run({ relayOk = it }) { GreetingClient.relayFetch("/relay/slow") } }, Modifier.padding(start = 8.dp)) { Text("Relay slow") }
            Button({ run({ relayHuc = it }) { GreetingClient.relayFetchWithUrlConnection() } }, Modifier.padding(start = 8.dp)) { Text("Relay URL") }
          }
          if (url != null) {
            Row(Modifier.padding(top = 8.dp)) {
              Button({ get({ urlOk = it }, true) }) { Text("Get") }
              Button({ get({ urlHuc = it }, false) }, Modifier.padding(start = 8.dp)) { Text("Get URL") }
            }
            Text("get okhttp: $urlOk", Modifier.padding(top = 8.dp))
            Text("get urlconnection: $urlHuc", Modifier.padding(top = 8.dp))
          }
          Text("relay okhttp: $relayOk", Modifier.padding(top = 8.dp))
          Text("relay urlconnection: $relayHuc", Modifier.padding(top = 8.dp))
          Text("okhttp: $okhttp", Modifier.padding(top = 12.dp))
          Text("urlconnection: $huc", Modifier.padding(top = 8.dp))
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
