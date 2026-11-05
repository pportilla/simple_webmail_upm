package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.mail.ImapIdler
import io.github.pportilla.upmmail.mail.IdleTransport
import io.github.pportilla.upmmail.mail.MailErrors
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** What the push connection of one account is doing. */
enum class PushState {
    /** Opening the connection. */
    CONNECTING,

    /** Connected: the server reports new mail as it arrives. */
    IDLING,

    /** The server cannot IDLE: the INBOX is checked every few minutes instead. */
    POLLING,

    /** The connection failed; waiting before the next attempt. */
    RETRYING,

    /** The server refused the login (not for a passing reason); retrying rarely until the password changes. */
    LOGIN_FAILED,
}

/** The IDLE connection of one account: [ImapIdler] in the app, a fake in tests. */
interface PushConnection {
    /** Like [ImapIdler.run]: idles until cancelled or failed; returns only if the server cannot IDLE. */
    suspend fun run(onIdle: () -> Unit, onChange: (ImapIdler.Change) -> Unit)

    /** Like [ImapIdler.refresh]. */
    suspend fun refresh(): Boolean
}

fun IdleTransport.asPushConnection(): PushConnection {
    val idler = this
    return object : PushConnection {
        override suspend fun run(onIdle: () -> Unit, onChange: (ImapIdler.Change) -> Unit) = idler.run(onIdle, onChange)

        override suspend fun refresh(): Boolean = idler.refresh()
    }
}

/** Delays used by [PushWatcher], in milliseconds. */
data class PushTimings(
    /** Changes reported within this window are synced together. */
    val debounce: Long = 1_500L,
    /** How often the INBOX is checked when the server cannot IDLE. */
    val pollInterval: Long = 2 * 60_000L,
    /** Wait before the first reconnection; it doubles after every failure, up to [maxRetry]. */
    val firstRetry: Long = 15_000L,
    val maxRetry: Long = 10 * 60_000L,
    /** A connection that lasted this long was healthy: the next failure starts the backoff again. */
    val healthyAfter: Long = 5 * 60_000L,
    /** Wait after a refused login, so that a changed password does not get the account locked. */
    val loginRetry: Long = 60 * 60_000L,
) {
    /** Wait before the next attempt after [failures] failures in a row (0 for the first). */
    fun retryDelay(failures: Int): Long = (firstRetry shl failures.coerceIn(0, MAX_SHIFT)).coerceAtMost(maxRetry)

    private companion object {
        const val MAX_SHIFT = 20
    }
}

/**
 * Keeps the INBOX of one account under watch: idles with [connection], syncs the INBOX through
 * [sync] after every change the server reports and after every (re)connection, and reconnects
 * with exponential backoff. A server without IDLE is polled instead.
 *
 * Syncs never overlap: changes that arrive during a sync lead to one more sync after it, and
 * changes within [PushTimings.debounce] of each other are synced together.
 */
class PushWatcher(
    private val connection: PushConnection,
    /** Syncs the INBOX; with `force = false` (polling) it may skip a sync if one just happened. */
    private val sync: suspend (force: Boolean) -> Unit,
    /** Whether a change reported by the server is worth a sync now. */
    private val isRelevant: (ImapIdler.Change) -> Boolean = { true },
    /** Runs [block] (a sync) keeping the device awake. */
    private val keepAwake: suspend (block: suspend () -> Unit) -> Unit = { it() },
    /** Told about failed connections and syncs, for logging. */
    private val onFailure: (MailException) -> Unit = {},
    private val timings: PushTimings = PushTimings(),
    /** Wall-clock time, which (unlike delays) keeps running while the device sleeps. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(PushState.CONNECTING)
    val state: StateFlow<PushState> = mutableState.asStateFlow()

    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val syncLock = Mutex()

    /** Asks a waiting watcher to act now (see [refresh]). */
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    /** Counts finished attempts, polls and pokes, so that [refresh] knows when it is done. */
    private val settled = MutableStateFlow(0L)

    /** Watches until cancelled. */
    suspend fun run() {
        coroutineScope {
            launch { syncLoop() }
            var failures = 0
            while (true) {
                mutableState.value = PushState.CONNECTING
                val connectedAt = AtomicLong(0L)
                val error = try {
                    connection.run(
                        onIdle = {
                            if (connectedAt.compareAndSet(0L, clock())) {
                                // Catch up on mail that arrived while disconnected. Never skipped,
                                // even just after another sync: the new connection does not
                                // announce mail that arrived before it opened the INBOX.
                                requestSync()
                                // A refresh asked for while connecting is answered by this connection.
                                pokes.tryReceive()
                            }
                            mutableState.value = PushState.IDLING
                            settle()
                        },
                        onChange = { change -> if (isRelevant(change)) requestSync() },
                    )
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.toMailException()
                }
                if (error == null) poll()
                onFailure(error)
                val since = connectedAt.get()
                if (since != 0L && clock() - since >= timings.healthyAfter) failures = 0
                if (error.kind == MailErrorKind.AUTHENTICATION && !MailErrors.isTemporaryLoginRefusal(error)) {
                    mutableState.value = PushState.LOGIN_FAILED
                    settle()
                    waitForLoginRetry()
                } else {
                    mutableState.value = PushState.RETRYING
                    settle()
                    withTimeoutOrNull(timings.retryDelay(failures++)) { pokes.receive() }
                }
            }
        }
    }

    /**
     * Checks the connection now, e.g. from an alarm while the device sleeps. An idle connection
     * proves itself with a NOOP round trip (a dead one is closed and reconnected); a watcher
     * waiting to reconnect or to poll does so at once. Returns when that is done.
     */
    suspend fun refresh() {
        if (state.value == PushState.IDLING) {
            connection.refresh()
            return
        }
        val before = settled.value
        pokes.trySend(Unit)
        settled.first { it > before }
    }

    /** The server cannot IDLE: check the INBOX regularly (and whenever poked). */
    private suspend fun poll(): Nothing {
        mutableState.value = PushState.POLLING
        while (true) {
            keepAwake { runSync(force = false) }
            settle()
            withTimeoutOrNull(timings.pollInterval) { pokes.receive() }
        }
    }

    /** Waits [PushTimings.loginRetry] of wall-clock time; pokes only re-check the clock. */
    private suspend fun waitForLoginRetry() {
        val failedAt = clock()
        while (true) {
            val left = timings.loginRetry - (clock() - failedAt)
            if (left <= 0) return
            withTimeoutOrNull(left) { pokes.receive() }
            settle()
        }
    }

    private fun requestSync() {
        syncRequests.trySend(Unit)
    }

    private suspend fun syncLoop(): Nothing {
        while (true) {
            syncRequests.receive()
            keepAwake {
                delay(timings.debounce)
                // Requests made during the debounce are covered by this sync.
                syncRequests.tryReceive()
                runSync(force = true)
            }
        }
    }

    private suspend fun runSync(force: Boolean) {
        syncLock.withLock {
            try {
                sync(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e.toMailException())
            }
        }
    }

    private fun settle() {
        settled.update { it + 1 }
    }
}
