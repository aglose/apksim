package dev.apksim.composeapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView

/**
 * apksim://idle-zoo?on=a,b,c: one independent source of "the app still has work" per switch (the family of issues
 * where apksim decided wrongly whether the app is idle: 83, 131, 197, 204, 215, 360, 459, 475, 479, 489). Plain Views.
 * A source is on when it is named in `on=` or its button is pressed; every source has a label
 * "NAME: on|off, N counted (expect idle|busy|busy then idle within N ms)" which `nodes` shows. Labels are rewritten
 * only by the "Refresh" button and when a source is switched, never by the source itself: a ticker that changed the
 * screen would not be idle. `scripts/idle-zoo-check.py` and `IdleZooTest` drive it.
 *
 * What a device user would call each source (the table both check against; [Source.expect]):
 * - ticker: a Runnable reposting itself every 16 ms, changing nothing: IDLE (the screen is done).
 * - slowticker: the same every 500 ms: IDLE.
 * - caret: a focused EditText, its caret blinking (issue 479): IDLE (nobody calls a blinking caret an animation).
 * - scroller: an OverScroller run through computeScroll for 1000 ms, no animator, as DrawerLayout settles: BUSY then
 *   IDLE within 1000 ms (the view moves meanwhile).
 * - animator: an INFINITE ValueAnimator invalidating a small view: BUSY (a real, endless animation).
 * - viewanim: a 300 ms AlphaAnimation: BUSY then IDLE within 300 ms.
 * - sensor: a SENSOR_DELAY_GAME accelerometer listener that touches no view: IDLE.
 * - overlay: an added 0x0 non-focusable overlay window (issue 83): IDLE.
 * - popup: an open PopupWindow with static text: IDLE.
 * - worker: a background thread posting a no-op to the main looper every 50 ms: IDLE.
 * - progress: an indeterminate ProgressBar: BUSY (a spinner a user sees turning).
 */
class IdleZooActivity : Activity() {
  enum class Expect { IDLE, BUSY, BUSY_THEN_IDLE }

  /** The name is what `on=` takes, [expect] what a device user would call it, [withinMs] when a BUSY_THEN_IDLE source is done. */
  enum class Source(val expect: Expect, val withinMs: Int = 0) {
    ticker(Expect.IDLE),
    slowticker(Expect.IDLE),
    caret(Expect.IDLE),
    scroller(Expect.BUSY_THEN_IDLE, 1000),
    animator(Expect.BUSY),
    viewanim(Expect.BUSY_THEN_IDLE, 300),
    sensor(Expect.IDLE),
    overlay(Expect.IDLE),
    popup(Expect.IDLE),
    worker(Expect.IDLE),
    progress(Expect.BUSY),
  }

  private val handler = Handler(Looper.getMainLooper())
  private val on = HashSet<Source>()
  private val counts = java.util.concurrent.ConcurrentHashMap<Source, Int>()
  private val labels = HashMap<Source, TextView>()
  private lateinit var column: LinearLayout
  private lateinit var stage: FrameLayout

  private var caretField: EditText? = null
  private var scrollView: ScrollerView? = null
  private var animView: Swatch? = null
  private var animator: ValueAnimator? = null
  private var fadeView: View? = null
  private var overlayView: View? = null
  private var popup: PopupWindow? = null
  private var progressBar: ProgressBar? = null
  private var sensorManager: SensorManager? = null
  private var sensorListener: SensorEventListener? = null
  @Volatile private var workerRun = false
  private var worker: Thread? = null
  private val tickers = HashMap<Source, Runnable>()

