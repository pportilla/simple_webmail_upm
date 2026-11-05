package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.flow.Flow

class AccountDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    fun observeAll(): Flow<List<Account>> = store.observe(Tables.ACCOUNTS) { getAll() }

    fun getAll(): List<Account> =
        sql.query("SELECT * FROM accounts ORDER BY sort_order, id", emptyList(), ::map)

    fun get(id: Long): Account? =
        sql.query("SELECT * FROM accounts WHERE id = ?", listOf(id), ::map).firstOrNull()

    fun getByEmail(email: String): Account? =
        sql.query("SELECT * FROM accounts WHERE email = ? COLLATE NOCASE", listOf(email.trim()), ::map).firstOrNull()

    fun count(): Int = sql.queryLong("SELECT COUNT(*) AS v FROM accounts")?.toInt() ?: 0

    /** Lower-case addresses of every account, to recognise the user's own messages. */
    fun ownAddresses(): Set<String> =
        sql.query("SELECT email FROM accounts", emptyList()) { it.stringOrEmpty("email").lowercase() }.toSet()

    fun usedColors(): List<Int> = sql.query("SELECT color FROM accounts", emptyList()) { it.intOr("color") }

    /** Inserts [account] (its id is ignored) at the end of the list and returns the new id. */
    fun insert(account: Account): Long = store.tx {
        val nextOrder = (sql.queryLong("SELECT MAX(sort_order) AS v FROM accounts") ?: -1L) + 1
        val id = sql.insert(Tables.ACCOUNTS, values(account) + mapOf("sort_order" to nextOrder, "created_at" to store.now()))
        store.changed(Tables.ACCOUNTS)
        id
    }

    fun update(account: Account): Boolean = store.tx {
        val changed = sql.update(Tables.ACCOUNTS, values(account) + mapOf("sort_order" to account.sortOrder), "id = ?", listOf(account.id))
        store.changed(Tables.ACCOUNTS)
        changed > 0
    }

    /** Deletes the account; folders, messages, pending operations, drafts and outbox cascade. */
    fun delete(id: Long) = store.tx {
        sql.execSQL("DELETE FROM messages_fts WHERE docid IN (SELECT id FROM messages WHERE account_id = ?)", listOf(id))
        sql.delete(Tables.ACCOUNTS, "id = ?", listOf(id))
        store.changed(
            Tables.ACCOUNTS, Tables.FOLDERS, Tables.MESSAGES, Tables.ATTACHMENTS, Tables.PENDING_OPS,
            Tables.OUTBOX, Tables.OUTBOX_ATTACHMENTS, Tables.DRAFTS, Tables.DRAFT_ATTACHMENTS, Tables.MESSAGES_FTS,
        )
    }

    private fun values(account: Account): Map<String, Any?> = mapOf(
        "email" to account.email.trim(),
        "display_name" to account.displayName,
        "imap_host" to account.imap.host,
        "imap_port" to account.imap.port,
        "imap_security" to account.imap.security,
        "imap_username" to account.imap.username,
        "smtp_host" to account.smtp.host,
        "smtp_port" to account.smtp.port,
        "smtp_security" to account.smtp.security,
        "smtp_username" to account.smtp.username,
        "signature" to account.signature,
        "color" to account.color,
        "notifications_enabled" to account.notificationsEnabled,
        "is_demo" to account.isDemo,
    )

    private fun map(row: SqlRow): Account = Account(
        id = row.longOr("id"),
        email = row.stringOrEmpty("email"),
        displayName = row.stringOrEmpty("display_name"),
        imap = ServerSettings(
            host = row.stringOrEmpty("imap_host"),
            port = row.intOr("imap_port"),
            security = securityOf(row.string("imap_security")),
            username = row.stringOrEmpty("imap_username"),
        ),
        smtp = ServerSettings(
            host = row.stringOrEmpty("smtp_host"),
            port = row.intOr("smtp_port"),
            security = securityOf(row.string("smtp_security")),
            username = row.stringOrEmpty("smtp_username"),
        ),
        signature = row.stringOrEmpty("signature"),
        color = row.intOr("color"),
        notificationsEnabled = row.bool("notifications_enabled"),
        sortOrder = row.intOr("sort_order"),
        isDemo = row.bool("is_demo"),
    )

    private fun securityOf(value: String?): SecurityMode =
        SecurityMode.entries.firstOrNull { it.name == value } ?: SecurityMode.SSL_TLS
}
