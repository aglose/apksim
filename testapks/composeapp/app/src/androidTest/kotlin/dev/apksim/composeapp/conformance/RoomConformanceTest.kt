package dev.apksim.composeapp.conformance

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val second = async(Dispatchers.Default) { db.notes().all().first { it.isNotEmpty() } }
    db.notes().all().first()  // the table has been read once
    db.notes().insert(Note(text = "x"))
    assertEquals(listOf("x"), withTimeout(5_000) { second.await() }.map { it.text })
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