  /** A view moved by an OverScroller from computeScroll, as ViewDragHelper does for a drawer: no animator. */
  private class ScrollerView(context: Context, val frames: () -> Unit) : View(context) {
    val scroller = OverScroller(context)
    private val paint = Paint().apply { color = Color.BLUE }
    fun start() { scroller.startScroll(0, 0, 0, 40, 1000); invalidate() }
    override fun computeScroll() {
      if (scroller.computeScrollOffset()) {
        scrollTo(0, -scroller.currY)
        frames()
        postInvalidateOnAnimation()
      }
    }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width / 2f, 20f, paint) }
  }

  private class Swatch(context: Context) : View(context) {
    var hue = 0f
    private val paint = Paint()
    override fun onDraw(c: Canvas) {
      paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
      c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 64, 16, 16) }
    for (s in Source.values()) {
      val label = TextView(this).apply { textSize = 12f }
      val button = Button(this).apply { text = s.name; textSize = 11f; setOnClickListener { toggle(s) } }
      labels[s] = label
      column.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { gravity = Gravity.CENTER_VERTICAL })
      })
    }
    column.addView(Button(this).apply { text = "Refresh"; setOnClickListener { refresh() } })
    stage = FrameLayout(this)
    column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300))
    setContentView(column)
    val named = intent?.data?.getQueryParameter("on").orEmpty().split(',').filter { it.isNotBlank() }
    for (n in named) {
      val s = Source.values().firstOrNull { it.name == n }
      if (s == null) android.util.Log.w("IdleZoo", "unknown source $n") else start(s)
    }
    refresh()
  }

  /** A restartable source (scroller, viewanim) runs again when pressed; any other is switched off when on. */
  private fun toggle(s: Source) {
    if (s in on && s != Source.scroller && s != Source.viewanim) stop(s) else start(s)
    refresh()
  }

  private fun bump(s: Source) { counts.merge(s, 1, Int::plus) }

  private fun ticker(s: Source, period: Long) {
    val r = object : Runnable { override fun run() { bump(s); if (s in on) handler.postDelayed(this, period) } }
    tickers[s] = r
    handler.postDelayed(r, period)
  }

  private fun start(s: Source) {
    on += s
    when (s) {
      Source.ticker -> if (s !in tickers) ticker(s, 16)
      Source.slowticker -> if (s !in tickers) ticker(s, 500)
      Source.caret -> if (caretField == null) {
        caretField = EditText(this).apply { hint = "caret"; setSingleLine() }.also { column.addView(it); it.requestFocus() }
      }
      Source.scroller -> {
        val v = scrollView ?: ScrollerView(this) { bump(s) }.also { scrollView = it; stage.addView(it, FrameLayout.LayoutParams(300, 20, Gravity.TOP)) }
        v.start()
      }
      Source.animator -> if (animator == null) {
        val v = animView ?: Swatch(this).also { animView = it; stage.addView(it, FrameLayout.LayoutParams(60, 60, Gravity.END)) }
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
          duration = 2000
          repeatCount = ValueAnimator.INFINITE
          addUpdateListener { v.hue = it.animatedValue as Float; v.invalidate(); bump(s) }
          start()
        }
      }
      Source.viewanim -> {
        val v = fadeView ?: View(this).apply { setBackgroundColor(Color.RED) }.also { fadeView = it; stage.addView(it, FrameLayout.LayoutParams(60, 60, Gravity.BOTTOM)) }
        v.startAnimation(AlphaAnimation(1f, 0.2f).apply {
          duration = 300
          setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
            override fun onAnimationEnd(a: android.view.animation.Animation?) { bump(s) }
          })
        })
      }
      Source.sensor -> if (sensorListener == null) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val l = object : SensorEventListener {
          override fun onSensorChanged(e: SensorEvent) { bump(s) }
          override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorListener = l
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
      }
      Source.overlay -> window.decorView.post {
        if (s !in on || overlayView != null) return@post
        val v = FrameLayout(this)
        overlayView = v
        val params = WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
        getSystemService(WindowManager::class.java).addView(v, params)
        bump(s)
      }
      Source.popup -> window.decorView.post {
        if (s !in on || popup != null) return@post
        val content = TextView(this).apply { text = "popup"; setBackgroundColor(Color.LTGRAY); setPadding(24, 24, 24, 24) }
        popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
          it.showAtLocation(window.decorView, Gravity.BOTTOM or Gravity.START, 40, 200)
        }
        bump(s)
      }
      Source.worker -> if (worker == null) {
        workerRun = true
        worker = Thread {
          while (workerRun) {
            handler.post { bump(s) }
            try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
          }
        }.also { it.isDaemon = true; it.name = "idle-zoo-worker"; it.start() }
      }
      Source.progress -> if (progressBar == null) {
        progressBar = ProgressBar(this).apply { isIndeterminate = true }.also { stage.addView(it, FrameLayout.LayoutParams(60, 60, Gravity.CENTER)) }
        bump(s)
      }
    }
  }

  private fun stop(s: Source) {
    on -= s
    when (s) {
      Source.ticker, Source.slowticker -> tickers.remove(s)?.let { handler.removeCallbacks(it) }
      Source.caret -> caretField?.let { column.removeView(it); caretField = null }
      Source.scroller -> scrollView?.let { stage.removeView(it); scrollView = null }
      Source.animator -> { animator?.cancel(); animator = null; animView?.let { stage.removeView(it) }; animView = null }
      Source.viewanim -> fadeView?.let { it.clearAnimation(); stage.removeView(it); fadeView = null }
      Source.sensor -> sensorListener?.let { sensorManager?.unregisterListener(it); sensorListener = null }
      Source.overlay -> overlayView?.let { getSystemService(WindowManager::class.java).removeView(it); overlayView = null }
      Source.popup -> popup?.let { it.dismiss(); popup = null }
      Source.worker -> { workerRun = false; worker?.interrupt(); worker = null }
      Source.progress -> progressBar?.let { stage.removeView(it); progressBar = null }
    }
  }

  private fun refresh() {
    for (s in Source.values()) {
      val e = when (s.expect) {
        Expect.IDLE -> "idle"
        Expect.BUSY -> "busy"
        Expect.BUSY_THEN_IDLE -> "busy then idle within ${s.withinMs} ms"
      }
      labels[s]!!.text = "${s.name}: ${if (s in on) "on" else "off"}, ${counts[s] ?: 0} counted (expect $e)"
    }
  }

  override fun onDestroy() {
    for (s in on.toList()) stop(s)
    super.onDestroy()
  }
}
