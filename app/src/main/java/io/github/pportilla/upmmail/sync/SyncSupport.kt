package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.security.PasswordStore
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.data.safety.SafetyPolicy
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.ImapIdler
import io.github.pportilla.upmmail.mail.ImapOptions
import io.github.pportilla.upmmail.mail.MailErrors
import io.github.pportilla.upmmail.mail.SmtpClient
import io.github.pportilla.upmmail.mail.requireEncryptedTransport
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Events of the sync engine that the app reacts to (notifications). All calls are best effort. */
interface SyncListener {
    /** New unread messages were found in the INBOX of [accountId]. */
    fun onNewMessages(accountId: Long, folderId: Long, messages: List<MessageSummary>) {}

    /** The INBOX was synchronised: notifications of messages read elsewhere can go. */
    fun onInboxSynced(accountId: Long, folderId: Long) {}

    /** A queued message could not be sent and needs the user's attention. */
    fun onSendFailed(item: OutboxItem) {}

    companion object {
        val NONE = object : SyncListener {}
    }
}

/** Any failure as a [MailException] (already-mapped ones are returned unchanged). */
fun Throwable.toMailException(): MailException =
    this as? MailException
        ?: runCatching { MailErrors.map(this) }.getOrElse { MailException(MailErrorKind.UNKNOWN, message, this) }

/**
 * Runs [block] and wraps its outcome in a Result with a [MailException] on failure.
 * Cancellation is rethrown, never turned into a failure.
 */
suspend inline fun <T> mailResult(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toMailException())
    }

/**
 * One cached [ImapClient] per account (it keeps its connection open between syncs); SMTP clients
 * are cheap and created per use. A client is replaced when the account's server settings change
 * and closed by [invalidate] when the account is removed or its password changes. Work that
 * asked for a client while the account was being removed leaves one behind: it is closed as
 * soon as the account is found missing ([account], [releaseIfRemoved]). Push connections
 * ([idler]) are not cached; their owner restarts them on [invalidated].
 */
class MailClientProvider(
    private val accountLookup: (Long) -> Account?,
    private val passwords: PasswordStore,
    private val imapFactory: (ServerSettings, () -> String) -> ImapClient = { s, p ->
        ImapClient(s, p, ImapOptions.DEFAULT.copy(abortable = true))
    },
    private val smtpFactory: (ServerSettings, () -> String) -> SmtpClient = { s, p -> SmtpClient(s, p) },
    /** Only local mock-server tests replace this policy; production always requires TLS. */
    private val transportPolicy: (ServerSettings) -> Unit = ::requireEncryptedTransport,
    private val safety: SafetyPolicy = LocalSafetyRepository(),
) {
    private class Cached(val settings: ServerSettings, val client: ImapClient)

    private val imapClients = HashMap<Long, Cached>()
    private val invalidations = MutableSharedFlow<Long>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Accounts passed to [invalidate]: their login changed or they were removed. */
    val invalidated: SharedFlow<Long> = invalidations.asSharedFlow()

    fun account(accountId: Long): Account =
        accountLookup(accountId) ?: run {
            forget(accountId)
            throw MailException(MailErrorKind.NOT_FOUND, "Account $accountId does not exist")
        }

    /** Closes the cached connection of [accountId] if the account no longer exists. */
    fun releaseIfRemoved(accountId: Long) {
        val exists = try {
            accountLookup(accountId) != null
        } catch (_: Exception) {
            true
        }
        if (!exists) forget(accountId)
    }

    fun imap(accountId: Long): ImapClient {
        safety.requireNetwork()
        val account = account(accountId)
        transportPolicy(account.imap)
        val stale: ImapClient?
        val client: ImapClient
        synchronized(imapClients) {
            safety.requireNetwork()
            val cached = imapClients[accountId]
            if (cached != null && cached.settings == account.imap) return cached.client
            stale = cached?.client
            client = imapFactory(account.imap, passwordProvider(accountId))
            imapClients[accountId] = Cached(account.imap, client)
        }
        stale?.closeQuietly()
        return client
    }

    fun smtp(accountId: Long): SmtpClient {
        safety.requireNetwork()
        val account = account(accountId)
        transportPolicy(account.smtp)
        return smtpFactory(account.smtp, passwordProvider(accountId))
    }

    /** A push connection to the account's INBOX, with its current server settings and password. */
    fun idler(accountId: Long): ImapIdler {
        safety.requireNetwork()
        val settings = account(accountId).imap
        transportPolicy(settings)
        return ImapIdler(settings, passwordProvider(accountId))
    }

    /** Closes and forgets the account's IMAP connection, and tells [invalidated] collectors. */
    fun invalidate(accountId: Long) {
        forget(accountId)
        invalidations.tryEmit(accountId)
    }

    private fun forget(accountId: Long) {
        val removed = synchronized(imapClients) { imapClients.remove(accountId) }
        removed?.client?.closeQuietly()
    }

    fun closeAll() {
        val all = synchronized(imapClients) { imapClients.values.toList().also { imapClients.clear() } }
        // Interrupt in-flight protocol work before waiting for a graceful close.
        all.forEach { it.client.abort() }
        all.forEach { it.client.closeQuietly() }
    }

    private fun passwordProvider(accountId: Long): () -> String = {
        // The account may have been revoked after the connection object was obtained.
        safety.requireNetwork()
        passwords.get(accountId)
            ?: throw MailException(MailErrorKind.AUTHENTICATION, "No stored password for account $accountId")
    }

    private fun ImapClient.closeQuietly() {
        try {
            abort()
            close()
        } catch (_: Throwable) {
            // Closing a dead connection may fail; nothing to do about it.
        }
    }
}

/**
 * Runs an action at a given time, once per key. A new request for the same key keeps whichever
 * time is earlier; an action that has already started is never cancelled (a later request just
 * schedules another run).
 */
class DelayedRunner(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Slot(val at: Long) {
        lateinit var job: Job
    }

    private val slots = HashMap<Any, Slot>()

    fun schedule(key: Any, at: Long, action: suspend () -> Unit) {
        synchronized(slots) {
            val existing = slots[key]
            if (existing != null && existing.job.isActive && existing.at <= at) return
            existing?.job?.cancel()
            val slot = Slot(at)
            slot.job = scope.launch(start = CoroutineStart.LAZY) {
                val wait = at - clock()
                if (wait > 0) delay(wait)
                synchronized(slots) {
                    // Replaced while waiting: the newer request will run instead.
                    if (slots[key] !== slot) return@launch
                    slots.remove(key)
                }
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Actions report their own failures; a background retry comes later.
                }
            }
            slots[key] = slot
            slot.job.start()
        }
    }

    fun cancel(key: Any) {
        synchronized(slots) { slots.remove(key) }?.job?.cancel()
    }
}
