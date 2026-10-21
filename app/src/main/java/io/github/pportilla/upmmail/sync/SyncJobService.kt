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
 * Runs the JobScheduler jobs of [AndroidSyncController] in the application scope and reports
 * back with jobFinished. A stopped job is cancelled and asks to be rescheduled; its coroutine
 * may still be finishing (a send is never interrupted) when the same job id starts again.
 */
class SyncJobService : JobService() {

    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (applicationContext as? UpmMailApp)?.container as? DefaultAppContainer ?: return false
        val controller = container.androidSyncController
        val jobId = params.jobId
        controller.jobStarted(jobId)
        val job = container.applicationScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var reschedule = false
            try {
                reschedule = when (jobId) {
                    AndroidSyncController.JOB_PERIODIC -> {
                        // A chance to bring push back: allowed if the app is exempt from battery
                        // optimisation, otherwise refused (and logged) without affecting the job.
                        container.pushController.startIfWanted()
                        controller.runPeriodicSync()
                    }
                    AndroidSyncController.JOB_OUTBOX -> controller.runOutboxJob()
                    AndroidSyncController.JOB_PENDING_OPS -> controller.runPendingOpsJob()
                    else -> false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Job $jobId failed", e)
                reschedule = e.toMailException().isTransient
            } finally {
                // Not this execution's any more when onStopJob came first: then jobFinished
                // must not be called (and a newer execution of the same id is left alone).
                if (running.remove(jobId, coroutineContext.job)) {
                    jobFinished(params, reschedule)
                    controller.afterJob(jobId)
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
        container?.androidSyncController?.jobStopped(params.jobId)
        return true
    }

    private companion object {
        const val TAG = "SyncJobService"
    }
}
