package io.github.pportilla.upmmail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.data.repository.SyncController
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailCheckIntervals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * [SyncController] on JobScheduler:
 *
 * - 15-minute background checks ([JOB_PERIODIC]), also the safety net for instant push,
 * - best-effort 5/10-minute inexact alarms, each coalesced into one network-constrained
 *   [JOB_ALARM_CHECK]; Android may batch or delay these, particularly in Doze,
 * - outbox job ([JOB_OUTBOX]): sends queued messages once due and online, even if the app died,
 * - pending-operations job ([JOB_PENDING_OPS]): pushes local changes that failed for lack of
 *   network once it is back,
 * - in-app timers that send due outbox messages immediately, and visible INBOX polling at the
 *   chosen interval (two minutes for push, no repeating timer for manual mode).
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
    private val planLock = Any()
    private val checkLock = Any()
    private val checkPlanner = MailCheckPlanner(AndroidMailCheckScheduler())
    private var pollJob: Job? = null
    private var scheduledOutboxAt: Long? = null

    /**
     * True while JobScheduler runs [JOB_OUTBOX] (between [jobStarted] and its end). Scheduling or
     * cancelling that job id would stop it, possibly in the middle of a send.
     */
    private var outboxJobRunning = false

    /** True between onAppForeground and onAppBackground. */
    @Volatile
    var isForeground: Boolean = false
        private set

    /** Follows preferences, accounts and eligibility to keep both kinds of checks up to date. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            combine(
                settings.settings.map { it.pushEnabled to it.syncIntervalMinutes }.distinctUntilChanged(),
                store.accounts.observeAll().map { it.isNotEmpty() }.distinctUntilChanged(),
                store.safety.state.map { it.mailboxAccessAllowed }.distinctUntilChanged(),
            ) { _, _, _ -> Unit }.collect { reconcileChecks() }
        }
    }

    override fun schedulePeriodicSync() {
        scope.launch(Dispatchers.IO) { reconcileChecks() }
    }

    /** Recreate alarms after process death, boot or package replacement; never touch outbox jobs. */
    internal fun reconcileChecks() {
        try {
            synchronized(checkLock) {
                val plan = MailCheckPlan.of(
                    settings.settings.value,
                    hasAccounts = safely(false) { store.accounts.count() > 0 },
                    authorized = store.safety.state.value.mailboxAccessAllowed,
                )
                checkPlanner.update(plan)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule mail checks", e)
        }
    }

    /** An inexact alarm only queues work; no network access or foreground service in the receiver. */
    internal fun onPollingAlarm() {
        reconcileChecks()
        if (runCatching { store.safety.requireNetwork() }.isFailure) return
        try {
            synchronized(checkLock) { checkPlanner.onAlarm() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not queue the mail check", e)
        }
    }

    private inner class AndroidMailCheckScheduler : MailCheckScheduler {
        override fun periodicMinutes(): Int? = jobScheduler.getPendingJob(JOB_PERIODIC)
            ?.takeIf { it.isPeriodic }?.intervalMillis?.let { TimeUnit.MILLISECONDS.toMinutes(it).toInt() }

        override fun schedulePeriodic(minutes: Int) {
            val job = JobInfo.Builder(JOB_PERIODIC, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(minutes.toLong()))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            jobScheduler.schedule(job)
        }

        override fun cancelPeriodic() { jobScheduler.cancel(JOB_PERIODIC) }

        override fun scheduleAlarm(minutes: Int) {
            val interval = TimeUnit.MINUTES.toMillis(minutes.toLong())
            checkNotNull(context.getSystemService(AlarmManager::class.java)).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + interval,
                interval,
                pollingAlarmIntent(),
            )
        }

        override fun cancelAlarm() {
            context.getSystemService(AlarmManager::class.java)?.cancel(pollingAlarmIntent())
        }

        override fun hasPendingAlarmJob(): Boolean = jobScheduler.getPendingJob(JOB_ALARM_CHECK) != null

        override fun scheduleAlarmJob() {
            jobScheduler.schedule(
                JobInfo.Builder(JOB_ALARM_CHECK, ComponentName(context, SyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build(),
            )
        }

        override fun cancelAlarmJob() { jobScheduler.cancel(JOB_ALARM_CHECK) }
    }

    private fun pollingAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, MailCheckAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ------------------------------------------------------------------ foreground

    override fun onAppForeground() {
        isForeground = true
        schedulePeriodicSync()
        refreshIfForeground()
    }

    /** May be called from IO after a platform check; never revives a backgrounded activity. */
    fun refreshIfForeground() {
        synchronized(lock) {
            if (!isForeground) return
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
            val threshold = if (account.id == selected?.id) RECENT_SYNC
                else MailCheckIntervals.foregroundDelayMillis(settings.settings.value) ?: RECENT_SYNC
            if (age > threshold) quietly { engine.syncFolder(engine.inboxOf(account.id).id) }
        }
        settings.settings.map(MailCheckIntervals::foregroundDelayMillis).distinctUntilChanged().collectLatest { interval ->
            if (interval == null) return@collectLatest
            while (currentCoroutineContext().isActive) {
                delay(interval)
                val current = selectedAccount(safely(emptyList()) { store.accounts.getAll() }) ?: continue
                if (!current.isDemo && !isOnline()) continue
                // Push (or a pull-to-refresh) may have synced it moments ago.
                val inbox = safely(null) { store.folders.findByRole(current.id, FolderRole.INBOX) }
                val age = inbox?.lastSyncedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
                if (age < RECENT_SYNC) continue
                quietly { engine.syncFolder(engine.inboxOf(current.id).id) }
            }
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

    /**
     * Plans the in-app timer and the outbox job for the earliest queued message. Serialised, so
     * a plan made from an older view of the queue cannot undo a newer one.
     */
    private fun planOutbox(minDelay: Long) {
        synchronized(planLock) {
            val next = if (store.safety.state.value.awaitingTermsAcknowledgement) null
                else safely(null) { store.outbox.nextSendAt() }
            if (next == null) {
                outboxRunner.cancel(OUTBOX_KEY)
                synchronized(lock) {
                    // A running job finishes on its own (and re-plans). While this process
                    // sends, the job stays as a safety net: once due it keeps the process
                    // alive, and the sender re-plans when it is done.
                    if (!outboxJobRunning && !engine.isSending) {
                        scheduledOutboxAt = null
                        runCatching { jobScheduler.cancel(JOB_OUTBOX) }
                    }
                }
                return
            }
            val at = maxOf(next, System.currentTimeMillis() + minDelay)
            scheduleOutboxJob(at)
            outboxRunner.schedule(OUTBOX_KEY, at) {
                // Offline, the network-constrained job sends instead: trying now would only use
                // up the message's retry attempts.
                if (isOnline()) {
                    quietly { engine.sendOutbox(null) }
                    // A short pause before re-planning, so a message that keeps failing cannot spin.
                    planOutbox(minDelay = REPLAN_DELAY)
                } else {
                    // Sample sends are wholly local; do not make them depend on connectivity
                    // or spend real accounts' retry attempts while the device is offline.
                    val demoAccounts = safely(emptyList()) { store.accounts.getAll().filter { it.isDemo } }
                    val hasSampleQueued = demoAccounts.any { account ->
                        safely(false) { store.outbox.list(account.id).any { it.state == io.github.pportilla.upmmail.model.OutboxState.QUEUED } }
                    }
                    if (hasSampleQueued) {
                        demoAccounts.forEach { quietly { engine.sendOutbox(it.id) } }
                        planOutbox(minDelay = REPLAN_DELAY)
                    }
                }
            }
        }
    }

    /** Safety net for when the process dies before the in-app timer fires. */
    private fun scheduleOutboxJob(at: Long) {
        synchronized(lock) {
            // Replacing a running job stops it; it re-plans when it ends (see afterJob).
            if (outboxJobRunning || scheduledOutboxAt == at) return
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
        reconcileChecks()
        if (!synchronized(checkLock) { checkPlanner.wanted(MailCheckJob.PERIODIC) }) return false
        engine.failStaleSending()
        quietly { engine.sendOutbox(null) }
        for (account in safely(emptyList()) { store.accounts.getAll() }) {
            quietly { engine.syncFolder(engine.inboxOf(account.id).id) }
        }
        return false
    }

    /** A short-interval check only reads mail. Outbox delivery has its own durable schedule. */
    suspend fun runAlarmCheck(): Boolean {
        reconcileChecks()
        if (!synchronized(checkLock) { checkPlanner.wanted(MailCheckJob.ALARM) }) return false
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
                if (result.blockedBy?.isTransient == true) retry = true
                result.nextDueAt?.let { flushScheduler().schedule(accountId, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.toMailException().isTransient) retry = true
            }
        }
        return retry
    }

    /** Called by [SyncJobService] when JobScheduler starts a job. */
    fun jobStarted(jobId: Int) {
        if (jobId == JOB_OUTBOX) synchronized(lock) { outboxJobRunning = true }
        checkJob(jobId)?.let { synchronized(checkLock) { checkPlanner.started(it) } }
    }

    /** Called when JobScheduler stopped a job (it reschedules it itself). */
    fun jobStopped(jobId: Int) {
        finishCheck(jobId)
        if (jobId == JOB_OUTBOX) {
            synchronized(lock) {
                outboxJobRunning = false
                scheduledOutboxAt = null
            }
        }
    }

    /** Called after a job finished, when rescheduling the same job id is safe again. */
    fun afterJob(jobId: Int) {
        finishCheck(jobId)
        if (jobId == JOB_OUTBOX) {
            synchronized(lock) {
                outboxJobRunning = false
                scheduledOutboxAt = null
            }
            requestOutboxSend()
        }
    }

    /** Do not resurrect an old checking mode after Android stopped its execution. */
    fun rescheduleStoppedJob(jobId: Int): Boolean = checkJob(jobId)?.let {
        synchronized(checkLock) { checkPlanner.wanted(it) }
    } ?: true

    private fun finishCheck(jobId: Int) {
        checkJob(jobId)?.let { kind ->
            try { synchronized(checkLock) { checkPlanner.finished(kind) } }
            catch (e: Exception) { Log.w(TAG, "Could not update completed mail check", e) }
        }
    }

    private fun checkJob(jobId: Int): MailCheckJob? = when (jobId) {
        JOB_PERIODIC -> MailCheckJob.PERIODIC
        JOB_ALARM_CHECK -> MailCheckJob.ALARM
        else -> null
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
        const val JOB_ALARM_CHECK = 1004

        private const val TAG = "SyncController"
        private const val OUTBOX_KEY = "outbox"
        private const val RECENT_SYNC = 30_000L
        private const val REPLAN_DELAY = 2_000L
        private const val BACKOFF = 30_000L
    }
}
