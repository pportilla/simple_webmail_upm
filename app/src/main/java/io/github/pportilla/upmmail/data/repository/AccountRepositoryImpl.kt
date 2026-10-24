package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.security.PasswordStore
import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.mail.ImapOptions
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.SmtpClient
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.mail.requireEncryptedTransport
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.sync.MailClientProvider
import io.github.pportilla.upmmail.sync.SyncEngine
import io.github.pportilla.upmmail.sync.mailResult
import io.github.pportilla.upmmail.sync.toMailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import io.github.pportilla.upmmail.data.safety.Audience
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicReference
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
    override suspend fun checkImap(settings: ServerSettings, password: String) = checkImap(settings, password) {}

    suspend fun checkImap(
        settings: ServerSettings,
        password: String,
        policyChanges: StateFlow<*>? = null,
        authorize: () -> Unit,
    ): Unit = coroutineScope {
        requireEncryptedTransport(settings)
        authorize()
        val client = ImapClient(settings, { authorize(); password }, ImapOptions.DEFAULT.copy(abortable = true))
        val refusal = AtomicReference<MailException?>(null)
        val observer = policyChanges?.let { changes ->
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                changes.collect {
                    try { authorize() } catch (error: Exception) {
                        refusal.compareAndSet(null, error.toMailException())
                        client.abort()
                    }
                }
            }
        }
        try {
            client.connect()
            authorize()
        } catch (error: Exception) {
            throw refusal.get() ?: error
        } finally {
            observer?.cancel()
            client.abort()
            try { client.close() } catch (_: Exception) { /* Already closed. */ }
        }
    }

    override suspend fun checkSmtp(settings: ServerSettings, password: String) = checkSmtp(settings, password) {}

    suspend fun checkSmtp(
        settings: ServerSettings,
        password: String,
        policyChanges: StateFlow<*>? = null,
        authorize: () -> Unit,
    ) {
        requireEncryptedTransport(settings)
        authorize()
        SmtpClient(settings) { authorize(); password }.verify(policyChanges, authorize)
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
    private val onDemoArrivalRequested: (Long) -> Unit = { throw UnsupportedOperationException("Sample arrival is unavailable") },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AccountRepository {

    override val accounts: Flow<List<Account>> = combine(store.accounts.observeAll(), store.safety.state) { accounts, safety ->
        if (!safety.termsAccepted || safety.audience == Audience.UNCONFIGURED) emptyList() else accounts
    }

    override suspend fun getAccount(id: Long): Account? = db { store.safety.requireAccountManagement(); store.accounts.get(id) }

    override suspend fun addDemoAccount(): Result<Account> = mailResult {
        // Sample mail is a separate account, never a way to reset or bypass age/guardian policy.
        store.safety.requireAccountManagement()
        store.safety.requireNetwork()
        val account = db {
            store.safety.requireAccountManagement()
            store.tx {
                store.accounts.getAll().firstOrNull { it.isDemo } ?: run {
                    val address = "review@example.invalid"
                    if (store.accounts.getByEmail(address) != null) throw duplicate(address)
                    val id = store.accounts.insert(Account(
                        id = 0,
                        email = address,
                        displayName = "Sample mailbox",
                        imap = ServerSettings("local-demo.invalid", 993, SecurityMode.SSL_TLS, address),
                        smtp = ServerSettings("local-demo.invalid", 465, SecurityMode.SSL_TLS, address),
                        signature = "",
                        color = pickColor(store.accounts.usedColors()),
                        notificationsEnabled = true,
                        sortOrder = 0,
                        isDemo = true,
                    ))
                    checkNotNull(store.accounts.get(id))
                }
            }
        }
        // The provider routes the explicit persisted flag to local transports before password
        // lookup. Nothing is entered into the password store and no server login is attempted.
        settings.update { it.copy(lastAccountId = account.id, lastFolderId = -1) }
        syncInBackground(account.id)
        account
    }

    override suspend fun scheduleDemoArrival(accountId: Long): Result<Unit> = mailResult {
        db {
            store.safety.requireAccountManagement()
            store.safety.requireNetwork()
            if (store.accounts.get(accountId)?.isDemo != true) {
                throw MailException(MailErrorKind.NOT_FOUND, "No sample account")
            }
        }
        onDemoArrivalRequested(accountId)
    }

    override suspend fun addAccount(
        email: String,
        password: String,
        displayName: String,
        imap: ServerSettings?,
        smtp: ServerSettings?,
    ): Result<Account> = mailResult {
        store.safety.requireAccountManagement()
        store.safety.requireNetwork()
        val address = email.trim()
        if (!AddressParser.isValidAddress(address)) throw MailException(MailErrorKind.UNKNOWN, "Invalid e-mail address: $address")
        if (db { store.accounts.getByEmail(address) } != null) throw duplicate(address)

        // Validate both before contacting either server, including when a custom checker is used.
        imap?.let(::requireEncryptedTransport)
        smtp?.let(::requireEncryptedTransport)

        val defaults = UpmPresets.defaultsFor(address)
        val imapSettings = if (imap != null) {
            imap.also { store.safety.requireAccountManagement(); checkImap(it, password) }
        } else {
            detectLogin(defaults.imap, defaults.imapUsernameCandidates) { store.safety.requireAccountManagement(); checkImap(it, password) }
        }
        val smtpSettings = if (smtp != null) {
            smtp.also { store.safety.requireAccountManagement(); checkSmtp(it, password) }
        } else {
            detectLogin(defaults.smtp, defaults.smtpUsernameCandidates) { store.safety.requireAccountManagement(); checkSmtp(it, password) }
        }

        val id = db {
            store.safety.requireAccountManagement()
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
        store.safety.requireAccountManagement()
        // Also applies to metadata-only updates with verify=false.
        requireEncryptedTransport(account.imap)
        requireEncryptedTransport(account.smtp)
        val existing = db { store.accounts.get(account.id) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Account ${account.id}")
        if (account.isDemo != existing.isDemo) {
            throw MailException(MailErrorKind.UNKNOWN, "An account cannot change between sample and real mail")
        }
        if (existing.isDemo) {
            if (account.email != existing.email || account.imap != existing.imap || account.smtp != existing.smtp || newPassword != null) {
                throw MailException(MailErrorKind.UNKNOWN, "Sample mailbox connection details cannot be changed")
            }
            val updated = account.copy(displayName = account.displayName.trim())
            db { store.safety.requireAccountManagement(); store.safety.forgetAccount(account.id); store.accounts.update(updated) }
            return@mailResult updated
        }
        val email = account.email.trim()
        if (!email.equals(existing.email, ignoreCase = true)) {
            if (!AddressParser.isValidAddress(email)) throw MailException(MailErrorKind.UNKNOWN, "Invalid e-mail address: $email")
            if (db { store.accounts.getByEmail(email) } != null) throw duplicate(email)
        }
        if (verify) {
            val password = newPassword ?: db { passwords.get(account.id) }
                ?: throw MailException(MailErrorKind.AUTHENTICATION, "No stored password")
            store.safety.requireAccountManagement()
            checkImap(account.imap, password)
            store.safety.requireAccountManagement()
            checkSmtp(account.smtp, password)
        }
        val updated = account.copy(email = email, displayName = account.displayName.trim())
        db { store.safety.requireAccountManagement(); store.safety.forgetAccount(account.id); store.accounts.update(updated) }
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
        store.safety.requireAccountManagement()
        store.safety.forgetAccount(id)
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

    private suspend fun checkImap(settings: ServerSettings, password: String) {
        store.safety.requireAccountManagement()
        if (checker === EngineCredentialChecker) EngineCredentialChecker.checkImap(settings, password, store.safety.state, store.safety::requireAccountManagement)
        else checker.checkImap(settings, password)
        store.safety.requireAccountManagement()
    }

    private suspend fun checkSmtp(settings: ServerSettings, password: String) {
        store.safety.requireAccountManagement()
        if (checker === EngineCredentialChecker) EngineCredentialChecker.checkSmtp(settings, password, store.safety.state, store.safety::requireAccountManagement)
        else checker.checkSmtp(settings, password)
        store.safety.requireAccountManagement()
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
