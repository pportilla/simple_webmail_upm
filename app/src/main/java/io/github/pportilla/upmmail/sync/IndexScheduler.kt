package io.github.pportilla.upmmail.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.Tables
import io.github.pportilla.upmmail.model.IndexStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the header index (see [HeaderIndexer]) up to date in the background with two
 * JobScheduler jobs, both run by [IndexJobService]:
 *
 * - bulk job ([JOB_INDEX_BULK]): a few minutes of crawling ([IndexBudget.bulk]) on a connection
 *   of its own, only on an unmetered network with battery and storage not low. While the index
 *   is being built it runs at once and then every 15 minutes (less often after runs that got
 *   nothing done, see [IndexJobPlan]); once it is complete, every 6 hours while charging, to
 *   index new mail and compare the folders with the server.
 * - light job ([JOB_INDEX_LIGHT]): every 2 hours on any network, the new mail of every folder and
 *   comparisons of small folders only ([IndexBudget.light]), on the account's main connection.
 *
 * It also runs the indexer's check of the folders our own moves and uploads added mail to
 * ([HeaderIndexer.watchDirty]), makes index runs stop when the device is short of storage and
 * tells them when it is on a metered network (see [IndexBudget.allowMetered]).
 * Both jobs are cancelled while there is no account. "Index now" in the search screen runs
 * the indexer directly, without a job.
 */
