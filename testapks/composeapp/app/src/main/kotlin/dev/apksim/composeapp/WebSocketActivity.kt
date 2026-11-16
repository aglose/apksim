package dev.apksim.composeapp

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * apksim://websocket?url=wss://HOST:PORT/ws&plain=ws://HOST:PORT/ws&cert=B64: OkHttp's WebSocket against the fixture
 * server's echo (testapks/composeapp/fixture; scripts/media-protocols-check.py). Over TLS (trusting only the fixture's
 * certificate) and in plain text, each of: a text echo, a binary echo of all 256 byte values, a burst of 5 messages the
 * server sends on its own, a 200,000-character text (several frames, compressed when the server agrees to
 * permessage-deflate), and a clean close (1000) answered by the server. One line each ("ws tls text: ok echo: hello"),
 * then a summary; the lines also go to logcat under `apksim-protocols`.
 */
class WebSocketActivity : androidx.activity.ComponentActivity() {
  private val lines = mutableStateListOf<String>()
  private var summary by mutableStateOf("ws: idle")

  private sealed interface Event {
    data class Open(val response: Response) : Event
    data class Text(val text: String) : Event
    data class Bytes(val bytes: ByteString) : Event
    data class Closed(val code: Int, val reason: String) : Event
    data class Failed(val error: Throwable) : Event
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val data = intent?.data
    val url = data?.getQueryParameter("url")
    val plain = data?.getQueryParameter("plain")
    val cert = data?.getQueryParameter("cert")
    setContent {
      MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
          Text("WebSocket", style = MaterialTheme.typography.titleLarge)
          Button({ run(url, plain, cert) }, Modifier.padding(top = 8.dp)) { Text("Run") }
          Text(summary, Modifier.padding(top = 8.dp))
          for (l in lines) Text(l, fontSize = 12.sp)
        }
      }
    }
    if (url != null || plain != null) run(url, plain, cert)
  }

  private fun run(url: String?, plain: String?, cert: String?) {
    lines.clear()
    val results = mutableListOf<CheckResult>()
    summary = summarize("ws", results, false)
    fun add(r: CheckResult) {
      results += r
      lines += r.line
      Log.i(GrpcActivity.TAG, r.line)
      summary = summarize("ws", results, false)
    }
    lifecycleScope.launch {
      for ((mode, target) in listOf("tls" to url, "plain" to plain)) {
        if (target == null) continue
        val b = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS)
        FixtureTls.from(cert)?.let { if (mode == "tls") b.sslSocketFactory(it.socketFactory, it.trustManager) }
        val client = b.build()
        val events = Channel<Event>(Channel.UNLIMITED)
        val ws = client.newWebSocket(Request.Builder().url(target).build(), object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Event.Open(response)) }
          override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Event.Text(text)) }
          override fun onMessage(webSocket: WebSocket, bytes: ByteString) { events.trySend(Event.Bytes(bytes)) }
          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Event.Closed(code, reason)) }
          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(Event.Failed(t)) }
        })
        suspend fun next(): Event = withTimeoutOrNull(15_000) { events.receive() } ?: Event.Failed(java.io.IOException("nothing for 15 s"))
        fun bad(e: Event) = if (e is Event.Failed) "${e.error.javaClass.simpleName}: ${e.error.message}" else "got $e".take(200)
        val open = next()
        if (open !is Event.Open) {
          add(CheckResult.fail("ws $mode open", bad(open)))
          client.dispatcher.executorService.shutdown()
          continue
        }
        add(CheckResult.ok("ws $mode open", "${open.response.protocol}" + (open.response.header("Sec-WebSocket-Extensions")?.let { ", $it" } ?: "")))
        // Each step in turn; after the first that fails the socket is cancelled and the rest are not run, so a broken
        // connection gives one failure at once rather than each later step waiting out its 15 s.
        val all = ByteArray(256) { it.toByte() }.toByteString()
        val large = "x".repeat(200_000)
        val steps: List<Pair<String, suspend () -> CheckResult>> = listOf(
          "text" to {
            ws.send("hello")
            next().let { if (it is Event.Text && it.text == "echo: hello") CheckResult.ok("", it.text) else CheckResult.fail("", bad(it)) }
          },
          "binary" to {
            ws.send(all)
            next().let { if (it is Event.Bytes && it.bytes == all) CheckResult.ok("", "256 bytes") else CheckResult.fail("", bad(it)) }
          },
          "burst" to {
            ws.send("burst 5")
            val burst = mutableListOf<Event>()
            for (i in 1..5) {
              val e = next()
              burst += e
              if (e !is Event.Text) break
            }
            if (burst.map { (it as? Event.Text)?.text } == (1..5).map { "burst $it" }) CheckResult.ok("", "5 of 5")
            else CheckResult.fail("", burst.joinToString { bad(it) }.take(200))
          },
          "large" to {
            ws.send(large)
            next().let { if (it is Event.Text && it.text == "echo: $large") CheckResult.ok("", "200000 characters") else CheckResult.fail("", bad(it).take(80)) }
          },
          "close" to {
            ws.close(1000, "done")
            next().let { if (it is Event.Closed && it.code == 1000) CheckResult.ok("", "1000 ${it.reason}") else CheckResult.fail("", bad(it)) }
          },
        )
        for ((step, run) in steps) {
          val r = run().copy(name = "ws $mode $step")
          add(r)
          if (r.status != "ok") {
            ws.cancel()
            break
          }
        }
        withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
          client.dispatcher.executorService.shutdown()
          client.connectionPool.evictAll()  // closing writes: never on the main thread
        }
      }
      summary = summarize("ws", results, true)
      Log.i(GrpcActivity.TAG, summary)
    }
  }
}
