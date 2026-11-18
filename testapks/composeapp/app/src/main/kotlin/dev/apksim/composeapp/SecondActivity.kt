package dev.apksim.composeapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An activity another one starts (StartActivityTest): it records that it came up, with the intent's extra.
 * `apksim://stack` opens it over whatever is running, and "Deeper" stacks another, so a running instance can be
 * several activities deep: each level has its own background colour and counts its own taps, which shows which
 * window draws and which takes touches (backgrounded, recreated or rebuilt after process death). Plain views, for
 * the `viewtree` command.
 */
class SecondActivity : Activity() {
  private var taps = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    createdWith = intent.getStringExtra("from")
    val depth = intent.getIntExtra("depth", 1)
    taps = savedInstanceState?.getInt("taps") ?: 0
    val label = TextView(this).apply {
      id = R.id.stack_label
      textSize = 24f
      setTextColor(Color.WHITE)
    }
    fun show() { label.text = "Stack $depth: $taps taps" }
    show()
    val tap = Button(this).apply {
      id = R.id.stack_tap
      text = "Tap $depth"
      setOnClickListener { taps++; show() }
    }
    val deeper = Button(this).apply {
      id = R.id.stack_deeper
      text = "Deeper"
      setOnClickListener {
        startActivity(Intent(this@SecondActivity, SecondActivity::class.java).putExtra("from", "stack").putExtra("depth", depth + 1))
      }
    }
    val finish = Button(this).apply {
      text = "Finish"
      setOnClickListener { finish() }
    }
    // The usual hand-off: the next screen started, this one finished.
    val handOff = Button(this).apply {
      text = "Hand off"
      setOnClickListener {
        startActivity(Intent(this@SecondActivity, SecondActivity::class.java).putExtra("from", "stack").putExtra("depth", depth + 1))
        finish()
      }
    }
    // A floating activity on top, then this one finishes under it: the one below this shows again, paused.
    val floatThenFinish = Button(this).apply {
      text = "Float, then finish"
      setOnClickListener {
        startActivity(Intent(this@SecondActivity, FloatingActivity::class.java))
        postDelayed({ finish() }, 1000)
      }
    }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(COLOURS[(depth - 1) % COLOURS.size])
      addView(label)
      addView(tap)
      addView(deeper)
      addView(finish)
      addView(handOff)
      addView(floatThenFinish)
    })
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt("taps", taps)
  }

  override fun onResume() {
    super.onResume()
    resumed = true
  }

  override fun onStop() {
    super.onStop()
    android.util.Log.i("Stack", "onStop depth ${intent.getIntExtra("depth", 1)}")
  }

  override fun onDestroy() {
    super.onDestroy()
    android.util.Log.i("Stack", "onDestroy depth ${intent.getIntExtra("depth", 1)}")
  }

  companion object {
    @Volatile var createdWith: String? = null
    @Volatile var resumed = false
    private val COLOURS = intArrayOf(Color.rgb(140, 30, 30), Color.rgb(30, 110, 40), Color.rgb(30, 50, 140), Color.rgb(120, 90, 20))
  }
}

/**
 * A dialog-themed (floating) activity: the activity under it stays visible, paused, as on a device. `?popup=1` shows a
 * PopupWindow at TOP|START 48,100 of its window (a sub-window is placed in the window it is attached to: 139,833 on the
 * phone), `?popup=offscreen` one with clipping off at -100,100 (FLAG_LAYOUT_NO_LIMITS: left partly off the display).
 */
class FloatingActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val content = TextView(this).apply { text = "Floating"; textSize = 24f; setPadding(48, 48, 48, 48) }
    setContentView(content)
    val popup = intent.data?.getQueryParameter("popup") ?: return
    content.post {
      val p = android.widget.PopupWindow(TextView(this).apply {
        text = "Popup on the floating window"
        setBackgroundColor(android.graphics.Color.rgb(200, 160, 40))
        setPadding(24, 24, 24, 24)
      }, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false)
      if (popup == "offscreen") p.isClippingEnabled = false
      p.showAtLocation(content, Gravity.TOP or Gravity.START, if (popup == "offscreen") -100 else 48, 100)
    }
  }
}

/**
 * A floating, translucent, dimming activity with WRAP_CONTENT x WRAP_CONTENT window layout whose list is taller than
 * the display (apksim://floating-tall, issue 506): the window should be shorter than the display, as on a device.
 */
class FloatingTallActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val list = android.widget.ListView(this)
    list.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, List(200) { "Row $it" })
    setContentView(list)
    window.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    if (intent.data?.getQueryParameter("bars") == "hide") {
      window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
    }
  }
}
