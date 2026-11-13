package dev.apksim.composeapp

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

/**
 * apksim://health: Health Connect as an app sees it through AndroidX's client (apksim's stand-in, `--health week`,
 * docs/health-connect.md). One line each: "health: available", "permissions: 5 of 5 granted", "steps today: 8412", "steps this week: 71250",
 * "heart rate: 74 bpm at 14:05", "sleep: 7 h 52 min last night". Without the permissions it asks for them with the
 * client's own contract (a runtime permission request). "Insert 100 steps" writes a record for the last five minutes
 * and reads everything again.
 */
class HealthActivity : ComponentActivity() {
  private val lines = LinkedHashMap<String, TextView>()
  private var client: HealthConnectClient? = null

  private val request = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
    show("permissions", "${granted.intersect(PERMISSIONS).size} of ${PERMISSIONS.size} granted")
    refresh()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.rgb(0x20, 0x50, 0x40))
    }
    for (key in listOf("health", "permissions", "steps today", "steps this week", "heart rate", "sleep", "inserted")) {
      val v = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
      lines[key] = v
      root.addView(v)
    }
    root.addView(Button(this).apply { text = "Insert 100 steps"; setOnClickListener { insert() } })
    root.addView(Button(this).apply { text = "Ask for permissions"; setOnClickListener { request.launch(PERMISSIONS) } })
    setContentView(root)
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      show("health", "unavailable (status $status)")
      return
    }
    show("health", "available")
    client = HealthConnectClient.getOrCreate(this)
    lifecycleScope.launch {
      val granted = client!!.permissionController.getGrantedPermissions()
      show("permissions", "${granted.intersect(PERMISSIONS).size} of ${PERMISSIONS.size} granted")
      if (!granted.containsAll(PERMISSIONS)) request.launch(PERMISSIONS) else refresh()
    }
  }

  private fun refresh() {
    val c = client ?: return
    lifecycleScope.launch {
      try {
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val steps = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(midnight, now)))
        show("steps today", "${steps[StepsRecord.COUNT_TOTAL] ?: 0}")
        val week = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(midnight.minus(6, ChronoUnit.DAYS), now)))
        show("steps this week", "${week[StepsRecord.COUNT_TOTAL] ?: 0}")
        val hr = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.after(now.minus(1, ChronoUnit.DAYS)),
          ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.samples?.maxByOrNull { it.time }
        show("heart rate", if (hr == null) "none" else "${hr.beatsPerMinute} bpm at ${TIME.format(hr.time.atZone(zone))}")
        // Two days back: a read takes the sessions that start in the window, and last night's began yesterday evening.
        val sleep = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.after(now.minus(2, ChronoUnit.DAYS)),
          ascendingOrder = false, pageSize = 1)).records.firstOrNull()
        show("sleep", if (sleep == null) "none" else {
          val m = ChronoUnit.MINUTES.between(sleep.startTime, sleep.endTime)
          "${m / 60} h ${m % 60} min last night"
        })
      } catch (e: Exception) {
        show("steps today", "error: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
  }

  private fun insert() {
    val c = client ?: return
    lifecycleScope.launch {
      try {
        val end = Instant.now()
        val offset = ZoneOffset.systemDefault().rules.getOffset(end)
        // Within today, so today's total goes up by exactly 100 whatever the time.
        val midnight = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val start = maxOf(end.minus(5, ChronoUnit.MINUTES), midnight)
        val ids = c.insertRecords(listOf(StepsRecord(start, offset, if (end > start) end else start.plusMillis(1), offset, 100,
          Metadata.manualEntry()))).recordIdsList
        show("inserted", "${ids.size} record")
        refresh()
      } catch (e: Exception) {
        show("inserted", "error: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
  }

  private fun show(key: String, text: String) = runOnUiThread { lines[key]?.text = "$key: $text" }

  companion object {
    val PERMISSIONS = setOf(
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.getWritePermission(StepsRecord::class),
      HealthPermission.getReadPermission(HeartRateRecord::class),
      HealthPermission.getWritePermission(HeartRateRecord::class),
      HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
  }
}
