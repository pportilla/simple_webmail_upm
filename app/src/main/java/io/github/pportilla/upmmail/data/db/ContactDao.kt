package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.Contact
import io.github.pportilla.upmmail.model.EmailAddress

/** Address history used for recipient suggestions. */
class ContactDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    /** Counts one appearance of each address in received mail (From/To/Cc). */
    fun recordSeen(addresses: List<EmailAddress>, date: Long, ownAddresses: Set<String>) =
        record(addresses, date, ownAddresses, contacted = false)

    /** Counts one message sent by the user to each address. */
    fun recordContacted(addresses: List<EmailAddress>, date: Long) =
        record(addresses, date, emptySet(), contacted = true)

    private fun record(addresses: List<EmailAddress>, date: Long, ownAddresses: Set<String>, contacted: Boolean) {
        val unique = addresses
            .filter { it.address.contains('@') }
            .distinctBy { it.address.trim().lowercase() }
            .filter { contacted || (it.address.trim().lowercase() !in ownAddresses && !isAutomated(it.address)) }
        if (unique.isEmpty()) return
        store.tx {
            for (address in unique) {
                val email = address.address.trim().lowercase()
                val name = address.name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(email, ignoreCase = true) }
                sql.insert(
                    Tables.CONTACTS,
                    mapOf("email" to email, "name" to name, "search_key" to searchKey(name, email)),
                    Conflict.IGNORE,
                )
                val counter = if (contacted) "times_contacted" else "times_seen"
                sql.executeUpdateDelete(
                    "UPDATE contacts SET $counter = $counter + 1, last_used = MAX(last_used, ?) WHERE email = ?",
                    listOf(date, email),
                )
                if (name != null) {
                    sql.update(Tables.CONTACTS, mapOf("name" to name, "search_key" to searchKey(name, email)), "email = ?", listOf(email))
                }
            }
            store.changed(Tables.CONTACTS)
        }
    }

    /**
     * Candidates whose name or address contains every word of [query] (accent- and
     * case-insensitive), most used first. Callers refine with a word-prefix match.
     */
    fun candidates(query: String, limit: Int): List<Contact> {
        val words = SearchText.words(query).distinct().take(MAX_QUERY_WORDS)
        if (words.isEmpty()) return emptyList()
        // Every word is filtered before the limit: the person typed in full is never crowded out
        // by more frequent contacts that only match the first word.
        val where = words.joinToString(" AND ") { "search_key LIKE ? ESCAPE '\\'" }
        return sql.query(
            """
            SELECT * FROM contacts WHERE $where
            ORDER BY (times_contacted * 3 + times_seen) DESC, last_used DESC LIMIT ?
            """.trimIndent(),
            words.map { "%" + SearchText.escapeLike(it) + "%" } + limit,
            ::map,
        )
    }

    /** Forgets every address (the last account was removed). */
    fun clear() = store.tx {
        sql.delete(Tables.CONTACTS, "1 = 1")
        store.changed(Tables.CONTACTS)
    }

    fun get(email: String): Contact? =
        sql.query("SELECT * FROM contacts WHERE email = ?", listOf(email.trim().lowercase()), ::map).firstOrNull()

    private fun map(row: SqlRow): Contact = Contact(
        email = row.stringOrEmpty("email"),
        name = row.string("name"),
        timesContacted = row.intOr("times_contacted"),
        timesSeen = row.intOr("times_seen"),
        lastUsed = row.longOr("last_used"),
    )

    companion object {
        private const val MAX_QUERY_WORDS = 5

        private val AUTOMATED_LOCAL_PARTS = listOf(
            "noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "do_not_reply",
            "mailer-daemon", "postmaster", "bounce", "bounces", "notifications", "notification",
        )

        /** Addresses nobody wants suggested as recipients (no-reply senders, bounces). */
        fun isAutomated(address: String): Boolean {
            val local = address.substringBefore('@').lowercase()
            return AUTOMATED_LOCAL_PARTS.any { local == it || local.startsWith("$it+") || local.startsWith("$it.") } ||
                local.startsWith("bounce-") || local.startsWith("noreply")
        }

        internal fun searchKey(name: String?, email: String): String =
            SearchText.fold(listOfNotNull(name, email).joinToString(" "))
    }
}
