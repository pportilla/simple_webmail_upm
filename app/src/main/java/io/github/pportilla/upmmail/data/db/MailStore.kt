package io.github.pportilla.upmmail.data.db

import kotlinx.coroutines.flow.Flow

/**
 * Entry point to the cache: the DAOs plus transaction handling. Writes record the tables they
 * touch with [changed]; observers are notified once the outermost transaction has committed.
 *
 * Every DAO function blocks; call them from an IO dispatcher.
 */
class MailStore(
    val sql: SqlDatabase,
    val observer: TableObserver = TableObserver(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pendingTables = ThreadLocal<MutableSet<String>?>()

    val accounts = AccountDao(this)
    val folders = FolderDao(this)
    val messages = MessageDao(this)
    val contacts = ContactDao(this)
    val pendingOps = PendingOpDao(this)
    val outbox = OutboxDao(this)
    val drafts = DraftDao(this)
    val recentSearches = RecentSearchDao(this)
    val headerIndex = HeaderIndexDao(this)

    fun now(): Long = clock()

    /** Runs [block] in a transaction; observers hear about the changed tables after commit. */
    fun <T> tx(block: () -> T): T {
        val outermost = pendingTables.get() == null
        if (outermost) pendingTables.set(LinkedHashSet())
        var committed = false
        try {
            val result = sql.transaction(block)
            committed = true
            return result
        } finally {
            if (outermost) {
                val tables = pendingTables.get().orEmpty().toSet()
                pendingTables.remove()
                if (committed) observer.notifyChanged(tables)
            }
        }
    }

    /** Records that [tables] changed (or notifies right away outside a transaction). */
    fun changed(vararg tables: String) {
        val pending = pendingTables.get()
        if (pending != null) pending.addAll(tables) else observer.notifyChanged(tables.toSet())
    }

    internal fun <T> observe(vararg tables: String, query: () -> T): Flow<T> =
        observer.observe(tables.toSet(), query)
}

/** "?, ?, ?" for an IN clause with [count] arguments. */
internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

/** Splits large IN lists so no statement exceeds SQLite's 999 bound-variable limit. */
internal const val IN_CHUNK = 500
