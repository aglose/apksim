package dev.apksim.composeapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.HandlerThread
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SensorManager's sensors (apksim's Sensors): the device lists the sensors an emulator does, a registered listener gets
 * an event at once (an app awaiting a first value must not wait for ever), at roughly the rate it asked for, on its
 * Handler's thread, with timestamps from SystemClock.elapsedRealtimeNanos, and none after it unregisters. The rest
 * (setting the light, proximity, the pose; taking a sensor away) needs apksim's `sensor` shell command and is skipped on a
 * device. Passes on the API 36 emulator as well.
 */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class SensorsTest {
  companion object {
    /** A Sensor kept in a static across tests, as an app singleton does (see kept1/kept2). */
    @Volatile var kept: Sensor? = null
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val onApksim = Build.FINGERPRINT == "robolectric"
  private val thread = HandlerThread("sensor-test").also { it.start() }
  private val handler = Handler(thread.looper)
  private val registered = mutableListOf<SensorEventListener>()

  private fun shell(command: String): String {
    val pfd = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  /** Collects a sensor's events, with the thread each came on. */
  private inner class Collector(val sensor: Sensor, rate: Int = SensorManager.SENSOR_DELAY_GAME) : SensorEventListener {
    val events = CopyOnWriteArrayList<FloatArray>()
    val timestamps = CopyOnWriteArrayList<Long>()
    val threads = CopyOnWriteArrayList<String>()
    val sensors = CopyOnWriteArrayList<Sensor>()
    var first = CountDownLatch(1)
    val ok: Boolean

    init {
      ok = sm.registerListener(this, sensor, rate, handler)
      registered += this
    }

    override fun onSensorChanged(event: SensorEvent) {
      events += event.values.clone()
      timestamps += event.timestamp
      threads += Thread.currentThread().name
      sensors += event.sensor
      first.countDown()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    fun await(timeoutMs: Long = 5000) = first.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Waits until an event satisfies [p], or the time is up. */
    fun until(timeoutMs: Long = 5000, p: (FloatArray) -> Boolean): Boolean {
      val end = System.nanoTime() + timeoutMs * 1_000_000
      while (System.nanoTime() < end) {
        if (events.any(p)) return true
        Thread.sleep(20)
      }
      return events.any(p)
    }
  }

  @Before
  fun setUp() {
    if (onApksim) shell("apksim sensor reset")
  }

  @After
  fun tearDown() {
    registered.forEach { sm.unregisterListener(it) }
    thread.quitSafely()
    if (onApksim) shell("apksim sensor reset")
  }

  /**
   * Lets time pass while the test waits: a real wait on a device; on apksim a CountDownLatch wait on the main thread runs
   * the main queue meanwhile with the clock following real time, where SystemClock.sleep would only move the clock.
   */
  private fun pause(ms: Long) {
    CountDownLatch(1).await(ms, TimeUnit.MILLISECONDS)
  }

  private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

  @Test
  fun aPhoneListsTheEmulatorsSensors() {
    for (t in listOf(
      Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY,
      Sensor.TYPE_PRESSURE, Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_GRAVITY,
      Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
      Sensor.TYPE_ORIENTATION, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
      Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
    )) {
      val s = sm.getDefaultSensor(t)
      assertNotNull("type $t", s)
      assertEquals(t, s!!.type)
      assertTrue(s.name, s.name.isNotEmpty() && s.maximumRange > 0f && s.resolution > 0f)
      assertTrue(sm.getSensorList(t).contains(s))
    }
    assertTrue(sm.getSensorList(Sensor.TYPE_ALL).size >= 17)
    assertNull("a phone has no heart rate sensor", sm.getDefaultSensor(Sensor.TYPE_HEART_RATE))
    assertEquals("on-change", Sensor.REPORTING_MODE_ON_CHANGE, sm.getDefaultSensor(Sensor.TYPE_LIGHT)!!.reportingMode)
    assertEquals(Sensor.REPORTING_MODE_CONTINUOUS, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!.reportingMode)
    assertTrue(sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)!!.isWakeUpSensor)
  }

  @Test
  fun theFeaturesFollowTheSensors() {
    val pm = context.packageManager
    for (f in listOf("accelerometer", "gyroscope", "compass", "light", "proximity", "barometer")) {
      assertTrue(f, pm.hasSystemFeature("android.hardware.sensor.$f"))
    }
    assertFalse(pm.hasSystemFeature("android.hardware.sensor.heartrate"))
  }

  @Test
  fun anAccelerometerListenerGetsAnEventAtOnceOnItsHandler() {
    val c = Collector(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!)
    assertTrue(c.ok)
    assertTrue("no accelerometer event", c.await())
    assertEquals("sensor-test", c.threads[0])
    assertEquals(Sensor.TYPE_ACCELEROMETER, c.sensors[0].type)
    assertEquals("at rest: gravity", 9.81f, norm(c.events[0]), 0.8f)
    val ts = c.timestamps[0]
    assertTrue("timestamp $ts vs ${SystemClock.elapsedRealtimeNanos()}", abs(SystemClock.elapsedRealtimeNanos() - ts) < 10_000_000_000L)
    if (onApksim) {
      assertEquals(0f, c.events[0][0], 0.01f)
      assertEquals(9.81f, c.events[0][1], 0.02f)  // upright in portrait
    }
  }

  @Test
  fun gravityAndTheGyroscopeAreAtRest() {
    val g = Collector(sm.getDefaultSensor(Sensor.TYPE_GRAVITY)!!)
    val y = Collector(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!)
    assertTrue(g.await() && y.await())
    assertEquals(9.81f, norm(g.events[0]), 0.8f)
    assertEquals(0f, norm(y.events[0]), 0.1f)
  }

  @Test
  fun eventsComeAtAboutTheRequestedRateAndStopAfterUnregister() {
    val c = Collector(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!, SensorManager.SENSOR_DELAY_GAME)  // 20 ms
    assertTrue(c.await())
    pause(1000)  // a real second on a device; the simulated clock moves it on apksim
    val n = c.events.size
    assertTrue("$n events in a second at 50 Hz", n in 25..120)
    val stamps = c.timestamps.toList()
    for (i in 1 until stamps.size) assertTrue("timestamps rise", stamps[i] > stamps[i - 1])
    sm.unregisterListener(c)
    pause(100)  // an event already queued may still arrive
    val after = c.events.size
    pause(500)
    assertEquals("no events after unregisterListener", after, c.events.size)
  }

  @Test
  fun onChangeSensorsDeliverTheirValueAtRegistration() {
    val light = Collector(sm.getDefaultSensor(Sensor.TYPE_LIGHT)!!, SensorManager.SENSOR_DELAY_NORMAL)
    val prox = Collector(sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)!!, SensorManager.SENSOR_DELAY_NORMAL)
    assertTrue("light", light.await())
    assertTrue("proximity", prox.await())
    assertEquals(1, light.events[0].size)
    assertTrue(light.events[0][0] >= 0f)
    assertTrue(prox.events[0][0] >= 0f)
  }

  @Test
  fun aSecondRegistrationOfTheSameListenerAndSensorIsRefusedAndKeepsTheFirst() {
    val c = Collector(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!, SensorManager.SENSOR_DELAY_GAME)
    assertTrue(c.await())
    assertFalse("already registered", sm.registerListener(c, c.sensor, SensorManager.SENSOR_DELAY_NORMAL, handler))
    val n = c.events.size
    pause(500)
    assertTrue("the first registration goes on at its rate", c.events.size - n > 5)
    sm.unregisterListener(c, c.sensor)
    pause(200)
    val after = c.events.size
    pause(300)
    assertEquals(after, c.events.size)
  }

  @Test
  fun lightAndProximityFollowTheShellCommand() {
    assumeTrue("the sensor shell command is apksim's", onApksim)
    val light = Collector(sm.getDefaultSensor(Sensor.TYPE_LIGHT)!!, SensorManager.SENSOR_DELAY_NORMAL)
    val prox = Collector(sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)!!, SensorManager.SENSOR_DELAY_NORMAL)
    assertTrue(light.await() && prox.await())
    shell("apksim sensor light 300")
    assertTrue("light 300: ${light.events.map { it[0] }}", light.until { it[0] == 300f })
    shell("apksim sensor proximity near")
    assertTrue("near", prox.until { it[0] == 0f })
    shell("apksim sensor proximity far")
    assertTrue("far", prox.until { it[0] == prox.sensor.maximumRange })
    val n = light.events.size
    shell("apksim sensor light 300")  // unchanged: no event
    pause(300)
    assertEquals(n, light.events.size)
  }

  @Test
  fun tiltAccelAndShakeMoveTheAccelerometer() {
    assumeTrue("the sensor shell command is apksim's", onApksim)
    val c = Collector(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!, SensorManager.SENSOR_DELAY_GAME)
    assertTrue(c.await())
    shell("apksim sensor accel 1 2 3")
    pause(100)
    assertEquals(listOf(1f, 2f, 3f), c.events.last().toList())
    shell("apksim sensor tilt 0 0")  // flat on a table
    pause(100)
    assertEquals(9.81f, c.events.last()[2], 0.02f)
    shell("apksim sensor shake 500")
    pause(400)
    assertTrue("a shake is large", c.events.maxOf { abs(it[0]) } > 5f)
    shell("apksim sensor reset")
    pause(700)
    assertEquals(9.81f, c.events.last()[1], 0.02f)
  }

  @Test
  fun aSensorTakenAwayIsGoneAndItsFeatureWith() {
    assumeTrue("the sensor shell command is apksim's", onApksim)
    shell("apksim sensor light off")
    assertNull(sm.getDefaultSensor(Sensor.TYPE_LIGHT))
    assertFalse(context.packageManager.hasSystemFeature("android.hardware.sensor.light"))
    shell("apksim sensor light on")
    assertNotNull(sm.getDefaultSensor(Sensor.TYPE_LIGHT))
    assertTrue(context.packageManager.hasSystemFeature("android.hardware.sensor.light"))
  }

  @Test
  fun stepsCountAndTheHeartRateWatchNeedsAPermission() {
    assumeTrue("the sensor shell command is apksim's", onApksim)
    // ACTIVITY_RECOGNITION is a runtime permission: without it the step sensors are hidden
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACTIVITY_RECOGNITION")
    val steps = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    assertNotNull(steps)
    val c = Collector(steps!!, SensorManager.SENSOR_DELAY_NORMAL)
    assertTrue(c.ok)
    assertTrue(c.await())
    assertEquals(0f, c.events[0][0], 0f)
    shell("apksim sensor steps +25")
    assertTrue("steps", c.until { it[0] == 25f })
    shell("apksim sensor steps 100")
    assertTrue("steps", c.until { it[0] == 100f })
  }

  @Test
  fun aContinuousListenerKeepsGettingEvents() {
    val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
    val n = java.util.concurrent.atomic.AtomicInteger()
    val l = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        n.incrementAndGet()
      }
      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    assertTrue(sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME, handler))
    registered += l
    pause(500)
    assertTrue("$n events", n.get() > 3)
  }

  @Test
  fun anAppAwaitingAFirstValueGetsOne() {
    // The hang this is for: a coroutine/suspend wrapper that registers and waits for the first event of a sensor.
    for (t in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT,
      Sensor.TYPE_PRESSURE, Sensor.TYPE_ROTATION_VECTOR)) {
      val c = Collector(sm.getDefaultSensor(t)!!, SensorManager.SENSOR_DELAY_NORMAL)
      assertTrue("type $t", c.await(3000))
    }
  }

  @Test
  fun kept1_aSensorIsKeptInAStatic() {
    kept = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    assertNotNull(kept)
  }

  @Test
  fun kept2_aSensorKeptFromAnEarlierTestStillRegisters() {
    val k = kept
    assumeTrue("kept1 runs first, in this process", k != null)
    val c = Collector(k!!)
    assertTrue("registerListener with a Sensor kept from before", c.ok)
    assertTrue(c.await())
    assertTrue("the same sensor is listed", sm.getSensorList(Sensor.TYPE_ACCELEROMETER).any { it.name == k.name })
  }

  @Test
  fun anAppResettingItsHandlerDoesNotEndTheStream() {
    val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
    val n = java.util.concurrent.atomic.AtomicInteger()
    val l = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        n.incrementAndGet()
        handler.removeCallbacksAndMessages(null)  // an inactivity timer reset
      }
      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    assertTrue(sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME, handler))
    registered += l
    pause(700)
    assertTrue("${n.get()} events", n.get() > 5)
  }

  @Test
  fun accuracyIsToldBeforeTheFirstEvent() {
    val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
    val order = CopyOnWriteArrayList<String>()
    val first = CountDownLatch(1)
    val l = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        order += "event"
        first.countDown()
      }
      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        order += "accuracy $accuracy"
      }
    }
    assertTrue(sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME, handler))
    registered += l
    assertTrue(first.await(5, TimeUnit.SECONDS))
    assertTrue("$order", order[0].startsWith("accuracy ") && order.indexOf("event") == 1)
    assertEquals("accuracy goes once", 1, order.count { it.startsWith("accuracy") })
  }

  @Test
  fun flushAndBadRates() {
    val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
    val plain = Collector(s)
    assertTrue(plain.await())
    assertTrue("flush of a registered listener", sm.flush(plain))
    assertFalse("flush of one not registered", sm.flush(object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {}
      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }))
    val l = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {}
      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    assertFalse("a negative rate", sm.registerListener(l, s, -1, handler))
  }

  @Test
  fun aSensorTakenAwayStopsItsEventsAndComesBackWithItsListener() {
    assumeTrue("the sensor shell command is apksim's", onApksim)
    val c = Collector(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!, SensorManager.SENSOR_DELAY_GAME)
    assertTrue(c.await())
    shell("apksim sensor accelerometer off")
    pause(200)
    val n = c.events.size
    pause(300)
    assertEquals("no events while it is away", n, c.events.size)
    shell("apksim sensor accelerometer on")
    pause(400)
    assertTrue("the listener hears it again", c.events.size > n)
  }

  @Test
  fun permissionGatedSensorsAreHiddenWithoutThePermission() {
    assumeTrue("a device with no step counter has nothing to hide", onApksim)
    instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, "android.permission.ACTIVITY_RECOGNITION")
    assertNull("hidden without ACTIVITY_RECOGNITION", sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER))
    assertTrue(sm.getSensorList(Sensor.TYPE_ALL).none { it.type == Sensor.TYPE_STEP_COUNTER || it.type == Sensor.TYPE_STEP_DETECTOR })
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACTIVITY_RECOGNITION")
    assertNotNull(sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER))
  }

  @Test
  fun theStepDetectorFiresOncePerStepWithRisingTimestamps() {
    assumeTrue("the sensor shell command is apksim's", onApksim)
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACTIVITY_RECOGNITION")
    val c = Collector(sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)!!, SensorManager.SENSOR_DELAY_NORMAL)
    assertTrue(c.ok)
    pause(100)
    assertEquals("nothing until a step", 0, c.events.size)
    shell("apksim sensor steps +3")
    assertTrue("3 steps", c.until { c.events.size >= 3 })
    pause(100)
    assertEquals(3, c.events.size)
    val t = c.timestamps.toList()
    assertTrue("rising: $t", t[0] < t[1] && t[1] < t[2])
  }

  /** The hang: a main-looper FASTEST listener that updates a view on every event, then an Espresso check (default test clock). */
  @Test
  fun zz_aFastMainLooperListenerThatRedrawsDoesNotHangEspresso() {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("apksim://sensors"))
      .setClassName(context.packageName, "dev.apksim.composeapp.SensorsActivity")
    val events = java.util.concurrent.atomic.AtomicInteger()
    var listener: SensorEventListener? = null
    androidx.test.core.app.ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
      scenario.onActivity { activity ->
        val tv = android.widget.TextView(activity)
        activity.addContentView(tv, android.view.ViewGroup.LayoutParams(300, 100))
        listener = object : SensorEventListener {
          override fun onSensorChanged(event: SensorEvent) {
            tv.text = "event ${events.incrementAndGet()}"  // a layout and a frame per event
          }
          override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_FASTEST)
      }
      try {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(org.hamcrest.Matchers.startsWith("sensors: ")))
          .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
      } finally {
        sm.unregisterListener(listener)
      }
    }
  }

  /** What a continuous main-looper listener costs a UiAutomator query: its time against the same query on a screen with none. */
  @Test
  fun zy_aUiAutomatorQueryWithSensorListenersTakesAboutAsLongAsWithout() {
    // On a device the screen's constant redraws keep UiAutomator waiting for idle (41 s measured): this is apksim's settle only.
    assumeTrue("apksim's idle wait", onApksim)
    val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
    fun query(uri: String, text: String): Long {
      val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(context.packageName)
      val t0 = System.nanoTime()
      androidx.test.core.app.ActivityScenario.launch<android.app.Activity>(intent).use {
        assertNotNull(text, device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textStartsWith(text)), 10_000))
        repeat(3) { device.findObject(androidx.test.uiautomator.By.textStartsWith(text)) }
      }
      return (System.nanoTime() - t0) / 1_000_000
    }
    val without = query("apksim://timezone", "jdk zone")
    val with = query("apksim://sensors", "sensors: ")
    assertTrue("with listeners $with ms, without $without ms", with < without + 5000)
  }
}
