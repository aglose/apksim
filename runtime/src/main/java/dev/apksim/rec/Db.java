package dev.apksim.rec;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The flight recorder's db channel. The translator sends app calls to the framework's SQLiteDatabase and
 * SQLiteStatement here (dex.MethodTranslator.DB_HOOKS): with the channel off each is the call itself; with it on, the
 * call is timed and kept as one record (database file, thread, operation, SQL, how long, what it returned or threw).
 * Room's framework driver, SQLDelight's Android driver and plain SQLiteOpenHelper code all go through these. A
 * bundled SQLite (androidx.sqlite's BundledSQLiteDriver, its own native library) does not, and is not recorded.
 *
 * <p>Values stay out unless asked for ({@link Rec#dbValues}, `rec on db+values`): an app's database holds its user's
 * data. Without them a record has the SQL with its placeholders, the number of bound arguments and the column names.
 */
public final class Db {
  private Db() {}

  private interface Call<R> {
    R run();
  }

  private static <R> R rec(SQLiteDatabase db, String op, String sql, Object args, Call<R> c) {
    long t0 = System.nanoTime();
    try {
      R r = c.run();
      log(db, op, sql, args, t0, r, null);
      return r;
    } catch (RuntimeException | Error e) {
      log(db, op, sql, args, t0, null, e);
      throw e;
    }
  }

  private static void log(SQLiteDatabase db, String op, String sql, Object args, long t0, Object result, Throwable error) {
    long us = (System.nanoTime() - t0) / 1000;
    long wall = System.currentTimeMillis();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("w", wall);
    m.put("db", name(db));
    m.put("thread", Thread.currentThread().getName());
    m.put("op", op);
    if (sql != null) m.put("sql", Rec.dbValues ? sql : literalsOut(sql));
    if (args != null) m.put("args", args);
    m.put("us", us);
    if (error != null) m.put("error", error.toString());
    else if (result instanceof Cursor) m.put("result", "cursor");
    else if (result instanceof Number && !op.equals("simpleQueryForLong")) m.put("result", result);
    else if (result != null && Rec.dbValues) m.put("result", String.valueOf(result));
    long size = 64 + (sql == null ? 0 : 2L * sql.length()) + (args == null ? 0 : 2L * args.toString().length());
    for (String k : new String[] {"result", "error"}) if (m.get(k) instanceof String v) size += 2L * v.length();  // a long simpleQueryForString
    Rec.DB.add(wall, size, m);
  }

  /**
   * The SQL with its literals left out, for the db channel without values: an app can write a value into the SQL
   * itself (`INSERT ... VALUES ('token')`, `WHERE pin = 1234`). String literals become '…', blobs X'…' and numbers …;
   * identifiers (bare, "quoted", `quoted`, [quoted]), keywords and placeholders stay. Only the record changes, never
   * the SQL that runs.
   */
  static String literalsOut(String sql) {
    StringBuilder b = new StringBuilder(sql.length());
    int n = sql.length();
    for (int i = 0; i < n; ) {
      char c = sql.charAt(i);
      if (c == '\'' || ((c == 'x' || c == 'X') && i + 1 < n && sql.charAt(i + 1) == '\'' && (i == 0 || !word(sql.charAt(i - 1))))) {
        boolean blob = c != '\'';
        int j = i + (blob ? 2 : 1);
        while (j < n) {  // '' inside a literal is a quote
          if (sql.charAt(j) == '\'') {
            if (j + 1 < n && sql.charAt(j + 1) == '\'') j += 2;
            else break;
          } else {
            j++;
          }
        }
        b.append(blob ? "X'…'" : "'…'");
        i = Math.min(n, j + 1);
      } else if (c == '"' || c == '`' || c == '[') {
        char close = c == '[' ? ']' : c;
        int j = sql.indexOf(close, i + 1);
        j = j < 0 ? n : j + 1;
        b.append(sql, i, j);
        i = j;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        int j = sql.indexOf('\n', i);
        j = j < 0 ? n : j;
        b.append("--…");  // a comment can hold anything too
        i = j;
      } else if ((Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))))
          && (i == 0 || !word(sql.charAt(i - 1)))) {
        int j = i;
        while (j < n && (word(sql.charAt(j)) || sql.charAt(j) == '.'
            || ((sql.charAt(j) == '+' || sql.charAt(j) == '-') && (sql.charAt(j - 1) == 'e' || sql.charAt(j - 1) == 'E')))) {
          j++;
        }
        b.append('…');
        i = j;
      } else if (c == '?') {
        int j = i + 1;
        while (j < n && Character.isDigit(sql.charAt(j))) j++;
        b.append(sql, i, j);  // a placeholder, numbered or not (?1)
        i = j;
      } else if (word(c)) {
        int j = i;
        while (j < n && word(sql.charAt(j))) j++;
        b.append(sql, i, j);  // a name or keyword, digits in it included (t2, ?1)
        i = j;
      } else {
        b.append(c);
        i++;
      }
    }
    return b.toString();
  }

  private static boolean word(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  private static String name(SQLiteDatabase db) {
    if (db == null) return null;
    try {
      String p = db.getPath();
      if (p == null) return null;
      int slash = p.lastIndexOf('/');
      return slash < 0 ? p : p.substring(slash + 1);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Bound arguments: their values when asked for, else how many. */
  private static Object args(Object[] a) {
    if (a == null) return null;
    if (!Rec.dbValues) return a.length;
    List<Object> out = new ArrayList<>(a.length);
    for (Object o : a) out.add(o instanceof byte[] b ? "<blob " + b.length + " bytes>" : o == null ? null : String.valueOf(o));
    return out;
  }

  /** ContentValues: the column names, or the values too when asked for. */
  private static Object values(ContentValues v) {
    if (v == null) return null;
    if (!Rec.dbValues) return new ArrayList<>(v.keySet());
    Map<String, Object> out = new LinkedHashMap<>();
    for (String k : v.keySet()) {
      Object o = v.get(k);
      out.put(k, o instanceof byte[] b ? "<blob " + b.length + " bytes>" : o == null ? null : String.valueOf(o));
    }
    return out;
  }

  /** "INSERT INTO t (a, b)" for a ContentValues insert (or REPLACE). */
  private static String insertSql(String verb, String table, ContentValues v) {
    return verb + " INTO " + table + (v == null || v.size() == 0 ? " DEFAULT VALUES" : " (" + String.join(", ", v.keySet()) + ")");
  }

  /** "UPDATE t SET a, b WHERE ..." for a ContentValues update. */
  private static String updateSql(String table, ContentValues v, String where) {
    return "UPDATE " + table + (v == null || v.size() == 0 ? "" : " SET " + String.join(", ", v.keySet())) + (where == null ? "" : " WHERE " + where);
  }

  /** ContentValues' values, kept only with Rec.dbValues (the column names are in the SQL). */
  private static Object valuesIfKept(ContentValues v) {
    return Rec.dbValues ? values(v) : null;
  }

  private static Object where(String[] whereArgs, ContentValues v) {
    Object a = args(whereArgs);
    Object c = valuesIfKept(v);
    if (a == null) return c;
    if (c == null) return a;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("values", c);
    m.put("whereArgs", a);
    return m;
  }

  private static String select(boolean distinct, String table, String[] cols, String where, String groupBy, String having,
      String orderBy, String limit) {
    try {
      return SQLiteQueryBuilder.buildQueryString(distinct, table, cols, where, groupBy, having, orderBy, limit);
    } catch (RuntimeException e) {
      return "SELECT ... FROM " + table + (where == null ? "" : " WHERE " + where);
    }
  }

  // ------------------------------------------------------------------ SQLiteDatabase

  public static void execSQL(SQLiteDatabase db, String sql) {
    if (!Rec.db) {
      db.execSQL(sql);
      return;
    }
    rec(db, "exec", sql, null, () -> {
      db.execSQL(sql);
      return null;
    });
  }

  public static void execSQL(SQLiteDatabase db, String sql, Object[] bindArgs) {
    if (!Rec.db) {
      db.execSQL(sql, bindArgs);
      return;
    }
    rec(db, "exec", sql, args(bindArgs), () -> {
      db.execSQL(sql, bindArgs);
      return null;
    });
  }

  public static Cursor rawQuery(SQLiteDatabase db, String sql, String[] selectionArgs) {
    if (!Rec.db) return db.rawQuery(sql, selectionArgs);
    return rec(db, "query", sql, args(selectionArgs), () -> db.rawQuery(sql, selectionArgs));
  }

  public static Cursor rawQuery(SQLiteDatabase db, String sql, String[] selectionArgs, CancellationSignal cancel) {
    if (!Rec.db) return db.rawQuery(sql, selectionArgs, cancel);
    return rec(db, "query", sql, args(selectionArgs), () -> db.rawQuery(sql, selectionArgs, cancel));
  }

  public static Cursor rawQueryWithFactory(SQLiteDatabase db, SQLiteDatabase.CursorFactory f, String sql, String[] selectionArgs,
      String editTable) {
    if (!Rec.db) return db.rawQueryWithFactory(f, sql, selectionArgs, editTable);
    return rec(db, "query", sql, args(selectionArgs), () -> db.rawQueryWithFactory(f, sql, selectionArgs, editTable));
  }

  public static Cursor rawQueryWithFactory(SQLiteDatabase db, SQLiteDatabase.CursorFactory f, String sql, String[] selectionArgs,
      String editTable, CancellationSignal cancel) {
    if (!Rec.db) return db.rawQueryWithFactory(f, sql, selectionArgs, editTable, cancel);
    return rec(db, "query", sql, args(selectionArgs), () -> db.rawQueryWithFactory(f, sql, selectionArgs, editTable, cancel));
  }

  public static Cursor query(SQLiteDatabase db, String table, String[] cols, String where, String[] whereArgs, String groupBy,
      String having, String orderBy) {
    if (!Rec.db) return db.query(table, cols, where, whereArgs, groupBy, having, orderBy);
    return rec(db, "query", select(false, table, cols, where, groupBy, having, orderBy, null), args(whereArgs),
        () -> db.query(table, cols, where, whereArgs, groupBy, having, orderBy));
  }

  public static Cursor query(SQLiteDatabase db, String table, String[] cols, String where, String[] whereArgs, String groupBy,
      String having, String orderBy, String limit) {
    if (!Rec.db) return db.query(table, cols, where, whereArgs, groupBy, having, orderBy, limit);
    return rec(db, "query", select(false, table, cols, where, groupBy, having, orderBy, limit), args(whereArgs),
        () -> db.query(table, cols, where, whereArgs, groupBy, having, orderBy, limit));
  }

  public static Cursor query(SQLiteDatabase db, boolean distinct, String table, String[] cols, String where, String[] whereArgs,
      String groupBy, String having, String orderBy, String limit) {
    if (!Rec.db) return db.query(distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit);
    return rec(db, "query", select(distinct, table, cols, where, groupBy, having, orderBy, limit), args(whereArgs),
        () -> db.query(distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit));
  }

  public static Cursor query(SQLiteDatabase db, boolean distinct, String table, String[] cols, String where, String[] whereArgs,
      String groupBy, String having, String orderBy, String limit, CancellationSignal cancel) {
    if (!Rec.db) return db.query(distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit, cancel);
    return rec(db, "query", select(distinct, table, cols, where, groupBy, having, orderBy, limit), args(whereArgs),
        () -> db.query(distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit, cancel));
  }

  public static Cursor queryWithFactory(SQLiteDatabase db, SQLiteDatabase.CursorFactory f, boolean distinct, String table,
      String[] cols, String where, String[] whereArgs, String groupBy, String having, String orderBy, String limit) {
    if (!Rec.db) return db.queryWithFactory(f, distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit);
    return rec(db, "query", select(distinct, table, cols, where, groupBy, having, orderBy, limit), args(whereArgs),
        () -> db.queryWithFactory(f, distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit));
  }

  public static Cursor queryWithFactory(SQLiteDatabase db, SQLiteDatabase.CursorFactory f, boolean distinct, String table,
      String[] cols, String where, String[] whereArgs, String groupBy, String having, String orderBy, String limit,
      CancellationSignal cancel) {
    if (!Rec.db) return db.queryWithFactory(f, distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit, cancel);
    return rec(db, "query", select(distinct, table, cols, where, groupBy, having, orderBy, limit), args(whereArgs),
        () -> db.queryWithFactory(f, distinct, table, cols, where, whereArgs, groupBy, having, orderBy, limit, cancel));
  }

  public static long insert(SQLiteDatabase db, String table, String nullColumnHack, ContentValues v) {
    if (!Rec.db) return db.insert(table, nullColumnHack, v);
    return rec(db, "insert", insertSql("INSERT", table, v), valuesIfKept(v), () -> db.insert(table, nullColumnHack, v));
  }

  public static long insertOrThrow(SQLiteDatabase db, String table, String nullColumnHack, ContentValues v) {
    if (!Rec.db) return db.insertOrThrow(table, nullColumnHack, v);
    return rec(db, "insert", insertSql("INSERT", table, v), valuesIfKept(v), () -> db.insertOrThrow(table, nullColumnHack, v));
  }

  public static long replace(SQLiteDatabase db, String table, String nullColumnHack, ContentValues v) {
    if (!Rec.db) return db.replace(table, nullColumnHack, v);
    return rec(db, "replace", insertSql("REPLACE", table, v), valuesIfKept(v), () -> db.replace(table, nullColumnHack, v));
  }

  public static long replaceOrThrow(SQLiteDatabase db, String table, String nullColumnHack, ContentValues v) {
    if (!Rec.db) return db.replaceOrThrow(table, nullColumnHack, v);
    return rec(db, "replace", insertSql("REPLACE", table, v), valuesIfKept(v), () -> db.replaceOrThrow(table, nullColumnHack, v));
  }

  public static long insertWithOnConflict(SQLiteDatabase db, String table, String nullColumnHack, ContentValues v, int conflict) {
    if (!Rec.db) return db.insertWithOnConflict(table, nullColumnHack, v, conflict);
    return rec(db, "insert", insertSql("INSERT", table, v), valuesIfKept(v), () -> db.insertWithOnConflict(table, nullColumnHack, v, conflict));
  }

  public static int delete(SQLiteDatabase db, String table, String where, String[] whereArgs) {
    if (!Rec.db) return db.delete(table, where, whereArgs);
    return rec(db, "delete", "DELETE FROM " + table + (where == null ? "" : " WHERE " + where), args(whereArgs),
        () -> db.delete(table, where, whereArgs));
  }

  public static int update(SQLiteDatabase db, String table, ContentValues v, String where, String[] whereArgs) {
    if (!Rec.db) return db.update(table, v, where, whereArgs);
    return rec(db, "update", updateSql(table, v, where), where(whereArgs, v),
        () -> db.update(table, v, where, whereArgs));
  }

  public static int updateWithOnConflict(SQLiteDatabase db, String table, ContentValues v, String where, String[] whereArgs,
      int conflict) {
    if (!Rec.db) return db.updateWithOnConflict(table, v, where, whereArgs, conflict);
    return rec(db, "update", updateSql(table, v, where), where(whereArgs, v),
        () -> db.updateWithOnConflict(table, v, where, whereArgs, conflict));
  }

  // Timed from the call: taking a transaction can wait for another connection's lock, or fail.
  public static void beginTransaction(SQLiteDatabase db) {
    if (!Rec.db) {
      db.beginTransaction();
      return;
    }
    rec(db, "begin", null, null, () -> {
      db.beginTransaction();
      return null;
    });
  }

  public static void beginTransactionNonExclusive(SQLiteDatabase db) {
    if (!Rec.db) {
      db.beginTransactionNonExclusive();
      return;
    }
    rec(db, "begin", null, null, () -> {
      db.beginTransactionNonExclusive();
      return null;
    });
  }

  public static void setTransactionSuccessful(SQLiteDatabase db) {
    db.setTransactionSuccessful();
    if (Rec.db) log(db, "success", null, null, System.nanoTime(), null, null);
  }

  public static void endTransaction(SQLiteDatabase db) {
    if (!Rec.db) {
      db.endTransaction();
      return;
    }
    rec(db, "end", null, null, () -> {
      db.endTransaction();
      return null;
    });
  }

  // ------------------------------------------------------------------ SQLiteStatement

  // SQLiteProgram's database and bound arguments (package-private there): what a statement's record names.
  private static final java.lang.reflect.Method PROGRAM_DB = programMethod("getDatabase");
  private static final java.lang.reflect.Method PROGRAM_ARGS = programMethod("getBindArgs");

  private static java.lang.reflect.Method programMethod(String name) {
    try {
      java.lang.reflect.Method m = android.database.sqlite.SQLiteProgram.class.getDeclaredMethod(name);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      return null;
    }
  }

  private static SQLiteDatabase db(SQLiteStatement s) {
    try {
      return PROGRAM_DB == null ? null : (SQLiteDatabase) PROGRAM_DB.invoke(s);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /** The arguments bound now: how many, or their values with db+values (as the database hooks). */
  private static Object bound(SQLiteStatement s) {
    try {
      Object[] a = PROGRAM_ARGS == null ? null : (Object[]) PROGRAM_ARGS.invoke(s);
      return a == null ? null : args(a);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /** The statement's SQL: SQLiteStatement.toString is "SQLiteProgram: " and its SQL (getSql is hidden). */
  private static String sql(SQLiteStatement s) {
    String t = s.toString();
    return t.startsWith("SQLiteProgram: ") ? t.substring(15) : t;
  }

  public static void execute(SQLiteStatement s) {
    if (!Rec.db) {
      s.execute();
      return;
    }
    rec(db(s), "exec", sql(s), bound(s), () -> {
      s.execute();
      return null;
    });
  }

  public static int executeUpdateDelete(SQLiteStatement s) {
    if (!Rec.db) return s.executeUpdateDelete();
    return rec(db(s), "updateDelete", sql(s), bound(s), s::executeUpdateDelete);
  }

  public static long executeInsert(SQLiteStatement s) {
    if (!Rec.db) return s.executeInsert();
    return rec(db(s), "insert", sql(s), bound(s), s::executeInsert);
  }

  public static long simpleQueryForLong(SQLiteStatement s) {
    if (!Rec.db) return s.simpleQueryForLong();
    return rec(db(s), "simpleQueryForLong", sql(s), bound(s), s::simpleQueryForLong);
  }

  public static String simpleQueryForString(SQLiteStatement s) {
    if (!Rec.db) return s.simpleQueryForString();
    return rec(db(s), "simpleQueryForString", sql(s), bound(s), s::simpleQueryForString);
  }
}
