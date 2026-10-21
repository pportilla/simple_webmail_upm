package io.github.pportilla.upmmail.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.data.repository.SyncController
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * [SyncController] on JobScheduler:
 *
 * - periodic background check (job [JOB_PERIODIC], interval from the settings, at least 15
 *   minutes, any network, survives reboots; cancelled when the interval is 0 or there are no
 *   accounts),
 * - outbox job ([JOB_OUTBOX]): sends queued messages once due and online, even if the app died,
 * - pending-operations job ([JOB_PENDING_OPS]): pushes local changes that failed for lack of
 *   network once it is back,
 * - in-app timers that send due outbox messages immediately, and INBOX polling every two minutes
 *   while the app is visible.
 *
 * [onForeground] runs whenever the app becomes visible (it starts instant push, see
 * [PushController]).
 */
class AndroidSyncController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: MailStore,
    private val engine: SyncEngine,
    private val settings: SettingsRepository,
    private val flushScheduler: () -> FlushScheduler,
    private val onForeground: () -> Unit = {},
) : SyncController {

    private val jobScheduler: JobScheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
    private val outboxRunner = DelayedRunner(scope)
    private val lock = Any()
    private var pollJob: Job? = null
    private var scheduledOutboxAt: Long? = null

    /** True between onAppForeground and onAppBackground. */
    @Volatile
    var isForeground: Boolean = false
        private set

    /** Starts following the settings and account list to keep the periodic job up to date. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            combine(
                settings.settings.map { it.syncIntervalMinutes }.distinctUntilChanged(),
                store.accounts.observeAll().map { it.isNotEmpty() }.distinctUntilChanged(),
            ) { interval, hasAccounts -> interval to hasAccounts }
                .distinctUntilChanged()
                .collect { (interval, hasAccounts) -> applyPeriodic(interval, hasAccounts) }
        }
    }

    override fun schedulePeriodicSync() {
        scope.launch(Dispatchers.IO) {
            applyPeriodic(settings.settings.value.syncIntervalMinutes, safely(false) { store.accounts.count() > 0 })
        }
    }

    private fun applyPeriodic(intervalMinutes: Int, hasAccounts: Boolean) {
        try {
            if (intervalMinutes <= 0 || !hasAccounts) {
                jobScheduler.cancel(JOB_PERIODIC)
                return
            }
            val interval = TimeUnit.MINUTES.toMillis(intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong())
            val existing = jobScheduler.getPendingJob(JOB_PERIODIC)
            if (existing != null && existing.isPeriodic && existing.intervalMillis == interval) return
            val job = JobInfo.Builder(JOB_PERIODIC, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(interval)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            jobScheduler.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the periodic sync", e)
        }
    }

    // ------------------------------------------------------------------ foreground

    override fun onAppForeground() {
        isForeground = true
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = scope.launch(Dispatchers.IO) { pollWhileVisible() }
        }
        onForeground()
    }

    override fun onAppBackground() {
        isForeground = false
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = null
        }
        requestOutboxSend()
    }

    private suspend fun pollWhileVisible() {
        quietly { engine.sendOutbox(null) }
        val accounts = safely(emptyList()) { store.accounts.getAll() }
        safely(emptyList()) { store.pendingOps.accountsWithPending() }.forEach { flushScheduler().schedule(it) }
        val selected = selectedAccount(accounts)
        // Selected account first, then the others whose INBOX was not checked recently.
        val ordered = listOfNotNull(selected) + accounts.filter { it.id != selected?.id }
        for (account in ordered) {
            val inbox = safely(null) { store.folders.findByRole(account.id, FolderRole.INBOX) }
            val age = inbox?.lastSyncedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
            // The visible account is refreshed unless it just was (quick app switches); the
            // others only when their INBOX is older than one polling interval.
            val threshold = if (account.id == selected?.id) RECENT_SYNC else POLL_INTERVAL
            if (age > threshold) quietly { engine.syncFolder(engine.inboxOf(account.id).id) }
        }
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL)
            if (!isOnline()) continue
            val current = selectedAccount(safely(emptyList()) { store.accounts.getAll() }) ?: continue
            // Push (or a pull-to-refresh) may have synced it moments ago.
            val inbox = safely(null) { store.folders.findByRole(current.id, FolderRole.INBOX) }
            val age = inbox?.lastSyncedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
            if (age < RECENT_POLL) continue
            quietly { engine.syncFolder(engine.inboxOf(current.id).id) }
        }
    }

    private fun selectedAccount(accounts: List<Account>): Account? {
        val last = settings.settings.value.lastAccountId
        return accounts.firstOrNull { it.id == last } ?: accounts.firstOrNull()
    }

    // ------------------------------------------------------------------ outbox

    override fun requestOutboxSend() {
        scope.launch(Dispatchers.IO) { planOutbox(minDelay = 0) }
    }

    private fun planOutbox(minDelay: Long) {
        val next = safely(null) { store.outbox.nextSendAt() }
        if (next == null) {
            outboxRunner.cancel(OUTBOX_KEY)
            synchronized(lock) {
                scheduledOutboxAt = null
                runCatching { jobScheduler.cancel(JOB_OUTBOX) }
            }
            return
        }
        val at = maxOf(next, System.currentTimeMillis() + minDelay)
        scheduleOutboxJob(at)
        outboxRunner.schedule(OUTBOX_KEY, at) {
            // Offline, the network-constrained job sends instead: trying now would only use up
            // the message's retry attempts.
            if (isOnline()) {
                quietly { engine.sendOutbox(null) }
                // A short pause before re-planning, so a message that keeps failing cannot spin.
                planOutbox(minDelay = REPLAN_DELAY)
            }
        }
    }

    /** Safety net for when the process dies before the in-app timer fires. */
    private fun scheduleOutboxJob(at: Long) {
        synchronized(lock) {
            if (scheduledOutboxAt == at) return
            scheduledOutboxAt = at
            try {
                val job = JobInfo.Builder(JOB_OUTBOX, ComponentName(context, SyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency((at - System.currentTimeMillis()).coerceAtLeast(0))
                    .setBackoffCriteria(BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build()
                jobScheduler.schedule(job)
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule the outbox job", e)
            }
        }
    }

    // ------------------------------------------------------------------ pending operations

    /** Asks JobScheduler to push pending changes as soon as the network is available. */
    fun requestPendingOpsFlush() {
        try {
            val job = JobInfo.Builder(JOB_PENDING_OPS, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            if (jobScheduler.getPendingJob(JOB_PENDING_OPS) == null) jobScheduler.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the pending operations job", e)
        }
    }

    // ------------------------------------------------------------------ job bodies

    /**
     * Background check: pending changes, outbox and INBOX of every account (new mail is notified
     * through the engine's listener). Returns true if the job should be retried with backoff.
     */
    suspend fun runPeriodicSync(): Boolean {
        engine.failStaleSending()
        quietly { engine.sendOutbox(null) }
        for (account in safely(emptyList()) { store.accounts.getAll() }) {
            quietly { engine.syncFolder(engine.inboxOf(account.id).id) }
        }
        return false
    }

    /** Sends the due outbox messages; follow-ups are planned by [afterJob]. */
    suspend fun runOutboxJob(): Boolean {
        engine.sendOutbox(null)
        return false
    }

    /** Pushes pending changes of every account. True (retry with backoff) if still offline. */
    suspend fun runPendingOpsJob(): Boolean {
        var retry = false
        for (accountId in safely(emptyList()) { store.pendingOps.accountsWithPending() }) {
            try {
                val result = engine.flushPendingOps(accountId)
                if (result.blockedBy?.kind == MailErrorKind.NETWORK) retry = true
                result.nextDueAt?.let { flushScheduler().schedule(accountId, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.toMailException().isTransient) retry = true
            }
        }
        return retry
    }

    /** Called after a job finished, when rescheduling the same job id is safe again. */
    fun afterJob(jobId: Int) {
        if (jobId == JOB_OUTBOX) {
            synchronized(lock) { scheduledOutboxAt = null }
            requestOutboxSend()
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Whether a network with Internet access is available (true if unknown). */
    private fun isOnline(): Boolean =
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            when {
                manager == null -> true
                network == null -> false
                else -> manager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            }
        } catch (_: Exception) {
            true
        }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Background sync step failed: ${e.message}")
        }
    }

    private inline fun <T> safely(default: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Database access failed", e)
            default
        }

    companion object {
        const val JOB_PERIODIC = 1001
        const val JOB_OUTBOX = 1002
        const val JOB_PENDING_OPS = 1003

        private const val TAG = "SyncController"
        private const val OUTBOX_KEY = "outbox"
        private const val MIN_INTERVAL_MINUTES = 15
        private const val POLL_INTERVAL = 2 * 60_000L
        private const val RECENT_SYNC = 30_000L
        private const val RECENT_POLL = POLL_INTERVAL / 2
        private const val REPLAN_DELAY = 2_000L
        private const val BACKOFF = 30_000L
    }
}
