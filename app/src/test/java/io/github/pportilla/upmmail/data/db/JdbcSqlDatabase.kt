package io.github.pportilla.upmmail.data.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [SqlDatabase] for JVM unit tests on top of sqlite-jdbc. One connection guarded by a reentrant
 * lock, held for the whole duration of a transaction, mirrors SQLite's single-writer model.
 */
class JdbcSqlDatabase private constructor(private val connection: Connection) : SqlDatabase {

    private val lock = ReentrantLock()
    private var depth = 0

    override fun execSQL(sql: String, args: List<Any?>) = lock.withLock {
        prepare(sql, args).use { it.execute() }
        Unit
    }

    override fun executeUpdateDelete(sql: String, args: List<Any?>): Int = lock.withLock {
        prepare(sql, args).use { it.executeUpdate() }
    }

    override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlRow) -> T): List<T> = lock.withLock {
        prepare(sql, args).use { statement ->
            statement.executeQuery().use { resultSet ->
                val row = ResultSetRow(resultSet)
                val result = ArrayList<T>()
                while (resultSet.next()) result.add(mapper(row))
                result
            }
        }
    }

    override fun insert(table: String, values: Map<String, Any?>, conflict: Conflict): Long = lock.withLock {
        val columns = values.keys.toList()
        val changed = prepare(insertSql(table, columns, conflict), columns.map { values[it] }).use { it.executeUpdate() }
        if (changed == 0) return@withLock -1L
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
    }

    override fun update(table: String, values: Map<String, Any?>, where: String, whereArgs: List<Any?>): Int {
        if (values.isEmpty()) return 0
        val columns = values.keys.toList()
        return executeUpdateDelete(updateSql(table, columns, where), columns.map { values[it] } + whereArgs)
    }

    override fun delete(table: String, where: String, whereArgs: List<Any?>): Int =
        executeUpdateDelete("DELETE FROM $table WHERE $where", whereArgs)

    override fun <T> transaction(block: () -> T): T = lock.withLock {
        val outermost = depth == 0
        if (outermost) connection.autoCommit = false
        depth++
        try {
            val result = block()
            depth--
            if (outermost) connection.commit()
            result
        } catch (t: Throwable) {
            depth--
            if (outermost) connection.rollback()
            throw t
        } finally {
            if (outermost) connection.autoCommit = true
        }
    }

    override fun close() = lock.withLock { connection.close() }

    private fun prepare(sql: String, args: List<Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { index, raw ->
            val position = index + 1
            when (val value = normalizeArg(raw)) {
                null -> statement.setNull(position, Types.NULL)
                is Long -> statement.setLong(position, value)
                is Double -> statement.setDouble(position, value)
                is String -> statement.setString(position, value)
                is ByteArray -> statement.setBytes(position, value)
                else -> error("Unsupported argument $value")
            }
        }
        return statement
    }

    private class ResultSetRow(private val rs: ResultSet) : SqlRow {
        override fun isNull(column: String): Boolean = rs.getObject(column) == null

        override fun string(column: String): String? = rs.getObject(column)?.let { if (it is ByteArray) String(it) else it.toString() }

        override fun long(column: String): Long? = when (val value = rs.getObject(column)) {
            null -> null
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

        override fun double(column: String): Double? = when (val value = rs.getObject(column)) {
            null -> null
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }

        override fun blob(column: String): ByteArray? = rs.getBytes(column)
    }

    companion object {
        /** A fresh in-memory database with the app schema. */
        fun inMemory(): JdbcSqlDatabase {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            val db = JdbcSqlDatabase(connection)
            db.transaction { MailSchema.create(db) }
            return db
        }
    }
}
