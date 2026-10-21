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
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the JobScheduler jobs of [AndroidSyncController] in the application scope and reports
 * back with jobFinished. A stopped job is cancelled and asks to be rescheduled.
 */
class SyncJobService : JobService() {

    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (applicationContext as? UpmMailApp)?.container as? DefaultAppContainer ?: return false
        val controller = container.androidSyncController
        val jobId = params.jobId
        val job = container.applicationScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var reschedule = false
            try {
                reschedule = when (jobId) {
                    AndroidSyncController.JOB_PERIODIC -> controller.runPeriodicSync()
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
                // Absent when onStopJob came first: then jobFinished must not be called.
                if (running.remove(jobId) != null) {
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
        return true
    }

    private companion object {
        const val TAG = "SyncJobService"
    }
}
