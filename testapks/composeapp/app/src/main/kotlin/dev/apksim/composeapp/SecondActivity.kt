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
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(COLOURS[(depth - 1) % COLOURS.size])
      addView(label)
      addView(tap)
      addView(deeper)
      addView(finish)
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

  companion object {
    @Volatile var createdWith: String? = null
    @Volatile var resumed = false
    private val COLOURS = intArrayOf(Color.rgb(140, 30, 30), Color.rgb(30, 110, 40), Color.rgb(30, 50, 140), Color.rgb(120, 90, 20))
  }
}

/** A dialog-themed (floating) activity: the activity under it stays visible, paused, as on a device. */
class FloatingActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(TextView(this).apply { text = "Floating"; textSize = 24f; setPadding(48, 48, 48, 48) })
  }
}
