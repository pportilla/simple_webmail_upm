package io.github.pportilla.upmmail.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram

/**
 * [SqlDatabase] on top of the framework SQLite ("upmmail.db") with write-ahead logging and
 * foreign keys enabled. The file is opened lazily on first use, so construct it anywhere but use
 * it off the main thread.
 */
class AndroidSqlDatabase private constructor(
    private val helper: Helper,
) : SqlDatabase by FrameworkSql({ helper.writableDatabase }) {

    constructor(context: Context) : this(Helper(context.applicationContext))

    override fun close() {
        helper.close()
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, MailSchema.NAME, null, MailSchema.VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            MailSchema.create(FrameworkSql { db })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            MailSchema.migrate(FrameworkSql { db }, oldVersion, newVersion)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            MailSchema.recreate(FrameworkSql { db })
        }
    }
}

/**
 * Statement execution on a framework [SQLiteDatabase]. Arguments are bound with their real types
 * (rawQuery would turn every argument into a string).
 */
private class FrameworkSql(private val database: () -> SQLiteDatabase) : SqlDatabase {

    override fun execSQL(sql: String, args: List<Any?>) {
        val db = database()
        if (args.isEmpty()) {
            db.execSQL(sql)
        } else {
            db.compileStatement(sql).use { statement ->
                bindAll(statement, args)
                statement.execute()
            }
        }
    }

    override fun executeUpdateDelete(sql: String, args: List<Any?>): Int =
        database().compileStatement(sql).use { statement ->
            bindAll(statement, args)
            statement.executeUpdateDelete()
        }

    override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlRow) -> T): List<T> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            bindAll(query, args)
            SQLiteCursor(driver, editTable, query)
        }
        return database().rawQueryWithFactory(factory, sql, null, "").use { cursor ->
            val row = CursorRow(cursor)
            val result = ArrayList<T>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) result.add(mapper(row))
            result
        }
    }

    override fun insert(table: String, values: Map<String, Any?>, conflict: Conflict): Long {
        val columns = values.keys.toList()
        return database().compileStatement(insertSql(table, columns, conflict)).use { statement ->
            bindAll(statement, columns.map { values[it] })
            // -1 when OR IGNORE skipped the row (no change was made).
            statement.executeInsert()
        }
    }

    override fun update(table: String, values: Map<String, Any?>, where: String, whereArgs: List<Any?>): Int {
        if (values.isEmpty()) return 0
        val columns = values.keys.toList()
        return executeUpdateDelete(updateSql(table, columns, where), columns.map { values[it] } + whereArgs)
    }

    override fun delete(table: String, where: String, whereArgs: List<Any?>): Int =
        executeUpdateDelete("DELETE FROM $table WHERE $where", whereArgs)

    override fun <T> transaction(block: () -> T): T {
        val db = database()
        // IMMEDIATE instead of EXCLUSIVE: with WAL, readers on other connections keep going.
        // Framework transactions nest; only the outermost one commits.
        db.beginTransactionNonExclusive()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = Unit

    private fun bindAll(program: SQLiteProgram, args: List<Any?>) {
        args.forEachIndexed { index, raw ->
            val position = index + 1
            when (val value = normalizeArg(raw)) {
                null -> program.bindNull(position)
                is Long -> program.bindLong(position, value)
                is Double -> program.bindDouble(position, value)
                is String -> program.bindString(position, value)
                is ByteArray -> program.bindBlob(position, value)
                else -> error("Unsupported argument $value")
            }
        }
    }
}

private class CursorRow(private val cursor: Cursor) : SqlRow {
    private val indexes = HashMap<String, Int>()

    private fun index(column: String): Int =
        indexes.getOrPut(column) { cursor.getColumnIndexOrThrow(column) }

    override fun isNull(column: String): Boolean = cursor.isNull(index(column))

    override fun string(column: String): String? {
        val i = index(column)
        return if (cursor.isNull(i)) null else cursor.getString(i)
    }

    override fun long(column: String): Long? {
        val i = index(column)
        return if (cursor.isNull(i)) null else cursor.getLong(i)
    }

    override fun double(column: String): Double? {
        val i = index(column)
        return if (cursor.isNull(i)) null else cursor.getDouble(i)
    }

    override fun blob(column: String): ByteArray? {
        val i = index(column)
        return if (cursor.isNull(i)) null else cursor.getBlob(i)
    }
}
