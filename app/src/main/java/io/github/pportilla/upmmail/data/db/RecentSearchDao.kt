package io.github.pportilla.upmmail.data.db

class RecentSearchDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    /** Newest first. */
    fun list(limit: Int = MAX_ENTRIES): List<String> =
        sql.query("SELECT query FROM recent_searches ORDER BY used_at DESC LIMIT ?", listOf(limit)) {
            it.stringOrEmpty("query")
        }

    /** Remembers [query] (trimmed; case-insensitive duplicates replaced), keeping the newest entries. */
    fun add(query: String) {
        val text = query.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        if (text.isEmpty()) return
        store.tx {
            sql.delete(Tables.RECENT_SEARCHES, "query = ? COLLATE NOCASE", listOf(text))
            sql.insert(Tables.RECENT_SEARCHES, mapOf("query" to text, "used_at" to store.now()), Conflict.REPLACE)
            sql.executeUpdateDelete(
                "DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY used_at DESC LIMIT ?)",
                listOf(MAX_ENTRIES),
            )
            store.changed(Tables.RECENT_SEARCHES)
        }
    }

    fun clear() = store.tx {
        sql.delete(Tables.RECENT_SEARCHES, "1 = 1")
        store.changed(Tables.RECENT_SEARCHES)
    }

    companion object {
        const val MAX_ENTRIES = 10
        private const val MAX_LENGTH = 200
    }
}
