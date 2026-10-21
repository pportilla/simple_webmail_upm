package io.github.pportilla.upmmail.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.DefaultAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the JobScheduler jobs of [IndexScheduler] in the application scope and reports back with
 * jobFinished. A stopped job is cancelled, which closes the crawler's connection at once, and
 * asks to be rescheduled; its coroutine may still be finishing when the same job id starts again
 * (the indexer then waits for it).
 */
class IndexJobService : JobService() {

    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (applicationContext as? UpmMailApp)?.container as? DefaultAppContainer ?: return false
        val scheduler = container.indexScheduler
        val jobId = params.jobId
        scheduler.jobStarted(jobId)
        val job = container.applicationScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                when (jobId) {
                    IndexScheduler.JOB_INDEX_BULK -> scheduler.runBulk(
                        charging = params.extras.getBoolean(IndexScheduler.EXTRA_CHARGING, false),
                        idleRuns = params.extras.getInt(IndexScheduler.EXTRA_IDLE_RUNS, 0),
                    )
                    IndexScheduler.JOB_INDEX_LIGHT -> scheduler.runLight()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Job $jobId failed", e)
            } finally {
                // Not this execution's any more when onStopJob came first: then jobFinished
                // must not be called (and a newer execution of the same id is left alone).
                if (running.remove(jobId, coroutineContext.job)) {
                    // Never retried with backoff: afterJob plans the next bulk job, and the
                    // light job is periodic.
                    jobFinished(params, false)
                    scheduler.afterJob(jobId)
                }
            }
        }
        running[jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        val container = (applicationContext as? UpmMailApp)?.container as? DefaultAppContainer
        container?.indexScheduler?.jobStopped(params.jobId)
        return true
    }

    private companion object {
        const val TAG = "IndexJobService"
    }
}
