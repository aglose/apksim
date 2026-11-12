package dev.apksim.composeapp.conformance

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.room as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class RoomConformanceTest {
  @Entity(tableName = "notes")
  data class Note(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String)

  @Dao
  interface Notes {
    @Insert suspend fun insert(note: Note): Long
    @Insert fun insertBlocking(note: Note): Long
    @Query("SELECT * FROM notes ORDER BY id") fun all(): Flow<List<Note>>
    @Query("SELECT * FROM notes ORDER BY id") suspend fun list(): List<Note>
    @Query("SELECT COUNT(*) FROM notes") fun countBlocking(): Int
  }

  @Database(entities = [Note::class], version = 1, exportSchema = false)
  abstract class NotesDb : RoomDatabase() {
    abstract fun notes(): Notes
  }

  /** Version 2 of a database whose version 1 had no `done` column. */
  @Entity(tableName = "tasks")
  data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    @ColumnInfo(defaultValue = "0") val done: Int = 0,
  )

  @Dao
  interface Tasks {
    @Query("SELECT * FROM tasks ORDER BY id") fun list(): List<Task>
  }

  @Database(entities = [Task::class], version = 2, exportSchema = false)
  abstract class TasksDb : RoomDatabase() {
    abstract fun tasks(): Tasks
  }

  /** Runs a blocking query in its onCreate, which a device runs on the main thread. */
  class QueriesInOnCreate : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      thrown = runCatching { db!!.notes().countBlocking() }.exceptionOrNull()
    }
  }

  companion object {
    @Volatile var db: NotesDb? = null
    @Volatile var thrown: Throwable? = null
  }

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val open = mutableListOf<RoomDatabase>()

  private fun memoryDb(): NotesDb = Room.inMemoryDatabaseBuilder(context, NotesDb::class.java).build().also { open += it }

  @After
  fun close() {
    open.forEach { it.close() }
  }

  /** A blocking DAO call from the test thread works: on a device that is not the main thread. */
  @Test
  fun aBlockingQueryFromTheTestBodyWorks() {
    val db = memoryDb()
    db.notes().insertBlocking(Note(text = "a"))
    assertEquals(1, db.notes().countBlocking())
  }

  /** A blocking DAO call on the main thread throws, as Room documents, unless allowMainThreadQueries is set. */
  @Test
  fun aBlockingQueryOnTheMainThreadThrows() {
    val db = memoryDb()
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      try {
        db.notes().countBlocking()
      } catch (e: IllegalStateException) {
        thrown = e
      }
    }
    assertTrue("no IllegalStateException", thrown?.message?.contains("main thread") == true)
  }

  /** A blocking query in a lifecycle callback (a fragment's onCreate) throws, though the test body launched it. */
  @Test
  fun aBlockingQueryInALifecycleCallbackThrows() {
    db = memoryDb()
    thrown = null
    launchFragmentInContainer<QueriesInOnCreate>().close()
    assertTrue("no IllegalStateException: $thrown", thrown is IllegalStateException && thrown!!.message!!.contains("main thread"))
  }

  /** A blocking query in Activity.runOnUiThread throws: the block runs on the main thread. */
  @Test
  fun aBlockingQueryInRunOnUiThreadThrows() {
    val db = memoryDb()
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      var activity: Activity? = null
      scenario.onActivity { activity = it }
      var caught: Throwable? = null
      val done = CountDownLatch(1)
      activity!!.runOnUiThread {
        caught = runCatching { db.notes().countBlocking() }.exceptionOrNull()
        done.countDown()
      }
      assertTrue(done.await(5, TimeUnit.SECONDS))
      assertTrue("no IllegalStateException: $caught", caught is IllegalStateException && caught!!.message!!.contains("main thread"))
    }
  }

  /** Suspend inserts and queries, from runBlocking in the test. */
  @Test
  fun suspendInsertsAndQueries() = runBlocking {
    val db = memoryDb()
    db.notes().insert(Note(text = "a"))
    db.notes().insert(Note(text = "b"))
    assertEquals(listOf("a", "b"), db.notes().list().map { it.text })
  }

  /** A Flow query emits the table again after an insert (the invalidation tracker). */
  @Test
  fun aFlowQueryEmitsAgainAfterAnInsert() = runBlocking {
    val db = memoryDb()
    val seen = Collections.synchronizedList(mutableListOf<List<String>>())
    val collector = launch(Dispatchers.Default) { db.notes().all().collect { rows -> seen += rows.map { it.text } } }
    withTimeout(5_000) { while (seen.isEmpty()) delay(10) }  // this collector's first emission, before the insert
    assertEquals(listOf(emptyList<String>()), seen.toList())
    db.notes().insert(Note(text = "x"))
    withTimeout(5_000) { while (seen.size < 2) delay(10) }
    collector.cancel()
    assertEquals(listOf("x"), seen.last())
  }

  /** An observer added to the invalidation tracker hears of a write to its table. */
  @Test
  fun anInvalidationObserverHearsAWrite() = runBlocking {
    val db = memoryDb()
    db.notes().list()  // opens the database
    val heard = CountDownLatch(1)
    db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("notes") {
      override fun onInvalidated(tables: Set<String>) {
        if ("notes" in tables) heard.countDown()
      }
    })
    db.notes().insert(Note(text = "y"))
    assertTrue("no invalidation", heard.await(5, TimeUnit.SECONDS))
  }

  /** withTransaction rolls everything back when its block throws. */
  @Test
  fun aTransactionRollsBackOnException() = runBlocking {
    val db = memoryDb()
    db.notes().insert(Note(text = "kept"))
    try {
      db.withTransaction {
        db.notes().insert(Note(text = "dropped"))
        throw IllegalArgumentException("roll back")
      }
    } catch (e: IllegalArgumentException) {
      assertEquals("roll back", e.message)
    }
    assertEquals(listOf("kept"), db.notes().list().map { it.text })
  }

  /** A file database keeps its rows after it is closed and opened again. */
  @Test
  fun aFileDatabaseKeepsItsRowsAcrossReopen() = runBlocking {
    val name = "conformance-notes.db"
    context.deleteDatabase(name)
    Room.databaseBuilder(context, NotesDb::class.java, name).build().apply {
      notes().insert(Note(text = "persisted"))
      close()
    }
    val again = Room.databaseBuilder(context, NotesDb::class.java, name).build().also { open += it }
    assertEquals(listOf("persisted"), again.notes().list().map { it.text })
  }

  private fun writeVersion1(name: String) {
    context.deleteDatabase(name)
    SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use {
      it.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL)")
      it.execSQL("INSERT INTO tasks (text) VALUES ('old')")
      it.version = 1
    }
  }

  /** A Migration runs when an older version is opened, and the rows survive it. */
  @Test
  fun aMigrationRunsWhenAnOlderVersionIsOpened() {
    val name = "conformance-tasks.db"
    writeVersion1(name)
    var migrated = 0
    val db = Room.databaseBuilder(context, TasksDb::class.java, name)
      .addMigrations(object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          migrated++
          db.execSQL("ALTER TABLE tasks ADD COLUMN done INTEGER NOT NULL DEFAULT 0")
        }
      })
      .build().also { open += it }
    val rows = db.tasks().list()
    assertEquals(1, migrated)
    assertEquals(listOf(Task(1, "old", 0)), rows)
  }

  /** Without a migration, fallbackToDestructiveMigration drops the old tables and starts empty. */
  @Test
  fun aDestructiveFallbackStartsEmpty() {
    val name = "conformance-tasks-destructive.db"
    writeVersion1(name)
    val db = Room.databaseBuilder(context, TasksDb::class.java, name)
      .fallbackToDestructiveMigration(dropAllTables = true)
      .build().also { open += it }
    assertEquals(emptyList<Task>(), db.tasks().list())
  }

  /** Without a migration or a fallback, opening an older version throws. */
  @Test
  fun aMissingMigrationThrows() {
    val name = "conformance-tasks-missing.db"
    writeVersion1(name)
    val db = Room.databaseBuilder(context, TasksDb::class.java, name).build().also { open += it }
    try {
      db.tasks().list()
      fail("opened without a migration")
    } catch (e: IllegalStateException) {
      assertTrue(e.message, e.message!!.contains("migration", ignoreCase = true))
    }
  }
}
