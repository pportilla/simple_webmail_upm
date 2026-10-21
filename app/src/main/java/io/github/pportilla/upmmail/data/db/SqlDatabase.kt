package io.github.pportilla.upmmail.data.db

/** How [SqlDatabase.insert] resolves a UNIQUE / PRIMARY KEY conflict. */
enum class Conflict(val sql: String) {
    ABORT("ABORT"),
    IGNORE("IGNORE"),
    REPLACE("REPLACE"),
}

/**
 * Read access to the current row of a query result, by column name. Only valid inside the mapper
 * passed to [SqlDatabase.query]; rows are mapped eagerly and the cursor is closed afterwards.
 */
interface SqlRow {
    fun isNull(column: String): Boolean

    fun string(column: String): String?

    fun long(column: String): Long?

    fun double(column: String): Double?

    fun blob(column: String): ByteArray?
}

fun SqlRow.int(column: String): Int? = long(column)?.toInt()

fun SqlRow.stringOrEmpty(column: String): String = string(column) ?: ""

fun SqlRow.longOr(column: String, default: Long = 0): Long = long(column) ?: default

fun SqlRow.intOr(column: String, default: Int = 0): Int = long(column)?.toInt() ?: default

fun SqlRow.bool(column: String): Boolean = (long(column) ?: 0L) != 0L

/**
 * The tiny subset of SQLite the data layer needs. It keeps the DAOs free of Android types, so
 * they run in plain JVM tests on top of a JDBC driver.
 *
 * Bind arguments may be null, String, Long, Int, Short, Byte, Boolean (stored as 1/0), Double,
 * Float, ByteArray or an Enum (stored by name).
 *
 * Implementations must support nested [transaction] calls (only the outermost one commits) and
 * must be safe to use from several threads.
 */
interface SqlDatabase {
    /** Runs a statement that returns no rows (DDL, PRAGMA, INSERT ... SELECT ...). */
    fun execSQL(sql: String, args: List<Any?> = emptyList())

    /** Runs an UPDATE or DELETE statement and returns the number of affected rows. */
    fun executeUpdateDelete(sql: String, args: List<Any?> = emptyList()): Int

    /** Runs a query and maps every row through [mapper] before returning. */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), mapper: (SqlRow) -> T): List<T>

    /** Inserts a row and returns its rowid, or -1 when [Conflict.IGNORE] skipped it. */
    fun insert(table: String, values: Map<String, Any?>, conflict: Conflict = Conflict.ABORT): Long

    /** Updates the rows matching [where] and returns how many were changed. */
    fun update(table: String, values: Map<String, Any?>, where: String, whereArgs: List<Any?> = emptyList()): Int

    /** Deletes the rows matching [where] and returns how many were deleted. */
    fun delete(table: String, where: String, whereArgs: List<Any?> = emptyList()): Int

    /** Runs [block] in a transaction: committed if it returns, rolled back if it throws. */
    fun <T> transaction(block: () -> T): T

    fun close()
}

/** First column of the first row as a Long, or null when there is no row or it is NULL. */
fun SqlDatabase.queryLong(sql: String, args: List<Any?> = emptyList()): Long? =
    query(sql, args) { row -> row.long(FIRST_COLUMN_ALIAS) }.firstOrNull()

/**
 * Column alias that [queryLong] reads. Queries passed to it must name their single result column
 * `v`, e.g. `SELECT COUNT(*) AS v FROM ...`.
 */
const val FIRST_COLUMN_ALIAS = "v"

/** Builds the INSERT statement shared by every [SqlDatabase] implementation. */
internal fun insertSql(table: String, columns: Collection<String>, conflict: Conflict): String =
    buildString {
        append("INSERT OR ").append(conflict.sql).append(" INTO ").append(table).append(" (")
        columns.joinTo(this, ", ")
        append(") VALUES (")
        columns.joinTo(this, ", ") { "?" }
        append(')')
    }

/** Builds the UPDATE statement shared by every [SqlDatabase] implementation. */
internal fun updateSql(table: String, columns: Collection<String>, where: String): String =
    buildString {
        append("UPDATE ").append(table).append(" SET ")
        columns.joinTo(this, ", ") { "$it = ?" }
        append(" WHERE ").append(where)
    }

/** Normalises a bind argument to one of null, Long, Double, String or ByteArray. */
internal fun normalizeArg(value: Any?): Any? = when (value) {
    null -> null
    is String -> value
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Boolean -> if (value) 1L else 0L
    is Double -> value
    is Float -> value.toDouble()
    is ByteArray -> value
    is Enum<*> -> value.name
    is Char -> value.toString()
    else -> throw IllegalArgumentException("Unsupported SQL argument type: ${value::class.java.name}")
}