class IndexScheduler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: MailStore,
    private val indexer: HeaderIndexer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val jobScheduler: JobScheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
    private val started = AtomicBoolean(false)
    private val lock = Any()

    /** True while JobScheduler runs [JOB_INDEX_BULK]: scheduling that id would stop the run. */
    private var bulkRunning = false

    /** The folders to index changed while the bulk job ran: [afterJob] then plans from the index status alone. */
    private var replanAfterBulk = false

    /** What the bulk run of the current job left to do, for [afterJob]. */
    private var lastBulk: BulkOutcome? = null

    private class BulkOutcome(val moreWork: Boolean, val idleRuns: Int)

    init {
        indexer.storageLow = ::isStorageLow
        indexer.meteredNetwork = ::isMetered
    }

    /**
     * Follows the accounts and their indexable folders to keep both jobs scheduled (or cancelled
     * without accounts) and starts the check of dirty folders. Only the first call does anything.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            var initial = true
            store.observe(Tables.ACCOUNTS, Tables.FOLDERS) { indexableFolders() }
                .distinctUntilChanged()
                .catch { e -> Log.w(TAG, "Could not read the folders", e) }
                .collect { folders ->
                    onFoldersChanged(hasAccounts = folders.isNotEmpty(), initial = initial)
                    initial = false
                }
        }
        scope.launch(Dispatchers.IO) { indexer.watchDirty(::isOnline) }
    }

    /**
     * The ids of the indexable folders of every account, by account. A new account first has no
     * folders: its folder list arriving moments later is what makes work for the index.
     */
    private fun indexableFolders(): Map<Long, Set<Long>> =
        store.accounts.getAll().associate { account ->
            account.id to store.folders.indexableFolders(account.id).mapTo(HashSet()) { it.id }
        }

    private fun onFoldersChanged(hasAccounts: Boolean, initial: Boolean) {
        if (!hasAccounts) {
            cancelJobs()
            return
        }
        scheduleLight()
        // A bulk job planned before this process started stays as it is: the periodic mail check
        // starts the process every 15 minutes, and planning again each time would postpone the
        // 6-hour job for ever. Accounts or folders added or removed since are a reason to plan again.
        if (initial && pendingJob(JOB_INDEX_BULK) != null) return
        planBulk(null)
    }

    // ------------------------------------------------------------------ job bodies

    /**
     * Body of the bulk job: one [IndexBudget.bulk] run. [charging] and [idleRuns] come from the
     * job's extras (see [IndexJobPlan.BulkSpec]). The next bulk job is planned by [afterJob].
     */
    suspend fun runBulk(charging: Boolean, idleRuns: Int) {
        val result = try {
            indexer.run(IndexBudget.bulk(clock(), charging))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IndexRunResult(0, 0, moreWork = true, error = e.toMailException())
        }
        result.error?.let { Log.i(TAG, "Index run stopped early (${it.kind})") }
        synchronized(lock) { lastBulk = BulkOutcome(result.moreWork, IndexJobPlan.idleRunsAfter(result, idleRuns)) }
    }

    /** Body of the light job: one [IndexBudget.light] run; skipped while another run works. */
    suspend fun runLight() {
        val result = indexer.run(IndexBudget.light(clock()))
        result.error?.let { Log.i(TAG, "Light index run stopped early (${it.kind})") }
    }

    /** Called by [IndexJobService] when JobScheduler starts a job. */
    fun jobStarted(jobId: Int) {
        if (jobId != JOB_INDEX_BULK) return
        synchronized(lock) {
            bulkRunning = true
            lastBulk = null
        }
    }

    /** Called when JobScheduler stopped a job: it reschedules the job itself. */
    fun jobStopped(jobId: Int) {
        if (jobId != JOB_INDEX_BULK) return
        synchronized(lock) {
            bulkRunning = false
            replanAfterBulk = false
            lastBulk = null
        }
    }

    /**
     * Called after a job finished, when scheduling the same job id is safe again: plans the next
     * bulk job from what the run left to do.
     */
    fun afterJob(jobId: Int) {
        if (jobId != JOB_INDEX_BULK) return
        val outcome = synchronized(lock) {
            bulkRunning = false
            // A run that left no outcome behind failed in an unexpected way: wait as after an idle run.
            val outcome = if (replanAfterBulk) null else lastBulk ?: BulkOutcome(moreWork = true, idleRuns = 1)
            replanAfterBulk = false
            lastBulk = null
            outcome
        }
        scope.launch(Dispatchers.IO) { planBulk(outcome) }
    }

    // ------------------------------------------------------------------ scheduling

    /** Schedules the bulk job for the index status and [outcome] (null: no run to go by). */
    private fun planBulk(outcome: BulkOutcome?) {
        val status = try {
            store.headerIndex.status()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the index status", e)
            return
        }
        val spec = IndexJobPlan.bulk(status, outcome?.moreWork, outcome?.idleRuns ?: 0)
        synchronized(lock) {
            if (bulkRunning) {
                replanAfterBulk = true
                return
            }
            try {
                if (spec == null) jobScheduler.cancel(JOB_INDEX_BULK) else jobScheduler.schedule(bulkJob(spec))
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule the index job", e)
            }
        }
    }

    private fun bulkJob(spec: IndexJobPlan.BulkSpec): JobInfo {
        val extras = PersistableBundle().apply {
            putBoolean(EXTRA_CHARGING, spec.requiresCharging)
            putInt(EXTRA_IDLE_RUNS, spec.idleRuns)
        }
        val builder = JobInfo.Builder(JOB_INDEX_BULK, ComponentName(context, IndexJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(spec.requiresCharging)
            .setMinimumLatency(spec.latencyMillis)
            .setPersisted(true)
            .setExtras(extras)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && spec.estimatedBytes > 0) {
            builder.setEstimatedNetworkBytes(spec.estimatedBytes, 0)
        }
        return builder.build()
    }

    private fun scheduleLight() {
        try {
            val existing = jobScheduler.getPendingJob(JOB_INDEX_LIGHT)
            if (existing != null && existing.isPeriodic && existing.intervalMillis == LIGHT_INTERVAL &&
                existing.flexMillis == LIGHT_FLEX
            ) {
                return
            }
            val job = JobInfo.Builder(JOB_INDEX_LIGHT, ComponentName(context, IndexJobService::class.java))
                .setPeriodic(LIGHT_INTERVAL, LIGHT_FLEX)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            jobScheduler.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the light index job", e)
        }
    }

    /** No account left: nothing to index (a running bulk job is stopped too). */
    private fun cancelJobs() {
        synchronized(lock) {
            try {
                jobScheduler.cancel(JOB_INDEX_BULK)
                jobScheduler.cancel(JOB_INDEX_LIGHT)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel the index jobs", e)
            }
        }
    }

    private fun pendingJob(jobId: Int): JobInfo? =
        try {
            jobScheduler.getPendingJob(jobId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the scheduled index job", e)
            null
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

    /** Whether the device uses a network the user pays by use, such as mobile data (false if unknown). */
    private fun isMetered(): Boolean =
        try {
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
        } catch (_: Exception) {
            false
        }

    /** Less than [LOW_STORAGE_BYTES] free where the database lives (false if unknown). */
    private fun isStorageLow(): Boolean =
        try {
            context.filesDir.usableSpace < LOW_STORAGE_BYTES
        } catch (_: Exception) {
            false
        }

    companion object {
        const val JOB_INDEX_BULK = 1101
        const val JOB_INDEX_LIGHT = 1102

        /** Bulk job extra: the job waits for charging (and may run for longer). */
        internal const val EXTRA_CHARGING = "charging"

        /** Bulk job extra: see [IndexJobPlan.BulkSpec.idleRuns]. */
        internal const val EXTRA_IDLE_RUNS = "idle_runs"

        private const val TAG = "IndexScheduler"
        private val LIGHT_INTERVAL = TimeUnit.HOURS.toMillis(2)
        private val LIGHT_FLEX = TimeUnit.MINUTES.toMillis(30)

        /** Index runs stop below this much free storage, well before the system's own warning. */
        private const val LOW_STORAGE_BYTES = 300L * 1024 * 1024
    }
}

/** When the next bulk index job runs (pure, so it is unit-tested). */
internal object IndexJobPlan {
    /** Pause between bulk runs while the index is being built. */
    const val CONTINUATION_DELAY = 15 * 60_000L

    /** Pause between bulk runs once the index is complete; also the longest backoff. */
    const val MAINTENANCE_DELAY = 6 * 60 * 60_000L

    /** Download per message not indexed yet: envelope, flags, size and Content-Type. */
    const val BYTES_PER_MESSAGE = 900L

    /** The pause after runs that got nothing done doubles at most this many times. */
    private const val MAX_DOUBLINGS = 5

    /**
     * One bulk job: it starts [latencyMillis] after being scheduled, waits for charging if
     * [requiresCharging] (and then gets the longer budget of [IndexBudget.bulk]) and should
     * download about [estimatedBytes]. [idleRuns] counts the runs in a row that left work but
     * got nothing done (see [idleRunsAfter]); it travels in the job's extras, so the backoff
     * survives the process.
     */
    data class BulkSpec(
        val latencyMillis: Long,
        val requiresCharging: Boolean,
        val estimatedBytes: Long,
        val idleRuns: Int = 0,
    )

    /**
     * The next bulk job, or null without accounts. [lastMoreWork] is what the last bulk run left
     * ([IndexRunResult.moreWork]); null when there is no run to go by (process start, accounts
     * or folders changed), and then the index status decides.
     *
     * - Work left: no charging needed; at once without a run to go by, otherwise after
     *   [CONTINUATION_DELAY], doubled for each idle run up to [MAINTENANCE_DELAY].
     * - Nothing left (the last run said so, or every account's index is complete): while
     *   charging, after [MAINTENANCE_DELAY]. A run's word beats the status: an account holding
     *   [io.github.pportilla.upmmail.data.db.HeaderIndexDao.ACCOUNT_CAP] messages is never
     *   complete, yet no run can do more for it.
     */
    fun bulk(status: List<IndexStatus>, lastMoreWork: Boolean?, idleRuns: Int = 0): BulkSpec? {
        if (status.isEmpty()) return null
        val bytes = status.sumOf { (it.total - it.indexed).coerceAtLeast(0).toLong() } * BYTES_PER_MESSAGE
        val workLeft = lastMoreWork ?: status.any { !it.complete }
        if (!workLeft) return BulkSpec(MAINTENANCE_DELAY, requiresCharging = true, estimatedBytes = bytes)
        if (lastMoreWork == null) return BulkSpec(0, requiresCharging = false, estimatedBytes = bytes)
        val idle = idleRuns.coerceAtLeast(0)
        val latency = minOf(CONTINUATION_DELAY shl minOf(idle, MAX_DOUBLINGS), MAINTENANCE_DELAY)
        return BulkSpec(latency, requiresCharging = false, estimatedBytes = bytes, idleRuns = idle)
    }

    /**
     * [idleRuns] after [result]: one more when the run left work without fetching or removing
     * anything (offline behind the Wi-Fi, a login refused), otherwise back to 0. A folder the
     * server refuses leaves no work behind (see [HeaderIndexer.run]), so it does not keep the
     * job coming back.
     */
    fun idleRunsAfter(result: IndexRunResult, idleRuns: Int): Int =
        if (result.moreWork && result.fetched == 0 && result.removed == 0) idleRuns + 1 else 0
}
