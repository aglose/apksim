package dev.apksim.composeapp.conformance

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

private val Context.conformanceStore by preferencesDataStore(name = "conformance-delegate")

/** androidx.datastore (Preferences) as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class DataStoreConformanceTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val count = intPreferencesKey("count")
  private val name = stringPreferencesKey("name")
  private val file = File(context.filesDir, "datastore/conformance-${UUID.randomUUID()}.preferences_pb")
  private val scopes = mutableListOf<CoroutineScope>()

  private fun store(
    corruption: ReplaceFileCorruptionHandler<Preferences>? = null,
  ): Pair<DataStore<Preferences>, CoroutineScope> {
    val scope = CoroutineScope(Dispatchers.IO + Job()).also { scopes += it }
    return PreferenceDataStoreFactory.create(corruptionHandler = corruption, scope = scope) { file } to scope
  }

  @After
  fun cancel() {
    runBlocking { scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }
    file.delete()
  }

  /** What edit writes, data reads back. */
  @Test
  fun anEditIsReadBack() = runBlocking {
    val (store, _) = store()
    store.edit { it[count] = 3; it[name] = "three" }
    val prefs = store.data.first()
    assertEquals(3, prefs[count])
    assertEquals("three", prefs[name])
  }

  /** data emits the current value, then each edit's. */
  @Test
  fun dataEmitsEachEdit() = runBlocking {
    val (store, _) = store()
    store.edit { it[count] = 0 }
    val values = Collections.synchronizedList(mutableListOf<Int?>())
    val collector = launch(Dispatchers.Default) { store.data.collect { values += it[count] } }
    suspend fun until(v: Int) = withTimeout(5_000) { while (values.lastOrNull() != v) delay(10) }
    until(0)
    store.edit { it[count] = 1 }
    until(1)
    store.edit { it[count] = 2 }
    until(2)
    collector.cancel()
    assertEquals(listOf(0, 1, 2), values.toList())
  }

  /** A new DataStore on the same file, once the first one's scope has ended, reads what the first wrote. */
  @Test
  fun aNewStoreOnTheFileReadsWhatTheLastWrote() = runBlocking {
    val (first, scope) = store()
    first.edit { it[count] = 7 }
    scope.coroutineContext[Job]!!.cancelAndJoin()
    val (second, _) = store()
    assertEquals(7, second.data.first()[count])
  }

  /** Two DataStores active on one file at once: the second's read fails, as documented. */
  @Test
  fun twoActiveStoresOnOneFileFail() = runBlocking {
    val (first, _) = store()
    first.edit { it[count] = 1 }
    val (second, _) = store()
    try {
      second.data.first()
      fail("a second active DataStore read the file")
    } catch (e: IllegalStateException) {
      assertTrue(e.message, e.message!!.contains("multiple DataStores"))
    }
  }

  /** An exception thrown in edit leaves the stored value as it was, and reaches the caller. */
  @Test
  fun aFailedEditLeavesTheOldValue() = runBlocking {
    val (store, _) = store()
    store.edit { it[count] = 5 }
    try {
      store.edit {
        it[count] = 6
        throw IllegalArgumentException("no")
      }
      fail("the edit's exception was lost")
    } catch (e: IllegalArgumentException) {
      assertEquals("no", e.message)
    }
    assertEquals(5, store.data.first()[count])
  }

  /** A corrupt file without a handler: reading throws CorruptionException. */
  @Test
  fun aCorruptFileThrowsWithoutAHandler() = runBlocking {
    file.parentFile!!.mkdirs()
    file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
    val (store, _) = store()
    try {
      store.data.first()
      fail("read a corrupt file")
    } catch (e: CorruptionException) {
      // as documented
    }
  }

  /** A corrupt file with ReplaceFileCorruptionHandler: the handler's value replaces it. */
  @Test
  fun aCorruptFileIsReplacedByTheHandler() = runBlocking {
    file.parentFile!!.mkdirs()
    file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
    val (store, _) = store(ReplaceFileCorruptionHandler { preferencesOf(count to 42) })
    assertEquals(42, store.data.first()[count])
  }

  /**
   * The preferencesDataStore delegate is one DataStore per process: the application's context and an activity's give
   * the same instance, and what one writes the other reads.
   */
  @Test
  fun theDelegateIsOneStorePerProcess() = runBlocking {
    val value = "written ${UUID.randomUUID()}"
    context.conformanceStore.edit { it[name] = value }
    val app = context.applicationContext
    assertTrue("a second DataStore for the same name", app.conformanceStore === context.conformanceStore)
    assertEquals(value, app.conformanceStore.data.first()[name])
  }
}
