package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.model.MailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Pushes pending operations to the server soon after the user acts: flag changes about a second
 * later (so quick toggles travel together), moves and deletes once their undo window is over.
 * Flushes that are blocked (offline, login problem) are reported to [onBlocked] so a background
 * job can retry once the network is back.
 */
class FlushScheduler(
    scope: CoroutineScope,
    private val flush: suspend (accountId: Long) -> FlushResult,
    private val onBlocked: (accountId: Long, error: MailException) -> Unit = { _, _ -> },
    private val onFlushed: (accountId: Long, result: FlushResult) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val runner = DelayedRunner(scope, clock)

    /** Flushes the account's operations at [at] (epoch millis), or earlier if already planned. */
    fun schedule(accountId: Long, at: Long = clock()) {
        runner.schedule(accountId, at) {
            try {
                val result = flush(accountId)
                result.blockedBy?.let { onBlocked(accountId, it) }
                result.nextDueAt?.let { schedule(accountId, it + MARGIN) }
                onFlushed(accountId, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onBlocked(accountId, e.toMailException())
            }
        }
    }

    /** Debounced flush for flag changes. */
    fun scheduleSoon(accountId: Long) = schedule(accountId, clock() + FLAG_DEBOUNCE)

    companion object {
        /** Delay before flag changes are pushed. */
        const val FLAG_DEBOUNCE = 1_000L

        /** Undo window of moves and deletes. */
        const val UNDO_WINDOW = 6_000L

        /** Slack after a due time, so the operation is surely due when the flush runs. */
        private const val MARGIN = 150L
    }
}
