package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.security.PasswordStore
import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.SmtpClient
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.sync.MailClientProvider
import io.github.pportilla.upmmail.sync.SyncEngine
import io.github.pportilla.upmmail.sync.mailResult
import io.github.pportilla.upmmail.sync.toMailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tests a login against the servers (one throwaway connection each). */
interface CredentialChecker {
    suspend fun checkImap(settings: ServerSettings, password: String)

    suspend fun checkSmtp(settings: ServerSettings, password: String)
}

/** [CredentialChecker] using the real mail engine. */
object EngineCredentialChecker : CredentialChecker {
    override suspend fun checkImap(settings: ServerSettings, password: String) {
        val client = ImapClient(settings) { password }
        try {
            client.connect()
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
                // The login result is what matters.
            }
        }
    }

    override suspend fun checkSmtp(settings: ServerSettings, password: String) {
        SmtpClient(settings) { password }.verify()
    }
}

/** [AccountRepository]: accounts in SQLite, passwords in the [PasswordStore]. */
class AccountRepositoryImpl(
    private val store: MailStore,
    private val passwords: PasswordStore,
    private val clients: MailClientProvider,
    private val engine: SyncEngine,
    private val settings: SettingsRepository,
    private val files: LocalFiles,
    private val scope: CoroutineScope,
    private val checker: CredentialChecker = EngineCredentialChecker,
    /** Called after an account was removed (e.g. to cancel its notifications). */
    private val onAccountRemoved: (Long) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AccountRepository {

    override val accounts: Flow<List<Account>> = store.accounts.observeAll()

    override suspend fun getAccount(id: Long): Account? = db { store.accounts.get(id) }

    override suspend fun addAccount(
        email: String,
        password: String,
        displayName: String,
        imap: ServerSettings?,
        smtp: ServerSettings?,
    ): Result<Account> = mailResult {
        val address = email.trim()
        if (!AddressParser.isValidAddress(address)) throw MailException(MailErrorKind.UNKNOWN, "Invalid e-mail address: $address")
        if (db { store.accounts.getByEmail(address) } != null) throw duplicate(address)

        val defaults = UpmPresets.defaultsFor(address)
        val imapSettings = if (imap != null) {
            imap.also { checker.checkImap(it, password) }
        } else {
            detectLogin(defaults.imap, defaults.imapUsernameCandidates) { checker.checkImap(it, password) }
        }
        val smtpSettings = if (smtp != null) {
            smtp.also { checker.checkSmtp(it, password) }
        } else {
            detectLogin(defaults.smtp, defaults.smtpUsernameCandidates) { checker.checkSmtp(it, password) }
        }

        val id = db {
            store.tx {
                if (store.accounts.getByEmail(address) != null) throw duplicate(address)
                store.accounts.insert(
                    Account(
                        id = 0,
                        email = address,
                        displayName = displayName.trim(),
                        imap = imapSettings,
                        smtp = smtpSettings,
                        signature = "",
                        color = pickColor(store.accounts.usedColors()),
                        notificationsEnabled = true,
                        sortOrder = 0,
                    ),
                )
            }
        }
        try {
            db { passwords.set(id, password) }
        } catch (e: Exception) {
            db { store.accounts.delete(id) }
            throw MailException(MailErrorKind.UNKNOWN, "The password could not be stored securely", e)
        }
        val account = db { store.accounts.get(id) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Account $id")
        syncInBackground(id)
        account
    }

    /**
     * Tries each login name in order. Only a rejected login moves on to the next candidate;
     * network or certificate problems are reported at once (another name would not help).
     */
    private suspend fun detectLogin(
        base: ServerSettings,
        candidates: List<String>,
        check: suspend (ServerSettings) -> Unit,
    ): ServerSettings {
        var lastRejection: MailException? = null
        for (username in candidates.ifEmpty { listOf(base.username) }) {
            val settings = base.copy(username = username)
            try {
                check(settings)
                return settings
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toMailException()
                if (error.kind != MailErrorKind.AUTHENTICATION) throw error
                lastRejection = error
            }
        }
        throw lastRejection ?: MailException(MailErrorKind.AUTHENTICATION)
    }

    override suspend fun updateAccount(account: Account, newPassword: String?, verify: Boolean): Result<Account> = mailResult {
        val existing = db { store.accounts.get(account.id) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Account ${account.id}")
        val email = account.email.trim()
        if (!email.equals(existing.email, ignoreCase = true)) {
            if (!AddressParser.isValidAddress(email)) throw MailException(MailErrorKind.UNKNOWN, "Invalid e-mail address: $email")
            if (db { store.accounts.getByEmail(email) } != null) throw duplicate(email)
        }
        if (verify) {
            val password = newPassword ?: db { passwords.get(account.id) }
                ?: throw MailException(MailErrorKind.AUTHENTICATION, "No stored password")
            checker.checkImap(account.imap, password)
            checker.checkSmtp(account.smtp, password)
        }
        val updated = account.copy(email = email, displayName = account.displayName.trim())
        db { store.accounts.update(updated) }
        if (newPassword != null) {
            try {
                db { passwords.set(account.id, newPassword) }
            } catch (e: Exception) {
                throw MailException(MailErrorKind.UNKNOWN, "The password could not be stored securely", e)
            }
        }
        val loginChanged = newPassword != null || existing.imap != updated.imap || existing.smtp != updated.smtp
        if (loginChanged) {
            clients.invalidate(account.id)
            // Work blocked by the old login (pending changes, queued mail) can go now.
            syncInBackground(account.id)
        }
        db { store.accounts.get(account.id) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Account ${account.id}")
    }

    override suspend fun removeAccount(id: Long) {
        clients.invalidate(id)
        withContext(io) {
            val downloaded = store.messages.idsWithDownloads(id)
            val composeUris = store.drafts.attachmentUrisOfAccount(id) + store.outbox.attachmentUrisOfAccount(id)
            store.accounts.delete(id)
            files.deleteMessageFiles(downloaded)
            files.deleteComposeFiles(composeUris, store.drafts.attachmentUris() + store.outbox.attachmentUris())
            passwords.remove(id)
            // A sync or flush that ended meanwhile may have connected again before the account
            // was gone: that connection is closed too.
            clients.releaseIfRemoved(id)
            // Address history and searches are not kept per account: they go with the last one.
            if (store.accounts.getAll().isEmpty()) {
                store.contacts.clear()
                store.recentSearches.clear()
            }
        }
        settings.update { current ->
            if (current.lastAccountId == id) current.copy(lastAccountId = -1, lastFolderId = -1) else current
        }
        try {
            withContext(io) { onAccountRemoved(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Housekeeping only.
        }
    }

    private fun syncInBackground(accountId: Long) {
        scope.launch(io) {
            try {
                engine.syncAccount(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The mailbox screen shows the folder state and retries on refresh.
            }
        }
    }

    private fun duplicate(address: String) =
        MailException(MailErrorKind.UNKNOWN, "The account $address has already been added")

    private suspend fun <T> db(block: () -> T): T = withContext(io) { block() }

    companion object {
        /** Distinct, readable account colours (UPM blue first). */
        val ACCOUNT_COLORS = listOf(
            0xFF003F87.toInt(),
            0xFF00897B.toInt(),
            0xFFE53935.toInt(),
            0xFF8E24AA.toInt(),
            0xFFF4511E.toInt(),
            0xFF43A047.toInt(),
            0xFF3949AB.toInt(),
            0xFFD81B60.toInt(),
            0xFF6D4C41.toInt(),
            0xFF546E7A.toInt(),
        )

        /** The first palette colour not used yet, cycling when all are taken. */
        fun pickColor(used: List<Int>): Int =
            ACCOUNT_COLORS.firstOrNull { it !in used } ?: ACCOUNT_COLORS[used.size % ACCOUNT_COLORS.size]
    }
}
