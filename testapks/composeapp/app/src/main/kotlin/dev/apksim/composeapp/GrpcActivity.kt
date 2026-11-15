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
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * apksim://grpc?tls=HOST:PORT&plain=HOST:PORT&cert=B64: gRPC over grpc-okhttp (HTTP/2) against the fixture server
 * (testapks/composeapp/fixture, its service `apksim.demo.Echo`; scripts/protocols-check.sh starts it and opens this). Over
 * TLS (ALPN h2, trusting only the fixture's certificate) and in plain text (h2 with prior knowledge), each of: a unary
 * call, a server stream of 5, a bidirectional stream of 3, a call the server fails with INVALID_ARGUMENT (non-zero
 * grpc-status in trailers), one the client's 300 ms deadline cancels (RST_STREAM), and a 300,000-byte answer (several
 * DATA frames and flow-control windows). One line each ("grpc tls unary: ok echo: hello"), then a summary
 * ("grpc: done, 12 ok, 0 unsupported, 0 failed"); the lines also go to logcat under `apksim-protocols`.
 */
class GrpcActivity : androidx.activity.ComponentActivity() {
  private val lines = mutableStateListOf<String>()
  private var summary by mutableStateOf("grpc: idle")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val data = intent?.data
    val tls = data?.getQueryParameter("tls")
    val plain = data?.getQueryParameter("plain")
    val cert = data?.getQueryParameter("cert")
    setContent {
      MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
          Text("gRPC", style = MaterialTheme.typography.titleLarge)
          Button({ run(tls, plain, cert) }, Modifier.padding(top = 8.dp)) { Text("Run") }
          Text(summary, Modifier.padding(top = 8.dp))
          for (l in lines) Text(l, fontSize = 12.sp)
        }
      }
    }
    if (tls != null || plain != null) run(tls, plain, cert)
  }

  private fun run(tls: String?, plain: String?, cert: String?) {
    lines.clear()
    val results = mutableListOf<CheckResult>()
    summary = summarize("grpc", results, false)
    lifecycleScope.launch {
      for ((mode, target) in listOf("tls" to tls, "plain" to plain)) {
        if (target == null) continue
        val channel = withContext(Dispatchers.IO) { channel(target, if (mode == "tls") FixtureTls.from(cert) else null, mode == "plain") }
        try {
          for ((name, check) in CHECKS) {
            val r = withContext(Dispatchers.IO) {
              try {
                check(channel).copy(name = "grpc $mode $name")
              } catch (e: StatusRuntimeException) {
                CheckResult.fail("grpc $mode $name", "${e.status.code}: ${e.status.description} ${e.status.cause ?: ""}".trim())
              } catch (e: Exception) {
                CheckResult.fail("grpc $mode $name", "${e.javaClass.simpleName}: ${e.message}")
              }
            }
            results += r
            lines += r.line
            Log.i(TAG, r.line)
            summary = summarize("grpc", results, false)
          }
        } finally {
          withContext(Dispatchers.IO) { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
        }
      }
      summary = summarize("grpc", results, true)
      Log.i(TAG, summary)
    }
  }

  companion object {
    const val TAG = "apksim-protocols"
    const val SERVICE = "apksim.demo.Echo"

    private val UTF8 = object : MethodDescriptor.Marshaller<String> {
      override fun stream(value: String): InputStream = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
      override fun parse(stream: InputStream): String = stream.readBytes().toString(Charsets.UTF_8)
    }

    private fun method(name: String, type: MethodDescriptor.MethodType): MethodDescriptor<String, String> =
      MethodDescriptor.newBuilder<String, String>().setType(type).setFullMethodName("$SERVICE/$name")
        .setRequestMarshaller(UTF8).setResponseMarshaller(UTF8).build()

    private val UNARY = method("Unary", MethodDescriptor.MethodType.UNARY)
    private val COUNT = method("Count", MethodDescriptor.MethodType.SERVER_STREAMING)
    private val CHAT = method("Chat", MethodDescriptor.MethodType.BIDI_STREAMING)
    private val FAIL = method("Fail", MethodDescriptor.MethodType.UNARY)
    private val SLOW = method("Slow", MethodDescriptor.MethodType.UNARY)
    private val BIG = method("Big", MethodDescriptor.MethodType.UNARY)

    private fun options(seconds: Long = 15) = CallOptions.DEFAULT.withDeadlineAfter(seconds, TimeUnit.SECONDS)

    fun channel(target: String, tls: FixtureTls?, plain: Boolean): ManagedChannel {
      val colon = target.lastIndexOf(':')
      val b = OkHttpChannelBuilder.forAddress(target.substring(0, colon), target.substring(colon + 1).toInt())
      if (plain) b.usePlaintext() else if (tls != null) b.sslSocketFactory(tls.socketFactory)
      return b.build()
    }

    /** Each check, in order; a check returns its result (its name is filled in by the caller) or throws. */
    val CHECKS: List<Pair<String, (ManagedChannel) -> CheckResult>> = listOf(
      "unary" to { ch ->
        val r = ClientCalls.blockingUnaryCall(ch, UNARY, options(), "hello")
        if (r == "echo: hello") CheckResult.ok("", r) else CheckResult.fail("", "answered '$r'")
      },
      "stream" to { ch ->
        val got = ClientCalls.blockingServerStreamingCall(ch, COUNT, options(), "5").asSequence().toList()
        if (got == (1..5).map { "count $it" }) CheckResult.ok("", "5 of 5") else CheckResult.fail("", "got $got")
      },
      "bidi" to { ch ->
        val got = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        var error: Throwable? = null
        val requests = ClientCalls.asyncBidiStreamingCall(ch.newCall(CHAT, options()), object : StreamObserver<String> {
          override fun onNext(value: String) {
            got += value
          }

          override fun onError(t: Throwable) {
            error = t
            done.countDown()
          }

          override fun onCompleted() = done.countDown()
        })
        for (m in listOf("a", "b", "c")) requests.onNext(m)
        requests.onCompleted()
        if (!done.await(15, TimeUnit.SECONDS)) CheckResult.fail("", "no end of stream after 15 s (got $got)")
        else if (error != null) throw error!!
        else if (got == listOf("chat: a", "chat: b", "chat: c")) CheckResult.ok("", "3 of 3")
        else CheckResult.fail("", "got $got")
      },
      "fail" to { ch ->
        try {
          CheckResult.fail("", "answered '${ClientCalls.blockingUnaryCall(ch, FAIL, options(), "x")}'")
        } catch (e: StatusRuntimeException) {
          if (e.status.code == Status.Code.INVALID_ARGUMENT && e.status.description == "fail on purpose") CheckResult.ok("", "INVALID_ARGUMENT: fail on purpose")
          else CheckResult.fail("", "${e.status.code}: ${e.status.description}")
        }
      },
      "deadline" to { ch ->
        try {
          CheckResult.fail("", "answered '${ClientCalls.blockingUnaryCall(ch, SLOW, CallOptions.DEFAULT.withDeadlineAfter(300, TimeUnit.MILLISECONDS), "x")}'")
        } catch (e: StatusRuntimeException) {
          if (e.status.code == Status.Code.DEADLINE_EXCEEDED) CheckResult.ok("", "DEADLINE_EXCEEDED") else CheckResult.fail("", "${e.status.code}: ${e.status.description}")
        }
      },
      "big" to { ch ->
        val r = ClientCalls.blockingUnaryCall(ch, BIG, options(), "x")
        val want = buildString { while (length < 300_000) append('a' + length % 26) }
        if (r == want) CheckResult.ok("", "300000 bytes") else CheckResult.fail("", "${r.length} bytes, not the expected 300000")
      },
    )
  }
}
