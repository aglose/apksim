package dev.apksim.composeapp

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Duration
import java.time.LocalDateTime
import java.time.Period
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Health Connect through AndroidX's HealthConnectClient, which on API 34+ talks to the platform's HealthConnectManager:
 * apksim's stand-in (docs/health-connect.md) and an emulator's real service give the same answers. Availability,
 * granted permissions, insert and read back (order, paging, which intervals a time filter takes), upserts by client
 * id, aggregates (heart-rate statistics, hourly groups; steps' totals split by overlap on apksim only), change tokens,
 * and a read without its permission. Each test grants itself what it needs with `pm grant`, starts from no records of
 * its own and reads only its own (other apps', or apksim's seeds, may be there). Every test but the two marked apksim
 * only passes on an API 36 emulator too.
 * `-e healthSeed week` with `--health week` also checks the seeded week, apksim only.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HealthConnectTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val pkg = context.packageName
  private lateinit var client: HealthConnectClient
  /** Two days ago on the hour: inside the 30 days an app reads back without the history permission. */
  private val base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.DAYS)
  private val zone = ZoneOffset.UTC
  /** This app's own records: a device's other apps (or apksim's --health seeds) may have written some too. */
  private val own = setOf(DataOrigin(pkg))

  @Before
  fun setUp() {
    for (p in GRANTED) shell("pm grant $pkg $p")
    assertEquals(HealthConnectClient.SDK_AVAILABLE, HealthConnectClient.getSdkStatus(context))
    client = HealthConnectClient.getOrCreate(context)
    runBlocking {
      // On a device the store outlives the test: start from none of this app's own records.
      client.deleteRecords(StepsRecord::class, TimeRangeFilter.after(Instant.EPOCH))
      client.deleteRecords(HeartRateRecord::class, TimeRangeFilter.after(Instant.EPOCH))
      for ((type, write) in OWN_TYPES) {
        if (context.checkSelfPermission(write) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
          client.deleteRecords(type, TimeRangeFilter.after(Instant.EPOCH))
        }
      }
    }
  }

  private fun shell(cmd: String): String =
    instrumentation.uiAutomation.executeShellCommand(cmd).let { pfd ->
      android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

  private fun steps(from: Instant, minutes: Long, count: Long, clientId: String? = null, version: Long = 0) =
    StepsRecord(from, zone, from.plus(minutes, ChronoUnit.MINUTES), zone, count,
      if (clientId == null) Metadata.manualEntry() else Metadata.manualEntry(clientId, version))

  private fun heartRate(from: Instant, vararg bpm: Long) = HeartRateRecord(from, zone, from.plus(bpm.size.toLong(), ChronoUnit.MINUTES), zone,
    bpm.mapIndexed { i, b -> HeartRateRecord.Sample(from.plus(i.toLong(), ChronoUnit.MINUTES), b) }, Metadata.manualEntry())

  @Test
  fun a_grantedPermissionsAreTheAppsOwn() = runBlocking {
    val granted = client.permissionController.getGrantedPermissions()
    assertTrue(granted.toString(), granted.containsAll(GRANTED))
    assertFalse(granted.toString(), HealthPermission.getReadPermission(BodyFatRecord::class) in granted)
  }

  @Test
  fun b_insertAndReadBackInOrderAndByPage() = runBlocking {
    val ids = client.insertRecords(listOf(steps(base, 60, 1000), steps(base.plus(1, ChronoUnit.HOURS), 60, 500))).recordIdsList
    assertEquals(2, ids.size)
    val window = TimeRangeFilter.between(base, base.plus(2, ChronoUnit.HOURS))
    val up = client.readRecords(ReadRecordsRequest(StepsRecord::class, window, own)).records
    assertEquals(listOf(1000L, 500L), up.map { it.count })
    assertEquals(ids, up.map { it.metadata.id })
    assertEquals(pkg, up[0].metadata.dataOrigin.packageName)
    val down = client.readRecords(ReadRecordsRequest(StepsRecord::class, window, own, ascendingOrder = false)).records
    assertEquals(listOf(500L, 1000L), down.map { it.count })
    val first = client.readRecords(ReadRecordsRequest(StepsRecord::class, window, own, pageSize = 1))
    assertEquals(listOf(1000L), first.records.map { it.count })
    assertNotNull(first.pageToken)
    val second = client.readRecords(ReadRecordsRequest(StepsRecord::class, window, own, pageSize = 1, pageToken = first.pageToken))
    assertEquals(listOf(500L), second.records.map { it.count })
    assertNull(second.pageToken)
  }

  @Test
  fun c_aReadFiltersIntervalsByTheirStart() = runBlocking {
    client.insertRecords(listOf(steps(base, 60, 1000)))
    // An interval belongs to the window its start is in: one that began before the filter is not read, even though it
    // runs on into it; one that begins inside it is, even though it ends after it.
    val later = TimeRangeFilter.between(base.plus(30, ChronoUnit.MINUTES), base.plus(3, ChronoUnit.HOURS))
    assertEquals(0, client.readRecords(ReadRecordsRequest(StepsRecord::class, later, own)).records.size)
    val earlier = TimeRangeFilter.between(base.minus(30, ChronoUnit.MINUTES), base.plus(30, ChronoUnit.MINUTES))
    assertEquals(1, client.readRecords(ReadRecordsRequest(StepsRecord::class, earlier, own)).records.size)
    val endingAtIt = TimeRangeFilter.between(base.minus(1, ChronoUnit.HOURS), base)
    assertEquals(0, client.readRecords(ReadRecordsRequest(StepsRecord::class, endingAtIt, own)).records.size)
  }

  @Test
  fun d_anUpsertByClientIdReplacesTheRecord() = runBlocking {
    client.insertRecords(listOf(steps(base, 60, 100, clientId = "walk-1", version = 1)))
    client.insertRecords(listOf(steps(base, 60, 300, clientId = "walk-1", version = 2)))
    val all = client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(base.minusSeconds(1)), own)).records
    assertEquals(listOf(300L), all.map { it.count })
    assertEquals("walk-1", all[0].metadata.clientRecordId)
  }

  @Test
  fun e_heartRateAggregates() = runBlocking {
    client.insertRecords(listOf(heartRate(base, 60, 70, 80), heartRate(base.plus(1, ChronoUnit.HOURS), 90)))
    val two = TimeRangeFilter.between(base, base.plus(2, ChronoUnit.HOURS))
    val r = client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN,
      HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT), two, own))
    assertEquals(75L, r[HeartRateRecord.BPM_AVG])
    assertEquals(60L, r[HeartRateRecord.BPM_MIN])
    assertEquals(90L, r[HeartRateRecord.BPM_MAX])
    assertEquals(4L, r[HeartRateRecord.MEASUREMENTS_COUNT])
    assertTrue(r.dataOrigins.toString(), r.dataOrigins.any { it.packageName == pkg })
    val hourly = client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(HeartRateRecord.BPM_MAX), two, Duration.ofHours(1), own))
    assertEquals(listOf(80L, 90L), hourly.map { it.result[HeartRateRecord.BPM_MAX] })
    assertEquals(base, hourly[0].startTime)
    // Nothing in the window: no value, not zero.
    val empty = client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_MAX),
      TimeRangeFilter.between(base.minus(5, ChronoUnit.HOURS), base.minus(4, ChronoUnit.HOURS)), own))
    assertNull(empty[HeartRateRecord.BPM_MAX])
  }

  /**
   * Steps (and the other activity totals) count only apps in the data category's priority list. A device adds an app
   * to it when the user grants a write permission in Health Connect's own screens, not on `pm grant`, so an emulator
   * answers no value here; apksim counts every app that wrote, as for an app the user granted. apksim only.
   */
  @Test
  fun e_stepsTotalsSplitIntervalsByOverlap() = runBlocking {
    assumeTrue("an emulator counts only apps in its priority list, which pm grant does not add to", onApksim)
    client.insertRecords(listOf(steps(base, 60, 1000), steps(base.plus(1, ChronoUnit.HOURS), 60, 500)))
    val two = TimeRangeFilter.between(base, base.plus(2, ChronoUnit.HOURS))
    assertEquals(1500L, client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), two, own))[StepsRecord.COUNT_TOTAL])
    // A sum counts the part of an interval inside the window.
    val half = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
      TimeRangeFilter.between(base.plus(30, ChronoUnit.MINUTES), base.plus(1, ChronoUnit.HOURS)), own))
    assertEquals(500L, half[StepsRecord.COUNT_TOTAL])
    val hourly = client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), two, Duration.ofHours(1), own))
    assertEquals(listOf(1000L, 500L), hourly.map { it.result[StepsRecord.COUNT_TOTAL] })
  }

  @Test
  fun f_changesTokenSeesAnInsertThenADeletion() = runBlocking {
    val token = client.getChangesToken(ChangesTokenRequest(setOf(StepsRecord::class), own))
    val id = client.insertRecords(listOf(steps(base, 30, 42))).recordIdsList.single()
    client.insertRecords(listOf(heartRate(base, 61)))  // another type: not this token's
    val first = client.getChanges(token)
    assertEquals(1, first.changes.size)
    val up = first.changes.single() as UpsertionChange
    assertEquals(42L, (up.record as StepsRecord).count)
    assertFalse(first.hasMore)
    client.deleteRecords(StepsRecord::class, listOf(id), emptyList())
    val second = client.getChanges(first.nextChangesToken)
    assertEquals(id, (second.changes.single() as DeletionChange).recordId)
    assertTrue(client.getChanges(second.nextChangesToken).changes.isEmpty())
  }

  @Test
  fun g_aReadWithoutItsPermissionThrowsSecurityException() = runBlocking {
    deniedWithoutPermission("read body fat") { client.readRecords(ReadRecordsRequest(BodyFatRecord::class, TimeRangeFilter.after(base))) }
  }

  /** Writes, aggregates, change tokens and deletes check their permissions too (body fat and basal metabolic rate: never granted). */
  @Test
  fun g_writesAggregatesTokensAndDeletesNeedTheirPermission() = runBlocking {
    deniedWithoutPermission("insert body fat") {
      client.insertRecords(listOf(BodyFatRecord(base, zone, Percentage(20.0), Metadata.manualEntry())))
    }
    deniedWithoutPermission("aggregate basal calories") {
      client.aggregate(AggregateRequest(setOf(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL), TimeRangeFilter.after(base)))
    }
    deniedWithoutPermission("a changes token for body fat") { client.getChangesToken(ChangesTokenRequest(setOf(BodyFatRecord::class))) }
    deniedWithoutPermission("delete body fat") { client.deleteRecords(BodyFatRecord::class, TimeRangeFilter.after(base)) }
  }

  private suspend fun deniedWithoutPermission(what: String, call: suspend () -> Unit) {
    try {
      call()
      fail("$what without its permission")
    } catch (e: SecurityException) {
      // as on a device
    }
  }

  /**
   * With only the write permission an app reads back what it wrote itself by naming itself as the origin; a read that
   * does not is refused ("... from other applications"), as on an API 36 emulator.
   */
  @Test
  fun i_aWriteOnlyAppReadsItsOwnRecords() = runBlocking {
    shell("pm grant $pkg ${HealthPermission.getWritePermission(SleepSessionRecord::class)}")
    assumeFalse("READ_SLEEP was granted earlier on this device", context.checkSelfPermission(
      HealthPermission.getReadPermission(SleepSessionRecord::class)) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    client.insertRecords(listOf(sleep(base.minus(10, ChronoUnit.HOURS))))
    val month = TimeRangeFilter.after(base.minus(30, ChronoUnit.DAYS))
    val read = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, month, own)).records
    assertEquals(listOf(pkg), read.map { it.metadata.dataOrigin.packageName })
    deniedWithoutPermission("read everyone's sleep") { client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, month)) }
  }

  @Test
  fun j_descendingPagesAndReadByIdAndUpdate() = runBlocking {
    val ids = client.insertRecords((0L..2L).map { steps(base.plus(it, ChronoUnit.HOURS), 30, 100 * (it + 1)) }).recordIdsList
    val window = TimeRangeFilter.between(base, base.plus(3, ChronoUnit.HOURS))
    val first = client.readRecords(ReadRecordsRequest(StepsRecord::class, window, own, ascendingOrder = false, pageSize = 2))
    assertEquals(listOf(300L, 200L), first.records.map { it.count })
    val second = client.readRecords(ReadRecordsRequest(StepsRecord::class, window, own, ascendingOrder = false, pageSize = 2,
      pageToken = first.pageToken))
    assertEquals(listOf(100L), second.records.map { it.count })
    assertNull(second.pageToken)
    assertEquals(200L, client.readRecord(StepsRecord::class, ids[1]).record.count)
    client.updateRecords(listOf(StepsRecord(base.plus(1, ChronoUnit.HOURS), zone, base.plus(90, ChronoUnit.MINUTES), zone, 250,
      Metadata.manualEntryWithId(ids[1]))))
    assertEquals(250L, client.readRecord(StepsRecord::class, ids[1]).record.count)
    try {
      client.updateRecords(listOf(steps(base, 5, 1).let { StepsRecord(it.startTime, zone, it.endTime, zone, 1,
        Metadata.manualEntryWithId("00000000-0000-0000-0000-000000000001")) }))
      fail("updated a record that does not exist")
    } catch (e: IllegalArgumentException) {
      // as on a device: "No record found for the following input"
    }
  }

  /** An empty client record id is none: two such records are two records, not one replacing the other. */
  @Test
  fun k_anEmptyClientIdIsNone() = runBlocking {
    client.insertRecords(listOf(steps(base, 10, 10, clientId = "")))
    client.insertRecords(listOf(steps(base.plus(20, ChronoUnit.MINUTES), 10, 20, clientId = "")))
    assertEquals(listOf(10L, 20L), client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(base.minusSeconds(1)), own)).records.map { it.count })
  }

  /** Instant records (weight) aggregate the samples inside the window. */
  @Test
  fun l_weightAggregates() = runBlocking {
    shell("pm grant $pkg ${HealthPermission.getReadPermission(WeightRecord::class)}")
    shell("pm grant $pkg ${HealthPermission.getWritePermission(WeightRecord::class)}")
    client.deleteRecords(WeightRecord::class, TimeRangeFilter.after(Instant.EPOCH))
    client.insertRecords(listOf(70.0, 72.0, 74.0).mapIndexed { i, kg ->
      WeightRecord(base.plus(i.toLong(), ChronoUnit.HOURS), zone, Mass.kilograms(kg), Metadata.manualEntry()) })
    val r = client.aggregate(AggregateRequest(setOf(WeightRecord.WEIGHT_AVG, WeightRecord.WEIGHT_MIN, WeightRecord.WEIGHT_MAX),
      TimeRangeFilter.between(base, base.plus(2, ChronoUnit.HOURS)), own))
    assertEquals(71.0, r[WeightRecord.WEIGHT_AVG]!!.inKilograms, 0.001)
    assertEquals(70.0, r[WeightRecord.WEIGHT_MIN]!!.inKilograms, 0.001)
    assertEquals(72.0, r[WeightRecord.WEIGHT_MAX]!!.inKilograms, 0.001)
  }

  /** Local-time filters and groups by period read records by their own zone offset's local time. */
  @Test
  fun m_localTimeFiltersAndGroupsByPeriod() = runBlocking {
    val plus2 = ZoneOffset.ofHours(2)
    val day = LocalDateTime.ofInstant(base, plus2).toLocalDate().minusDays(1)
    // 23:30 local (+02:00) on [day], and 00:30 local the next day.
    val late = day.atTime(23, 30).toInstant(plus2)
    val early = day.plusDays(1).atTime(0, 30).toInstant(plus2)
    client.insertRecords(listOf(late to 80L, early to 90L).map { (t, bpm) ->
      HeartRateRecord(t, plus2, t.plusSeconds(60), plus2, listOf(HeartRateRecord.Sample(t, bpm)), Metadata.manualEntry()) })
    val local = TimeRangeFilter.between(day.atTime(23, 0), day.plusDays(1).atTime(0, 0))
    assertEquals(listOf(80L), client.readRecords(ReadRecordsRequest(HeartRateRecord::class, local, own)).records.map { it.samples[0].beatsPerMinute })
    val days = client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(HeartRateRecord.BPM_MAX),
      TimeRangeFilter.between(day.atStartOfDay(), day.plusDays(2).atStartOfDay()), Period.ofDays(1), own))
    assertEquals(listOf(80L, 90L), days.map { it.result[HeartRateRecord.BPM_MAX] })
    assertEquals(day.atStartOfDay(), days[0].startTime)
  }

  /** More groups than a device answers (5000) is refused, as there: "Number of buckets must not exceed 5000". */
  @Test
  fun n_tooManyGroupsAreRefused() = runBlocking {
    try {
      client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(HeartRateRecord.BPM_MAX),
        TimeRangeFilter.between(base.minus(7, ChronoUnit.DAYS), base), Duration.ofMinutes(1)))
      fail("10,080 groups answered")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message, e.message!!.contains("5000"))
    }
  }

  /** Changes come a page of 1000 at a time; a token the service does not know reads as expired. */
  @Test
  fun o_changesComeInPagesAndABadTokenIsExpired() = runBlocking {
    val token = client.getChangesToken(ChangesTokenRequest(setOf(StepsRecord::class), own))
    client.insertRecords((0L until 1005L).map { steps(base.plus(it, ChronoUnit.MINUTES), 1, it + 1) })
    val first = client.getChanges(token)
    assertEquals(1000, first.changes.size)
    assertTrue(first.hasMore)
    val second = client.getChanges(first.nextChangesToken)
    assertEquals(5, second.changes.size)
    assertFalse(second.hasMore)
    assertTrue(client.getChanges("not a token").changesTokenExpired)
  }

  /**
   * Session durations leave out what the framework's DURATION_EXCLUDE_TYPES name: a night's awake stage, a run's pause.
   * apksim only: an emulator counts sleep and exercise only from apps in its priority list (no value after pm grant).
   */
  @Test
  fun p_sessionDurationsLeaveOutAwakeAndPauses() = runBlocking {
    assumeTrue("an emulator counts only apps in its priority list, which pm grant does not add to", onApksim)
    for (p in listOf(SleepSessionRecord::class, ExerciseSessionRecord::class)) {
      shell("pm grant $pkg ${HealthPermission.getReadPermission(p)}")
      shell("pm grant $pkg ${HealthPermission.getWritePermission(p)}")
    }
    val night = base.minus(10, ChronoUnit.HOURS)
    client.insertRecords(listOf(sleep(night)))
    val all = TimeRangeFilter.between(night.minus(1, ChronoUnit.HOURS), night.plus(9, ChronoUnit.HOURS))
    assertEquals(Duration.ofMinutes(450), client.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), all, own))[SleepSessionRecord.SLEEP_DURATION_TOTAL])
    // The first three hours hold the half hour awake: 2.5 h.
    val part = TimeRangeFilter.between(night, night.plus(3, ChronoUnit.HOURS))
    assertEquals(Duration.ofMinutes(150), client.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), part, own))[SleepSessionRecord.SLEEP_DURATION_TOTAL])
    val run = base.minus(20, ChronoUnit.HOURS)
    client.insertRecords(listOf(ExerciseSessionRecord(startTime = run, startZoneOffset = zone, endTime = run.plus(60, ChronoUnit.MINUTES),
      endZoneOffset = zone, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, metadata = Metadata.manualEntry(),
      segments = listOf(ExerciseSegment(run, run.plus(20, ChronoUnit.MINUTES), ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING),
        ExerciseSegment(run.plus(20, ChronoUnit.MINUTES), run.plus(30, ChronoUnit.MINUTES), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE),
        ExerciseSegment(run.plus(30, ChronoUnit.MINUTES), run.plus(60, ChronoUnit.MINUTES), ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING)))))
    assertEquals(Duration.ofMinutes(50), client.aggregate(AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
      TimeRangeFilter.between(run, run.plus(2, ChronoUnit.HOURS)), own))[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL])
  }

  /** Eight hours from [from]: light two hours, awake half an hour, then deep. */
  private fun sleep(from: Instant) = SleepSessionRecord(startTime = from, startZoneOffset = zone, endTime = from.plus(8, ChronoUnit.HOURS),
    endZoneOffset = zone, metadata = Metadata.manualEntry(), stages = listOf(
      SleepSessionRecord.Stage(from, from.plus(2, ChronoUnit.HOURS), SleepSessionRecord.STAGE_TYPE_LIGHT),
      SleepSessionRecord.Stage(from.plus(2, ChronoUnit.HOURS), from.plus(150, ChronoUnit.MINUTES), SleepSessionRecord.STAGE_TYPE_AWAKE),
      SleepSessionRecord.Stage(from.plus(150, ChronoUnit.MINUTES), from.plus(8, ChronoUnit.HOURS), SleepSessionRecord.STAGE_TYPE_DEEP)))

  @Test
  fun h_theSeededWeek() = runBlocking {
    assumeTrue("needs --health week -e healthSeed week", InstrumentationRegistry.getArguments().getString("healthSeed") == "week")
    shell("pm grant $pkg ${HealthPermission.getReadPermission(SleepSessionRecord::class)}")
    val day = TimeRangeFilter.after(Instant.now().minus(1, ChronoUnit.DAYS))
    val week = TimeRangeFilter.after(Instant.now().minus(7, ChronoUnit.DAYS))
    val steps = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), week))[StepsRecord.COUNT_TOTAL]
    assertTrue("steps $steps", (steps ?: 0) > 20_000)
    val hr = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, day, ascendingOrder = false, pageSize = 1)).records
    assertEquals(1, hr.size)
    assertEquals("dev.apksim.health", hr[0].metadata.dataOrigin.packageName)
    assertTrue(hr[0].samples.all { it.beatsPerMinute in 40..190 })
    val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, week)).records
    assertTrue("sleep sessions ${sleep.size}", sleep.size >= 6)
    assertTrue(sleep[0].stages.isNotEmpty())
    // The app's own records sit beside the seeded ones; deleting its own leaves the seeds.
    client.insertRecords(listOf(steps(Instant.now().minus(10, ChronoUnit.MINUTES), 5, 77)))
    client.deleteRecords(StepsRecord::class, TimeRangeFilter.after(Instant.EPOCH))
    assertEquals(steps, client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), week))[StepsRecord.COUNT_TOTAL])
  }

  companion object {
    /** Types a test writes beyond steps and heart rate, cleaned up at setup where the app may write them. */
    val OWN_TYPES = listOf(SleepSessionRecord::class, ExerciseSessionRecord::class, WeightRecord::class).map {
      it to HealthPermission.getWritePermission(it) }
    /** apksim's test runs keep Robolectric's build fingerprint. */
    val onApksim = android.os.Build.FINGERPRINT.contains("robolectric")
    val GRANTED = setOf(
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.getWritePermission(StepsRecord::class),
      HealthPermission.getReadPermission(HeartRateRecord::class),
      HealthPermission.getWritePermission(HeartRateRecord::class),
    )
  }
}
