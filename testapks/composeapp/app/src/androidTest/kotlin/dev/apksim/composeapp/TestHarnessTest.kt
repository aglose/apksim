package dev.apksim.composeapp

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TimeZone
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException

/**
 * What a large suite's base test class leans on: a JUnit Timeout rule around Compose and Espresso, shell commands
 * through UiAutomation, and the wall clock as the framework and the app both see it. Each passes on a device too.
 */
@RunWith(AndroidJUnit4::class)
class TimeoutRuleTest {
  private val compose = createAndroidComposeRule<MainActivity>()

  // JUnit's Timeout runs the test body on a thread of its own, with a wall-clock limit.
  @get:Rule val rules: RuleChain = RuleChain.outerRule(Timeout.seconds(30)).around(compose)

  @Test
  fun composeAndEspressoWorkUnderATimeout() {
    compose.onNodeWithText("FAB clicks: 0").assertIsDisplayed()
    compose.onNodeWithContentDescription("Add").performClick()
    compose.onNodeWithText("FAB clicks: 1").assertIsDisplayed()
    onView(isRoot()).check(matches(isDisplayed()))
  }

  @Test
  fun aTestPastItsTimeoutFails() {
    val slow = object : Statement() {
      override fun evaluate() = Thread.sleep(10_000)
    }
    val start = SystemClock.elapsedRealtime()
    val startNanos = System.nanoTime()
    try {
      Timeout.millis(300).apply(slow, Description.EMPTY).evaluate()
      fail("a 10 s statement under a 300 ms timeout finished")
    } catch (e: TestTimedOutException) {
      assertEquals(300L, e.timeout)
    }
    val tookMs = (System.nanoTime() - startNanos) / 1_000_000
    assertTrue("the timeout took $tookMs ms to fire", tookMs < 5_000)
    assertTrue(SystemClock.elapsedRealtime() >= start)
  }
}

@RunWith(AndroidJUnit4::class)
class ShellCommandTest {
  private fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  @Test
  fun getpropAnswers() {
    assertEquals(Build.VERSION.SDK_INT.toString(), shell("getprop ro.build.version.sdk").trim())
    assertTrue(shell("/system/bin/getprop ro.serialno").isNotBlank())
    assertTrue(shell("getprop ro.build.display.id").isNotBlank())
  }

  @Test
  fun animatorDurationScaleSettingReachesTheApp() {
    try {
      shell("settings put global animator_duration_scale 0")
      shell("settings put global transition_animation_scale 0")
      assertEquals(0f, shell("settings get global animator_duration_scale").trim().toFloat())
      assertEquals(0f, shell("settings get global transition_animation_scale").trim().toFloat())
      // On a device the window manager tells the app's process a moment later.
      val deadline = System.nanoTime() + 5_000_000_000L
      while (ValueAnimator.getDurationScale() != 0f && System.nanoTime() < deadline) Thread.sleep(20)
      assertEquals(0f, ValueAnimator.getDurationScale())
    } finally {
      shell("settings put global animator_duration_scale 1")
      shell("settings put global transition_animation_scale 1")
    }
  }

  @Test
  fun touchMakesAFileTheShellSees() {
    val name = ".apksimShellTest${System.nanoTime()}"
    try {
      shell("touch /sdcard/$name")
      assertTrue(shell("ls -a /sdcard").lines().any { it.trim() == name })
    } finally {
      shell("rm /sdcard/$name")
    }
  }

  /** An ordered broadcast's result comes back to the shell, as `am broadcast` prints it. */
  @Test
  fun amBroadcastReportsTheReceiversResult() {
    val action = "dev.apksim.composeapp.TEST_BROADCAST"
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        setResult(7, "pong", null)
      }
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    if (Build.VERSION.SDK_INT >= 33) {
      context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
    } else {
      context.registerReceiver(receiver, IntentFilter(action))
    }
    try {
      val out = shell("am broadcast -a $action")
      assertTrue("am broadcast printed: $out", out.contains("Broadcast completed: result=7, data=\"pong\""))
    } finally {
      context.unregisterReceiver(receiver)
    }
  }

  @Test
  fun logcatHoldsTheProcessLog() {
    shell("logcat -c")
    val marker = "marker-${System.nanoTime()}"
    Log.i("ShellCommandTest", marker)
    val deadline = System.nanoTime() + 5_000_000_000L
    var log = shell("logcat -d")
    while (!log.contains(marker) && System.nanoTime() < deadline) {
      Thread.sleep(50)
      log = shell("logcat -d")
    }
    assertTrue("logcat -d did not show the line just logged", log.contains(marker))
    shell("logcat -c")
    assertTrue("logcat -c left the line in", !shell("logcat -d").contains(marker))
  }
}

@RunWith(AndroidJUnit4::class)
class WallClockTest {
  private val onApksim = Build.FINGERPRINT == "robolectric"

  @Test
  fun frameworkAndAppAgreeOnTheTime() {
    val now = System.currentTimeMillis()
    assertTrue("DateUtils.isToday(now) is false", DateUtils.isToday(now))
    // android.icu reads the clock inside the framework, the app above reads it itself.
    val icu = android.icu.util.Calendar.getInstance().timeInMillis
    assertTrue("the framework's clock is ${now - icu} ms from the app's", abs(icu - now) < 60_000)
    assertTrue(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) >= 2025)
  }

  /** With `-e timezone ZONE`, as a CI job that sets the device's zone passes it. */
  @Test
  fun timezoneIsTheOneAsked() {
    val zone = InstrumentationRegistry.getArguments().getString("timezone")
    assumeTrue(zone != null)
    assertEquals(zone, TimeZone.getDefault().id)
    assertEquals(zone, android.icu.util.TimeZone.getDefault().id)
    // Joda-Time (up to 2.10) reads user.timezone before the JDK's default: apksim left it unset here and at the host's
    // zone in warm launches, where a day-off Joda date showed (issue 91). A device may leave it unset; apksim sets it.
    if (onApksim) assertEquals(zone, System.getProperty("user.timezone"))
    assertEquals(zone, org.joda.time.DateTimeZone.getDefault().id)
    assertEquals(java.time.LocalDate.now(), org.joda.time.LocalDate.now().let { java.time.LocalDate.of(it.year, it.monthOfYear, it.dayOfMonth) })
  }

  /** `apksim timezone ZONE` changes every zone source and broadcasts ACTION_TIMEZONE_CHANGED, as a device does. */
  @Test
  fun timezoneCommandChangesTheZoneAndBroadcastsIt() {
    assumeTrue("the zone is changed with apksim's `timezone` command", onApksim)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val heard = mutableListOf<String?>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        heard += intent.getStringExtra(Intent.EXTRA_TIMEZONE)
      }
    }
    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), Context.RECEIVER_EXPORTED)
    try {
      val zone = if (TimeZone.getDefault().id == "Asia/Tokyo") "Europe/Paris" else "Asia/Tokyo"
      val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("apksim timezone $zone")
      assertEquals(zone, ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }.trim())
      androidx.test.espresso.Espresso.onIdle()
      assertEquals(listOf<String?>(zone), heard)
      assertEquals(zone, TimeZone.getDefault().id)
      assertEquals(zone, android.icu.util.TimeZone.getDefault().id)
      assertEquals(zone, System.getProperty("user.timezone"))
    } finally {
      context.unregisterReceiver(receiver)
    }
  }
}
