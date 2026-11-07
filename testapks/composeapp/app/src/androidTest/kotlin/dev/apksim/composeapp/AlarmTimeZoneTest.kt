package dev.apksim.composeapp

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** AlarmManager.setTimeZone called directly, as a setup wizard's zone picker calls it (issue 113). */
@RunWith(AndroidJUnit4::class)
class AlarmTimeZoneTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val alarms = context.getSystemService(AlarmManager::class.java)

  @Test
  fun setTimeZoneWithAValidIdChangesEveryZoneSourceAndBroadcastsIt() {
    val original = TimeZone.getDefault().id
    val zone = if (original == "America/Los_Angeles") "Europe/Paris" else "America/Los_Angeles"
    val heard = mutableListOf<String?>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        heard += intent.getStringExtra(Intent.EXTRA_TIMEZONE)
      }
    }
    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), Context.RECEIVER_EXPORTED)
    try {
      alarms.setTimeZone(zone)
      Espresso.onIdle()
      assertEquals(zone, TimeZone.getDefault().id)
      assertEquals(zone, android.icu.util.TimeZone.getDefault().id)
      assertEquals(zone, System.getProperty("user.timezone"))
      assertEquals(listOf<String?>(zone), heard)
    } finally {
      alarms.setTimeZone(original)
      context.unregisterReceiver(receiver)
    }
  }

  @Test
  fun setTimeZoneRejectsAnUnknownIdAndKeepsTheZone() {
    val before = TimeZone.getDefault().id
    try {
      alarms.setTimeZone("Not/AZone")
      throw AssertionError("an unknown zone ID was accepted")
    } catch (e: IllegalArgumentException) {
      // as on a device
    }
    assertEquals(before, TimeZone.getDefault().id)
  }

  @Test
  fun setTimeZoneIgnoresNullAndEmptyIds() {
    val before = TimeZone.getDefault().id
    alarms.setTimeZone(null as String?)
    alarms.setTimeZone("")
    assertEquals(before, TimeZone.getDefault().id)
  }
}
