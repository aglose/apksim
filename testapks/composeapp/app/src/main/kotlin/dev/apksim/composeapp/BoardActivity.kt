package dev.apksim.composeapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * apksim://board?user=NAME[&post=TEXT]: a message board on the app's compiled-in backend (https://api.apksim.test,
 * scripts/network-fixture.py's /v1/board), for scenes of several instances sharing one server (scripts/scene.py,
 * docs/scenes.md). It posts TEXT once when opened with it, and "Post ping" posts "ping N"; it reads the board every
 * 500 ms and shows "board: alice: hello | bob: ping 1". Like apksim://network, it reaches the server only through
 * apksim's network layer (--network-host, --network-trust).
 */
class BoardActivity : Activity() {
  private val io = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private lateinit var board: TextView
  private lateinit var status: TextView
  private var pings = 0
  private var resumed = false
  private val user get() = intent.data?.getQueryParameter("user") ?: "anon"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    board = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
    status = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.rgb(0x20, 0x50, 0x40))
      addView(TextView(context).apply { textSize = 20f; setTextColor(Color.WHITE); text = "user: $user" })
      addView(board)
      addView(status)
      addView(Button(context).apply { text = "Post ping"; setOnClickListener { post("ping ${++pings}") } })
    })
    board.text = "board: loading"
    status.text = "posted: none"
    val text = intent.data?.getQueryParameter("post")
    if (savedInstanceState == null && text != null) post(text)
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    poll()
  }

  override fun onPause() {
    resumed = false
    super.onPause()
  }

  override fun onDestroy() {
    io.shutdownNow()
    super.onDestroy()
  }

  private fun poll() {
    if (!resumed || io.isShutdown) return
    io.execute {
      val shown = try {
        val messages = JSONObject(call(Request.Builder().url("$BASE/v1/board"))).getJSONArray("messages")
        if (messages.length() == 0) "empty"
        else (0 until messages.length()).joinToString(" | ") {
          val m = messages.getJSONObject(it)
          "${m.getString("from")}: ${m.getString("text")}"
        }
      } catch (e: Exception) {
        "error ${e.javaClass.simpleName}: ${e.message}"
      }
      main.post {
        val text = "board: $shown"
        if (board.text.toString() != text) board.text = text  // a still board lets the screen settle

        main.postDelayed({ poll() }, 500)
      }
    }
  }

  private fun post(text: String) {
    io.execute {
      val result = try {
        val body = JSONObject().put("from", user).put("text", text).toString()
        "posted: $text (${JSONObject(call(Request.Builder().url("$BASE/v1/board").post(body.toRequestBody(JSON)))).getInt("count")} on the board)"
      } catch (e: Exception) {
        "post failed: ${e.javaClass.simpleName}: ${e.message}"
      }
      main.post { status.text = result }
    }
  }

  private fun call(b: Request.Builder): String =
    client.newCall(b.header("Authorization", "Bearer board-$user").build()).execute().use { r ->
      check(r.isSuccessful) { "HTTP ${r.code}" }
      r.body.string()
    }

  companion object {
    const val BASE = GreetingClient.BASE
    private val JSON = "application/json".toMediaType()
    private val client by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }
  }
}
