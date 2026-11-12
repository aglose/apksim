package dev.apksim.composeapp.conformance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * androidx.work as documented, with the WorkManager the app's own startup initializer made (work-runtime is in the
 * app, as in an app that uses it), checked on an API 36 emulator first (docs/conformance.md).
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerConformanceTest {
  class Echo(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
      val text = inputData.getString("text") ?: return Result.failure()
      return Result.success(workDataOf("text" to "$text!"))
    }
  }

  class Fails(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = Result.failure(workDataOf("why" to "on purpose"))
  }

  class RetriesOnce(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = if (runAttemptCount == 0) Result.retry() else Result.success(workDataOf("attempt" to runAttemptCount))
  }

  /** Waits for [release] (or 10 s, or until stopped), so a test can see it RUNNING. */
  class Blocks(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
      started.countDown()
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while (!isStopped && System.nanoTime() < end && !release.await(50, TimeUnit.MILLISECONDS)) Unit
      return Result.success()
    }
  }

  /** Suspends for a minute unless cancelled. */
  class Sleeps(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
      started.countDown()
      try {
        delay(60_000)
      } catch (e: CancellationException) {
        cancelled.set(true)
        throw e
      }
      return Result.success()
    }
  }

  companion object {
    @Volatile var started = CountDownLatch(1)
    @Volatile var release = CountDownLatch(1)
    val cancelled = AtomicBoolean()
  }

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val work = WorkManager.getInstance(context)
  private val tag = "conformance-${UUID.randomUUID()}"

  @After
  fun cancel() {
    release.countDown()
    work.cancelAllWorkByTag(tag).result.get(5, TimeUnit.SECONDS)
  }

  private fun info(id: UUID): WorkInfo = work.getWorkInfoById(id).get(5, TimeUnit.SECONDS)!!

  private fun until(id: UUID, timeoutMs: Long = 10_000, done: (WorkInfo) -> Boolean): WorkInfo = runBlocking {
    withTimeout(timeoutMs) { work.getWorkInfoByIdFlow(id).first { it != null && done(it) }!! }
  }

  /** A one-time request runs and succeeds with its output. */
  @Test
  fun aOneTimeRequestSucceedsWithItsOutput() {
    val request = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "hi")).build()
    work.enqueue(request)
    val done = until(request.id) { it.state.isFinished }
    assertEquals(WorkInfo.State.SUCCEEDED, done.state)
    assertEquals("hi!", done.outputData.getString("text"))
  }

  /** In a chain, a worker's output is the next one's input. */
  @Test
  fun aChainPassesOutputOn() {
    val first = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "a")).build()
    val second = OneTimeWorkRequestBuilder<Echo>().addTag(tag).build()
    work.beginWith(first).then(second).enqueue()
    assertEquals("a!!", until(second.id) { it.state.isFinished }.outputData.getString("text"))
  }

  /** When a worker fails, the work that depends on it is marked FAILED too. */
  @Test
  fun aFailureFailsItsDependents() {
    val first = OneTimeWorkRequestBuilder<Fails>().addTag(tag).build()
    val second = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "never")).build()
    work.beginWith(first).then(second).enqueue()
    val failed = until(first.id) { it.state.isFinished }
    assertEquals(WorkInfo.State.FAILED, failed.state)
    assertEquals("on purpose", failed.outputData.getString("why"))
    assertEquals(WorkInfo.State.FAILED, until(second.id) { it.state.isFinished }.state)
  }

  /** Result.retry runs the work again after its backoff, with runAttemptCount counting up. */
  @Test
  fun aRetryRunsAgainAfterItsBackoff() {
    val request = OneTimeWorkRequestBuilder<RetriesOnce>().addTag(tag)
      .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
    work.enqueue(request)
    // The attempt count goes up as an attempt starts; after the first, the work waits ENQUEUED for its backoff.
    until(request.id) { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount >= 1 }
    val done = until(request.id, 40_000) { it.state.isFinished }
    assertEquals(WorkInfo.State.SUCCEEDED, done.state)
    assertEquals(1, done.outputData.getInt("attempt", -1))
  }

  /** Unique work with KEEP keeps the running request and drops the new one. */
  @Test
  fun uniqueWorkKeepKeepsTheFirst() {
    started = CountDownLatch(1)
    release = CountDownLatch(1)
    val name = "$tag-unique"
    val first = OneTimeWorkRequestBuilder<Blocks>().addTag(tag).build()
    work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, first)
    assertTrue("never started", started.await(10, TimeUnit.SECONDS))
    val second = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "x")).build()
    work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, second).result.get(5, TimeUnit.SECONDS)
    val infos = work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
    assertEquals(listOf(first.id), infos.map { it.id })
    assertEquals(WorkInfo.State.RUNNING, infos.single().state)
    release.countDown()
    assertEquals(WorkInfo.State.SUCCEEDED, until(first.id) { it.state.isFinished }.state)
  }

  /** Unique work with REPLACE cancels and deletes the running request and runs the new one. */
  @Test
  fun uniqueWorkReplaceCancelsTheFirst() {
    started = CountDownLatch(1)
    release = CountDownLatch(1)
    val name = "$tag-replace"
    val first = OneTimeWorkRequestBuilder<Blocks>().addTag(tag).build()
    work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, first)
    assertTrue("never started", started.await(10, TimeUnit.SECONDS))
    val second = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "y")).build()
    work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, second)
    assertEquals("y!", until(second.id) { it.state.isFinished }.outputData.getString("text"))
    // REPLACE cancels the old work and deletes it: its info is gone (or, until the delete lands, CANCELLED).
    val old = work.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS)
    assertTrue("old work: ${old?.state}", old == null || old.state == WorkInfo.State.CANCELLED)
    assertEquals(listOf(second.id), work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).map { it.id })
  }

  /** Cancelling a running CoroutineWorker cancels its coroutine, and the work ends CANCELLED. */
  @Test
  fun cancellingStopsACoroutineWorker() {
    started = CountDownLatch(1)
    cancelled.set(false)
    val request = OneTimeWorkRequestBuilder<Sleeps>().addTag(tag).build()
    work.enqueue(request)
    assertTrue("never started", started.await(10, TimeUnit.SECONDS))
    work.cancelWorkById(request.id).result.get(5, TimeUnit.SECONDS)
    assertEquals(WorkInfo.State.CANCELLED, until(request.id) { it.state.isFinished }.state)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!cancelled.get() && System.nanoTime() < deadline) Thread.sleep(10)
    assertTrue("the coroutine was not cancelled", cancelled.get())
  }

  /** Work whose constraint is not met stays ENQUEUED (an idle device: a test's screen is on). */
  @Test
  fun unmetConstraintsKeepWorkEnqueued() {
    val request = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "z"))
      .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build()).build()
    work.enqueue(request).result.get(5, TimeUnit.SECONDS)
    Thread.sleep(1_000)
    assertEquals(WorkInfo.State.ENQUEUED, info(request.id).state)
  }

  /** An initial delay holds the work ENQUEUED first; it then runs. */
  @Test
  fun anInitialDelayHoldsTheWorkFirst() {
    val request = OneTimeWorkRequestBuilder<Echo>().addTag(tag).setInputData(workDataOf("text" to "later"))
      .setInitialDelay(1, TimeUnit.SECONDS).build()
    work.enqueue(request).result.get(5, TimeUnit.SECONDS)
    assertEquals(WorkInfo.State.ENQUEUED, info(request.id).state)
    assertEquals("later!", until(request.id) { it.state.isFinished }.outputData.getString("text"))
  }
}
